package xyz.activityplus.android.core

import java.io.InputStream
import java.security.MessageDigest

/**
 * The "Check media" logic on plain data read from MediaStore: totals, folders people care about,
 * old and large files, and duplicate groups. Pure, so it is unit-tested; the reading lives in data/MediaLoader.
 */
object MediaCheck {
    enum class Kind { IMAGE, VIDEO, AUDIO }

    /** Times are in milliseconds; 0 means MediaStore does not know. [path] is the relative path, e.g. "DCIM/Camera/". */
    data class Item(
        val id: Long,
        val kind: Kind,
        val name: String,
        val size: Long,
        val mime: String,
        val takenMillis: Long,
        val addedMillis: Long,
        val modifiedMillis: Long,
        val path: String,
        val durationMillis: Long = 0,
    ) {
        /** Ids are only safe per collection, so selections and duplicate lists use this. */
        val key: String get() = "${kind.ordinal}/$id"

        /** When the file was made, as far as anything tells: taken, else added, else modified. */
        val bornMillis: Long get() = listOf(takenMillis, addedMillis, modifiedMillis).firstOrNull { it > 0 } ?: 0
    }

    // Permissions (names as constants so the logic stays free of Android types).

    const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val LEGACY = "android.permission.READ_EXTERNAL_STORAGE"

    /** What the app can read. [partial] is Android 14's "selected photos and videos only". */
    data class Access(val images: Boolean, val videos: Boolean, val audio: Boolean, val partial: Boolean) {
        val any: Boolean get() = images || videos || audio
        val all: Boolean get() = images && videos && audio && !partial
    }

    /** Permissions to ask for on this Android version; never the all-files permission. */
    fun permissionsFor(sdk: Int): List<String> = when {
        sdk >= 34 -> listOf(IMAGES, VIDEO, AUDIO, SELECTED)
        sdk >= 33 -> listOf(IMAGES, VIDEO, AUDIO)
        else -> listOf(LEGACY)
    }

    fun accessOf(sdk: Int, granted: Set<String>): Access {
        if (sdk < 33) {
            val ok = LEGACY in granted
            return Access(ok, ok, ok, false)
        }
        val images = IMAGES in granted
        val videos = VIDEO in granted
        // Only a selection was allowed: photos and videos can be read, but just those.
        val partial = sdk >= 34 && SELECTED in granted && !images && !videos
        return Access(images || partial, videos || partial, AUDIO in granted, partial)
    }

    // Overview

    data class Totals(val imageBytes: Long, val videoBytes: Long, val audioBytes: Long) {
        val total: Long get() = imageBytes + videoBytes + audioBytes
    }

    fun totals(items: List<Item>): Totals {
        fun sum(k: Kind) = items.filter { it.kind == k }.sumOf { it.size }
        return Totals(sum(Kind.IMAGE), sum(Kind.VIDEO), sum(Kind.AUDIO))
    }

    fun largest(items: List<Item>, kind: Kind, n: Int = 20): List<Item> =
        items.filter { it.kind == kind }.sortedByDescending { it.size }.take(n)

    // Folders

    enum class Folder { SCREENSHOTS, SCREEN_RECORDINGS, WHATSAPP, TELEGRAM, SIGNAL, CAMERA, DOWNLOADS }

    /** The folder people care about that a relative path belongs to, or null. Messengers win over generic folders. */
    fun folderOf(path: String): Folder? {
        val p = path.lowercase()
        return when {
            "whatsapp" in p -> Folder.WHATSAPP
            "telegram" in p -> Folder.TELEGRAM
            "signal" in p -> Folder.SIGNAL
            "screenshot" in p -> Folder.SCREENSHOTS
            "screenrecord" in p || "screen record" in p || "screen_record" in p -> Folder.SCREEN_RECORDINGS
            p.startsWith("camera") || "/camera" in p -> Folder.CAMERA
            p.startsWith("download") -> Folder.DOWNLOADS
            else -> null
        }
    }

    data class FolderGroup(val folder: Folder, val count: Int, val bytes: Long)

    /** Only folders that hold something, biggest first. */
    fun folderGroups(items: List<Item>): List<FolderGroup> =
        items.groupBy { folderOf(it.path) }
            .mapNotNull { (folder, list) -> folder?.let { FolderGroup(it, list.size, list.sumOf { i -> i.size }) } }
            .sortedByDescending { it.bytes }

    // Old and large

    const val OLD_MIN_BYTES = 50L * 1000 * 1000
    const val OLD_MIN_AGE_MILLIS = 365L * 86_400_000

    /** Files of at least [minBytes] last modified at least [minAgeMillis] ago, biggest first. */
    fun oldAndLarge(
        items: List<Item>,
        nowMillis: Long,
        minBytes: Long = OLD_MIN_BYTES,
        minAgeMillis: Long = OLD_MIN_AGE_MILLIS,
    ): List<Item> = items
        .filter { it.size >= minBytes && it.modifiedMillis > 0 && nowMillis - it.modifiedMillis >= minAgeMillis }
        .sortedByDescending { it.size }

    // Duplicates

    const val DUPLICATE_CAP = 2000
    const val DUPLICATE_MIN_BYTES = 10_000L

    data class Candidates(val items: List<Item>, val truncated: Boolean)

    /**
     * Files that share size and mime type with another file. Whole groups, the ones that would free
     * the most first, until [cap] files are reached; anything left out is reported as truncated.
     */
    fun duplicateCandidates(items: List<Item>, cap: Int = DUPLICATE_CAP): Candidates {
        val groups = items
            .filter { it.size >= DUPLICATE_MIN_BYTES }
            .groupBy { it.size to it.mime }
            .values.filter { it.size >= 2 }
            .sortedByDescending { it.first().size * (it.size - 1) }
        val picked = ArrayList<Item>()
        var truncated = false
        for (g in groups) {
            if (picked.size + g.size > cap) {
                truncated = true
                continue
            }
            picked += g
        }
        return Candidates(picked, truncated)
    }

    /** A candidate with the hash of its first and last 64 KB plus its size. */
    data class Hashed(val item: Item, val hash: String)

    data class DuplicateGroup(val items: List<Item>, val keep: Item) {
        val size: Long get() = keep.size
        val freeable: Long get() = size * (items.size - 1)
        val others: List<Item> get() = items.filter { it.key != keep.key }
    }

    /** Same size, mime type and partial hash: groups of two or more, the ones that free the most first. */
    fun duplicateGroups(hashed: List<Hashed>): List<DuplicateGroup> =
        hashed.groupBy { Triple(it.item.size, it.item.mime, it.hash) }
            .values.filter { it.size >= 2 }
            .map { g ->
                val sorted = g.map { it.item }.sortedWith(keepOrder)
                DuplicateGroup(sorted, sorted.first())
            }
            .sortedByDescending { it.freeable }

    /** The one to keep comes first: the one in Camera, else the oldest, else the lowest id. */
    private val keepOrder = compareBy<Item>(
        { folderOf(it.path) != Folder.CAMERA },
        { if (it.bornMillis > 0) it.bornMillis else Long.MAX_VALUE },
        { it.id },
    )

    // Partial hash

    const val HASH_CHUNK = 64 * 1024

    /**
     * SHA-256 over the first and last 64 KB of [input] plus [size]; the whole file when it is smaller than two chunks.
     * Null when the stream is shorter than [size] (the file changed while reading).
     */
    fun partialHash(input: InputStream, size: Long): String? {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        val buf = ByteArray(HASH_CHUNK)
        if (size <= 2L * HASH_CHUNK) {
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return null
                md.update(buf, 0, n)
                left -= n
            }
        } else {
            if (!readFully(input, buf, md)) return null
            if (!skipFully(input, size - 2L * HASH_CHUNK)) return null
            if (!readFully(input, buf, md)) return null
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readFully(input: InputStream, buf: ByteArray, md: MessageDigest): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        md.update(buf, 0, off)
        return true
    }

    /** skip() may return 0 without being at the end; one read tells the difference. */
    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) {
                left -= n
            } else {
                if (input.read() < 0) return false
                left--
            }
        }
        return true
    }
}

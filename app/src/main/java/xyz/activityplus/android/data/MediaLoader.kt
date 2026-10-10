package xyz.activityplus.android.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.activityplus.android.core.MediaCheck
import xyz.activityplus.android.core.MediaCheck.Hashed
import xyz.activityplus.android.core.MediaCheck.Item
import xyz.activityplus.android.core.MediaCheck.Kind

/**
 * Reads the media list from MediaStore (never by walking paths) and, on request, the first and last
 * 64 KB of duplicate candidates. Everything stays on the phone.
 */
object MediaLoader {
    private val columns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DURATION,
    )

    private fun collection(kind: Kind): Uri = when (kind) {
        Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    fun uri(item: Item): Uri = ContentUris.withAppendedId(collection(item.kind), item.id)

    /** Only the kinds [access] allows; a kind that fails to read is left out, not fatal. */
    fun load(context: Context, access: MediaCheck.Access): List<Item> {
        val out = ArrayList<Item>()
        if (access.images) out += read(context, Kind.IMAGE)
        if (access.videos) out += read(context, Kind.VIDEO)
        if (access.audio) out += read(context, Kind.AUDIO)
        return out
    }

    private fun read(context: Context, kind: Kind): List<Item> {
        val out = ArrayList<Item>()
        try {
            context.contentResolver.query(collection(kind), columns, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext()) {
                    val bytes = c.getLong(size)
                    if (bytes <= 0) continue
                    out += Item(
                        id = c.getLong(id),
                        kind = kind,
                        name = c.getString(name) ?: "",
                        size = bytes,
                        mime = c.getString(mime) ?: "",
                        takenMillis = c.getLong(taken),
                        // DATE_ADDED and DATE_MODIFIED are in seconds, DATE_TAKEN in milliseconds.
                        addedMillis = c.getLong(added) * 1000,
                        modifiedMillis = c.getLong(modified) * 1000,
                        path = c.getString(path) ?: "",
                        durationMillis = c.getLong(duration),
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked while the screen was open: nothing of this kind.
        }
        return out
    }

    /**
     * Hashes the candidates one by one on the calling (background) thread. Cancelling the coroutine
     * stops between files. Files that cannot be read are skipped.
     */
    suspend fun hash(context: Context, candidates: List<Item>, onProgress: (done: Int) -> Unit): List<Hashed> {
        val out = ArrayList<Hashed>()
        candidates.forEachIndexed { i, item ->
            currentCoroutineContext().ensureActive()
            val hash = try {
                context.contentResolver.openInputStream(uri(item))?.use { MediaCheck.partialHash(it, item.size) }
            } catch (_: Exception) {
                null
            }
            if (hash != null) out += Hashed(item, hash)
            onProgress(i + 1)
        }
        return out
    }
}

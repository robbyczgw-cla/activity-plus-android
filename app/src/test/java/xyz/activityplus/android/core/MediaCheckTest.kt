package xyz.activityplus.android.core

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.activityplus.android.core.MediaCheck.Folder
import xyz.activityplus.android.core.MediaCheck.Hashed
import xyz.activityplus.android.core.MediaCheck.Item
import xyz.activityplus.android.core.MediaCheck.Kind

class MediaCheckTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val mb = 1_000_000L

    private fun item(
        id: Long,
        size: Long = mb,
        kind: Kind = Kind.IMAGE,
        mime: String = "image/jpeg",
        path: String = "DCIM/Camera/",
        taken: Long = 0,
        added: Long = 0,
        modified: Long = 0,
    ) = Item(id, kind, "f$id", size, mime, taken, added, modified, path)

    // Permissions

    @Test fun permissionsPerAndroidVersion() {
        assertEquals(listOf(MediaCheck.LEGACY), MediaCheck.permissionsFor(29))
        assertEquals(listOf(MediaCheck.LEGACY), MediaCheck.permissionsFor(32))
        assertEquals(listOf(MediaCheck.IMAGES, MediaCheck.VIDEO, MediaCheck.AUDIO), MediaCheck.permissionsFor(33))
        assertTrue(MediaCheck.SELECTED in MediaCheck.permissionsFor(34))
        assertFalse(MediaCheck.permissionsFor(36).any { "MANAGE_EXTERNAL_STORAGE" in it })
    }

    @Test fun legacyPermissionCoversEverythingBeforeAndroid13() {
        val a = MediaCheck.accessOf(31, setOf(MediaCheck.LEGACY))
        assertTrue(a.all)
        assertFalse(MediaCheck.accessOf(31, emptySet()).any)
    }

    @Test fun fullAccessOnAndroid14() {
        val a = MediaCheck.accessOf(34, setOf(MediaCheck.IMAGES, MediaCheck.VIDEO, MediaCheck.AUDIO, MediaCheck.SELECTED))
        assertTrue(a.all)
        assertFalse(a.partial)
    }

    @Test fun selectedPhotosOnlyIsPartial() {
        val a = MediaCheck.accessOf(34, setOf(MediaCheck.SELECTED))
        assertTrue(a.partial)
        assertTrue(a.images && a.videos)
        assertFalse(a.audio)
        assertFalse(a.all)
    }

    @Test fun selectedPhotosWithAudioIsStillPartial() {
        val a = MediaCheck.accessOf(34, setOf(MediaCheck.SELECTED, MediaCheck.AUDIO))
        assertTrue(a.partial)
        assertTrue(a.audio)
    }

    @Test fun selectedPermissionIsIgnoredBeforeAndroid14() {
        assertFalse(MediaCheck.accessOf(33, setOf(MediaCheck.SELECTED)).any)
    }

    @Test fun noPermissionMeansNoAccess() {
        assertFalse(MediaCheck.accessOf(34, emptySet()).any)
    }

    // Overview

    @Test fun totalsPerKind() {
        val t = MediaCheck.totals(
            listOf(
                item(1, 3 * mb), item(2, 2 * mb),
                item(3, 10 * mb, Kind.VIDEO, "video/mp4"),
                item(4, mb, Kind.AUDIO, "audio/mpeg"),
            )
        )
        assertEquals(5 * mb, t.imageBytes)
        assertEquals(10 * mb, t.videoBytes)
        assertEquals(mb, t.audioBytes)
        assertEquals(16 * mb, t.total)
    }

    @Test fun largestVideosOnlyVideosBiggestFirstCapped() {
        val items = (1L..30L).map { item(it, it * mb, Kind.VIDEO, "video/mp4") } + item(99, 500 * mb)
        val top = MediaCheck.largest(items, Kind.VIDEO, 20)
        assertEquals(20, top.size)
        assertEquals(30L, top.first().id)
        assertEquals(11L, top.last().id)
        assertTrue(top.all { it.kind == Kind.VIDEO })
    }

    // Folders

    @Test fun foldersByRelativePath() {
        assertEquals(Folder.SCREENSHOTS, MediaCheck.folderOf("Pictures/Screenshots/"))
        assertEquals(Folder.SCREENSHOTS, MediaCheck.folderOf("DCIM/Screenshots/"))
        assertEquals(Folder.SCREEN_RECORDINGS, MediaCheck.folderOf("Movies/ScreenRecordings/"))
        assertEquals(Folder.SCREEN_RECORDINGS, MediaCheck.folderOf("DCIM/Screen recordings/"))
        assertEquals(Folder.WHATSAPP, MediaCheck.folderOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals(Folder.WHATSAPP, MediaCheck.folderOf("Pictures/WhatsApp/"))
        assertEquals(Folder.TELEGRAM, MediaCheck.folderOf("Pictures/Telegram/"))
        assertEquals(Folder.SIGNAL, MediaCheck.folderOf("Pictures/Signal/"))
        assertEquals(Folder.CAMERA, MediaCheck.folderOf("DCIM/Camera/"))
        assertEquals(Folder.DOWNLOADS, MediaCheck.folderOf("Download/"))
        assertEquals(Folder.DOWNLOADS, MediaCheck.folderOf("Download/Invoices/"))
    }

    @Test fun otherFoldersAreNotClassified() {
        assertNull(MediaCheck.folderOf("Music/Albums/"))
        assertNull(MediaCheck.folderOf(""))
        assertNull(MediaCheck.folderOf("Pictures/"))
    }

    @Test fun matchingIgnoresCase() {
        assertEquals(Folder.WHATSAPP, MediaCheck.folderOf("pictures/whatsapp images/"))
        assertEquals(Folder.CAMERA, MediaCheck.folderOf("dcim/camera/"))
    }

    @Test fun screenshotsInsideDcimAreNotCamera() {
        assertEquals(Folder.SCREENSHOTS, MediaCheck.folderOf("DCIM/Screenshots/"))
    }

    @Test fun messengerBeatsDownloads() {
        assertEquals(Folder.TELEGRAM, MediaCheck.folderOf("Download/Telegram/"))
    }

    @Test fun folderGroupsCountAndSumBiggestFirst() {
        val groups = MediaCheck.folderGroups(
            listOf(
                item(1, 2 * mb, path = "DCIM/Camera/"),
                item(2, 3 * mb, path = "DCIM/Camera/"),
                item(3, 20 * mb, path = "Pictures/Screenshots/"),
                item(4, 50 * mb, path = "Music/"),
            )
        )
        assertEquals(listOf(Folder.SCREENSHOTS, Folder.CAMERA), groups.map { it.folder })
        assertEquals(2, groups[1].count)
        assertEquals(5 * mb, groups[1].bytes)
    }

    // Old and large

    @Test fun oldAndLargeNeedsBothSizeAndAge() {
        val items = listOf(
            item(1, 60 * mb, modified = now - 400 * day),
            item(2, 60 * mb, modified = now - 100 * day),
            item(3, 10 * mb, modified = now - 400 * day),
            item(4, 80 * mb, modified = now - 500 * day),
            item(5, 70 * mb, modified = 0),
        )
        assertEquals(listOf(4L, 1L), MediaCheck.oldAndLarge(items, now).map { it.id })
    }

    @Test fun oldAndLargeBoundaries() {
        val exactly = item(1, MediaCheck.OLD_MIN_BYTES, modified = now - MediaCheck.OLD_MIN_AGE_MILLIS)
        val justUnder = item(2, MediaCheck.OLD_MIN_BYTES - 1, modified = now - MediaCheck.OLD_MIN_AGE_MILLIS)
        assertEquals(listOf(1L), MediaCheck.oldAndLarge(listOf(exactly, justUnder), now).map { it.id })
    }

    // Duplicate candidates

    @Test fun candidatesShareSizeAndMime() {
        val items = listOf(
            item(1, 5 * mb), item(2, 5 * mb),
            item(3, 5 * mb, mime = "image/png"),
            item(4, 7 * mb),
        )
        val c = MediaCheck.duplicateCandidates(items)
        assertEquals(setOf(1L, 2L), c.items.map { it.id }.toSet())
        assertFalse(c.truncated)
    }

    @Test fun tinyFilesAreNoCandidates() {
        val c = MediaCheck.duplicateCandidates(listOf(item(1, 100), item(2, 100)))
        assertTrue(c.items.isEmpty())
    }

    @Test fun candidateCapKeepsTheBiggestGroups() {
        val items = listOf(
            item(1, 9 * mb), item(2, 9 * mb), item(3, 9 * mb),
            item(4, 2 * mb), item(5, 2 * mb),
            item(6, 1 * mb), item(7, 1 * mb),
        )
        val c = MediaCheck.duplicateCandidates(items, cap = 5)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), c.items.map { it.id }.toSet())
        assertTrue(c.truncated)
    }

    @Test fun candidateCapNeverExceeded() {
        val items = (1L..50L).flatMap { g -> listOf(item(g * 2, g * mb), item(g * 2 + 1, g * mb)) }
        val c = MediaCheck.duplicateCandidates(items, cap = 21)
        assertEquals(20, c.items.size)
        assertTrue(c.truncated)
    }

    // Duplicate groups

    @Test fun groupsNeedTwoWithTheSameHash() {
        val a = item(1, 5 * mb)
        val b = item(2, 5 * mb)
        val c = item(3, 5 * mb)
        val groups = MediaCheck.duplicateGroups(listOf(Hashed(a, "x"), Hashed(b, "x"), Hashed(c, "y")))
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups[0].items.map { it.id }.toSet())
    }

    @Test fun sameHashButDifferentSizeIsNotADuplicate() {
        val groups = MediaCheck.duplicateGroups(listOf(Hashed(item(1, 5 * mb), "x"), Hashed(item(2, 6 * mb), "x")))
        assertTrue(groups.isEmpty())
    }

    @Test fun sameHashButDifferentMimeIsNotADuplicate() {
        val groups = MediaCheck.duplicateGroups(
            listOf(Hashed(item(1, 5 * mb), "x"), Hashed(item(2, 5 * mb, mime = "image/png"), "x"))
        )
        assertTrue(groups.isEmpty())
    }

    @Test fun keepsTheOneInCameraEvenIfNewer() {
        val camera = item(1, taken = now, path = "DCIM/Camera/")
        val old = item(2, taken = now - 100 * day, path = "Download/")
        val g = MediaCheck.duplicateGroups(listOf(Hashed(old, "h"), Hashed(camera, "h"))).single()
        assertEquals(1L, g.keep.id)
        assertEquals(listOf(2L), g.others.map { it.id })
    }

    @Test fun keepsTheOldestWithoutCamera() {
        val a = item(1, taken = now - 10 * day, path = "Download/")
        val b = item(2, taken = now - 50 * day, path = "Pictures/WhatsApp/")
        val c = item(3, taken = now - 20 * day, path = "Pictures/")
        val g = MediaCheck.duplicateGroups(listOf(Hashed(a, "h"), Hashed(b, "h"), Hashed(c, "h"))).single()
        assertEquals(2L, g.keep.id)
    }

    @Test fun oldestFallsBackToAddedAndModified() {
        val a = item(1, added = now - 5 * day, path = "Download/")
        val b = item(2, modified = now - 9 * day, path = "Download/")
        val g = MediaCheck.duplicateGroups(listOf(Hashed(a, "h"), Hashed(b, "h"))).single()
        assertEquals(2L, g.keep.id)
    }

    @Test fun unknownDatesLoseAgainstKnownOnes() {
        val unknown = item(1, path = "Download/")
        val known = item(2, taken = now, path = "Download/")
        val g = MediaCheck.duplicateGroups(listOf(Hashed(unknown, "h"), Hashed(known, "h"))).single()
        assertEquals(2L, g.keep.id)
    }

    @Test fun freeableIsSizeTimesExtraCopies() {
        val items = (1L..3L).map { Hashed(item(it, 4 * mb, taken = it), "h") }
        val g = MediaCheck.duplicateGroups(items).single()
        assertEquals(8 * mb, g.freeable)
    }

    @Test fun groupsSortedByFreeableDescending() {
        val small = listOf(Hashed(item(1, 2 * mb), "a"), Hashed(item(2, 2 * mb), "a"))
        val big = listOf(Hashed(item(3, 30 * mb), "b"), Hashed(item(4, 30 * mb), "b"))
        val groups = MediaCheck.duplicateGroups(small + big)
        assertEquals(listOf(30 * mb, 2 * mb), groups.map { it.freeable })
    }

    @Test fun keyIsUniquePerKind() {
        assertNotEquals(item(7).key, item(7, kind = Kind.VIDEO, mime = "video/mp4").key)
    }

    // Partial hash

    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    @Test fun equalFilesHashEqual() {
        val data = bytes(500_000)
        assertEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong()),
            MediaCheck.partialHash(ByteArrayInputStream(data.copyOf()), data.size.toLong()),
        )
    }

    @Test fun changeInTheFirstOrLastChunkChangesTheHash() {
        val data = bytes(500_000)
        val base = MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong())
        val head = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val tail = data.copyOf().also { it[data.size - 10] = (it[data.size - 10] + 1).toByte() }
        assertNotEquals(base, MediaCheck.partialHash(ByteArrayInputStream(head), data.size.toLong()))
        assertNotEquals(base, MediaCheck.partialHash(ByteArrayInputStream(tail), data.size.toLong()))
    }

    @Test fun changeInTheMiddleIsNotSeen() {
        // The documented limit of the partial hash: only the two ends are read.
        val data = bytes(500_000)
        val mid = data.copyOf().also { it[250_000] = (it[250_000] + 1).toByte() }
        assertEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong()),
            MediaCheck.partialHash(ByteArrayInputStream(mid), data.size.toLong()),
        )
    }

    @Test fun smallFilesAreHashedWhole() {
        val data = bytes(100_000)
        val changed = data.copyOf().also { it[50_000] = (it[50_000] + 1).toByte() }
        assertNotEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong()),
            MediaCheck.partialHash(ByteArrayInputStream(changed), data.size.toLong()),
        )
    }

    @Test fun shortStreamGivesNull() {
        val data = bytes(500_000)
        assertNull(MediaCheck.partialHash(ByteArrayInputStream(data, 0, 300_000), data.size.toLong()))
        assertNull(MediaCheck.partialHash(ByteArrayInputStream(bytes(1000)), 5000))
    }

    @Test fun streamThatSkipsNothingStillWorks() {
        val data = bytes(500_000)
        val stubborn = object : FilterInputStream(ByteArrayInputStream(data)) {
            override fun skip(n: Long): Long = 0
        }
        assertEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong()),
            MediaCheck.partialHash(stubborn as InputStream, data.size.toLong()),
        )
    }

    @Test fun streamThatReturnsShortReadsStillWorks() {
        val data = bytes(500_000)
        val trickle = object : FilterInputStream(ByteArrayInputStream(data)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1000))
        }
        assertEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), data.size.toLong()),
            MediaCheck.partialHash(trickle, data.size.toLong()),
        )
    }

    @Test fun sizeIsPartOfTheHash() {
        // Same bytes read, different declared size: not the same file.
        val data = bytes(100_000)
        assertNotEquals(
            MediaCheck.partialHash(ByteArrayInputStream(data), 100_000),
            MediaCheck.partialHash(ByteArrayInputStream(data), 99_999),
        )
    }
}

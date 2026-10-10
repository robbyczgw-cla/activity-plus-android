package xyz.activityplus.android.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.activityplus.android.MainActivity
import xyz.activityplus.android.R
import xyz.activityplus.android.core.Format
import xyz.activityplus.android.core.MediaCheck
import xyz.activityplus.android.core.MediaCheck.Folder
import xyz.activityplus.android.core.MediaCheck.Hashed
import xyz.activityplus.android.core.MediaCheck.Item
import xyz.activityplus.android.core.MediaCheck.Kind
import xyz.activityplus.android.data.MediaLoader
import xyz.activityplus.android.ui.Actions
import xyz.activityplus.android.ui.components.Badge
import xyz.activityplus.android.ui.components.BigValue
import xyz.activityplus.android.ui.components.Card
import xyz.activityplus.android.ui.components.CardHeader
import xyz.activityplus.android.ui.components.Gap
import xyz.activityplus.android.ui.components.Meter
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.components.StatLine
import xyz.activityplus.android.ui.rememberResumes
import xyz.activityplus.android.ui.theme.LocalSurfaces
import xyz.activityplus.android.ui.theme.Metric

private const val SHOWN_OLD = 30
private const val SHOWN_GROUPS = 50

private fun mediaAccess(context: Context): MediaCheck.Access {
    val sdk = Build.VERSION.SDK_INT
    val granted = MediaCheck.permissionsFor(sdk)
        .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        .toSet()
    return MediaCheck.accessOf(sdk, granted)
}

/** The system asks only when this is called, i.e. when the user tapped "Check media". */
@Composable
private fun rememberMediaRequest(onResult: (MediaCheck.Access) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(mediaAccess(context))
    }
    return { launcher.launch(MediaCheck.permissionsFor(Build.VERSION.SDK_INT).toTypedArray()) }
}

/** The Storage page's entry: explains why access is needed and opens the media screen. */
@Composable
fun MediaCheckCard() {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val request = rememberMediaRequest { if (it.any) openMediaScreen(context) else denied = true }
    Card {
        CardHeader(stringResource(R.string.media_title), Metric.Disk, Icons.Outlined.PhotoLibrary)
        Gap(6.dp)
        Note(stringResource(R.string.media_why))
        if (denied) {
            Gap(6.dp)
            Note(stringResource(R.string.media_denied))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { if (mediaAccess(context).any) openMediaScreen(context) else request() }) {
                Text(stringResource(R.string.media_check_button))
            }
            if (denied) {
                TextButton(onClick = { Actions.appInfo(context, context.packageName) }) {
                    Text(stringResource(R.string.media_open_settings))
                }
            }
        }
    }
}

// MainActivity is singleTask, so this reaches the running activity through onNewIntent.
private fun openMediaScreen(context: Context) {
    context.startActivity(Intent(context, MainActivity::class.java).putExtra("tab", "MEDIA"))
}

@Composable
fun MediaScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumes = rememberResumes()
    var access by remember { mutableStateOf(mediaAccess(context)) }
    LaunchedEffect(resumes) { access = mediaAccess(context) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberMediaRequest { access = it; denied = !it.any }

    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Item>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(access, refresh) {
        if (!access.any) {
            items = emptyList()
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { runCatching { MediaLoader.load(context, access) }.getOrDefault(emptyList()) }
        items = loaded
        val present = loaded.mapTo(HashSet()) { it.key }
        selected = selected.filterTo(HashSet()) { it in present }
    }

    // Duplicates: the user starts the comparison, it runs off the main thread and can be cancelled.
    var hashed by remember { mutableStateOf<List<Hashed>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var capped by remember { mutableStateOf(false) }
    var noCandidates by remember { mutableStateOf(false) }
    fun startDuplicates() {
        val list = items ?: return
        val candidates = MediaCheck.duplicateCandidates(list)
        capped = candidates.truncated
        noCandidates = candidates.items.isEmpty()
        if (noCandidates) {
            hashed = emptyList()
            return
        }
        val total = candidates.items.size
        progress = 0 to total
        job = scope.launch {
            try {
                hashed = withContext(Dispatchers.IO) {
                    MediaLoader.hash(context, candidates.items) { done -> progress = done to total }
                }
            } finally {
                progress = null
                job = null
            }
        }
    }

    val canDelete = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) selected = emptySet()
        refresh++
    }
    fun requestDelete() {
        if (!canDelete) return
        val byKey = items.orEmpty().associateBy { it.key }
        val uris = selected.mapNotNull { byKey[it] }.map { MediaLoader.uri(it) }
        if (uris.isEmpty()) return
        // Android shows its own confirmation; nothing is deleted before the user agrees there.
        runCatching {
            val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    val list = items
    val derived = remember(list) {
        val all = list.orEmpty()
        Derived(
            totals = MediaCheck.totals(all),
            videos = MediaCheck.largest(all, Kind.VIDEO),
            folders = MediaCheck.folderGroups(all),
            old = MediaCheck.oldAndLarge(all, System.currentTimeMillis()),
        )
    }
    val groups = remember(hashed, list) {
        val present = list.orEmpty().mapTo(HashSet()) { it.key }
        MediaCheck.duplicateGroups(hashed.orEmpty().filter { it.item.key in present })
    }
    val selectedItems = remember(selected, list) {
        val byKey = list.orEmpty().associateBy { it.key }
        selected.mapNotNull { byKey[it] }
    }
    fun toggle(item: Item) {
        selected = if (item.key in selected) selected - item.key else selected + item.key
    }
    val selectable = canDelete

    Box(Modifier.fillMaxSize()) {
        Screen(title = stringResource(R.string.media_title), subtitle = stringResource(R.string.media_subtitle)) {
            if (!access.any) {
                item {
                    Card {
                        Note(stringResource(R.string.media_why))
                        if (denied) {
                            Gap(6.dp)
                            Note(stringResource(R.string.media_denied))
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = request) { Text(stringResource(R.string.media_check_button)) }
                            if (denied) {
                                TextButton(onClick = { Actions.appInfo(context, context.packageName) }) {
                                    Text(stringResource(R.string.media_open_settings))
                                }
                            }
                        }
                    }
                }
                return@Screen
            }
            if (list == null) {
                item { Note(stringResource(R.string.loading)) }
                return@Screen
            }

            // 1. Overview
            item { OverviewCard(access, derived.totals, canDelete) }

            // 2. Largest videos
            item {
                Card {
                    CardHeader(stringResource(R.string.media_largest_videos), Metric.Disk)
                    Gap(6.dp)
                    if (derived.videos.isEmpty()) Note(stringResource(R.string.media_none))
                    derived.videos.forEach { v ->
                        MediaRow(v, selectable, v.key in selected, detail(context, v, v.bornMillis), { toggle(v) })
                    }
                }
            }

            // 3. Folders people care about
            item {
                Card {
                    CardHeader(stringResource(R.string.media_folders), Metric.Disk)
                    Note(stringResource(R.string.media_folders_note))
                    Gap(6.dp)
                    if (derived.folders.isEmpty()) Note(stringResource(R.string.media_none))
                    derived.folders.forEach { g ->
                        StatLine(
                            stringResource(folderName(g.folder)),
                            stringResource(
                                R.string.media_folder_line,
                                pluralStringResource(R.plurals.media_files, g.count, g.count),
                                Format.bytes(g.bytes).toString(),
                            ),
                        )
                    }
                }
            }

            // 4. Old and large
            item {
                Card {
                    CardHeader(stringResource(R.string.media_old), Metric.Disk)
                    Note(stringResource(R.string.media_old_note))
                    Gap(6.dp)
                    if (derived.old.isEmpty()) Note(stringResource(R.string.media_none))
                    derived.old.take(SHOWN_OLD).forEach { o ->
                        MediaRow(o, selectable, o.key in selected, detail(context, o, o.modifiedMillis), { toggle(o) })
                    }
                    if (derived.old.size > SHOWN_OLD) {
                        Note(stringResource(R.string.media_more, derived.old.size - SHOWN_OLD), Modifier.padding(top = 6.dp))
                    }
                }
            }

            // 5. Duplicates
            item {
                Card {
                    CardHeader(stringResource(R.string.media_dup_title), Metric.Disk)
                    Gap(6.dp)
                    val p = progress
                    if (p != null) {
                        Note(stringResource(R.string.media_dup_progress, p.first, p.second))
                        Gap(8.dp)
                        Meter(p.first.toDouble() / p.second.coerceAtLeast(1), Metric.Disk)
                        TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.media_dup_cancel)) }
                    } else {
                        val done = hashed
                        if (done == null) {
                            Note(stringResource(R.string.media_dup_intro))
                        } else if (noCandidates) {
                            Note(stringResource(R.string.media_dup_no_candidates))
                        } else if (groups.isEmpty()) {
                            Note(stringResource(R.string.media_dup_none))
                        } else {
                            Text(
                                pluralStringResource(
                                    R.plurals.media_dup_summary, groups.size, groups.size,
                                    Format.bytes(groups.sumOf { it.freeable }).toString(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (done != null && capped) {
                            Note(stringResource(R.string.media_dup_capped, MediaCheck.DUPLICATE_CAP), Modifier.padding(top = 4.dp))
                        }
                        FilledTonalButton(onClick = ::startDuplicates, modifier = Modifier.padding(top = 10.dp)) {
                            Text(stringResource(if (done == null) R.string.media_dup_start else R.string.media_dup_again))
                        }
                    }
                }
            }
            groups.take(SHOWN_GROUPS).forEach { g ->
                item(key = "dup-" + g.keep.key) {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Note(
                                stringResource(R.string.media_dup_group, g.items.size, Format.bytes(g.size).toString()),
                                Modifier.weight(1f),
                            )
                            if (selectable) {
                                TextButton(onClick = { selected = selected + g.others.map { it.key } }) {
                                    Text(stringResource(R.string.media_select_others))
                                }
                            }
                        }
                        g.items.forEach { i ->
                            MediaRow(
                                i, selectable, i.key in selected, detail(context, i, i.bornMillis), { toggle(i) },
                                badge = if (i.key == g.keep.key) stringResource(R.string.media_keep) else null,
                            )
                        }
                    }
                }
            }
            if (selectedItems.isNotEmpty()) item { Spacer(Modifier.height(72.dp)) }
        }

        if (selectedItems.isNotEmpty()) {
            Surface(
                color = LocalSurfaces.current.cardRaised,
                tonalElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.media_selected, selectedItems.size, Format.bytes(selectedItems.sumOf { it.size }).toString()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Note(stringResource(R.string.media_delete_note))
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.media_clear)) }
                    Button(onClick = ::requestDelete) { Text(stringResource(R.string.media_delete)) }
                }
            }
        }
    }
}

private class Derived(
    val totals: MediaCheck.Totals,
    val videos: List<Item>,
    val folders: List<MediaCheck.FolderGroup>,
    val old: List<Item>,
)

@Composable
private fun OverviewCard(access: MediaCheck.Access, totals: MediaCheck.Totals, canDelete: Boolean) {
    val context = LocalContext.current
    Card {
        BigValue(Format.bytes(totals.total))
        Text(
            stringResource(
                R.string.media_overview,
                Format.bytes(totals.imageBytes).toString(),
                Format.bytes(totals.videoBytes).toString(),
                Format.bytes(totals.audioBytes).toString(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (access.partial) Note(stringResource(R.string.media_partial), Modifier.padding(top = 8.dp))
        val missing = buildList {
            if (!access.images) add(stringResource(R.string.media_kind_photos))
            if (!access.videos) add(stringResource(R.string.media_kind_videos))
            if (!access.audio) add(stringResource(R.string.media_kind_audio))
        }
        if (missing.isNotEmpty()) {
            Note(stringResource(R.string.media_missing, missing.joinToString(", ")), Modifier.padding(top = 8.dp))
        }
        if (!canDelete) Note(stringResource(R.string.media_no_delete), Modifier.padding(top = 8.dp))
        if (!access.all) {
            TextButton(onClick = { Actions.appInfo(context, context.packageName) }) {
                Text(stringResource(R.string.media_change_access))
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: Item,
    selectable: Boolean,
    selected: Boolean,
    detail: String,
    onToggle: () -> Unit,
    badge: String? = null,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { openItem(context, item) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge, Metric.Energy)
                }
            }
            Note(detail)
        }
        if (selectable) Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

/** "2.1 GB · 3 Mar 2024 · 1 min · DCIM/Camera". */
private fun detail(context: Context, item: Item, dateMillis: Long): String = listOfNotNull(
    Format.bytes(item.size).toString(),
    dateMillis.takeIf { it > 0 }?.let { DateFormat.getMediumDateFormat(context).format(Date(it)) },
    item.durationMillis.takeIf { item.kind == Kind.VIDEO && it >= 1000 }?.let { Format.duration(it / 1000) },
    item.path.trimEnd('/').takeIf { it.isNotEmpty() },
).joinToString(" · ")

private fun folderName(folder: Folder): Int = when (folder) {
    Folder.SCREENSHOTS -> R.string.media_folder_screenshots
    Folder.SCREEN_RECORDINGS -> R.string.media_folder_screen_recordings
    Folder.WHATSAPP -> R.string.media_folder_whatsapp
    Folder.TELEGRAM -> R.string.media_folder_telegram
    Folder.SIGNAL -> R.string.media_folder_signal
    Folder.CAMERA -> R.string.media_folder_camera
    Folder.DOWNLOADS -> R.string.media_folder_downloads
}

private fun openItem(context: Context, item: Item) {
    val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (item.mime.isBlank()) intent.data = MediaLoader.uri(item) else intent.setDataAndType(MediaLoader.uri(item), item.mime)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
    }
}

package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.R
import xyz.activityplus.android.ui.components.Note
import xyz.activityplus.android.ui.rememberUsageAccess

/**
 * "Who is eating my storage, and when is it full?" Each section lives in its own file and adds
 * one item here, in this order.
 */
@Composable
fun StorageScreen() {
    val access = rememberUsageAccess()
    Screen(title = stringResource(R.string.storage_title), subtitle = stringResource(R.string.storage_subtitle)) {
        if (!access) item { UsageAccessCard() }
        // 1. Breakdown by category (apps, images, videos, audio, system, free)
        item { StorageBreakdownSection() }
        // 2. Growth per app over time and the "full in N days" forecast
        // 3. Caches
        item { StorageCachesSection() }
        // 4. Unused apps
        item { StorageUnusedSection() }
        // 5–6. Media check: large and old media, duplicates (asks for media access)
        // 7. Speed test
        item { Note(stringResource(R.string.storage_note), Modifier.padding(horizontal = 4.dp)) }
    }
}

package xyz.activityplus.android.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.activityplus.android.ActivityPlusApp
import xyz.activityplus.android.core.ForegroundTracker
import xyz.activityplus.android.core.SYSTEM_ROW
import xyz.activityplus.android.core.Snapshot
import xyz.activityplus.android.data.Monitor
import xyz.activityplus.android.data.Settings
import xyz.activityplus.android.ui.theme.LocalSurfaces

val app get() = ActivityPlusApp.instance

/**
 * Live data while the screen is visible: registers with the monitor between ON_START and ON_STOP.
 * [fastMillis] lets a screen ask for faster samples (the battery screen uses one per second).
 */
@Composable
fun rememberLive(fastMillis: Long = 2000L): Pair<Snapshot?, Monitor.Series> {
    val owner = LocalLifecycleOwner.current
    val settings by app.prefs.settings.collectAsState()
    DisposableEffect(owner, settings.intervalSeconds, fastMillis) {
        val interval = (settings.intervalSeconds * 1000L).coerceAtMost(fastMillis)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> app.monitor.register("ui", interval)
                Lifecycle.Event.ON_STOP -> app.monitor.unregister("ui")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.monitor.register("ui", interval)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            app.monitor.unregister("ui")
        }
    }
    val s by app.monitor.latest.collectAsState()
    val series by app.monitor.series.collectAsState()
    return s to series
}

@Composable
fun rememberSettings(): Settings = app.prefs.settings.collectAsState().value

/** Re-checks usage access whenever the screen comes back (the user may have just granted it). */
@Composable
fun rememberUsageAccess(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(ForegroundTracker.hasUsageAccess(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = ForegroundTracker.hasUsageAccess(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state.value
}

private val iconCache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

@Composable
fun AppIcon(pkg: String, size: Dp = 36.dp) {
    val bitmap by produceState(initialValue = iconCache[pkg], pkg) {
        if (value == null && !pkg.startsWith("#")) {
            value = withContext(Dispatchers.IO) {
                iconCache.getOrPut(pkg) {
                    app.usage.icon(pkg)?.let { d: Drawable -> d.toBitmap(96, 96).asImageBitmap() }
                }
            }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(LocalSurfaces.current.cardRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (pkg) {
                    SYSTEM_ROW -> "A"
                    xyz.activityplus.android.core.DrainTracker.SCREEN_OFF -> "☾"
                    else -> "·"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Runs [block] on IO whenever [keys] change; null while loading. */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, block: suspend () -> T): T? {
    val state = remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) {
        state.value = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
    }
    return state.value
}

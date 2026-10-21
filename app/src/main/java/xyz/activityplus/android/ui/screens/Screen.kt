package xyz.activityplus.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.activityplus.android.core.WindowLayout
import xyz.activityplus.android.ui.theme.LocalSurfaces

/**
 * What a [Screen]'s content lambda can add. It mirrors the two calls every page uses from
 * LazyListScope, so the pages do not know whether they sit in one list or in two columns.
 */
interface ScreenScope {
    /** One card (or a few things that belong together). [fullWidth] spans both columns when there are two. */
    fun item(key: Any? = null, fullWidth: Boolean = false, content: @Composable () -> Unit)

    fun <T> items(items: List<T>, key: ((T) -> Any)? = null, content: @Composable (T) -> Unit)
}

private class ListScreenScope(private val scope: LazyListScope) : ScreenScope {
    override fun item(key: Any?, fullWidth: Boolean, content: @Composable () -> Unit) {
        scope.item(key = key) { content() }
    }

    override fun <T> items(items: List<T>, key: ((T) -> Any)?, content: @Composable (T) -> Unit) {
        scope.items(items, key = key) { content(it) }
    }
}

private class GridScreenScope(private val scope: LazyStaggeredGridScope) : ScreenScope {
    override fun item(key: Any?, fullWidth: Boolean, content: @Composable () -> Unit) {
        scope.item(key = key, span = if (fullWidth) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) { content() }
    }

    override fun <T> items(items: List<T>, key: ((T) -> Any)?, content: @Composable (T) -> Unit) {
        scope.items(items, key = key) { content(it) }
    }
}

/**
 * Page with a big title and a lazy list of cards. On a narrow window that is one column. Where there
 * is room (landscape, tablets, foldables) the cards flow into two columns, the title spans both, and
 * the whole page stops growing at a sensible width instead of stretching the cards.
 */
@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: ScreenScope.() -> Unit,
) {
    // The system bars and a camera cutout are padded here, once: in landscape the navigation bar can
    // be at the side, and nothing may end up under it. Insets a parent already used (the Scaffold
    // in the main window) are not padded twice.
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical),
        ),
    ) {
        // The width the page really gets (after rail and insets), not the window's.
        val lanes = WindowLayout.lanes(maxWidth.value)
        val margin = WindowLayout.sideMarginDp(maxWidth.value, lanes).dp
        val contentPadding = PaddingValues(start = 16.dp + margin, end = 16.dp + margin, top = 12.dp, bottom = 24.dp)
        val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalSurfaces.current.muted)
                    }
                }
                action?.invoke()
            }
        }
        // Lets a card on the page (the glance card) bring another one into view; item 0 is the header.
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val gridState = rememberLazyStaggeredGridState()
        val scrollTo: (Int) -> Unit = { i ->
            scope.launch { if (lanes == 1) listState.animateScrollToItem(i) else gridState.animateScrollToItem(i) }
        }
        if (lanes == 1) CompositionLocalProvider(LocalScreenScroll provides scrollTo) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val screen = ListScreenScope(this)
                screen.item(content = header)
                screen.content()
            }
        } else CompositionLocalProvider(LocalScreenScroll provides scrollTo) {
            // A staggered grid, not rows of two: the cards differ a lot in height and would leave gaps.
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Fixed(lanes),
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val screen = GridScreenScope(this)
                screen.item(fullWidth = true, content = header)
                screen.content()
            }
        }
    }
}

/**
 * Content of a bottom sheet. It scrolls: in landscape a sheet is barely taller than the screen
 * and would otherwise cut off its lower half, buttons included.
 */
@Composable
fun Modifier.sheetContent(): Modifier =
    verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()

/** Text of a dialog that may be taller than a landscape screen: scrolls, so the buttons stay reachable. */
@Composable
fun Modifier.dialogContent(): Modifier = verticalScroll(rememberScrollState())

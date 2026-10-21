package xyz.activityplus.android.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.activityplus.android.ui.theme.ActivityPlusTheme

/**
 * Renders cards on the JVM (Robolectric) with plain data and checks what a user reads. Texts, not
 * pixels: the assertions use the app's own strings, so they hold in any theme. A tall screen, so
 * that a long card is displayed whole and nothing needs scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h4000dp")
abstract class CardTest {
    @get:Rule val rule = createComposeRule()

    protected val context: Context get() = RuntimeEnvironment.getApplication()

    protected fun show(content: @Composable () -> Unit) {
        rule.setContent { ActivityPlusTheme(dark = false) { Column { content() } } }
    }

    // Without arguments not through the formatter, as stringResource does: "30 %" is no format.
    protected fun str(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    protected fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String = context.resources.getQuantityString(id, n, *args)

    /** The whole text is on screen, not merely somewhere in the tree. */
    protected fun shown(text: String) {
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    /** Part of a longer line, e.g. a reason joined to a label. */
    protected fun shownPart(text: String) {
        rule.onNodeWithText(text, substring = true).assertIsDisplayed()
    }

    /** For screens that load from the (empty) stores off the main thread first. */
    protected fun waitShown(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        shown(text)
    }

    protected fun absent(text: String) {
        rule.onNodeWithText(text, substring = true).assertDoesNotExist()
    }
}

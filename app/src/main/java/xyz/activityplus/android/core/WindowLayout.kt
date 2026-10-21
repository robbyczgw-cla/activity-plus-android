package xyz.activityplus.android.core

/**
 * Layout decisions for the window size, in dp, without Android types so they can be tested.
 * The width classes are Material's window size classes (compact < 600 dp, medium < 840 dp), which
 * is enough here: no need for the window-size-class library for two comparisons.
 */
object WindowLayout {
    enum class Width { COMPACT, MEDIUM, EXPANDED }

    const val MEDIUM_FROM_DP = 600f
    const val EXPANDED_FROM_DP = 840f

    /** Two card columns need about 300 dp each; below this the content stays in one column. */
    const val TWO_LANES_FROM_DP = 640f

    /** One column of cards gets wider than this only as empty margin, not as longer cards. */
    const val ONE_LANE_MAX_DP = 640f
    const val TWO_LANES_MAX_DP = 1000f

    fun widthClass(widthDp: Float): Width = when {
        widthDp >= EXPANDED_FROM_DP -> Width.EXPANDED
        widthDp >= MEDIUM_FROM_DP -> Width.MEDIUM
        else -> Width.COMPACT
    }

    /** A navigation rail on the left replaces the bottom bar from medium width on (tablets, landscape). */
    fun useRail(windowWidthDp: Float): Boolean = widthClass(windowWidthDp) != Width.COMPACT

    /** Card columns for the space a page really gets (the window minus rail and system bars). */
    fun lanes(contentWidthDp: Float): Int = if (contentWidthDp >= TWO_LANES_FROM_DP) 2 else 1

    /** The widest the cards may spread; the rest becomes margin, so a tablet does not get stretched cards. */
    fun maxContentDp(lanes: Int): Float = if (lanes >= 2) TWO_LANES_MAX_DP else ONE_LANE_MAX_DP

    /** Extra margin on each side that keeps the content at its maximum width, centered. */
    fun sideMarginDp(contentWidthDp: Float, lanes: Int): Float =
        ((contentWidthDp - maxContentDp(lanes)) / 2f).coerceAtLeast(0f)
}

package xyz.activityplus.android.core

/**
 * The report the user shares for a forum or a support request, as plain text and as the lines of
 * the image card. Pure: the caller words the labels and values (they come from string resources
 * and [Format]), this only decides what is left out and how it is laid out.
 */
object ShareReport {
    /** One fact. A row without a value is left out: a missing reading says nothing. */
    data class Row(val label: String, val value: String?)

    data class Section(val title: String, val rows: List<Row>)

    /** One line of the image card: a heading, or a label with its value on the right. */
    data class Line(val heading: Boolean, val left: String, val right: String = "")

    private fun Row.shown() = !value.isNullOrBlank()

    /** Sections that have something to show, flattened; blank values and empty sections are gone. */
    fun lines(sections: List<Section>): List<Line> = sections.flatMap { s ->
        val rows = s.rows.filter { it.shown() }
        if (rows.isEmpty()) emptyList()
        else listOf(Line(true, s.title)) + rows.map { Line(false, it.label, it.value!!.trim()) }
    }

    /**
     * Plain text for the share sheet: title, a one-line device summary, then each section as a
     * heading and "label: value" lines. A row without a label is just its value.
     */
    fun text(title: String, subtitle: String, sections: List<Section>): String = buildString {
        append(title).append('\n')
        if (subtitle.isNotBlank()) append(subtitle).append('\n')
        for (line in lines(sections)) {
            if (line.heading) append('\n').append(line.left).append('\n')
            else if (line.left.isBlank()) append("  ").append(line.right).append('\n')
            else append("  ").append(line.left).append(": ").append(line.right).append('\n')
        }
    }.trimEnd() + "\n"

    /** "Google Pixel 8": the model alone on most phones, but some already start with the maker. */
    fun deviceName(manufacturer: String, model: String): String {
        val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
        val name = model.trim()
        return when {
            name.isEmpty() -> maker
            maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
            else -> "$maker $name"
        }
    }
}

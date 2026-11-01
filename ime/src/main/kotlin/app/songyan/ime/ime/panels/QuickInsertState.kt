package app.songyan.ime.ime.panels

import app.songyan.ime.content.QuickInsertDateFormat

/** One date/time format row: the format plus the text it would insert right now. */
data class QuickInsertFormatRow(
    val format: QuickInsertDateFormat,
    val preview: String,
    val selected: Boolean = false,
)

/**
 * What the quick-insert section of the phrases panel renders. The owner computes the previews (the
 * date formats are resolved when the panel opens, not when it was built) and supplies the action state.
 */
data class QuickInsertState(
    /** The text "repeat last input" would insert, or null when there is nothing to repeat. */
    val repeatPreview: String? = null,
    val dateRows: List<QuickInsertFormatRow> = emptyList(),
)

package app.songyan.ime.content

import app.songyan.ime.ime.panels.QuickInsertFormatRow
import app.songyan.ime.ime.panels.QuickInsertState

/**
 * Builds the keyboard settings panel state from [core].
 *
 * The date previews are computed right before the panel is built, so a panel opened across
 * midnight still inserts today: nothing about the date is cached in the panel itself.
 */
fun quickInsertState(core: QuickInsertCore): QuickInsertState {
    val dateRows =
        QuickInsertDateFormat.entries.map { format ->
            QuickInsertFormatRow(
                format = format,
                preview = core.formattedText(format),
                selected = format == core.selectedFormat,
            )
        }
    return QuickInsertState(
        repeatPreview = core.repeatPreview,
        dateRows = dateRows,
    )
}

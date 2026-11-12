package app.songyan.ime.ime.tools

import app.songyan.ime.tools.ToolFailure

/** The failed tool and the words it gave, as the panel needs them to name the failure. */
data class FailedToolCopy(
    /** Title of the tool that failed; null when the model has no usable title. */
    val title: String?,
    /** The tool's own message, trimmed; null when it gave none, so the panel uses its fallback. */
    val message: String?,
)

/**
 * The tool's own error, when the model carries one. The contract says a tool's message is shown
 * as written, and the user has to know which tool said it, so both travel together; keeping the
 * decision here (instead of in the view) lets the wording be tested without an Android context.
 * Returns null for failures that have their own fixed copy (timeout, cancel, no result, an
 * unavailable input): those never show a tool's words.
 */
fun ToolPanelController.State.failedToolCopy(): FailedToolCopy? {
    val error = failure as? ToolFailure.ToolError ?: return null
    val message = error.message?.trim()?.takeIf { it.isNotEmpty() }
    val title = tool?.title?.trim()?.takeIf { it.isNotEmpty() }
    if (message == null && title == null) return null
    return FailedToolCopy(title = title, message = message)
}

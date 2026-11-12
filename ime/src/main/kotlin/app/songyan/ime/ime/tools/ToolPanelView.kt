package app.songyan.ime.ime.tools

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.ime.R
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.UnavailableReason
import app.songyan.ime.tools.WriteBlockReason

/**
 * The tool panel: the available tools, the running state, the previewed results and the actions the
 * model allows (copy, replace, insert, undo, cancel). It renders [ToolPanelController.State] and
 * reports taps; every decision stays with the controller and [app.songyan.ime.tools.ToolRun].
 */
class ToolPanelView(
    context: Context,
) : LinearLayout(context) {
    var onRun: (ToolDescriptor) -> Unit = {}
    var onSelect: (Int) -> Unit = {}
    var onCopy: () -> Unit = {}
    var onReplace: () -> Unit = {}
    var onInsert: () -> Unit = {}
    var onUndo: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onCloseRequested: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val title = TextView(context)
    private val status = TextView(context)
    private val list = LinearLayout(context)
    private val actions = LinearLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(
            KeyboardPalette
                .from(context)
                .tokens.background
                .toInt(),
        )
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        title.setText(R.string.ime_tool_panel_title)
        title.setTextSize(16f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.setTextSize(14f)
        status.setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.orientation = VERTICAL
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        actions.orientation = HORIZONTAL
        actions.gravity = Gravity.END
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addAction(R.string.ime_tool_panel_close) { onCloseRequested() }
    }

    fun render(state: ToolPanelController.State) {
        list.removeAllViews()
        actions.removeAllViews()
        addAction(R.string.ime_tool_panel_close) { onCloseRequested() }
        // A refusal or a failure names the model's reason, but the phase keeps its own actions so
        // the user can retry, choose another result or insert instead of being left with Close only.
        val notice = outcomeText(state)
        if (!state.available) {
            status.setText(R.string.ime_tool_panel_unavailable)
            return
        }
        when (state.phase) {
            ToolPhase.RUNNING -> {
                status.setText(R.string.ime_tool_panel_running)
                addAction(R.string.ime_tool_panel_cancel) { onCancel() }
            }
            ToolPhase.PREVIEWING -> {
                val preview = state.preview
                status.setText(
                    when {
                        preview == null -> R.string.ime_tool_panel_failed
                        preview.isTruncated -> R.string.ime_tool_panel_truncated
                        else -> R.string.ime_tool_panel_select_hint
                    },
                )
                preview?.results?.forEachIndexed { index, result ->
                    val row = Button(context)
                    row.text = result
                    row.isAllCaps = false
                    row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    row.setTypeface(
                        row.typeface,
                        if (index ==
                            preview.selectedIndex
                        ) {
                            Typeface.BOLD
                        } else {
                            Typeface.NORMAL
                        },
                    )
                    row.setOnClickListener { onSelect(index) }
                    list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
                addAction(R.string.ime_tool_panel_copy) { onCopy() }
                addAction(R.string.ime_tool_panel_replace) { onReplace() }
                addAction(R.string.ime_tool_panel_insert) { onInsert() }
            }
            ToolPhase.WROTE -> {
                status.setText(R.string.ime_tool_panel_written)
                if (state.canUndo) addAction(R.string.ime_tool_panel_undo) { onUndo() }
            }
            ToolPhase.FAILED -> {
                status.setText(failureText(state))
                // An explicit retry re-runs the failed tool; the list below still offers the
                // others, so a failure never leaves the user with Close only.
                state.tool?.let { failed -> addAction(R.string.ime_tool_panel_retry) { onRun(failed) } }
                state.tools.forEach { tool ->
                    val row = Button(context)
                    row.text = tool.title
                    row.isAllCaps = false
                    row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    row.setOnClickListener { onRun(tool) }
                    list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
            }
            else -> {
                status.setText(R.string.ime_tool_panel_select_hint)
                state.tools.forEach { tool ->
                    val row = Button(context)
                    row.text = tool.title
                    row.isAllCaps = false
                    row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    row.setOnClickListener { onRun(tool) }
                    list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
            }
        }
        if (notice != null) status.setText(notice)
    }

    /** A refused write or undo names the model's own reason instead of leaving the last phase text. */
    private fun outcomeText(state: ToolPanelController.State): Int? =
        when (val write = state.write) {
            is ToolWriteResult.Blocked -> blockReasonText(write.reason)
            ToolWriteResult.NotApplied, ToolWriteResult.Unavailable -> R.string.ime_tool_panel_failed
            else ->
                when (val undo = state.undo) {
                    is ToolUndoResult.Blocked -> blockReasonText(undo.reason)
                    ToolUndoResult.Expired -> R.string.ime_tool_panel_undo_expired
                    ToolUndoResult.NotApplied, ToolUndoResult.Unavailable -> R.string.ime_tool_panel_failed
                    else -> null
                }
        }

    private fun blockReasonText(reason: WriteBlockReason): Int =
        when (reason) {
            WriteBlockReason.DOCUMENT_CHANGED -> R.string.ime_tool_panel_field_changed
            WriteBlockReason.SECURE_ENTRY -> R.string.ime_tool_panel_unavailable
            WriteBlockReason.NO_TEXT -> R.string.ime_tool_panel_no_text
            WriteBlockReason.EDITED_AFTER_WRITE -> R.string.ime_tool_panel_edited
            WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT -> R.string.ime_tool_panel_partial
            WriteBlockReason.UNSUPPORTED_INSERTION -> R.string.ime_tool_panel_selection
        }

    /**
     * The line under the panel title for a failed run. A tool's own words are shown as written and
     * prefixed with the tool's title (a script's name): the user has to know which tool said it.
     * The generic copy is only the fallback when the model carries neither.
     */
    private fun failureText(state: ToolPanelController.State): CharSequence {
        val failed = state.failedToolCopy()
        if (failed != null) {
            return when {
                failed.title != null && failed.message != null ->
                    context.getString(R.string.ime_tool_panel_error_with_tool, failed.title, failed.message)
                failed.message != null -> failed.message
                else -> context.getString(R.string.ime_tool_panel_failed)
            }
        }
        return context.getString(
            when (val failure = state.failure) {
                is ToolFailure.Unavailable ->
                    when (failure.reason) {
                        UnavailableReason.SECURE_ENTRY -> R.string.ime_tool_panel_unavailable
                        UnavailableReason.NO_TEXT -> R.string.ime_tool_panel_no_text
                        UnavailableReason.MISSING_CAPABILITIES -> R.string.ime_tool_panel_failed
                    }
                ToolFailure.Cancelled -> R.string.ime_tool_panel_cancelled
                ToolFailure.TimedOut -> R.string.ime_tool_panel_timeout
                ToolFailure.NoResult -> R.string.ime_tool_panel_no_result
                is ToolFailure.ToolError -> R.string.ime_tool_panel_failed
                null -> R.string.ime_tool_panel_failed
            },
        )
    }

    private fun addAction(
        label: Int,
        onClick: () -> Unit,
    ) {
        val button = Button(context)
        button.setText(label)
        button.isAllCaps = false
        button.setOnClickListener { onClick() }
        actions.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
}

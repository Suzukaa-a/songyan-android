package app.songyan.ime.ime.tools

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
 *
 * The panel shares the keyboard surface with the keys, so it must never be clipped: the list of
 * tools and results scrolls, a long result is cut after a few lines until the user unfolds it, and
 * the action row scrolls sideways so a large system font cannot push the last button out of reach.
 * Colours come from the same [KeyboardPalette] as the keys, so the panel is readable on both the
 * light and the dark keyboard surface.
 */
class ToolPanelView(
    context: Context,
    initialPalette: KeyboardPalette = KeyboardPalette.from(context),
) : LinearLayout(context) {
    var onRun: (ToolDescriptor) -> Unit = {}
    var onSelect: (Int) -> Unit = {}

    /** Copies the selected result; true when something went to the clipboard (drives the tip). */
    var onCopy: () -> Boolean = { false }
    var onReplace: () -> Unit = {}
    var onInsert: () -> Unit = {}
    var onUndo: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onCloseRequested: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private var palette = initialPalette
    private val title = TextView(context)
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private val actionDivider = View(context)
    private val actionScroll = HorizontalScrollView(context)
    private val actions = LinearLayout(context)
    private var lastState: ToolPanelController.State? = null

    /** The 2-second "Done." line a copy leaves in the status slot, then the state comes back. */
    private var copyTip: Runnable? = null

    /** Own handler, not the view's: the tip must run even before the panel is attached. */
    private val tipHandler = Handler(Looper.getMainLooper())

    /** Results the user unfolded; kept while the same preview stays on screen. */
    private val expandedResults = mutableSetOf<Int>()
    private var lastResults: List<String> = emptyList()

    init {
        orientation = VERTICAL
        applyPalette()
        val pad = padding(12)
        setPadding(pad, pad, pad, pad)
        title.setText(R.string.ime_tool_panel_title)
        title.setTextColor(palette.text)
        title.setTextSize(16f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.setTextSize(14f)
        status.setTextColor(palette.muted)
        status.setPadding(0, padding(6), 0, padding(6))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Running shows a thin indeterminate bar under the status line, where the eye already is.
        progress.isIndeterminate = true
        progress.indeterminateTintList = ColorStateList.valueOf(palette.accent)
        progress.visibility = GONE
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, padding(3)))
        list.orientation = VERTICAL
        // The action row is a sibling below the scroll area, but the last row still needs room to
        // clear the panel's bottom edge and the close icon's touch target: a safe inset of its own.
        list.setPadding(0, 0, 0, padding(12))
        scroll.isFillViewport = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(actionDivider, LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, padding(1) / 2)))
        actions.orientation = HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.setPadding(0, padding(4), 0, 0)
        actionScroll.isHorizontalScrollBarEnabled = false
        actionScroll.overScrollMode = OVER_SCROLL_NEVER
        actionScroll.addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(actionScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Re-reads the palette after a day/night change and paints the current state again. */
    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        applyPalette()
        lastState?.let(::render)
    }

    fun render(state: ToolPanelController.State) {
        lastState = state
        applyPalette()
        list.removeAllViews()
        actions.removeAllViews()
        progress.visibility = GONE
        if (state.preview?.results != lastResults) {
            lastResults = state.preview?.results.orEmpty()
            expandedResults.clear()
        }
        // A refusal or a failure names the model's reason, but the phase keeps its own actions so
        // the user can retry, choose another result or insert instead of being left with Close only.
        val notice = outcomeText(state)
        if (!state.available) {
            status.setText(R.string.ime_tool_panel_unavailable)
        } else {
            when (state.phase) {
                ToolPhase.RUNNING -> {
                    // A few seconds in, the elapsed time shows the run is waiting, not stuck.
                    if (state.elapsedSeconds >= ELAPSED_SHOWN_AFTER_SECONDS) {
                        val seconds = state.elapsedSeconds
                        status.text =
                            context.getString(
                                R.string.ime_tool_panel_running_elapsed,
                                String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60),
                            )
                    } else {
                        status.setText(R.string.ime_tool_panel_running)
                    }
                    progress.visibility = VISIBLE
                    addAction(R.string.ime_tool_panel_cancel, ActionStyle.TEXT) { onCancel() }
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
                        addResult(index, result, index == preview.selectedIndex)
                    }
                    // The write actions read in the order of their weight: the one that replaces
                    // the selection is primary, inserting after it is tonal, copying is text.
                    addAction(R.string.ime_tool_panel_copy, ActionStyle.TEXT) {
                        if (onCopy()) showCopyTip()
                    }
                    addAction(R.string.ime_tool_panel_insert, ActionStyle.TONAL) { onInsert() }
                    addAction(R.string.ime_tool_panel_replace, ActionStyle.PRIMARY) { onReplace() }
                }
                ToolPhase.WROTE -> {
                    status.setText(R.string.ime_tool_panel_written)
                    if (state.canUndo) addAction(R.string.ime_tool_panel_undo, ActionStyle.TONAL) { onUndo() }
                }
                ToolPhase.FAILED -> {
                    status.setText(failureText(state))
                    // An explicit retry re-runs the failed tool; the list below still offers the
                    // others, so a failure never leaves the user with Close only.
                    state.tool?.let { failed ->
                        addAction(R.string.ime_tool_panel_retry, ActionStyle.TONAL) { onRun(failed) }
                    }
                    state.tools.forEach { tool -> addTool(tool) }
                }
                else -> {
                    status.setText(R.string.ime_tool_panel_select_hint)
                    state.tools.forEach { tool -> addTool(tool) }
                }
            }
        }
        if (notice != null) status.setText(notice)
        addClose()
    }

    /** One tool the user can run; a row, not a chip, because it starts something rather than toggling. */
    private fun addTool(tool: ToolDescriptor) {
        val row =
            Button(context).apply {
                text = tool.title
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 14f
                setTextColor(palette.text)
                // The platform button style tints its background; the palette owns these colours.
                backgroundTintList = null
                stateListAnimator = null
                elevation = 0f
                background = rowBackground(selected = false)
                textAlignment = TEXT_ALIGNMENT_VIEW_START
                setPadding(padding(12), padding(10), padding(12), padding(10))
                setOnClickListener { onRun(tool) }
            }
        list.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = padding(2)
                bottomMargin = padding(2)
            },
        )
    }

    /**
     * One previewed result. It carries the selection itself (colour, a check mark and the platform's
     * selected state, so TalkBack announces which result the write actions would use). A long result
     * is cut after [RESULT_MAX_LINES] lines and offers a fold/unfold toggle below it.
     */
    private fun addResult(
        index: Int,
        result: String,
        selected: Boolean,
    ) {
        val expandable = result.length > RESULT_PREVIEW_CHARS
        val unfolded = index in expandedResults
        val row = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(
            TextView(context).apply {
                text = if (selected) "\u2713 " + result else result
                textSize = 14f
                maxLines = if (expandable && !unfolded) RESULT_MAX_LINES else Int.MAX_VALUE
                ellipsize = if (expandable && !unfolded) TextUtils.TruncateAt.END else null
                setTextColor(if (selected) palette.accentText else palette.text)
                background = rowBackground(selected)
                isClickable = true
                isFocusable = true
                isSelected = selected
                setPadding(padding(12), padding(10), padding(12), padding(10))
                contentDescription = if (selected) context.getString(R.string.ime_item_selected, result) else null
                setOnClickListener { onSelect(index) }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        if (expandable) {
            row.addView(
                TextView(context).apply {
                    text =
                        context.getString(
                            if (unfolded) R.string.ime_tool_panel_collapse else R.string.ime_tool_panel_expand,
                        )
                    textSize = 13f
                    setTextColor(palette.accent)
                    setPadding(padding(12), 0, padding(12), padding(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (unfolded) expandedResults.remove(index) else expandedResults.add(index)
                        lastState?.let(::render)
                    }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        list.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = padding(2)
                bottomMargin = padding(2)
            },
        )
    }

    /** The close affordance is an icon at the end of the action row, as elsewhere in the keyboard. */
    private fun addClose() {
        actions.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = ColorStateList.valueOf(palette.muted)
                contentDescription = context.getString(R.string.ime_tool_panel_close)
                isClickable = true
                isFocusable = true
                setPadding(padding(10), padding(10), padding(10), padding(10))
                setOnClickListener { onCloseRequested() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = padding(4)
            },
        )
    }

    private enum class ActionStyle { PRIMARY, TONAL, TEXT }

    /**
     * One action button, worded by its weight: primary fills with the accent, tonal sits on the
     * control surface, text burns none of the surface. The row scrolls sideways, so a button is
     * never clipped by a long label or a large system font.
     */
    private fun addAction(
        label: Int,
        style: ActionStyle,
        onClick: () -> Unit,
    ) {
        actions.addView(
            Button(context).apply {
                setText(label)
                isAllCaps = false
                textSize = 14f
                minWidth = 0
                minHeight = 0
                setPadding(padding(14), padding(9), padding(14), padding(9))
                // A themed Button would tint the palette fill grey; this panel paints its own
                // surface colours so the buttons stay legible on the dark keyboard too.
                backgroundTintList = null
                stateListAnimator = null
                elevation = 0f
                setTextColor(
                    when (style) {
                        ActionStyle.PRIMARY -> palette.accentText
                        ActionStyle.TONAL -> palette.text
                        ActionStyle.TEXT -> palette.accent
                    },
                )
                background =
                    when (style) {
                        ActionStyle.PRIMARY ->
                            GradientDrawable().apply {
                                cornerRadius = padding(10).toFloat()
                                setColor(palette.accent)
                            }
                        ActionStyle.TONAL ->
                            GradientDrawable().apply {
                                cornerRadius = padding(10).toFloat()
                                setColor(palette.control)
                                setStroke(maxOf(1, padding(1) / 2), palette.controlBorder)
                            }
                        ActionStyle.TEXT -> GradientDrawable().apply { setColor(0) }
                    }
                setOnClickListener { onClick() }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = padding(8)
            },
        )
    }

    /**
     * Copying has no state of its own in the model, so the panel says it happened itself: the
     * status line shows the existing "Done." copy for two seconds and then repaints the state.
     */
    private fun showCopyTip() {
        status.setText(R.string.ime_tool_panel_written)
        copyTip?.let(tipHandler::removeCallbacks)
        val tip =
            Runnable {
                copyTip = null
                lastState?.let(::render)
            }
        copyTip = tip
        tipHandler.postDelayed(tip, COPY_TIP_MS)
    }

    override fun onDetachedFromWindow() {
        copyTip?.let(tipHandler::removeCallbacks)
        copyTip = null
        super.onDetachedFromWindow()
    }

    private fun rowBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = padding(10).toFloat()
            setColor(if (selected) palette.accent else palette.key)
            if (!selected) setStroke(maxOf(1, padding(1) / 2), palette.keyBorder)
        }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        actionDivider.setBackgroundColor(palette.divider)
        actionScroll.setBackgroundColor(palette.background)
        title.setTextColor(palette.text)
        // The muted role is designed for the surface in the default palettes, but a custom
        // appearance can pair it with a background only just readable: fall back to the body
        // colour rather than leave the status line dimmer than WCAG AA.
        status.setTextColor(readableText(palette.muted))
        progress.indeterminateTintList = ColorStateList.valueOf(palette.accent)
    }

    /** [preferred] when it stays readable on the panel surface, the body colour otherwise. */
    private fun readableText(preferred: Int): Int =
        if (contrast(preferred, palette.background) >= MIN_SECONDARY_CONTRAST) preferred else palette.text

    /** WCAG contrast ratio of two opaque ARGB colours. */
    private fun contrast(
        foreground: Int,
        background: Int,
    ): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
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

    private fun padding(dp: Int): Int = (dp * density).toInt()

    private companion object {
        const val ELAPSED_SHOWN_AFTER_SECONDS = 3L

        /** Lines a long result shows before the fold link appears. */
        const val RESULT_MAX_LINES = 3

        /** Characters past which a result gets the fold link; shorter ones fit in the lines above. */
        const val RESULT_PREVIEW_CHARS = 120

        /** WCAG AA for normal text; the status line is body text, not a large heading. */
        const val MIN_SECONDARY_CONTRAST = 4.5

        /** How long the "Done." tip a copy shows stays in the status slot. */
        const val COPY_TIP_MS = 2_000L
    }
}

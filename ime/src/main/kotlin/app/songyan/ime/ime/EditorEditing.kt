package app.songyan.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import app.songyan.ime.keyboard.support.CaretMotion

/** What the edit panel's keys do to the editor. */
enum class EditAction {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    LINE_START,
    LINE_END,
    SELECT_ALL,
    COPY,
    CUT,
    PASTE,
    UNDO,
    DELETE,
}

/** Characters of text read around the caret for a move; a longer document is moved within this window. */
private const val EDIT_WINDOW = 4096

/** The text around the selection, with where it sits in the document. All offsets are UTF-16 units. */
private class CaretWindow(
    val text: String,
    /** Document offset of `text[0]`. */
    val offset: Int,
    /** Document offsets of the selection's ends, in the order the editor reports them (anchor, focus). */
    val anchor: Int,
    val focus: Int,
    /** The window holds the whole document. */
    val complete: Boolean,
) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)
}

/**
 * Performs one key of the edit panel. Movement and selection use `setSelection` from the text around
 * the caret, never DPAD events, so an editor cannot read them as navigation out of the field. A key-event
 * editor (a terminal) has no text to measure and gets nothing here.
 *
 * Delete is the keyboard's Backspace. Copy, cut, paste, select all and undo are the editor's own context-menu actions first, so a rich
 * editor keeps its formatting and undo history; the plain clipboard is the fallback for an editor that
 * does not offer them. [mayReadText] is false in a private editor, where the keyboard must not read the
 * text back out.
 *
 * @return whether the editor took the action.
 */
internal fun EditorConnection.performEdit(
    action: EditAction,
    extend: Boolean,
    clipboard: ClipboardManager?,
    mayReadText: Boolean = true,
): Boolean {
    val ic = connection() ?: return false
    if (action == EditAction.DELETE) {
        // The keyboard's own Backspace path: the selection if there is one, else one grapheme, and a
        // key-event editor gets a Delete key. It reads no more text than Backspace does.
        selectionAnchor = -1
        delete()
        return true
    }
    if (keyEventEditor) return false
    val handled =
        when (action) {
            EditAction.SELECT_ALL -> selectAll(ic)
            EditAction.COPY -> mayReadText && copy(ic, clipboard)
            EditAction.CUT -> mayReadText && cut(ic, clipboard)
            EditAction.PASTE -> paste(ic, clipboard)
            EditAction.UNDO -> undo(ic)
            else -> moveCaret(ic, action, extend)
        }
    if (handled && action.changesText) {
        // The text changed under the keyboard's bookkeeping: what it knew about the caret is stale.
        selection.forget()
        forgetText()
    }
    return handled
}

private val EditAction.changesText: Boolean
    get() = this == EditAction.CUT || this == EditAction.PASTE || this == EditAction.UNDO

private fun EditorConnection.readWindow(ic: InputConnection): CaretWindow? {
    val request = ExtractedTextRequest().apply { hintMaxChars = EDIT_WINDOW }
    val extracted = ic.getExtractedText(request, 0)
    val text = extracted?.text?.toString()
    if (extracted != null && text != null && extracted.selectionStart >= 0 && extracted.selectionEnd >= 0) {
        val offset = extracted.startOffset
        return CaretWindow(
            text = text,
            offset = offset,
            anchor = offset + extracted.selectionStart,
            focus = offset + extracted.selectionEnd,
            complete = offset == 0 && extracted.partialEndOffset < 0,
        )
    }
    // An editor without extracted text: the selection the keyboard tracked, and the text around it.
    if (!selection.known) return null
    val start = minOf(selection.start, selection.end)
    val before = ic.getTextBeforeCursor(EDIT_WINDOW, 0)?.toString() ?: return null
    val selected = ic.getSelectedText(0)?.toString().orEmpty()
    val after = ic.getTextAfterCursor(EDIT_WINDOW, 0)?.toString() ?: return null
    return CaretWindow(
        text = before + selected + after,
        offset = start - before.length,
        anchor = selection.start,
        focus = selection.end,
        complete = false,
    )
}

private fun EditorConnection.moveCaret(
    ic: InputConnection,
    action: EditAction,
    extend: Boolean,
): Boolean {
    val window = readWindow(ic) ?: return false
    val collapsed = window.start == window.end
    // Extending keeps one end fixed; the anchor is remembered across keys because the editor only
    // reports the two ends, not which of them the user is moving.
    val anchor =
        when {
            !extend -> -1
            selectionAnchor in setOf(window.start, window.end) -> selectionAnchor
            else -> window.anchor
        }
    val focus =
        if (anchor < 0) {
            window.focus
        } else if (anchor == window.start) {
            window.end
        } else {
            window.start
        }
    val text = window.text
    val from = (focus - window.offset).coerceIn(0, text.length)
    val collapsedTo =
        when {
            extend || collapsed -> null
            action == EditAction.LEFT -> window.start
            action == EditAction.RIGHT -> window.end
            else -> null
        }
    val target =
        collapsedTo
            ?: window.offset +
            when (action) {
                EditAction.LEFT -> CaretMotion.left(text, from)
                EditAction.RIGHT -> CaretMotion.right(text, from)
                EditAction.UP -> CaretMotion.up(text, from)
                EditAction.DOWN -> CaretMotion.down(text, from)
                EditAction.LINE_START -> CaretMotion.lineStart(text, from)
                EditAction.LINE_END -> CaretMotion.lineEnd(text, from)
                else -> return false
            }
    val newAnchor = if (extend) (if (anchor >= 0) anchor else focus) else target
    if (!ic.setSelection(newAnchor, target)) return false
    selectionAnchor = if (extend) newAnchor else -1
    selection.moved(newAnchor, target)
    forgetText()
    return true
}

private fun EditorConnection.selectAll(ic: InputConnection): Boolean {
    if (ic.performContextMenuAction(android.R.id.selectAll)) {
        selection.forget()
        selectionAnchor = -1
        return true
    }
    val window = readWindow(ic) ?: return false
    if (!window.complete) return false
    val ok = ic.setSelection(0, window.text.length)
    if (ok) {
        selection.moved(0, window.text.length)
        selectionAnchor = 0
    }
    return ok
}

private fun copy(
    ic: InputConnection,
    clipboard: ClipboardManager?,
): Boolean {
    if (ic.getSelectedText(0).isNullOrEmpty()) return false
    if (ic.performContextMenuAction(android.R.id.copy)) return true
    val selected = ic.getSelectedText(0)?.toString().orEmpty()
    if (selected.isEmpty() || clipboard == null) return false
    clipboard.setPrimaryClip(ClipData.newPlainText("", selected))
    return true
}

private fun cut(
    ic: InputConnection,
    clipboard: ClipboardManager?,
): Boolean {
    if (ic.getSelectedText(0).isNullOrEmpty()) return false
    if (ic.performContextMenuAction(android.R.id.cut)) return true
    if (!copy(ic, clipboard)) return false
    return ic.commitText("", 1)
}

private fun paste(
    ic: InputConnection,
    clipboard: ClipboardManager?,
): Boolean {
    if (ic.performContextMenuAction(android.R.id.paste)) return true
    val text =
        clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(null)
            ?.toString()
    if (text.isNullOrEmpty()) return false
    return ic.commitText(text, 1)
}

private fun undo(ic: InputConnection): Boolean {
    if (ic.performContextMenuAction(android.R.id.undo)) return true
    // The editor has no undo action of its own: the shortcut every editor that has one listens for.
    val now = System.currentTimeMillis()
    val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta)
    val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta)
    return ic.sendKeyEvent(down) && ic.sendKeyEvent(up)
}

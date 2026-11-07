package app.songyan.ime.ime.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import app.songyan.ime.ime.isSensitiveClip

/**
 * The editor side of a keyboard tool: what a tool may read, and how its result is written back.
 *
 * Implementations run on the main thread (they touch the input connection); the panel only calls
 * them from a touch or an accessibility action.
 */
interface KeyboardToolsEditor {
    /** The selected text, or null when nothing is selected or this editor may not run tools. */
    fun selection(): KeyboardToolText?

    /** The paragraph the caret is in, from its start to the caret; null when it is empty. */
    fun paragraph(): KeyboardToolText?

    /** What the clipboard holds, bounded like any other source; null when empty or unreadable. */
    fun clipboard(): String?

    /**
     * Writes [text] over exactly the source that was handed to the tool. False leaves the editor
     * untouched: the field changed, another editor is bound, or nothing is selected there anymore.
     */
    fun replace(
        source: KeyboardToolText,
        text: String,
    ): Boolean

    /** Puts [text] on the clipboard; false when there is no clipboard to write to. */
    fun copy(text: String): Boolean
}

/**
 * [KeyboardToolsEditor] over the keyboard's single editor connection.
 *
 * The rules are the tool rules: only the selection or the paragraph before the caret is read, never
 * the rest of the field; nothing is read in a private editor (a password field or one that asked for
 * no personalized learning); and a replacement is only written when the same editor still shows
 * exactly the text the tool was handed, because the user may have typed meanwhile.
 */
class EditorConnectionKeyboardTools(
    private val editor: EditorConnection,
    private val info: () -> EditorInfo?,
    private val policy: () -> EditorPolicy,
    private val clipboard: ClipboardManager?,
) : KeyboardToolsEditor {
    /** The editor and the exact text the last captured source came from. */
    private class Captured(
        val kind: KeyboardToolSourceKind,
        val text: String,
        val packageName: String?,
        val fieldId: Int,
    )

    private var captured: Captured? = null

    override fun selection(): KeyboardToolText? {
        if (!mayRead()) return null
        val selected =
            editor
                .connection()
                ?.getSelectedText(0)
                ?.toString()
                .orEmpty()
        if (selected.isEmpty() || selected.length > TOOL_TEXT_LIMIT) return null
        return capture(KeyboardToolSourceKind.SELECTION, selected)
    }

    override fun paragraph(): KeyboardToolText? {
        if (!mayRead()) return null
        // A paragraph is what a bare caret is in: the text after the last line break before it. With
        // a selection there is no caret to replace up to, so the selection is the only source.
        if (!editor.connection()?.getSelectedText(0).isNullOrEmpty()) return null
        // One unit past the limit tells a paragraph that fits from one the read window cut.
        val before = editor.contextBeforeCursor(TOOL_TEXT_LIMIT + 1) ?: return null
        val paragraph = before.substringAfterLast('\n')
        if (paragraph.isBlank() || paragraph.length > TOOL_TEXT_LIMIT) return null
        return capture(KeyboardToolSourceKind.PARAGRAPH, paragraph)
    }

    override fun clipboard(): String? {
        if (!mayRead()) return null
        // A clip the host marked sensitive (a copied password) is never read, as for the copied chip;
        // a locked or denied clipboard throws and must not close the panel.
        val text =
            runCatching {
                val manager = clipboard ?: return null
                if (manager.primaryClipDescription?.isSensitiveClip() == true) return null
                val clip = manager.primaryClip ?: return null
                if (clip.itemCount == 0) return null
                clip
                    .getItemAt(0)
                    ?.coerceToText(null)
                    ?.toString()
                    .orEmpty()
            }.getOrNull() ?: return null
        if (text.isEmpty() || text.length > TOOL_TEXT_LIMIT) return null
        return text
    }

    override fun replace(
        source: KeyboardToolText,
        text: String,
    ): Boolean {
        val expected = captured ?: return false
        if (expected.kind != source.kind || expected.text != source.text) return false
        val current = info() ?: return false
        if (current.packageName != expected.packageName || current.fieldId != expected.fieldId) return false
        if (!mayRead()) return false
        return when (source.kind) {
            KeyboardToolSourceKind.SELECTION -> editor.replaceSelection(source.text, text)
            KeyboardToolSourceKind.PARAGRAPH -> editor.replaceBeforeCursor(source.text, text)
            // A clipboard source never came from the editor, so it is only ever inserted or copied.
            KeyboardToolSourceKind.CLIPBOARD -> false
        }
    }

    override fun copy(text: String): Boolean {
        val manager = clipboard ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("", text))
        return true
    }

    private fun mayRead(): Boolean {
        if (editor.composing) return false
        if (policy().privateInput) return false
        return info() != null
    }

    private fun capture(
        kind: KeyboardToolSourceKind,
        text: String,
    ): KeyboardToolText {
        val current = info()
        captured = Captured(kind, text, current?.packageName, current?.fieldId ?: 0)
        return KeyboardToolText(text, kind)
    }

    companion object {
        /**
         * How much text a tool may be handed: one paragraph or one selection, never the document.
         * The panel shows the text it will process, so a longer source is refused rather than cut
         * silently; selecting less is the user's own way to narrow it.
         */
        const val TOOL_TEXT_LIMIT: Int = 1_000
    }
}

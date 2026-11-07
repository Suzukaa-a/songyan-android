package app.songyan.ime.ime

import android.content.ClipboardManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.os.Trace
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LocaleSpan
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.support.SettingsTarget
import java.text.BreakIterator

/**
 * The keyboard's only writer into the editor.
 *
 * The composition is marked text the user has not accepted: whenever it is abandoned (the last
 * letter deleted, cleared, an engine error, the cursor moved away, the keyboard hidden, the input
 * finished) it is removed from the editor rather than finished in place. Text only lands through
 * `commitText`. Only a composition the keyboard can no longer locate or no longer owns is unmarked
 * with `finishComposingText`. The field shows the engine's spaced reading so the user sees how the
 * letters were split; an editor that finishes the composition itself when input ends under it
 * (focus moves, the activity is recreated, the keyboard process dies) can leave that reading behind.
 */
class EditorConnection(
    internal val connection: () -> InputConnection?,
) {
    var composing = false
        private set

    /** The composition text this connection last wrote into the editor. */
    private var composingText = ""

    /**
     * Input language of the composition and its locale list, built only when the language changes.
     * The list is attached to the marked text as a LocaleSpan so a framework editor can pick that
     * language's glyph forms; every input language, simplified Chinese included, carries its own.
     */
    private var composingLanguage: InputLanguage? = null
    private var composingLocales: LocaleList? = null

    /** The editor is not a text editor (`TYPE_NULL`, terminals): it only understands key events. */
    var keyEventEditor = false
        private set

    /** Where the editor's selection should be after the keyboard's own edits. */
    val selection = EditorSelection()

    /** Where an extended selection of the edit panel is anchored; -1 when no extension is under way. */
    internal var selectionAnchor = -1

    /** The last selection report was not the keyboard's own edit: the user or the host moved the caret. */
    var selectionMovedExternally = false
        private set

    /**
     * The tail of the text before the cursor (composition included) as the keyboard last knew it, kept
     * in step with the keyboard's own edits so the automatic capital needs no editor round trip per
     * keystroke. Null when unknown: a cursor the user moved, a key event, a new editor.
     */
    private var textBefore: String? = null

    /** [textBefore] starts at the very beginning of the field, so nothing precedes it. */
    private var textBeforeComplete = false

    /** The editor of the running input, as the service handed it over. */
    private var startedInfo: EditorInfo? = null

    /** A composition the editor itself finished as plain text when its connection closed. */
    private var stranded: StrandedComposition? = null

    /** Overridable for tests. */
    internal var strandedWindowMs: Long = STRANDED_WINDOW_MS

    private data class StrandedComposition(
        val packageName: String?,
        val fieldId: Int,
        val end: Int,
        val text: String,
        val at: Long,
    )

    fun update(
        preedit: String,
        commits: List<String>,
        selectedCandidate: Boolean = false,
        language: InputLanguage = InputLanguage.ZH_HANS,
    ): Boolean {
        if (preedit.isEmpty() && commits.isEmpty()) {
            abandon()
            return true
        }
        val ic = connection() ?: return false
        var accepted = true
        val attribute =
            if (Build.VERSION.SDK_INT >= 37 && selectedCandidate) {
                TextAttribute.Builder().setTextSuggestionSelected(true).build()
            } else {
                null
            }
        val wasComposing = composing
        ic.beginBatchEdit()
        Trace.beginSection("Songyan.editorUpdate")
        try {
            for (text in commits) {
                // The 3-arg overload is API 33+; the attribute itself is only built on API 37+.
                if (Build.VERSION.SDK_INT >= 33 && attribute != null) {
                    accepted = ic.commitText(text, 1, attribute) && accepted
                } else {
                    accepted = ic.commitText(text, 1) && accepted
                }
            }
            if (preedit.isNotEmpty()) {
                if (language != composingLanguage) {
                    composingLanguage = language
                    composingLocales = LocaleList.forLanguageTags(language.languageTag)
                }
                val composingText = preeditWithLanguage(preedit)
                // Android 17: mark the composing text of a candidate selection for CJKV
                // accessibility; the flag is only meaningful on that platform, so fail closed.
                if (Build.VERSION.SDK_INT >= 33 && attribute != null) {
                    ic.setComposingText(composingText, 1, attribute)
                } else {
                    ic.setComposingText(composingText, 1)
                }
            } else if (composing && commits.isEmpty()) {
                ic.setComposingText("", 1)
            }
            followText(if (wasComposing) composingText.length else 0, commits.joinToString("") + preedit)
            composing = preedit.isNotEmpty()
            composingText = preedit
            selection.afterUpdate(commits, preedit, wasComposing)
        } finally {
            Trace.endSection()
            ic.endBatchEdit()
        }
        return accepted
    }

    /**
     * [preedit] with the composition language attached: a framework editor applies the span's
     * TextPaint locale while it measures and draws the marked range, so that language's glyph
     * forms are used. Only text a language's fonts render differently needs that: an ASCII
     * spelling reads the same in every language, so it goes over as the engine's own string
     * instead of a wrapped CharSequence built on every keystroke. An editor with its own text
     * engine may drop the spans anyway.
     */
    private fun preeditWithLanguage(preedit: String): CharSequence {
        if (preedit.all { it.code < 0x80 }) return preedit
        val locales = composingLocales ?: return preedit
        return SpannableString(preedit).apply {
            setSpan(LocaleSpan(locales), 0, preedit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Removes the composition from the editor: the spelling was never accepted, so it must not stay
     * behind as text. A no-op when nothing is composed.
     */
    fun abandon() {
        if (!composing) return
        val ic = connection()
        val written = composingText
        val removed = written.length
        composing = false
        composingText = ""
        if (ic != null) {
            // Removing the marked region is safe only while the editor still holds it; an editor
            // that dropped it would take the empty text as a replacement of a non-empty selection.
            val selected = selection.known && selection.start != selection.end
            if (!selected || selection.composingStart >= 0) {
                // Hiding the keyboard can end the editor's composing region before this call
                // arrives (seen in Compose fields and Chrome), and clearing a region that is gone
                // clears nothing. When the text before the cursor did not change, the spelling is
                // still there unmarked: delete exactly what was written. Only when the editor
                // last reported the composition as its own: without that, text that merely reads
                // the same is the user's, not the keyboard's.
                val ours = selection.known && selection.composingStart >= 0
                val span = written.length + 1
                val before = if (ours) ic.getTextBeforeCursor(span, 0)?.toString() else null
                ic.setComposingText("", 1)
                if (before != null &&
                    before.endsWith(written) &&
                    ic.getTextBeforeCursor(span, 0)?.toString() == before
                ) {
                    ic.deleteSurroundingText(written.length, 0)
                }
                followText(removed, "")
            } else {
                forgetText()
            }
        } else {
            forgetText()
        }
        selection.afterUpdate(emptyList(), "", true)
    }

    /**
     * A new editor (or the same one restarted). A restarted editor keeps the composition only while
     * it still shows exactly the keyboard's text right before the cursor; the caller must then keep
     * the engine composition too. Returns whether the composition survives.
     *
     * A composition the keyboard does not continue is unmarked in the new input: whoever marked it
     * (this keyboard before its process died, or an editor that kept the region of a closed
     * connection) is gone, and left marked it would be replaced by the next letter typed.
     */
    fun startInput(
        info: EditorInfo?,
        restarting: Boolean,
    ): Boolean {
        keyEventEditor =
            info != null &&
            EditorPolicy.effectiveInputType(info.inputType) and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        BackspaceTrace.editorPackage = info?.packageName
        forgetText()
        // The input method restarts its own input for a configuration change with the EditorInfo it
        // already holds (`InputMethodService.resetStateForNewConfiguration`): the editor was not
        // restarted and still shows the composition, but that EditorInfo's selection is the one from
        // when input began, and the editor may not answer reads while it handles the change itself.
        val ownRestart = restarting && info != null && info === startedInfo
        startedInfo = info
        if (ownRestart && composing) return true
        val initialStart = info?.initialSelStart ?: EditorSelection.UNKNOWN
        val initialEnd = info?.initialSelEnd ?: EditorSelection.UNKNOWN
        if (restarting && composing && keepsComposition(initialStart, initialEnd)) {
            val start = initialEnd - composingText.length
            connection()?.setComposingRegion(start, initialEnd)
            selection.reset(start, start)
            selection.afterUpdate(emptyList(), composingText, false)
            return true
        }
        val wasComposing = composing
        // Whatever the old connection held was finished by the editor when it closed.
        composing = false
        composingText = ""
        selection.reset(initialStart, initialEnd)
        if (!restarting) removeStranded(info, initialStart, initialEnd)
        if (!restarting || wasComposing) connection()?.finishComposingText()
        return false
    }

    private fun keepsComposition(
        initialStart: Int,
        initialEnd: Int,
    ): Boolean {
        if (initialStart != initialEnd || initialEnd < composingText.length) return false
        if (selection.composingEnd != EditorSelection.UNKNOWN && selection.composingEnd != initialEnd) return false
        val before = connection()?.getTextBeforeCursor(composingText.length, 0)?.toString()
        return before == composingText
    }

    /**
     * The input is finishing. The editor finishes any composition itself when its connection
     * closes (`BaseInputConnection.closeConnection`), often before this call can remove it, for
     * example when the activity is recreated for a rotation or a dark-mode switch. Remember what was
     * composed so the same field can drop it when it comes back with that text before its cursor.
     */
    fun finishInput(info: EditorInfo?) {
        if (composing && info != null) {
            // The end is unknown (-1) when the editor never reported its composing region.
            stranded =
                StrandedComposition(
                    packageName = info.packageName,
                    fieldId = info.fieldId,
                    end = selection.composingEnd,
                    text = composingText,
                    at = SystemClock.uptimeMillis(),
                )
        }
        abandon()
    }

    /**
     * Deletes the spelling a closing connection left as plain text: only in the same field, only
     * shortly after, and only when the cursor sits exactly at its end with exactly that text before
     * it. Anything less certain stays untouched, since it may be the user's own text. When the
     * composing end was never reported the field identity alone (often 0 or -1 for web and Compose
     * fields) cannot tell the spelling from the user's text, so nothing is deleted: the text is only
     * unmarked, which is harmless if it was never composing.
     */
    private fun removeStranded(
        info: EditorInfo?,
        initialStart: Int,
        initialEnd: Int,
    ) {
        val left = stranded ?: return
        stranded = null
        if (info == null) return
        if (info.packageName != left.packageName || info.fieldId != left.fieldId) return
        if (initialStart != initialEnd || (left.end >= 0 && initialEnd != left.end)) return
        if (initialEnd < left.text.length) return
        val ic = connection() ?: return
        val before = ic.getTextBeforeCursor(left.text.length, 0)?.toString()
        if (before != left.text) return
        // Too long ago, or the composing end was never known, to be sure the text is not the user's
        // own: it stays, unmarked by the caller.
        if (left.end >= 0 && SystemClock.uptimeMillis() - left.at <= strandedWindowMs) {
            if (ic.deleteSurroundingText(left.text.length, 0)) selection.shifted(-left.text.length)
        }
        forgetText()
    }

    /**
     * A selection report from the editor. Returns true when the user moved the cursor away from the
     * composition: it has then been removed from the editor (the cursor kept where the user put it)
     * and the caller must discard the engine composition.
     */
    fun onUpdateSelection(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Boolean {
        val expected = selection.isExpected(oldStart, oldEnd, newStart, newEnd)
        selectionMovedExternally = !expected
        // Whoever moved the cursor may also have changed the text before it.
        if (!expected) forgetText()
        if (expected) {
            if (composing) selection.reportedComposing(candidatesStart, candidatesEnd)
            return false
        }
        if (!composing) {
            selection.moved(newStart, newEnd)
            return false
        }
        // An editor that does not report its composing region (-1) has not ended the composition;
        // only the cursor position tells.
        val regionStart = if (candidatesStart >= 0) candidatesStart else selection.composingStart
        val regionEnd = if (candidatesEnd >= 0) candidatesEnd else selection.composingEnd
        if (newStart == newEnd && regionEnd >= 0 && newEnd == regionEnd) {
            selection.moved(newStart, newEnd)
            selection.reportedComposing(candidatesStart, candidatesEnd)
            return false
        }
        val ic = connection()
        val written = composingText
        composing = false
        composingText = ""
        if (ic == null || regionStart < 0 || regionEnd < regionStart) {
            // Where the composition is cannot be told, so it is not removed; unmarked, it stays put
            // instead of being replaced by the next letter typed wherever the cursor now is.
            ic?.finishComposingText()
            selection.forget()
            return true
        }
        val start = EditorSelection.afterRemoving(newStart, regionStart, regionEnd)
        val end = EditorSelection.afterRemoving(newEnd, regionStart, regionEnd)
        ic.beginBatchEdit()
        try {
            if (candidatesStart < 0) {
                // The editor did not confirm its composing region: mark it again first, and only
                // where it still holds exactly the keyboard's text.
                if (!holdsText(ic, regionStart, regionEnd, written)) {
                    ic.finishComposingText()
                    selection.moved(newStart, newEnd)
                    return true
                }
                ic.setComposingRegion(regionStart, regionEnd)
            }
            ic.setComposingText("", 1)
            ic.setSelection(start, end)
        } finally {
            ic.endBatchEdit()
        }
        selection.reset(start, end)
        return true
    }

    private fun holdsText(
        ic: InputConnection,
        start: Int,
        end: Int,
        expected: String,
    ): Boolean {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = extracted.text ?: return false
        val from = start - extracted.startOffset
        val to = end - extracted.startOffset
        return from >= 0 && to <= text.length && from <= to && text.subSequence(from, to).toString() == expected
    }

    /**
     * Deletes one user-perceived character. A code point is not enough: a family or skin-tone emoji
     * is several code points, and deleting one of them leaves a broken glyph behind.
     *
     * An editor that is not a text editor, or that has no text before the cursor, gets a Delete key
     * event instead: it may still delete something the keyboard cannot see (a terminal's line, a
     * recipient chip), exactly as with a hardware keyboard.
     */
    fun delete() {
        val ic = connection()
        if (ic == null) {
            BackspaceTrace.record(BackspaceTrace.Event.EDITOR_NO_CONNECTION, composing)
            return
        }
        if (keyEventEditor) {
            BackspaceTrace.record(BackspaceTrace.Event.EDITOR_KEY_EVENT_FOR_KEY_EVENT_EDITOR, composing)
            sendKey(ic, KeyEvent.KEYCODE_DEL)
            selection.forget()
            forgetText()
            return
        }
        // A blocking round trip per delete (and per repeat) only to learn what the keyboard already
        // knows: when the editor's reported selection is a known caret, nothing is selected. An editor
        // that does not report its selection stays unknown here, so it is still asked.
        val caretKnown = selection.known && selection.start == selection.end
        if (!caretKnown && !ic.getSelectedText(0).isNullOrEmpty()) {
            BackspaceTrace.record(BackspaceTrace.Event.EDITOR_SELECTION_REMOVED, composing)
            ic.commitText("", 1)
            selection.shifted(0)
            return
        }
        val before = ic.getTextBeforeCursor(GRAPHEME_WINDOW, 0)?.toString()
        if (before.isNullOrEmpty()) {
            BackspaceTrace.record(BackspaceTrace.Event.EDITOR_KEY_EVENT_NO_TEXT_BEFORE, composing, caretKnown)
            sendKey(ic, KeyEvent.KEYCODE_DEL)
            selection.forget()
            forgetText()
            return
        }
        val codePoints = trailingClusterCodePoints(before)
        val units = before.length - before.offsetByCodePoints(before.length, -codePoints)
        // In UTF-16 units, as AOSP LatinIME deletes: the read above already measured the cluster, and
        // every editor implements this call. The code-point variant is newer; an editor whose own
        // connection lacks it drops the call silently in the framework (the AbstractMethodError is
        // swallowed), so the press would delete nothing.
        BackspaceTrace.record(BackspaceTrace.Event.EDITOR_DELETED_BEFORE_CURSOR, composing, caretKnown)
        ic.deleteSurroundingText(units, 0)
        // This read is the freshest view of the text there is: keep what it shows, minus the deletion.
        seedText(before, selection.start.takeIf { selection.known && selection.start == selection.end })
        followText(units, "")
        selection.shifted(-units)
    }

    /**
     * Replaces [expected] with [replacement] in one batch edit, only when the editor shows exactly
     * [expected] right before a bare caret; a selection would be overwritten by the replacement too.
     * False leaves the text untouched.
     */
    fun replaceBeforeCursor(
        expected: String,
        replacement: String,
    ): Boolean {
        val ic = connection() ?: return false
        if (keyEventEditor || composing || expected.isEmpty()) return false
        ic.beginBatchEdit()
        try {
            if (!ic.getSelectedText(0).isNullOrEmpty()) return false
            if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
            deleteBeforeCursor(expected.length)
            update("", listOf(replacement))
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    /**
     * Replaces the selected text with [replacement] in one edit, only when the editor still selects
     * exactly [expected]. False leaves the editor untouched.
     */
    fun replaceSelection(
        expected: String,
        replacement: String,
    ): Boolean {
        val ic = connection() ?: return false
        if (keyEventEditor || composing || expected.isEmpty()) return false
        ic.beginBatchEdit()
        try {
            if (ic.getSelectedText(0)?.toString() != expected) return false
            if (!ic.commitText(replacement, 1)) return false
            selection.forget()
            forgetText()
            return true
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Deletes [units] UTF-16 units before the caret in one edit (a typed word replaced by a suggestion). */
    fun deleteBeforeCursor(units: Int) {
        val ic = connection() ?: return
        if (keyEventEditor || units <= 0) return
        ic.deleteSurroundingText(units, 0)
        followText(units, "")
        selection.shifted(-units)
    }

    /**
     * What `getCursorCapsMode(inputType)` would answer, usually without asking the editor: the platform
     * computes it from the text before the cursor, which the keyboard follows through its own edits.
     * The editor is read only to learn that text (once per cursor jump, one read instead of one
     * capitalisation query), or when the known tail is too short to decide.
     */
    fun automaticCapsMode(inputType: Int): Int {
        val modes = inputType and CAPS_MODES
        // The platform derives nothing from a field that asked for no capitals.
        if (modes == 0) return 0
        val ic = connection() ?: return 0
        if (textBefore == null) {
            val before = ic.getTextBeforeCursor(CAPS_WINDOW, 0)?.toString()
            if (before != null) seedText(before, if (selection.known) minOf(selection.start, selection.end) else null)
        }
        val known = textBefore
        if (known != null) {
            val decided = decidedCapsMode(known, textBeforeComplete, modes)
            if (decided != null) return decided
        }
        return ic.getCursorCapsMode(inputType)
    }

    /** Learns [text] as the tail before the cursor; [cursor] (the absolute offset) tells whether it is all of it. */
    private fun seedText(
        text: String,
        cursor: Int?,
    ) {
        textBeforeComplete = cursor != null && cursor == text.length
        textBefore = text
        trimText()
    }

    /** The keyboard's own edit: the last [removed] chars before the cursor became [added]. */
    private fun followText(
        removed: Int,
        added: String,
    ) {
        val known = textBefore ?: return
        if (removed > known.length) {
            forgetText()
            return
        }
        // A deletion that eats into the known tail leaves the rest unchanged but shorter: still the tail.
        textBefore = known.substring(0, known.length - removed) + added
        trimText()
    }

    private fun trimText() {
        val known = textBefore ?: return
        if (known.length > CAPS_WINDOW) {
            textBefore = known.substring(known.length - CAPS_WINDOW)
            textBeforeComplete = false
        }
    }

    internal fun forgetText() {
        textBefore = null
        textBeforeComplete = false
    }

    /** Bounded text before the selection: the nine-key symbol cycle checks its last write. */
    fun contextBeforeCursor(limit: Int): String? {
        val ic = connection() ?: return null
        return ic.getTextBeforeCursor(limit, 0)?.toString()
    }

    /**
     * Delivers a plain Return, for an editor that asked for no action
     * ([EditorInfo.IME_ACTION_NONE]) or left the action unspecified.
     *
     * [newlineAsText] picks the delivery. A multi-line editor gets the line break as text, which is
     * ordered with every commit before it and cannot be lost. Any other editor gets an Enter key
     * event, the delivery the platform's own `InputMethodService.sendKeyChar` falls back to when there
     * is no action to perform, so a host that listens for the Enter key sees the press instead of a
     * stray newline character in its text.
     *
     * The event's return value is not read: `BaseInputConnection.sendKeyEvent` dispatches the event
     * and reports `false` regardless, so treating it as "not handled" would insert the line break
     * twice.
     *
     * @return whether the Return reached the editor; a key event counts even though it writes no
     * text itself, and a missing connection is the only false.
     */
    fun sendReturn(newlineAsText: Boolean): Boolean {
        val ic = connection() ?: return false
        if (newlineAsText && !keyEventEditor) {
            val accepted = ic.commitText("\n", 1)
            selection.shifted(1)
            followText(0, "\n")
            return accepted
        }
        sendKey(ic, KeyEvent.KEYCODE_ENTER)
        selection.forget()
        forgetText()
        return true
    }

    /**
     * Moves the cursor by [delta] user-perceived characters (negative = left) with `setSelection`
     * from the known selection, so the editor never receives arrow keys it could read as navigation
     * (leaving the field, moving between lines). A key-event editor gets the arrow keys it expects.
     */
    fun moveCursor(delta: Int) {
        if (delta == 0) return
        val ic = connection() ?: return
        abandon()
        if (keyEventEditor) {
            val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(delta)) { sendKey(ic, key) }
            forgetText()
            return
        }
        if (!selection.known) return
        val window = kotlin.math.abs(delta) * GRAPHEME_WINDOW
        val target =
            if (delta < 0) {
                val before = ic.getTextBeforeCursor(window, 0)?.toString() ?: return
                minOf(selection.start, selection.end) - clusterUnits(before, -delta, fromEnd = true)
            } else {
                val after = ic.getTextAfterCursor(window, 0)?.toString() ?: return
                maxOf(selection.start, selection.end) + clusterUnits(after, delta, fromEnd = false)
            }
        if (ic.setSelection(target, target)) {
            selection.moved(target, target)
            forgetText()
        }
    }

    /**
     * A soft-keyboard key press, shaped like `InputMethodService.sendDownUpKeyEvents`: real event
     * times, the virtual keyboard device, and [KeyEvent.FLAG_SOFT_KEYBOARD] so the editor knows it
     * did not come from hardware.
     */
    private fun sendKey(
        ic: InputConnection,
        code: Int,
    ) {
        val down = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ic.sendKeyEvent(
            KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags),
        )
        ic.sendKeyEvent(
            KeyEvent(
                down,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                code,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
            ),
        )
    }

    companion object {
        /** How far back the last grapheme cluster is measured; the editor reports at most this much. */
        const val GRAPHEME_WINDOW: Int = 32

        /** How much of the text before the cursor is kept to derive the automatic capital. */
        const val CAPS_WINDOW: Int = 64

        private const val CAPS_MODES =
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        /** Stand-ins for whatever may precede a truncated tail: a paragraph, a word, a sentence end. */
        private val UNSEEN_PREFIXES = listOf("\n", "x", ".", " ", "x.", "x. ", "?", "\"")

        /**
         * The caps mode of [tail] (the text before the cursor), or null when text that is not in
         * [tail] could still change it. A tail starting the field decides alone; a truncated one
         * decides only when every kind of earlier text gives the same answer.
         */
        fun decidedCapsMode(
            tail: String,
            complete: Boolean,
            modes: Int,
        ): Int? {
            val mode = TextUtils.getCapsMode(tail, tail.length, modes)
            if (complete) return mode
            for (prefix in UNSEEN_PREFIXES) {
                if (TextUtils.getCapsMode(prefix + tail, prefix.length + tail.length, modes) != mode) return null
            }
            return mode
        }

        /** How long after a finished input a stranded spelling may still be recognised. */
        const val STRANDED_WINDOW_MS: Long = 10_000L

        /**
         * Code points of the last extended grapheme cluster of [before], bounded by the read window.
         *
         * A cluster that reaches the window's start cannot be measured from a truncated read, so it
         * fails closed to one code point - the previous behaviour, never a guess that could delete
         * more text than the user asked for.
         */
        fun trailingClusterCodePoints(before: String?): Int {
            if (before.isNullOrEmpty()) return 1
            val boundary = BreakIterator.getCharacterInstance()
            boundary.setText(before)
            val end = boundary.last()
            val start = boundary.previous()
            if (start == BreakIterator.DONE || end == BreakIterator.DONE) return 1
            if (start == 0 && before.length >= GRAPHEME_WINDOW) return 1
            return before.codePointCount(start, end).coerceAtLeast(1)
        }

        /** UTF-16 length of [count] grapheme clusters at the end (or start) of [text], bounded by it. */
        fun clusterUnits(
            text: String,
            count: Int,
            fromEnd: Boolean,
        ): Int {
            if (text.isEmpty() || count <= 0) return 0
            val boundary = BreakIterator.getCharacterInstance()
            boundary.setText(text)
            var position = if (fromEnd) boundary.last() else boundary.first()
            repeat(count) {
                val next = if (fromEnd) boundary.previous() else boundary.next()
                if (next == BreakIterator.DONE) return if (fromEnd) text.length - position else position
                position = next
            }
            return if (fromEnd) text.length - position else position
        }
    }
}

/**
 * The keyboard's editor callbacks over one [EditorConnection]: the single wiring the input method
 * service and the editor tests share, so a test exercises exactly what users get.
 */
fun EditorConnection.keyboardCallbacks(
    action: () -> Unit,
    onCommitted: (String) -> Unit = {},
    panelsChanged: (Boolean) -> Unit = {},
    openSettings: () -> Unit = {},
    hideKeyboard: () -> Unit = {},
    automaticCapsWanted: () -> Unit = {},
    openSettingsPage: (SettingsTarget) -> Unit = {},
    openToolsSettings: () -> Unit = {},
    clipboard: ClipboardManager? = null,
    inputMethods: InputMethodAccess = InputMethodAccess.NONE,
    engineNoticeAction: (EngineNoticeAction) -> Unit = {},
): KeyboardController.Callbacks =
    KeyboardController.Callbacks(
        commit = { text, selected -> update("", listOf(text), selected).also { if (it) onCommitted(text) } },
        preedit = { text, language -> update(text, emptyList(), language = language) },
        delete = { delete() },
        moveCursor = { moveCursor(it) },
        action = action,
        error = { abandon() },
        panelsChanged = panelsChanged,
        openSettings = openSettings,
        hideKeyboard = hideKeyboard,
        openSettingsPage = openSettingsPage,
        openToolsSettings = openToolsSettings,
        contextBeforeCursor = { contextBeforeCursor(it) },
        automaticCapsWanted = automaticCapsWanted,
        deleteBefore = { deleteBeforeCursor(it) },
        replaceBeforeCursor = { expected, replacement ->
            replaceBeforeCursor(expected, replacement).also { if (it) onCommitted(replacement) }
        },
        editAction = { action, extend, mayRead -> performEdit(action, extend, clipboard, mayRead) },
        editAvailability = { mayRead -> editAvailability(clipboard, mayRead) },
        inputMethods = inputMethods,
        engineNoticeAction = engineNoticeAction,
    )

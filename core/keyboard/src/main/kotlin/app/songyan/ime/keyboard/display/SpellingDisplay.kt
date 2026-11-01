package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.TextSelection

/**
 * Replaces only the engine-reported unconfirmed highlighted segment with the highlighted
 * candidate's real spelling.
 *
 * Both inputs come from the engine and are never guessed in the front end: the range is librime's
 * `sel_start`/`sel_end` (UTF-8 byte offsets into [EngineSnapshot.preedit]) and the spelling is the
 * canonical pinyin the bridge decodes from the candidate's own syllable codes. Anything outside the
 * range (confirmed text, punctuation, tail code) and the caret position stay as reported.
 */
object SpellingDisplay {
    /**
     * Replaces `[selStart, selEnd)` (UTF-8 bytes) of [preedit] with [spelling] and remaps
     * [caretByte] into the result (returned as a UTF-16 offset).
     *
     * Returns null when the rewrite cannot be done safely (out of bounds, not on a UTF-8 boundary);
     * callers must then fall back to the engine preedit.
     *
     * @param droppingTail drops `[selEnd, end)`. Used for the authorized 9-key `canonical`
     * capability: librime's text after the highlighted segment is always the not-yet-converted raw
     * digit code, which must not be shown; the confirmed prefix is kept.
     */
    fun rewrite(
        preedit: String,
        selStart: Int,
        selEnd: Int,
        caretByte: Int,
        spelling: String,
        droppingTail: Boolean = false,
    ): Pair<String, Int>? {
        val byteCount = preedit.toByteArray(Charsets.UTF_8).size
        if (selStart < 0 || selEnd < selStart || selEnd > byteCount) return null
        if (caretByte < 0 || caretByte > byteCount) return null
        val startIndex = utf16IndexAtByteOffset(preedit, selStart) ?: return null
        val endIndex = utf16IndexAtByteOffset(preedit, selEnd) ?: return null
        val caretIndex = utf16IndexAtByteOffset(preedit, caretByte) ?: return null

        val prefix = preedit.substring(0, startIndex)
        val suffix = if (droppingTail) "" else preedit.substring(endIndex)
        val prefixBytes = prefix.toByteArray(Charsets.UTF_8).size
        val replacementBytes = spelling.toByteArray(Charsets.UTF_8).size
        val coveredBytes = selEnd - selStart
        val newCaretByte =
            when {
                caretByte <= selStart -> caretByte
                droppingTail -> prefixBytes + replacementBytes
                caretByte >= selEnd -> caretByte + (replacementBytes - coveredBytes)
                else -> selStart + replacementBytes
            }
        val text = prefix + spelling + suffix
        val clamped = newCaretByte.coerceIn(0, text.toByteArray(Charsets.UTF_8).size)
        val caret = utf16IndexAtByteOffset(text, clamped) ?: return null
        return text to caret
    }

    /**
     * [spelling] for the preedit range [span], keeping the separators the user typed there: librime
     * keeps a typed `'` in the preedit as it was typed and only adds a space where it split syllables
     * itself. A spelling that dropped the user's `'` would make the key that typed it, and the Delete
     * that removes it, change nothing on screen.
     *
     * Where [span] and [spelling] have the same number of syllables, each boundary the user marked
     * with `'` gets `'`; a `'` typed after the last syllable is kept at the end either way.
     */
    fun withTypedSeparators(
        span: String,
        spelling: String,
    ): String {
        if (spelling.isEmpty() || TYPED_SEPARATOR !in span) return spelling
        val spanRuns = separatorRuns(span)
        val spellingRuns = separatorRuns(spelling)
        val text = StringBuilder()
        var last = 0
        if (spanRuns.inner.size == spellingRuns.inner.size) {
            for ((index, run) in spellingRuns.inner.withIndex()) {
                text.append(spelling, last, run.first)
                val typed = TYPED_SEPARATOR in spanRuns.inner[index].let { span.substring(it.first, it.last + 1) }
                text.append(if (typed) TYPED_SEPARATOR.toString() else spelling.substring(run.first, run.last + 1))
                last = run.last + 1
            }
        }
        text.append(spelling, last, spelling.length)
        val typedTrailing = spanRuns.trailing?.let { TYPED_SEPARATOR in span.substring(it.first, it.last + 1) } == true
        if (typedTrailing && !text.endsWith(TYPED_SEPARATOR)) text.append(TYPED_SEPARATOR)
        return text.toString()
    }

    private const val TYPED_SEPARATOR = '\''

    private class SeparatorRuns(
        val inner: List<IntRange>,
        val trailing: IntRange?,
    )

    /** The runs of separators between syllables, and the run that ends [text], if any. */
    private fun separatorRuns(text: String): SeparatorRuns {
        val runs = ArrayList<IntRange>()
        var index = 0
        while (index < text.length) {
            if (!isSeparator(text[index])) {
                index++
                continue
            }
            val start = index
            while (index < text.length && isSeparator(text[index])) index++
            runs += start until index
        }
        val leading = runs.firstOrNull()?.takeIf { it.first == 0 }
        val trailing = runs.lastOrNull()?.takeIf { it.last == text.length - 1 && it != leading }
        return SeparatorRuns(runs.filter { it != leading && it != trailing }, trailing)
    }

    private fun isSeparator(char: Char): Boolean = char == ' ' || char == TYPED_SEPARATOR

    /** UTF-16 index of the character that starts at UTF-8 [byteOffset]; null when not on a boundary. */
    internal fun utf16IndexAtByteOffset(
        text: String,
        byteOffset: Int,
    ): Int? {
        if (byteOffset < 0) return null
        var bytes = 0
        var index = 0
        while (index < text.length) {
            if (bytes == byteOffset) return index
            val char = text[index]
            val charCount = if (char.isHighSurrogate() && index + 1 < text.length) 2 else 1
            val codePoint =
                if (charCount == 2) {
                    val low = text[index + 1]
                    if (low.isLowSurrogate()) {
                        Character.toCodePoint(char, low)
                    } else {
                        return null
                    }
                } else {
                    char.code
                }
            bytes += utf8Length(codePoint)
            if (bytes > byteOffset) return null
            index += charCount
        }
        return if (bytes == byteOffset) text.length else null
    }

    private fun utf8Length(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
}

/**
 * The value handed to the host as marked text (Android: `setComposingText`). Derived from engine
 * evidence only.
 *
 * For a `canonical` (9-key numeric) schema ([EngineSnapshot.hidesNumericTransportCode]) the text is
 * the confirmed prefix plus the resolved canonical display of the highlighted span, then the keys
 * typed after that span. Nothing typed is ever left out: an empty or shortened mark made presses
 * look swallowed.
 */
data class MarkedComposition(
    val text: String,
    val selection: TextSelection,
) {
    /**
     * @param spellingPreedit legacy entry point (demo/legacy snapshots): applies only when the
     * snapshot has never declared a `keyboard_adapter` capability, so a failed capability cannot leak
     * canonical full pinyin.
     * @param readingFallback read-only reading of the highlighted candidate proven by the engine;
     * used when the capability yields no display value instead of falling back to a merged key code.
     * @param keypadTail keys typed after the highlighted span, spelled by the caller where it can.
     * @param exactLetters exact-pick token -> its letter, so a lone exact pick spells back as the letter.
     */
    constructor(
        snapshot: EngineSnapshot,
        spellingPreedit: Boolean = false,
        readingFallback: String = "",
        keypadTail: String = "",
        exactLetters: Map<Char, Char> = emptyMap(),
    ) : this(
        text = resolveText(snapshot, spellingPreedit, readingFallback, keypadTail, exactLetters).first,
        selection =
            TextSelection(
                resolveText(snapshot, spellingPreedit, readingFallback, keypadTail, exactLetters).second,
            ),
    )

    companion object {
        private fun resolveText(
            snapshot: EngineSnapshot,
            spellingPreedit: Boolean,
            readingFallback: String,
            keypadTail: String,
            exactLetters: Map<Char, Char>,
        ): Pair<String, Int> {
            val display = if (snapshot.displayMode != CompositionDisplayMode.NATIVE) snapshot.highlightedDisplay else ""
            val legacy =
                if (display.isEmpty() && spellingPreedit && !snapshot.hasDisplayCapability) {
                    snapshot.highlightedSpelling
                } else {
                    ""
                }
            val replacement = display.ifEmpty { legacy.ifEmpty { readingFallback } }
            if (snapshot.hidesNumericTransportCode) {
                return keypadText(snapshot, replacement, keypadTail)
            }
            if (replacement.isNotEmpty()) {
                val span = span(snapshot.preedit, snapshot.preeditSelStart, snapshot.preeditSelEnd).orEmpty()
                val rewritten =
                    SpellingDisplay.rewrite(
                        preedit = snapshot.preedit,
                        selStart = snapshot.preeditSelStart,
                        selEnd = snapshot.preeditSelEnd,
                        caretByte = snapshot.preeditCaretByte,
                        spelling = SpellingDisplay.withTypedSeparators(span, replacement),
                    )
                if (rewritten != null) return rewritten
            }
            val text = snapshot.preedit.map { exactLetters[it] ?: it }.joinToString("")
            return text to snapshot.preeditSelection.start
        }

        /**
         * The 9-key mark: the confirmed prefix, [spelling] for the highlighted span or, when empty,
         * the span's keys, then the spelled keys after the span, joined with `'`.
         */
        fun keypadText(
            snapshot: EngineSnapshot,
            spelling: String,
            spelledTail: String = "",
        ): Pair<String, Int> {
            val bytes = snapshot.preedit.toByteArray(Charsets.UTF_8)
            val selStart = snapshot.preeditSelStart
            val selEnd = snapshot.preeditSelEnd

            fun keys(
                from: Int,
                until: Int,
            ): String {
                if (from < 0 || until > bytes.size || from > until) return ""
                return String(bytes, from, until - from, Charsets.UTF_8).filterNot { it.isWhitespace() }
            }
            val rewritten =
                run {
                    if (selStart < 0 || selStart > selEnd || selEnd > bytes.size) return@run null
                    val spanKeys = keys(selStart, selEnd)
                    val span = span(snapshot.preedit, selStart, selEnd).orEmpty()
                    SpellingDisplay.rewrite(
                        preedit = snapshot.preedit,
                        selStart = selStart,
                        selEnd = selEnd,
                        caretByte = snapshot.preeditCaretByte,
                        spelling =
                            if (spelling.isEmpty()) {
                                spanKeys
                            } else {
                                SpellingDisplay.withTypedSeparators(
                                    span,
                                    spelling,
                                )
                            },
                        droppingTail = true,
                    )
                }
            if (rewritten == null) {
                val text = snapshot.preedit.filterNot { it.isWhitespace() }
                return text to text.length
            }
            val rawTail = keys(selEnd, bytes.size)
            val tail = if (rawTail.isEmpty() || spelledTail.isEmpty()) rawTail else spelledTail
            val last = rewritten.first.lastOrNull()
            val joins = last != null && last.isAscii() && (last.isLetter() || last.isDigit())
            val letters = last?.isLetter() == true || tail.firstOrNull()?.isLetter() == true
            val separator = if (tail.isNotEmpty() && joins && letters) "'" else ""
            val text = rewritten.first + separator + tail
            val caret = if (snapshot.preeditCaretByte >= selEnd) text.length else rewritten.second
            return text to caret
        }

        private fun Char.isAscii(): Boolean = this.code < 0x80

        /** The preedit text between two UTF-8 offsets; null when they are not character boundaries. */
        private fun span(
            preedit: String,
            startByte: Int,
            endByte: Int,
        ): String? {
            if (startByte > endByte) return null
            val from = SpellingDisplay.utf16IndexAtByteOffset(preedit, startByte) ?: return null
            val to = SpellingDisplay.utf16IndexAtByteOffset(preedit, endByte) ?: return null
            return preedit.substring(from, to)
        }
    }
}

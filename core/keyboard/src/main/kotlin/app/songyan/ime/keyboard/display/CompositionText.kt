package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.ReadingCandidate

/**
 * The one composition string the editor field and the candidate bar show while composing. Display
 * only: commits always come from the engine.
 *
 * The engine preedit of a compact layout is a key code (`64426` on the keypad, merged-key letters on
 * 14 / 18 keys), so the highlighted span is replaced with the schema's declared display value or the
 * candidate's proven reading, a keypad sentence without either is spelled from its key segments,
 * and the result is trimmed to what was actually typed (one key reads `n`, not the top candidate's
 * `na`). A 26-key layout shows the engine preedit unchanged.
 */
object CompositionText {
    /**
     * @param compactLayout the current physical layout is a proven compact spelling layout.
     * @param readingKeyMap letter -> the key that types it, from the layout's own key labels.
     * @param exactLetters exact-pick token -> its letter (merged-key swipes).
     */
    fun marked(
        snapshot: EngineSnapshot,
        compactLayout: Boolean,
        readingKeyMap: Map<Char, Char>,
        exactLetters: Map<Char, Char> = emptyMap(),
        readings: List<ReadingCandidate> = emptyList(),
        knownReadings: Map<Char, List<String>> = emptyMap(),
    ): String {
        if (!snapshot.isComposing) return ""
        val keypad = snapshot.hidesNumericTransportCode
        val letters = if (snapshot.isAsciiMode) emptyMap() else exactLetters
        // A keypad reading that still carries digits would echo the transport code.
        val proven = snapshot.highlightedReading.takeUnless { keypad && it.any(Char::isDigit) }.orEmpty()
        val verified =
            if (proven.isNotEmpty() && compactLayout) {
                typedReading(proven, snapshot.rawInput, keypad, readingKeyMap, letters)
            } else {
                proven
            }
        val reading =
            when {
                verified.isNotEmpty() -> verified
                keypad && compactLayout -> sentenceSpelling(snapshot, readingKeyMap, readings, knownReadings).orEmpty()
                else -> ""
            }
        val usesSpellingPreedit =
            snapshot.displayMode != CompositionDisplayMode.NATIVE && snapshot.highlightedDisplay.isNotEmpty()
        val marked =
            MarkedComposition(
                snapshot = snapshot,
                spellingPreedit = usesSpellingPreedit,
                readingFallback = if (keypad) keypadJoined(reading) else reading,
                keypadTail =
                    if (keypad && compactLayout) {
                        keypadTailSpelling(snapshot, readingKeyMap, readings, knownReadings).orEmpty()
                    } else {
                        ""
                    },
                exactLetters = letters,
            )
        if (verified.isNotEmpty() || !compactLayout || snapshot.isAsciiMode) return marked.text
        if (marked.selection.start != marked.text.length) return marked.text
        if (!marked.text.all { it.code < 0x80 && (it.isLetter() || it == ' ' || it == '\'') }) return marked.text
        return typedReading(marked.text, snapshot.rawInput, keypad, readingKeyMap, letters)
    }

    /**
     * A sentence the engine joined from several words has no provable reading, and a keypad field
     * hides the numeric code, so it is spelled from the engine's own syllable segments of the
     * highlighted code (`94 546 484 92`). The highlighted candidate's own proven reading, taken from
     * [readings] (the page's candidates with their pronunciations) or [knownReadings] (readings seen
     * earlier in the session), decides each syllable; only a segment with no proven reading falls
     * back to the most common syllable, so `484` for 贵 reads `gui`, not the likelier `hui`.
     */
    fun sentenceSpelling(
        snapshot: EngineSnapshot,
        readingKeyMap: Map<Char, Char>,
        readings: List<ReadingCandidate> = emptyList(),
        knownReadings: Map<Char, List<String>> = emptyMap(),
    ): String? {
        if (!snapshot.hidesNumericTransportCode || readingKeyMap.isEmpty()) return null
        val candidate = snapshot.candidates.getOrNull(snapshot.highlightedIndex) ?: return null
        val span = utf8Slice(snapshot.preedit, snapshot.preeditSelStart, snapshot.preeditSelEnd) ?: return null
        if (span.isEmpty()) return null
        return SentenceSpelling.spell(segments(span), candidate.text, readings, readingKeyMap, knownReadings)
    }

    /** The keys typed after the highlighted span, spelled like a sentence tail. */
    private fun keypadTailSpelling(
        snapshot: EngineSnapshot,
        readingKeyMap: Map<Char, Char>,
        readings: List<ReadingCandidate>,
        knownReadings: Map<Char, List<String>>,
    ): String? {
        if (!snapshot.hidesNumericTransportCode || readingKeyMap.isEmpty()) return null
        val bytes = snapshot.preedit.toByteArray(Charsets.UTF_8)
        val end = snapshot.preeditSelEnd
        if (end < 0 || end >= bytes.size) return null
        val tail = utf8Slice(snapshot.preedit, end, bytes.size) ?: return null
        val segments = segments(tail)
        if (segments.isEmpty()) return null
        return SentenceSpelling.spell(segments, "", readings, readingKeyMap, knownReadings)
    }

    /**
     * Show only what was typed: one key is `n`, not `na`, and `bu g` rather than 不过's full `bu guo`.
     * Unprovable key maps keep the reading. A keypad joins syllables with `'` (`ni'h`).
     */
    private fun typedReading(
        reading: String,
        rawInput: String,
        keypad: Boolean,
        readingKeyMap: Map<Char, Char>,
        exactLetters: Map<Char, Char>,
    ): String {
        if (reading.isEmpty()) return reading
        val coverage =
            ReadingInputCoverage.of(rawInput, readingKeyMap, exactLetters)
                ?: return if (keypad) keypadJoined(reading) else reading
        val syllables = reading.split(' ', '\'').filter { it.isNotEmpty() }
        return coverage.typedText(syllables, if (keypad) SentenceSpelling.SEPARATOR else " ")
            ?: if (keypad) keypadJoined(reading) else reading
    }

    private fun keypadJoined(reading: String): String = reading.replace(" ", SentenceSpelling.SEPARATOR)

    private fun segments(span: String): List<String> =
        span.split(' ', ReadingInputCoverage.SEPARATOR).filter {
            it.isNotEmpty()
        }

    private fun utf8Slice(
        text: String,
        start: Int,
        end: Int,
    ): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (start < 0 || end > bytes.size || start > end) return null
        val from = SpellingDisplay.utf16IndexAtByteOffset(text, start) ?: return null
        val to = SpellingDisplay.utf16IndexAtByteOffset(text, end) ?: return null
        return text.substring(from, to)
    }
}

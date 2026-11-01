package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.ReadingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The composition a compact layout shows must be letters: without this pipeline the keypad showed
 * its transport code (`64 426`) in the field and the candidate bar.
 */
class CompositionTextTest {
    private fun keypad(
        preedit: String,
        rawInput: String,
        candidate: String,
        reading: String = "",
    ): EngineSnapshot {
        val end = preedit.toByteArray(Charsets.UTF_8).size
        return EngineSnapshot(
            preedit = preedit,
            rawInput = rawInput,
            isComposing = true,
            candidates = listOf(Candidate(0, candidate)),
            highlightedIndex = 0,
            preeditSelStart = 0,
            preeditSelEnd = end,
            preeditCaretByte = end,
            highlightedReading = reading,
            hidesNumericTransportCode = true,
        )
    }

    @Test
    fun aProvenKeypadReadingReplacesTheDigits() {
        val text = CompositionText.marked(keypad("64 426", "64426", "你好", "ni hao"), true, SentenceSpellingTest.T9)
        assertEquals("ni'hao", text)
    }

    @Test
    fun oneKeyShowsOnlyWhatWasTyped() {
        val text = CompositionText.marked(keypad("6", "6", "你", "ni"), true, SentenceSpellingTest.T9)
        assertEquals("n", text)
    }

    @Test
    fun aSentenceWithoutReadingIsSpelledFromItsSegments() {
        val text =
            CompositionText.marked(keypad("94 546 484 92", "9454648492", "一金贵呀"), true, SentenceSpellingTest.T9)
        // No candidate proves 贵 here, so its keys read as the more common hui: letters, never digits.
        assertEquals("yi'jin'hui'ya", text)
    }

    @Test
    fun theHighlightedCandidatesOwnReadingDecidesTheSpelling() {
        // Three keys, highlighted 贵: the candidate's proven reading is gui, not the commoner hui.
        val readings = listOf(ReadingCandidate.of(0, "贵", syllables = listOf("gui")))
        val text = CompositionText.marked(keypad("484", "484", "贵"), true, SentenceSpellingTest.T9, readings = readings)
        assertEquals("gui", text)
    }

    @Test
    fun aReadingSeenEarlierInTheSessionDecidesWhenThePageHasNone() {
        val text =
            CompositionText.marked(
                keypad("484", "484", "贵"),
                true,
                SentenceSpellingTest.T9,
                knownReadings = mapOf('贵' to listOf("gui")),
            )
        assertEquals("gui", text)
    }

    @Test
    fun aSentenceUsesTheReadingsOfItsCharacters() {
        val readings = listOf(ReadingCandidate.of(0, "一金贵呀", syllables = listOf("yi", "jin", "gui", "ya")))
        val text =
            CompositionText.marked(
                keypad("94 546 484 92", "9454648492", "一金贵呀"),
                true,
                SentenceSpellingTest.T9,
                readings = readings,
            )
        assertEquals("yi'jin'gui'ya", text)
    }

    @Test
    fun aKeypadReadingThatCarriesDigitsIsNeverShown() {
        val text = CompositionText.marked(keypad("64", "64", "你", "6 4"), true, SentenceSpellingTest.T9)
        assertFalse(text, text.any(Char::isDigit))
    }

    @Test
    fun aFullKeyboardShowsTheEnginePreedit() {
        val snapshot =
            EngineSnapshot(
                preedit = "ni hao",
                rawInput = "nihao",
                isComposing = true,
                candidates = listOf(Candidate(0, "你好")),
                highlightedIndex = 0,
                preeditSelEnd = 6,
                preeditCaretByte = 6,
            )
        assertEquals("ni hao", CompositionText.marked(snapshot, false, emptyMap()))
        assertEquals("", CompositionText.marked(EngineSnapshot(), false, emptyMap()))
    }
}

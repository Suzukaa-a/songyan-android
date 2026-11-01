package app.songyan.ime.keyboard.support

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishSuggestionsTest {
    private fun tracker(model: EnglishNextWordModel = EnglishNextWordModel()) = EnglishWordTracker(model)

    @Test
    fun lettersBuildTheWordAndASpaceMakesItTheContext() {
        val t = tracker()
        t.note("I")
        t.note("'ve")
        assertEquals("I've", t.word)
        t.note(" ")
        assertEquals("", t.word)
        assertEquals("I've", t.previous)
    }

    @Test
    fun aLeadingApostropheDoesNotStartAWord() {
        val t = tracker()
        t.note("'")
        assertEquals("", t.word)
        assertNull(t.previous)
    }

    @Test
    fun anyOtherCharacterEndsTheWordAndTheContext() {
        val t = tracker()
        t.note("hello ")
        t.note("wor")
        t.note("1")
        assertEquals("", t.word)
        assertNull(t.previous)
    }

    @Test
    fun finishedPairsAreLearnedForNextWordGuesses() {
        val model = EnglishNextWordModel()
        val t = tracker(model)
        t.note("zebra crossing ")
        assertEquals(listOf("crossing"), model.predictions("zebra"))
    }

    @Test
    fun backspaceTrimsTheWordThenLosesTheContext() {
        val t = tracker()
        t.note("hi th")
        t.backspace()
        assertEquals("t", t.word)
        t.backspace()
        t.backspace()
        assertEquals("", t.word)
        assertNull(t.previous)
    }

    @Test
    fun onlySingleEnglishWordsSurviveTheFilter() {
        val raw = listOf("then", "那", "the-", "don't", "th ", "3d", "Thai", "")
        assertEquals(listOf("then", "the-", "don't", "Thai"), EnglishSuggestionShaping.englishWords(raw))
    }

    @Test
    fun shapingFollowsTheTypedCapitalizationAndDeduplicates() {
        val words = listOf("then", "Then", "there", "the", "they")
        assertEquals(listOf("then", "there", "the"), EnglishSuggestionShaping.shape(words, "th"))
        assertEquals(listOf("Then", "There", "The"), EnglishSuggestionShaping.shape(words, "Th"))
        assertEquals(listOf("THEN", "THERE", "THE"), EnglishSuggestionShaping.shape(words, "TH"))
        assertEquals(listOf("T"), EnglishSuggestionShaping.shape(listOf("t"), "T"))
    }

    @Test
    fun enginePredictionSlotsKeepTheirPageIndices() {
        val snapshot =
            EngineSnapshot(
                isPrediction = true,
                candidates = listOf("a", "", "b", "a", "c", "d").mapIndexed { i, t -> Candidate(index = i, text = t) },
            )
        assertEquals(listOf(0 to "a", 2 to "b", 4 to "c"), ABCSuggestionSlots.enginePredictions(snapshot, 3))
    }
}

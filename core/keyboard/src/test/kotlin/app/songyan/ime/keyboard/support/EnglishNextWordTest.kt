package app.songyan.ime.keyboard.support

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishNextWordTest {
    @Test
    fun seedPredictionsAreCaseInsensitiveAndApostropheAware() {
        val model = EnglishNextWordModel()
        assertEquals(listOf("been", "got", "never"), model.predictions("I've"))
        assertEquals(listOf("been", "got", "never"), model.predictions("i’ve"))
        assertEquals(listOf("been", "got", "never"), model.predictions("  I'VE  "))
    }

    @Test
    fun learnedPairsRankBeforeTheSeedTable() {
        val model = EnglishNextWordModel()
        repeat(2) { model.learn("Thanks", "a lot") }
        assertEquals("seed completions still follow", true, model.predictions("thanks").contains("for"))
        assertEquals("a lot", model.predictions("thanks").first())
    }

    @Test
    fun unknownWordAndEmptyInputProduceNoPrediction() {
        val model = EnglishNextWordModel()
        assertTrue(model.predictions("qwerty").isEmpty())
        assertTrue(model.predictions("").isEmpty())
        assertTrue(model.predictions("the", limit = 0).isEmpty())
    }

    @Test
    fun learnIgnoresEmptyAndOverlongWords() {
        val model = EnglishNextWordModel()
        model.learn("", "next")
        model.learn("prev", "")
        model.learn("prev", "x".repeat(25))
        assertTrue(model.predictions("prev").isEmpty())
    }

    @Test
    fun learnedCapacityDropsOldestFirst() {
        val model = EnglishNextWordModel()
        // Fill past the capacity with distinct previous words.
        for (index in 0..EnglishNextWordModel.LEARNED_CAPACITY) {
            model.learn("prev$index", "next$index")
        }
        assertEquals("the oldest pair is dropped", emptyList<String>(), model.predictions("prev0"))
        assertTrue(model.predictions("prev1").isNotEmpty())
    }

    @Test
    fun abcSlotsOnlyExposePredictionCandidates() {
        val candidates = listOf("的", "", "的", "了", "是", "在").mapIndexed { index, text -> Candidate(index, text) }
        val ordinary = EngineSnapshot(candidates = candidates)
        assertTrue(ABCSuggestionSlots.enginePredictions(ordinary, 3).isEmpty())

        val prediction = ordinary.copy(isPrediction = true)
        val slots = ABCSuggestionSlots.enginePredictions(prediction, 3)
        assertEquals(listOf("的", "了", "是"), slots.map { it.second })
        assertEquals(listOf(0, 3, 4), slots.map { it.first })
    }
}

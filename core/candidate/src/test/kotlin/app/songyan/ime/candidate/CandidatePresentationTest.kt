package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Test

/** Presentation state visibility rules and the renderer action surface. */
class CandidatePresentationTest {
    private fun candidate(
        index: Int,
        text: String,
        comment: String = "",
    ) = Candidate(index = index, text = text, comment = comment)

    @Test
    fun visibilityRules() {
        val composing =
            CandidatePresentationState(
                preedit = "ni hao",
                rawInput = "nihao",
                candidates = listOf(candidate(0, "你好"), candidate(1, "您好", "nin hao")),
                highlightedIndex = 0,
                pageIndex = 0,
                canPageForward = true,
                isComposing = true,
            )
        var bar = CandidateBarDefinition.systemDefault
        assertTrue(composing.showsCompositionRow(bar))
        assertTrue(composing.showsCandidateBar)
        assertTrue(composing.showsExpandedArea)
        bar =
            bar.copy(
                compositionRow =
                    CandidateBarDefinition.CompositionRow(
                        CandidateBarDefinition.CompositionRow.Visibility.HIDDEN,
                    ),
            )
        assertFalse(composing.showsCompositionRow(bar))
        assertTrue(composing.showsCandidateBar, "hiding the composition row does not affect the candidate strip")
        bar =
            bar.copy(
                compositionRow =
                    CandidateBarDefinition.CompositionRow(
                        CandidateBarDefinition.CompositionRow.Visibility.COMPOSING_WITH_IDLE_RAW,
                    ),
            )
        assertTrue(
            CandidatePresentationState().showsCompositionRow(bar),
            "advanced users can opt to show the raw-code row when empty",
        )
        assertTrue(CandidatePresentationState(isEnglishMode = true).isEnglishMode)
        assertFalse(CandidatePresentationState(isEnglishMode = true).showsCandidateBar)

        val engineEnglish =
            CandidatePresentationState(
                candidates = listOf(candidate(2, "hello")),
                pageSize = 5,
                isComposing = true,
                isEnglishMode = true,
            )
        assertTrue(engineEnglish.showsCandidateBar)
        assertTrue(engineEnglish.showsExpandedArea)

        val afterCommit =
            CandidatePresentationState(
                candidates = listOf(candidate(0, "世界")),
                pageSize = 5,
                isComposing = true,
            )
        assertTrue(
            afterCommit.showsExpandedArea,
            "a real prediction segment has no input code but still reports composing",
        )
    }

    @Test
    fun stateIsDerivedFromAnEngineSnapshotWithAnOptionalPreeditOverride() {
        val snapshot = EngineSnapshot(schemaID = "s", preedit = "ni", rawInput = "ni", isComposing = true)
        val state =
            CandidatePresentationState.of(
                snapshot,
                isEnglishMode = false,
                preeditOverride = "display",
                layout = "l",
                revision = 4,
            )
        assertEquals("display", state.preedit)
        assertEquals("ni", state.rawInput)
        assertEquals("s", state.schemaID)
        assertEquals("l", state.layout)
        assertEquals(4, state.revision)
        assertTrue(state.hasCandidateMenu)

        val plain = CandidatePresentationState.of(snapshot, isEnglishMode = true)
        assertEquals("ni", plain.preedit, "no override keeps the engine preedit")
        assertTrue(plain.isEnglishMode)
    }

    @Test
    fun actionSinkForwardsRealIndexOnly() {
        val performed = mutableListOf<CandidateAreaAction>()
        val sink = CandidateAreaActionSink { performed.add(it) }
        sink.perform(CandidateAreaAction.Select(2))
        sink.perform(CandidateAreaAction.PageForward)
        sink.perform(CandidateAreaAction.Expand)
        assertEquals(
            listOf<CandidateAreaAction>(
                CandidateAreaAction.Select(2),
                CandidateAreaAction.PageForward,
                CandidateAreaAction.Expand,
            ),
            performed,
        )
    }

    @Test
    fun contextFallsBackToTheSinglePageAndExposesPrefetch() {
        val state =
            CandidatePresentationState(
                candidates = listOf(candidate(0, "a"), candidate(1, "b")),
                pageIndex = 1,
                isComposing = true,
            )
        val context =
            CandidateAreaContext(
                state = state,
                bar = CandidateBarDefinition.systemDefault,
                actions = CandidateAreaActionSink {},
                typography = CandidateTypography.STANDARD,
            )
        assertEquals(listOf("a", "b"), context.displayEntries.map { it.text })
        assertEquals(1, context.displayEntries.first().pageIndex, "the fallback keeps the real page ownership")
        assertEquals(null, context.prefetchIndex(entryCount = 2), "nothing to load without a browser frontier")
    }

    @Test
    fun verifiedReadingSubsetKeepsAbsoluteSelectionAndNeverFallsBack() {
        val state =
            CandidatePresentationState(candidates = listOf(candidate(0, "original")), pageIndex = 0, isComposing = true)
        val filtered =
            BrowserCandidate(
                pageIndex = 3,
                pageLocalIndex = 2,
                candidate = Candidate(index = 2, text = "verified", comment = "kao lv", selectLabel = "3"),
            )
        val sink = CandidateAreaActionSink {}
        val context =
            CandidateAreaContext(
                state = state,
                bar = CandidateBarDefinition.systemDefault,
                actions = sink,
                typography = CandidateTypography.STANDARD,
                visibleEntries = listOf(filtered),
                visibleHasMore = true,
                visibleFrontier = 18,
            )
        assertEquals(listOf("verified"), context.displayEntries.map { it.text })
        assertEquals("kao lv", context.displayEntries.first().comment)
        assertEquals(CandidateAreaAction.SelectOnPage(page = 3, index = 2), context.selectAction(filtered))
        assertEquals(18, context.entryFrontier)
        assertTrue(context.hasMoreEntries)
        assertEquals(0, context.prefetchIndex(entryCount = 1))

        val noMatches =
            CandidateAreaContext(
                state = state,
                bar = CandidateBarDefinition.systemDefault,
                actions = sink,
                typography = CandidateTypography.STANDARD,
                visibleEntries = emptyList(),
                visibleHasMore = false,
            )
        assertTrue(
            noMatches.displayEntries.isEmpty(),
            "an empty verified subset cannot silently expose the original engine page",
        )
    }
}

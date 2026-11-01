package app.songyan.ime.candidate

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Test

/** Header visibility, content kind and the tools affordance contract. */
class CandidateHeaderSurfaceTest {
    private fun input(
        isEnglish: Boolean = false,
        candidates: Boolean = false,
        status: Boolean = false,
        picker: Boolean = false,
        fallback: Boolean = false,
        reading: Boolean = false,
    ) = CandidateHeaderVisibilityInput(
        isEnglish = isEnglish,
        hasCandidates = candidates,
        hasStatus = status,
        hasAmbiguityPicker = picker,
        hasCompositionFallback = fallback,
        isReadingFiltering = reading,
    )

    @Test
    fun pureIdleHasNoRealInputContent() {
        assertFalse(CandidateHeaderVisibility.showsHeader(input()))
        assertFalse(CandidateHeaderVisibility.showsHeader(input(isEnglish = true)))
    }

    @Test
    fun everyRealContentKindKeepsTheHeaderVisible() {
        assertTrue(CandidateHeaderVisibility.showsHeader(input(candidates = true)))
        assertTrue(CandidateHeaderVisibility.showsHeader(input(isEnglish = true, candidates = true)))
        assertTrue(CandidateHeaderVisibility.showsHeader(input(status = true)))
        assertTrue(CandidateHeaderVisibility.showsHeader(input(picker = true)))
        assertTrue(CandidateHeaderVisibility.showsHeader(input(reading = true)))
        assertTrue(
            CandidateHeaderVisibility.showsHeader(input(fallback = true)),
            "the header must show when a whole-composition fallback exists",
        )
        assertTrue(
            CandidateHeaderVisibility.showsHeader(input(isEnglish = true, fallback = true)),
            "the composition fallback must not be swallowed by the English collapse",
        )
    }

    @Test
    fun contentKindFollowsLayoutAndState() {
        fun content(
            layout: CandidateHeaderLayout = CandidateHeaderLayout.QWERTY26,
            isEnglish: Boolean = false,
            isComposing: Boolean = false,
            hasCandidates: Boolean = false,
            hasStatus: Boolean = false,
            isExpanded: Boolean = false,
        ) = CandidateHeaderSurfacePolicy.content(
            layout = layout,
            isEnglish = isEnglish,
            isComposing = isComposing,
            hasCandidates = hasCandidates,
            hasStatus = hasStatus,
            isExpanded = isExpanded,
        )
        assertEquals(CandidateHeaderContent.IDLE, content())
        assertEquals(CandidateHeaderContent.IDLE, content(isEnglish = true))
        assertEquals(CandidateHeaderContent.CANDIDATES, content(hasCandidates = true))
        assertEquals(CandidateHeaderContent.CANDIDATES_EXPANDED, content(hasCandidates = true, isExpanded = true))
        assertEquals(CandidateHeaderContent.STATUS, content(hasStatus = true))
        assertEquals(CandidateHeaderContent.COMPOSITION_WITHOUT_CANDIDATES, content(isComposing = true))
        // Real candidates win over a status hint.
        assertEquals(CandidateHeaderContent.CANDIDATES, content(hasCandidates = true, hasStatus = true))
    }

    @Test
    fun layoutFamiliesMapFromThePhysicalLayout() {
        assertEquals(CandidateHeaderLayout.QWERTY26, CandidateHeaderLayout.of(KeyboardLayoutKind.QWERTY26))
        assertEquals(CandidateHeaderLayout.PHONE14, CandidateHeaderLayout.of(KeyboardLayoutKind.MERGED14))
        assertEquals(CandidateHeaderLayout.PHONE18, CandidateHeaderLayout.of(KeyboardLayoutKind.MERGED18))
        assertEquals(CandidateHeaderLayout.PHONE9, CandidateHeaderLayout.of(KeyboardLayoutKind.PHONE9))
        assertFalse(CandidateHeaderLayout.QWERTY26.offersAmbiguityPicker, "26-key never offers disambiguation")
        assertTrue(CandidateHeaderLayout.PHONE9.offersAmbiguityPicker)
        assertTrue(CandidateHeaderLayout.PHONE9.usesPhoneShell)
        assertFalse(CandidateHeaderLayout.PHONE14.usesPhoneShell)
    }

    @Test
    fun surfaceHeightTopsUpForTheCandidateFontSize() {
        assertEquals(36.0, CandidateHeaderSurfacePolicy.surfaceHeight(36.0), 0.001)
        assertEquals(36.0, CandidateHeaderSurfacePolicy.surfaceHeight(36.0, candidateSize = 17.0), 0.001)
        assertEquals(41.0, CandidateHeaderSurfacePolicy.surfaceHeight(36.0, candidateSize = 30.0), 0.001)
        assertEquals(44.0, CandidateHeaderSurfacePolicy.surfaceHeight(44.0), 0.001)
    }

    @Test
    fun zOrderPutsTheBackgroundBelowTheContent() {
        assertTrue(
            CandidateHeaderSurfacePolicy.zIndex(CandidateHeaderLayer.BACKGROUND) <
                CandidateHeaderSurfacePolicy.zIndex(CandidateHeaderLayer.CONTENT),
        )
        assertTrue(CandidateHeaderSurfacePolicy.FORBIDS_BLUR_MATERIAL_OPACITY_FOR_NORMAL_CANDIDATES)
    }

    @Test
    fun toolsAffordanceContract() {
        assertFalse(
            KeyboardToolsAffordance.INDICATOR_GLYPH.isEmpty(),
            "the language key must have a visible long-press menu indicator",
        )
        assertTrue(KeyboardToolsAffordance.MINIMUM_HIT_TARGET >= 44.0)
    }
}

/** Reading row eligibility, grant arbitration and the cold-start hint. */
class CandidateReadingRowTest {
    @Test
    fun readingRowHeightDependsOnTheGrantAndTheSizeClass() {
        assertEquals(0.0, CandidateReadingRowPolicy.height(reserved = false), 0.001)
        assertEquals(32.0, CandidateReadingRowPolicy.height(reserved = true), 0.001)
        assertEquals(28.0, CandidateReadingRowPolicy.height(reserved = true, compact = true), 0.001)
        assertEquals(0.0, CandidateReadingRowPolicy.height(reserved = false, compact = true), 0.001)
    }

    @Test
    fun textTopPaddingNeverClipsASqueezedRow() {
        assertEquals(8.0, CandidateReadingRowPolicy.textTopPadding(rowHeight = 32.0), 0.001)
        assertEquals(4.0, CandidateReadingRowPolicy.textTopPadding(rowHeight = 28.0), 0.001)
        assertEquals(0.0, CandidateReadingRowPolicy.textTopPadding(rowHeight = 24.0), 0.001)
        assertEquals(0.0, CandidateReadingRowPolicy.textTopPadding(rowHeight = 10.0), 0.001)
    }

    @Test
    fun grantRequiresASettledSourceAgreeingWithTheReport() {
        val fromRegistry = CandidateReadingRowReport(eligible = true, resolvedFromRegistry = true)
        val fromBuiltIn = CandidateReadingRowReport(eligible = true, resolvedFromRegistry = false)
        assertTrue(CandidateReadingRowGrant.accepts(fromRegistry, layoutSourceSettled = true, registryAvailable = true))
        assertFalse(CandidateReadingRowGrant.accepts(fromBuiltIn, layoutSourceSettled = true, registryAvailable = true))
        assertFalse(
            CandidateReadingRowGrant.accepts(fromRegistry, layoutSourceSettled = false, registryAvailable = true),
        )
        assertTrue(CandidateReadingRowGrant.accepts(fromBuiltIn, layoutSourceSettled = true, registryAvailable = false))
        assertFalse(
            CandidateReadingRowGrant.accepts(fromRegistry, layoutSourceSettled = true, registryAvailable = false),
        )
    }

    @Test
    fun hintAppliesOnlyToTheSameLayoutSelection() {
        val hint = CandidateReadingRowHint(selection = "merged14|bamboo", eligible = true)
        assertTrue(hint.resolve(currentSelection = "merged14|bamboo"))
        assertFalse(hint.resolve(currentSelection = "merged14|other"))
        assertFalse(hint.resolve(currentSelection = null))
        assertFalse(CandidateReadingRowHint(selection = "x", eligible = false).resolve(currentSelection = "x"))
        assertEquals("a|b", CandidateReadingRowHint.selectionKey("a", "b"))
        assertEquals("|", CandidateReadingRowHint.selectionKey(null, null))
    }

    @Test
    fun anUnknownLayoutKeepsTheCandidateAreaUntilResolved() {
        val hint =
            CandidateReadingRowHint(selection = "merged14|bamboo", eligible = true, candidateAreaRequired = false)
        assertFalse(hint.resolveCandidateArea(currentSelection = "merged14|bamboo"))
        assertTrue(
            hint.resolveCandidateArea(currentSelection = "phone9|other"),
            "a changed layout retains the candidate area until its definition is resolved",
        )
        assertTrue(CandidateReadingRowHint.resolveCandidateArea(hint = null, currentSelection = "x"))
        assertFalse(CandidateReadingRowHint.resolve(hint = null, currentSelection = "x"))
    }
}

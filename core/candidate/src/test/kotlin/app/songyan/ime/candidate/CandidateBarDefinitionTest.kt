package app.songyan.ime.candidate

import org.junit.Test

/** Candidate area config: defaults, strict decoding, font-aware height budget. */
class CandidateBarDefinitionTest {
    @Test
    fun defaultsAndStrictDecode() {
        val bar =
            CandidateBarDefinition.decode(
                """{"formatVersion":1,"id":"my-bar","name":"bar","expanded":{"mode":"list"}}""",
            )
        assertEquals(CandidateBarDefinition.CompositionRow.Visibility.COMPOSING_ONLY, bar.compositionRow.visible)
        assertEquals(44.0, bar.collapsed.height, "v1 omitted height keeps the historical default")
        assertEquals(40.0, CandidateBarDefinition.systemDefault.collapsed.height)
        assertEquals(CandidateBarDefinition.Expanded.Mode.LIST, bar.expanded.mode)
        assertEquals(1, bar.expandedEffectiveColumns)
        assertEquals(CandidateBarDefinition.Controls.PagePosition.BOTH, bar.controls.pagePosition)
        // The built-in default writes the page buttons at the trailing end explicitly.
        assertEquals(
            CandidateBarDefinition.Controls.PagePosition.TRAILING,
            CandidateBarDefinition.decode(CandidateBarDefinition.systemDefault.encode()).controls.pagePosition,
        )
        // Default = the cleanest UX: no composition row without input.
        assertFalse(CandidatePresentationState().showsCompositionRow(bar))
        // Export and re-import agree for omitted fields.
        assertEquals(bar, CandidateBarDefinition.decode(bar.encode()))
    }

    @Test
    fun unknownFieldsWrongVersionsAndInvalidValuesAreRejected() {
        val rejected =
            listOf(
                """{"formatVersion":3,"id":"x","name":"x"}""",
                """{"formatVersion":1,"id":"../x","name":"x"}""",
                """{"formatVersion":1,"id":"x","name":"x","collapsed":{"height":60}}""",
                """{"formatVersion":1,"id":"x","name":"x","expanded":{"columns":5}}""",
                """{"formatVersion":1,"id":"x","name":"x","renderer":"evil"}""",
                """{"formatVersion":1,"id":"x","name":"x","candidates":["fake"]}""",
                """{"formatVersion":1,"id":"x","name":"x","expanded":{"mode":"weird"}}""",
            )
        for (json in rejected) {
            assertFailsWith<CandidateBarError>(json) { CandidateBarDefinition.decode(json) }
        }
    }

    @Test
    fun defaultHeaderHeightAndFontAwareEffectiveBudget() {
        assertEquals(44.0, CandidateBarDefinition.Collapsed().height)
        // The built-in system default is tightened to 40 dp; importing with an omitted height still uses v1's 44 dp.
        assertEquals(40.0, CandidateBarDefinition.systemDefault.collapsed.height)
        assertEquals(36.0, CandidateBarDefinition.Collapsed.effectiveHeight(36.0))
        assertEquals(36.0, CandidateBarDefinition.Collapsed.effectiveHeight(36.0, candidateSize = 17.0))
        assertEquals(41.0, CandidateBarDefinition.Collapsed.effectiveHeight(36.0, candidateSize = 30.0))
        assertEquals(66.0, CandidateBarDefinition.Collapsed.effectiveHeight(36.0, candidateSize = 50.0))
        for (height in listOf(44.0, 50.0, 56.0)) {
            assertEquals(height, CandidateBarDefinition.Collapsed.effectiveHeight(height))
        }
        val persisted =
            CandidateBarDefinition.decode("""{"formatVersion":1,"id":"old","name":"old","collapsed":{"height":50}}""")
        assertEquals(50.0, persisted.collapsed.height)
        assertEquals(
            50.0,
            persisted.collapsedHeightBudget,
            "an explicit 44..56 config must be kept at the default font size",
        )
    }

    @Test
    fun expandedDefaultIsAdaptiveAndExplicitModesPreserved() {
        assertEquals(CandidateBarDefinition.Expanded.Mode.ADAPTIVE, CandidateBarDefinition.systemDefault.expanded.mode)
        assertTrue(CandidateBarDefinition.systemDefault.expanded.isAdaptive)

        val grid =
            CandidateBarDefinition.decode(
                """{"formatVersion":1,"id":"g","name":"g","expanded":{"mode":"grid","columns":3}}""",
            )
        assertEquals(CandidateBarDefinition.Expanded.Mode.GRID, grid.expanded.mode)
        assertFalse(grid.expanded.isAdaptive)
        assertEquals(3, grid.expandedEffectiveColumns, "an explicit user column count must be kept")

        val list =
            CandidateBarDefinition.decode(
                """{"formatVersion":1,"id":"l","name":"l","expanded":{"mode":"list"}}""",
            )
        assertEquals(CandidateBarDefinition.Expanded.Mode.LIST, list.expanded.mode)
        assertEquals(1, list.expandedEffectiveColumns)

        val adaptive =
            CandidateBarDefinition.decode(
                """{"formatVersion":1,"id":"a","name":"a","expanded":{"mode":"adaptive"}}""",
            )
        assertEquals(CandidateBarDefinition.Expanded.Mode.ADAPTIVE, adaptive.expanded.mode)
        assertEquals(adaptive, CandidateBarDefinition.decode(adaptive.encode()), "export and re-import agree")
    }

    @Test
    fun omittedFillStrategyStaysEvenSplitAndExplicitValueSurvivesRoundTrip() {
        var diy = CandidateBarDefinition.systemDefault
        diy = diy.copy(id = "existing-diy")
        diy = diy.copy(collapsed = diy.collapsed.copy(fillsAvailableWidth = null))
        val decoded = CandidateBarDefinition.decode(diy.encode())
        assertNull(decoded.collapsed.fillsAvailableWidth)

        val copy = CandidateBarDefinition.systemDefault.duplicate(nameSuffix = " copy")
        val restored = CandidateBarDefinition.decode(copy.encode())
        assertEquals(copy.copy(id = restored.id, name = restored.name), restored)
        assertEquals(false, restored.collapsed.fillsAvailableWidth)
        assertEquals(40.0, restored.collapsed.height)
    }

    @Test
    fun duplicateGetsAFreshIdentityAndKeepsLayout() {
        val copy = CandidateBarDefinition.systemDefault.duplicate(nameSuffix = " copy")
        assertTrue(copy.id.isNotEmpty())
        assertTrue(copy.id != CandidateBarDefinition.SYSTEM_DEFAULT_ID)
        assertEquals(CandidateBarDefinition.systemDefault.name + " copy", copy.name)
        assertEquals(CandidateBarDefinition.systemDefault.collapsed, copy.collapsed)
        assertEquals(CandidateBarDefinition.systemDefault.expanded, copy.expanded)
    }

    @Test
    fun oversizeInputIsRejectedWithoutTruncation() {
        val padding = " ".repeat(CandidateBarDefinition.MAX_BYTES)
        val json = """{"formatVersion":1,"id":"x","name":"x","author":"$padding"}"""
        assertFailsWith<CandidateBarError> { CandidateBarDefinition.decode(json) }
    }
}

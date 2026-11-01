package app.songyan.ime.ime

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CandidateBarStyleTest {
    private val tokens = KeyboardDesignTokens.light()

    @Test
    fun theBuiltInDefaultKeepsTheKeyboardTokens() {
        val style = CandidateBarStyle.of(CandidateBarDefinition.systemDefault)
        assertNull(style.rowHeightDp)
        assertNull(style.chipPaddingDp)
        assertNull(style.cellSpacingDp)
        assertSame(tokens, style.applyTo(tokens))
    }

    @Test
    fun aChangedRowHeightBecomesATouchTargetAtLeast48Dp() {
        val taller =
            CandidateBarDefinition.systemDefault.run {
                copy(collapsed = collapsed.copy(height = 52.0))
            }
        assertEquals(60f, CandidateBarStyle.of(taller).rowHeightDp!!, 0.001f)
        val shorter =
            CandidateBarDefinition.systemDefault.run {
                copy(collapsed = collapsed.copy(height = 36.0))
            }
        assertEquals(48f, CandidateBarStyle.of(shorter).rowHeightDp!!, 0.001f)
    }

    @Test
    fun changedPaddingAndSpacingOverrideTheTokens() {
        val bar =
            CandidateBarDefinition.systemDefault.run {
                copy(collapsed = collapsed.copy(cellPaddingX = 16.0, cellSpacing = 6.0))
            }
        val applied = CandidateBarStyle.of(bar).applyTo(tokens)
        assertEquals(19f, applied.candidateChipPaddingDp, 0.001f)
        assertEquals(6f, applied.candidateCellSpacingDp, 0.001f)
        assertEquals(tokens.candidateBarHeightDp, applied.candidateBarHeightDp, 0.001f)
    }

    @Test
    fun paddingKeepsTheStepBetweenTheIosDefaultAndTheAndroidToken() {
        // The settings slider shows the padding the keyboard draws: one step up from the default is 11 dp, not 8.
        val bar =
            CandidateBarDefinition.systemDefault.run {
                copy(collapsed = collapsed.copy(cellPaddingX = collapsed.cellPaddingX + 1))
            }
        assertEquals(
            tokens.candidateChipPaddingDp + 1,
            CandidateBarStyle.of(bar).applyTo(tokens).candidateChipPaddingDp,
            0.001f,
        )
    }

    @Test
    fun expandedModeColumnsAndSpacingFollowTheDefinition() {
        val bar =
            CandidateBarDefinition.systemDefault.run {
                copy(expanded = expanded.copy(mode = Mode.GRID, columns = 3, rowSpacing = 4.0, columnSpacing = 2.0))
            }
        val style = CandidateBarStyle.of(bar)
        assertEquals(Mode.GRID, style.expandedMode)
        assertEquals(3, style.columns)
        assertEquals(4f, style.rowGapDp, 0.001f)
        assertEquals(2f, style.columnGapDp, 0.001f)
    }

    @Test
    fun aListIsOneColumnWhateverTheColumnCount() {
        val bar =
            CandidateBarDefinition.systemDefault.run {
                copy(expanded = expanded.copy(mode = Mode.LIST, columns = 4))
            }
        assertEquals(1, CandidateBarStyle.of(bar).columns)
    }

    @Test
    fun noInstalledAppearanceMeansThePlatformLook() {
        assertEquals(CandidateBarStyle(), CandidateBarStyle.of(null))
    }
}

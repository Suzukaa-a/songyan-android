package app.songyan.ime.ime

import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import app.songyan.ime.ime.palette.KeyboardAppearanceRegistry
import app.songyan.ime.ime.palette.KeyboardAppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBarStyleTest {
    private val tokens = KeyboardDesignTokens.light()

    private fun bar(
        height: Double = 48.0,
        padding: Double = 10.0,
        spacing: Double = 0.0,
    ) = CandidateBarDefinition.androidDefault.run {
        copy(collapsed = collapsed.copy(height = height, cellPaddingX = padding, cellSpacing = spacing))
    }

    @Test
    fun theAndroidBuiltInBarDrawsTheNumbersTheKeyboardAlwaysHad() {
        val applied = CandidateBarStyle.of(CandidateBarDefinition.androidDefault, 18.0).applyTo(tokens)
        assertEquals(48f, applied.candidateBarHeightDp, 0.001f)
        assertEquals(10f, applied.candidateChipPaddingDp, 0.001f)
        assertEquals(0f, applied.candidateCellSpacingDp, 0.001f)
    }

    @Test
    fun theRowHeightIsTheFileValueWithNoFloorOrOffset() {
        assertEquals(36f, CandidateBarStyle.of(bar(height = 36.0), 18.0).rowHeightDp!!, 0.001f)
        assertEquals(52f, CandidateBarStyle.of(bar(height = 52.0), 18.0).rowHeightDp!!, 0.001f)
    }

    @Test
    fun theHeightIsClampedToTheFormatRange() {
        assertEquals(56f, CandidateBarStyle.of(bar(height = 70.0), 18.0).rowHeightDp!!, 0.001f)
        assertEquals(36f, CandidateBarStyle.of(bar(height = 10.0), 18.0).rowHeightDp!!, 0.001f)
    }

    @Test
    fun aLargeCandidateFontTopsTheHeightUp() {
        // ceil(30 * 1.25 + 3) = 41 beats a 40 dp row; ceil(28 * 1.25 + 3) = 38 does not.
        assertEquals(41f, CandidateBarStyle.of(bar(height = 40.0), 30.0).rowHeightDp!!, 0.001f)
        assertEquals(40f, CandidateBarStyle.of(bar(height = 40.0), 28.0).rowHeightDp!!, 0.001f)
    }

    @Test
    fun paddingAndSpacingAreLiteralDp() {
        val applied = CandidateBarStyle.of(bar(padding = 16.0, spacing = 6.0), 18.0).applyTo(tokens)
        assertEquals(16f, applied.candidateChipPaddingDp, 0.001f)
        assertEquals(6f, applied.candidateCellSpacingDp, 0.001f)
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

    @Test
    fun theRowGrowsWithTheUsersTextSizeAndTheSystemFontScale() {
        val skin = AppearancePresets.androidDefault.skin
        val document =
            AppearancePresets.androidDefault
                .replacing(skin.copy(typography = skin.typography.copy(candidateSize = 30.0)))
                .replacing(bar(height = 36.0))
        KeyboardAppearanceRegistry.install(KeyboardAppearanceSettings(document = document, dynamicColor = false))
        try {
            assertEquals(41f, CandidateBarStyle.current().rowHeightDp!!, 0.001f)
            CandidateBarStyle.textFactor = 1.3f
            // 30 x 1.3 = 39 sp: ceil(39 x 1.25 + 3) = 52.
            assertEquals(52f, CandidateBarStyle.current().rowHeightDp!!, 0.001f)
            // The font scale counts up to the keyboard's 1.3 cap: 30 x 1.3 x 1.3 = 50.7 sp needs 67 dp.
            assertEquals(67f, CandidateBarStyle.current(fontScale = 2f).rowHeightDp!!, 0.001f)
        } finally {
            CandidateBarStyle.textFactor = 1f
            KeyboardAppearanceRegistry.install(KeyboardAppearanceSettings(document = null, dynamicColor = false))
        }
    }
}

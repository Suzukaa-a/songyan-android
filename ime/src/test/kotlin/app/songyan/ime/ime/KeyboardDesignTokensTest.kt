package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compact variant used by phone compact and short split-screen panes. */
class KeyboardDesignTokensTest {
    private val portrait = KeyboardDesignTokens.light()
    private val compact = portrait.forCompactWindow()

    @Test
    fun portraitGeometryFollowsTheSpec() {
        assertEquals(56f, portrait.rowHeightDp)
        assertEquals(8f, portrait.keyRadiusDp)
        assertEquals(22f, portrait.keyLabelTextSizeSp)
        // A 47 dp cap with a 9 dp gap between rows, the same gaps as the iOS keyboard.
        assertEquals(9f, portrait.keyGapVerticalDp)
        assertEquals(47f, portrait.rowHeightDp - portrait.keyGapVerticalDp)
        assertEquals(
            portrait.candidateBarHeightDp + 4 * portrait.rowHeightDp + portrait.keyAreaBottomGapDp,
            portrait.totalHeightDp,
        )
    }

    @Test
    fun theCandidateRowIsOneTouchTargetInPortraitAndAtLeast40InCompact() {
        assertTrue(portrait.candidateBarHeightDp >= portrait.minTouchTargetDp)
        assertEquals(40f, compact.candidateBarHeightDp)
    }

    @Test
    fun compactKeyboardIsShorterButKeysStayTouchable() {
        assertTrue(compact.rowHeightDp < portrait.rowHeightDp)
        assertEquals(40f, compact.rowHeightDp)
        assertTrue(compact.candidateBarHeightDp < portrait.candidateBarHeightDp)
        assertTrue(compact.totalHeightDp < portrait.totalHeightDp)
    }
}

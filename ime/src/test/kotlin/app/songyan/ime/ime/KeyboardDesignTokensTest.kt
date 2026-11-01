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
        // Cap 49-50 dp with a 6-7 dp gap between rows.
        val cap = portrait.rowHeightDp - portrait.keyGapVerticalDp
        assertTrue(cap in 49f..50f)
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

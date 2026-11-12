package app.songyan.ime.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCursorDragPolicyTest {
    @Test
    fun unownedHorizontalDragsStartAtFourteenDpInEitherDirection() {
        for (direction in listOf(-1, 1)) {
            assertFalse(starts(dxDp = direction * 13.99f))
            assertTrue(starts(dxDp = direction * 14f))
            assertTrue(starts(dxDp = direction * 30f, dyDp = -29f))
        }
    }

    @Test
    fun verticalAndDiagonalSlidesDoNotStart() {
        assertFalse(starts(dxDp = 14f, dyDp = 14f))
        assertFalse(starts(dxDp = -14f, dyDp = -14f))
        assertFalse(starts(dxDp = 14f, dyDp = 15f))
        assertFalse(starts(dxDp = 0f, dyDp = 30f))
    }

    @Test
    fun typingAndTheDisabledSettingKeepTheGestureOutOfCaretMode() {
        assertFalse(starts(settingOn = false))
        assertFalse(starts(isComposing = true))
    }

    @Test
    fun sidewaysGestureOwnersKeepTheirGestureBeforeAndAfterTheEighteenDpLock() {
        for (distance in listOf(14f, 18f, 72f)) {
            assertFalse(starts(ownsSidewaysGesture = true, dxDp = distance))
            assertFalse(starts(ownsSidewaysGesture = true, dxDp = -distance))
        }
    }

    @Test
    fun spaceAndDeleteKeepTheirExistingHandling() {
        assertFalse(starts(isSpace = true))
        assertFalse(starts(isDelete = true))
    }

    private fun starts(
        settingOn: Boolean = true,
        isComposing: Boolean = false,
        ownsSidewaysGesture: Boolean = false,
        isSpace: Boolean = false,
        isDelete: Boolean = false,
        dxDp: Float = 30f,
        dyDp: Float = 0f,
    ): Boolean =
        KeyboardCursorDragPolicy.starts(settingOn, isComposing, ownsSidewaysGesture, isSpace, isDelete, dxDp, dyDp)
}

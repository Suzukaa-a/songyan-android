package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlIconsTest {
    private fun xs(points: FloatArray) = points.filterIndexed { i, _ -> i % 2 == 0 }

    private fun ys(points: FloatArray) = points.filterIndexed { i, _ -> i % 2 == 1 }

    @Test
    fun backspacePointsLeftTowardTheTextItDeletes() {
        val points = ControlIcons.backspace
        val tip = xs(points).indexOf(xs(points).min())
        assertEquals("the leftmost point is the tip on the centre line", 0f, ys(points)[tip])
        assertEquals("only the tip reaches that far", 1, xs(points).count { it == xs(points).min() })
        val cross = xs(ControlIcons.backspaceCross)
        assertTrue("the cross sits in the body, right of the tip", cross.min() > xs(points).min() / 2)
    }

    @Test
    fun theShiftArrowIsCentredOnTheKey() {
        val ys = ys(ControlIcons.shift)
        assertEquals(0f, (ys.min() + ys.max()) / 2, 0.001f)
        val xs = xs(ControlIcons.shift)
        assertEquals(0f, (xs.min() + xs.max()) / 2, 0.001f)
        assertTrue("the Caps Lock bar hangs below the arrow", ControlIcons.capsLockBar[2] > ys.max())
    }
}

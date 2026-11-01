package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WideSurfaceTest {
    @Test
    fun anUnsplitKeyboardIsCappedAndDockedOnAWideSurface() {
        val centre = WideSurface.plan(1024.0, null, KeyboardDock.CENTER)
        assertEquals(600.0, centre.contentWidth, 1e-9)
        assertEquals(212.0, centre.leadingInset, 1e-9)
        assertEquals(212.0, centre.trailingInset, 1e-9)
        val left = WideSurface.plan(1024.0, null, KeyboardDock.LEFT)
        assertEquals(0.0, left.leadingInset, 1e-9)
        assertEquals(424.0, left.trailingInset, 1e-9)
        val right = WideSurface.plan(1024.0, null, KeyboardDock.RIGHT)
        assertEquals(424.0, right.leadingInset, 1e-9)
        assertEquals(0.0, right.trailingInset, 1e-9)
    }

    @Test
    fun aPhoneSizedSurfaceKeepsItsFullWidth() {
        for (dock in KeyboardDock.entries) {
            val plan = WideSurface.plan(411.0, null, dock)
            assertFalse(plan.active)
            assertEquals(411.0, plan.contentWidth, 1e-9)
        }
    }

    @Test
    fun anActiveSplitTakesTheWholeSurface() {
        val split = LayoutSplitConfiguration()
        val plan = WideSurface.plan(1024.0, split, KeyboardDock.RIGHT)
        assertFalse(plan.active)
        assertEquals(1024.0, plan.contentWidth, 1e-9)
        // The same split on a phone-sized surface is not active, so nothing changes there.
        assertFalse(WideSurface.plan(411.0, split, KeyboardDock.RIGHT).active)
    }

    @Test
    fun aSplitThatIsOffDoesNotTakeTheSurface() {
        val off = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF)
        assertEquals(600.0, WideSurface.plan(1024.0, off, KeyboardDock.CENTER).contentWidth, 1e-9)
    }

    @Test
    fun theSplitModeOverridesTheLayoutsOwnSplit() {
        val own = LayoutSplitConfiguration.of(rowBreaks = listOf(4))
        assertEquals(own, WideSurface.effectiveSplit(own, SplitMode.LAYOUT))
        assertNull(WideSurface.effectiveSplit(null, SplitMode.LAYOUT))
        assertNull(WideSurface.effectiveSplit(own, SplitMode.OFF))
        assertEquals(own, WideSurface.effectiveSplit(own, SplitMode.ON))
        assertNotNull(WideSurface.effectiveSplit(null, SplitMode.ON))
        assertTrue(WideSurface.effectiveSplit(null, SplitMode.ON)!!.isActive(1024.0))
        // Turned on for every layout, one that stays whole by default splits at its own cuts.
        val whole =
            LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF, rowBreaks = listOf(5))
        assertTrue(WideSurface.effectiveSplit(whole, SplitMode.ON)!!.isActive(1024.0))
        assertEquals(listOf(5), WideSurface.effectiveSplit(whole, SplitMode.ON)!!.rowBreaks)
        assertNull(WideSurface.effectiveSplit(whole, SplitMode.LAYOUT)?.takeIf { it.isActive(1024.0) })
    }

    @Test
    fun storedValuesFallBackToTheNeutralChoice() {
        assertEquals(KeyboardDock.CENTER, KeyboardDock.resolve(null))
        assertEquals(KeyboardDock.CENTER, KeyboardDock.resolve("bottom"))
        assertEquals(KeyboardDock.LEFT, KeyboardDock.resolve("left"))
        assertEquals(SplitMode.LAYOUT, SplitMode.resolve("nonsense"))
        assertEquals(SplitMode.ON, SplitMode.resolve("on"))
    }
}

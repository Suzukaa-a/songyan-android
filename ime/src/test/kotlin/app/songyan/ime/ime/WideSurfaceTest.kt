package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
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
        val centre = WideSurface.plan(1024.0, null, KeyboardContentDock.CENTER)
        assertEquals(600.0, centre.contentWidth, 1e-9)
        assertEquals(212.0, centre.leadingInset, 1e-9)
        assertEquals(212.0, centre.trailingInset, 1e-9)
        val left = WideSurface.plan(1024.0, null, KeyboardContentDock.LEFT)
        assertEquals(0.0, left.leadingInset, 1e-9)
        assertEquals(424.0, left.trailingInset, 1e-9)
        val right = WideSurface.plan(1024.0, null, KeyboardContentDock.RIGHT)
        assertEquals(424.0, right.leadingInset, 1e-9)
        assertEquals(0.0, right.trailingInset, 1e-9)
    }

    @Test
    fun aPhoneSizedSurfaceKeepsItsFullWidth() {
        for (dock in listOf(KeyboardContentDock.LEFT, KeyboardContentDock.CENTER, KeyboardContentDock.RIGHT)) {
            val plan = WideSurface.plan(411.0, null, dock)
            assertFalse(plan.active)
            assertEquals(411.0, plan.contentWidth, 1e-9)
        }
    }

    @Test
    fun anActiveSplitTakesTheWholeSurface() {
        val split = LayoutSplitConfiguration()
        val plan = WideSurface.plan(1024.0, split, KeyboardContentDock.RIGHT)
        assertFalse(plan.active)
        assertEquals(1024.0, plan.contentWidth, 1e-9)
        // The same split on a phone-sized surface is not active, so nothing changes there.
        assertFalse(WideSurface.plan(411.0, split, KeyboardContentDock.RIGHT).active)
    }

    @Test
    fun onlyASurfaceWiderThanTheSplitWidthGetsTheSideMargin() {
        // A phone, at or below the split width, keeps its full width whatever margin is chosen.
        val phone = WideSurface.plan(411.0, null, KeyboardContentDock.CENTER, sideMarginDp = 24)
        assertEquals(411.0, phone.contentWidth, 1e-9)
        assertEquals(0.0, phone.leadingInset, 1e-9)
        assertFalse(phone.active)
        assertEquals(0.0, WideSurface.sideMargin(600.0, 40, 600.0), 1e-9)
        assertEquals(40.0, WideSurface.sideMargin(601.0, 40, 600.0), 1e-9)
        // Lowering the split width makes the same phone large: it gets the margin.
        val lowered =
            WideSurface.plan(
                411.0,
                null,
                KeyboardContentDock.CENTER,
                automaticAboveDp = 400.0,
                sideMarginDp = 24,
            )
        assertEquals(363.0, lowered.contentWidth, 1e-9)
        assertEquals(24.0, lowered.leadingInset, 1e-9)
        assertEquals(24.0, lowered.trailingInset, 1e-9)
        // On a tablet the cap and the dock work on the width left after the margins.
        val tablet = WideSurface.plan(1024.0, null, KeyboardContentDock.LEFT, sideMarginDp = 40)
        assertEquals(600.0, tablet.contentWidth, 1e-9)
        assertEquals(40.0, tablet.leadingInset, 1e-9)
        assertEquals(344.0 + 40.0, tablet.trailingInset, 1e-9)
    }

    @Test
    fun theSideMarginNeverLeavesTheKeyAreaBelow280() {
        assertEquals(65.5, WideSurface.sideMargin(411.0, 96, 400.0), 1e-9)
        val plan =
            WideSurface.plan(
                411.0,
                null,
                KeyboardContentDock.CENTER,
                automaticAboveDp = 400.0,
                sideMarginDp = 96,
            )
        assertEquals(280.0, plan.contentWidth, 1e-9)
        assertEquals(65.5, plan.leadingInset, 1e-9)
    }

    @Test
    fun theSplitWidthDecidesTheSplitOnTheFullSurfaceSoAMarginNeverUndoesIt() {
        val split = LayoutSplitConfiguration()
        val lowered = WideSurface.plan(500.0, split, KeyboardContentDock.CENTER, automaticAboveDp = 450.0)
        assertTrue(lowered.contentWidth == 500.0)
        assertFalse(split.isActive(580.0, 580.0))
        assertTrue(split.isActive(581.0, 580.0))
        // 700 dp is above the default 600: it splits, and a 60 dp margin stays blank on both sides.
        val marginal = WideSurface.plan(700.0, split, KeyboardContentDock.CENTER, sideMarginDp = 60)
        assertEquals(580.0, marginal.contentWidth, 1e-9)
        assertEquals(60.0, marginal.leadingInset, 1e-9)
        val tablet = WideSurface.plan(1024.0, split, KeyboardContentDock.CENTER, sideMarginDp = 40)
        assertEquals(944.0, tablet.contentWidth, 1e-9)
        assertEquals(40.0, tablet.leadingInset, 1e-9)
    }

    @Test
    fun aSplitThatIsOffDoesNotTakeTheSurface() {
        val off = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF)
        assertEquals(600.0, WideSurface.plan(1024.0, off, KeyboardContentDock.CENTER).contentWidth, 1e-9)
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
    fun aPercentDocksTheKeyAreaThatShareOfTheFreeSpaceFromTheLeft() {
        val plan = WideSurface.plan(1000.0, null, KeyboardContentDock.of(25))
        assertEquals(100.0, plan.leadingInset, 1e-9)
        assertEquals(300.0, plan.trailingInset, 1e-9)
    }

    @Test
    fun storedValuesFallBackToTheNeutralChoice() {
        assertEquals(SplitMode.LAYOUT, SplitMode.resolve("nonsense"))
        assertEquals(SplitMode.ON, SplitMode.resolve("on"))
    }
}

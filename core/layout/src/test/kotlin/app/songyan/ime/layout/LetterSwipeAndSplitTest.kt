package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/LetterSwipeTrackerTests. */
class LetterSwipeTrackerTest {
    @Test
    fun tapDriftNeverPicks() {
        val tracker = LetterSwipeTracker(2)
        for ((dx, dy) in listOf(3.0 to 2.0, -6.0 to 4.0, 10.0 to -9.0, 17.0 to 1.0)) {
            tracker.update(dx, dy)
        }
        assertFalse(tracker.isLocked)
        assertNull(tracker.choice)
    }

    @Test
    fun leftAndRightPickTheMatchingLetter() {
        val left = LetterSwipeTracker(2)
        assertTrue(left.update(-20.0, 3.0))
        assertEquals(0, left.choice)
        val right = LetterSwipeTracker(2)
        assertTrue(right.update(22.0, -5.0))
        assertEquals(1, right.choice)
    }

    @Test
    fun mostlyVerticalTravelDoesNotLock() {
        val tracker = LetterSwipeTracker(2)
        tracker.update(20.0, 25.0)
        assertFalse(tracker.isLocked)
        assertNull(tracker.choice)
    }

    @Test
    fun lockedSwipeFollowsTheFingerAndCanReturnToATap() {
        val tracker = LetterSwipeTracker(2)
        tracker.update(20.0, 0.0)
        assertEquals(1, tracker.choice)
        // After the lock, vertical drift no longer matters.
        assertFalse(tracker.update(14.0, 30.0))
        assertEquals(1, tracker.choice)
        assertTrue(tracker.update(-12.0, 0.0))
        assertEquals(0, tracker.choice)
        assertTrue(tracker.update(3.0, 0.0))
        assertNull(tracker.choice)
        assertTrue(tracker.isLocked)
    }

    @Test
    fun singleLetterKeysNeverSwipe() {
        val tracker = LetterSwipeTracker(1)
        assertFalse(tracker.update(40.0, 0.0))
        assertNull(tracker.choice)
    }

    @Test
    fun theLockDistanceMatchesTheIosConstant() {
        // 18 dp lock, 8 dp tap fallback, matching the calibrated iOS values.
        assertEquals(18.0, LetterSwipeTracker.LOCK_DISTANCE, 1e-9)
        assertEquals(8.0, LetterSwipeTracker.RETURN_DISTANCE, 1e-9)
        val exactly = LetterSwipeTracker(2)
        assertFalse(exactly.update(17.9, 0.0))
        assertFalse(exactly.isLocked)
        val locked = LetterSwipeTracker(2)
        assertTrue(locked.update(18.0, 0.0))
        assertTrue(locked.isLocked)
    }

    @Test
    fun nonFiniteOffsetsAreIgnored() {
        val tracker = LetterSwipeTracker(2)
        assertFalse(tracker.update(Double.NaN, 0.0))
        assertFalse(tracker.update(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(tracker.update(0.0, Double.NaN))
        assertFalse(tracker.isLocked)
        assertNull(tracker.choice)
    }

    @Test
    fun aThreeLetterKeyPicksTheEdgeLettersOnly() {
        val tracker = LetterSwipeTracker(3)
        assertTrue(tracker.update(-20.0, 0.0))
        assertEquals(0, tracker.choice)
        assertTrue(tracker.update(20.0, 0.0))
        assertEquals(2, tracker.choice)
    }
}

/** Ported from LayoutKitTests/LayoutSplitConfigurationTests. */
class LayoutSplitConfigurationTest {
    @Test
    fun standardRowsKeepOneCharacterWidthAtPhoneAndTabletWidths() {
        for (width in listOf(280.0, 320.0, 393.0, 600.0, 768.0, 1024.0, 1366.0)) {
            for (
            split in
            listOf(
                null,
                LayoutSplitConfiguration.of(rowBreaks = listOf(5, 4, 3), gapFraction = 0.24),
            )
            ) {
                val geometry = StandardKeyRowGeometry.resolve(width, split)
                assertEquals(width, geometry.keyWidth * 10 + 36 + geometry.gap, 0.001)
                assertEquals(
                    width,
                    geometry.keyWidth * 7 + geometry.thirdRowSideWidth * 2 + 32 + geometry.gap,
                    0.001,
                )
                assertTrue(geometry.keyWidth > 0.0)
                assertTrue(geometry.thirdRowSideWidth > 0.0)
            }
        }
    }

    @Test
    fun splitRetractsInFloatingWidthAndReturnsOnWideSurface() {
        val split = LayoutSplitConfiguration.of(gapFraction = 0.4)
        assertEquals(0.0, split.gap(300.0, keyCount = 10), 1e-9)
        assertTrue(split.gap(768.0, keyCount = 10) > 200.0)
        assertEquals(4, split.breakIndex(row = 0, keyCount = 7))
    }

    @Test
    fun automaticSpacingKeepsLargeTabletKeysWithinThumbReach() {
        val split = LayoutSplitConfiguration()
        for (width in listOf(834.0, 1024.0, 1194.0, 1366.0)) {
            val geometry = StandardKeyRowGeometry.resolve(width, split)
            assertTrue((width - geometry.gap) / 2 <= LayoutSplitConfiguration.MAXIMUM_THUMB_HALF_WIDTH + 1e-9)
            assertTrue(geometry.keyWidth >= 32.0)
        }
    }

    @Test
    fun automaticSpacingAdaptsAcrossFoldedExpandedPortraitAndLandscapeWidths() {
        val split = LayoutSplitConfiguration()
        val widths = listOf(320.0, 393.0, 466.0, 669.0, 744.0, 834.0, 1133.0, 1194.0, 1366.0)
        val gaps = widths.map { split.gap(it, keyCount = 10) }
        assertEquals(0.0, gaps[0], 1e-9)
        assertEquals(0.0, gaps[1], 1e-9)
        assertEquals(0.0, gaps[2], 1e-9)
        for (index in 4 until gaps.size) assertTrue(gaps[index] > gaps[index - 1])
        assertTrue(gaps[5] > 834 * 0.3)
        val forced = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        assertEquals(110.0, forced.gap(466.0, keyCount = 10), 0.001)
    }

    @Test
    fun splitActivationDecidesWhetherANarrowSurfaceSplits() {
        val automatic = LayoutSplitConfiguration(rowBreaks = listOf(5))
        assertFalse(automatic.isActive(466.0))
        assertTrue(automatic.isActive(834.0))
        assertNull(automatic.resolvedForWidth(466.0))
        assertNull(automatic.breakIndex(row = 0, keyCount = 10, width = 466.0))
        val resolved = automatic.resolvedForWidth(834.0)!!
        assertEquals(LayoutSplitConfiguration.Activation.ALWAYS, resolved.activation)
        assertEquals(LayoutSplitConfiguration.Activation.AUTOMATIC, automatic.activation)
        assertTrue(resolved.gap(400.0, keyCount = 10) > 0.0)
        val forced = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        assertTrue(forced.isActive(320.0))
        assertEquals(4, forced.breakIndex(row = 0, keyCount = 7, width = 320.0))
        val disabled = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF)
        assertFalse(disabled.isActive(1024.0))
    }

    @Test
    fun automaticSpacingKeepsThreeColumnDigitsFromStretchingOnTablets() {
        val split = LayoutSplitConfiguration()
        for (width in listOf(834.0, 1194.0, 1366.0)) {
            val gap = split.gap(width, keyCount = 3)
            val digitWidth = (width - gap - 8) / 3
            assertTrue(digitWidth >= 32.0)
            assertTrue(digitWidth <= PhoneKeypadMetrics.PREFERRED_DIGIT_WIDTH + 0.001)
        }
        for (width in listOf(320.0, 466.0)) assertEquals(0.0, split.gap(width, keyCount = 3), 1e-9)
        val forced = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        for (width in listOf(466.0, 669.0)) {
            val gap = forced.gap(width, keyCount = 3)
            val digitWidth = (width - gap - 8) / 3
            assertTrue(digitWidth >= 32.0)
            assertTrue(digitWidth <= PhoneKeypadMetrics.PREFERRED_DIGIT_WIDTH + 0.001)
        }
    }

    @Test
    fun pagesFollowMainSpacingUnlessExplicitlyOverridden() {
        val main = LayoutSplitConfiguration(rowBreaks = listOf(5, 5, 4, 3))
        val inherited = LayoutSplitConfiguration.resolving(page = null, main = main)!!
        assertEquals(LayoutSplitConfiguration.Activation.AUTOMATIC, inherited.activation)
        assertTrue(inherited.rowBreaks.isEmpty())
        val disabled = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF)
        assertEquals(disabled, LayoutSplitConfiguration.resolving(page = disabled, main = main))
        val custom = LayoutSplitConfiguration.of(rowBreaks = listOf(4, 6, 5), gapFraction = 0.4)
        assertEquals(custom, LayoutSplitConfiguration.resolving(page = custom, main = main))
        assertNull(LayoutSplitConfiguration.resolving(page = null, main = null))
    }

    @Test
    fun importedSplitRejectsInvalidGapsAndRowBoundaries() {
        for (fraction in listOf(-0.1, 0.86, Double.NaN, Double.POSITIVE_INFINITY)) {
            val split = LayoutSplitConfiguration.of(gapFraction = fraction)
            org.junit.Assert.assertThrows(LayoutException::class.java) {
                split.validate(listOf(10))
            }
        }
        org.junit.Assert.assertThrows(LayoutException::class.java) {
            LayoutSplitConfiguration(rowBreaks = listOf(10)).validate(listOf(10))
        }
        org.junit.Assert.assertThrows(LayoutException::class.java) {
            LayoutSplitConfiguration(rowBreaks = listOf(0)).validate(listOf(10))
        }
        org.junit.Assert.assertThrows(LayoutException::class.java) {
            LayoutSplitConfiguration(rowBreaks = listOf(5, 2)).validate(listOf(10))
        }
    }

    @Test
    fun splitOnlyHappensWhenTheUserTurnsItOn() {
        assertNull(LayoutSplitConfiguration.resolving(page = null, main = null))
        val unsplit = StandardKeyRowGeometry.resolve(834.0, null)
        assertEquals(0.0, unsplit.gap, 1e-9)
        assertEquals(834.0, unsplit.keyWidth * 10 + 36, 0.001)
        for (width in listOf(320.0, 834.0)) {
            assertFalse(LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF).isActive(width))
        }
        val automatic = LayoutSplitConfiguration()
        assertFalse(automatic.isActive(600.0))
        assertTrue(automatic.isActive(601.0))
        assertNull(automatic.resolvedForWidth(600.0))
        assertNotNull(automatic.resolvedForWidth(601.0))
    }

    @Test
    fun theAutomaticThresholdIsTheUsersAndEqualWidthDoesNotSplit() {
        val automatic = LayoutSplitConfiguration()
        assertFalse(automatic.isActive(400.0, automaticAboveDp = 400.0))
        assertTrue(automatic.isActive(401.0, automaticAboveDp = 400.0))
        assertTrue(automatic.isActive(601.0, automaticAboveDp = 400.0))
        assertFalse(automatic.isActive(1200.0, automaticAboveDp = 1200.0))
        assertTrue(automatic.isActive(1200.5, automaticAboveDp = 1200.0))
        assertNull(automatic.resolvedForWidth(700.0, automaticAboveDp = 700.0))
        assertNotNull(automatic.resolvedForWidth(701.0, automaticAboveDp = 700.0))
        // The threshold concerns the automatic rule only.
        assertTrue(
            LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS).isActive(100.0, 1200.0),
        )
        assertFalse(
            LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF).isActive(2000.0, 400.0),
        )
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)
}

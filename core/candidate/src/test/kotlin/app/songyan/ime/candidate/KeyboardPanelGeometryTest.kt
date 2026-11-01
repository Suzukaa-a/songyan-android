package app.songyan.ime.candidate

import org.junit.Test
import kotlin.math.abs

/** Panel geometry: fixed height budget, contiguous bands, fitting, height presets. */
class KeyboardPanelGeometryTest {
    private val geometry =
        KeyboardPanelGeometry.create(
            candidateHeight = 44.0,
            keyHeight = 54.0,
            selectedMainRows = KeyboardPanelGeometry.STANDARD_MAIN_ROW_COUNT,
        )

    @Test
    fun standardMainRowCountIsThreeNoMatterTheRequest() {
        assertEquals(3, geometry.visibleMainRows)
        for (requested in listOf(0, 1, 2, 3, 4)) {
            val created =
                KeyboardPanelGeometry.create(
                    candidateHeight = 44.0,
                    keyHeight = 54.0,
                    selectedMainRows = requested,
                )
            assertEquals(3, created.visibleMainRows, "requested " + requested)
        }
    }

    @Test
    fun panelHeightIsHeaderPlusBase() {
        val plan = geometry.layoutPlan(showsHeader = true)
        assertEquals(geometry.headerHeight, plan.headerHeight)
        assertEquals(geometry.panelHeight, plan.panelHeight, 0.001)
        // The candidate area absorbs the panel's top padding, so the full height is the base height
        // plus the header minus that padding (plus the candidate top padding, which is 0).
        assertEquals(
            geometry.headerHeight,
            plan.panelHeight -
                geometry.layoutPlan(showsHeader = false).panelHeight +
                KeyboardPanelGeometry.VERTICAL_PADDING -
                KeyboardPanelGeometry.CANDIDATE_TOP_PADDING,
            0.001,
            "the full height equals the base height plus the header",
        )
    }

    @Test
    fun bandsAreContiguousAndMeetExactly() {
        val plan = geometry.layoutPlan(showsHeader = true)
        assertTrue(plan.bandsAreContiguous, "bands must meet exactly with no shared pixel row")
        assertEquals(plan.contentTop, plan.readingRowFrame.minY, 0.001)
        assertEquals(plan.readingRowFrame.maxY, plan.headerFrame.minY, 0.001)
        assertEquals(plan.headerFrame.maxY, plan.mainAreaFrame.minY, 0.001)
        assertEquals(
            plan.controlRowFrame.minY,
            plan.mainAreaFrame.maxY + KeyboardPanelGeometry.CONTROL_SPACING,
            0.001,
        )
        assertEquals(plan.panelHeight, plan.contentBottom, 0.001)
    }

    @Test
    fun distanceFromThePanelBottomDoesNotDependOnCandidateState() {
        val withCandidateArea = geometry.layoutPlan(showsHeader = true)
        val withoutCandidateArea = geometry.layoutPlan(showsHeader = false)
        // Bottom-anchored hosting: a band's distance from the bottom is state independent.
        assertEquals(
            withCandidateArea.distanceToPanelBottomFromTop(withCandidateArea.mainAreaFrame),
            withoutCandidateArea.distanceToPanelBottomFromTop(withoutCandidateArea.mainAreaFrame),
            0.001,
        )
        assertEquals(
            withCandidateArea.distanceToPanelBottomFromBottom(withCandidateArea.controlRowFrame),
            withoutCandidateArea.distanceToPanelBottomFromBottom(withoutCandidateArea.controlRowFrame),
            0.001,
        )
    }

    @Test
    fun firstKeyRowTopEqualsMainAreaTop() {
        val plan = geometry.layoutPlan(showsHeader = true)
        assertEquals(plan.mainAreaFrame.minY, plan.firstKeyRowTop, 0.001)
        assertEquals(
            KeyboardPanelGeometry.CANDIDATE_TOP_PADDING + plan.readingRowHeight + plan.headerHeight,
            plan.firstKeyRowTop,
            0.001,
            "the first key row top is padding + reading row + header, constant across candidate states",
        )
    }

    @Test
    fun fixedPanelHeightKeepsTheCandidateBudgetInEveryState() {
        val fixed =
            KeyboardPanelGeometry.fixedPanelHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
            )
        val plan =
            KeyboardPanelGeometry
                .create(
                    candidateHeight = 44.0,
                    keyHeight = 54.0,
                    selectedMainRows = 3,
                    candidateSize = 21.0,
                ).layoutPlan(showsHeader = true)
        assertEquals(plan.panelHeight, fixed, 0.001)
        // Without the candidate area the shell loses the header band but regains the top padding the
        // candidate area had absorbed.
        val withoutArea =
            KeyboardPanelGeometry.fixedPanelHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateAreaReserved = false,
                candidateSize = 21.0,
            )
        assertEquals(
            plan.panelHeight - plan.configuredHeaderHeight + KeyboardPanelGeometry.VERTICAL_PADDING,
            withoutArea,
            0.001,
        )
    }

    @Test
    fun presentationShellHeightAddsTheCandidateAreaBonus() {
        val shell =
            KeyboardPanelGeometry.presentationShellHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
                readingRow = false,
            )
        val fixed =
            KeyboardPanelGeometry.fixedPanelHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
            )
        assertEquals(fixed + KeyboardPanelGeometry.CANDIDATE_AREA_BONUS, shell, 0.001)

        // A reading row gets less than the full bonus: its share and the space under it are taken out.
        val withReading =
            KeyboardPanelGeometry.presentationShellHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
                readingRow = true,
            )
        val readingHeight = CandidateReadingRowPolicy.height(reserved = true)
        assertEquals(
            fixed + readingHeight + KeyboardPanelGeometry.CANDIDATE_AREA_BONUS -
                KeyboardPanelGeometry.READING_LAYOUT_BONUS_CUT,
            withReading,
            0.001,
        )
        assertTrue(withReading > shell, "a reading row still makes the shell taller than without one")
    }

    @Test
    fun fitKeepsKeyHeightWhenTheShellFitsAndGivesExtraHeightToTheCandidateArea() {
        val shell =
            KeyboardPanelGeometry.fixedPanelHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
            ) + KeyboardPanelGeometry.CANDIDATE_AREA_BONUS
        val fit =
            KeyboardPanelGeometry.fit(
                shellHeight = shell,
                candidateHeight = 44.0,
                standardKeyHeight = 54.0,
                readingRowReserved = false,
                candidateAreaReserved = true,
                candidateSize = 21.0,
            )
        assertEquals(54.0, fit.keyHeight, 0.001, "key rows never exceed the standard key height")
        assertTrue(
            fit.headerHeight > CandidateBarDefinition.Collapsed.effectiveHeight(44.0, 21.0),
            "the spare height goes to the candidate area",
        )
        assertEquals(0.0, fit.topSlack, 0.001)
    }

    @Test
    fun fitSqueezesTheReadingRowBeforeTheKeyRows() {
        val tight =
            KeyboardPanelGeometry.fit(
                shellHeight = 200.0,
                candidateHeight = 44.0,
                standardKeyHeight = 54.0,
                readingRowReserved = true,
                candidateAreaReserved = true,
                candidateSize = 21.0,
            )
        assertTrue(tight.keyHeight <= 54.0)
        assertTrue(tight.readingRowHeight >= CandidateReadingRowPolicy.MINIMUM_HEIGHT)
        assertTrue(
            tight.readingRowHeight < CandidateReadingRowPolicy.HEIGHT,
            "the reading row gives up height before the key rows do",
        )
    }

    @Test
    fun fitWithoutACandidateAreaKeepsTheSlackAtTheTop() {
        val fit =
            KeyboardPanelGeometry.fit(
                shellHeight = 400.0,
                candidateHeight = 44.0,
                standardKeyHeight = 54.0,
                readingRowReserved = false,
                candidateAreaReserved = false,
                candidateSize = 21.0,
            )
        assertEquals(0.0, fit.headerHeight, 0.001)
        assertTrue(fit.topSlack > 0.0, "a page without a candidate area keeps the extra height as top blank space")
        assertEquals(54.0, fit.keyHeight, 0.001)
    }

    @Test
    fun candidateTextCentreSitsHalfTheRowAboveTheKeys() {
        assertEquals(
            CandidateBarDefinition.Collapsed.effectiveHeight(44.0, 21.0) / 2 +
                KeyboardPanelGeometry.CANDIDATE_BOTTOM_SPACE,
            KeyboardPanelGeometry.candidateTextCentreAboveKeys(candidateHeight = 44.0, candidateSize = 21.0),
            0.001,
        )
    }

    @Test
    fun heightPresetsResolveAndClampToBoundedKeyHeights() {
        assertEquals(KeyboardHeightPreset.STANDARD, KeyboardHeightPreset.resolve(null))
        assertEquals(KeyboardHeightPreset.STANDARD, KeyboardHeightPreset.resolve(""))
        assertEquals(KeyboardHeightPreset.STANDARD, KeyboardHeightPreset.resolve("Standard"))
        assertEquals(KeyboardHeightPreset.COMPACT, KeyboardHeightPreset.resolve("compact"))
        assertEquals(KeyboardHeightPreset.COMFORTABLE, KeyboardHeightPreset.resolve("comfortable"))
        assertEquals(48.0, KeyboardHeightPreset.COMPACT.keyHeight(compact = false))
        assertEquals(54.0, KeyboardHeightPreset.STANDARD.keyHeight(compact = false))
        assertEquals(60.0, KeyboardHeightPreset.COMFORTABLE.keyHeight(compact = false))
        for (preset in KeyboardHeightPreset.entries) {
            assertEquals(44.0, preset.keyHeight(compact = true), "a compact-height surface uses the shorter row")
        }
    }

    @Test
    fun presentationBudgetDerivesKeyHeightAndShell() {
        val budget =
            KeyboardPanelPresentationBudget(
                candidateHeight = 44.0,
                candidateSize = 21.0,
                readingRow = false,
                candidateArea = true,
                compact = false,
                heightPreset = KeyboardHeightPreset.STANDARD,
            )
        assertEquals(54.0, budget.keyHeight, 0.001)
        assertEquals(
            KeyboardPanelGeometry.presentationShellHeight(
                candidateHeight = 44.0,
                keyHeight = 54.0,
                candidateSize = 21.0,
                readingRow = false,
            ),
            budget.shellHeight,
            0.001,
        )
        assertEquals(44.0, budget.withCompact(true).keyHeight, 0.001)
    }

    @Test
    fun panelVerticalFrameIntersectsOnlyWhenItOverlaps() {
        val a = PanelVerticalFrame(top = 0.0, height = 10.0)
        assertTrue(a.intersects(PanelVerticalFrame(top = 5.0, height = 10.0)))
        assertFalse(a.intersects(PanelVerticalFrame(top = 10.0, height = 10.0)), "touching edges do not overlap")
        assertEquals(10.0, a.maxY, 0.001)
        assertTrue(abs(a.minY) < 1e-9)
    }
}

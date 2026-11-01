package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateLayoutsTest {
    private val start = 2f
    private val end = 2f
    private val gap = 0f
    private val min = 44f

    private fun row(
        widths: List<Float>,
        available: Float,
    ) = CollapsedRowLayout.layout(widths, available, start, end, gap, min)

    @Test
    fun aShortListKeepsItsOwnWidthsFromTheStartAndLeavesTheRestEmpty() {
        // Five short candidates on a 370 px row keep their natural widths instead of being stretched.
        val natural = listOf(52f, 60f, 70f, 56f, 64f)
        val result = row(natural, available = 370f)
        assertEquals(natural, result.widths)
        assertEquals(start, result.lefts[0], 0.001f)
        assertEquals(natural.sum() + start + end, result.contentWidth, 0.001f)
        assertTrue("the strip ends well before the row does", result.contentWidth < 370f)
    }

    @Test
    fun cellsTouchSoNoTapFallsBetweenTwoCandidates() {
        val result = row(listOf(52f, 60f, 70f), available = 370f)
        for (i in 1 until result.lefts.size) {
            assertEquals(result.lefts[i - 1] + result.widths[i - 1], result.lefts[i], 0.001f)
        }
    }

    @Test
    fun aListLongerThanTheRowScrollsByItsWholeWidth() {
        val natural = List(12) { 60f }
        val result = row(natural, available = 300f)
        assertEquals(natural, result.widths)
        assertEquals(12 * 60f + start + end, result.contentWidth, 0.001f)
        assertTrue("content must exceed the row so it scrolls", result.contentWidth > 300f)
    }

    @Test
    fun appendingAPageExtendsTheStripWithoutMovingWhatIsShown() {
        val first = List(5) { 50f + it }
        val more = first + List(5) { 80f }
        val before = row(first, available = 300f)
        val after = row(more, available = 300f)
        assertEquals(before.lefts, after.lefts.take(5))
        assertEquals(before.widths, after.widths.take(5))
        assertEquals(before.contentWidth + 5 * 80f, after.contentWidth, 0.001f)
    }

    @Test
    fun aSingleCharacterIsNeverNarrowerThanTheMinimum() {
        val result = row(listOf(18f, 38f, 18f), available = 200f)
        assertTrue(result.widths.all { it == min })
    }

    @Test
    fun onlyACandidateWiderThanTheWholeRowIsCut() {
        val result = row(listOf(900f, 60f), available = 300f)
        assertEquals(300f - start - end, result.widths[0], 0.001f)
        assertEquals(60f, result.widths[1], 0.001f)
    }

    @Test
    fun aConfiguredSpacingSeparatesCells() {
        val result = CollapsedRowLayout.layout(listOf(50f, 50f, 50f), 300f, start, end, gap = 6f, minWidth = min)
        assertEquals(start + 56f, result.lefts[1], 0.001f)
        assertEquals(start + 3 * 50f + 2 * 6f + end, result.contentWidth, 0.001f)
    }

    @Test
    fun anEmptyStripIsOnlyItsInsets() {
        assertEquals(start + end, row(emptyList(), available = 300f).contentWidth, 0.001f)
    }

    @Test
    fun theExpandedGridWrapsWhenARowIsFull() {
        val result =
            ExpandedFlowLayout.layout(
                listOf(100f, 100f, 100f, 100f),
                available = 250f,
                gap = 8f,
                rowHeight = 48f,
                minWidth = 40f,
            )
        val rows = result.cells.groupBy { it.top }
        assertEquals(2, rows.size)
        assertEquals(2, rows.getValue(0f).size)
        assertEquals(96f, result.contentHeight, 0.001f)
    }

    @Test
    fun aLongCandidateTakesItsOwnRowCappedToTheGrid() {
        val result =
            ExpandedFlowLayout.layout(
                listOf(60f, 900f, 60f),
                available = 300f,
                gap = 8f,
                rowHeight = 48f,
                minWidth = 40f,
            )
        assertEquals(
            3,
            result.cells
                .map { it.top }
                .toSet()
                .size,
        )
        assertEquals(300f, result.cells[1].right - result.cells[1].left, 0.001f)
    }

    @Test
    fun theGridNeverLosesACandidate() {
        val natural = List(37) { 40f + (it % 5) * 30f }
        val result = ExpandedFlowLayout.layout(natural, available = 320f, gap = 8f, rowHeight = 48f, minWidth = 40f)
        assertEquals(natural.size, result.cells.size)
        assertEquals((0 until natural.size).toList(), result.cells.map { it.index })
        assertTrue(result.cells.all { it.right <= 320f + 0.001f })
    }

    @Test
    fun onlyTheRowsInTheViewportAreVisible() {
        val result =
            ExpandedFlowLayout.layout(
                List(40) { 100f },
                available = 250f,
                gap = 8f,
                rowHeight = 48f,
                minWidth = 40f,
            )
        val shown = ExpandedFlowLayout.visible(result.cells, scroll = 96f, viewport = 100f)
        assertTrue(shown.all { it.bottom > 96f && it.top < 196f })
        assertTrue(shown.isNotEmpty())
    }

    @Test
    fun aColumnGridPlacesCellsInEqualColumnsAndWrapsRows() {
        val result =
            ExpandedFlowLayout.columns(
                count = 5,
                columns = 2,
                available = 200f,
                gap = 4f,
                rowHeight = 48f,
                rowGap = 2f,
            )
        assertEquals(98f, result.cells[0].right - result.cells[0].left, 0.001f)
        assertEquals(102f, result.cells[1].left, 0.001f)
        assertEquals(50f, result.cells[2].top, 0.001f)
        // Three rows: the gap is only between rows.
        assertEquals(3 * 48f + 2 * 2f, result.contentHeight, 0.001f)
    }

    @Test
    fun aListIsOneFullWidthColumn() {
        val result = ExpandedFlowLayout.columns(count = 3, columns = 1, available = 300f, gap = 0f, rowHeight = 48f)
        assertTrue(result.cells.all { it.left == 0f && it.right == 300f })
        assertEquals(144f, result.contentHeight, 0.001f)
    }

    @Test
    fun theFlowKeepsItsRowGap() {
        val result =
            ExpandedFlowLayout.layout(
                listOf(120f, 120f),
                available = 150f,
                gap = 0f,
                rowHeight = 48f,
                minWidth = 40f,
                rowGap = 6f,
            )
        assertEquals(54f, result.cells[1].top, 0.001f)
        assertEquals(102f, result.contentHeight, 0.001f)
    }

    // ---- inline readings beside the strip ------------------------------------------------------

    @Test
    fun fewReadingsTakeExactlyTheirOwnWidth() {
        val sections = InlineStrip.sections(listOf(50f, 60f), start = 2f, available = 360f)
        assertEquals(112f, sections.readingWidth, 0.001f)
        assertEquals(0f, sections.readingMaxScroll, 0.001f)
    }

    @Test
    fun manyReadingsAreCappedAtAThirdOfTheRowAndScrollOnTheirOwn() {
        val widths = List(8) { 50f }
        val sections = InlineStrip.sections(widths, start = 2f, available = 360f)
        assertEquals(120f, sections.readingWidth, 0.001f)
        assertEquals(2f + 8 * 50f - 120f, sections.readingMaxScroll, 0.001f)
        // The candidates keep the other two thirds and their own extent, whatever the readings need.
        val candidates = CollapsedRowLayout.layout(List(10) { 50f }, 360f - sections.readingWidth, 6f, 2f, 0f, 44f)
        assertEquals(6f + 10 * 50f + 2f, candidates.contentWidth, 0.001f)
        assertEquals(6f, candidates.lefts[0], 0.001f)
    }

    @Test
    fun twoReadingsAlwaysFitWhenAThirdIsTooNarrow() {
        val sections = InlineStrip.sections(listOf(70f, 80f, 60f), start = 2f, available = 360f)
        assertEquals(152f, sections.readingWidth, 0.001f)
    }

    @Test
    fun theReadingsNeverTakeMoreThanHalfTheRow() {
        val sections = InlineStrip.sections(listOf(150f, 150f, 60f), start = 2f, available = 360f)
        assertEquals(180f, sections.readingWidth, 0.001f)
    }

    @Test
    fun noReadingsNoSection() {
        val sections = InlineStrip.sections(emptyList(), start = 2f, available = 360f)
        assertEquals(0f, sections.readingWidth, 0.001f)
        assertEquals(0f, sections.readingMaxScroll, 0.001f)
    }

    @Test
    fun eachSectionHitTestsWithItsOwnScroll() {
        val lefts = listOf(2f, 52f, 102f, 152f)
        val widths = List(4) { 50f }
        assertEquals(1, InlineStrip.hit(60f, scroll = 0f, lefts = lefts, widths = widths))
        // Scrolled by 100 px, the same point is two cells later.
        assertEquals(3, InlineStrip.hit(60f, scroll = 100f, lefts = lefts, widths = widths))
        assertEquals(-1, InlineStrip.hit(1f, scroll = 0f, lefts = lefts, widths = widths))
        // Candidate cells start right after the hairline, in view coordinates, with a scroll of their own.
        val candidateLefts = listOf(126f, 176f)
        assertEquals(0, InlineStrip.hit(130f, scroll = 0f, lefts = candidateLefts, widths = listOf(50f, 50f)))
        assertEquals(1, InlineStrip.hit(130f, scroll = 50f, lefts = candidateLefts, widths = listOf(50f, 50f)))
    }
}

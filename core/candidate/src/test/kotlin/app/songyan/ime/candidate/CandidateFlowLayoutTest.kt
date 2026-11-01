package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.Candidate
import org.junit.Test

/** Collapsed strip and expanded adaptive flow layout, plus the text-width estimator. */
class CandidateFlowLayoutTest {
    private val typography = CandidateTypography(candidateSize = 20.0, secondarySize = 10.0)

    private fun entry(
        text: String,
        comment: String = "",
        page: Int = 0,
        local: Int = 0,
        selectKey: String? = null,
        selectLabel: String? = null,
    ) = BrowserCandidate(
        pageIndex = page,
        pageLocalIndex = local,
        candidate =
            Candidate(
                index = local,
                text = text,
                comment = comment,
                selectKey = selectKey,
                selectLabel = selectLabel,
            ),
    )

    // MARK: Text metrics

    @Test
    fun textMetricsTreatCjkAsFullEmAndLatinAsSixtyTwoPercent() {
        assertEquals(0.0, CandidateTextMetrics.estimatedWidth("", 20.0))
        assertEquals(2 * 20 * 0.62, CandidateTextMetrics.estimatedWidth("ab", 20.0), 0.001)
        assertTrue(CandidateTextMetrics.estimatedWidth("汉", 20.0) > CandidateTextMetrics.estimatedWidth("a", 20.0))
        assertEquals(0.0, CandidateTextMetrics.estimatedWidth("汉", 0.0))
    }

    @Test
    fun textMetricsCountCodePointsNotUtf16Units() {
        // An emoji is one scalar: it must measure one em, not two (iOS counts per Character).
        assertEquals(20.0, CandidateTextMetrics.estimatedWidth("\uD83D\uDE00", 20.0), 0.001)
        assertEquals(20.0, CandidateTextMetrics.estimatedWidth("\uD840\uDC00", 20.0), 0.001)
        assertEquals(40.0, CandidateTextMetrics.estimatedWidth("\uD83D\uDE00\uD83D\uDE00", 20.0), 0.001)
        // Two emoji and two latin letters: 2 em + 2 * 0.62 em.
        assertEquals(
            2 * 20.0 + 2 * 20.0 * 0.62,
            CandidateTextMetrics.estimatedWidth("\uD83D\uDE00\uD83D\uDE00ab", 20.0),
            0.001,
        )
    }

    // MARK: Collapsed strip

    @Test
    fun threeCharWordGetsIntrinsicWidthNotFixed44() {
        val intrinsic =
            CandidateCollapsedFlow.intrinsicCellWidth(
                text = "早上好",
                comment = "",
                selectionBadge = null,
                typography = typography,
                cellPaddingX = 12.0,
            )
        // 3 x 20 + 2 x 12 + slack = 88; a fixed 44 would truncate the word to one character plus an ellipsis.
        assertEquals(88.0, intrinsic, 0.001)
        assertTrue(intrinsic > 60.0, "must at least hold the three CJK glyphs themselves")

        val entries =
            listOf("早上好", "最", "造成", "早上", "最长", "噪声", "最后")
                .mapIndexed { offset, text -> entry(text, local = offset) }
        val layout =
            CandidateCollapsedFlow.layout(
                entries = entries,
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 356.0,
            )
        assertTrue(layout.scrolls, "when several do not fit it must scroll rather than shrink or truncate")
        assertEquals(entries.size, layout.cellWidths.size)
        assertEquals(88.0, layout.cellWidths[0], 0.001, "the top three-character word keeps its full width")
        assertTrue(layout.cellWidths.all { it >= 44.0 }, "not below the touch minimum")
    }

    @Test
    fun fewShortCandidatesFillEvenly() {
        val entries = listOf("的", "是", "在").mapIndexed { offset, text -> entry(text, local = offset) }
        val layout =
            CandidateCollapsedFlow.layout(
                entries = entries,
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 356.0,
            )
        assertFalse(layout.scrolls, "three single characters should fill at equal width without scrolling")
        assertEquals(1, layout.cellWidths.toSet().size, "equal width")
        val even = layout.cellWidths[0]
        assertTrue(even >= 44.0)
        assertTrue(even <= CandidateCollapsedFlow.MAXIMUM_EVEN_CELL_WIDTH)
        assertEquals(356.0, even * 3 + 4.0 * 2, 0.5)
    }

    @Test
    fun compactCollapsedWidthsStayStableWhenCandidatesAppend() {
        val collapsed = CandidateBarDefinition.Collapsed(cellPaddingX = 7.0, cellSpacing = 0.0)
        val entries = listOf("早上好", "早上", "早").mapIndexed { offset, text -> entry(text, local = offset) }
        val initial =
            CandidateCollapsedFlow.layout(
                entries = entries,
                collapsed = collapsed,
                typography = typography,
                availableWidth = 356.0,
                fillsAvailableWidth = false,
            )
        val appended =
            CandidateCollapsedFlow.layout(
                entries = entries + entry("长".repeat(20), local = 3),
                collapsed = collapsed,
                typography = typography,
                availableWidth = 356.0,
                fillsAvailableWidth = false,
            )
        assertFalse(initial.scrolls)
        assertTrue(appended.scrolls)
        assertEquals(initial.cellWidths, appended.cellWidths.take(3))
        assertEquals(listOf(78.0, 58.0, 44.0), initial.cellWidths)
    }

    @Test
    fun longWordKeepsIntrinsicWidthAndIsNotShrunk() {
        val long = "汉".repeat(8) // 160 + 24 + slack
        val entries = listOf(entry("的", local = 0), entry(long, local = 1))
        val layout =
            CandidateCollapsedFlow.layout(
                entries = entries,
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 356.0,
            )
        assertEquals(188.0, layout.cellWidths[1], 0.001, "the long word uses its intrinsic width and is not shrunk")
        assertFalse(layout.scrolls, "total 44+188+4 <= 356, no scrolling needed (scrolls reflects real overflow)")

        val overflow =
            CandidateCollapsedFlow.layout(
                entries = listOf(entry(long, local = 0), entry(long, local = 1)),
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 356.0,
            )
        assertTrue(overflow.scrolls, "scroll only when the total 188+188+4 > 356")
        assertEquals(listOf(188.0, 188.0), overflow.cellWidths)
    }

    @Test
    fun overLongCellExceedsViewportButStaysBounded() {
        val long = "汉".repeat(40) // 800 + 24 + 4 = 828
        val layout =
            CandidateCollapsedFlow.layout(
                entries = listOf(entry(long, local = 0)),
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 300.0,
            )
        assertTrue(layout.scrolls, "an 828 cell over a 300 viewport means a real horizontal offset")
        assertEquals(828.0, layout.cellWidths[0], 0.001, "uses the full intrinsic text width, not capped at 300")

        val extreme = "汉".repeat(1000)
        val bounded =
            CandidateCollapsedFlow.layout(
                entries = listOf(entry(extreme, local = 0)),
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 300.0,
            )
        assertTrue(bounded.cellWidths[0].isFinite())
        assertEquals(CandidateCollapsedFlow.maximumCellWidth(availableWidth = 300.0), bounded.cellWidths[0], 0.001)
        assertEquals(1200.0, bounded.cellWidths[0], 0.001)
        assertTrue(bounded.scrolls)
    }

    @Test
    fun anUnconstrainedWidthKeepsTheIntrinsicCellWidth() {
        val long = "汉".repeat(20) // 20 em = 400, plus padding and slack
        val layout =
            CandidateCollapsedFlow.layout(
                entries = listOf(entry(long, local = 0)),
                collapsed = CandidateBarDefinition.Collapsed(fillsAvailableWidth = false),
                typography = typography,
                availableWidth = Double.POSITIVE_INFINITY,
            )
        assertTrue(
            layout.cellWidths.single() > 400.0,
            "an unconstrained layout must not clamp the guard rail to 44 dp",
        )
        assertFalse(layout.scrolls)
        // The near-MAX_VALUE sentinel used by the regular path behaves the same way.
        assertTrue(
            CandidateCollapsedFlow
                .maximumCellWidth(availableWidth = Double.MAX_VALUE) > 1_000_000.0,
        )
    }

    @Test
    fun anUnconstrainedExpandedWidthUsesTheIntrinsicOwnRowWidth() {
        val long = "汉".repeat(20)
        val candidates = listOf(entry(long, local = 0))
        val rows =
            CandidateExpandedFlow.layout(
                entries = candidates,
                expanded = CandidateBarDefinition.Expanded(),
                typography = typography,
                cellPaddingX = 12.0,
                availableWidth = Double.POSITIVE_INFINITY,
            )
        assertEquals(1, rows.size)
        val intrinsic =
            CandidateExpandedFlow.cellWidth(
                entry = candidates.single(),
                typography = typography,
                cellPaddingX = 12.0,
            )
        assertEquals(
            intrinsic,
            rows.single().widths.single(),
            0.001,
            "an own-row cell must not take the non-finite sentinel as its width",
        )
    }

    @Test
    fun evenFillUsesTotalWidthNotPerCellTolerance() {
        val entries = listOf("早上好", "中午好", "下午好", "晚上好").mapIndexed { offset, text -> entry(text, local = offset) }
        // Four intrinsic 88 cells with 4 spacing give 364, which does not fit 362: it must scroll.
        val layout =
            CandidateCollapsedFlow.layout(
                entries = entries,
                collapsed = CandidateBarDefinition.Collapsed(),
                typography = typography,
                availableWidth = 362.0,
            )
        assertTrue(layout.scrolls, "accumulated per-cell tolerance must not keep it even-width")
        assertEquals(listOf(88.0, 88.0, 88.0, 88.0), layout.cellWidths)
    }

    @Test
    fun wholeCellViewportEndsOnACellBoundary() {
        // Two whole cells plus their gap fit in 200; the viewport stops at the last complete cell.
        assertEquals(
            180.0,
            CandidateCollapsedFlow.wholeCellViewportWidth(
                cellWidths = listOf(88.0, 88.0, 88.0),
                spacing = 4.0,
                availableWidth = 200.0,
            ),
            0.001,
        )
        // The first cell is already wider than the viewport: the viewport stays the available width.
        assertEquals(
            100.0,
            CandidateCollapsedFlow.wholeCellViewportWidth(
                cellWidths = listOf(300.0),
                spacing = 4.0,
                availableWidth = 100.0,
            ),
            0.001,
        )
        assertEquals(
            100.0,
            CandidateCollapsedFlow.wholeCellViewportWidth(
                cellWidths = listOf(44.0, 44.0),
                spacing = 4.0,
                availableWidth = 100.0,
            ),
            0.001,
        )
    }

    @Test
    fun selectionBadgeParticipatesInBothWidthBudgets() {
        val plain = entry("甲", local = 0)
        val labelled = entry("甲", local = 1, selectLabel = "标签")
        assertTrue(
            CandidateExpandedFlow.cellWidth(entry = labelled, typography = typography, cellPaddingX = 2.0) >
                CandidateExpandedFlow.cellWidth(entry = plain, typography = typography, cellPaddingX = 2.0),
        )
        assertTrue(
            CandidateCollapsedFlow.intrinsicCellWidth(
                text = labelled.text,
                comment = labelled.comment,
                selectionBadge = labelled.selectionBadge,
                typography = typography,
                cellPaddingX = 2.0,
            ) >
                CandidateCollapsedFlow.intrinsicCellWidth(
                    text = plain.text,
                    comment = plain.comment,
                    selectionBadge = plain.selectionBadge,
                    typography = typography,
                    cellPaddingX = 2.0,
                ),
        )
    }

    // MARK: Expanded adaptive flow

    @Test
    fun expandedAdaptivePacksShortCandidatesByTextWidth() {
        val entries =
            listOf("是", "时", "事", "实", "十", "师", "使").mapIndexed {
                offset,
                text,
                ->
                entry(text, local = offset)
            }
        val rows =
            CandidateExpandedFlow.layout(
                entries = entries,
                expanded = CandidateBarDefinition.Expanded(),
                typography = typography,
                cellPaddingX = 12.0,
                availableWidth = 300.0,
            )
        // 6 x 44 + 5 x 4 = 284 <= 300; a 7th needs 4 + 44 more, so it wraps.
        assertEquals(2, rows.size)
        assertEquals(6, rows[0].entries.size)
        assertEquals(1, rows[1].entries.size)
        assertTrue(
            rows.all { row -> row.widths.all { it == 44.0 } },
            "a single-character cell hugs its text (== minimum touch width)",
        )
    }

    @Test
    fun expandedAdaptiveWrapsOnlyWhenTheRowOverflows() {
        val two = listOf(entry("是", local = 0), entry("时", local = 1))
        assertEquals(
            1,
            CandidateExpandedFlow
                .layout(
                    entries = two,
                    expanded = CandidateBarDefinition.Expanded(),
                    typography = typography,
                    cellPaddingX = 12.0,
                    availableWidth = 92.0,
                ).size,
        )
        assertEquals(
            2,
            CandidateExpandedFlow
                .layout(
                    entries = two,
                    expanded = CandidateBarDefinition.Expanded(),
                    typography = typography,
                    cellPaddingX = 12.0,
                    availableWidth = 91.0,
                ).size,
        )
    }

    @Test
    fun expandedAdaptiveLongCandidateOwnsRowCappedToAvailableWidth() {
        val long = "汉".repeat(20) // ~400 over the 300 available width
        val entries = listOf(entry("是", local = 0), entry(long, local = 1), entry("时", local = 2))
        val rows =
            CandidateExpandedFlow.layout(
                entries = entries,
                expanded = CandidateBarDefinition.Expanded(),
                typography = typography,
                cellPaddingX = 12.0,
                availableWidth = 300.0,
            )
        assertEquals(3, rows.size)
        assertEquals(long, rows[1].entries.first().text)
        assertEquals(listOf(300.0), rows[1].widths, "a long word is capped at the available width")
    }

    @Test
    fun expandedAdaptiveFullRowThresholdForcesOwnRow() {
        val entries = listOf(entry("你好", local = 0), entry("您好", local = 1))
        assertEquals(
            1,
            CandidateExpandedFlow
                .layout(
                    entries = entries,
                    expanded = CandidateBarDefinition.Expanded(),
                    typography = typography,
                    cellPaddingX = 12.0,
                    availableWidth = 300.0,
                ).size,
        )
        val thresholded = CandidateBarDefinition.Expanded(fullRowTextLength = 1)
        val forced =
            CandidateExpandedFlow.layout(
                entries = entries,
                expanded = thresholded,
                typography = typography,
                cellPaddingX = 12.0,
                availableWidth = 300.0,
            )
        assertEquals(2, forced.size)
        assertEquals(listOf(300.0), forced[0].widths)
        assertTrue(CandidateExpandedFlow.wantsOwnRow(entry("甲", comment = "四个字注释"), fullRowTextLength = 1))
    }

    @Test
    fun expandedAdaptiveTextMetricsAndCommentWidth() {
        assertTrue(
            CandidateExpandedFlow.cellWidth(
                entry = entry("甲", comment = "很长很长的注释内容"),
                typography = typography,
                cellPaddingX = 12.0,
            ) >
                CandidateExpandedFlow.cellWidth(entry = entry("甲"), typography = typography, cellPaddingX = 12.0),
            "the cell width grows when the comment is wider",
        )
    }

    @Test
    fun expandedAdaptiveDegenerateInputs() {
        assertTrue(
            CandidateExpandedFlow
                .layout(
                    entries = emptyList(),
                    expanded = CandidateBarDefinition.Expanded(),
                    typography = typography,
                    cellPaddingX = 12.0,
                    availableWidth = 300.0,
                ).isEmpty(),
        )
        val rows =
            CandidateExpandedFlow.layout(
                entries = listOf(entry("是", local = 0), entry("时", local = 1)),
                expanded = CandidateBarDefinition.Expanded(),
                typography = typography,
                cellPaddingX = 12.0,
                availableWidth = Double.POSITIVE_INFINITY,
            )
        assertEquals(1, rows.size)
        assertEquals(listOf(44.0, 44.0), rows[0].widths)
    }

    @Test
    fun expandedRowHeightFittingKeepsWholeRowsInsideTheViewport() {
        assertEquals(44.0, CandidateExpandedFlow.fittedRowHeight(availableHeight = 40.0, spacing = 4.0))
        assertEquals(44.0, CandidateExpandedFlow.fittedRowHeight(availableHeight = 92.0, spacing = 4.0))
        // 100 available with 4 spacing holds two 48 rows.
        assertEquals(48.0, CandidateExpandedFlow.fittedRowHeight(availableHeight = 100.0, spacing = 4.0), 0.001)
        val floored = CandidateExpandedFlow.fittedRowHeight(rowAreaHeight = 100.0, spacing = 4.0, displayScale = 3.0)
        assertEquals(0.0, (floored * 3.0) % 1.0, 0.001, "the row height is floored to device pixels")
        assertEquals(48.0, CandidateExpandedFlow.viewportHeight(totalHeight = 56.0), 0.001)
        assertEquals(54.0, CandidateExpandedFlow.rowAreaHeight(totalHeight = 64.0), 0.001)
    }
}

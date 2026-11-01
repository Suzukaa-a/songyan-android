package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ported from LayoutKitTests/KeyboardPageGeometryTests (dp values). */
class KeyboardPageGeometryTest {
    private fun assertEquals(
        expected: Double,
        actual: Double,
    ) {
        assertEquals("", expected, actual, 0.001)
    }

    @Test
    fun alphabetPreviewUsesTheRuntimeTenColumnGridAndControls() {
        val page = KeyboardPagePresets.englishQwerty
        for (width in listOf(320.0, 466.0, 834.0, 1194.0)) {
            val split = LayoutSplitConfiguration()
            val rows = page.rows.indices.map { KeyboardPageRowGeometry.resolve(width, page, it, split) }
            assertFalse(rows[0].showsLeadingControl)
            assertTrue(rows[2].showsLeadingControl)
            assertTrue(rows[2].showsDelete)
            for (row in rows) {
                assertTrue(row.keyWidths.all { abs(it - rows[0].keyWidths[0]) < 0.001 })
            }
            assertEquals(width, rows[2].rowWidth + rows[2].sideWidth * 2 + 8)
        }
    }

    @Test
    fun numericAndSymbolThirdRowsIncludeTheSwitchAndDeleteColumns() {
        for (
        page in
        listOf(
            KeyboardPagePresets.numericRowsPage(false),
            KeyboardPagePresets.numericRowsPage(true),
            KeyboardPagePresets.symbolRowsPage(false),
            KeyboardPagePresets.symbolRowsPage(true),
        )
        ) {
            val split = LayoutSplitConfiguration()
            val first = KeyboardPageRowGeometry.resolve(834.0, page, 0, split)
            val third = KeyboardPageRowGeometry.resolve(834.0, page, 2, split)
            assertTrue(third.showsLeadingControl)
            assertTrue(third.showsDelete)
            assertEquals(7, third.keyWidths.size)
            // Both side keys are as wide as Shift / Delete on the ABC page.
            val standard = StandardKeyRowGeometry.resolve(834.0, split, 4.0)
            assertEquals(standard.thirdRowSideWidth, third.sideWidth)
            assertEquals(first.keyWidths[0], third.keyWidths[0])
            assertEquals(834.0, third.rowWidth + (third.sideWidth + 4) * 2)
        }
    }

    @Test
    fun authoredWidthsAndDeclaredDeleteAreNotReplacedByAutomaticChrome() {
        val page =
            KeyboardPageDefinition(
                id = "custom",
                name = "数字",
                kind = KeyboardPageKind.NUMERIC,
                rows =
                    listOf(
                        LayoutRow(
                            listOf(
                                LayoutKey("1", width = 2.0, action = LayoutAction.literal("1")),
                                LayoutKey("2", action = LayoutAction.literal("2")),
                                LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                            ),
                        ),
                    ),
            )
        val geometry = KeyboardPageRowGeometry.resolve(600.0, page, 0, null)
        assertFalse(geometry.showsDelete)
        assertEquals(geometry.keyWidths[1] * 2, geometry.keyWidths[0])
    }

    @Test
    fun categoryTailRowsKeepTheWidthsOfTheirFullRowCounterparts() {
        for (weights in listOf(listOf(2.0, 1.0, 1.0, 1.0), listOf(2.0, 2.0, 2.0, 2.0), listOf(3.0, 1.0, 3.0, 1.0))) {
            for (split in listOf(null, LayoutSplitConfiguration())) {
                val full = KeyboardCategoryRowGeometry.resolve(650.0, 4, weights, 0, split)
                for (count in 1..3) {
                    val tail =
                        KeyboardCategoryRowGeometry.resolve(
                            650.0,
                            4,
                            weights.take(count),
                            1,
                            split,
                            referenceWeights = weights,
                        )
                    assertEquals(full.keyWidths.take(count), tail.keyWidths)
                    assertTrue(tail.usedWidth < 650.0)
                }
            }
        }
    }

    @Test
    fun shortCategoryIsInvariantToUniformRelativeWeightScaling() {
        val baseline = KeyboardCategoryRowGeometry.resolve(650.0, 4, listOf(1.0, 1.0), 0, null)
        for (weights in listOf(listOf(2.0, 2.0), listOf(3.0, 3.0))) {
            assertEquals(
                baseline.keyWidths,
                KeyboardCategoryRowGeometry.resolve(650.0, 4, weights, 0, null).keyWidths,
            )
        }
        val uneven = KeyboardCategoryRowGeometry.resolve(650.0, 4, listOf(3.0, 1.0), 0, null)
        val scaled = KeyboardCategoryRowGeometry.resolve(650.0, 4, listOf(6.0, 2.0), 0, null)
        assertEquals(uneven.keyWidths, scaled.keyWidths)
        assertEquals(uneven.keyWidths[1] * 3, uneven.keyWidths[0])
    }

    @Test
    fun authoredStandardAlphabetKeepsEqualColumnsAndRealSideWidths() {
        val page = KeyboardPagePresets.englishQwerty.materialized()
        for (width in listOf(320.0, 466.0, 834.0)) {
            val split = LayoutSplitConfiguration()
            val standard = StandardKeyRowGeometry.resolve(width, split)
            val first = KeyboardPageRowGeometry.resolve(width, page, 0, split)
            val second = KeyboardPageRowGeometry.resolve(width, page, 1, split)
            val third = KeyboardPageRowGeometry.resolve(width, page, 2, split)
            assertTrue(first.usesStandardAlphabet)
            assertTrue(third.usesStandardAlphabet)
            assertFalse(first.showsLeadingControl)
            assertFalse(third.showsDelete)
            assertEquals(10, first.keyIndices.size)
            assertEquals(9, third.keyIndices.size)
            assertEquals(first.keyIndices.size, first.keyWidths.size)
            assertEquals(third.keyIndices.size, third.keyWidths.size)
            for (keyWidth in first.keyWidths) assertEquals(standard.keyWidth, keyWidth)
            for (keyWidth in second.keyWidths) assertEquals(standard.keyWidth, keyWidth)
            assertEquals(standard.thirdRowSideWidth, third.keyWidths.first())
            assertEquals(standard.thirdRowSideWidth, third.keyWidths.last())
            for (keyWidth in third.keyWidths.drop(1).dropLast(1)) {
                assertEquals(standard.keyWidth, keyWidth)
            }
            // The row's own area plus the two sides and their gaps still sums to the keyboard width.
            assertEquals(
                width,
                third.keyWidths
                    .drop(1)
                    .dropLast(1)
                    .sum() +
                    standard.gap +
                    6 * 4 +
                    third.inset * 2 +
                    standard.thirdRowSideWidth * 2 +
                    8,
            )
        }
    }

    @Test
    fun changedSideWeightReallyGetsNarrower() {
        val materialized = KeyboardPagePresets.englishQwerty.materialized()
        val split = LayoutSplitConfiguration()
        val baseline = KeyboardPageRowGeometry.resolve(834.0, materialized, 2, split)
        val lastRow = materialized.rows[2].keys.toMutableList()
        lastRow[0] = lastRow[0].withWidth(1.0)
        val changed = materialized.withRows(materialized.rows.dropLast(1) + LayoutRow(lastRow))
        val geometry = KeyboardPageRowGeometry.resolve(834.0, changed, 2, split)
        assertFalse(geometry.usesStandardAlphabet)
        assertTrue(geometry.keyWidths[0] < baseline.keyWidths[0])
        assertEquals(geometry.keyWidths[1], geometry.keyWidths[0])
        assertEquals(9, geometry.keyWidths.size)
    }

    @Test
    fun replacingAStandardAlphabetLetterWithAControlLeavesTheEqualColumnGeometry() {
        val materialized = KeyboardPagePresets.englishQwerty.materialized()
        val split = LayoutSplitConfiguration()
        val lastRow = materialized.rows[2].keys.toMutableList()
        lastRow[1] = lastRow[1].withAction(LayoutAction(LayoutActionKind.SPACE))
        val changed = materialized.withRows(materialized.rows.dropLast(1) + LayoutRow(lastRow))
        assertFalse(KeyboardPageRowGeometry.resolve(834.0, changed, 2, split).usesStandardAlphabet)

        val firstRow = materialized.rows[0].keys.toMutableList()
        firstRow[0] = firstRow[0].withAction(LayoutAction(LayoutActionKind.BACKSPACE))
        val firstRowChanged = materialized.withRows(listOf(LayoutRow(firstRow)) + materialized.rows.drop(1))
        assertFalse(KeyboardPageRowGeometry.resolve(834.0, firstRowChanged, 0, split).usesStandardAlphabet)
    }

    @Test
    fun materialisedNumericRowKeepsTenColumnsWithSideKeysOnTheThirdRow() {
        val page = KeyboardPagePresets.numericAutomatic.materialized()
        for (width in listOf(320.0, 466.0, 834.0)) {
            val split = LayoutSplitConfiguration()
            val standard = StandardKeyRowGeometry.resolve(width, split)
            for (row in page.rows.indices) {
                val geometry = KeyboardPageRowGeometry.resolve(width, page, row, split)
                assertEquals(if (row == 2) 9 else 10, geometry.keyIndices.size)
                assertEquals(geometry.keyIndices.size, geometry.keyWidths.size)
                assertTrue(geometry.usesStandardAlphabet)
                // Authored data carries the switch and Delete, so nothing is synthesized.
                assertFalse(geometry.showsLeadingControl)
                assertFalse(geometry.showsDelete)
                for ((index, keyWidth) in geometry.keyWidths.withIndex()) {
                    val isSide = row == 2 && (index == 0 || index == 8)
                    assertEquals(if (isSide) standard.thirdRowSideWidth else standard.keyWidth, keyWidth)
                }
            }
        }
    }

    @Test
    fun missingRowsAndNonFiniteWidthsStayFinite() {
        val page = KeyboardPagePresets.englishQwerty
        val missing = KeyboardPageRowGeometry.resolve(382.0, page, 9, null)
        assertTrue(missing.keyIndices.isEmpty())
        assertTrue(missing.keyWidths.isEmpty())
        assertEquals(0, missing.splitIndex)
        for (width in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val geometry = KeyboardPageRowGeometry.resolve(width, page, 0, null)
            assertTrue(geometry.keyWidths.all { it.isFinite() && it >= 0.0 })
            assertTrue(geometry.rowWidth.isFinite() && geometry.rowWidth >= 0.0)
        }
    }
}

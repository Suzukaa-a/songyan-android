package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/KeyboardPageFooterGeometryTests (dp values). */
class KeyboardPageFooterGeometryTest {
    private fun lettersPolicy(globe: Boolean = true): KeyboardControlPolicy =
        KeyboardControlPolicy(
            page = KeyboardControlPolicy.Page.LETTERS,
            isEnglishMode = false,
            hasGlobe = globe,
            isPhoneLayout = false,
            usesAsciiPunctuation = false,
        )

    private fun footer(page: KeyboardPageDefinition): List<LayoutKey> =
        page.materialized().chrome?.bottom ?: emptyList()

    private fun assertFillsWidth(
        geometry: KeyboardPageFooterGeometry,
        width: Double,
    ) {
        assertEquals(
            "",
            width,
            geometry.keyWidths.sum() + geometry.spacing * maxOf(0, geometry.keyWidths.size - 1),
            0.001,
        )
        assertTrue(geometry.keyWidths.all { it.isFinite() && it >= 0.0 })
    }

    @Test
    fun defaultAbcFooterKeepsTheControlRowBudget() {
        val keys = footer(KeyboardPagePresets.englishQwerty)
        for (width in listOf(320.0, 382.0, 466.0, 834.0)) {
            val policy = lettersPolicy()
            val geometry = KeyboardPageFooterGeometry.resolve(width, keys, policy)
            assertTrue(geometry.usesControlRowBudget)
            val metrics = policy.metrics(width)
            assertEquals(policy.slots.map { metrics[it] }, geometry.keyWidths)
            // The tuned default bottom row is not squeezed by a fresh copy.
            assertEquals("", metrics[KeyboardControlPolicy.Slot.SPACE], geometry.keyWidths[3], 0.001)
            assertFillsWidth(geometry, width)
        }
    }

    @Test
    fun hiddenGlobeUsesTheSameBudgetWithoutItsWidth() {
        val metricsKeys =
            footer(KeyboardPagePresets.englishQwerty).filter {
                it.action.type != LayoutActionKind.GLOBE
            }
        val policy = lettersPolicy(globe = false)
        val geometry = KeyboardPageFooterGeometry.resolve(382.0, metricsKeys, policy)
        assertTrue(geometry.usesControlRowBudget)
        assertEquals(metricsKeys.size, geometry.keyWidths.size)
        assertFillsWidth(geometry, 382.0)
        assertTrue("space takes the width the hidden globe gave up", geometry.keyWidths[2] > 100.0)
    }

    @Test
    fun everyDefaultFooterVariantMatchesItsOwnControlRowPolicy() {
        data class Variant(
            val page: KeyboardPageDefinition,
            val policy: KeyboardControlPolicy,
        )
        val variants =
            listOf(
                Variant(
                    KeyboardPagePresets.numericRowsPage(false),
                    KeyboardControlPolicy(
                        page = KeyboardControlPolicy.Page.NUMERIC,
                        isEnglishMode = false,
                        hasGlobe = true,
                        isPhoneLayout = false,
                        usesAsciiPunctuation = false,
                    ),
                ),
                Variant(
                    KeyboardPagePresets.symbolRowsPage(false),
                    KeyboardControlPolicy(
                        page = KeyboardControlPolicy.Page.SYMBOLS,
                        isEnglishMode = false,
                        hasGlobe = true,
                        isPhoneLayout = false,
                        usesAsciiPunctuation = false,
                        usesCategorySymbolPage = false,
                    ),
                ),
                Variant(
                    KeyboardPagePresets.symbolsCategorized,
                    KeyboardControlPolicy(
                        page = KeyboardControlPolicy.Page.SYMBOLS,
                        isEnglishMode = false,
                        hasGlobe = true,
                        isPhoneLayout = true,
                        usesAsciiPunctuation = false,
                        includesBottomEnter = false,
                    ),
                ),
                Variant(
                    KeyboardPagePresets.numericKeypad,
                    KeyboardControlPolicy(
                        page = KeyboardControlPolicy.Page.NUMERIC,
                        isEnglishMode = false,
                        hasGlobe = true,
                        isPhoneLayout = true,
                        usesAsciiPunctuation = false,
                        includesBottomEnter = false,
                    ),
                ),
            )
        for (variant in variants) {
            val geometry = KeyboardPageFooterGeometry.resolve(402.0, footer(variant.page), variant.policy)
            assertTrue(variant.page.name, geometry.usesControlRowBudget)
            assertFillsWidth(geometry, 402.0)
        }
    }

    @Test
    fun keypadFooterOnThePhoneShellKeepsTheRailColumns() {
        val policy =
            KeyboardControlPolicy(
                page = KeyboardControlPolicy.Page.NUMERIC,
                isEnglishMode = false,
                hasGlobe = false,
                isPhoneLayout = true,
                usesAsciiPunctuation = false,
                includesBottomEnter = false,
            ).alignedTo(KeyboardControlPolicy.PhoneColumns(rail = 88.0, digit = 64.0))
        val keys =
            footer(KeyboardPagePresets.numericKeypad).filter {
                it.action.type != LayoutActionKind.GLOBE
            }
        val geometry = KeyboardPageFooterGeometry.resolve(402.0, keys, policy)
        assertTrue(geometry.usesControlRowBudget)
        // 返回 under the rail, 中英, space and 符号 under the digit columns, Return under the right column.
        assertEquals(5, geometry.keyWidths.size)
        assertEquals("the first bottom key sits under the rail", 88.0, geometry.keyWidths[0], 0.001)
        assertEquals("Return sits under the right column", 88.0, geometry.keyWidths[4], 0.001)
        assertEquals(
            1,
            geometry.keyWidths
                .subList(1, 4)
                .map { Math.round(it * 1000) }
                .distinct()
                .size,
        )
        assertFillsWidth(geometry, 402.0)
        // The globe takes the cell under the rail; 返回 and 中英 share the first column's.
        val withGlobe =
            KeyboardPageFooterGeometry.resolve(
                402.0,
                footer(KeyboardPagePresets.numericKeypad),
                policy.copy(hasGlobe = true),
            )
        assertTrue(withGlobe.usesControlRowBudget)
        assertEquals(88.0, withGlobe.keyWidths[0], 0.001)
        assertEquals(geometry.keyWidths[1], withGlobe.keyWidths[1] + withGlobe.keyWidths[2] + withGlobe.spacing, 0.001)
        assertEquals(geometry.keyWidths.takeLast(3), withGlobe.keyWidths.takeLast(3))
        assertFillsWidth(withGlobe, 402.0)
    }

    @Test
    fun customLabelsAndLongPressDoNotChangeTheGeometry() {
        val policy = lettersPolicy()
        val baseline = KeyboardPageFooterGeometry.resolve(382.0, footer(KeyboardPagePresets.englishQwerty), policy)
        val decorated =
            footer(KeyboardPagePresets.englishQwerty).map { key ->
                key
                    .withLabel("我的" + key.label)
                    .withSecondaryLabel("提示")
                    .withLongPress(
                        listOf(LayoutOption("选项", LayoutAction.pageSwitch(LayoutPageTarget.LETTERS))),
                    )
            }
        val geometry = KeyboardPageFooterGeometry.resolve(382.0, decorated, policy)
        assertTrue(geometry.usesControlRowBudget)
        assertEquals(baseline.keyWidths, geometry.keyWidths)
    }

    @Test
    fun anyWidthOrStructureChangeFallsBackToAuthoredRelativeWidths() {
        val policy = lettersPolicy()
        val defaults = footer(KeyboardPagePresets.englishQwerty)
        val spaceWider = defaults.toMutableList().also { it[3] = it[3].withWidth(3.0) }
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, spaceWider, policy).usesControlRowBudget,
        )
        val globeExplicit = defaults.toMutableList().also { it[0] = it[0].withWidth(1.0) }
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, globeExplicit, policy).usesControlRowBudget,
        )
        val letterExplicit = defaults.toMutableList().also { it[1] = it[1].withWidth(1.0) }
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, letterExplicit, policy).usesControlRowBudget,
        )
        val reordered = listOf(defaults[0], defaults[2], defaults[1], defaults[3], defaults[4], defaults[5])
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, reordered, policy).usesControlRowBudget,
        )
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, defaults.dropLast(1), policy).usesControlRowBudget,
        )
        assertFalse(
            KeyboardPageFooterGeometry
                .resolve(
                    382.0,
                    defaults + LayoutKey("×", action = LayoutAction(LayoutActionKind.CLEAR_INPUT)),
                    policy,
                ).usesControlRowBudget,
        )
        // Author deletes the globe but the live system does offer one: no auto-repair, generic.
        assertFalse(
            KeyboardPageFooterGeometry.resolve(382.0, defaults.drop(1), policy).usesControlRowBudget,
        )
    }

    @Test
    fun authoredRelativeWidthsShareTheAvailableWidth() {
        val keys =
            listOf(
                LayoutKey("a", width = 1.0, action = LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC)),
                LayoutKey("b", width = 3.0, action = LayoutAction.literal("b")),
            )
        val geometry = KeyboardPageFooterGeometry.resolve(402.0, keys, lettersPolicy())
        assertFalse(geometry.usesControlRowBudget)
        val available = 402.0 - geometry.spacing
        assertEquals(listOf(available / 4, available * 3 / 4), geometry.keyWidths)
        assertFillsWidth(geometry, 402.0)
    }

    @Test
    fun hugeFiniteWeightsStayFinite() {
        val keys =
            listOf(
                LayoutKey("a", width = Double.MAX_VALUE, action = LayoutAction.pageSwitch(LayoutPageTarget.LETTERS)),
                LayoutKey("b", width = 1.0, action = LayoutAction.literal("b")),
                LayoutKey("c", width = Double.MAX_VALUE, action = LayoutAction.literal("c")),
            )
        val geometry = KeyboardPageFooterGeometry.resolve(402.0, keys, lettersPolicy())
        assertFalse(geometry.usesControlRowBudget)
        assertTrue(geometry.keyWidths.all { it.isFinite() && it >= 0.0 })
        assertEquals("", geometry.keyWidths[2], geometry.keyWidths[0], 0.001)
        assertTrue(geometry.keyWidths[1] < geometry.keyWidths[0])
        assertFillsWidth(geometry, 402.0)
    }

    @Test
    fun emptyAndInvalidFootersStayFinite() {
        val policy = lettersPolicy()
        val empty = KeyboardPageFooterGeometry.resolve(382.0, emptyList(), policy)
        assertTrue(empty.keyWidths.isEmpty())
        assertFalse(empty.usesControlRowBudget)
        for (width in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val geometry =
                KeyboardPageFooterGeometry.resolve(
                    width,
                    footer(KeyboardPagePresets.englishQwerty),
                    policy,
                )
            assertTrue(geometry.keyWidths.all { it.isFinite() })
            assertTrue(geometry.keyWidths.all { it >= 0.0 })
        }
    }
}

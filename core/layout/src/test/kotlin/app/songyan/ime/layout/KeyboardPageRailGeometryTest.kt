package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/KeyboardPageRailGeometryTests (dp values). */
class KeyboardPageRailGeometryTest {
    private fun assertClose(
        expected: Double,
        actual: Double,
    ) {
        assertEquals("", expected, actual, 0.001)
    }

    @Test
    fun phoneEditingRailSpans54And54And112() {
        val geometry = KeyboardPageRailGeometry.resolve(228.0, listOf(1.0, 1.0, 2.0))
        assertEquals(3, geometry.keyHeights.size)
        assertClose(54.0, geometry.keyHeights[0])
        assertClose(54.0, geometry.keyHeights[1])
        assertClose(112.0, geometry.keyHeights[2])
        assertClose(228.0, geometry.contentHeight)
        assertClose(4.0, geometry.spacing)
    }

    @Test
    fun railHeightsFollowTheFormulaAndScrollInsteadOfCompressing() {
        data class Model(
            val height: Double,
            val weights: List<Double>,
            val spacing: Double,
        )
        for (
        model in
        listOf(
            Model(400.0, listOf(1.0, 1.0, 1.0), 4.0),
            Model(300.0, listOf(2.0, 1.0), 6.0),
            Model(120.0, listOf(1.0, 1.0, 1.0), 4.0),
        )
        ) {
            val geometry = KeyboardPageRailGeometry.resolve(model.height, model.weights, model.spacing)
            val total = model.weights.sum()
            for ((index, weight) in model.weights.withIndex()) {
                val raw = (model.height + model.spacing) * weight / total - model.spacing
                assertClose(maxOf(44.0, raw), geometry.keyHeights[index])
            }
            assertClose(
                geometry.keyHeights.sum() + model.spacing * (model.weights.size - 1),
                geometry.contentHeight,
            )
        }
    }

    @Test
    fun minimumTapHeightWinsAndTheRailScrolls() {
        val geometry = KeyboardPageRailGeometry.resolve(80.0, listOf(1.0, 1.0, 1.0), spacing = 0.0)
        assertEquals(listOf(44.0, 44.0, 44.0), geometry.keyHeights)
        assertClose(132.0, geometry.contentHeight)
        assertTrue(geometry.contentHeight > 80.0)
        val single = KeyboardPageRailGeometry.resolve(30.0, listOf(1.0), spacing = 4.0)
        assertEquals(listOf(44.0), single.keyHeights)
        assertClose(44.0, single.contentHeight)
    }

    @Test
    fun hugeFiniteWeightsStayFinite() {
        val geometry =
            KeyboardPageRailGeometry.resolve(
                228.0,
                listOf(Double.MAX_VALUE, Double.MAX_VALUE),
            )
        assertTrue(geometry.keyHeights.all { it.isFinite() })
        assertTrue(geometry.contentHeight.isFinite())
        assertClose(geometry.keyHeights[1], geometry.keyHeights[0])
        assertClose(228.0, geometry.contentHeight)
    }

    @Test
    fun emptyAndInvalidWeightsStayFinite() {
        val empty = KeyboardPageRailGeometry.resolve(228.0, emptyList())
        assertTrue(empty.keyHeights.isEmpty())
        assertClose(0.0, empty.contentHeight)
        val invalid = KeyboardPageRailGeometry.resolve(228.0, listOf(0.0, -1.0, Double.NaN), spacing = 4.0)
        assertEquals(listOf(44.0, 44.0, 44.0), invalid.keyHeights)
        assertTrue(invalid.keyHeights.all { it.isFinite() })
        val nonFinite =
            KeyboardPageRailGeometry.resolve(Double.POSITIVE_INFINITY, listOf(1.0, 1.0), spacing = Double.NaN)
        assertTrue(nonFinite.contentHeight.isFinite())
    }
}

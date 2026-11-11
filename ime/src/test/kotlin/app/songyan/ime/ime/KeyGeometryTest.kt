package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeometryTest {
    @Test fun weightedRowsHaveNoDeadStrips() {
        val bounds = KeyGeometry.row(listOf(1f, 4f, 1f), 10f, 20f, 300f, 50f)
        assertEquals(KeyBounds(10f, 20f, 60f, 70f), bounds[0])
        assertEquals(KeyBounds(60f, 20f, 260f, 70f), bounds[1])
        assertEquals(0, KeyGeometry.hit(bounds, 59.99f, 40f))
        assertEquals(1, KeyGeometry.hit(bounds, 60f, 40f))
        assertEquals(2, KeyGeometry.hit(bounds, 309.99f, 40f))
        assertEquals(-1, KeyGeometry.hit(bounds, 310f, 40f))
    }

    @Test fun hitTestingRejectsPointsOutsideTheKeyArea() {
        val bounds = KeyGeometry.row(List(10) { 1f }, 0f, 10f, 400f, 48f)
        assertEquals(-1, KeyGeometry.hit(bounds, -1f, 20f))
        assertEquals(-1, KeyGeometry.hit(bounds, 20f, 9f))
        assertEquals(-1, KeyGeometry.hit(bounds, 20f, 58f))
        assertEquals(9, KeyGeometry.hit(bounds, 399f, 57f))
    }

    @Test fun aFingerStaysNearItsKeyWithinTheMargin() {
        val key = KeyBounds(0f, 100f, 40f, 160f)
        assertTrue(KeyGeometry.near(key, 20f, 130f, 8f))
        assertTrue("rolled past the edge, within the margin", KeyGeometry.near(key, -7f, 167f, 8f))
        assertFalse(KeyGeometry.near(key, -9f, 130f, 8f))
        assertFalse(KeyGeometry.near(key, 20f, 168f, 8f))
        assertFalse(KeyGeometry.near(key, 48f, 130f, 8f))
    }

    @Test fun aPressNearTheTopOfALowerRowGoesToTheKeyAboveIt() {
        // Four 50 px rows, one key each.
        val rows = (0 until 4).map { KeyBounds(0f, it * 50f, 100f, it * 50f + 50f) }
        // 2 px into the bottom row: a finger that aimed at the row above lands this low.
        assertEquals(2, KeyGeometry.hitCorrected(rows, 50f, 152f, 200f))
        assertEquals(3, KeyGeometry.hit(rows, 50f, 152f))
        // Well inside the bottom row it stays there.
        assertEquals(3, KeyGeometry.hitCorrected(rows, 50f, 170f, 200f))
        // At the top of the key area nothing moves: a top-row press never jumps out of the keys.
        assertEquals(0, KeyGeometry.hitCorrected(rows, 50f, 1f, 200f))
        assertEquals(1, KeyGeometry.hitCorrected(rows, 50f, 53f, 200f))
    }
}

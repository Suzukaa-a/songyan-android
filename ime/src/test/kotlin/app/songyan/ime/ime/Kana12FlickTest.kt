package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kana12FlickTest {
    private val directions =
        listOf(
            Kana12Direction.Center,
            Kana12Direction.Left,
            Kana12Direction.Up,
            Kana12Direction.Right,
            Kana12Direction.Down,
        )

    @Test
    fun `a press shorter than the threshold is a tap`() {
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(0.0, 0.0))
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(17.9, 0.0))
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(0.0, -17.9))
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(-12.0, 12.0))
    }

    @Test
    fun `the threshold itself already starts a flick`() {
        assertEquals(Kana12Direction.Up, Kana12Flick.direction(0.0, -Kana12Flick.THRESHOLD_DP))
        assertEquals(Kana12Direction.Right, Kana12Flick.direction(Kana12Flick.THRESHOLD_DP, 0.0))
    }

    @Test
    fun `each screen direction picks its flick`() {
        assertEquals(Kana12Direction.Left, Kana12Flick.direction(-40.0, 3.0))
        assertEquals(Kana12Direction.Up, Kana12Flick.direction(3.0, -40.0))
        assertEquals(Kana12Direction.Right, Kana12Flick.direction(40.0, -3.0))
        assertEquals(Kana12Direction.Down, Kana12Flick.direction(-3.0, 40.0))
    }

    @Test
    fun `an exact diagonal ties to the horizontal direction`() {
        assertEquals(Kana12Direction.Right, Kana12Flick.direction(30.0, 30.0))
        assertEquals(Kana12Direction.Left, Kana12Flick.direction(-30.0, 30.0))
        assertEquals(Kana12Direction.Right, Kana12Flick.direction(30.0, -30.0))
        assertEquals(Kana12Direction.Left, Kana12Flick.direction(-30.0, -30.0))
    }

    @Test
    fun `a bad pointer sample never flicks`() {
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(Double.NaN, 40.0))
        assertEquals(Kana12Direction.Center, Kana12Flick.direction(40.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `the threshold is the keyboard's own swipe lock distance`() {
        assertEquals(18.0, Kana12Flick.THRESHOLD_DP, 1e-9)
    }

    @Test
    fun `every gojuon row types a tap plus its five vowel series`() {
        val vowels =
            mapOf(
                Kana12Rows.a to listOf("あ", "い", "う", "え", "お"),
                Kana12Rows.ka to listOf("か", "き", "く", "け", "こ"),
                Kana12Rows.sa to listOf("さ", "し", "す", "せ", "そ"),
                Kana12Rows.ta to listOf("た", "ち", "つ", "て", "と"),
                Kana12Rows.na to listOf("な", "に", "ぬ", "ね", "の"),
                Kana12Rows.ha to listOf("は", "ひ", "ふ", "へ", "ほ"),
                Kana12Rows.ma to listOf("ま", "み", "む", "め", "も"),
                Kana12Rows.ra to listOf("ら", "り", "る", "れ", "ろ"),
            )
        for ((row, faces) in vowels) {
            assertEquals("row ${row.id}", faces, directions.map { row.face(it) })
        }
    }

    @Test
    fun `the ya row borrows the round brackets`() {
        assertEquals(
            listOf("や", "（", "ゆ", "）", "よ"),
            directions.map { Kana12Rows.ya.face(it) },
        )
    }

    @Test
    fun `the wa row has no down face`() {
        assertEquals(listOf("わ", "を", "ん", "ー", null), directions.map { Kana12Rows.wa.face(it) })
        assertNull(Kana12Rows.wa.face(Kana12Direction.Down))
    }

    @Test
    fun `the punctuation key taps a comma and flicks the other marks`() {
        assertEquals(
            listOf("、", "。", "？", "！", "…"),
            directions.map { Kana12Rows.punctuation.face(it) },
        )
    }

    @Test
    fun `the punctuation keycap shows the whole mark set`() {
        assertEquals("、。?!", Kana12Rows.punctuation.keycap)
        assertEquals("、", Kana12Rows.punctuation.center)
    }

    @Test
    fun `every other row shows its tap face on the cap`() {
        for (row in Kana12Rows.all) {
            if (row == Kana12Rows.punctuation) continue
            assertEquals(row.id, row.center, row.keycap)
        }
    }

    @Test
    fun `the row ids are the ones the input model receives`() {
        assertEquals(
            listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", "wa", "punctuation"),
            Kana12Rows.all.map { it.id },
        )
    }

    @Test
    fun `the grid puts the modify key beside wa and the punctuation key`() {
        val grid = Kana12Rows.grid
        assertEquals(4, grid.size)
        assertTrue(grid.all { it.size == 3 })
        assertEquals(listOf(Kana12Rows.a, Kana12Rows.ka, Kana12Rows.sa), grid[0])
        assertEquals(listOf(Kana12Rows.ta, Kana12Rows.na, Kana12Rows.ha), grid[1])
        assertEquals(listOf(Kana12Rows.ma, Kana12Rows.ya, Kana12Rows.ra), grid[2])
        assertNull(grid[3][0])
        assertEquals(Kana12Rows.wa, grid[3][1])
        assertEquals(Kana12Rows.punctuation, grid[3][2])
    }

    @Test
    fun `the preview draws five cells around the pivot`() {
        val surface = Kana12Rect(0.0, 0.0, 400.0, 224.0)
        val cells = Kana12Flick.previewBounds(200.0, 112.0, surface)
        assertEquals(5, cells.size)
        val center = cells.getValue(Kana12Direction.Center)
        assertEquals(200.0, center.centerX, 1e-9)
        assertEquals(112.0, center.centerY, 1e-9)
        assertEquals(Kana12Flick.PREVIEW_CELL_DP, center.width, 1e-9)
        assertEquals(center.left - Kana12Flick.PREVIEW_GAP_DP, cells.getValue(Kana12Direction.Left).right, 1e-9)
        assertEquals(center.top - Kana12Flick.PREVIEW_GAP_DP, cells.getValue(Kana12Direction.Up).bottom, 1e-9)
        assertEquals(center.right + Kana12Flick.PREVIEW_GAP_DP, cells.getValue(Kana12Direction.Right).left, 1e-9)
        assertEquals(center.bottom + Kana12Flick.PREVIEW_GAP_DP, cells.getValue(Kana12Direction.Down).top, 1e-9)
    }

    @Test
    fun `a preview at the surface edge moves as one cross`() {
        val surface = Kana12Rect(0.0, 0.0, 400.0, 224.0)
        val cells = Kana12Flick.previewBounds(4.0, 4.0, surface)
        assertEquals(0.0, cells.values.minOf { it.top }, 1e-9)
        assertEquals(0.0, cells.values.minOf { it.left }, 1e-9)
        for (cell in cells.values) {
            assertTrue(cell.left >= -1e-9)
            assertTrue(cell.top >= -1e-9)
            assertTrue(cell.right <= 400.0 + 1e-9)
            assertTrue(cell.bottom <= 224.0 + 1e-9)
        }
        val center = cells.getValue(Kana12Direction.Center)
        assertEquals(center.centerY, cells.getValue(Kana12Direction.Left).centerY, 1e-9)
        assertEquals(center.centerX, cells.getValue(Kana12Direction.Up).centerX, 1e-9)
    }
}

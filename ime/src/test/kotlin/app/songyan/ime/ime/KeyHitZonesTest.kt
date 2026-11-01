package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Device-like geometry of one rendered key area (1080 px wide, 2.625 density).
 *
 * The numbers mirror the design tokens' geometry fields and are solved with the same pure helpers
 * the view uses, so the assertions compare the real solve with itself.
 */
private class KeyArea(
    val slots: List<KeyBounds>,
    val drawn: List<KeyBounds>,
    val hit: List<KeyBounds>,
    val height: Float,
    val width: Float,
) {
    fun owner(
        x: Float,
        y: Float,
    ): Int = KeyGeometry.hit(hit, x, y)
}

private class RowArea(
    val keys: List<LayoutKey>,
    val area: KeyArea,
    val rowStarts: List<Int>,
    val rowSizes: List<Int>,
) {
    fun rowOf(index: Int): Int {
        for (row in rowStarts.indices) {
            if (index >= rowStarts[row] && index < rowStarts[row] + rowSizes[row]) return row
        }
        return -1
    }
}

private const val DENSITY = 2.625f
private const val WIDTH_PX = 1080f
private const val ROW_HEIGHT_DP = 60f
private const val GAP_H_DP = 5f
private const val GAP_V_DP = 8f
private const val PADDING_DP = 3f
private const val BOTTOM_GAP_DP = 8f
private const val ROWS = 4

private fun lettersConfig(
    layout: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
    english: Boolean = false,
): KeyboardPages.Config =
    KeyboardPages.Config(
        layout = layout,
        englishDirectInput = english,
        symbolMarksAscii = false,
        languageSwitch = true,
        lettersReachable = true,
    )

private fun rowArea(definition: LayoutDefinition): RowArea {
    val rows = KeyRowSolver.columns(definition.rows).filter { it.isNotEmpty() }
    val padding = PADDING_DP * DENSITY
    val gap = GAP_H_DP * DENSITY
    val rowHeight = ROW_HEIGHT_DP * DENSITY
    val top = BOTTOM_GAP_DP / 2 * DENSITY
    val inner = WIDTH_PX - 2 * padding
    val height = rows.size * rowHeight + BOTTOM_GAP_DP * DENSITY
    val rowSlots = KeyRowSolver.slots(rows, padding, top, rowHeight, inner, gap)
    val slots = rowSlots.flatten()
    val insetX = KeyInsets.rowHorizontal()
    val insetY = KeyInsets.rowVertical(GAP_V_DP * DENSITY)
    val drawn =
        slots.map { slot ->
            KeyBounds(slot.left + insetX, slot.top + insetY, slot.right - insetX, slot.bottom - insetY)
        }
    val hit = KeyHitZones.fillRows(rowSlots, 0f, 0f, WIDTH_PX, height).flatten()
    val starts = mutableListOf<Int>()
    var running = 0
    for (row in rowSlots) {
        starts += running
        running += row.size
    }
    return RowArea(
        keys = rows.flatten(),
        area = KeyArea(slots, drawn, hit, height, WIDTH_PX),
        rowStarts = starts,
        rowSizes = rowSlots.map { it.size },
    )
}

private fun lettersArea(
    layout: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
    english: Boolean = false,
): RowArea = rowArea(KeyboardPages.definition(null, lettersConfig(layout, english)))

private fun pageArea(page: KeyboardPageKind): RowArea = rowArea(KeyboardPages.definition(page, lettersConfig()))

/** The nine-key shell, keeping the placed-key metadata so rails and grid can be told apart. */
private class ShellArea(
    val placed: List<PhoneKeypadLayout.PlacedKey>,
    val area: KeyArea,
) {
    fun indexOf(
        region: PhoneKeypadLayout.Region,
        position: Int,
    ): Int {
        var seen = 0
        for (index in placed.indices) {
            if (placed[index].region != region) continue
            if (seen == position) return index
            seen += 1
        }
        return -1
    }
}

private fun shellArea(
    numeric: Boolean = false,
    railChoices: Int? = null,
): ShellArea {
    val padding = PADDING_DP * DENSITY
    val contentWidth = WIDTH_PX - 2 * padding
    val keyAreaHeightDp = ROWS * ROW_HEIGHT_DP
    val shell =
        requireNotNull(
            PhoneKeypadLayout.build(
                PhoneKeypadLayout.Config(
                    width = contentWidth / DENSITY,
                    height = keyAreaHeightDp,
                    numeric = numeric,
                    composing = false,
                    asciiPunctuation = false,
                    labels =
                        PhoneKeypadLayout.Labels(
                            page = "123",
                            language = "英文",
                            space = "空格",
                            symbols = "符号",
                            back = "返回",
                            zero = "0",
                            enter = "换行",
                            clear = "重输",
                            delete = "⌫",
                        ),
                ),
            ),
        )
    val shellInset = (WIDTH_PX - contentWidth) / 2
    val rowOffset = BOTTOM_GAP_DP / 2 * DENSITY
    val height = keyAreaHeightDp * DENSITY + BOTTOM_GAP_DP * DENSITY
    // A shorter reading rail replaces only the slots it fills; the remaining leading-rail slots
    // keep their idle quick symbols, so every physical slot stays in the geometry (a dropped slot
    // would leave an unowned region of the key area).
    var railIndex = 0
    val placed =
        shell.keys.map { key ->
            if (key.region != PhoneKeypadLayout.Region.LEADING_RAIL || railChoices == null) return@map key
            val filled = railIndex < railChoices
            railIndex += 1
            if (filled) {
                key.copy(key = LayoutKey("读词", action = LayoutAction.literal("读词")))
            } else {
                key
            }
        }
    val slots =
        placed.map { key ->
            KeyBounds(
                key.left * DENSITY + shellInset,
                key.top * DENSITY + rowOffset,
                key.right * DENSITY + shellInset,
                key.bottom * DENSITY + rowOffset,
            )
        }
    val hit = KeyHitZones.fill(slots, 0f, 0f, WIDTH_PX, height)
    return ShellArea(placed, KeyArea(slots, slots, hit, height, WIDTH_PX))
}

private fun allAreas(): List<Pair<String, KeyArea>> =
    listOf(
        "qwerty26" to lettersArea().area,
        "merged14" to lettersArea(KeyboardLayoutKind.MERGED14).area,
        "merged18" to lettersArea(KeyboardLayoutKind.MERGED18).area,
        "abc" to lettersArea(english = true).area,
        "numeric" to pageArea(KeyboardPageKind.NUMERIC).area,
        "symbols" to pageArea(KeyboardPageKind.SYMBOLS).area,
        "phone9" to shellArea().area,
        "phone9numeric" to shellArea(numeric = true).area,
    )

class KeyHitZonesTest {
    @Test
    fun noPointInTheKeyAreaIsDead() {
        for ((name, area) in allAreas()) {
            var x = 0.5f
            while (x < area.width) {
                var y = 0.5f
                while (y < area.height) {
                    assertTrue(name + " has a dead point at " + x + "," + y, area.owner(x, y) >= 0)
                    y += 3f
                }
                x += 3f
            }
        }
    }

    @Test
    fun hitZonesNeverOverlap() {
        for ((name, area) in allAreas()) {
            for (i in area.hit.indices) {
                for (j in i + 1 until area.hit.size) {
                    val a = area.hit[i]
                    val b = area.hit[j]
                    val overlapX = min(a.right, b.right) - max(a.left, b.left)
                    val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                    assertTrue(
                        name + " keys " + i + " and " + j + " overlap",
                        overlapX <= 0.01f || overlapY <= 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun everyDrawnKeycapIsInsideItsOwnHitZone() {
        for ((name, area) in allAreas()) {
            for (i in area.hit.indices) {
                val hit = area.hit[i]
                val drawn = area.drawn[i]
                assertTrue(
                    name + " key " + i + " hit zone misses its keycap",
                    hit.left <= drawn.left + 0.01f &&
                        hit.top <= drawn.top + 0.01f &&
                        hit.right >= drawn.right - 0.01f &&
                        hit.bottom >= drawn.bottom - 0.01f,
                )
            }
        }
    }

    @Test
    fun rowGutterMidpointsSplitBetweenTheTwoNeighbours() {
        val tolerance = 0.25f
        for ((name, row) in listOf(
            "qwerty26" to lettersArea(),
            "merged14" to lettersArea(KeyboardLayoutKind.MERGED14),
            "merged18" to lettersArea(KeyboardLayoutKind.MERGED18),
            "abc" to lettersArea(english = true),
            "numeric" to pageArea(KeyboardPageKind.NUMERIC),
            "symbols" to pageArea(KeyboardPageKind.SYMBOLS),
        )) {
            val area = row.area
            for (index in row.rowStarts.indices) {
                val start = row.rowStarts[index]
                val end = start + row.rowSizes[index]
                for (i in start until end - 1) {
                    val left = area.hit[i]
                    val right = area.hit[i + 1]
                    val midpoint = (left.right + right.left) / 2f
                    val y = (left.top + left.bottom) / 2f
                    assertEquals(name + " gutter left of " + i, i, area.owner(midpoint - tolerance, y))
                    assertEquals(name + " gutter right of " + i, i + 1, area.owner(midpoint + tolerance, y))
                }
            }
        }
    }

    @Test
    fun rowBoundariesBelongToTheLowerRow() {
        val tolerance = 0.25f
        for ((name, row) in listOf(
            "qwerty26" to lettersArea(),
            "merged14" to lettersArea(KeyboardLayoutKind.MERGED14),
            "abc" to lettersArea(english = true),
            "numeric" to pageArea(KeyboardPageKind.NUMERIC),
        )) {
            val area = row.area
            for (index in 0 until row.rowStarts.size - 1) {
                val upper = row.rowStarts[index]
                val lower = row.rowStarts[index + 1]
                val boundary = (area.slots[upper].bottom + area.slots[lower].top) / 2f
                var x = 1f
                while (x < area.width) {
                    val above = area.owner(x, boundary - tolerance)
                    val below = area.owner(x, boundary + tolerance)
                    assertEquals(name + " row " + index + " upper side at " + x, index, row.rowOf(above))
                    assertEquals(name + " row " + index + " lower side at " + x, index + 1, row.rowOf(below))
                    x += 10f
                }
            }
        }
    }

    @Test
    fun nineKeyGuttersSplitBetweenRailGridAndBottomRow() {
        for ((name, shell) in listOf("phone9" to shellArea(), "phone9numeric" to shellArea(numeric = true))) {
            val area = shell.area
            // Grid columns of one row: the seam belongs half to each digit.
            for (row in 0 until 3) {
                for (column in 0 until 2) {
                    val left = shell.indexOf(PhoneKeypadLayout.Region.MAIN, row * 3 + column)
                    val right = shell.indexOf(PhoneKeypadLayout.Region.MAIN, row * 3 + column + 1)
                    if (left < 0 || right < 0) continue
                    val a = area.hit[left]
                    val b = area.hit[right]
                    val midpoint = (a.right + b.left) / 2f
                    val y = (a.top + a.bottom) / 2f
                    val seam = name + " grid seam r" + row + "c" + column
                    assertEquals(seam + " left", left, area.owner(midpoint - 0.25f, y))
                    assertEquals(seam + " right", right, area.owner(midpoint + 0.25f, y))
                }
            }
            // Rail to grid: the leading rail's first slot and the grid's first column touch.
            val rail = shell.indexOf(PhoneKeypadLayout.Region.LEADING_RAIL, 0)
            val grid = shell.indexOf(PhoneKeypadLayout.Region.MAIN, 0)
            if (rail >= 0 && grid >= 0) {
                val a = area.hit[rail]
                val b = area.hit[grid]
                val midpoint = (a.right + b.left) / 2f
                val y = max(a.top, b.top) + 2f
                assertEquals(name + " rail seam left", rail, area.owner(midpoint - 0.25f, y))
                assertEquals(name + " rail seam right", grid, area.owner(midpoint + 0.25f, y))
            }
            // Bottom row: the space bar owns its share and meets its neighbour at the midpoint.
            val bubble = shell.placed.indexOfFirst { it.key.action.type == LayoutActionKind.SPACE }
            // On the digits the space bar stands in the right column, below Delete, not in the bottom row.
            if (bubble > 0 && shell.placed[bubble].region == PhoneKeypadLayout.Region.BOTTOM) {
                val left = bubble - 1
                val a = area.hit[left]
                val b = area.hit[bubble]
                val midpoint = (a.right + b.left) / 2f
                val y = (b.top + b.bottom) / 2f
                assertEquals(name + " footer seam left", left, area.owner(midpoint - 0.25f, y))
                assertEquals(name + " footer seam right", bubble, area.owner(midpoint + 0.25f, y))
            }
        }
    }

    @Test
    fun cornersOfTheKeyAreaHitTheEdgeKeys() {
        for ((name, area) in allAreas()) {
            assertEquals(name + " top-left", 0, area.owner(0.5f, 0.5f))
            assertTrue(name + " top-right", area.owner(area.width - 0.5f, 0.5f) >= 0)
            assertTrue(name + " bottom-left", area.owner(0.5f, area.height - 0.5f) >= 0)
            assertTrue(name + " bottom-right", area.owner(area.width - 0.5f, area.height - 0.5f) >= 0)
        }
    }

    @Test
    fun footerKeysTileTheBottomRow() {
        val row = lettersArea()
        val area = row.area
        val space = row.keys.indexOfFirst { it.action.type == LayoutActionKind.SPACE }
        assertTrue("footer has a space bar", space >= 0)
        val footerRow = row.rowOf(space)
        assertEquals("space bar is in the last row", row.rowStarts.lastIndex, footerRow)
        val start = row.rowStarts[footerRow]
        val end = start + row.rowSizes[footerRow]
        for (i in start until end) {
            val hit = area.hit[i]
            assertTrue("footer key " + i + " has a positive target", hit.right > hit.left && hit.bottom > hit.top)
        }
        val right = space + 1
        assertTrue("space bar is not the last footer key", right < end)
        val midpoint = (area.hit[space].right + area.hit[right].left) / 2f
        val y = (area.hit[space].top + area.hit[space].bottom) / 2f
        assertEquals(space, area.owner(midpoint - 0.25f, y))
        assertEquals(right, area.owner(midpoint + 0.25f, y))
    }

    @Test
    fun outerKeysReachTheAreaEdge() {
        val area = lettersArea().area
        val first = area.hit[0]
        assertTrue("left edge is owned", abs(first.left) < 0.01f)
        val last = area.hit.last()
        assertTrue("right edge is owned", abs(last.right - area.width) < 0.01f)
        assertTrue("top edge is owned", abs(first.top) < 0.01f)
        assertTrue("bottom edge is owned", abs(last.bottom - area.height) < 0.01f)
    }

    @Test
    fun gutterStripIsHalfTheVisualGap() {
        // The audit's dead strip was the middle half of the gap between two keycaps. Both halves of
        // that strip now belong to the nearer neighbour.
        val row = lettersArea()
        val area = row.area
        val gap = GAP_H_DP * DENSITY
        for (i in 0 until row.rowSizes[0] - 1) {
            val a = area.hit[i]
            val b = area.hit[i + 1]
            assertTrue("neighbour targets meet", abs(a.right - b.left) < 0.01f)
            assertTrue("gutter is split at the midpoint", abs(a.right - area.slots[i].right - gap / 2) < 0.01f)
        }
    }

    @Test
    fun visualGutterIsFiveDpWideAndEightDpTallOnRowPages() {
        val gapH = GAP_H_DP * DENSITY
        val gapV = GAP_V_DP * DENSITY
        for ((name, row) in listOf(
            "qwerty26" to lettersArea(),
            "abc" to lettersArea(english = true),
            "numeric" to pageArea(KeyboardPageKind.NUMERIC),
            "symbols" to pageArea(KeyboardPageKind.SYMBOLS),
        )) {
            val area = row.area
            for (index in row.rowStarts.indices) {
                val start = row.rowStarts[index]
                val end = start + row.rowSizes[index]
                if (end - start < 2) continue
                for (i in start until end - 1) {
                    val visual = area.drawn[i + 1].left - area.drawn[i].right
                    assertTrue(
                        name + " horizontal visible gap is " + visual + " px, expected " + gapH,
                        abs(visual - gapH) < 0.01f,
                    )
                }
                if (index > 0) {
                    val above = area.drawn[row.rowStarts[index - 1]].bottom
                    val visual = area.drawn[start].top - above
                    assertTrue(
                        name + " vertical visible gap is " + visual + " px, expected " + gapV,
                        abs(visual - gapV) < 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun visualGutterMatchesTheTokensOnTheNineKeyShell() {
        val gapH = GAP_H_DP * DENSITY
        val gapV = GAP_V_DP * DENSITY
        for ((name, shell) in listOf("phone9" to shellArea(), "phone9numeric" to shellArea(numeric = true))) {
            // The shell's own column gutter, measured between two adjacent grid columns.
            val grid = (0 until 3).map { column -> shell.indexOf(PhoneKeypadLayout.Region.MAIN, column) }
            val spacing =
                if (grid.all { it >= 0 }) {
                    shell.area.slots[grid[1]].left - shell.area.slots[grid[0]].right
                } else {
                    4 * DENSITY
                }
            val insetX = KeyInsets.phoneHorizontal(gapH, spacing)
            val insetY = KeyInsets.phoneVertical(gapV)
            assertTrue(name + " visual gutter is 5 dp", abs(spacing + 2 * insetX - gapH) < 0.01f)
            assertTrue(name + " visual row gap is 8 dp", abs(2 * insetY - gapV) < 0.01f)
        }
    }

    @Test
    fun fewerRailChoicesStillLeaveEveryPointOwned() {
        val fullSlots = shellArea().area.hit.size
        for (choices in 1..2) {
            val shell = shellArea(railChoices = choices)
            val area = shell.area
            assertEquals(
                "rail choices " + choices + " must not drop a physical key",
                fullSlots,
                area.hit.size,
            )
            var x = 0.5f
            while (x < area.width) {
                var y = 0.5f
                while (y < area.height) {
                    assertTrue(
                        "rail choices " + choices + ": dead point at " + x + "," + y,
                        area.owner(x, y) >= 0,
                    )
                    y += 3f
                }
                x += 3f
            }
            for (i in area.hit.indices) {
                for (j in i + 1 until area.hit.size) {
                    val a = area.hit[i]
                    val b = area.hit[j]
                    val overlapX = min(a.right, b.right) - max(a.left, b.left)
                    val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                    assertTrue(
                        "rail choices " + choices + ": keys " + i + " and " + j + " overlap",
                        overlapX <= 0.01f || overlapY <= 0.01f,
                    )
                }
            }
        }
    }
}

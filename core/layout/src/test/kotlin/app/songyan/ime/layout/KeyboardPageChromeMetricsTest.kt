package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPageChromeMetricsTest {
    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun metrics(
        chrome: KeyboardPageChrome,
        width: Double = 400.0,
    ) = KeyboardPageChromeMetrics.resolve(width, 54.0, chrome, 4.0)

    @Test
    fun noRailsLeavesTheWholeWidthToTheMainArea() {
        val m = metrics(KeyboardPageChrome(bottom = listOf(key("a"))))
        assertEquals(0.0, m.leadingWidth, 0.001)
        assertEquals(0.0, m.trailingWidth, 0.001)
        assertEquals(0.0, m.mainLeft, 0.001)
        assertEquals(400.0, m.mainWidth, 0.001)
        assertEquals(400.0, m.bottomWidth, 0.001)
    }

    @Test
    fun authoredRailWidthsTakeTheirSpaceAndAGap() {
        val m =
            metrics(
                KeyboardPageChrome(
                    leading = listOf(key("a")),
                    trailing = listOf(key("b")),
                    leadingWidth = 50.0,
                    trailingWidth = 60.0,
                ),
            )
        assertEquals(54.0, m.mainLeft, 0.001)
        assertEquals(400.0 - 54.0 - 64.0, m.mainWidth, 0.001)
        assertEquals(400.0, m.bottomWidth, 0.001)
    }

    @Test
    fun trailingRailSpanningTheBottomNarrowsTheFooter() {
        val m =
            metrics(
                KeyboardPageChrome(trailing = listOf(key("b")), trailingSpansBottom = true, trailingWidth = 60.0),
            )
        assertEquals(336.0, m.bottomWidth, 0.001)
    }

    @Test
    fun aMissingWidthTakesThePhoneKeypadRail() {
        val m = metrics(KeyboardPageChrome(leading = listOf(key("a"))))
        assertEquals(PhoneKeypadMetrics.resolve(400.0, 54.0).railWidth, m.leadingWidth, 0.001)
    }

    @Test
    fun oversizedRailsShrinkTogetherAndKeepAUsableMainArea() {
        val m =
            metrics(
                KeyboardPageChrome(
                    leading = listOf(key("a")),
                    trailing = listOf(key("b")),
                    leadingWidth = 300.0,
                    trailingWidth = 100.0,
                ),
                width = 400.0,
            )
        val minimumMain = 44.0 * 3 + 8.0
        assertEquals(minimumMain, m.mainWidth, 0.001)
        assertEquals(3.0, m.leadingWidth / m.trailingWidth, 0.001)
    }
}

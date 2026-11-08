package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * With the system's own keyboard switcher showing, the footer drops its globe key. The footer
 * stretched the space bar into the gap; Return takes that width instead.
 */
class KeyRowSolverGlobeTest {
    private fun key(
        kind: LayoutActionKind,
        width: Double? = null,
    ) = LayoutKey(label = kind.name, width = width, action = LayoutAction(kind))

    private val footer =
        LayoutRow(
            keys =
                listOf(
                    key(LayoutActionKind.GLOBE),
                    key(LayoutActionKind.PAGE_SWITCH),
                    key(LayoutActionKind.SPACE, width = 4.0),
                    key(LayoutActionKind.ENTER, width = 1.5),
                ),
        )

    @Test
    fun theGlobeWidthGoesToReturnInAFooter() {
        val keys = KeyRowSolver.columns(listOf(footer), showsGlobe = false).single()
        assertEquals(
            listOf(LayoutActionKind.PAGE_SWITCH, LayoutActionKind.SPACE, LayoutActionKind.ENTER),
            keys.map { it.action.type },
        )
        assertEquals(listOf(1.0, 4.0, 2.5), keys.map { it.widthWeight })
    }

    @Test
    fun aShownGlobeLeavesTheFooterAsAuthored() {
        assertEquals(footer.keys, KeyRowSolver.columns(listOf(footer), showsGlobe = true).single())
    }

    @Test
    fun aRowWithoutASpaceBarOnlyLosesTheGlobe() {
        val row = LayoutRow(keys = listOf(key(LayoutActionKind.GLOBE), key(LayoutActionKind.ENTER)))
        assertEquals(
            listOf(1.0),
            KeyRowSolver.columns(listOf(row), showsGlobe = false).single().map { it.widthWeight },
        )
    }
}

package app.songyan.ime.layout

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One reading of merged keys for 14, 18 and 9 keys, bundled or hand-written. */
class LayoutFoldTest {
    private fun LayoutDefinition.relabel(vararg changes: Pair<String, Pair<String, String>>): LayoutDefinition {
        val byLabel = changes.toMap()
        return copy(
            rows =
                rows.map { row ->
                    LayoutRow(
                        row.keys.map { key ->
                            val change = byLabel[key.label] ?: return@map key
                            key.copy(label = change.first, action = LayoutAction.code(change.second))
                        },
                    )
                },
        )
    }

    @Test
    fun theBundledLayoutsFoldIntoTheBundledFolds() {
        assertSame(CompactFold.MERGED14, LayoutFold.of(BuiltinLayouts.merged14, KeyboardLayoutKind.MERGED14))
        assertSame(CompactFold.MERGED18, LayoutFold.of(BuiltinLayouts.merged18, KeyboardLayoutKind.MERGED18))
        assertSame(CompactFold.PHONE9, LayoutFold.of(BuiltinLayouts.phone9, KeyboardLayoutKind.PHONE9))
    }

    @Test
    fun aRegroupedFourteenKeyLayoutFoldsAsWritten() {
        val firstKey =
            BuiltinLayouts.merged14.rows[0]
                .keys[0]
                .label
        val secondKey =
            BuiltinLayouts.merged14.rows[0]
                .keys[1]
                .label
        val moved = BuiltinLayouts.merged14.relabel(firstKey to ("Q" to "q"), secondKey to ("WER" to "w"))
        val fold = LayoutFold.of(moved, KeyboardLayoutKind.MERGED14)!!
        assertFalse(fold.isBuiltin)
        assertEquals('w', fold.map['e'])
        assertEquals('w', fold.map['r'])
        assertEquals('q', fold.map['q'])
        assertTrue(LayoutFold.problems(moved).isEmpty)
    }

    @Test
    fun aRegroupedNineKeyLayoutFoldsAsWritten() {
        val two =
            BuiltinLayouts.phone9.rows
                .flatMap { it.keys }
                .first { it.action.value == "2" }
                .label
        val three =
            BuiltinLayouts.phone9.rows
                .flatMap { it.keys }
                .first { it.action.value == "3" }
                .label
        val moved = BuiltinLayouts.phone9.relabel(two to ("AB" to "2"), three to ("CDEF" to "3"))
        val fold = LayoutFold.of(moved, KeyboardLayoutKind.PHONE9)!!
        assertEquals('3', fold.map['c'])
        assertTrue(fold.isPhoneDigits)
    }

    @Test
    fun aLetterOnTwoKeysOrOnNoneIsNamedAndDoesNotFold() {
        val firstKey =
            BuiltinLayouts.merged14.rows[0]
                .keys[0]
                .label
        val repeated = BuiltinLayouts.merged14.relabel(firstKey to ("QWE" to "q"))
        assertNull(LayoutFold.of(repeated, KeyboardLayoutKind.MERGED14))
        assertEquals(listOf('e'), LayoutFold.problems(repeated).repeated)
        val missing = BuiltinLayouts.merged14.relabel(firstKey to ("Q" to "q"))
        assertNull(LayoutFold.of(missing, KeyboardLayoutKind.MERGED14))
        assertEquals(listOf('w'), LayoutFold.problems(missing).missing)
    }
}

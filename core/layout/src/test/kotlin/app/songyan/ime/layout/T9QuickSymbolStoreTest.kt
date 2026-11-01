package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nine-key quick symbols: one drawable character per slot, no duplicates in any edit path. */
class T9QuickSymbolStoreTest {
    @Test
    fun theDefaultListIsValid() {
        assertTrue(T9QuickSymbolStore.isValid(T9QuickSymbolStore.DEFAULT_SYMBOLS))
        assertEquals(6, T9QuickSymbolSnapshot().characters.size)
    }

    @Test
    fun aSlotIsOneDrawableBmpCharacter() {
        assertFalse("whitespace", T9QuickSymbolStore.isValid("， 。"))
        assertFalse("control character", T9QuickSymbolStore.isValid("，\u0000"))
        assertFalse(
            "an astral scalar cannot be addressed as one Char slot",
            T9QuickSymbolStore.isValid("\uD83D\uDE00，"),
        )
        assertFalse("a lone surrogate renders as tofu", T9QuickSymbolStore.isValid("\uD83D，"))
        assertFalse("empty", T9QuickSymbolStore.isValid(""))
        assertFalse(
            "over the slot cap",
            T9QuickSymbolStore.isValid("，。？！、…，。？！、…，"),
        )
    }

    @Test
    fun wholeListEditsRejectDuplicates() {
        assertThrows(T9QuickSymbolException::class.java) {
            T9QuickSymbolStore.replaceSymbolsResult(T9QuickSymbolSnapshot(), "，。，")
        }
        val (updated, changed) = T9QuickSymbolStore.replaceSymbolsResult(T9QuickSymbolSnapshot(), "，。？！")
        assertTrue(changed)
        assertEquals("，。？！", updated.symbols)
        val (same, again) = T9QuickSymbolStore.replaceSymbolsResult(updated, "，。？！")
        assertFalse("re-selecting the saved list writes nothing", again)
        assertEquals(updated, same)
    }

    @Test
    fun aSlotEditCannotCreateADuplicate() {
        val current = T9QuickSymbolSnapshot(symbols = "，。？！")
        // "？" already sits in slot 2; moving it to slot 0 would leave the list the edit rejects.
        assertThrows(T9QuickSymbolException::class.java) {
            T9QuickSymbolStore.replaceSlotResult(current, 0, "？")
        }
        val (updated, changed) = T9QuickSymbolStore.replaceSlotResult(current, 0, "、")
        assertTrue(changed)
        assertEquals("、。？！", updated.symbols)
        val (same, again) = T9QuickSymbolStore.replaceSlotResult(updated, 0, "、")
        assertFalse("re-selecting the saved slot writes nothing", again)
        assertEquals(updated, same)
    }

    @Test
    fun slotEditsValidateTheirIndexAndSymbol() {
        val current = T9QuickSymbolSnapshot()
        assertThrows(T9QuickSymbolException::class.java) {
            T9QuickSymbolStore.replaceSlotResult(current, 99, "、")
        }
        assertThrows(T9QuickSymbolException::class.java) {
            T9QuickSymbolStore.replaceSlotResult(current, 0, "、。")
        }
        assertThrows(T9QuickSymbolException::class.java) {
            T9QuickSymbolStore.replaceSlotResult(current, 0, "\uD83D\uDE00")
        }
        // A first slot edit against the implicit default persists it.
        val (updated, changed) = T9QuickSymbolStore.replaceSlotResult(null, 0, "，")
        assertTrue(changed)
        assertEquals(T9QuickSymbolStore.DEFAULT_SYMBOLS, updated.symbols)
    }
}

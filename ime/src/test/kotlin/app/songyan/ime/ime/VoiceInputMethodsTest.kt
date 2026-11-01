package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceInputMethodsTest {
    private fun ime(
        id: String,
        pkg: String,
        vararg modes: String,
    ) = ImeEntry(id, pkg, id, modes.toList())

    @Test
    fun findsAnInputMethodWithAVoiceSubtype() {
        val list =
            listOf(
                ime("a/.Latin", "a", "keyboard"),
                ime("b/.Speech", "b", "keyboard", "voice"),
            )
        assertEquals(VoiceTarget("b/.Speech", 1, "b/.Speech"), VoiceInputMethods.find(list, "own"))
    }

    @Test
    fun theModeIsMatchedWithoutCaseAndNoVendorIsAssumed() {
        assertEquals(0, VoiceInputMethods.find(listOf(ime("x/.V", "vendor.unknown", "Voice")), "own")?.subtypeIndex)
    }

    @Test
    fun ownInputMethodNeverCounts() {
        assertNull(VoiceInputMethods.find(listOf(ime("own/.Ime", "own", "voice")), "own"))
    }

    @Test
    fun noneWhenNoSubtypeIsVoiceOrNothingIsEnabled() {
        assertNull(VoiceInputMethods.find(listOf(ime("a/.Latin", "a", "keyboard"), ime("b/.NoSubtypes", "b")), "own"))
        assertNull(VoiceInputMethods.find(emptyList(), "own"))
    }

    @Test
    fun theSystemsOrderDecidesBetweenSeveral() {
        val list = listOf(ime("first/.V", "first", "voice"), ime("second/.V", "second", "voice"))
        assertEquals("first/.V", VoiceInputMethods.find(list, "own")?.imeId)
    }
}

package app.songyan.ime.settings.ui

import app.songyan.ime.settings.ui.screens.parseWidePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidePositionInputTest {
    @Test
    fun `a typed whole number from 0 to 100 is accepted`() {
        assertEquals(0, parseWidePosition("0"))
        assertEquals(25, parseWidePosition(" 25 "))
        assertEquals(100, parseWidePosition("100"))
    }

    @Test
    fun `anything else is rejected`() {
        for (text in listOf("", "-1", "101", "2.5", "abc", "50%")) assertNull(text, parseWidePosition(text))
    }
}

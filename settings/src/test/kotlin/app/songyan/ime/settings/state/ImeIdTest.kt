package app.songyan.ime.settings.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeIdTest {
    @Test
    fun `short class form is expanded with the package`() {
        val id = parseImeId("app.songyan.ime/.SongyanInputMethodService")
        assertEquals("app.songyan.ime", id?.packageName)
        assertEquals("app.songyan.ime.SongyanInputMethodService", id?.className)
    }

    @Test
    fun `full class form is kept`() {
        val id = parseImeId("app.songyan.ime/app.songyan.ime.SongyanInputMethodService")
        assertEquals("app.songyan.ime.SongyanInputMethodService", id?.className)
    }

    @Test
    fun `subtype suffix is ignored`() {
        val id = parseImeId("app.songyan.ime/.SongyanInputMethodService:0")
        assertEquals("app.songyan.ime.SongyanInputMethodService", id?.className)
    }

    @Test
    fun `malformed ids are rejected`() {
        assertNull(parseImeId(null))
        assertNull(parseImeId(""))
        assertNull(parseImeId("   "))
        assertNull(parseImeId("app.songyan.ime"))
        assertNull(parseImeId("/.Ime"))
        assertNull(parseImeId("app.songyan.ime/"))
    }

    @Test
    fun `selection matches the same component only`() {
        val target = ImeId("app.songyan.ime", "app.songyan.ime.SongyanInputMethodService")
        assertTrue(selectedImeMatches("app.songyan.ime/.SongyanInputMethodService", target))
        assertFalse(selectedImeMatches("app.songyan.ime/.OtherService", target))
        assertFalse(selectedImeMatches("other.app/.Ime", target))
        assertFalse(selectedImeMatches(null, target))
        assertFalse(selectedImeMatches("", target))
    }
}

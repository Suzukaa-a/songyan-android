package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardTypefacesTest {
    @Test
    fun familiesMapToPlatformFamilies() {
        assertNull(KeyboardTypefaces.familyName("system"))
        assertEquals("serif", KeyboardTypefaces.familyName("serif"))
        assertEquals("monospace", KeyboardTypefaces.familyName("monospaced"))
        assertEquals("sans-serif-rounded", KeyboardTypefaces.familyName("rounded"))
    }

    @Test
    fun weightsMapToCssWeights() {
        assertEquals(400, KeyboardTypefaces.weightOf("regular"))
        assertEquals(500, KeyboardTypefaces.weightOf("medium"))
        assertEquals(600, KeyboardTypefaces.weightOf("semibold"))
    }
}

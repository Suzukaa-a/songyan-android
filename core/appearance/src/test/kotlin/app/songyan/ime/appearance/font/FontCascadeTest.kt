package app.songyan.ime.appearance.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCascadeTest {
    private val order = listOf("a.ttf", "b.otf", "c.ttc")
    private val all = order.toSet()

    @Test
    fun theChosenFontComesFirstThenTheRestInImportOrder() {
        assertEquals(listOf("b.otf", "a.ttf", "c.ttc"), FontCascade.files("b.otf", order, all))
        assertEquals(listOf("a.ttf", "b.otf", "c.ttc"), FontCascade.files("a.ttf", order, all))
    }

    @Test
    fun systemChoiceIsAnEmptyCascade() {
        assertTrue(FontCascade.files(FontCascade.SYSTEM, order, all).isEmpty())
    }

    @Test
    fun unreadableFontsAreSkippedInTheFallback() {
        assertEquals(listOf("c.ttc", "a.ttf"), FontCascade.files("c.ttc", order, setOf("a.ttf", "c.ttc")))
    }

    @Test
    fun aBrokenOrMissingChoiceIsTheSystemFont() {
        assertTrue(FontCascade.files("b.otf", order, setOf("a.ttf", "c.ttc")).isEmpty())
        assertTrue(FontCascade.files("gone.ttf", order, all).isEmpty())
    }

    @Test
    fun aDuplicateInTheOrderListAppearsOnce() {
        assertEquals(
            listOf("a.ttf", "b.otf"),
            FontCascade.files("a.ttf", listOf("a.ttf", "b.otf", "a.ttf", "b.otf"), setOf("a.ttf", "b.otf")),
        )
    }

    @Test
    fun storedNamesAreSafeAndUnique() {
        assertEquals("Font.ttf", FontFileNames.storedName("Font.ttf", emptyList()))
        assertEquals("Font-2.ttf", FontFileNames.storedName("Font.ttf", listOf("font.TTF")))
        assertEquals("Font-3.ttf", FontFileNames.storedName("Font.ttf", listOf("Font.ttf", "Font-2.ttf")))
        assertEquals("evil.ttf", FontFileNames.storedName("../../evil.ttf", emptyList()))
        assertEquals("a_b.otf", FontFileNames.storedName("a:b.OTF", emptyList()))
        assertEquals("font.ttf", FontFileNames.storedName("", emptyList()))
        assertEquals("hidden.ttf", FontFileNames.storedName(".hidden.ttf", emptyList()))
    }

    @Test
    fun rolesSelectTheirOwnList() {
        val files = DisplayFontFiles(candidates = listOf("a.ttf"), keys = listOf("b.otf"))
        assertEquals(listOf("a.ttf"), files.of(FontRole.CANDIDATES))
        assertEquals(listOf("b.otf"), files.of(FontRole.KEYS))
        assertNotEquals(files, DisplayFontFiles.SYSTEM)
    }

    @Test
    fun theStoredOrderRoundTrips() {
        assertEquals(listOf("a.ttf", "b b.otf"), FontOrderWire.decode(FontOrderWire.encode(listOf("a.ttf", "b b.otf"))))
        assertTrue(FontOrderWire.decode(null).isEmpty())
        assertEquals(listOf("a.ttf"), FontOrderWire.decode("a.ttf\n\na.ttf"))
    }
}

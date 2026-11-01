package app.songyan.ime.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The compact fold and layout facts. */
class KeyboardLayoutTest {
    @Test
    fun compactFoldsMatchTheDocumentedLetterGroups() {
        val merged14 = CompactFold.MERGED14
        assertEquals("14", merged14.id)
        assertEquals('q', merged14.map['q'])
        assertEquals('q', merged14.map['w'])
        assertEquals('e', merged14.map['e'])
        assertEquals('e', merged14.map['r'])
        assertFalse(merged14.isPhoneDigits)

        val merged18 = CompactFold.MERGED18
        assertEquals("18", merged18.id)
        assertEquals('q', merged18.map['q'])
        assertEquals('w', merged18.map['w'])
        assertEquals('w', merged18.map['e'])

        val phone9 = CompactFold.PHONE9
        assertTrue(phone9.isPhoneDigits)
        assertEquals('2', phone9.map['a'])
        assertEquals('9', phone9.map['z'])
        assertEquals(setOf('2', '3', '4', '5', '6', '7', '8', '9'), phone9.keysSent)
    }

    @Test
    fun exactTokensCoverEveryMergedLeaderAndStayOutOfTheAlphabet() {
        for (fold in listOf(CompactFold.MERGED14, CompactFold.MERGED18)) {
            val shared =
                fold.map
                    .filter { (source, target) -> source != target }
                    .values
                    .toSet()
            assertEquals("one token per merged key", shared.size, fold.exactTokens.size)
            assertTrue(
                "tokens never collide with a letter or digit the layout sends",
                fold.exactTokens.values.none { it in fold.keysSent || it.isLetterOrDigit() },
            )
        }
    }

    @Test
    fun layoutKindFacts() {
        assertNull(KeyboardLayoutKind.QWERTY26.compactFold)
        assertFalse(KeyboardLayoutKind.QWERTY26.requiresSpellingDisplay)
        assertTrue(KeyboardLayoutKind.MERGED14.requiresSpellingDisplay)
        assertTrue(KeyboardLayoutKind.PHONE9.requiresSpellingDisplay)
        assertEquals(KeyboardLayoutKind.KeyFold.IDENTITY, KeyboardLayoutKind.QWERTY26.keyFold)
        assertEquals(KeyboardLayoutKind.KeyFold.LETTER_GROUPS, KeyboardLayoutKind.MERGED18.keyFold)
        assertEquals(KeyboardLayoutKind.KeyFold.PHONE_DIGITS, KeyboardLayoutKind.PHONE9.keyFold)
        assertEquals("26 键", KeyboardLayoutKind.QWERTY26.userTitle)
    }

    @Test
    fun builtinTemplateSchemasMapEveryLayout() {
        for (layout in KeyboardLayoutKind.entries) {
            val engine = BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, layout)
            assertNotNull("$layout must have a companion", engine)
            assertEquals(
                layout,
                BuiltinTemplateSchemas.ADAPTERS.firstOrNull { it.schemaID == engine }?.layout
                    ?: KeyboardLayoutKind.QWERTY26,
            )
        }
        assertEquals(
            BuiltinTemplateSchemas.QUANPIN,
            BuiltinTemplateSchemas.defaultSchemaID(KeyboardLayoutKind.QWERTY26),
        )
        assertNull(BuiltinTemplateSchemas.engineSchemaID("unknown_scheme", KeyboardLayoutKind.MERGED14))
    }
}

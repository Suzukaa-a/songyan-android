package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layout's own grouping may put codes other than letters on a key; the companion folds them too. */
class CompactFoldCodesTest {
    @Test
    fun aMarkSharingALettersKeyFoldsOntoIt() {
        // The bundled 14-key grouping, plus `;` on the QW key.
        val fold = requireNotNull(CompactFold.of(KeyboardLayoutKind.MERGED14, CompactFold.MERGED14.map + (';' to 'q')))
        val physical = requireNotNull(CompactSchemaAdapter.physicalFold("abcdefghijklmnopqrstuvwxyz;", fold))
        assertTrue(physical.rules.toString(), "derive/;/q/" in physical.rules)
        assertFalse(';' in physical.ignoredCharacters)
        // Without it on a key, `;` is typed as itself, as before.
        val bundled =
            requireNotNull(CompactSchemaAdapter.physicalFold("abcdefghijklmnopqrstuvwxyz;", CompactFold.MERGED14))
        assertFalse(bundled.rules.any { it.startsWith("derive/;") })
    }

    @Test
    fun regexCharactersAreEscaped() {
        assertEquals("derive/\\./l/", CompactSchemaAdapter.deriveRule('.', 'l'))
        assertEquals("derive|\\/|l|", CompactSchemaAdapter.deriveRule('/', 'l'))
        assertEquals("derive/'/q/", CompactSchemaAdapter.deriveRule('\'', 'q'))
    }
}

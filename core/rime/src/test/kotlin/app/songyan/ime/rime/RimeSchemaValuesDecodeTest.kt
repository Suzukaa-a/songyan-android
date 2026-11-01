package app.songyan.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the sparse `(key, value)` decode of rime_jni.cc `nativeSchemaValues`.
 *
 * The bridge emits a pair only for keys that exist, so a missing key must not shift a
 * later key's value onto itself. These cases need no native library: the flat array the
 * bridge would return is fed directly.
 */
class RimeSchemaValuesDecodeTest {
    @Test
    fun missingKeyDoesNotShiftTheNextValue() {
        // "keyboard_adapter/source_schema" is absent, so the bridge only returns the second
        // pair. Positional decoding would attribute that value to the missing key.
        val decoded = decode(SPELLING_ALPHABET, ALPHABET)
        assertEquals(mapOf(SPELLING_ALPHABET to ALPHABET), decoded)
        assertFalse(
            "a missing key must not inherit another key's value",
            decoded.containsKey(KB_ADAPTER_SOURCE),
        )
    }

    @Test
    fun presentKeysDecodeByTheirOwnName() {
        assertEquals(mapOf("first" to "1", "second" to "2"), decode("first", "1", "second", "2"))
    }

    @Test
    fun emptyValueIsKept() {
        // A present but empty config scalar is different from an absent key.
        assertEquals(mapOf("menu/page_size" to ""), decode("menu/page_size", ""))
    }

    @Test
    fun trailingUnpairedElementIsIgnored() {
        // A truncated array must never read the last element as a value.
        assertEquals(mapOf("first" to "1"), decode("first", "1", "second"))
    }

    @Test
    fun nullEntriesAreSkipped() {
        assertEquals(emptyMap<String, String>(), decode("first", null, null, "2"))
        assertEquals(emptyMap<String, String>(), decode(null, "1"))
    }

    @Test
    fun emptyArrayYieldsEmptyMap() {
        assertTrue(RimeNative.decodeSchemaValues(emptyArray()).isEmpty())
    }

    @Test
    fun duplicateKeyKeepsTheLastValue() {
        assertEquals(mapOf("k" to "new"), decode("k", "old", "k", "new"))
    }

    private fun decode(vararg flat: String?): Map<String, String> = RimeNative.decodeSchemaValues(arrayOf(*flat))

    private companion object {
        const val KB_ADAPTER_SOURCE = "keyboard_adapter/source_schema"
        const val SPELLING_ALPHABET = "spelling/alphabet"
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    }
}

package app.songyan.ime.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dictionary links are fetched over the network, so only https is accepted. */
class DictionaryImportUrlTest {
    @Test
    fun httpsLinksAreAcceptedAndTrimmed() {
        assertEquals(
            "https://example.com/words.dict.yaml",
            normalizeDictionaryUrl("  https://example.com/words.dict.yaml  "),
        )
    }

    @Test
    fun theSchemeIsCaseInsensitive() {
        assertEquals(
            "HTTPS://Example.com/words.dict.yaml",
            normalizeDictionaryUrl("HTTPS://Example.com/words.dict.yaml"),
        )
    }

    @Test
    fun plainHttpAndOtherSchemesAreRejected() {
        assertNull(normalizeDictionaryUrl("http://example.com/words.dict.yaml"))
        assertNull(normalizeDictionaryUrl("ftp://example.com/words.dict.yaml"))
        assertNull(normalizeDictionaryUrl("file:///sdcard/words.dict.yaml"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(normalizeDictionaryUrl(""))
        assertNull(normalizeDictionaryUrl("   "))
    }

    @Test
    fun aLinkWithoutAHostIsRejected() {
        assertNull(normalizeDictionaryUrl("https://"))
        assertNull(normalizeDictionaryUrl("https:///words.dict.yaml"))
        assertNull(normalizeDictionaryUrl("https:// spaced.example/words.dict.yaml"))
    }
}

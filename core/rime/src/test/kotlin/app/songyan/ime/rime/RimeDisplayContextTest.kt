package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeDisplayContextTest {
    private fun context(
        mode: String = "canonical",
        declaration: String = "canonical_pinyin",
        producerSchema: String = "songyan_t9",
        producerLoaded: Boolean = true,
        dictionary: String = "luna_pinyin",
    ): String =
        """
        {"capability":{"version":1,"display_mode":"$mode","source_schema":"songyan_quanpin",
          "translator_namespace":"translator","separator":" ","mapping_policy":"unique_normal",
          "adapter_schema":"songyan_t9","syllables":{"64":"ni"}},
         "capability_present":true,
         "producer":{"schema_id":"$producerSchema","translator_namespace":"translator",
          "dictionary":"$dictionary","prism":"songyan_t9","producer_loaded":$producerLoaded},
         "source":{"source_schema":"songyan_quanpin","translator_namespace":"translator",
          "producer_class":"script_translator","dictionary":"luna_pinyin","alphabet":"abc",
          "declaration":"$declaration","prefix_declaration":"","algebra":[]}}
        """.trimIndent()

    @Test
    fun anAgreeingCanonicalCapabilityIsAuthorized() {
        val decoded = requireNotNull(RimeDisplayContext.fromJson(context()))
        assertTrue(decoded.capabilityPresent)
        assertEquals(CompositionDisplayMode.CANONICAL, decoded.authorizedMode())
    }

    @Test
    fun anyDisagreementKeepsTheNativePreedit() {
        assertNull(RimeDisplayContext.fromJson(context(declaration = ""))?.authorizedMode())
        assertNull(RimeDisplayContext.fromJson(context(producerSchema = "other"))?.authorizedMode())
        assertNull(RimeDisplayContext.fromJson(context(producerLoaded = false))?.authorizedMode())
        assertNull(RimeDisplayContext.fromJson(context(dictionary = "other"))?.authorizedMode())
        assertNull(RimeDisplayContext.fromJson(context(mode = "native"))?.authorizedMode())
    }

    @Test
    fun malformedJsonDecodesAsNothing() {
        assertNull(RimeDisplayContext.fromJson("{"))
        assertNull(requireNotNull(RimeDisplayContext.fromJson("{}")).authorizedMode())
    }
}

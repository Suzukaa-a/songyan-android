package app.songyan.ime.ime

import android.text.style.LocaleSpan
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.InputLanguage
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composing text handed to a real EditText carries the running scheme's language as a
 * LocaleSpan only when the marked text is not ASCII: a language's fonts render CJK and kana
 * differently, while an ASCII spelling (the bundled pinyin compositions) is the same text in
 * every language. The engine's own bopomofo composition supplies the non-ASCII case; the span
 * following a language change is checked on one CJK text through EditorConnection directly.
 */
@RunWith(AndroidJUnit4::class)
class ComposingLocaleSpanDeviceTest {
    @Test
    fun anAsciiCompositionCarriesNoLocaleSpan() {
        for (schemaID in ASCII_SCHEMES) {
            // A host per scheme: a scheme switch keeps the composition it inherited, and the
            // confirmed part would show up in the field before the next keystroke.
            withHost { host ->
                host.start()
                switchTo(host, schemaID)
                assertListed(host, schemaID)
                assertEquals("active schema", schemaID, activeSchema(host))
                host.type("ni")
                host.awaitIdle()
                val text = hostComposingText(host)
                assertTrue(
                    "composing text under " + schemaID + " was '" + text + "'",
                    text == "ni",
                )
                assertEquals(
                    "composing locale under " + schemaID,
                    emptyList<String>(),
                    hostComposingLocaleTags(host),
                )
            }
        }
    }

    @Test
    fun aBopomofoCompositionKeepsItsLocaleSpan() {
        withHost { host ->
            host.start()
            switchTo(host, BOPOMOFO_SCHEMA)
            assertListed(host, BOPOMOFO_SCHEMA)
            assertEquals("active schema", BOPOMOFO_SCHEMA, activeSchema(host))
            host.type("ni")
            host.awaitIdle()
            val candidates = host.barLabels()
            assertTrue("no candidates for ni: " + candidates, candidates.isNotEmpty())
            val text = hostComposingText(host)
            // The fixture schema declares the same letter-to-symbol preedit format a real bopomofo
            // schema declares: this text is the engine's own composition, not a supplied string.
            assertEquals("composing text", BOPOMOFO_NI, text)
            assertEquals("composing locale", listOf("zh-Hant"), hostComposingLocaleTags(host))
        }
    }

    @Test
    fun theComposingSpanFollowsALanguageChangeEvenWhenThePreeditIsTheSame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var field: EditText
        lateinit var editor: EditorConnection
        instrumentation.runOnMainSync {
            field = EditText(instrumentation.targetContext)
            field.requestFocus()
            field.setText("")
            field.setSelection(0)
            val connection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
            editor = EditorConnection { connection }
        }
        // A CJK character: its glyphs depend on the language, so the same text is handed over
        // again after the scheme switched language and the span must follow it.
        for ((language, languageTag) in LANGUAGE_LOCALES) {
            instrumentation.runOnMainSync { editor.update(CJK_TEXT, emptyList(), language = language) }
            instrumentation.waitForIdleSync()
            var tags: List<String> = emptyList()
            instrumentation.runOnMainSync {
                val text = field.editableText
                tags = text.getSpans(0, text.length, LocaleSpan::class.java).flatMap(::localeTags)
            }
            assertEquals("composing locale for " + language, listOf(languageTag), tags)
        }
    }

    private fun activeSchema(host: FeatureHost): String? = host.main2 { host.controller.session?.selectedSchemaID }

    private fun assertListed(
        host: FeatureHost,
        schemaID: String,
    ) {
        val schemas = host.main2 { host.backend.availableSchemas.map { it.id } }
        assertTrue("deployed schemas were " + schemas, schemaID in schemas)
    }

    private fun hostComposingText(host: FeatureHost): String = host.main2 { host.field.editableText.toString() }

    private fun hostComposingLocaleTags(host: FeatureHost): List<String> =
        host.main2 {
            val editable = host.field.editableText
            editable.getSpans(0, editable.length, LocaleSpan::class.java).flatMap(::localeTags)
        }

    private fun localeTags(span: LocaleSpan): List<String> = span.locales.toLanguageTags().split(",")

    private fun switchTo(
        host: FeatureHost,
        schemaID: String,
    ) {
        host.main { runBlocking { host.controller.session?.selectSchema(schemaID) } }
        host.awaitIdle()
    }

    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            block(host)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    private companion object {
        /** The bundled pinyin schemes; their compositions are the spelling the user typed. */
        val ASCII_SCHEMES = listOf("songyan_quanpin", "songyan_flypy")

        /** A CJK character whose glyph forms differ between Japanese and Chinese fonts. */
        const val CJK_TEXT = "直"

        val LANGUAGE_LOCALES =
            listOf(
                InputLanguage.JA to "ja",
                InputLanguage.ZH_HANT to "zh-Hant",
                InputLanguage.ZH_HANS to "zh-Hans",
            )

        lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deployTheFixtureSchemes() {
            engine = DeployedEngine("composing-locale", english = false, patch = ::addInputLanguageFixtureSchemes)
        }

        @AfterClass
        @JvmStatic
        fun removeTheFixtureSchemes() {
            engine.delete()
        }
    }
}

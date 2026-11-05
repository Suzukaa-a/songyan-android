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
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composing text handed to a real EditText carries the running scheme's language as a
 * LocaleSpan, so the editor can pick that language's glyph forms. Every input language carries its
 * own locale, simplified Chinese included; a custom editor is free to ignore the span.
 */
@RunWith(AndroidJUnit4::class)
class ComposingLocaleSpanDeviceTest {
    @Test
    fun everyInputLanguageComposesWithItsOwnLocaleSpan() {
        withHost { host ->
            host.start()
            for ((schemaID, languageTag) in SCHEME_LOCALES) {
                switchTo(host, schemaID)
                host.type("ka")
                host.awaitIdle()
                assertEquals(
                    "composing locale under " + schemaID,
                    listOf(languageTag),
                    hostComposingLocaleTags(host),
                )
            }
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
        // The same text is handed over again after the scheme switched language.
        for ((language, languageTag) in LANGUAGE_LOCALES) {
            instrumentation.runOnMainSync { editor.update("ka", emptyList(), language = language) }
            instrumentation.waitForIdleSync()
            var tags: List<String> = emptyList()
            instrumentation.runOnMainSync {
                val text = field.editableText
                tags = text.getSpans(0, text.length, LocaleSpan::class.java).flatMap(::localeTags)
            }
            assertEquals("composing locale for " + language, listOf(languageTag), tags)
        }
    }

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
        /** Real schemes the input-language table reads as ja, zh-Hans and zh-Hant. */
        val SCHEME_LOCALES =
            listOf(
                "kagiroi_test" to "ja",
                "songyan_quanpin" to "zh-Hans",
                "bopomofo_test" to "zh-Hant",
            )

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

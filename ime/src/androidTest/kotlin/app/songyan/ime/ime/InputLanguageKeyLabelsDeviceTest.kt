package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Automatic key names over the shipped engine: a schema the input-language table recognizes turns
 * the whole keyboard, and switching back to the built-in full pinyin restores simplified Chinese
 * names. The fixture schemas reuse the built-in dictionary; they prove the keyboard rules, not the
 * full Japanese input experience.
 */
@RunWith(AndroidJUnit4::class)
class InputLanguageKeyLabelsDeviceTest {
    @Test
    fun aJapaneseSchemeNamesItsKeysInJapaneseAndTheBuiltInSchemeInSimplifiedChinese() {
        withHost { host ->
            host.start()
            // The host starts on the bundled pair; the fixture scheme proves the table switch.
            switchTo(host, "kagiroi_test")
            assertTrue("ja space: " + drawn(host), "空白" in drawn(host))
            assertFalse("no simplified space under ja: " + drawn(host), "空格" in drawn(host))
            host.type("ka")
            host.awaitIdle()
            assertTrue("ja composing space: " + drawn(host), "変換" in drawn(host))
            assertTrue("ja composing return: " + drawn(host), "確定" in drawn(host))
            switchTo(host, "songyan_quanpin")
            assertTrue("built-in space: " + drawn(host), "空格" in drawn(host))
            assertFalse("no japanese space: " + drawn(host), "空白" in drawn(host))
        }
    }

    @Test
    fun aTraditionalSchemeNamesItsKeysInTraditionalChinese() {
        withHost { host ->
            host.start(imeOptions = EditorInfo.IME_ACTION_SEARCH)
            switchTo(host, "bopomofo_test")
            assertTrue("hant space: " + drawn(host), "空白" in drawn(host))
            assertTrue("hant search: " + drawn(host), "搜尋" in drawn(host))
            switchTo(host, "songyan_quanpin")
            assertTrue("built-in space: " + drawn(host), "空格" in drawn(host))
            assertTrue("built-in search: " + drawn(host), "搜索" in drawn(host))
        }
    }

    private fun drawn(host: FeatureHost): List<String> = host.main2 { host.find(KeyboardView::class.java).labels }

    /** Switches the running session, then waits until the controller repaints the new faces. */
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
        lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deployTheFixtureSchemes() {
            engine = DeployedEngine("input-language", english = false, patch = ::addScriptSchemes)
        }

        @AfterClass
        @JvmStatic
        fun removeTheFixtureSchemes() {
            engine.delete()
        }

        /** Adds two real schemas the input-language table recognizes, ahead of the built-in pair. */
        private fun addScriptSchemes(source: File) {
            for ((id, name) in listOf("kagiroi_test" to "カギロイ", "bopomofo_test" to "注音測試")) {
                File(source, id + ".schema.yaml").writeText(schema(id, name))
            }
            val default = File(source, "default.yaml")
            default.writeText(
                default.readText().replace(
                    "schema_list:",
                    "schema_list:\n  - schema: kagiroi_test\n  - schema: bopomofo_test",
                ),
            )
        }

        private fun schema(
            id: String,
            name: String,
        ): String =
            """
            schema:
              schema_id: $id
              name: $name
              version: "0.0.1"
            engine:
              processors:
                - speller
                - selector
                - express_editor
              segmentors:
                - abc_segmentor
              translators:
                - script_translator
              filters:
                - uniquifier
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
              algebra: []
            translator:
              dictionary: songyan_basic
              prism: $id
              user_dict: shared_$id
              enable_user_dict: false
            """.trimIndent() + "\n"
    }
}

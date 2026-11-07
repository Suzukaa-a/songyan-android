package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The marks beside the space bar over the shipped engine: the native footer brackets it with the
 * comma the script language uses (Japanese 、, Chinese ，) and the full stop 。, and a tap types
 * exactly the shown mark. The fixture schemas reuse the built-in dictionary; they prove the
 * keyboard rules, not the full Japanese input experience.
 */
@RunWith(AndroidJUnit4::class)
class InputLanguagePunctuationDeviceTest {
    @Test
    fun theMarksBesideTheSpaceBarFollowTheInputLanguage() {
        withHost { host ->
            host.start()
            assertSpaceSides(host, left = "，", right = "。")
            host.tap("，")
            host.assertText("，")
            host.tap("。")
            host.assertText("，。")
            switchTo(host, "kagiroi_test")
            assertSpaceSides(host, left = "、", right = "。")
            host.tap("、")
            host.assertText("，。、")
            switchTo(host, "songyan_quanpin")
            assertSpaceSides(host, left = "，", right = "。")
            host.tap("，")
            host.assertText("，。、，")
        }
    }

    @Test
    fun aTraditionalSchemeKeepsTheChineseMarks() {
        withHost { host ->
            host.start()
            switchTo(host, "bopomofo_test")
            assertSpaceSides(host, left = "，", right = "。")
        }
    }

    private fun assertSpaceSides(
        host: FeatureHost,
        left: String,
        right: String,
    ) {
        val labels = host.main2 { host.find(KeyboardView::class.java).labels }
        val space = labels.indexOfFirst { it == "空格" || it == "空白" }
        assertTrue("no space bar in " + labels, space > 0 && space < labels.lastIndex)
        assertEquals("left of space in " + labels, left, labels[space - 1])
        assertEquals("right of space in " + labels, right, labels[space + 1])
    }

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
            engine = DeployedEngine("input-language-punct", english = false, patch = ::addScriptSchemes)
        }

        @AfterClass
        @JvmStatic
        fun removeTheFixtureSchemes() {
            engine.delete()
        }

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

package app.songyan.ime.ime

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.panels.KeyboardSettingsPanelView
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.rime.EngineOptionPreferences
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * A scheme's switches (traditional output here) in the keyboard settings panel, against the real engine.
 * No bundled scheme declares a switch, so the deployed full-pinyin scheme gets one the way a scheme
 * package would add it: a simplifier filter with its OpenCC config, and the switch it is driven by.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class EngineSwitchesDeviceTest {
    private fun withHost(
        options: File,
        block: (FeatureHost) -> Unit,
    ) {
        val host = FeatureHost(engine.backend(options))
        try {
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.tileLabel() = string(R.string.ime_qs_engine_traditional)

    private fun FeatureHost.panelLabels() = labelsOf(KeyboardSettingsPanelView::class.java)

    private fun FeatureHost.toggleTraditional() {
        tapToolbar(string(R.string.ime_menu_keyboard_settings))
        tap(tileLabel(), KeyboardSettingsPanelView::class.java, prefix = true)
        main2 { controller.closePanels() }
        awaitIdle()
    }

    private fun FeatureHost.candidatesFor(letters: String): List<String> {
        type(letters)
        await { barLabels().isNotEmpty() }
        return barLabels().also {
            tap(string(R.string.ime_delete))
            tap(string(R.string.ime_delete))
            tap(string(R.string.ime_delete))
        }
    }

    private fun optionsDir() = File(engine.root, "options-" + System.nanoTime()).also { it.mkdirs() }

    @Test
    fun theSchemesSwitchShowsInThePanelAndTheKeyboardStateCarriesIt() =
        withHost(optionsDir()) { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_menu_keyboard_settings))
            assertTrue(host.panelLabels().toString(), host.panelLabels().any { it.startsWith(host.tileLabel()) })
            val states = host.main2 { host.controller.session!!.options }
            assertEquals(listOf("traditionalize"), states.map { it.descriptor.name })
            assertEquals(EngineOptionKind.TRADITIONAL, states.single().descriptor.kind)
            assertFalse(states.single().value)
        }

    @Test
    fun turningTheSwitchOnChangesTheCandidatesAndTurningItOffRestoresThem() =
        withHost(optionsDir()) { host ->
            host.start()
            val simplified = host.candidatesFor("guo")
            assertTrue("国 in $simplified", "国" in simplified)

            host.toggleTraditional()
            val traditional = host.candidatesFor("guo")
            assertTrue("國 in $traditional", "國" in traditional)
            assertFalse("国" in traditional)

            host.toggleTraditional()
            assertTrue("国" in host.candidatesFor("guo"))
        }

    @Test
    fun theChoiceSurvivesARestartOfTheEngineAndTheKeyboard() {
        val options = optionsDir()
        withHost(options) { host ->
            host.start()
            host.toggleTraditional()
            assertTrue("國" in host.candidatesFor("guo"))
        }
        withHost(options) { host ->
            host.start()
            val states = host.main2 { host.controller.session!!.options }
            assertTrue("the saved choice is shown on the new session", states.single().value)
            assertTrue("國" in host.candidatesFor("guo"))
            // And the committed text is traditional.
            host.type("guo")
            host.tap("國", CandidateBarView::class.java)
            host.assertText("國")
        }
    }

    @Test
    fun anotherProfileStartsAtTheSchemesDefault() {
        withHost(optionsDir()) { host ->
            host.start()
            host.toggleTraditional()
        }
        withHost(optionsDir()) { host ->
            host.start()
            assertFalse(
                host.main2 {
                    host.controller.session!!
                        .options
                        .single()
                        .value
                },
            )
            assertTrue("国" in host.candidatesFor("guo"))
        }
    }

    @Test
    fun aChangeMadeWhileTheKeyboardWasAwayIsAppliedWhenTheNextEditorStarts() {
        val options = optionsDir()
        withHost(options) { host ->
            host.start()
            assertTrue("国" in host.candidatesFor("guo"))
            // The settings app writes the same store the keyboard reads; the engine keeps running.
            val descriptor =
                host.main2 {
                    host.controller.session!!
                        .options
                        .single()
                        .descriptor
                }
            EngineOptionPreferences(options).write(descriptor, true)
            host.main { host.controller.retire() }
            host.start()
            assertTrue("國" in host.candidatesFor("guo"))
        }
    }

    @Test
    fun nativeCharacterChoicesAndCustomNamedEmojiWorkThroughThePanelAndSurviveRestart() {
        val radio =
            DeployedEngine("radio-switches", english = false) { source ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val opencc = File(source, "opencc")
                copyTree(context.assets, "rime/opencc", opencc)
                File(opencc, "custom_faces.txt").writeText("国\t😀\n")
                File(opencc, "custom_faces.json").writeText(
                    """{"name":"Emoji","segmentation":{"type":"mmseg","dict":{"type":"text","file":"custom_faces.txt"}},"conversion_chain":[{"dict":{"type":"text","file":"custom_faces.txt"}}]}""",
                )
                val schema = File(source, "songyan_quanpin.schema.yaml")
                schema.writeText(
                    schema.readText().replace(
                        "  filters:\n    - uniquifier",
                        "  filters:\n    - simplifier@emoji\n    - simplifier@traditional\n    - simplifier@hongkong\n    - simplifier@taiwan\n    - uniquifier",
                    ) + """

switches:
  - options: [original, traditional, hongkong, taiwan]
    reset: 0
    states: [简体, 繁体, 香港, 臺灣]
  - name: emoji
    reset: 0
    states: [关, 开]
traditional:
  option_name: traditional
  opencc_config: s2t.json
hongkong:
  option_name: hongkong
  opencc_config: s2hk.json
taiwan:
  option_name: taiwan
  opencc_config: s2tw.json
emoji:
  option_name: emoji
  opencc_config: custom_faces.json
  tips: all
""",
                )
            }
        val options = File(radio.root, "choices")

        fun run(block: (FeatureHost) -> Unit) {
            val host = FeatureHost(radio.backend(options))
            try {
                block(host)
            } finally {
                host.close()
                kotlinx.coroutines.runBlocking { host.backend.shutdown() }
            }
        }
        try {
            run { host ->
                host.start()
                host.tapToolbar(host.string(R.string.ime_menu_keyboard_settings))
                host.tap(
                    host.string(R.string.ime_qs_engine_emoji),
                    KeyboardSettingsPanelView::class.java,
                    prefix = true,
                )
                assertTrue(
                    host.await {
                        host.main2 {
                            host.controller.session!!
                                .options
                                .first {
                                    it.descriptor.name ==
                                        "emoji"
                                }.value
                        }
                    },
                )
                host.main2 { host.controller.closePanels() }
                assertTrue(host.await { host.labelsOf(KeyboardView::class.java).isNotEmpty() })
                host.awaitIdle()
                val emojiCandidates = host.candidatesFor("guo")
                assertTrue(
                    "custom named emoji conversion: $emojiCandidates",
                    emojiCandidates.any {
                        it.substringBefore(" ") ==
                            "😀"
                    },
                )
                host.tapToolbar(host.string(R.string.ime_menu_keyboard_settings))
                host.tap(
                    host.string(R.string.ime_qs_engine_emoji),
                    KeyboardSettingsPanelView::class.java,
                    prefix = true,
                )
                assertTrue(
                    host.await {
                        host.main2 {
                            !host.controller.session!!
                                .options
                                .first {
                                    it.descriptor.name ==
                                        "emoji"
                                }.value
                        }
                    },
                )
                host.main2 { host.controller.closePanels() }
                assertTrue(host.await { host.labelsOf(KeyboardView::class.java).isNotEmpty() })
                host.awaitIdle()
                for (label in listOf("繁体", "香港", "臺灣")) {
                    host.tapToolbar(host.string(R.string.ime_menu_keyboard_settings))
                    host.tap(
                        host.string(R.string.ime_qs_engine_character_form),
                        KeyboardSettingsPanelView::class.java,
                        prefix = true,
                    )
                    host.tap(label, KeyboardSettingsPanelView::class.java)
                    host.main2 { host.controller.closePanels() }
                    host.awaitIdle()
                    val selected =
                        host.main2 {
                            host.controller.session!!.options.filter {
                                it.descriptor.choiceGroup.isNotEmpty() &&
                                    it.value
                            }
                        }
                    assertEquals(listOf(label), selected.map { it.descriptor.choiceLabel })
                    assertTrue("$label candidates", "國" in host.candidatesFor("guo"))
                }
                host.type("guo")
                host.tap("國", CandidateBarView::class.java)
                host.assertText("國")
            }
            run { host ->
                host.start()
                val selected =
                    host.main2 {
                        host.controller.session!!.options.filter {
                            it.descriptor.choiceGroup.isNotEmpty() &&
                                it.value
                        }
                    }
                assertEquals(listOf("taiwan"), selected.map { it.descriptor.name })
                assertTrue("saved character form", "國" in host.candidatesFor("guo"))
            }
        } finally {
            radio.delete()
        }
    }

    private companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine =
                DeployedEngine("switches", english = false) { source ->
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    copyTree(context.assets, "rime/opencc", File(source, "opencc"))
                    val schema = File(source, "songyan_quanpin.schema.yaml")
                    val text = schema.readText()
                    val patched =
                        text
                            .replace(
                                "  filters:\n    - uniquifier",
                                "  filters:\n    - simplifier@traditionalize\n    - uniquifier",
                            ).plus(
                                "\nswitches:\n  - name: traditionalize\n    reset: 0\n    states: [ 简, 繁 ]\n" +
                                    "traditionalize:\n  option_name: traditionalize\n  opencc_config: s2t.json\n",
                            )
                    assertNotEquals("the filter list moved", text, patched)
                    schema.writeText(patched)
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }

        private fun copyTree(
            assets: AssetManager,
            path: String,
            target: File,
        ) {
            target.mkdirs()
            for (name in assets.list(path).orEmpty()) {
                val child = "$path/$name"
                if (assets.list(child).orEmpty().isNotEmpty()) {
                    copyTree(assets, child, File(target, name))
                } else {
                    assets.open(child).use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

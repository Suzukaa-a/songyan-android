package app.songyan.ime.ime

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.panels.KeyboardSettingsPanelView
import app.songyan.ime.keyboard.engine.BuiltinScriptState
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
 * A scheme's switches in the keyboard settings panel, against the real engine: the bundled scheme's
 * own four-state script group, and the generic single switch, radio group and emoji switch a scheme
 * package adds the way an author would.
 */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class EngineSwitchesDeviceTest {
    private fun withHost(
        options: File,
        block: (FeatureHost) -> Unit,
    ) = withHost(engine, options, block)

    private fun withHost(
        deployed: DeployedEngine,
        options: File,
        block: (FeatureHost) -> Unit,
    ) {
        val host = FeatureHost(deployed.backend(options))
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
        // Closing a panel is animated: wait for the key surface before typing on it.
        assertTrue(await { labelsOf(KeyboardView::class.java).isNotEmpty() })
        awaitIdle()
    }

    private fun FeatureHost.candidatesFor(letters: String): List<String> {
        type(letters)
        await { barLabels().isNotEmpty() }
        return barLabels().also {
            tapControl(ControlKey.DELETE)
            tapControl(ControlKey.DELETE)
            tapControl(ControlKey.DELETE)
        }
    }

    private fun optionsDir() = optionsDir(engine)

    private fun optionsDir(deployed: DeployedEngine) =
        File(deployed.root, "options-" + System.nanoTime()).also { it.mkdirs() }

    /**
     * The shipped bundled scheme offers its four script states by their resources, and the engine
     * really converts: Taiwan's s2twp turns ruanjian into 軟體.
     */
    @Test
    fun theBuiltinSchemeOffersFourScriptStates() =
        withHost(builtin, optionsDir(builtin)) { host ->
            host.start()
            val states = host.main2 { host.controller.session!!.options }
            assertEquals(listOf("simplification", "zht", "zh_tw", "zh_hk"), states.map { it.descriptor.name })
            assertEquals(
                listOf(
                    BuiltinScriptState.SIMPLIFIED,
                    BuiltinScriptState.TRADITIONAL,
                    BuiltinScriptState.TAIWAN,
                    BuiltinScriptState.HONG_KONG,
                ),
                states.map { it.descriptor.knownScriptState },
            )
            assertEquals(listOf(true, false, false, false), states.map { it.value })
            // The data keeps its own labels; the UI words the four states from resources.
            assertEquals(listOf("简体", "繁體", "臺灣", "香港"), states.map { it.descriptor.choiceLabel })

            host.tapToolbar(host.string(R.string.ime_menu_keyboard_settings))
            host.tap(
                host.string(R.string.ime_qs_engine_character_form),
                KeyboardSettingsPanelView::class.java,
                prefix = true,
            )
            val labels = host.panelLabels()
            for (label in FOUR_STATE_LABELS) {
                assertTrue("$label in $labels", labels.any { it.startsWith(host.string(label)) })
            }
            host.tap(host.string(R.string.ime_qs_script_taiwan), KeyboardSettingsPanelView::class.java)
            assertTrue(
                host.await {
                    host.main2 {
                        host.controller.session!!
                            .options
                            .first { it.descriptor.name == "zh_tw" }
                            .value
                    }
                },
            )
            host.main2 { host.controller.closePanels() }
            assertTrue(host.await { host.labelsOf(KeyboardView::class.java).isNotEmpty() })
            host.awaitIdle()

            host.type("ruanjian")
            assertTrue(host.await { host.barLabels().any { it.startsWith("軟體") } })
            host.tap("軟體", CandidateBarView::class.java, prefix = true)
            host.assertText("軟體")
        }

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
                val bundled = schema.readText()
                // The author replaces the bundled's own conversion filters and switches block.
                val filters =
                    bundled.replace(
                        "    - simplifier@zht\n    - simplifier@zh_tw\n    - simplifier@zh_hk\n",
                        "    - simplifier@emoji\n    - simplifier@traditional\n" +
                            "    - simplifier@hongkong\n    - simplifier@taiwan\n",
                    )
                assertNotEquals("the bundled script filters moved", bundled, filters)
                val switches =
                    filters.replace(
                        "switches:\n  - options: [simplification, zht, zh_tw, zh_hk]\n" +
                            "    states: [简体, 繁體, 臺灣, 香港]\n    reset: 0",
                        "switches:\n  - options: [original, traditional, hongkong, taiwan]\n" +
                            "    reset: 0\n    states: [简体, 繁体, 香港, 臺灣]\n" +
                            "  - name: emoji\n    reset: 0\n    states: [关, 开]",
                    )
                assertNotEquals("the bundled switches block moved", filters, switches)
                schema.writeText(
                    switches + """

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
                    assertTrue(host.await { host.labelsOf(KeyboardView::class.java).isNotEmpty() })
                    host.awaitIdle()
                    val selected =
                        host.main2 {
                            host.controller.session!!.options.filter {
                                it.descriptor.choiceGroup.isNotEmpty() &&
                                    it.value
                            }
                        }
                    assertEquals(listOf(label), selected.map { it.descriptor.choiceLabel })
                    // An author's group is worded by its own labels, never by the bundled state resources.
                    assertEquals(listOf(null), selected.map { it.descriptor.knownScriptState })
                    assertTrue("$label candidates", "國" in host.candidatesFor("guo"))
                }
                assertTrue(host.await { host.labelsOf(KeyboardView::class.java).isNotEmpty() })
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
        /** The bundled scheme exactly as shipped: its own four-state group declares which states it has. */
        private lateinit var builtin: DeployedEngine

        /** A copy of the bundled scheme patched to one generic boolean switch, as a scheme package adds one. */
        private lateinit var engine: DeployedEngine

        private val FOUR_STATE_LABELS =
            listOf(
                R.string.ime_qs_script_simplified,
                R.string.ime_qs_script_traditional,
                R.string.ime_qs_script_taiwan,
                R.string.ime_qs_script_hong_kong,
            )

        @BeforeClass
        @JvmStatic
        fun deploy() {
            builtin =
                DeployedEngine("builtin", english = false) { source ->
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    copyTree(context.assets, "rime/opencc", File(source, "opencc"))
                }
            engine =
                DeployedEngine("switches", english = false) { source ->
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    copyTree(context.assets, "rime/opencc", File(source, "opencc"))
                    val schema = File(source, "songyan_quanpin.schema.yaml")
                    val text = schema.readText()
                    // Replace the bundled's own filters and switches block with one author-style switch.
                    val filters =
                        text.replace(
                            "    - simplifier@zht\n    - simplifier@zh_tw\n    - simplifier@zh_hk\n",
                            "    - simplifier@traditionalize\n",
                        )
                    assertNotEquals("the bundled script filters moved", text, filters)
                    val switches =
                        filters.replace(
                            "switches:\n  - options: [simplification, zht, zh_tw, zh_hk]\n" +
                                "    states: [简体, 繁體, 臺灣, 香港]\n    reset: 0",
                            "switches:\n  - name: traditionalize\n    reset: 0\n    states: [ 简, 繁 ]",
                        )
                    assertNotEquals("the bundled switches block moved", filters, switches)
                    schema.writeText(
                        switches +
                            "\ntraditionalize:\n  option_name: traditionalize\n  opencc_config: s2t.json\n",
                    )
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
            builtin.delete()
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

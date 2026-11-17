package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.GeneratedLayouts
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.UserKeyboards
import app.songyan.ime.packages.SchemaKeyProfiles
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * rime-wubi on the real engine: its deployed schema reads as a shape-code scheme, it is offered on
 * 26-key layouts only, and the 26-key board made for it carries the `'` key that ends a reverse
 * lookup. The package is pushed to the device (`-e wubiFixture <dir>`), never committed.
 */
@RunWith(AndroidJUnit4::class)
class WubiKeyProfileDeviceTest {
    @Test
    fun wubiIsShapeCodeOffered26KeyOnlyWithAKeyThatReachesTheEngine() {
        // The test engine loads the schema from its source tree as the package wrote it; the app's
        // deployment profiles the compiled copy, which the end-to-end run covers.
        val profile = SchemaKeyProfiles.compute(File(engine.source, "$WUBI.schema.yaml").readText())
        val profiles = mapOf(WUBI to profile)
        assertEquals(SchemaKeyProfile.Kind.SHAPE_CODE, profile.kind)
        assertTrue("'" in profile.codes)

        val backend = engine.backend()
        (backend as RimeEngineBackend).keyProfiles = profiles
        val host = FeatureHost(backend, "qwerty26", schemaID = WUBI)
        try {
            val resources =
                requireNotNull(KeyboardResourceSets.forEngineSchemas(backend.availableSchemas, emptyList(), profiles))
            assertEquals(
                listOf(KeyboardLayoutKind.QWERTY26),
                resources.availableCombinations.filter { it.schemaID == WUBI }.map { it.layout },
            )
            val families = resources.let { app.songyan.ime.keyboard.engine.LayoutFamilyContext.UNKNOWN }
            val entries =
                ApplicableLayouts.entries(
                    KeyboardScheme(WUBI, setOf(KeyboardLayoutKind.QWERTY26), profile),
                    LayoutRegistry.create(),
                    families,
                )
            assertEquals(GeneratedLayouts.keys26Id(WUBI), entries.first().keyboard.board)

            val board = GeneratedLayouts.keys26(WUBI, "26 键＋编码键", profile)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                KeyboardLayoutCatalog.install(LayoutRegistry.create().install(board.encoded()))
            }
            host.myKeyboardsFlow.value =
                UserKeyboards.Stored(
                    listOf(PublishedKeyboard(WUBI, KeyboardLayoutKind.QWERTY26, board.id)),
                    setOf(WUBI),
                )
            host.layoutDefinitionFlow.value = board.id
            host.start()
            host.awaitIdle()
            assertEquals(board.id, host.main2 { host.controller.mainDefinition().id })

            host.tap("g")
            host.tap("'")
            val raw = host.backend.currentSnapshot().rawInput
            assertTrue("the engine received the apostrophe: $raw", raw.contains("'") || host.text().isNotEmpty())
        } finally {
            host.close()
            runBlocking { backend.shutdown() }
        }
    }

    companion object {
        private const val WUBI = "wubi86"
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            val dir = InstrumentationRegistry.getArguments().getString("wubiFixture")
            requireNotNull(dir) { "pass -e wubiFixture <directory with wubi86.schema.yaml and wubi86.dict.yaml>" }
            engine =
                DeployedEngine("wubi-profile", english = false) { source ->
                    for (name in listOf(
                        "wubi86.schema.yaml",
                        "wubi86.dict.yaml",
                    )) {
                        File(dir, name).copyTo(File(source, name), true)
                    }
                    // The reverse lookup dictionary the schema depends on, reduced to one entry.
                    File(
                        source,
                        "pinyin_simp.dict.yaml",
                    ).writeText("---\nname: pinyin_simp\nversion: \"1\"\nsort: by_weight\n...\n你\tni\n")
                    File(source, "pinyin_simp.schema.yaml").writeText(
                        "schema:\n  schema_id: pinyin_simp\n  name: pinyin_simp\n" +
                            "engine:\n  translators:\n    - script_translator\n" +
                            "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: pinyin_simp\n",
                    )
                    File(source, "default.yaml").appendText("  - schema: wubi86\n")
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
            if (::engine.isInitialized) engine.delete()
        }
    }
}

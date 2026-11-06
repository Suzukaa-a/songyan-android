package app.songyan.ime.ime

import android.content.ClipboardManager
import android.content.res.AssetManager
import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.BuiltinToolDefinitions
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.ToolOpenCCConverter
import app.songyan.ime.content.ToolStore
import app.songyan.ime.ime.panels.QuickSettingChange
import app.songyan.ime.ime.tools.EditorConnectionKeyboardTools
import app.songyan.ime.ime.tools.KeyboardToolsEditor
import app.songyan.ime.ime.tools.KeyboardToolsGateway
import app.songyan.ime.ime.tools.KeyboardToolsPanel
import app.songyan.ime.ime.tools.KeyboardToolsRuntime
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.rime.OpenCCConversion
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The keyboard tools against the real engine and the real OpenCC data.
 *
 * Every case drives the shipped panel through the keyboard's own accessibility items and the shipped
 * editor path: a tool only sees the text it was shown, writes only where that text still is, and a
 * private field offers neither the entry nor a pinned tool. The script conversion runs on the app's
 * own OpenCC instance over the bundled data, never on a stub.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardToolsDeviceTest {
    @Test
    fun aSelectedWordIsReplacedByTheToolResult() =
        withToolsHost { host, _, _ ->
            host.start()
            host.enableTraditional()
            host.setText("软件", 0, 2)
            host.openTools()
            host.runTool(expected = "軟體")
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("軟體")
        }

    @Test
    fun withoutSelectionTheParagraphBeforeTheCursorIsUsed() =
        withToolsHost { host, _, _ ->
            host.start()
            host.enableTraditional()
            // No selection: the tool is handed the paragraph before the caret.
            host.setText("你好世界软件")
            host.openTools()
            assertTrue(
                "the paragraph was not shown as the source",
                host.toolLabels().any { it.contains("你好世界软件") },
            )
            host.runTool(expected = "你好世界軟體")
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("你好世界軟體")
        }

    @Test
    fun replaceFallsBackToInsertWhenTheTextChanged() =
        withToolsHost { host, _, _ ->
            host.start()
            host.enableTraditional()
            host.setText("软件", 0, 2)
            host.openTools()
            host.runTool(expected = "軟體")
            // The user typed while the preview was on screen: replacing would overwrite text the
            // tool never saw, so the result is inserted at the caret instead.
            host.setText("软件x")
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("软件x軟體")
        }

    @Test
    fun toolsAreHiddenInPasswordFields() =
        withToolsHost { host, _, _ ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            host.awaitIdle()
            val labels = host.toolbarLabels()
            assertFalse(
                "a password field must not offer the tools entry: " + labels,
                host.string(R.string.ime_tools_title) in labels,
            )
            assertFalse("a password field must not offer a tool", host.main2 { host.controller.toolsAvailable })
        }

    @Test
    fun aPinnedToolRunsFromTheToolbar() =
        withToolsHost { host, conversion, toolsDir ->
            // Pinned the way the settings app stores it: the metadata index beside the tool files.
            val store = ToolStore(toolsDir, BuiltinToolDefinitions.all(OpenCCToolConfig.S2TWP))
            runBlocking { store.setPinned(BuiltinToolDefinitions.SCRIPT_CONVERSION_ID, true) }
            // A pinned tool reaches the toolbar on the next keyboard show.
            host.toolGateway = host.gatewayFor(toolsDir, conversion)
            host.start()
            host.enableTraditional()
            host.setText("软件", 0, 2)
            host.await { host.toolbarLabels().any { it == host.toolName } }
            // A pinned tool is the one-tap entry: it opens the panel with the run already done.
            host.tapToolbar(host.toolName)
            host.await { host.toolLabels().any { it.contains("軟體") } }
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("軟體")
        }

    @Test
    fun backClosesTheToolPanelFirst() =
        withToolsHost { host, _, _ ->
            host.start()
            host.setText("软件")
            host.openTools()
            assertTrue("the panel did not take Back", host.backOwned)
            // Back is dispatched to the keyboard while a panel is open; it consumes the press, and
            // the keyboard itself stays on screen.
            assertTrue(host.main2 { host.controller.closePanels() })
            host.await { !host.backOwned }
            host.await { host.toolLabels().isEmpty() }
            assertTrue("Back closed the keyboard with the panel", host.labelsOf(KeyboardView::class.java).isNotEmpty())
        }

    private fun withToolsHost(block: (FeatureHost, OpenCCConversion, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val toolsDir = File(context.cacheDir, "keyboard-tools-" + System.nanoTime()).also { it.mkdirs() }
        val conversion = OpenCCConversion { File(engine.source, "opencc") }
        val options = File(engine.root, "tools-options-" + System.nanoTime()).also { it.mkdirs() }
        val backend = runBlocking { engine.backend(optionsDir = options) }
        val host = FeatureHost(backend)
        try {
            host.toolGateway = host.gatewayFor(toolsDir, conversion)
            host.toolsEditor = toolsEditor(host)
            block(host, conversion, toolsDir)
        } finally {
            host.close()
            runBlocking { backend.shutdown() }
        }
    }

    /** The editor side of the tools, over the same connection the keyboard writes through. */
    private fun toolsEditor(host: FeatureHost): KeyboardToolsEditor =
        EditorConnectionKeyboardTools(
            editor = host.editor,
            info = { host.info },
            policy = { EditorPolicy.from(host.info) },
            clipboard = host.context.getSystemService(ClipboardManager::class.java),
        )

    private fun FeatureHost.gatewayFor(
        toolsDir: File,
        conversion: OpenCCConversion,
    ): KeyboardToolsGateway =
        KeyboardToolsRuntime(
            directory = { toolsDir.toPath() },
            openCC = ToolOpenCCConverter { text, config -> conversion.convert(text, config.wireName) },
            // The built-in examples have no pattern steps; one would fail closed without a provider.
            regex = null,
            // A tool names itself in the languages its file was authored in; the tests pin the authored one.
            interfaceLanguageTags = { listOf("zh-Hans") },
        )

    /** The script conversion's authored Simplified Chinese name, as the panel lists it. */
    private val FeatureHost.toolName: String
        get() = "繁简转换"

    /** Switches the bundled scheme to Taiwan traditional, so the tool converts towards 繁體. */
    private fun FeatureHost.enableTraditional() {
        main { controller.applyQuickSetting(QuickSettingChange.EngineOption("zh_tw", true)) }
        assertTrue(
            "the actual script switch did not reach Taiwan traditional",
            await {
                main2 {
                    controller.inputLanguage
                } == InputLanguage.ZH_HANT
            },
        )
        assertEquals(InputLanguage.ZH_HANT, main2 { controller.inputLanguage })
    }

    private fun FeatureHost.openTools() {
        tapToolbar(string(R.string.ime_tools_title))
        await { toolLabels().any { it == toolName } }
    }

    private fun FeatureHost.runTool(expected: String) {
        tap(toolName, KeyboardToolsPanel::class.java)
        await { toolLabels().any { it.contains(expected) } }
        assertTrue("the preview never showed $expected: " + toolLabels(), toolLabels().any { it.contains(expected) })
    }

    private fun FeatureHost.toolLabels(): List<String> = labelsOf(KeyboardToolsPanel::class.java)

    private fun FeatureHost.tapToolAction(label: Int) {
        tap(string(label), KeyboardToolsPanel::class.java)
        awaitIdle()
    }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine =
                DeployedEngine("keyboard-tools", english = false) { source ->
                    // Native filter capability is resolved during deployment, before a backend starts.
                    copyTree(
                        InstrumentationRegistry.getInstrumentation().targetContext.assets,
                        "rime/opencc",
                        File(source, "opencc"),
                    )
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }

        private fun copyTree(
            assets: AssetManager,
            assetPath: String,
            target: File,
        ) {
            target.mkdirs()
            for (name in assets.list(assetPath).orEmpty()) {
                val childPath = "$assetPath/$name"
                val child = File(target, name)
                if (assets.list(childPath).orEmpty().isNotEmpty()) {
                    copyTree(assets, childPath, child)
                } else {
                    assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

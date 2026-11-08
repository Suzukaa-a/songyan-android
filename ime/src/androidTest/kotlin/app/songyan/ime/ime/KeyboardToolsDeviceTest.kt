package app.songyan.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.AssetManager
import android.os.PersistableBundle
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
import org.junit.Assert.assertNull
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
    fun withoutSelectionTheWholeFieldIsUsed() =
        withToolsHost { host, _, _ ->
            host.start()
            host.enableTraditional()
            // No selection: the tool is handed the whole field.
            host.setText("你好世界软件")
            host.openTools()
            assertTrue(
                "the field was not shown as the source",
                host.toolLabels().any { it.contains("你好世界软件") },
            )
            host.runTool(expected = "你好世界軟體")
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("你好世界軟體")
        }

    /** The caret sits in the middle: text before and after it is one source and is replaced whole. */
    @Test
    fun textAfterTheCursorIsPartOfTheFieldAndIsReplacedWithIt() =
        withToolsHost { host, _, _ ->
            host.start()
            host.enableTraditional()
            host.setText("前面软件\n后面软件", 4)
            host.openTools()
            assertTrue(
                "the text after the caret was not shown as the source",
                host.toolLabels().any { it.contains("后面软件") },
            )
            host.runTool(expected = "前面軟體\n後面軟體")
            host.tapToolAction(R.string.ime_tools_replace)
            host.assertText("前面軟體\n後面軟體")
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

    /**
     * The whole field is what a bare caret works on. Once text is selected it is neither offered nor
     * replaced: deleting around the selection and then typing would also overwrite the selection.
     */
    @Test
    fun aSelectionKeepsTheFieldFromBeingReplaced() =
        withToolsHost { host, _, _ ->
            host.start()
            val tools = requireNotNull(host.toolsEditor)
            host.setText("你好世界", 2)
            val field = requireNotNull(host.main2 { tools.field() }) { "no field source for a bare caret" }
            assertEquals(2, field.caret)
            host.setText("你好世界", 2, 4)
            assertNull("the field was offered over a selection", host.main2 { tools.field() })
            assertFalse("the field was replaced over a selection", host.main2 { tools.replace(field, "X") })
            assertEquals("你好世界", host.text())
        }

    /** A field longer than a tool may take is refused whole, never cut. */
    @Test
    fun aFieldOverTheLimitIsNotOffered() =
        withToolsHost { host, _, _ ->
            host.start()
            val tools = requireNotNull(host.toolsEditor)
            val limit = EditorConnectionKeyboardTools.TOOL_TEXT_LIMIT
            host.setText("字".repeat(limit))
            assertEquals("字".repeat(limit), host.main2 { tools.field() }?.text)
            host.setText("😀" + "字".repeat(limit - 1))
            assertNull("an over-long field was cut instead of refused", host.main2 { tools.field() })
            // Text after the caret counts toward the limit too.
            host.setText("字".repeat(limit) + "字", limit / 2)
            assertNull("the text after the caret was left out of the limit", host.main2 { tools.field() })
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
    fun aClipMarkedSensitiveIsNeverShownAsTheSource() =
        withToolsHost { host, _, _ ->
            val clipboard = host.context.getSystemService(ClipboardManager::class.java)
            // The real keyboard may read the clipboard as the default input method; this host is not
            // one, so it borrows the shell's background read instead.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
            try {
                host.start()
                // An empty field leaves the clipboard as the source; an ordinary clip is offered...
                host.main2 { clipboard.setPrimaryClip(ClipData.newPlainText("", "普通剪贴")) }
                host.openTools()
                assertTrue(
                    "an ordinary clip was not offered: ${host.toolLabels()}",
                    host.await { host.toolLabels().any { it.contains("普通剪贴") } },
                )
                assertTrue(host.main2 { host.controller.closePanels() })
                host.await { host.toolLabels().isEmpty() }
                // ...but one the host flagged sensitive (a copied password) is never read.
                val sensitive =
                    ClipData.newPlainText("", "秘密口令").apply {
                        // EXTRA_IS_SENSITIVE's value, also the spelling hosts used before API 33.
                        description.extras =
                            PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    }
                host.main2 { clipboard.setPrimaryClip(sensitive) }
                host.openTools()
                host.awaitIdle()
                assertFalse(
                    "a sensitive clip reached the tool panel: ${host.toolLabels()}",
                    host.toolLabels().any { it.contains("秘密口令") },
                )
            } finally {
                automation.dropShellPermissionIdentity()
            }
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
            toolsDir.deleteRecursively()
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

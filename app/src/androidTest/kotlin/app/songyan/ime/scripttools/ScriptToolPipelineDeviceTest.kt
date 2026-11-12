package app.songyan.ime.scripttools

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorToolGatewayBridge
import app.songyan.ime.ime.tools.ToolPanelController
import app.songyan.ime.script.ScriptCatalogCache
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptHeader
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-side seam between the script host and the keyboard's tool pipeline: the catalog is built
 * from cached metadata only, so showing the entry (and opening the panel) never binds the script
 * proxy, reads a script or loads an engine, and a password field never offers the entry.
 */
@RunWith(AndroidJUnit4::class)
class ScriptToolPipelineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val limits =
        ToolLimits(maxInputUnits = 20_000, timeoutMillis = 15_000, undoWindowMillis = null, maxResults = 3)

    private fun entry(
        id: String,
        enabled: Boolean = true,
        pending: Boolean = false,
        available: Boolean = true,
        needsAudio: Boolean = false,
    ): ScriptEntry =
        ScriptEntry(
            id = id,
            name = "加书名号",
            description = null,
            enabled = enabled,
            order = 0,
            apiVersion = 1,
            connects = emptyList(),
            needsAudio = needsAudio,
            availableOnThisHost = available,
            confirmationPending = pending,
            settings = emptyList(),
            sourceOrigin = ScriptSourceOrigin.Pasted,
        )

    private class Field(
        val edit: EditText,
        val info: EditorInfo,
        val connection: InputConnection,
    )

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }

    private fun field(
        text: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
    ): Field {
        lateinit var result: Field
        instrumentation.runOnMainSync {
            val edit = EditText(context)
            edit.setInputType(inputType)
            edit.imeOptions = imeOptions
            edit.setText(text)
            edit.setSelection(text.length)
            val info = EditorInfo()
            info.inputType = inputType
            info.packageName = context.packageName
            info.fieldId = 3
            val connection = requireNotNull(edit.onCreateInputConnection(info))
            info.imeOptions = imeOptions
            info.initialSelStart = edit.selectionStart
            info.initialSelEnd = edit.selectionEnd
            result = Field(edit, info, connection)
            activeConnection = connection
            editor.startInput(info, false)
        }
        return result
    }

    @Test
    fun cachedMetadataProducesTheToolCatalogWithoutTheHost() {
        // The lambda stands in for the host's cached entries: calling tools() must not ask the
        // registry (which would start the proxy), it only maps the cached metadata. A voice
        // script is not a text tool: it runs from the microphone, so the text panel leaves it out.
        val entries =
            listOf(
                entry("one"),
                entry("disabled", enabled = false),
                entry("pending", pending = true),
                entry("unavailable", available = false),
                entry("audio", needsAudio = true),
            )
        val tools = ScriptToolCatalog { entries }.tools()
        assertEquals(listOf("script:one"), tools.map { it.id })
        assertEquals(setOf(ToolCapability.TEXT), tools.single().requiredCapabilities)
    }

    @Test
    fun aPasswordFieldHasNoToolEntry() {
        val target = field("secret", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val bridge =
            EditorToolGatewayBridge(
                editor = editor,
                catalog = ScriptToolCatalog { listOf(entry("one")) },
                toolLimits = limits,
                info = { target.info },
            )
        assertFalse("a password field must not offer tools", bridge.toolsAvailable())
        val controller =
            ToolPanelController(bridge, ScriptToolExecutor { throw AssertionError("no run expected") }, scope)
        assertFalse(controller.isAvailable())
    }

    @Test
    fun aNoLearningFieldStillOffersTheEntry() {
        val target = field("hello", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val bridge =
            EditorToolGatewayBridge(
                editor = editor,
                catalog = ScriptToolCatalog { listOf(entry("one")) },
                toolLimits = limits,
                info = { target.info },
            )
        assertTrue(bridge.toolsAvailable())
    }

    private fun installMinimalScript(): String {
        val registry = ScriptRegistryHolder.current
        val source = context.assets.open("script/examples/minimal.js").use { it.readBytes() }
        val header = runBlocking { registry.inspect(source) }
        assertTrue("the minimal example parses: " + header, header is ScriptHeader.Parsed)
        return runBlocking {
            val added = registry.add(source, ScriptSourceOrigin.Pasted)
            registry.confirm(added.id, emptyMap())
            registry.setEnabled(added.id, true)
            added.id
        }
    }

    private fun awaitPhase(
        controller: ToolPanelController,
        phase: app.songyan.ime.tools.ToolPhase,
    ): ToolPanelController.State {
        repeat(600) {
            val state = controller.state()
            if (state.phase == phase) return state
            Thread.sleep(50)
        }
        return controller.state()
    }

    private fun catalogFromSnapshot() = ScriptToolCatalog { ScriptCatalogCache(context).enabledEntries() }

    @Test
    fun anInstalledScriptRunsFromThePanelWritesBackAndUndoes() {
        val id = installMinimalScript()
        try {
            val target = field("示例文字")
            val original = target.edit.text.toString()
            val bridge =
                EditorToolGatewayBridge(
                    editor = editor,
                    catalog = catalogFromSnapshot(),
                    toolLimits = limits,
                    info = { target.info },
                )
            val executor = ScriptToolExecutor { ScriptRegistryHolder.current }
            val controller = ToolPanelController(bridge, executor, scope)
            val tool = bridge.catalog.tools().first { it.id == "script:" + id }
            controller.start(tool)
            val preview = awaitPhase(controller, app.songyan.ime.tools.ToolPhase.PREVIEWING)
            assertTrue("the script preview arrives: " + preview.failure, preview.preview != null)
            controller.replace()
            assertEquals("「示例文字」", target.edit.text.toString())
            controller.undo()
            assertEquals(original, target.edit.text.toString())
        } finally {
            runBlocking { ScriptRegistryHolder.current.delete(id) }
        }
    }

    @Test
    fun aChangedSourceIsInsertedInsteadOfOverwritten() {
        val id = installMinimalScript()
        try {
            val target = field("hello")
            val bridge =
                EditorToolGatewayBridge(
                    editor = editor,
                    catalog = catalogFromSnapshot(),
                    toolLimits = limits,
                    info = { target.info },
                )
            val executor = ScriptToolExecutor { ScriptRegistryHolder.current }
            val controller = ToolPanelController(bridge, executor, scope)
            controller.start(bridge.catalog.tools().first { it.id == "script:" + id })
            awaitPhase(controller, app.songyan.ime.tools.ToolPhase.PREVIEWING)
            // The user keeps typing while the preview is up: the result must be inserted, not written
            // over that new text.
            instrumentation.runOnMainSync {
                editor.update("", listOf("!"))
                target.info.initialSelStart = target.edit.text.length
                target.info.initialSelEnd = target.edit.text.length
                editor.startInput(target.info, false)
            }
            controller.replace()
            assertTrue("the user's text survives: " + target.edit.text, target.edit.text.startsWith("hello!"))
            assertTrue("the script result landed: " + target.edit.text, target.edit.text.contains("「hello」"))
        } finally {
            runBlocking { ScriptRegistryHolder.current.delete(id) }
        }
    }

    @Test
    fun anOfflineScriptRunTouchesNoNetworkInTheImeProcess() {
        val id = installMinimalScript()
        try {
            val target = field("hello")
            val violations = mutableListOf<String>()
            val bridge =
                EditorToolGatewayBridge(
                    editor = editor,
                    catalog = catalogFromSnapshot(),
                    toolLimits = limits,
                    info = { target.info },
                )
            val controller = ToolPanelController(bridge, ScriptToolExecutor { ScriptRegistryHolder.current }, scope)
            val tool = bridge.catalog.tools().first { it.id == "script:" + id }
            instrumentation.runOnMainSync {
                val previous = android.os.StrictMode.getThreadPolicy()
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy
                        .Builder()
                        .detectNetwork()
                        .penaltyListener(context.mainExecutor) { violation ->
                            violations.add(violation.stackTrace.take(3).joinToString("\n"))
                        }.build(),
                )
                try {
                    controller.start(tool)
                } finally {
                    android.os.StrictMode.setThreadPolicy(previous)
                }
            }
            awaitPhase(controller, app.songyan.ime.tools.ToolPhase.PREVIEWING)
            assertTrue("no network from the IME process: " + violations, violations.isEmpty())
        } finally {
            runBlocking { ScriptRegistryHolder.current.delete(id) }
        }
    }

    @Test
    fun openingTheCatalogDoesNotTouchTheNetwork() {
        val target = field("hello")
        val violations = mutableListOf<String>()
        instrumentation.runOnMainSync {
            val previous = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy
                    .Builder()
                    .detectNetwork()
                    .penaltyListener(context.mainExecutor) { violation ->
                        violations.add(violation.stackTrace.take(3).joinToString("\n"))
                    }.build(),
            )
            try {
                val catalog = ScriptToolCatalog { listOf(entry("one")) }
                val bridge =
                    EditorToolGatewayBridge(
                        editor = editor,
                        catalog = catalog,
                        toolLimits = limits,
                        info = { target.info },
                    )
                assertTrue(bridge.toolsAvailable())
                val controller =
                    ToolPanelController(bridge, ScriptToolExecutor { throw AssertionError("no run expected") }, scope)
                controller.open()
                catalog.tools()
            } finally {
                android.os.StrictMode.setThreadPolicy(previous)
            }
        }
        assertTrue("no network on the IME process: " + violations, violations.isEmpty())
    }
}

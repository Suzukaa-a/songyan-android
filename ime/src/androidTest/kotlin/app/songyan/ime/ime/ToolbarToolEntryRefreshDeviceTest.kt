package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.ime.tools.ToolExecutor
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The toolbar is built while the keyboard view is created, before the tool catalog's
 * preloaded snapshot has been read (its sources are files, so the read happens on IO). The
 * service calls [KeyboardController.onToolCatalogRefreshed] when that read finishes; this
 * covers the controller half: the tool entry appears without any keystroke and without
 * rebuilding the controller.
 */
@RunWith(AndroidJUnit4::class)
class ToolbarToolEntryRefreshDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val limits =
        ToolLimits(maxInputUnits = 64, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 1)
    private val tool = ToolDescriptor("upper", "Upper", setOf(ToolCapability.TEXT))

    @Volatile
    private var items: List<ToolDescriptor> = emptyList()

    private val catalog = ToolCatalog { items }
    private val executor = ToolExecutor { _, _ -> emptyList() }

    private lateinit var controller: KeyboardController

    /** The bridge only offers tools to a real editor; a bare null connection hides the entry. */
    private var connection: InputConnection? = null

    private fun actions(): List<ToolbarView.Action> = controller.toolbar.actionsForTest()

    private fun start() {
        instrumentation.runOnMainSync {
            val edit =
                EditText(context).apply {
                    setText("hello")
                    setSelection(text.length)
                }
            connection = edit.onCreateInputConnection(EditorInfo())
            controller =
                KeyboardController(
                    context,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { _, _ -> true },
                        preedit = { _, _ -> },
                        delete = {},
                        moveCursor = {},
                        action = {},
                        error = { throw AssertionError(it) },
                    ),
                    toolBridge =
                        EditorToolGatewayBridge(
                            editor = EditorConnection { connection },
                            catalog = catalog,
                            toolLimits = limits,
                            info = { EditorInfo() },
                        ),
                    toolExecutor = executor,
                )
            controller.configure(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
            controller.render()
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun theToolEntryAppearsWhenThePreloadedSnapshotArrives() {
        start()
        // The keyboard view was built while the snapshot was still empty: no tool entry yet.
        instrumentation.runOnMainSync { assertFalse(ToolbarView.Action.TOOLS in actions()) }

        items = listOf(tool)
        // What the service does when the IO read completes.
        instrumentation.runOnMainSync { controller.onToolCatalogRefreshed() }
        instrumentation.runOnMainSync { assertTrue(ToolbarView.Action.TOOLS in actions()) }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { scope.cancel() }
    }
}

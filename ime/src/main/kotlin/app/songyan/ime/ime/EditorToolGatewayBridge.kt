package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.ime.tools.ToolEditorBridge
import app.songyan.ime.ime.tools.ToolUndoResult
import app.songyan.ime.ime.tools.ToolWriteResult
import app.songyan.ime.tools.CapabilityTable
import app.songyan.ime.tools.EditorSnapshot
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolRun
import app.songyan.ime.tools.ToolSession

/**
 * The production-shaped adapter: one [EditorToolGateway] per keyboard tool session, rebuilt by
 * [closeSession] so a field change cannot reuse an old document id. The host injects the catalog
 * (a lightweight list) and the current [EditorInfo]; nothing here reads scripts or starts processes.
 */
class EditorToolGatewayBridge(
    private val editor: EditorConnection,
    override val catalog: ToolCatalog,
    private val toolLimits: ToolLimits,
    private val info: () -> EditorInfo?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolEditorBridge {
    override val limits: ToolLimits get() = toolLimits

    private var session = newSession()
    private var gateway = newGateway()

    override fun toolsAvailable(): Boolean {
        val current = info() ?: return false
        if (editor.keyEventEditor || editor.connection() == null) return false
        return EditorPolicy.from(current).fieldKind != FieldKind.PASSWORD
    }

    override fun snapshot(): EditorSnapshot? = gateway.snapshot(info())

    override fun beginRun(tool: ToolDescriptor): ToolRun? =
        gateway.beginRun(tool, CapabilityTable(setOf(ToolCapability.TEXT)), info())

    override fun replace(run: ToolRun): ToolWriteResult = gateway.replace(run, info())

    override fun insert(run: ToolRun): ToolWriteResult = gateway.insert(run, info())

    override fun undo(run: ToolRun): ToolUndoResult = gateway.undo(run, info())

    override fun closeSession() {
        session = newSession()
        gateway = newGateway()
    }

    private fun newSession() = ToolSession("tools-" + System.nanoTime())

    private fun newGateway() = EditorToolGateway(editor, toolLimits, session, clock).also { it.startInput() }
}

package app.songyan.ime.ime.tools

import app.songyan.ime.tools.EditorSnapshot
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolRun
import app.songyan.ime.tools.WriteBlockReason

/**
 * The tool names the host can offer, prepared as a lightweight state.
 *
 * Every extension kind feeds this one list: a user script is an addon implementation of the same
 * contract, an externally installed APK is another, and neither gets its own tool UI, catalog or run
 * state. The list is a prepared capability summary, so building or showing it must never read a
 * script file, load a runtime or start a process; that work belongs to the addon's own loader before
 * this state exists.
 */
fun interface ToolCatalog {
    fun tools(): List<ToolDescriptor>

    companion object {
        val EMPTY = ToolCatalog { emptyList() }
    }
}

/**
 * Runs one tool over the acquired text; called only after the user starts that tool.
 *
 * One implementation dispatches every addon kind (script addon, APK addon, built-in) through this
 * call, so the panel has a single run flow. The addon's own format, sandbox and installation are its
 * loader's business; this seam only promises "given a [ToolDescriptor] and its input, return results".
 */
fun interface ToolExecutor {
    suspend fun run(
        tool: ToolDescriptor,
        input: String,
    ): List<String>
}

/** How a requested write ended; a refusal carries the model's own reason for the panel. */
sealed interface ToolWriteResult {
    data object Applied : ToolWriteResult

    data class Blocked(
        val reason: WriteBlockReason,
    ) : ToolWriteResult

    data object Unavailable : ToolWriteResult

    /** The model issued the request but the editor did not take it; the run is back at its preview. */
    data object NotApplied : ToolWriteResult
}

/** How a requested undo ended. */
sealed interface ToolUndoResult {
    data object Applied : ToolUndoResult

    data class Blocked(
        val reason: WriteBlockReason,
    ) : ToolUndoResult

    data object Expired : ToolUndoResult

    data object Unavailable : ToolUndoResult

    data object NotApplied : ToolUndoResult
}

/**
 * The keyboard's seam to the document gateway: which tools exist, what the current editor allows,
 * and the safe read/write/undo operations. The implementation owns the [ToolRun] session and the
 * [EditorSnapshot]; the panel only drives it.
 */
interface ToolEditorBridge {
    val catalog: ToolCatalog

    val limits: ToolLimits

    /** Cheap entry check: the catalog has tools and the focused editor may show them (no text read). */
    fun toolsAvailable(): Boolean

    /** The editor as a tool may see it; null when there is no editor or it may not be read. */
    fun snapshot(): EditorSnapshot?

    /** Starts a run for [tool]; null when the current editor has no usable text. */
    fun beginRun(tool: ToolDescriptor): ToolRun?

    fun replace(run: ToolRun): ToolWriteResult

    fun insert(run: ToolRun): ToolWriteResult

    fun undo(run: ToolRun): ToolUndoResult

    /** The editor session ended or changed: drop the tool session state. */
    fun closeSession()
}

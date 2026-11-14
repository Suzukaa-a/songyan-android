package app.songyan.ime.ime.tools

import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.ToolPreview
import app.songyan.ime.tools.ToolRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives one tool run through the shared model. The run itself (phase, preview, undo offer, two-phase
 * write) stays in [ToolRun]; this controller only starts tasks, answers them and reflects the model's
 * state to the panel. A new editor, a closed panel or Cancel ends the run and cancels its task.
 */
class ToolPanelController(
    private val bridge: ToolEditorBridge,
    private val executor: ToolExecutor,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val available: Boolean,
        val tools: List<ToolDescriptor>,
        val tool: ToolDescriptor?,
        val phase: ToolPhase,
        val preview: ToolPreview?,
        val failure: ToolFailure?,
        val canUndo: Boolean,
        val write: ToolWriteResult? = null,
        val undo: ToolUndoResult? = null,
        /** How long the run in progress has been going, so a slow model reads as waiting. */
        val elapsedSeconds: Long = 0,
    )

    var onChanged: (State) -> Unit = {}

    private var run: ToolRun? = null
    private var selected: ToolDescriptor? = null
    private var task: Job? = null
    private var timeout: Job? = null
    private var ticker: Job? = null
    private var writeOutcome: ToolWriteResult? = null
    private var undoOutcome: ToolUndoResult? = null

    fun isAvailable(): Boolean = bridge.catalog.tools().isNotEmpty() && bridge.toolsAvailable()

    fun state(): State {
        val current = run
        return State(
            available = isAvailable(),
            tools = bridge.catalog.tools(),
            tool = selected,
            phase = current?.phase ?: ToolPhase.IDLE,
            preview = current?.preview,
            failure = current?.failure,
            canUndo = current?.canUndo(clock()) == true,
            write = writeOutcome,
            undo = undoOutcome,
            elapsedSeconds =
                if (current?.phase == ToolPhase.RUNNING) current.elapsedMillis(clock()) / 1000 else 0,
        )
    }

    fun open() = publish()

    /** Starts [tool] on the current editor; its result arrives asynchronously. */
    fun start(tool: ToolDescriptor) {
        if (!bridge.toolsAvailable()) return
        end()
        clearOutcomes()
        val started = bridge.beginRun(tool) ?: return publish()
        run = started
        selected = tool
        publish()
        val input = started.source?.text ?: return
        task =
            scope.launch {
                val results =
                    try {
                        executor.run(tool, input)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        started.fail(started.runId, error.message)
                        publish()
                        return@launch
                    }
                started.complete(started.runId, results)
                publish()
            }
        ticker =
            scope.launch {
                while (started.phase == ToolPhase.RUNNING) {
                    delay(1_000)
                    if (started.phase == ToolPhase.RUNNING) publish()
                }
            }
        timeout =
            scope.launch {
                delay(started.timeoutMillis + TIMEOUT_SLACK_MS)
                // The model names the task to cancel on timeout; a late tick does nothing.
                if (started.tick(clock()) != null) task?.cancel()
                publish()
            }
    }

    fun selectResult(index: Int) {
        run?.selectResult(index)
        clearOutcomes()
        publish()
    }

    fun copy(): String? = run?.copy()

    fun replace() {
        val current = run ?: return
        writeOutcome = bridge.replace(current)
        undoOutcome = null
        publish()
    }

    fun insert() {
        val current = run ?: return
        writeOutcome = bridge.insert(current)
        undoOutcome = null
        publish()
    }

    fun undo() {
        val current = run ?: return
        undoOutcome = bridge.undo(current)
        writeOutcome = null
        publish()
    }

    /** Cancel from the panel: the run stops and its task is cancelled. */
    fun cancel() {
        run?.cancel()
        end()
        publish()
    }

    /** The editor changed or the keyboard is going away: nothing from the old field may land. */
    fun onEditorChanged() {
        run?.cancel()
        end()
        bridge.closeSession()
        publish()
    }

    fun close() {
        run?.cancel()
        end()
        bridge.closeSession()
        publish()
    }

    private fun clearOutcomes() {
        writeOutcome = null
        undoOutcome = null
    }

    private fun end() {
        clearOutcomes()
        task?.cancel()
        task = null
        timeout?.cancel()
        timeout = null
        ticker?.cancel()
        ticker = null
        run = null
        selected = null
    }

    private fun publish() {
        onChanged(state())
    }

    private companion object {
        /** The model decides the deadline; the panel only stops waiting a moment after it. */
        const val TIMEOUT_SLACK_MS = 1_000L
    }
}

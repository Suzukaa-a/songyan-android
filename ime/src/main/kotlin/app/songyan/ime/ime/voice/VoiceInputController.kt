package app.songyan.ime.ime.voice

import android.content.Context
import app.songyan.ime.ime.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A take this long after the recorder's own cap is collected: the recorder already stopped it. */
private const val AUTO_STOP_SLACK_MS = 500L

/** Belt for a host that never answers the permission request; the host owns its own shorter wait. */
private const val PERMISSION_WAIT_MS = 45_000L

/** The stored voice choice names an extension instead of a script when it has this prefix. */
private const val ADDON_VOICE_PREFIX = "addon:"

/**
 * The keyboard's own voice input: one press starts a take, the next press hands it to the selected
 * `@input audio` script, and what the script returns is committed at the caret with an offer to undo.
 *
 * The microphone, the permission and the script host live in the app process ([VoiceInputHost]); the
 * keyboard only records the state the toolbar draws and performs the verified insert and undo on
 * the editor. Nothing here runs before the user presses the microphone: the script's catalog is
 * read from the cache the proxy already wrote.
 *
 * A take ends when the user presses again, when the recorder reaches the host's cap (this controller
 * then transcribes it by itself, so the toolbar never stays "recording"), or when the editor/window
 * goes away (the take is dropped). A microphone permission answer arrives through the host's
 * bounded wait; a refusal, a timeout or a host failure is a message, never a stuck state.
 */
class VoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: VoiceInputHost,
    /** False in a password field or without an editor: the toolbar then shows no microphone. */
    private val canWrite: () -> Boolean,
    /** A live composition is never written into; the press is answered with a hint instead. */
    private val isComposing: () -> Boolean,
    /** Commits [text] at the caret; false when the editor took nothing. */
    private val insert: (String) -> Boolean,
    /** Replaces the text right before the caret; false when it is not exactly there any more. */
    private val replaceBeforeCursor: (expected: String, replacement: String) -> Boolean,
    /** Something the toolbar shows changed (availability, undo offer). */
    private val onChanged: () -> Unit,
    /** A one-off line for the candidate row. */
    private val onMessage: (String) -> Unit,
) {
    /** What the microphone button stands for right now. */
    enum class State {
        IDLE,
        RECORDING,
        TRANSCRIBING,

        /** The script's text is committed; [undoAvailable] offers to take it back. */
        UNDOABLE,
    }

    var state: State = State.IDLE
        private set

    private var enabled = false
    private var scriptId: String? = null

    /** True while the running state belongs to an extension flow rather than to a local take. */
    private var addonTake = false

    /** The text this controller committed, while it may still be taken back. */
    private var undoText: String? = null

    private var work: Job? = null
    private var autoStop: Job? = null

    /** Applies the stored settings; both values arrive from the same preference source. */
    fun configure(
        enabled: Boolean,
        scriptId: String?,
    ) {
        if (this.enabled == enabled && this.scriptId == scriptId) return
        this.enabled = enabled
        if (this.scriptId != scriptId) {
            this.scriptId = scriptId
            // Another script (or none) means the old undo offer no longer belongs to anything.
            undoText = null
            if (state == State.UNDOABLE) state = State.IDLE
            // A running extension flow belongs to the choice that started it; drop it before the
            // toolbar starts drawing a different one.
            if (addonTake) dropTake()
        }
        onChanged()
    }

    /** The toolbar offers the microphone: voice on, a runnable script or extension picked, and an editor. */
    fun available(): Boolean = enabled && (selectedScript() != null || selectedAddon() != null) && canWrite()

    /** The toolbar offers undo instead of the microphone after a committed result. */
    fun undoAvailable(): Boolean = state == State.UNDOABLE && undoText != null && canWrite()

    fun isRecording(): Boolean = state == State.RECORDING

    /** The microphone button: start a take, or end the running one and run the script. */
    fun onPressed() {
        when (state) {
            State.RECORDING -> if (addonTake) stopAddonTake() else stopAndRun()
            State.TRANSCRIBING -> Unit
            State.IDLE, State.UNDOABLE -> {
                if (!available()) {
                    onMessage(context.getString(R.string.ime_voice_no_script))
                    return
                }
                if (isComposing()) {
                    onMessage(context.getString(R.string.ime_voice_finish_input))
                    return
                }
                val addon = selectedAddon()
                if (addon != null) {
                    // The extension records and recognizes itself: no microphone take here, and the
                    // permission question belongs to the extension.
                    startAddonTake(addon.id)
                    return
                }
                if (!host.hasMicrophonePermission()) {
                    requestPermissionThenStart()
                    return
                }
                startTake()
            }
        }
    }

    /** The undo button: takes back exactly the text this controller committed, when it is still there. */
    fun undo() {
        val text = undoText ?: return
        if (!canWrite() || isComposing()) {
            onMessage(context.getString(R.string.ime_voice_undo_unavailable))
            return
        }
        val restored =
            try {
                replaceBeforeCursor(text, "")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
        if (restored) {
            undoText = null
            state = State.IDLE
            onMessage(context.getString(R.string.ime_voice_undone))
            onChanged()
        } else {
            // The user typed into or deleted around the result: removing it now would take their text too.
            onMessage(context.getString(R.string.ime_voice_undo_unavailable))
        }
    }

    /** The editor changed: nothing of the old field may be written or taken back any more. */
    fun onEditorChanged() {
        dropTake()
        undoText = null
        if (state != State.IDLE) state = State.IDLE
        onChanged()
    }

    /** The keyboard window is going away: stop the take and drop the offer. */
    fun onWindowHidden() = onEditorChanged()

    /** The keyboard view is being destroyed. */
    fun dispose() {
        dropTake()
        work?.cancel()
        work = null
    }

    private fun selectedScript(): VoiceScript? =
        scriptId
            ?.takeUnless {
                it.startsWith(ADDON_VOICE_PREFIX)
            }?.let(host::script)

    private fun selectedAddon(): AddonVoiceProvider? =
        scriptId?.takeIf { it.startsWith(ADDON_VOICE_PREFIX) }?.let(host::addonProvider)

    private val addonListener =
        object : AddonVoiceListener {
            override fun onText(
                text: String,
                isFinal: Boolean,
            ) {
                if (!isFinal) return
                if (!addonTake || (state != State.RECORDING && state != State.TRANSCRIBING)) return
                commit(text)
            }

            override fun onFailed(problem: VoiceInputProblem) {
                if (!addonTake || (state != State.RECORDING && state != State.TRANSCRIBING)) return
                addonTake = false
                finishWith(problemMessage(problem))
            }
        }

    /** Starts the extension's own flow; the toolbar shows the same recording state as a local take. */
    private fun startAddonTake(providerId: String) {
        undoText = null
        if (state == State.UNDOABLE) state = State.IDLE
        val problem =
            try {
                host.startAddonVoice(providerId, addonListener)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                VoiceInputProblem.SCRIPT_FAILED
            }
        if (problem != null) {
            finishWith(problemMessage(problem))
            return
        }
        addonTake = true
        state = State.RECORDING
        onMessage(context.getString(R.string.ime_voice_recording))
        onChanged()
    }

    private fun stopAddonTake() {
        state = State.TRANSCRIBING
        onMessage(context.getString(R.string.ime_voice_transcribing))
        onChanged()
        runCatching { host.stopAddonVoice() }
    }

    /**
     * Asks the host for the microphone and starts the take it was waiting for. The host owns the
     * wait (it bounds it, so a system that silently refuses the activity cannot leave this stuck);
     * the outer timeout is only a belt for a host that never answers.
     */
    private fun requestPermissionThenStart() {
        if (work?.isActive == true) return
        work =
            scope.launch {
                val granted =
                    try {
                        withTimeoutOrNull(PERMISSION_WAIT_MS) { host.awaitMicrophonePermission() } ?: false
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        false
                    }
                // The editor may have changed while the dialog was up: then nothing starts.
                if (!canWrite()) return@launch
                if (state == State.RECORDING || state == State.TRANSCRIBING) return@launch
                if (granted) startTake() else onMessage(context.getString(R.string.ime_voice_permission_needed))
            }
    }

    private fun startTake() {
        undoText = null
        if (state == State.UNDOABLE) state = State.IDLE
        val problem =
            try {
                host.startRecording()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                VoiceInputProblem.RECORDING_FAILED
            }
        if (problem != null) {
            finishWith(problemMessage(problem))
            return
        }
        state = State.RECORDING
        onMessage(context.getString(R.string.ime_voice_recording))
        scheduleAutoStop()
        onChanged()
    }

    /**
     * The recorder stops a take by itself at the host's cap. This collects it on the same path as a
     * second press, so the toolbar never stays on "recording" and the text still lands.
     */
    private fun scheduleAutoStop() {
        autoStop?.cancel()
        autoStop =
            scope.launch {
                delay(host.maxTakeMillis + AUTO_STOP_SLACK_MS)
                if (state == State.RECORDING) stopAndRun()
            }
    }

    private fun stopAndRun() {
        val script =
            selectedScript() ?: run {
                finishWith(context.getString(R.string.ime_voice_no_script))
                runCatching { host.cancelRecording() }
                return
            }
        autoStop?.cancel()
        autoStop = null
        state = State.TRANSCRIBING
        onMessage(context.getString(R.string.ime_voice_transcribing))
        onChanged()
        work =
            scope.launch {
                try {
                    val recording = withContext(Dispatchers.IO) { host.stopRecording() }
                    if (recording is VoiceRecordingResult.Failed) {
                        finishWith(problemMessage(recording.problem))
                        return@launch
                    }
                    val take = recording as VoiceRecordingResult.Recorded
                    val result = host.runAudio(script.id, take.wav)
                    if (result is VoiceScriptResult.Failed) {
                        finishWith(scriptFailureMessage(script.name, result))
                        return@launch
                    }
                    commit((result as VoiceScriptResult.Text).text)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    // The registry may be missing or its process may have died; the user still gets copy.
                    finishWith(context.getString(R.string.ime_voice_failed))
                }
            }
    }

    /** Commits the script's text and keeps the undo offer; nothing lands in the editor unverified. */
    private fun commit(text: String) {
        addonTake = false
        val value = text.trim()
        if (value.isEmpty()) {
            finishWith(context.getString(R.string.ime_voice_nothing_heard))
            return
        }
        if (!canWrite() || isComposing()) {
            finishWith(context.getString(R.string.ime_voice_finish_input))
            return
        }
        val inserted =
            try {
                insert(value)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
        if (!inserted) {
            finishWith(context.getString(R.string.ime_voice_failed))
            return
        }
        undoText = value
        state = State.UNDOABLE
        onChanged()
    }

    private fun finishWith(message: String) {
        addonTake = false
        state = State.IDLE
        onMessage(message)
        onChanged()
    }

    /** Ends whatever is running and throws the take away: an editor/window change cancels it all. */
    private fun dropTake() {
        work?.cancel()
        work = null
        autoStop?.cancel()
        autoStop = null
        if (state == State.RECORDING || state == State.TRANSCRIBING) {
            runCatching { if (addonTake) host.cancelAddonVoice() else host.cancelRecording() }
        }
        addonTake = false
    }

    /** A voice script's failure in the words the tool panel uses: the script's own message under
     *  its name, fixed wording for the reasons the runtime classifies, the generic line otherwise. */
    private fun scriptFailureMessage(
        name: String,
        failure: VoiceScriptResult.Failed,
    ): String {
        val reason =
            when (failure.code) {
                "script" ->
                    failure.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { words ->
                        if (failure.line >
                            0
                        ) {
                            context.getString(R.string.ime_tool_error_at_line, words, failure.line)
                        } else {
                            words
                        }
                    }
                "timeout" -> context.getString(R.string.ime_tool_error_timeout)
                "outOfMemory" -> context.getString(R.string.ime_tool_error_out_of_memory)
                "addressNotAllowed" -> context.getString(R.string.ime_tool_error_address)
                "networkFailure" -> context.getString(R.string.ime_tool_error_network)
                else -> null
            } ?: return problemMessage(failure.problem)
        return context.getString(R.string.ime_tool_panel_error_with_tool, name, reason)
    }

    private fun problemMessage(problem: VoiceInputProblem): String =
        context.getString(
            when (problem) {
                VoiceInputProblem.NO_PERMISSION -> R.string.ime_voice_permission_needed
                VoiceInputProblem.NO_MICROPHONE -> R.string.ime_voice_no_microphone
                VoiceInputProblem.DEVICE_BUSY -> R.string.ime_voice_device_busy
                VoiceInputProblem.RECORDING_FAILED -> R.string.ime_voice_failed
                VoiceInputProblem.SCRIPT_FAILED -> R.string.ime_voice_script_failed
            },
        )
}

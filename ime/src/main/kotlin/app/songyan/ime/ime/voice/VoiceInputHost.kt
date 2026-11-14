package app.songyan.ime.ime.voice

/** A stored audio script as the toolbar needs it: an id to run and a name for the user. */
data class VoiceScript(
    val id: String,
    val name: String,
)

/** An installed extension that records and recognizes speech in its own process; [id] is "addon:<package>". */
data class AddonVoiceProvider(
    val id: String,
    val name: String,
)

/** What an extension voice flow reports; the host delivers these on the main thread. */
interface AddonVoiceListener {
    /** One transcription line; only the final one is committed. */
    fun onText(
        text: String,
        isFinal: Boolean,
    )

    /** The flow could not run or died; the controller shows the reason and stays usable. */
    fun onFailed(problem: VoiceInputProblem)
}

/** Why voice input could not run, in the keyboard's own words (the UI copy comes from resources). */
enum class VoiceInputProblem {
    /** RECORD_AUDIO is not granted. */
    NO_PERMISSION,

    /** The device reports no microphone at all. */
    NO_MICROPHONE,

    /** The microphone exists but the system would not hand it to this take. */
    DEVICE_BUSY,

    /** The take or its file failed after it started. */
    RECORDING_FAILED,

    /** The script host refused the run (a bad script, a timeout, the host missing). */
    SCRIPT_FAILED,
}

/** One finished take: the WAV bytes the script receives, and how long the take ran. */
sealed interface VoiceRecordingResult {
    data class Recorded(
        val wav: ByteArray,
        val durationMs: Long,
    ) : VoiceRecordingResult

    data class Failed(
        val problem: VoiceInputProblem,
    ) : VoiceRecordingResult
}

/** What the audio script returned for one take. */
sealed interface VoiceScriptResult {
    /** The text to commit. Empty when the script returned no usable output. */
    data class Text(
        val text: String,
    ) : VoiceScriptResult

    data class Failed(
        val problem: VoiceInputProblem,
        /** The script's own message when [code] is "script"; otherwise a diagnostic, never shown. */
        val detail: String?,
        /** The script runtime's error name (timeout, addressNotAllowed…), when it gave one. */
        val code: String? = null,
        val line: Int = 0,
    ) : VoiceScriptResult
}

/**
 * What the app process does for the keyboard's voice input: open the microphone, ask for
 * RECORD_AUDIO, and run the selected `@input audio` script. The service implements it; nothing here
 * links the keyboard to the script module, and no call starts a process or reads a script before the
 * user presses the microphone.
 */
interface VoiceInputHost {
    /** The installed, enabled, confirmed audio script with [id]; null when it cannot run here. */
    fun script(id: String): VoiceScript?

    fun hasMicrophonePermission(): Boolean

    /**
     * Shows the system request and waits for its answer; false on a refusal or when no answer
     * arrives in reasonable time (the system can silently refuse to show an activity started from
     * the background). The answer comes from the transparent activity, never from a keyboard call.
     */
    suspend fun awaitMicrophonePermission(): Boolean = false

    /** Opens the microphone; null means the take started, otherwise why it did not. */
    fun startRecording(): VoiceInputProblem?

    /** Ends the take and returns its WAV bytes. Runs off the main thread. */
    suspend fun stopRecording(): VoiceRecordingResult

    /** Ends a running take and throws its file away (keyboard hidden, another input started). */
    fun cancelRecording()

    /** The extension voice provider with [id] ("addon:<package>"); null when it is not enabled here. */
    fun addonProvider(id: String): AddonVoiceProvider? = null

    /**
     * Starts the extension's own voice flow: it records and recognizes itself, Songyan only shows
     * the keyboard state and commits the final text. Null means it started; otherwise why it did not.
     */
    fun startAddonVoice(
        providerId: String,
        listener: AddonVoiceListener,
    ): VoiceInputProblem? = null

    /** Asks the running extension flow to finish; the last text line carries the result. */
    fun stopAddonVoice() = Unit

    /** Drops the running extension flow (editor or window changed). */
    fun cancelAddonVoice() = Unit

    /** Runs the audio script over one recorded take. */
    suspend fun runAudio(
        scriptId: String,
        wav: ByteArray,
    ): VoiceScriptResult

    /** How long one take may run; the recorder ends it and the controller then transcribes it. */
    val maxTakeMillis: Long get() = DEFAULT_MAX_TAKE_MILLIS

    companion object {
        /** Provisional, like the recorder's cap: one utterance inside a minute. */
        const val DEFAULT_MAX_TAKE_MILLIS = 60_000L

        /** The host that offers nothing: preview builds and tests that do not wire voice input. */
        val NONE: VoiceInputHost =
            object : VoiceInputHost {
                override fun script(id: String): VoiceScript? = null

                override fun hasMicrophonePermission(): Boolean = false

                override fun startRecording(): VoiceInputProblem = VoiceInputProblem.NO_MICROPHONE

                override suspend fun stopRecording(): VoiceRecordingResult =
                    VoiceRecordingResult.Failed(VoiceInputProblem.RECORDING_FAILED)

                override fun cancelRecording() = Unit

                override suspend fun runAudio(
                    scriptId: String,
                    wav: ByteArray,
                ): VoiceScriptResult = VoiceScriptResult.Failed(VoiceInputProblem.SCRIPT_FAILED, null)
            }
    }
}

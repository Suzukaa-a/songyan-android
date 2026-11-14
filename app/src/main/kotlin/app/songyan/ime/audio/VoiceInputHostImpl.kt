package app.songyan.ime.audio

import android.content.Context
import app.songyan.ime.addons.AddonRegistryHolder
import app.songyan.ime.addons.AddonVoiceSession
import app.songyan.ime.ime.voice.AddonVoiceListener
import app.songyan.ime.ime.voice.AddonVoiceProvider
import app.songyan.ime.ime.voice.VoiceInputHost
import app.songyan.ime.ime.voice.VoiceInputProblem
import app.songyan.ime.ime.voice.VoiceRecordingResult
import app.songyan.ime.ime.voice.VoiceScript
import app.songyan.ime.ime.voice.VoiceScriptResult
import app.songyan.ime.script.ScriptCatalogCache
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptRunOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's side of the keyboard's voice input, living in the input method process.
 *
 * The take is recorded here ([VoiceRecorder]); while the keyboard window is visible the process
 * counts as foreground, so no foreground service is involved (P10 platform fact). RECORD_AUDIO is
 * asked for through the transparent [MicrophonePermissionActivity], and the selected `@input audio`
 * script runs through the installed script host. The catalog comes from the snapshot the proxy
 * already wrote, so offering the microphone starts no process and reads no script.
 */
class VoiceInputHostImpl(
    private val context: Context,
    private val recorder: VoiceRecorder,
    private val catalog: ScriptCatalogCache,
    private val registry: () -> ScriptRegistry = { ScriptRegistryHolder.current },
    /** How long the transparent request may wait for an answer before the keyboard says it failed. */
    private val permissionTimeoutMs: Long = PERMISSION_TIMEOUT_MS,
) : VoiceInputHost {
    /** One session object, so start/stop/cancel address the same running flow. */
    private val addonSession: AddonVoiceSession? by lazy {
        AddonRegistryHolder.current?.let { AddonVoiceSession(context, it) }
    }

    override fun script(id: String): VoiceScript? =
        // Called from the keyboard thread: never stat or read the snapshot here.
        catalog
            .cachedEntries()
            .firstOrNull { it.id == id && it.needsAudio && it.availableOnThisHost }
            ?.let { VoiceScript(it.id, it.name.ifBlank { it.id }) }

    override fun hasMicrophonePermission(): Boolean = MicrophonePermission.isGranted(context)

    override suspend fun awaitMicrophonePermission(): Boolean {
        if (MicrophonePermission.isGranted(context)) return true
        // The previous answer must not be taken for this request; the activity publishes the new one.
        MicrophonePermission.forgetPreviousOutcome()
        context.startActivity(MicrophonePermission.requestIntent(context))
        return MicrophonePermission.awaitOutcome(permissionTimeoutMs) == MicrophonePermission.Outcome.GRANTED
    }

    override fun startRecording(): VoiceInputProblem? =
        when (val start = recorder.start()) {
            VoiceRecordingStart.Started -> null
            is VoiceRecordingStart.Rejected -> start.error.toProblem()
        }

    override suspend fun stopRecording(): VoiceRecordingResult =
        withContext(Dispatchers.IO) {
            when (val outcome = recorder.stop()) {
                is VoiceRecordingOutcome.Recorded -> {
                    val file = outcome.clip.file
                    try {
                        VoiceRecordingResult.Recorded(file.readBytes(), outcome.clip.durationMs)
                    } finally {
                        file.delete()
                    }
                }
                is VoiceRecordingOutcome.Failed -> {
                    // A take that failed or timed out may still hold a file; drop it with the take.
                    recorder.cancel()
                    VoiceRecordingResult.Failed(outcome.error.toProblem())
                }
                VoiceRecordingOutcome.NotRecording -> {
                    recorder.cancel()
                    VoiceRecordingResult.Failed(VoiceInputProblem.RECORDING_FAILED)
                }
            }
        }

    override fun cancelRecording() {
        recorder.cancel()
    }

    override val maxTakeMillis: Long get() = VoiceRecordingPolicy.PROVISIONAL_MAX_DURATION_MS

    override fun addonProvider(id: String): AddonVoiceProvider? = addonSession?.provider(id)

    override fun startAddonVoice(
        providerId: String,
        listener: AddonVoiceListener,
    ): VoiceInputProblem? = addonSession?.start(providerId, listener) ?: VoiceInputProblem.SCRIPT_FAILED

    override fun stopAddonVoice() {
        addonSession?.stop()
    }

    override fun cancelAddonVoice() {
        addonSession?.cancel()
    }

    override suspend fun runAudio(
        scriptId: String,
        wav: ByteArray,
    ): VoiceScriptResult =
        when (val outcome = registry().runAudio(scriptId, wav)) {
            is ScriptRunOutcome.Success ->
                VoiceScriptResult.Text(outcome.outputs.firstOrNull { it.isNotBlank() }.orEmpty())
            is ScriptRunOutcome.Failed ->
                VoiceScriptResult.Failed(VoiceInputProblem.SCRIPT_FAILED, outcome.message, outcome.code, outcome.line)
            ScriptRunOutcome.Cancelled -> VoiceScriptResult.Failed(VoiceInputProblem.SCRIPT_FAILED, null)
        }

    private fun VoiceRecordingError.toProblem(): VoiceInputProblem =
        when (this) {
            VoiceRecordingError.NO_PERMISSION -> VoiceInputProblem.NO_PERMISSION
            VoiceRecordingError.NO_MICROPHONE -> VoiceInputProblem.NO_MICROPHONE
            VoiceRecordingError.DEVICE_BUSY -> VoiceInputProblem.DEVICE_BUSY
            VoiceRecordingError.RECORDING_FAILED -> VoiceInputProblem.RECORDING_FAILED
        }

    private companion object {
        /** The system dialog is modal; past this the keyboard stops waiting and says it failed. */
        const val PERMISSION_TIMEOUT_MS = 30_000L
    }
}

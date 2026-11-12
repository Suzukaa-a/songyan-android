package app.songyan.ime.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RECORD_AUDIO as voice input needs it.
 *
 * The keyboard checks [isGranted] before every take. A service cannot ask for a runtime permission
 * itself, so a refusal starts [requestIntent]: the transparent [MicrophonePermissionActivity] shows
 * the system request over the keyboard, finishes at once, and publishes the answer here, where the
 * input method observes it without an activity result.
 */
object MicrophonePermission {
    /** What the user answered. */
    enum class Outcome {
        /** The user allowed the microphone, or it was already allowed. */
        GRANTED,

        /** The user refused, or the system refused without asking because they refused before. */
        DENIED,
    }

    private val latest = MutableStateFlow<Outcome?>(null)

    /** The answer to the last request in this process; null until the first request finished. */
    val latestOutcome: StateFlow<Outcome?> = latest.asStateFlow()

    /** Forgets the previous answer, so [awaitOutcome] waits for the request that is about to start. */
    internal fun forgetPreviousOutcome() {
        latest.value = null
    }

    /**
     * Waits for the next answer for at most [timeoutMs]; null means the request never produced one
     * (the system refused to show the dialog, or the user left it). The caller must call
     * [forgetPreviousOutcome] before starting the request, or an old answer is returned at once.
     */
    suspend fun awaitOutcome(timeoutMs: Long): Outcome? =
        withTimeoutOrNull(timeoutMs) { latest.filterNotNull().first() }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The intent that starts [MicrophonePermissionActivity]. NEW_TASK because the input method is a
     * service; the translucent activity still shows above the keyboard's task and finishes at once.
     */
    fun requestIntent(context: Context): Intent =
        Intent(context, MicrophonePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun publish(outcome: Outcome) {
        latest.value = outcome
    }
}

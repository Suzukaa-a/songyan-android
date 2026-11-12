package app.songyan.ime.audio

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for RECORD_AUDIO over the keyboard and disappears.
 *
 * An `InputMethodService` cannot request a runtime permission itself, so it starts this activity
 * through [MicrophonePermission.requestIntent]. The activity has no content and a translucent
 * theme, so the keyboard stays visible below it; it asks the system (or only checks when the
 * permission is already there) and finishes in the same pass, publishing the answer to
 * [MicrophonePermission] for the service that launched it.
 */
class MicrophonePermissionActivity : ComponentActivity() {
    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            finishWith(if (granted) MicrophonePermission.Outcome.GRANTED else MicrophonePermission.Outcome.DENIED)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MicrophonePermission.isGranted(this)) {
            finishWith(MicrophonePermission.Outcome.GRANTED)
            return
        }
        requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** The activity result is for anyone launching this for the answer; the flow is for the service. */
    private fun finishWith(outcome: MicrophonePermission.Outcome) {
        setResult(if (outcome == MicrophonePermission.Outcome.GRANTED) RESULT_OK else RESULT_CANCELED)
        MicrophonePermission.publish(outcome)
        finish()
    }
}

package app.songyan.ime.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The input method cannot start another app for a result, so this invisible relay does it in the
 * app's own process, hands the answer back with its request token, and keeps its task out of the
 * recent apps list. It stays alive while the other app is on screen, which is what lets the answer
 * arrive; a late answer for an old token is ignored by [ProcessTextRelay].
 */
class ProcessTextTargetActivity : ComponentActivity() {
    private var delivered = false

    private val speech: Boolean get() = intent.getBooleanExtra(EXTRA_SPEECH, false)

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            deliver(intent.getStringExtra(EXTRA_TOKEN).orEmpty(), answerOf(result))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open = this
        // A recreated relay is already waiting for its own result.
        if (savedInstanceState != null) return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val text = intent.getStringExtra(EXTRA_TEXT)
        val target = intent.getStringExtra(EXTRA_TARGET)?.let(ComponentName::unflattenFromString)
        // A speech request carries no text; every other request must.
        if (token.isNullOrEmpty() || target == null || (!speech && text == null)) {
            return deliver(token.orEmpty(), null)
        }
        val outbound =
            if (speech) {
                SpeechTargets.intent(intent.getStringExtra(EXTRA_LANGUAGE))
            } else {
                ProcessTextTargets.intent(text.orEmpty())
            }.setComponent(target)
        try {
            launcher.launch(outbound)
        } catch (_: ActivityNotFoundException) {
            deliver(token, null)
        } catch (_: SecurityException) {
            // The app stopped being callable between the query and now: cancel this request only.
            deliver(token, null)
        }
    }

    /** The opened app's result is a trust boundary: a malformed answer counts as none. */
    private fun answerOf(result: ActivityResult): String? {
        if (result.resultCode != RESULT_OK) return null
        if (speech) return SpeechTargets.transcript(result.data)
        val data = result.data ?: return null
        return try {
            data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun deliver(
        token: String,
        answer: String?,
    ) {
        if (delivered) return
        delivered = true
        ProcessTextRelay.deliverResult(token, answer)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (open === this) open = null
        super.onDestroy()
    }

    companion object {
        /** The relay still waiting for its answer, if any; only this app's own activity is finished. */
        private var open: ProcessTextTargetActivity? = null

        /** Ends a relay whose request was cancelled; the other app's own task is left alone. */
        internal fun cancelOpen() {
            open?.finish()
        }

        const val EXTRA_TOKEN: String = "app.songyan.ime.tools.extra.PROCESS_TEXT_TOKEN"
        const val EXTRA_TEXT: String = "app.songyan.ime.tools.extra.PROCESS_TEXT"
        const val EXTRA_TARGET: String = "app.songyan.ime.tools.extra.PROCESS_TEXT_TARGET"
        const val EXTRA_SPEECH: String = "app.songyan.ime.tools.extra.SPEECH"
        const val EXTRA_LANGUAGE: String = "app.songyan.ime.tools.extra.SPEECH_LANGUAGE"
    }
}

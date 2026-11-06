package app.songyan.ime.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.inputmethod.EditorInfo
import java.util.UUID

/**
 * Hands the editor's text to another app through [ProcessTextTargetActivity] and keeps the answer.
 *
 * The answer usually arrives before the editor that asked has the focus again, so it is never
 * written from here: it waits for an explicit apply (the keyboard tool's own action). The editor is
 * tracked from the input method's own start/finish callbacks, so an answer is only written when the
 * editor that asked is bound again — never through a stale connection. Each request carries a fresh
 * token, so a relay that comes back after the process restarted cannot answer a newer request.
 * Main thread only.
 */
object ProcessTextRelay {
    /** What the input method provides: the editor being typed into and the clipboard. */
    interface Host {
        /** The text and editor to hand out; null when this editor may not run a tool. */
        fun snapshotForProcessText(): ProcessTextRequest?

        /**
         * Whether [request]'s text can still be written where it came from: the same editor is
         * bound, its text is unchanged, and this field may run a tool at all. False offers only
         * insert or copy.
         */
        fun canWriteProcessText(request: ProcessTextRequest): Boolean

        /** Writes [text] over the request's text; only on an explicit user action. */
        fun writeProcessText(
            request: ProcessTextRequest,
            text: String,
        ): ProcessTextOutcome

        /** Puts [text] on the clipboard and tells the user why it is there. */
        fun copyProcessText(text: String)

        /** Refreshes a visible preview when the external app returns without a keyboard show. */
        fun onProcessTextAnswer() {}
    }

    private val session = ProcessTextSession { UUID.randomUUID().toString() }
    private var host: Host? = null

    fun attach(value: Host) {
        host = value
    }

    fun detach(value: Host) {
        if (host === value) {
            host = null
            session.dismiss()
            ProcessTextTargetActivity.cancelOpen()
        }
    }

    /**
     * Starts the relay for the editor in front. The app to open is required: choosing one here would
     * decide for the user. False when the editor may not run a tool or another request is open.
     */
    fun request(
        context: Context,
        target: ComponentName,
        /** The app's own name, so the preview can say which app answered. */
        targetLabel: String? = null,
    ): Boolean {
        val host = host ?: return false
        val request = host.snapshotForProcessText() ?: return false
        val token = session.start(request, targetLabel) ?: return false
        // A relay left from a cancelled request must not stay behind while this one runs.
        ProcessTextTargetActivity.cancelOpen()
        // A relay cancelled while its other app was still open leaves that task behind with this
        // activity as its base intent; without a task of its own the next request would be folded
        // into that task instead of launching, and never answer.
        val outbound =
            Intent(context, ProcessTextTargetActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(ProcessTextTargetActivity.EXTRA_TOKEN, token)
                .putExtra(ProcessTextTargetActivity.EXTRA_TEXT, request.text)
                .putExtra(ProcessTextTargetActivity.EXTRA_TARGET, target.flattenToString())
        return try {
            context.startActivity(outbound)
            true
        } catch (_: ActivityNotFoundException) {
            session.onResult(token, null)
            false
        } catch (_: SecurityException) {
            session.onResult(token, null)
            false
        }
    }

    /** A confirmed different editor retires the answer without writing or copying it. */
    fun onEditorStarted(info: EditorInfo?) {
        session.onEditor(info)
    }

    /** The answer waiting for the user; null while the other app is still on screen. */
    fun pendingResult(): ProcessTextResult? = session.pendingResult()

    /** Whether the waiting answer can still replace its source; false offers insert or copy only. */
    fun canApplyPending(): Boolean {
        val host = host ?: return false
        val pending = session.pendingResult() ?: return false
        return host.canWriteProcessText(pending.request)
    }

    /** Puts the waiting answer on the clipboard without writing it; the user asked for that. */
    fun copyPending(): Boolean {
        val host = host ?: return false
        val pending = session.take() ?: return false
        host.copyProcessText(pending.text)
        return true
    }

    /** Writes the waiting answer where it came from, or copies it; the user asked for it. */
    fun applyPending(): ProcessTextOutcome {
        val host = host ?: return ProcessTextOutcome.DROPPED
        val pending = session.take() ?: return ProcessTextOutcome.DROPPED
        return host.writeProcessText(pending.request, pending.text)
    }

    /** Drops the waiting answer without writing anything (the tool panel was closed). */
    fun dismissPending() {
        session.dismiss()
        // The other app may never answer a request nobody waits for any more.
        ProcessTextTargetActivity.cancelOpen()
    }

    /** The relay activity reports an answer for [token]; a null answer is a cancel. */
    internal fun deliverResult(
        token: String,
        answer: String?,
    ) {
        val accepted = session.onResult(token, answer)
        if (accepted) host?.onProcessTextAnswer()
    }
}

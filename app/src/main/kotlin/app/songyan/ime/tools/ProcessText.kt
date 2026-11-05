package app.songyan.ime.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.view.inputmethod.EditorInfo

/** What a tool was handed: the selected text, or the text before the caret. */
enum class ProcessTextKind {
    /** The text before the caret, from the start of its line. */
    CURSOR,

    /** The text the user selected. */
    SELECTION,
}

/** The text a tool was handed, and the editor it came from. */
data class ProcessTextRequest(
    val text: String,
    val packageName: String?,
    val fieldId: Int,
    val kind: ProcessTextKind = ProcessTextKind.CURSOR,
)

/** A tool's answer, still waiting for the user to put it into a field. */
data class ProcessTextResult(
    val request: ProcessTextRequest,
    val text: String,
)

/** What happened to a tool's answer. */
enum class ProcessTextOutcome {
    /** Put into the field the text came from, replacing the same selection or text before the caret. */
    REPLACED,

    /** Copied to the clipboard: the field could not be confirmed, or its text had changed. */
    COPIED,

    /** Nothing to write: there was no answer, or no editor. */
    DROPPED,
}

/**
 * An editor is the one the request came from only when its app and its own field id match. An
 * editor that names no field (field id 0, as Compose and web views leave it) or a negative id
 * cannot be confirmed, so its answers are only copied.
 */
internal fun ProcessTextRequest.sameEditorAs(info: EditorInfo?): Boolean =
    info != null &&
        packageName != null &&
        packageName == info.packageName &&
        fieldId > 0 &&
        fieldId == info.fieldId

/** The ACTION_PROCESS_TEXT intent other apps answer, and the apps this app may see for it. */
object ProcessTextTargets {
    const val MIME_TYPE: String = "text/plain"

    /** What another app receives. Also what the manifest's package query is written for. */
    fun intent(text: String): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

    /**
     * The apps on this device that can actually be opened for text: exported activities whose
     * permission this app holds, since anything else would fail to start later. The manifest query
     * is what makes the other packages visible at all.
     */
    fun available(context: Context): List<ResolveInfo> =
        query(context).filter { info ->
            val activity = info.activityInfo ?: return@filter false
            if (!activity.exported) return@filter false
            val permission = activity.permission
            permission.isNullOrEmpty() ||
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun query(context: Context): List<ResolveInfo> {
        val manager = context.packageManager
        val intent = intent("")
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
    }
}

/**
 * The relay's bookkeeping: one request at a time, identified by a token that is never reused, so a
 * relay that comes back after the process restarted cannot answer a newer request. A result is kept
 * until the user applies it or dismisses it; an editor binding that is neither its own nor the
 * opened app's is what turns it into a copy.
 */
internal class ProcessTextSession(
    private val newToken: () -> String,
) {
    class Pending(
        val token: String,
        val request: ProcessTextRequest,
    ) {
        var answered = false
        var answer: String? = null
    }

    private var pending: Pending? = null

    /** Starts a request; null while another one is unanswered or its answer still waits. */
    fun start(request: ProcessTextRequest): String? {
        if (pending != null) return null
        val token = newToken()
        pending = Pending(token, request)
        return token
    }

    /** The app answered [token]. False for a late answer of an old request, or one answered twice. */
    fun onResult(
        token: String,
        answer: String?,
    ): Boolean {
        val current = pending ?: return false
        if (current.token != token || current.answered) return false
        if (answer == null) {
            pending = null
            return true
        }
        current.answer = answer
        current.answered = true
        return true
    }

    /**
     * The editor binding changed. Until the opened app answers, every binding is that app's own
     * editor or the absence between editors, so nothing is copied; an answer that then belongs to
     * no editor still waiting for it is.
     */
    fun onEditor(info: EditorInfo?): String? {
        val current = pending ?: return null
        if (!current.answered || info == null) return null
        if (current.request.sameEditorAs(info)) return null
        pending = null
        return current.answer
    }

    /** The waiting answer, or null while the other app is still on screen. */
    fun pendingResult(): ProcessTextResult? {
        val current = pending ?: return null
        if (!current.answered) return null
        return ProcessTextResult(current.request, current.answer.orEmpty())
    }

    /** Takes the waiting answer for an explicit write. */
    fun take(): ProcessTextResult? = pendingResult()?.also { pending = null }

    fun dismiss() {
        pending = null
    }
}

package app.songyan.ime.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What a tool was handed: the selected text, the whole field, the clipboard, or nothing. */
enum class ProcessTextKind {
    /** The whole input field, the text before and after a bare caret. */
    FIELD,

    /** The text the user selected. */
    SELECTION,

    /** The clipboard: it is not in the field, so an answer is only ever inserted or copied. */
    CLIPBOARD,

    /** No source text: the app produces the text (speech recognition), so an answer is only inserted. */
    NONE,
}

/** The text the user picked for a tool, before the editor it came from is attached. */
data class ProcessTextSource(
    val text: String,
    val kind: ProcessTextKind,
    /** For [ProcessTextKind.FIELD]: how much of [text] lies before the caret. */
    val caret: Int = 0,
)

/** The text a tool was handed, and the editor it came from. */
data class ProcessTextRequest(
    val text: String,
    val packageName: String?,
    val fieldId: Int,
    val kind: ProcessTextKind = ProcessTextKind.FIELD,
    /** For [ProcessTextKind.FIELD]: how much of [text] lies before the caret. */
    val caret: Int = 0,
)

/** A tool's answer, still waiting for the user to put it into a field. */
data class ProcessTextResult(
    val request: ProcessTextRequest,
    val text: String,
    /** The app that answered, as the caller named it; null when the request came in unnamed. */
    val appLabel: String? = null,
)

/** What happened to a tool's answer. */
enum class ProcessTextOutcome {
    /** Put into the field the text came from, replacing the same selection or the whole field. */
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
    fun available(context: Context): List<ResolveInfo> = launchableActivities(context, intent(""))
}

/**
 * The ACTION_RECOGNIZE_SPEECH intent speech apps answer. The request carries only what a free-form
 * dictation needs: the input language is the one thing the keyboard knows that the app cannot.
 */
object SpeechTargets {
    /** What a speech app receives; [language] is a BCP-47 tag such as zh-CN, or null to leave it to the app. */
    fun intent(language: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .also { if (language != null) it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }

    /** The speech apps that can actually be opened; same rule as [ProcessTextTargets.available]. */
    fun available(context: Context): List<ResolveInfo> = launchableActivities(context, intent(null))

    /** The first recognition of an answer, which every recognizer orders best first; null when it has none. */
    fun transcript(data: Intent?): String? =
        try {
            data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
}

private fun launchableActivities(
    context: Context,
    intent: Intent,
): List<ResolveInfo> =
    filterLaunchable(queryActivities(context, intent)) { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

/** Exported activities whose permission, if any, [holds] says this app holds. */
internal fun filterLaunchable(
    candidates: List<ResolveInfo>,
    holds: (String) -> Boolean,
): List<ResolveInfo> =
    candidates.filter { info ->
        val activity = info.activityInfo ?: return@filter false
        if (!activity.exported) return@filter false
        val permission = activity.permission
        permission.isNullOrEmpty() || holds(permission)
    }

private fun queryActivities(
    context: Context,
    intent: Intent,
): List<ResolveInfo> {
    val manager = context.packageManager
    return if (Build.VERSION.SDK_INT >= 33) {
        manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        manager.queryIntentActivities(intent, 0)
    }
}

/**
 * The relay's bookkeeping: one request at a time, identified by a token that is never reused, so a
 * relay that comes back after the process restarted cannot answer a newer request. A result is kept
 * until the user applies it or dismisses it; a different editor retires it without writing anywhere.
 */
internal class ProcessTextSession(
    private val newToken: () -> String,
) {
    class Pending(
        val token: String,
        val request: ProcessTextRequest,
        val appLabel: String?,
    ) {
        var answered = false
        var answer: String? = null
    }

    private var pending: Pending? = null

    /**
     * Starts a request; null while an answer still waits for the user to apply or dismiss it. An
     * unanswered request is replaced: the app it opened may never return (Back, a crash), and the
     * user starting another one has given up on it; its late answer no longer matches the token.
     * [appLabel] names the app that was opened, so the preview can say where the answer came from.
     */
    fun start(
        request: ProcessTextRequest,
        appLabel: String? = null,
    ): String? {
        if (pending?.answered == true) return null
        val token = newToken()
        pending = Pending(token, request, appLabel)
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

    /** A different editor retires an answered request; an absent binding waits for the return. */
    fun onEditor(info: EditorInfo?): String? {
        val current = pending ?: return null
        if (!current.answered || info == null) return null
        // The platform may bind an unidentified connection while the activity result returns.
        // It cannot authorize a write or prove that the source field was replaced.
        if (info.packageName.isNullOrEmpty()) return null
        // Nor can a window without a text editor (the relay's own activity while the answer comes
        // back): it is no field at all, so it is not evidence that the user moved to another one.
        if (info.inputType == InputType.TYPE_NULL) return null
        if (current.request.sameEditorAs(info)) return null
        // Unnamed editors cannot authorize replacement, but their preview may still be copied
        // explicitly. A missing field id is not evidence of a different field in the same app.
        if (current.request.packageName == info.packageName &&
            (current.request.fieldId <= 0 || info.fieldId <= 0)
        ) {
            return null
        }
        pending = null
        return null
    }

    /** The waiting answer, or null while the other app is still on screen. */
    fun pendingResult(): ProcessTextResult? {
        val current = pending ?: return null
        if (!current.answered) return null
        return ProcessTextResult(current.request, current.answer.orEmpty(), current.appLabel)
    }

    /** Takes the waiting answer for an explicit write. */
    fun take(): ProcessTextResult? = pendingResult()?.also { pending = null }

    fun dismiss() {
        pending = null
    }
}

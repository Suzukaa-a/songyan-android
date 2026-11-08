package app.songyan.ime.tools

import android.content.ComponentName
import android.content.Context
import app.songyan.ime.ime.tools.KeyboardToolAnswer
import app.songyan.ime.ime.tools.KeyboardToolAnswerOutcome
import app.songyan.ime.ime.tools.KeyboardToolApp
import app.songyan.ime.ime.tools.KeyboardToolSourceKind
import app.songyan.ime.ime.tools.KeyboardToolText
import app.songyan.ime.ime.tools.KeyboardToolsProcessText
import app.songyan.ime.keyboard.engine.InputLanguage
import kotlinx.coroutines.CancellationException

/**
 * Ties the tools panel's "other apps" entries to the relay and to the enabled list.
 *
 * [open] hands the panel's text to the app through the relay activity; the answer is only ever
 * previewed, so [apply] is the user's second, explicit step and never happens on its own. The relay
 * calls run on the main thread, because it keeps the editor binding the request was started from.
 */
class ProcessTextToolGateway(
    context: Context,
    private val enabled: ProcessTextToolApps,
    private val speech: ProcessTextToolApps,
) : KeyboardToolsProcessText {
    private val appContext = context.applicationContext

    override suspend fun apps(): List<KeyboardToolApp> =
        try {
            enabled.enabledApps().map { app -> KeyboardToolApp(app.component, app.label) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A package query that fails leaves the panel without entries rather than failing it.
            emptyList()
        }

    override suspend fun speechApps(): List<KeyboardToolApp> =
        try {
            speech.enabledApps().map { app -> KeyboardToolApp(app.component, app.label) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

    override fun openSpeech(
        appId: String,
        label: String,
        inputLanguage: InputLanguage,
    ): Boolean {
        val component = ComponentName.unflattenFromString(appId) ?: return false
        return ProcessTextRelay.requestSpeech(appContext, component, label, speechLanguageTag(inputLanguage))
    }

    override fun open(
        appId: String,
        label: String,
        source: KeyboardToolText,
    ): Boolean {
        val component = ComponentName.unflattenFromString(appId) ?: return false
        val kind =
            when (source.kind) {
                KeyboardToolSourceKind.SELECTION -> ProcessTextKind.SELECTION
                KeyboardToolSourceKind.FIELD -> ProcessTextKind.FIELD
                KeyboardToolSourceKind.CLIPBOARD -> ProcessTextKind.CLIPBOARD
            }
        return ProcessTextRelay.request(
            appContext,
            component,
            label,
            ProcessTextSource(source.text, kind, source.caret),
        )
    }

    override fun pending(): KeyboardToolAnswer? =
        ProcessTextRelay.pendingResult()?.let { result ->
            KeyboardToolAnswer(
                text = result.text,
                appLabel = result.appLabel.orEmpty(),
                canReplace = ProcessTextRelay.canApplyPending(),
            )
        }

    override fun apply(): KeyboardToolAnswerOutcome =
        when (ProcessTextRelay.applyPending()) {
            ProcessTextOutcome.REPLACED -> KeyboardToolAnswerOutcome.REPLACED
            ProcessTextOutcome.COPIED -> KeyboardToolAnswerOutcome.COPIED
            ProcessTextOutcome.DROPPED -> KeyboardToolAnswerOutcome.DROPPED
        }

    override fun copy(): Boolean = ProcessTextRelay.copyPending()

    override fun dismiss() = ProcessTextRelay.dismissPending()
}

/**
 * The language a recognizer is asked for: the script-neutral Chinese tags recognizers know, since
 * most of them do not accept `zh-Hans` or `zh-Hant`.
 */
internal fun speechLanguageTag(language: InputLanguage): String =
    when (language) {
        InputLanguage.ZH_HANS -> "zh-CN"
        InputLanguage.ZH_HANT -> "zh-TW"
        InputLanguage.JA -> "ja-JP"
    }

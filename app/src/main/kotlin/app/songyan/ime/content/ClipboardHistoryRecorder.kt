package app.songyan.ime.content

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records Android clipboard changes into the opt-in history while the keyboard is on screen, and a
 * clip copied while it was hidden when it next shows ([catchUp]).
 *
 * This is the native counterpart of the iOS app, which only ever recorded what passed through its
 * own paste button: Android has a system clipboard, so the feature is an opt-in listener instead.
 * The privacy rules are strict and all enforced here or in [ClipboardCapturePolicy]:
 * - nothing is read while history is off (the default) or the keyboard is hidden;
 * - nothing is read for a password field or a field with `IME_FLAG_NO_PERSONALIZED_LEARNING`
 *   (the owner sets [setCaptureAllowed] from its editor policy);
 * - the clip description is inspected first and a clip flagged `EXTRA_IS_SENSITIVE` is never read;
 * - only the item's text is taken; a content URI is never resolved, because that would read
 *   another app's data and block this callback on disk;
 * - the text is stored on an IO dispatcher, never on the callback thread;
 * - the clip version last taken is remembered across processes, so a show never re-adds a clip the
 *   history already had (or one the user deleted from it).
 *
 * The owner calls [start] when its input view is alive and [setCaptureAllowed] whenever the
 * visible/private state changes. Attaching the listener itself is cheap and does not read the
 * clipboard; only a change notification does.
 */
class ClipboardHistoryRecorder(
    context: Context,
    private val repository: QuickContentRepository,
    private val scope: CoroutineScope,
    private val clipboard: ClipboardManager? = context.getSystemService(ClipboardManager::class.java),
) : ClipboardManager.OnPrimaryClipChangedListener {
    private val policy = ClipboardCapturePolicy()
    private var listening = false
    private var restored = false
    private val handledStore by lazy {
        context.applicationContext.getSharedPreferences(HANDLED_PREFS, Context.MODE_PRIVATE)
    }

    /** Whether a clipboard change may be captured right now: keyboard visible and field not private. */
    fun setCaptureAllowed(allowed: Boolean) {
        policy.setCaptureAllowed(allowed)
    }

    /**
     * Mirrors the stored opt-in flag right after the settings screen changed it, so a history that
     * was just turned off refuses the very next change instead of waiting for a process restart.
     */
    fun setHistoryEnabled(enabled: Boolean) {
        policy.setHistoryEnabled(enabled)
    }

    fun start() {
        if (listening) return
        clipboard?.addPrimaryClipChangedListener(this)
        listening = true
    }

    fun stop() {
        if (!listening) return
        clipboard?.removePrimaryClipChangedListener(this)
        listening = false
        policy.setCaptureAllowed(false)
    }

    /**
     * The keyboard just showed in a field that may be observed: take a clip copied while it was
     * hidden (or while this process was not running). Only the description is looked at unless
     * the policy says the clip is new.
     */
    fun catchUp() {
        if (listening) onPrimaryClipChanged()
    }

    override fun onPrimaryClipChanged() {
        val description = runCatching { clipboard?.primaryClipDescription }.getOrNull() ?: return
        val observation = observe(description)
        // The opt-in flag lives in content.json and the settings screen writes it through its own
        // store instance, so the value cached in this process at start can be stale. Re-read it
        // before deciding, and only then touch the clip: reading a clip makes Android announce a
        // paste, so a stale "history is on" would keep announcing pastes after the user turned the
        // feature off. The coroutine stays on the main thread except for the repository hop, so the
        // capture policy is still only touched from one thread.
        scope.launch {
            val enabled = runCatching { repository.refresh() }.getOrNull()?.clipboardEnabled == true
            policy.setHistoryEnabled(enabled)
            if (!restored) {
                restored = true
                val stored =
                    runCatching {
                        withContext(
                            Dispatchers.IO,
                        ) { handledStore.getLong(KEY_HANDLED, ClipboardCapturePolicy.NO_TIMESTAMP) }
                    }.getOrDefault(ClipboardCapturePolicy.NO_TIMESTAMP)
                policy.restoreHandled(stored)
            }
            if (policy.decide(observation) != ClipboardDecision.READ) return@launch
            val text = readText() ?: return@launch
            policy.markHandled(observation)
            withContext(Dispatchers.IO) { handledStore.edit().putLong(KEY_HANDLED, observation.timestamp).apply() }
            repository.recordCapturedClipboardText(text)
        }
    }

    private fun observe(description: ClipDescription): ClipboardObservation =
        ClipboardObservation(
            available = true,
            textMimeType =
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML),
            sensitive = isSensitive(description),
            timestamp = description.timestamp,
        )

    /** The clip is never read before this returns false. */
    private fun isSensitive(description: ClipDescription): Boolean {
        val extras = description.extras ?: return false
        return extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ||
            extras.getBoolean(LEGACY_SENSITIVE_EXTRA, false)
    }

    /** Only item text: resolving a URI here would read another app and block the callback. */
    private fun readText(): String? =
        runCatching {
            // A locked or denied clipboard throws instead of returning null; never let that reach
            // the IME's process as a crash.
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
        }.getOrNull()

    private companion object {
        /** Pre-API 33 spelling of [ClipDescription.EXTRA_IS_SENSITIVE]. */
        const val LEGACY_SENSITIVE_EXTRA: String = "android.content.extra.IS_SENSITIVE"

        /** Holds only a boot-relative clip timestamp, never clip content. */
        const val HANDLED_PREFS = "clipboard_capture"
        const val KEY_HANDLED = "handled_timestamp"
    }
}

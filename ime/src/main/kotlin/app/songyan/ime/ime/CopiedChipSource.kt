package app.songyan.ime.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper

/**
 * Feeds the toolbar's copied-text chip from the Android clipboard.
 *
 * The keyboard is the default input method while it is shown, so it may read the clipboard; the
 * rules of [ToolbarChipPolicy] decide when it does. Only the clip's description is looked at first
 * (reading a description never announces a paste), and a content URI is never resolved. The owner
 * calls [onInputStarted] / [onInputFinished] around each editor; a clip copied while the keyboard
 * was hidden is picked up when it next shows, and one copied while it is shown by the listener.
 * Main thread only.
 */
class CopiedChipSource(
    private val clipboard: ClipboardManager?,
    ttlMs: Long = ToolbarChipPolicy.DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ClipboardManager.OnPrimaryClipChangedListener {
    private val policy = ToolbarChipPolicy(ttlMs)
    private var listening = false

    /** Something about the chip changed (it appeared, was used or expired); the owner redraws the band. */
    var onChanged: () -> Unit = {}

    private val expire = Runnable { onChanged() }

    /** A new editor is on screen. A private field never gets a chip, and nothing of it is read. */
    fun onInputStarted(privateInput: Boolean) {
        policy.setAllowed(!privateInput)
        if (!privateInput && !listening) {
            clipboard?.addPrimaryClipChangedListener(this)
            listening = true
        }
        if (privateInput) stopListening()
        refresh()
        schedule()
    }

    /** The keyboard hid: the chip is dropped and the clipboard is no longer watched. */
    fun onInputFinished() {
        policy.setAllowed(false)
        stopListening()
        handler.removeCallbacks(expire)
    }

    fun stop() = onInputFinished()

    /** The chip to show now, or null. */
    fun current(): ToolbarChip? = policy.current(clock())

    fun used() {
        policy.used()
        handler.removeCallbacks(expire)
    }

    fun dismiss() {
        policy.dismiss()
        handler.removeCallbacks(expire)
    }

    override fun onPrimaryClipChanged() {
        refresh()
        schedule()
        onChanged()
    }

    private fun stopListening() {
        if (!listening) return
        clipboard?.removePrimaryClipChangedListener(this)
        listening = false
    }

    private fun refresh() {
        val description = runCatching { clipboard?.primaryClipDescription }.getOrNull()
        val observation = observe(description)
        val now = clock()
        if (!policy.shouldRead(observation, now)) return
        policy.offer(observation, readText(), now)
    }

    private fun schedule() {
        handler.removeCallbacks(expire)
        val chip = policy.current(clock()) ?: return
        handler.postDelayed(expire, (chip.expiresAt - clock()).coerceAtLeast(0L) + 1)
    }

    private fun observe(description: ClipDescription?): ClipObservation {
        if (description == null) return ClipObservation(false, false, false, 0L)
        val sensitive = description.isSensitiveClip()
        return ClipObservation(
            available = true,
            textMimeType = description.hasMimeType("text/plain") || description.hasMimeType("text/html"),
            sensitive = sensitive,
            timestamp = description.timestamp,
        )
    }

    /** Only the item's text; a locked or denied clipboard throws and must never crash the keyboard. */
    private fun readText(): String? =
        runCatching {
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()
        }.getOrNull()
}

/** The host flagged the clip with `EXTRA_IS_SENSITIVE` (or its pre-API 33 spelling); its content is never read. */
internal fun ClipDescription.isSensitiveClip(): Boolean {
    val extras = extras ?: return false
    return extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) || extras.getBoolean(LEGACY_SENSITIVE, false)
}

/** Pre-API 33 spelling of [ClipDescription.EXTRA_IS_SENSITIVE]. */
private const val LEGACY_SENSITIVE = "android.content.extra.IS_SENSITIVE"

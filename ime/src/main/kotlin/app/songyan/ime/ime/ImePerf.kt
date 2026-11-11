package app.songyan.ime.ime

import android.os.Build
import android.os.Trace
import android.util.Log

/**
 * Latency probes for the three milestones a user can feel.
 *
 * Enable with `adb shell setprop log.tag.SongyanPerf DEBUG`. The flag is cached once per keyboard
 * show via [refresh], so the touch path never calls `Log.isLoggable`; when disabled every hook is one
 * volatile read plus one long write, with no allocation and no locks. Hooks are called from the main
 * thread only (the controller dispatches on `Dispatchers.Main.immediate`).
 *
 * Trace async sections are emitted regardless of the log tag (API 29+) so Perfetto captures work
 * without flipping a property. Durations are measured with [System.nanoTime] between main-thread
 * callbacks and logged in microseconds:
 *
 * - `show_us`: `onStartInputView` entry to the first pre-draw of the IME window.
 * - `key_us`: key ACTION_DOWN received to the session finished and the preedit was applied.
 *   `wait_us` is the time the tap spent queued before dispatch started, `engine_us` is dispatch
 *   including the engine round trip and `setComposingText`.
 * - `commit_us`: candidate ACTION_UP to `selectCandidate` returned (commit text applied);
 *   `select_us` is the engine selection part.
 */
object ImePerf {
    const val TAG = "SongyanPerf"
    const val SHOW_SECTION = "songyan.ime.show"
    const val KEY_SECTION = "songyan.ime.key"
    const val COMMIT_SECTION = "songyan.ime.commit"

    private const val NONE = Long.MIN_VALUE

    /** A pending marker older than this lost its completion (cancelled press, hidden window). */
    private const val STALE_NS = 5_000_000_000L

    // Always true on devices; SDK_INT is 0 in JVM unit tests, where Trace is not implemented.
    private val traceSupported = Build.VERSION.SDK_INT >= 29

    @Volatile private var logging = false
    private var cookie = 0

    private var showAt = NONE
    private var showCookie = 0

    private var keyAt = NONE
    private var keyDispatchAt = NONE
    private var keyCookie = 0

    private var commitAt = NONE
    private var commitRunAt = NONE
    private var commitCookie = 0

    private var keyBoundsPending = false

    /** Caches the log tag decision; called once per keyboard show, never from the touch path. */
    fun refresh() {
        logging = Log.isLoggable(TAG, Log.DEBUG)
        keyBoundsPending = logging
    }

    val isLogging: Boolean get() = logging

    /** The session accepted the editor and its first snapshot is rendered; taps are no longer dropped. */
    fun ready() {
        if (logging) Log.d(TAG, "ready")
    }

    /** Asks for the key boxes to be logged again (the keyboard moved). */
    fun rearmKeyBounds() {
        keyBoundsPending = logging
    }

    /**
     * True once per show while the adb driver still needs this layout's key hit boxes. Only key
     * geometry that the driver cannot guess from uiautomator is dumped, so it is one-shot.
     */
    fun consumeKeyBounds(): Boolean = keyBoundsPending.also { keyBoundsPending = false }

    /** One key hit box in screen pixels; the adb driver taps these centres. */
    fun keyBound(
        index: Int,
        kind: String,
        value: String?,
        cx: Int,
        cy: Int,
    ) {
        if (!logging) return
        Log.d(TAG, "key_bound i=" + index + " kind=" + kind + " value=" + (value ?: "") + " cx=" + cx + " cy=" + cy)
    }

    /** First visible candidate centre in screen pixels; emitted on every candidate-bar rebuild. */
    fun candidateBound(
        cx: Int,
        cy: Int,
    ) {
        if (!logging) return
        Log.d(TAG, "cand0 cx=" + cx + " cy=" + cy)
    }

    /** Keyboard window is about to become visible. */
    fun showStart() {
        showAt = System.nanoTime()
        showCookie = beginAsync(SHOW_SECTION)
    }

    /** First frame of the keyboard window is about to be drawn. */
    fun showFirstFrame() {
        val start = showAt
        showAt = NONE
        endAsync(SHOW_SECTION, showCookie)
        if (start == NONE) return
        val elapsed = System.nanoTime() - start
        if (elapsed > STALE_NS) return
        if (logging) Log.d(TAG, "show_us=" + (elapsed / 1_000))
    }

    /** Key touches down; the press may still turn into a long press or a swipe. */
    fun keyDown() {
        keyAt = System.nanoTime()
        keyDispatchAt = NONE
        keyCookie = beginAsync(KEY_SECTION)
    }

    /** Queued action reached the dispatcher, i.e. the main-thread part starts after the wait. */
    fun keyDispatchStart() {
        if (keyAt != NONE) keyDispatchAt = System.nanoTime()
    }

    /** Dispatcher finished: the session consumed the key and the preedit was pushed to the editor. */
    fun keyDispatched() {
        val end = System.nanoTime()
        val start = keyAt
        val dispatch = keyDispatchAt
        keyDispatchAt = NONE
        // A long press can dispatch repeatedly from one DOWN; only the first completion is one press.
        if (start == NONE) return
        keyAt = NONE
        endAsync(KEY_SECTION, keyCookie)
        val elapsed = end - start
        if (elapsed > STALE_NS) return
        if (logging) {
            val wait = if (dispatch == NONE) 0L else dispatch - start
            Log.d(
                TAG,
                "key_us=" + (elapsed / 1_000) +
                    " wait_us=" + (wait / 1_000) +
                    " engine_us=" + ((elapsed - wait) / 1_000),
            )
        }
    }

    /** Candidate touched up; the selection is about to be enqueued. */
    fun commitStart() {
        commitAt = System.nanoTime()
        commitRunAt = NONE
        commitCookie = beginAsync(COMMIT_SECTION)
    }

    /** Selection reached the dispatcher, right before the engine call. */
    fun commitRunStart() {
        if (commitAt != NONE) commitRunAt = System.nanoTime()
    }

    /** Selection finished: the committed text was handed to the editor. */
    fun commitDone() {
        val end = System.nanoTime()
        val start = commitAt
        val run = commitRunAt
        commitRunAt = NONE
        if (start == NONE) return
        commitAt = NONE
        endAsync(COMMIT_SECTION, commitCookie)
        val elapsed = end - start
        if (elapsed > STALE_NS) return
        if (logging) {
            val select = if (run == NONE) 0L else end - run
            Log.d(TAG, "commit_us=" + (elapsed / 1_000) + " select_us=" + (select / 1_000))
        }
    }

    private fun beginAsync(name: String): Int {
        if (!traceSupported) return -1
        val next = ++cookie
        Trace.beginAsyncSection(name, next)
        return next
    }

    private fun endAsync(
        name: String,
        value: Int,
    ) {
        if (!traceSupported || value < 0) return
        Trace.endAsyncSection(name, value)
    }
}

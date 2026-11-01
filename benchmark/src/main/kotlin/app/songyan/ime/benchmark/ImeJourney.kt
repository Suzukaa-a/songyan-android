package app.songyan.ime.benchmark

import android.graphics.Point
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The user journeys that the Macrobenchmarks measure and the Baseline Profile covers: open the
 * settings 试打 screen, show the keyboard, type pinyin and pick the first candidate.
 *
 * uiautomator cannot see the input method window, so key and candidate positions come from the
 * `key_bound` / `cand0` lines ImePerf logs while `log.tag.SongyanPerf` is DEBUG (the same source
 * scripts/collect-ime-perf.sh uses). The trace sections the metrics read are emitted either way.
 */
object ImeJourney {
    const val PACKAGE = "app.songyan.ime.benchmark"
    const val SHOW_SECTION = "songyan.ime.show"
    const val KEY_SECTION = "songyan.ime.key"
    const val COMMIT_SECTION = "songyan.ime.commit"

    private const val IME_ID = "$PACKAGE/app.songyan.ime.SongyanInputMethodService"
    private const val PERF_TAG = "SongyanPerf"
    private val APP_LOG = Regex("(?i)songyan|rime|AndroidRuntime|deploy|FATAL")
    private const val UI_TIMEOUT_MS = 15_000L
    private const val OPEN_TRIES = 3
    private const val TOUCH_SETTLE_MS = 400L

    /** A first install deploys the built-in engine data before the keyboard can draw. */
    private const val KEYBOARD_TIMEOUT_MS = 120_000L

    /** "ni hao": every prefix is a valid pinyin spelling, so each tap updates the candidates. */
    val LETTERS = listOf("n", "i", "h", "a", "o")

    /** Device state the journeys need; idempotent, so every setup block may call it. */
    fun MacrobenchmarkScope.prepareDevice() {
        // The journeys find the settings rows by their Chinese labels.
        shell("cmd locale set-app-locales $PACKAGE --user 0 --locales zh-CN")
        // The first-launch notification prompt would cover the settings screen.
        shell("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        shell("ime enable $IME_ID")
        shell("ime set $IME_ID")
        shell("setprop log.tag.$PERF_TAG DEBUG")
    }

    /**
     * Brings the 试打 screen with its text field to the front. A cold emulator can be slow to draw
     * the first screen, or show a system dialog over it, so a few tries are made before giving up
     * with what is on screen.
     */
    fun MacrobenchmarkScope.openTryout() {
        repeat(OPEN_TRIES) {
            startActivityAndWait()
            device.wait(Until.findObject(By.text("试打")), UI_TIMEOUT_MS)?.click()
            if (device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS) != null) return
            device.pressBack()
        }
        val hierarchy =
            java.io
                .ByteArrayOutputStream()
                .also { device.dumpWindowHierarchy(it) }
                .toString()
        error("the 试打 text field did not appear; on screen: " + hierarchy.take(4000))
    }

    /** Taps the text field and waits until the keyboard is drawn and accepts taps. */
    fun MacrobenchmarkScope.showKeyboard(): Keyboard {
        shell("logcat -c")
        val field =
            checkNotNull(device.findObject(By.clazz("android.widget.EditText"))) { "no text field to focus" }
        field.click()
        val deadline = SystemClock.uptimeMillis() + KEYBOARD_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val lines = perfLines()
            // Key boxes are logged once per layout, not on every show of the same layout.
            parseKeys(lines).takeIf { it.isNotEmpty() }?.let { lastKeys = it }
            val keys = lastKeys
            // A new editor session logs "ready"; showing again for the same editor only logs show_us.
            if (keys != null && lines.any { it.endsWith(": ready") || " show_us=" in it || ": show_us=" in it }) {
                // The first injected tap can land before the window accepts touches.
                SystemClock.sleep(TOUCH_SETTLE_MS)
                return Keyboard(keys)
            }
            SystemClock.sleep(100)
        }
        error("the keyboard did not become ready; check that the engine deployed")
    }

    /** Hides the keyboard with Back and lets the window animation finish. */
    fun MacrobenchmarkScope.hideKeyboard() {
        device.pressBack()
        device.waitForIdle()
        SystemClock.sleep(500)
    }

    /** Types [letters] and returns once the candidate bar shows candidates for the last one. */
    fun MacrobenchmarkScope.type(
        keyboard: Keyboard,
        letters: List<String>,
    ): Point {
        val before = candidateLines().size
        for (letter in letters) {
            val key = checkNotNull(keyboard.keys[letter]) { "no key for '$letter'" }
            tap(key)
            SystemClock.sleep(120)
        }
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = candidateLines()
            if (candidates.size > before) return parsePoint(candidates.last())
            SystemClock.sleep(50)
        }
        error("no candidates after typing " + letters.joinToString(""))
    }

    /** Commits the first candidate. */
    fun MacrobenchmarkScope.pickCandidate(candidate: Point) {
        tap(candidate)
        SystemClock.sleep(300)
    }

    /**
     * Makes sure taps compose pinyin: a keyboard left in English mode inserts letters without
     * candidates, and the key and commit metrics would measure nothing. A first install is also
     * silent until the engine data is deployed, which takes minutes on a slow emulator, so the
     * attempts run until a deadline; each failed attempt flips the language, so once the engine
     * answers one of two consecutive attempts is in pinyin.
     */
    fun MacrobenchmarkScope.ensureChinese(keyboard: Keyboard) {
        val deadline = SystemClock.uptimeMillis() + KEYBOARD_TIMEOUT_MS
        var attempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++
            val ok = runCatching { type(keyboard, listOf("n", "i")) }.isSuccess
            repeat(3) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_DEL) }
            if (ok) return
            val switch = checkNotNull(keyboard.languageSwitch) { "no language key to switch to pinyin" }
            tap(switch)
            SystemClock.sleep(500)
        }
        error(
            "the keyboard does not compose pinyin after $attempts attempts; perf log: " +
                perfLines().filter { "key_us" !in it }.takeLast(15) +
                "; app log: " +
                shell(
                    "logcat -d -v brief *:I",
                ).lines().filter { APP_LOG.containsMatchIn(it) && "key_" !in it }.takeLast(40),
        )
    }

    /** Key centres of the current layout by letter, plus the language key. */
    class Keyboard(
        val keys: Map<String, Point>,
        val languageSwitch: Point? = keys[LANGUAGE_SWITCH],
    )

    private const val LANGUAGE_SWITCH = "\u0000language"

    /** The test process outlives the app process, so a later show can reuse the boxes it logged. */
    private var lastKeys: Map<String, Point>? = null

    private fun MacrobenchmarkScope.shell(command: String): String = device.executeShellCommand(command)

    /**
     * `input tap` holds for about 0 ms. UiAutomator's click holds about 100 ms, and key_us counts
     * from ACTION_DOWN, so that hold would swamp the latency being measured.
     */
    private fun MacrobenchmarkScope.tap(point: Point) {
        shell("input tap " + point.x + " " + point.y)
    }

    private fun MacrobenchmarkScope.perfLines(): List<String> =
        shell("logcat -d -v brief -s $PERF_TAG").lines().filter { it.isNotBlank() }

    private fun MacrobenchmarkScope.candidateLines(): List<String> = perfLines().filter { " cand0 " in it }

    private fun parseKeys(lines: List<String>): Map<String, Point> {
        val keys = HashMap<String, Point>()
        for (line in lines) {
            if (" key_bound " !in line) continue
            val kind = field(line, "kind")
            val value = field(line, "value")
            val point = parsePoint(line)
            if (kind == "LANGUAGE_SWITCH") keys[LANGUAGE_SWITCH] = point
            if (!value.isNullOrEmpty()) keys[value] = point
        }
        return keys
    }

    private fun parsePoint(line: String): Point =
        Point(checkNotNull(field(line, "cx")).toInt(), checkNotNull(field(line, "cy")).toInt())

    private fun field(
        line: String,
        name: String,
    ): String? = Regex("""(?:^|\s)$name=(\S*)""").find(line)?.groupValues?.get(1)
}

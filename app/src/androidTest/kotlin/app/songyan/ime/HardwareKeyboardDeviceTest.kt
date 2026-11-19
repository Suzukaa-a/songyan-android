package app.songyan.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import app.songyan.ime.ime.R as ImeR
import app.songyan.ime.settings.R as SettingsR

/**
 * Hardware keys through the real input method service, in a real field. Keys are injected with
 * `UiAutomation.injectInputEvent`: they travel the same way a Bluetooth keyboard's do (input
 * dispatcher, the focused window, its input method stage, `InputMethodService.onKeyDown`).
 * The emulator has no hardware keyboard configuration, so what the system says about the
 * on-screen keyboard comes from [SongyanInputMethodService.systemSoftKeysForTest].
 */
@RunWith(AndroidJUnit4::class)
class HardwareKeyboardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val density = context.resources.displayMetrics.density
    private var previousIme: String? = null
    private var wasEnabled = false
    private var host: Activity? = null

    @Before
    fun setUp() {
        ensureAnInputMethod(context)
        previousIme = shell("settings get secure default_input_method")
        wasEnabled = shell("ime list -s").lines().contains(KEYBOARD_ID)
        shell("ime enable $KEYBOARD_ID")
        shell("ime set $KEYBOARD_ID")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (shell("settings get secure default_input_method") != KEYBOARD_ID) {
            check(SystemClock.elapsedRealtime() < deadline) { "$KEYBOARD_ID did not become the default keyboard" }
            Thread.sleep(100)
        }
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    @After
    fun tearDown() {
        SongyanInputMethodService.systemSoftKeysForTest = null
        device.pressBack()
        host?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
    }

    @Test
    fun typingOnAHardwareKeyboardComposesAndCommits() {
        SongyanInputMethodService.systemSoftKeysForTest = null
        openTryOutField()
        awaitKey(context.getString(ImeR.string.ime_language_to_english))
        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        val first = awaitFirstCandidate()
        press(KeyEvent.KEYCODE_SPACE)
        awaitField(first)
    }

    @Test
    fun withTheOnScreenKeyboardOffOnlyTheBarAppears() {
        SongyanInputMethodService.systemSoftKeysForTest = false
        openTryOutField()
        Thread.sleep(SETTLE_MS)
        assertNull("nothing of the keyboard shows before a key", imeWindow())

        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        awaitFirstCandidate()
        val window = requireNotNull(imeWindow())
        val leaves = imeLeaves()
        assertTrue("candidates are listed: $leaves", leaves.isNotEmpty())
        assertNull(
            "no key area: ${labels()}",
            labels().firstOrNull { it == context.getString(ImeR.string.ime_language_to_english) },
        )
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        assertTrue("the window is only a bar: $bounds", bounds.height() < MAX_BAR_DP * density)

        // The app is laid out above the bar: its ime inset starts at the bar's top.
        val activity = requireNotNull(host)
        var imeTop = 0
        var screenBottom = 0
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val insets = requireNotNull(decor.rootWindowInsets)
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            screenBottom = location[1] + decor.height
            imeTop = screenBottom - insets.getInsets(WindowInsets.Type.ime()).bottom
        }
        val barTop = leaves.minOf { it.top }
        assertTrue("ime inset top $imeTop, bar top $barTop", abs(imeTop - barTop) <= 1)
        assertTrue("the inset is only the bar", screenBottom - imeTop < MAX_BAR_DP * density)

        val region =
            Regex("""touchable region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)""")
                .find(imeWindowDump())
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
        requireNotNull(region) { "no single-rectangle touchable region" }
        assertEquals("touchable region top", barTop, region[1])
    }

    @Test
    fun aBarCandidateCanBeTapped() {
        SongyanInputMethodService.systemSoftKeysForTest = false
        openTryOutField()
        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        awaitFirstCandidate()
        val candidate = imeButtons().minWith(compareBy({ it.second.top }, { it.second.left }))
        val word = candidate.first.substringBefore(' ')
        val clicked = imeNode { it.contentDescription?.toString() == candidate.first }
        assertNotNull(clicked)
        assertTrue(clicked!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitField(word)
    }

    @Test
    fun idleArrowKeysMoveTheCaretInTheField() {
        SongyanInputMethodService.systemSoftKeysForTest = null
        openTryOutField()
        awaitKey(context.getString(ImeR.string.ime_language_to_english))
        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        press(KeyEvent.KEYCODE_SPACE)
        val first = awaitFirstCandidateOrNull()
        Thread.sleep(SETTLE_MS)
        val end = selectionStart()
        assertTrue("something was committed", end > 0)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (selectionStart() >= end && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertEquals("the arrow key moved the caret ($first)", end - 1, selectionStart())
    }

    @Test
    fun backDismissesTheBarAndTheNextKeyBringsItBack() {
        SongyanInputMethodService.systemSoftKeysForTest = false
        openTryOutField()
        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        awaitFirstCandidate()

        device.pressBack()
        awaitNoImeWindow()
        // The unfinished spelling is taken out of the field, as on the soft keyboard.
        assertEquals("", awaitSettled { fieldText() })

        press(KeyEvent.KEYCODE_N)
        awaitField("n")
        awaitFirstCandidateOrNull()
        assertNotNull("the bar is back", imeWindow())
    }

    @Test
    fun withTheOnScreenKeyboardOnBackThenAKeyBringsTheKeyboardBack() {
        SongyanInputMethodService.systemSoftKeysForTest = true
        openTryOutField()
        awaitKey("q")

        device.pressBack()
        awaitNoImeWindow()

        // Composing with nothing on screen would let Space commit a candidate the user never saw.
        press(KeyEvent.KEYCODE_N)
        press(KeyEvent.KEYCODE_I)
        awaitField("ni")
        awaitFirstCandidate()
        assertNotNull("the keyboard is back", imeWindow())
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun press(keyCode: Int) {
        assertTrue(device.pressKeyCode(keyCode))
        Thread.sleep(KEY_GAP_MS)
    }

    private fun openTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickWhenSettled(By.text(context.getString(SettingsR.string.row_tryout_title)))
        clickWhenSettled(FIELD)
        instrumentation.runOnMainSync {
            host =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is SongyanSettingsActivity }
        }
    }

    private fun clickWhenSettled(selector: BySelector) {
        repeat(5) {
            val node =
                requireNotNull(device.wait(Until.findObject(selector), TIMEOUT_MS)) { "$selector did not appear" }
            try {
                node.click()
                return
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("$selector kept being replaced")
    }

    private fun fieldText(): String {
        repeat(5) {
            try {
                val field = device.findObject(FIELD) ?: return ""
                return field.text?.takeUnless { field.hint != null && it == field.hint }.orEmpty()
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("the try-out field kept being replaced")
    }

    /** The caret of the try-out field as its accessibility node reports it; -1 when unknown. */
    private fun selectionStart(): Int {
        fun walk(node: AccessibilityNodeInfo): Int? {
            if (node.className == "android.widget.EditText") return node.textSelectionStart
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk) ?: -1
    }

    private fun awaitField(expected: String) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = fieldText()
            if (last == expected) return
            Thread.sleep(250)
        }
        throw AssertionError("the field shows '$last', not '$expected'")
    }

    private fun awaitSettled(read: () -> String): String {
        var last = read()
        repeat(10) {
            Thread.sleep(500)
            val now = read()
            if (now == last) return now
            last = now
        }
        return last
    }

    /** The first candidate's word: the leftmost button of the topmost row of the keyboard window. */
    private fun awaitFirstCandidate(): String =
        requireNotNull(awaitFirstCandidateOrNull()) { "no candidate appeared; ${labels()}" }

    private fun awaitFirstCandidateOrNull(): String? {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            imeButtons().minWithOrNull(compareBy({ it.second.top }, { it.second.left }))?.let {
                return it.first.substringBefore(' ')
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun awaitKey(label: String) {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (label in labels()) return
            Thread.sleep(250)
        }
        throw AssertionError("no '$label' key; ${labels()}")
    }

    private fun awaitNoImeWindow() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (imeWindow() == null) return
            Thread.sleep(250)
        }
        throw AssertionError("the keyboard window stayed")
    }

    private fun imeWindow(): AccessibilityWindowInfo? =
        instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun imeNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child)?.let { return it } }
            return null
        }
        return imeWindow()?.root?.let(::walk)
    }

    /** Every visible button of the keyboard window with its label and screen bounds. */
    private fun imeButtons(): List<Pair<String, Rect>> {
        val out = mutableListOf<Pair<String, Rect>>()

        fun walk(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (node.childCount == 0 && node.className == "android.widget.Button") {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val label = node.contentDescription?.toString()
                if (!bounds.isEmpty && label != null) out += label to bounds
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        imeWindow()?.root?.let(::walk)
        return out
    }

    private fun imeLeaves(): List<Rect> = imeButtons().map { it.second }

    private fun labels(): List<String> = imeButtons().map { it.first }

    private fun imeWindowDump(): String =
        shell("dumpsys window windows")
            .split(Regex("""(?m)^\s+Window #\d+ """))
            .firstOrNull {
                it.startsWith("Window{") &&
                    it.contains(" InputMethod}") &&
                    it.contains("package=${context.packageName} ")
            }
            ?: throw AssertionError("no input method window of ${context.packageName}")

    private fun shell(command: String): String = device.executeShellCommand(command).trim()

    private companion object {
        /** Debug builds add `.debug` to the package, not to the class. */
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val TIMEOUT_MS = 15_000L
        const val READY_TIMEOUT_MS = 120_000L
        const val SETTLE_MS = 1_500L
        const val KEY_GAP_MS = 150L
        const val MAX_BAR_DP = 120
        val FIELD: BySelector = By.clazz("android.widget.EditText")
    }
}

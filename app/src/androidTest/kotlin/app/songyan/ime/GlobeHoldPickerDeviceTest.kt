package app.songyan.ime

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.songyan.ime.prefs.KeyboardPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.songyan.ime.ime.R as ImeR
import app.songyan.ime.settings.R as SettingsR

/**
 * Holding the switch-input-method key on the real keyboard brings up the system's input method
 * picker while the finger is still down, even when the finger rolls a little as a real thumb does.
 */
@RunWith(AndroidJUnit4::class)
class GlobeHoldPickerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val preferences = KeyboardPreferences(context)
    private var previousIme: String? = null
    private var wasEnabled = false
    private var showedSwitchKey = false

    @Before
    fun setUp() {
        showedSwitchKey = runBlocking { preferences.showSwitchKey.first() }
        runBlocking { preferences.setShowSwitchKey(true) }
        previousIme = shell("settings get secure default_input_method")
        wasEnabled = shell("ime list -s").lines().contains(KEYBOARD_ID)
        shell("ime enable $KEYBOARD_ID")
        shell("ime set $KEYBOARD_ID")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (shell("settings get secure default_input_method") != KEYBOARD_ID) {
            check(SystemClock.elapsedRealtime() < deadline) { "$KEYBOARD_ID did not become the default keyboard" }
            Thread.sleep(100)
        }
    }

    @After
    fun tearDown() {
        device.pressBack()
        device.pressBack()
        device.pressBack()
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
        runBlocking { preferences.setShowSwitchKey(showedSwitchKey) }
    }

    @Test
    fun holdingTheSwitchKeyWithAThumbRollShowsThePicker() {
        raiseKeyboardInTryOutField()
        val globe =
            requireNotNull(awaitKey(context.getString(ImeR.string.ime_toolbar_switch_input_method))) {
                "no switch-input-method key; keys: ${keyLabels()}"
            }
        val box = Rect().also { globe.getBoundsInScreen(it) }
        val drift = DRIFT_DP * context.resources.displayMetrics.density
        // Past the 8 dp touch slop, still well inside the key.
        assertTrue("switch key too narrow for the drift: $box", box.width() / 2f > drift + 2f)
        val x = box.exactCenterX()
        val y = box.exactCenterY()

        val downAt = SystemClock.uptimeMillis()
        inject(downAt, MotionEvent.ACTION_DOWN, x, y)
        try {
            Thread.sleep(60)
            inject(downAt, MotionEvent.ACTION_MOVE, x + drift / 2f, y + 1f)
            Thread.sleep(30)
            inject(downAt, MotionEvent.ACTION_MOVE, x + drift, y + 2f)
            // Only a hold asks for the picker before the release; a tap acts on the release.
            assertTrue(
                "the input method picker did not appear during the hold",
                device.wait(Until.hasObject(pickerEntry()), PICKER_TIMEOUT_MS),
            )
        } finally {
            inject(downAt, MotionEvent.ACTION_UP, x + drift, y + 2f)
        }
        // The release after a hold must not switch keyboards behind the picker.
        assertEquals(KEYBOARD_ID, shell("settings get secure default_input_method"))
    }

    /** The picker row naming this keyboard, drawn by the system. */
    private fun pickerEntry(): BySelector {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val label =
            manager.enabledInputMethodList
                .first { it.id == KEYBOARD_ID }
                .loadLabel(context.packageManager)
                .toString()
        return By.pkg("android").text(label)
    }

    private fun inject(
        downAt: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "could not inject $event" }
        } finally {
            event.recycle()
        }
    }

    private fun raiseKeyboardInTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickWhenSettled(By.text(context.getString(SettingsR.string.row_tryout_title)))
        clickWhenSettled(FIELD)
        // The keys are redrawn once the engine is up and again when a background deploy publishes;
        // a hold must not straddle either.
        requireNotNull(awaitKey(context.getString(ImeR.string.ime_language_to_english))) {
            "Chinese input never became ready; keys: ${keyLabels()}"
        }
        awaitNoDeploy()
    }

    /** Waits until the deploy service has stayed stopped for a second. */
    private fun awaitNoDeploy() {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var quiet = 0
        while (quiet < 2) {
            check(SystemClock.elapsedRealtime() < deadline) { "a deploy kept running" }
            // Only the deploy service's own record counts: the dump also lists the last ANR'd service,
            // which can be any app's.
            val running =
                shell("dumpsys activity services $DEPLOY_SERVICE").lineSequence().any {
                    it.trimStart().startsWith("ServiceRecord{") && it.contains(DEPLOY_SERVICE)
                }
            quiet = if (running) 0 else quiet + 1
            Thread.sleep(500)
        }
    }

    /** Settings redraws while a deploy finishes, which can replace a node between finding and clicking it. */
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

    /** The keyboard's node for [label], once the keys are drawn. */
    private fun awaitKey(label: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            keyboardRoot()?.let { find(it, label) }?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun keyboardRoot(): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.root

    private fun find(
        node: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == label && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child, label)?.let { return it } }
        return null
    }

    private fun keyLabels(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo) {
            node.contentDescription?.let { out += it.toString() }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        keyboardRoot()?.let(::walk)
        return out
    }

    private fun shell(command: String): String = device.executeShellCommand(command).trim()

    private companion object {
        /** Debug builds add `.debug` to the package, not to the class. */
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val DEPLOY_SERVICE = "app.songyan.ime.debug/app.songyan.ime.deploy.DeployService"
        const val TIMEOUT_MS = 15_000L

        /** A debug reinstall redeploys; a large imported scheme can take minutes before the keys are back. */
        const val READY_TIMEOUT_MS = 300_000L

        /** Well past the hold delay, which follows the system's touch & hold delay (at most 1.5 s). */
        const val PICKER_TIMEOUT_MS = 3_000L

        /** A thumb's roll during a hold: past the 8 dp touch slop, inside any switch key. */
        const val DRIFT_DP = 11f
        val FIELD: BySelector = By.clazz("android.widget.EditText")
    }
}

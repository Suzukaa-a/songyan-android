package app.songyan.ime

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.songyan.ime.ime.R as ImeR
import app.songyan.ime.settings.R as SettingsR

/**
 * The real keyboard in a real field: hiding it in the middle of a word must take the unfinished
 * spelling out of the field, not leave "bei jing" behind as text the user never chose.
 */
@RunWith(AndroidJUnit4::class)
class HideWhileComposingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var previousIme: String? = null
    private var wasEnabled = false

    @Before
    fun setUp() {
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
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
    }

    @Test
    fun backInTheMiddleOfAWordLeavesTheFieldAsItWas() {
        raiseKeyboardInTryOutField()
        "beijing".forEach(::pressKey)
        device.wait({ fieldText().isNotEmpty() }, 5_000)

        device.pressBack()

        assertEquals("", awaitSettled { fieldText() })
    }

    private fun raiseKeyboardInTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickWhenSettled(By.text(context.getString(SettingsR.string.row_tryout_title)))
        clickWhenSettled(FIELD)
        // The keys only appear once the engine is up.
        requireNotNull(awaitKey(context.getString(ImeR.string.ime_language_to_english))) {
            "Chinese input never became ready; keys: ${keyLabels()}"
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

    /** The try-out field's text from a fresh node; an empty field reports its hint as its text. */
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

    /** The value once two reads half a second apart agree: the editor has applied the keyboard's last edit. */
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

    private fun pressKey(letter: Char) {
        val node = requireNotNull(awaitKey(letter.toString())) { "no $letter key" }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(150)
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
        const val TIMEOUT_MS = 15_000L

        /** A debug reinstall redeploys; a large imported scheme can take minutes before Chinese input is back. */
        const val READY_TIMEOUT_MS = 300_000L
        val FIELD: BySelector = By.clazz("android.widget.EditText")
    }
}

package app.songyan.ime

import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.songyan.ime.settings.R as SettingsR

/** A per-app language chosen while the keyboard process is running reaches the keys without a restart. */
@RunWith(AndroidJUnit4::class)
class AppLocaleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val localeManager = context.getSystemService(LocaleManager::class.java)
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
        localeManager.applicationLocales = LocaleList.forLanguageTags("en")
    }

    @After
    fun tearDown() {
        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
        device.pressBack()
        device.pressBack()
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
    }

    @Test
    fun theKeysFollowAPerAppLanguageChosenWhileTheKeyboardIsRunning() {
        raiseKeyboardInTryOutField()
        assertNotNull("English names: ${keyLabels()}", awaitKey("Space"))

        localeManager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        // Changing the language recreates the settings screen; the keyboard shows again over it.
        raiseKeyboardInTryOutField()

        assertNotNull("Chinese names after the change: ${keyLabels()}", awaitKey("空格"))
    }

    private fun raiseKeyboardInTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val tryOut =
            context
                .createConfigurationContext(
                    android.content.res
                        .Configuration()
                        .apply { setLocales(this@AppLocaleDeviceTest.localeManager.applicationLocales) },
                ).getString(SettingsR.string.row_tryout_title)
        clickWhenSettled(By.text(tryOut))
        clickWhenSettled(FIELD)
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
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val TIMEOUT_MS = 15_000L
        const val READY_TIMEOUT_MS = 300_000L
        val FIELD: BySelector = By.clazz("android.widget.EditText")
    }
}

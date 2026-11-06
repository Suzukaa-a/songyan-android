package app.songyan.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.songyan.ime.settings.R as SettingsR

/** A per-app language chosen while the keyboard is running reaches its own copy without a restart. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class AppLocaleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private var previousIme: String? = null
    private var wasEnabled = false
    private var previousLocales = LocaleList.getEmptyLocaleList()
    private var previousAccessibilityFlags = 0

    @Before
    fun setUp() {
        // The keyboard draws in its own window; only a connection asked for interactive windows
        // reports it, so the chrome reads below can see the keys.
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        previousAccessibilityFlags = serviceInfo.flags
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = serviceInfo
        previousLocales = localeManager.applicationLocales
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
        localeManager.applicationLocales = previousLocales
        device.pressBack()
        device.pressBack()
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set " + quote(it)) }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        serviceInfo.flags = previousAccessibilityFlags
        instrumentation.uiAutomation.serviceInfo = serviceInfo
    }

    @Test
    fun theKeyboardChromeFollowsAPerAppLanguageChosenWhileItIsRunning() {
        localeManager.applicationLocales = LocaleList.forLanguageTags("en-US")
        raiseKeyboardInTryOutField()
        assertNotNull("English chrome: ${keyLabels()}", awaitLabel(ENGLISH_CHROME))

        localeManager.applicationLocales = LocaleList.forLanguageTags("zh-TW")
        // Changing the language recreates the settings screen; the keyboard shows again over it.
        raiseKeyboardInTryOutField()

        assertNotNull("Traditional chrome after the change: ${keyLabels()}", awaitLabel(TRADITIONAL_CHROME))
    }

    /**
     * Every locale of the resource-fallback matrix hits the language it is supposed to. The toolbar's
     * settings entry is worded from the keyboard service's own resources, so this also proves the
     * service reads the per-app locale every time it shows instead of keeping its attach-time one.
     */
    @Test
    fun everyListedLocaleHitsItsLanguageInTheKeyboardChrome() {
        for ((tag, expected) in LOCALE_MATRIX) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
            assertTrue("$tag: the keyboard service follows the app locale", awaitServiceLocale(tag))
            device.pressBack()
            raiseKeyboardInTryOutField()
            assertNotNull("$tag: \"$expected\" in ${keyLabels()}", awaitLabel(expected))
        }
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

    private fun awaitLabel(label: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            keyboardRoot()?.let { find(it, label) }?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    /** The service re-reads the app locale on show; this waits for the change to be visible to it. */
    private fun awaitServiceLocale(tag: String): Boolean {
        val wanted = LocaleList.forLanguageTags(tag).toLanguageTags()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ServiceLocale.wanted(context).toLanguageTags() == wanted) return true
            Thread.sleep(100)
        }
        return false
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

    /**
     * Runs [command] through a real shell. UiDevice's helper starts it without a shell, so quoting
     * and an empty argument are lost (restoring the empty app-locale list would become the literal
     * "und"); stdin to `sh` keeps the command exactly as written.
     */
    private fun shell(command: String): String {
        val descriptors = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { it.appendLine(command) }
        return ParcelFileDescriptor
            .AutoCloseInputStream(descriptors[0])
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }

    /** One shell word, exactly as written. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val TIMEOUT_MS = 15_000L
        const val READY_TIMEOUT_MS = 300_000L

        /** The toolbar's settings entry per language; it differs in all four, so a fallback cannot pass. */
        const val ENGLISH_CHROME = "Keyboard settings"
        const val SIMPLIFIED_CHROME = "键盘设置"
        const val TRADITIONAL_CHROME = "鍵盤設定"
        const val JAPANESE_CHROME = "キーボード設定"

        /** J0.5's per-app locale matrix: every Traditional tag hits zh-Hant, every Hans tag zh-Hans. */
        val LOCALE_MATRIX =
            listOf(
                "en-US" to ENGLISH_CHROME,
                "fr-FR" to ENGLISH_CHROME,
                "zh-CN" to SIMPLIFIED_CHROME,
                "zh-SG" to SIMPLIFIED_CHROME,
                "zh-TW" to TRADITIONAL_CHROME,
                "zh-HK" to TRADITIONAL_CHROME,
                "zh-MO" to TRADITIONAL_CHROME,
                "zh-Hant" to TRADITIONAL_CHROME,
                "ja-JP" to JAPANESE_CHROME,
            )

        val FIELD: BySelector = By.clazz("android.widget.EditText")
    }
}

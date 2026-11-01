package app.songyan.ime.settings

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.songyan.ime.SongyanSettingsActivity
import app.songyan.ime.keyboard.support.SettingsTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The settings activity on a device: StrictMode stays clean, the default keyboard is recognised. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val violations = CopyOnWriteArrayList<String>()
    private var previousIme: String? = null
    private var wasEnabled = false

    private fun str(id: Int) = context.getString(id)

    private fun settingsIntent(target: SettingsTarget? = null) =
        Intent(context, SettingsActivityDeviceTest::class.java.classLoader!!.loadClass(SETTINGS_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (target != null) putExtra(SettingsTarget.EXTRA, target.id) }

    @Before
    fun setUp() {
        File(context.filesDir, "content").deleteRecursively()
        previousIme = defaultKeyboard()
        wasEnabled = shell("ime list -s").lines().contains(KEYBOARD_ID)
    }

    @After
    fun tearDown() {
        previousIme?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
    }

    private fun shell(command: String): String =
        UiDevice.getInstance(instrumentation).executeShellCommand(command).trim()

    private fun defaultKeyboard(): String = shell("settings get secure default_input_method")

    /** Polls until [id] is the current default keyboard; the setting changes asynchronously. */
    private fun awaitDefaultKeyboard(id: String): String {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var current: String
        do {
            current = defaultKeyboard()
            if (current == id) break
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return current
    }

    private fun watchStrictMode() {
        val executor = Executors.newSingleThreadExecutor()
        instrumentation.runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyListener(executor) { violation ->
                        // Only what this app's own code does counts; the platform's own reads are not ours to fix.
                        val trace = violation.stackTraceToString()
                        if (trace.contains("app.songyan.ime")) violations += trace
                    }.build(),
            )
        }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    fun usingTheQuickContentPageDoesNoDiskWorkOnTheMainThread() {
        watchStrictMode()
        ActivityScenario.launch<SongyanSettingsActivity>(settingsIntent(SettingsTarget.ADD_PHRASE)).use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            // The phrase field has the focus; the search term field below it is optional.
            compose.onNode(hasSetTextAction() and isFocused()).performTextInput("严格模式")
            compose.onNode(hasText(str(R.string.quick_content_save)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { has("严格模式") }
            // Flip the clipboard switch: that writes the content file.
            compose.onNode(hasText(str(R.string.quick_content_clipboard_switch)) and hasClickAction()).performClick()
            compose.waitForIdle()
        }
        assertEquals("main-thread violations from the app:\n" + violations.joinToString("\n---\n"), 0, violations.size)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    fun startingTheSettingsActivityDoesNoDiskWorkOnTheMainThread() {
        watchStrictMode()
        ActivityScenario.launch<SongyanSettingsActivity>(settingsIntent()).use {
            compose.waitUntil(10_000) { has(str(R.string.section_keyboard)) }
        }
        assertEquals("main-thread violations from the app:\n" + violations.joinToString("\n---\n"), 0, violations.size)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    fun startingTheKeyboardDoesNoDiskWorkOnTheMainThread() {
        shell("ime enable $KEYBOARD_ID")
        shell("ime set $KEYBOARD_ID")
        assertEquals(KEYBOARD_ID, awaitDefaultKeyboard(KEYBOARD_ID))
        watchStrictMode()
        ActivityScenario.launch<SongyanSettingsActivity>(settingsIntent()).use {
            compose.waitUntil(10_000) { has(str(R.string.row_tryout_title)) }
            compose.onNode(hasText(str(R.string.row_tryout_title)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            // Focusing the field raises the keyboard, which starts the input method service here.
            compose.onNode(hasSetTextAction()).performClick()
            Thread.sleep(KEYBOARD_SETTLE_MS)
        }
        assertEquals("main-thread violations from the app:\n" + violations.joinToString("\n---\n"), 0, violations.size)
    }

    @Test
    fun theDefaultKeyboardIsNeverReportedAsNotTheDefault() {
        val enabled = shell("ime enable $KEYBOARD_ID")
        val selected = shell("ime set $KEYBOARD_ID")
        assertEquals("enable: $enabled set: $selected", KEYBOARD_ID, awaitDefaultKeyboard(KEYBOARD_ID))
        ActivityScenario.launch<SongyanSettingsActivity>(settingsIntent()).use {
            compose.waitUntil(10_000) { has(str(R.string.section_keyboard)) }
            compose.waitForIdle()
            assertFalse(has(str(R.string.home_select_title)))
            assertFalse(has(str(R.string.home_enable_title)))
        }
    }

    @Test
    fun launchingSettingsDoesNotAskForNotificationsBeforeAnythingIsBeingPrepared() {
        ActivityScenario.launch<SongyanSettingsActivity>(settingsIntent()).use {
            compose.waitUntil(10_000) { has(str(R.string.section_keyboard)) }
            val device = UiDevice.getInstance(instrumentation)
            val dialog =
                device.wait(
                    androidx.test.uiautomator.Until
                        .hasObject(By.res(PERMISSION_ALLOW_BUTTON)),
                    2_000,
                )
            assertFalse("the permission dialog appeared at launch", dialog == true)
        }
    }

    @Test
    fun predictiveBackAndPerAppLanguageAreDeclaredInTheInstalledManifest() {
        val manifest = applicationAttributes()
        assertEquals("true", manifest["enableOnBackInvokedCallback"])
        assertTrue("localeConfig missing: $manifest", manifest.containsKey("localeConfig"))
    }

    /** The attributes of the installed manifest's `<application>`, read from the APK itself. */
    private fun applicationAttributes(): Map<String, String> {
        val parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "application") {
                return (0 until parser.attributeCount).associate {
                    parser.getAttributeName(it) to
                        parser.getAttributeValue(it)
                }
            }
        }
        return emptyMap()
    }

    private companion object {
        /** The id Android's input method manager uses; debug builds add `.debug` to the package, not the class. */
        const val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val KEYBOARD_SETTLE_MS = 3_000L
        const val SETTINGS_ACTIVITY = "app.songyan.ime.SongyanSettingsActivity"
        const val PERMISSION_ALLOW_BUTTON = "com.android.permissioncontroller:id/permission_allow_button"
    }
}

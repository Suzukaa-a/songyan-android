package app.songyan.ime.tools

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.songyan.ime.SongyanApplication
import app.songyan.ime.SongyanSettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import app.songyan.ime.ime.R as ImeR
import app.songyan.ime.settings.R as SettingsR

/**
 * The real keyboard in a real editor: a tool hands text to another app through the relay, and the
 * answer only lands in the field it came from when the input method is explicitly told to apply it.
 */
@RunWith(AndroidJUnit4::class)
class ProcessTextRelayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val testPackage = instrumentation.context.packageName
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
            check(SystemClock.elapsedRealtime() < deadline) { "$KEYBOARD_ID never became the keyboard" }
            Thread.sleep(100)
        }
        instrumentation.uiAutomation.serviceInfo =
            instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        // The keyboard process may read the clipboard; the test reads it the same way.
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        clipboard().clearPrimaryClip()
        main2 { ProcessTextRelay.dismissPending() }
    }

    @After
    fun tearDown() {
        main2 { ProcessTextRelay.dismissPending() }
        device.pressHome()
        shell("am force-stop $testPackage")
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    /** The answer arrives while the editor is still behind the other app; applying writes it back. */
    @Test
    fun aResultIsWrittenBackOnlyWhenItIsApplied() {
        openHost(identify = true)
        val layout = first(app().keyboardPreferences.layoutId)
        val schema = first(app().keyboardPreferences.activeSchemaId)
        assertEquals(ProcessTextHostActivity.FIRST_TEXT, hostText(0))

        assertTrue("the tool was refused", requestTool(FakeProcessTextActivity::class.java))

        val pending =
            awaitPending() ?: failState(
                "no answer came back",
                shell("dumpsys activity activities | grep -E 'ProcessTextTargetActivity|FakeProcessText' | head -8"),
            )
        assertEquals("HELLO WORLD", pending.text)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }
        assertEquals("an answer must not write itself", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
        assertNull("the answer was copied without being applied", clipboardText())

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the answer was not written back", awaitHostText("HELLO WORLD"))
        assertEquals("the layout changed", layout, first(app().keyboardPreferences.layoutId))
        assertEquals("the input scheme changed", schema, first(app().keyboardPreferences.activeSchemaId))
    }

    /** Only the selected word is replaced in place, not the whole paragraph before the caret. */
    @Test
    fun aSelectedTextIsReplacedInPlace() {
        openHost(identify = true)
        selectHello()

        assertTrue("the tool was refused", requestTool(FakeProcessTextActivity::class.java))
        val pending = requireNotNull(awaitPending()) { "no answer came back" }
        assertEquals("the source text changed", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
        assertEquals("the selection was not handed over", ProcessTextHostActivity.SELECTION, pending.request.text)
        assertEquals(ProcessTextKind.SELECTION, pending.request.kind)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the selection was not replaced: " + hostText(0), awaitHostText("HELLO world"))
    }

    /** The app gets the text the panel picked, not whatever the editor happens to select. */
    @Test
    fun theChosenSourceIsWhatTheAppReceives() {
        openHost(identify = true)
        selectHello()

        val clip = ProcessTextSource("copied", ProcessTextKind.CLIPBOARD)
        assertTrue("the tool was refused", requestTool(FakeProcessTextActivity::class.java, clip))
        val pending = requireNotNull(awaitPending()) { "no answer came back" }
        assertEquals("the selection was handed over instead", "copied", pending.request.text)
        assertEquals("COPIED", pending.text)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        // A clipboard source was never in the field, so applying copies and writes nothing.
        assertEquals(ProcessTextOutcome.COPIED, main2 { ProcessTextRelay.applyPending() })
        assertEquals("the field was written to", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
    }

    /** An empty answer over a selection leaves the rest of the field as it was. */
    @Test
    fun anEmptyAnswerReplacesTheSelection() {
        openHost(identify = true)
        selectHello()

        assertTrue("the tool was refused", requestTool(EmptyProcessTextActivity::class.java))
        val pending = requireNotNull(awaitPending()) { "no answer came back" }
        assertEquals("", pending.text)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the selection was not emptied: " + hostText(0), awaitHostText(" world"))
    }

    /** An ordinary editor with a real view id is named by the platform and can be written back. */
    @Test
    fun aPlainHostFieldIsNamedByTheSystem() {
        openHost()
        assertTrue("the tool was refused", requestTool(FakeProcessTextActivity::class.java))
        val pending = requireNotNull(awaitPending()) { "no answer came back" }
        Log.i(TAG, "plain host fieldId=" + pending.request.fieldId)
        assertEquals(ProcessTextHostActivity.FIELD_FIRST, pending.request.fieldId)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the answer was not written back", awaitHostText("HELLO WORLD"))
    }

    /** An editor with no field id of its own cannot be confirmed, so the answer is only copied. */
    @Test
    fun anEditorWithoutAFieldIdIsOnlyCopied() {
        openHost(unnamed = true)
        assertTrue("the tool was refused", requestTool(FakeProcessTextActivity::class.java))
        val pending = requireNotNull(awaitPending()) { "no answer came back" }
        assertTrue("fieldId=" + pending.request.fieldId, pending.request.fieldId <= 0)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.COPIED, main2 { ProcessTextRelay.applyPending() })
        assertEquals("the answer was not copied", "HELLO WORLD", clipboardText())
        assertEquals("the field was written to", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
    }

    /** A tool only handles text already in the field: a composition is left exactly as it is. */
    @Test
    fun aComposingEditorRefusesTheRequestAndKeepsTheComposition() {
        openHost(identify = true)
        "beijing".forEach(::pressKey)
        assertTrue("the spelling never reached the field", await { hostText(0) != ProcessTextHostActivity.FIRST_TEXT })
        val composing = hostText(0)

        assertFalse("a tool must not run over a composition", requestTool(FakeProcessTextActivity::class.java))
        assertEquals("the composition changed", composing, hostText(0))
        assertNull(main2 { ProcessTextRelay.pendingResult() })
        requireNotNull(awaitKeyboardShown()) { "the composition lost the keyboard" }
    }

    /** A different field retires the answer without changing text or the clipboard. */
    @Test
    fun aResultForAnotherFieldOfTheSameAppIsRetired() {
        openHost(identify = true)
        assertTrue(requestTool(FakeProcessTextActivity::class.java))
        requireNotNull(awaitPending()) { "no answer came back" }

        tapSecondField()

        assertTrue("the answer stayed waiting for another field", awaitPendingCleared())
        assertNull("the answer was copied without an explicit action", clipboardText())
        assertEquals("the other field was written to", ProcessTextHostActivity.SECOND_TEXT, hostText(1))
        assertEquals("the first field changed", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
    }

    /** A field of a different app is not written to either. */
    @Test
    fun aResultForAnotherAppIsRetired() {
        openHost(identify = true)
        assertTrue(requestTool(FakeProcessTextActivity::class.java))
        requireNotNull(awaitPending()) { "no answer came back" }

        openSettingsTryOutField()

        assertTrue("the answer stayed waiting for another app", awaitPendingCleared())
        assertNull("the answer was copied without an explicit action", clipboardText())
        assertNull(main2 { ProcessTextRelay.pendingResult() })
    }

    /** A password field never runs a tool, and nothing of it leaves. */
    @Test
    fun aPasswordFieldRefusesTheRequest() {
        openHost(password = true)
        assertFalse("a tool must not run in a password field", requestTool(FakeProcessTextActivity::class.java))
        assertNull(main2 { ProcessTextRelay.pendingResult() })
        assertNull("something was copied from a password field", clipboardText())
        assertNotNull("the field text changed", awaitNode(testPackage, ProcessTextHostActivity.FIRST_TEXT))
    }

    /** A field that asked for no personalized learning never runs a tool either. */
    @Test
    fun aNoLearningFieldRefusesTheRequest() {
        openHost(noLearning = true)
        assertFalse("a tool must not run in a no-learning field", requestTool(FakeProcessTextActivity::class.java))
        assertNull(main2 { ProcessTextRelay.pendingResult() })
        assertNull("something was copied from a no-learning field", clipboardText())
        assertNotNull("the field text changed", awaitNode(testPackage, ProcessTextHostActivity.FIRST_TEXT))
    }

    /** The opened app has an editor of its own; taking the keyboard for a moment is not a new target. */
    @Test
    fun anAppWithItsOwnEditorDoesNotTakeTheAnswer() {
        openHost(identify = true)
        assertTrue(requestTool(SelfEditingProcessTextActivity::class.java))

        val pending = requireNotNull(awaitPending(15_000)) { "no answer came back" }
        assertEquals("HELLO WORLD", pending.text)
        assertNull("the answer was copied while the other app was open", clipboardText())
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the answer was not written back", awaitHostText("HELLO WORLD"))
    }

    /**
     * A request cancelled while its other app is still open must not swallow the next one: the new
     * relay has to launch and answer on its own, and the old callback must not count.
     */
    @Test
    fun aNewRequestRunsItsOwnRelayAfterTheOldOneWasDismissed() {
        openHost(identify = true)
        assertTrue("the old request was refused", requestTool(PausedProcessTextActivity::class.java))
        // The user leaves the other app and cancels the request while it is still waiting.
        device.pressHome()
        main2 { ProcessTextRelay.dismissPending() }
        assertNull("a dismissed request still had an answer", main2 { ProcessTextRelay.pendingResult() })

        bringHostBack()
        assertTrue("the new request was refused", requestTool(LowercaseProcessTextActivity::class.java))
        val pending = awaitPending(8_000) ?: failState("the new relay never answered", relayState())
        assertTrue(
            "the new answer did not come from the new app: " + pending.text,
            pending.text.startsWith(LOWERCASE_MARK),
        )
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back" }

        assertEquals(ProcessTextOutcome.REPLACED, main2 { ProcessTextRelay.applyPending() })
        assertTrue("the new answer was not written back", awaitHostText("lower:hello world"))

        // The old app is still running and answers late; that answer must not reach the field.
        SystemClock.sleep(6_000)
        assertNull("the old answer polluted the request", main2 { ProcessTextRelay.pendingResult() })
        assertEquals("the old answer polluted the field", "lower:hello world", hostText(0))
        assertTrue("an old relay task stayed behind: " + relayTasks(), await(5_000) { relayTasks().isEmpty() })
    }

    /**
     * Android 11+ only shows the other packages that the manifest queries for, and only apps this
     * app may call: exported, and any permission they ask for is one this app holds.
     */
    @Test
    fun theRelayCanSeeTheAppThatHandlesText() {
        val available = main2 { ProcessTextTargets.available(context) }
        val visible = available.map { it.activityInfo.packageName + "/" + it.activityInfo.name }
        assertTrue(
            "the text app is not visible: " + visible,
            visible.contains(testPackage + "/" + FakeProcessTextActivity::class.java.name),
        )
        available.forEach { info ->
            assertTrue(
                "an app that cannot be started is offered: " + info.activityInfo.name,
                info.activityInfo.exported,
            )
            val permission = info.activityInfo.permission
            assertTrue(
                "an app that needs a permission this app does not hold is offered: " + info.activityInfo.name,
                permission.isNullOrEmpty() ||
                    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    private fun app() = context.applicationContext as SongyanApplication

    private fun <T> first(flow: kotlinx.coroutines.flow.Flow<T>): T = runBlocking { flow.first() }

    private fun openHost(
        identify: Boolean = false,
        unnamed: Boolean = false,
        password: Boolean = false,
        noLearning: Boolean = false,
    ) {
        // A clean start, the way a user opens the app; nothing of an earlier test can interfere.
        device.pressHome()
        shell("am force-stop $testPackage")
        val extras =
            buildString {
                if (identify) append(" --ez " + ProcessTextHostActivity.EXTRA_IDENTIFY + " true")
                if (unnamed) append(" --ez " + ProcessTextHostActivity.EXTRA_UNNAMED + " true")
                if (password) append(" --ez " + ProcessTextHostActivity.EXTRA_PASSWORD + " true")
                if (noLearning) append(" --ez " + ProcessTextHostActivity.EXTRA_NO_LEARNING + " true")
            }
        val started = shell("am start -W -n $testPackage/app.songyan.ime.tools.ProcessTextHostActivity$extras")
        val editor =
            awaitNode(testPackage, ProcessTextHostActivity.FIRST_TEXT)
                ?: failState("the editor never appeared", started)
        // What a user does: tap the field; the keyboard then has to show up for it.
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        awaitKeyboardShown() ?: failState("the keyboard never appeared", started)
    }

    /** Brings the already running editor app back, the way a user returns to it; no process starts. */
    private fun bringHostBack() {
        shell("am start -n " + testPackage + "/app.songyan.ime.tools.ProcessTextHostActivity")
        val editor =
            awaitNode(testPackage, ProcessTextHostActivity.FIRST_TEXT)
                ?: failState("the editor did not come back", relayState())
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        awaitKeyboardShown() ?: failState("the keyboard did not come back", imeState())
    }

    /** A native action of the host app, used the way its user would: click its button. */
    private fun selectHello() {
        val button =
            requireNotNull(awaitNode(testPackage, ProcessTextHostActivity.SELECT_HELLO)) {
                "the select button did not appear"
            }
        assertTrue("the select button did not work", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("the keyboard left after selecting", awaitKeyboardShown() != null)
    }

    /** Keeps the state a flaky run needs: the launch output, the focus, the IME and the screen. */
    private fun failState(
        message: String,
        started: String,
    ): Nothing {
        val ui = File(context.cacheDir, "relay-failure.xml")
        runCatching { device.dumpWindowHierarchy(ui) }
        val focus = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4")
        val texts = runCatching { ui.readText() }.getOrDefault("").take(4000)
        throw AssertionError(
            message + "\nstart=" + started + "\nfocus=" + focus + "\nime=" + imeState() + "\nui=" + texts,
        )
    }

    private fun requestTool(
        activity: Class<*>,
        source: ProcessTextSource? = null,
    ): Boolean {
        val target = ComponentName(testPackage, activity.name)
        return main2 { ProcessTextRelay.request(context, target, source = source) }
    }

    private fun tapSecondField() {
        val node =
            requireNotNull(awaitNode(testPackage, ProcessTextHostActivity.SECOND_TEXT)) {
                "the second field did not appear"
            }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue("the second field did not take the click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun openSettingsTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        clickWhenSettled(By.text(context.getString(SettingsR.string.row_tryout_title)))
        clickWhenSettled(By.clazz(EDIT_TEXT_CLASS))
    }

    private fun awaitPending(timeoutMs: Long = TIMEOUT_MS): ProcessTextResult? {
        var result: ProcessTextResult? = null
        await(timeoutMs) {
            main2 { result = ProcessTextRelay.pendingResult() }
            result != null
        }
        return result
    }

    private fun awaitPendingCleared(): Boolean = await { main2 { ProcessTextRelay.pendingResult() } == null }

    private fun clipboard(): ClipboardManager = requireNotNull(context.getSystemService(ClipboardManager::class.java))

    private fun clipboardText(): String? =
        clipboard()
            .primaryClip
            ?.takeIf {
                it.itemCount > 0
            }?.getItemAt(0)
            ?.text
            ?.toString()

    private fun hostTexts(): List<String> = rootsOf(testPackage).flatMap { textsOf(it, EDIT_TEXT_CLASS) }

    private fun hostText(index: Int): String = hostTexts().getOrElse(index) { "" }

    private fun awaitHostText(expected: String): Boolean = await { hostText(0) == expected }

    private fun await(
        durationMs: Long = TIMEOUT_MS,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /**
     * The keyboard window is back. An answer from another app is previewed by the tools panel, which
     * covers the key area, so the panel's own close button marks the window as shown there too.
     */
    private fun awaitKeyboardShown(): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var found: AccessibilityNodeInfo? = null
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            found = awaitKey("q") ?: awaitKey(context.getString(ImeR.string.ime_panel_close))
            if (found == null) Thread.sleep(POLL_MS)
        }
        return found
    }

    /** What a lost answer needs: whether it already reached the clipboard, and what still runs. */
    private fun relayState(): String =
        "clipboard=" + clipboardText() +
            " / activities=" +
            relayTasks() +
            " / host=" + shell("dumpsys activity activities").lines().count { it.contains("ProcessTextHostActivity") }

    private fun relayTasks(): String =
        shell(
            "dumpsys activity activities",
        ).lines().filter { it.contains("ProcessTextTargetActivity") }.joinToString("\n")

    private fun imeState(): String =
        shell("dumpsys input_method | grep -E 'mInputShown|mShowRequested|mCurId|mCurFocusedWindow'") + " / " +
            shell("dumpsys window windows | grep -E 'InputMethod|songyan' | head -6")

    /** Window content of one app; UiDevice's own queries only look at the window on top. */
    private fun rootsOf(packageName: String): List<AccessibilityNodeInfo> =
        instrumentation.uiAutomation.windows
            .mapNotNull { it.root }
            .filter { it.packageName == packageName }

    private fun textsOf(
        root: AccessibilityNodeInfo,
        className: String,
    ): List<String> {
        val out = mutableListOf<String>()
        walk(root) { node -> if (node.className == className) out += node.text?.toString().orEmpty() }
        return out
    }

    private fun awaitNode(
        packageName: String,
        label: String,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            findLabeled(packageName, label)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return findLabeled(packageName, label)
    }

    private fun findLabeled(
        packageName: String,
        label: String,
    ): AccessibilityNodeInfo? {
        for (root in rootsOf(packageName)) {
            var found: AccessibilityNodeInfo? = null
            walk(root) { node ->
                if (found == null &&
                    node.isVisibleToUser &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label)
                ) {
                    found = node
                }
            }
            if (found != null) return found
        }
        return null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, visit) }
    }

    private fun pressKey(label: Char) {
        val node = requireNotNull(awaitKey(label.toString())) { "no " + label + " key" }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(150)
    }

    private fun awaitKey(label: String): AccessibilityNodeInfo? = keyboardRoot()?.let { find(it, label) }

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

    /** The settings screen redraws while a deploy finishes, which can replace a node mid-click. */
    private fun clickWhenSettled(selector: BySelector) {
        repeat(5) {
            val node =
                requireNotNull(device.wait(Until.findObject(selector), TIMEOUT_MS)) {
                    selector.toString() +
                        " did not appear"
                }
            try {
                node.click()
                return
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError(selector.toString() + " kept being replaced")
    }

    private fun shell(command: String): String = device.executeShellCommand(command).trim()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> main2(block: () -> T): T {
        var result: T? = null
        main { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        val KEYBOARD_ID = "app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService"
        const val EDIT_TEXT_CLASS = "android.widget.EditText"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val TAG = "ProcessTextRelayTest"
        const val LOWERCASE_MARK = "lower:"
    }
}

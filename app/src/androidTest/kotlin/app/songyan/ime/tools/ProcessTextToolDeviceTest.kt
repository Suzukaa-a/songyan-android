package app.songyan.ime.tools

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.songyan.ime.content.BuiltinToolDefinitions
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.ToolStore
import app.songyan.ime.settings.state.toolNameTags
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

/**
 * The tools panel against a real app that handles text.
 *
 * A test activity stands in for a translation app: it answers ACTION_PROCESS_TEXT with the text in
 * capitals and nothing else. The panel lists it only when the user turned it on in settings, the
 * answer is only ever previewed, and applying it goes through the real relay — the same code the
 * keyboard runs with any installed app. Nothing here fakes the engine or the relay.
 */
@RunWith(AndroidJUnit4::class)
class ProcessTextToolDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val testPackage = instrumentation.context.packageName
    private lateinit var apps: ProcessTextToolApps
    private lateinit var speechApps: ProcessTextToolApps

    private var previousIme: String? = null
    private var previousClipboard: ClipData? = null
    private var previousEnabled: Set<String> = emptySet()
    private var previousSpeechEnabled: Set<String> = emptySet()
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
        previousClipboard = clipboard().primaryClip
        clipboard().clearPrimaryClip()
        main2 { ProcessTextRelay.dismissPending() }
        apps = ProcessTextToolApps(context, File(context.filesDir, ToolStore.DIRECTORY_NAME))
        previousEnabled = runBlocking { apps.enabled() }
        // Every case starts with none of the stand-in apps turned on, the way a fresh install is.
        clearEnabled()
        val toolsDir = File(context.filesDir, ToolStore.DIRECTORY_NAME)
        speechApps = ProcessTextToolApps(context, toolsDir, ToolAppKind.SPEECH)
        previousSpeechEnabled = runBlocking { speechApps.enabled() }
        clearSpeechEnabled()
    }

    @After
    fun tearDown() {
        main2 { ProcessTextRelay.dismissPending() }
        if (::apps.isInitialized) {
            clearEnabled()
            for (activity in listOf(FakeProcessTextActivity::class.java, CancelledProcessTextActivity::class.java)) {
                val component = componentOf(activity).flattenToString()
                if (component in previousEnabled) runBlocking { apps.setEnabled(component, true) }
            }
        }
        if (::speechApps.isInitialized) {
            clearSpeechEnabled()
            for (activity in SPEECH_APPS) {
                val component = componentOf(activity).flattenToString()
                if (component in previousSpeechEnabled) runBlocking { speechApps.setEnabled(component, true) }
            }
        }
        previousClipboard?.let { clipboard().setPrimaryClip(it) } ?: clipboard().clearPrimaryClip()
        device.pressHome()
        shell("am force-stop $testPackage")
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    /**
     * Selecting a word and picking the other app end to end: the answer is previewed, applying it
     * replaces exactly the selected word, and nothing is written before the user asks for it.
     */
    @Test
    fun aSelectedWordIsReplacedOnlyAfterTheAnswerIsApplied() {
        enable(FakeProcessTextActivity::class.java)
        assertEquals(
            "the enabled app was not stored",
            setOf(componentOf(FakeProcessTextActivity::class.java).flattenToString()),
            runBlocking { apps.enabled() },
        )
        openHost(identify = true)
        selectHello()
        openToolsPanel(ProcessTextHostActivity.SELECTION, labelOf(FakeProcessTextActivity::class.java))
        tapImeLabel(labelOf(FakeProcessTextActivity::class.java))

        val preview = context.getString(ImeR.string.ime_tools_result_label, "HELLO")
        requireNotNull(awaitImeLabel(preview)) { "the answer was never previewed: " + imeLabels() }
        assertEquals(
            "the answer was written without the user applying it",
            ProcessTextHostActivity.FIRST_TEXT,
            hostText(0),
        )
        assertNotNull("the answer stopped waiting", main2 { ProcessTextRelay.pendingResult() })
        assertNull("the answer was copied without being applied", clipboardText())

        tapImeLabel(context.getString(ImeR.string.ime_tools_replace))
        assertTrue("the answer was not written back: " + hostTexts(), awaitHostText("HELLO world"))
        assertNull("the answer still waits after it was applied", main2 { ProcessTextRelay.pendingResult() })
    }

    /** Nothing is offered until the user turns the app on, and turning it off takes it back. */
    @Test
    fun anAppIsListedOnlyAfterTheUserTurnsItOn() {
        openHost()
        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT)
        // The built-in tools are the panel's other content: once they are listed, the app list was
        // read too, so a missing entry means the user never turned it on.
        assertTrue(
            "the tool list never loaded: " + imeLabels(),
            await { imeLabels().any { it in builtinToolLabels() } },
        )
        assertFalse(
            "an app the user never turned on was listed",
            imeLabels().contains(labelOf(FakeProcessTextActivity::class.java)),
        )

        enable(FakeProcessTextActivity::class.java)
        tapImeLabel(context.getString(ImeR.string.ime_panel_close))
        assertTrue(
            "the panel did not close",
            await(5_000) { findImeLabel(context.getString(ImeR.string.ime_panel_close)) == null },
        )

        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT, labelOf(FakeProcessTextActivity::class.java))
        assertTrue(
            "the app the user turned on was not listed: " + imeLabels(),
            imeLabels().contains(labelOf(FakeProcessTextActivity::class.java)),
        )
    }

    /**
     * Moving to another field retires the answer; neither an editor nor the clipboard may change
     * without the user choosing a result action.
     */
    @Test
    fun anAnswerForAnotherFieldDoesNotWriteOrCopy() {
        enable(FakeProcessTextActivity::class.java)
        openHost(identify = true)
        selectHello()
        openToolsPanel(ProcessTextHostActivity.SELECTION, labelOf(FakeProcessTextActivity::class.java))
        tapImeLabel(labelOf(FakeProcessTextActivity::class.java))
        requireNotNull(awaitImeLabel(context.getString(ImeR.string.ime_tools_result_label, "HELLO"))) {
            "the answer was never previewed: " + imeLabels()
        }

        tapSecondField()

        assertTrue(
            "the old answer still waits for another field",
            await(10_000) {
                main2 {
                    ProcessTextRelay.pendingResult()
                } == null
            },
        )
        assertNull("changing fields must not copy an answer without an explicit action", clipboardText())
        assertEquals("the second field was written to", ProcessTextHostActivity.SECOND_TEXT, hostText(1))
        assertEquals("the first field changed", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
    }

    /** An app that closes without an answer writes nothing and copies nothing. */
    @Test
    fun aCancelledAppWritesNothing() {
        enable(CancelledProcessTextActivity::class.java)
        openHost(identify = true)
        selectHello()
        openToolsPanel(
            ProcessTextHostActivity.SELECTION,
            labelOf(CancelledProcessTextActivity::class.java),
        )
        tapImeLabel(labelOf(CancelledProcessTextActivity::class.java))

        // The other app closed at once, so the keyboard comes back to the editor.
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not come back: " + imeLabels() }
        assertNull("a cancelled request still waits", main2 { ProcessTextRelay.pendingResult() })
        assertNull("a cancelled request copied something", clipboardText())
        assertEquals("the field was written to", ProcessTextHostActivity.FIRST_TEXT, hostText(0))
        assertTrue(
            "a cancelled answer was previewed: " + imeLabels(),
            imeLabels().none { it.startsWith(resultPrefix()) },
        )
        assertNull(
            "a cancelled answer offered a write",
            findImeLabel(context.getString(ImeR.string.ime_tools_replace)),
        )
    }

    /** A password field offers neither the tools entry nor a way to hand its text to another app. */
    @Test
    fun anAppIsHiddenInPasswordFields() {
        enable(FakeProcessTextActivity::class.java)
        openHost(password = true)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not appear: " + imeLabels() }
        assertNull(
            "the tools entry is offered in a password field",
            findImeLabel(context.getString(ImeR.string.ime_tools_title)),
        )
        assertNull("a tool request was prepared in a password field", main2 { ProcessTextRelay.pendingResult() })
        assertNull("something was copied from a password field", clipboardText())
    }

    /** A speech app is a tool of its own: it is listed only once the user turns it on. */
    @Test
    fun aSpeechAppIsListedOnlyAfterTheUserTurnsItOn() {
        openHost()
        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT)
        assertTrue(
            "the tool list never loaded: " + imeLabels(),
            await { imeLabels().any { it in builtinToolLabels() } },
        )
        assertFalse(
            "a speech app the user never turned on was listed",
            imeLabels().contains(labelOf(FakeSpeechActivity::class.java)),
        )

        enableSpeech(FakeSpeechActivity::class.java)
        tapImeLabel(context.getString(ImeR.string.ime_panel_close))
        assertTrue(
            "the panel did not close",
            await(5_000) { findImeLabel(context.getString(ImeR.string.ime_panel_close)) == null },
        )

        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT, labelOf(FakeSpeechActivity::class.java))
    }

    /**
     * A speech app needs no source text. Its first recognition is only previewed; the one way to
     * write it is inserting it at the caret, and nothing is written before the user asks.
     */
    @Test
    fun aTranscriptIsPreviewedAndOnlyInsertedWhenTheUserChoosesIt() {
        enableSpeech(FakeSpeechActivity::class.java)
        openHost(identify = true)
        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT, labelOf(FakeSpeechActivity::class.java))
        tapImeLabel(labelOf(FakeSpeechActivity::class.java))

        val preview = context.getString(ImeR.string.ime_tools_result_label, FakeSpeechActivity.TRANSCRIPT)
        requireNotNull(awaitImeLabel(preview)) { "the transcript was never previewed: " + imeLabels() }
        assertEquals(
            "the transcript was written without the user inserting it",
            ProcessTextHostActivity.FIRST_TEXT,
            hostText(0),
        )
        assertNull("the transcript was copied without being chosen", clipboardText())
        assertNull(
            "a transcript offered to replace text it never came from",
            findImeLabel(context.getString(ImeR.string.ime_tools_replace)),
        )

        tapImeLabel(context.getString(ImeR.string.ime_tools_insert))
        assertTrue(
            "the transcript was not inserted at the caret: " + hostTexts(),
            await { hostText(0) == ProcessTextHostActivity.FIRST_TEXT + FakeSpeechActivity.TRANSCRIPT },
        )
        assertNull("the transcript still waits after it was inserted", main2 { ProcessTextRelay.pendingResult() })
    }

    /** The request carries a free-form model and the input language, and nothing else. */
    @Test
    fun theRecognizerIsAskedForFreeFormTextInTheInputLanguageOnly() {
        enableSpeech(EchoSpeechActivity::class.java)
        openHost(identify = true)
        openToolsPanel(ProcessTextHostActivity.FIRST_TEXT, labelOf(EchoSpeechActivity::class.java))
        tapImeLabel(labelOf(EchoSpeechActivity::class.java))

        // The language is the keyboard's current input language, in the tag form recognizers know.
        val expected =
            listOf("zh-CN", "zh-TW", "ja-JP").map {
                context.getString(ImeR.string.ime_tools_result_label, "model=free_form;lang=$it;extras=2")
            }
        assertTrue(
            "the request was not echoed as expected: " + imeLabels(),
            await { expected.any { findImeLabel(it) != null } },
        )
    }

    /** Speech apps are tools too: a password field offers none of them. */
    @Test
    fun aSpeechAppIsHiddenInPasswordFields() {
        enableSpeech(FakeSpeechActivity::class.java)
        openHost(password = true)
        requireNotNull(awaitKeyboardShown()) { "the keyboard did not appear: " + imeLabels() }
        assertNull(
            "the tools entry is offered in a password field",
            findImeLabel(context.getString(ImeR.string.ime_tools_title)),
        )
        assertNull(
            "a speech request was prepared in a password field",
            main2 { ProcessTextRelay.pendingResult() },
        )
    }

    private fun componentOf(activity: Class<*>): ComponentName = ComponentName(testPackage, activity.name)

    private fun labelOf(activity: Class<*>): String {
        val manager = context.packageManager

        @Suppress("DEPRECATION")
        val info = manager.getActivityInfo(componentOf(activity), 0)
        return info.loadLabel(manager).toString()
    }

    /** The names the panel draws for the built-in examples in the device's languages. */
    private fun builtinToolLabels(): Set<String> =
        BuiltinToolDefinitions
            .all(OpenCCToolConfig.S2T)
            .map { it.displayName(toolNameTags(context.resources.configuration.locales)) }
            .toSet()

    private fun resultPrefix(): String = context.getString(ImeR.string.ime_tools_result_label, "").trim()

    private fun enable(activity: Class<*>) {
        runBlocking { apps.setEnabled(componentOf(activity).flattenToString(), true) }
    }

    private fun enableSpeech(activity: Class<*>) {
        runBlocking { speechApps.setEnabled(componentOf(activity).flattenToString(), true) }
    }

    private fun clearSpeechEnabled() {
        for (activity in SPEECH_APPS) {
            runBlocking { speechApps.setEnabled(componentOf(activity).flattenToString(), false) }
        }
    }

    private fun clearEnabled() {
        for (activity in STAND_IN_APPS) {
            runBlocking { apps.setEnabled(componentOf(activity).flattenToString(), false) }
        }
    }

    private fun openHost(
        identify: Boolean = false,
        password: Boolean = false,
    ) {
        device.pressHome()
        shell("am force-stop $testPackage")
        val extras =
            buildString {
                if (identify) append(" --ez " + ProcessTextHostActivity.EXTRA_IDENTIFY + " true")
                if (password) append(" --ez " + ProcessTextHostActivity.EXTRA_PASSWORD + " true")
            }
        val started = shell("am start -W -n $testPackage/app.songyan.ime.tools.ProcessTextHostActivity$extras")
        val editor =
            awaitNode(testPackage, ProcessTextHostActivity.FIRST_TEXT)
                ?: failState("the editor never appeared", started)
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        awaitKeyboardShown() ?: failState("the keyboard never appeared", started)
    }

    /** The panel shows the captured source at once; its lists arrive from the app right after. */
    private fun openToolsPanel(
        source: String,
        waitForAppLabel: String? = null,
    ) {
        tapImeLabel(context.getString(ImeR.string.ime_tools_title))
        requireNotNull(awaitImeLabel(context.getString(ImeR.string.ime_tools_source_label, source))) {
            "the tools panel did not open: " + imeLabels()
        }
        if (waitForAppLabel != null) {
            requireNotNull(awaitImeLabel(waitForAppLabel)) { "the other app was not listed: " + imeLabels() }
        }
    }

    private fun selectHello() {
        val button =
            requireNotNull(awaitNode(testPackage, ProcessTextHostActivity.SELECT_HELLO)) {
                "the select button did not appear"
            }
        assertTrue("the select button did not work", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("the keyboard left after selecting", awaitKeyboardShown() != null)
    }

    private fun tapSecondField() {
        val node =
            requireNotNull(awaitNode(testPackage, ProcessTextHostActivity.SECOND_TEXT)) {
                "the second field did not appear"
            }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue("the second field did not take the click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun failState(
        message: String,
        started: String,
    ): Nothing {
        val ui = File(context.cacheDir, "process-text-tool-failure.xml")
        runCatching { device.dumpWindowHierarchy(ui) }
        val focus = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4")
        val texts = runCatching { ui.readText() }.getOrDefault("").take(4000)
        throw AssertionError(
            message + "\nstart=" + started + "\nfocus=" + focus + "\nime=" + imeState() + "\nui=" + texts,
        )
    }

    private fun imeState(): String =
        shell("dumpsys input_method | grep -E 'mInputShown|mShowRequested|mCurId|mCurFocusedWindow'") + " / " +
            shell("dumpsys window windows | grep -E 'InputMethod|songyan' | head -6")

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

    private fun clipboard(): ClipboardManager = requireNotNull(context.getSystemService(ClipboardManager::class.java))

    private fun clipboardText(): String? =
        clipboard()
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /** The keyboard window together with one of its keys, the way a user sees it. */
    private fun awaitKeyboardShown(): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var key: AccessibilityNodeInfo? = null
        while (key == null && SystemClock.elapsedRealtime() < deadline) {
            key = awaitKey("q")
            if (key == null) Thread.sleep(POLL_MS)
        }
        return key
    }

    private fun awaitKey(label: String): AccessibilityNodeInfo? = keyboardRoot()?.let { find(it, label) }

    private fun keyboardRoot(): AccessibilityNodeInfo? =
        runCatching {
            instrumentation.uiAutomation.windows
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.root
        }.getOrNull()

    /** Every label the keyboard window exposes: the toolbar, a panel's cells and its bottom keys. */
    private fun imeLabels(): List<String> {
        val out = mutableListOf<String>()
        keyboardRoot()?.let { root -> walk(root) { node -> node.contentDescription?.toString()?.let { out += it } } }
        return out
    }

    private fun findImeLabel(label: String): AccessibilityNodeInfo? = keyboardRoot()?.let { find(it, label) }

    private fun awaitImeLabel(
        label: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            findImeLabel(label)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return findImeLabel(label)
    }

    private fun tapImeLabel(label: String) {
        val node = requireNotNull(awaitImeLabel(label)) { label + " did not appear: " + imeLabels() }
        assertTrue(label + " did not take the click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun find(
        node: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == label && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child, label)?.let { return it } }
        return null
    }

    /** Window content of one app; UiDevice's own queries only look at the window on top. */
    private fun rootsOf(packageName: String): List<AccessibilityNodeInfo> =
        runCatching {
            instrumentation.uiAutomation.windows
                .mapNotNull { it.root }
                .filter { it.packageName == packageName }
        }.getOrDefault(emptyList())

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

        /** The stand-in speech apps; each is off when a case starts. */
        val SPEECH_APPS: List<Class<*>> = listOf(FakeSpeechActivity::class.java, EchoSpeechActivity::class.java)

        /** Every stand-in text app this test may turn on; each is off when a case starts. */
        val STAND_IN_APPS: List<Class<*>> =
            listOf(
                FakeProcessTextActivity::class.java,
                EmptyProcessTextActivity::class.java,
                SelfEditingProcessTextActivity::class.java,
                PausedProcessTextActivity::class.java,
                LowercaseProcessTextActivity::class.java,
                CancelledProcessTextActivity::class.java,
            )
    }
}

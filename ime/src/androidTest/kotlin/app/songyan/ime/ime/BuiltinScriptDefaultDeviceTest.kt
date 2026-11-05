package app.songyan.ime.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a fresh emulator with the dedicated benchmarkRelease app installed: first deployment
 * must persist the app language's script choice, and real keyboard touches must commit its output.
 *
 * Opt in with -Pandroid.testInstrumentationRunnerArguments.songyan.bootstrap.target=app.songyan.ime.benchmark.
 * The test preserves existing app data, so repeat first-install runs require a new emulator.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class BuiltinScriptDefaultDeviceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private val automation: UiAutomation = instrumentation.uiAutomation
    private val target = InstrumentationRegistry.getArguments().getString(ARG_TARGET).orEmpty()

    private var previousIme: String? = null
    private var previousLocale = ""
    private var previousPerfTag = ""
    private var previousErrorDialogs = "null"
    private var previousAccessibilityFlags = 0
    private var prepared = false

    @Before
    fun setUp() {
        // A fresh emulator supplies the first-install state without clearing any installed package.
        assumeTrue("run with --es " + ARG_TARGET + " " + BENCHMARK, target == BENCHMARK)
        previousIme = shell("settings get secure default_input_method").trim()
        previousLocale =
            Regex(
                "\\[([^]]*)]",
            ).find(shell("cmd locale get-app-locales $BENCHMARK --user 0"))?.groupValues?.get(1).orEmpty()
        previousPerfTag = shell("getprop log.tag.$PERF_TAG").trim()
        previousErrorDialogs = shell("settings get global hide_error_dialogs").trim()
        val serviceInfo = automation.serviceInfo
        previousAccessibilityFlags = serviceInfo.flags
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        prepared = true
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd locale set-app-locales $BENCHMARK --user 0 --locales zh-TW")
        shell("pm grant $BENCHMARK android.permission.POST_NOTIFICATIONS 2>/dev/null")
        shell("settings put global hide_error_dialogs 1")
        shell("setprop log.tag.$PERF_TAG DEBUG")
        shell("ime enable $IME_ID")
        shell("ime set $IME_ID")
    }

    @After
    fun tearDown() {
        if (!prepared) return
        shell("cmd locale set-app-locales $BENCHMARK --user 0 --locales " + quote(previousLocale))
        shell("setprop log.tag.$PERF_TAG " + quote(previousPerfTag))
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set " + quote(it)) }
        if (previousErrorDialogs == "null") {
            shell("settings delete global hide_error_dialogs")
        } else {
            shell("settings put global hide_error_dialogs " + quote(previousErrorDialogs))
        }
        val serviceInfo = automation.serviceInfo
        serviceInfo.flags = previousAccessibilityFlags
        automation.serviceInfo = serviceInfo
    }

    @Test
    fun aFirstInstallationStartsTaiwaneseAndKeepsItAfterReopening() {
        launchSettings()
        openTryOutField()
        waitForKeyboard(READY_TIMEOUT_MS)

        assertCharacterForm("臺灣")
        typeAndCommitRuanjian()

        // Closing and reopening the keyboard keeps the saved choice.
        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(1_500)
        click(requireHostNode("the trial typing text field") { it.className?.toString()?.contains("EditText") == true })
        waitForKeyboard(READY_TIMEOUT_MS)
        assertCharacterForm("臺灣")
        typeAndCommitRuanjian()
    }

    private fun launchSettings() {
        shell("am start -W -n $TARGET_SETTINGS_ACTIVITY")
        waitFor("the settings home screen", READY_TIMEOUT_MS) {
            findHostNode { isTrialTypingRow(textOf(it)) }
        }
    }

    private fun openTryOutField() {
        click(requireHostNode("the trial typing row") { isTrialTypingRow(textOf(it)) })
        val field = waitFor("the trial typing text field", READY_TIMEOUT_MS) { fieldNode() }
        click(field)
    }

    private fun waitForKeyboard(timeoutMs: Long) {
        waitFor("the keyboard session", timeoutMs) {
            val log = perfLog()
            (log.contains(": ready") && log.contains(" key_bound ")).takeIf { it }
        }
    }

    private fun isCharacterFormNode(node: AccessibilityNodeInfo): Boolean =
        textOf(node).let { it.contains("Character form") || it.contains("字形") }

    private fun assertCharacterForm(state: String) {
        val languageKey = keyBound("LANGUAGE_SWITCH") ?: error("no language switch key was logged")
        shell(
            "input swipe " + languageKey.first + " " + languageKey.second +
                " " + languageKey.first + " " + languageKey.second + " 800",
        )
        val settingsRow = requireImeNode("the keyboard menu") { isKeyboardSettingsRow(textOf(it)) }
        click(settingsRow)
        val form =
            waitFor("the Character form tile", READY_TIMEOUT_MS) {
                val node = findImeNode(::isCharacterFormNode)
                if (node == null) {
                    val offscreen =
                        imeWindowRoot()?.let { root ->
                            findNode(
                                root,
                                ::isCharacterFormNode,
                                0,
                                false,
                            )
                        }
                    val surface = Rect()
                    offscreen?.parent?.getBoundsInScreen(surface)
                    if (!surface.isEmpty) {
                        val x = surface.centerX()
                        val fromY = surface.top + surface.height() * 70 / 100
                        val toY = surface.top + surface.height() * 35 / 100
                        shell("input swipe $x $fromY $x $toY 300")
                    }
                }
                node
            }
        assertTrue("the script tile shows " + state, textOf(form).contains(state))
        click(form)
        requireImeNode("the selected " + state + " option") { node ->
            val text = textOf(node)
            val selected =
                node.isSelected ||
                    text.contains("使用中") ||
                    text.contains("selected") ||
                    text.contains("已选") ||
                    text.contains("已選")
            text.contains(state) && selected
        }
        // The target can fall back to base UI resources while its app locale seeds a different script.
        val close =
            requireImeNode("the panel close button") {
                textOf(it).trim() in setOf("Close", "关闭", "關閉", "閉じる")
            }
        assertTrue("the panel closes", close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun typeAndCommitRuanjian() {
        val field = requireNotNull(fieldNode()) { "trial text field is not visible" }
        val before = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        for (letter in RUANJIAN) {
            val key =
                requireImeNode("the letter " + letter) { node ->
                    node.contentDescription?.toString()?.equals(letter.toString(), ignoreCase = true) == true ||
                        node.text?.toString()?.equals(letter.toString(), ignoreCase = true) == true
                }
            assertTrue("the real keyboard accepted " + letter, key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(150)
        }
        // The engine logs the first candidate's box for every composition; committing it proves the
        // first candidate for ruanjian in the Taiwanese state is 軟體.
        val first = waitFor("the first candidate", READY_TIMEOUT_MS) { candidateBox() }
        shell("input tap " + first.first + " " + first.second)
        waitFor("軟體 in the trial field", READY_TIMEOUT_MS) {
            findHostNode {
                it.className?.toString()?.contains("EditText") == true && it.text?.toString() == before + "軟體"
            }
        }
    }

    private fun isTrialTypingRow(text: String): Boolean =
        text.contains("Trial Typing") || text.contains("试打") || text.contains("試打")

    private fun isKeyboardSettingsRow(text: String): Boolean =
        text.contains("Keyboard settings") ||
            text.contains("键盘设置") ||
            text.contains("鍵盤設定")

    private fun textOf(node: AccessibilityNodeInfo): String =
        node.text?.toString().orEmpty() + "\n" + node.contentDescription?.toString().orEmpty()

    private fun fieldNode(): AccessibilityNodeInfo? =
        findHostNode { it.className?.toString()?.contains("EditText") == true }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun shell(command: String): String {
        // UiAutomation tokenizes the command; stdin preserves shell quoting and empty locale values.
        val descriptors = automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { it.appendLine(command) }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
    }

    private fun perfLog(): String = shell("logcat -d -s $PERF_TAG:D 2>/dev/null")

    private fun candidateBox(): Pair<Int, Int>? =
        perfLog().lines().lastOrNull { it.contains(" cand0 ") }?.let(::pointOf)

    private fun keyBound(kind: String): Pair<Int, Int>? =
        perfLog()
            .lines()
            .lastOrNull { it.contains(" key_bound ") && it.contains(" kind=" + kind + " ") }
            ?.let(::pointOf)

    private fun pointOf(line: String): Pair<Int, Int>? {
        val match = POINT.find(line) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun <T : Any> waitFor(
        what: String,
        timeoutMs: Long,
        block: () -> T?,
    ): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            block()?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("did not find " + what + diag())
    }

    private fun diag(): String {
        val windows = runCatching { automation.windows.size }.getOrElse { -1 }
        val root = runCatching { automation.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        return " (windows=" + windows + ", active=" + root + ")"
    }

    private fun requireHostNode(
        what: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo = waitFor(what, READY_TIMEOUT_MS) { findHostNode(predicate) }

    private fun requireImeNode(
        what: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo = waitFor(what, READY_TIMEOUT_MS) { findImeNode(predicate) }

    private fun findImeNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        imeWindowRoot()?.let { root -> findNode(root, predicate, 0)?.let { return it } }
        return null
    }

    private fun findHostNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() != BENCHMARK) continue
            findNode(root, predicate, 0)?.let { return it }
        }
        return null
    }

    private fun imeWindowRoot(): AccessibilityNodeInfo? =
        automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
        visibleOnly: Boolean = true,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if ((!visibleOnly || (node.isVisibleToUser && !bounds.isEmpty)) && predicate(node)) return node
        if (depth > MAX_DEPTH) return null
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            findNode(child, predicate, depth + 1, visibleOnly)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        assertEquals("a clickable node has bounds", true, rect.width() > 0 && rect.height() > 0)
        shell("input tap " + rect.centerX() + " " + rect.centerY())
        SystemClock.sleep(500)
    }

    private companion object {
        const val ARG_TARGET = "songyan.bootstrap.target"
        const val BENCHMARK = "app.songyan.ime.benchmark"
        const val IME_ID = BENCHMARK + "/app.songyan.ime.SongyanInputMethodService"
        const val TARGET_SETTINGS_ACTIVITY = BENCHMARK + "/app.songyan.ime.SongyanSettingsActivity"
        const val PERF_TAG = "SongyanPerf"
        const val RUANJIAN = "ruanjian"
        const val READY_TIMEOUT_MS = 120_000L
        const val POLL_MS = 400L
        const val MAX_DEPTH = 60
        val POINT = Regex("cx=(-?[0-9]+) cy=(-?[0-9]+)")
    }
}

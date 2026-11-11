package app.songyan.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import app.songyan.ime.settings.R as SettingsR

/**
 * The real keyboard, raised by a real text field, on whatever device runs the test. The insets
 * the app receives and the touchable region the window manager holds must both start at the
 * keyboard's top: the app above stays visible and keeps its touches.
 */
@RunWith(AndroidJUnit4::class)
class ImeWindowInsetsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val density = context.resources.displayMetrics.density
    private val preferences = (context.applicationContext as SongyanApplication).keyboardPreferences
    private var previousIme: String? = null
    private var wasEnabled = false
    private var host: Activity? = null

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
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    @After
    fun tearDown() {
        host?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        runBlocking { preferences.setBottomGapDp(0) }
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        if (!wasEnabled) shell("ime disable $KEYBOARD_ID")
    }

    @Test
    fun insetsAndTouchableRegionStartAtTheKeyboardsTop() {
        raiseKeyboardInTryOutField()
        val keys = awaitKeyboardNodes()
        val keyboardTop = keys.minOf { it.top }
        val keyboardBottom = keys.maxOf { it.bottom }

        val activity = requireNotNull(host)
        var imeTop = 0
        var screenBottom = 0
        var navigationBottom = 0
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val insets = requireNotNull(decor.rootWindowInsets)
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            screenBottom = location[1] + decor.height
            imeTop = screenBottom - insets.getInsets(WindowInsets.Type.ime()).bottom
            navigationBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        }
        val slack = (16 * density).toInt()
        val report = "keys [$keyboardTop, $keyboardBottom], ime top $imeTop, screen bottom $screenBottom"

        // What the app sees: the keyboard starts where its top row starts, not at the status bar.
        assertTrue("the app's ime inset does not start at the keyboard: $report", abs(imeTop - keyboardTop) <= 1)
        // Below the keys there is only the navigation area, not an opaque empty sheet. On Android 16+
        // the keyboard's own bar (back / switch buttons) is 48 dp, taller than the app's gesture bar.
        val navigation = maxOf(navigationBottom, (IME_NAVIGATION_DP * density).toInt())
        assertTrue(
            "the keyboard window reaches far below its keys: $report, navigation $navigationBottom",
            screenBottom - keyboardBottom <= navigation + slack,
        )

        // What the window manager holds: only the keyboard takes touches.
        val window = imeWindowDump()
        val region =
            Regex("""touchable region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)""")
                .find(window)
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
        requireNotNull(region) { "no single-rectangle touchable region in:\n$window" }
        assertEquals("touchable region top, $report:\n$window", keyboardTop, region[1])
        assertEquals("touchable region bottom, $report", screenBottom, region[3])
    }

    /** A bottom gap puts the keys higher above the screen's bottom, and the insets and touches still start at the top. */
    @Test
    fun theBottomGapRaisesTheKeysAndTheInsetsFollow() {
        runBlocking { preferences.setBottomGapDp(0) }
        raiseKeyboardInTryOutField()
        val activity = requireNotNull(host)
        val baseline = awaitKeyboardNodes()
        val screenBottom = screenBottom(activity)
        val below = screenBottom - baseline.maxOf { it.bottom }
        val gap = 32
        runBlocking { preferences.setBottomGapDp(gap) }

        val wanted = below + ((gap - 2) * density).toInt()
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var keys = imeLeaves()
        while (keys.isEmpty() || screenBottom - keys.maxOf { it.bottom } < wanted) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "the keys did not rise by the gap: below $below px, wanted $wanted, leaves $keys, screen $screenBottom"
            }
            Thread.sleep(250)
            keys = imeLeaves()
        }
        val keyboardTop = awaitKeyboardNodes().minOf { it.top }
        assertTrue(
            "the keyboard's top did not rise: ${baseline.minOf { it.top }} to $keyboardTop",
            baseline.minOf { it.top } - keyboardTop >= ((gap - 2) * density).toInt(),
        )

        var imeTop = 0
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val insets = requireNotNull(decor.rootWindowInsets)
            imeTop = screenBottom - insets.getInsets(WindowInsets.Type.ime()).bottom
        }
        assertTrue(
            "the app's ime inset does not start at the keyboard: $imeTop vs $keyboardTop",
            abs(imeTop - keyboardTop) <= 1,
        )
        val region =
            Regex("""touchable region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)""")
                .find(imeWindowDump())
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
        requireNotNull(region) { "no single-rectangle touchable region" }
        assertEquals("touchable region top", keyboardTop, region[1])
        assertEquals("touchable region bottom", screenBottom, region[3])
    }

    private fun screenBottom(activity: Activity): Int {
        var bottom = 0
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            bottom = location[1] + decor.height
        }
        return bottom
    }

    /**
     * Settings redraws when a deploy started by an earlier test finishes, which can replace the node
     * between finding and clicking it; find it again rather than fail on a node that went away.
     */
    private fun clickWhenSettled(
        selector: BySelector,
        what: String,
    ) {
        repeat(3) {
            val node =
                requireNotNull(device.wait(Until.findObject(selector), LAUNCH_TIMEOUT_MS)) { "$what did not appear" }
            try {
                node.click()
                return
            } catch (_: StaleObjectException) {
            }
        }
        throw AssertionError("$what kept being replaced")
    }

    private fun raiseKeyboardInTryOutField() {
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val tryOut = context.getString(SettingsR.string.row_tryout_title)
        clickWhenSettled(By.text(tryOut), "the try-out row")
        clickWhenSettled(By.clazz("android.widget.EditText"), "the try-out field")
        instrumentation.runOnMainSync {
            host =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is SongyanSettingsActivity }
        }
    }

    /** The keyboard's leaf nodes (toolbar buttons and keys) once the engine is ready and the window settled. */
    private fun awaitKeyboardNodes(): List<Rect> {
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var last: List<Rect> = emptyList()
        while (SystemClock.elapsedRealtime() < deadline) {
            val leaves = imeLeaves()
            // Two equal reads in a row: the show animation is over.
            if (leaves.size >= MIN_KEYS && leaves == last) return leaves
            last = leaves
            Thread.sleep(500)
        }
        throw AssertionError("the keyboard did not show its keys; last read ${last.size} nodes")
    }

    private fun imeLeaves(): List<Rect> {
        val window =
            instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: return emptyList()
        val root = window.root ?: return emptyList()
        val out = mutableListOf<Rect>()
        collectLeaves(root, out)
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun collectLeaves(
        node: AccessibilityNodeInfo,
        out: MutableList<Rect>,
    ) {
        if (!node.isVisibleToUser) return
        if (node.childCount == 0) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && node.className == "android.widget.Button") out += bounds
            return
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectLeaves(it, out) }
    }

    /** This keyboard's section of `dumpsys window windows`. */
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
        const val LAUNCH_TIMEOUT_MS = 15_000L

        /** A first deployment on a fresh install has to finish before the keys appear. */
        const val READY_TIMEOUT_MS = 120_000L
        const val MIN_KEYS = 20
        const val IME_NAVIGATION_DP = 48
        const val SETTLE_TIMEOUT_MS = 15_000L
    }
}

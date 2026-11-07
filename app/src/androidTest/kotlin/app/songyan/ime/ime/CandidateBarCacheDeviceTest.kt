package app.songyan.ime.ime

import android.app.Instrumentation
import android.content.Intent
import android.content.res.AssetManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanSettingsActivity
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The candidate row is a Canvas view, so its candidates exist only as virtual accessibility nodes
 * addressed by item index. When the engine replaces the list, the indices stay the same while the
 * words behind them change: the row must announce a subtree content change, otherwise the
 * accessibility cache keeps serving the previous words at those ids.
 *
 * This checks the framework cache itself rather than the node provider: UiAutomation reads nodes
 * through the same `AccessibilityInteractionClient` cache an accessibility service has and receives
 * the row's events through it, so a child that stays stale here is the child a screen reader would
 * keep reading on a device.
 */
@RunWith(AndroidJUnit4::class)
class CandidateBarCacheDeviceTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aReplacedCandidateListIsReadAgainAtTheSameVirtualIndex() {
        val root = File(context.cacheDir, ENGINE_DIRECTORY).also { it.deleteRecursively() }
        val source = File(root, "source")
        copyAssetTree(context.assets, "rime/builtin", source)
        val deployment =
            runBlocking {
                RimeDeployment.deploy(source, File(root, "deploy"), RimeEngine.DEFAULT_APP_NAME, "test")
            }
        assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
        val backend =
            runBlocking {
                RimeEngineBackend.create(
                    sharedDataDir = source,
                    userDataDir = File(root, "user"),
                    compiledDir = deployment.compiledDir,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                )
            }
        try {
            runBlocking { backend.selectSchema("songyan_quanpin") }
            // A real composition over the shipped engine, not a hand-built snapshot: "ni" is a code
            // the bundled full-pinyin scheme lists candidates for.
            runBlocking { backend.processKey("n") }
            var snapshot: EngineSnapshot = runBlocking { backend.processKey("i") }
            assertTrue("the engine listed no candidate for 'ni'", snapshot.candidates.isNotEmpty())
            awaitAccessibilityEnabled()

            ActivityScenario
                .launch<SongyanSettingsActivity>(
                    Intent(context, SongyanSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ).use { scenario ->
                    lateinit var bar: CandidateBarView
                    scenario.onActivity { activity ->
                        bar = CandidateBarView(activity)
                        // Only a handle for locating the row in the accessibility tree; the candidates
                        // are its virtual children.
                        bar.contentDescription = BAR_TAG
                        activity.setContentView(bar)
                    }
                    awaitLaidOut(scenario, bar)
                    show(scenario, bar, snapshot)
                    // Drain initial window/layout events before warming the child cache.
                    instrumentation.uiAutomation.waitForIdle(100, TIMEOUT_MS)

                    val firstLabel = labelOf(scenario, bar)
                    assertTrue("the row listed no candidate label", firstLabel.isNotEmpty())
                    val host =
                        requireNotNull(awaitHostNode()) {
                            "the candidate row is not in the accessibility tree under '$BAR_TAG'"
                        }
                    // Warm the cache with the node a screen reader reads for item 0.
                    val cachedFirst =
                        requireNotNull(host.getChild(0)) { "the candidate row exposed no first child" }
                    assertEquals(
                        "the accessibility tree did not start on the row's first word",
                        firstLabel,
                        cachedFirst.contentDescription?.toString(),
                    )

                    // Type on until the real engine replaces the first candidate: the same item index
                    // then describes another word.
                    var expected = firstLabel
                    for (key in NEXT_KEYS) {
                        snapshot = runBlocking { backend.processKey(key) }
                        show(scenario, bar, snapshot)
                        val candidate = labelOf(scenario, bar)
                        if (candidate != firstLabel) {
                            expected = candidate
                            break
                        }
                    }
                    assertNotEquals(
                        "typing 'ni' + '${NEXT_KEYS.joinToString("")}' did not replace the first candidate",
                        firstLabel,
                        expected,
                    )

                    // Re-walk from the node fetched before the replacement, without refresh() or
                    // clearCache(): a screen reader re-reads a cached parent exactly like this.
                    val reread = awaitCachedLabel(host, expected)
                    assertEquals(
                        "item 0 now shows a new word but the accessibility cache still serves the old one",
                        expected,
                        reread,
                    )
                }
        } finally {
            runBlocking { backend.shutdown() }
            root.deleteRecursively()
        }
    }

    /**
     * The row only announces while the accessibility manager reports enabled, which this process
     * learns of once UiAutomation introspects. Waiting here keeps the event path the one under test
     * instead of depending on when the connection happened to be established.
     */
    private fun awaitAccessibilityEnabled() {
        instrumentation.uiAutomation
        val manager = requireNotNull(context.getSystemService(AccessibilityManager::class.java))
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!manager.isEnabled && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue("accessibility is not reported enabled to the app", manager.isEnabled)
    }

    /** The label the row itself reports for item 0, read in-process from its node provider. */
    private fun labelOf(
        scenario: ActivityScenario<SongyanSettingsActivity>,
        bar: CandidateBarView,
    ): String {
        var label = ""
        scenario.onActivity {
            label =
                bar.accessibilityNodeProvider
                    .createAccessibilityNodeInfo(0)
                    ?.contentDescription
                    ?.toString()
                    .orEmpty()
        }
        return label
    }

    private fun show(
        scenario: ActivityScenario<SongyanSettingsActivity>,
        bar: CandidateBarView,
        snapshot: EngineSnapshot,
    ) {
        scenario.onActivity { bar.update(snapshot) }
        instrumentation.waitForIdleSync()
    }

    private fun awaitLaidOut(
        scenario: ActivityScenario<SongyanSettingsActivity>,
        bar: CandidateBarView,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var sized = false
            scenario.onActivity { sized = bar.width > 0 && bar.height > 0 }
            if (sized) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("the candidate row was never laid out in the activity")
    }

    /** The row's host node in the active window, with the accessibility tree as it is cached now. */
    private fun awaitHostNode(): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            findHost(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    private fun findHost(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        if (node.contentDescription?.toString() == BAR_TAG) return node
        for (index in 0 until node.childCount) {
            findHost(node.getChild(index), depth + 1)?.let { return it }
        }
        return null
    }

    /** Polls the cached child until the invalidation event has been read or the wait is over. */
    private fun awaitCachedLabel(
        host: AccessibilityNodeInfo,
        expected: String,
    ): String {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var label = ""
        while (SystemClock.uptimeMillis() < deadline) {
            label =
                host
                    .getChild(0)
                    ?.contentDescription
                    ?.toString()
                    .orEmpty()
            if (label == expected) return label
            SystemClock.sleep(POLL_MS)
        }
        return label
    }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private companion object {
        const val ENGINE_DIRECTORY = "candidate-bar-accessibility"
        const val BAR_TAG = "candidate bar under test"
        const val TIMEOUT_MS = 3_000L
        const val POLL_MS = 50L
        const val MAX_TREE_DEPTH = 24

        /** Keys typed after "ni": the real engine replaces its first candidate within these. */
        val NEXT_KEYS = listOf("h", "a", "o")
    }
}

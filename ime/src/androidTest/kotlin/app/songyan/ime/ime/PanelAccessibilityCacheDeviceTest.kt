package app.songyan.ime.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.ime.panels.QuickInsertFormatRow
import app.songyan.ime.ime.panels.QuickInsertHost
import app.songyan.ime.ime.panels.QuickInsertState
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a screen reader reads from the phrases panel after the user opens its quick-insert section
 * and after the app replaces the quick rows while the panel is open.
 *
 * The panel's items are virtual nodes whose ids are reused when the section or the rows change, so
 * an accessibility client that cached the previous items keeps reading them until the view
 * announces that its subtree changed. The test shows the shipped views in a real window and asks
 * UiAutomation - its accessibility cache included - exactly what a screen reader gets; refreshing
 * a node would bypass that cache and hide the defect.
 */
@RunWith(AndroidJUnit4::class)
class PanelAccessibilityCacheDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The app's quick-insert seam: the rows are its state, and a row writes its text into the editor. */
    private class QuickInsertProbe(
        var rows: List<QuickInsertFormatRow>,
    ) : QuickInsertHost {
        var repeatPreview: String? = null
        var insert: (String) -> Boolean = { false }

        override fun quickInsertState(): QuickInsertState = QuickInsertState(repeatPreview, rows)

        override fun repeatLastInput(): Boolean = repeatPreview?.let { insert(it) } ?: false

        override fun insertDateFormat(format: QuickInsertDateFormat): Boolean =
            rows.firstOrNull { it.format == format }?.let { insert(it.preview) } ?: false
    }

    @Test
    fun theKeyboardPageReplacesItsCachedVirtualKeys() {
        val host = FeatureHost(engine.backend())
        val automation = instrumentation.uiAutomation
        val restoreFlags = requestInteractiveWindows(automation)
        var activity: Activity? = null
        try {
            host.start()
            activity = startWindow(host)
            SystemClock.sleep(SETTLE_MS)
            assertTrue(
                "letter keys never appeared; windows: " + windowSummary(automation),
                awaitWindow { "q" in windowLabels(automation) },
            )

            host.tapNumbersPage()

            assertTrue(
                "the numbers page still exposes old keys: " + windowLabels(automation),
                host.await { windowLabels(automation).let { "1" in it && "q" !in it } },
            )
            host.tapControl(ControlKey.RETURN_TO_LETTERS)
            assertTrue("letter keys did not return", host.await { "q" in windowLabels(automation) })
        } finally {
            restoreFlags()
            activity?.let { window -> host.main { window.finish() } }
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun theQuickRowsAreReadableAfterTheRailSwitchAndAfterTheRowsRefresh() {
        val probe = QuickInsertProbe(QUICK_DATE_ROWS)
        val host = FeatureHost(engine.backend())
        host.quickInsert = probe
        host.phrases = listOf(CommonPhrase(text = PHRASE, shortcut = "xk"))
        var activity: Activity? = null
        try {
            host.start()
            probe.insert = { text -> host.connection.commitText(text, 1) }
            // The connection must ask for interactive windows before the window appears; the
            // connection's own flags are put back at the end of the test.
            val automation = instrumentation.uiAutomation
            val restoreFlags = requestInteractiveWindows(automation)
            try {
                activity = startWindow(host)
                val rail =
                    setOf(
                        host.string(R.string.ime_panel_phrases),
                        host.string(R.string.ime_panel_clipboard),
                        host.string(R.string.ime_panel_quick_insert),
                    )
                val returnToLetters = host.string(R.string.ime_return_to_letters)
                host.main { host.controller.openQuickContentPanel() }
                // The panel's lists arrive after it opens: wait on the view itself, so the client
                // cache is seeded with the finished phrases section, not with an empty one.
                assertTrue(
                    "the phrases section never loaded",
                    host.await {
                        val labels = host.labelsOf(PhraseClipboardPanelView::class.java)
                        rail.all { it in labels } && PHRASE in labels
                    },
                )
                // A freshly shown window still announces subtree changes of its own; let them
                // arrive before seeding, or one of them could pass for the change under test.
                SystemClock.sleep(SETTLE_MS)

                // A screen reader only sees the panel once its window is queryable; wait for the
                // tree itself, still on the phrases section the client will be seeded with.
                assertTrue(
                    "the panel never appeared in the window's tree; windows: " + windowSummary(automation),
                    awaitWindow {
                        val labels = panelLabels(automation, rail)
                        labels != null && returnToLetters in labels && PHRASE in labels
                    },
                )
                val phrases = requireNotNull(panelItems(automation, rail))
                val phraseLabels = phrases.map { it.label() }
                assertTrue(
                    "the seeded tree is not the phrases section: " + phraseLabels,
                    returnToLetters in phraseLabels,
                )

                // The user picks the quick-insert rail choice: a real click on the real view.
                val quickRail = host.string(R.string.ime_panel_quick_insert)
                val railItem = phrases.firstOrNull { it.label() == quickRail }
                assertNotNull("no quick-insert rail item in " + phraseLabels, railItem)
                assertTrue(
                    "the rail item did not take the click",
                    railItem!!.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )

                // A new query, without refreshing: the rows the section now draws must be readable.
                val dates = probe.rows.map { it.preview }
                val switched =
                    host.await {
                        panelLabels(automation, rail)?.let { labels -> dates.all { it in labels } } == true
                    }
                assertTrue(
                    "the quick rows never became readable: " + panelLabels(automation, rail),
                    switched,
                )
                val rows = requireNotNull(panelItems(automation, rail))
                val rowLabels = rows.map { it.label() }
                assertFalse("the phrases keys are not quick rows: " + rowLabels, returnToLetters in rowLabels)
                val dateRow =
                    requireNotNull(rows.firstOrNull { it.label() == dates.first() }) {
                        "no " + dates.first() + " row in " + rowLabels
                    }
                assertTrue("a quick row must take a click", dateRow.isClickable)
                assertTrue("a quick row must be enabled", dateRow.isEnabled)

                // A commit reaches the editor while the panel is open; the app's commit callback
                // replaces the rows, so the repeat row now previews what was just typed.
                probe.repeatPreview = REPEAT_PREVIEW
                host.main { host.controller.callbacks.commit(REPEAT_PREVIEW, false) }
                val replaced =
                    host.await {
                        panelLabels(automation, rail)?.any { label -> REPEAT_PREVIEW in label } == true
                    }
                assertTrue(
                    "the replaced row is not readable: " + panelLabels(automation, rail),
                    replaced,
                )

                // The row just read still inserts through the shipped path, into the editor.
                val refreshed = requireNotNull(panelItems(automation, rail))
                val insertRow =
                    requireNotNull(refreshed.firstOrNull { it.label() == dates.first() }) {
                        "no " + dates.first() + " row after the refresh"
                    }
                assertTrue(
                    "the date row did not take the click",
                    insertRow.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
                host.assertText(REPEAT_PREVIEW + dates.first())
            } finally {
                restoreFlags()
            }
        } finally {
            activity?.let { window -> runCatching { host.main { window.finish() } } }
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    /** Shows the keyboard in a real window; the accessibility client only reads attached trees. */
    private fun startWindow(host: FeatureHost): Activity {
        val activity =
            instrumentation.startActivitySync(
                Intent(context, PanelHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        host.attachTo(activity)
        instrumentation.waitForIdleSync()
        return activity
    }

    /**
     * UiAutomation only sees window roots after its connection asks for interactive windows. The
     * flag stays on for the test, and the connection's own flags come back afterwards.
     */
    private fun requestInteractiveWindows(automation: UiAutomation): () -> Unit {
        val original = automation.serviceInfo.flags
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        return {
            automation.serviceInfo = automation.serviceInfo.apply { flags = original }
        }
    }

    /**
     * The first query of a freshly shown window crosses into the accessibility service, which on a
     * slow emulator can take far longer than an in-process view check.
     */
    private fun awaitWindow(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(WINDOW_POLL_MS)
        }
        return condition()
    }

    private fun windowSummary(automation: UiAutomation): String =
        automation.windows.joinToString { window ->
            "${window.type}/${window.title}/${window.root?.packageName}/active=${window.isActive}"
        }

    private fun windowLabels(automation: UiAutomation): List<String> {
        val root = applicationRoot(automation) ?: return emptyList()
        val labels = ArrayList<String>()

        fun visit(
            node: AccessibilityNodeInfo,
            depth: Int,
        ) {
            if (depth <= 0) return
            labels += node.label()
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth - 1) }
        }
        visit(root, MAX_DEPTH)
        return labels
    }

    /** The items the panel exposes right now, read through the accessibility client's cache. */
    private fun panelItems(
        automation: UiAutomation,
        rail: Set<String>,
    ): List<AccessibilityNodeInfo>? {
        val root = applicationRoot(automation) ?: return null
        val panel = findPanel(root, rail, MAX_DEPTH) ?: return null
        return (0 until panel.childCount).mapNotNull { panel.getChild(it) }
    }

    private fun panelLabels(
        automation: UiAutomation,
        rail: Set<String>,
    ): List<String>? = panelItems(automation, rail)?.map { it.label() }

    /**
     * The root of this app's window. The window's own root query bypasses the client cache, so
     * every read starts from the window's current state and only the descendants come from cache.
     */
    private fun applicationRoot(automation: UiAutomation): AccessibilityNodeInfo? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == context.packageName) return root
        }
        // The interactive window list can lag the window it was just asked about.
        return automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }
    }

    /** The panel is the node whose own items carry every rail label. */
    private fun findPanel(
        node: AccessibilityNodeInfo,
        rail: Set<String>,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth <= 0) return null
        val children = (0 until node.childCount).mapNotNull { node.getChild(it) }
        if (rail.all { railLabel -> children.any { it.label() == railLabel } }) return node
        for (child in children) findPanel(child, rail, depth - 1)?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.label(): String = contentDescription?.toString().orEmpty()

    companion object {
        private const val PHRASE = "辛苦了，谢谢"
        private const val REPEAT_PREVIEW = "上一条输入"

        /** A window needs a moment for the announcements its first layout queues. */
        private const val SETTLE_MS = 500L
        private const val MAX_DEPTH = 24
        private const val WINDOW_TIMEOUT_MS = 30_000L
        private const val WINDOW_POLL_MS = 100L

        /** The five date formats the app offers, with previews a fixed instant would produce. */
        private val QUICK_DATE_ROWS =
            listOf(
                QuickInsertFormatRow(QuickInsertDateFormat.DATE, "2026-09-09"),
                QuickInsertFormatRow(QuickInsertDateFormat.DATE_CHINESE, "2026年9月9日"),
                QuickInsertFormatRow(QuickInsertDateFormat.TIME, "14:30"),
                QuickInsertFormatRow(QuickInsertDateFormat.DATE_TIME, "2026-09-09 14:30"),
                QuickInsertFormatRow(QuickInsertDateFormat.DATE_TIME_CHINESE, "2026年9月9日 14:30"),
            )

        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("panel-a11y-cache", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}

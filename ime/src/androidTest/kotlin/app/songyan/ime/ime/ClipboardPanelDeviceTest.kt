package app.songyan.ime.ime

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The clipboard page of the quick-content panel, entered from the idle toolbar. */
@RunWith(AndroidJUnit4::class)
class ClipboardPanelDeviceTest {
    @Test
    fun turningClipboardHistoryOnStaysInTheKeyboard() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            val panel = PhraseClipboardPanelView::class.java
            host.awaitIdle()
            val before = host.labelsOf(panel)
            assertTrue(before.toString(), host.string(R.string.ime_panel_clipboard_enable) in before)
            host.tap(host.string(R.string.ime_panel_clipboard_enable), panel)
            host.awaitIdle()
            assertTrue(host.clipboardEnabled)
            assertEquals("settings were not opened", 0, host.settingsOpened)
            assertTrue("the panel stays open", host.backOwned)
            val labels = host.labelsOf(panel)
            assertTrue(labels.toString(), host.string(R.string.ime_panel_open_settings) in labels)
            assertFalse(host.string(R.string.ime_panel_clipboard_enable) in labels)
        }

    @Test
    fun aClipboardEntryHasAnAccessibilityDeleteActionThatDoesNotPasteIt() =
        withHost { host ->
            host.clipboardEnabled = true
            host.clipboardEntries = listOf("first clip", "second clip")
            host.start()
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            val type = PhraseClipboardPanelView::class.java
            val index = host.labelsOf(type).indexOf("first clip")
            assertTrue(index >= 0)
            host.main {
                val provider = host.find(type).accessibilityNodeProvider
                val node = provider.createAccessibilityNodeInfo(index)!!
                val action = node.actionList.firstOrNull { it.label == host.string(R.string.ime_delete) }
                assertTrue("clipboard entries need an explicit delete action", action != null)
                assertTrue(provider.performAction(index, action!!.id, null))
            }
            host.awaitIdle()
            assertEquals(listOf("second clip"), host.clipboardEntries)
            assertFalse("first clip" in host.labelsOf(type))
            assertEquals("", host.text())
        }

    @Test
    fun accessibilityClickAfterTouchDeletionPastesTheNextEntryInsteadOfDeletingIt() =
        withHost { host ->
            host.clipboardEnabled = true
            host.clipboardEntries = listOf("first clip", "second clip")
            host.start()
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            val type = PhraseClipboardPanelView::class.java
            val index = host.labelsOf(type).indexOf("first clip")
            assertTrue(index >= 0)
            host.main {
                val panel = host.find(type)
                val bounds = Rect()
                panel.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, bounds.right - 4f, bounds.exactCenterY(), 0)
                    panel.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            host.awaitIdle()
            assertEquals(listOf("second clip"), host.clipboardEntries)
            host.tap("second clip", type)
            assertEquals("second clip", host.text())
            assertEquals(listOf("second clip"), host.clipboardEntries)
        }

    @Test
    fun trimmingMemoryReleasesTheRowsOfAClosedPanelAndReopeningLoadsThemAgain() =
        withHost { host ->
            host.clipboardEnabled = true
            host.clipboardEntries = listOf("first clip", "second clip")
            host.start()
            val type = PhraseClipboardPanelView::class.java
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            assertTrue(host.await { "first clip" in host.labelsOf(type) })
            val panel = host.main2 { host.find(type) }
            assertTrue("the held view shows the rows", "first clip" in host.main2 { labels(panel) })

            // The open panel keeps what it shows, so a trim does not take the rows from under the user.
            host.main { host.controller.trimMemory() }
            host.awaitIdle()
            assertTrue("the open panel keeps its rows", "first clip" in host.labelsOf(type))
            host.tap("first clip", type)
            host.assertText("first clip")
            host.awaitIdle()
            assertFalse("inserting an entry closes the panel", host.backOwned)
            assertTrue("the panel leaves the window", host.await { host.main2 { host.findOrNull(type) == null } })

            // The view outlives the close: once trimmed while hidden it must hold no rows of its own,
            // however it was closed (the title's close button leaves the panel through the host).
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            assertTrue(host.await { "second clip" in host.labelsOf(type) })
            host.tap(host.string(R.string.ime_panel_close), type)
            assertTrue("the panel leaves the window", host.await { host.main2 { host.findOrNull(type) == null } })
            host.awaitIdle()
            assertFalse("the close button closes the panel", host.backOwned)
            host.main { host.controller.trimMemory() }
            host.awaitIdle()
            val kept = host.main2 { labels(panel) }
            assertTrue("the closed panel view still answers", kept.isNotEmpty())
            assertFalse("a trimmed closed panel keeps no rows: $kept", "first clip" in kept || "second clip" in kept)

            // Opening it again loads the rows from the store, and they still insert.
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            assertTrue(host.await { "second clip" in host.labelsOf(type) })
            host.tap("second clip", type)
            host.assertText("first clipsecond clip")
        }

    /**
     * The accessibility labels of a panel view, also after it left the window: the provider reads the
     * view's own rows, which a kept reference must no longer hold after the panel was trimmed.
     */
    private fun labels(view: View): List<String> {
        val provider = view.accessibilityNodeProvider
        val result = ArrayList<String>()
        var id = 0
        while (true) {
            val node = provider.createAccessibilityNodeInfo(id) ?: break
            result += (node.contentDescription ?: node.text ?: "").toString()
            id++
        }
        return result
    }

    private fun <T> withHost(block: (FeatureHost) -> T): T {
        val host = FeatureHost(engine.backend())
        try {
            return block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("clipboard-panel", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}

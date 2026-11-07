package app.songyan.ime.ime

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nine-key rail shows three of its engine-backed choices. Touch scrolls it by dragging, but a
 * screen reader cannot drag, so the surface has to expose the same one-slot step as an accessibility
 * scroll action and report the choices the window then holds.
 *
 * The real full-pinyin engine proves four choices for `9426` after the bundled dictionary gained the
 * `zhan` syllable: the two lockable spellings and two candidate readings. The reading `xi` (西安) is
 * the fourth, so it is only reachable by scrolling the rail.
 */
@RunWith(AndroidJUnit4::class)
class ReadingRailAccessibilityDeviceTest {
    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend(), "phone9")
        try {
            block(host)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    /** Types [digits] on the digit keys by what they do, not by the face the language gives them. */
    private fun typeDigits(
        host: FeatureHost,
        digits: String,
    ) {
        for (digit in digits) host.tapAction(LayoutActionKind.CODE, digit.toString())
    }

    /** The labels the rail slots currently draw, as accessibility reads them. */
    private fun FeatureHost.railWindow(): List<String> {
        val indices = main2 { find(KeyboardView::class.java).railIndices.sorted() }
        val labels = labelsOf(KeyboardView::class.java)
        return indices.map { labels.getOrElse(it) { "" } }.filter { it.isNotBlank() }
    }

    /** The window the four choices start with: the two spellings, then the more common reading. */
    private fun FeatureHost.awaitFirstWindow(): Boolean = await { railWindow() == FIRST_WINDOW }

    /** The window one step further, where the 西安 reading enters. */
    private fun FeatureHost.awaitRevealedWindow(): Boolean = await { railWindow() == REVEALED_WINDOW }

    /** The scroll actions the keyboard surface itself offers assistive technology. */
    private fun FeatureHost.surfaceScrollActions(): List<Int> =
        main2 {
            val view = find(KeyboardView::class.java)
            val node = view.accessibilityNodeProvider.createAccessibilityNodeInfo(View.NO_ID)
            requireNotNull(node) { "no keyboard accessibility node" }
            node.actionList.map { it.id }
        }

    /** Performs one action on the keyboard surface the way a screen reader does. */
    private fun FeatureHost.scrollRail(action: Int): Boolean =
        main2 {
            val view = find(KeyboardView::class.java)
            view.accessibilityNodeProvider.performAction(View.NO_ID, action, null)
        }

    @Test
    fun theRailOffersAccessibilityScrollingOnlyWhileAnotherChoiceIsHidden() =
        withHost { host ->
            host.start()
            typeDigits(host, "9426")
            assertTrue("first window: ${host.railWindow()}", host.awaitFirstWindow())
            val first = host.surfaceScrollActions()
            assertTrue(
                "the rail can scroll to its hidden choices: $first",
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in first,
            )
            assertFalse(
                "nothing is hidden before the first window: $first",
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in first,
            )
            assertTrue(
                "a screen reader scrolls the rail forward",
                host.scrollRail(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
            )
            assertTrue("revealed window: ${host.railWindow()}", host.awaitRevealedWindow())
            val revealed = host.surfaceScrollActions()
            assertTrue(
                "the window can scroll back to the first choices: $revealed",
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in revealed,
            )
            assertTrue(
                "a screen reader scrolls the rail back",
                host.scrollRail(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
            )
            assertTrue("first window again: ${host.railWindow()}", host.awaitFirstWindow())
            assertFalse(
                "the first window has nothing before it",
                host.scrollRail(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
            )
        }

    @Test
    fun aRevealedReadingIsSelectableAndCommitsItsFirstWord() =
        withHost { host ->
            host.start()
            typeDigits(host, "9426")
            assertTrue("rail window: ${host.railWindow()}", host.awaitFirstWindow())
            assertTrue(
                "reveal the hidden reading",
                host.scrollRail(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
            )
            assertTrue("revealed window: ${host.railWindow()}", host.awaitRevealedWindow())
            host.tap("xi")
            host.awaitIdle()
            assertTrue("西安 reads xi an: ${host.barLabels()}", host.barLabels().first().startsWith("西安"))
            host.tapControl(ControlKey.SPACE)
            host.assertText("西安")
        }

    companion object {
        /** The two lockable spellings of `9426`, then the reading 一 spells. */
        private val FIRST_WINDOW = listOf("xiao", "zhan", "yi")

        /** One step further: the 西安 reading pushes the last spelling out of the window. */
        private val REVEALED_WINDOW = listOf("zhan", "yi", "xi")

        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("reading-rail-a11y", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}

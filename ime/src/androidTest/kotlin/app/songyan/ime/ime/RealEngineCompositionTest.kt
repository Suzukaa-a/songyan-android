package app.songyan.ime.ime

import android.graphics.Rect
import android.text.TextPaint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.layout.LayoutAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editor behaviour of the keyboard over the real engine and a real platform editor: keys go in
 * through the keyboard's own accessibility actions, text comes out through the same
 * [EditorConnection] wiring the input method service uses, and the engine is the shipped librime
 * with the bundled schemes. Selection reports, which only the system delivers to a live input
 * method, are fed in the order and with the values the framework sends them.
 *
 * Composition, nine-key basics, the toolbar and the candidate grid.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineCompositionTest : RealEngineEditorTestBase() {
    @Test
    fun deletingTheLastLetterLeavesNothingBehind() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.awaitField("n")
            host.tapDelete()
            host.awaitIdle()
            assertEquals("", host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun clearingANineKeyCompositionLeavesNothingBehind() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "64426") host.tapKeyFor(key)
            host.awaitComposing()
            host.tapClearControl()
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun aNineKeyCompositionShowsLettersNotItsKeyCodes() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "64426") host.tapKeyFor(key)
            host.awaitComposing()
            val shown = host.text()
            assertTrue("field shows '$shown'", shown.isNotEmpty() && shown.none(Char::isDigit))
            val bar = host.barLabels()
            assertTrue("candidate bar shows $bar", bar.isNotEmpty())
        }

    @Test
    fun aNineKeySentenceIsSpelledWithTheReadingsSeenWhileTyping() =
        withHost(layout = "phone9") { host ->
            host.start()
            // 546 on its own offers 林 (lin) as a candidate: the engine proves that reading.
            for (key in "546") host.tapKeyFor(key)
            host.awaitField("lin")
            host.tapClearControl()
            host.awaitIdle()
            // The sentence 一林 has no reading of its own; 林 must not fall back to the commoner jin.
            for (key in "94546") host.tapKeyFor(key)
            host.awaitComposing()
            val snapshot = host.backend.currentSnapshot()
            assertEquals("一林", snapshot.candidates[snapshot.highlightedIndex].text)
            host.awaitField("yi'lin")
        }

    @Test
    fun aNineKeyPressMadeWhileTheRailIsReadEndsOnTheRailOfTheWholeInput() =
        withHost(layout = "phone9", pauseDismiss = true) { host ->
            host.start()

            fun shown(): String {
                var keys = emptyList<String>()
                host.main { keys = host.labelsOf(host.find(KeyboardView::class.java)) }
                return host.text() + " | " + host.barLabels() + " | " + keys
            }
            for (key in "6442") host.tapKeyFor(key)
            host.awaitComposing()
            host.awaitIdle()
            val reference = shown()
            host.tapClearControl()
            host.awaitIdle()

            val backend = host.backend as PausedDismissBackend
            for (key in "64") host.tapKeyFor(key)
            host.awaitIdle()
            val gate = CompletableDeferred<Unit>()
            backend.railGate = gate
            host.main { host.controller.enqueueKey(LayoutAction.code("4")) }
            runBlocking { kotlinx.coroutines.withTimeout(TIMEOUT_MS) { backend.railHeld.await() } }
            // The next key arrives while the rail of 644 is still being read.
            host.main { host.controller.enqueueKey(LayoutAction.code("2")) }
            gate.complete(Unit)
            host.awaitComposing()
            host.awaitIdle()
            assertEquals(reference, shown())
        }

    @Test
    fun aNineKeyRailRelabelledInPlaceIsTheKeypadAFullRebuildDraws() =
        withHost(layout = "phone9") { host ->
            host.start()

            fun drawn(view: KeyboardView): List<Any> =
                listOf(
                    view.keys,
                    view.labels,
                    view.spokenLabels,
                    view.identities,
                    view.railIndices,
                    view.bounds,
                    view.hitBounds,
                    view.labelSizes.toList(),
                )
            host.tapKeyFor('6')
            host.awaitComposing()
            host.awaitIdle()
            var previous = emptyList<String>()
            host.main { previous = host.find(KeyboardView::class.java).railChoices }
            var relabelled = 0
            for (key in "4426") {
                host.tapKeyFor(key)
                host.awaitIdle()
                host.main {
                    val view = host.find(KeyboardView::class.java)
                    if (previous.isNotEmpty() && view.railChoices.isNotEmpty() && view.railChoices != previous) {
                        relabelled++
                    }
                    previous = view.railChoices
                    val inPlace = drawn(view)
                    view.rebuild()
                    assertEquals(inPlace, drawn(view))
                }
            }
            assertTrue("some key moved the readings on in place", relabelled > 0)
        }

    @Test
    fun anIdleKeyboardShowsTheToolbarAndCompositionSwapsItForCandidates() =
        withHost { host ->
            host.start()
            val toolbar = host.toolbarLabelsNow()
            assertEquals(
                listOf(R.string.ime_panel_clipboard, R.string.ime_toolbar_emoji, R.string.ime_toolbar_edit)
                    .map(host::string),
                toolbar.take(3),
            )
            assertTrue(host.string(R.string.ime_toolbar_hide) in toolbar)
            assertTrue("no candidates while idle", host.barLabelsNow().isEmpty())
            val idleHeight = host.bandHeight()
            assertEquals(48f * host.density, idleHeight.toFloat(), 1f)
            host.tap("n")
            host.awaitComposing()
            assertTrue("the toolbar gives way to the candidates", host.toolbarLabelsNow().isEmpty())
            assertEquals("the band keeps its height", idleHeight, host.bandHeight())
            host.tapDelete()
            host.awaitIdle()
            assertTrue("the toolbar comes back", host.toolbarLabelsNow().isNotEmpty())
            assertEquals(idleHeight, host.bandHeight())
        }

    @Test
    fun theToolbarOpensTheClipboardAndHidesTheKeyboard() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            host.awaitIdle()
            assertTrue("the panel owns Back", host.backOwned)
            assertTrue(host.findOrNullOnMain(PhraseClipboardPanelView::class.java) != null)
            host.main { host.controller.closePanels() }
            host.awaitToolbar()
            host.tapToolbar(host.string(R.string.ime_toolbar_hide))
            assertTrue("the hide entry reaches the service", host.hidden)
        }

    @Test
    fun noCandidateIsCutShortWhenItFitsTheRow() {
        // The width a cell gets and the width the text is drawn at must agree, also at a big font
        // scale: a candidate like 明 must not render as 明… in a cell that fits it.
        for (fontScale in listOf(1.0f, 1.3f, 2.0f)) {
            withHost(fontScale = fontScale) { host ->
                host.start()
                for (key in "ji") host.tap(key.toString())
                host.awaitComposing()
                host.main {
                    val bar = host.find(CandidateBarView::class.java)
                    val provider = bar.accessibilityNodeProvider
                    val paint = TextPaint().apply { textSize = host.candidateTextPx() }
                    var index = 0
                    while (true) {
                        val node = provider.createAccessibilityNodeInfo(index) ?: break
                        val label = (node.contentDescription ?: "").toString()
                        val text = label.substringBefore(' ')
                        if (text.isNotEmpty() && text.all { Character.isIdeographic(it.code) }) {
                            val bounds = Rect()
                            node.getBoundsInParent(bounds)
                            val needed = paint.measureText(text)
                            val room = bounds.width() - 2 * host.chipPaddingPx()
                            // A cell the whole row cannot hold may be cut; every other one must fit.
                            if (bounds.width() < bar.width - 2 * host.chipPaddingPx()) {
                                assertTrue(
                                    "font scale $fontScale: '$text' needs $needed px but its cell offers $room",
                                    room + 1f >= needed,
                                )
                            }
                        }
                        index++
                    }
                    assertTrue("only $index accessible items in the bar", index > 2)
                }
            }
        }
    }

    @Test
    fun longPressingALearnedWordOffersToDeleteItAndTheWordGoes() =
        withHost { host ->
            host.start()
            host.learn("yuwo", listOf("鱼", "我"))
            val typed = host.text()
            for (key in "yuwo") host.tap(key.toString())
            host.awaitComposing()
            assertEquals(
                "鱼我",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            host.longPressBarItem("鱼我")
            // The hold's release left the word's menu, not the character strip.
            assertEquals(listOf(host.string(R.string.ime_candidate_delete_word)), host.barLabels())
            host.main {
                val bar = host.find(CandidateBarView::class.java)
                val menu = requireNotNull(bar.menu).rect
                val candidate = requireNotNull(bar.viewCell(bar.entries.indexOf(bar.menu!!.entry)))
                assertFalse(
                    "the menu $menu must leave the candidate $candidate in view",
                    menu.intersects(
                        candidate.left.toFloat(),
                        candidate.top.toFloat(),
                        candidate.right.toFloat(),
                        candidate.bottom.toFloat(),
                    ),
                )
            }
            host.tap(host.string(R.string.ime_candidate_delete_word), bar = true)
            host.awaitIdle()
            assertFalse("the menu is gone", host.main2 { host.find(CandidateBarView::class.java).popupOpen })
            assertTrue(
                "a learned word must be forgotten: ${host.backend.currentSnapshot().candidates.map { it.text }}",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .none { it.text == "鱼我" },
            )
            assertTrue("deleting commits nothing", host.text().startsWith(typed) && host.text() != typed)
            assertTrue("the composition goes on", host.editor.composing)
        }

    @Test
    fun aWordThatWasNeverLearnedStaysAfterDeleteIsAsked() =
        withHost { host ->
            host.start()
            for (key in "yuwo") host.tap(key.toString())
            host.awaitComposing()
            val first =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            host.longPressBarItem(first)
            host.tap(host.string(R.string.ime_candidate_delete_word), bar = true)
            host.awaitIdle()
            assertEquals(
                first,
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            assertTrue("still composing", host.editor.composing)
        }

    @Test
    fun aLongPressNeverLeavesAStripThatTheNextTapTypesFrom() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            val before = host.text()
            host.longPressBarItem("你好")
            // Whatever is left on screen after the release, touching the candidate again must only
            // close it: no character of the long-pressed word is typed.
            host.tapBarAt("你好")
            host.awaitIdle()
            assertEquals(before, host.text())
            assertTrue("the composition survives", host.editor.composing)
            assertFalse(host.main2 { host.find(CandidateBarView::class.java).popupOpen })
            host.tap("你好", bar = true, prefix = true)
            host.awaitField("你好")
        }

    @Test
    fun anExpandedGridKeepsLoadingPagesAndSelectsAcrossThem() =
        withHost(smallPages = true) { host ->
            host.start()
            // Five candidates, two per page: three engine pages the grid must stitch together.
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            assertFalse("the engine has more pages", host.backend.currentSnapshot().isLastPage)
            val pageSize = host.backend.currentSnapshot().pageSize
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            val firstScreen = host.barLabels().size
            host.dragBar(upBy = EXPANDED_DRAG_PX, steps = EXPANDED_DRAG_STEPS)
            host.dragBar(upBy = EXPANDED_DRAG_PX, steps = EXPANDED_DRAG_STEPS)
            host.awaitIdle()
            val labels = host.barLabels()
            assertTrue("pages beyond the first must load: $firstScreen -> ${labels.size}", labels.size > pageSize + 3)
            // The fifth candidate lives on the third engine page.
            val target = labels[LAST_OF_FIVE]
            host.tap(target, bar = true)
            host.awaitIdle()
            val picked = target.substringBefore(' ')
            assertTrue("'$picked' from a later page was typed: '${host.text()}'", host.text().startsWith(picked))
        }

    @Test
    fun theExpandButtonRespondsToTheFirstTapDuringCandidateInertia() =
        withHost(fontScale = 2f) { host ->
            // Keep the five real candidates wider than the row even when font scaling is capped.
            host.start(width = (200 * host.density).toInt())
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val expected =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            host.flingBarThenTap(expected)
            host.awaitField("ji")
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("j")
            host.tap("i")
            host.awaitIdle()
            host.flingBarThenTap(host.string(R.string.ime_expand))
            host.awaitIdle()
            assertTrue(
                "the first tap expands the moving candidate row",
                host.barLabels().contains(host.string(R.string.ime_candidate_back)),
            )
            assertEquals("ji", host.text())
            host.tap(host.string(R.string.ime_candidate_back), bar = true)
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("j")
            host.tap("i")
            host.tap(host.string(R.string.ime_space))
            host.awaitField(expected)
        }

    @Test
    fun theExpandedColumnGoesBackDeletesAndRetypes() =
        withHost { host ->
            host.start()
            for (key in "jiu") host.tap(key.toString())
            host.awaitComposing()
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            host.tap(host.string(R.string.ime_delete), bar = true)
            host.awaitField("ji")
            host.tap(host.string(R.string.ime_candidate_back), bar = true)
            host.awaitIdle()
            assertFalse(host.main2 { host.find(CandidateBarView::class.java).expanded })
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            host.tap(host.string(R.string.ime_clear), bar = true)
            host.awaitIdle()
            assertEquals("", host.text())
            assertTrue("the toolbar is back", host.toolbarLabelsNow().isNotEmpty())
        }

    @Test
    fun holdingExpandedBackspaceStopsWhenCandidatesClose() =
        withHost { host ->
            host.start(initial = "abc")
            for (key in "jiu") host.tap(key.toString())
            host.awaitField("abcjiu")
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            host.longPressBarItem(
                host.string(R.string.ime_delete),
                ViewConfiguration.getLongPressTimeout() + 1_200L,
            )
            host.awaitField("abcj")
            assertFalse(host.main2 { host.find(CandidateBarView::class.java).expanded })
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("abc")
            host.tap("n")
            host.tap("i")
            host.tap(host.string(R.string.ime_space))
            host.awaitField("abc你")
        }

    @Test
    fun cancelingExpandedBackspaceStopsBeforeRepeat() {
        for (cancelAction in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE)) {
            withHost { host ->
                host.start(initial = "abc")
                for (key in "jiu") host.tap(key.toString())
                host.awaitField("abcjiu")
                host.tap(host.string(R.string.ime_expand), bar = true)
                host.awaitIdle()
                host.cancelBarPress(host.string(R.string.ime_delete), cancelAction)
                host.awaitField("abcji")
            }
        }
    }

    @Test
    fun anIdleSpaceTypesASpace() =
        withHost { host ->
            host.start()
            host.tapSpace()
            host.awaitField(" ")
        }
}

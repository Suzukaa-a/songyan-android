package app.songyan.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.emoji2.text.EmojiCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.EditPanelView
import app.songyan.ime.ime.panels.EmojiPanelView
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The edit panel and the emoji panel, entered from the idle toolbar, against the shipped engine. */
@RunWith(AndroidJUnit4::class)
class EditEmojiPanelDeviceTest {
    @Test
    fun theIdleToolbarOffersTheEmojiAndTheEditPanels() =
        withHost { host ->
            host.start()
            val toolbar = host.toolbarLabels()
            assertTrue(host.string(R.string.ime_toolbar_emoji) in toolbar)
            assertTrue(host.string(R.string.ime_toolbar_edit) in toolbar)
            assertEquals(ToolbarView.DEFAULT_ACTIONS.size, toolbar.size)
        }

    // ---- edit panel -------------------------------------------------------------------------

    @Test
    fun editPanelMovesAndExtendsTheSelectionWithSetSelectionAndSendsNoKeyEvents() =
        withHost { host ->
            host.start()
            host.setText("hello world", 5)
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            assertNotNull(host.findOrNullOnMain(EditPanelView::class.java))
            host.tap(host.string(R.string.ime_edit_right), EditPanelView::class.java)
            assertEquals(6 to 6, host.selection())
            host.tap(host.string(R.string.ime_edit_left), EditPanelView::class.java)
            assertEquals(5 to 5, host.selection())
            host.tap(host.string(R.string.ime_edit_select), EditPanelView::class.java)
            host.tap(host.string(R.string.ime_edit_right), EditPanelView::class.java)
            host.tap(host.string(R.string.ime_edit_right), EditPanelView::class.java)
            assertEquals(5 to 7, host.selection())
            host.tap(host.string(R.string.ime_edit_left), EditPanelView::class.java)
            assertEquals("Select keeps its anchor", 5 to 6, host.selection())
            assertTrue("no DPAD events: ${host.keyEvents}", host.keyEvents.none { it.keyCode in DPAD })
            assertEquals("hello world", host.text())
        }

    @Test
    fun editPanelDeleteRemovesTheSelectionOrTheGraphemeBeforeTheCaret() =
        withHost { host ->
            host.start()
            // After "c": the family emoji is eight UTF-16 units.
            host.setText("ab👨‍👩‍👧cd", 11)
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_delete), EditPanelView::class.java)
            host.assertText("ab👨‍👩‍👧d")
            host.tap(host.string(R.string.ime_edit_delete), EditPanelView::class.java)
            host.assertText("abd")
            host.tap(host.string(R.string.ime_edit_select_all), EditPanelView::class.java)
            host.tap(host.string(R.string.ime_edit_delete), EditPanelView::class.java)
            host.assertText("")
        }

    @Test
    fun editPanelKeysFillTheContentAndTheArrowsSpanItsHeight() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            val bounds = mutableMapOf<String, android.graphics.Rect>()
            host.main {
                val panel = host.find(EditPanelView::class.java)
                val provider = panel.accessibilityNodeProvider
                for (index in 0 until 40) {
                    val node = provider.createAccessibilityNodeInfo(index) ?: continue
                    val box = android.graphics.Rect()
                    node.getBoundsInParent(box)
                    bounds[node.contentDescription?.toString() ?: node.text?.toString() ?: continue] = box
                }
            }
            val left = bounds.getValue(host.string(R.string.ime_edit_left))
            val right = bounds.getValue(host.string(R.string.ime_edit_right))
            assertEquals(left.top, right.top)
            assertEquals(left.bottom, right.bottom)
            val up = bounds.getValue(host.string(R.string.ime_edit_up))
            assertTrue(left.right <= up.left && up.right <= right.left)
            assertTrue(left.height() > up.height() * 2)
        }

    @Test
    fun editPanelMovesByLine() =
        withHost { host ->
            host.start()
            host.setText("one\ntwo", 5)
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_line_end), EditPanelView::class.java)
            assertEquals(7 to 7, host.selection())
            host.tap(host.string(R.string.ime_edit_line_start), EditPanelView::class.java)
            assertEquals(4 to 4, host.selection())
            host.tap(host.string(R.string.ime_edit_up), EditPanelView::class.java)
            assertEquals(0 to 0, host.selection())
            host.tap(host.string(R.string.ime_edit_down), EditPanelView::class.java)
            assertEquals(4 to 4, host.selection())
        }

    @Test
    fun editPanelSelectAllCopyCutPasteAndUndoWorkOnTheEditor() =
        withHost { host ->
            host.allowClipboardReads()
            host.start()
            host.setText("alpha beta", 10)
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_select_all), EditPanelView::class.java)
            assertEquals(0 to 10, host.selection())
            host.tap(host.string(R.string.ime_edit_copy), EditPanelView::class.java)
            host.tap(host.string(R.string.ime_edit_right), EditPanelView::class.java)
            assertEquals(10 to 10, host.selection())
            host.tap(host.string(R.string.ime_edit_paste), EditPanelView::class.java)
            host.assertText("alpha betaalpha beta")
            host.tap(host.string(R.string.ime_edit_undo), EditPanelView::class.java)
            host.assertText("alpha beta")
            host.tap(host.string(R.string.ime_edit_select_all), EditPanelView::class.java)
            host.tap(host.string(R.string.ime_edit_cut), EditPanelView::class.java)
            host.assertText("")
            host.tap(host.string(R.string.ime_edit_paste), EditPanelView::class.java)
            host.assertText("alpha beta")
        }

    @Test
    fun editPanelPastesWhatAnotherAppPutOnTheClipboard() =
        withHost { host ->
            host.allowClipboardReads()
            host.start()
            host.main {
                host.context
                    .getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("", "from elsewhere"))
            }
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_paste), EditPanelView::class.java)
            host.assertText("from elsewhere")
        }

    @Test
    fun editPanelConfirmsAComposition() =
        withHost { host ->
            host.start()
            host.type("ni")
            assertTrue(host.await { host.editor.composing })
            host.main { host.controller.openEditPanel() }
            host.awaitIdle()
            host.tap(host.string(R.string.ime_edit_left), EditPanelView::class.java)
            assertFalse("the composition was confirmed before the caret moved", host.editor.composing)
            assertTrue(host.text().isNotEmpty())
        }

    @Test
    fun theSelectModeResetsWhenThePanelCloses() =
        withHost { host ->
            host.start()
            host.setText("abc", 1)
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_select), EditPanelView::class.java)
            host.main { host.controller.closePanels() }
            host.awaitIdle()
            host.tapToolbar(host.string(R.string.ime_toolbar_edit))
            host.tap(host.string(R.string.ime_edit_right), EditPanelView::class.java)
            assertEquals("Select is off after reopening", 2 to 2, host.selection())
        }

    // ---- emoji panel ------------------------------------------------------------------------

    @Test
    fun emojiPanelBrowsesCategoriesInsertsAndKeepsRecents() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            assertTrue(host.await { "😀" in host.panelLabels() })
            host.tap("😀", EmojiPanelView::class.java)
            host.assertText("😀")
            assertEquals(listOf("😀"), host.recentEmoji)
            host.tap(host.string(R.string.ime_emoji_category_animals), EmojiPanelView::class.java)
            host.tap(host.string(R.string.ime_panel_recent), EmojiPanelView::class.java)
            assertTrue("recent shows the inserted emoji", "😀" in host.panelLabels())
        }

    @Test
    fun tappingACategoryLowInTheRailLeavesTheRailWhereItWas() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            val flags = host.string(R.string.ime_emoji_category_flags)
            assertTrue(host.await { flags in host.panelLabels() })

            fun flagsTop(): Int {
                val index = host.labelsOf(EmojiPanelView::class.java).indexOf(flags)
                return host.main2 {
                    val panel = host.find(EmojiPanelView::class.java)
                    val node = panel.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!
                    android.graphics
                        .Rect()
                        .also { node.getBoundsInParent(it) }
                        .top
                }
            }
            // Drag the rail up so the last category is in reach, as a finger would.
            host.main {
                val panel = host.find(EmojiPanelView::class.java)
                val x = 20 * panel.resources.displayMetrics.density
                val start = panel.height * 0.9f
                val time = android.os.SystemClock.uptimeMillis()
                listOf(
                    android.view.MotionEvent.ACTION_DOWN to start,
                    android.view.MotionEvent.ACTION_MOVE to start - panel.height * 0.3f,
                    android.view.MotionEvent.ACTION_MOVE to start - panel.height * 0.6f,
                    android.view.MotionEvent.ACTION_UP to start - panel.height * 0.6f,
                ).forEach { (action, y) ->
                    val event = android.view.MotionEvent.obtain(time, time, action, x, y, 0)
                    panel.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            host.awaitIdle()
            val before = flagsTop()
            host.tap(flags, EmojiPanelView::class.java)
            assertEquals("the rail must not jump back to its top", before, flagsTop())
        }

    @Test
    fun holdingAnEmojiOffersItsSkinTonesAndTheChosenOneIsInsertedAndRemembered() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_people) in host.panelLabels() })
            host.tap(host.string(R.string.ime_emoji_category_people), EmojiPanelView::class.java)
            assertTrue(host.await { "👋" in host.panelLabels() })
            host.longPress("👋", EmojiPanelView::class.java)
            assertTrue("variants: ${host.panelLabels()}", "👋🏽" in host.panelLabels())
            host.tap("👋🏽", EmojiPanelView::class.java)
            host.assertText("👋🏽")
            assertEquals("👋🏽", host.recentEmoji.first())
            assertTrue("the category is back", "👋" in host.panelLabels())
        }

    @Test
    fun theSkinToneChoiceIsANamedActionAndItsVariantsAreReachableWithoutHolding() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_people) in host.panelLabels() })
            host.tap(host.string(R.string.ime_emoji_category_people), EmojiPanelView::class.java)
            assertTrue(host.await { "👋" in host.panelLabels() })
            val action = host.string(R.string.ime_emoji_choose_skin_tone_action)
            assertTrue("the hand offers skin tones", action in host.actionLabels("👋"))
            assertFalse("the eyes have none", action in host.actionLabels("👀"))
            host.performAction("👋", action)
            assertTrue("variants: ${host.panelLabels()}", "👋🏽" in host.panelLabels())
            assertFalse("a variant offers no picker of its own", action in host.actionLabels("👋🏽"))
            host.tap("👋🏽", EmojiPanelView::class.java)
            host.assertText("👋🏽")
            assertEquals("👋🏽", host.recentEmoji.first())
            assertTrue("the category is back", "👋" in host.panelLabels())
        }

    // ---- trimming memory --------------------------------------------------------------------

    @Test
    fun trimmingMemoryKeepsTheCompositionAndTheKeyboardKeepsTypingAndDrawing() =
        withHost { host ->
            host.start()
            host.type("ni")
            assertTrue(host.await { host.editor.composing && host.barLabels().any { "你" in it } })
            val before = host.text()
            val bar = host.barLabels()
            val keys = host.labelsOf(KeyboardView::class.java)

            host.main { host.controller.trimMemory() }
            host.awaitIdle()

            assertTrue("still composing", host.editor.composing)
            assertEquals(before, host.text())
            assertEquals(bar, host.barLabels())
            assertEquals(keys, host.labelsOf(KeyboardView::class.java))
            assertTrue("the keyboard draws again", host.drawsSomething(KeyboardView::class.java))
            assertTrue("the bar draws again", host.drawsSomething(CandidateBarView::class.java))

            host.type("hao")
            assertTrue(host.await { host.editor.composing && host.barLabels().any { "好" in it } })
            host.main { host.controller.trimMemory() }
            host.tapBar(host.barLabels().first { label -> label.any { it.code > 0x2E80 } })
            assertTrue("committed: '${host.text()}'", host.text().any { it.code > 0x2E80 })
        }

    @Test
    fun trimmingMemoryWhileTheEmojiPanelIsOpenKeepsItAndReopeningReloadsAfterAClosedTrim() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { "😀" in host.panelLabels() })
            host.main { host.controller.trimMemory() }
            host.awaitIdle()
            assertTrue("the open panel keeps what it shows", "😀" in host.panelLabels())
            host.tap("😀", EmojiPanelView::class.java)
            host.assertText("😀")

            host.tap(host.string(R.string.ime_return_to_letters), EmojiPanelView::class.java)
            host.main { host.controller.trimMemory() }
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(
                "the catalog loads again",
                host.await {
                    host.string(R.string.ime_emoji_category_people) in
                        host.panelLabels()
                },
            )
            assertTrue(host.await { "😀" in host.panelLabels() })
            host.tap(host.string(R.string.ime_emoji_category_people), EmojiPanelView::class.java)
            assertTrue(host.await { "👋" in host.panelLabels() })
        }

    @Test
    fun emojiSearchTakesEnglishKeywordsTypedOnTheKeyboard() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            host.startEmojiSearch()
            host.tap(host.string(R.string.ime_language_to_english))
            host.type("cry")
            assertEquals("what is typed goes into the query, not the editor", "", host.text())
            assertTrue("results: ${host.barLabels()}", host.await { "😭" in host.barLabels() })
            assertTrue(host.barLabels().first().contains("cry"))
            host.tapBar("😭")
            host.assertText("😭")
            assertTrue("the search stays open", "😭" in host.barLabels())
            host.tapBar("😭")
            host.assertText("😭😭")
            assertEquals(listOf("😭"), host.recentEmoji)
        }

    @Test
    fun emojiSearchTakesChineseKeywordsTypedWithTheInputMethod() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            host.startEmojiSearch()
            host.type("ai")
            assertTrue(host.await { host.editor.composing.not() && "爱" in host.barLabels() })
            assertEquals("the spelling is not written to the editor", "", host.text())
            host.tapBar("爱")
            val chip = "搜索：爱"
            assertTrue("chip and results: ${host.barLabels()}", host.await { host.barLabels().firstOrNull() == chip })
            assertTrue("hearts for 爱: ${host.barLabels()}", host.barLabels().size > 1)
            assertEquals("", host.text())
            host.tapBar(host.barLabels()[1])
            assertTrue(host.text().isNotEmpty())
        }

    @Test
    fun emojiSearchShowsTheSpellingInProgressBeforeAnythingIsCommitted() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            host.startEmojiSearch()
            host.type("ai")
            val chip = "搜索：ai"
            assertTrue("the query shows the spelling: ${host.barLabels()}", host.await { chip in host.barLabels() })
            assertTrue("the candidates stay: ${host.barLabels()}", "爱" in host.barLabels())
            assertEquals("", host.text())
        }

    @Test
    fun backAndTheQueryChipEndTheEmojiSearch() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            host.startEmojiSearch()
            assertTrue("Back belongs to the search", host.backOwned)
            host.tap("搜索表情", CandidateBarView::class.java)
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
            assertFalse(host.backOwned)
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            host.startEmojiSearch()
            host.main { assertTrue(host.controller.closePanels()) }
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
            host.type("a")
            assertEquals("typing after the search goes to the editor again", "", host.text().replace("a", ""))
        }

    @Test
    fun emojiSearchOpenedFromTheNumbersPageTypesOnTheLetters() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tapToolbar(host.string(R.string.ime_toolbar_emoji))
            assertTrue(host.await { host.string(R.string.ime_emoji_category_smileys) in host.panelLabels() })
            host.startEmojiSearch()
            // On the numbers page there is no "a" key to tap.
            host.type("ai")
            val chip = "搜索：ai"
            assertTrue("the letters were typed: ${host.barLabels()}", host.await { chip in host.barLabels() })
        }

    @Test
    fun theEditorsEmojiMetadataVersionReachesThePolicy() {
        val info =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                extras = Bundle().apply { putInt(EmojiCompat.EDITOR_INFO_METAVERSION_KEY, 7) }
            }
        assertEquals(7, EditorPolicy.from(info).emojiMetaVersion)
        assertEquals(0, EditorPolicy.from(EditorInfo()).emojiMetaVersion)
    }

    private fun FeatureHost.node(
        label: String,
        type: Class<out android.view.View> = EmojiPanelView::class.java,
    ): Pair<android.view.View, Int> {
        val view = find(type)
        val provider = view.accessibilityNodeProvider
        val labels =
            generateSequence(0) { it + 1 }.map { provider.createAccessibilityNodeInfo(it) }.takeWhile {
                it !=
                    null
            }
        val index = labels.indexOfFirst { it!!.contentDescription?.toString() == label }
        assertTrue("no item '$label'", index >= 0)
        return view to index
    }

    private fun FeatureHost.actionLabels(label: String): List<String> =
        main2 {
            val (view, index) = node(label)
            view.accessibilityNodeProvider
                .createAccessibilityNodeInfo(index)!!
                .actionList
                .mapNotNull { it.label?.toString() }
        }

    /** What TalkBack does: finds the node's action by its spoken label and performs it by id. */
    private fun FeatureHost.performAction(
        label: String,
        actionLabel: String,
    ) {
        main {
            val (view, index) = node(label)
            val provider = view.accessibilityNodeProvider
            val action =
                provider
                    .createAccessibilityNodeInfo(index)!!
                    .actionList
                    .first { it.label?.toString() == actionLabel }
            assertTrue(provider.performAction(index, action.id, null))
        }
        awaitIdle()
    }

    private fun FeatureHost.drawsSomething(type: Class<out android.view.View>): Boolean =
        main2 {
            val view = find(type)
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            val first = bitmap.getPixel(0, 0)
            (0 until bitmap.height step 4).any { y ->
                (0 until bitmap.width step 4).any { x ->
                    bitmap.getPixel(x, y) !=
                        first
                }
            }
        }

    private fun FeatureHost.panelLabels(): List<String> = labelsOf(EmojiPanelView::class.java)

    /** The search starts on the engine queue after the tap returns; wait until the panel has given way to the keyboard. */
    private fun FeatureHost.startEmojiSearch() {
        tap(string(R.string.ime_emoji_search), EmojiPanelView::class.java)
        assertTrue("the panel gave way to the keyboard", await { findOrNullOnMain(EmojiPanelView::class.java) == null })
    }

    private fun <V : android.view.View> FeatureHost.findOrNullOnMain(type: Class<V>): V? = main2 { findOrNull(type) }

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
        private val DPAD =
            setOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
            )
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("panels", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}

package app.songyan.ime.ime

import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutPageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Editor behaviour of the keyboard over the real engine and a real platform editor: keys go in
 * through the keyboard's own accessibility actions, text comes out through the same
 * [EditorConnection] wiring the input method service uses, and the engine is the shipped librime
 * with the bundled schemes. Selection reports, which only the system delivers to a live input
 * method, are fed in the order and with the values the framework sends them.
 *
 * Symbol and number pages, field types, spelling display, flicks and the keys that send key events.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineKeysAndPagesTest : RealEngineEditorTestBase() {
    @Test
    fun theNumbersPageKeepsItsPlaceAfterDigits() =
        withHost { host ->
            host.start()
            host.tapPage(LayoutPageTarget.NUMERIC)
            host.tap("1")
            host.tap("2")
            host.awaitField("12")
        }

    @Test
    fun spaceAfterDigitsOrSymbolsReturnsToLettersForContinuedTyping() =
        withHost { host ->
            host.start()
            val space = host.string(R.string.ime_space)
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(space)
            host.awaitField(" ")
            assertTrue(host.keyLabels().contains("1"))
            host.tap("1")
            host.tap("2")
            host.tap(space)
            host.awaitField(" 12 ")
            assertTrue(host.keyLabels().contains("a"))
            host.tap("n")
            host.tap("i")
            host.tap(space)
            host.awaitField(" 12 你")
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(space)
            host.awaitField(" 12 你 ")
            assertTrue(host.keyLabels().contains("1"))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap("【")
            host.tap(space)
            host.awaitField(" 12 你 【 ")
            assertTrue(host.keyLabels().contains("a"))
        }

    @Test
    fun anEmailFieldHasTheAtSignBesideTheSpaceBarAndNoLanguageKey() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            host.tap("@")
            host.tap(".")
            host.awaitField("@.")
            assertEquals(0, host.keyCount { it.action.type == LayoutActionKind.LANGUAGE_SWITCH })
        }

    @Test
    fun aUrlFieldStartsInEnglishAndStillSwitchesToChinese() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            // The URL footer puts the slash beside the space bar.
            host.tap("/")
            host.awaitField("/")
            host.tapLanguageKey()
            host.tap("n")
            host.tap("i")
            host.awaitComposing()
        }

    @Test
    fun oneLanguageSwitchTurnsOffTheAsciiModeRimeOwns() =
        withHost { host ->
            host.start()
            // Rime itself owns ASCII mode here (a schema switch, or a key binding on a hardware
            // keyboard) while the front end is still Chinese: only the snapshot shows it.
            host.main { host.controller.enqueue { host.controller.session?.setOption("ascii_mode", true) } }
            host.awaitIdle()

            fun engineAsciiMode(): Boolean =
                host.main2 {
                    host.controller.session
                        ?.snapshot
                        ?.isAsciiMode == true
                }
            assertTrue("the engine is in ASCII mode", engineAsciiMode())
            assertEquals(InputMode.CHINESE, host.main2 { host.controller.session?.mode })
            // The keyboard already shows English while Rime owns ASCII mode, so its language key
            // offers the way back — and one press turns that option off. Handing the front end
            // English instead would leave the engine ASCII with no press able to return.
            val toChinese = host.string(R.string.ime_language_to_chinese)
            assertTrue("the keyboard shows English: " + host.keyLabels(), toChinese in host.keyLabels())
            // The marks follow the same face: the letters footer offers the ASCII comma, not the
            // fullwidth one, while the engine is in ASCII mode.
            val asciiComma = ","
            val chineseComma = "，"
            assertTrue("ASCII marks: " + host.keyLabels(), asciiComma in host.keyLabels())
            assertFalse("no fullwidth comma: " + host.keyLabels(), chineseComma in host.keyLabels())
            host.tap(toChinese)
            host.awaitIdle()
            assertFalse("one press turns Rime's ASCII mode off", engineAsciiMode())
            assertEquals(InputMode.CHINESE, host.main2 { host.controller.session?.mode })
            assertTrue(
                "the keyboard shows Chinese again: " + host.keyLabels(),
                host.string(R.string.ime_language_to_english) in host.keyLabels(),
            )
            assertTrue("Chinese marks are back: " + host.keyLabels(), chineseComma in host.keyLabels())
            host.tap("n")
            host.tap("i")
            host.awaitComposing()
            val you = host.barLabels().firstOrNull { it.startsWith("你") }
            assertTrue("Chinese candidates are back: " + host.barLabels(), you != null)
            host.touchCandidate(you!!)
            host.awaitField("你")
        }

    @Test
    fun aPasswordFieldHasADigitRow() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            host.tap("7")
            host.awaitField("7")
        }

    /** The digit row adds a row of height; it does not squeeze five rows into the room of four. */
    @Test
    fun aPasswordDigitRowAddsHeightInsteadOfSqueezingTheLetters() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            var pitch = 0f
            host.main { pitch = host.controller.rowHeightDp * host.context.resources.displayMetrics.density }
            // A middle row's touch band is exactly one pitch; the top row's also reaches the edge.
            val letter = host.keyRect("a").height()
            assertTrue("'a' is $letter px, the row pitch is $pitch px", abs(letter - pitch) <= 2f)
            val digit = host.keyRect("7").height()
            assertTrue("'7' is $digit px, the row pitch is $pitch px", digit >= pitch - 2f)
        }

    @Test
    fun aChineseLetterLongPressTypesItsPunctuation() =
        withHost { host ->
            host.start()
            val a = host.keyCenter("a")
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, a) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, a, downTime = start) }
            host.awaitField("，")
        }

    @Test
    fun undoingASelectionWithDeleteHighlightsTheFirstCandidateAgain() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            // Choose a single-character candidate (a partial choice), then take it back.
            val before = host.backend.currentSnapshot()
            val partial = before.candidates.firstOrNull { it.text == "你" }
            assertTrue("no partial candidate in ${before.candidates.map { it.text }}", partial != null)
            assertTrue(partial!!.index > 0)
            host.tap(partial.text, bar = true)
            host.awaitIdle()
            val chosen = host.backend.currentSnapshot()
            assertTrue("after choice: raw=${chosen.rawInput} field=${host.text()}", chosen.isComposing)
            host.tapDelete()
            host.awaitIdle()
            val after = host.backend.currentSnapshot()
            assertTrue("composition is back: raw=${after.rawInput} field=${host.text()}", after.isComposing)
            assertEquals("the first candidate is highlighted", 0, after.highlightedIndex)
        }

    /**
     * The field shows the keys typed, not the reading the engine decodes from them: `ni'hc` read as
     * `ni hao` made Delete look like it needed two presses, and the user's own `'` vanished.
     */
    @Test
    fun aDoublePinyinFieldShowsTheKeysTypedAndEachDeleteTakesOneBack() =
        withHost(schema = "songyan_flypy") { host ->
            host.start()
            host.typeKeys("ni'hc")
            assertEquals(
                "你好",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            host.awaitField("ni'hc")
            for (expected in listOf("ni'h", "ni'", "ni")) {
                host.tapDelete()
                host.awaitField(expected)
                assertEquals(expected, host.backend.currentSnapshot().rawInput)
            }
        }

    @Test
    fun aFullPinyinFieldKeepsTheTypedApostropheAndEachDeleteTakesOneKeyBack() =
        withHost { host ->
            host.start()
            host.typeKeys("ni'hc")
            host.awaitField("ni'hc")
            for (expected in listOf("ni'h", "ni'", "ni", "n")) {
                host.tapDelete()
                host.awaitField(expected)
                assertEquals(expected, host.backend.currentSnapshot().rawInput)
            }
        }

    /**
     * Space confirms what the engine's highlighted candidate covers, and the key it could not
     * convert stays in the field as typed: the field never claimed `h` was already part of a word.
     */
    @Test
    fun spaceAfterAnUnconvertedInitialConfirmsTheWordAndLeavesTheInitialAsTyped() {
        for (schema in listOf("songyan_quanpin", "songyan_flypy")) {
            withHost(schema = schema) { host ->
                host.start()
                host.typeKeys("ni'h")
                host.awaitField("ni'h")
                assertEquals(
                    "你",
                    host.backend
                        .currentSnapshot()
                        .candidates
                        .first()
                        .text,
                )
                host.tapSpace()
                host.awaitField("你h")
                assertEquals("ni'h", host.backend.currentSnapshot().rawInput)
                host.tapSpace()
                host.awaitField("你h")
                assertFalse("$schema: the second Space ends the composition", host.editor.composing)
            }
        }
    }

    /**
     * A saved layout the keyboard cannot run right now (here: no 9-key form of this scheme, which no
     * deployment generated companions for) falls back for this editor only; the saved choice stays,
     * so the layout comes back once it can run again.
     */
    @Test
    fun aLayoutThatCannotRunNowKeepsTheSavedChoice() {
        withHost(layout = "phone9", schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            host.awaitIdle()
            var running: KeyboardLayoutKind? = null
            host.main { running = host.controller.layoutKind }
            assertNotEquals("precondition: the scheme has no 9-key form", KeyboardLayoutKind.PHONE9, running)
            assertEquals("phone9", host.preferences.layoutId.value)
        }
    }

    /** A compact layout spells its keys out, but an apostrophe the user typed stays in the spelling. */
    @Test
    fun aCompactSpellingKeepsTheTypedApostropheAndEachDeleteShows() {
        for (layout in listOf("merged18", "phone9")) {
            withHost(layout = layout, schema = "songyan_quanpin") { host ->
                host.start()
                for (letter in "ni") host.tapFolded(layout, letter)
                host.typeKeys("'")
                host.awaitField("ni'")
                host.tapFolded(layout, 'h')
                host.awaitIdle()
                // One more key, spelled as whichever letter of that key the engine reads.
                assertTrue("$layout: '${host.text()}'", host.text().length == 4 && host.text().startsWith("ni'"))
                host.tapDelete()
                host.awaitField("ni'")
                host.tapDelete()
                host.awaitField("ni")
            }
        }
    }

    @Test
    fun aDoublePinyinKeyShowsItsFinalsInTheCorner() =
        withHost(schema = "songyan_flypy") { host ->
            host.start()
            val labels = host.keyLabels()
            assertTrue(labels.toString(), labels.contains("q"))
            // The hint is display only: the key still types its own letter.
            host.tap("q")
            host.awaitComposing()
        }

    @Test
    fun anUpwardFlickOnANineKeyDigitKeyTypesTheDigit() =
        withHost(layout = "phone9") { host ->
            host.start()
            val label = host.keyLabels().first { it.split(' ').contains("5") }
            val key = host.keyCenter(label)
            val density = host.context.resources.displayMetrics.density
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, key)
                host.touch(
                    start + 16,
                    MotionEvent.ACTION_MOVE,
                    key.first to key.second - 30f * density,
                    downTime = start,
                )
                host.touch(start + 40, MotionEvent.ACTION_UP, key.first to key.second - 30f * density, downTime = start)
            }
            host.awaitField("5")
            // A short press on the same key is still the letters: it starts a composition.
            host.tap(label)
            host.awaitComposing()
        }

    @Test
    fun anUpwardFlickOnALetterKeyTypesTheHintItShows() =
        withHost { host ->
            host.start()
            flickKey(host, "a", -30f)
            host.awaitField("，")
        }

    @Test
    fun aDownwardFlickTypesNothingWhileTheFlickGoesUp() =
        withHost { host ->
            host.start()
            // "a" also offers "," in its long press; only the hint drawn on the key is a flick.
            flickKey(host, "a", 30f)
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun withTheFlickSetDownADownwardFlickTypesTheHintAndAnUpwardOneDoesNot() =
        withHost { host ->
            host.preferences.keyFlickDirection.value = FlickDirection.DOWN.wire
            host.start()
            host.awaitUntil("flick set down") {
                host.main2 { host.controller.keyboard.flickDirection } ==
                    FlickDirection.DOWN
            }
            flickKey(host, "a", 30f)
            host.awaitField("，")
            flickKey(host, "a", -30f)
            host.awaitIdle()
            assertEquals("，", host.text())
        }

    @Test
    fun withTheFlickOffAnUpwardFlickNeverTypesTheHint() =
        withHost { host ->
            host.preferences.keyFlickDirection.value = FlickDirection.OFF.wire
            host.start()
            host.awaitUntil(
                "flick off",
            ) { host.main2 { host.controller.keyboard.flickDirection } == FlickDirection.OFF }
            flickKey(host, "a", -30f)
            host.awaitIdle()
            assertFalse(host.text(), host.text().contains("，"))
        }

    @Test
    fun anUpwardFlickOnAMergedKeyTypesTheMarkItShows() {
        for ((layout, pair, mark) in listOf(Triple("merged14", "df", "。"), Triple("merged18", "fg", "？"))) {
            withHost(layout = layout) { host ->
                host.start()
                flickKey(host, pair, -30f)
                host.awaitField(mark)
            }
        }
    }

    @Test
    fun aShortVerticalJitterOnALetterKeyStillTypesTheLetter() =
        withHost { host ->
            host.start()
            flickKey(host, "a", -4f)
            host.awaitComposing()
            // The letter composes: the field shows its spelling, not a symbol.
            assertEquals("a", host.text())
        }

    @Test
    fun aDownwardFlickWithNothingToTypeDoesNotTypeTheKeyItEndsOn() =
        withHost { host ->
            host.start()
            // The flick goes up, so a downward one has nothing to type; the finger lifts over "a",
            // which must not be typed in its place.
            dragKey(host, "q", "a")
            host.awaitIdle()
            assertEquals("", host.text())
            host.tap("w")
            host.awaitComposing()
            assertEquals("w", host.text())
        }

    @Test
    fun aDownwardFlickOnANineKeyDigitKeyDoesNotTypeTheKeyBelow() =
        withHost(layout = "phone9") { host ->
            host.start()
            val five = host.keyLabels().first { it.split(' ').contains("5") }
            val eight = host.keyLabels().first { it.split(' ').contains("8") }
            dragKey(host, five, eight)
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun theSymbolPageOpensTheSymbolLibraryWithRecentFirstAndABottomBar() =
        withHost { host ->
            host.start()
            host.tapPage(LayoutPageTarget.NUMERIC)
            host.tapPage(LayoutPageTarget.SYMBOLS)
            host.tapSymbolPanelKey()
            host.awaitIdle()
            var labels = emptyList<String>()
            host.main {
                val panel = host.find(app.songyan.ime.ime.panels.SymbolPanelView::class.java)
                labels = host.labelsOf(panel)
            }
            // The rail has its recent slot even though nothing was used yet; the panel words it from chrome resources.
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_panel_recent)))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_delete)))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_space)))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_return_to_letters)))
        }

    @Test
    fun backClosesACandidatePopupBeforeTheKeyboard() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            host.main {
                val bar = host.find(CandidateBarView::class.java)
                val index = host.barLabelsNow().indexOfFirst { it.startsWith("你好") }
                assertTrue(
                    bar.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
                )
                assertTrue("the character strip is open", bar.popupOpen)
                assertTrue("Back belongs to the keyboard while a popup is open", host.backOwned)
                assertTrue(host.controller.closePanels())
                assertFalse(bar.popupOpen)
            }
            assertTrue("the composition survives", host.editor.composing)
        }

    @Test
    fun cursorMovesUseTheSelectionNotArrowKeys() =
        withHost { host ->
            host.start(initial = "a\uD83D\uDC4Dc")
            host.main {
                host.editor.moveCursor(-1)
                assertEquals(3, host.field.selectionStart)
                // The emoji is one character: a single step crosses both of its UTF-16 units.
                host.editor.moveCursor(-1)
                assertEquals(1, host.field.selectionStart)
                assertEquals(0, host.keyEvents.size)
            }
        }

    @Test
    fun returnIsASoftKeyboardKeyEventWithRealTimes() =
        withHost { host ->
            host.start()
            host.main { assertTrue(host.editor.sendReturn(newlineAsText = false)) }
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), host.keyEvents.map { it.action })
            for (event in host.keyEvents) {
                assertEquals(KeyEvent.KEYCODE_ENTER, event.keyCode)
                assertTrue(event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
                assertTrue(event.downTime > 0 && event.eventTime >= event.downTime)
            }
        }

    @Test
    fun returnDuringAChineseCompositionCommitsTheTypedLetters() =
        withHost { host ->
            host.start()
            for (letter in "zhong") host.tap(letter.toString())
            host.awaitField("zhong")
            host.tapEnter()
            host.awaitIdle()
            assertEquals("zhong", host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun returnDuringANineKeyCompositionCommitsTheHighlightedCandidate() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94546") host.tapKeyFor(key)
            host.awaitComposing()
            val snapshot = host.backend.currentSnapshot()
            val expected = snapshot.candidates[snapshot.highlightedIndex].text
            host.tapEnter()
            host.awaitIdle()
            assertEquals(expected, host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun theFirstKeyAfterTheKeyboardAppearsIsKept() =
        withHost { host ->
            // No wait: the key arrives while the session is still being prepared.
            host.start(await = false)
            host.tap("n")
            host.awaitField("n")
        }

    @Test
    fun deleteIsAKeyEventWhereTheKeyboardCannotSeeText() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_NULL)
            host.tapDelete()
            assertEquals(KeyEvent.KEYCODE_DEL, host.keyEvents.firstOrNull()?.keyCode)
        }

    @Test
    fun anIncognitoFieldComposesChineseWithoutLearning() {
        // Control: the same pick in an ordinary field is learned and ranks higher afterwards.
        val learned =
            withHost { host ->
                host.start()
                pickAndRetype(host)
            }
        assertTrue(
            "an ordinary field must learn: ${learned.first} -> ${learned.second}",
            learned.second < learned.first,
        )
        val incognito =
            withHost { host ->
                host.start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
                pickAndRetype(host, retypeInOrdinaryField = true)
            }
        assertEquals("an incognito pick must not be learned", incognito.first, incognito.second)
    }

    // ---- compact layouts: reading display and exact letters --------------------------------------

    @Test
    fun everyCompactLayoutSpellsAReadingNotItsKeys() {
        for (layout in listOf("merged14", "merged18", "phone9")) {
            for (
            (schema, keys, shown) in
            listOf(Triple("songyan_quanpin", "nihao", "ni hao"), Triple("songyan_flypy", "nihc", "ni hao"))
            ) {
                withHost(layout = layout, schema = schema) { host ->
                    host.start()
                    for (key in keys) host.tapFolded(layout, key)
                    host.awaitComposing()
                    host.awaitIdle()
                    android.util.Log.i("CompactDump", "$layout $schema field='${host.text()}' want '$shown'")
                }
            }
        }
    }
}

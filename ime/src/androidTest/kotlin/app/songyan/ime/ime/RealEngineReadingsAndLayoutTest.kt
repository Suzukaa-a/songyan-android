package app.songyan.ime.ime

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.ShuangpinHintPresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
 * Compact layouts, readings, shuangpin hints, split and wide surfaces.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineReadingsAndLayoutTest : RealEngineEditorTestBase() {
    /** The merged pads' Shift slot is the syllable separator: it splits a run, and types nothing idle. */
    @Test
    fun theMergedSeparatorSplitsAComposingRunAndTypesNothingWhenIdle() =
        withHost(layout = "merged14", schema = "songyan_quanpin") { host ->
            host.start()
            val separator = instrumentation.targetContext.getString(R.string.ime_separator)
            host.tap(separator)
            host.awaitIdle()
            assertEquals("idle, the separator types nothing", "", host.text())
            for (key in listOf("zx", "ui")) host.tap(key)
            host.tap(separator)
            for (key in listOf("as", "bn")) host.tap(key)
            host.awaitComposing()
            assertTrue("the split run reads xi'an: ${host.barLabels()}", host.barLabels().first().startsWith("西安"))
        }

    @Test
    fun aMergedKeySwipeTypesItsExactLetterThroughTheCompanionToken() =
        withHost(layout = "merged14", schema = "songyan_quanpin") { host ->
            host.start()
            val token = CompactFold.MERGED14.exactTokens.getValue('b')
            val schema =
                host.controller.session!!
                    .snapshot.schemaID
            // The companion publishes the token in its alphabet: that is what lets the keyboard send it.
            assertTrue(
                "alphabet of $schema lacks $token",
                runBlocking { host.backend.spellerAlphabet(schema) }.orEmpty().contains(token),
            )
            // A tap on the BN key reads as either letter.
            host.tap("bn")
            host.tap("ui")
            host.awaitComposing()
            val either = host.barLabels()
            assertTrue(
                "a tap keeps both readings: $either",
                either.any { it.startsWith("你") } && either.any { it.startsWith("不") },
            )
            host.discard()
            // Swiping left picks b exactly: the engine reads the token, the field still spells b.
            host.swipe("bn", -1)
            host.tap("ui")
            host.awaitComposing()
            assertEquals(
                "${token}u",
                host.controller.session!!
                    .snapshot.rawInput,
            )
            assertTrue("only b words: ${host.barLabels()}", host.barLabels().none { it.startsWith("你") })
            assertTrue(host.barLabels().any { it.startsWith("不") })
            host.awaitField("bu")
            host.discard()
            // Swiping right picks n exactly.
            host.swipe("bn", 1)
            host.tap("ui")
            host.awaitComposing()
            assertTrue("only n words: ${host.barLabels()}", host.barLabels().none { it.startsWith("不") })
            assertTrue(host.barLabels().any { it.startsWith("你") })
        }

    // ---- nine-key rail: spelling picker and reading filter --------------------------------------

    @Test
    fun aNineKeySpellingPickLocksTheSegmentAndDeleteRestoresIt() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94") host.tapKeyFor(key)
            host.awaitComposing()
            // The engine proves both spellings of the one key pair; the rail offers them.
            host.awaitKey("xi")
            host.awaitKey("yi")
            val before = host.backend.currentSnapshot()
            assertEquals("94", before.rawInput)
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertNotEquals(
                "the engine rewrote the segment",
                "94",
                host.controller.session!!
                    .snapshot.rawInput,
            )
            assertTrue("only xi words remain: ${host.barLabels()}", host.barLabels().none { it.startsWith("一") })
            assertTrue("a chosen spelling is not offered again", host.keyLabels().none { it == "yi" })
            // Delete undoes the lock before it deletes anything.
            host.tapDelete()
            host.awaitKey("yi")
            assertEquals("94", host.backend.currentSnapshot().rawInput)
            assertTrue("the first word is back: ${host.barLabels()}", host.barLabels().any { it.startsWith("一") })
        }

    @Test
    fun aKeyTypedAfterASpellingPickRestoresTheOriginalCodeFirst() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94") host.tapKeyFor(key)
            host.awaitComposing()
            host.awaitKey("xi")
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            host.tapKeyFor('6')
            host.awaitIdle()
            // No alias token is left in front of the new key: the input is the typed code plus it.
            assertEquals("946", host.backend.currentSnapshot().rawInput)
        }

    @Test
    fun aChosenReadingNarrowsTheCandidatesAndSpaceCommitsItsFirstMatch() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "9426") host.tapKeyFor(key)
            host.awaitComposing()
            // The full spelling set has a second lockable spelling now, so xi starts just below the window.
            host.scrollReadingRailUp()
            host.awaitKey("xi")
            val engineFirst =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            assertEquals("小", engineFirst)
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            val bar = host.barLabels()
            assertTrue("only words that read xi remain: $bar", bar.none { it.startsWith("小") })
            assertTrue("西安 reads xi an: $bar", bar.first().startsWith("西安"))
            // The field follows what space will commit.
            host.awaitField("xi'an")
            host.tapSpace()
            host.awaitField("西安")
        }

    @Test
    fun deleteUndoesAChosenReadingBeforeItDeletesACode() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "9426") host.tapKeyFor(key)
            host.awaitComposing()
            // The full spelling set has a second lockable spelling now, so xi starts just below the window.
            host.scrollReadingRailUp()
            host.awaitKey("xi")
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            host.tapDelete()
            // Deleting restores the typed input, so the reading window resets to the same three slots.
            host.scrollReadingRailUp()
            host.awaitKey("xi")
            assertEquals("9426", host.backend.currentSnapshot().rawInput)
            assertTrue("the engine's own first word is back", host.barLabels().first().startsWith("小"))
        }

    @Test
    fun aMergedLayoutKeepsAnEmptyReadingRowWhileIdleAndTheHeightNeverJumps() {
        for (layout in listOf("merged14", "merged18")) {
            withHost(layout = layout) { host ->
                host.start()
                val row = host.readingRowHeight()
                assertEquals(
                    "row is ${ReadingRowBudget.ROW_DP} dp",
                    (ReadingRowBudget.ROW_DP * host.density).toInt(),
                    row,
                )
                assertTrue(
                    "idle row has nothing to read: ${host.readingRowLabels()}",
                    host.readingRowLabels().isEmpty(),
                )
                val idleBand = host.topBandHeight()
                // The row under the readings is as tall as the reading row, not a full bar.
                assertEquals("two rows of $row px", 2 * row, idleBand)
                typeFolded(host, layout, "xian")
                host.awaitUntil("a reading in the row") { host.readingRowLabels().isNotEmpty() }
                assertEquals("composing keeps the band height", idleBand, host.topBandHeight())
                assertEquals(row, host.readingRowHeight())
                host.discard()
                assertEquals("clearing keeps the band height", idleBand, host.topBandHeight())
                assertTrue(host.readingRowLabels().isEmpty())
                assertEquals(row, host.readingRowHeight())
            }
        }
    }

    @Test
    fun theOtherLayoutsHaveNoReadingRow() {
        for (layout in listOf("qwerty26", "phone9")) {
            withHost(layout = layout) { host ->
                host.start()
                assertEquals(layout, 0, host.readingRowHeight())
            }
        }
    }

    @Test
    fun theRowUnderTheReadingRowGivesBackWhatAFullBarWouldTake() {
        var plain = 0
        withHost(layout = "qwerty26") { host ->
            host.start()
            plain = host.topBandHeight()
        }
        withHost(layout = "merged14") { host ->
            host.start()
            val row = host.readingRowHeight()
            assertEquals(2 * row, host.topBandHeight())
            assertTrue("the pair is shorter than a reading row on a full bar", host.topBandHeight() < plain + row)
        }
    }

    @Test
    fun tappingAReadingInTheRowSelectsItAndNarrowsTheCandidates() =
        withHost(layout = "merged14") { host ->
            host.start()
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitReadingRow(reading)
            host.touchReadingRowCorner(reading)
            host.awaitReadingRow(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertTrue("西安 reads xi an: ${host.barLabels()}", host.barLabels().first().startsWith("西安"))
            // Delete undoes the choice before it deletes a code.
            host.tapDelete()
            host.awaitReadingRow(reading)
        }

    @Test
    fun aReadingRowCellIsAtLeast48DpWide() =
        withHost(layout = "merged18") { host ->
            host.start()
            typeFolded(host, "merged18", "xian")
            host.awaitUntil("readings in the row") { host.readingRowLabels().isNotEmpty() }
            host.main {
                val view = host.find(ReadingRowView::class.java)
                val provider = view.accessibilityNodeProvider
                var index = 0
                while (true) {
                    val node = provider.createAccessibilityNodeInfo(index) ?: break
                    val bounds = Rect()
                    node.getBoundsInParent(bounds)
                    assertTrue("cell $index is ${bounds.width()} px", bounds.width() >= (48 * host.density).toInt())
                    assertEquals("the cell covers the whole row", view.height, bounds.height())
                    index++
                }
            }
        }

    @Test
    fun inlineReadingsSitAtTheStartOfTheCandidateRowAndAddNoHeight() {
        var plain = 0
        withHost(layout = "qwerty26") { host ->
            host.start()
            plain = host.topBandHeight()
        }
        withHost(layout = "merged14", readingPlacement = "inline") { host ->
            host.start()
            assertEquals("no row of its own", 0, host.readingRowHeight())
            assertEquals("the band keeps the plain height", plain, host.topBandHeight())
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitInlineReading(reading)
            assertEquals(plain, host.topBandHeight())
            val first = host.barItemBounds(reading)
            val candidate =
                host.barLabels().first {
                    !it.startsWith(host.readingPrefix()) &&
                        !it.startsWith(host.spellingPrefix())
                }
            assertTrue("readings come before the candidates", first.right <= host.barItemBounds(candidate).left)
            // A tap on the reading selects it; the candidates narrow and the choice shows as chosen.
            host.tap(reading, bar = true)
            host.awaitInlineReading(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertTrue("西安 reads xi an: ${host.barLabels()}", host.barLabels().any { it.startsWith("西安") })
        }
    }

    @Test
    fun manyInlineReadingsStillLeaveTheFirstCandidateInViewAndTappable() =
        withHost(layout = "merged14", readingPlacement = "inline") { host ->
            // A narrow row: two readings already need more than the readings' share of it.
            host.start(width = CROWDED_WIDTH_PX)
            // The 14-key d/f and u/i keys make di, du and fu one code: three spellings for one syllable.
            typeFolded(host, "merged14", "di")
            host.awaitInlineReading(host.context.getString(R.string.ime_reading_lock, "fu"))
            val readings = host.inlineReadingLabels()
            assertTrue("several readings: $readings", readings.size >= 2)
            val candidate =
                host.barLabels().first {
                    !it.startsWith(host.readingPrefix()) &&
                        !it.startsWith(host.spellingPrefix()) &&
                        it != host.string(R.string.ime_expand)
                }
            val first = host.barItemBounds(candidate)
            val lastReading = host.barItemBounds(readings.last())
            assertTrue("the readings overflow their section: $readings", lastReading.right > first.left)
            var stripEnd = 0
            host.main { stripEnd = host.find(CandidateBarView::class.java).viewport.right }
            assertTrue("the first candidate is whole and in view: $first", first.left >= 0 && first.right <= stripEnd)
            host.saveScreenshot("crowded-inline-readings")
            host.touchCandidate(candidate)
            val word = candidate.substringBefore(' ')
            assertTrue("'$word' was typed: '${host.text()}'", host.text().startsWith(word))
        }

    @Test
    fun changingThePlacementMovesTheReadingsWithoutRestarting() =
        withHost(layout = "merged14") { host ->
            host.start()
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitReadingRow(reading)
            host.main { host.preferences.readingPlacement.value = "inline" }
            host.awaitInlineReading(reading)
            assertEquals(0, host.readingRowHeight())
            host.main { host.preferences.readingPlacement.value = "row" }
            host.awaitReadingRow(reading)
        }

    @Test
    fun theKeyboardMenuOffersWhatTheEngineListsLessWhatTheUserHid() =
        withHost { host ->
            host.start()
            // Generated layout companions (keyboard_<hash>_<fold>) run as schemas but are never offered.
            val listed =
                host.backend.availableSchemas
                    .map { it.id }
                    .filterNot { it.startsWith("keyboard_") }
            assertTrue("the engine lists more than one input method: $listed", listed.size > 1)
            assertEquals(listed, host.main2 { host.controller.menu.schemaIds })
            val current = host.backend.currentSnapshot().schemaID
            val other = listed.first { it != current && !current.startsWith(it) }
            host.main { host.preferences.menuHiddenSchemas.value = setOf(other) }
            host.awaitIdle()
            assertEquals(listed - other, host.main2 { host.controller.menu.schemaIds })
        }

    @Test
    fun turningShuangpinHintsOnDrawsThemUnderTheLettersAndEnglishDropsThem() =
        withHost { host ->
            host.start()

            fun hintUnder(letter: String): List<String?>? =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.keys
                        .indexOfFirst { it.label == letter }
                        .takeIf { it >= 0 }
                        ?.let { view.syllableLines.getOrNull(it)?.columns }
                }
            assertNull(hintUnder("q"))
            host.main { host.preferences.shuangpinHints.value = ShuangpinHintPresets.builtinXiaohe.hints }
            host.awaitIdle()
            assertEquals(listOf("iu"), hintUnder("q"))
            // A letter without a hint still gets an empty line so its row keeps one baseline.
            assertEquals(listOf<String?>(null), hintUnder("a"))
            // Typing still sends the letter, not the hint.
            host.tap("q")
            host.awaitComposing()
            host.tapDelete()
            host.awaitIdle()
            host.tapLanguageKey()
            host.awaitIdle()
            assertNull(hintUnder("q"))
        }

    /**
     * The keycap does not grow with the system font, so at the largest font a letter used to run
     * into its shuangpin hint and corner mark, and "iang/uang" broke onto a second line over the l.
     */
    @Test
    fun theLargestSystemFontKeepsEveryLetterClearOfItsHints() =
        withHost { host ->
            host.start()
            host.main { host.preferences.shuangpinHints.value = ShuangpinHintPresets.builtinXiaohe.hints }
            host.awaitIdle()

            fun collidingAt(fontScale: Float): Pair<List<String>, Boolean> =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.textScale = ImeText.scale(view.resources.displayMetrics.density, fontScale)
                    view.fitLabels()
                    val colliding =
                        view.keys.indices
                            .filter { view.stackCollides(it, 1f) }
                            .map { view.labels[it] }
                    val l = view.keys.indexOfFirst { it.label == "l" }
                    colliding to view.syllableLines[l]!!.breaks.any { it > 0 }
                }
            val (atDefault, _) = collidingAt(1f)
            val (colliding, wrapped) = collidingAt(2f)

            assertEquals(atDefault, colliding)
            assertFalse("iang/uang stays on one line", wrapped)
        }

    @Test
    fun aKeyOnlyTheSplitArrangementHasTypesItsLetterWhenSplitAndIsAbsentWhenWhole() {
        fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))
        val base = BuiltinLayouts.qwerty26
        val split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.AUTOMATIC)
        val halves = split.derivedArrangement(base.rows).toMutableList()
        halves[0] = halves[0].copy(left = halves[0].left + letter('y'))
        halves[1] = halves[1].copy(right = listOf(letter('g')) + halves[1].right)
        halves[2] = halves[2].copy(right = listOf(letter('v')) + halves[2].right)
        val layout = base.withSplit(split.copy(halves = halves))
        instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(LayoutRegistry.create().replace(layout)) }
        try {
            withHost { host ->
                host.start(width = 1080)
                val duplicated = listOf("y", "g", "v")
                // A phone-wide keyboard is whole: the split-only keys have no slot at all.
                for (letter in duplicated) assertEquals(letter, 1, host.keyLabels().count { it.equals(letter, true) })
                host.resize(2400)
                for (letter in duplicated) {
                    assertEquals(letter, 2, host.keyLabels().count { it.equals(letter, true) })
                    for (nth in 0..1) {
                        host.tapNth(letter, nth)
                        host.awaitField(letter)
                        host.tapDelete()
                        host.awaitField("")
                    }
                }
                host.resize(1080)
                for (letter in duplicated) assertEquals(letter, 1, host.keyLabels().count { it.equals(letter, true) })
            }
        } finally {
            instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(LayoutRegistry.create()) }
        }
    }

    @Test
    fun theKeyAreaOnAWideSurfaceSitsAtThePercentOfTheFreeSpaceAndFollowsTheSettingLive() {
        withHost(smallestWidthDp = 800) { host ->
            host.preferences.widePosition.value = 25
            host.start(width = 2400)
            val density = host.density
            // The full keyboard keeps stretching; a layout that stops growing (a merged pad) docks.
            val cap = WideSurface.contentCapDp(KeyboardLayoutKind.MERGED14)
            host.main2 { host.find(KeyboardView::class.java).wideContentCapDp = cap }
            host.awaitIdle()
            // The large-screen side margin comes off both sides first; the position shares what is left.
            val marginPx =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    WideSurface
                        .sideMargin(
                            2400.0 / density,
                            view.sideMarginPerMille,
                            view.splitWidthDp.toDouble(),
                        ).toFloat() *
                        density
                }
            val freePx = 2400f - 2 * marginPx - cap.toFloat() * density

            fun keyAreaLeft(): Float =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.contentFrame(null).left
                }

            assertEquals(marginPx + freePx * 0.25f, keyAreaLeft(), 1.5f)
            // The first key of the top row starts at the key area's edge, not at the screen edge.
            assertEquals(keyAreaLeft(), host.keyRect("q").left.toFloat(), 16f * density)

            host.main2 { host.preferences.widePosition.value = 100 }
            host.awaitIdle()
            assertEquals(marginPx + freePx, keyAreaLeft(), 1.5f)
            host.main2 { host.preferences.widePosition.value = 0 }
            host.awaitIdle()
            assertEquals(marginPx, keyAreaLeft(), 1.5f)
        }
    }
}

package app.songyan.ime.ime

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutJson
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneKeypadLayoutTest {
    private val labels =
        PhoneKeypadLayout.Labels(
            page = "123",
            language = "英文",
            space = "空格",
            symbols = "符号",
            back = "返回",
            zero = "0",
            enter = "换行",
            clear = "重输",
            delete = "⌫",
        )

    private fun config(
        composing: Boolean = false,
        ascii: Boolean = false,
        saved: String = "",
        languageSwitch: Boolean = true,
        lettersReachable: Boolean = true,
        numeric: Boolean = false,
        height: Float = 240f,
        definition: LayoutDefinition = PhoneKeypadLayout.BUNDLED,
    ) = PhoneKeypadLayout.Config(
        width = 1000f,
        height = height,
        numeric = numeric,
        composing = composing,
        asciiPunctuation = ascii,
        savedQuickSymbols = saved,
        languageSwitch = languageSwitch,
        lettersReachable = lettersReachable,
        definition = definition,
        labels = labels,
    )

    private fun PhoneKeypadLayout.Shell.region(region: PhoneKeypadLayout.Region) = keys.filter { it.region == region }

    private fun PhoneKeypadLayout.Shell.space() =
        region(PhoneKeypadLayout.Region.BOTTOM).single { it.key.action.type == LayoutActionKind.SPACE }

    @Test
    fun shellHasOneGridTwoRailsAndOneBottomRow() {
        val shell = requireNotNull(PhoneKeypadLayout.build(config()))
        assertEquals(9, shell.region(PhoneKeypadLayout.Region.MAIN).size)
        assertEquals(3, shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).size)
        assertEquals(3, shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL).size)
        assertEquals(4, shell.region(PhoneKeypadLayout.Region.BOTTOM).size)
        val metrics = shell.metrics
        assertEquals(metrics.mainWidth, (metrics.digitWidth * 3 + metrics.spacing * 2), 0.001)
        // The grid and both rails share one content width, so nothing is off by a gap.
        assertEquals(metrics.contentWidth, metrics.computedContentWidth, 0.001)
        assertTrue(metrics.railsAreSymmetric())
    }

    @Test
    fun shellFillsTheHeightBudgetSharedWithTheRowPages() {
        listOf(config(), config(numeric = true)).forEach { value ->
            val shell = requireNotNull(PhoneKeypadLayout.build(value))
            // Four bands (three grid rows plus the bottom row) and three gutters.
            assertEquals(value.height.toDouble(), shell.height.toDouble(), 0.5)
            assertEquals(value.height.toDouble(), shell.keys.maxOf { it.bottom.toDouble() }, 0.5)
        }
    }

    /** The space bar sits under the grid on the keyboard's centre line and takes most of the grid's width. */
    @Test
    fun spaceBarIsCentredUnderTheGrid() {
        for (languageSwitch in listOf(true, false)) {
            val shell = requireNotNull(PhoneKeypadLayout.build(config(languageSwitch = languageSwitch)))
            val space = shell.space()
            val bottom = shell.region(PhoneKeypadLayout.Region.BOTTOM)
            val zero = bottom.last()
            assertEquals("0", zero.key.label)
            if (languageSwitch) {
                assertEquals(shell.metrics.contentWidth / 2, ((space.left + space.right) / 2).toDouble(), 0.5)
                // side : space : side = 1 : 2.4 : 1
                assertEquals(2.4, ((space.right - space.left) / (zero.right - zero.left)).toDouble(), 0.01)
            }
            // The page key sits under the leading rail.
            assertEquals(shell.metrics.railWidth, (bottom.first().right - bottom.first().left).toDouble(), 0.001)
        }
    }

    @Test
    fun editingRailPutsTheActionKeyBesideTheBottomRow() {
        // The letters page; the digit pad has its own right column (numericPageIsADialPadOnTheLetterShellsGrid).
        val shell = requireNotNull(PhoneKeypadLayout.build(config()))
        val trailing = shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL)
        assertEquals(listOf(1, 1, 2), trailing.map { it.rowSpan })
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CLEAR_INPUT, LayoutActionKind.ENTER),
            trailing.map { it.key.action.type },
        )
        // The action key is the tallest of the three and reaches the bottom of the keyboard.
        assertEquals(shell.height, trailing.last().bottom, 0.5f)
    }

    @Test
    fun idleFirstKeyCyclesAndComposingFirstKeySeparates() {
        // The leading rail types the first three symbols directly, so the cycle starts at the fourth.
        val idle = PhoneKeypadLayout.firstKey(composing = false, savedQuickSymbols = "", asciiPunctuation = false)
        assertEquals(LayoutActionKind.LITERAL, idle.action.type)
        assertEquals("！", idle.action.value)
        assertEquals("1", idle.longPress?.single()?.label)
        assertEquals(
            "！",
            PhoneKeypadLayout.firstKeyLiteral(composing = false, savedQuickSymbols = "", asciiPunctuation = false),
        )
        assertEquals(
            null,
            PhoneKeypadLayout.firstKeyLiteral(composing = true, savedQuickSymbols = "", asciiPunctuation = false),
        )
        val composing = PhoneKeypadLayout.firstKey(composing = true, savedQuickSymbols = "", asciiPunctuation = false)
        assertEquals(LayoutActionKind.CODE, composing.action.type)
        assertEquals("'", composing.action.value)
        val local =
            BuiltinLayouts.phone9.copy(
                t9QuickSymbols = "；：！、",
                t9FirstKeyBehavior = app.songyan.ime.layout.T9FirstKeyBehavior.QUICK_SYMBOLS,
            )
        val shell = requireNotNull(PhoneKeypadLayout.build(config(saved = "？", definition = local)))
        assertEquals(listOf("；", "：", "！"), shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).map { it.key.label })
        assertEquals(
            "、",
            shell.keys
                .single { it.t9FirstKey }
                .key.action.value,
        )
        val custom =
            requireNotNull(
                PhoneKeypadLayout.build(
                    config(
                        definition =
                            local.copy(
                                t9FirstKeyBehavior = app.songyan.ime.layout.T9FirstKeyBehavior.CUSTOM,
                            ),
                    ),
                ),
            )
        assertTrue(custom.keys.none { it.t9FirstKey })
        assertEquals(
            "，",
            custom
                .region(PhoneKeypadLayout.Region.MAIN)
                .first()
                .key.action.value,
        )
    }

    @Test
    fun asciiEditorsGetHalfWidthQuickSymbols() {
        val shell = requireNotNull(PhoneKeypadLayout.build(config(ascii = true)))
        assertEquals(
            listOf(",", ".", "?"),
            shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).map { it.key.label },
        )
        assertEquals(
            "!",
            shell
                .region(PhoneKeypadLayout.Region.MAIN)
                .first()
                .key.action.value,
        )
    }

    @Test
    fun savedQuickSymbolsReorderTheRailAndTheCycle() {
        val shell = requireNotNull(PhoneKeypadLayout.build(config(saved = "？！")))
        assertEquals(
            listOf("？", "！", "，"),
            shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).map { it.key.label },
        )
        assertEquals(
            "。",
            shell
                .region(PhoneKeypadLayout.Region.MAIN)
                .first()
                .key.action.value,
        )
    }

    @Test
    fun bottomRowFollowsTheEditor() {
        val full = requireNotNull(PhoneKeypadLayout.build(config()))
        assertEquals(
            listOf(
                LayoutActionKind.PAGE_SWITCH,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.SPACE,
                LayoutActionKind.LITERAL,
            ),
            full.region(PhoneKeypadLayout.Region.BOTTOM).map { it.key.action.type },
        )
        val ascii = requireNotNull(PhoneKeypadLayout.build(config(languageSwitch = false)))
        assertTrue(
            ascii.region(PhoneKeypadLayout.Region.BOTTOM).none {
                it.key.action.type == LayoutActionKind.LANGUAGE_SWITCH
            },
        )
        val numericEditor = requireNotNull(PhoneKeypadLayout.build(config(lettersReachable = false)))
        assertTrue(
            numericEditor.region(PhoneKeypadLayout.Region.BOTTOM).none {
                it.key.action.type == LayoutActionKind.PAGE_SWITCH && it.key.action.value == "numeric"
            },
        )
    }

    @Test
    fun firstKeyStateSeparatesWhileComposingAndCyclesWhenIdle() {
        val state = T9FirstKeyState()
        assertTrue(state.tap(true, "", false, null, 0.0, 1) is T9FirstKeyState.Decision.Separator)
        val first = (state.tap(false, "", false, "", 1.0, 1) as T9FirstKeyState.Decision.Cycle).edit
        assertEquals("！", first.text)
        assertNull(first.replacing)
        state.recordAccepted(first.text, accepted = true, now = 1.0, revision = 1)
        val second = (state.tap(false, "", false, "！", 1.4, 1) as T9FirstKeyState.Decision.Cycle).edit
        assertEquals("、", second.text)
        assertEquals("！", second.replacing)
    }

    @Test
    fun firstKeyStateStartsOverAfterTheInterval() {
        val state = T9FirstKeyState()
        val edit = (state.tap(false, "", false, "", 1.0, 1) as T9FirstKeyState.Decision.Cycle).edit
        state.recordAccepted(edit.text, accepted = true, now = 1.0, revision = 1)
        val late = (state.tap(false, "", false, "！", 2.0, 1) as T9FirstKeyState.Decision.Cycle).edit
        assertEquals("！", late.text)
        assertNull(late.replacing)
    }

    @Test
    fun firstKeyStateReplacesAgainstItsOwnAcceptedWriteWithoutEditorContext() {
        val state = T9FirstKeyState()
        val first = (state.tap(false, "", false, null, 1.0, 1) as T9FirstKeyState.Decision.Cycle).edit
        state.recordAccepted(first.text, accepted = true, now = 1.0, revision = 1)
        // A host that cannot report its text still gets the in-place replacement.
        val second = (state.tap(false, "", false, null, 1.3, 1) as T9FirstKeyState.Decision.Cycle).edit
        assertEquals("、", second.text)
        assertEquals("！", second.replacing)
        // A rejected write is never replaced.
        state.recordAccepted("、", accepted = false, now = 1.5, revision = 1)
        val fresh = (state.tap(false, "", false, null, 1.6, 1) as T9FirstKeyState.Decision.Cycle).edit
        assertEquals("！", fresh.text)
        assertNull(fresh.replacing)
    }

    @Test
    fun numericPageIsADialPadOnTheLetterShellsGrid() {
        val shell = requireNotNull(PhoneKeypadLayout.build(config(numeric = true)))

        fun key(label: String) = shell.keys.single { it.key.label == label }
        assertEquals(
            (0..9).map { it.toString() },
            shell.keys
                .map { it.key.label }
                .filter {
                    it.length == 1 &&
                        it[0].isDigit()
                }.sorted(),
        )
        // Three quick marks, one beside each digit row: the rail and the grid share their rows.
        val rail = shell.region(PhoneKeypadLayout.Region.LEADING_RAIL)
        assertEquals(listOf(".", "？", "！"), rail.map { it.key.label })
        assertEquals(listOf("1", "4", "7").map { key(it).top }, rail.map { it.top })
        assertEquals(listOf("1", "4", "7").map { key(it).bottom }, rail.map { it.bottom })
        assertTrue(
            rail
                .first()
                .key.longPress
                .orEmpty()
                .isNotEmpty(),
        )
        assertTrue(
            rail
                .first()
                .key.longPress
                .orEmpty()
                .none { it.label == "，" },
        )
        // Delete, 0 and the comma down the right, one row each, beside 3, 6 and 9.
        val trailing = shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL)
        assertEquals(listOf("⌫", "0", "，"), trailing.map { it.key.label })
        assertEquals(listOf("3", "6", "9").map { key(it).top }, trailing.map { it.top })
        assertEquals(listOf(1, 1, 1), trailing.map { it.rowSpan })
        assertTrue(shell.keys.none { it.key.action.type == LayoutActionKind.CLEAR_INPUT })
        assertEquals(
            ",",
            requireNotNull(PhoneKeypadLayout.build(config(numeric = true, ascii = true)))
                .region(PhoneKeypadLayout.Region.TRAILING_RAIL)[2]
                .key.label,
        )
        assertEquals(config(numeric = true).height.toDouble(), shell.keys.maxOf { it.bottom.toDouble() }, 0.5)
        assertEquals(shell.metrics.contentWidth, shell.metrics.computedContentWidth, 0.001)
    }

    @Test
    fun theBottomRowPutsOneKeyUnderTheRailEachColumnAndTheRightColumn() {
        fun PhoneKeypadLayout.Shell.edges(predicate: (LayoutKey) -> Boolean) =
            keys.single { predicate(it.key) }.let { it.left to it.right }

        fun PhoneKeypadLayout.Shell.column(label: String) =
            keys.single { it.key.label == label }.let {
                it.left to
                    it.right
            }
        val back: (LayoutKey) -> Boolean = { it.action.pageSwitchTarget == LayoutPageTarget.LETTERS }
        val symbols: (LayoutKey) -> Boolean = { it.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS }
        val language: (LayoutKey) -> Boolean = { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }
        val globe: (LayoutKey) -> Boolean = { it.action.type == LayoutActionKind.GLOBE }
        val space: (LayoutKey) -> Boolean = { it.action.type == LayoutActionKind.SPACE }
        val enter: (LayoutKey) -> Boolean = { it.action.type == LayoutActionKind.ENTER }

        val shell = requireNotNull(PhoneKeypadLayout.build(config(numeric = true)))
        val rail = shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).first().let { it.left to it.right }
        assertEquals(rail, shell.edges(back))
        assertEquals(shell.column("7"), shell.edges(language))
        assertEquals(shell.column("8"), shell.edges(space))
        assertEquals(shell.column("9"), shell.edges(symbols))
        assertEquals(shell.column("0"), shell.edges(enter))
        // Return is one row tall, in the bottom row with the rest.
        val bottomKeys =
            shell.keys.filter {
                back(it.key) ||
                    language(it.key) ||
                    space(it.key) ||
                    symbols(it.key) ||
                    enter(it.key)
            }
        assertEquals(1, bottomKeys.map { it.top to it.bottom }.distinct().size)
        assertTrue(bottomKeys.first().top >= shell.keys.single { it.key.label == "8" }.bottom - 0.001f)
        assertTrue(shell.keys.none { globe(it.key) })

        // The globe takes the cell under the rail, and 返回 and 中/英 share the next, in that order.
        val withGlobe = requireNotNull(PhoneKeypadLayout.build(config(numeric = true).copy(showsGlobe = true)))
        assertEquals(rail, withGlobe.edges(globe))
        val (backLeft, backRight) = withGlobe.edges(back)
        val (languageLeft, languageRight) = withGlobe.edges(language)
        assertEquals(withGlobe.column("7").first, backLeft, 0.001f)
        assertEquals(withGlobe.column("7").second, languageRight, 0.001f)
        assertTrue(backRight < languageLeft)
        assertEquals(withGlobe.column("8"), withGlobe.edges(space))

        // A cell without its key goes to the key beside it: no hole in the row.
        val noLanguage = requireNotNull(PhoneKeypadLayout.build(config(numeric = true, languageSwitch = false)))
        assertEquals(rail.first to noLanguage.column("7").second, noLanguage.edges(back))
        val editor = requireNotNull(PhoneKeypadLayout.build(config(numeric = true, lettersReachable = false)))
        assertTrue(editor.keys.none { back(it.key) })
        assertEquals(rail.first to editor.column("7").second, editor.edges(language))
    }

    // A custom nine-key layout draws from its own data.

    private fun edited(
        rows: (List<LayoutRow>) -> List<LayoutRow> = { it },
        areas: ((List<LayoutAuxiliaryArea>) -> List<LayoutAuxiliaryArea>)? = null,
    ): LayoutDefinition {
        val preset = BuiltinLayouts.phone9
        return preset.copy(
            id = "custom9",
            name = "我的九键",
            rows = rows(preset.rows),
            auxiliaryAreas = areas?.invoke(preset.auxiliaryAreas.orEmpty()) ?: preset.auxiliaryAreas,
        )
    }

    private fun relabel(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
        label: String,
    ) = rows.mapIndexed { r, value ->
        if (r !=
            row
        ) {
            value
        } else {
            LayoutRow(
                value.keys.mapIndexed { k, old ->
                    if (k ==
                        key
                    ) {
                        old.copy(label = label)
                    } else {
                        old
                    }
                },
            )
        }
    }

    private fun declared(
        leading: List<LayoutKey>?,
        trailing: List<LayoutKey>?,
    ) = edited(areas = {
        buildList {
            if (leading != null) {
                add(
                    LayoutAuxiliaryArea(
                        LayoutAuxiliaryArea.Position.LEADING,
                        LayoutAuxiliaryArea.Role.PUNCTUATION,
                        leading,
                    ),
                )
            }
            if (trailing != null) {
                add(
                    LayoutAuxiliaryArea(
                        LayoutAuxiliaryArea.Position.TRAILING,
                        LayoutAuxiliaryArea.Role.EDITING,
                        trailing,
                    ),
                )
            }
        }
    })

    private fun literal(
        text: String,
        span: Double? = null,
    ) = LayoutKey(text, width = span, action = LayoutAction.literal(text))

    @Test
    fun theGridIsDrawnFromTheLayoutsOwnKeys() {
        val definition = edited(rows = { relabel(relabel(it, 1, 1, "我的"), 2, 2, "九") })
        val main =
            requireNotNull(
                PhoneKeypadLayout.build(config(definition = definition)),
            ).region(PhoneKeypadLayout.Region.MAIN)
        assertEquals(9, main.size)
        assertEquals("我的", main[4].key.label)
        assertEquals("九", main[8].key.label)
        // The key still sends its declared digit and keeps its long-press digit.
        assertEquals("5", main[4].key.action.value)
        assertEquals(
            "5",
            main[4]
                .key.longPress
                ?.single()
                ?.label,
        )
    }

    @Test
    fun theBundledShellIsWhatAnUntouchedLayoutDraws() {
        val copy = BuiltinLayouts.phone9.copy(id = "copy", name = "副本")
        val bundled = requireNotNull(PhoneKeypadLayout.build(config()))
        val fromCopy = requireNotNull(PhoneKeypadLayout.build(config(definition = copy)))
        assertEquals(bundled.keys, fromCopy.keys)
    }

    @Test
    fun aRetitledFirstKeyKeepsCyclingWithTheAuthorsFace() {
        val definition = edited(rows = { relabel(it, 0, 0, "标点") })
        val idle =
            requireNotNull(
                PhoneKeypadLayout.build(config(definition = definition)),
            ).region(PhoneKeypadLayout.Region.MAIN).first()
        assertEquals("标点", idle.key.label)
        assertEquals("！", idle.key.action.value)
        val composing =
            requireNotNull(PhoneKeypadLayout.build(config(definition = definition, composing = true)))
                .region(PhoneKeypadLayout.Region.MAIN)
                .first()
        assertEquals("'", composing.key.action.value)
        assertTrue(PhoneKeypadLayout.cyclesFirstKey(definition))
    }

    @Test
    fun aFirstKeyWithOtherSemanticsTypesWhatItsAuthorDeclared() {
        val definition =
            edited(rows = { rows ->
                rows.mapIndexed { r, row ->
                    if (r != 0) row else LayoutRow(listOf(literal("。")) + row.keys.drop(1))
                }
            })
        assertFalse(PhoneKeypadLayout.cyclesFirstKey(definition))
        val first =
            requireNotNull(
                PhoneKeypadLayout.build(config(definition = definition)),
            ).region(PhoneKeypadLayout.Region.MAIN).first()
        assertEquals("。", first.key.label)
        assertEquals("。", first.key.action.value)
    }

    @Test
    fun declaredRailsReplaceTheBundledOnesKeyForKey() {
        val definition =
            declared(
                leading = listOf(literal("@"), literal("#")),
                trailing =
                    listOf(
                        LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                        literal("，", span = 2.0),
                    ),
            )
        val shell = requireNotNull(PhoneKeypadLayout.build(config(definition = definition)))
        val leading = shell.region(PhoneKeypadLayout.Region.LEADING_RAIL)
        assertEquals(listOf("@", "#"), leading.map { it.key.label })
        val trailing = shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL)
        assertEquals(listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.LITERAL), trailing.map { it.key.action.type })
        // Spans 1 and 2 share the three digit rows in proportion and meet without a gap.
        val grid = shell.region(PhoneKeypadLayout.Region.MAIN)
        assertEquals(grid.last().bottom, trailing.last().bottom, 0.5f)
        assertEquals(trailing[0].bottom, trailing[1].top, 0.001f)
        assertEquals(1, trailing[0].rowSpan)
        assertEquals(2, trailing[1].rowSpan)
        assertTrue(trailing.all { it.bottom <= shell.space().top + 0.001f })
    }

    @Test
    fun aLayoutWithNoDeclaredRailDrawsNoneAndKeepsThreeReadingSlots() {
        val none = declared(leading = null, trailing = null)
        val shell = requireNotNull(PhoneKeypadLayout.build(config(definition = none)))
        assertTrue(shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL).isEmpty())
        val slots = shell.region(PhoneKeypadLayout.Region.LEADING_RAIL)
        assertEquals(3, slots.size)
        assertTrue(slots.all { it.readingSlot })
        // The slots belong to the shell's geometry whether or not a composition is running.
        val composing = requireNotNull(PhoneKeypadLayout.build(config(definition = none, composing = true)))
        assertEquals(
            slots.map { it.top to it.bottom },
            composing.region(PhoneKeypadLayout.Region.LEADING_RAIL).map {
                it.top to
                    it.bottom
            },
        )
    }

    @Test
    fun theBottomRowFollowsTheLayoutsEditedControlRow() {
        val definition =
            edited(rows = { rows ->
                rows.dropLast(1) +
                    LayoutRow(
                        listOf(LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout"))),
                    )
            })
        val shell = requireNotNull(PhoneKeypadLayout.build(config(definition = definition)))
        assertEquals(listOf("布局"), shell.region(PhoneKeypadLayout.Region.BOTTOM).map { it.key.label })
        val bottom = shell.region(PhoneKeypadLayout.Region.BOTTOM).single()
        assertEquals(0f, bottom.left, 0.001f)
        assertTrue(bottom.right <= shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL).last().left)
    }

    @Test
    fun aLayoutWithoutTheTelephoneStructureIsNotDrawnByTheShell() {
        val broken =
            edited(rows = { rows ->
                rows.mapIndexed { r, row ->
                    if (r !=
                        1
                    ) {
                        row
                    } else {
                        LayoutRow(row.keys.drop(1))
                    }
                }
            })
        assertFalse(PhoneKeypadLayout.draws(broken))
        assertNull(PhoneKeypadLayout.build(config(definition = broken)))
        assertTrue(PhoneKeypadLayout.draws(BuiltinLayouts.phone9))
        assertTrue(PhoneKeypadRailPolicy.usesBundledRails(PhoneKeypadLayout.BUNDLED))
    }

    /** The layout file the iOS app's own encoder wrote (core:layout's interop fixtures). */
    private fun iosAuthored(): LayoutDefinition {
        val file = File("../core/layout/src/test/resources/ios-interop/layout-custom-phone9.json")
        assertTrue("missing " + file.absolutePath, file.exists())
        return LayoutJson.decode(file.readText())
    }

    @Test
    fun anIosAuthoredNineKeyLayoutIsDrawnFromItsData() {
        val shell = requireNotNull(PhoneKeypadLayout.build(config(definition = iosAuthored())))
        val main = shell.region(PhoneKeypadLayout.Region.MAIN)
        assertEquals("我的", main[4].key.label)
        assertEquals(listOf("。", "？"), shell.region(PhoneKeypadLayout.Region.LEADING_RAIL).map { it.key.label })
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CLEAR_INPUT),
            shell.region(PhoneKeypadLayout.Region.TRAILING_RAIL).map { it.key.action.type },
        )
        assertTrue(shell.region(PhoneKeypadLayout.Region.BOTTOM).any { it.key.label == "空 格" })
        assertEquals(
            iosAuthored()
                .rows
                .last()
                .keys
                .filter { it.action.type != LayoutActionKind.GLOBE },
            shell.region(PhoneKeypadLayout.Region.BOTTOM).map { it.key },
        )
    }
}

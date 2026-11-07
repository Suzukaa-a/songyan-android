package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Japanese twelve-key (kana12) board: the 4 x 3 kana grid between a leading rail
 * (composition caret left, 記号, あ/A, the system keyboard switch) and a trailing rail (delete,
 * space, Return spanning two rows). A kana cap carries its row id, not one Latin key: the flick,
 * toggle and romaji spelling of a row live in the kana input model. The board declares all four rows
 * itself, so neither the 26-key Shift/Delete chrome nor the automatic footer replaces it.
 *
 * These checks read the in-code definition and the codec only: the author-facing example arrives
 * with the iOS layout sync, and [BuiltinLayoutsTest] compares against it.
 */
class Kana12LayoutTest {
    /** The tap glyph of each grid cell with the row id the kana handler spells into the engine. */
    private val grid =
        listOf(
            listOf("あ" to "a", "か" to "ka", "さ" to "sa"),
            listOf("た" to "ta", "な" to "na", "は" to "ha"),
            listOf("ま" to "ma", "や" to "ya", "ら" to "ra"),
        )

    /** The bottom row puts the modifier and the punctuation cap around わ. */
    private val bottomCaps = listOf("小゛゜", "わ", "、。?!")

    private val layout get() = BuiltinLayouts.kana12

    @Test
    fun fourthKanaRowRemainsInputContent() {
        assertNull(LayoutBottomRow.index(layout))
        assertNull(LayoutBottomRow.authored(layout))
    }

    private fun key(schemaID: String) = KeyboardCompositionKey(schemaID, LayoutInput.Builtin.QWERTY26)

    private fun rail(position: LayoutAuxiliaryArea.Position) =
        requireNotNull(layout.auxiliaryAreas?.firstOrNull { it.position == position })

    @Test
    fun theBoardIsRegisteredAsABundledLayout() {
        assertEquals(InputLayoutPolicy.KANA_LAYOUT, layout.id)
        assertEquals("kana12", layout.id)
        assertEquals("假名 12 键", layout.name)
        assertEquals(layout, BuiltinLayouts.byId("kana12"))
        assertEquals(layout, BuiltinLayouts.all.last())

        val registry = LayoutRegistry.create()
        assertEquals(layout, registry.layout("kana12"))
        assertEquals("kana12", registry.resolvedLayout("kana12")!!.id)
        assertEquals(listOf("kana12"), registry.usableLayouts { it.id == "kana12" }.map { it.id })
        // A bundled board is reset, never deleted.
        val error = assertThrows(LayoutException::class.java) { LayoutRegistry.create().remove("kana12") }
        assertEquals(LayoutInvalidCode.LAYOUT_PRESET_IS_READ_ONLY, (error as LayoutException.Invalid).code)
    }

    @Test
    fun theGridIsTheFourByThreeLayout() {
        assertEquals(4, layout.rows.size)
        assertEquals(listOf(3, 3, 3, 3), layout.rows.map { it.keys.size })
        val caps = layout.rows.dropLast(1).map { row -> row.keys.map { it.label } }
        assertEquals(grid.map { row -> row.map { it.first } }, caps)
        assertEquals(
            bottomCaps,
            layout.rows
                .last()
                .keys
                .map { it.label },
        )
    }

    @Test
    fun everyKanaCapCarriesItsRowsTapSpelling() {
        val expected = grid.flatten()
        val taps = layout.rows.dropLast(1).flatMap { it.keys }
        assertEquals(expected.map { it.second }, taps.map { it.action.value })
        for ((cap, cell) in taps.zip(expected)) {
            assertEquals(cap.label, LayoutAction.kana(cell.second), cap.action)
            assertEquals(cap.label, LayoutActionKind.KANA, cap.action.type)
            cap.action.validate()
            assertNull(cap.label, cap.secondaryLabel)
            assertNull(cap.label, cap.longPress)
            // The authored kana glyph is the face; a title mode would let a control rename it.
            assertNull(cap.label, cap.titleMode)
            // The board never claims a Latin key: a romaji spelling is not one engine code, it is the
            // kana handler's business, so no cap is a code key.
            assertNotEquals(cap.label, LayoutActionKind.CODE, cap.action.type)
        }
    }

    @Test
    fun theBottomRowCarriesTheModifierAndThePunctuationCap() {
        val bottom = layout.rows.last().keys
        // 小゛゜ is the kana renderer's Japanese variant key: it edits the kana just typed, so a
        // shift or a literal would type something else entirely.
        assertEquals(LayoutAction.kanaModifier, bottom[0].action)
        assertNull(bottom[0].action.value)
        assertNull(bottom[0].titleMode)
        bottom[0].action.validate()
        // わ taps its own row.
        assertEquals(LayoutAction.kana("wa"), bottom[1].action)
        // The cap taps 、; the other four marks are flick targets of the kana model.
        assertEquals(LayoutAction.literal("、"), bottom[2].action)
        assertNull(bottom[2].titleMode)
    }

    @Test
    fun theLeadingRailCarriesTheCursorSymbolsLanguageAndSystemSwitch() {
        val leading = rail(LayoutAuxiliaryArea.Position.LEADING)
        assertEquals(LayoutAuxiliaryArea.Role.EDITING, leading.role)
        assertEquals(
            listOf(
                LayoutActionKind.CURSOR_LEFT,
                LayoutActionKind.PAGE_SWITCH,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.GLOBE,
            ),
            leading.keys.map { it.action.type },
        )
        // A kind of its own rather than a code: the controller sends the engine's own Left key so a
        // replacement typed after the caret move cannot overtake it.
        assertEquals(LayoutAction.cursorLeft, leading.keys[0].action)
        assertEquals("←", leading.keys[0].label)
        assertNull(leading.keys[0].action.value)
        leading.keys[0].action.validate()
        assertEquals(LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS), leading.keys[1].action)
        assertEquals(LayoutAction.languageSwitch(), leading.keys[2].action)
        assertEquals(LayoutAction(LayoutActionKind.GLOBE), leading.keys[3].action)
        // Each rail key is one row tall; the rail carries no width of its own.
        assertTrue(leading.keys.all { it.width == null })
    }

    @Test
    fun theTrailingRailCarriesDeleteSpaceAndATwoRowReturn() {
        val trailing = rail(LayoutAuxiliaryArea.Position.TRAILING)
        assertEquals(LayoutAuxiliaryArea.Role.EDITING, trailing.role)
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.SPACE, LayoutActionKind.ENTER),
            trailing.keys.map { it.action.type },
        )
        // A rail key's width is its vertical span: Return covers the lower two rows through the
        // existing field, not a height metadata of its own.
        assertEquals(listOf(null, null, 2.0), trailing.keys.map { it.width })
        // The rail spans the same four rows as the grid.
        assertEquals(4.0, trailing.keys.sumOf { it.widthWeight }, 1e-9)
    }

    @Test
    fun onlyTheSystemControlsAreAutomatic() {
        for (cap in layout.rows.flatMap { it.keys }) {
            assertNull("the authored Japanese cap " + cap.label + " is its own face", cap.titleMode)
        }
        val railKeys = (layout.auxiliaryAreas ?: emptyList()).flatMap { it.keys }
        assertEquals(
            listOf("記号", "あ/A", "🌐", "⌫", "空格", "⏎"),
            railKeys.filter { it.titleMode == LayoutKeyTitleMode.AUTOMATIC }.map { it.label },
        )
        // Only the named caret key keeps a stored face: no control draws it.
        assertEquals(listOf("←"), railKeys.filter { it.titleMode == null }.map { it.label })
    }

    @Test
    fun theBoardDeclaresItsOwnRowsSoNoShellReplacesThem() {
        // The fourth row types kana and marks, so it is content, not a control row.
        assertTrue(
            layout.rows
                .last()
                .keys
                .any { it.action.type == LayoutActionKind.KANA },
        )
        assertTrue(
            layout.rows
                .last()
                .keys
                .none { it.action.type == LayoutActionKind.CODE },
        )
        assertFalse(PhoneKeypadStructure.matches(layout))
        assertFalse(CompactSpellingLayout.matches(layout))
        assertNull(LetterGroupLayout.groups(layout))
        assertNull(BuiltinLayouts.handSplit(layout))
        assertNull(layout.t9FirstKeyBehavior)
        assertNull(layout.t9QuickSymbols)
    }

    @Test
    fun theBoardAsksForTheCandidateBarWithoutAReadingRow() {
        // The encoding kind stays the 26-key one: the board is identified by its id.
        assertEquals(LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26), layout.input)
        assertNull(layout.input.schemaID)
        assertNull(layout.note)
        assertNull(layout.split)
        // The kana rows reach the engine through the kana batch, so the board still needs the
        // candidate bar even though no key is a Latin code key.
        assertTrue(layout.sendsEngineCode)
        assertEquals(LayoutCandidateArea(candidates = true, reading = false), layout.effectiveCandidateArea)
        layout.validate()
    }

    @Test
    fun theBoardRoundTripsThroughTheCodec() {
        assertEquals(layout, LayoutJson.decode(layout.encoded()))
        // The new wire values are the decoder contract: an older build rejects them instead of
        // drawing a kana cap as a letter.
        val encoded = layout.encoded()
        assertTrue(encoded.contains("\"type\": \"kana\""))
        assertTrue(encoded.contains("\"type\": \"kanaModifier\""))
        assertTrue(encoded.contains("\"type\": \"cursorLeft\""))
        assertFalse(encoded.contains("\"asciiValue\""))
    }

    @Test
    fun aKanaDefinitionWithAnUnknownRowIsRejectedByTheCodec() {
        val bad = kanaOrKanaLike("\"kana\"", "\"left\"")
        val error = assertThrows(LayoutException::class.java) { LayoutJson.decode(bad) }
        assertEquals(LayoutInvalidCode.KANA_REQUIRES_KNOWN_ROW, (error as LayoutException.Invalid).code)
        // A foreign kind name is not silently drawn either.
        val unknownKind = kanaOrKanaLike("\"kanaRow\"", "\"a\"")
        val malformed = assertThrows(LayoutException::class.java) { LayoutJson.decode(unknownKind) }
        assertEquals(LayoutInvalidCode.MALFORMED_JSON, (malformed as LayoutException.Invalid).code)
    }

    /** One single-key definition in the shared JSON shape, so the codec itself is what is checked. */
    private fun kanaOrKanaLike(
        type: String,
        value: String,
    ): String =
        "{\"id\": \"kana12-bad\", \"name\": \"假名\", " +
            "\"input\": {\"builtinLayout\": \"qwerty26\"}, " +
            "\"rows\": [{\"keys\": [{\"label\": \"あ\", " +
            "\"action\": {\"type\": " + type + ", \"value\": " + value + "}}]}]}"

    @Test
    fun theKanaKindsValidateTheirOwnValues() {
        for (rowID in LayoutAction.KANA_ROW_IDS) {
            LayoutAction.kana(rowID).validate()
        }
        assertEquals(
            listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", "wa", "punctuation"),
            LayoutAction.KANA_ROW_IDS,
        )
        for (unknown in listOf("", "kya", "A", "ka ", "かな", "left")) {
            val error = assertThrows(LayoutException::class.java) { LayoutAction.kana(unknown).validate() }
            val code = (error as LayoutException.Invalid).code
            assertEquals(unknown, LayoutInvalidCode.KANA_REQUIRES_KNOWN_ROW, code)
        }
        for (kind in listOf(LayoutActionKind.KANA_MODIFIER, LayoutActionKind.CURSOR_LEFT)) {
            LayoutAction(kind).validate()
            val error = assertThrows(LayoutException::class.java) { LayoutAction(kind, value = "a").validate() }
            val code = (error as LayoutException.Invalid).code
            assertEquals(kind.name, LayoutInvalidCode.KEY_OF_KIND_TAKES_NO_VALUE, code)
        }
    }

    @Test
    fun onlyCodeAndKanaReachTheEngine() {
        // A Latin code key still takes exactly one printable ASCII character: the kana repertoire
        // must not loosen it, since a romaji spelling or a named key is not one engine key.
        assertTrue(LayoutAction.code("k").isEngineInput)
        for (invalid in listOf("ka", "Left", "kana", "")) {
            assertThrows(LayoutException::class.java) { LayoutAction.code(invalid).validate() }
        }
        assertTrue(LayoutAction.kana("ka").isEngineInput)
        assertFalse(LayoutAction.cursorLeft.isEngineInput)
        assertFalse(LayoutAction.kanaModifier.isEngineInput)
        assertFalse(LayoutAction.literal("、").isEngineInput)
        // A kana cap is input, so a layout of nothing else still requests the candidate bar.
        val kanaOnly =
            LayoutDefinition(
                id = "kana-only",
                name = "假名",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = listOf(LayoutRow(listOf(LayoutKey("あ", action = LayoutAction.kana("a"))))),
            )
        assertTrue(kanaOnly.sendsEngineCode)
        assertEquals(LayoutCandidateArea(candidates = true, reading = false), kanaOnly.effectiveCandidateArea)
    }

    @Test
    fun theJapaneseFamilyOffersTheBoardButKeepsTheRomajiDefault() {
        val registry = LayoutRegistry.create()
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi")))
        assertEquals("romaji26", registry.composition(key("kagiroi")).mainLayoutID)
        assertTrue(registry.mainLayoutCandidates(key("kagiroi")).map { it.id }.contains("kana12"))
        // Availability is the Japanese family's own: another family never offers the kana board.
        for (other in listOf("bopomofo_tw", "cangjie5", "rime_ice", "jyut6ping3")) {
            val offered = registry.mainLayoutCandidates(key(other)).map { it.id }
            assertFalse(other, offered.contains("kana12"))
        }
        // A pairing saved on the kana board reads back as that board, untouched.
        val saved = KeyboardComposition(mainLayoutID = "kana12")
        val chosen = LayoutRegistry.create(compositions = mapOf("kagiroi:qwerty26" to saved))
        assertEquals("kana12", chosen.composition(key("kagiroi")).mainLayoutID)
        assertEquals(saved, chosen.compositions.getValue("kagiroi:qwerty26"))
    }

    @Test
    fun theShapePresetOfTheIdentityKindIsStillThe26KeyBoard() {
        // The board keeps the 26-key identity transport, so a shape preset never becomes kana12.
        assertEquals(BuiltinLayouts.qwerty26, BuiltinLayouts.preset(LayoutInput.Builtin.QWERTY26))
        assertEquals(
            setOf(KeyboardLayoutKind.QWERTY26),
            KeyboardLayoutKind.entries
                .filter { InputLayoutPolicy.allows(it, InputSchemeFamily.JAPANESE) }
                .toSet(),
        )
        assertNotNull(BuiltinLayouts.byId(InputLayoutPolicy.ROMAJI_LAYOUT))
    }
}

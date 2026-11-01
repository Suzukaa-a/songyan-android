package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutDefinitionTest {
    @Test
    fun everySyncedExampleDecodes() {
        for (path in LayoutFixtures.all) {
            val layout = LayoutFixtures.readDefinition(path)
            assertTrue(path, layout.rows.isNotEmpty())
            layout.validate()
        }
    }

    @Test
    fun qwertyExampleParsesTheAuthoredFields() {
        val layout = LayoutFixtures.readDefinition(LayoutFixtures.QWERTY26)
        assertEquals("qwerty26", layout.id)
        assertEquals("26 键", layout.name)
        assertEquals(LayoutInput.Builtin.QWERTY26, layout.input.builtinLayout)
        assertNull(layout.input.schemaID)
        assertEquals(0.35, layout.effectiveLongPressDelay, 1e-9)
        assertEquals(4, layout.rows.size)
        assertEquals(listOf(10, 9, 7, 5), layout.rows.map { it.keys.size })

        val q = layout.rows[0].keys[0]
        assertEquals("q", q.label)
        assertEquals("1", q.secondaryLabel)
        assertEquals(LayoutAction.code("q"), q.action)
        assertEquals(listOf(LayoutOption("1", LayoutAction.literal("1"))), q.longPress)

        // Control row: relative width is the space bar only.
        val controls = layout.rows[3].keys
        assertEquals(
            listOf(
                LayoutActionKind.MODE_SWITCH,
                LayoutActionKind.GLOBE,
                LayoutActionKind.SPACE,
                LayoutActionKind.BACKSPACE,
                LayoutActionKind.ENTER,
            ),
            controls.map { it.action.type },
        )
        assertEquals(4.0, controls[2].widthWeight, 1e-9)
        assertEquals("nextLayout", controls[0].action.value)
    }

    @Test
    fun phone9ExampleParsesAuxiliaryAreas() {
        val layout = LayoutFixtures.readDefinition(LayoutFixtures.PHONE9)
        val areas = layout.auxiliaryAreas!!
        assertEquals(2, areas.size)
        assertEquals(LayoutAuxiliaryArea.Position.TRAILING, areas[0].position)
        assertEquals(LayoutAuxiliaryArea.Role.EDITING, areas[0].role)
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CODE, LayoutActionKind.ENTER),
            areas[0].keys.map { it.action.type },
        )
        // The 9-key rail's separator key sends the apostrophe spelling code.
        assertEquals("'", areas[0].keys[1].action.value)
        assertEquals(LayoutAuxiliaryArea.Position.LEADING, areas[1].position)
        assertEquals(LayoutAuxiliaryArea.Role.DISAMBIGUATION, areas[1].role)
        assertTrue(areas[1].keys.isEmpty())
        assertTrue(areas[1].requiresEngineDisambiguation)
    }

    @Test
    fun merged18ExampleParsesRelativeWidths() {
        val layout = LayoutFixtures.readDefinition(LayoutFixtures.MERGED18)
        assertEquals(listOf(7, 6, 5, 5), layout.rows.map { it.keys.size })
        val singles = layout.rows[0].keys.filter { it.label.length == 1 }
        val pairs = layout.rows[0].keys.filter { it.label.length == 2 }
        assertTrue(singles.all { it.widthWeight == 1.3 })
        assertTrue(pairs.all { it.widthWeight == 1.7 })
    }

    @Test
    fun unknownFieldsAreRejected() {
        val json =
            LayoutFixtures.readText(LayoutFixtures.QWERTY26).replaceFirst(
                "\"id\": \"qwerty26\",",
                "\"id\": \"qwerty26\", \"unknownField\": 1,",
            )
        assertThrows(LayoutException::class.java) { LayoutJson.decode(json) }
    }

    @Test
    fun malformedJsonIsRejected() {
        assertThrows(LayoutException::class.java) { LayoutJson.decode("{}") }
        assertThrows(LayoutException::class.java) { LayoutJson.decode("not json") }
    }

    @Test
    fun oversizedDefinitionIsRejectedBeforeDecoding() {
        val bytes = ByteArray(LayoutResourceBudget.DEFINITION_BYTES + 1) { ' '.code.toByte() }
        val error = assertThrows(LayoutException::class.java) { LayoutJson.decode(bytes) }
        assertEquals(
            LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun roundTripPreservesEveryParsedField() {
        for (path in LayoutFixtures.all) {
            val layout = LayoutFixtures.readDefinition(path)
            assertEquals(path, layout, LayoutJson.decode(layout.encoded()))
        }
    }

    @Test
    fun encodedFormOmitsNullFields() {
        val encoded = LayoutFixtures.readDefinition(LayoutFixtures.PHONE9).encoded()
        assertFalse(encoded.contains("\"split\""))
        assertFalse(encoded.contains("\"candidateArea\""))
        assertTrue(encoded.contains("\"schemaID\"") == false)
    }

    // MARK: Action validation mirrors the authored contract

    @Test
    fun codeRequiresOnePrintableAsciiCharacter() {
        LayoutAction.code("a").validate()
        for (bad in listOf("", "ab", "中", " ")) {
            assertThrows(LayoutException::class.java) { LayoutAction.code(bad).validate() }
        }
    }

    @Test
    fun literalRequiresNonemptyValueAndRejectsEmptyAsciiCounterpart() {
        LayoutAction.literal("，").validate()
        LayoutAction.literal("。", ":").validate()
        assertThrows(LayoutException::class.java) { LayoutAction.literal("").validate() }
        assertThrows(LayoutException::class.java) { LayoutAction.literal("x", "").validate() }
    }

    @Test
    fun asciiValueIsLiteralOnly() {
        val error =
            assertThrows(LayoutException::class.java) {
                LayoutAction(LayoutActionKind.SPACE, asciiValue = "x").validate()
            }
        assertEquals(
            LayoutInvalidCode.ASCII_VALUE_ONLY_ON_LITERAL,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun modeSwitchOnlySupportsNextLayout() {
        LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout").validate()
        LayoutAction(LayoutActionKind.MODE_SWITCH).validate()
        assertThrows(LayoutException::class.java) {
            LayoutAction(LayoutActionKind.MODE_SWITCH, "runScript").validate()
        }
    }

    @Test
    fun pageSwitchAndLanguageSwitchRequireKnownTargets() {
        LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS).validate()
        assertEquals(
            LayoutPageTarget.SYMBOLS,
            LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS).pageSwitchTarget,
        )
        assertEquals(
            LayoutLanguageSwitchBehavior.LETTERS,
            LayoutAction.languageSwitch(LayoutLanguageSwitchBehavior.LETTERS).languageSwitchBehavior,
        )
        assertThrows(LayoutException::class.java) {
            LayoutAction(LayoutActionKind.PAGE_SWITCH, "home").validate()
        }
        assertThrows(LayoutException::class.java) {
            LayoutAction(LayoutActionKind.LANGUAGE_SWITCH, "toggleEverything").validate()
        }
    }

    @Test
    fun functionKeysTakeNoValue() {
        for (
        kind in
        listOf(
            LayoutActionKind.SPACE,
            LayoutActionKind.BACKSPACE,
            LayoutActionKind.ENTER,
            LayoutActionKind.GLOBE,
            LayoutActionKind.SHIFT,
            LayoutActionKind.SYMBOL_LOCK,
            LayoutActionKind.CLEAR_INPUT,
        )
        ) {
            LayoutAction(kind).validate()
            assertThrows(LayoutException::class.java) { LayoutAction(kind, "x").validate() }
        }
    }

    @Test
    fun literalValueResolvesTheAsciiBranch() {
        val pair = LayoutAction.literal("。", ".")
        assertEquals("。", pair.literalValue(usesAsciiPunctuation = false))
        assertEquals(".", pair.literalValue(usesAsciiPunctuation = true))
        val fixed = LayoutAction.literal("。")
        assertEquals("。", fixed.literalValue(usesAsciiPunctuation = true))
        assertNull(LayoutAction(LayoutActionKind.SPACE).literalValue(usesAsciiPunctuation = true))
    }

    // MARK: Definition validation

    private fun definition(
        id: String = "d",
        name: String = "n",
        input: LayoutInput = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
        rows: List<LayoutRow> =
            listOf(LayoutRow(listOf(LayoutKey("a", action = LayoutAction.code("a"))))),
    ) = LayoutDefinition(id = id, name = name, input = input, rows = rows)

    @Test
    fun idNameAndInputAreRequired() {
        assertThrows(LayoutException::class.java) { definition(id = " ").validate() }
        assertThrows(LayoutException::class.java) { definition(name = "").validate() }
        assertThrows(LayoutException::class.java) { definition(input = LayoutInput()).validate() }
        assertThrows(LayoutException::class.java) {
            definition(input = LayoutInput(schemaID = " ")).validate()
        }
        assertThrows(LayoutException::class.java) { definition(rows = emptyList()).validate() }
        assertThrows(LayoutException::class.java) {
            definition(rows = listOf(LayoutRow(emptyList()))).validate()
        }
    }

    @Test
    fun longPressDelayMustBePositiveFinite() {
        for (delay in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows("delay=" + delay, LayoutException::class.java) {
                definition().copy(longPressDelay = delay).validate()
            }
        }
        definition().copy(longPressDelay = 0.2).validate()
        assertEquals(0.35, definition().effectiveLongPressDelay, 1e-9)
    }

    @Test
    fun keysRequireLabelAndPositiveFiniteWidth() {
        assertThrows(LayoutException::class.java) {
            definition(
                rows =
                    listOf(
                        LayoutRow(
                            listOf(LayoutKey("", action = LayoutAction.literal("x"))),
                        ),
                    ),
            ).validate()
        }
        for (width in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(LayoutException::class.java) {
                definition(
                    rows =
                        listOf(
                            LayoutRow(
                                listOf(
                                    LayoutKey(
                                        "x",
                                        width = width,
                                        action = LayoutAction.literal("x"),
                                    ),
                                ),
                            ),
                        ),
                ).validate()
            }
        }
    }

    @Test
    fun longPressOptionsRequireLabels() {
        assertThrows(LayoutException::class.java) {
            definition(
                rows =
                    listOf(
                        LayoutRow(
                            listOf(
                                LayoutKey(
                                    "a",
                                    action = LayoutAction.code("a"),
                                    longPress = listOf(LayoutOption("", LayoutAction.literal("x"))),
                                ),
                            ),
                        ),
                    ),
            ).validate()
        }
    }

    @Test
    fun disambiguationAreasMustNotCarryKeys() {
        val withKeys =
            definition().copy(
                auxiliaryAreas =
                    listOf(
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.LEADING,
                            role = LayoutAuxiliaryArea.Role.DISAMBIGUATION,
                            keys = listOf(LayoutKey("x", action = LayoutAction.literal("x"))),
                        ),
                    ),
            )
        val error = assertThrows(LayoutException::class.java) { withKeys.validate() }
        assertEquals(
            LayoutInvalidCode.DISAMBIGUATION_KEYS_MUST_BE_ENGINE_SUPPLIED,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun readingRowRequiresACandidateArea() {
        val layout =
            definition().copy(candidateArea = LayoutCandidateArea(candidates = false, reading = true))
        val error = assertThrows(LayoutException::class.java) { layout.validate() }
        assertEquals(
            LayoutInvalidCode.READING_ROW_REQUIRES_CANDIDATE_AREA,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun codeKeysRequireACandidateArea() {
        val layout =
            definition().copy(candidateArea = LayoutCandidateArea(candidates = false, reading = false))
        val error = assertThrows(LayoutException::class.java) { layout.validate() }
        assertEquals(
            LayoutInvalidCode.CODE_KEYS_REQUIRE_CANDIDATE_AREA,
            (error as LayoutException.Invalid).code,
        )
    }

    @Test
    fun effectiveCandidateAreaDerivesFromTheKeyStructure() {
        // A 26-key layout sends codes, so it needs the candidate row but no reading row.
        val qwerty = BuiltinLayouts.qwerty26
        assertTrue(qwerty.sendsEngineCode)
        assertEquals(LayoutCandidateArea(candidates = true, reading = false), qwerty.effectiveCandidateArea)
        // A literal-only layout needs neither.
        val literals =
            definition(
                rows =
                    listOf(
                        LayoutRow(listOf(LayoutKey("x", action = LayoutAction.literal("x")))),
                    ),
            )
        assertFalse(literals.sendsEngineCode)
        assertEquals(
            LayoutCandidateArea(candidates = false, reading = false),
            literals.effectiveCandidateArea,
        )
    }

    @Test
    fun keyPathsAddressRowsAndAuxiliaryAreas() {
        val phone = BuiltinLayouts.phone9
        assertEquals(
            phone.rows[0].keys[1],
            phone.key(LayoutKeyPath.Row(row = 0, key = 1)),
        )
        assertEquals(
            phone.auxiliaryAreas!![0].keys[0],
            phone.key(LayoutKeyPath.Auxiliary(area = 0, key = 0)),
        )
        assertNull(phone.key(LayoutKeyPath.Row(row = 99, key = 0)))
        assertNull(phone.key(LayoutKeyPath.Auxiliary(area = 99, key = 0)))
        // Out-of-range replacements are ignored rather than throwing.
        assertEquals(phone, phone.replacingKey(LayoutKeyPath.Row(row = 99, key = 0), phone.rows[0].keys[0]))
    }

    @Test
    fun replacingAKeyKeepsEveryOtherField() {
        val phone = BuiltinLayouts.phone9
        val path = LayoutKeyPath.Auxiliary(area = 0, key = 0)
        val replacement = phone.key(path)!!.copy(secondaryLabel = "问号", width = 1.5)
        val edited = phone.replacingKey(path, replacement)
        assertEquals(replacement, edited.key(path))
        assertEquals(phone.rows, edited.rows)
        assertEquals(phone.note, edited.note)
        assertEquals(phone.auxiliaryAreas!!.size, edited.auxiliaryAreas!!.size)
    }

    @Test
    fun draftEditsMayBeInvalidUntilSaveValidates() {
        val phone = BuiltinLayouts.phone9
        val path = LayoutKeyPath.Auxiliary(area = 0, key = 0)
        val invalid = phone.replacingKey(path, LayoutKey("坏", action = LayoutAction.code("abc")))
        // Constructing the draft is allowed; validating the whole draft is not.
        assertThrows(LayoutException::class.java) { invalid.validate() }
    }
}

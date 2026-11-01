package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/PhoneKeypadStructureTests and PhoneKeypadRailPolicyTests. */
class PhoneKeypadStructureTest {
    private fun digit(
        value: String,
        label: String? = null,
    ): LayoutKey = LayoutKey(label ?: value, secondaryLabel = value, action = LayoutAction.code(value))

    private fun oneKey(label: String = "，。？"): LayoutKey =
        LayoutKey(
            label,
            secondaryLabel = "1",
            action = LayoutAction.literal("，"),
            longPress = listOf(LayoutOption("1", LayoutAction.literal("1"))),
        )

    private fun controls(): LayoutRow =
        LayoutRow(
            listOf(
                LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")),
                LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE)),
            ),
        )

    private fun phoneRows(
        first: LayoutKey? = null,
        digits: List<LayoutKey>? = null,
        trailingControls: Boolean = false,
    ): List<LayoutRow> {
        val values = digits ?: listOf("2", "3", "4", "5", "6", "7", "8", "9").map { digit(it) }
        val flat = listOf(first ?: oneKey()) + values
        val rows = mutableListOf<LayoutRow>()
        for (index in 0 until 3) rows.add(LayoutRow(flat.subList(index * 3, index * 3 + 3)))
        if (trailingControls) rows.add(controls())
        return rows
    }

    private fun definition(
        id: String = "custom-phone",
        rows: List<LayoutRow>,
        areas: List<LayoutAuxiliaryArea>? = null,
    ): LayoutDefinition =
        LayoutDefinition(
            id = id,
            name = "导入九键",
            input = LayoutInput(schemaID = "imported_t9"),
            rows = rows,
            auxiliaryAreas = areas,
        )

    @Test
    fun bundledT9MatchesThePhoneStructure() {
        assertTrue(PhoneKeypadStructure.matches(BuiltinLayouts.phone9))
        assertEquals(3, PhoneKeypadStructure.mainRows(BuiltinLayouts.phone9)!!.size)
    }

    @Test
    fun canonicalImportedPhoneKeypadMatches() {
        val imported = definition(rows = phoneRows())
        assertTrue(PhoneKeypadStructure.matches(imported))
        assertEquals(3, PhoneKeypadStructure.mainRows(imported)!!.size)
        assertTrue(PhoneKeypadStructure.matches(definition(rows = phoneRows(trailingControls = true))))
    }

    @Test
    fun firstSlotMayBePunctuationWithLongPressOneOrPlainOne() {
        assertTrue(PhoneKeypadStructure.matches(definition(rows = phoneRows(first = oneKey()))))
        assertTrue(
            PhoneKeypadStructure.matches(
                definition(rows = phoneRows(first = LayoutKey("1", action = LayoutAction.code("1")))),
            ),
        )
        assertTrue(
            PhoneKeypadStructure.matches(
                definition(rows = phoneRows(first = LayoutKey("1", action = LayoutAction.literal("1")))),
            ),
        )
    }

    @Test
    fun duplicateMissingAndMisorderedDigitsAreRejected() {
        val canonical = listOf("2", "3", "4", "5", "6", "7", "8", "9").map { digit(it) }
        val duplicate = canonical.toMutableList().also { it[6] = digit("9") }
        assertNull(PhoneKeypadStructure.mainRows(definition(rows = phoneRows(digits = duplicate))))
        val missing =
            listOf("2", "3", "4", "5", "6", "7", "8").map { digit(it) } +
                listOf(LayoutKey("，", action = LayoutAction.literal("，")))
        assertNull(PhoneKeypadStructure.mainRows(definition(rows = phoneRows(digits = missing))))
        val misordered = listOf("3", "2", "4", "5", "6", "7", "8", "9").map { digit(it) }
        assertNull(PhoneKeypadStructure.mainRows(definition(rows = phoneRows(digits = misordered))))
    }

    @Test
    fun wrongFirstSlotIsRejected() {
        assertNull(
            PhoneKeypadStructure.mainRows(
                definition(rows = phoneRows(first = LayoutKey("abc", action = LayoutAction.code("a")))),
            ),
        )
    }

    @Test
    fun qwertyAndMergedLayoutsNeverMatch() {
        for (layout in BuiltinLayouts.all) {
            if (layout.id == "phone9") continue
            assertNull(layout.id, PhoneKeypadStructure.mainRows(layout))
            assertFalse(layout.id, PhoneKeypadStructure.matches(layout))
        }
    }

    @Test
    fun calculatorNumericGridIsRejected() {
        val layout = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3")
        val rows =
            (0 until 3).map { row ->
                LayoutRow(
                    (0 until 3).map { column ->
                        val value = layout[row * 3 + column]
                        LayoutKey(value, action = LayoutAction.code(value))
                    },
                )
            }
        assertFalse(
            PhoneKeypadStructure.matches(definition(id = "calc", rows = rows + listOf(controls()))),
        )
    }

    @Test
    fun threeByThreeLetterOrSymbolGridIsNotAPhoneKeypad() {
        val letters = "abcdefghi".map { it.toString() }
        val letterRows =
            (0 until 3).map { row ->
                LayoutRow(
                    (0 until 3).map { column ->
                        LayoutKey(
                            letters[row * 3 + column],
                            action =
                                LayoutAction.code(
                                    letters[
                                        row *
                                            3 +
                                            column,
                                    ],
                                ),
                        )
                    },
                )
            }
        assertFalse(PhoneKeypadStructure.matches(definition(rows = letterRows + listOf(controls()))))

        val symbols = listOf("@", "#", "-", "+", "(", ")", "/", "*", "=")
        val symbolRows =
            (0 until 3).map { row ->
                LayoutRow(
                    (0 until 3).map { column ->
                        val value = symbols[row * 3 + column]
                        LayoutKey(value, action = LayoutAction.literal(value))
                    },
                )
            }
        assertFalse(PhoneKeypadStructure.matches(definition(id = "symbols9", rows = symbolRows + listOf(controls()))))
    }

    @Test
    fun rowsWithWrongKeyCountAreRejected() {
        val rows =
            listOf(
                LayoutRow(listOf(digit("2"), digit("3"))),
                LayoutRow(listOf(digit("4"), digit("5"))),
                LayoutRow(listOf(digit("6"), digit("7"))),
                LayoutRow(listOf(LayoutKey("空格", action = LayoutAction(LayoutActionKind.SPACE)))),
            )
        assertFalse(PhoneKeypadStructure.matches(definition(id = "twoCol", rows = rows)))
    }

    @Test
    fun threeRowPadWithoutControlRowIsAccepted() {
        assertTrue(PhoneKeypadStructure.matches(definition(rows = phoneRows())))
    }

    // MARK: Rail policy

    @Test
    fun bundledT9AlwaysGetsItsAuthoredRail() {
        for (declared in listOf(0, 1, 2, 5)) {
            assertEquals(
                PhoneKeypadRailContent.BundledAuthored,
                PhoneKeypadRailPolicy.trailing(isBuiltinPhonePad = true, declaredKeyCount = declared),
            )
            assertEquals(
                PhoneKeypadRailContent.BundledAuthored,
                PhoneKeypadRailPolicy.leading(isBuiltinPhonePad = true, declaredKeyCount = declared),
            )
        }
        assertEquals(listOf(1, 1, 2), PhoneKeypadRailPolicy.BUNDLED_TRAILING_SPANS)
    }

    @Test
    fun importedKeypadWithNoDeclaredRailRendersNothing() {
        assertEquals(
            PhoneKeypadRailContent.Empty,
            PhoneKeypadRailPolicy.trailing(isBuiltinPhonePad = false, declaredKeyCount = 0),
        )
        assertEquals(
            PhoneKeypadRailContent.Empty,
            PhoneKeypadRailPolicy.leading(isBuiltinPhonePad = false, declaredKeyCount = 0),
        )
    }

    @Test
    fun importedKeypadRendersExactlyItsDeclaredKeys() {
        for (count in listOf(1, 2, 3, 7)) {
            assertEquals(
                PhoneKeypadRailContent.Declared(count),
                PhoneKeypadRailPolicy.trailing(isBuiltinPhonePad = false, declaredKeyCount = count),
            )
        }
    }

    @Test
    fun authoredRailIsUnreachableForImports() {
        for (declared in 0..12) {
            assertTrue(
                PhoneKeypadRailPolicy.trailing(isBuiltinPhonePad = false, declaredKeyCount = declared) !=
                    PhoneKeypadRailContent.BundledAuthored,
            )
            assertTrue(
                PhoneKeypadRailPolicy.leading(isBuiltinPhonePad = false, declaredKeyCount = declared) !=
                    PhoneKeypadRailContent.BundledAuthored,
            )
        }
    }

    @Test
    fun declaredRailKeysAreReadVerbatim() {
        val definition =
            definition(
                id = "import-rail",
                rows = phoneRows(),
                areas =
                    listOf(
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.TRAILING,
                            role = LayoutAuxiliaryArea.Role.EDITING,
                            keys =
                                listOf(
                                    LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                                    LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)),
                                ),
                        ),
                    ),
            )
        val declared = PhoneKeypadRailPolicy.declaredKeys(definition, LayoutAuxiliaryArea.Position.TRAILING)
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.ENTER),
            declared.map { it.action.type },
        )
        assertTrue(PhoneKeypadRailPolicy.declaredKeys(definition, LayoutAuxiliaryArea.Position.LEADING).isEmpty())
    }

    @Test
    fun engineDisambiguationAreasAreExcludedFromRails() {
        val definition =
            definition(
                id = "import-disambig",
                rows = phoneRows(),
                areas =
                    listOf(
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.LEADING,
                            role = LayoutAuxiliaryArea.Role.DISAMBIGUATION,
                        ),
                    ),
            )
        assertTrue(PhoneKeypadRailPolicy.declaredKeys(definition, LayoutAuxiliaryArea.Position.LEADING).isEmpty())
        assertTrue(PhoneKeypadRailPolicy.declaredKeys(definition, LayoutAuxiliaryArea.Position.TRAILING).isEmpty())
    }

    @Test
    fun declaredControlRowIsPreservedForImports() {
        val rows = phoneRows(trailingControls = true)
        val definition = definition(id = "import-controls", rows = rows)
        assertEquals(rows.last().keys, PhoneKeypadRailPolicy.controlRowKeys(definition))
        assertEquals(
            listOf(LayoutActionKind.MODE_SWITCH, LayoutActionKind.SPACE),
            PhoneKeypadRailPolicy.controlRowKeys(definition)!!.map { it.action.type },
        )
        // A pad with no Delete anywhere stays without one.
        assertFalse(
            PhoneKeypadRailPolicy.controlRowKeys(definition)!!.any {
                it.action.type == LayoutActionKind.BACKSPACE
            },
        )
    }

    @Test
    fun phone9ControlRowIsTheDeclaredBottomRow() {
        assertEquals(
            BuiltinLayouts.phone9.rows
                .last()
                .keys,
            PhoneKeypadRailPolicy.controlRowKeys(BuiltinLayouts.phone9),
        )
        // The three letter rows are not a control row.
        for (layout in listOf(BuiltinLayouts.qwerty26, BuiltinLayouts.merged14)) {
            assertNull(PhoneKeypadRailPolicy.controlRowKeys(layout.copy(rows = layout.rows.take(3))))
        }
    }
}

/** Ported from LayoutKitTests/CompactSpellingLayoutTests. */
class CompactSpellingLayoutTest {
    private fun code(value: String) = LayoutKey(value, secondaryLabel = value, action = LayoutAction.code(value))

    private fun literal(value: String) = LayoutKey(value, action = LayoutAction.literal(value))

    private fun oneKey() =
        LayoutKey(
            "，。？",
            secondaryLabel = "1",
            action = LayoutAction.literal("，"),
            longPress = listOf(LayoutOption("1", LayoutAction.literal("1"))),
        )

    private fun controls() =
        LayoutRow(
            listOf(
                LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")),
                LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE)),
                LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)),
            ),
        )

    private fun definition(
        id: String,
        rows: List<LayoutRow>,
        builtin: LayoutInput.Builtin? = null,
        schemaID: String? = null,
    ) = LayoutDefinition(
        id = id,
        name = id,
        input = LayoutInput(schemaID = schemaID, builtinLayout = builtin),
        rows = rows,
    )

    private fun telephoneRows(trailingControls: Boolean = false): List<LayoutRow> {
        val flat = listOf(oneKey()) + listOf("2", "3", "4", "5", "6", "7", "8", "9").map { code(it) }
        val rows = (0 until 3).map { LayoutRow(flat.subList(it * 3, it * 3 + 3)) }.toMutableList()
        if (trailingControls) rows.add(controls())
        return rows
    }

    private fun qwerty26Rows(): List<LayoutRow> =
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row ->
            LayoutRow(row.map { code(it.toString()) })
        } + listOf(controls())

    private fun group(letters: String) = LayoutKey(letters.uppercase(), action = LayoutAction.code(letters.take(1)))

    private fun merged14Rows(trailingControls: Boolean = true): List<LayoutRow> {
        val groups =
            listOf(
                listOf("qw", "er", "ty", "ui", "op"),
                listOf("as", "df", "gh", "jk"),
                listOf("l", "zx", "cv", "bn", "m"),
            )
        val rows = groups.map { row -> LayoutRow(row.map { group(it) }) }
        return if (trailingControls) rows + listOf(controls()) else rows
    }

    @Test
    fun bundledCompactLayoutsMatch() {
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.phone9))
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.merged14))
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.merged18))
        assertFalse(CompactSpellingLayout.matches(BuiltinLayouts.qwerty26))
        assertFalse(CompactSpellingLayout.matches(null))
    }

    @Test
    fun letterGroupsAreReadFromLabels() {
        val rows =
            listOf(
                LayoutRow(listOf("qwe", "rty", "uio", "p").map { group(it) }),
                LayoutRow(listOf("asd", "fgh", "jkl").map { group(it) }),
                LayoutRow(listOf("zxc", "vbn", "m").map { group(it) }),
            )
        assertTrue(CompactSpellingLayout.matches(definition(id = "triples", rows = rows + listOf(controls()))))
    }

    @Test
    fun mislabelledOrIncompleteGroupsAreRejected() {
        var rows = merged14Rows(trailingControls = false)
        // QW sending w: the key would not send its group's first letter.
        rows =
            listOf(LayoutRow(listOf(LayoutKey("QW", action = LayoutAction.code("w"))) + rows[0].keys.drop(1))) +
            rows.drop(1)
        assertFalse(CompactSpellingLayout.matches(definition(id = "wrongSent", rows = rows)))

        rows = merged14Rows(trailingControls = false)
        rows = rows.toMutableList().also { it[1] = LayoutRow(it[1].keys + listOf(group("aq"))) }
        assertFalse(CompactSpellingLayout.matches(definition(id = "overlap", rows = rows)))

        rows = merged14Rows(trailingControls = false)
        rows = rows.toMutableList().also { it[2] = LayoutRow(it[2].keys.dropLast(1)) }
        assertFalse(CompactSpellingLayout.matches(definition(id = "missing", rows = rows)))
    }

    @Test
    fun importedNineKeyAndFourteenKeyStillMatch() {
        assertTrue(CompactSpellingLayout.matches(definition("imp9", telephoneRows(), schemaID = "imported_t9")))
        assertTrue(
            CompactSpellingLayout.matches(
                definition("imp9c", telephoneRows(trailingControls = true), schemaID = "imported_t9"),
            ),
        )
        assertTrue(CompactSpellingLayout.matches(definition("imp14", merged14Rows(), schemaID = "imported_14")))
        assertTrue(
            CompactSpellingLayout.matches(
                definition("imp14bare", merged14Rows(trailingControls = false), schemaID = "imported_14"),
            ),
        )
    }

    @Test
    fun twentySixKeyRowsNeverMatchEvenWhenBuiltinIsMislabeled() {
        assertFalse(
            CompactSpellingLayout.matches(
                definition("bogus14", qwerty26Rows(), builtin = LayoutInput.Builtin.MERGED14),
            ),
        )
        assertFalse(
            CompactSpellingLayout.matches(definition("bogus9", qwerty26Rows(), builtin = LayoutInput.Builtin.PHONE9)),
        )
        assertFalse(CompactSpellingLayout.matches(definition("bogusSchema", qwerty26Rows(), schemaID = "imported_14")))
    }

    @Test
    fun nineKeyShapesThatAreNotTelephonePadsAreRejected() {
        val calculator = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3")
        val calculatorRows =
            (0 until 3).map { row ->
                LayoutRow((0 until 3).map { column -> code(calculator[row * 3 + column]) })
            } + listOf(controls())
        assertFalse(CompactSpellingLayout.matches(definition("calc", calculatorRows)))

        val symbols = listOf("@", "#", "-", "+", "(", ")", "/", "*", "=")
        val symbolRows =
            (0 until 3).map { row ->
                LayoutRow((0 until 3).map { column -> literal(symbols[row * 3 + column]) })
            } + listOf(controls())
        assertFalse(CompactSpellingLayout.matches(definition("symbols", symbolRows)))
    }

    @Test
    fun fourteenKeyRowsThatAreNotTheMergedShapeAreRejected() {
        val fourRows =
            listOf(
                LayoutRow(listOf("q", "w", "e", "r").map { code(it) }),
                LayoutRow(listOf("t", "y", "u", "i").map { code(it) }),
                LayoutRow(listOf("o", "p", "a", "s").map { code(it) }),
                LayoutRow(listOf("d", "f").map { code(it) }),
            )
        assertFalse(CompactSpellingLayout.matches(definition("fourRow14", fourRows + listOf(controls()))))
    }
}

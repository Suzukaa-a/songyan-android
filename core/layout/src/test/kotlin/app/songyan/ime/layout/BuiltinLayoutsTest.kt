package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The programmatic builtins must validate and stay in step with the synced author examples. */
class BuiltinLayoutsTest {
    @Test
    fun everyBuiltinValidatesAndRoundTrips() {
        for (builtin in BuiltinLayouts.all) {
            builtin.validate()
            assertEquals(builtin, LayoutJson.decode(builtin.encoded()))
        }
        assertEquals(
            listOf("qwerty26", "merged14", "merged18", "phone9", "zhuyin41", "cangjie26", "romaji26"),
            BuiltinLayouts.all.map { it.id },
        )
    }

    /** The examples are the copy source for authors, so they must match the declarations. */
    @Test
    fun examplesMatchTheBuiltinDeclarations() {
        for (builtin in BuiltinLayouts.all) {
            val example = LayoutFixtures.readDefinition("layouts/" + builtin.id + ".json")
            assertEquals(builtin.id, example.id)
            assertEquals(builtin.name, example.name)
            assertEquals(builtin.input, example.input)
            assertEquals(
                builtin.id,
                builtin.rows.flatMap { it.keys }.map { it.action },
                example.rows.flatMap { it.keys }.map { it.action },
            )
            assertEquals(
                builtin.id,
                builtin.rows.flatMap { it.keys }.map { it.width },
                example.rows.flatMap { it.keys }.map { it.width },
            )
            // The declaration decides whether alternates exist: the scheme-driven pads keep plain
            // code caps, so their examples carry none and the older layouts keep theirs.
            assertEquals(
                builtin.id,
                builtin.rows.flatMap { it.keys }.any { !it.longPress.isNullOrEmpty() },
                example.rows.flatMap { it.keys }.any { !it.longPress.isNullOrEmpty() },
            )
        }
    }

    @Test
    fun qwertyStructureMatchesTheSyncedExampleRowByRow() {
        val declared = BuiltinLayouts.qwerty26
        val example = LayoutFixtures.readDefinition(LayoutFixtures.QWERTY26)
        assertEquals(declared.rows.size, example.rows.size)
        for ((index, row) in declared.rows.withIndex()) {
            assertEquals("row " + index, row.keys.size, example.rows[index].keys.size)
            for ((keyIndex, key) in row.keys.withIndex()) {
                val other = example.rows[index].keys[keyIndex]
                assertEquals(key.label, other.label)
                assertEquals(key.secondaryLabel, other.secondaryLabel)
                assertEquals(key.action, other.action)
                assertEquals(key.width, other.width)
            }
        }
    }

    @Test
    fun qwertyTopRowCarriesDigitsAsSecondaryAndLongPress() {
        for (key in BuiltinLayouts.qwerty26.rows[0].keys) {
            assertNotNull(key.label, key.secondaryLabel)
            assertEquals(
                key.secondaryLabel,
                key.longPress!!
                    .single()
                    .action.value,
            )
        }
    }

    @Test
    fun qwertyEveryLetterHasLongPressAlternates() {
        val letters =
            BuiltinLayouts.qwerty26.rows
                .take(3)
                .flatMap { it.keys }
        assertEquals(26, letters.size)
        for (key in letters) {
            val options = key.longPress
            assertNotNull(key.label, options)
            assertTrue(key.label, options!!.isNotEmpty())
            assertTrue(
                key.label,
                options.all { it.action.type == LayoutActionKind.LITERAL },
            )
        }
        // The bunded default table is the one the declaration uses.
        assertEquals(
            listOf("，", ","),
            BuiltinLayouts.qwerty26.rows[1]
                .keys[0]
                .longPress!!
                .map { it.label },
        )
    }

    @Test
    fun merged14EveryKeyHasRealLongPressAlternates() {
        val keys =
            BuiltinLayouts.merged14.rows
                .dropLast(1)
                .flatMap { it.keys }
        assertEquals(14, keys.count())
        for (key in keys) {
            val options = key.longPress
            assertNotNull(key.label, options)
            assertFalse(key.label, options!!.isEmpty())
            assertTrue(
                key.label,
                options.all {
                    it.action.type == LayoutActionKind.LITERAL || it.action.type == LayoutActionKind.CODE
                },
            )
        }

        fun alt(label: String) = keys.first { it.label == label }.longPress!!.map { it.action.value }
        assertEquals(listOf("r"), alt("ER"))
        assertEquals(listOf("i"), alt("UI"))
        assertEquals(listOf("x"), alt("ZX"))
        assertEquals(listOf("w", "1", "!"), alt("QW"))
        assertEquals(listOf("，"), alt("L"))
        assertEquals(listOf("。"), alt("M"))
        // The main key sends the cluster representative.
        assertEquals(LayoutAction.code("q"), keys.first { it.label == "QW" }.action)
        assertEquals("1 !", keys.first { it.label == "QW" }.secondaryLabel)
        assertEquals("合并键", keys.first { it.label == "ER" }.secondaryLabel)
        assertNull(keys.first { it.label == "L" }.secondaryLabel)
    }

    @Test
    fun merged18UsesTheDocumentedRowUnits() {
        val weights =
            BuiltinLayouts.merged18.rows.dropLast(1).map { row ->
                MergedKeypadGeometry.units(row.keys.map { it.widthWeight })
            }
        assertEquals(10.3, weights[0], 0.0001)
        assertEquals(9.0, weights[1], 0.0001)
        assertEquals(7.3, weights[2], 0.0001)
        assertEquals(
            listOf(10.0, 9.0, 7.0),
            BuiltinLayouts.qwerty26.rows.take(3).map { row ->
                MergedKeypadGeometry.units(row.keys.map { it.widthWeight })
            },
        )
        // Each 18-key row is a single letter or a two-letter pair, and singles are narrower.
        for (row in BuiltinLayouts.merged18.rows.dropLast(1)) {
            for (key in row.keys) {
                assertEquals(if (key.label.length == 2) 1.7 else 1.3, key.widthWeight, 0.0001)
            }
        }
    }

    @Test
    fun mergedLayoutsDefaultToAutomaticSplitting() {
        for (layout in listOf(BuiltinLayouts.merged14, BuiltinLayouts.merged18)) {
            val split = layout.split
            assertNotNull(layout.id, split)
            assertEquals(
                LayoutSplitConfiguration.Activation.AUTOMATIC,
                split!!.activation,
            )
            // Qwerty26 and phone9 keep the layout default (no saved split).
            assertNull(BuiltinLayouts.qwerty26.split)
            assertNull(BuiltinLayouts.phone9.split)
        }
    }

    @Test
    fun phone9MatchesTheExampleIncludingTheFirstKeyDigit() {
        val builtin = BuiltinLayouts.phone9
        assertEquals(builtin, LayoutFixtures.readDefinition(LayoutFixtures.PHONE9))
        val first = builtin.rows[0].keys[0]
        assertEquals("，。？", first.label)
        assertEquals("1", first.secondaryLabel)
        assertEquals(LayoutAction.literal("，"), first.action)
        assertEquals(listOf("1"), first.longPress!!.map { it.label })
        assertEquals(listOf("1"), first.longPress!!.map { it.action.value })
        assertEquals(listOf(LayoutActionKind.LITERAL), first.longPress!!.map { it.action.type })
        // Same style baseline as 2-9: badge equals long-press equals the key's digit.
        for (key in builtin.rows.flatMap { it.keys }) {
            if (key.action.type != LayoutActionKind.CODE) continue
            val digit = key.action.value!!
            assertEquals(key.label, digit, key.secondaryLabel)
            assertEquals(key.label, listOf(digit), key.longPress!!.map { it.action.value })
        }
    }

    @Test
    fun phone9RailsCarryTheBundledEditingKeysAndAnEmptyDisambiguationSlot() {
        val areas = BuiltinLayouts.phone9.auxiliaryAreas!!
        assertFalse(areas.any { it.role == LayoutAuxiliaryArea.Role.PUNCTUATION })
        assertTrue(areas.any { it.requiresEngineDisambiguation && it.keys.isEmpty() })
        assertEquals(
            listOf("⌫", "分词", "⏎"),
            areas.first { it.position == LayoutAuxiliaryArea.Position.TRAILING }.keys.map { it.label },
        )
    }

    @Test
    fun structuralMatchersAcceptTheBundledLayouts() {
        assertTrue(PhoneKeypadStructure.matches(BuiltinLayouts.phone9))
        for (layout in BuiltinLayouts.all) {
            if (layout.id == "phone9") continue
            assertFalse(layout.id, PhoneKeypadStructure.matches(layout))
        }
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.phone9))
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.merged14))
        assertTrue(CompactSpellingLayout.matches(BuiltinLayouts.merged18))
        assertFalse(CompactSpellingLayout.matches(BuiltinLayouts.qwerty26))
    }

    @Test
    fun letterGroupsFollowTheDeclaredLabels() {
        assertEquals(26, LetterGroupLayout.groups(BuiltinLayouts.qwerty26)!!.size)
        assertEquals(
            listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m"),
            LetterGroupLayout.groups(BuiltinLayouts.merged14)!!.map { it.joinToString("") },
        )
        assertEquals(
            listOf(
                "q",
                "we",
                "rt",
                "y",
                "u",
                "io",
                "p",
                "a",
                "sd",
                "fg",
                "h",
                "jk",
                "l",
                "z",
                "xc",
                "v",
                "bn",
                "m",
            ),
            LetterGroupLayout.groups(BuiltinLayouts.merged18)!!.map { it.joinToString("") },
        )
        assertNull(LetterGroupLayout.groups(BuiltinLayouts.phone9))
        assertNull(LetterGroupLayout.groups(null))
    }

    @Test
    fun readingKeyMapComesFromCompactLabels() {
        val t9 = CompactSpellingLayout.readingKeyMap(BuiltinLayouts.phone9)
        assertEquals('6', t9['n'])
        assertEquals('4', t9['h'])
        assertEquals('9', t9['z'])
        val merged = CompactSpellingLayout.readingKeyMap(BuiltinLayouts.merged14)
        assertEquals('q', merged['w'])
        assertEquals('b', merged['n'])
        assertTrue(CompactSpellingLayout.readingKeyMap(BuiltinLayouts.qwerty26).isEmpty())
        assertTrue(CompactSpellingLayout.readingKeyMap(null).isEmpty())
    }

    @Test
    fun t9QuickSymbolOrderingIsSingleSourced() {
        val values = BuiltinLayouts.t9QuickSymbolValues(saved = "", ascii = false)
        assertEquals(values.take(3), BuiltinLayouts.t9QuickRailValues(saved = "", ascii = false))
        assertEquals(values.drop(3), BuiltinLayouts.t9QuickCycleValues(saved = "", ascii = false))
        assertEquals(values.size, values.toSet().size)
        assertEquals("，。？", BuiltinLayouts.t9QuickSymbolValues("", ascii = false).take(3).joinToString(""))
        val ascii = BuiltinLayouts.t9QuickSymbolValues("", ascii = true)
        assertEquals(",", ascii[0])
        assertTrue(ascii.all { it.all { character -> character.code in 0..127 } })
        // A saved list reorders the front without dropping defaults.
        val saved = BuiltinLayouts.t9QuickSymbolValues(saved = "？！", ascii = false)
        assertEquals(listOf("？", "！", "，", "。"), saved.take(4))
    }

    @Test
    fun t9DigitKeysUseDeclaredAlternates() {
        val digitKey = BuiltinLayouts.phone9.rows[0].keys[1]
        assertTrue(BuiltinLayouts.t9DigitKeyUsesDeclaredAlternates(digitKey))
        assertFalse(BuiltinLayouts.t9DigitKeyUsesDeclaredAlternates(BuiltinLayouts.phone9.rows[0].keys[0]))
        assertFalse(
            BuiltinLayouts.t9DigitKeyUsesDeclaredAlternates(
                LayoutKey("a", action = LayoutAction.code("a")),
            ),
        )
    }

    @Test
    fun t9CycleKeyFollowsTheOrderedValues() {
        val key = BuiltinLayouts.t9CycleKey(listOf("，", "。", "？", "!"))!!
        assertEquals("，。？", key.label)
        assertEquals("1", key.secondaryLabel)
        assertEquals(LayoutAction.literal("，"), key.action)
        assertEquals(listOf("1"), key.longPress!!.map { it.action.value })
        assertNull(BuiltinLayouts.t9CycleKey(emptyList()))
        assertNull(BuiltinLayouts.t9CycleKey(listOf("")))
        // The address-mark fallback key is built from the fallback marks, tap inserts the first.
        assertEquals("@/.", BuiltinLayouts.t9PunctuationKey().label)
        assertEquals(LayoutAction.literal("@"), BuiltinLayouts.t9PunctuationKey().action)
    }

    @Test
    fun numericAndSymbolPageRowsKeepTenColumns() {
        for (ascii in listOf(false, true)) {
            val numeric = BuiltinLayouts.numericPageRows(ascii)
            assertEquals(3, numeric.size)
            assertEquals(10, numeric[0].size)
            assertEquals(10, numeric[1].size)
            assertEquals(7, numeric[2].size)
            assertEquals(7, BuiltinLayouts.symbolPageRows(ascii)[2].size)
            val symbols = BuiltinLayouts.symbolPageRows(ascii)
            assertEquals(3, symbols.size)
            assertEquals(10, symbols[0].size)
            assertEquals(10, symbols[1].size)
            val rail = BuiltinLayouts.phoneNumericRailValues(ascii)
            assertEquals(rail.size, rail.toSet().size)
            // The row pages type the comma from the bottom row, so the digit pad's rail keeps it.
            assertTrue(rail.contains(if (ascii) "," else "，"))
        }
    }

    /** A phone number starts with "+": one tap from the 123 page and on the digit pad's rail. */
    @Test
    fun plusIsOnTheAsciiNumericPageAndTheDigitPadRail() {
        assertTrue(BuiltinLayouts.numericPageRows(ascii = true).flatten().contains("+"))
        assertTrue(BuiltinLayouts.phoneNumericRailValues(ascii = true).contains("+"))
        assertTrue(BuiltinLayouts.symbolPageRows(ascii = true).flatten().contains("*"))
        val numeric = BuiltinLayouts.numericPageRows(ascii = true).flatten().toSet()
        assertTrue(BuiltinLayouts.symbolPageRows(ascii = true).flatten().none { it in numeric })
    }

    @Test
    fun t9LeadingShortcutsMatchTheBranch() {
        assertEquals(
            listOf("@", "/", "-", "(", ")", "…"),
            BuiltinLayouts.t9LeadingShortcuts(ascii = false).map { it.label },
        )
        assertEquals(
            listOf("@", "/", "-", "(", ")", "_"),
            BuiltinLayouts.t9LeadingShortcuts(ascii = true).map { it.label },
        )
    }

    @Test
    fun byIdFindsEveryBuiltin() {
        for (builtin in BuiltinLayouts.all) assertEquals(builtin.id, builtin, BuiltinLayouts.byId(builtin.id))
        assertNull(BuiltinLayouts.byId("missing"))
    }

    @Test
    fun handCutsApplyToTheBundled26And18KeyLayouts() {
        val qwerty = BuiltinLayouts.handSplit(BuiltinLayouts.qwerty26)!!
        assertEquals(LayoutSplitConfiguration.Activation.OFF, qwerty.activation)
        assertEquals(listOf(5, 5, 5), qwerty.rowBreaks)
        val merged = BuiltinLayouts.handSplit(BuiltinLayouts.merged18)!!
        assertEquals(BuiltinLayouts.merged18.split!!.activation, merged.activation)
        assertEquals(listOf(3, 3, 4), merged.rowBreaks)
        assertNull(BuiltinLayouts.handSplit(BuiltinLayouts.merged14))
        assertNull(BuiltinLayouts.handSplit(BuiltinLayouts.phone9))
    }

    @Test
    fun aRenamedCopyOrOneWithAnotherNoteKeepsTheHandCuts() {
        val copy = BuiltinLayouts.qwerty26.copyWithId("mine", "我的 26 键")
        assertEquals(listOf(5, 5, 5), BuiltinLayouts.handSplit(copy)?.rowBreaks)
        assertEquals(listOf(5, 5, 5), BuiltinLayouts.handSplit(copy.copy(note = "另一句说明"))?.rowBreaks)
        assertEquals(
            listOf(3, 3, 4),
            BuiltinLayouts.handSplit(BuiltinLayouts.merged18.copyWithId("m", "我的"))?.rowBreaks,
        )
    }

    @Test
    fun editedRowsAndSavedBreaksGetNoHandCuts() {
        val edited = BuiltinLayouts.qwerty26.let { it.copy(rows = it.rows.dropLast(1)) }
        assertNull(BuiltinLayouts.handSplit(edited))
        val saved =
            LayoutSplitConfiguration(
                activation = LayoutSplitConfiguration.Activation.ALWAYS,
                rowBreaks = listOf(4, 4, 4),
            )
        assertNull(BuiltinLayouts.handSplit(BuiltinLayouts.qwerty26.withSplit(saved)))
    }

    @Test
    fun aSavedSplitWithoutCutsKeepsItsOwnSettings() {
        val always = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        val kept = BuiltinLayouts.handSplit(BuiltinLayouts.qwerty26.withSplit(always))!!
        assertEquals(LayoutSplitConfiguration.Activation.ALWAYS, kept.activation)
        assertEquals(listOf(5, 5, 5), kept.rowBreaks)
    }

    @Test
    fun persistingHandCutsWritesThemIntoACopyAndLeavesOthersAlone() {
        for ((layout, cuts) in listOf(
            BuiltinLayouts.qwerty26 to listOf(5, 5, 5),
            BuiltinLayouts.merged18 to listOf(3, 3, 4),
        )) {
            val copy = BuiltinLayouts.persistingHandCuts(layout).copyWithId("mine", "我的")
            assertEquals(cuts, copy.split?.rowBreaks)
            assertNull("the copy now has saved cuts", BuiltinLayouts.handSplit(copy))
            copy.validate()
        }
        assertEquals(BuiltinLayouts.phone9, BuiltinLayouts.persistingHandCuts(BuiltinLayouts.phone9))
    }
}

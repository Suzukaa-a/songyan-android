package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from LayoutKitTests/Phone9SemanticsTests. The two bundled 9-key identities stay separate:
 * the visual identity tolerates only the main rows' long-press menus, the primary-gesture identity
 * ignores main-row metadata so the first key keeps cycling after label edits. The Android shell draws
 * its main keys from data, so it reads only the second one (and the rail identity).
 */
class Phone9SemanticsTest {
    private val preset get() = BuiltinLayouts.phone9

    private fun clone(
        rows: List<LayoutRow> = preset.rows,
        areas: List<LayoutAuxiliaryArea>? = preset.auxiliaryAreas,
        input: LayoutInput = preset.input,
    ) = LayoutDefinition(id = "phone9-clone", name = "9 键副本", input = input, rows = rows, auxiliaryAreas = areas)

    private fun replacingKey(
        rows: List<LayoutRow>,
        row: Int,
        key: Int,
        transform: (LayoutKey) -> LayoutKey,
    ): List<LayoutRow> =
        rows.mapIndexed { r, value ->
            if (r !=
                row
            ) {
                value
            } else {
                LayoutRow(value.keys.mapIndexed { k, old -> if (k == key) transform(old) else old })
            }
        }

    private fun replacingAuxKey(
        areas: List<LayoutAuxiliaryArea>,
        area: Int,
        key: Int,
        transform: (LayoutKey) -> LayoutKey,
    ): List<LayoutAuxiliaryArea> =
        areas.mapIndexed { a, value ->
            if (a != area) {
                value
            } else {
                value.copy(keys = value.keys.mapIndexed { k, old -> if (k == key) transform(old) else old })
            }
        }

    private fun extraLongPress(key: LayoutKey) =
        key.withLongPress(
            (key.longPress ?: emptyList()) + LayoutOption("#+=", LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)),
        )

    private fun assertVisualBroken(definition: LayoutDefinition) =
        assertFalse(BuiltinLayouts.matchesPhone9VisualShell(definition))

    @Test
    fun explicitCustomKeepsTheAuthoredComma() {
        val custom = clone().copy(t9FirstKeyBehavior = T9FirstKeyBehavior.CUSTOM)
        custom.validate()
        assertEquals(LayoutAction.literal("，"), custom.rows[0].keys[0].action)
        assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(custom))
        assertFalse(BuiltinLayouts.usesT9QuickFirstKey(custom))
    }

    @Test
    fun explicitQuickSymbolsWorksForStructuralPhoneLayouts() {
        val phone =
            clone(rows = replacingKey(preset.rows, 0, 0) { it.withAction(LayoutAction.literal("！")) })
                .copy(
                    input = LayoutInput(schemaID = "custom_t9"),
                    t9FirstKeyBehavior = T9FirstKeyBehavior.QUICK_SYMBOLS,
                )
        phone.validate()
        assertFalse(BuiltinLayouts.matchesPhone9PrimarySemantics(phone))
        assertTrue(BuiltinLayouts.usesT9QuickFirstKey(phone))
        val nonPhone = BuiltinLayouts.qwerty26.copy(t9FirstKeyBehavior = T9FirstKeyBehavior.QUICK_SYMBOLS)
        assertFalse(BuiltinLayouts.usesT9QuickFirstKey(nonPhone))
    }

    @Test
    fun missingBehaviorPreservesLegacyInference() {
        val legacy = LayoutJson.decode(clone().encoded())
        assertNull(legacy.t9FirstKeyBehavior)
        assertTrue(BuiltinLayouts.usesT9QuickFirstKey(legacy))
        val edited = legacy.copy(rows = replacingKey(legacy.rows, 0, 0) { it.withAction(LayoutAction.literal("！")) })
        assertFalse(BuiltinLayouts.usesT9QuickFirstKey(edited))
        assertFalse(BuiltinLayouts.usesT9QuickFirstKey(BuiltinLayouts.qwerty26))
    }

    @Test
    fun t9FieldsRoundTripWithSharedWireNames() {
        val behaviors =
            listOf(T9FirstKeyBehavior.QUICK_SYMBOLS to "quickSymbols", T9FirstKeyBehavior.CUSTOM to "custom")
        for ((behavior, wire) in behaviors) {
            for (symbols in listOf(null, "", "！；…")) {
                val definition = clone().copy(t9FirstKeyBehavior = behavior, t9QuickSymbols = symbols)
                val encoded = definition.encoded()
                assertTrue(encoded.contains("\"t9FirstKeyBehavior\": \"$wire\""))
                assertEquals(definition, LayoutJson.decode(encoded))
                assertEquals(symbols ?: "@/:", definition.effectiveT9QuickSymbols("@/:"))
            }
        }
        assertNull(LayoutJson.decode(preset.encoded()).t9QuickSymbols)
    }

    @Test
    fun t9FieldsRejectNonPhoneLayoutsAndInvalidSymbols() {
        val nonPhone = BuiltinLayouts.qwerty26
        val behaviorError =
            assertThrows(LayoutException.Invalid::class.java) {
                nonPhone.copy(t9FirstKeyBehavior = T9FirstKeyBehavior.CUSTOM).validate()
            }
        assertEquals(LayoutInvalidCode.T9_FIRST_KEY_BEHAVIOR_REQUIRES_PHONE, behaviorError.code)
        for (symbols in listOf("", "！")) {
            val invalid = nonPhone.copy(t9QuickSymbols = symbols)
            assertEquals("@/:", invalid.effectiveT9QuickSymbols("@/:"))
            val error = assertThrows(LayoutException.Invalid::class.java) { invalid.validate() }
            assertEquals(LayoutInvalidCode.T9_QUICK_SYMBOLS_INVALID, error.code)
        }
        for (symbols in listOf("！！", " ", "\n", "😀", "abcdefghijklmn")) {
            val error =
                assertThrows(LayoutException.Invalid::class.java) { clone().copy(t9QuickSymbols = symbols).validate() }
            assertEquals(LayoutInvalidCode.T9_QUICK_SYMBOLS_INVALID, error.code)
        }
    }

    @Test
    fun bundledAndRenamedCloneMatchBoth() {
        for (definition in listOf(preset, clone())) {
            assertTrue(BuiltinLayouts.matchesPhone9VisualShell(definition))
            assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
        }
    }

    @Test
    fun firstKeyAndDigitLongPressEditsKeepBoth() {
        var rows = replacingKey(preset.rows, 0, 0, ::extraLongPress)
        rows = replacingKey(rows, 1, 0) { it.withLongPress(emptyList()) }
        val edited = clone(rows = rows)
        assertTrue(BuiltinLayouts.matchesPhone9VisualShell(edited))
        assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(edited))
    }

    @Test
    fun mainLabelSecondaryAndTitleEditsKeepPrimaryOnly() {
        val label = clone(rows = replacingKey(preset.rows, 1, 0) { it.copy(label = "RET") })
        val secondary = clone(rows = replacingKey(preset.rows, 1, 1) { it.withSecondaryLabel("x") })
        val title = clone(rows = replacingKey(preset.rows, 1, 2) { it.withTitleMode(LayoutKeyTitleMode.AUTOMATIC) })
        for (definition in listOf(label, secondary, title)) {
            assertVisualBroken(definition)
            assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
        }
    }

    @Test
    fun mainGeometryOrActionEditsBreakBoth() {
        val width = clone(rows = replacingKey(preset.rows, 1, 0) { it.withWidth(2.0) })
        val action = clone(rows = replacingKey(preset.rows, 1, 0) { it.withAction(LayoutAction.code("0")) })
        val swapped = preset.rows.toMutableList().also { it[1] = LayoutRow(it[1].keys.reversed()) }
        for (definition in listOf(width, action, clone(rows = swapped))) {
            assertVisualBroken(definition)
            assertFalse(BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
        }
        // The three-row phone shape keeps the canonical main rows: primary gestures are main-row only.
        val fewer = clone(rows = preset.rows.take(3), areas = emptyList())
        assertVisualBroken(fewer)
        assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(fewer))
    }

    @Test
    fun mainKeyCountChangeBreaksBoth() {
        val rows = preset.rows.toMutableList()
        rows[0] = LayoutRow(rows[0].keys.take(2) + preset.rows[0].keys.take(1))
        val edited = clone(rows = rows)
        assertFalse(BuiltinLayouts.matchesPhone9PrimarySemantics(edited))
        assertVisualBroken(edited)
    }

    @Test
    fun controlRowEditsKeepPrimaryGesturesButBreakTheVisualShell() {
        val last = preset.rows.lastIndex
        val longPress = clone(rows = replacingKey(preset.rows, last, 0, ::extraLongPress))
        val label = clone(rows = replacingKey(preset.rows, last, 0) { it.copy(label = "布局!") })
        val width = clone(rows = replacingKey(preset.rows, last, 1) { it.withWidth(8.0) })
        for (definition in listOf(longPress, label, width)) {
            assertVisualBroken(definition)
            assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
        }
    }

    @Test
    fun railEditsKeepPrimaryGesturesAndMoveTheRailsToData() {
        val areas = preset.auxiliaryAreas!!
        val longPress = clone(areas = replacingAuxKey(areas, 0, 0, ::extraLongPress))
        val label = clone(areas = replacingAuxKey(areas, 0, 0) { it.copy(label = "Del") })
        val action = clone(areas = replacingAuxKey(areas, 0, 1) { it.withAction(LayoutAction(LayoutActionKind.ENTER)) })
        val removed =
            clone(
                areas =
                    areas.mapIndexed { index, area ->
                        if (index ==
                            0
                        ) {
                            area.copy(keys = area.keys.drop(1))
                        } else {
                            area
                        }
                    },
            )
        for (definition in listOf(longPress, label, action, removed)) {
            assertVisualBroken(definition)
            assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
            // Any rail edit is what the shell draws: the bundled rails are gone.
            assertFalse(PhoneKeypadRailPolicy.usesBundledRails(definition))
        }
    }

    @Test
    fun unknownSchemaAndOtherLayoutsNeverMatch() {
        val bound = clone(input = LayoutInput(schemaID = "unknown_t9", builtinLayout = LayoutInput.Builtin.PHONE9))
        val imported = LayoutDefinition("imported-t9", "导入九键", LayoutInput(), preset.rows, preset.auxiliaryAreas)
        val others = listOf(BuiltinLayouts.qwerty26, BuiltinLayouts.merged14, BuiltinLayouts.merged18)
        for (definition in listOf(bound, imported) + others) {
            assertFalse(definition.id, BuiltinLayouts.matchesPhone9VisualShell(definition))
            assertFalse(definition.id, BuiltinLayouts.matchesPhone9PrimarySemantics(definition))
        }
    }

    @Test
    fun theBundledRailsAreTheDefaultAndDeclaringThemKeepsTheReadingArea() {
        assertTrue(PhoneKeypadRailPolicy.usesBundledRails(preset))
        val saved = "、，；…！"
        val declared = PhoneKeypadRailPolicy.withDeclaredRails(preset, savedQuickSymbols = saved)
        assertFalse(PhoneKeypadRailPolicy.usesBundledRails(declared))
        declared.validate()
        val leading = PhoneKeypadRailPolicy.declaredKeys(declared, LayoutAuxiliaryArea.Position.LEADING)
        val trailing = PhoneKeypadRailPolicy.declaredKeys(declared, LayoutAuxiliaryArea.Position.TRAILING)
        assertEquals(BuiltinLayouts.t9QuickRailValues(saved, false), leading.map { it.label })
        assertEquals(BuiltinLayouts.t9QuickRailValues(saved, false), leading.map { it.action.literalValue(false) })
        assertEquals(BuiltinLayouts.t9QuickRailValues(saved, true), leading.map { it.action.literalValue(true) })
        assertEquals(BuiltinLayouts.t9QuickRailValues(saved, true), leading.map { it.face(true) })
        assertEquals(declared, LayoutJson.decode(declared.encoded()))
        for (local in listOf("", "！；…")) {
            val snapshot = PhoneKeypadRailPolicy.withDeclaredRails(preset.copy(t9QuickSymbols = local), saved)
            val keys = PhoneKeypadRailPolicy.declaredKeys(snapshot, LayoutAuxiliaryArea.Position.LEADING)
            assertEquals(BuiltinLayouts.t9QuickRailValues(local, false), keys.map { it.action.literalValue(false) })
            assertEquals(BuiltinLayouts.t9QuickRailValues(local, true), keys.map { it.action.literalValue(true) })
        }
        assertEquals(listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CLEAR_INPUT), trailing.map { it.action.type })
        assertTrue(declared.auxiliaryAreas!!.any { it.role == LayoutAuxiliaryArea.Role.DISAMBIGUATION })
        // A layout that already declares its rails is left as it is.
        assertEquals(declared, PhoneKeypadRailPolicy.withDeclaredRails(declared, savedQuickSymbols = "@/:"))
    }

    @Test
    fun declaredRailSpansFollowTheTelephoneRuleUntilAKeyCarriesItsOwn() {
        fun keys(count: Int) = List(count) { LayoutKey("k$it", action = LayoutAction.literal("k")) }
        assertEquals(emptyList<Double>(), PhoneKeypadRailPolicy.declaredSpans(emptyList()))
        assertEquals(listOf(3.0), PhoneKeypadRailPolicy.declaredSpans(keys(1)))
        assertEquals(listOf(2.0, 1.0), PhoneKeypadRailPolicy.declaredSpans(keys(2)))
        assertEquals(listOf(1.0, 1.0, 1.0), PhoneKeypadRailPolicy.declaredSpans(keys(3)))
        assertEquals(List(4) { 0.75 }, PhoneKeypadRailPolicy.declaredSpans(keys(4)))
        // Keys past the limit are kept in the file but never drawn.
        assertEquals(PhoneKeypadRailPolicy.MAX_RAIL_KEYS, PhoneKeypadRailPolicy.declaredSpans(keys(9)).size)
        val weighted = listOf(keys(1)[0].withWidth(1.0), keys(1)[0].withWidth(2.0))
        assertEquals(listOf(1.0, 2.0), PhoneKeypadRailPolicy.declaredSpans(weighted))
    }
}

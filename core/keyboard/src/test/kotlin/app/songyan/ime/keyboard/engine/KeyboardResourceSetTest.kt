package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resource set decides layout/schema pairing and publication gating; ported behavior. */
class KeyboardResourceSetTest {
    private fun builtin() = KeyboardResourceSet.builtin("/shared", "/user")

    @Test
    fun theBuiltinSetOffersEveryLayoutThroughItsAdapter() {
        val set = builtin()
        assertEquals(
            KeyboardLayoutKind.entries.toSet(),
            set.availableLayouts.toSet(),
        )
        for (layout in KeyboardLayoutKind.entries) {
            val combination = requireNotNull(set.combination(layout))
            assertEquals(layout, combination.layout)
            assertEquals(
                BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, layout),
                combination.engineSchemaID,
            )
        }
        assertTrue(builtin().usesIndependentSelection)
    }

    @Test
    fun aSourceSchemaIdRoundTripsThroughItsAdapter() {
        val set = builtin()
        val adapter = BuiltinTemplateSchemas.ADAPTERS.first { it.layout == KeyboardLayoutKind.PHONE9 }
        assertEquals(adapter.sourceSchemaID, set.sourceSchemaID(adapter.schemaID))
        assertEquals(adapter.schemaID, set.logicalSchemaID(adapter.sourceSchemaID, KeyboardLayoutKind.PHONE9))
        // A 26-key source maps to itself.
        assertEquals(
            BuiltinTemplateSchemas.QUANPIN,
            set.logicalSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26),
        )
    }

    @Test
    fun aCompactLayoutWithoutAdapterHasNoEngineSchema() {
        // Falling back to the source would feed folded 9-key codes to a 26-key schema.
        assertNull(builtin().logicalSchemaID("rime_ice", KeyboardLayoutKind.PHONE9))
        assertEquals("rime_ice", builtin().logicalSchemaID("rime_ice", KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun disabledSchemasAreRejectedWithATypedRefusal() {
        val set = builtin().copy(schemas = listOf(KeyboardSchemaDescriptor("rime_ice", "雾凇拼音")))
        assertNull("an enabled scheme passes", set.enabledMembershipRefusal("rime_ice"))
        val refusal = requireNotNull(set.enabledMembershipRefusal("other_scheme"))
        assertEquals("the refusal names the missing scheme", "other_scheme", refusal.schemaID)
        assertEquals("the refusal carries what is actually enabled", listOf("rime_ice"), refusal.enabledSchemaIDs)
    }

    @Test
    fun selectingKeepsOnlyAValidLayout() {
        val set = builtin()
        val selected = set.selecting(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED14)
        assertEquals(BuiltinTemplateSchemas.QUANPIN, selected.selectedSchemaID)
        assertEquals(KeyboardLayoutKind.MERGED14, selected.preferredLayout)

        // An unknown schema is never invented.
        assertEquals(null, set.selecting("unknown_scheme").selectedSchemaID)
    }

    @Test
    fun bootSelectionFallsBackThroughTheCandidateList() {
        val set = builtin()
        val picked = requireNotNull(set.bootSelection(listOf(KeyboardLayoutKind.PHONE9, KeyboardLayoutKind.QWERTY26)))
        assertEquals(KeyboardLayoutKind.PHONE9, picked.first)
        assertNull(set.bootSelection(emptyList()))
    }

    @Test
    fun publishedKeyboardsGateTheOfferedCombinations() {
        val set =
            builtin().copy(
                schemas = listOf(KeyboardSchemaDescriptor(BuiltinTemplateSchemas.QUANPIN, "内置全拼")),
                publishedKeyboards =
                    listOf(
                        PublishedKeyboard(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26),
                    ),
                encodingAdapters = BuiltinTemplateSchemas.ADAPTERS,
            )
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26),
            set.availableCombinations.map { it.layout },
        )
        // Nothing added = nothing offered, even though the layouts exist.
        assertEquals(
            emptyList<KeyboardLayoutKind>(),
            set.copy(publishedKeyboards = emptyList()).availableCombinations.map { it.layout },
        )
    }

    @Test
    fun aCompactCombinationWithoutAnAdapterIsNotRunnable() {
        val set =
            KeyboardResourceSet(
                id = "no-adapter",
                name = "无配套",
                sharedDirectory = "/shared",
                userDirectory = "/user",
                layoutEntries = emptyList(),
                schemas = listOf(KeyboardSchemaDescriptor("custom_scheme", "自建方案")),
            )
        assertNull(
            "never fall back to the source's 26-key",
            set.structurallyRunnableEngineSchema("custom_scheme", KeyboardLayoutKind.PHONE9),
        )
        assertEquals(
            "custom_scheme",
            set.structurallyRunnableEngineSchema("custom_scheme", KeyboardLayoutKind.QWERTY26),
        )
        assertNull(set.combination(KeyboardLayoutKind.PHONE9))
    }

    @Test
    fun publishedKeyboardHelpersKeepIdentityAndOrder() {
        val keyboard = PublishedKeyboard("rime_ice", KeyboardLayoutKind.MERGED14)
        assertEquals("rime_ice:merged14", keyboard.id)
        assertTrue(keyboard.needsGeneratedAdapter)
        assertFalse(PublishedKeyboard("rime_ice", KeyboardLayoutKind.QWERTY26).needsGeneratedAdapter)

        assertEquals(
            listOf(keyboard),
            PublishedKeyboard.adding(keyboard, PublishedKeyboard.adding(keyboard, emptyList())),
        )
        assertEquals(emptyList<PublishedKeyboard>(), PublishedKeyboard.removing(keyboard, listOf(keyboard)))
        assertEquals(
            keyboard,
            PublishedKeyboard.selectionAfterRemoval(listOf(keyboard), keyboard),
        )
        assertEquals(null, PublishedKeyboard.selectionAfterRemoval(emptyList(), keyboard))
        assertEquals(listOf(keyboard), PublishedKeyboard.generationTasks(forRequested = listOf(keyboard, keyboard)))
    }

    @Test
    fun displayNamesComeFromTheCurrentDescriptors() {
        val keyboard = PublishedKeyboard(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED14)
        val display =
            PublishedKeyboard.display(
                keyboard,
                listOf(KeyboardSchemaDescriptor(BuiltinTemplateSchemas.QUANPIN, "内置全拼")),
            )
        assertEquals("内置全拼 · 14 键", display.title)
        // An id-only descriptor never leaks the raw id.
        assertEquals(
            "14 键",
            PublishedKeyboard
                .display(
                    keyboard,
                    listOf(KeyboardSchemaDescriptor(BuiltinTemplateSchemas.QUANPIN, "")),
                ).title,
        )
        assertNotNull(display.keyboard)
    }
}

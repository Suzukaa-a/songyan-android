package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardResourceSetsTest {
    private fun schema(id: String) = KeyboardSchemaDescriptor(id = id, name = id)

    @Test
    fun noEngineSchemasMeansNoResourceSet() {
        assertNull(KeyboardResourceSets.forEngineSchemas(emptyList()))
    }

    @Test
    fun theBundledSchemesKeepTheirCompactCompanions() {
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema(BuiltinTemplateSchemas.FLYPY)),
                ),
            )

        for (layout in KeyboardLayoutKind.entries) {
            assertEquals(
                BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, layout),
                set.combination(layout)?.engineSchemaID,
            )
        }
        assertFalse(set.availableCombinations.any { it.schemaID.startsWith("keyboard_") })
    }

    @Test
    fun theEngineSchemasKeepTheirDeclaredAlphabetForTheFamilyRule() {
        val dachen =
            KeyboardSchemaDescriptor(
                id = "dachen",
                name = "大字注音",
                alphabet = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-",
            )
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(dachen, schema(BuiltinTemplateSchemas.QUANPIN)),
                ),
            )
        assertEquals(InputSchemeFamily.ZHUYIN, set.familyOf("dachen"))
        assertFalse(
            "the zhuyin scheme keeps the identity shape only",
            set.availableCombinations.any { it.schemaID == "dachen" && it.layout != KeyboardLayoutKind.QWERTY26 },
        )
    }

    @Test
    fun anImportedSchemeIsSelectableOnThe26KeyLayout() {
        val imported = "songyan_testscheme"
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(
                        schema(BuiltinTemplateSchemas.QUANPIN),
                        schema(BuiltinTemplateSchemas.FLYPY),
                        schema(imported),
                        schema("keyboard_47246213a538cb99d374_14"),
                    ),
                ),
            )

        val combination =
            set.availableCombinations.firstOrNull {
                it.schemaID == imported && it.layout == KeyboardLayoutKind.QWERTY26
            }
        assertNotNull("the imported scheme must be a runnable 26-key combination", combination)
        assertEquals(imported, combination?.engineSchemaID)
        // The gate the keyboard applies before it asks the engine to switch must let it through.
        assertNull(set.incompatibilityReason(KeyboardLayoutKind.QWERTY26, imported))
        assertEquals(imported, set.logicalSchemaID(imported, KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun anImportedSchemeGetsNoFoldedLayoutTheEngineCannotRun() {
        val imported = "songyan_testscheme"
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema(imported)),
                ),
            )

        val folded =
            set.availableCombinations.filter {
                it.schemaID == imported && it.layout != KeyboardLayoutKind.QWERTY26
            }
        assertTrue(folded.isEmpty())
        assertNotNull(set.incompatibilityReason(KeyboardLayoutKind.MERGED14, imported))
    }

    @Test
    fun generatedCompanionsNeverBecomeInputMethods() {
        val companion = BuiltinTemplateSchemas.ADAPTERS.first().schemaID
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema(companion)),
                ),
            )

        assertFalse(set.availableCombinations.any { it.schemaID == companion })
        assertTrue(set.inputSchemeChoices.none { it.id == companion })
    }

    @Test
    fun anImportedSchemeRunsOnTheCompanionsItsGenerationCompiled() {
        val imported = "songyan_testscheme"
        val companion14 = "keyboard_0123456789abcdef0123_14"
        val adapters =
            listOf(
                KeyboardResourceSet.EncodingAdapter(imported, companion14, KeyboardLayoutKind.MERGED14, "测试"),
                KeyboardResourceSet.EncodingAdapter(imported, imported, KeyboardLayoutKind.PHONE9, "测试"),
                // A generation cannot rebind a bundled scheme.
                KeyboardResourceSet.EncodingAdapter(
                    BuiltinTemplateSchemas.QUANPIN,
                    companion14,
                    KeyboardLayoutKind.MERGED18,
                    null,
                ),
            )
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema(imported)),
                    adapters,
                ),
            )

        assertEquals(companion14, set.logicalSchemaID(imported, KeyboardLayoutKind.MERGED14))
        assertEquals(imported, set.logicalSchemaID(imported, KeyboardLayoutKind.PHONE9))
        assertEquals(imported, set.logicalSchemaID(imported, KeyboardLayoutKind.QWERTY26))
        assertNull(set.logicalSchemaID(imported, KeyboardLayoutKind.MERGED18))
        assertEquals(
            BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED18),
            set.logicalSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED18),
        )
        assertEquals(imported, set.sourceSchemaID(companion14))
        assertTrue(set.inputSchemeChoices.none { it.id == companion14 })
    }
}

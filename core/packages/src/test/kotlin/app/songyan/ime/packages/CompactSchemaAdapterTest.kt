package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactSchemaAdapterTest {
    @Test
    fun identifierMatchesTheGeneratedCompanionsShippedInTheBundle() {
        // The bundled keyboard already ships these ids, so the hashing contract must agree.
        for (adapter in BuiltinTemplateSchemas.ADAPTERS) {
            val fold = adapter.layout.compactFold ?: continue
            assertEquals(adapter.schemaID, CompactSchemaAdapter.identifier(adapter.sourceSchemaID, fold))
        }
        assertEquals(
            "keyboard_47246213a538cb99d374_14",
            CompactSchemaAdapter.identifier(BuiltinTemplateSchemas.QUANPIN, CompactFold.MERGED14),
        )
        assertEquals(
            "keyboard_3d7821f984693864ae91_9",
            CompactSchemaAdapter.identifier(BuiltinTemplateSchemas.FLYPY, CompactFold.PHONE9),
        )
    }

    @Test
    fun generatedCompanionIdsAreRecognized() {
        assertTrue(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_47246213a538cb99d374_14"))
        assertTrue(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_3d7821f984693864ae91_9"))
        assertFalse(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_layout"))
        assertFalse(CompactSchemaAdapter.isGeneratedCompanionID("songyan_quanpin"))
        assertFalse(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_47246213a538cb99d374_"))
        assertFalse(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_47246213A538CB99D374_14"))
    }

    @Test
    fun primaryProducerFollowsTheNativeSingleProducerRule() {
        // A sole default producer is the primary, in its default namespace.
        assertEquals(
            CompactSchemaAdapter.PrimaryProducer(CompactSchemaAdapter.ProducerClass.SCRIPT, "translator", true),
            CompactSchemaAdapter.primaryProducer(listOf("script_translator")),
        )
        assertEquals(
            CompactSchemaAdapter.PrimaryProducer(CompactSchemaAdapter.ProducerClass.TABLE, "translator", true),
            CompactSchemaAdapter.primaryProducer(listOf("table_translator")),
        )
        // Auxiliary entries never count and never make it mixed.
        assertEquals(
            "translator",
            CompactSchemaAdapter
                .primaryProducer(
                    listOf(
                        "punct_translator",
                        "script_translator",
                        "table_translator@custom_phrase",
                        "lua_translator@*date",
                    ),
                )?.namespace,
        )
        // Two defaults are mixed provenance: no primary.
        assertNull(CompactSchemaAdapter.primaryProducer(listOf("script_translator", "table_translator")))
        // With no default, exactly one named entry is a read-only fallback.
        assertEquals(
            CompactSchemaAdapter.PrimaryProducer(CompactSchemaAdapter.ProducerClass.SCRIPT, "main", false),
            CompactSchemaAdapter.primaryProducer(listOf("script_translator@main")),
        )
        assertNull(CompactSchemaAdapter.primaryProducer(listOf("script_translator@a", "script_translator@b")))
        assertNull(CompactSchemaAdapter.primaryProducer(listOf("punct_translator", "lua_translator@x")))
        assertNull(CompactSchemaAdapter.primaryProducer(emptyList()))
    }
}

package app.songyan.ime.state

import app.songyan.ime.ime.KeyboardResourceSets
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.rime.SchemaOptionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The settings app places an installed scheme's family from the alphabet its deployed schema
 * declares, not from the shape of its id: an alias with the zhuyin key map must be gated like the
 * keyboard gates it.
 */
class SchemeLayoutFamiliesTest {
    private fun supportedLayouts(
        id: String,
        schemas: List<KeyboardSchemaDescriptor>,
    ): Set<String> {
        val resources = requireNotNull(KeyboardResourceSets.forEngineSchemas(schemas))
        return KeyboardLayoutKind.entries
            .filter { resources.incompatibilityReason(it, id) == null }
            .map { it.wire }
            .toSet()
    }

    @Test
    fun anAliasIdIsGatedByItsDeployedAlphabet() {
        val build = Files.createTempDirectory("generation-build").toFile()
        try {
            File(build, "dachen.schema.yaml").writeText(
                "schema:\n  schema_id: dachen\n" +
                    "speller:\n  alphabet: \"1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-\"\n",
            )
            val ids = listOf("dachen", "songyan_quanpin")
            // This is the mapping the settings app state builds from the deployed generation.
            val schemas =
                ids.map { KeyboardSchemaDescriptor(it, it, SchemaOptionCapabilities.spellerAlphabet(build, it)) }
            val resources = requireNotNull(KeyboardResourceSets.forEngineSchemas(schemas))
            assertEquals(InputSchemeFamily.ZHUYIN, resources.familyOf("dachen"))
            assertEquals(InputSchemeFamily.GENERIC, resources.familyOf("songyan_quanpin"))
            assertEquals(
                "the zhuyin alias keeps the identity shape only",
                setOf("qwerty26"),
                supportedLayouts("dachen", schemas),
            )
            assertTrue("qwerty26" in supportedLayouts("songyan_quanpin", schemas))
        } finally {
            build.deleteRecursively()
        }
    }
}

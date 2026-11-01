package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled 14-, 18- and 9-key schemes are generator output, so the Kotlin port must reproduce
 * the shipped bytes exactly. Ported from the iOS `BuiltinCompanionTests`.
 */
class BuiltinCompanionTest {
    private val builtinDir = File("../rime/src/main/assets/rime/builtin")

    private fun projection(source: String): CompactSchemaAdapter.SourceProjection {
        val stream = javaClass.getResourceAsStream("/package-fixtures/$source.projection.json")
        assertNotNull("missing fixture $source", stream)
        return requireNotNull(CompactSchemaAdapter.SourceProjection.decode(stream!!.readBytes()))
    }

    private fun yaml(name: String): Map<String, Any?> =
        requireNotNull(YamsYaml.loadMap(File(builtinDir, name).readText())) { name }

    private fun companion(adapter: app.songyan.ime.keyboard.engine.KeyboardResourceSet.EncodingAdapter): ByteArray {
        val source = adapter.sourceSchemaID
        val schema = yaml("$source.schema.yaml")
        val delimiter =
            schema["translator"].asStringMap()?.get("delimiter") as? String
                ?: schema["speller"].asStringMap()?.get("delimiter") as? String
        val plan =
            CompactSchemaAdapter.CompanionPlan(
                File(builtinDir, "$source.schema.yaml").readBytes(),
                source,
                projection(source),
                delimiter,
            )
        val fold = requireNotNull(adapter.layout.compactFold)
        val body = requireNotNull(plan.companion(fold)) { "$source cannot fold to ${fold.id} keys" }
        val header =
            "# Generated from $source.schema.yaml for the ${fold.id}-key layout; do not edit.\n" +
                "# Regenerate with Tools/BuildRimeIOS/scripts/gen-builtin-companions.sh.\n" +
                "# Rule sources and licenses: see $source.schema.yaml.\n"
        return header.toByteArray(Charsets.UTF_8) + body
    }

    @Test
    fun bundledCompanionsMatchTheGeneratorByteForByte() {
        for (adapter in BuiltinTemplateSchemas.ADAPTERS) {
            val fold = requireNotNull(adapter.layout.compactFold)
            assertEquals(adapter.schemaID, CompactSchemaAdapter.identifier(adapter.sourceSchemaID, fold))
            val shipped = File(builtinDir, adapter.schemaID + ".schema.yaml").readBytes()
            val generated = companion(adapter)
            if (!shipped.contentEquals(generated)) {
                assertEquals(adapter.schemaID, String(shipped, Charsets.UTF_8), String(generated, Charsets.UTF_8))
            }
            assertArrayEquals(adapter.schemaID, shipped, generated)
        }
    }

    @Test
    fun companionsCarryDisplayAndRewrite() {
        for (adapter in BuiltinTemplateSchemas.ADAPTERS) {
            val root = yaml(adapter.schemaID + ".schema.yaml")
            assertEquals(adapter.schemaID, root["schema"].asStringMap()?.get("schema_id"))
            assertEquals("songyan_basic", root["translator"].asStringMap()?.get("dictionary"))
            assertEquals("songyan_shared", root["translator"].asStringMap()?.get("user_dict"))
            val rewrite = requireNotNull(root["keyboard_rewrite"].asStringMap()) { adapter.schemaID }
            assertEquals(CompactSchemaAdapter.TOKEN_ALIAS_LOCK_VALUE, rewrite["lock"])
            val display = root["keyboard_adapter"].asStringMap()?.get("display_mode")
            assertEquals(
                adapter.schemaID,
                if (adapter.layout ==
                    KeyboardLayoutKind.PHONE9
                ) {
                    "canonical"
                } else {
                    "source_code"
                },
                display,
            )
        }
    }

    /** A tap sends a key's own letter for its whole group, so its exact pick must be a distinct code. */
    @Test
    fun exactPickOfAKeysOwnLetterIsDistinctFromItsTap() {
        for (adapter in BuiltinTemplateSchemas.ADAPTERS.filter {
            !requireNotNull(
                it.layout.compactFold,
            ).isPhoneDigits
        }) {
            val fold = requireNotNull(adapter.layout.compactFold)
            val root = yaml(adapter.schemaID + ".schema.yaml")
            val speller = requireNotNull(root["speller"].asStringMap())
            val algebra = requireNotNull(speller["algebra"].asStringList())
            val alphabet = speller["alphabet"] as String
            val preedit = root["translator"].asStringMap()?.get("preedit_format").asStringList()
            assertEquals(if (adapter.layout == KeyboardLayoutKind.MERGED14) 12 else 8, fold.exactTokens.size)
            for ((letter, token) in fold.exactTokens) {
                val exact = algebra.indexOf("derive/$letter/$token/")
                assertTrue(adapter.schemaID, exact >= 0)
                for ((partner, key) in fold.map) {
                    if (key != letter || partner == letter) continue
                    val tap = algebra.indexOf("derive/$partner/$letter/")
                    assertTrue(tap >= 0)
                    assertTrue("${adapter.schemaID}: $token must see $letter only", exact < tap)
                }
                assertTrue(adapter.schemaID, alphabet.contains(token))
            }
            val parts = requireNotNull(preedit?.first()).split("/")
            assertEquals("xlit", parts[0])
            assertEquals(
                parts[1].zip(parts[2]).toMap(),
                fold.exactTokens.entries.associate { it.value to it.key },
            )
        }
    }

    @Test
    fun defaultSchemaListDeploysSourcesAndCompanions() {
        val list = yaml("default.yaml")["schema_list"] as List<*>
        assertEquals(
            BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS + BuiltinTemplateSchemas.ADAPTERS.map { it.schemaID },
            list.map { it.asStringMap()?.get("schema") },
        )
        for (id in BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS) {
            assertEquals(
                BuiltinTemplateSchemas.NAMES[id],
                yaml("$id.schema.yaml")["schema"].asStringMap()?.get("name"),
            )
        }
    }

    /** The projections stand in for the live engine: they must still describe these schemes. */
    @Test
    fun projectionsMatchTheBundledSchemesAndDictionary() {
        val dictionary = File(builtinDir, "songyan_basic.dict.yaml").readText()
        val rows =
            dictionary
                .split("\n...\n")[1]
                .split("\n")
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split("\t") }
                .filter { it.size == 3 }
        val syllables = rows.flatMap { it[1].split(" ") }.toSet()
        assertFalse(syllables.isEmpty())
        for (source in BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS) {
            val projection = projection(source)
            val schema = yaml("$source.schema.yaml")
            assertEquals(source, projection.sourceSchema)
            assertEquals("songyan_basic", projection.dictionary)
            assertEquals(
                schema["speller"].asStringMap()?.get("algebra").asStringList() ?: emptyList<String>(),
                projection.algebra,
            )
            assertEquals(source, syllables, projection.syllables.map { it.token }.toSet())
        }
    }

    @Test
    fun theIntegrationEntryPointReturnsEveryLayoutKeyedByFileName() {
        val source = BuiltinTemplateSchemas.QUANPIN
        val files =
            CompactSchemaAdapter.generateCompanions(
                File(builtinDir, "$source.schema.yaml").readBytes(),
                source,
                projection(source),
                sourceDelimiter = " '",
            )
        val expected =
            listOf(CompactFold.MERGED14, CompactFold.MERGED18, CompactFold.PHONE9)
                .map { CompactSchemaAdapter.identifier(source, it) + ".schema.yaml" }
        assertEquals(expected, files.keys.toList())
    }
}

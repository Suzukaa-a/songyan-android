package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.packages.CompactSchemaAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The deployment of a scheme package generates and compiles 14-, 18- and 9-key companions
 * for its input methods and records them for the keyboard; what the generator does not cover stays
 * on 26 keys, and what it refuses fails the deployment.
 */
class KeyboardCompanionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val source = "songyan_quanpin"
    private val schemaText = File("../core/rime/src/main/assets/rime/builtin/$source.schema.yaml").readText()
    private val projection =
        File("../core/packages/src/test/resources/package-fixtures/$source.projection.json").readBytes()

    /** Compiles by copying, as librime would leave `build/<id>.schema.yaml` behind. */
    private inner class FakeEngine(
        override val compiledDir: File,
        var projectionBytes: ByteArray? = projection,
        var compiles: Boolean = true,
        var composes: (String) -> Boolean = { true },
    ) : DeployedEngine {
        val deployed = mutableListOf<String>()
        val probed = mutableListOf<Pair<String, String>>()

        override suspend fun sourceProjection(schemaId: String): ByteArray? = projectionBytes

        override suspend fun schemaValue(
            schemaId: String,
            key: String,
        ): String? {
            if (!File(compiledDir, "$schemaId.schema.yaml").isFile) return null
            return when (key) {
                "schema/schema_id" -> schemaId
                "schema/name" -> "全拼"
                "translator/delimiter" -> " '"
                else -> null
            }
        }

        override suspend fun deploySchema(file: File): Boolean {
            deployed += file.name
            if (!compiles) return false
            file.copyTo(File(compiledDir, file.name), overwrite = true)
            return true
        }

        override suspend fun composesKeys(
            schemaId: String,
            keys: String,
        ): Boolean {
            probed += schemaId to keys
            return composes(schemaId)
        }
    }

    private fun setUp(
        compiled: String = schemaText,
        name: String = "a",
    ): Triple<File, File, FakeEngine> {
        val sourceDir = temp.newFolder("source-$name")
        val workDir = temp.newFolder("work-$name")
        val build = File(workDir, "build").also { it.mkdirs() }
        File(build, "$source.schema.yaml").writeText(compiled)
        return Triple(sourceDir, workDir, FakeEngine(build))
    }

    @Test
    fun familiesWithoutCompactKeyboardsDoNotPublishCompanions() =
        runBlocking {
            for (schemaId in listOf("cangjie5", "quick5", "bopomofo_tw", "kagiroi")) {
                val (sourceDir, workDir, engine) = setUp(name = schemaId)
                File(engine.compiledDir, "$schemaId.schema.yaml").writeText(schemaText.replace(source, schemaId))
                engine.projectionBytes = String(projection, Charsets.UTF_8).replace(source, schemaId).toByteArray()

                KeyboardCompanions(sourceDir, workDir, listOf(schemaId)).generate(engine)

                assertTrue(schemaId, EncodingAdapterRegistry.read(engine.compiledDir, setOf(schemaId)).isEmpty())
            }
        }

    @Test
    fun aSchemeWhoseSpellerIsNotProvableIsNotFolded() =
        runBlocking {
            // The same input method without a speller processor: its profile is unknown, so no folded keys.
            val (sourceDir, workDir, engine) = setUp(compiled = schemaText.replace("- speller", "- lua_processor"))

            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)

            assertTrue(EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)).isEmpty())
            assertTrue(engine.deployed.isEmpty())
        }

    @Test
    fun onlyTheRequestedFoldsAreBuilt() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()

            KeyboardCompanions(
                sourceDir,
                workDir,
                listOf(source),
                requested = mapOf(source to setOf(KeyboardLayoutKind.MERGED18)),
            ).generate(engine)

            assertEquals(
                listOf(KeyboardLayoutKind.MERGED18),
                EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)).map { it.layout },
            )
            assertEquals(1, engine.deployed.size)
            assertTrue(CompanionStatus.readUnavailable(engine.compiledDir).isEmpty())
        }

    @Test
    fun aRefusedPairIsRecordedAndNotAskedForAgain() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()
            engine.projectionBytes = null
            val asked = mapOf(source to setOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.PHONE9))

            KeyboardCompanions(sourceDir, workDir, listOf(source), requested = asked).generate(engine)

            val unavailable = CompanionStatus.readUnavailable(engine.compiledDir)
            assertEquals(asked, unavailable)
            val adapters = EncodingAdapterRegistry.read(engine.compiledDir, setOf(source))
            assertTrue(CompanionStatus.missing(asked, adapters, unavailable).isEmpty())
        }

    @Test
    fun aRequestedFoldOfANonSyllabicSchemeIsRefused() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp(compiled = schemaText.replace("- speller", "- lua_processor"))
            val asked = mapOf(source to setOf(KeyboardLayoutKind.PHONE9))

            KeyboardCompanions(sourceDir, workDir, listOf(source), requested = asked).generate(engine)

            assertEquals(asked, CompanionStatus.readUnavailable(engine.compiledDir))
            assertTrue(engine.deployed.isEmpty())
        }

    @Test
    fun aSourceKeptFromTheFirstPhaseKeepsItsCompanionsAndRefusals() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()
            val asked = mapOf(source to setOf(KeyboardLayoutKind.MERGED14))
            KeyboardCompanions(sourceDir, workDir, listOf(source), requested = asked).generate(engine)
            val before = engine.deployed.size

            KeyboardCompanions(
                sourceDir,
                workDir,
                listOf(source),
                reuse = setOf(source),
                requested = asked,
            ).generate(engine)

            assertEquals(before, engine.deployed.size)
            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14),
                EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)).map { it.layout },
            )
        }

    @Test
    fun aRomanizationSchemeFoldsLikePinyin() =
        runBlocking {
            val schemaId = "jyut6ping3"
            val (sourceDir, workDir, engine) = setUp(name = schemaId)
            File(engine.compiledDir, "$schemaId.schema.yaml").writeText(schemaText.replace(source, schemaId))
            engine.projectionBytes = String(projection, Charsets.UTF_8).replace(source, schemaId).toByteArray()

            KeyboardCompanions(sourceDir, workDir, listOf(schemaId)).generate(engine)

            assertEquals(3, EncodingAdapterRegistry.read(engine.compiledDir, setOf(schemaId)).size)
        }

    @Test
    fun aPinyinSchemeGetsThreeCompiledCompanionsAndARegistry() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()

            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)

            val adapters = EncodingAdapterRegistry.read(engine.compiledDir, setOf(source))
            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
                adapters.map { it.layout },
            )
            for (adapter in adapters) {
                val fold = requireNotNull(adapter.layout.compactFold)
                assertEquals(CompactSchemaAdapter.identifier(source, fold), adapter.schemaID)
                assertEquals("全拼", adapter.schemaName)
                // Written into the work directory and compiled; the stored package is untouched.
                assertTrue(File(workDir, adapter.schemaID + ".schema.yaml").isFile)
                assertTrue(File(engine.compiledDir, adapter.schemaID + ".schema.yaml").isFile)
            }
            assertEquals(3, engine.deployed.size)
            assertTrue(sourceDir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun anExplicitNumericPrefixLockGetsACompanionWhilePlainDigitsStayNative() =
        runBlocking {
            for (opted in listOf(false, true)) {
                val (sourceDir, workDir, engine) = setUp(name = "numeric-$opted")
                engine.projectionBytes =
                    """
                    {"source_schema":"$source","translator_namespace":"translator",
                    "dictionary":"songyan_basic","producer_class":"script_translator",
                    "alphabet":"23456789abcdefghijklmnopqrstuvwxyz","algebra":[],
                    "prefix_declaration":"${if (opted) "letter_class_v1" else ""}",
                    "syllables":{"ba":[{"text":"22","normal":true,"correction":false}]}}
                    """.trimIndent().toByteArray()

                KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)

                val nine =
                    EncodingAdapterRegistry
                        .read(engine.compiledDir, setOf(source))
                        .single { it.layout == KeyboardLayoutKind.PHONE9 }
                if (opted) {
                    assertTrue(nine.schemaID != source)
                    assertTrue(
                        File(workDir, "${nine.schemaID}.schema.yaml").readText().contains("prefix_token_rewrite_v4"),
                    )
                } else {
                    assertEquals(source, nine.schemaID)
                }
            }
        }

    @Test
    fun aNineKeyCompanionWhoseDigitsAreSwallowedIsSkipped() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()
            val nine = CompactSchemaAdapter.identifier(source, requireNotNull(KeyboardLayoutKind.PHONE9.compactFold))
            engine.composes = { it != nine }

            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)

            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18),
                EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)).map { it.layout },
            )
            // Only the 9-key layout is probed, with exactly the keys it sends.
            assertEquals(listOf(nine to "23456789"), engine.probed)
            assertFalse(File(engine.compiledDir, "$nine.schema.yaml").exists())
        }

    @Test
    fun aSchemeWithoutAProvableProjectionOrProducerStaysOn26Keys() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()
            engine.projectionBytes = null
            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)
            assertEquals(emptyList<Any>(), EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)))
            assertTrue(engine.deployed.isEmpty())

            val table =
                "schema:\n  schema_id: $source\nengine:\n  translators:\n    - script_translator\n    - table_translator\n"
            val (otherSource, otherWork, other) = setUp(table, name = "b")
            KeyboardCompanions(otherSource, otherWork, listOf(source)).generate(other)
            assertEquals(emptyList<Any>(), EncodingAdapterRegistry.read(other.compiledDir, setOf(source)))
        }

    @Test
    fun aShapeCodeTableStaysOn26KeysWithoutReadingItsProjection() =
        runBlocking {
            // rime-stroke: a table_translator over 157 000 codes, whose folded spellings exhausted the deploy process.
            val stroke =
                "schema:\n  schema_id: $source\n  name: stroke\nengine:\n  translators:\n" +
                    "    - punct_translator\n    - reverse_lookup_translator\n    - table_translator\n" +
                    "speller:\n  alphabet: abcdefghijklmnopqrstuvwxyz\ntranslator:\n  dictionary: stroke\n"
            val (sourceDir, workDir, engine) = setUp(stroke)
            var asked = false
            val counting =
                object : DeployedEngine by engine {
                    override suspend fun sourceProjection(schemaId: String): ByteArray? {
                        asked = true
                        return engine.sourceProjection(schemaId)
                    }
                }

            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(counting)

            assertFalse(asked)
            assertTrue(engine.deployed.isEmpty())
            assertEquals(emptyList<Any>(), EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)))
        }

    @Test
    fun aProjectionPastTheFoldBudgetStaysOn26Keys() =
        runBlocking {
            val (sourceDir, workDir, engine) = setUp()
            // A script source whose tokens are whole codes, like a stroke table served by script_translator.
            val tokens =
                (0..CompactSchemaAdapter.FOLD_TOKEN_LIMIT).joinToString(",") { index ->
                    val code = Integer.toString(index, 5).map { "hspnz"[it - '0'] }.joinToString("")
                    "\"$code\":[{\"text\":\"$code\",\"normal\":true,\"correction\":false}]"
                }
            engine.projectionBytes =
                (
                    "{\"source_schema\":\"$source\",\"translator_namespace\":\"translator\"," +
                        "\"producer_class\":\"script_translator\",\"dictionary\":\"d\",\"alphabet\":\"hspnz\"," +
                        "\"separator\":\" \",\"algebra\":[],\"syllables\":{$tokens}}"
                ).toByteArray()

            KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine)

            assertTrue(engine.deployed.isEmpty())
            assertEquals(emptyList<Any>(), EncodingAdapterRegistry.read(engine.compiledDir, setOf(source)))
        }

    @Test
    fun aSchemaTheGeneratorRefusesFailsTheDeployment() {
        val (sourceDir, workDir, engine) = setUp(schemaText.replace("schema_id: $source", "schema_id: someone_else"))
        assertThrows(KeyboardCompanionException::class.java) {
            runBlocking { KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine) }
        }
        assertFalse(File(engine.compiledDir, EncodingAdapterRegistry.FILE_NAME).exists())
    }

    @Test
    fun aCompanionLibrimeDoesNotCompileFailsTheDeployment() {
        val (sourceDir, workDir, engine) = setUp()
        engine.compiles = false
        assertThrows(DeployException::class.java) {
            runBlocking { KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine) }
        }
        assertFalse(File(engine.compiledDir, EncodingAdapterRegistry.FILE_NAME).exists())
    }

    @Test
    fun aPackageSchemaWithTheCompanionsNameIsRefused() {
        val (sourceDir, workDir, engine) = setUp()
        val taken = CompactSchemaAdapter.identifier(source, requireNotNull(KeyboardLayoutKind.MERGED14.compactFold))
        File(sourceDir, "$taken.schema.yaml").writeText("schema:\n  schema_id: $taken\n")
        assertThrows(KeyboardCompanionException::class.java) {
            runBlocking { KeyboardCompanions(sourceDir, workDir, listOf(source)).generate(engine) }
        }
    }

    @Test
    fun theRegistryKeepsOnlyEntriesItsBuildCanRun() {
        val build = temp.newFolder("registry")
        val compiled = "keyboard_00000000000000000000_14"
        File(build, "$compiled.schema.yaml").writeText("schema:\n  schema_id: $compiled\n")
        val adapters =
            listOf(
                adapter(source, compiled, KeyboardLayoutKind.MERGED14),
                adapter(source, "keyboard_00000000000000000000_18", KeyboardLayoutKind.MERGED18),
                adapter("not_deployed", compiled, KeyboardLayoutKind.PHONE9),
                adapter(source, compiled, KeyboardLayoutKind.QWERTY26),
                adapter(source, "../escape", KeyboardLayoutKind.PHONE9),
                adapter(source, source, KeyboardLayoutKind.PHONE9),
                adapter(source, source, KeyboardLayoutKind.PHONE9),
            )
        File(build, EncodingAdapterRegistry.FILE_NAME).writeText(EncodingAdapterRegistry.encode(adapters))

        assertEquals(
            listOf(adapters[0], adapters[5]),
            EncodingAdapterRegistry.read(build, setOf(source)),
        )
        File(build, EncodingAdapterRegistry.FILE_NAME).writeText("{not json")
        assertEquals(emptyList<Any>(), EncodingAdapterRegistry.read(build, setOf(source)))
    }

    private fun adapter(
        sourceId: String,
        schemaId: String,
        layout: KeyboardLayoutKind,
    ) = app.songyan.ime.keyboard.engine.KeyboardResourceSet
        .EncodingAdapter(sourceId, schemaId, layout, null)
}

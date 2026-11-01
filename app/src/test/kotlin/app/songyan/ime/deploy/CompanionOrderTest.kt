package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SourceScheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A scheme package with several input methods is published as soon as the one in use has its
 * compact layouts; the rest follow in a second, complete generation.
 */
class CompanionOrderTest {
    private val storage = DeployFixtures.storage()
    private val runner = CompilingRunner()
    private val coordinator = DeployFixtures.coordinator(storage, runner)
    private val publisher = GenerationPublisher(storage)

    private val quanpin = File("../core/rime/src/main/assets/rime/builtin/songyan_quanpin.schema.yaml").readText()
    private val projection =
        File("../core/packages/src/test/resources/package-fixtures/songyan_quanpin.projection.json").readText()

    /** Pretends to be librime: a schema compiles by being copied into the build directory. */
    private inner class CompilingRunner : RimeDeployRunner {
        val compiled = mutableListOf<String>()

        override suspend fun deployFull(
            sharedDataDir: File,
            userDataDir: File,
            appName: String,
            appVersion: String,
            afterDeploy: (suspend (DeployedEngine) -> Unit)?,
        ): DeployOutcome {
            compiled.clear()
            val build = File(userDataDir, "build").also { it.mkdirs() }
            val schemas = sharedDataDir.listFiles().orEmpty().filter { it.name.endsWith(".schema.yaml") }
            schemas.forEach { it.copyTo(File(build, it.name), overwrite = true) }
            File(build, "default.yaml").writeText("__build_info:\n  rime_version: 1.17.0\n")
            afterDeploy?.invoke(
                object : DeployedEngine {
                    override val compiledDir = build

                    override suspend fun sourceProjection(schemaId: String): ByteArray =
                        projection.replace("songyan_quanpin", schemaId).toByteArray()

                    override suspend fun schemaValue(
                        schemaId: String,
                        key: String,
                    ): String? {
                        if (!File(build, "$schemaId.schema.yaml").isFile) return null
                        return when (key) {
                            "schema/schema_id" -> schemaId
                            "schema/name" -> schemaId
                            "translator/delimiter" -> " '"
                            else -> null
                        }
                    }

                    override suspend fun deploySchema(file: File): Boolean {
                        compiled += file.name.removeSuffix(".schema.yaml")
                        file.copyTo(File(build, file.name), overwrite = true)
                        return true
                    }
                },
            )
            return DeployOutcome(true, build, "1.17.0", schemas.map { it.name.removeSuffix(".schema.yaml") }, null)
        }
    }

    private fun staged(vararg ids: String): BuiltSource {
        val dir = File(storage.importStagingDir, "package").also { it.mkdirs() }
        File(dir, "default.yaml").writeText("schema_list:\n" + ids.joinToString("") { "  - schema: $it\n" })
        for (id in ids) File(dir, "$id.schema.yaml").writeText(quanpin.replace("songyan_quanpin", id))
        return BuiltSource(
            dir,
            SourceManifest(
                "scheme-0123456789abcdef",
                SourceKind.SCHEME,
                "Demo",
                ids.map { SourceScheme(it, it) },
                "rev-1",
            ),
        )
    }

    private fun layoutSources(record: PublicationRecord): Set<String> {
        val build = File(storage.generationDir(record.generation), "build")
        return EncodingAdapterRegistry.read(build, record.schemaIds.toSet()).map { it.sourceSchemaID }.toSet()
    }

    @Test
    fun theInputMethodInUseGetsItsLayoutsFirstAndTheRestFollow() =
        runTest {
            storage.keyboardSchemaFile.writeText("beta")

            val first = coordinator.installSource(staged("alpha", "beta", "gamma")).getOrThrow()

            assertTrue(first.companionsPending)
            assertEquals(setOf("beta"), layoutSources(first))
            assertEquals(3, runner.compiled.count { it.startsWith("keyboard_") })
            assertTrue(coordinator.companionsPending())

            val complete = coordinator.completeCompanions().getOrThrow()!!

            assertFalse(complete.companionsPending)
            assertEquals(setOf("alpha", "beta", "gamma"), layoutSources(complete))
            assertEquals(complete, publisher.readActive())
            assertFalse(coordinator.companionsPending())
            assertEquals("nothing left to do", complete, coordinator.completeCompanions().getOrThrow())
        }

    /**
     * The second part continues in the work directory of the first: the layouts already compiled
     * are not compiled again, and the directory is gone once the generation is complete.
     */
    @Test
    fun completingTheLayoutsContinuesWhereTheFirstPartStopped() =
        runTest {
            storage.keyboardSchemaFile.writeText("beta")
            val first = coordinator.installSource(staged("alpha", "beta", "gamma")).getOrThrow()
            val workDir = storage.deployWorkDir(first.sourceId)
            assertEquals(listOf("beta"), PendingWork.read(workDir, first.generation))
            val betaLayouts = runner.compiled.filter { it.startsWith("keyboard_") }

            val complete = coordinator.completeCompanions().getOrThrow()!!

            assertEquals(6, runner.compiled.count { it.startsWith("keyboard_") })
            assertTrue("beta's layouts are not compiled again", runner.compiled.none { it in betaLayouts })
            assertEquals(setOf("alpha", "beta", "gamma"), layoutSources(complete))
            assertFalse(workDir.exists())
        }

    @Test
    fun changedFeatureSettingsCompleteTheLayoutsFromScratch() =
        runTest {
            storage.keyboardSchemaFile.writeText("beta")
            coordinator.installSource(staged("alpha", "beta", "gamma")).getOrThrow()
            val features = EngineFeatureStore(storage)
            features.write(features.read().copy(typoCorrection = !features.read().typoCorrection))

            val complete = coordinator.completeCompanions().getOrThrow()!!

            assertEquals(9, runner.compiled.count { it.startsWith("keyboard_") })
            assertEquals(setOf("alpha", "beta", "gamma"), layoutSources(complete))
        }

    @Test
    fun withoutAKnownInputMethodThePackagesFirstGoesFirst() =
        runTest {
            storage.keyboardSchemaFile.writeText("songyan_quanpin")

            val first = coordinator.installSource(staged("alpha", "beta")).getOrThrow()

            assertEquals(setOf("alpha"), layoutSources(first))
        }

    @Test
    fun aPackageWithOneInputMethodIsCompleteAtOnce() =
        runTest {
            val record = coordinator.installSource(staged("alpha")).getOrThrow()

            assertFalse(record.companionsPending)
            assertEquals(setOf("alpha"), layoutSources(record))
        }

    @Test
    fun thePendingMarkSurvivesTheRecordsRoundTrip() {
        val record =
            PublicationRecord(2, "g", "s", "a", "r", 0L, companionsPending = true)
        assertTrue(PublicationRecord.parse(record.toJson())!!.companionsPending)
        assertFalse(PublicationRecord.parse(record.copy(companionsPending = false).toJson())!!.companionsPending)
    }
}

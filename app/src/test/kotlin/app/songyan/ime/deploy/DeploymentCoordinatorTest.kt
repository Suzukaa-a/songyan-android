package app.songyan.ime.deploy

import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SourceScheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Installing staged source trees: validation, rollback and clean builds. */
class DeploymentCoordinatorTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val coordinator = DeployFixtures.coordinator(storage, runner)
    private val publisher = GenerationPublisher(storage)

    private fun staged(
        name: String,
        marker: String,
        sourceId: String = "scheme-0123456789abcdef",
        root: File = storage.importStagingDir,
    ): BuiltSource {
        val dir = File(root, name).also { it.mkdirs() }
        File(dir, "default.yaml").writeText("schema_list:\n  - schema: demo\n")
        File(dir, "demo.schema.yaml").writeText("schema:\n  schema_id: demo\n# $marker\n")
        return BuiltSource(
            dir,
            SourceManifest(sourceId, SourceKind.SCHEME, "Demo", listOf(SourceScheme("demo", "Demo")), "rev-$marker"),
        )
    }

    @Test
    fun firstRunAndRetryWaitForTheUsersScheme() =
        runTest {
            assertTrue(coordinator.ensureDeployed().isFailure)
            assertTrue(coordinator.redeployActive().isFailure)
            assertNull(publisher.readActive())
            assertFalse(storage.sourceDir(SourceIds.BUILTIN).exists())
            val downloaded = coordinator.installSource(staged("download", "first")).getOrThrow()
            assertEquals(downloaded, coordinator.ensureDeployed().getOrThrow())
        }

    @Test
    fun aFailedFirstImportDoesNotEnableTheBundledSchemeOnRetry() =
        runTest {
            runner.succeed = false
            assertTrue(coordinator.installSource(staged("failed", "first")).isFailure)
            assertTrue(coordinator.ensureDeployed().isFailure)
            assertNull(publisher.readActive())
        }

    @Test
    fun aFirstInstallKilledAfterMovingTheSourceDoesNotActivateTheBundledScheme() =
        runTest {
            val source = staged("interrupted", "first")
            app.songyan.ime.storage.SourceCatalog
                .write(source.directory, source.manifest)
            File(source.directory, app.songyan.ime.storage.InputSchemeSetup.INSTALLING).writeText("")
            source.directory.renameTo(storage.sourceDir(source.manifest.sourceId))
            assertTrue(coordinator.ensureDeployed().isFailure)
            assertNull(publisher.readActive())
        }

    @Test
    fun restoredLearningCanStillRecoverWithTheBundledScheme() =
        runTest {
            val learned = File(storage.userRoot, "pinyin/words.userdb").also { it.mkdirs() }
            val words = File(learned, "words").also { it.writeText("keep") }
            assertEquals(SourceIds.BUILTIN, coordinator.ensureDeployed().getOrThrow().sourceId)
            assertEquals("keep", words.readText())
        }

    @Test
    fun anExistingBuiltInChoiceSurvivesAnUpgrade() =
        runTest {
            coordinator.deployBuiltin().getOrThrow()
            val upgraded =
                DeployFixtures.coordinator(
                    storage,
                    runner,
                    stamp = DeployFixtures.STAMP.copy(app = "upgrade"),
                )
            assertEquals(SourceIds.BUILTIN, upgraded.ensureDeployed().getOrThrow().sourceId)
        }

    @Test
    fun installMovesTheStagedTreeAndPublishesIt() =
        runTest {
            val stage = staged("a", "alpha")

            val record = coordinator.installSource(stage).getOrThrow()

            assertEquals("scheme-0123456789abcdef", record.sourceId)
            assertEquals(listOf("demo"), record.schemaIds)
            assertFalse("the staged tree is moved, not copied", stage.directory.exists())
            assertEquals(record, publisher.readActive())
            assertEquals(listOf(false), runner.workDirExisted)
            assertFalse("the scratch build directory is removed", storage.deployWorkDir(record.sourceId).exists())
        }

    @Test
    fun aFailedReinstallKeepsThePreviousTreeAndGeneration() =
        runTest {
            val first = coordinator.installSource(staged("a", "alpha")).getOrThrow()
            runner.succeed = false

            assertTrue(coordinator.installSource(staged("b", "beta")).isFailure)

            assertEquals(first, publisher.readActive())
            val schema = File(storage.sourceDir(first.sourceId), "demo.schema.yaml").readText()
            assertTrue(schema.contains("alpha"))
            assertTrue(
                "no hidden backup is left behind",
                storage.sourcesDir.listFiles().orEmpty().none {
                    it.name.startsWith(
                        DeploymentCoordinator.SOURCE_BACKUP_PREFIX,
                    )
                },
            )
        }

    @Test
    fun refusesTreesFromOutsideTheStagingRootAndUnsafeIdentities() =
        runTest {
            val outside = staged("x", "x", root = File(storage.rimeRoot, "elsewhere"))
            assertTrue(coordinator.installSource(outside).isFailure)
            assertTrue(coordinator.installSource(staged("y", "y", sourceId = "../escape")).isFailure)
            assertTrue(coordinator.installSource(staged("z", "z", sourceId = SourceIds.BUILTIN)).isFailure)
            val missingDefault = staged("w", "w").also { File(it.directory, "default.yaml").delete() }
            assertTrue(coordinator.installSource(missingDefault).isFailure)
            assertNull(publisher.readActive())
        }

    @Test
    fun aStaleGenerationIsNotUsable() =
        runTest {
            coordinator.deployBuiltin().getOrThrow()
            assertTrue(coordinator.hasUsableGeneration())
            val newer =
                DeployFixtures.coordinator(
                    storage,
                    runner,
                    stamp = DeployFixtures.STAMP.copy(engine = "1.18.0"),
                )
            assertFalse(newer.hasUsableGeneration())

            newer.ensureDeployed().getOrThrow()
            assertTrue(newer.hasUsableGeneration())
            assertEquals(2, runner.calls)
        }
}

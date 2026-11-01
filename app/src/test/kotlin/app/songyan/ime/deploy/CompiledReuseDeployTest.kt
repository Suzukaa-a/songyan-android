package app.songyan.ime.deploy

import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SourceScheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A deployment starts from the compiled dictionaries of the source's last generation built by the same engine. */
class CompiledReuseDeployTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val coordinator = DeployFixtures.coordinator(storage, runner)

    private fun staged(
        name: String,
        sourceId: String = "scheme-0123456789abcdef",
    ): BuiltSource {
        val dir = File(storage.importStagingDir, name).also { it.mkdirs() }
        File(dir, "default.yaml").writeText("schema_list:\n  - schema: demo\n")
        File(dir, "demo.schema.yaml").writeText("schema:\n  schema_id: demo\ntranslator:\n  dictionary: demo\n")
        File(dir, "demo.dict.yaml").writeText("---\nname: demo\n...\n你\tni\t1\n")
        return BuiltSource(
            dir,
            SourceManifest(sourceId, SourceKind.SCHEME, "Demo", listOf(SourceScheme("demo", "Demo")), "rev-$name"),
        )
    }

    @Test
    fun aRebuildStartsFromTheSourcesLastBuild() =
        runTest {
            coordinator.installSource(staged("a")).getOrThrow()
            coordinator.redeployActive().getOrThrow()

            assertEquals(listOf(emptySet(), setOf("demo.table.bin", "demo.prism.bin")), runner.buildAtStart)
            assertFalse("the work directory is removed", storage.deployWorkDir("scheme-0123456789abcdef").exists())
        }

    @Test
    fun aNewVersionOfThePackageStartsFromThePreviousOne() =
        runTest {
            coordinator.installSource(staged("a")).getOrThrow()
            coordinator.updateSource(staged("b")).getOrThrow()

            assertEquals(setOf("demo.table.bin", "demo.prism.bin"), runner.buildAtStart.last())
        }

    @Test
    fun anotherSourcesBuildIsNotUsed() =
        runTest {
            coordinator.deployBuiltin().getOrThrow()
            coordinator.installSource(staged("a")).getOrThrow()

            assertEquals(emptySet<String>(), runner.buildAtStart.last())
        }

    /** The checksums librime checks do not cover its own table builder, which another engine may change. */
    @Test
    fun anotherEnginesBuildIsNotUsed() =
        runTest {
            coordinator.installSource(staged("a")).getOrThrow()
            val upgraded =
                DeployFixtures.coordinator(storage, runner, stamp = DeployFixtures.STAMP.copy(engine = "1.18.0"))

            upgraded.redeployActive().getOrThrow()

            assertEquals(emptySet<String>(), runner.buildAtStart.last())
        }

    @Test
    fun aWorkDirectoryLeftByADeploymentThatDiedIsRemoved() =
        runTest {
            val left = storage.deployWorkDir("scheme-fedcba9876543210").also { it.mkdirs() }
            File(left, "build").mkdirs()
            PendingWork.write(left, "some-old-generation", listOf("demo"))

            coordinator.deployBuiltin().getOrThrow()

            assertFalse(left.exists())
        }

    @Test
    fun configTimesOfAnInstalledTreeFollowTheirContent() =
        runTest {
            val record = coordinator.installSource(staged("a")).getOrThrow()
            val schema = File(storage.sourceDir(record.sourceId), "demo.schema.yaml")

            assertEquals(CompiledReuse.contentTime(schema.readBytes()), schema.lastModified())
            assertTrue(File(storage.sourceDir(record.sourceId), "demo.dict.yaml").isFile)
        }
}

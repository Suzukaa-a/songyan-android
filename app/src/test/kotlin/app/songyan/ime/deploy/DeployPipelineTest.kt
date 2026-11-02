package app.songyan.ime.deploy

import app.songyan.ime.deploy.DeployFixtures.STAMP
import app.songyan.ime.deploy.DeployFixtures.dictionary
import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Requests as the :deploy process runs them, from the incoming files to the published generation:
 * imports add sources side by side, every stored source can be used again or deleted, and a
 * stale generation is rebuilt from the source that is active, never silently the built-in one.
 */
class DeployPipelineTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)

    private suspend fun importDictionary(
        id: String,
        files: Map<String, String>,
    ): RequestStatus {
        DeployFixtures.pickDictionaries(storage, id, files)
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_DICTIONARY))
    }

    private suspend fun importScheme(id: String): RequestStatus {
        DeployFixtures.pickSchemePackage(storage, id)
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME))
    }

    @Test
    fun aFreshInstallDoesNotPublishTheBuiltInPackage() =
        runTest {
            assertEquals(RequestState.FAILED, pipeline.run(DeployRequest("first", DeployMode.BUILTIN)).state)
            assertNull(publisher.readActive())
        }

    @Test
    fun restoredLearningPublishesTheBuiltInPackageWithAStamp() =
        runTest {
            File(storage.userRoot, "pinyin").mkdirs()
            val status = pipeline.run(DeployRequest("r1", DeployMode.BUILTIN))

            assertEquals(RequestState.SUCCEEDED, status.state)
            val active = publisher.readActive()!!
            assertEquals(SourceIds.BUILTIN, active.sourceId)
            assertEquals(listOf("songyan_quanpin", "songyan_flypy"), active.schemaIds)
            assertEquals(STAMP, active.stamp)
            assertTrue(publisher.isComplete(active))

            // A current generation is reused, not rebuilt.
            pipeline.run(DeployRequest("r2", DeployMode.BUILTIN))
            assertEquals(1, runner.calls)
            assertEquals(active.generation, publisher.readActive()!!.generation)
        }

    @Test
    fun aDictionaryWithImportTablesIsImportedFromSeveralFilesAndKeepsItsOriginals() =
        runTest {
            val status =
                importDictionary(
                    "r1",
                    mapOf(
                        "main.dict.yaml" to dictionary("main", "你好\tni hao\t10\n", imports = listOf("extra")),
                        "extra.dict.yaml" to dictionary("extra", "世界\tshi jie\t5\n"),
                    ),
                )

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val active = publisher.readActive()!!
            assertEquals(SourceIds.DICTIONARY, active.sourceId)
            val source = storage.sourceDir(SourceIds.DICTIONARY)
            assertTrue(File(source, DictionaryImportBuilder.ORIGINALS_DIRECTORY + "/extra.dict.yaml").isFile)
            assertEquals(SourceKind.DICTIONARY, SourceCatalog(storage).read(SourceIds.DICTIONARY)?.kind)
            assertFalse("the incoming files belong to the request and are gone", storage.incomingDir("r1").exists())
        }

    @Test
    fun cancellationDuringNativeDeploymentFinishesSafelyAndRestoresTheSourceWithoutPublishing() =
        runTest {
            val old = dictionary("main", "你好\tni hao\t1\n")
            importDictionary("old", mapOf("main.dict.yaml" to old))
            val before = publisher.readActive()!!
            val stored = File(storage.sourceDir(SourceIds.DICTIONARY), "songyan_imported.dict.yaml").readText()
            var cancelled = false
            var finished = false
            val cancellingRunner =
                object : RimeDeployRunner by runner {
                    override suspend fun deployFull(
                        sharedDataDir: File,
                        userDataDir: File,
                        appName: String,
                        appVersion: String,
                        afterDeploy: (suspend (DeployedEngine) -> Unit)?,
                    ): DeployOutcome {
                        runner.failIf = {
                            cancelled = true
                            false
                        }
                        return runner.deployFull(sharedDataDir, userDataDir, appName, appVersion, afterDeploy).also {
                            assertTrue(it.success)
                            assertTrue(userDataDir.isDirectory)
                            finished = true
                        }
                    }
                }
            DeployFixtures.pickDictionaries(
                storage,
                "cancel",
                mapOf("main.dict.yaml" to dictionary("main", "世界\tshi jie\t1\n")),
            )

            val result =
                DeployFixtures.pipeline(storage, cancellingRunner).run(
                    DeployRequest("cancel", DeployMode.IMPORT_DICTIONARY),
                    cancelled = { cancelled },
                )

            assertTrue(finished)
            assertEquals(RequestState.FAILED, result.state)
            assertEquals(DeployPipeline.CANCELLED, result.problem)
            assertEquals(before, publisher.readActive())
            assertEquals(1, publisher.publishedRecords().size)
            assertEquals(stored, File(storage.sourceDir(SourceIds.DICTIONARY), "songyan_imported.dict.yaml").readText())
            assertFalse(storage.incomingDir("cancel").exists())
        }

    @Test
    fun aMissingImportedTableNamesTheFileItNeeds() =
        runTest {
            val status =
                importDictionary(
                    "r1",
                    mapOf(
                        "main.dict.yaml" to dictionary("main", "你好\tni hao\t1\n", imports = listOf("extra")),
                    ),
                )

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("MISSING_DEPENDENCIES", status.problem)
            assertEquals(listOf("extra.dict.yaml"), status.paths)
            assertNull(publisher.readActive())
        }

    @Test
    fun aSchemePackageIsStoredBesideTheDictionaryAndEitherCanBeUsedAgain() =
        runTest {
            importDictionary("r1", mapOf("words.dict.yaml" to dictionary("words", "你好\tni hao\t1\n")))
            val dictionaryGeneration = publisher.readActive()!!.generation
            val scheme = importScheme("r2")

            assertEquals(scheme.problem, RequestState.SUCCEEDED, scheme.state)
            val schemeRecord = publisher.readActive()!!
            assertTrue(SourceIds.isScheme(schemeRecord.sourceId))
            // The package's own default.yaml decides the order and which input methods exist.
            assertEquals(listOf("rime_ice", "double_pinyin"), schemeRecord.schemaIds)
            val schemeSource = storage.sourceDir(schemeRecord.sourceId)
            assertTrue(File(schemeSource, "lua/date_translator.lua").isFile)
            assertEquals("{\"package\": true}\n", File(schemeSource, "opencc/s2t.json").readText())
            assertTrue(
                "bundled OpenCC data fills what the package lacks",
                File(schemeSource, "opencc/STCharacters.ocd2").isFile,
            )
            assertFalse(File(schemeSource, "__MACOSX").exists())
            assertTrue(
                "the dictionary source survives a scheme import",
                storage.sourceDir(SourceIds.DICTIONARY).isDirectory,
            )

            val calls = runner.calls
            val back = pipeline.run(DeployRequest("r3", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.DICTIONARY))
            assertEquals(RequestState.SUCCEEDED, back.state)
            assertEquals(SourceIds.DICTIONARY, publisher.readActive()!!.sourceId)
            assertEquals("a stored current generation is reused", calls, runner.calls)
            assertEquals(dictionaryGeneration, publisher.readActive()!!.generation)

            val sources = SourceCatalog(storage).list().map { it.sourceId }
            assertTrue(SourceIds.DICTIONARY in sources && schemeRecord.sourceId in sources)
        }

    @Test
    fun deletingTheActiveSourceFallsBackToBuiltInAndKeepsItsLearningData() =
        runTest {
            importScheme("r1")
            val scheme = publisher.readActive()!!.sourceId
            val learning =
                File(LearningData.directory(storage, scheme), "rime_ice.userdb/CURRENT").also {
                    it.parentFile.mkdirs()
                    it.writeText("learned")
                }

            val status = pipeline.run(DeployRequest("r2", DeployMode.DELETE_SOURCE, sourceId = scheme))

            assertEquals(RequestState.SUCCEEDED, status.state)
            assertEquals(SourceIds.BUILTIN, publisher.readActive()!!.sourceId)
            assertFalse(storage.sourceDir(scheme).exists())
            assertTrue("deleting a package keeps what was learned with it", learning.isFile)

            // Importing the same package again finds the same identity, so the learning comes back.
            importScheme("r3")
            assertEquals(scheme, publisher.readActive()!!.sourceId)
        }

    @Test
    fun deletingAnInactiveSourceLeavesTheKeyboardAlone() =
        runTest {
            importDictionary("r1", mapOf("words.dict.yaml" to dictionary("words", "你好\tni hao\t1\n")))
            importScheme("r2")
            val active = publisher.readActive()!!

            pipeline.run(DeployRequest("r3", DeployMode.DELETE_SOURCE, sourceId = SourceIds.DICTIONARY))

            assertEquals(active.generation, publisher.readActive()!!.generation)
            assertFalse(storage.sourceDir(SourceIds.DICTIONARY).exists())
            val builtin = pipeline.run(DeployRequest("r4", DeployMode.DELETE_SOURCE, sourceId = SourceIds.BUILTIN))
            assertEquals("the bundled package cannot be deleted", RequestState.FAILED, builtin.state)
        }

    @Test
    fun anAppUpdateRebuildsTheActiveSourceNotTheBuiltInOne() =
        runTest {
            importDictionary("r1", mapOf("words.dict.yaml" to dictionary("words", "你好\tni hao\t1\n")))
            val old = publisher.readActive()!!

            // A new app version ships changed templates; the dictionary is rebuilt over them.
            val updatedStamp = STAMP.copy(app = "1.1/2/200")
            val updated = DeployFixtures.pipeline(storage, runner, DeployFixtures.assets("# v2\n"), updatedStamp)
            val status = updated.run(DeployRequest("r2", DeployMode.BUILTIN))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val rebuilt = publisher.readActive()!!
            assertEquals(SourceIds.DICTIONARY, rebuilt.sourceId)
            assertNotEquals(old.generation, rebuilt.generation)
            assertEquals(updatedStamp, rebuilt.stamp)
            val schema =
                File(
                    storage.generationDir(rebuilt.generation),
                    "resources/songyan_quanpin.schema.yaml",
                ).readText()
            assertTrue("the rebuilt source uses the new templates", schema.contains("# v2"))
            assertTrue(schema.contains("songyan_imported"))
        }

    @Test
    fun retryRebuildsTheActiveSchemePackage() =
        runTest {
            importScheme("r1")
            val scheme = publisher.readActive()!!

            val status = pipeline.run(DeployRequest("r2", DeployMode.REDEPLOY_ACTIVE))

            assertEquals(RequestState.SUCCEEDED, status.state)
            val rebuilt = publisher.readActive()!!
            assertEquals(scheme.sourceId, rebuilt.sourceId)
            assertNotEquals(scheme.generation, rebuilt.generation)
        }

    @Test
    fun aRejectedPackageReportsWhyAndLeavesNothingBehind() =
        runTest {
            val files = DeployFixtures.schemePackageFiles().filterKeys { !it.endsWith(".lua") }
            DeployFixtures.pickSchemePackage(storage, "r1", files)

            val status = pipeline.run(DeployRequest("r1", DeployMode.IMPORT_SCHEME))

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("MISSING_DEPENDENCY", status.problem)
            assertTrue(status.paths.any { it.endsWith("date_translator.lua") })
            assertNull(publisher.readActive())
            assertFalse(storage.incomingDir("r1").exists())
            assertTrue(
                storage.importStagingDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun aCancelledImportStopsWithoutPublishing() =
        runTest {
            DeployFixtures.pickSchemePackage(storage, "r1")

            val status = pipeline.run(DeployRequest("r1", DeployMode.IMPORT_SCHEME)) { true }

            assertEquals(RequestState.FAILED, status.state)
            assertEquals(DeployPipeline.CANCELLED, status.problem)
            assertNull(publisher.readActive())
            assertNotNull(status)
        }
}

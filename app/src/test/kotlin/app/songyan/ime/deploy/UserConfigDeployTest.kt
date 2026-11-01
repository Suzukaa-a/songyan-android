package app.songyan.ime.deploy

import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.userconfig.UserConfigFiles
import app.songyan.ime.userconfig.UserConfigStatus
import app.songyan.ime.userconfig.UserScripts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The user's Rime configuration in the deployment: applied files are layered over the source as
 * librime's user data folder and published with the build; edits wait for "apply"; a file that
 * cannot be used, or a deployment that fails with it, leaves the keyboard on its generation.
 */
class UserConfigDeployTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)
    private val status = UserConfigStatus.Store(storage)

    private fun edit(
        name: String,
        text: String,
    ) = File(storage.userConfigDir.also { it.mkdirs() }, name).writeText(text)

    private suspend fun run(mode: DeployMode): RequestStatus =
        pipeline.run(DeployRequest("r" + System.nanoTime(), mode))

    private suspend fun importPackage(): String {
        DeployFixtures.pickSchemePackage(storage, "p1", DeployFixtures.stackablePackageFiles())
        return pipeline.run(DeployRequest("p1", DeployMode.IMPORT_SCHEME)).sourceId!!
    }

    @Test
    fun appliedFilesPatchTheSourceInUseAndArePublishedWithIt() =
        runTest {
            val id = importPackage()
            val before = publisher.readActive()!!
            edit("rime_ice.custom.yaml", PATCH)
            edit("custom_phrase.txt", "松烟\tsy\t1\n")

            val applied = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(applied.problem, RequestState.SUCCEEDED, applied.state)
            val after = publisher.readActive()!!
            assertEquals(id, after.sourceId)
            assertNotEquals(before.generation, after.generation)
            assertNotEquals("the configuration is part of the revision", before.revision, after.revision)
            assertEquals(PATCH, runner.overlays.last()["rime_ice.custom.yaml"])
            assertEquals(
                PATCH,
                File(storage.generationDir(after.generation), "resources/rime_ice.custom.yaml").readText(),
            )
            assertTrue(File(storage.generationDir(after.generation), "resources/custom_phrase.txt").isFile)
            assertTrue(publisher.isComplete(after))
            assertEquals(UserConfigStatus.State.APPLIED, status.read()!!.state)
        }

    @Test
    fun aCopyOfAFileInAFolderReplacesThePackagesFileInTheDeploymentAndTheGeneration() =
        runTest {
            val id = importPackage()
            val own = "---\nname: cn_dicts/base\n...\n我的\two de\t1\n"
            File(storage.userConfigDir, "cn_dicts").mkdirs()
            File(storage.userConfigDir, "cn_dicts/base.dict.yaml").writeText(own)

            val applied = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(applied.problem, RequestState.SUCCEEDED, applied.state)
            assertEquals(own, runner.overlays.last()["cn_dicts/base.dict.yaml"])
            val generation = storage.generationDir(publisher.readActive()!!.generation)
            assertEquals(own, File(generation, "resources/cn_dicts/base.dict.yaml").readText())
            assertTrue(
                "the package's other files are still the package's",
                File(generation, "resources/rime_ice.schema.yaml").isFile,
            )
            assertTrue(
                "the stored package is untouched",
                File(storage.sourceDir(id), "cn_dicts/base.dict.yaml").readText().contains("世界"),
            )
        }

    @Test
    fun aScriptThatIsNotConfirmedIsRefusedByNameAndOnceConfirmedApplies() =
        runTest {
            importPackage()
            File(storage.userConfigDir, "lua").mkdirs()
            File(storage.userConfigDir, "lua/date.lua").writeText("return 1\n")
            val calls = runner.calls

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, refused.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNCONFIRMED_SCRIPT", refused.problem)
            assertEquals(listOf("lua/date.lua"), refused.paths)
            assertEquals("nothing was deployed", calls, runner.calls)
            assertFalse(File(storage.appliedConfigDir, "lua/date.lua").exists())

            val scripts = UserScripts(storage)
            scripts.accept(scripts.unconfirmed(storage.userConfigDir, null))
            val applied = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(applied.problem, RequestState.SUCCEEDED, applied.state)
            assertEquals("return 1\n", runner.overlays.last()["lua/date.lua"])

            // A script that changes after it was confirmed is asked about again.
            File(storage.userConfigDir, "lua/date.lua").writeText("return 2\n")
            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNCONFIRMED_SCRIPT", run(DeployMode.APPLY_USER_CONFIG).problem)
        }

    @Test
    fun aFileThatNamesAFileOutsideTheFoldersIsRefusedBeforeDeploying() =
        runTest {
            importPackage()
            edit("x.custom.yaml", "patch:\n  translator/dictionary: ../../../secret\n")
            val calls = runner.calls

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNSAFE_REFERENCE", refused.problem)
            assertEquals(listOf("x.custom.yaml"), refused.paths)
            assertEquals(calls, runner.calls)
        }

    @Test
    fun editsWaitForApplyingAndThenReachEveryLaterDeployment() =
        runTest {
            edit("songyan_quanpin.custom.yaml", PATCH)

            run(DeployMode.REDEPLOY_ACTIVE)
            assertFalse("not applied yet", runner.overlays.last().containsKey("songyan_quanpin.custom.yaml"))

            run(DeployMode.APPLY_USER_CONFIG)
            edit("songyan_quanpin.custom.yaml", "patch:\n  menu/page_size: 3\n")
            run(DeployMode.REDEPLOY_ACTIVE)

            assertEquals(
                "the applied copy, not the newer edit",
                PATCH,
                runner.overlays.last()["songyan_quanpin.custom.yaml"],
            )
        }

    @Test
    fun aBrokenFileIsRefusedByNameAndNothingChanges() =
        runTest {
            edit("songyan_quanpin.custom.yaml", PATCH)
            run(DeployMode.APPLY_USER_CONFIG)
            val active = publisher.readActive()!!
            val calls = runner.calls
            edit("default.custom.yaml", "patch:\n  schema_list: [\n")

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, refused.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + "INVALID_YAML", refused.problem)
            assertEquals(listOf("default.custom.yaml"), refused.paths)
            assertEquals("nothing was deployed", calls, runner.calls)
            assertEquals(active.generation, publisher.readActive()!!.generation)
            assertFalse(File(storage.appliedConfigDir, "default.custom.yaml").exists())
            assertEquals("default.custom.yaml", status.read()!!.file)
        }

    @Test
    fun aPatchTheEngineRejectsKeepsThePreviousGenerationAndConfiguration() =
        runTest {
            edit("songyan_quanpin.custom.yaml", PATCH)
            run(DeployMode.APPLY_USER_CONFIG)
            val active = publisher.readActive()!!
            val digest = UserConfigFiles.digest(storage.appliedConfigDir)
            edit("songyan_quanpin.custom.yaml", "patch:\n  engine/processors: []\n")
            runner.failIf = { File(it, "songyan_quanpin.custom.yaml").readText().contains("processors") }

            val failed = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, failed.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + DeploymentCoordinator.DEPLOY_PROBLEM, failed.problem)
            assertEquals("the keyboard keeps its generation", active.generation, publisher.readActive()!!.generation)
            assertEquals(
                "the earlier configuration stays applied",
                digest,
                UserConfigFiles.digest(storage.appliedConfigDir),
            )
            assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
        }

    @Test
    fun aConfigurationThatBreaksAnotherSourceIsLeftOutOfItsDeployment() =
        runTest {
            edit("default.custom.yaml", "patch:\n  schema_list:\n    - schema: double_pinyin\n")
            val id = importPackage()
            run(DeployMode.APPLY_USER_CONFIG)
            // Only the package has double_pinyin: the bundled input methods deploy no schema with it.
            runner.failIf = { work ->
                File(work, "default.custom.yaml").isFile && runner.deployedSources.last().name == SourceIds.BUILTIN
            }

            val switched = pipeline.run(DeployRequest("a1", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN))

            assertEquals(switched.problem, RequestState.SUCCEEDED, switched.state)
            val active = publisher.readActive()!!
            assertEquals(SourceIds.BUILTIN, active.sourceId)
            assertFalse(runner.overlays.last().containsKey("default.custom.yaml"))
            val note = status.read()!!
            assertEquals(UserConfigStatus.State.SKIPPED, note.state)
            assertEquals(SourceIds.BUILTIN, note.sourceId)

            // Back on the package, its generation built with the configuration is used again.
            val calls = runner.calls
            pipeline.run(DeployRequest("a2", DeployMode.ACTIVATE_SOURCE, sourceId = id))
            assertEquals(id, publisher.readActive()!!.sourceId)
            assertEquals(calls, runner.calls)
        }

    @Test
    fun aPatchedSchemaListDecidesTheInputMethodsOffered() =
        runTest {
            importPackage()
            edit("default.custom.yaml", "patch:\n  schema_list:\n    - schema: double_pinyin\n")
            runner.schemaList = listOf("double_pinyin")

            run(DeployMode.APPLY_USER_CONFIG)

            val active = publisher.readActive()!!
            assertEquals(listOf("double_pinyin"), active.schemaIds)
            assertEquals("double_pinyin", active.schemaId)
        }

    @Test
    fun theUsersOwnDictionaryIsWhatAStackedDictionaryExtends() =
        runTest {
            val id = importPackage()
            DeployFixtures.pickDictionaries(
                storage,
                "s1",
                mapOf("mine.dict.yaml" to DeployFixtures.dictionary("mine", "松烟\tsong yan\t9\n")),
            )
            pipeline.run(DeployRequest("s1", DeployMode.IMPORT_DICTIONARY, sourceId = id))
            edit("rime_ice.dict.yaml", "---\nname: rime_ice\nversion: \"1\"\nsort: by_weight\n...\n自定\tzi ding\t1\n")

            run(DeployMode.APPLY_USER_CONFIG)

            val dictionary = runner.overlays.last().getValue("rime_ice.dict.yaml")
            assertTrue(runner.overlays.toString(), dictionary.contains("songyan_imported"))
            assertTrue(dictionary, dictionary.contains("自定"))
            assertNull(
                runner.overlays
                    .last()
                    .keys
                    .firstOrNull { it.startsWith(".own-") },
            )
        }

    @Test
    fun anApplicationThatDiedHalfWayLeavesTheEarlierConfiguration() =
        runTest {
            edit("songyan_quanpin.custom.yaml", PATCH)
            run(DeployMode.APPLY_USER_CONFIG)
            // What a process killed between setting the old copy aside and publishing leaves behind.
            val previous = File(storage.configRoot, ".previous-1")
            storage.appliedConfigDir.copyRecursively(previous)
            File(storage.appliedConfigDir, "songyan_quanpin.custom.yaml").writeText("half: done\n")
            File(storage.configRoot, ".staging-2").mkdirs()

            run(DeployMode.REDEPLOY_ACTIVE)

            assertEquals(PATCH, File(storage.appliedConfigDir, "songyan_quanpin.custom.yaml").readText())
            assertEquals(PATCH, runner.overlays.last()["songyan_quanpin.custom.yaml"])
            assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
        }

    private companion object {
        const val PATCH = "patch:\n  menu/page_size: 7\n"
    }
}

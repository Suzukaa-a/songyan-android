package app.songyan.ime.deploy

import app.songyan.ime.packages.EngineFeatureReport
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.userconfig.UserConfigEditor
import app.songyan.ime.userconfig.UserConfigFiles
import app.songyan.ime.userconfig.UserConfigScope
import app.songyan.ime.userconfig.UserConfigStatus
import app.songyan.ime.userconfig.UserOverrides
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

    /** The scope the settings screen edits and applying deploys: the source in use. */
    private val scope get() = UserConfigScope.active(storage)
    private val status get() = UserConfigStatus.Store(storage, scope)
    private val editDir get() = storage.userConfigDir(scope)
    private val appliedDir get() = storage.appliedConfigDir(scope)

    private fun edit(
        name: String,
        text: String,
    ) = File(editDir.also { it.mkdirs() }, name).writeText(text)

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
            File(editDir, "cn_dicts").mkdirs()
            File(editDir, "cn_dicts/base.dict.yaml").writeText(own)

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
            File(editDir, "lua").mkdirs()
            File(editDir, "lua/date.lua").writeText("return 1\n")
            val calls = runner.calls

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, refused.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNCONFIRMED_SCRIPT", refused.problem)
            assertEquals(listOf("lua/date.lua"), refused.paths)
            assertEquals("nothing was deployed", calls, runner.calls)
            assertFalse(File(appliedDir, "lua/date.lua").exists())

            val scripts = UserScripts(storage)
            scripts.accept(scripts.unconfirmed(editDir, null))
            val applied = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(applied.problem, RequestState.SUCCEEDED, applied.state)
            assertEquals("return 1\n", runner.overlays.last()["lua/date.lua"])

            // A script that changes after it was confirmed is asked about again.
            File(editDir, "lua/date.lua").writeText("return 2\n")
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
    fun aCompiledFileThatNamesAFileOutsideTheDataFoldersFailsTheDeploymentWhateverTheSpelling() =
        runTest {
            importPackage()
            val before = publisher.readActive()!!
            // The compiled file is where every patch spelling ends up.
            runner.compiledYaml["extra.yaml"] =
                "translator:\n  user_dict: /data/user/0/app/files/rime/config/edit/leak\n"
            edit("rime_ice.custom.yaml", PATCH)
            val calls = runner.calls

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, refused.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNSAFE_REFERENCE", refused.problem)
            assertEquals(listOf("extra.yaml"), refused.paths)
            assertTrue("it did compile", runner.calls > calls)
            assertEquals("the keyboard keeps its generation", before.generation, publisher.readActive()!!.generation)
            assertEquals("the earlier configuration stays", null, UserConfigFiles.digest(appliedDir))
            assertEquals("extra.yaml", status.read()?.file)
        }

    @Test
    fun aRestoredBackupsScriptsAreLeftOutOfEveryDeploymentUntilConfirmedAgain() =
        runTest {
            importPackage()
            File(editDir, "lua").mkdirs()
            File(editDir, "lua/date.lua").writeText("return 1\n")
            val scripts = UserScripts(storage)
            scripts.accept(scripts.unconfirmed(editDir, null))
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            assertEquals("return 1\n", runner.overlays.last()["lua/date.lua"])

            // A restore brings back `applied/` but not the confirmations.
            storage.scriptsConfirmedFile.delete()
            run(DeployMode.REDEPLOY_ACTIVE)

            assertFalse("the script is left out", runner.overlays.last().containsKey("lua/date.lua"))
            val active = publisher.readActive()!!
            assertFalse(File(storage.generationDir(active.generation), "resources/lua/date.lua").exists())
        }

    @Test
    fun aCopyIdenticalToOneSourcesOwnFileIsStillAScriptForAnother() {
        val overlay = DeployOverlay(storage)

        fun manifest(id: String) =
            app.songyan.ime.storage.SourceManifest(
                sourceId = id,
                kind = app.songyan.ime.storage.SourceKind.SCHEME,
                displayName = id,
                schemes =
                    listOf(
                        app.songyan.ime.storage
                            .SourceScheme("s", "s"),
                    ),
                revision = "1",
            )
        File(storage.sourceDir("scheme-a"), "lua").mkdirs()
        File(storage.sourceDir("scheme-a"), "lua/date.lua").writeText("return 1\n")
        File(storage.sourceDir("scheme-b"), "lua").mkdirs()
        File(storage.sourceDir("scheme-b"), "lua/date.lua").writeText("return 2\n")
        File(appliedDir, "lua").mkdirs()
        File(appliedDir, "lua/date.lua").writeText("return 1\n")

        assertEquals(emptySet<String>(), overlay.excluded(manifest("scheme-a"), appliedDir))
        assertEquals(setOf("lua/date.lua"), overlay.excluded(manifest("scheme-b"), appliedDir))
    }

    @Test
    fun editsWaitForApplyingAndThenReachEveryLaterDeployment() =
        runTest {
            pipeline.run(DeployRequest("prepare", DeployMode.BUILTIN, force = true))
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
    fun aDeletedModelPatchStopsControllingTheModelAfterApplying() =
        runTest {
            val name = "songyan_quanpin.custom.yaml"
            runner.compiledYaml["songyan_quanpin.schema.yaml"] =
                "schema:\n  schema_id: songyan_quanpin\nengine:\n  translators: [script_translator]\n"
            val patch = "patch:\n  grammar/language: \"\"\n"
            edit(name, patch)
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)

            fun grammarAvailability(): Availability {
                val active = publisher.readActive()!!
                val report = File(storage.generationDir(active.generation), "build/" + EngineFeatureReport.FILE_NAME)
                return EngineFeatureReport
                    .decode(report.readText())!!
                    .schemas
                    .getValue("songyan_quanpin")
                    .grammar
            }

            assertEquals(Availability.SCHEME, grammarAvailability())
            File(editDir, name).delete()
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.REDEPLOY_ACTIVE).state)
            assertEquals(patch, runner.overlays.last()[name])
            assertEquals(Availability.SCHEME, grammarAvailability())

            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            assertFalse(runner.overlays.last().containsKey(name))
            assertFalse(File(appliedDir, name).exists())
            assertEquals(Availability.AVAILABLE, grammarAvailability())

            assertEquals(RequestState.SUCCEEDED, run(DeployMode.REDEPLOY_ACTIVE).state)
            assertFalse(runner.overlays.last().containsKey(name))
            assertEquals(Availability.AVAILABLE, grammarAvailability())
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
            assertFalse(File(appliedDir, "default.custom.yaml").exists())
            assertEquals("default.custom.yaml", status.read()!!.file)
        }

    @Test
    fun patchFailuresPreserveTheFilenameAndOriginalError() =
        runTest {
            importPackage()
            val active = publisher.readActive()!!
            runner.compiledYaml["rime_ice.schema.yaml"] =
                File("../core/rime/src/main/assets/rime/builtin/songyan_quanpin.schema.yaml")
                    .readText()
                    .replace("songyan_quanpin", "rime_ice")
            val detail = "patch target files must use LF line endings and end with a newline"
            for (ending in listOf("", "\r\n")) {
                edit("rime_ice.custom.yaml", "patch: {menu/page_size: 7}" + ending)

                val failed = run(DeployMode.APPLY_USER_CONFIG)

                assertEquals(RequestState.FAILED, failed.state)
                assertEquals(DeployPipeline.CONFIG_PREFIX + DeploymentCoordinator.DEPLOY_PROBLEM, failed.problem)
                assertEquals(detail, failed.detail)
                val requests = DeployRequestStore(storage)
                requests.write("format-failure", failed)
                assertEquals(detail, requests.read("format-failure")?.detail)
                assertEquals(listOf("rime_ice.custom.yaml"), failed.paths)
                assertEquals(detail, status.read()?.detail)
                assertEquals("rime_ice.custom.yaml", status.read()?.file)
                assertEquals(active.generation, publisher.readActive()!!.generation)
            }
        }

    @Test
    fun aPatchTheEngineRejectsKeepsThePreviousGenerationAndConfiguration() =
        runTest {
            edit("songyan_quanpin.custom.yaml", PATCH)
            run(DeployMode.APPLY_USER_CONFIG)
            val active = publisher.readActive()!!
            val digest = UserConfigFiles.digest(appliedDir)
            edit("songyan_quanpin.custom.yaml", "patch:\n  engine/processors: []\n")
            runner.failIf = { File(it, "songyan_quanpin.custom.yaml").readText().contains("processors") }

            val failed = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(RequestState.FAILED, failed.state)
            assertEquals(DeployPipeline.CONFIG_PREFIX + DeploymentCoordinator.DEPLOY_PROBLEM, failed.problem)
            assertEquals("the keyboard keeps its generation", active.generation, publisher.readActive()!!.generation)
            assertEquals(
                "the earlier configuration stays applied",
                digest,
                UserConfigFiles.digest(appliedDir),
            )
            assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
        }

    @Test
    fun aConfigurationAppliedForOneSourceNeverReachesAnother() =
        runTest {
            // Both packages ship double_pinyin; each has its own `lua/` folder.
            DeployFixtures.pickSchemePackage(storage, "b1", DeployFixtures.schemePackageFiles("other_ime", "别的拼音"))
            val b = pipeline.run(DeployRequest("b1", DeployMode.IMPORT_SCHEME)).sourceId!!
            val a = importPackage()
            assertNotEquals(a, b)
            assertEquals(a, scope)
            val mine = listOf("default.custom.yaml", "double_pinyin.custom.yaml", "lua/mine.lua")
            edit("default.custom.yaml", "patch:\n  menu/page_size: 5\n")
            edit("double_pinyin.custom.yaml", PATCH)
            File(editDir, "lua").mkdirs()
            File(editDir, "lua/mine.lua").writeText("return 1\n")
            val scripts = UserScripts(storage)
            scripts.accept(scripts.unconfirmed(editDir, storage.sourceDir(a)))
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            val applied = publisher.readActive()!!
            assertEquals(a, applied.sourceId)
            assertTrue(
                runner.overlays
                    .last()
                    .keys
                    .containsAll(mine),
            )

            // Switching to the other package: its generation was built without them and still is current.
            val calls = runner.calls
            pipeline.run(DeployRequest("s1", DeployMode.ACTIVATE_SOURCE, sourceId = b))
            assertEquals(b, publisher.readActive()!!.sourceId)
            assertEquals("its revision did not change", calls, runner.calls)
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.REDEPLOY_ACTIVE).state)
            assertTrue(
                runner.overlays
                    .last()
                    .keys
                    .none { it in mine },
            )
            val bGeneration = storage.generationDir(publisher.readActive()!!.generation)
            assertTrue(mine.none { File(bGeneration, "resources/$it").exists() })

            // What the settings screen edits now is the other package's, and it is empty.
            assertEquals(b, scope)
            assertTrue(UserConfigFiles.files(editDir).isEmpty())
            assertNull(status.read())
            assertNull(UserOverrides(storage, b).overrideFile("double_pinyin.custom.yaml"))
            assertEquals(
                "the editor starts from the template",
                "template",
                UserConfigEditor(storage, b).load("double_pinyin.custom.yaml", storage.sourceDir(b)) { "template" },
            )

            // Editing and applying there leaves the first package's files and generation alone.
            edit("double_pinyin.custom.yaml", "patch:\n  menu/page_size: 3\n")
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            assertEquals("patch:\n  menu/page_size: 3\n", runner.overlays.last()["double_pinyin.custom.yaml"])
            assertFalse(runner.overlays.last().containsKey("default.custom.yaml"))
            assertEquals(PATCH, File(storage.userConfigDir(a), "double_pinyin.custom.yaml").readText())
            assertEquals(PATCH, File(storage.appliedConfigDir(a), "double_pinyin.custom.yaml").readText())
            assertEquals(UserConfigStatus.State.APPLIED, UserConfigStatus.Store(storage, a).read()!!.state)
            val before = runner.calls
            pipeline.run(DeployRequest("s2", DeployMode.ACTIVATE_SOURCE, sourceId = a))
            assertEquals(applied.revision, publisher.readActive()!!.revision)
            assertEquals(applied.configDigest, publisher.readActive()!!.configDigest)
            assertEquals("the first package's generation is reused as it was", before, runner.calls)
        }

    @Test
    fun anInterruptedApplicationIsSettledByThePublicationOfItsOwnSource() =
        runTest {
            val id = importPackage()
            edit("rime_ice.custom.yaml", PATCH)
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            pipeline.run(DeployRequest("s1", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN))
            // The package's copy was swapped but never published: the keyboard is on another source.
            File(storage.appliedConfigDir(id), "rime_ice.custom.yaml").writeText("patch:\n  menu/page_size: 9\n")
            File(storage.configRoot, ".previous-$id-1")
                .also { it.mkdirs() }
                .resolve("rime_ice.custom.yaml")
                .writeText(PATCH)

            assertEquals(RequestState.SUCCEEDED, run(DeployMode.REDEPLOY_ACTIVE).state)

            assertEquals(PATCH, File(storage.appliedConfigDir(id), "rime_ice.custom.yaml").readText())
            assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
        }

    @Test
    fun aConfigurationTheSourceNoLongerDeploysWithIsLeftOutAndNoted() =
        runTest {
            val id = importPackage()
            edit("default.custom.yaml", "patch:\n  menu/page_size: 5\n")
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
            // An app or package update changed what the patch meets.
            runner.failIf = { work -> File(work, "default.custom.yaml").isFile }

            val redeployed = run(DeployMode.REDEPLOY_ACTIVE)

            assertEquals(redeployed.problem, RequestState.SUCCEEDED, redeployed.state)
            assertEquals(id, publisher.readActive()!!.sourceId)
            assertFalse(runner.overlays.last().containsKey("default.custom.yaml"))
            val note = status.read()!!
            assertEquals(UserConfigStatus.State.SKIPPED, note.state)
            assertEquals(id, note.sourceId)
            assertNull(
                "the built-in package's status is its own",
                UserConfigStatus.Store(storage, SourceIds.BUILTIN).read(),
            )

            runner.failIf = null
            assertEquals(RequestState.SUCCEEDED, run(DeployMode.REDEPLOY_ACTIVE).state)
            assertEquals(UserConfigStatus.State.APPLIED, status.read()!!.state)
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
            val previous = File(storage.configRoot, ".previous-$scope-1")
            appliedDir.copyRecursively(previous)
            File(appliedDir, "songyan_quanpin.custom.yaml").writeText("half: done\n")
            File(storage.configRoot, ".staging-$scope-2").mkdirs()

            run(DeployMode.REDEPLOY_ACTIVE)

            assertEquals(PATCH, File(appliedDir, "songyan_quanpin.custom.yaml").readText())
            assertEquals(PATCH, runner.overlays.last()["songyan_quanpin.custom.yaml"])
            assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
        }

    @Test
    fun anApplicationThatDiedAfterActivationKeepsThePublishedConfigurationIncludingAnEmptyOne() =
        runTest {
            importPackage()
            edit("rime_ice.custom.yaml", PATCH)
            run(DeployMode.APPLY_USER_CONFIG)
            for (next in listOf("patch:\n  menu/page_size: 9\n", "")) {
                val old = File(appliedDir, "rime_ice.custom.yaml").readText()
                if (next.isEmpty()) {
                    File(editDir, "rime_ice.custom.yaml").delete()
                } else {
                    edit("rime_ice.custom.yaml", next)
                }
                assertEquals(RequestState.SUCCEEDED, run(DeployMode.APPLY_USER_CONFIG).state)
                val committed = publisher.readActive()!!
                // The process died after activation, before deleting the backup or updating the status.
                File(storage.configRoot, ".previous-$scope-1")
                    .also { it.mkdirs() }
                    .resolve("rime_ice.custom.yaml")
                    .writeText(old)
                status.write(UserConfigStatus.failed(null, DeploymentCoordinator.DEPLOY_PROBLEM))

                val recovered =
                    DeployFixtures
                        .pipeline(
                            storage,
                            runner,
                        ).run(DeployRequest("recover" + System.nanoTime(), DeployMode.BUILTIN))

                assertEquals(RequestState.SUCCEEDED, recovered.state)
                assertEquals(committed, publisher.readActive())
                assertEquals(
                    next.takeIf { it.isNotEmpty() },
                    File(appliedDir, "rime_ice.custom.yaml").takeIf { it.isFile }?.readText(),
                )
                assertEquals(UserConfigStatus.applied(UserConfigFiles.digest(appliedDir)), status.read())
                assertTrue(storage.configRoot.listFiles()!!.none { it.name.startsWith(".") })
            }
        }

    private companion object {
        const val PATCH = "patch:\n  menu/page_size: 7\n"
    }

    @Test
    fun aComponentAssembledFromPiecesFailsTheDeploymentUnlessConfirmedOrShipped() =
        runTest {
            importPackage()
            val before = publisher.readActive()!!
            runner.compiledYaml["extra.yaml"] = "engine:\n  filters:\n    - lua_filter@*assembled\n"
            edit("rime_ice.custom.yaml", PATCH)

            val refused = run(DeployMode.APPLY_USER_CONFIG)

            assertEquals(DeployPipeline.CONFIG_PREFIX + "UNCONFIRMED_SCRIPT", refused.problem)
            assertEquals(listOf("extra.yaml"), refused.paths)
            assertEquals(before.generation, publisher.readActive()!!.generation)
        }

    @Test
    fun anAppliedCopyThatNoLongerPassesItsChecksIsLeftOutAndNamed() =
        runTest {
            importPackage()
            // What a backup restore or an older version left: never run through today's checks.
            File(appliedDir, "x.custom.yaml")
                .also { it.parentFile?.mkdirs() }
                .writeText("translator:\n  user_dict: /abs/leak\n")

            run(DeployMode.REDEPLOY_ACTIVE)

            assertFalse(runner.overlays.last().containsKey("x.custom.yaml"))
            assertEquals("x.custom.yaml", status.read()?.file)
            assertEquals(UserConfigStatus.State.FAILED, status.read()?.state)
        }
}

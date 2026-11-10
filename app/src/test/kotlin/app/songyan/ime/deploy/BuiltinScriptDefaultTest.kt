package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.rime.EngineOptionPreferences
import app.songyan.ime.rime.SchemaOptionCapabilities
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.BuiltinSchemes
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The bundled scheme's four-state script switch on a first installation: the app language decides
 * it once, while an installation that already has data keeps what it had — the old silent default
 * included. Drives the production deploy path and reads the same preference files the engine
 * applies when the keyboard opens.
 */
class BuiltinScriptDefaultTest {
    @Test
    fun aFirstDeploymentWithATaiwanLocaleStartsTaiwanese() =
        runTest {
            for (tag in listOf("zh-TW", "zh-Hant-TW")) {
                val storage = DeployFixtures.storage()
                coordinator(storage, listOf(tag)).activateSource(SourceIds.BUILTIN).getOrThrow()
                assertScriptState(storage, BuiltinScriptState.TAIWAN)
            }
        }

    @Test
    fun aFirstDeploymentWithAHongKongOrMacaoLocaleStartsHongKong() =
        runTest {
            for (tag in listOf("zh-HK", "zh-MO", "zh-Hant-HK", "zh-Hant-MO")) {
                val storage = DeployFixtures.storage()
                coordinator(storage, listOf(tag)).deployBuiltin().getOrThrow()
                assertScriptState(storage, BuiltinScriptState.HONG_KONG)
            }
        }

    @Test
    fun aFirstDeploymentWithAnotherTraditionalLocaleStartsTraditional() =
        runTest {
            for (tag in listOf("zh-Hant", "zh-Hant-US")) {
                val storage = DeployFixtures.storage()
                coordinator(storage, listOf(tag)).deployBuiltin().getOrThrow()
                assertScriptState(storage, BuiltinScriptState.TRADITIONAL)
            }
        }

    @Test
    fun aFirstDeploymentWithASimplifiedOrOtherLocaleStartsSimplified() =
        runTest {
            for (tags in listOf(emptyList<String>(), listOf("zh"), listOf("zh-CN"), listOf("ja-JP"), listOf("en-US"))) {
                val storage = DeployFixtures.storage()
                coordinator(storage, tags).deployBuiltin().getOrThrow()
                assertScriptState(storage, BuiltinScriptState.SIMPLIFIED)
            }
        }

    @Test
    fun anInstallationThatAlreadyHasDataKeepsItsOldDefault() =
        runTest {
            val storage = DeployFixtures.storage()
            coordinator(storage, listOf("zh-TW")).deployBuiltin().getOrThrow()
            // An installation from before the switch existed: a source and a generation, no choice.
            storage.optionsDir.deleteRecursively()
            coordinator(storage, listOf("zh-TW"), updatedStamp()).ensureDeployed().getOrThrow()
            for (schemaID in BuiltinSchemes.IDS) {
                for (state in BuiltinScriptState.values()) assertNull(read(storage, schemaID, state))
            }
        }

    @Test
    fun aChoiceTheUserSavedSurvivesLaterDeploymentsAndLanguageChanges() =
        runTest {
            val storage = DeployFixtures.storage()
            coordinator(storage, listOf("zh-TW")).deployBuiltin().getOrThrow()
            for (schemaID in BuiltinSchemes.IDS) {
                preferences(storage).write(member(schemaID, BuiltinScriptState.TRADITIONAL), true)
            }
            coordinator(storage, listOf("zh-HK"), updatedStamp()).ensureDeployed().getOrThrow()
            assertScriptState(storage, BuiltinScriptState.TRADITIONAL)
        }

    @Test
    fun theFirstDeploymentsChoiceSurvivesAReopenAndALanguageChange() =
        runTest {
            val storage = DeployFixtures.storage()
            coordinator(storage, listOf("zh-TW")).deployBuiltin().getOrThrow()
            coordinator(storage, listOf("zh-CN")).deployBuiltin().getOrThrow()
            assertScriptState(storage, BuiltinScriptState.TAIWAN)
        }

    @Test
    fun aFirstImportedDictionaryStillLetsTheBuiltInSchemesStartTaiwanese() =
        runTest {
            val storage = DeployFixtures.storage()
            val deploy = pipeline(storage, listOf("zh-TW"))
            assertEquals(RequestState.SUCCEEDED, importDictionary(storage, deploy, "first").state)
            assertScriptState(storage, BuiltinScriptState.TAIWAN)
            coordinator(storage, listOf("zh-CN")).activateSource(SourceIds.BUILTIN).getOrThrow()
            assertScriptState(storage, BuiltinScriptState.TAIWAN)
        }

    @Test
    fun aDefaultThatCannotBeSavedFailsTheDeploymentSoItCanBeRetried() =
        runTest {
            val storage = DeployFixtures.storage()
            // A file where the preference directory belongs makes every save fail.
            storage.optionsDir.writeText("blocked")
            val deploy = pipeline(storage, listOf("zh-TW"))
            assertEquals(RequestState.FAILED, importDictionary(storage, deploy, "blocked").state)
            assertNull(GenerationPublisher(storage).readActive())
            // Retried once the obstacle is gone, the installation takes the default with it.
            storage.optionsDir.delete()
            assertEquals(RequestState.SUCCEEDED, importDictionary(storage, deploy, "retry").state)
            assertScriptState(storage, BuiltinScriptState.TAIWAN)
        }

    @Test
    fun aSavedChoiceTheModelDoesNotKnowIsNeverReplacedByTheDefault() =
        runTest {
            for (stored in listOf("zh_hant_custom", "false", "true")) {
                val storage = DeployFixtures.storage()
                // A choice this build's four-state group cannot read back: an older single
                // switch's true/false, or a state a future version added. The file is the choice.
                preferences(storage).write(member(BuiltinSchemes.QUANPIN, BuiltinScriptState.TRADITIONAL), true)
                val savedFile = optionFiles(storage).single()
                savedFile.writeText(stored)

                coordinator(storage, listOf("zh-TW")).deployBuiltin().getOrThrow()

                assertEquals(
                    "an unreadable saved choice keeps its stored value",
                    stored,
                    savedFile.readText().trim(),
                )
                for (state in BuiltinScriptState.values()) {
                    assertEquals(
                        "the scheme without a saved choice still takes the language default",
                        state == BuiltinScriptState.TAIWAN,
                        read(storage, BuiltinSchemes.FLYPY, state),
                    )
                }
            }
        }

    @Test
    fun theBundledSchemasDeclareTheSwitchTheSavedChoiceNames() {
        for (schemaID in BuiltinSchemes.IDS) {
            assertEquals(
                BuiltinScriptState.values().map { it.optionName },
                scriptSwitch(schemaID).first().choiceGroup,
            )
        }
    }

    private fun coordinator(
        storage: AppStorage,
        localeTags: List<String>,
        stamp: BuildStamp = DeployFixtures.STAMP,
    ) = DeploymentCoordinator(
        storage = storage,
        assets = DeployFixtures.assets(),
        runner = DeployFixtures.FakeRunner(),
        appName = "rime.songyan",
        stamp = stamp,
        localeTags = { localeTags },
    )

    private fun pipeline(
        storage: AppStorage,
        localeTags: List<String>,
    ) = DeployPipeline(storage, coordinator(storage, localeTags), SourceBuilder(DeployFixtures.assets()))

    private suspend fun importDictionary(
        storage: AppStorage,
        deploy: DeployPipeline,
        requestId: String,
    ): RequestStatus {
        DeployFixtures.pickDictionaries(
            storage,
            requestId,
            mapOf("main.dict.yaml" to DeployFixtures.dictionary("main", "你好\tni hao\t1\n")),
        )
        return deploy.run(DeployRequest(requestId, DeployMode.IMPORT_DICTIONARY))
    }

    /** The files the engine reads back at a start, through the preference store the deploy path wrote. */
    private fun preferences(storage: AppStorage) = EngineOptionPreferences(storage.optionsDir)

    private fun read(
        storage: AppStorage,
        schemaID: String,
        state: BuiltinScriptState,
    ): Boolean? = preferences(storage).read(member(schemaID, state))

    private fun member(
        schemaID: String,
        state: BuiltinScriptState,
    ): EngineOptionDescriptor = scriptSwitch(schemaID).first { it.name == state.optionName }

    private fun assertScriptState(
        storage: AppStorage,
        state: BuiltinScriptState,
    ) {
        for (schemaID in BuiltinSchemes.IDS) {
            for (candidate in BuiltinScriptState.values()) {
                assertEquals(
                    schemaID + " should have " + candidate.optionName + " selected: " + (candidate == state),
                    candidate == state,
                    read(storage, schemaID, candidate),
                )
            }
        }
    }

    /** The shipped schema's script switch as the bundling parser reads it, not a copy of the data. */
    private fun scriptSwitch(schemaID: String): List<EngineOptionDescriptor> =
        SchemaOptionCapabilities
            .parse(
                resourceID = LearningData.namespace(SourceIds.BUILTIN),
                schemaID = schemaID,
                yaml = shippedSchema(schemaID),
                openccInstalled = { true },
                openccKind = { EngineOptionKind.TRADITIONAL },
            ).filter { it.choiceGroup.isNotEmpty() }

    private fun shippedSchema(schemaID: String): String =
        File("../core/rime/src/main/assets/rime/builtin/$schemaID.schema.yaml").readText()

    private fun updatedStamp(): BuildStamp = DeployFixtures.STAMP.copy(app = "1.1/2/200")

    /** The preference files the store wrote, found by walking its directory instead of a path copy. */
    private fun optionFiles(storage: AppStorage): List<File> =
        storage.optionsDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".txt") }
            .toList()
}

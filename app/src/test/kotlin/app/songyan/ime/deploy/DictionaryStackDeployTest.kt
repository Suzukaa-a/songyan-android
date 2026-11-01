package app.songyan.ime.deploy

import app.songyan.ime.deploy.DeployFixtures.dictionary
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A dictionary imported for a scheme package is stacked onto that package's own dictionary
 * through `import_tables`, deployed from the work directory without editing the package, applied
 * again when the package is re-imported, and taken away again when the user removes it.
 */
class DictionaryStackDeployTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)

    private val packageDictionary =
        "---\nname: rime_ice\nversion: \"1\"\nsort: by_weight\nimport_tables:\n  - cn_dicts/base\n...\n你好\tni hao\t1\n"

    /** rime-ice's shape: a script translator over `rime_ice`, which imports more tables. */
    private fun packageFiles(dictionaryText: String = packageDictionary): Map<String, String> =
        linkedMapOf(
            "rime_ice-main/default.yaml" to "schema_list:\n  - schema: rime_ice\n  - schema: double_pinyin\n",
            "rime_ice-main/rime_ice.schema.yaml" to
                "schema:\n  schema_id: rime_ice\n  name: 雾凇拼音\nengine:\n  translators:\n" +
                "    - punct_translator\n    - script_translator\ntranslator:\n  dictionary: rime_ice\n",
            "rime_ice-main/double_pinyin.schema.yaml" to
                "schema:\n  schema_id: double_pinyin\n  name: 自然码双拼\nengine:\n  translators:\n" +
                "    - script_translator\ntranslator:\n  dictionary: rime_ice\n",
            "rime_ice-main/rime_ice.dict.yaml" to dictionaryText,
            "rime_ice-main/cn_dicts/base.dict.yaml" to "---\nname: cn_dicts/base\n...\n世界\tshi jie\t1\n",
        )

    private suspend fun importPackage(
        id: String,
        files: Map<String, String> = packageFiles(),
    ): String {
        DeployFixtures.pickSchemePackage(storage, id, files)
        val status = pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME))
        assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
        return publisher.readActive()!!.sourceId
    }

    private suspend fun stack(
        id: String,
        target: String,
    ): RequestStatus {
        DeployFixtures.pickDictionaries(storage, id, mapOf("mine.dict.yaml" to dictionary("mine", "松烟\tsong yan\t9\n")))
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_DICTIONARY, sourceId = target))
    }

    @Test
    fun theDictionaryIsAddedToThePackagesOwnImportTablesAndThePackageStaysUntouched() =
        runTest {
            val scheme = importPackage("r1")
            val pristine = File(storage.sourceDir(scheme), "rime_ice.dict.yaml").readText()
            val withoutStack = publisher.readActive()!!.revision

            val status = stack("r2", scheme)

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val record = publisher.readActive()!!
            assertEquals(scheme, record.sourceId)
            assertNotEquals("the stack is part of the published revision", withoutStack, record.revision)
            assertEquals(withoutStack.substringBefore('+'), record.revision.substringBefore('+'))
            val overlay = runner.overlays.last()
            assertEquals(
                "---\nname: rime_ice\nversion: \"1\"\nsort: by_weight\nimport_tables:\n  - cn_dicts/base\n" +
                    "  - songyan_imported\n...\n你好\tni hao\t1\n",
                overlay["rime_ice.dict.yaml"],
            )
            assertTrue(overlay.getValue("songyan_imported.dict.yaml").contains("松烟\tsong yan\t9"))
            assertFalse(
                "no songyan_basic in a package",
                overlay.getValue("songyan_imported.dict.yaml").contains("songyan_basic"),
            )
            assertEquals(pristine, File(storage.sourceDir(scheme), "rime_ice.dict.yaml").readText())
            assertFalse(File(storage.sourceDir(scheme), "songyan_imported.dict.yaml").exists())
        }

    @Test
    fun reimportingThePackageAppliesTheStackAgainAndRemovingItRedeploysWithout() =
        runTest {
            val scheme = importPackage("r1")
            val withoutStack = publisher.readActive()!!.revision
            stack("r2", scheme)

            assertEquals(scheme, importPackage("r3"))
            assertTrue(
                runner.overlays
                    .last()
                    .getValue("rime_ice.dict.yaml")
                    .contains("  - songyan_imported\n"),
            )
            assertNotEquals(withoutStack, publisher.readActive()!!.revision)

            val removed = pipeline.run(DeployRequest("r4", DeployMode.REMOVE_DICTIONARY, sourceId = scheme))

            assertEquals(removed.problem, RequestState.SUCCEEDED, removed.state)
            assertTrue("the package deploys without any overlay", runner.overlays.last().isEmpty())
            assertEquals(withoutStack, publisher.readActive()!!.revision)
            assertFalse(storage.stackedDictionaryDir(scheme).exists())
        }

    @Test
    fun aPackageWhoseDictionaryCannotTakeTheStackRefusesItAndKeepsTheKeyboardAsItWas() =
        runTest {
            val scheme =
                importPackage(
                    "r1",
                    packageFiles("---\nname: rime_ice\ncolumns:\n  - text\n  - weight\n...\n你好\t1\n"),
                )
            val before = publisher.readActive()!!

            val status = stack("r2", scheme)

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("TARGET_UNSUPPORTED", status.problem)
            assertEquals(before.generation, publisher.readActive()!!.generation)
            assertFalse(storage.stackedDictionaryDir(scheme).exists())
        }

    @Test
    fun aStackOperationKilledMidWayIsUndoneAtTheNextRequest() =
        runTest {
            val scheme = importPackage("r1")
            stack("r2", scheme)
            val stackDir = storage.stackedDictionaryDir(scheme)
            val kept = File(stackDir, DeployOverlay.STACKED_FILE).readText()
            // A second stack was moving in when the process died: the old one is still set aside.
            val aside = File(storage.stackedDictionariesDir, ".previous-$scheme-1")
            stackDir.copyRecursively(aside)
            File(stackDir, DeployOverlay.STACKED_FILE).writeText("half written")

            pipeline.run(DeployRequest("r3", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN))

            assertEquals(kept, File(stackDir, DeployOverlay.STACKED_FILE).readText())
            assertFalse(aside.exists())
        }

    @Test
    fun deletingThePackageDeletesItsStackedDictionary() =
        runTest {
            val scheme = importPackage("r1")
            stack("r2", scheme)

            pipeline.run(DeployRequest("r3", DeployMode.DELETE_SOURCE, sourceId = scheme))

            assertFalse(storage.stackedDictionaryDir(scheme).exists())
            assertEquals(SourceIds.BUILTIN, publisher.readActive()!!.sourceId)
        }

    @Test
    fun theRequestCarriesItsTargetAcrossProcesses() {
        val extras =
            mapOf(
                DeployContract.EXTRA_SOURCE_ID to "scheme-0123",
                DeployContract.EXTRA_URL to "https://x/a.dict.yaml",
            )
        val file = DeployRequest.from("r1", "import-dictionary", false) { extras[it] }
        val url = DeployRequest.from("r2", "import-dictionary-url", false) { extras[it] }
        val remove = DeployRequest.from("r3", "remove-dictionary", false) { extras[it] }
        assertEquals("scheme-0123", file?.sourceId)
        assertEquals("scheme-0123", url?.sourceId)
        assertEquals(DeployMode.REMOVE_DICTIONARY, remove?.mode)
        assertEquals(null, DeployRequest.from("r4", "import-dictionary", false) { null }?.sourceId)
    }
}

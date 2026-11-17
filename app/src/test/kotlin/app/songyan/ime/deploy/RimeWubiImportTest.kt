package app.songyan.ime.deploy

import app.songyan.ime.packages.MissingFeature
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * rime/rime-wubi, imported the way a desktop Rime user would: on its own, after the package that
 * provides its reverse lookup, and as one merged folder without a default.yaml.
 */
class RimeWubiImportTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val catalog = SourceCatalog(storage)

    private suspend fun importFile(
        files: Map<String, String>,
        name: String? = null,
    ): RequestStatus {
        val id = "f" + System.nanoTime()
        DeployFixtures.pickSchemePackage(storage, id, files)
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME, packageName = name))
    }

    @Test
    fun wubiInstallsWithoutItsLookupAndBorrowsItFromAnInstalledPackage() =
        runTest {
            val alone = importFile(prefixed("rime-wubi-master", WUBI))
            assertEquals(RequestState.SUCCEEDED, alone.state)
            val manifest = catalog.read(alone.sourceId!!)!!
            assertEquals("rime-wubi", manifest.displayName)
            assertEquals(listOf("wubi86"), manifest.schemaIds)
            assertEquals("the package's main input method is the one in use", "wubi86", activeSchema())
            assertEquals(
                listOf(MissingFeature(MissingFeature.Kind.REVERSE_LOOKUP, listOf("pinyin_simp"))),
                alone.missingFeatures,
            )

            assertEquals(RequestState.SUCCEEDED, importFile(prefixed("rime-pinyin-simp-master", PINYIN_SIMP)).state)
            val again = importFile(prefixed("rime-wubi-master", WUBI))
            assertEquals(RequestState.SUCCEEDED, again.state)
            val source = storage.sourceDir(again.sourceId!!)
            assertTrue(File(source, "pinyin_simp.dict.yaml").isFile)
            assertTrue(File(source, "pinyin_simp.schema.yaml").isFile)
            assertEquals(listOf("wubi86"), catalog.read(again.sourceId!!)!!.schemaIds)
        }

    @Test
    fun aMergedFolderOffersWubiAndIsNamedAfterTheFile() =
        runTest {
            val status = importFile(WUBI + PINYIN_SIMP, name = "我的五笔.zip")
            assertEquals(RequestState.SUCCEEDED, status.state)
            val manifest = catalog.read(status.sourceId!!)!!
            assertEquals("我的五笔", manifest.displayName)
            assertEquals(listOf("wubi86"), manifest.schemaIds)
            assertEquals("wubi86", activeSchema())
        }

    private fun activeSchema(): String? = GenerationPublisher(storage).readActive()?.schemaId

    private fun prefixed(
        wrapper: String,
        files: Map<String, String>,
    ) = files.mapKeys { "$wrapper/${it.key}" }

    private companion object {
        val WUBI =
            mapOf(
                "wubi86.schema.yaml" to
                    "schema:\n  schema_id: wubi86\n  name: 五笔86\n  dependencies:\n    - pinyin_simp\n" +
                    "engine:\n  translators:\n    - reverse_lookup_translator\n    - table_translator\n" +
                    "translator:\n  dictionary: wubi86\nreverse_lookup:\n  dictionary: pinyin_simp\n",
                "wubi86.dict.yaml" to "---\nname: wubi86\nversion: '1'\n...\n我\ttrnt\n",
            )
        val PINYIN_SIMP =
            mapOf(
                "pinyin_simp.schema.yaml" to
                    "schema:\n  schema_id: pinyin_simp\n  name: 袖珍简化字拼音\n" +
                    "engine:\n  translators:\n    - script_translator\n" +
                    "translator:\n  dictionary: pinyin_simp\n",
                "pinyin_simp.dict.yaml" to "---\nname: pinyin_simp\nversion: '1'\n...\n我\two\n",
            )
    }
}

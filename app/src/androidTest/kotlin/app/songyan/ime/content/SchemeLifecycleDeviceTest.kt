package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.deploy.DeployState
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.deploy.DeploymentStarter
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.SettingsAppStateHolder
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * P4-1 on a device: a stored scheme package's status, undoing its deletion and switching to a
 * newer stored version, against the real :deploy process and librime.
 */
@RunWith(AndroidJUnit4::class)
class SchemeLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)
    private val settings = SettingsInForeground()
    private val sources get() = StoredSourcesHolder.current

    @Before
    fun setUp() = settings.open()

    @After
    fun tearDown() {
        runBlocking {
            for (id in catalog.list().map { it.sourceId }.filter { SourceIds.isScheme(it) } + catalog.damaged()) {
                runCatching { sources.delete(id) }
            }
            runCatching { sources.use(SourceIds.BUILTIN) }
        }
        storage.trashDir.deleteRecursively()
        settings.close()
    }

    private fun importPackage(files: Map<String, String> = packageFiles()): String =
        runBlocking {
            val result = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage(files)))
            assertTrue("scheme import failed: $result", result is SchemeImportResult.Success)
            requireNotNull(publisher.readActive()).sourceId
        }

    /** The settings state source re-reads asynchronously; wait for the row of [id] to satisfy [accept]. */
    private fun awaitRow(
        id: String,
        accept: (StoredSourceInfo?) -> Boolean,
    ): StoredSourceInfo? {
        val state = SettingsAppStateHolder.current
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            state.refresh()
            val row =
                state.state.value.sources
                    .firstOrNull { it.id == id }
            if (accept(row)) return row
            Thread.sleep(100)
        }
        throw AssertionError("row of $id never satisfied the check: " + state.state.value.sources)
    }

    @Test
    fun aDeletedPackageComesBackWithItsLearnedWordsAndCanBeUsedAgain() =
        runBlocking<Unit> {
            val id = importPackage()
            val learned =
                File(LearningData.directory(storage, id), "demo_scheme.userdb/CURRENT").also {
                    it.parentFile!!.mkdirs()
                    it.writeText("learned")
                }

            assertEquals(StoredSourceResult.Success, sources.delete(id))
            assertNull(catalog.read(id))
            assertEquals(SourceIds.BUILTIN, publisher.readActive()?.sourceId)
            assertTrue(storage.trashedSourceDir(id).isDirectory)

            assertEquals(StoredSourceResult.Success, sources.restore(id))
            assertNotNull(catalog.read(id))
            assertEquals("restoring does not switch the keyboard", SourceIds.BUILTIN, publisher.readActive()?.sourceId)
            assertTrue("the words it learned never moved", learned.isFile)

            assertEquals(StoredSourceResult.Success, sources.use(id))
            val active = requireNotNull(publisher.readActive())
            assertEquals(id, active.sourceId)
            assertTrue(publisher.isComplete(active))
            assertEquals(StoredSourceStatus.IN_USE, awaitRow(id) { it?.status == StoredSourceStatus.IN_USE }?.status)
        }

    @Test
    fun anImportNamesTheGrammarModelsThePackageNeedsButDoesNotShip() =
        runBlocking<Unit> {
            val named =
                packageFiles() + ("demo-main/demo_scheme.custom.yaml" to "patch:\n  grammar/language: demo-model\n")
            val missing = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage(named)))
            assertEquals(
                listOf("demo-model.gram"),
                (missing as SchemeImportResult.Success).info?.requiredModels,
            )

            val shipped =
                SchemeImportHolder.current.importFromUri(
                    Uri.fromFile(
                        schemePackage(
                            named + ("demo-main/demo-model.gram" to "model"),
                        ),
                    ),
                )
            assertEquals(emptyList<String>(), (shipped as SchemeImportResult.Success).info?.requiredModels)
        }

    @Test
    fun aPackageThatLostItsFilesIsDamagedAndIsRemovedForGood() =
        runBlocking<Unit> {
            val id = importPackage()
            assertEquals(StoredSourceResult.Success, sources.use(SourceIds.BUILTIN))
            File(storage.sourceDir(id), "demo_scheme.schema.yaml").delete()

            val row = awaitRow(id) { it?.status == StoredSourceStatus.DAMAGED }
            assertFalse(row!!.usable)

            assertEquals(StoredSourceResult.Success, sources.delete(id))
            assertFalse(storage.sourceDir(id).exists())
            assertFalse("a damaged package is not kept for undo", storage.trashedSourceDir(id).exists())
            awaitRow(id) { it == null }
        }

    @Test
    fun filesNewerThanTheRunningGenerationOfferTheNewVersionUntilItIsUsed() =
        runBlocking<Unit> {
            val id = importPackage()
            assertEquals(StoredSourceStatus.IN_USE, awaitRow(id) { it?.status == StoredSourceStatus.IN_USE }?.status)
            val running = requireNotNull(publisher.readActive())
            // A newer version of the stored files (an update installed while another one ran).
            val stored = requireNotNull(catalog.read(id))
            SourceCatalog.write(storage.sourceDir(id), stored.copy(revision = "newer-" + stored.revision.takeLast(8)))

            awaitRow(id) { it?.status == StoredSourceStatus.UPDATE_AVAILABLE }

            assertEquals(StoredSourceResult.Success, sources.use(id))
            val after = requireNotNull(publisher.readActive())
            assertEquals(id, after.sourceId)
            assertTrue(
                "the new version was deployed, not the old generation reused",
                after.generation != running.generation,
            )
            assertTrue(after.revision.startsWith("newer-"))
            awaitRow(id) { it?.status == StoredSourceStatus.IN_USE }
        }

    @Test
    fun aMenuRequestForAStoredPackageReusesItsKeptGenerationWithoutARebuild() =
        runBlocking<Unit> {
            val id = importPackage()
            val first = requireNotNull(publisher.readActive())
            assertEquals(StoredSourceResult.Success, sources.use(SourceIds.BUILTIN))
            assertEquals(SourceIds.BUILTIN, publisher.readActive()?.sourceId)
            assertTrue(
                "the package's generation is kept while another one is active",
                storage.generationDir(first.generation).isDirectory,
            )

            // The request the keyboard menu sends from the input method process.
            assertTrue(DeploymentStarter.requestActivate(context, id))
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (publisher.readActive()?.sourceId != id && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }

            val after = requireNotNull(publisher.readActive())
            assertEquals(id, after.sourceId)
            assertEquals("the kept generation was switched to, not compiled again", first.generation, after.generation)
            // The deploy process may still be finishing up; the next test must not start under it.
            val status = DeployStatusStore(storage)
            while (status.read() == DeployState.PREPARING && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
        }

    @Test
    fun aPackageImportedFromAFileHasNoLinkToUpdateFrom() =
        runBlocking<Unit> {
            val id = importPackage()

            val row = awaitRow(id) { it != null }

            assertFalse(row!!.updatable)
            assertNull(catalog.read(id)!!.origin)
        }

    @Test
    fun aNewerFileOfTheSamePackageUpdatesItAndKeepsItsLearnedWordsAndGrammarModel() =
        runBlocking<Unit> {
            val id = importPackage(packageFiles() + ("demo-main/demo-model.gram" to "grammar model"))
            val learned =
                File(LearningData.directory(storage, id), "demo_scheme.userdb/CURRENT").also {
                    it.parentFile!!.mkdirs()
                    it.writeText("learned")
                }
            val before = requireNotNull(catalog.read(id)).revision

            val result =
                SchemeImportHolder.current.importFromUri(
                    Uri.fromFile(schemePackage(packageFiles(extraRows = "松烟\tsong yan\t1\n"))),
                )

            assertTrue("update failed: $result", result is SchemeImportResult.Success)
            assertTrue("reported as an update", (result as SchemeImportResult.Success).updated)
            val active = requireNotNull(publisher.readActive())
            assertEquals("the new version keeps the package id", id, active.sourceId)
            assertTrue(publisher.isComplete(active))
            assertEquals(listOf(id), catalog.list().map { it.sourceId }.filter { SourceIds.isScheme(it) })
            assertTrue(before != requireNotNull(catalog.read(id)).revision)
            assertTrue(File(storage.sourceDir(id), "demo_scheme.dict.yaml").readText().contains("松烟"))
            assertEquals("grammar model", File(storage.sourceDir(id), "demo-model.gram").readText())
            assertTrue("the learned words stay", learned.isFile)
            assertEquals(StoredSourceStatus.IN_USE, awaitRow(id) { it?.status == StoredSourceStatus.IN_USE }?.status)
        }

    /**
     * The flow the report came from, with the real rime-ice: imported from a ZIP with a grammar
     * model added to its folder, then a newer ZIP of it. Needs the package, which is not bundled:
     * `-e schemePackage <rime-ice ZIP the app can read>`.
     */
    @Test
    fun rimeIceUpdatedFromANewerFileKeepsItsLearnedWordsAndGrammarModel() =
        runBlocking<Unit> {
            val path = InstrumentationRegistry.getArguments().getString("schemePackage")
            assumeTrue("no schemePackage argument", path != null)
            val original = File(requireNotNull(path))
            val withModel = File(context.cacheDir, "rime-ice-with-model.zip")
            copyZip(original, withModel, mapOf("rime-ice-main/wanxiang-lts-zh-hans.gram" to "grammar model"))
            val id = importPackage(withModel)
            val learned =
                File(LearningData.directory(storage, id), "rime_ice.userdb/CURRENT").also {
                    it.parentFile!!.mkdirs()
                    it.writeText("learned")
                }
            val newer = File(context.cacheDir, "rime-ice-newer.zip")
            copyZip(original, newer, mapOf("rime-ice-main/custom_phrase.txt" to "松烟\tsy\t1\n"))

            val result = SchemeImportHolder.current.importFromUri(Uri.fromFile(newer))

            assertTrue("update failed: $result", result is SchemeImportResult.Success && result.updated)
            val active = requireNotNull(publisher.readActive())
            assertEquals(id, active.sourceId)
            assertTrue(publisher.isComplete(active))
            assertEquals(listOf(id), catalog.list().map { it.sourceId }.filter { SourceIds.isScheme(it) })
            assertTrue(File(storage.sourceDir(id), "wanxiang-lts-zh-hans.gram").isFile)
            assertTrue(File(storage.generationDir(active.generation), "resources/wanxiang-lts-zh-hans.gram").isFile)
            assertTrue(learned.isFile)
            withModel.delete()
            newer.delete()
        }

    /** [source] with [replaced] entries written over (or added to) it. */
    private fun copyZip(
        source: File,
        target: File,
        replaced: Map<String, String>,
    ) {
        ZipFile(source).use { zip ->
            ZipOutputStream(FileOutputStream(target)).use { out ->
                for (entry in zip.entries()) {
                    if (entry.name in replaced) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                for ((name, text) in replaced) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
            }
        }
    }

    private fun importPackage(archive: File): String =
        runBlocking {
            val result = SchemeImportHolder.current.importFromUri(Uri.fromFile(archive))
            assertTrue("scheme import failed: $result", result is SchemeImportResult.Success)
            requireNotNull(publisher.readActive()).sourceId
        }

    private fun packageFiles(extraRows: String = ""): Map<String, String> =
        linkedMapOf(
            "demo-main/default.yaml" to "schema_list:\n  - schema: demo_scheme\n",
            "demo-main/demo_scheme.schema.yaml" to
                "schema:\n  schema_id: demo_scheme\n  name: 演示方案\n" +
                "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: demo_scheme\n",
            "demo-main/demo_scheme.dict.yaml" to
                "---\nname: demo_scheme\nversion: \"1\"\nsort: by_weight\n...\n演示\tyan shi\t1\n" + extraRows,
        )

    private fun schemePackage(files: Map<String, String>): File {
        val target = File(context.cacheDir, "lifecycle-scheme.zip")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }
}

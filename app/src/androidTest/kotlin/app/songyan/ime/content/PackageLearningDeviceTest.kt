package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.learning.LearningTransferException
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.PackageLearningResult
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A scheme package that ships learned words (`*.userdb.txt`): the import installs it either way,
 * and the words reach the typing only when the user agrees. Real :deploy process, real engine and
 * the real learning merge, over the app's own storage.
 */
@RunWith(AndroidJUnit4::class)
class PackageLearningDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)
    private val settings = SettingsInForeground()
    private val gateway get() = SchemeImportHolder.current
    private val sources get() = StoredSourcesHolder.current
    private val imported = ArrayList<String>()

    @Before
    fun setUp() = settings.open()

    @After
    fun tearDown() {
        runBlocking {
            for (id in imported) runCatching { sources.delete(id) }
            runCatching { sources.use(SourceIds.BUILTIN) }
        }
        for (id in imported) {
            LearningData.directory(storage, id).deleteRecursively()
            storage.trashedSourceDir(id).deleteRecursively()
        }
        storage.trashDir.deleteRecursively()
        File(context.cacheDir, ARCHIVE).delete()
        settings.close()
    }

    private fun snapshot(
        word: String,
        count: Int = 100,
    ) = "# Rime user dictionary\n#@/db_name\tdemo_scheme\n#@/db_type\tuserdb\n#@/rime_version\t1.9.0\n" +
        "#@/user_id\tother-device\n#@/tick\t500\nyan shi \t$word\tc=$count d=$count t=500\n"

    private fun files(
        nonce: String,
        snapshot: String? = snapshot("艳狮"),
    ): Map<String, String> =
        linkedMapOf(
            "demo-main/default.yaml" to "schema_list:\n  - schema: demo_scheme\n",
            "demo-main/demo_scheme.schema.yaml" to
                "schema:\n  schema_id: demo_scheme\n  name: 演示方案\n" +
                "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: demo_scheme\n",
            "demo-main/demo_scheme.dict.yaml" to
                "---\nname: demo_scheme\nversion: \"1\"\nsort: by_weight\n...\n演示\tyan shi\t1\n",
            "demo-main/README.md" to nonce,
        ) + (snapshot?.let { mapOf("demo-main/demo_scheme.userdb.txt" to it) } ?: emptyMap())

    private suspend fun import(files: Map<String, String>): SchemeImportResult.Success {
        val archive = File(context.cacheDir, ARCHIVE)
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        val result = gateway.importFromUri(Uri.fromFile(archive))
        assertTrue("scheme import failed: $result", result is SchemeImportResult.Success)
        val success = result as SchemeImportResult.Success
        success.info?.sourceId?.let { if (it !in imported) imported += it }
        return success
    }

    private suspend fun firstCandidate(code: String): String? {
        val active = requireNotNull(publisher.readActive())
        val generation = storage.generationDir(active.generation)
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = LearningData.directory(storage, active.sourceId),
                appName = RimeBindings.APP_NAME,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        return try {
            engine.selectSchema("demo_scheme")
            for (char in code) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            engine
                .readSnapshot()
                .candidates
                .firstOrNull()
                ?.text
        } finally {
            engine.close()
        }
    }

    private val learnedRows: String
        get() =
            runBlocking {
                try {
                    (context.applicationContext as SongyanApplication)
                        .learningBackupService
                        .backup()
                        .dictionaries
                        .joinToString("\n") { String(it.data, Charsets.UTF_8) }
                        .lines()
                        .filterNot { it.startsWith("#") }
                        .sorted()
                        .joinToString("\n")
                } catch (_: LearningTransferException) {
                    // Nothing learned yet: an empty set to compare with.
                    ""
                }
            }

    @Test
    fun acceptingMergesThePackagesLearnedWordsAndTheyRankFirst() =
        runBlocking {
            val result = import(files(UUID.randomUUID().toString()))
            val info = requireNotNull(result.info)
            assertEquals("one dictionary has words waiting", 1, info.learningDictionaries)
            assertTrue(info.inUse)
            assertEquals("the words wait for the answer", "演示", firstCandidate("yanshi"))

            val merged = gateway.mergePackageLearning(requireNotNull(info.sourceId))

            assertTrue(merged.toString(), merged is PackageLearningResult.Merged)
            assertEquals(LearningFilesText.merged(context, 1, 1), (merged as PackageLearningResult.Merged).message)
            assertEquals("艳狮", firstCandidate("yanshi"))
            assertTrue(storage.packageLearningDir(info.sourceId!!).list().isNullOrEmpty())
        }

    @Test
    fun aPackageNotInUseIsUsedFirstAndThenMerged() =
        runBlocking {
            val info = requireNotNull(import(files(UUID.randomUUID().toString())).info)
            assertEquals(
                StoredSourcesHolder.current.use(SourceIds.BUILTIN),
                app.songyan.ime.settings.state.StoredSourceResult.Success,
            )
            assertEquals(SourceIds.BUILTIN, publisher.readActive()?.sourceId)

            val merged = gateway.mergePackageLearning(requireNotNull(info.sourceId))

            assertTrue(merged.toString(), merged is PackageLearningResult.Merged)
            assertEquals(info.sourceId, publisher.readActive()?.sourceId)
            assertEquals("艳狮", firstCandidate("yanshi"))
        }

    @Test
    fun decliningChangesNothingAndThePackageStaysInstalled() =
        runBlocking {
            val info = requireNotNull(import(files(UUID.randomUUID().toString())).info)
            val id = requireNotNull(info.sourceId)
            val before = learnedRows

            gateway.discardPackageLearning(id)

            assertEquals(before, learnedRows)
            assertEquals("演示", firstCandidate("yanshi"))
            assertEquals(id, publisher.readActive()?.sourceId)
            assertTrue(catalog.read(id) != null)
            assertTrue(storage.packageLearningDir(id).list().isNullOrEmpty())
            val again = gateway.mergePackageLearning(id)
            assertTrue("nothing is left to merge: $again", again is PackageLearningResult.Failure)
        }

    @Test
    fun aPackageWithoutLearnedWordsAsksNothing() =
        runBlocking {
            val info = requireNotNull(import(files(UUID.randomUUID().toString(), snapshot = null)).info)

            assertEquals(0, info.learningDictionaries)
        }

    @Test
    fun aNewerFileMergesIntoTheLearningDataItKeptAndAMergeAgainChangesNothing() =
        runBlocking {
            val nonce = UUID.randomUUID().toString()
            val first = requireNotNull(import(files(nonce)).info)
            val id = requireNotNull(first.sourceId)
            assertTrue(gateway.mergePackageLearning(id) is PackageLearningResult.Merged)
            val afterFirst = learnedRows
            assertTrue(afterFirst, afterFirst.contains("艳狮"))

            // The same words again, in a newer file of the same package.
            val update = import(files("$nonce-2"))
            assertTrue("a newer file updates in place", update.updated)
            assertEquals(id, update.info?.sourceId)
            assertEquals(1, update.info?.learningDictionaries)
            assertTrue(gateway.mergePackageLearning(id) is PackageLearningResult.Merged)

            assertEquals("merging the same words twice adds nothing", afterFirst, learnedRows)
            assertEquals("艳狮", firstCandidate("yanshi"))

            // A newer file with a stronger word merges into the same learning data.
            val third = import(files("$nonce-3", snapshot("演示家", count = 300)))
            assertEquals(id, third.info?.sourceId)
            assertTrue(gateway.mergePackageLearning(id) is PackageLearningResult.Merged)
            assertTrue(learnedRows.contains("艳狮"))
            assertEquals("演示家", firstCandidate("yanshi"))
            assertNotEquals(afterFirst, learnedRows)
        }

    private companion object {
        const val ARCHIVE = "package-learning.zip"
    }
}

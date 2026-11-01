package app.songyan.ime.learning

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.LearningBackupGatewayImpl
import app.songyan.ime.deploy.BuildStamps
import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Importing the learned words of another Rime front-end (a `.userdb.txt` from its sync folder, or
 * a ZIP of them) through the settings gateway, with the real engine over a real deployment of the
 * bundled package in an isolated storage root.
 */
@RunWith(AndroidJUnit4::class)
class RimeSnapshotImportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var storage: AppStorage
    private lateinit var record: PublicationRecord
    private lateinit var service: LearningBackupService
    private lateinit var gateway: LearningBackupGatewayImpl

    private val live: File get() = LearningData.directory(storage, record.sourceId)

    @Before
    fun deploy() =
        runBlocking {
            root = File(context.cacheDir, "snapshot-import").also { it.deleteRecursively() }
            storage = AppStorage(root).also { it.ensureBaseDirectories() }
            record =
                DeploymentCoordinator(
                    storage = storage,
                    assets = AndroidAssetTree(context.assets, "rime"),
                    runner = RimeBindings.deployRunner(),
                    appName = RimeBindings.APP_NAME,
                    stamp = BuildStamps.current(context),
                ).ensureDeployed().getOrThrow()
            service =
                LearningBackupService(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    ownership = (context.applicationContext as SongyanApplication).engineOwnership,
                    engine = RimeBindings.learningEngine(RimeBindings.APP_NAME, "test"),
                    sourceName = { "内置输入方案" },
                )
            gateway =
                LearningBackupGatewayImpl(context, service, GenerationPublisher(storage), sourceName = { "内置输入方案" })
        }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun <T> withEngine(block: suspend (RimeEngine) -> T): T {
        val generation = storage.generationDir(record.generation)
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = live,
                appName = RimeBindings.APP_NAME,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        return try {
            engine.selectSchema(record.schemaId)
            block(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun RimeEngine.candidates(code: String): List<String> {
        clearComposition()
        for (char in code) processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
        return readSnapshot().candidates.map { it.text }
    }

    private suspend fun firstCandidate(code: String): String? = withEngine { it.candidates(code).firstOrNull() }

    /** Commits the candidate at [index] for [code] until the engine ranks it first; returns its text. */
    private suspend fun learn(
        code: String,
        index: Int,
    ): String =
        withEngine { engine ->
            val word = engine.candidates(code)[index]
            repeat(MAXIMUM_COMMITS) {
                val position = engine.candidates(code).indexOf(word)
                if (position == 0) return@withEngine word
                assertEquals(word, engine.selectCandidate(position)?.commitText)
            }
            fail("$word for $code did not move to the front after $MAXIMUM_COMMITS commits")
            word
        }

    /** The learned rows as an export shows them; equal before and after means nothing was written. */
    private suspend fun learnedRows(): String =
        service
            .backup()
            .dictionaries
            .joinToString("\n") { String(it.data, Charsets.UTF_8) }
            .lines()
            .filterNot { it.startsWith("#") }
            .sorted()
            .joinToString("\n")

    private fun leftovers(): List<String> =
        storage.userRoot
            .list()
            .orEmpty()
            .filter { it.startsWith(".merge-") } +
            listOf("snapshot-import", "declared-scratch").filter { !File(storage.rimeRoot, it).list().isNullOrEmpty() }

    /**
     * A snapshot as another front-end's `sync_user_data` writes it: an older librime, another
     * user id, Windows line ends and a BOM, as a Windows editor may leave it.
     */
    private fun foreignSnapshot(
        dictionary: String,
        vararg rows: String,
    ): String =
        "\uFEFF# Rime user dictionary\r\n#@/db_name\t$dictionary\r\n#@/db_type\tuserdb\r\n" +
            "#@/rime_version\t1.9.0\r\n#@/tick\t500\r\n#@/user_id\tweasel-4f1c\r\n" +
            rows.joinToString("") { "$it\r\n" }

    private fun pick(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(root, "picked/$name").also { it.parentFile!!.mkdirs() }.apply { writeBytes(bytes) })

    @Test
    fun aSnapshotFromAnotherFrontEndMergesAndItsWordComesFirst() =
        runBlocking {
            val kept = learn("shi", 2)
            assertNotEquals("松烟", firstCandidate("songyan"))
            val uri =
                pick(
                    "songyan_shared.userdb.txt",
                    foreignSnapshot("songyan_shared", "song yan \t松烟\tc=100 d=100 t=500").toByteArray(),
                )

            val check = gateway.inspect(uri)
            assertTrue(check.toString(), check is LearningImportCheck.Files && check.canMerge)
            val result = gateway.mergeInspected()
            assertTrue(result.toString(), result is LearningBackupResult.Reported)

            assertEquals("松烟", firstCandidate("songyan"))
            assertEquals("what was learned here stays", kept, firstCandidate("shi"))
            val safety = requireNotNull(service.safetyBackup()) { "no backup was saved before the merge" }
            assertTrue(safety.dictionaries.any { String(it.data).contains(kept) })
            assertTrue(leftovers().isEmpty())
        }

    @Test
    fun aSnapshotForADictionaryNoInputMethodUsesChangesNothing() =
        runBlocking {
            learn("shi", 2)
            val before = learnedRows()
            val uri =
                pick(
                    "rime_ice.userdb.txt",
                    foreignSnapshot("rime_ice", "song yan \t松烟\tc=100 d=100 t=500").toByteArray(),
                )

            val check = gateway.inspect(uri)
            assertTrue(check.toString(), check is LearningImportCheck.Files && !check.canMerge)
            assertTrue((check as LearningImportCheck.Files).description.contains("rime_ice.userdb.txt"))
            gateway.discardInspected()

            assertEquals(before, learnedRows())
            assertNotEquals("松烟", firstCandidate("songyan"))
            assertFalse(service.safetyBackup() != null)
            assertTrue(leftovers().isEmpty())
        }

    @Test
    fun aZipOfASyncFolderMergesWhatFitsAndSkipsTheRest() =
        runBlocking {
            val kept = learn("shi", 2)
            assertNotEquals("墨夜", firstCandidate("moye"))
            val zip = File(root, "sync.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                for (
                (name, text) in
                listOf(
                    "sync/phone/songyan_shared.userdb.txt" to
                        foreignSnapshot("songyan_shared", "mo ye \t墨夜\tc=100 d=100 t=500"),
                    "sync/phone/rime_ice.userdb.txt" to
                        foreignSnapshot("rime_ice", "song yan \t松烟\tc=100 d=100 t=500"),
                    "sync/phone/installation.yaml" to "installation_id: phone\n",
                )
                ) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(text.toByteArray())
                    out.closeEntry()
                }
            }
            val check = gateway.inspect(pick("sync.zip", zip.readBytes()))
            assertTrue(check.toString(), check is LearningImportCheck.Files && check.canMerge)
            assertTrue((check as LearningImportCheck.Files).description.contains("rime_ice.userdb.txt"))
            assertTrue(gateway.mergeInspected() is LearningBackupResult.Reported)

            assertEquals("墨夜", firstCandidate("moye"))
            assertNotEquals("松烟", firstCandidate("songyan"))
            assertEquals(kept, firstCandidate("shi"))
            assertTrue(leftovers().isEmpty())
        }

    private companion object {
        const val MAXIMUM_COMMITS = 12
    }
}

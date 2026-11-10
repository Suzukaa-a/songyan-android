package app.songyan.ime.learning

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.LearningBackupArchive
import app.songyan.ime.content.LearningBackupGatewayImpl
import app.songyan.ime.deploy.BuildStamps
import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.settings.state.LearningPrepared
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Learning backup and merge with the real engine over a real deployment of the bundled package,
 * in an isolated storage root. Engine ownership is the app's own, so a keyboard running in this
 * process would be set aside exactly as in production.
 */
@RunWith(AndroidJUnit4::class)
class LearningBackupDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var storage: AppStorage
    private lateinit var record: PublicationRecord
    private var interruptBeforeSwap = false
    private lateinit var service: LearningBackupService

    private val live: File get() = LearningData.directory(storage, record.sourceId)

    @Before
    fun deploy() =
        runBlocking {
            root = File(context.cacheDir, "learning-backup").also { it.deleteRecursively() }
            storage = AppStorage(root).also { it.ensureBaseDirectories() }
            record =
                DeploymentCoordinator(
                    storage = storage,
                    assets = AndroidAssetTree(context.assets, "rime"),
                    runner = RimeBindings.deployRunner(),
                    appName = RimeBindings.APP_NAME,
                    stamp = BuildStamps.current(context),
                ).deployBuiltin().getOrThrow()
            service =
                LearningBackupService(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    ownership = (context.applicationContext as SongyanApplication).engineOwnership,
                    engine = RimeBindings.learningEngine(RimeBindings.APP_NAME, "test"),
                    sourceName = { "内置输入方案" },
                    beforeSwap = { if (interruptBeforeSwap) throw IOException("process killed before the swap") },
                )
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
            .filter { it.startsWith(".merge-") }

    private fun iosFixture(): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("learning/ios-learning-backup.json")
            .use { it.readBytes() }

    private suspend fun expectProblem(
        problem: LearningTransferProblem,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("expected $problem")
        } catch (e: LearningTransferException) {
            assertEquals(problem, e.problem)
        }
    }

    @Test
    fun anIosExportedBackupMergesAndItsWordsComeFirst() =
        runBlocking {
            val kept = learn("shi", 2)
            assertNotEquals("松烟", firstCandidate("songyan"))
            val archive = service.inspect(iosFixture())
            assertEquals("内置输入方案", archive.sourceName)

            service.merge(archive)

            assertEquals("松烟", firstCandidate("songyan"))
            assertEquals("墨夜", firstCandidate("moye"))
            assertEquals("what was learned here stays", kept, firstCandidate("shi"))
            val safety = requireNotNull(service.safetyBackup()) { "no backup was saved before the merge" }
            assertTrue(safety.dictionaries.any { String(it.data).contains(kept) })
            assertTrue(leftovers().isEmpty())
        }

    @Test
    fun anAndroidBackupRestoresIntoEmptyLearningData() =
        runBlocking {
            val word = learn("shi", 3)
            assertEquals(word, firstCandidate("shi"))
            val bytes = service.backup().encoded()
            assertEquals("userScheme:songyan-builtin", LearningBackupArchive.decode(bytes).sourceId)
            assertTrue(live.deleteRecursively())
            assertNotEquals(word, firstCandidate("shi"))

            service.merge(service.inspect(bytes))

            assertEquals(word, firstCandidate("shi"))
        }

    @Test
    fun aCorruptedArchiveChangesNothing() =
        runBlocking {
            learn("shi", 2)
            val before = learnedRows()
            val fixture = iosFixture()
            val tampered = String(fixture, Charsets.UTF_8).replaceFirst("\"data\":\"Iy", "\"data\":\"Ix").toByteArray()
            expectProblem(LearningTransferProblem.INVALID_ARCHIVE) { service.inspect(tampered) }
            expectProblem(LearningTransferProblem.INVALID_ARCHIVE) { service.inspect(fixture.copyOf(fixture.size / 2)) }
            // A sound envelope around rows that are not a userdb snapshot: the engine refuses it.
            val junk =
                LearningBackupArchive(
                    sourceId = "userScheme:songyan-builtin",
                    resourceRevision = "r",
                    sourceName = "n",
                    engineVersion = "1.17.0",
                    createdAt = Instant.now(),
                    dictionaries =
                        listOf(
                            LearningBackupArchive.Dictionary("songyan_shared", "not a snapshot\n".toByteArray()),
                        ),
                )
            expectProblem(LearningTransferProblem.INVALID_ARCHIVE) { service.merge(junk) }
            assertEquals(before, learnedRows())
            assertTrue(leftovers().isEmpty())
        }

    @Test
    fun anArchiveFromAnotherSchemeChangesNothing() =
        runBlocking {
            learn("shi", 2)
            val before = learnedRows()
            val text = String(iosFixture(), Charsets.UTF_8)
            expectProblem(LearningTransferProblem.OTHER_SOURCE) {
                service.inspect(text.replace("userScheme:songyan-builtin", "userScheme:scheme-0123abcd").toByteArray())
            }
            // Accepted by its source key, but for a dictionary the built-in input methods do not learn into.
            val other = LearningBackupArchive.decode(iosFixture())
            val renamed =
                String(other.dictionaries.single().data, Charsets.UTF_8).replace("songyan_shared", "rime_ice")
            val foreign =
                LearningBackupArchive(
                    sourceId = "userDictionary",
                    resourceRevision = other.resourceRevision,
                    sourceName = other.sourceName,
                    engineVersion = other.engineVersion,
                    createdAt = other.createdAt,
                    dictionaries = listOf(LearningBackupArchive.Dictionary("rime_ice", renamed.toByteArray())),
                )
            expectProblem(LearningTransferProblem.OTHER_SOURCE) { service.merge(foreign) }
            assertEquals(before, learnedRows())
            assertNotEquals("松烟", firstCandidate("songyan"))
            assertTrue(leftovers().isEmpty())
        }

    @Test
    fun aMergeInterruptedBeforeItLandsLeavesTheLearningDataIntact() =
        runBlocking {
            learn("shi", 2)
            val before = learnedRows()
            interruptBeforeSwap = true
            expectProblem(LearningTransferProblem.FAILED) { service.merge(service.inspect(iosFixture())) }
            assertEquals(before, learnedRows())
            assertNotEquals("松烟", firstCandidate("songyan"))
            assertTrue(leftovers().isEmpty())

            interruptBeforeSwap = false
            service.merge(service.inspect(iosFixture()))
            assertEquals("松烟", firstCandidate("songyan"))
        }

    @Test
    fun theSettingsGatewaySavesAndMergesThroughDocumentUris() =
        runBlocking {
            val word = learn("shi", 3)
            val gateway =
                LearningBackupGatewayImpl(context, service, GenerationPublisher(storage), sourceName = { "内置输入方案" })
            val prepared = gateway.prepareBackup()
            assertTrue(prepared.toString(), prepared is LearningPrepared.Ready)
            val file = File(root, "picked/" + (prepared as LearningPrepared.Ready).suggestedFileName)
            file.parentFile!!.mkdirs()
            file.writeText("a longer file the provider reuses; truncation must drop this tail ".repeat(200))
            assertEquals(LearningBackupResult.Success, gateway.writePrepared(Uri.fromFile(file)))
            LearningBackupArchive.decode(file.readBytes())

            assertTrue(live.deleteRecursively())
            val check = gateway.inspect(Uri.fromFile(file))
            assertEquals("内置输入方案", (check as LearningImportCheck.Ready).summary.sourceName)
            assertEquals(LearningBackupResult.Success, gateway.mergeInspected())
            assertEquals(word, firstCandidate("shi"))

            file.writeText("{}")
            assertTrue(gateway.inspect(Uri.fromFile(file)) is LearningImportCheck.Failure)
        }

    private companion object {
        const val MAXIMUM_COMMITS = 12
    }
}

package app.songyan.ime.learning

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.LearningBackupGatewayImpl
import app.songyan.ime.deploy.BuildStamps
import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.engine.EngineStartGuard
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.settings.state.QuarantinedAvailability
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

/**
 * Restoring learning data the start guard set aside, with the real engine over a real deployment
 * in an isolated storage root: the data is quarantined by the guard itself, read back through a
 * temporary engine on a copy, confirmed and merged by the settings gateway.
 */
@RunWith(AndroidJUnit4::class)
class QuarantineRestoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var storage: AppStorage
    private lateinit var record: PublicationRecord
    private lateinit var gateway: LearningBackupGatewayImpl

    private val live: File get() = LearningData.directory(storage, record.sourceId)

    @Before
    fun deploy() =
        runBlocking {
            root = File(context.cacheDir, "quarantine-restore").also { it.deleteRecursively() }
            storage = AppStorage(root).also { it.ensureBaseDirectories() }
            record =
                DeploymentCoordinator(
                    storage = storage,
                    assets = AndroidAssetTree(context.assets, "rime"),
                    runner = RimeBindings.deployRunner(),
                    appName = RimeBindings.APP_NAME,
                    stamp = BuildStamps.current(context),
                ).deployBuiltin().getOrThrow()
            val service =
                LearningBackupService(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    ownership = (context.applicationContext as SongyanApplication).engineOwnership,
                    engine = RimeBindings.learningEngine(RimeBindings.APP_NAME, "test"),
                    sourceName = { "内置输入方案" },
                )
            gateway = LearningBackupGatewayImpl(context, service, GenerationPublisher(storage), { "内置输入方案" })
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

    /** What two unfinished starts followed by a third one do: the learning data moves to quarantine. */
    private fun quarantineTheLearningData() {
        val guard = EngineStartGuard(storage)
        repeat(3) { guard.beforeStart(record) }
        assertFalse("the learning data was moved aside", LearningData.holdsLearningData(live))
    }

    @Test
    fun quarantinedLearningDataComesBackThroughTheCheckedMerge() =
        runBlocking {
            val word = learn("shi", 3)
            assertEquals(word, firstCandidate("shi"))
            quarantineTheLearningData()
            assertNotEquals("the keyboard starts without it", word, firstCandidate("shi"))

            val listed = gateway.quarantined().single()
            assertEquals(QuarantinedAvailability.READY, listed.availability)
            assertEquals("内置输入方案", listed.sourceName)

            val check = gateway.inspectQuarantined(listed.id)
            assertTrue(check.toString(), check is LearningImportCheck.Ready)
            assertEquals(listed.timeEpochMs, (check as LearningImportCheck.Ready).summary.createdAtEpochMs)
            assertNotEquals("nothing is live before the user confirms", word, firstCandidate("shi"))

            assertEquals(LearningBackupResult.Success, gateway.mergeInspected())

            assertEquals(word, firstCandidate("shi"))
            assertEquals(QuarantinedAvailability.RESTORED, gateway.quarantined().single().availability)
            assertTrue(
                "the set-aside data itself stays until the user deletes it",
                File(storage.rimeRoot, "quarantine").walkTopDown().any { it.name.endsWith(".userdb") },
            )
            assertFalse(File(storage.rimeRoot, "quarantine-work").exists())
        }

    @Test
    fun deletingASetRemovesItsDataAndAnUnreadableSetCannotBeRestored() =
        runBlocking {
            learn("shi", 3)
            quarantineTheLearningData()
            val set = gateway.quarantined().single()

            // A restore whose engine read never came back leaves this marker behind.
            File(storage.rimeRoot, "quarantine/${set.id}/restore-attempt").writeBytes(byteArrayOf(1))
            assertEquals(QuarantinedAvailability.UNREADABLE, gateway.quarantined().single().availability)
            val refused = gateway.inspectQuarantined(set.id)
            assertTrue(refused.toString(), refused is LearningImportCheck.Failure)
            assertTrue(
                "kept as it is",
                File(storage.rimeRoot, "quarantine").walkTopDown().any { it.name.endsWith(".userdb") },
            )

            assertEquals(LearningBackupResult.Success, gateway.deleteQuarantined(set.id))
            assertTrue(gateway.quarantined().isEmpty())
            assertFalse(File(storage.rimeRoot, "quarantine").walkTopDown().any { it.name.endsWith(".userdb") })
        }

    private companion object {
        const val MAXIMUM_COMMITS = 12
    }
}

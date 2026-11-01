package app.songyan.ime.deploy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole first-run chain with the production classes and the real engine: the bundled package
 * is installed as the built-in source, fully deployed, published as a generation, and an engine
 * opened on that generation with prebuilt tables types Chinese. Runs in an isolated storage root so
 * the installed app's own data is untouched.
 */
@RunWith(AndroidJUnit4::class)
class DeployChainDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun isolate() {
        root = File(context.cacheDir, "deploy-chain").also { it.deleteRecursively() }
        root.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theBundledPackageIsInstalledDeployedPublishedAndTypesChinese() =
        runBlocking {
            val storage = AppStorage(root)
            storage.ensureBaseDirectories()
            val stamp = BuildStamps.current(context)
            val coordinator =
                DeploymentCoordinator(
                    storage = storage,
                    assets = AndroidAssetTree(context.assets, "rime"),
                    runner = RimeBindings.deployRunner(),
                    appName = RimeBindings.APP_NAME,
                    stamp = stamp,
                )
            assertEquals("nothing published before the first deployment", null, coordinator.activeGeneration())

            val record = coordinator.ensureDeployed().getOrThrow()

            assertEquals(SourceIds.BUILTIN, record.sourceId)
            assertEquals(stamp, record.stamp)
            val publisher = GenerationPublisher(storage)
            assertTrue("published generation is incomplete", publisher.isComplete(record))
            assertEquals(record, publisher.readActive())
            assertTrue(coordinator.hasUsableGeneration())
            assertTrue(
                "built-in source tree was not installed",
                File(storage.sourceDir(SourceIds.BUILTIN), "default.yaml").isFile,
            )
            val generation = storage.generationDir(record.generation)
            assertTrue(File(generation, "build/default.yaml").isFile)

            val user = File(root, "engine-user")
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = File(generation, "resources"),
                    userDataDir = user,
                    appName = RimeBindings.APP_NAME,
                    appVersion = "test",
                    compiledDir = File(generation, "build"),
                )
            try {
                assertTrue(
                    "published schema ${record.schemaId} cannot be selected",
                    engine.selectSchema(record.schemaId),
                )
                for (char in "nihao") engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
                val texts = engine.readSnapshot().candidates.map { it.text }
                assertTrue("no Chinese candidates for nihao in ${record.schemaId}: $texts", texts.contains("你好"))
                assertEquals("你好", engine.selectCandidate(texts.indexOf("你好"))?.commitText)
            } finally {
                engine.close()
            }

            // A second call finds the current generation and does not publish again.
            assertEquals(record, coordinator.ensureDeployed().getOrThrow())
        }

    /**
     * The shipped package and starter dictionaries weigh on different scales; a fresh install must
     * still offer the most frequent character of a reading first rather than its rarer homophones.
     */
    @Test
    fun theDeployedBuiltinRanksTheMostFrequentCharacterFirst() =
        runBlocking {
            val storage = AppStorage(root)
            storage.ensureBaseDirectories()
            val record =
                DeploymentCoordinator(
                    storage = storage,
                    assets = AndroidAssetTree(context.assets, "rime"),
                    runner = RimeBindings.deployRunner(),
                    appName = RimeBindings.APP_NAME,
                    stamp = BuildStamps.current(context),
                ).ensureDeployed().getOrThrow()
            val generation = storage.generationDir(record.generation)
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = File(generation, "resources"),
                    userDataDir = File(root, "engine-user"),
                    appName = RimeBindings.APP_NAME,
                    appVersion = "test",
                    compiledDir = File(generation, "build"),
                )
            try {
                assertTrue(engine.selectSchema(record.schemaId))
                for ((input, expected) in listOf("ni" to "你", "shi" to "是", "de" to "的", "wo" to "我")) {
                    engine.clearComposition()
                    for (char in input) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
                    val texts = engine.readSnapshot().candidates.map { it.text }
                    assertEquals("first candidate for $input in $texts", expected, texts.firstOrNull())
                }
            } finally {
                engine.close()
            }
        }
}

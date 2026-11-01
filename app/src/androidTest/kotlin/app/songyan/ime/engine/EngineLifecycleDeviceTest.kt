package app.songyan.ime.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.DiagnosticsGatewayImpl
import app.songyan.ime.deploy.BuildStamps
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationLease
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The IME runtime's lifecycle with the production classes and the real engine, in an isolated
 * storage root: a deployment that finishes while the user is composing, a crash loop at engine
 * start, and the build stamp that decides when a generation must be rebuilt.
 */
@RunWith(AndroidJUnit4::class)
class EngineLifecycleDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as SongyanApplication
    private lateinit var root: File
    private lateinit var storage: AppStorage

    @Before
    fun isolate() {
        root = File(context.cacheDir, "engine-lifecycle").also { it.deleteRecursively() }
        storage = AppStorage(root).also { it.ensureBaseDirectories() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun coordinator() =
        DeploymentCoordinator(
            storage = storage,
            assets = AndroidAssetTree(context.assets, "rime"),
            runner = RimeBindings.deployRunner(),
            appName = RimeBindings.APP_NAME,
            stamp = BuildStamps.current(context),
        )

    private class Recorder : ImeEngineRuntime.Callbacks {
        @Volatile
        var backend: EngineBackend? = null
        val ready = AtomicInteger()
        val released = AtomicInteger()
        val failed = AtomicInteger()

        override fun onState(state: EngineRuntimeState) {
            if (state == EngineRuntimeState.READY) ready.incrementAndGet()
            if (state == EngineRuntimeState.FAILED) failed.incrementAndGet()
        }

        override fun onEngine(backend: EngineBackend) {
            this.backend = backend
        }

        override fun onEngineReleased() {
            backend = null
            released.incrementAndGet()
        }
    }

    private fun runtime(
        callbacks: Recorder,
        guard: EngineStartGuard? = null,
    ) = ImeEngineRuntime(
        storage = storage,
        publisher = GenerationPublisher(storage),
        deployStatus = DeployStatusStore(storage),
        factory = RimeBindings.engineFactory(storage.optionsDir),
        appName = RimeBindings.APP_NAME,
        appVersion = "test",
        callbacks = callbacks,
        requestFirstDeploy = { },
        currentStamp = { BuildStamps.current(context) },
        ownership = app.engineOwnership,
        startGuard = guard,
        // The deploy-finished broadcast and the keyboard callbacks arrive on the main thread.
        mainDispatcher = Dispatchers.Main.immediate,
    )

    private suspend fun type(
        backend: EngineBackend,
        keys: String,
    ) = keys.map { backend.processKey(it.toString()) }.last()

    @Test
    fun aDeploymentThatFinishesWhileComposingKeepsTheCompositionAndSwapsAfterTheKeyboardCloses() =
        runBlocking {
            // Two generations exist before any engine of this process opens (the deploy process
            // owns librime in production); the older one is active first.
            val first = coordinator().deployBuiltin(force = true).getOrThrow()
            val second = coordinator().deployBuiltin(force = true).getOrThrow()
            assertTrue(first.generation != second.generation)
            GenerationPublisher(storage).reactivate(first.generation)

            val callbacks = Recorder()
            val runtime = runtime(callbacks)
            try {
                runtime.ensureStarted()
                withTimeout(30_000) { while (callbacks.ready.get() < 1) delay(20) }
                val engine = requireNotNull(callbacks.backend)
                runOnMain { runtime.setInputViewShown(true) }
                assertTrue(type(engine, "ni").isComposing)

                // The deployment publishes the newer generation and broadcasts, maybe more than once.
                GenerationPublisher(storage).reactivate(second.generation)
                repeat(3) { runtime.refreshIfChanged() }
                withTimeout(10_000) { while (!runtime.isSwapDeferred) delay(20) }
                delay(300)
                assertEquals("the engine under the user's fingers is not released", 0, callbacks.released.get())
                assertSame(engine, callbacks.backend)
                val continued = type(engine, "hao")
                assertTrue("the composition goes on where it was", continued.isComposing)
                assertEquals("nihao", continued.rawInput.replace(" ", ""))
                assertTrue(continued.candidates.any { it.text == "你好" })

                // The keyboard closes: the newer generation is loaded once.
                runOnMain { runtime.setInputViewShown(false) }
                withTimeout(30_000) { while (callbacks.ready.get() < 2) delay(20) }
                assertEquals(1, callbacks.released.get())
                assertFalse(runtime.isSwapDeferred)
                val swapped = requireNotNull(callbacks.backend)
                assertNotSame(engine, swapped)
                assertEquals(
                    second.generation,
                    GenerationLease(storage).leased().single(),
                )
                assertTrue(type(swapped, "nihao").candidates.any { it.text == "你好" })
            } finally {
                runtime.close()
            }
        }

    @Test
    fun twoStartsThatNeverFinishedMoveTheLearningDataAsideAndTheKeyboardStillTypes() =
        runBlocking {
            val record = coordinator().ensureDeployed().getOrThrow()
            assertEquals(SourceIds.BUILTIN, record.sourceId)
            val learning = LearningData.directory(storage, record.sourceId)
            File(learning, "suspect.userdb/data").apply {
                parentFile!!.mkdirs()
                writeText("corrupt")
            }
            // Two earlier processes died inside the engine start (a native crash leaves no trace
            // in Kotlin; the marker is what it leaves behind).
            repeat(2) { assertEquals(EngineStartGuard.Decision.Start, EngineStartGuard(storage).beforeStart(record)) }

            val callbacks = Recorder()
            val runtime = runtime(callbacks, EngineStartGuard(storage))
            try {
                runtime.ensureStarted()
                withTimeout(30_000) { while (callbacks.ready.get() < 1) delay(20) }
                assertEquals(0, callbacks.failed.get())
                assertTrue(type(requireNotNull(callbacks.backend), "nihao").candidates.any { it.text == "你好" })
            } finally {
                runtime.close()
            }
            assertFalse("the suspect learning data is not opened", File(learning, "suspect.userdb").exists())
            val moved =
                File(storage.rimeRoot, EngineStartGuard.QUARANTINE_DIR)
                    .walkTopDown()
                    .single { it.name == "data" }
            assertEquals("moved aside, never deleted", "corrupt", moved.readText())
            assertFalse("a finished start clears its marker", File(storage.rimeRoot, "engine-start.json").exists())

            // Settings show what happened.
            val notice = requireNotNull(DiagnosticsGatewayImpl(context, storage).engineRecovery())
            assertTrue(notice.movedLearningData)
            assertFalse(notice.switchedToBuiltin)
            assertTrue(
                DiagnosticsGatewayImpl(context, storage).report().contains("engine start recoveries:\n  builtin"),
            )
        }

    @Test
    fun theStampFollowsTheLinkedEngineAndIsStableAcrossReads() =
        runBlocking {
            val stamp = BuildStamps.current(context)
            assertEquals(stamp, BuildStamps.current(context))
            // The runtime refuses compiled data from another librime release, so that release must be
            // part of the stamp: a generation built by another engine is then rebuilt, not loaded.
            val record: PublicationRecord = coordinator().ensureDeployed().getOrThrow()
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = File(storage.generationDir(record.generation), "resources"),
                    userDataDir = File(root, "stamp-user"),
                    appName = RimeBindings.APP_NAME,
                    appVersion = "test",
                    compiledDir = File(storage.generationDir(record.generation), "build"),
                )
            try {
                assertEquals(engine.version(), stamp.engine.substringBefore('+'))
            } finally {
                engine.close()
            }
            assertEquals(stamp, record.stamp)
            assertTrue("a current generation is reused, not rebuilt", coordinator().hasUsableGeneration())
        }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}

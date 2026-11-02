package app.songyan.ime.engine

import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.GenerationLease
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.LearningMerger
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The IME runtime must never run two engine starts at once: the native bridge refuses a second
 * instance ("another RimeEngine is active in this process"), so an overlapping start used to report
 * a spurious failure and tear down the engine that had just started.
 */
class ImeEngineRuntimeTest {
    private class FakeBackend : EngineBackend {
        @Volatile
        var closed = false

        override val kind: EngineBackendKind = EngineBackendKind.RIME
        override val inputUnavailableReason: String? = null
        override val availableSchemas: List<KeyboardSchemaDescriptor> = emptyList()
        override val statusSummary: String = "fake"
        override var spellingDemand: (() -> Boolean)? = null

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) {
            // The runtime never calls into the backend before handing it to the service.
        }

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot = EngineSnapshot()

        override suspend fun selectCandidate(index: Int): EngineSnapshot = EngineSnapshot()

        override suspend fun nextPage(): EngineSnapshot = EngineSnapshot()

        override suspend fun previousPage(): EngineSnapshot = EngineSnapshot()

        override fun currentSnapshot(): EngineSnapshot = EngineSnapshot()

        override suspend fun shutdown() {
            closed = true
        }
    }

    private fun storageWithPublishedGeneration(): AppStorage {
        val files = Files.createTempDirectory("app-files").toFile()
        val storage = AppStorage(files).also { it.ensureBaseDirectories() }
        val generation = "1000-songyan-builtin-abcd"
        val generationDir = storage.generationDir(generation).also { it.mkdirs() }
        File(generationDir, "resources").mkdirs()
        File(generationDir, "resources/default.yaml").writeText("config_version: \"1\"\n")
        File(generationDir, "build").mkdirs()
        File(generationDir, "build/build_info.yaml").writeText("rime_version: 1.17.0\n")
        val record =
            "{\"version\":1,\"generation\":\"" + generation +
                "\",\"sourceId\":\"songyan-builtin\",\"schemaId\":\"songyan_quanpin\"," +
                "\"revision\":\"1\",\"createdAt\":0}"
        storage.activeFile.writeText(record)
        return storage
    }

    @Test
    fun aFreshInstallWaitsForASchemeAndStartsAfterItIsPublished() =
        runBlocking {
            val storage =
                AppStorage(
                    Files.createTempDirectory("fresh-files").toFile(),
                ).also { it.ensureBaseDirectories() }
            app.songyan.ime.deploy
                .DeployStatusStore(storage)
                .write(app.songyan.ime.deploy.DeployState.PREPARING)
            val callbacks = RecordingCallbacks()
            val created = java.util.Collections.synchronizedList(mutableListOf<File>())
            val rebuilds = AtomicInteger()
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory = countingFactory(created),
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks = callbacks,
                    requestFirstDeploy = { rebuilds.incrementAndGet() },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )
            try {
                runtime.ensureStarted()
                withTimeout(10_000) { while ("NEEDS_SCHEME" !in callbacks.events) delay(10) }
                assertTrue(created.isEmpty())
                assertEquals(0, rebuilds.get())
                val published = storageWithPublishedGeneration()
                published.rimeRoot.copyRecursively(storage.rimeRoot, overwrite = true)
                runtime.refreshIfChanged()
                withTimeout(10_000) { while (callbacks.ready.get() < 1) delay(10) }
                assertEquals(1, created.size)
            } finally {
                runtime.close()
            }
        }

    @Test
    fun overlappingTriggersCreateExactlyOneEngine() =
        runBlocking {
            val storage = storageWithPublishedGeneration()
            val creates = AtomicInteger()
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            val ready = AtomicInteger()
            val failed = AtomicInteger()
            val factory =
                PublishedEngineFactory { _, _, _, _, _ ->
                    creates.incrementAndGet()
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        // A prebuilt start reads tables before it returns; slow it down so an ungated
                        // implementation would certainly overlap.
                        delay(120)
                        object : EngineHandle {
                            private val fake = FakeBackend()

                            override val backend: EngineBackend = fake

                            override suspend fun close() {
                                fake.shutdown()
                            }
                        }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory = factory,
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks =
                        object : ImeEngineRuntime.Callbacks {
                            override fun onState(state: EngineRuntimeState) {
                                if (state == EngineRuntimeState.READY) ready.incrementAndGet()
                                if (state == EngineRuntimeState.FAILED) failed.incrementAndGet()
                            }

                            override fun onEngine(backend: EngineBackend) = Unit

                            override fun onEngineReleased() = Unit
                        },
                    requestFirstDeploy = { },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )

            // A keyboard show and the deploy-finished broadcast can arrive in the same frame.
            repeat(6) { runtime.ensureStarted() }
            repeat(6) { runtime.refreshIfChanged() }

            withTimeout(10_000) {
                while (creates.get() < 1 || inFlight.get() > 0) delay(10)
            }
            // Let the remaining serialized reloads see the running engine.
            delay(300)

            assertEquals("overlapping starts must not create a second engine", 1, creates.get())
            assertEquals("engine starts must never overlap", 1, maxInFlight.get())
            assertEquals("a serialized start must not report a failure", 0, failed.get())
            assertTrue("the single start must reach READY", ready.get() >= 1)
            assertTrue(runtime.isRunning)

            runtime.close()
            assertFalse("shutdown must stop the engine it started", runtime.isRunning)
        }

    @Test
    fun aStaleOrBrokenGenerationAsksForOneRebuildAndKeepsTheWorkingEngine() =
        runBlocking {
            val storage = storageWithPublishedGeneration()
            val userDirs = mutableListOf<File>()
            val factory =
                PublishedEngineFactory { _, user, _, _, _ ->
                    userDirs += user
                    object : EngineHandle {
                        override val backend: EngineBackend = FakeBackend()

                        override suspend fun close() = Unit
                    }
                }
            val rebuilds = AtomicInteger()
            val ready = AtomicInteger()
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory = factory,
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks =
                        object : ImeEngineRuntime.Callbacks {
                            override fun onState(state: EngineRuntimeState) {
                                if (state == EngineRuntimeState.READY) ready.incrementAndGet()
                            }

                            override fun onEngine(backend: EngineBackend) = Unit

                            override fun onEngineReleased() = Unit
                        },
                    requestFirstDeploy = { rebuilds.incrementAndGet() },
                    // The record carries no stamp, as one written by an earlier version.
                    currentStamp = { BuildStamp("2.0/2/2", "1.17.0", "2") },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )

            runtime.ensureStarted()
            withTimeout(10_000) { while (ready.get() < 1) delay(10) }
            runtime.refreshIfChanged()
            delay(200)

            assertEquals("a stale generation still loads while it is rebuilt", 1, userDirs.size)
            assertEquals("the rebuild is asked for once per generation", 1, rebuilds.get())
            assertEquals(LearningData.directory(storage, "songyan-builtin"), userDirs.single())
            assertEquals(setOf("1000-songyan-builtin-abcd"), GenerationLease(storage).leased())

            // A newer active record whose generation is missing must not cost the running engine.
            storage.activeFile.writeText(
                storage.activeFile.readText().replace("1000-songyan-builtin-abcd", "2000-songyan-builtin-ffff"),
            )
            runtime.refreshIfChanged()
            delay(200)
            assertTrue(runtime.isRunning)
            assertEquals(1, userDirs.size)
            assertEquals(2, rebuilds.get())
            runtime.close()
        }

    @Test
    fun legacyLearningDataIsMergedBeforeTheEngineOpensAndAFailureIsRetriedWithTheNextGeneration() =
        runBlocking {
            val storage = storageWithPublishedGeneration()
            for (legacy in listOf(SourceIds.BUILTIN, SourceIds.DICTIONARY)) {
                File(storage.learningDir(legacy), "songyan_shared.userdb").mkdirs()
            }
            File(storage.learningDir(LearningData.PINYIN), "songyan_shared.userdb").mkdirs()
            val engineOpen = AtomicInteger()
            val mergedWhileOpen = AtomicInteger()
            val merges = mutableListOf<Pair<String, String>>()
            var succeed = false
            val ready = AtomicInteger()
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory =
                        PublishedEngineFactory { _, _, _, _, _ ->
                            engineOpen.incrementAndGet()
                            object : EngineHandle {
                                override val backend: EngineBackend = FakeBackend()

                                override suspend fun close() {
                                    engineOpen.decrementAndGet()
                                }
                            }
                        },
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks =
                        object : ImeEngineRuntime.Callbacks {
                            override fun onState(state: EngineRuntimeState) {
                                if (state == EngineRuntimeState.READY) ready.incrementAndGet()
                            }

                            override fun onEngine(backend: EngineBackend) = Unit

                            override fun onEngineReleased() = Unit
                        },
                    requestFirstDeploy = { },
                    learningMerger = { record ->
                        LearningMerger { from, _ ->
                            if (engineOpen.get() != 0) mergedWhileOpen.incrementAndGet()
                            merges += record.generation to from.name
                            succeed
                        }
                    },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )

            runtime.ensureStarted()
            withTimeout(10_000) { while (ready.get() < 1) delay(10) }
            runtime.refreshIfChanged()
            delay(200)
            assertEquals("a failed merge is not repeated for the same generation", 2, merges.size)
            assertTrue(storage.learningDir(SourceIds.BUILTIN).isDirectory)
            assertTrue(storage.learningDir(SourceIds.DICTIONARY).isDirectory)

            // The next published generation retries, again with no engine of this process open.
            val next = "2000-songyan-builtin-ffff"
            storage.generationDir("1000-songyan-builtin-abcd").copyRecursively(storage.generationDir(next))
            storage.activeFile.writeText(storage.activeFile.readText().replace("1000-songyan-builtin-abcd", next))
            succeed = true
            runtime.refreshIfChanged()
            withTimeout(10_000) { while (ready.get() < 2) delay(10) }

            assertEquals(4, merges.size)
            assertEquals(next, merges.last().first)
            assertEquals("no merge may run while an engine of this process is open", 0, mergedWhileOpen.get())
            assertFalse(storage.learningDir(SourceIds.BUILTIN).exists())
            assertFalse(storage.learningDir(SourceIds.DICTIONARY).exists())
            runtime.close()
        }

    private fun publishNext(
        storage: AppStorage,
        next: String,
    ) {
        storage.generationDir("1000-songyan-builtin-abcd").copyRecursively(storage.generationDir(next))
        storage.activeFile.writeText(
            storage.activeFile.readText().replace(Regex("\"generation\":\"[^\"]+\""), "\"generation\":\"$next\""),
        )
    }

    private class RecordingCallbacks : ImeEngineRuntime.Callbacks {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val ready = AtomicInteger()

        override fun onState(state: EngineRuntimeState) {
            if (state == EngineRuntimeState.READY) ready.incrementAndGet()
            events += state.name
        }

        override fun onEngine(backend: EngineBackend) {
            events += "engine"
        }

        override fun onEngineReleased() {
            events += "released"
        }

        override suspend fun awaitInputIdle() {
            events += "idle"
        }
    }

    private fun countingFactory(created: MutableList<File>) =
        PublishedEngineFactory { shared, _, _, _, _ ->
            created += shared.parentFile
            object : EngineHandle {
                override val backend: EngineBackend = FakeBackend()

                override suspend fun close() = Unit
            }
        }

    @Test
    fun aDeployFinishedWhileTheKeysAreShownWaitsForTheInputViewToClose() =
        runBlocking {
            val storage = storageWithPublishedGeneration()
            val created = java.util.Collections.synchronizedList(mutableListOf<File>())
            val callbacks = RecordingCallbacks()
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory = countingFactory(created),
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks = callbacks,
                    requestFirstDeploy = { },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )
            runtime.setInputViewShown(true)
            // No engine yet: a shown keyboard must not wait for anything.
            runtime.ensureStarted()
            withTimeout(10_000) { while (callbacks.ready.get() < 1) delay(10) }
            assertEquals(1, created.size)

            // The deploy-finished broadcast can arrive several times while the user is typing.
            publishNext(storage, "2000-songyan-builtin-ffff")
            repeat(3) { runtime.refreshIfChanged() }
            delay(300)
            assertEquals("the engine under the shown keys is never replaced", 1, created.size)
            assertFalse(callbacks.events.contains("released"))
            assertTrue(runtime.isSwapDeferred)
            assertTrue(runtime.isRunning)

            // Hidden: the newest generation is loaded once, after the queued keys ran.
            runtime.setInputViewShown(false)
            withTimeout(10_000) { while (callbacks.ready.get() < 2) delay(10) }
            delay(200)
            assertEquals(2, created.size)
            assertEquals("2000-songyan-builtin-ffff", created.last().name)
            assertFalse(runtime.isSwapDeferred)
            val idle = callbacks.events.indexOf("idle")
            assertTrue(
                "queued keys drain before the release: " + callbacks.events,
                idle >= 0 && idle < callbacks.events.indexOf("released"),
            )
            runtime.close()
        }

    @Test
    fun anImportedGenerationWhoseStartsKeptDyingIsNotStartedAndTheBuiltInSourceIsRequested() =
        runBlocking {
            val storage = storageWithPublishedGeneration()
            storage.activeFile.writeText(storage.activeFile.readText().replace("\"songyan-builtin\"", "\"scheme-a\""))
            val active = GenerationPublisher(storage).readActive()!!
            // Two earlier processes died inside the start.
            repeat(2) { assertEquals(EngineStartGuard.Decision.Start, EngineStartGuard(storage).beforeStart(active)) }

            val created = java.util.Collections.synchronizedList(mutableListOf<File>())
            val callbacks = RecordingCallbacks()
            val builtinRequests = AtomicInteger()
            val runtime =
                ImeEngineRuntime(
                    storage = storage,
                    publisher = GenerationPublisher(storage),
                    deployStatus = DeployStatusStore(storage),
                    factory = countingFactory(created),
                    appName = "rime.songyan",
                    appVersion = "test/1",
                    callbacks = callbacks,
                    requestFirstDeploy = { },
                    startGuard = EngineStartGuard(storage),
                    requestBuiltin = { builtinRequests.incrementAndGet() },
                    ioDispatcher = Dispatchers.Default,
                    mainDispatcher = Dispatchers.Default,
                )
            runtime.ensureStarted()
            runtime.refreshIfChanged()
            delay(300)
            assertEquals("the generation that crashed is not opened again", 0, created.size)
            assertEquals("the built-in source is asked for once", 1, builtinRequests.get())
            assertFalse(callbacks.events.contains(EngineRuntimeState.FAILED.name))
            assertEquals(1, EngineStartGuard.quarantines(storage).size)

            // The deploy process published the built-in generation: it starts and clears the marker.
            storage.activeFile.writeText(storage.activeFile.readText().replace("\"scheme-a\"", "\"songyan-builtin\""))
            publishNext(storage, "3000-songyan-builtin-eeee")
            runtime.refreshIfChanged()
            withTimeout(10_000) { while (callbacks.ready.get() < 1) delay(10) }
            assertEquals(1, created.size)
            assertFalse(File(storage.rimeRoot, "engine-start.json").exists())
            runtime.close()
        }
}

package app.songyan.ime.rime

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.EngineBackendError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Engine robustness that the JVM tests cannot cover because it needs the Android runtime: a failed
 * start must not leave the engine thread alive, and a write the engine refuses must be an error
 * instead of a successful snapshot of the old state.
 */
@RunWith(AndroidJUnit4::class)
class RimeEngineRobustnessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aFailedStartLeavesNoEngineThreadBehind() {
        // Every engine of the process shares one engine thread; failures never add more.
        val before = maxOf(engineThreads(), 1)
        val scratch = File(instrumentation.targetContext.cacheDir, "engine-failure")
        scratch.deleteRecursively()
        repeat(3) { attempt ->
            val failure =
                runCatching {
                    runBlocking {
                        RimeEngine.createPrebuilt(
                            sharedDataDir = File(scratch, "missing-shared"),
                            userDataDir = File(scratch, "user"),
                            appName = RimeEngine.DEFAULT_APP_NAME,
                            appVersion = "test",
                            compiledDir = File(scratch, "missing-build"),
                        )
                    }
                }.exceptionOrNull()
            assertTrue("attempt " + attempt + ": a start without data directories must fail", failure is RimeException)
        }
        assertTrue(
            "a failed start left the engine thread alive: " + engineThreads() + " (before: " + before + ")",
            awaitEngineThreads(atMost = before),
        )
    }

    @Test
    fun aWriteWithoutALiveSessionIsAnErrorNotASuccessfulSnapshot() {
        runBlocking {
            val root = File(instrumentation.targetContext.cacheDir, "engine-stopped")
            root.deleteRecursively()
            val source = File(root, "source")
            copyAssetTree(instrumentation.targetContext.assets, BUILTIN_ASSET_ROOT, source)
            val user = File(root, "user")
            val deployment = RimeDeployment.deploy(source, user, RimeEngine.DEFAULT_APP_NAME, "test")
            assertTrue("deployment failed: " + deployment.lastError, deployment.success)
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = source,
                    userDataDir = user,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                    compiledDir = deployment.compiledDir,
                )
            try {
                val backend = RimeEngineBackend(engine)
                // A live engine keeps accepting declared options.
                assertNotNull(backend.setOption("simplification", true))
                // No session left: the write cannot be applied, so it must not look applied.
                engine.destroySession()
                val refused = runCatching { backend.setOption("simplification", true) }.exceptionOrNull()
                assertTrue(
                    "a refused write must throw, got: " + refused,
                    refused is EngineBackendError.OperationFailed,
                )
            } finally {
                engine.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun aCancelledCallerStillGetsItsCommitDeliveredByTheNextSnapshot() {
        runBlocking {
            val root = File(instrumentation.targetContext.cacheDir, "engine-cancel")
            root.deleteRecursively()
            val source = File(root, "source")
            copyAssetTree(instrumentation.targetContext.assets, BUILTIN_ASSET_ROOT, source)
            val user = File(root, "user")
            val deployment = RimeDeployment.deploy(source, user, RimeEngine.DEFAULT_APP_NAME, "test")
            assertTrue("deployment failed: " + deployment.lastError, deployment.success)
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = source,
                    userDataDir = user,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                    compiledDir = deployment.compiledDir,
                )
            try {
                engine.processKeySequence("nihao")
                // Hold the engine thread so the commit request is queued when its caller is cancelled.
                val hold = java.util.concurrent.CountDownLatch(1)
                RimeDispatcher.shared().post { hold.await() }
                // Undispatched, the caller runs until its request is queued behind the hold, so the
                // cancel always lands while the commit waits rather than before it is asked for.
                val caller = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { engine.commitComposition() }
                caller.cancel()
                hold.countDown()
                caller.join()

                val next = engine.readSnapshot()
                assertTrue("the cancelled commit must reach the next snapshot", next.commitText.isNotEmpty())
                assertTrue("and only once", engine.readSnapshot().commitText.isEmpty())
            } finally {
                engine.close()
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun aCreateThatOutlivesItsWaitDoesNotKeepTheEngineSlot() {
        runBlocking {
            val dispatcher = RimeDispatcher.shared()
            val hold = java.util.concurrent.CountDownLatch(1)
            dispatcher.post { hold.await() }
            var destroyed = 0L
            val late =
                dispatcher.onEngineBlocking(timeoutMillis = 50, onAbandoned = { value: Long ->
                    destroyed = value
                }) { 42L }
            assertTrue("the wait gives up", late.isFailure)
            hold.countDown()
            // The block still ran on the engine thread and its result was handed to the cleanup.
            dispatcher.onEngine { Unit }
            assertEquals(42L, destroyed)
        }
    }

    /**
     * A dictionary of one entry is a valid source (a user's own scheme package can ship one), so it
     * must compile and translate. Unpatched librime writes through a pointer into the mapped table
     * file after growing it, and the string table of such a small dictionary outgrows the size
     * estimated from the entry count: the deploy thread crashed with SIGSEGV in
     * Table::OnBuildFinish (guarded by native/patches/librime-table-build-remap.patch).
     */
    @Test
    fun aOneEntryDictionaryDeploysAndTranslates() {
        runBlocking {
            val root = File(instrumentation.targetContext.cacheDir, "engine-tiny-dictionary")
            root.deleteRecursively()
            val source = File(root, "source").apply { mkdirs() }
            val user = File(root, "user")
            File(source, "default.yaml").writeText("config_version: \"1\"\nschema_list:\n  - schema: tiny_test\n")
            File(source, "tiny_test.schema.yaml").writeText(
                "schema:\n  schema_id: tiny_test\n  name: Tiny Test\n  version: \"1\"\n" +
                    "engine:\n  processors: [speller, selector, navigator, express_editor]\n" +
                    "  segmentors: [abc_segmentor, fallback_segmentor]\n" +
                    "  translators: [table_translator]\n" +
                    "translator:\n  dictionary: tiny_test\n" +
                    "speller:\n  alphabet: \"abcdefghijklmnopqrstuvwxyz\"\n",
            )
            File(source, "tiny_test.dict.yaml").writeText(
                "---\nname: tiny_test\nversion: \"1\"\nsort: original\n...\n演示\tyanshi\n",
            )
            val deployment = RimeDeployment.deploy(source, user, RimeEngine.DEFAULT_APP_NAME, "test")
            assertTrue("deployment failed: " + deployment.lastError, deployment.success)
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = source,
                    userDataDir = user,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                    compiledDir = deployment.compiledDir,
                )
            try {
                for (char in "yanshi") engine.processKey(RimeKey(RimeKey.baseKeycode(char)!!))
                assertEquals(listOf("演示"), engine.readSnapshot().candidates.map { it.text })
            } finally {
                engine.close()
                root.deleteRecursively()
            }
        }
    }

    private fun engineThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == ENGINE_THREAD_NAME }

    /** Waits for the engine threads from a failed start to finish quitting. */
    private fun awaitEngineThreads(atMost: Int): Boolean {
        val deadline = System.currentTimeMillis() + ENGINE_THREAD_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (engineThreads() <= atMost) return true
            Thread.sleep(20L)
        }
        return engineThreads() <= atMost
    }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = assetPath + "/" + name
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input ->
                    child.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        private const val BUILTIN_ASSET_ROOT = "rime/builtin"
        private const val ENGINE_THREAD_NAME = "songyan-rime-engine"
        private const val ENGINE_THREAD_TIMEOUT_MILLIS = 2_000L
    }
}

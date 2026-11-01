package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.BUILTIN_ASSET_ROOT
import app.songyan.ime.rime.EngineTestSupport.copyAssetTree
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A snapshot read the bridge fails (an unreadable candidate, an allocation failure) used to leave
 * the engine refusing every later action, and the cancel meant to recover refused too: Chinese
 * input stayed dead until the process restarted. The failure is forced after the native read ran,
 * so the engine state is exactly what a real failed read leaves behind.
 */
@RunWith(AndroidJUnit4::class)
class RimeSnapshotRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aFailedReadAfterAKeyRecoversAtOnceAndInputGoesOn() =
        withEngine { engine ->
            type(engine, "ni")
            engine.failNextSnapshotReads(1)
            val failed = engine.processKey(RimeKey(RimeKey.baseKeycode('h')!!))
            // The composition is given up, and the caller sees that, not the stale one.
            assertFalse("the recovered state is not composing", failed.snapshot.isComposing)
            assertFalse(engine.requiresSnapshotRecovery())

            type(engine, "nihao")
            assertTrue("candidates come back", engine.readSnapshot().candidates.isNotEmpty())
            val committed = engine.commitComposition()
            assertTrue("a commit reaches the editor again", committed.snapshot.commitText.isNotEmpty())
        }

    @Test
    fun aCancelRecoversAnEngineWhoseImmediateRecoveryFailed() =
        withEngine { engine ->
            type(engine, "ni")
            // The read after the key and the recovery read both fail: the action is an error.
            engine.failNextSnapshotReads(2)
            val error = runCatching { engine.processKey(RimeKey(RimeKey.baseKeycode('h')!!)) }.exceptionOrNull()
            assertTrue("got " + error, (error as? RimeException)?.kind == RimeException.Kind.SNAPSHOT_UNAVAILABLE)
            assertTrue(engine.requiresSnapshotRecovery())

            // The keyboard discards the composition (selection moved, keyboard hidden): this must work.
            val cancelled = engine.cancelComposition()
            assertFalse(cancelled.isComposing)
            assertFalse(engine.requiresSnapshotRecovery())
            type(engine, "ni")
            assertTrue(engine.readSnapshot().isComposing)
        }

    @Test
    fun theNextKeyRecoversWhenNothingCancelled() =
        withEngine { engine ->
            type(engine, "ni")
            engine.failNextSnapshotReads(2)
            runCatching { engine.processKey(RimeKey(RimeKey.baseKeycode('h')!!)) }
            assertTrue(engine.requiresSnapshotRecovery())

            // No cancel in between: the next key recovers first, then is typed into a fresh composition.
            val next = engine.processKey(RimeKey(RimeKey.baseKeycode('n')!!))
            assertTrue(next.snapshot.isComposing)
            assertEquals("n", next.snapshot.input)
            assertFalse(engine.requiresSnapshotRecovery())
        }

    private fun withEngine(block: suspend (RimeEngine) -> Unit) =
        runBlocking {
            val root = File(context.cacheDir, "engine-snapshot-recovery")
            root.deleteRecursively()
            val source = File(root, "source")
            copyAssetTree(context.assets, BUILTIN_ASSET_ROOT, source)
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
                block(engine)
            } finally {
                engine.close()
                root.deleteRecursively()
            }
        }
}

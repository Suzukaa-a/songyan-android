package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.candidateTexts
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Raw hardware keys (keysym plus modifier mask) through the real engine's backend. */
@RunWith(AndroidJUnit4::class)
class RimeRawKeyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var shared: File
    private lateinit var user: File
    private lateinit var compiled: File

    @Before
    fun deployBuiltin() =
        runBlocking {
            root = File(instrumentation.targetContext.cacheDir, "raw-key").also { it.deleteRecursively() }
            shared = File(root, "shared")
            user = File(root, "user")
            AssetTrees.copy(instrumentation.targetContext.assets, EngineTestSupport.BUILTIN_ASSET_ROOT, shared)
            val deployment = RimeDeployment.deploy(shared, user, appVersion = "test")
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            compiled = deployment.compiledDir
        }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun lettersThenSpaceCommitTheFirstCandidate() =
        withBackend { backend ->
            for (char in "ni") {
                val result = backend.processRawKey(char.code, RimeModifier.NONE)
                assertTrue("'$char' is an engine key", result.consumed)
            }
            val first =
                backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            val result = backend.processRawKey(RimeKeyCode.SPACE, RimeModifier.NONE)
            assertTrue(result.consumed)
            assertEquals(first, result.snapshot.committed)
        }

    @Test
    fun aLetterReleaseIsNotConsumedAndChangesNothing() =
        withBackend { backend ->
            backend.processRawKey('n'.code, RimeModifier.NONE)
            val before = backend.currentSnapshot()
            val release = backend.processRawKey('n'.code, RimeModifier.RELEASE)
            assertFalse(release.consumed)
            assertEquals(before, release.snapshot)
        }

    @Test
    fun anIdleArrowIsNotConsumed() =
        withBackend { backend ->
            assertFalse(backend.processRawKey(RimeKeyCode.LEFT, RimeModifier.NONE).consumed)
        }

    @Test
    fun rawKeysDoNotLearnWhileLearningIsSuppressed() =
        runBlocking {
            val engine = RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = compiled)
            try {
                assertTrue(engine.selectSchema("songyan_quanpin"))
                val backend = RimeEngineBackend(engine)
                type(engine, "shi")
                val baseline = candidateTexts(engine)
                engine.clearComposition()
                assertTrue("need a lower-ranked candidate", baseline.size >= 3)
                assertTrue(backend.setLearningSuppressed(true))
                repeat(4) {
                    for (char in "shi") backend.processRawKey(char.code, RimeModifier.NONE)
                    backend.selectCandidate(2)
                    backend.processRawKey('a'.code, RimeModifier.NONE)
                    engine.clearComposition()
                }
                assertTrue(backend.setLearningSuppressed(false))
                type(engine, "shi")
                assertEquals(baseline, candidateTexts(engine))
            } finally {
                engine.close()
            }
        }

    private fun withBackend(block: suspend (RimeEngineBackend) -> Unit) =
        runBlocking {
            val engine = RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = compiled)
            try {
                assertTrue(engine.selectSchema("songyan_quanpin"))
                block(RimeEngineBackend(engine))
            } finally {
                engine.close()
            }
        }
}

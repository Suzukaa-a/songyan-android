package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.candidateTexts
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Learning changes what the engine ranks first: committing a lower-ranked candidate repeatedly
 * moves it up for the same input, and the move is still there when a new engine opens the same
 * user directory.
 */
@RunWith(AndroidJUnit4::class)
class RimeLearningRankingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var shared: File
    private lateinit var user: File
    private lateinit var compiled: File

    @Before
    fun deployBuiltin() =
        runBlocking {
            root = File(instrumentation.targetContext.cacheDir, "learning-ranking").also { it.deleteRecursively() }
            shared = File(root, "shared")
            user = File(root, "user")
            EngineTestSupport.copyAssetTree(
                instrumentation.targetContext.assets,
                EngineTestSupport.BUILTIN_ASSET_ROOT,
                shared,
            )
            val deployment = RimeDeployment.deploy(shared, user, appVersion = "test")
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            compiled = deployment.compiledDir
        }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aRepeatedlyChosenCandidateRanksHigherAndStaysThereAfterReopening() =
        runBlocking {
            var chosen = ""
            var baselineIndex = 0
            withEngine { engine ->
                val baseline = candidatesFor(engine, KEYS)
                assertTrue("need at least 3 candidates for '$KEYS': $baseline", baseline.size >= 3)
                baselineIndex = 2
                chosen = baseline[baselineIndex]
                var rank = baselineIndex
                var commits = 0
                // Learning weight accumulates per commit; stop as soon as the ranking moved.
                while (rank >= baselineIndex && commits < MAX_COMMITS) {
                    commitByText(engine, KEYS, chosen)
                    commits++
                    rank = candidatesFor(engine, KEYS).indexOf(chosen)
                }
                assertTrue(
                    "'$chosen' stayed at index $baselineIndex after $commits commits of '$KEYS'",
                    rank in 0 until baselineIndex,
                )
                baselineIndex = rank
            }

            // A new engine on the same user directory reads what the first one wrote.
            withEngine { engine ->
                val reopened = candidatesFor(engine, KEYS)
                val rank = reopened.indexOf(chosen)
                assertTrue(
                    "'$chosen' lost its learned rank after reopening: $reopened (was index $baselineIndex)",
                    rank in 0..baselineIndex,
                )
            }
        }

    @Test
    fun learningOneInputDoesNotReorderAnotherInput() =
        runBlocking {
            withEngine { engine ->
                val other = candidatesFor(engine, OTHER_KEYS)
                val chosen = candidatesFor(engine, KEYS)[2]
                repeat(3) { commitByText(engine, KEYS, chosen) }
                assertEquals(other, candidatesFor(engine, OTHER_KEYS))
            }
        }

    private suspend fun withEngine(block: suspend (RimeEngine) -> Unit) {
        val engine = RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = compiled)
        try {
            assertTrue(engine.selectSchema("songyan_quanpin"))
            block(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun candidatesFor(
        engine: RimeEngine,
        keys: String,
    ): List<String> {
        engine.clearComposition()
        type(engine, keys)
        val texts = candidateTexts(engine)
        engine.clearComposition()
        return texts
    }

    private suspend fun commitByText(
        engine: RimeEngine,
        keys: String,
        text: String,
    ) {
        engine.clearComposition()
        type(engine, keys)
        val index = candidateTexts(engine).indexOf(text)
        assertTrue("'$text' not on the first page for '$keys'", index >= 0)
        assertEquals(text, engine.selectCandidate(index)?.commitText)
        // librime writes the commit to the userdb when the next translation starts.
        type(engine, "a")
        engine.clearComposition()
    }

    private companion object {
        const val KEYS = "shi"
        const val OTHER_KEYS = "ni"
        const val MAX_COMMITS = 6
    }
}

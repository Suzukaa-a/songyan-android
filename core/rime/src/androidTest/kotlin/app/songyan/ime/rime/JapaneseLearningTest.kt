package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Kagiroi learns the word the user picks.
 *
 * With the real, pinned engine over a locally compiled kagiroi package, choosing "日本語" for
 * `nihongo` and typing `nihongo` again must rank it first, and a new engine opened on the same user
 * directory must still rank it first. That rank can coincide with the natural dictionary order, so
 * the test also reads the user dictionary librime itself exports: a learned entry for the word is
 * the evidence that the choice was memorized rather than merely displayed.
 *
 * The package is the same pushed ZIP the replay test uses (`-e kagiroiFixture <absolute path>`);
 * a missing package fails instead of skipping, and the engine and its learning data stay in this
 * test package's cache.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseLearningTest {
    @Test
    fun aChosenWordRanksFirstWhenTypedAgainAndStaysFirstAfterReopening() =
        runBlocking {
            val prepared = JapaneseSchemeFixture.prepare()
            try {
                val user = prepared.userDir("learning-user")
                withEngine(prepared, user) { engine ->
                    val baseline = candidatesFor(engine, KEYS)
                    assertTrue("no candidates for '$KEYS': $baseline", baseline.isNotEmpty())
                    val chosen = baseline.indexOf(WORD)
                    assertTrue("'$WORD' is not offered for '$KEYS': $baseline", chosen >= 0)
                    assertEquals(WORD, engine.selectCandidate(chosen)?.commitText)
                    // Repeating the input starts the next translation, which also flushes the commit
                    // into librime's user dictionary before the engine is closed.
                    val ranked = candidatesFor(engine, KEYS)
                    println("LEARNING\ttappedAgain=$ranked")
                    assertEquals(
                        "after choosing '$WORD', the next '$KEYS' does not rank it first: $ranked",
                        WORD,
                        ranked.firstOrNull(),
                    )
                }

                // A new engine on the same user directory reads what the first one wrote.
                withEngine(prepared, user) { engine ->
                    val reopened = candidatesFor(engine, KEYS)
                    println("LEARNING\tafterReopen=$reopened")
                    assertEquals(
                        "'$WORD' lost its first rank after reopening the same user directory: $reopened",
                        WORD,
                        reopened.firstOrNull(),
                    )
                    // The rank alone can be satisfied by the natural dictionary order, so read
                    // librime's own user-dictionary export as the learning evidence.
                    val exported =
                        engine.exportUserDictSnapshots()
                            ?: throw AssertionError("the engine exported no user dictionary after learning '$WORD'")
                    assertTrue("the engine exported no user dictionary snapshot", exported.isNotEmpty())
                    val learned = exported.map { RimeUserDbText.inspect(it) }.filter { it.entryCount > 0 }
                    assertTrue(
                        "the exported user dictionaries carry no learned entries: " +
                            exported.map { it.name },
                        learned.isNotEmpty(),
                    )
                    val rows = learned.joinToString("\n") { it.file.readText() }
                    println("LEARNING\tuserdb=${learned.map { it.dictionary }}")
                    assertTrue(
                        "the exported user dictionary does not contain '$WORD': $rows",
                        rows.contains(WORD),
                    )
                }
            } finally {
                JapaneseSchemeFixture.cleanup(prepared)
            }
        }

    private suspend fun withEngine(
        prepared: JapaneseSchemeFixture.Prepared,
        user: File,
        block: suspend (RimeEngine) -> Unit,
    ) {
        val engine = JapaneseSchemeFixture.openEngine(prepared, user)
        try {
            JapaneseSchemeFixture.selectKagiroi(engine)
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
        for (char in keys) {
            engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
        }
        return engine.readSnapshot().candidates.map { it.text }
    }

    private companion object {
        const val KEYS = "nihongo"
        const val WORD = "日本語"
    }
}

package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.kana12.Kana12RomajiTable
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J2.5 acceptance: the compiled kana -> romaji table of the twelve-key keyboard, replayed against
 * the real, pinned engine and the locally compiled kagiroi package.
 *
 * The table is the single source the keyboard spells with ([Kana12RomajiTable]); it promises that
 * every surface it lists types alone as itself and that every ordered pair of surfaces types as the
 * two surfaces concatenated, so two adjacent kana are never split differently by the speller. This
 * test proves both promises on the device: every one of the 82 entries alone, then all 82 x 82 =
 * 6724 ordered pairs. Nothing is sampled, reduced or stubbed; a mismatch names the keys, the
 * preedit the engine produced and the preedit the table promises. Any failure here is a table defect
 * (fix the table, never the engine).
 *
 * One engine is opened per test method and every case is fed on it in order; librime is a
 * process-wide singleton and [RimeEngine] serializes every call on its engine thread, so the cases
 * never race each other. The compiled package is the same pushed ZIP the Japanese replay test uses
 * (`-e kagiroiFixture <absolute path>`); a missing package fails instead of skipping.
 */
@RunWith(AndroidJUnit4::class)
class KanaRomajiTableTest {
    @Test
    fun everyEntryTypesItsOwnKana() =
        runBlocking {
            val entries = Kana12RomajiTable.entries
            assertEquals("the compiled table no longer carries the 82 verified surfaces", 82, entries.size)
            withEngine("singles") { engine ->
                val failures = ArrayList<String>()
                var passed = 0
                for (entry in entries) {
                    engine.clearComposition()
                    val actual = preeditFor(engine, entry.romaji)
                    if (actual == entry.kana) {
                        passed++
                    } else {
                        failures += "'" + entry.romaji + "' -> " + quote(actual) + ", expected " + quote(entry.kana)
                    }
                }
                report("singles", entries.size, passed, failures)
            }
        }

    @Test
    fun everyOrderedPairTypesBothKanasInOrder() =
        runBlocking {
            val entries = Kana12RomajiTable.entries
            val expectedPairs = entries.size * entries.size
            assertEquals("the table's ordered pair count changed", 6724, expectedPairs)
            withEngine("pairs") { engine ->
                val failures = ArrayList<String>()
                var checked = 0
                var passed = 0
                for (first in entries) {
                    for (second in entries) {
                        checked++
                        val expected = first.kana + second.kana
                        val keys = first.romaji + second.romaji
                        engine.clearComposition()
                        val actual = preeditFor(engine, keys)
                        if (actual == expected) {
                            passed++
                        } else if (failures.size < MAX_REPORTED_FAILURES) {
                            failures +=
                                "'" + keys + "' (" + first.kana + " + " + second.kana + ") -> " +
                                quote(actual) + ", expected " + quote(expected)
                        }
                        if (checked % PROGRESS_EVERY == 0) {
                            println("KANA_TABLE\tpairs\tchecked=" + checked + "\tpassed=" + passed)
                        }
                    }
                }
                report("pairs", expectedPairs, passed, failures)
            }
        }

    /**
     * Types [keys] as one engine key sequence and returns the preedit the real engine shows.
     *
     * The sequence is fed through librime's own key-sequence input, which presses every character in
     * order through the same key pipeline a single press takes; reading the state once per case keeps
     * all 6724 real cases feasible. Null means the engine refused to parse or consume the sequence.
     */
    private suspend fun preeditFor(
        engine: RimeEngine,
        keys: String,
    ): String? {
        val result = engine.processKeySequence(keys)
        if (!result.consumed) return null
        return result.snapshot.preedit
    }

    private suspend fun <T> withEngine(
        name: String,
        block: suspend (RimeEngine) -> T,
    ): T {
        val engine = JapaneseSchemeFixture.openEngine(prepared, prepared.userDir("kana-table-" + name))
        try {
            JapaneseSchemeFixture.selectKagiroi(engine)
            return block(engine)
        } finally {
            engine.close()
        }
    }

    private fun report(
        kind: String,
        total: Int,
        passed: Int,
        failures: List<String>,
    ) {
        println("KANA_TABLE\t" + kind + "\ttotal=" + total + "\tpassed=" + passed + "\tfailed=" + failures.size)
        assertEquals(
            "the " + kind + " table acceptance failed on " + (total - passed) + " of " + total +
                " cases through the real kagiroi engine:\n" + failures.joinToString("\n"),
            0,
            total - passed,
        )
    }

    private fun quote(value: String?): String = if (value == null) "<refused>" else "'" + value + "'"

    companion object {
        private const val MAX_REPORTED_FAILURES = 40
        private const val PROGRESS_EVERY = 500

        private lateinit var prepared: JapaneseSchemeFixture.Prepared

        @BeforeClass
        @JvmStatic
        fun extractThePushedPackage() {
            prepared = JapaneseSchemeFixture.prepare()
        }

        @AfterClass
        @JvmStatic
        fun removeTheExtractedPackage() {
            if (::prepared.isInitialized) JapaneseSchemeFixture.cleanup(prepared)
        }
    }
}

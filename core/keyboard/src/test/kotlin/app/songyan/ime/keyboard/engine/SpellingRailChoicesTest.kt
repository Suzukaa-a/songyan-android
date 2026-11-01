package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.display.ReadingInputCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from KeyboardCoreTests.SpellingRailChoicesTests. */
class SpellingRailChoicesTest {
    private fun report(
        input: String = "64",
        mode: AmbiguityLockMode = AmbiguityLockMode.TOKEN_ALIAS,
        complete: Boolean = true,
        segment: IntRange = 0 until 2,
    ) = AmbiguitySpellingsReport(
        revisionInput = input,
        segment = segment,
        options = listOf("mi", "ni"),
        allSpellings = listOf("mi", "ni", "display-only"),
        lockMode = mode,
        isComplete = complete,
    )

    private fun readings(revision: Int = 7): CandidateReadingFilter {
        val filter = CandidateReadingFilter()
        filter.reset(revision)
        filter.append(
            listOf(
                ReadingCandidate.of(0, "你", syllables = listOf("ni")),
                ReadingCandidate.of(1, "米", syllables = listOf("mi")),
                // The engine proves no path for an unclassified candidate (a guessed reading never reaches the model).
                ReadingCandidate.of(2, "拟", readingsComplete = false),
                ReadingCandidate.of(3, "你好", syllables = listOf("ni", "hao")),
            ),
            requested = 4,
            revision = revision,
        )
        return filter
    }

    private fun resolve(
        report: AmbiguitySpellingsReport? = null,
        eligible: Boolean = true,
        input: String = "64",
        revision: Int = 7,
        reportRevision: Int = 7,
        filter: CandidateReadingFilter = readings(),
    ) = SpellingRailChoices.resolve(
        eligible = eligible,
        input = input,
        revision = revision,
        report = report,
        reportRevision = reportRevision,
        readings = filter,
    )

    @Test
    fun onlyEngineLockableChoicesAppearOnce() {
        val choices = resolve(report())
        assertEquals(listOf("mi", "ni"), choices.lockOptions)
        assertTrue(choices.readings.isEmpty())
        assertFalse(choices.canUndoLock)
    }

    @Test
    fun realReadingsWorkWithoutRewriteCapability() {
        val choices = resolve(report(mode = AmbiguityLockMode.NONE))
        assertTrue(choices.lockOptions.isEmpty())
        assertEquals(listOf("ni", "mi"), choices.readings)
    }

    @Test
    fun unsupportedOrIdleContextShowsNoChoicesEvenWithCachedEvidence() {
        assertTrue(resolve(report(), eligible = false).isEmpty)
        assertTrue(resolve(report(), input = "").isEmpty)
        assertTrue(resolve(filter = CandidateReadingFilter()).isEmpty)
    }

    @Test
    fun staleEvidenceCannotCreateSelectableChoices() {
        val old = readings(revision = 6)
        assertTrue(resolve(report(), reportRevision = 6, filter = old).isEmpty)
        assertTrue(resolve(report(input = "65"), filter = old).isEmpty)
    }

    @Test
    fun incompleteAndInvalidReportsDoNotCreateLocks() {
        assertTrue(resolve(report(complete = false)).lockOptions.isEmpty())
        assertTrue(resolve(report(segment = 0 until 3)).lockOptions.isEmpty())
        assertTrue(resolve(report(segment = 1 until 1)).lockOptions.isEmpty())
    }

    @Test
    fun selectionFiltersRealCandidatesAndUndoRestoresTheirOriginalOrder() {
        val filter = readings()
        filter.select("ni")
        var choices = resolve(filter = filter)
        assertTrue(choices.isFiltering)
        assertEquals(listOf("ni"), choices.readingPrefix)
        assertEquals(listOf("hao"), choices.readings)
        assertEquals(listOf(0, 3), filter.matches.map { it.index })
        filter.select("guessed")
        assertEquals(listOf("ni"), filter.prefix)
        filter.undoSelection()
        choices = resolve(filter = filter)
        assertFalse(choices.isFiltering)
        assertEquals(listOf("ni", "mi"), choices.readings)
        assertEquals(listOf(0, 1, 2, 3), filter.matches.map { it.index })
    }

    @Test
    fun typedNineKeyCoverageDoesNotOfferUntypedCompletionSyllables() {
        val keys = nineKeyMap()
        val filter = readings()
        filter.setCoverage(ReadingInputCoverage.of("64", keys))
        assertEquals(listOf("ni", "mi"), resolve(filter = filter).readings)
        filter.select("ni")
        assertTrue(resolve(filter = filter).readings.isEmpty())
        assertEquals(listOf(0, 3), filter.matches.map { it.index })
    }

    @Test
    fun lockRemainsVisibleForUndoOnlyWhileRewrittenInputMatches() {
        val choices =
            SpellingRailChoices.resolve(
                eligible = true,
                input = "ni",
                revision = 8,
                report = null,
                reportRevision = 7,
                lockedOption = "ni",
                lockedInput = "ni",
                readings = readings(),
            )
        assertTrue(choices.canUndoLock)
        assertEquals("ni", choices.lockedOption)
        assertTrue(choices.lockOptions.isEmpty())
        assertTrue(choices.readings.isEmpty())
        val stale =
            SpellingRailChoices.resolve(
                eligible = true,
                input = "ni4",
                revision = 9,
                report = null,
                reportRevision = 7,
                lockedOption = "ni",
                lockedInput = "ni",
                readings = readings(),
            )
        assertTrue(stale.isEmpty)
    }

    companion object {
        fun nineKeyMap(): Map<Char, Char> {
            val map = HashMap<Char, Char>()
            val groups = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
            "23456789".toList().zip(groups).forEach { (digit, letters) -> letters.forEach { map[it] = digit } }
            return map
        }
    }
}

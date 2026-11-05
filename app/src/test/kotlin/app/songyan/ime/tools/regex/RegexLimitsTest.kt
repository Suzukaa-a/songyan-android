package app.songyan.ime.tools.regex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The caps are checked before anything is sent, so an oversized text is refused as too long and can
 * never be reported as a deadline, which would hide a real one.
 */
class RegexLimitsTest {
    @Test
    fun theDefaultInputLimitIsAThousandCharacters() {
        val limits = RegexLimits()
        assertNull(limits.rejectionOf("a", "a".repeat(1000), "-"))
        assertEquals(RegexRun.TooLong(RegexField.INPUT), limits.rejectionOf("a", "a".repeat(1001), "-"))
    }

    @Test
    fun aTestThatMustReallyMatchMayRaiseTheInputLimit() {
        val limits = RegexLimits(input = 6000)
        assertNull("5001 characters must reach the matcher", limits.rejectionOf("a", "a".repeat(5001), "-"))
        assertNull(limits.rejectionOf("a", "a".repeat(6000), "-"))
        assertEquals(RegexRun.TooLong(RegexField.INPUT), limits.rejectionOf("a", "a".repeat(6001), "-"))
    }

    @Test
    fun thePatternAndTheReplacementAreCappedToo() {
        val limits = RegexLimits(pattern = 16, input = 16, replacement = 4)
        assertEquals(RegexRun.TooLong(RegexField.PATTERN), limits.rejectionOf("a".repeat(17), "a", "-"))
        assertEquals(RegexRun.TooLong(RegexField.REPLACEMENT), limits.rejectionOf("a", "a", "-----"))
        assertNull("no replacement is not a too long replacement", limits.rejectionOf("a", "a", null))
    }

    @Test
    fun aLimitCountsCharactersNotBytes() {
        val limits = RegexLimits(input = 10)
        assertNull(limits.rejectionOf("a", "\u7e41".repeat(10), "-"))
        assertEquals(RegexRun.TooLong(RegexField.INPUT), limits.rejectionOf("a", "\u7e41".repeat(11), "-"))
    }
}

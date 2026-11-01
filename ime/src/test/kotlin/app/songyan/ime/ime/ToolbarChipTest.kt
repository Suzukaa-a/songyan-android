package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarChipTest {
    private val ttl = 60_000L

    private fun clip(
        timestamp: Long,
        sensitive: Boolean = false,
        text: Boolean = true,
        available: Boolean = true,
    ) = ClipObservation(available, text, sensitive, timestamp)

    private fun policy() = ToolbarChipPolicy(ttl).also { it.setAllowed(true) }

    private fun ToolbarChipPolicy.take(
        observation: ClipObservation,
        text: String?,
        now: Long,
    ): ToolbarChip? = if (shouldRead(observation, now)) offer(observation, text, now) else null

    @Test
    fun aFreshTextClipBecomesACopiedChip() {
        val chip = policy().take(clip(1_000), "hello", now = 2_000)
        assertEquals(ToolbarChipKind.COPIED, chip?.kind)
        assertEquals("hello", chip?.text)
        assertEquals(1_000 + ttl, chip?.expiresAt)
    }

    @Test
    fun theChipExpiresSixtySecondsAfterTheClipWasSet() {
        val p = policy()
        p.take(clip(1_000), "hello", now = 5_000)
        assertNotNull(p.current(now = 1_000 + ttl - 1))
        assertNull(p.current(now = 1_000 + ttl))
        assertNull(p.current(now = 1_000 + ttl + 5))
    }

    @Test
    fun aClipOlderThanTheLifetimeIsNeverRead() {
        assertFalse(policy().shouldRead(clip(1_000), now = 1_000 + ttl + 1))
    }

    @Test
    fun aClockBehindTheClipDoesNotStretchTheLifetime() {
        val chip = policy().take(clip(10_000), "x", now = 4_000)
        assertEquals(4_000 + ttl, chip?.expiresAt)
    }

    @Test
    fun aSensitiveClipIsNeverReadAndDropsTheOlderChip() {
        val p = policy()
        p.take(clip(1_000), "old", now = 2_000)
        assertFalse(p.shouldRead(clip(3_000, sensitive = true), now = 3_500))
        assertNull(p.current(now = 3_500))
    }

    @Test
    fun nonTextAndEmptyClipboardsGiveNoChip() {
        val p = policy()
        assertFalse(p.shouldRead(clip(1_000, text = false), 1_500))
        assertFalse(p.shouldRead(clip(0, available = false), 1_500))
        assertFalse(p.shouldRead(clip(0), 1_500))
    }

    @Test
    fun aPrivateFieldNeverReadsAndDropsTheChip() {
        val p = policy()
        p.take(clip(1_000), "hello", now = 2_000)
        p.setAllowed(false)
        assertNull(p.current(2_500))
        assertFalse(p.shouldRead(clip(3_000), 3_500))
    }

    @Test
    fun aClipSkippedInAPrivateFieldIsOfferedInTheNextNormalOne() {
        val p = policy()
        p.setAllowed(false)
        assertFalse(p.shouldRead(clip(1_000), 2_000))
        p.setAllowed(true)
        assertNotNull(p.take(clip(1_000), "hello", 3_000))
    }

    @Test
    fun aUsedClipIsNotOfferedAgain() {
        val p = policy()
        p.take(clip(1_000), "hello", 2_000)
        p.used()
        assertNull(p.current(2_100))
        assertFalse(p.shouldRead(clip(1_000), 2_200))
        // A new copy is a new clip.
        assertNotNull(p.take(clip(3_000), "again", 3_100))
    }

    @Test
    fun aDismissedClipIsNotOfferedAgainEvenAfterTheKeyboardReshows() {
        val p = policy()
        p.take(clip(1_000), "hello", 2_000)
        p.dismiss()
        p.setAllowed(false)
        p.setAllowed(true)
        assertFalse(p.shouldRead(clip(1_000), 5_000))
    }

    @Test
    fun theSameClipSeenTwiceIsReadOnce() {
        val p = policy()
        assertNotNull(p.take(clip(1_000), "hello", 2_000))
        assertFalse(p.shouldRead(clip(1_000), 2_100))
    }

    @Test
    fun blankAndHugeTextGiveNoChipAndAreNotReadTwice() {
        val p = policy()
        assertNull(p.take(clip(1_000), "  \n ", 2_000))
        assertFalse(p.shouldRead(clip(1_000), 2_100))
        assertNull(p.take(clip(2_000), "x".repeat(ToolbarChipPolicy.MAX_CHARS + 1), 2_100))
        assertNotNull(p.take(clip(3_000), "x".repeat(ToolbarChipPolicy.MAX_CHARS), 3_100))
    }

    @Test
    fun theChipTextIsTrimmedButOtherwiseWhatIsPasted() {
        assertEquals("a\nb", policy().take(clip(1), " a\nb \n", 2)?.text)
    }

    @Test
    fun aCodeMessageBecomesACodeChipHoldingOnlyTheCode() {
        val chip = policy().take(clip(1_000), "【松烟】验证码 482915，5分钟内有效，请勿告知他人。", 2_000)
        assertEquals(ToolbarChipKind.CODE, chip?.kind)
        assertEquals("482915", chip?.text)
    }

    // The rule for reading a code out of copied text.

    @Test
    fun codesAreFoundNextToACodeWord() {
        assertEquals("123456", ClipCodeRule.extract("Your verification code is 123456."))
        assertEquals("8841", ClipCodeRule.extract("Use code 8841 to sign in"))
        assertEquals("654321", ClipCodeRule.extract("验证码：654321（10分钟内有效）"))
        assertEquals("654321", ClipCodeRule.extract("驗證碼654321"))
        assertEquals("1234", ClipCodeRule.extract("Your PIN: 1234"))
        assertEquals("906123", ClipCodeRule.extract("Your OTP is 906123"))
    }

    @Test
    fun aNumberWithoutACodeWordIsNotACode() {
        assertNull(ClipCodeRule.extract("123456"))
        assertNull(ClipCodeRule.extract("Meet at 4pm, room 2040"))
    }

    @Test
    fun aCodeWordWithoutAStandaloneNumberIsNotACode() {
        assertNull(ClipCodeRule.extract("Your verification code will arrive soon"))
        // Too short, too long, phone numbers, amounts, versions, ids glued to letters.
        assertNull(ClipCodeRule.extract("code 123"))
        assertNull(ClipCodeRule.extract("code 123456789"))
        assertNull(ClipCodeRule.extract("验证码请拨打 13800138000"))
        assertNull(ClipCodeRule.extract("code costs \$1234"))
        assertNull(ClipCodeRule.extract("code version 3.1415"))
        assertNull(ClipCodeRule.extract("code A12345B"))
        assertNull(ClipCodeRule.extract("code 2024-05-01"))
    }

    @Test
    fun twoDifferentNumbersAreAmbiguousAndGiveNoCode() {
        assertNull(ClipCodeRule.extract("code 123456, order 654321"))
        assertEquals("123456", ClipCodeRule.extract("code 123456, repeat: 123456"))
    }

    @Test
    fun longTextIsNeverACodeMessage() {
        assertNull(ClipCodeRule.extract("code 123456 " + "x".repeat(ClipCodeRule.MAX_TEXT)))
    }

    // The chip's text.

    @Test
    fun theLabelIsOneLineAndTruncatedWithAnEllipsis() {
        val chip = ToolbarChip(ToolbarChipKind.COPIED, "line one\n\n  line two", 1, 2)
        assertEquals("line one line two", chip.label())
        val long = ToolbarChip(ToolbarChipKind.COPIED, "a".repeat(100), 1, 2)
        assertEquals(10, long.label(10).length)
        assertTrue(long.label(10).endsWith("…"))
        assertEquals("a".repeat(100), long.text)
    }

    @Test
    fun theLabelNeverSplitsASurrogatePair() {
        val chip = ToolbarChip(ToolbarChipKind.COPIED, "ab" + "😀".repeat(10), 1, 2)
        val label = chip.label(4)
        assertFalse(Character.isHighSurrogate(label[label.length - 2]))
    }

    // What the band shows.

    @Test
    fun theBandChoosesCandidatesThenSuggestionsThenTheChipThenTheToolbar() {
        assertEquals(BandContent.CANDIDATES, BandContent.of(idle = false, hasInlineSuggestions = true, hasChip = true))
        assertEquals(BandContent.INLINE_SUGGESTIONS, BandContent.of(true, hasInlineSuggestions = true, hasChip = true))
        assertEquals(BandContent.CHIP, BandContent.of(true, hasInlineSuggestions = false, hasChip = true))
        assertEquals(BandContent.TOOLBAR, BandContent.of(true, hasInlineSuggestions = false, hasChip = false))
    }

    // Where the chip and its close button are.

    @Test
    fun theChipHugsItsTextInTheMiddleAndTheCloseButtonSitsAtTheEnd() {
        val g = ToolbarChipGeometry.of(width = 1000, height = 120, contentWidth = 300, marginPx = 20, dismissPx = 120)
        assertEquals(350, g.chipLeft)
        assertEquals(650, g.chipRight)
        assertEquals(860, g.dismissLeft)
        assertEquals(980, g.dismissRight)
        assertEquals(0, g.hit(500f, 60f))
        assertEquals(1, g.hit(900f, 60f))
        assertEquals(-1, g.hit(100f, 60f))
        assertEquals(-1, g.hit(500f, 130f))
    }

    @Test
    fun aWideChipTakesAtMostHalfTheBar() {
        val g = ToolbarChipGeometry.of(width = 1000, height = 100, contentWidth = 5_000, marginPx = 20, dismissPx = 100)
        assertEquals(500, g.chipRight - g.chipLeft)
        assertEquals(250, g.chipLeft)
        assertEquals(1000 - 20, g.dismissRight)
    }

    @Test
    fun onANarrowBarTheChipStaysClearOfTheCloseButton() {
        val g = ToolbarChipGeometry.of(width = 300, height = 100, contentWidth = 5_000, marginPx = 20, dismissPx = 100)
        assertTrue(g.chipRight <= g.dismissLeft)
    }
}

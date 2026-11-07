package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutPageTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMotionTest {
    private val normal = 1f
    private val half = 0.5f
    private val off = 0f

    @Test
    fun aKeyIsPressedImmediatelyWithoutEasingIn() {
        assertEquals(0.96f, KeyMotion.PRESSED_SCALE, 0f)
        assertEquals(KeyMotion.PRESSED_SCALE, KeyMotion.pressScale(), 0f)
    }

    @Test
    fun releaseSpringStartsPressedAndSettlesAtFullSizeWithoutOvershoot() {
        assertEquals(KeyMotion.PRESSED_SCALE, KeyMotion.releaseScale(5_000L, 5_000L, normal), 0.001f)
        var previous = KeyMotion.PRESSED_SCALE
        for (elapsed in 1..KeyMotion.releaseSettleMs + 50) {
            val scale = KeyMotion.releaseScale(5_000L + elapsed, 5_000L, normal)
            assertTrue("critically damped: never past 1 ($scale)", scale <= 1.0001f)
            assertTrue("monotone ($scale < $previous)", scale >= previous - 0.0001f)
            previous = scale
        }
        assertEquals(1f, previous, 0.003f)
        assertEquals(1f, KeyMotion.releaseScale(5_000L, 0L, normal), 0.001f)
    }

    @Test
    fun theReleaseSpringSettlesWithinAFewFrames() {
        assertTrue(KeyMotion.releaseSettleMs in 60L..250L)
        assertTrue(KeyMotion.releaseAnimating(5_010L, 5_000L, normal))
        assertFalse(KeyMotion.releaseAnimating(5_000L + KeyMotion.releaseSettleMs + 130L, 5_000L, normal))
    }

    @Test
    fun theReleaseColourFadesOutOver120MsDecelerating() {
        assertEquals(1f, KeyMotion.releaseColor(5_000L, 5_000L, normal), 0.001f)
        assertEquals(0f, KeyMotion.releaseColor(5_000L + KeyMotion.RELEASE_COLOR_MS, 5_000L, normal), 0.001f)
        assertEquals(120L, KeyMotion.RELEASE_COLOR_MS)
        // Ease-out: most of the change happens early.
        assertTrue(KeyMotion.releaseColor(5_060L, 5_000L, normal) < 0.3f)
    }

    @Test
    fun durationScaleZeroJumpsToTheFinalState() {
        // Animator duration scale 0 (remove animations) means no frame loop: final states only.
        assertEquals(1f, KeyMotion.releaseScale(1_000L, 1_000L, off), 0.001f)
        assertEquals(0f, KeyMotion.releaseColor(1_000L, 1_000L, off), 0.001f)
        assertFalse(KeyMotion.releaseAnimating(1_000L, 1_000L, off))
        assertEquals(1f, KeyMotion.popupOpenValue(1_000L, 1_000L, off), 0.001f)
        assertEquals(0f, KeyMotion.popupCloseProgress(1_000L, 1_000L, off), 0.001f)
    }

    @Test
    fun aNonZeroDurationScaleScalesTheTime() {
        // Half scale reaches the same spring point in half the time.
        assertEquals(
            KeyMotion.releaseScale(1_000L + 40L, 1_000L, normal),
            KeyMotion.releaseScale(1_000L + 20L, 1_000L, half),
            0.001f,
        )
        assertEquals(
            KeyMotion.popupOpenValue(1_000L + 100L, 1_000L, normal),
            KeyMotion.popupOpenValue(1_000L + 50L, 1_000L, half),
            0.001f,
        )
        assertEquals(40f, KeyMotion.duration(KeyMotion.POPUP_CLOSE_MS, half), 0.001f)
    }

    @Test
    fun theUnderDampedPopupSpringOvershootsALittleAndSettles() {
        assertEquals(KeyMotion.POPUP_START_SCALE, KeyMotion.popupScale(0f), 0.001f)
        assertEquals(1f, KeyMotion.popupScale(1f), 0.001f)
        var peak = 0f
        for (elapsed in 0..400L) peak = maxOf(peak, KeyMotion.popupOpenValue(1_000L + elapsed, 1_000L, normal))
        assertTrue("overshoots 1 ($peak)", peak > 1.001f)
        assertTrue("but only slightly ($peak)", peak < 1.1f)
        assertEquals(1f, KeyMotion.popupOpenValue(1_000L + KeyMotion.popupSettleMs, 1_000L, normal), 0.01f)
        assertEquals(0f, KeyMotion.popupAlpha(0f), 0.001f)
        assertEquals(1f, KeyMotion.popupAlpha(1f), 0.001f)
    }

    @Test
    fun popupClosesIn80MsAndTheHighlightCrossFadesIn50() {
        assertEquals(1f, KeyMotion.popupCloseAlpha(0f), 0.001f)
        assertEquals(0f, KeyMotion.popupCloseAlpha(1f), 0.001f)
        assertEquals(1f, KeyMotion.popupCloseScale(0f), 0.001f)
        assertTrue(KeyMotion.popupCloseScale(1f) < 1f)
        assertEquals(1f, KeyMotion.popupCloseProgress(1_000L + 80L, 1_000L, normal), 0.001f)
        assertEquals(1f, KeyMotion.highlightProgress(1_000L + 50L, 1_000L, normal), 0.001f)
        assertEquals(0.5f, KeyMotion.highlightProgress(1_000L + 25L, 1_000L, normal), 0.001f)
        assertEquals(40L, KeyMotion.PREVIEW_HOLD_MS)
    }

    @Test
    fun theSpringStepResponseMatchesTheClosedForm() {
        val critical = KeySpring(stiffness = 1500f, dampingRatio = 1f)
        assertEquals(0f, critical.step(0f), 0f)
        // x(t) = 1 - (1 + wt) e^(-wt) with w = sqrt(1500).
        val w = Math.sqrt(1500.0)
        val t = 0.05
        assertEquals((1 - (1 + w * t) * Math.exp(-w * t)).toFloat(), critical.step(0.05f), 0.0005f)
    }
}

class PopupSelectionTest {
    // Four 48 px cells above a key whose press began at the centre of the first cell (density 1).
    private val popup = KeyBounds(100f, 100f, 292f, 152f)
    private val originX = 124f
    private val originY = 180f

    private fun index(
        x: Float,
        y: Float = originY,
        current: Int = 0,
        mirrored: Boolean = false,
        origin: Float = originX,
    ): Int = PopupSelection.index(popup, 4, origin, originY, x, y, current, 1f, mirrored)

    @Test
    fun restingOrWobblingOnTheKeyKeepsTheFirstAlternate() {
        assertEquals(0, index(originX))
        assertEquals(0, index(originX + 11f, originY - 30f))
        assertEquals(0, index(originX - 11f, originY + 20f))
    }

    @Test
    fun slidingAlongTheKeyRowSelectsByTravelWithoutReachingThePopup() {
        assertEquals(1, index(originX + 48f))
        assertEquals(2, index(originX + 96f))
        assertEquals(3, index(originX + 144f))
        assertEquals("inside the popup works the same", 2, index(originX + 96f, 120f))
        assertEquals("above the popup too", 2, index(originX + 96f, 60f))
    }

    @Test
    fun aBoundaryNeedsAMarginToCross() {
        // The boundary between cells 0 and 1 is 24 px from the origin.
        assertEquals(0, index(originX + 30f, current = 0))
        assertEquals(1, index(originX + 33f, current = 0))
        assertEquals(1, index(originX + 18f, current = 1))
        assertEquals(0, index(originX + 15f, current = 1))
    }

    @Test
    fun overshootingTheEndsSticksToTheLastCellUntilTheCancelMargin() {
        assertEquals(3, index(292f + 20f))
        assertEquals(-1, index(292f + 30f))
        assertEquals(0, index(100f - 20f, current = 0))
        assertEquals(-1, index(100f - 30f))
    }

    @Test
    fun aClearPullDownCancelsAndComingBackRestoresTheFirstAlternate() {
        assertEquals(1, index(originX + 48f, originY + 60f, current = 1))
        assertEquals(-1, index(originX + 48f, originY + 72f, current = 1))
        assertEquals(0, index(originX, current = -1))
    }

    @Test
    fun aMirroredPopupCountsTravelLeftwardsFromTheRightmostCell() {
        val origin = 268f
        assertEquals(0, index(origin, mirrored = true, origin = origin))
        assertEquals(1, index(origin - 48f, mirrored = true, origin = origin))
        assertEquals(3, index(origin - 144f, mirrored = true, origin = origin))
    }

    @Test
    fun anEmptyAlternateListSelectsNothing() {
        assertEquals(-1, PopupSelection.index(popup, 0, originX, originY, originX, originY, 0, 1f))
    }
}

class KeyIdentityTest {
    @Test
    fun relabellingKeepsTheIdentity() {
        val keys = listOf("q", "w", "e").map { LayoutKey(it, action = LayoutAction.code(it)) }
        val before = KeyIdentity.list(keys, emptySet(), listOf("q", "w", "e"))
        // Shift only changes the drawn face, which is a separate label list.
        val after = KeyIdentity.list(keys, emptySet(), listOf("Q", "W", "E"))
        assertEquals(before, after)
        assertEquals(0, KeyIdentity.index(after)[before[0]])
    }

    @Test
    fun aPageSwitchMakesHeldIdentitiesUnresolvable() {
        val letters = listOf("q", "w").map { LayoutKey(it, action = LayoutAction.code(it)) }
        val numeric = listOf("1", "2").map { LayoutKey(it, action = LayoutAction.literal(it)) }
        val held = KeyIdentity.list(letters, emptySet(), listOf("q", "w"))
        val page = KeyIdentity.index(KeyIdentity.list(numeric, emptySet(), listOf("1", "2")))
        for (identity in held) assertNull(identity, page[identity])
    }

    @Test
    fun railChoicesSnapshotByLabel() {
        val keys =
            listOf("ni", "hao").map { word ->
                LayoutKey(word, action = LayoutAction.literal(word))
            }
        val first = KeyIdentity.list(keys, setOf(0, 1), listOf("ni", "hao"))
        val second = KeyIdentity.list(keys, setOf(0, 1), listOf("ni", "he"))
        val index = KeyIdentity.index(second)
        assertEquals(first[0], second[0])
        assertEquals(0, index[first[0]])
        assertNull(index[first[1]])
    }

    /**
     * The nine-key rollover: starting a composition replaces the leading-rail symbols with reading
     * words, and a shorter rail list shifts every grid slot. A held digit must be re-resolved by
     * its own identity, not by the index it had when the finger went down.
     */
    @Test
    fun aHeldGridKeyIsResolvedAgainAfterTheRailShrinks() {
        val digits = (1..9).map { LayoutKey(it.toString(), action = LayoutAction.code(it.toString())) }
        val beforeRail = listOf("，", "。", "？").map { LayoutKey(it, action = LayoutAction.literal(it)) }
        val before = beforeRail + digits
        val beforeIdentities = KeyIdentity.list(before, setOf(0, 1, 2), listOf("，", "。", "？"))
        val heldIndex = 3 + 4
        val heldIdentity = beforeIdentities[heldIndex]

        val afterRail = listOf("ni hao").map { LayoutKey(it, action = LayoutAction.literal(it)) }
        val after = afterRail + digits
        val afterIdentities = KeyIdentity.list(after, setOf(0), listOf("ni hao"))
        assertEquals("the digit keeps one identity", heldIdentity, afterIdentities[1 + 4])
        assertEquals(1 + 4, KeyIdentity.index(afterIdentities)[heldIdentity])
        assertNull(KeyIdentity.index(afterIdentities)[beforeIdentities[0]])
    }

    @Test
    fun duplicateActionsGetDistinctIdentities() {
        val keys = List(2) { LayoutKey("。", action = LayoutAction.literal("。")) }
        val identities = KeyIdentity.list(keys, emptySet(), listOf("。", "。"))
        assertTrue(identities[0] != identities[1])
        assertEquals(1, KeyIdentity.index(identities)[identities[1]])
    }
}

class KeyPressSnapshotTest {
    private val alternates =
        listOf(
            LayoutOption("1", LayoutAction.literal("1")),
            LayoutOption("!", LayoutAction.literal("!")),
        )

    @Test
    fun theSymbolKeyKnowsItOpensThePanel() {
        val snapshot =
            KeyPressSnapshot(
                identity = "k",
                action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS),
                railLabel = null,
                longPress = emptyList(),
                swipeCodes = null,
            )
        assertTrue(snapshot.isSymbolPanelKey)
        assertFalse(snapshot.isBackspace)
        assertFalse(snapshot.isSpace)
    }

    @Test
    fun backspaceAndSpaceKeepTheirKind() {
        val backspace =
            KeyPressSnapshot(
                identity = "b",
                action = LayoutAction(LayoutActionKind.BACKSPACE),
                railLabel = null,
                longPress = emptyList(),
                swipeCodes = null,
            )
        val space =
            KeyPressSnapshot(
                identity = "s",
                action = LayoutAction(LayoutActionKind.SPACE),
                railLabel = null,
                longPress = emptyList(),
                swipeCodes = null,
            )
        assertTrue(backspace.isBackspace)
        assertTrue(space.isSpace)
    }

    @Test
    fun alternatesAreCapturedAtPressTime() {
        val snapshot =
            KeyPressSnapshot(
                identity = "q",
                action = LayoutAction.code("q"),
                railLabel = null,
                longPress = alternates,
                swipeCodes = listOf("q", "w"),
            )
        assertEquals(alternates, snapshot.longPress)
        assertEquals(listOf("q", "w"), snapshot.swipeCodes)
    }
}

class SlideRecoveryTest {
    @Test
    fun charactersAndSpaceRecoverAfterADrift() {
        assertTrue(SlideRecovery.accepts(LayoutAction.code("q")))
        assertTrue(SlideRecovery.accepts(LayoutAction.literal("。")))
        assertTrue(SlideRecovery.accepts(LayoutAction(LayoutActionKind.SPACE)))
    }

    @Test
    fun modifiersAndNavigationDoNotRecover() {
        assertFalse(SlideRecovery.accepts(LayoutAction(LayoutActionKind.SHIFT)))
        assertFalse(SlideRecovery.accepts(LayoutAction(LayoutActionKind.BACKSPACE)))
        assertFalse(SlideRecovery.accepts(LayoutAction(LayoutActionKind.ENTER)))
        assertFalse(SlideRecovery.accepts(LayoutAction(LayoutActionKind.GLOBE)))
        assertFalse(SlideRecovery.accepts(LayoutAction.languageSwitch()))
        assertFalse(SlideRecovery.accepts(LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)))
        assertFalse(SlideRecovery.accepts(null))
    }
}

class PressFeedbackTest {
    @Test
    fun anOrdinaryKeyTicksAsItsOwnAction() {
        val action = LayoutAction.code("q")
        val snapshot = KeyPressSnapshot("k", action, null, emptyList(), null)
        assertEquals(action, PressFeedback.tick(snapshot))
    }

    @Test
    fun aRailChoiceTicksAsItsWord() {
        val snapshot =
            KeyPressSnapshot(
                "rail:ni hao#0",
                LayoutAction.literal("ni hao"),
                "ni hao",
                emptyList(),
                null,
            )
        assertEquals(LayoutAction.literal("ni hao"), PressFeedback.tick(snapshot))
    }
}

class ImeMotionScaleTest {
    @After
    fun clearOverride() {
        ImeMotion.scaleOverride = null
    }

    @Test
    fun durationFollowsTheAnimatorScale() {
        ImeMotion.scaleOverride = 0f
        assertFalse(ImeMotion.enabled)
        ImeMotion.scaleOverride = 0.5f
        assertTrue(ImeMotion.enabled)
        assertEquals(90L, ImeMotion.duration(ImeMotion.CANDIDATE_MS))
        ImeMotion.scaleOverride = 2f
        assertEquals(360L, ImeMotion.duration(ImeMotion.CANDIDATE_MS))
    }

    @Test
    fun theSystemScaledSpringKeepsItsPhysicalResponse() {
        ImeMotion.scaleOverride = 1f
        val normal = ImeMotion.revealSpring(1f).getAcceleration(0.25f, 0.5f)
        for (scale in listOf(0.5f, 2f)) {
            ImeMotion.scaleOverride = scale
            assertEquals(normal, ImeMotion.revealSpring(1f).getAcceleration(0.25f, 0.5f), 0.001f)
        }
    }

    @Test
    fun aDurationIsNeverZero() {
        ImeMotion.scaleOverride = 0f
        assertEquals(1L, ImeMotion.duration(0L))
    }
}

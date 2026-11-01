package app.songyan.ime.keyboard.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFeedbackSettingsTest {
    @Test
    fun theDefaultFollowsTheSystem() {
        val settings = KeyFeedbackSettings()
        assertTrue(settings.playsSound(systemSoundEnabled = true))
        assertFalse(settings.playsSound(systemSoundEnabled = false))
        assertTrue(settings.vibrates(systemHapticsEnabled = true))
        assertFalse(settings.vibrates(systemHapticsEnabled = false))
    }

    @Test
    fun anExplicitOffSilencesEvenWhenTheSystemAllowsIt() {
        val settings = KeyFeedbackSettings(soundEnabled = false, hapticsEnabled = false)
        assertFalse(settings.playsSound(systemSoundEnabled = true))
        assertFalse(settings.vibrates(systemHapticsEnabled = true))
    }

    @Test
    fun anExplicitHapticsOnWinsOverTheSystemSwitchButSoundStillNeedsTheSystem() {
        val settings = KeyFeedbackSettings(soundEnabled = true, hapticsEnabled = true)
        assertTrue(settings.vibrates(systemHapticsEnabled = false))
        assertFalse(settings.playsSound(systemSoundEnabled = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVolumeAboveOneIsRejected() {
        KeyFeedbackSettings(soundVolume = 1.5f)
    }

    @Test
    fun clampKeepsTheRange() {
        assertEquals(0f, KeyFeedbackSettings.clamp(-1f), 0f)
        assertEquals(1f, KeyFeedbackSettings.clamp(3f), 0f)
    }

    @Test
    fun candidateTextSizeFallsBackToStandardForUnknownIds() {
        assertEquals(CandidateTextSize.STANDARD, CandidateTextSize.fromId(null))
        assertEquals(CandidateTextSize.STANDARD, CandidateTextSize.fromId("huge"))
        assertEquals(CandidateTextSize.LARGE, CandidateTextSize.fromId("large"))
        assertTrue(CandidateTextSize.entries.all { it.factor in 0.5f..1.3f })
    }
}

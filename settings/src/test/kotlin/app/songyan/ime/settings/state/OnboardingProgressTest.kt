package app.songyan.ime.settings.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingProgressTest {
    @Test
    fun aSelectedKeyboardWithoutASchemeStillStartsWithDownload() {
        val progress = onboardingProgress(ImeStatus(true, true), hasTyped = true, schemeReady = false)
        assertEquals(OnboardingStep.DOWNLOAD_SCHEME, progress.nextStep)
        assertFalse(progress.tryOutDone)
        assertFalse(progress.allDone)
    }

    @Test
    fun aPublishedSchemeUnlocksTheNextSystemStep() {
        val progress = onboardingProgress(ImeStatus(false, false), hasTyped = false, schemeReady = true)
        assertTrue(progress.schemeDone)
        assertEquals(OnboardingStep.ENABLE_IME, progress.nextStep)
    }

    @Test
    fun `selected implies enabled even when the enabled list could not be read`() {
        val progress = onboardingProgress(ImeStatus(enabled = false, selected = true), hasTyped = false)
        assertTrue(progress.enableDone)
        assertTrue(progress.selectDone)
        assertEquals(OnboardingStep.TRY_OUT, progress.nextStep)
    }

    @Test
    fun `first step is enable when the ime is unknown to the system`() {
        val progress = onboardingProgress(ImeStatus(enabled = false, selected = false), hasTyped = false)
        assertFalse(progress.enableDone)
        assertEquals(OnboardingStep.ENABLE_IME, progress.nextStep)
    }

    @Test
    fun `second step is select once enabled but not current`() {
        val progress = onboardingProgress(ImeStatus(enabled = true, selected = false), hasTyped = false)
        assertTrue(progress.enableDone)
        assertFalse(progress.selectDone)
        assertEquals(OnboardingStep.SELECT_IME, progress.nextStep)
    }

    @Test
    fun `try out is the last step`() {
        val progress = onboardingProgress(ImeStatus(enabled = true, selected = true), hasTyped = false)
        assertEquals(OnboardingStep.TRY_OUT, progress.nextStep)
        assertFalse(progress.allDone)
    }

    @Test
    fun `typing completes onboarding`() {
        val progress = onboardingProgress(ImeStatus(enabled = true, selected = true), hasTyped = true)
        assertNull(progress.nextStep)
        assertTrue(progress.allDone)
    }

    @Test
    fun `typing only completes the try out while songyan is the keyboard`() {
        val otherKeyboard = onboardingProgress(ImeStatus(enabled = true, selected = false), hasTyped = true)
        assertFalse(otherKeyboard.tryOutDone)
        assertEquals(OnboardingStep.SELECT_IME, otherKeyboard.nextStep)

        val songyan = onboardingProgress(ImeStatus(enabled = true, selected = true), hasTyped = true)
        assertTrue(songyan.tryOutDone)
        assertTrue(songyan.allDone)
    }

    @Test
    fun `a failed read reports no step instead of guessing`() {
        val progress = onboardingProgress(ImeStatus.failed(), hasTyped = false)
        assertTrue(progress.readFailed)
        assertNull(progress.nextStep)
        assertFalse(progress.allDone)
    }

    @Test
    fun `a failed read never claims a step is done`() {
        val progress = onboardingProgress(ImeStatus.failed(), hasTyped = true)
        assertFalse(progress.enableDone)
        assertFalse(progress.selectDone)
    }
}

package app.songyan.ime.engine

import app.songyan.ime.ime.EngineNotice
import app.songyan.ime.ime.EngineNoticeAction
import app.songyan.ime.ime.EngineNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineNoticesTest {
    @Test
    fun aFreshInstallAsksForASchemeWithoutAnIneffectiveRetry() {
        assertEquals(
            EngineNotice(EngineNoticeKind.NEEDS_SCHEME, listOf(EngineNoticeAction.SETTINGS)),
            engineNoticeFor(EngineRuntimeState.NEEDS_SCHEME, settingsOffered = false),
        )
    }

    @Test
    fun readyEngineShowsNoNotice() {
        assertNull(engineNoticeFor(EngineRuntimeState.READY, settingsOffered = false))
        assertNull(engineNoticeFor(EngineRuntimeState.READY, settingsOffered = true))
    }

    @Test
    fun failureOffersRetryAndSettings() {
        assertEquals(
            EngineNotice(EngineNoticeKind.FAILED, listOf(EngineNoticeAction.RETRY, EngineNoticeAction.SETTINGS)),
            engineNoticeFor(EngineRuntimeState.FAILED, settingsOffered = false),
        )
    }

    @Test
    fun firstInstallPointsAtSettingsFromTheStart() {
        assertEquals(
            EngineNotice(EngineNoticeKind.PREPARING, listOf(EngineNoticeAction.SETTINGS)),
            engineNoticeFor(EngineRuntimeState.NO_PUBLICATION, settingsOffered = false),
        )
    }

    @Test
    fun quickPreparationShowsNoButtonsAndASlowOneOffersSettings() {
        assertEquals(
            EngineNotice(EngineNoticeKind.PREPARING),
            engineNoticeFor(EngineRuntimeState.PREPARING, settingsOffered = false),
        )
        assertEquals(
            EngineNotice(EngineNoticeKind.PREPARING, listOf(EngineNoticeAction.SETTINGS)),
            engineNoticeFor(EngineRuntimeState.PREPARING, settingsOffered = true),
        )
    }
}

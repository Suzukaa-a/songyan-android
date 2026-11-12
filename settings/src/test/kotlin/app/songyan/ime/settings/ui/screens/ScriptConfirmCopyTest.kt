package app.songyan.ime.settings.ui.screens

import app.songyan.ime.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Which "what it can read" line the confirmation page shows for each kind of script. */
class ScriptConfirmCopyTest {
    @Test
    fun aTextScriptSaysWhatItReads() {
        assertEquals(R.string.scripts_confirm_reads_body, readsBodyRes(needsAudio = false))
    }

    @Test
    fun aVoiceScriptSaysItRecords() {
        assertEquals(R.string.scripts_confirm_reads_audio, readsBodyRes(needsAudio = true))
    }

    @Test
    fun theTwoCopiesAreNotTheSameResource() {
        assertNotEquals(readsBodyRes(needsAudio = false), readsBodyRes(needsAudio = true))
    }
}

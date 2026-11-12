package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The host's own audio policy: what it accepts before a run starts, and the codes it answers with.
 *  These are pure decisions, so no engine or device is involved. */
class ScriptAudioInputTest {
    @Test
    fun theLimitIsOneMinuteOfSixteenKilohertzMonoPcmPlusTheWavHeader() {
        // 16_000 samples/s * 2 bytes * 60 s, plus the 44-byte RIFF/WAVE header the recorder writes.
        // The number is provisional until S4 measures it, on both platforms together.
        assertEquals(1_920_000, ScriptAudioInput.PROVISIONAL_MAX_PCM_BYTES)
        assertEquals(44, ScriptAudioInput.WAV_HEADER_BYTES)
        assertEquals(1_920_044, ScriptAudioInput.PROVISIONAL_MAX_BYTES)
    }

    @Test
    fun aFullMinuteWavIsAcceptedAndOneByteMoreIsRefused() {
        // The longest legitimate recording: one minute of PCM plus the header the recorder wrote.
        assertNull(ScriptAudioInput.refusal(needsAudio = true, byteCount = 1_920_044L))
        val refused = ScriptAudioInput.refusal(needsAudio = true, byteCount = 1_920_045L)
        assertEquals("audioTooLarge", refused?.code)
        // The limit must cover the header: a minute of PCM alone is still inside the policy.
        assertNull(ScriptAudioInput.sizeRefusal(1_920_000L))
        assertNull(ScriptAudioInput.sizeRefusal(1_920_000L + ScriptAudioInput.WAV_HEADER_BYTES))
    }

    @Test
    fun aTextScriptRefusesAudioAsACallerMistake() {
        val refused = ScriptAudioInput.refusal(needsAudio = false, byteCount = 4_096L)
        assertEquals("inputMismatch", refused?.code)
        assertEquals("the script does not declare @input audio", refused?.message)
    }

    @Test
    fun anAudioScriptWithoutAudioReportsTheSharedCapabilityCode() {
        val refused = ScriptAudioInput.refusal(needsAudio = true, byteCount = 0L)
        assertEquals("capabilityUnavailable", refused?.code)
        assertNull(ScriptAudioInput.sizeRefusal(1L))
    }
}

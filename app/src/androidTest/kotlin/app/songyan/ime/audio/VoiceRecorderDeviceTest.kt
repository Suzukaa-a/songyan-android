package app.songyan.ime.audio

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real microphone path on the emulator, whose virtual microphone provides the audio: a take of
 * about one second, then the WAV file read back byte by byte.
 *
 * The permission is not granted from inside the process — changing the app's own runtime permission
 * kills it — so the runbook grants it first:
 * `adb -s <serial> shell pm grant app.songyan.ime.debug android.permission.RECORD_AUDIO`.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val takes = mutableListOf<File>()

    @Before
    fun requireTheMicrophonePermission() {
        assertTrue(
            "grant RECORD_AUDIO first; adb -s <serial> shell pm grant ${context.packageName} " +
                Manifest.permission.RECORD_AUDIO,
            MicrophonePermission.isGranted(context),
        )
    }

    @After
    fun deleteTheTakes() {
        takes.forEach { it.delete() }
    }

    @Test
    fun oneSecondOfTheRealMicrophoneIsA16kMono16BitWav() {
        val recorder = VoiceRecorder(context)
        try {
            assertEquals(VoiceRecordingStart.Started, recorder.start())
            SystemClock.sleep(1_000)
            val clip = recorder.stop().requireRecorded()
            takes += clip.file

            assertEquals(VoiceTakeEnd.STOPPED_BY_CALLER, clip.end)
            assertEquals(16_000, clip.format.sampleRateHz)
            assertEquals(1, clip.format.channels)
            assertEquals(16, clip.format.bitsPerSample)

            val bytes = clip.file.readBytes()
            val wav = WavBytes(bytes)
            assertEquals("RIFF", wav.riff)
            assertEquals("WAVE", wav.wave)
            assertEquals("fmt ", wav.fmtChunk)
            assertEquals(WavBytes.PCM, wav.audioFormat)
            assertEquals(16_000, wav.sampleRateHz)
            assertEquals(1, wav.channels)
            assertEquals(16, wav.bitsPerSample)
            assertEquals(32_000, wav.byteRate)
            assertEquals(2, wav.blockAlign)
            assertEquals("data", wav.dataChunk)
            assertEquals(bytes.size.toLong() - WavFile.HEADER_BYTES, wav.dataBytes)
            assertEquals(bytes.size - WavFile.HEADER_BYTES, clip.dataBytes.toInt())
            // The emulator's virtual microphone can deliver slower than real time (it is enough for
            // capture to happen at all); the take must contain audio, not a second of it.
            assertTrue("the take must contain audio, had ${wav.dataBytes} bytes", wav.dataBytes > 0)
            assertEquals(wav.dataBytes / wav.blockAlign, clip.frames)
            assertEquals(clip.frames * 1_000 / 16_000, clip.durationMs)
            assertTrue(
                "a stopped take cannot be longer than the wall clock, was ${clip.durationMs} ms",
                clip.durationMs <= 1_600L,
            )
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun aTakeEndsByItselfAtTheHostCap() {
        val recorder = VoiceRecorder(context, VoiceRecordingPolicy(maxDurationMs = 300))
        try {
            assertEquals(VoiceRecordingStart.Started, recorder.start())
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (recorder.isRecording && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertFalse("the cap must end the take", recorder.isRecording)

            val clip = recorder.stop().requireRecorded()
            takes += clip.file
            assertEquals(VoiceTakeEnd.REACHED_LIMIT, clip.end)
            assertTrue("the cap must record at least its 300 ms, was ${clip.durationMs} ms", clip.durationMs >= 300L)
            assertTrue("the cap must stop soon after 300 ms, was ${clip.durationMs} ms", clip.durationMs <= 1_200L)
        } finally {
            recorder.cancel()
        }
    }

    private fun VoiceRecordingOutcome.requireRecorded(): VoiceClip {
        assertTrue("expected a finished take, got $this", this is VoiceRecordingOutcome.Recorded)
        return (this as VoiceRecordingOutcome.Recorded).clip
    }
}

/** A WAV file read the way a player would, so every field can be asserted. */
private class WavBytes(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val riff: String = bytes.ascii(0, 4)
    val wave: String = bytes.ascii(8, 4)
    val fmtChunk: String = bytes.ascii(12, 4)
    val audioFormat: Int = buffer.getShort(20).toInt() and 0xFFFF
    val channels: Int = buffer.getShort(22).toInt() and 0xFFFF
    val sampleRateHz: Int = buffer.getInt(24)
    val byteRate: Int = buffer.getInt(28)
    val blockAlign: Int = buffer.getShort(32).toInt() and 0xFFFF
    val bitsPerSample: Int = buffer.getShort(34).toInt() and 0xFFFF
    val dataChunk: String = bytes.ascii(36, 4)
    val dataBytes: Long = buffer.getInt(40).toLong() and 0xFFFF_FFFFL

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String = String(this, offset, length, Charsets.US_ASCII)

    companion object {
        const val PCM = 1
    }
}

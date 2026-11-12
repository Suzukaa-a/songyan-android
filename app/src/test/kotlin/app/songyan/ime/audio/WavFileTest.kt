package app.songyan.ime.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The WAV container the recorder writes, checked byte by byte without a device. */
class WavFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val format = VoiceRecordingPolicy.DEFAULT.format

    @Test
    fun theHeaderDeclares16kMono16BitPcmWithNoAudioYet() {
        val header = WavFile.header(format)
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(WavFile.HEADER_BYTES, header.size)
        assertEquals("RIFF", header.ascii(0, 4))
        assertEquals(36, bytes.getInt(4))
        assertEquals("WAVE", header.ascii(8, 4))
        assertEquals("fmt ", header.ascii(12, 4))
        assertEquals(16, bytes.getInt(16))
        assertEquals(1, bytes.getShort(20).toInt())
        assertEquals(16_000, bytes.getInt(24))
        assertEquals(32_000, bytes.getInt(28))
        assertEquals(2, bytes.getShort(32).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
        assertEquals("data", header.ascii(36, 4))
        assertEquals(0, bytes.getInt(40))
    }

    @Test
    fun patchingTheHeaderWritesTheRiffAndDataLengths() {
        val file = temporaryFolder.newFile("take.wav")
        file.writeBytes(WavFile.header(format))

        WavFile.patchDataBytes(file, dataBytes = 32_000)

        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36L + 32_000, bytes.getInt(4).toLong())
        assertEquals(32_000, bytes.getInt(40))
    }

    @Test
    fun patchingKeepsTheDeclaredFormatAndLeavesTheAudioToTheRecorder() {
        val file = temporaryFolder.newFile("take.wav")
        file.writeBytes(WavFile.header(format))

        WavFile.patchDataBytes(file, dataBytes = 640)

        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, bytes.getInt(24))
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
        assertEquals(WavFile.HEADER_BYTES, bytes.capacity())
    }

    @Test
    fun theHostCapIsTheProvisionalMinute() {
        assertEquals(60_000L, VoiceRecordingPolicy.PROVISIONAL_MAX_DURATION_MS)
        assertEquals(60_000L, VoiceRecordingPolicy.DEFAULT.maxDurationMs)
        assertEquals(16_000L * 2 * 60, VoiceRecordingPolicy.DEFAULT.maxDataBytes)
    }

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ): String = String(this, offset, length, Charsets.US_ASCII)
}

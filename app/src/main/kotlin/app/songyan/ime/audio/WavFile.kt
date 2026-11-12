package app.songyan.ime.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 44-byte RIFF/WAVE header of a PCM take.
 *
 * The recorder writes [header] before the first sample, so an interrupted take still leaves a file
 * a reader can open, and patches the two length fields with [patchDataBytes] when the take ends.
 * Fields are little-endian, the layout WAV readers require; no other metadata is written.
 */
object WavFile {
    /** Bytes the header takes before the first PCM byte. */
    const val HEADER_BYTES = 44

    private const val TAG_RIFF = "RIFF"
    private const val TAG_WAVE = "WAVE"
    private const val TAG_FMT = "fmt "
    private const val TAG_DATA = "data"
    private const val FORMAT_PCM = 1
    private const val FMT_CHUNK_BYTES = 16
    private const val RIFF_CHUNK_OVERHEAD = 36

    /**
     * A header that declares no audio yet: the sample format and rate are final, the two lengths are
     * patched by [patchDataBytes] once the file is complete.
     */
    fun header(format: WavFormat): ByteArray {
        val bytes = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(TAG_RIFF.toByteArray(Charsets.US_ASCII))
        bytes.putInt(RIFF_CHUNK_OVERHEAD)
        bytes.put(TAG_WAVE.toByteArray(Charsets.US_ASCII))
        bytes.put(TAG_FMT.toByteArray(Charsets.US_ASCII))
        bytes.putInt(FMT_CHUNK_BYTES)
        bytes.putShort(FORMAT_PCM.toShort())
        bytes.putShort(format.channels.toShort())
        bytes.putInt(format.sampleRateHz)
        bytes.putInt(format.byteRate)
        bytes.putShort(format.blockAlign.toShort())
        bytes.putShort(format.bitsPerSample.toShort())
        bytes.put(TAG_DATA.toByteArray(Charsets.US_ASCII))
        bytes.putInt(0)
        return bytes.array()
    }

    /** Sets the RIFF size (36 + data) and the data size of a file written with [header]. */
    fun patchDataBytes(
        file: File,
        dataBytes: Long,
    ) {
        require(dataBytes <= 0xFFFF_FFFFL) { "a WAV take cannot exceed 4 GiB, was $dataBytes bytes" }
        RandomAccessFile(file, "rw").use { out ->
            out.seek(4)
            out.writeIntLe(RIFF_CHUNK_OVERHEAD + dataBytes)
            out.seek(40)
            out.writeIntLe(dataBytes)
        }
    }

    /** [RandomAccessFile] writes big-endian Integers, which is the wrong end for a WAV header. */
    private fun RandomAccessFile.writeIntLe(value: Long) {
        for (shift in 0..24 step 8) writeByte(((value shr shift) and 0xff).toInt())
    }
}

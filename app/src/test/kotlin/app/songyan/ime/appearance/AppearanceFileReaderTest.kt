package app.songyan.ime.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppearanceFileReaderTest {
    @Test
    fun aFileAtTheBudgetIsRead() {
        val bytes = ByteArray(AppearanceDocument.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(AppearanceDocument.MAX_BYTES, AppearanceFileReader.decode(bytes).length)
    }

    @Test
    fun aFileOverTheBudgetIsRejectedAsOversize() {
        val bytes = ByteArray(AppearanceDocument.MAX_BYTES + 1)
        val error = assertThrows(AppearanceError::class.java) { AppearanceFileReader.decode(bytes) }
        assertEquals(AppearanceErrorCode.OVERSIZE, error.code)
    }

    @Test
    fun multiByteTextSurvivesTheDecode() {
        assertEquals("松烟", AppearanceFileReader.decode("松烟".encodeToByteArray()))
    }
}

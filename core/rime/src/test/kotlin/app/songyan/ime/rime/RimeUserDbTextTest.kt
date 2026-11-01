package app.songyan.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Snapshots as other Rime front-ends and older librime versions write them. */
class RimeUserDbTextTest {
    private val directory: File = Files.createTempDirectory("userdb-text").toFile()

    private fun file(text: String): File =
        File(directory, "s-${text.hashCode()}.userdb.txt").also { it.writeText(text) }

    private fun expectInvalid(text: String) {
        try {
            RimeUserDbText.inspect(file(text))
            fail("accepted: $text")
        } catch (e: RimeLearningException) {
            assertEquals(RimeLearningException.Kind.INVALID_SNAPSHOT, e.kind)
        }
    }

    @Test
    fun aCurrentLibrimeSnapshotNamesItsDictionaryAndCountsItsRows() {
        val snapshot =
            RimeUserDbText.inspect(
                file(
                    """
                    # Rime user dictionary
                    #@/db_name	rime_ice
                    #@/db_type	userdb
                    #@/rime_version	1.11.2
                    #@/tick	1024
                    #@/user_id	trime-1234
                    ni hao 	你好	c=12 d=3.2 t=1000
                    song yan 	松烟	c=1 d=0.5 t=1020

                    """.trimIndent(),
                ),
            )
        assertEquals("rime_ice", snapshot.dictionary)
        assertEquals(2, snapshot.entryCount)
    }

    @Test
    fun anOldSnapshotWithoutTypeOrVersionAndAKctNameIsAccepted() {
        val snapshot =
            RimeUserDbText.inspect(
                file("# Rime user dictionary\n#@/db_name\tluna_pinyin.userdb.kct\nni \t你\tc=3 d=1 t=1\n"),
            )
        assertEquals("luna_pinyin", snapshot.dictionary)
        assertEquals(1, snapshot.entryCount)
    }

    @Test
    fun windowsLineEndsAreRead() {
        val snapshot =
            RimeUserDbText.inspect(
                file("# Rime user dictionary\r\n#@/db_name\tluna_pinyin\r\n#@/db_type\tuserdb\r\nni \t你\tc=3\r\n"),
            )
        assertEquals("luna_pinyin", snapshot.dictionary)
        assertEquals(1, snapshot.entryCount)
    }

    @Test
    fun rowsAfterNoCommentCountEvenWithAHash() {
        val snapshot =
            RimeUserDbText.inspect(
                file("# Rime user dictionary\n#@/db_name\tx\n# no comment\n#hash \t#\tc=1\nni \t你\tc=1\n"),
            )
        assertEquals(2, snapshot.entryCount)
    }

    @Test
    fun aSnapshotWithoutRowsIsReadAsEmpty() {
        assertEquals(0, RimeUserDbText.inspect(file("# Rime user dictionary\n#@/db_name\tx\nbroken-row\n")).entryCount)
    }

    @Test
    fun foreignOrDamagedFilesAreRefused() {
        expectInvalid("# Rime table\n#@/db_name\tx\n")
        expectInvalid("ni hao\t你好\t1\n")
        expectInvalid("# Rime user dictionary\n#@/db_type\tuserdb\nni \t你\tc=1\n")
        expectInvalid("# Rime user dictionary\n#@/db_name\tx\n#@/db_type\ttabledb\nni \t你\tc=1\n")
        expectInvalid("# Rime user dictionary\n#@/db_name\tx\n#@/db_name\ty\n")
        expectInvalid("# Rime user dictionary\n#@/db_name\t../escape\n")
        expectInvalid("# Rime user dictionary\n#@/db_name\t.temp\n")
        expectInvalid("")
        val notUtf8 = File(directory, "latin1.userdb.txt")
        notUtf8.writeBytes(
            "# Rime user dictionary\n#@/db_name\tx\nni \t".toByteArray() + byteArrayOf(0xC4.toByte(), 0x0A),
        )
        try {
            RimeUserDbText.inspect(notUtf8)
            fail("accepted a file that is not UTF-8")
        } catch (e: RimeLearningException) {
            assertEquals(RimeLearningException.Kind.INVALID_SNAPSHOT, e.kind)
        }
    }

    @Test
    fun theHeaderIsRecognizedWithOrWithoutABom() {
        val header = RimeUserDbText.HEADER.toByteArray()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(RimeUserDbText.looksLikeSnapshot(header + "\n".toByteArray()))
        assertTrue(RimeUserDbText.looksLikeSnapshot(bom + header))
        assertEquals(3, RimeUserDbText.bomLength(bom + header))
        assertEquals(0, RimeUserDbText.bomLength(header))
        assertFalse(RimeUserDbText.looksLikeSnapshot("{\"version\":1}".toByteArray()))
        assertFalse(RimeUserDbText.looksLikeSnapshot(header.copyOf(5)))
    }

    @Test
    fun theDictionaryNameEndsBeforeTheLastUserdb() {
        assertEquals("luna_pinyin", RimeUserDbText.dictionaryName("luna_pinyin.userdb.kct"))
        assertEquals("luna_pinyin", RimeUserDbText.dictionaryName("luna_pinyin.userdb"))
        assertEquals("rime_ice", RimeUserDbText.dictionaryName("rime_ice"))
    }
}

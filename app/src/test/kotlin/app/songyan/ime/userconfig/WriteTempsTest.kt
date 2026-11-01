package app.songyan.ime.userconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What a dead writer left behind is swept; what a live one owns, and everything else, stays. */
class WriteTempsTest {
    @Test
    fun staleTemporaryFilesGoAndOpenOnesAndOtherFilesStay() {
        val dir = Files.createTempDirectory("config").toFile()
        File(dir, ".write-1").writeText("dead")
        File(dir, ".write-2").writeText("live")
        File(dir, "status.json").writeText("{}")
        File(dir, "edit").mkdirs()

        val removed = WriteTemps.sweep(dir, listOf(".write-2"))

        assertEquals(1, removed)
        assertEquals(setOf(".write-2", "status.json", "edit"), dir.list()!!.toSet())
        assertTrue(File(dir, ".write-2").exists())
    }
}

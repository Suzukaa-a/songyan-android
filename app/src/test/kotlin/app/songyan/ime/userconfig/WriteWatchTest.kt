package app.songyan.ime.userconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** One writer may neither grow past the file limit nor stay open for ever; old editor temporaries are swept. */
class WriteWatchTest {
    private val watch = WriteWatch(limitBytes = 100, maxOpenMs = 1000, openedAt = 0)

    @Test
    fun theVerdictFollowsSizeAndAge() {
        assertEquals(WriteWatch.Verdict.FINE, watch.check(size = 100, now = 999))
        assertEquals(WriteWatch.Verdict.TOO_BIG, watch.check(size = 101, now = 10))
        assertEquals(WriteWatch.Verdict.TOO_LONG, watch.check(size = 1, now = 1000))
        assertEquals(WriteWatch.Verdict.TOO_LONG, watch.check(size = 1000, now = 5000))
    }

    @Test
    fun theLimitsAreBounded() {
        assertTrue(WriteTemps.MAX_OPEN in 1..16)
        assertTrue(WriteTemps.MAX_OPEN_MS <= 15 * 60 * 1000)
    }

    @Test
    fun oldEditorTemporariesAreSweptFromTheEditFolderAndNewOnesStay() {
        val edit = Files.createTempDirectory("edit").toFile()
        File(edit, "lua").mkdirs()
        val old = File(edit, "lua/.write-1").also { it.writeText("x") }
        val copy = File(edit, ".copy-2").also { it.writeText("x") }
        val fresh = File(edit, ".write-3").also { it.writeText("x") }
        val keep = File(edit, "lua/a.lua").also { it.writeText("x") }
        old.setLastModified(1_000)
        copy.setLastModified(1_000)

        val removed = WriteTemps.sweepTree(edit, olderThanMs = 60_000, now = 100_000)

        assertEquals(2, removed)
        assertTrue(fresh.exists() && keep.exists())
    }
}

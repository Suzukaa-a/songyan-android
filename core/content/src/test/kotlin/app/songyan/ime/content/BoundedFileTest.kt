package app.songyan.ime.content

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Ported from QuickContentFileReaderTests: the bounded, regular-file-only reader. */
class BoundedFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Path {
        val path = temporary.root.toPath().resolve(name)
        Files.write(path, bytes)
        return path
    }

    @Test
    fun aWriteSweepsAbandonedTemporaryFilesButKeepsFreshOnes() {
        val directory = temporary.newFolder("sweep").toPath()
        val stale = directory.resolve(".content-tmp-stale.tmp")
        val fresh = directory.resolve(".content-tmp-fresh.tmp")
        Files.write(stale, "abandoned".toByteArray())
        Files.write(fresh, "in-flight".toByteArray())
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 2 * 60 * 60 * 1000))
        BoundedFile.writeAtomically(directory.resolve("content.json"), "{}".toByteArray())
        assertFalse("an abandoned temporary file is removed", Files.exists(stale))
        assertTrue("a fresh temporary file belongs to a live write and is left alone", Files.exists(fresh))
        assertEquals("{}", Files.readString(directory.resolve("content.json")))
    }

    @Test
    fun regularReadsHonorExactBudget() {
        val exact = ByteArray(16 * 1024) { 0x20 }
        val path = file("input", exact)
        assertArrayEquals(exact, BoundedFile.read(path, exact.size))
        Files.write(path, exact + byteArrayOf(0x20))
        val error = assertThrows(ContentException::class.java) { BoundedFile.read(path, exact.size) }
        assertEquals(ContentErrorCode.OVERSIZE, error.code)
    }

    @Test
    fun directoryIsRejected() {
        val directory = temporary.newFolder("a-directory").toPath()
        val error = assertThrows(ContentException::class.java) { BoundedFile.read(directory, 1024) }
        assertEquals(ContentErrorCode.NOT_A_REGULAR_FILE, error.code)
    }

    @Test
    fun fifoIsRejectedBeforeItIsOpened() {
        val directory = temporary.newFolder("pipe-dir").toPath()
        val pipe = TestFiles.fifo(directory, "pipe.json", "untrusted".toByteArray())
        val error = assertThrows(ContentException::class.java) { BoundedFile.read(pipe, 1024) }
        assertEquals(ContentErrorCode.NOT_A_REGULAR_FILE, error.code)
    }

    @Test
    fun symlinkToRegularFileIsFollowedButDanglingLinkIsRejected() {
        val target = file("target.json", "{\"version\":1,\"items\":[]}".toByteArray())
        val link = temporary.root.toPath().resolve("link.json")
        Files.createSymbolicLink(link, target)
        // iOS resolves links and then requires a regular file, so this one is readable.
        assertArrayEquals(Files.readAllBytes(target), BoundedFile.read(link, 1024))
        Files.delete(target)
        val error = assertThrows(ContentException::class.java) { BoundedFile.read(link, 1024) }
        assertEquals(ContentErrorCode.NOT_A_REGULAR_FILE, error.code)
    }
}

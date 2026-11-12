package app.songyan.ime.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The scratch directory of the takes: naming, and the cleanup a killed process needs. */
class VoiceTakeFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aTakeIsNamedInItsOwnFilespace() {
        val directory = temporaryFolder.newFolder(VoiceTakeFiles.DIRECTORY)
        val file = VoiceTakeFiles.takeFile(directory, "1700000000000-1")
        assertEquals("take-1700000000000-1.wav", file.name)
        assertEquals(directory, file.parentFile)
    }

    @Test
    fun pruningDeletesOnlyEarlierTakes() {
        val directory = temporaryFolder.newFolder("voice")
        val first = File(directory, "take-1-1.wav").apply { writeBytes(ByteArray(4)) }
        val second = File(directory, "take-2-1.wav").apply { writeBytes(ByteArray(4)) }
        val note = File(directory, "settings.txt").apply { writeText("keep") }
        val otherWav = File(directory, "sample.wav").apply { writeBytes(ByteArray(4)) }
        val subdirectory = File(directory, "take-dir").apply { mkdirs() }

        assertEquals(2, VoiceTakeFiles.prune(directory))

        assertEquals(false, first.exists())
        assertEquals(false, second.exists())
        assertEquals("nothing else may be deleted", true, note.exists())
        assertEquals(true, otherWav.exists())
        assertEquals("pruning only deletes files", true, subdirectory.exists())
    }

    @Test
    fun pruningAnAbsentDirectoryIsHarmless() {
        assertEquals(0, VoiceTakeFiles.prune(File(temporaryFolder.root, "never-created")))
    }
}

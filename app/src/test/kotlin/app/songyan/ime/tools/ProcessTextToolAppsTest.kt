package app.songyan.ime.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The enabled list on disk, driven through the same methods the settings page and the keyboard call.
 *
 * The list decides which other apps the keyboard offers, so a document this build cannot read must
 * never be rewritten from the empty list a display read falls back to: the components it still names
 * would be turned off without the user asking. Every case uses real files in a temporary directory.
 */
class ProcessTextToolAppsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val one = "app.one/app.one.Handler"
    private val two = "app.two/app.two.Handler"
    private val three = "app.three/app.three.Handler"

    private fun directory(): File = temporary.newFolder("tools")

    private fun appsIn(directory: File): ProcessTextToolApps = ProcessTextToolApps(directory)

    private fun documentFile(directory: File): File = File(directory, ProcessTextToolApps.FILE_NAME)

    private suspend fun failureOf(block: suspend () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected the operation to fail")
        } catch (error: AssertionError) {
            throw error
        } catch (error: Throwable) {
            error
        }

    @Test
    fun aMissingDocumentEnablesNothingAndTheFirstToggleWritesIt() =
        runBlocking {
            val directory = directory()
            assertTrue("a directory with no document enables nothing", appsIn(directory).enabled().isEmpty())

            assertEquals(setOf(one), appsIn(directory).setEnabled(one, true))

            assertTrue("the first toggle did not write the document", documentFile(directory).isFile)
            assertEquals("the toggle was not stored", setOf(one), appsIn(directory).enabled())
        }

    @Test
    fun turningOneAppOffKeepsTheOthers() =
        runBlocking {
            val directory = directory()
            appsIn(directory).setEnabled(one, true)
            assertEquals(setOf(one, two), appsIn(directory).setEnabled(two, true))

            assertEquals(setOf(two), appsIn(directory).setEnabled(one, false))

            assertEquals("the toggle was not stored", setOf(two), appsIn(directory).enabled())
        }

    @Test
    fun aCorruptDocumentIsRefusedInsteadOfBeingReplacedByOneToggle() =
        runBlocking {
            val directory = directory()
            val file = documentFile(directory)
            appsIn(directory).setEnabled(one, true)
            appsIn(directory).setEnabled(two, true)
            val written = Files.readAllBytes(file.toPath())
            // What a restore from a backup or a writer that was killed can leave behind.
            val damaged = """{"version":1,"enabled":[""".toByteArray(Charsets.UTF_8)
            Files.write(file.toPath(), damaged)

            assertTrue(
                "a display read must still open the page with nothing enabled",
                appsIn(directory).enabled().isEmpty(),
            )

            val refused = failureOf { appsIn(directory).setEnabled(three, true) }
            assertTrue("expected the toggle to be refused but was " + refused, refused is IOException)
            assertArrayEquals(
                "a document this build cannot read must not be replaced",
                damaged,
                Files.readAllBytes(file.toPath()),
            )

            // Once the user repairs the file, every component it named is still enabled.
            Files.write(file.toPath(), written)
            assertEquals(setOf(one, two), appsIn(directory).enabled())
        }

    @Test
    fun aDocumentFromAnotherBuildIsRefusedInsteadOfBeingReplaced() =
        runBlocking {
            val directory = directory()
            val file = documentFile(directory)
            val newer = """{"version":2,"enabled":["$one","$two"]}""".toByteArray(Charsets.UTF_8)
            Files.write(file.toPath(), newer)

            assertTrue("a display read shows nothing enabled", appsIn(directory).enabled().isEmpty())

            val refused = failureOf { appsIn(directory).setEnabled(three, true) }
            assertTrue("expected the toggle to be refused but was " + refused, refused is IOException)
            assertArrayEquals(
                "a document from another build must not be replaced",
                newer,
                Files.readAllBytes(file.toPath()),
            )
        }

    @Test
    fun anUnreadableDocumentIsRefusedInsteadOfBeingReplaced() =
        runBlocking {
            assumeTrue(
                "needs POSIX permissions",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            )
            val directory = directory()
            val file = documentFile(directory)
            appsIn(directory).setEnabled(one, true)
            appsIn(directory).setEnabled(two, true)
            val written = Files.readAllBytes(file.toPath())

            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("---------"))
            try {
                val refused = failureOf { appsIn(directory).setEnabled(three, true) }
                assertTrue("expected the toggle to be refused but was " + refused, refused is IOException)
            } finally {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }

            assertArrayEquals(
                "an unreadable document must not be replaced",
                written,
                Files.readAllBytes(file.toPath()),
            )
            assertEquals(setOf(one, two), appsIn(directory).enabled())
        }
}

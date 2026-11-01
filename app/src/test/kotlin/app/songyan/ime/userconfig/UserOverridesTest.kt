package app.songyan.ime.userconfig

import app.songyan.ime.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The user's copies of a source's files: made from the package's bytes, never touching it, tracked per path. */
class UserOverridesTest {
    private val storage = AppStorage(Files.createTempDirectory("files").toFile()).also { it.ensureBaseDirectories() }
    private val overrides = UserOverrides(storage)
    private val source = File(storage.sourcesDir, "scheme-1").also { File(it, "lua").mkdirs() }

    private fun packaged(
        path: String,
        text: String,
    ) = File(source, path).also { it.parentFile?.mkdirs() }.apply { writeText(text) }

    private fun refusal(block: () -> Unit): UserConfigException {
        try {
            block()
        } catch (e: UserConfigException) {
            return e
        }
        fail("expected a refusal")
        throw AssertionError()
    }

    @Test
    fun aCopyStartsFromThePackagesBytesAndLeavesThePackageAlone() {
        val original = packaged("lua/date.lua", "return 'a'\n")

        val copy = overrides.copyOf(original, "lua/date.lua")

        assertEquals(File(storage.userConfigDir, "lua/date.lua").canonicalFile, copy.canonicalFile)
        assertEquals("return 'a'\n", copy.readText())
        copy.writeText("return 'mine'\n")
        assertEquals("return 'a'\n", original.readText())
        assertEquals(copy.canonicalFile, overrides.overrideFile("lua/date.lua")?.canonicalFile)
    }

    @Test
    fun anExistingCopyIsReusedNotOverwritten() {
        val original = packaged("a.schema.yaml", "x: 1\n")
        overrides.copyOf(original, "a.schema.yaml").writeText("x: 2\n")

        overrides.copyOf(original, "a.schema.yaml")

        assertEquals("x: 2\n", File(storage.userConfigDir, "a.schema.yaml").readText())
    }

    @Test
    fun pathsThatEscapeOrCannotBeOverridesAreRefused() {
        val original = packaged("a.schema.yaml", "x: 1\n")
        for (path in listOf(
            "../a.schema.yaml",
            "lua/../../a.yaml",
            "build/a.yaml",
            "a.md",
            "songyan_imported.dict.yaml",
        )) {
            assertEquals(path, UserConfigProblem.NAME, refusal { overrides.target(original, path) }.problem)
        }
        assertNull(overrides.overrideFile("../a.schema.yaml"))
    }

    @Test
    fun aFileOverTheLimitOrOneTooManyIsRefused() {
        val big = packaged("big.dict.yaml", "")
        big.writeBytes(ByteArray((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        assertEquals(UserConfigProblem.TOO_LARGE, refusal { overrides.copyOf(big, "big.dict.yaml") }.problem)
        assertFalse(File(storage.userConfigDir, "big.dict.yaml").exists())

        for (i in 0 until UserConfigFiles.MAX_FILES) File(storage.userConfigDir, "f$i.txt").writeText("x")
        val one = packaged("one.yaml", "a: 1\n")
        assertEquals(UserConfigProblem.TOO_MANY, refusal { overrides.copyOf(one, "one.yaml") }.problem)
    }

    @Test
    fun onlyFilesThatShadowASourceFileAreOverrides() {
        packaged("a.schema.yaml", "x: 1\n")
        File(storage.userConfigDir, "a.schema.yaml").writeText("x: 2\n")
        File(storage.userConfigDir, "default.custom.yaml").writeText("patch: {}\n")
        File(storage.userConfigDir, "lua").mkdirs()
        File(storage.userConfigDir, "lua/new.lua").writeText("return 1\n")

        assertEquals(listOf("a.schema.yaml"), overrides.entries(source).map { it.path })
        assertTrue(overrides.entries(null).isEmpty())
    }

    @Test
    fun eachOverrideIsToldOnceAndAFileMadeAgainIsToldAgain() {
        packaged("a.schema.yaml", "x: 1\n")
        packaged("b.schema.yaml", "y: 1\n")
        File(storage.userConfigDir, "a.schema.yaml").writeText("x: 2\n")
        File(storage.userConfigDir, "b.schema.yaml").writeText("y: 2\n")
        assertEquals(listOf("a.schema.yaml", "b.schema.yaml"), overrides.unacknowledged(source))

        overrides.acknowledge(listOf("a.schema.yaml", "b.schema.yaml"))
        assertTrue(overrides.unacknowledged(source).isEmpty())
        File(storage.userConfigDir, "c.schema.yaml").writeText("z: 2\n")
        packaged("c.schema.yaml", "z: 1\n")
        assertEquals(listOf("c.schema.yaml"), overrides.unacknowledged(source))

        // What was told survives a new instance (the settings screen is opened again).
        assertTrue(UserOverrides(storage).unacknowledged(source) == listOf("c.schema.yaml"))
        assertTrue(overrides.restore("a.schema.yaml"))
        File(storage.userConfigDir, "a.schema.yaml").writeText("x: 3\n")
        assertEquals(listOf("a.schema.yaml", "c.schema.yaml"), overrides.unacknowledged(source))
    }

    @Test
    fun aNewerPackageFileIsNoticedAndRestoringBringsTheNewOneBack() {
        val original = packaged("lua/date.lua", "return 1\n")
        overrides.copyOf(original, "lua/date.lua").writeText("return 'mine'\n")
        assertFalse(overrides.entries(source).single().updated)

        original.writeText("return 2\n")

        assertTrue(overrides.entries(source).single().updated)
        assertTrue(overrides.restore("lua/date.lua"))
        assertTrue(overrides.entries(source).isEmpty())
        assertFalse("the emptied folder goes too", File(storage.userConfigDir, "lua").exists())
        assertFalse(storage.overrideMarksFile.exists())
        assertEquals("return 2\n", original.readText())
    }

    @Test
    fun aFileTheUserMadeHasNoBaseSoItIsNeverCalledUpdated() {
        val original = packaged("a.schema.yaml", "x: 1\n")
        File(storage.userConfigDir, "a.schema.yaml").writeText("x: 2\n")
        original.writeText("x: 3\n")

        assertFalse(overrides.entries(source).single().updated)
    }

    @Test
    fun aNameThatDiffersOnlyInCaseOrIsNotASourceFileIsRefused() {
        val original = packaged("a.schema.yaml", "x: 1\n")
        File(storage.userConfigDir, "A.schema.yaml").writeText("x: 2\n")

        assertEquals(UserConfigProblem.NAME, refusal { overrides.target(original, "a.schema.yaml") }.problem)
        assertEquals(
            "a path the source has no file at cannot be made up",
            UserConfigProblem.NAME,
            refusal { overrides.target(File(source, "nothing.yaml"), "nothing.yaml") }.problem,
        )
    }

    @Test
    fun aLinkInTheSourceIsNotFollowed() {
        val outside = File(source.parentFile, "outside-" + System.nanoTime()).also { it.writeText("secret") }
        Files.createSymbolicLink(File(source, "link.yaml").toPath(), outside.toPath())

        assertNull(UserOverrides.packageFile(source, "link.yaml"))
    }
}

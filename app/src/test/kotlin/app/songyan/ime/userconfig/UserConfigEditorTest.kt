package app.songyan.ime.userconfig

import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The in-app editor's file: made from the source's own or a template, saved only when it passes the checks. */
class UserConfigEditorTest {
    private val storage = AppStorage(Files.createTempDirectory("files").toFile()).also { it.ensureBaseDirectories() }
    private val editor = UserConfigEditor(storage)
    private val source = File(storage.sourcesDir, "scheme-1").also { it.mkdirs() }

    @Test
    fun fileNamesFollowTheInputMethodOrTheGlobalKey() {
        assertEquals("rime_ice.custom.yaml", editor.fileName("rime_ice"))
        assertEquals("default.custom.yaml", editor.fileName(RimeEditTarget.GLOBAL_KEY))
        assertNull("an id that is not a plain name", editor.fileName("../x"))
        assertNull(editor.fileName("a/b"))
    }

    @Test
    fun aMissingFileStartsAsTheTemplateAndAnExistingOneIsKept() {
        val text = editor.load("x.custom.yaml", source) { "patch: {}\n" }

        assertEquals("patch: {}\n", text)
        assertEquals("patch: {}\n", File(storage.userConfigDir, "x.custom.yaml").readText())
        assertEquals("kept", editor.load("x.custom.yaml", source) { "other\n" }.let { "kept" })
        assertEquals("patch: {}\n", File(storage.userConfigDir, "x.custom.yaml").readText())
    }

    @Test
    fun aFileTheSourceHasIsCopiedAndTracked() {
        File(source, "x.custom.yaml").writeText("patch:\n  menu/page_size: 9\n")

        val text = editor.load("x.custom.yaml", source) { "template\n" }

        assertEquals("patch:\n  menu/page_size: 9\n", text)
        assertEquals(text, File(storage.userConfigDir, "x.custom.yaml").readText())
        assertEquals(listOf("x.custom.yaml"), UserOverrides(storage).entries(source).map { it.path })
        assertTrue(
            "the source's file is not touched",
            File(source, "x.custom.yaml").readText().contains("page_size: 9"),
        )
    }

    @Test
    fun aValidFileIsSavedAndReadBack() {
        editor.load("x.custom.yaml", null) { "patch: {}\n" }

        assertNull(editor.save("x.custom.yaml", "patch:\n  menu/page_size: 7\n"))

        assertEquals("patch:\n  menu/page_size: 7\n", File(storage.userConfigDir, "x.custom.yaml").readText())
        assertTrue("no temporary file is left", storage.userConfigDir.list()!!.none { it.startsWith(".") })
    }

    @Test
    fun anInvalidFileIsRefusedWithItsLineAndTheOldOneStays() {
        editor.load("x.custom.yaml", null) { "patch: {}\n" }

        val refusal = editor.save("x.custom.yaml", "patch:\n  a: 1\n  b: [2\n  c: 3\n")

        assertEquals(UserConfigProblem.INVALID_YAML, refusal?.problem)
        assertNotNull(refusal?.line)
        assertEquals("patch: {}\n", File(storage.userConfigDir, "x.custom.yaml").readText())
    }

    @Test
    fun aFileThatNamesOtherFoldersOrNestsDeepIsRefused() {
        editor.load("x.custom.yaml", null) { "patch: {}\n" }

        assertEquals(
            UserConfigProblem.UNSAFE_REFERENCE,
            editor.save("x.custom.yaml", "patch:\n  translator/dictionary: ../../secret\n")?.problem,
        )
        assertEquals(
            UserConfigProblem.INVALID_YAML,
            editor.save("x.custom.yaml", "a: " + "[".repeat(500) + "\n")?.problem,
        )
        assertEquals(UserConfigProblem.NOT_TEXT, editor.save("x.custom.yaml", "a: \u0000\n")?.problem)
        assertEquals("patch: {}\n", File(storage.userConfigDir, "x.custom.yaml").readText())
    }

    @Test
    fun theFolderLimitsHoldForTheEditorToo() {
        val big = "# " + "a".repeat((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) + "\n"
        editor.load("x.custom.yaml", null) { "patch: {}\n" }
        assertEquals(UserConfigProblem.TOO_LARGE, editor.save("x.custom.yaml", big)?.problem)

        for (i in 0 until UserConfigFiles.MAX_FILES) File(storage.userConfigDir, "f$i.txt").writeText("x")
        assertEquals(UserConfigProblem.TOO_MANY, editor.save("x.custom.yaml", "patch: {}\n")?.problem)
        val refusal =
            try {
                editor.load("new.custom.yaml", null) { "patch: {}\n" }
                null
            } catch (e: UserConfigException) {
                e.problem
            }
        assertEquals(UserConfigProblem.TOO_MANY, refusal)
        assertFalse(File(storage.userConfigDir, "new.custom.yaml").exists())
    }
}

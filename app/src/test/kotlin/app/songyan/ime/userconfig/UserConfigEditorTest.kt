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
    fun openingACustomFileDoesNotCreateAnOverride() {
        assertEquals("patch: {}\n", editor.load("x.custom.yaml", source) { "patch: {}\n" })
        assertFalse(File(storage.userConfigDir, "x.custom.yaml").exists())
        File(source, "x.custom.yaml").writeText("patch:\n  menu/page_size: 9\n")
        val text = editor.load("x.custom.yaml", source) { "template\n" }
        assertEquals("patch:\n  menu/page_size: 9\n", text)
        assertTrue(UserOverrides(storage).entries(source).isEmpty())
        assertNull(editor.save("x.custom.yaml", "patch:\n  menu/page_size: 5\n"))
        assertEquals(listOf("x.custom.yaml"), UserOverrides(storage).entries(source).map { it.path })
        assertEquals("patch:\n  menu/page_size: 9\n", File(source, "x.custom.yaml").readText())
    }

    @Test
    fun generatedSettingsStayOutsideTheUsersEdits() {
        val block = "__patch:\n# Rx: songyan:typo {\n  - patch:\n      typo: true\n# }\n"
        File(source, "x.custom.yaml").writeText(block)
        assertEquals("patch: {}\n", editor.load("x.custom.yaml", source) { "patch: {}\n" })
        assertFalse(File(storage.userConfigDir, "x.custom.yaml").exists())
        val copy = File(storage.userConfigDir, "x.custom.yaml")
        copy.writeText(block)
        assertTrue(UserOverrides(storage).entries(source).isEmpty())
        assertTrue(UserOverrides(storage).unacknowledged(source).isEmpty())
        copy.writeText("patch:\n  menu/page_size: 5\n" + block)
        assertEquals(listOf("x.custom.yaml"), UserOverrides(storage).entries(source).map { it.path })
        assertEquals("patch:\n  menu/page_size: 5\n", editor.load("x.custom.yaml", source) { "" })
    }

    @Test
    fun aValidFileIsSavedAndReadBack() {
        assertNull(editor.save("x.custom.yaml", "patch:\n  menu/page_size: 7\n"))

        assertEquals("patch:\n  menu/page_size: 7\n", File(storage.userConfigDir, "x.custom.yaml").readText())
        assertTrue("no temporary file is left", storage.userConfigDir.list()!!.none { it.startsWith(".") })
    }

    @Test
    fun anInvalidFileIsRefusedWithItsLineAndTheOldOneStays() {
        editor.save("x.custom.yaml", "patch: {}\n")

        val refusal = editor.save("x.custom.yaml", "patch:\n  a: 1\n  b: [2\n  c: 3\n")

        assertEquals(UserConfigProblem.INVALID_YAML, refusal?.problem)
        assertNotNull(refusal?.line)
        assertEquals("patch: {}\n", File(storage.userConfigDir, "x.custom.yaml").readText())
    }

    @Test
    fun aFileThatNamesOtherFoldersOrNestsDeepIsRefused() {
        editor.save("x.custom.yaml", "patch: {}\n")

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
        editor.save("x.custom.yaml", "patch: {}\n")
        assertEquals(UserConfigProblem.TOO_LARGE, editor.save("x.custom.yaml", big)?.problem)

        for (i in 0 until UserConfigFiles.MAX_FILES) File(storage.userConfigDir, "f$i.txt").writeText("x")
        assertEquals(UserConfigProblem.TOO_MANY, editor.save("x.custom.yaml", "patch: {}\n")?.problem)
        assertEquals(UserConfigProblem.TOO_MANY, editor.save("new.custom.yaml", "patch: {}\n")?.problem)
        assertFalse(File(storage.userConfigDir, "new.custom.yaml").exists())
    }

    @Test
    fun aScriptKeyNamesALuaFileAndNothingElse() {
        assertEquals("lua/my_filter.lua", editor.fileName(RimeEditTarget.scriptKey("my_filter")))
        for (key in listOf(
            "script:Bad",
            "script:a-b",
            "script:../x",
            "script:a/b",
            "script:",
            "script:" + "a".repeat(41),
        )) {
            assertNull(key, editor.fileName(key))
        }
        assertTrue(RimeEditTarget.isScriptName("a1_b2"))
        assertFalse(RimeEditTarget.isScriptName("A"))
        assertFalse(RimeEditTarget.isScriptName(""))
    }

    @Test
    fun aNewScriptIsMadeUnderLuaFromItsTemplateAndSavesLikeAnyFile() {
        val name = requireNotNull(editor.fileName(RimeEditTarget.scriptKey("probe")))

        val text = editor.load(name, null) { ScriptTemplate.text("probe", "How to use it.") }

        assertTrue(text.startsWith("-- How to use it.\n-- engine/filters/+: [lua_filter@*probe]\nreturn {"))
        val file = File(storage.userConfigDir, "lua/probe.lua")
        assertEquals(text, file.readText())
        assertTrue(UserConfigFiles.isScriptPath("lua/probe.lua"))
        assertNull(editor.save(name, text + "-- more\n"))
        assertTrue(file.readText().endsWith("-- more\n"))
        assertEquals(
            "the new script is one the user must confirm",
            listOf("lua/probe.lua"),
            UserScripts(storage).unconfirmed(storage.userConfigDir, source).map { it.id },
        )
    }
}

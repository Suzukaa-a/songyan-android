package app.songyan.ime.userconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The checks a user's Rime configuration passes before any deployment uses it. */
class UserConfigFilesTest {
    private val root = Files.createTempDirectory("config").toFile()
    private val edit = File(root, "edit").also { it.mkdirs() }
    private val target = File(root, "applied")

    private fun refusal(block: () -> Unit): UserConfigException {
        try {
            block()
        } catch (e: UserConfigException) {
            return e
        }
        fail("expected the configuration to be refused")
        throw AssertionError()
    }

    @Test
    fun onlyFlatTextConfigurationNamesAreFiles() {
        for (name in listOf(
            "rime_ice.custom.yaml",
            "default.custom.yaml",
            "custom_phrase.txt",
            "my.dict.yaml",
            "a+b.yaml",
        )) {
            assertTrue(name, UserConfigFiles.isAllowedName(name))
        }
        for (name in listOf(
            ".yaml",
            "x.yml",
            "x.lua",
            "x.yaml.bak",
            "../x.yaml",
            "a/b.yaml",
            ".hidden.yaml",
            "x..yaml",
            "songyan_imported.dict.yaml",
            "空格 x.yaml",
        )) {
            assertFalse(name, UserConfigFiles.isAllowedName(name))
        }
    }

    @Test
    fun aValidFolderIsCopiedAndAnythingElseInItIsLeftOut() {
        File(edit, "rime_ice.custom.yaml").writeText("patch:\n  menu/page_size: 7\n")
        File(edit, "custom_phrase.txt").writeText("松烟\tsy\t1\n")
        // Only a dictionary's header is YAML; its rows follow the end marker.
        File(edit, "mine.dict.yaml").writeText("---\nname: mine\nversion: \"1\"\n...\n松烟\tsong yan\t1\n")
        File(edit, "notes.md").writeText("ignored")

        UserConfigFiles.snapshot(edit, target)

        assertEquals(listOf("custom_phrase.txt", "mine.dict.yaml", "rime_ice.custom.yaml"), target.list()!!.sorted())
        assertEquals(UserConfigFiles.digest(edit), UserConfigFiles.digest(target))
    }

    @Test
    fun brokenYamlIsRefusedWithItsFileName() {
        File(edit, "default.custom.yaml").writeText("patch:\n  schema_list: [\n")

        val error = refusal { UserConfigFiles.snapshot(edit, target) }

        assertEquals("default.custom.yaml", error.file)
        assertEquals(UserConfigProblem.INVALID_YAML, error.problem)
    }

    @Test
    fun aByteOrderMarkIsFineButBinaryIsNotText() {
        File(edit, "a.custom.yaml").writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "patch: {}\n".toByteArray(),
        )
        UserConfigFiles.snapshot(edit, target)

        File(edit, "b.txt").writeBytes(byteArrayOf(0x00, 0x01, 0xFF.toByte()))
        val error = refusal { UserConfigFiles.snapshot(edit, target) }
        assertEquals("b.txt", error.file)
        assertEquals(UserConfigProblem.NOT_TEXT, error.problem)
    }

    @Test
    fun theSizeLimitsHold() {
        File(edit, "big.txt").writeBytes(ByteArray((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        assertEquals(UserConfigProblem.TOO_LARGE, refusal { UserConfigFiles.snapshot(edit, target) }.problem)
        File(edit, "big.txt").delete()

        val chunk = ByteArray(UserConfigFiles.MAX_FILE_BYTES.toInt()) { 'a'.code.toByte() }
        for (i in 1..3) File(edit, "part$i.txt").writeBytes(chunk)
        assertEquals(UserConfigProblem.TOO_LARGE, refusal { UserConfigFiles.snapshot(edit, target) }.problem)
        edit.listFiles()!!.forEach { it.delete() }

        for (i in 0..UserConfigFiles.MAX_FILES) File(edit, "f$i.txt").writeText("x")
        assertEquals(UserConfigProblem.TOO_MANY, refusal { UserConfigFiles.snapshot(edit, target) }.problem)
    }

    @Test
    fun theDeploymentGetsCopiesItCanRewrite() {
        File(edit, "rime_ice.custom.yaml").writeText("patch: {}\n")
        val work = File(root, "work")

        val copied = UserConfigFiles.copyInto(edit, work)
        copied.single().writeText("patch: {changed: true}\n")

        assertEquals("patch: {}\n", File(edit, "rime_ice.custom.yaml").readText())
    }

    @Test
    fun theDigestFollowsNamesAndContent() {
        assertNull(UserConfigFiles.digest(edit))
        File(edit, "a.yaml").writeText("x: 1\n")
        val first = UserConfigFiles.digest(edit)
        File(edit, "a.yaml").writeText("x: 2\n")
        assertNotEquals(first, UserConfigFiles.digest(edit))
        assertTrue(UserConfigFiles.changesSchemaList(edit).not())
        File(edit, "default.custom.yaml").writeText("patch: {}\n")
        assertTrue(UserConfigFiles.changesSchemaList(edit))
    }
}

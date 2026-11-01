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
    fun onlyTextConfigurationNamesAreFiles() {
        for (name in listOf(
            "rime_ice.custom.yaml",
            "default.custom.yaml",
            "custom_phrase.txt",
            "my.dict.yaml",
            "a+b.yaml",
            "rime.lua",
        )) {
            assertTrue(name, UserConfigFiles.isAllowedName(name))
        }
        for (name in listOf(
            ".yaml",
            "x.yml",
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
    fun pathsMirrorASourceButNeverReachTheBuildFolderOrEscape() {
        for (path in listOf("lua/date.lua", "rime.lua", "opencc/emoji.txt", "a/b/c/d/x.yaml")) {
            assertTrue(path, UserConfigFiles.isAllowedPath(path))
        }
        for (path in listOf(
            "build/x.yaml",
            "lua/build/x.yaml",
            "../x.yaml",
            "lua/../x.yaml",
            "lua//x.lua",
            "/lua/x.lua",
            "lua\\x.lua",
            ".hidden/x.yaml",
            "lua/.x.lua",
            "a/b/c/d/e/x.yaml",
            "lua/x.md",
        )) {
            assertFalse(path, UserConfigFiles.isAllowedPath(path))
        }
    }

    @Test
    fun foldersAreSnapshottedAndDeployedKeepingTheirPaths() {
        File(edit, "lua").mkdirs()
        File(edit, "lua/date.lua").writeText("return 1\n")
        File(edit, "rime_ice.schema.yaml").writeText("schema:\n  name: x\n")
        File(edit, "build").mkdirs()
        File(edit, "build/x.yaml").writeText("ignored: true\n")

        UserConfigFiles.snapshot(edit, target)

        assertEquals("return 1\n", File(target, "lua/date.lua").readText())
        assertFalse(File(target, "build").exists())
        assertEquals(UserConfigFiles.digest(edit), UserConfigFiles.digest(target))
        val work = File(root, "work")
        assertEquals(
            listOf("lua/date.lua", "rime_ice.schema.yaml"),
            UserConfigFiles.copyInto(target, work).map { UserConfigFiles.relativePath(work, it) },
        )
        assertEquals("return 1\n", File(work, "lua/date.lua").readText())
        // Same file name and content in another folder: another file.
        val flat = File(root, "flat").also { it.mkdirs() }
        val nested = File(root, "nested").also { File(it, "lua").mkdirs() }
        File(flat, "date.txt").writeText("same\n")
        File(nested, "lua/date.txt").writeText("same\n")
        assertNotEquals(UserConfigFiles.digest(flat), UserConfigFiles.digest(nested))
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

    @Test
    fun aFileThatNamesAFileOutsideTheFoldersIsRefusedByName() {
        File(edit, "x.custom.yaml").writeText("patch:\n  translator/dictionary: ../../../data/secret\n")

        val error = refusal { UserConfigFiles.snapshot(edit, target) }

        assertEquals(UserConfigProblem.UNSAFE_REFERENCE, error.problem)
        assertEquals("x.custom.yaml", error.file)
        File(edit, "x.custom.yaml").writeText("__include: /abs/path:/x\n")
        assertEquals(UserConfigProblem.UNSAFE_REFERENCE, refusal { UserConfigFiles.snapshot(edit, target) }.problem)
        File(edit, "x.custom.yaml").writeText("patch:\n  translator/dictionary: rime_ice\n")
        UserConfigFiles.snapshot(edit, target)
    }

    @Test
    fun deepNestingAndAliasBombsAreRefusedNotParsed() {
        File(edit, "deep.custom.yaml").writeText("a: " + "[".repeat(10_000) + "\n")
        assertEquals(UserConfigProblem.INVALID_YAML, refusal { UserConfigFiles.snapshot(edit, target) }.problem)

        File(edit, "deep.custom.yaml").delete()
        val bomb =
            buildString {
                append("l0: &l0 [a, a, a, a, a, a, a, a, a]\n")
                for (i in 1..12) append("l$i: &l$i [" + "*l${i - 1}, ".repeat(9) + "*l${i - 1}]\n")
            }
        File(edit, "bomb.custom.yaml").writeText(bomb)
        assertEquals(UserConfigProblem.INVALID_YAML, refusal { UserConfigFiles.snapshot(edit, target) }.problem)
    }

    @Test
    fun aParseErrorCarriesTheLine() {
        val error = UserConfigFiles.check("x.custom.yaml", "a: 1\nb: 2\nc: [3\nd: 4\n".toByteArray())

        assertEquals(UserConfigProblem.INVALID_YAML, error?.problem)
        assertTrue("line ${error?.line}", error?.line in 3..5)
    }

    @Test
    fun aNewFileNeverGetsAScriptName() {
        assertTrue(UserConfigFiles.isAllowedName("date.lua"))
        assertFalse(UserConfigFiles.isCreatableName("date.lua"))
        assertTrue(UserConfigFiles.isCreatableName("date.yaml"))
    }

    @Test
    fun scriptsLiveWhereRimeLooksForThemAndNowhereElse() {
        for (path in listOf("rime.lua", "lua/date.lua", "lua/sub/mod.lua", "lua/sub/init.lua")) {
            assertTrue(path, UserConfigFiles.isAllowedPath(path))
            assertTrue(path, UserConfigFiles.isScriptPath(path))
        }
        for (path in listOf(
            "date.lua",
            "opencc/x.lua",
            "other/lua/x.lua",
            "lua/x.lua.bak",
            "lua/.x.lua",
            "lua/../x.lua",
        )) {
            assertFalse(path, UserConfigFiles.isAllowedPath(path))
        }
        assertTrue(UserConfigFiles.isScriptFolder("lua"))
        assertTrue(UserConfigFiles.isScriptFolder("lua/sub"))
        assertFalse(UserConfigFiles.isScriptFolder("opencc"))
        assertFalse(UserConfigFiles.isScriptFolder("luax"))

        File(edit, "lua").mkdirs()
        File(edit, "lua/date.lua").writeText("return 1\n")
        File(edit, "stray.lua").writeText("return 2\n")
        File(edit, "opencc").mkdirs()
        File(edit, "opencc/x.lua").writeText("return 3\n")
        File(edit, "rime.lua").writeText("return 4\n")
        assertEquals(
            listOf("lua/date.lua", "rime.lua"),
            UserConfigFiles.files(edit).map { UserConfigFiles.relativePath(edit, it) },
        )
    }

    @Test
    fun aHiddenOrTaggedFileNameIsRefusedLikeAPlainOne() {
        for (text in listOf(
            "patch:\n  translator/user_dict/=: /data/data/app/files/rime/config/edit/leak\n",
            "patch:\n  translator/db_class/=: plain_userdb\n  translator/user_dict/+: ../../leak\n",
            "translator:\n  dictionary: !!null ../../x\n",
            "schema_list:\n  - schema: ../../edit/live\n",
        )) {
            val error = UserConfigFiles.check("x.custom.yaml", text.toByteArray())
            assertEquals(text, UserConfigProblem.UNSAFE_REFERENCE, error?.problem)
        }
    }

    @Test
    fun theAppliedCopyIsCheckedAgainBeforeItIsUsed() {
        File(edit, "ok.custom.yaml").writeText("patch:\n  menu/page_size: 7\n")
        assertNull(UserConfigFiles.verify(edit))

        File(edit, "evil.custom.yaml").writeText("translator:\n  user_dict: /abs/leak\n")
        val bad = UserConfigFiles.verify(edit)
        assertEquals(UserConfigProblem.UNSAFE_REFERENCE, bad?.problem)
        assertEquals("evil.custom.yaml", bad?.file)

        File(edit, "evil.custom.yaml").writeText("a: [1\n")
        assertEquals(UserConfigProblem.INVALID_YAML, UserConfigFiles.verify(edit)?.problem)
    }
}

package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.CRC32

class SchemePackageImportTest {
    @Test
    fun importsADirectoryQualifiedLuaComponent() {
        val prepared =
            prepare(
                zip(
                    "japanese.schema.yaml" to
                        (
                            String(schema("japanese")) +
                                "engine:\n  translators:\n    - lua_translator@*kagiroi/kagiroi_translator\n"
                        ).toByteArray(),
                    "lua/kagiroi/kagiroi_translator.lua" to "return {}\n".toByteArray(),
                ),
            )
        assertTrue(File(prepared.root, "lua/kagiroi/kagiroi_translator.lua").isFile)
    }

    @Test
    fun importsCompiledTablesWithoutRequiringTheTextDictionary() {
        val binaries =
            mapOf(
                "build/words.table.bin" to byteArrayOf(1, 2, 3),
                "build/words.reverse.bin" to byteArrayOf(4, 5),
                "build/japanese.prism.bin" to byteArrayOf(6, 7),
            )
        val prepared =
            prepare(
                zip(
                    "japanese.schema.yaml" to schema("japanese", dictionary = "words"),
                    *binaries.toList().toTypedArray(),
                ),
            )
        assertEquals(listOf("japanese"), prepared.schemaIds)
        for ((path, bytes) in binaries) {
            assertTrue(path, File(prepared.root, path).readBytes().contentEquals(bytes))
        }
        assertTrue(prepared.skippedFiles.isEmpty())
    }

    @Test
    fun importsAGithubRepositoryArchiveWithFinderMetadata() {
        val prepared = prepare(zip(*wrapped("rime-ice-main", realisticPackage()), *finderMetadata("rime-ice-main")))

        assertEquals(listOf("rime_ice", "double_pinyin"), prepared.schemaIds)
        assertEquals("rime_ice", prepared.primarySchemaId)
        assertEquals("雾凇拼音", prepared.displayName)
        val staged = stagedPaths(prepared.root)
        for (path in realisticPackage().keys.filter { !it.endsWith(".webp") && !it.endsWith(".sh") }) {
            assertTrue(path, path in staged)
        }
        assertFalse(staged.any { it.startsWith("rime-ice-main") })
        assertFalse(staged.any { it.contains("__MACOSX") || it.endsWith(".DS_Store") || it.contains("/._") })
        assertEquals(listOf("others/demo.webp", "tools/install.sh"), prepared.skippedFiles.sorted())
    }

    @Test
    fun primaryInputMethodFollowsThePackageSchemaListNotTheAlphabet() {
        // An alphabetically earlier scheme that the package does not list (a reverse-lookup table)
        // must neither become the primary input method nor be offered as one.
        val prepared = prepare(zip(*realisticPackage().toList().toTypedArray()))

        assertEquals("rime_ice", prepared.primarySchemaId)
        assertFalse("aaa_reverse" in prepared.schemaIds)
        assertTrue(File(prepared.root, "aaa_reverse.schema.yaml").isFile)
    }

    @Test
    fun keepsThePackageDefaultConfigurationByteForByte() {
        val files = realisticPackage()
        val prepared = prepare(zip(*files.toList().toTypedArray()))

        assertEquals(String(files.getValue("default.yaml")), File(prepared.root, "default.yaml").readText())
    }

    @Test
    fun supplementsOnlyWhatThePackageLacks() {
        val files = realisticPackage()
        val prepared =
            prepare(zip(*files.toList().toTypedArray())) { root ->
                // Mirrors the app's supplement: shared presets and OpenCC data, never overwriting.
                for ((path, text) in mapOf(
                    "default.yaml" to "x",
                    "symbols.yaml" to "symbols:\n",
                    "opencc/s2t.json" to "{}",
                )) {
                    val target = File(root, path)
                    if (target.exists()) continue
                    target.parentFile.mkdirs()
                    target.writeText(text)
                }
            }

        assertEquals(String(files.getValue("default.yaml")), File(prepared.root, "default.yaml").readText())
        assertEquals(String(files.getValue("opencc/emoji.json")), File(prepared.root, "opencc/emoji.json").readText())
        assertTrue(File(prepared.root, "symbols.yaml").isFile)
    }

    @Test
    fun aPackageWithoutDefaultConfigOffersEveryRootScheme() {
        val prepared =
            prepare(
                zip(
                    "b.schema.yaml" to schema("b", dictionary = "words"),
                    "a.schema.yaml" to schema("a", dictionary = "words"),
                    "words.dict.yaml" to dictionaryFile("words"),
                ),
            )

        assertEquals(listOf("a", "b"), prepared.schemaIds)
    }

    @Test
    fun learningIdentityFollowsThePackageBytes() {
        val first = prepare(zip(*realisticPackage().toList().toTypedArray()))
        val again = prepare(zip(*wrapped("other-wrapper", realisticPackage()), *finderMetadata("other-wrapper")))
        val changed =
            prepare(
                zip(
                    *realisticPackage()
                        .plus(
                            "rime_ice.dict.yaml" to dictionaryFile("rime_ice", "好\thao\t1\n"),
                        ).toList()
                        .toTypedArray(),
                ),
            )

        assertTrue(first.packageId.matches(Regex("scheme-[0-9a-f]{16}")))
        assertEquals(first.packageId, again.packageId)
        assertEquals(first.revision, again.revision)
        assertNotEquals(first.packageId, changed.packageId)
    }

    @Test
    fun acceptsUnicodeAndSpacesInPaths() {
        val prepared =
            prepare(
                zip(
                    *realisticPackage().toList().toTypedArray(),
                    "说明/输入 方案.txt" to "说明\n".toByteArray(),
                ),
            )

        assertTrue(File(prepared.root, "说明/输入 方案.txt").isFile)
    }

    @Test
    fun rejectsTraversalAbsoluteAndEscapedPaths() {
        for (path in listOf(
            "../escape.yaml",
            "/absolute.yaml",
            "a/../../b.yaml",
            "C:/drive.yaml",
            "a\\b.yaml",
            "a//b.yaml",
        )) {
            assertReason(SchemePackageImportError.Reason.UNSAFE_PATH, path) {
                prepare(rawZip(*realisticPackage().toList().toTypedArray(), path to "x".toByteArray()))
            }
        }
    }

    @Test
    fun rejectsDuplicatePathsIgnoringCase() {
        assertReason(SchemePackageImportError.Reason.DUPLICATE_PATH) {
            prepare(
                rawZip(
                    *realisticPackage().toList().toTypedArray(),
                    "Notes.txt" to "a".toByteArray(),
                    "notes.TXT" to "b".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun reportsGbkEntryNamesAsAnEncodingProblem() {
        val gbk = Charset.forName("GBK")
        val archive =
            rawZip(
                *realisticPackage().toList().toTypedArray(),
                "说明.txt" to "x".toByteArray(),
                nameCharset = gbk,
            )

        assertReason(SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING) { prepare(archive) }
    }

    @Test
    fun rejectsLearningData() {
        assertReason(SchemePackageImportError.Reason.USER_DATA) {
            prepare(zip(*realisticPackage().toList().toTypedArray(), "rime_ice.userdb/CURRENT" to "x".toByteArray()))
        }
    }

    @Test
    fun learnedWordSnapshotsAreNotSourceFilesAndDoNotChangeThePackage() {
        val base = realisticPackage().toList().toTypedArray()
        val plain = prepare(zip(*base))
        val withSnapshots =
            prepare(
                zip(
                    *base,
                    "rime_ice.userdb.txt" to "# Rime user dictionary\n".toByteArray(),
                    "sync/phone/double_pinyin.userdb.TXT" to "x".toByteArray(),
                ),
            )

        assertEquals(plain.packageId, withSnapshots.packageId)
        assertEquals(plain.revision, withSnapshots.revision)
        assertEquals(stagedPaths(plain.root), stagedPaths(withSnapshots.root))
    }

    @Test
    fun aLearningDatabaseStillBlocksTheImportEvenWithSnapshotsBeside() {
        assertReason(SchemePackageImportError.Reason.USER_DATA) {
            prepare(
                zip(
                    *realisticPackage().toList().toTypedArray(),
                    "rime_ice.userdb/words.userdb.txt" to "x".toByteArray(),
                ),
            )
        }
        assertReason(SchemePackageImportError.Reason.USER_DATA) {
            prepare(zip(*realisticPackage().toList().toTypedArray(), "rime_ice.userdb.bak" to "x".toByteArray()))
        }
    }

    @Test
    fun rejectsBytesThatAreNotAZip() {
        assertReason(SchemePackageImportError.Reason.INVALID_ZIP) { prepare("not a zip".toByteArray()) }
        assertReason(SchemePackageImportError.Reason.INVALID_ZIP) { prepare(byteArrayOf(0x50, 0x4B, 3, 4, 0)) }
    }

    @Test
    fun enforcesTheIosCaps() {
        val defaults = SchemePackageLimits()
        assertEquals(1536L * 1024 * 1024, defaults.archiveBytes)
        assertEquals(2000, defaults.entryCount)
        assertEquals(128L * 1024 * 1024, defaults.sourceFileBytes)
        assertEquals(256L * 1024 * 1024, defaults.sourceTotalBytes)
        assertEquals(512L * 1024 * 1024, defaults.modelFileBytes)

        val archive = zip(*realisticPackage().toList().toTypedArray())
        assertReason(SchemePackageImportError.Reason.TOO_LARGE) {
            prepare(archive, limits = SchemePackageLimits(archiveBytes = 16))
        }
        assertReason(SchemePackageImportError.Reason.TOO_MANY_ENTRIES) {
            prepare(archive, limits = SchemePackageLimits(entryCount = 3))
        }
        assertReason(SchemePackageImportError.Reason.TOO_LARGE) {
            prepare(archive, limits = SchemePackageLimits(sourceFileBytes = 64))
        }
        assertReason(SchemePackageImportError.Reason.TOO_LARGE) {
            prepare(archive, limits = SchemePackageLimits(sourceTotalBytes = 256))
        }
    }

    @Test
    fun rejectsPackagesWithoutARootScheme() {
        assertReason(SchemePackageImportError.Reason.MISSING_SCHEMA) {
            prepare(zip("nested/a.schema.yaml" to schema("a"), "README.md" to "x".toByteArray()))
        }
    }

    @Test
    fun rejectsAListedSchemaWhoseIdDoesNotMatchItsFile() {
        assertReason(SchemePackageImportError.Reason.INVALID_SCHEMA) {
            prepare(
                zip(
                    "default.yaml" to "schema_list:\n  - schema: a\n".toByteArray(),
                    "a.schema.yaml" to schema("other"),
                ),
            )
        }
    }

    @Test
    fun rejectsConfigurationThatIsNotUtf8() {
        assertReason(SchemePackageImportError.Reason.INVALID_ENCODING) {
            prepare(
                zip(
                    "default.yaml" to "schema_list:\n  - schema: a\n".toByteArray(),
                    "a.schema.yaml" to "schema:\n  schema_id: a\n  name: 中文\n".toByteArray(Charset.forName("GBK")),
                ),
            )
        }
    }

    @Test
    fun checksTheWholeDependencyClosure() {
        val base = realisticPackage()
        for (removed in listOf(
            "rime_ice.dict.yaml",
            "cn_dicts/base.dict.yaml",
            "lua/date_translator.lua",
            "lua/lib/util.lua",
            "symbols_v.yaml",
            "opencc/emoji.json",
            "opencc/emoji.txt",
            "double_pinyin.schema.yaml",
        )) {
            val error =
                assertReason(SchemePackageImportError.Reason.MISSING_DEPENDENCY, removed) {
                    prepare(zip(*base.minus(removed).toList().toTypedArray()))
                }
            assertTrue(removed + " in " + error.paths, removed in error.paths)
        }
    }

    @Test
    fun aReverseLookupSchemeAndThePresetVocabularyMayLackWhatADesktopRimeShares() {
        // 薄荷's stroke lookup needs luna_pinyin and essay.txt, which neither 薄荷 nor 白霜 ships.
        val files =
            arrayOf(
                "default.yaml" to "schema_list:\n  - schema: mint\n".toByteArray(),
                "mint.schema.yaml" to
                    (
                        "schema: {schema_id: mint, dependencies: [stroke, absent_lookup]}\n" +
                            "translator: {dictionary: mint}\n"
                    ).toByteArray(),
                "mint.dict.yaml" to "---\nname: mint\nversion: '1'\n...\n".toByteArray(),
                "stroke.schema.yaml" to
                    (
                        "schema: {schema_id: stroke, dependencies: [luna_pinyin]}\n" +
                            "translator: {dictionary: stroke}\nreverse_lookup: {dictionary: luna_pinyin}\n"
                    ).toByteArray(),
                "stroke.dict.yaml" to "---\nname: stroke\nuse_preset_vocabulary: true\n...\n".toByteArray(),
            )
        assertEquals(listOf("mint"), prepare(zip(*files)).schemaIds)

        // What the input method in the list needs itself still stops the import.
        val error =
            assertReason(SchemePackageImportError.Reason.MISSING_DEPENDENCY) {
                prepare(zip(*files.filter { it.first != "mint.dict.yaml" }.toTypedArray()))
            }
        assertEquals(listOf("mint.dict.yaml"), error.paths)
    }

    @Test
    fun followsIncludesAndPatchesButNotOptionalOrLocalReferences() {
        val prepared =
            prepare(
                zip(
                    "sample.schema.yaml" to
                        (
                            "schema: {schema_id: sample}\n" +
                                "algebra_common: [erase/^xx$/]\n" +
                                "speller: {__include: algebra_common}\n" +
                                "__patch: [local_patch, ':/local_patch', " +
                                "'external.yaml:/patch', {menu/page_size: 7}]\n" +
                                "local_patch: {__include: '/algebra_common'}\n" +
                                "optional: {__include: 'absent:/node?'}\n" +
                                "translator: {dictionary: null}\n" +
                                "literal: |\n  __include: not_a_dependency:/node\n"
                        ).toByteArray(),
                    "external.yaml" to "patch: {__include: 'nested/config:/patch'}\n".toByteArray(),
                    "nested/config.yaml" to "patch: {menu/page_size: 5}\n".toByteArray(),
                ),
            )
        assertEquals(listOf("sample"), prepared.schemaIds)
        assertReason(SchemePackageImportError.Reason.MISSING_DEPENDENCY) {
            prepare(
                zip(
                    "sample.schema.yaml" to
                        "schema: {schema_id: sample}\n__patch: ['external.yaml:/patch']\n".toByteArray(),
                    "external.yaml" to "patch: {__include: 'nested/config:/patch'}\n".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun validatesGrammarModels() {
        val prepared =
            prepare(
                zip(
                    *realisticPackage().toList().toTypedArray(),
                    "wanxiang-lts-zh-hans.gram" to byteArrayOf(1, 2, 3),
                ),
            )
        assertTrue(File(prepared.root, "wanxiang-lts-zh-hans.gram").isFile)
        assertReason(SchemePackageImportError.Reason.INVALID_MODEL) {
            prepare(zip(*realisticPackage().toList().toTypedArray(), "model.gram" to ByteArray(0)))
        }
        assertReason(SchemePackageImportError.Reason.INVALID_MODEL) {
            prepare(zip(*realisticPackage().toList().toTypedArray(), "models/.gram" to byteArrayOf(1)))
        }
    }

    @Test
    fun refusesCustomizationsOfTheBuiltInInputMethods() {
        assertReason(SchemePackageImportError.Reason.BUILTIN_CUSTOMIZATION) {
            prepare(
                zip(
                    *realisticPackage().toList().toTypedArray(),
                    "songyan_quanpin.custom.yaml" to "patch:\n  menu/page_size: 9\n".toByteArray(),
                ),
            )
        }
        // Customizing the package's own configuration is ordinary Rime usage.
        prepare(
            zip(
                *realisticPackage().toList().toTypedArray(),
                "rime_ice.custom.yaml" to "patch:\n  menu/page_size: 9\n".toByteArray(),
                "default.custom.yaml" to "patch:\n  menu/page_size: 9\n".toByteArray(),
            ),
        )
    }

    @Test
    fun aLiteralDefaultCustomPatchChoosesTheInputMethods() {
        val prepared =
            prepare(
                zip(
                    *realisticPackage().toList().toTypedArray(),
                    "default.custom.yaml" to "patch:\n  schema_list:\n    - schema: double_pinyin\n".toByteArray(),
                ),
            )

        assertEquals(listOf("double_pinyin"), prepared.schemaIds)
    }

    @Test
    fun fakeRequiresInCommentsAndStringsAreNotDependencies() {
        // Only a real call names a dependency: comments and string literals are plain text, however
        // much they look like Lua code.
        val helper =
            """
            -- require("fake/line-comment")
            --[[ require("fake/long-comment") ]]
            --[=[ require("fake/long-bracket-comment") ]=]
            --[==[ require("fake/level-two-comment") ]==]
            local text = "require('fake/normal-string')"
            local escaped = "an escaped quote \"require('fake/escaped-string')\""
            local long = [[require("fake/long-string")]]
            local longer = [=[require("fake/long-bracket-string")]=]
            local longest = [==[require("fake/level-two-string")]==]
            return {}
            """.trimIndent()
        val prepared = prepare(zip(*luaPackage(helper)))

        assertEquals(listOf("lexing"), prepared.schemaIds)
    }

    @Test
    fun aRealRequireWrittenAroundCommentsWhitespaceAndParenthesesResolves() {
        val helper =
            """
            local first = require --[[ between ]] ( "lexing/first" )
            local second = require
              ( 'lexing/second' )
            local third = require --[=[ bracket ]=] ( "lexing/second" )
            return { first, second, third }
            """.trimIndent()
        val prepared = prepare(zip(*luaPackage(helper)))

        assertEquals(listOf("lexing"), prepared.schemaIds)
    }

    @Test
    fun aRealRequireWrittenAroundCommentsStillFailsTheImportWhenMissing() {
        // The closure must not depend on the call being on one line or on the comment being short.
        val helper =
            """
            local block = require --[[ between ]] ( "lexing/missing-block" )
            local line = require -- between
              ( "lexing/missing-line" )
            return { block, line }
            """.trimIndent()
        val error =
            assertReason(SchemePackageImportError.Reason.MISSING_DEPENDENCY) {
                prepare(zip(*luaPackage(helper)))
            }

        assertTrue("block in " + error.message, "lua/lexing/missing-block.lua" in error.paths)
        assertTrue("line in " + error.message, "lua/lexing/missing-line.lua" in error.paths)
    }

    @Test
    fun aStringWithDashesAndAShortCommentDoNotSwallowARealRequire() {
        // "--" inside a string is data, and "--[" after it opens an ordinary short comment rather
        // than a long one, so the next require is still a dependency.
        val helper =
            """
            local separator = "--"
            --[ require("fake/not-a-long-comment")
            local missing = require("lexing/missing-after-string")
            return { separator, missing }
            """.trimIndent()
        val error =
            assertReason(SchemePackageImportError.Reason.MISSING_DEPENDENCY) {
                prepare(zip(*luaPackage(helper)))
            }

        assertEquals(listOf("lua/lexing/missing-after-string.lua"), error.paths)
    }

    @Test
    fun aModulePathThatLeavesLuaIsStillRefused() {
        val helper =
            """
            local escaped = require("../escape")
            return { escaped }
            """.trimIndent()
        assertReason(SchemePackageImportError.Reason.UNSAFE_PATH) {
            prepare(zip(*luaPackage(helper)))
        }
    }

    private fun prepare(
        archive: ByteArray,
        limits: SchemePackageLimits = SchemePackageLimits(),
        supplement: (File) -> Unit = {},
    ): PreparedSchemeImport {
        val directory = Files.createTempDirectory("scheme-import").toFile()
        directory.deleteOnExit()
        val file = File(directory, "package.zip")
        file.writeBytes(archive)
        return SchemePackageImportBuilder.prepare(file, File(directory, "out"), supplement, limits)
    }

    private fun stagedPaths(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toSet()

    private fun assertReason(
        reason: SchemePackageImportError.Reason,
        context: String = "",
        block: () -> Unit,
    ): SchemePackageImportError {
        try {
            block()
        } catch (error: SchemePackageImportError) {
            assertEquals(context + ": " + error.message, reason, error.reason)
            return error
        }
        fail("expected $reason $context")
        throw AssertionError()
    }

    /**
     * Shaped like rime-ice: two listed schemes, an unlisted auxiliary scheme that sorts first, a
     * dictionary with import_tables, Lua components (module and nested require), an import_preset,
     * an OpenCC config with its data, a README, an image and an installer script.
     */
    private fun realisticPackage(): Map<String, ByteArray> =
        linkedMapOf(
            "default.yaml" to
                (
                    "config_version: '2024'\nschema_list:\n  - schema: rime_ice\n  - schema: double_pinyin\n" +
                        "menu:\n  page_size: 5\n"
                ).toByteArray(),
            "rime_ice.schema.yaml" to
                (
                    "schema:\n  schema_id: rime_ice\n  name: 雾凇拼音\n" +
                        "engine:\n  translators:\n    - script_translator\n    - lua_translator@*date_translator\n" +
                        "translator:\n  dictionary: rime_ice\n" +
                        "punctuator:\n  import_preset: symbols_v\n" +
                        "emoji:\n  opencc_config: emoji.json\n"
                ).toByteArray(),
            "double_pinyin.schema.yaml" to
                (
                    "schema:\n  schema_id: double_pinyin\n  name: 小鹤双拼\n" +
                        "translator:\n  dictionary: rime_ice\n"
                ).toByteArray(),
            "aaa_reverse.schema.yaml" to
                "schema:\n  schema_id: aaa_reverse\ntranslator:\n  dictionary: rime_ice\n".toByteArray(),
            "rime_ice.dict.yaml" to
                "---\nname: rime_ice\nversion: '1'\nimport_tables:\n  - cn_dicts/base\n...\n".toByteArray(),
            "cn_dicts/base.dict.yaml" to dictionaryFile("base"),
            "symbols_v.yaml" to "punctuator:\n  half_shape: {}\n".toByteArray(),
            "lua/date_translator.lua" to "local util = require(\"lib.util\")\nreturn function() end\n".toByteArray(),
            "lua/lib/util.lua" to "local M = {}\nreturn M\n".toByteArray(),
            "opencc/emoji.json" to
                (
                    "{\"name\":\"emoji\"," +
                        "\"conversion_chain\":[{\"dict\":{\"type\":\"text\",\"file\":\"emoji.txt\"}}]}"
                ).toByteArray(),
            "opencc/emoji.txt" to "笑\t笑 😄\n".toByteArray(),
            "README.md" to "# rime-ice\n".toByteArray(),
            "LICENSE" to "GPL-3.0\n".toByteArray(),
            "others/demo.webp" to byteArrayOf(1, 2, 3),
            "tools/install.sh" to "#!/bin/sh\n".toByteArray(),
        )

    private fun wrapped(
        root: String,
        files: Map<String, ByteArray>,
    ): Array<Pair<String, ByteArray>> = files.map { (path, data) -> "$root/$path" to data }.toTypedArray()

    private fun finderMetadata(root: String): Array<Pair<String, ByteArray>> =
        arrayOf(
            "$root/.DS_Store" to byteArrayOf(0, 0, 0, 1),
            "$root/._rime_ice.schema.yaml" to byteArrayOf(0, 5, 22, 7, 0xFF.toByte()),
            "__MACOSX/$root/._rime_ice.schema.yaml" to byteArrayOf(0, 5, 22, 7, 0xFF.toByte()),
            "__MACOSX/._$root" to byteArrayOf(0, 5, 22, 7),
        )

    private fun schema(
        id: String,
        dictionary: String? = null,
    ): ByteArray =
        (
            "schema:\n  schema_id: $id\n  name: $id\n" +
                (if (dictionary != null) "translator:\n  dictionary: $dictionary\n" else "")
        ).toByteArray()

    /**
     * One listed schema that mounts a Lua component, so the helper module is read as source, plus the
     * two real modules the lexing tests resolve.
     */
    private fun luaPackage(helper: String): Array<Pair<String, ByteArray>> =
        arrayOf(
            "default.yaml" to "schema_list:\n  - schema: lexing\n".toByteArray(),
            "lexing.schema.yaml" to
                (
                    "schema:\n  schema_id: lexing\n  name: Lexing\n" +
                        "engine:\n  translators:\n    - lua_translator@*lexing/helper\n"
                ).toByteArray(),
            "lua/lexing/helper.lua" to helper.toByteArray(),
            "lua/lexing/first.lua" to "return {}\n".toByteArray(),
            "lua/lexing/second.lua" to "return {}\n".toByteArray(),
        )

    private fun dictionaryFile(
        name: String,
        rows: String = "你\tni\t100\n",
    ): ByteArray = "---\nname: $name\nversion: '1'\n...\n$rows".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = rawZip(*entries)

    /**
     * Stored ZIP with a central directory. `ZipOutputStream` refuses duplicate names and always
     * writes UTF-8, so the duplicate-name and GBK-name cases need a hand-written archive.
     */
    private fun rawZip(
        vararg entries: Pair<String, ByteArray>,
        nameCharset: Charset = Charsets.UTF_8,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val utf8Flag = if (nameCharset == Charsets.UTF_8) 0x0800L else 0L
        for ((name, data) in entries) {
            val offset = output.size().toLong()
            val nameBytes = name.toByteArray(nameCharset)
            val crc = CRC32().apply { update(data) }.value
            output.le(0x04034b50L, 4)
            output.le(20, 2)
            output.le(utf8Flag, 2)
            output.le(0, 2)
            output.le(0, 4)
            output.le(crc, 4)
            output.le(data.size.toLong(), 4)
            output.le(data.size.toLong(), 4)
            output.le(nameBytes.size.toLong(), 2)
            output.le(0, 2)
            output.write(nameBytes)
            output.write(data)
            central.le(0x02014b50L, 4)
            central.le(20, 2)
            central.le(20, 2)
            central.le(utf8Flag, 2)
            central.le(0, 2)
            central.le(0, 4)
            central.le(crc, 4)
            central.le(data.size.toLong(), 4)
            central.le(data.size.toLong(), 4)
            central.le(nameBytes.size.toLong(), 2)
            central.le(0, 2)
            central.le(0, 2)
            central.le(0, 2)
            central.le(0, 2)
            central.le(0, 4)
            central.le(offset, 4)
            central.write(nameBytes)
        }
        val centralOffset = output.size().toLong()
        output.write(central.toByteArray())
        output.le(0x06054b50L, 4)
        output.le(0, 2)
        output.le(0, 2)
        output.le(entries.size.toLong(), 2)
        output.le(entries.size.toLong(), 2)
        output.le(central.size().toLong(), 4)
        output.le(centralOffset, 4)
        output.le(0, 2)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.le(
        value: Long,
        bytes: Int,
    ) {
        for (index in 0 until bytes) write(((value shr (8 * index)) and 0xFF).toInt())
    }
}

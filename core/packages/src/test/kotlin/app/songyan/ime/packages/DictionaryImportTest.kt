package app.songyan.ime.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class DictionaryImportTest {
    @Test
    fun stagesTheBuiltInSchemesPatchedToTheImportedDictionary() {
        val prepared = prepare(dictionary("你好\tni hao\t10\n绿色\tlǜ sè\t12\n"))
        assertEquals(
            listOf(
                "LICENSE",
                "default.yaml",
                "import-sources/words.dict.yaml",
                "import.json",
                "keyboard_47246213a538cb99d374_14.schema.yaml",
                "songyan_basic.dict.yaml",
                "songyan_flypy.schema.yaml",
                "songyan_imported.dict.yaml",
                "songyan_quanpin.schema.yaml",
                "user-dictionary.json",
            ),
            prepared.files.keys.toList(),
        )
        for (path in listOf("songyan_quanpin.schema.yaml", "songyan_flypy.schema.yaml")) {
            val schema = stagedText(prepared, path)
            assertTrue(path, schema.contains("  dictionary: songyan_imported\n"))
            assertTrue(path, schema.contains("  user_dict: songyan_shared\n"))
            assertFalse(path, schema.contains("songyan_basic"))
        }
        assertEquals(2, prepared.entryCount)
        assertEquals("songyan_imported", prepared.dictionaryName)
        assertEquals("main", prepared.displayName)
        assertEquals("user-pinyin", prepared.sourceId)
        assertEquals("songyan_quanpin", prepared.schemaId)
        assertEquals(sha256(dictionary("你好\tni hao\t10\n绿色\tlǜ sè\t12\n")), prepared.revision)
        assertEquals(prepared.revision, prepared.sha256)
    }

    @Test
    fun generatesTheImportedDictionaryWithThePinnedHeader() {
        val bytes = dictionary("你好\tni hao\t10\n")
        val prepared = prepare(bytes)
        val generated = stagedText(prepared, "songyan_imported.dict.yaml")
        assertTrue(generated.startsWith("#"))
        assertTrue(generated.contains("---\nname: songyan_imported\n"))
        assertTrue(generated.contains("version: \"" + prepared.revision + "\"\n"))
        assertTrue(generated.contains("sort: by_weight\n"))
        assertTrue(generated.contains("use_preset_vocabulary: false\n"))
        assertTrue(generated.contains("import_tables:\n  - songyan_basic\n"))
        assertTrue(generated.indexOf("import_tables:") < generated.indexOf("\n...\n"))
        assertTrue(generated.endsWith("你好\tni hao\t10\n"))
        assertEquals(entryLines(prepared), listOf("你好\tni hao\t10"))
    }

    @Test
    fun keepsTheBuiltInStarterDictionaryAndAugmentsIt() {
        val builtin = builtinTemplate()
        val prepared = prepare(dictionary("你好\tni hao\t10\n"), "words.dict.yaml", builtin)
        assertArrayEquals(
            builtin.getValue("songyan_basic.dict.yaml"),
            prepared.files.getValue("songyan_basic.dict.yaml"),
        )
        assertEquals(
            "# User-imported pinyin dictionary layered over the built-in starter dictionary;\n" +
                "# the original files and their notices are in import-sources/.\n" +
                "---\nname: songyan_imported\nversion: \"" + prepared.revision + "\"\n" +
                "sort: by_weight\nuse_preset_vocabulary: false\n" +
                "import_tables:\n  - songyan_basic\n...\n你好\tni hao\t10\n",
            stagedText(prepared, "songyan_imported.dict.yaml"),
        )
        // Imported rows follow the header, so the engine layers them over the built-in words.
        assertEquals(listOf("你好\tni hao\t10"), entryLines(prepared))
        assertEquals(1, prepared.entryCount)
    }

    @Test
    fun excludesTheBuiltInPackageManifest() {
        val builtin = builtinTemplate()
        assertTrue(builtin.containsKey("manifest.yaml"))
        val prepared = prepare(dictionary("一\tyi\n"), "a.dict.yaml", builtin)
        assertFalse(prepared.files.containsKey("manifest.yaml"))
        // Every other built-in file still reaches the staged source verbatim.
        assertEquals(
            builtin.keys - setOf("manifest.yaml"),
            prepared.files.keys -
                setOf(
                    "songyan_imported.dict.yaml",
                    "user-dictionary.json",
                    "LICENSE",
                    "import.json",
                    "import-sources/a.dict.yaml",
                ),
        )
        assertArrayEquals(
            builtin.getValue("songyan_basic.dict.yaml"),
            prepared.files.getValue("songyan_basic.dict.yaml"),
        )
    }

    @Test
    fun rewritesToneMarksCaseAndUmlautsLikeIos() {
        val cases =
            listOf(
                Triple("你好", "nǐ hǎo", "你好\tni hao\t1"),
                Triple("你好", "NI HAO", "你好\tni hao\t1"),
                Triple("绿色", "lǜ sè", "绿色\tlv se\t1"),
                Triple("绿色", "lu: se", "绿色\tlv se\t1"),
                Triple("女儿", "nǚ ér", "女儿\tnv er\t1"),
                Triple("女", "nu\u0308", "女\tnv\t1"),
                Triple("略", "lu:e", "略\tlve\t1"),
                Triple("女", "nüe", "女\tnve\t1"),
                Triple("局", "jü", "局\tju\t1"),
                Triple("去", "qv", "去\tqu\t1"),
                Triple("学", "xve", "学\txue\t1"),
                Triple("与", "yv", "与\tyu\t1"),
                Triple("儿", "r", "儿\ter\t1"),
                Triple("田", "tian2", "田\ttian\t1"),
                Triple("水", "shui3", "水\tshui\t1"),
                Triple("中", "zhōng", "中\tzhong\t1"),
                Triple("鹅", "é", "鹅\te\t1"),
                Triple("一", "yi1", "一\tyi\t1"),
                Triple("女", "nv", "女\tnv\t1"),
                Triple("绿", "lv", "绿\tlv\t1"),
                Triple("略", "LU:E", "略\tlve\t1"),
            )
        for ((word, code, expected) in cases) {
            assertEquals(code, expected, entryLines(prepare(dictionary(word + "\t" + code + "\t1\n"))).single())
        }
    }

    @Test
    fun rejectsReadingsOutsideFullPinyin() {
        for (code in listOf("tm", "xyz", "m", "ng", "ngo")) {
            expectFailure(DictionaryImportError.Reason.UNKNOWN_SYLLABLE) {
                prepare(dictionary("一\t" + code + "\n"))
            }
        }
        expectFailure(DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH) {
            prepare(dictionary("你好\tni hao hao\n"))
        }
        expectFailure(DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH) {
            prepare(dictionary("你好\tni\n"))
        }
    }

    @Test
    fun countsSupplementaryIdeographsAsOneWordCharacter() {
        val wide = ideograph(0x33479)
        assertEquals(listOf(wide + "\tchun\t1"), entryLines(prepare(dictionary(wide + "\tchun\n"))))
        expectFailure(DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH) {
            prepare(dictionary(wide + "一\tchun\n"))
        }
        expectFailure(DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH) {
            prepare(dictionary(wide + "\tchun chun\n"))
        }
    }

    @Test
    fun acceptsOnlyAssignedIdeographs() {
        val accepted =
            listOf(
                0x3400,
                0x4DBF,
                0x4E00,
                0x9FFF,
                0xFA11,
                0x20000,
                0x2A6DF,
                0x2B81D,
                0x2EE5D,
                0x3134A,
                0x323B0,
                0x3339C,
                0x33479,
            )
        for (value in accepted) {
            assertEquals(value.toString(), 1, prepare(dictionary(ideograph(value) + "\tchun\n")).entryCount)
        }
        val rejected = listOf(0x4DC0, 0x2A6E0, 0x2EE5E, 0x3347A, 0xE000, 0x1F600)
        for (value in rejected) {
            expectFailure(DictionaryImportError.Reason.NON_HAN_WORD) {
                prepare(dictionary(ideograph(value) + "\tchun\n"))
            }
        }
        for (word in listOf("A", "a", "😀", "一A", "一 ")) {
            expectFailure(DictionaryImportError.Reason.NON_HAN_WORD) {
                prepare(dictionary(word + "\tchun\n"))
            }
        }
        expectFailure(DictionaryImportError.Reason.NON_HAN_WORD) {
            prepare(dictionary("\tyi\n"))
        }
    }

    @Test
    fun acceptsTheCompatibilityIdeographsTheIosImporterKept() {
        val rows = StringBuilder()
        for (value in 0x2F800..0x2FA1D) rows.append(ideograph(value)).append("\tli\n")
        val prepared = prepare(dictionary(rows.toString()), "compatibility.dict.yaml")
        assertEquals(542, prepared.entryCount)
        val generated = stagedText(prepared, "songyan_imported.dict.yaml")
        for (value in 0x2F800..0x2FA1D) {
            assertTrue(value.toString(), generated.contains(ideograph(value) + "\tli\t1\n"))
        }
        for (value in listOf(0x2F7FF, 0x2FA1E, 0x2FA1F, 0x2FA20)) {
            expectFailure(DictionaryImportError.Reason.NON_HAN_WORD) {
                prepare(dictionary(ideograph(value) + "\tli\n"))
            }
        }
    }

    @Test
    fun rejectsInvalidEncodingAndEmbeddedNul() {
        expectFailure(DictionaryImportError.Reason.INVALID_ENCODING) {
            prepare(byteArrayOf(0xFF.toByte()))
        }
        expectFailure(DictionaryImportError.Reason.INVALID_ENCODING) {
            prepare("name: main\n...\n一\tyi\u0000\n".toByteArray(Charsets.UTF_8))
        }
        val withBom = ("\uFEFF---\nname: main\n...\n一\tyi\n").toByteArray(Charsets.UTF_8)
        assertEquals(1, prepare(withBom).entryCount)
    }

    @Test
    fun acceptsCrlfLineEndings() {
        val bytes = "---\r\nname: main\r\n...\r\n你好\tni hao\t1\r\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("你好\tni hao\t1"), entryLines(prepare(bytes)))
    }

    @Test
    fun rejectsUnsafeDictionaryFileNames() {
        expectFailure(DictionaryImportError.Reason.UNSUPPORTED_FILE_TYPE) {
            prepare(dictionary("一\tyi\n"), "a.schema.yaml")
        }
        expectFailure(DictionaryImportError.Reason.UNSUPPORTED_FILE_TYPE) {
            prepare(dictionary("一\tyi\n"), "a.yaml")
        }
        val unsafe =
            listOf(
                "../a.dict.yaml",
                "/abs/a.dict.yaml",
                "dir//a.dict.yaml",
                "dir/./a.dict.yaml",
                "dir/../a.dict.yaml",
                "a b.dict.yaml",
                "词库.dict.yaml",
                "a".repeat(250) + ".dict.yaml",
            )
        for (name in unsafe) {
            expectFailure(DictionaryImportError.Reason.UNSAFE_FILE_NAME) {
                prepare(dictionary("一\tyi\n"), name)
            }
        }
        expectFailure(DictionaryImportError.Reason.UNSAFE_FILE_NAME) {
            prepare(dictionary("一\tyi\n", header = "name: ../evil\n"))
        }
        assertEquals(1, prepare(dictionary("一\tyi\n"), "dicts/a.dict.yaml").entryCount)
    }

    @Test
    fun requiresTheHeaderToEndWithinTheHeaderLineCap() {
        expectFailure(DictionaryImportError.Reason.MISSING_HEADER_END) {
            prepare("name: main\n一\tyi\n".toByteArray(Charsets.UTF_8))
        }
        val cap = DictionaryImportBuilder.MAX_HEADER_LINES
        // The closing marker is the (cap + 1)-th header line here, one past the limit.
        expectFailure(DictionaryImportError.Reason.MISSING_HEADER_END) {
            prepare(("name: main\n" + "# filler\n".repeat(cap - 1) + "...\n一\tyi\n").toByteArray(Charsets.UTF_8))
        }
        val lastAllowed =
            ("name: main\n" + "# filler\n".repeat(cap - 2) + "...\n一\tyi\n").toByteArray(Charsets.UTF_8)
        assertEquals(1, prepare(lastAllowed).entryCount)
    }

    @Test
    fun acceptsLongCommentOnlyHeadersLikeRimeIce() {
        // rime-ice base.dict.yaml documents its header in more than 3,000 comment lines.
        val comments = ("# " + "rime-ice dictionary header note\n").repeat(3300)
        val bytes =
            ("---\n" + comments + "name: main\nversion: \"1\"\n...\n你好\tni hao\t1\n")
                .toByteArray(Charsets.UTF_8)
        val prepared = prepare(bytes)
        assertEquals(1, prepared.entryCount)
        assertEquals(listOf("你好\tni hao\t1"), entryLines(prepared))
        assertEquals("main", prepared.displayName)
    }

    @Test
    fun rejectsYamlItDoesNotFullyUnderstand() {
        val headers =
            listOf(
                "__include: evil\n",
                "name: main\nname: other\n",
                "name: main other\n",
                "name: main\n  indented: value\n",
                "name: main\ncolumns: text\n",
                "name: main\nimport_tables: extra\n",
                "name: \"main\n",
                "name: {aliased: main}\n",
                "name: main\n? complex\n",
            )
        for (header in headers) {
            expectFailure(DictionaryImportError.Reason.UNSUPPORTED_YAML) {
                prepare(dictionary("一\tyi\n", header = header))
            }
        }
    }

    @Test
    fun rejectsDictionariesThatNeedFurtherProcessing() {
        expectFailure(DictionaryImportError.Reason.UNSUPPORTED_PRESET_VOCABULARY) {
            prepare(dictionary("一\tyi\n", header = "name: main\nuse_preset_vocabulary: true\n"))
        }
        expectFailure(DictionaryImportError.Reason.UNSUPPORTED_SORT_ORDER) {
            prepare(dictionary("一\tyi\n", header = "name: main\nsort: original\n"))
        }
        expectFailure(DictionaryImportError.Reason.MISSING_DEPENDENCIES) {
            prepare(dictionary("一\tyi\n", header = "name: main\nimport_tables:\n  - dicts/extra\n"))
        }
        expectFailure(DictionaryImportError.Reason.UNSAFE_FILE_NAME) {
            prepare(dictionary("一\tyi\n", header = "name: main\nimport_tables: [../outside]\n"))
        }
        val noDependencies = dictionary("一\tyi\n", header = "name: main\nimport_tables: []\n")
        assertEquals(1, prepare(noDependencies).entryCount)
        val byWeight = dictionary("一\tyi\n", header = "name: main\nsort: by_weight\nuse_preset_vocabulary: false\n")
        assertEquals(1, prepare(byWeight).entryCount)
    }

    @Test
    fun validatesDeclaredColumns() {
        val reordered =
            dictionary("一\t1\tyi\n", header = "name: main\ncolumns: [text, weight, code]\n")
        assertEquals(listOf("一\tyi\t1"), entryLines(prepare(reordered)))
        val explicitDefault =
            dictionary("一\tyi\t1\n", header = "name: main\ncolumns: [text, code, weight]\n")
        assertEquals(1, prepare(explicitDefault).entryCount)
        for (columns in listOf("[text, weight]", "[text]", "[text, text, code]", "[text, code, freq]")) {
            expectFailure(DictionaryImportError.Reason.UNSUPPORTED_COLUMNS) {
                prepare(dictionary("一\t1\n", header = "name: main\ncolumns: " + columns + "\n"))
            }
        }
    }

    @Test
    fun validatesRowColumns() {
        assertEquals(listOf("一\tyi\t1"), entryLines(prepare(dictionary("一\tyi\n"))))
        assertEquals(listOf("一\tyi\t7"), entryLines(prepare(dictionary("一\tyi\t7\n"))))
        expectFailure(DictionaryImportError.Reason.INVALID_ROW) {
            prepare(dictionary("一\tyi\t1\textra\n"))
        }
        expectFailure(DictionaryImportError.Reason.INVALID_ROW) {
            prepare(dictionary("一\tyi\n", header = "name: main\ncolumns: [text, code, weight]\n"))
        }
        expectFailure(DictionaryImportError.Reason.INVALID_WEIGHT) {
            prepare(dictionary("一\tyi\t\n"))
        }
    }

    @Test
    fun acceptsEveryWeightFormTheIosImporterAccepted() {
        for (weight in listOf("1", "0", "12", "1.5", "50%")) {
            val prepared = prepare(dictionary("一\tyi\t" + weight + "\n"))
            assertEquals(listOf("一\tyi\t" + weight), entryLines(prepared))
        }
        for (weight in listOf("nan", "NaN", "-1", "1 2", "1e3", "1.2.3", "12345678901234567")) {
            expectFailure(DictionaryImportError.Reason.INVALID_WEIGHT) {
                prepare(dictionary("一\tyi\t" + weight + "\n"))
            }
        }
    }

    @Test
    fun rejectsEmptyAndOversizedDictionaries() {
        expectFailure(DictionaryImportError.Reason.EMPTY_DICTIONARY) { prepare(dictionary("")) }
        expectFailure(DictionaryImportError.Reason.EMPTY_DICTIONARY) {
            prepare(dictionary("# only comments\n\n"))
        }
        expectFailure(DictionaryImportError.Reason.FILE_TOO_LARGE) {
            prepare(
                dictionary("一\tyi\n"),
                "a.dict.yaml",
                builtinTemplate(),
                DictionaryImportLimits(byteCap = 8),
            )
        }
        expectFailure(DictionaryImportError.Reason.TOO_MANY_ENTRIES) {
            prepare(
                dictionary("一\tyi\n二\ter\n"),
                "a.dict.yaml",
                builtinTemplate(),
                DictionaryImportLimits(entryCap = 1),
            )
        }
        assertEquals(64L * 1024 * 1024, DictionaryImportBuilder.MAX_BYTE_COUNT)
        assertEquals(2_000_000, DictionaryImportBuilder.MAX_ENTRY_COUNT)
        assertEquals(4096, DictionaryImportBuilder.MAX_HEADER_LINES)
        assertEquals(240, DictionaryImportBuilder.MAX_FILE_NAME_BYTES)
    }

    @Test
    fun rejectsBuiltInTemplatesItCannotPatch() {
        val input = dictionary("一\tyi\n")
        val withoutDictionaryReference =
            builtinTemplate().toMutableMap().apply {
                this["songyan_quanpin.schema.yaml"] =
                    "schema:\n  schema_id: songyan_quanpin\ntranslator:\n  user_dict: songyan_shared\n"
                        .toByteArray(Charsets.UTF_8)
            }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", withoutDictionaryReference)
        }
        val duplicatedReference =
            builtinTemplate().toMutableMap().apply {
                this["songyan_quanpin.schema.yaml"] =
                    (
                        "schema:\n  schema_id: songyan_quanpin\ntranslator:\n  dictionary: songyan_basic\n" +
                            "  dictionary: songyan_basic\n  user_dict: songyan_shared\n"
                    ).toByteArray(Charsets.UTF_8)
            }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", duplicatedReference)
        }
        val withoutUserDictionary =
            builtinTemplate().toMutableMap().apply {
                this["songyan_flypy.schema.yaml"] =
                    "schema:\n  schema_id: songyan_flypy\ntranslator:\n  dictionary: songyan_basic\n"
                        .toByteArray(Charsets.UTF_8)
            }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", withoutUserDictionary)
        }
        val withoutDefault =
            builtinTemplate().toMutableMap().apply { remove("default.yaml") }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", withoutDefault)
        }
        val withoutStarterDictionary =
            builtinTemplate().toMutableMap().apply { remove("songyan_basic.dict.yaml") }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", withoutStarterDictionary)
        }
        val withoutSchemes =
            builtinTemplate().filterKeys { !it.endsWith(".schema.yaml") }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", withoutSchemes)
        }
        val escapingKey = builtinTemplate().toMutableMap().apply { put("../evil.yaml", byteArrayOf()) }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", escapingKey)
        }
        val invalidSchemaBytes =
            builtinTemplate().toMutableMap().apply { put("songyan_quanpin.schema.yaml", byteArrayOf(0xFF.toByte())) }
        expectFailure(DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE) {
            prepare(input, "a.dict.yaml", invalidSchemaBytes)
        }
    }

    @Test
    fun copiesTheDefaultConfigurationByteForByte() {
        val builtin = builtinTemplate()
        val prepared = prepare(dictionary("一\tyi\n"), "a.dict.yaml", builtin)
        assertArrayEquals(builtin.getValue("default.yaml"), prepared.files.getValue("default.yaml"))
        val originalFlypy = String(builtin.getValue("songyan_flypy.schema.yaml"), Charsets.UTF_8)
        val patchedFlypy = stagedText(prepared, "songyan_flypy.schema.yaml")
        assertEquals(
            originalFlypy.replace("  dictionary: songyan_basic", "  dictionary: songyan_imported"),
            patchedFlypy,
        )
        assertEquals("songyan_quanpin", prepared.schemaId)
    }

    @Test
    fun metadataDescribesTheStagedSource() {
        val prepared = prepare(dictionary("你好\tni hao\t3\n"))
        val metadata =
            Json
                .parseToJsonElement(stagedText(prepared, DictionaryImportBuilder.METADATA_FILE))
                .jsonObject
        assertEquals("songyan_imported.dict.yaml", metadata.getValue("dictionaryFile").jsonPrimitive.content)
        assertEquals("songyan_imported", metadata.getValue("dictionaryName").jsonPrimitive.content)
        assertEquals("main", metadata.getValue("displayName").jsonPrimitive.content)
        assertEquals(1, metadata.getValue("entryCount").jsonPrimitive.int)
        assertEquals(prepared.revision, metadata.getValue("revision").jsonPrimitive.content)
        assertEquals("songyan_quanpin", metadata.getValue("schemaId").jsonPrimitive.content)
        assertEquals(prepared.sha256, metadata.getValue("sha256").jsonPrimitive.content)
        assertEquals("user-pinyin", metadata.getValue("sourceId").jsonPrimitive.content)
        assertTrue(prepared.files.containsKey(metadata.getValue("dictionaryFile").jsonPrimitive.content))
        assertTrue(stagedText(prepared, DictionaryImportBuilder.LICENSE_FILE).contains("User-provided dictionary data"))
    }

    @Test
    fun isDeterministicForTheSameInput() {
        val bytes = dictionary("你好\tni hao\t3\n")
        val first = prepare(bytes, "words.dict.yaml")
        val second = prepare(bytes, "words.dict.yaml")
        assertEquals(sha256(bytes), first.revision)
        assertEquals(first.revision, first.sha256)
        assertEquals(64, first.revision.length)
        assertEquals(first.files.keys, second.files.keys)
        for (key in first.files.keys) {
            assertArrayEquals(key, first.files.getValue(key), second.files.getValue(key))
        }
        // The original file keeps its own name; everything the engine reads is identical.
        val renamed = prepare(bytes, "dicts/other.dict.yaml")
        assertEquals(first.revision, renamed.revision)
        assertArrayEquals(
            first.files.getValue("songyan_imported.dict.yaml"),
            renamed.files.getValue("songyan_imported.dict.yaml"),
        )
        assertArrayEquals(bytes, renamed.files.getValue("import-sources/dicts/other.dict.yaml"))
    }

    @Test
    fun mergesImportTablesInDependencyOrderAndKeepsTheOriginals() {
        // rime-ice shape: the main dictionary imports tables in subdirectories; the picker only
        // hands over file names, so a dependency resolves by its file name.
        val main =
            dictionary("主\tzhu\t1\n", header = "name: rime_ice\nimport_tables:\n  - cn_dicts/base\n  - cn_dicts/ext\n")
        val base = dictionary("基\tji\t2\n", header = "name: base\n")
        val ext = dictionary("扩\tkuo\t3\n", header = "name: ext\nimport_tables: [cn_dicts/base]\n")
        val prepared =
            prepareAll(listOf("ext.dict.yaml" to ext, "rime_ice.dict.yaml" to main, "base.dict.yaml" to base))

        assertEquals(listOf("基\tji\t2", "扩\tkuo\t3", "主\tzhu\t1"), entryLines(prepared))
        assertEquals("rime_ice", prepared.displayName)
        assertEquals(3, prepared.entryCount)
        assertArrayEquals(main, prepared.files.getValue("import-sources/rime_ice.dict.yaml"))
        assertArrayEquals(base, prepared.files.getValue("import-sources/base.dict.yaml"))
        assertTrue(stagedText(prepared, DictionaryImportBuilder.ORIGIN_FILE).contains("\"path\": \"ext.dict.yaml\""))
    }

    @Test
    fun reportsMissingCyclicAndStrayDictionaries() {
        val main = dictionary("主\tzhu\n", header = "name: main\nimport_tables: [cn_dicts/base, cn_dicts/ext]\n")
        val base = dictionary("基\tji\n", header = "name: base\n")
        try {
            prepareAll(listOf("main.dict.yaml" to main, "base.dict.yaml" to base))
            fail("expected missing dependencies")
        } catch (error: DictionaryImportError) {
            assertEquals(DictionaryImportError.Reason.MISSING_DEPENDENCIES, error.reason)
            assertEquals(listOf("cn_dicts/ext.dict.yaml"), error.paths)
        }
        val a = dictionary("一\tyi\n", header = "name: a\nimport_tables: [b]\n")
        val b = dictionary("二\ter\n", header = "name: b\nimport_tables: [a]\n")
        expectFailure(DictionaryImportError.Reason.DEPENDENCY_CYCLE) {
            prepareAll(listOf("a.dict.yaml" to a, "b.dict.yaml" to b))
        }
        val c = dictionary("三\tsan\n", header = "name: c\nimport_tables: [d]\n")
        val d = dictionary("四\tsi\n", header = "name: d\nimport_tables: [c]\n")
        expectFailure(DictionaryImportError.Reason.DEPENDENCY_CYCLE) {
            prepareAll(
                listOf(
                    "root.dict.yaml" to dictionary("五\twu\n", header = "name: r\nimport_tables: [c]\n"),
                    "c.dict.yaml" to c,
                    "d.dict.yaml" to d,
                ),
            )
        }
        expectFailure(DictionaryImportError.Reason.UNREFERENCED_FILE) {
            prepareAll(listOf("a.dict.yaml" to dictionary("一\tyi\n"), "b.dict.yaml" to dictionary("二\ter\n")))
        }
        expectFailure(DictionaryImportError.Reason.TOO_MANY_FILES) {
            prepareAll(
                listOf("a.dict.yaml" to dictionary("一\tyi\n"), "b.dict.yaml" to dictionary("二\ter\n")),
                limits = DictionaryImportLimits(fileCap = 1),
            )
        }
        assertEquals(128, DictionaryImportBuilder.MAX_FILE_COUNT)
    }

    @Test
    fun stagesOnlySafeRelativePaths() {
        val prepared = prepare(dictionary("一\tyi\n"))
        for (path in prepared.files.keys) {
            assertTrue(path, !path.startsWith("/"))
            assertTrue(path, !path.startsWith(".."))
            assertTrue(path, !path.contains("/../"))
            assertTrue(path, !path.contains("\\"))
            assertTrue(path, !path.contains(":"))
        }
    }

    @Test
    fun usesTheHeaderNameAsTheDisplayName() {
        val prepared = prepare(dictionary("一\tyi\n", header = "name: 'rime-ice'\nversion: \"1.0\"\n"))
        assertEquals("rime-ice", prepared.displayName)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val prepared = prepare(dictionary("# a note\n\n一\tyi\n# trailing note\n"))
        assertEquals(listOf("一\tyi\t1"), entryLines(prepared))
        val commentedHeader = prepare(dictionary("一\tyi\n", header = "name: main # the source\nversion: '1'\n"))
        assertEquals("main", commentedHeader.displayName)
    }

    /** A prepared import plus the staged tree read back as path to bytes. */
    private class Staged(
        prepared: PreparedDictionaryImport,
        val files: Map<String, ByteArray>,
    ) {
        val entryCount = prepared.entryCount
        val dictionaryName = prepared.dictionaryName
        val displayName = prepared.displayName
        val sourceId = prepared.sourceId
        val schemaId = prepared.schemaId
        val revision = prepared.revision
        val sha256 = prepared.sha256
    }

    private fun prepare(
        dictionaryFile: ByteArray,
        dictionaryFileName: String = "words.dict.yaml",
        builtin: Map<String, ByteArray> = builtinTemplate(),
        limits: DictionaryImportLimits = DictionaryImportLimits(),
    ): Staged = prepareAll(listOf(dictionaryFileName to dictionaryFile), builtin, limits)

    private fun prepareAll(
        sources: List<Pair<String, ByteArray>>,
        builtin: Map<String, ByteArray> = builtinTemplate(),
        limits: DictionaryImportLimits = DictionaryImportLimits(),
    ): Staged {
        val directory = Files.createTempDirectory("dictionary-import").toFile()
        directory.deleteOnExit()
        val inputs =
            sources.mapIndexed { index, (path, bytes) ->
                val file = File(directory, "input-$index")
                file.writeBytes(bytes)
                DictionarySourceFile(path, file)
            }
        val target = File(directory, "out")
        val prepared = DictionaryImportBuilder.prepare(inputs, builtin, target, limits)
        val files = sortedMapOf<String, ByteArray>()
        target.walkTopDown().filter { it.isFile }.forEach {
            files[it.relativeTo(target).path.replace(File.separatorChar, '/')] = it.readBytes()
        }
        return Staged(prepared, files)
    }

    private fun dictionary(
        rows: String,
        header: String = "name: main\n",
    ): ByteArray = ("---\n" + header + "...\n" + rows).toByteArray(Charsets.UTF_8)

    private fun builtinTemplate(): Map<String, ByteArray> =
        linkedMapOf(
            "default.yaml" to
                (
                    "config_version: \"1\"\nmenu:\n  page_size: 5\nschema_list:\n" +
                        "  - schema: songyan_quanpin\n  - schema: songyan_flypy\n"
                ).toByteArray(Charsets.UTF_8),
            "manifest.yaml" to "formatVersion: 1\nid: songyan-builtin\nkind: schema\n".toByteArray(Charsets.UTF_8),
            "songyan_basic.dict.yaml" to "name: songyan_basic\n...\n你\tni\t1\n".toByteArray(Charsets.UTF_8),
            "songyan_quanpin.schema.yaml" to
                (
                    "schema:\n  schema_id: songyan_quanpin\ntranslator:\n  dictionary: songyan_basic\n" +
                        "  user_dict: songyan_shared\n"
                ).toByteArray(Charsets.UTF_8),
            "songyan_flypy.schema.yaml" to
                (
                    "schema:\n  schema_id: songyan_flypy\ntranslator:\n  dictionary: songyan_basic\n" +
                        "  user_dict: songyan_shared\n"
                ).toByteArray(Charsets.UTF_8),
            "keyboard_47246213a538cb99d374_14.schema.yaml" to
                (
                    "schema:\n  schema_id: keyboard_47246213a538cb99d374_14\ntranslator:\n" +
                        "  dictionary: songyan_basic\n  user_dict: songyan_shared\n"
                ).toByteArray(Charsets.UTF_8),
        )

    private fun stagedText(
        prepared: Staged,
        path: String,
    ): String = String(prepared.files.getValue(path), Charsets.UTF_8)

    private fun entryLines(prepared: Staged): List<String> =
        stagedText(prepared, "songyan_imported.dict.yaml")
            .split('\n')
            .dropWhile { it != "..." }
            .drop(1)
            .filter { it.isNotEmpty() }

    private fun expectFailure(
        reason: DictionaryImportError.Reason,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: DictionaryImportError) {
            assertEquals(error.message, reason, error.reason)
            return
        }
        fail("expected " + reason)
    }

    private fun ideograph(value: Int): String = String(Character.toChars(value))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

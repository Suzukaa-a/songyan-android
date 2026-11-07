package app.songyan.ime.rime

import app.songyan.ime.packages.CompactSchemaAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BuiltinSourceInstallerTest {
    private fun assets(): FakeAssetTree =
        FakeAssetTree().apply {
            put("builtin/default.yaml", "config_version: \"1\"\nschema_list:\n  - schema: songyan_quanpin\n")
            put("builtin/songyan_quanpin.schema.yaml", "schema:\n  schema_id: songyan_quanpin\n")
            put("builtin/songyan_basic.dict.yaml", "name: songyan_basic\n")
            put(
                "prelude/default.yaml",
                "config_version: '0.50'\nschema_list:\n  - schema: luna_pinyin\nmenu:\n  page_size: 5\n",
            )
            put("prelude/key_bindings.yaml", "key_bindings:\n")
            put("prelude/punctuation.yaml", "punctuation:\n")
            put("prelude/symbols.yaml", "symbols:\n")
            put("prelude/LICENSE", "GPL\n")
            put("prelude/AUTHORS", "Songyan\n")
            put("prelude/PROVENANCE.md", "P\n")
            put("prelude/COPYING", "C\n")
            put("opencc/s2t.json", "{}\n")
            put("opencc/STPhrases.ocd2", "binary")
        }

    @Test
    fun install_copiesPackageAndSupplementsPresets() {
        val target = Files.createTempDirectory("builtin").toFile()
        BuiltinSourceInstaller(assets()).install(target, "1.0.0/1")

        assertTrue(java.io.File(target, "songyan_quanpin.schema.yaml").isFile)
        assertTrue(java.io.File(target, "songyan_basic.dict.yaml").isFile)
        assertTrue(java.io.File(target, "key_bindings.yaml").isFile)
        assertTrue(java.io.File(target, "punctuation.yaml").isFile)
        assertTrue(java.io.File(target, "symbols.yaml").isFile)
        assertTrue(java.io.File(target, "opencc/STPhrases.ocd2").isFile)
        assertTrue(java.io.File(target, "frontend-rime-prelude/LICENSE").isFile)
        // The package provides default.yaml, so the prelude copy must not replace it.
        assertEquals(
            "config_version: \"1\"\nschema_list:\n  - schema: songyan_quanpin\n",
            java.io.File(target, "default.yaml").readText(),
        )
    }

    @Test
    fun install_rewritesSchemaListWhenPackageHasNoDefault() {
        val tree =
            FakeAssetTree().apply {
                put("builtin/songyan_quanpin.schema.yaml", "schema:\n")
                put("builtin/songyan_flypy.schema.yaml", "schema:\n")
                put(
                    "prelude/default.yaml",
                    "config_version: '0.50'\nschema_list:\n  - schema: luna_pinyin\nmenu:\n  page_size: 5\n",
                )
            }
        val target = Files.createTempDirectory("builtin").toFile()
        BuiltinSourceInstaller(tree).install(target, "1")

        val text = java.io.File(target, "default.yaml").readText()
        assertTrue(text.contains("- schema: songyan_flypy"))
        assertTrue(text.contains("- schema: songyan_quanpin"))
        assertFalse(text.contains("luna_pinyin"))
        assertTrue(text.contains("menu:"))
    }

    @Test
    fun install_appendsTheBundledStarterRowsToThePackageDictionary() {
        val tree = assets()
        tree.put("builtin/songyan_basic.dict.yaml", "---\nname: songyan_basic\nsort: by_weight\n...\n啊\ta\t1\n")
        tree.put("starter/songyan_starter.dict.yaml", "啊\ta\t9\n你\tni\t5\n")
        val target = Files.createTempDirectory("builtin").toFile()

        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")

        val merged = java.io.File(target, "songyan_basic.dict.yaml").readText()
        val rows = merged.lines().filter { it.contains('\t') }
        // The package row stays in place but takes the starter's frequency scale.
        assertEquals(listOf("啊\ta\t9", "你\tni\t5"), rows)
    }

    @Test
    fun install_splitsTheStarterFrequencyAcrossReadingsAsThePackageDoes() {
        val tree = assets()
        tree.put(
            "builtin/songyan_basic.dict.yaml",
            "---\nname: songyan_basic\n...\n# Single characters\n的\tde\t3000\n的\tdi\t60\n地\tdi\t5000\n",
        )
        tree.put("starter/songyan_starter.dict.yaml", "的\tde\t300000\n的\tdi\t300000\n地\tdi\t4000\n")
        val target = Files.createTempDirectory("builtin").toFile()

        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")

        val rows =
            java.io
                .File(target, "songyan_basic.dict.yaml")
                .readLines()
                .filter { it.contains('\t') }
        // A starter frequency below the package weight never demotes the package row.
        assertEquals(listOf("的\tde\t300000", "的\tdi\t6000", "地\tdi\t5000"), rows)
    }

    /**
     * The bundled package and starter tables merged as shipped: in a by_weight dictionary the
     * heaviest single character of a reading is the first single-character candidate, and the most
     * frequent character must not sink below rarer homophones the starter adds.
     */
    @Test
    fun install_ranksTheMostFrequentCharacterFirstWithTheShippedTables() {
        val tree = assets()
        tree.put("builtin/songyan_basic.dict.yaml", java.io.File(SHIPPED_BASIC).readText())
        tree.put("starter/songyan_starter.dict.yaml", java.io.File(SHIPPED_STARTER).readText())
        val target = Files.createTempDirectory("builtin").toFile()

        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")

        val lines = java.io.File(target, "songyan_basic.dict.yaml").readLines()
        val entries =
            lines
                .drop(lines.indexOfFirst { it.trim() == "..." } + 1)
                .filter { !it.startsWith("#") }
                .map { it.split('\t') }
                .filter { it.size >= 3 && it[0].codePointCount(0, it[0].length) == 1 }

        fun firstFor(reading: String): List<String> =
            entries
                .filter { it[1] == reading }
                .sortedByDescending { it[2].toLong() }
                .take(3)
                .map { it[0] }
        assertEquals("你", firstFor("ni").first())
        assertEquals("是", firstFor("shi").first())
        assertEquals("的", firstFor("de").first())
        assertEquals("我", firstFor("wo").first())
        assertEquals("了", firstFor("le").first())
        // A secondary reading in the package stays secondary instead of inheriting the full frequency.
        assertFalse(firstFor("di").contains("的"))
    }

    /**
     * A polyphonic character's frequency is split by how often it takes each reading, so a rare
     * reading (了 liao) cannot tie with the common one and outrank its homophones; everyday
     * phrases are present so a sentence is not built from single characters (我想去, not 我向去).
     */
    @Test
    fun shippedStarter_weighsReadingsByUsageAndCarriesEverydayPhrases() {
        val weights =
            java.io
                .File(SHIPPED_STARTER)
                .readLines()
                .filter { !it.startsWith("#") }
                .map { it.split('\t') }
                .associate { (it[0] + ' ' + it[1]) to it[2].toLong() }

        fun weight(entry: String): Long = requireNotNull(weights[entry]) { "$entry is missing" }
        assertTrue(weight("了 le") > 10 * weight("了 liao"))
        assertTrue(weight("都 dou") > 10 * weight("都 du"))
        assertTrue(weight("说 shuo") > 10 * weight("说 shui"))
        assertTrue(weight("想 xiang") > weight("向 xiang"))
        listOf("想去 xiang qu", "我想 wo xiang", "你好 ni hao", "谢谢 xie xie", "一点儿 yi dian er").forEach(::weight)
    }

    /**
     * Fuzzy pinyin and the folded layouts need the dictionary to be plain pinyin; a single stray
     * reading (erhua "r", the two-syllable 瓲 "tunwa") switches them off for the built-in methods.
     */
    @Test
    fun shippedStarter_spellsEveryReadingInStandardPinyin() {
        val stray =
            java.io
                .File(SHIPPED_STARTER)
                .readLines()
                .filter { !it.startsWith("#") }
                .flatMap { it.split('\t')[1].split(' ') }
                .toSet()
                .filterNot(CompactSchemaAdapter::isCanonicalPinyinToken)
        assertEquals(emptyList<String>(), stray)
    }

    @Test
    fun supplementFrontendFiles_mergesTheStarterIntoAnImportedSource() {
        val tree = assets()
        tree.put("starter/songyan_starter.dict.yaml", "你\tni\t5\n")
        val target = Files.createTempDirectory("imported").toFile()
        target.mkdirs()
        java.io.File(target, "songyan_basic.dict.yaml").writeText("---\nname: songyan_basic\n...\n啊\ta\t1\n")

        BuiltinSourceInstaller(tree).supplementFrontendFiles(target)
        BuiltinSourceInstaller(tree).supplementFrontendFiles(target)

        val merged = java.io.File(target, "songyan_basic.dict.yaml").readText()
        val rows = merged.lines().filter { it.contains('\t') }
        assertEquals(listOf("啊\ta\t1", "你\tni\t5"), rows)
    }

    @Test
    fun install_leavesAnUnparseableDictionaryUntouched() {
        val tree = assets()
        tree.put("builtin/songyan_basic.dict.yaml", "name: songyan_basic\nno-header\n")
        tree.put("starter/songyan_starter.dict.yaml", "你\tni\t5\n")
        val target = Files.createTempDirectory("builtin").toFile()

        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")

        assertEquals("name: songyan_basic\nno-header\n", java.io.File(target, "songyan_basic.dict.yaml").readText())
    }

    @Test
    fun install_isIdempotentForSameStamp() {
        val tree = assets()
        val target = Files.createTempDirectory("builtin").toFile()
        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")
        val marker = java.io.File(target, "marker.txt")
        marker.writeText("kept")

        BuiltinSourceInstaller(tree).install(target, "1.0.0/1")
        assertTrue(marker.isFile)

        BuiltinSourceInstaller(tree).install(target, "2.0.0/2")
        assertFalse(marker.isFile)
    }

    @Test
    fun supplementFrontendFiles_preservesUnreadableCustomBytes() {
        val target = kagiroiTarget(KAGIROI_RAW_SCHEMA)
        val custom = java.io.File(target, "kagiroi.custom.yaml")
        val bytes = "patch:\n  style/color_scheme: ".toByteArray() + byteArrayOf(0xFF.toByte()) + "\n".toByteArray()
        custom.writeBytes(bytes)

        BuiltinSourceInstaller(assets()).supplementFrontendFiles(target)

        assertEquals(bytes.toList(), custom.readBytes().toList())
    }

    @Test
    fun supplementFrontendFiles_writesTheKagiroiSourcePatch() {
        val target = kagiroiTarget(KAGIROI_RAW_SCHEMA)
        val schemaBytes = java.io.File(target, "kagiroi.schema.yaml").readBytes()

        BuiltinSourceInstaller(assets()).supplementFrontendFiles(target)

        assertEquals(
            schemaBytes.toList(),
            java.io
                .File(target, "kagiroi.schema.yaml")
                .readBytes()
                .toList(),
        )
        val text = kagiroiPatchText(target)
        assertTrue(
            "the paging preset must be emptied, not rewritten: " + text,
            Regex("key_bindings/paging_with_minus_equal/__append\\s*:\\s*\\[\\s*\\]").containsMatchIn(text),
        )
        assertTrue("half-width comma must be patched: " + text, text.contains("punctuator/half_shape/,"))
        assertTrue("half-width period must be patched: " + text, text.contains("punctuator/half_shape/."))
        assertEquals(2, commitCount(text))
        assertTrue("the half-width comma and period must commit: " + text, commitsJapanesePunctuation(text))
        assertFalse("full-width punctuation must stay as shipped: " + text, text.contains("punctuator/full_shape"))
        val accepts = bindingAccepts(text)
        assertFalse("paging bindings must not be added: " + text, accepts.contains("minus"))
        assertFalse("paging bindings must not be added: " + text, accepts.contains("equal"))
    }

    @Test
    fun supplementFrontendFiles_writesTheCompiledKagiroiPatch() {
        val target = kagiroiTarget(KAGIROI_COMPILED_SCHEMA)

        BuiltinSourceInstaller(assets()).supplementFrontendFiles(target)

        val text = kagiroiPatchText(target)
        assertTrue("the key binder must be replaced as a whole: " + text, text.contains("key_binder:"))
        assertFalse(
            "keeping import_preset would re-add the paging bindings at the preset link: " + text,
            text.contains("import_preset"),
        )
        val accepts = bindingAccepts(text)
        assertEquals(31, accepts.size)
        assertFalse(accepts.contains("minus"))
        assertFalse(accepts.contains("equal"))
        assertTrue(accepts.containsAll(listOf("Control+u", "Control+i", "Control+o", "Control+q")))
        assertTrue(accepts.contains("Control+y"))
        assertTrue(accepts.contains("Control+v"))
        assertEquals(2, commitCount(text))
        assertTrue("the half-width comma and period must commit: " + text, commitsJapanesePunctuation(text))
        assertFalse("full-width punctuation must stay as shipped: " + text, text.contains("punctuator/full_shape"))
    }

    @Test
    fun supplementFrontendFiles_isIdempotentForBothKagiroiShapes() {
        for (schema in listOf(KAGIROI_RAW_SCHEMA, KAGIROI_COMPILED_SCHEMA)) {
            val target = kagiroiTarget(schema)
            val installer = BuiltinSourceInstaller(assets())
            installer.supplementFrontendFiles(target)
            val once = java.io.File(target, "kagiroi.custom.yaml").readBytes()
            installer.supplementFrontendFiles(target)
            val twice = java.io.File(target, "kagiroi.custom.yaml").readBytes()
            assertEquals(once.toList(), twice.toList())
        }
    }

    @Test
    fun supplementFrontendFiles_leavesASourceWithoutKagiroiUnpatched() {
        val target = Files.createTempDirectory("sample").toFile()
        target.mkdirs()
        java.io.File(target, "sample.schema.yaml").writeText("schema:\n  schema_id: sample\n")

        BuiltinSourceInstaller(assets()).supplementFrontendFiles(target)

        assertFalse(target.listFiles().orEmpty().any { it.name.endsWith(".custom.yaml") })
    }

    @Test
    fun supplementFrontendFiles_keepsTheAuthorsOwnKagiroiPatchEntries() {
        val target = kagiroiTarget(KAGIROI_RAW_SCHEMA)
        java.io.File(target, "kagiroi.custom.yaml").writeText("patch:\n  \"menu/page_size\": 7\n")

        BuiltinSourceInstaller(assets()).supplementFrontendFiles(target)

        val text = kagiroiPatchText(target)
        assertTrue(
            "the author's own entries must stay: " + text,
            Regex("\"?menu/page_size\"?\\s*:\\s*7").containsMatchIn(text),
        )
        assertTrue(
            "the paging preset must be emptied: " + text,
            Regex("key_bindings/paging_with_minus_equal/__append\\s*:\\s*\\[\\s*\\]").containsMatchIn(text),
        )
        assertEquals(2, commitCount(text))
        assertTrue("the half-width comma and period must commit: " + text, commitsJapanesePunctuation(text))
    }

    private fun kagiroiTarget(schema: String): java.io.File {
        val target = Files.createTempDirectory("kagiroi").toFile()
        java.io.File(target, "kagiroi.schema.yaml").writeText(schema)
        java.io.File(target, "kagiroi.yaml").writeText(KAGIROI_PRESET)
        return target
    }

    private fun kagiroiPatchText(target: java.io.File): String = java.io.File(target, "kagiroi.custom.yaml").readText()

    /** The accept keys of the scheme patch, in file order; one per binding. */
    private fun bindingAccepts(text: String): List<String> =
        Regex("accept:\\s*\"?([A-Za-z0-9+_]+)\"?").findAll(text).map { it.groupValues[1] }.toList()

    private fun commitCount(text: String): Int = Regex("commit:").findAll(text).count()

    /** The half-width comma and period commits, whether written raw or Yams-escaped. */
    private fun commitsJapanesePunctuation(text: String): Boolean = text.contains("、") || text.contains("u3001")

    private companion object {
        // Unit tests run in the module directory.
        const val SHIPPED_BASIC = "../core/rime/src/main/assets/rime/builtin/songyan_basic.dict.yaml"
        const val SHIPPED_STARTER = "src/main/assets/rime/starter/songyan_starter.dict.yaml"

        /** The pinned source package: key_binder/bindings is an in-place __patch of presets. */
        val KAGIROI_RAW_SCHEMA =
            """
            schema:
              schema_id: kagiroi
              name: カギロイ

            switches:
              - name: ascii_mode
                reset: 0
                states: [ あ, 英字 ]

            punctuator:
              __patch:
                - kagiroi:/punct/3_dan

            editor:
              bindings:
                Return: commit_script_text

            key_binder:
              import_preset: default
              bindings:
                __patch:
                  - kagiroi:/key_bindings/paging_with_minus_equal
                  - kagiroi:/key_bindings/kagiroi_switches
            """.trimIndent()

        /** The preset file the source schema patches. */
        val KAGIROI_PRESET =
            """
            key_bindings:
              paging_with_minus_equal:
                __append:
                  - { when: paging, accept: minus, send: Page_Up }
                  - { when: has_menu, accept: equal, send: Page_Down }
              kagiroi_switches:
                __append:
                  - { when: composing, accept: "Control+u", set_option: hiragana }
                  - { when: composing, accept: "Control+i", set_option: katakana }
                  - { when: composing, accept: "Control+o", set_option: hw_katakana }
                  - { when: has_menu, accept: "Control+q", toggle: emoji }

            punct:
              3_dan:
                full_shape:
                  ' ' : { commit: '　' }
                  ',' : { commit: 、 }
                  '.' : { commit: 。 }
                half_shape:
                  ',' : [ 、, ・, ， ]
                  '.' : [ 。, …, ．, . ]
                  '?' : { commit: ？ }
            """.trimIndent()

        /** The prebuilt package: one flat bindings list plus import_preset: default. */
        val KAGIROI_COMPILED_SCHEMA =
            """
            schema:
              schema_id: kagiroi
              name: "カギロイ"

            key_binder:
              bindings:
                - {accept: "Control+p", send: Up, when: composing}
                - {accept: "Control+n", send: Down, when: composing}
                - {accept: "Control+b", send: Left, when: composing}
                - {accept: "Control+f", send: Right, when: composing}
                - {accept: "Control+a", send: Home, when: composing}
                - {accept: "Control+e", send: End, when: composing}
                - {accept: "Control+d", send: Delete, when: composing}
                - {accept: "Control+k", send: "Shift+Delete", when: composing}
                - {accept: "Control+h", send: BackSpace, when: composing}
                - {accept: "Control+g", send: Escape, when: composing}
                - {accept: "Control+bracketleft", send: Escape, when: composing}
                - {accept: "Control+y", send: Page_Up, when: composing}
                - {accept: "Alt+v", send: Page_Up, when: composing}
                - {accept: "Control+v", send: Page_Down, when: composing}
                - {accept: ISO_Left_Tab, send: "Shift+Left", when: composing}
                - {accept: "Shift+Tab", send: "Shift+Left", when: composing}
                - {accept: Tab, send: "Shift+Right", when: composing}
                - {accept: "Control+Shift+1", select: .next, when: always}
                - {accept: "Control+Shift+2", toggle: ascii_mode, when: always}
                - {accept: "Control+Shift+3", toggle: full_shape, when: always}
                - {accept: "Control+Shift+4", toggle: simplification, when: always}
                - {accept: "Control+Shift+5", toggle: extended_charset, when: always}
                - {accept: "Control+Shift+exclam", select: .next, when: always}
                - {accept: "Control+Shift+at", toggle: ascii_mode, when: always}
                - {accept: "Control+Shift+numbersign", toggle: full_shape, when: always}
                - {accept: "Control+Shift+dollar", toggle: simplification, when: always}
                - {accept: "Control+Shift+percent", toggle: extended_charset, when: always}
                - {accept: minus, send: Page_Up, when: paging}
                - {accept: equal, send: Page_Down, when: has_menu}
                - {accept: "Control+u", set_option: hiragana, when: composing}
                - {accept: "Control+i", set_option: katakana, when: composing}
                - {accept: "Control+o", set_option: hw_katakana, when: composing}
                - {accept: "Control+q", toggle: emoji, when: has_menu}
              import_preset: default

            punctuator:
              full_shape:
                " ": {commit: "　"}
                ",": {commit: "、"}
                .: {commit: "。"}
                "?": {commit: "？"}
              half_shape:
                ",": ["、", "・", "，"]
                .: ["。", "…", "．", .]
                "?": {commit: "？"}
            """.trimIndent()
    }
}

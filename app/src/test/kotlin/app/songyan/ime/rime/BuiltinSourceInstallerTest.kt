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

    private companion object {
        // Unit tests run in the module directory.
        const val SHIPPED_BASIC = "../core/rime/src/main/assets/rime/builtin/songyan_basic.dict.yaml"
        const val SHIPPED_STARTER = "src/main/assets/rime/starter/songyan_starter.dict.yaml"
    }
}

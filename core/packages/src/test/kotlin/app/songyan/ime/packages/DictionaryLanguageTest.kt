package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictionaryLanguageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun detect(vararg texts: String) = DictionaryLanguage.detect(texts.asSequence())

    private val simplified = "国际学习电脑发现开关门问题车站飞机书店经济贸易产业环境艺术图书馆"
    private val traditional = "國際學習電腦發現開關門問題車站飛機書店經濟貿易產業環境藝術圖書館"

    @Test
    fun aSimplifiedDictionaryIsSimplifiedChinese() {
        assertEquals("zh-Hans", detect(*simplified.map { it.toString() }.toTypedArray(), simplified))
    }

    @Test
    fun aTraditionalDictionaryIsTraditionalChinese() {
        assertEquals("zh-Hant", detect(*traditional.map { it.toString() }.toTypedArray(), traditional))
    }

    @Test
    fun aMixedDictionaryFollowsTheSystemScript() {
        // Each script has plenty, but neither is twice the other.
        assertEquals("zh", detect(simplified, traditional))
        // Too few script-specific characters to prove either side.
        assertEquals("zh", detect("国", "中文"))
    }

    @Test
    fun kanaEntriesMakeItJapaneseEvenWithKanjiAround() {
        val kanji = List(40) { "漢字" }
        assertEquals("ja", detect(*(kanji + listOf("ひらがな", "カタカナ", "かな")).toTypedArray()))
        // Under 5 % of the entries it is still Chinese.
        assertEquals("zh", detect(*(List(100) { "字典" } + listOf("ひらがな")).toTypedArray()))
    }

    @Test
    fun hangulEntriesMakeItKorean() {
        assertEquals("ko", detect("한국어", "사전", "English", "단어", "가나다"))
        assertEquals("none", detect(*(List(10) { "word" } + listOf("한")).toTypedArray()))
    }

    @Test
    fun anEnglishDictionaryForcesNothing() {
        assertEquals("none", detect("hello", "world", "dictionary"))
        assertEquals("none", detect())
    }

    @Test
    fun theDictionaryFileIsReadWithItsColumnsAndOneLevelOfImports() {
        val dir = temp.newFolder("src")
        File(dir, "main.dict.yaml").writeText(
            "# header\n---\nname: main\nversion: \"1\"\nimport_tables:\n  - extra\n" +
                "columns:\n  - code\n  - text\n...\n" +
                "a\t国\n# comment\nb\t学\n",
        )
        File(dir, "extra.dict.yaml").writeText(
            "---\nname: extra\n...\n" + simplified.map { "$it\tx" }.joinToString("\n") + "\n",
        )
        assertEquals("zh-Hans", DictionaryLanguage.read(dir, "main"))
    }

    @Test
    fun aMissingDictionaryCannotBeTold() {
        val dir = temp.newFolder("empty")
        assertNull(DictionaryLanguage.read(dir, "nothing"))
        assertNull(DictionaryLanguage.read(dir, "../escape"))
        File(dir, "headless.dict.yaml").writeText("a\tb\n")
        assertNull(DictionaryLanguage.read(dir, "headless"))
    }

    @Test
    fun theDeploymentFillsTheLanguageOfThePrimaryDictionaryIntoTheProfile() {
        val dir = temp.newFolder("build")
        val source = temp.newFolder("source")
        File(dir, "wubi86.schema.yaml").writeText(
            javaClass
                .getResourceAsStream(
                    "/package-fixtures/key-profiles/wubi86.schema.yaml",
                )!!
                .readBytes()
                .toString(Charsets.UTF_8),
        )
        File(source, "wubi86.dict.yaml").writeText(
            "---\nname: wubi86\n...\n" + simplified.map { "$it\ta" }.joinToString("\n") + "\n",
        )
        SchemaKeyProfiles.write(dir, listOf("wubi86"), source)
        assertEquals("zh-Hans", SchemaKeyProfiles.read(dir, listOf("wubi86")).getValue("wubi86").language)
    }
}

package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rule and key text is written into a generated YAML recipe, so its escaping is part of the format. */
class FuzzyPinyinTextTest {
    private val quote = '"'
    private val backslash = '\\'

    @Test
    fun insertKeysAreZeroPaddedListInsertions() {
        assertEquals("speller/algebra/@before 00", FuzzyPinyin.insertKey(0))
        assertEquals("speller/algebra/@before 09", FuzzyPinyin.insertKey(9))
        assertEquals("speller/algebra/@before 10", FuzzyPinyin.insertKey(10))
        assertEquals("speller/algebra", FuzzyPinyin.ALGEBRA_KEY)
        assertEquals("speller/algebra", FuzzyPinyin.Target("any", "translator").configKey)
    }

    @Test
    fun quotedWritesValidYamlFlowScalars() {
        assertEquals("" + quote + "plain" + quote, FuzzyPinyin.quoted("plain"))
        // A double quote and a backslash are escaped with a backslash, JSON style.
        assertEquals(
            "" + quote + "a" + backslash + quote + "b" + quote,
            FuzzyPinyin.quoted("a\"b"),
        )
        assertEquals(
            "" + quote + "a" + backslash + backslash + "b" + quote,
            FuzzyPinyin.quoted("a\\b"),
        )
        // Control characters are written as a lowercase four-digit unicode escape.
        assertEquals(
            "" + quote + backslash + "u000a" + quote,
            FuzzyPinyin.quoted("\n"),
        )
    }

    @Test
    fun generatedRecipesKeepTheIOSByteLayout() {
        val off = FuzzyPinyin.make(emptySet(), listOf(FuzzyPinyin.Target("alpha", "translator")))
        assertEquals(
            "recipe:\n  Rx: fuzzy-pinyin\n  description: 模糊音\npatch_files:\n" +
                "  alpha.custom.yaml:\n    - patch/+: {}\n",
            String(off.source, Charsets.UTF_8),
        )
        val on = FuzzyPinyin.make(setOf("z-zh"), listOf(FuzzyPinyin.Target("alpha", "translator")))
        assertEquals(
            "recipe:\n  Rx: fuzzy-pinyin\n  description: 模糊音\npatch_files:\n" +
                "  alpha.custom.yaml:\n" +
                "    - patch/+:\n" +
                "        \"speller/algebra/@before 00\": \"fuzz/^zh/z/\"\n" +
                "        \"speller/algebra/@before 01\": \"fuzz/^z(?!h)/zh/\"\n",
            String(on.source, Charsets.UTF_8),
        )
    }
}

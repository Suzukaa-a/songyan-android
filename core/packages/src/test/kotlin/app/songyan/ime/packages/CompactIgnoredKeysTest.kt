package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sources that declare keys a compact layout never sends (tone digits, `\`), modelled on
 * rime_wanxiang. Ported from the iOS `CompactIgnoredKeysTests`.
 */
class CompactIgnoredKeysTest {
    private val adapter = CompactSchemaAdapter
    private val toneAlphabet = "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA1234567890`;/\\"

    private fun syllable(
        token: String,
        vararg spellings: String,
    ) = CompactSchemaAdapter.ProjectedSyllable(
        token,
        spellings.map { CompactSchemaAdapter.ProjectedSpelling(it, true, false) },
    )

    private fun projection(
        alphabet: String,
        syllables: List<CompactSchemaAdapter.ProjectedSyllable>,
        separator: String = " ",
    ) = CompactSchemaAdapter.SourceProjection(
        sourceSchema = "tone",
        translatorNamespace = "translator",
        dictionary = "d",
        alphabet = alphabet,
        algebra = emptyList(),
        separator = separator,
        producerClass = "script_translator",
        syllables = syllables,
    )

    private val toneProjection =
        projection(
            toneAlphabet,
            listOf(
                syllable("nǐ", "ni9", "ni"),
                syllable("hǎo", "hao9", "hao"),
                syllable("mā", "ma7", "ma"),
                syllable("dà", "da0", "da"),
            ),
        )

    private val toneSource =
        """
        schema: {schema_id: tone, name: tone}
        engine:
          translators: [punct_translator, script_translator, "lua_translator@*calc", "table_translator@mixed"]
        speller:
          alphabet: "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA1234567890`;/\\\\"
          algebra:
            - 'xform/^([a-z]+)${'$'}/${'$'}10/'
            - 'derive/(.+)(\\d)/${'$'}1/'
        translator: {dictionary: d}
        mixed: {dictionary: mixed}
        """.trimIndent().toByteArray()

    private fun speller(data: ByteArray): Map<String, Any?> {
        val root = requireNotNull(YamsYaml.loadMap(String(data)))
        return requireNotNull(root["speller"].asStringMap())
    }

    /** librime's `erase` drops a spelling only when the pattern matches all of it. */
    private fun erased(text: String): Boolean {
        val pattern = CompactSchemaAdapter.SOURCE_DIGIT_SPELLING_ERASE.removePrefix("erase/").removeSuffix("/")
        return Regex(pattern).matchEntire(text) != null
    }

    @Test
    fun toneDigitsAndBackslashNoLongerDisqualifyTheSource() {
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(toneSource, "tone"))
        for (fold in listOf(CompactFold.MERGED14, CompactFold.MERGED18)) {
            val physical = requireNotNull(adapter.physicalFold(toneAlphabet, fold))
            assertEquals("0123456789\\", physical.ignoredCharacters)
            assertTrue(physical.typeableAlphabet.contains("`"))
            assertTrue("paired uppercase stays a source key", physical.typeableAlphabet.contains("W"))
        }
        val nine = requireNotNull(adapter.physicalFold(toneAlphabet, CompactFold.PHONE9))
        assertEquals("01\\", nine.ignoredCharacters)
    }

    @Test
    fun letterGroupCompanionLeavesIgnoredKeysOut() {
        for (fold in listOf(CompactFold.MERGED14, CompactFold.MERGED18)) {
            val data = adapter.generate(toneSource, "tone", fold, null, toneProjection)
            val speller = speller(data)
            assertFalse("a key the layout never sends is not accepted", (speller["alphabet"] as String).contains("\\"))
            val algebra = requireNotNull(speller["algebra"].asStringList())
            assertFalse(algebra.contains(CompactSchemaAdapter.SOURCE_DIGIT_SPELLING_ERASE))
        }
    }

    /** A tone digit would read as a letter key once letters fold (`hao9` -> `4269`), so erase first. */
    @Test
    fun nineKeyCompanionErasesToneSpellingsBeforeTheFold() {
        val data = adapter.generate(toneSource, "tone", CompactFold.PHONE9, null, toneProjection)
        val speller = speller(data)
        val algebra = requireNotNull(speller["algebra"].asStringList())
        val erase = algebra.indexOf(CompactSchemaAdapter.SOURCE_DIGIT_SPELLING_ERASE)
        assertTrue(erase >= 0)
        assertEquals("nothing runs between the erase and the fold", true, algebra[erase + 1].startsWith("xlit/"))
        assertTrue("the source's own rules run first", algebra.indexOf("derive/(.+)(\\d)/\$1/") < erase)
        assertFalse((speller["alphabet"] as String).contains("\\"))

        for (tone in listOf("ni9", "hao9", "ma7", "da0", "h9")) assertTrue(tone, erased(tone))
        for (plain in listOf("ni", "hao", "ma", "da", "`", "h")) assertFalse(plain, erased(plain))
        val aliases = adapter.tokenAliasAssignments(toneProjection).map { it.second }
        assertTrue(aliases.isNotEmpty())
        for (alias in aliases) assertFalse(alias, erased(alias))
        assertFalse(erased("90"))
        assertFalse(CompactFold.PHONE9.keysSent.contains('0'))
        assertTrue(adapter.tokenAliasLockAuthorized(toneProjection, data))
    }

    /** A telephone-digit source's digits mean what the keys mean: nothing is erased. */
    @Test
    fun numericCodeSourceKeepsItsDigitSpellings() {
        val numericAlphabet = "23456789abcdefghijklmnopqrstuvwxyz"
        val numeric = projection(numericAlphabet, listOf(syllable("ni", "64"), syllable("hao", "426")), "'")
        assertFalse(adapter.erasesSourceDigitSpellings(numeric))
        assertTrue(adapter.erasesSourceDigitSpellings(toneProjection))
        val source =
            """
            schema: {schema_id: t9, name: t9}
            engine: {translators: [script_translator]}
            speller:
              alphabet: "$numericAlphabet"
              algebra: ['xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/']
            translator: {dictionary: d}
            """.trimIndent().toByteArray()
        val data = adapter.generate(source, "t9", CompactFold.PHONE9, null, numeric)
        assertFalse(
            requireNotNull(speller(data)["algebra"].asStringList()).contains(
                CompactSchemaAdapter.SOURCE_DIGIT_SPELLING_ERASE,
            ),
        )
        // Its spellings are all digits, so a letter-group layout reaches none of them.
        val plan = CompactSchemaAdapter.CompanionPlan(source, "t9", numeric, "'")
        assertNull(plan.companion(CompactFold.MERGED14))
        assertNull(plan.companion(CompactFold.MERGED18))
        assertNotNull(plan.companion(CompactFold.PHONE9))
    }

    @Test
    fun toneSourceGetsACompanionForEveryCompactLayout() {
        val plan = CompactSchemaAdapter.CompanionPlan(toneSource, "tone", toneProjection, " '")
        for (fold in listOf(CompactFold.MERGED14, CompactFold.MERGED18, CompactFold.PHONE9)) {
            assertNotNull(fold.id, plan.companion(fold))
        }
        // A source whose every spelling needs an ignored key gets none.
        val toneOnly = projection(toneAlphabet, listOf(syllable("nǐ", "ni9")))
        val unreachable = CompactSchemaAdapter.CompanionPlan(toneSource, "tone", toneOnly, " '")
        assertNull(unreachable.companion(CompactFold.MERGED14))
        assertNull(unreachable.companion(CompactFold.PHONE9))
    }
}

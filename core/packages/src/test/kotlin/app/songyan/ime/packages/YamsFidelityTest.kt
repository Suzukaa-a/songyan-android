package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gaps found comparing the ported fuzzy-pinyin and typo-correction readers with iOS: iOS reads
 * compiled schemas with Yams (YAML 1.1 types) and Foundation string rules, so the readers must
 * too, or a value the iOS code accepts reads differently here.
 */
class YamsFidelityTest {
    @Test
    fun theSwitchIsReadWithYamsBooleanRules() {
        val target = TypoCorrection.Target("x", "translator")
        // YAML 1.1 booleans: iOS sees Bool(true), a YAML 1.2 reader would see the string "yes".
        assertEquals(
            true,
            TypoCorrection.compiledValue("translator:\n  enable_correction: yes\n".toByteArray(), target),
        )
        assertEquals(
            false,
            TypoCorrection.compiledValue("translator:\n  enable_correction: Off\n".toByteArray(), target),
        )
        assertNull(TypoCorrection.compiledValue("translator:\n  enable_correction: 'yes'\n".toByteArray(), target))
    }

    @Test
    fun anUnquotedNumericAlphabetIsTakenLiterallyUnderYamsTypes() {
        fun schema(alphabet: String) = compiledSchema(id = "s", alphabet = alphabet)
        // `0b11` loads as the integer 3, whose text has no letters: not a full alphabet.
        assertTrue(TypoCorrection.eligibility(schema("0b11"), "s") is TypoCorrection.Eligibility.Ineligible)
        assertTrue(FuzzyPinyin.eligibility(schema("0b11"), "s") is FuzzyPinyin.Eligibility.Ineligible)
        assertTrue(
            TypoCorrection.eligibility(
                schema("zyxwvutsrqponmlkjihgfedcba"),
                "s",
            ) is TypoCorrection.Eligibility.Eligible,
        )
    }

    @Test
    fun identifierChecksFollowIcuDollarSemantics() {
        // Swift `range(of:options:.regularExpression)`: `$` also matches before a final line break.
        assertTrue(TypoCorrection.validIdentifier("abc\n"))
        assertFalse(TypoCorrection.validIdentifier("a b"))
        assertFalse(TypoCorrection.validIdentifier(""))
        assertTrue(CompactSchemaAdapter.isGeneratedCompanionID("keyboard_47246213a538cb99d374_14\n"))
        assertFalse(CompactSchemaAdapter.isGeneratedCompanionID("xkeyboard_47246213a538cb99d374_14"))
    }

    @Test
    fun schemaNameTrimsOnlyFoundationWhitespace() {
        fun name(value: String) = FuzzyPinyin.schemaName("schema:\n  name: $value\n".toByteArray())
        assertEquals("Flypy", name("Flypy"))
        assertNull(name("'   '"))
        // U+00A0 is a Unicode space separator: whitespace to Foundation, not to Kotlin's isWhitespace.
        assertNull(name("'\u00A0'"))
        // A line break is not in `CharacterSet.whitespaces`, so a name made of one is kept.
        assertEquals("\n", name("\"\\n\""))
    }

    @Test
    fun algebraKeyDetectionSeesThroughNoBreakSpaces() {
        assertTrue(FuzzyPinyin.touchesAlgebraKey("\u00A0\u00A0speller/algebra:"))
        assertTrue(FuzzyPinyin.touchesAlgebraKey("\t- speller/algebra/+:"))
        assertFalse(FuzzyPinyin.touchesAlgebraKey("name: algebra"))
    }

    @Test
    fun describeScalarNamesYamlNullTheWaySwiftDoes() {
        assertEquals("<null>", describeScalar(YamsYaml.Null))
        assertEquals("true", describeScalar(true))
        assertEquals("1.0", describeScalar(1.0))
        assertEquals("23456789", describeScalar(23456789L))
    }

    @Test
    fun luaSchemaNameUsesTheSameDescription() {
        val data = "schema:\n  schema_id: s\n  name:\nengine:\n  processors: [speller]\n".toByteArray()
        assertEquals("<null>", LuaComponents.state(data, "s")?.name)
    }
}

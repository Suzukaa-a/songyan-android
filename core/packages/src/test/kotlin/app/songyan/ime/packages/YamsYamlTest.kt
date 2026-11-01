package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YamsYaml] must behave like Yams 5.4.0 (libyaml): the iOS generator's output is compared byte
 * for byte, and its YAML 1.1 type resolution decides what a compiled schema "contains". The
 * expected texts below are the Yams results for the same inputs; several appear verbatim in the
 * bundled companion files (`''''`, `'90000'`, `1e+0`, `"\xB7"`).
 */
class YamsYamlTest {
    private fun load(text: String): Map<String, Any?> = requireNotNull(YamsYaml.loadMap(text))

    private fun dump(value: Any?): String = YamsYaml.dump(value)

    // ---- load: YAML 1.1 type resolution ----

    @Test
    fun plainScalarsResolveWithTheYams11Rules() {
        val root =
            load(
                """
                a: yes
                b: No
                c: on
                d: 0b11
                e: 0o17
                f: 017
                g: 0x1F
                h: 1_000
                i: 08
                j: 1.0
                k: ~
                l:
                m: 2024-05-20
                n: 1:30
                o: .inf
                p: -12
                q: 1e3
                r: true
                """.trimIndent(),
            )
        assertEquals(true, root["a"])
        assertEquals(false, root["b"])
        assertEquals(true, root["c"])
        assertEquals(3L, root["d"])
        assertEquals(15L, root["e"])
        assertEquals(15L, root["f"])
        assertEquals(31L, root["g"])
        assertEquals(1000L, root["h"])
        // `08` is not an octal int, so the float rule takes it.
        assertEquals(8.0, root["i"])
        assertEquals(1.0, root["j"])
        assertEquals(YamsYaml.Null, root["k"])
        assertEquals(YamsYaml.Null, root["l"])
        assertEquals(YamsYaml.Timestamp("2024-05-20T00:00:00Z"), root["m"])
        assertEquals(90L, root["n"])
        assertEquals(Double.POSITIVE_INFINITY, root["o"])
        assertEquals(-12L, root["p"])
        assertEquals(1000.0, root["q"])
        assertEquals(true, root["r"])
    }

    @Test
    fun quotedScalarsAndExplicitStringTagsStayStrings() {
        val root = load("a: '0x1F'\nb: \"yes\"\nc: !!str 123\nd: '~'\ne: ''\nf: \"null\"\n")
        assertEquals("0x1F", root["a"])
        assertEquals("yes", root["b"])
        assertEquals("123", root["c"])
        assertEquals("~", root["d"])
        assertEquals("", root["e"])
        assertEquals("null", root["f"])
    }

    @Test
    fun integersBeyondTheMachineWordStayStrings() {
        assertEquals("99999999999999999999", load("a: 99999999999999999999")["a"])
    }

    @Test
    fun mergeKeysFlattenAndExplicitKeysWin() {
        val root =
            load(
                """
                base: &base {x: 1, y: 2}
                other: &other {y: 20, z: 30}
                one: {<<: *base, y: 5}
                many: {<<: [*base, *other]}
                """.trimIndent(),
            )
        assertEquals(mapOf("x" to 1L, "y" to 5L), root["one"])
        // Earlier maps in a merge list take priority over later ones.
        assertEquals(mapOf("x" to 1L, "y" to 2L, "z" to 30L), root["many"])
    }

    @Test
    fun nonScalarKeysAndEmptyDocumentsAreHandledLikeSwift() {
        assertEquals(null, YamsYaml.load(""))
        assertEquals(null, YamsYaml.loadMap("- a\n- b\n"))
        assertEquals(null, YamsYaml.loadMap("{unterminated"))
    }

    // ---- dump ----

    @Test
    fun keysAreSortedAndBlockSequencesAreIndentless() {
        assertEquals("a: x\nb: 1\n", dump(mapOf("b" to 1L, "a" to "x")))
        assertEquals(
            "a:\n- x\n- y\nb: {}\nc: []\n",
            dump(
                mapOf(
                    "c" to emptyList<String>(),
                    "b" to emptyMap<String, Any?>(),
                    "a" to listOf("x", "y"),
                ),
            ),
        )
        assertEquals("- k: v\n- - 1\n  - 2\n", dump(listOf(mapOf("k" to "v"), listOf(1L, 2L))))
        assertEquals("k:\n  n: 1\n", dump(mapOf("k" to mapOf("n" to 1L))))
    }

    @Test
    fun stringsThatWouldResolveToOtherTypesAreSingleQuoted() {
        assertEquals("a: '90000'\n", dump(mapOf("a" to "90000")))
        assertEquals(
            "a: 'true'\nb: 'null'\nc: ''\nd: '~'\ne: '1.5'\nf: '2024-05-20'\n",
            dump(
                mapOf(
                    "a" to "true",
                    "b" to "null",
                    "c" to "",
                    "d" to "~",
                    "e" to "1.5",
                    "f" to "2024-05-20",
                ),
            ),
        )
        assertEquals("a: true\nb: 'true'\n", dump(mapOf("a" to true, "b" to "true")))
    }

    @Test
    fun indicatorsForceQuotingAndApostropheDoublesUp() {
        assertEquals("a: ''''\n", dump(mapOf("a" to "'")))
        assertEquals("a: ' '''\n", dump(mapOf("a" to " '")))
        assertEquals(
            "a: '! x'\nb: '#x'\nc: '- x'\nd: x y\ne: 'a: b'\nf: a:b\n",
            dump(
                mapOf(
                    "a" to "! x",
                    "b" to "#x",
                    "c" to "- x",
                    "d" to "x y",
                    "e" to "a: b",
                    "f" to "a:b",
                ),
            ),
        )
        assertEquals("a: '[x]'\nb: x[y]\n", dump(mapOf("a" to "[x]", "b" to "x[y]")))
        assertEquals("a: 'x #y'\nb: x#y\n", dump(mapOf("a" to "x #y", "b" to "x#y")))
        assertEquals("'---': a\n", dump(mapOf("---" to "a")))
    }

    @Test
    fun everyNonAsciiCharacterIsEscapedInADoubleQuotedScalar() {
        assertEquals("a: \"\\u4E2D\\u6587\"\n", dump(mapOf("a" to "中文")))
        assertEquals("a: \"x \\xB7 y\"\n", dump(mapOf("a" to "x · y")))
        assertEquals("a: \"\\U0001F600\"\n", dump(mapOf("a" to "😀")))
        assertEquals("a: \"\\t\"\n", dump(mapOf("a" to "\t")))
        assertEquals("\"\\u4E2D\": 1\n", dump(mapOf("中" to 1L)))
        // The description string of the built-in scheme: a trailing newline plus non-ASCII text.
        assertEquals("a: \"x \\u952E\\n\"\n", dump(mapOf("a" to "x 键\n")))
    }

    @Test
    fun doublesUseTheScientificFormOfTheNumberFormatter() {
        assertEquals("a: 1e+0\n", dump(mapOf("a" to 1.0)))
        assertEquals("a: 5e-1\n", dump(mapOf("a" to 0.5)))
        assertEquals("a: 1.23456e+2\n", dump(mapOf("a" to 123.456)))
        assertEquals("a: 1e+2\n", dump(mapOf("a" to 100.0)))
        assertEquals("a: -2.5e-3\n", dump(mapOf("a" to -0.0025)))
        assertEquals("a: 0e+0\n", dump(mapOf("a" to 0.0)))
        assertEquals(
            "a: .inf\nb: -.inf\nc: .nan\n",
            dump(
                mapOf(
                    "a" to Double.POSITIVE_INFINITY,
                    "b" to Double.NEGATIVE_INFINITY,
                    "c" to Double.NaN,
                ),
            ),
        )
        // 15 significant digits, half-even.
        assertEquals("a: 1.23456789012346e+0\n", dump(mapOf("a" to 1.234567890123456)))
    }

    @Test
    fun loadedValuesRoundTripThroughTheSameTypes() {
        val text = "a: yes\nb: 0x1F\nc: 1.0\nd: ~\ne: 2024-05-20\nf: '0x1F'\n"
        assertEquals("a: true\nb: 31\nc: 1e+0\nd: null\ne: 2024-05-20T00:00:00Z\nf: '0x1F'\n", dump(load(text)))
    }

    @Test
    fun timestampsKeepTheirTimeZoneAndFraction() {
        assertEquals("a: 2024-05-20T10:30:00Z\n", dump(load("a: 2024-05-20 10:30:00")))
        assertEquals("a: 2024-05-20T08:30:00Z\n", dump(load("a: 2024-05-20T10:30:00+02:00")))
        assertEquals("a: 2024-05-20T10:30:00.5Z\n", dump(load("a: 2024-05-20T10:30:00.5Z")))
        // An impossible date is not a Date, so it stays the string it was written as.
        assertEquals("a: '2024-13-45'\n", dump(load("a: 2024-13-45")))
    }

    @Test
    fun longPlainScalarsFoldAfterColumn80() {
        val words = List(10) { "abcdefghi" }
        val value = words.joinToString(" ")
        val expected = "a: " + words.take(8).joinToString(" ") + "\n  " + words.drop(8).joinToString(" ") + "\n"
        assertEquals(expected, dump(mapOf("a" to value)))
        assertEquals(mapOf("a" to value), load(expected))
    }

    @Test
    fun overlongKeysUseTheExplicitKeyForm() {
        val key = "k".repeat(130)
        val out = dump(mapOf(key to "v"))
        assertEquals("? $key\n: v\n", out)
        assertEquals(mapOf(key to "v"), load(out))
    }

    @Test
    fun dumpOutputIsAlwaysReadableByTheLoader() {
        val tricky =
            mapOf(
                "a" to
                    listOf(
                        "derive/^([jqxy])u$/\$1v/",
                        "xform/(.)ao$/\$1C/",
                        "abbrev/^([a-z]).+$/\$1/",
                        "xlit/<~|>#@^+!%*_/abcdegjoqtuz/",
                    ),
                "b" to mapOf("k: v" to "x: y", "#" to "- z"),
                "c" to "line1\nline2",
                "d" to " lead and trail ",
                "e" to "",
            )
        val reloaded = load(dump(tricky))
        assertEquals(tricky["a"], reloaded["a"])
        assertEquals(tricky["b"], reloaded["b"])
        assertEquals("line1\nline2", reloaded["c"])
        assertEquals(" lead and trail ", reloaded["d"])
        assertEquals("", reloaded["e"])
        assertTrue(dump(tricky).endsWith("\n"))
    }
}

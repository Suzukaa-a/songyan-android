package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ported from ImportSafetyTests: the YAML alias-bomb guard as seen through the public entry points. */
class ImportSafetyTest {
    private val billionLaughs =
        buildString {
            append("a: &a [\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\"]\n")
            var previous = "a"
            for (name in listOf("b", "c", "d", "e", "f", "g", "h", "i")) {
                append("$name: &$name [${List(9) { "*$previous" }.joinToString(",")}]\n")
                previous = name
            }
        }

    private val rimeStyleAnchors =
        "# Comment mentions *not_an_alias and &not_an_anchor.\n" +
            "base: &base\n  enable_completion: true\n  enable_sentence: false\n" +
            "speller:\n  alphabet: \"zyxwvutsrqponmlkjihgfedcba*\"\n  algebra:\n" +
            "    - xform/^(.*)\$/\$1/\n    - \"derive/^([a-z]+)*\$/x/\"\n" +
            "translator:\n  <<: *base\n  dictionary: main\n" +
            "melt_eng:\n  <<: *base\n  dictionary: melt_eng\n" +
            "list: [*base, {a: 1}]\n"

    private fun assertLimitExceeded(
        yaml: String,
        block: (String) -> Unit,
    ) {
        try {
            block(yaml)
            fail("expected a rejection for: $yaml")
        } catch (_: BoundedYaml.LimitExceeded) {
            // Expected.
        }
    }

    @Test
    fun aBillionLaughsDocumentIsRejectedQuicklyByLoadAndCompose() {
        val started = System.nanoTime()
        assertLimitExceeded(billionLaughs) { BoundedYaml.load(it) }
        assertLimitExceeded(billionLaughs) { BoundedYaml.compose(it) }
        assertTrue((System.nanoTime() - started) / 1_000_000_000 < 10)
    }

    @Test
    fun theDependencyScanReportsAnAliasBombWithTheFileName() {
        val files = mapOf("main.schema.yaml" to ("schema: {schema_id: main}\n" + billionLaughs).toByteArray())
        val started = System.nanoTime()
        try {
            SchemeSourceDependencies(files.keys) { files[it] }.missing()
            fail("an alias bomb must be rejected")
        } catch (error: SchemeSourceDependencies.Invalid) {
            assertEquals("main.schema.yaml", error.path)
            assertTrue(error.message, error.message!!.contains("main.schema.yaml"))
            assertTrue(error.message, error.message!!.contains("alias expansion"))
        }
        assertTrue((System.nanoTime() - started) / 1_000_000_000 < 10)
    }

    @Test
    fun anAliasAsAMappingKeyIsRejectedInFlowAndExplicitForms() {
        assertLimitExceeded("a: &a [1, 2]\nb: {*a : 1}\n") { BoundedYaml.load(it) }
        assertLimitExceeded("a: &a [1, 2]\n? *a\n: 1\n") { BoundedYaml.load(it) }
    }

    @Test
    fun ordinaryAnchorsAndStarsInRegexesAreAccepted() {
        val root = BoundedYaml.load(rimeStyleAnchors).asStringMap()!!
        assertEquals("main", root["translator"].asStringMap()!!["dictionary"])
        // iOS (Yams) merges `<<` here; the engine does not, so the validator keeps the key literal.
        assertEquals(true, root["translator"].asStringMap()!!["<<"].asStringMap()!!["enable_completion"])
        assertNotNull(BoundedYaml.compose(rimeStyleAnchors))
        assertEquals(
            listOf("main.dict.yaml", "melt_eng.dict.yaml"),
            missing("main.schema.yaml" to ("schema: {schema_id: main}\n" + rimeStyleAnchors)),
        )
    }

    @Test
    fun tagsKeepTheShadowAndOriginalSyntaxValidation() {
        val tags = "plain: !regex*pattern x*y\nquoted: !<tag:example.org,2026:regex*> '.*'\n"
        val root = BoundedYaml.load(tags).asStringMap()!!
        assertEquals("x*y", root["plain"])
        assertEquals(".*", root["quoted"])
        assertEquals(root, YamsYaml.loadMap(tags))
        // Duplicate keys stay accepted (last one wins) because the engine reads them that way.
        assertEquals("b", BoundedYaml.load("same: '*a'\nsame: b\n").asStringMap()!!["same"])
        try {
            BoundedYaml.load("base: &a*b value\n")
            fail("expected a syntax error")
        } catch (_: Exception) {
            // Expected.
        }
    }

    @Test
    fun aQuoteInsideABlockScalarCannotHideAliases() {
        val aliases = "a: &a [1, 2]\nb: &b [*a, *a]\nc: [*b, *b]\n"
        for (scalar in listOf("|\n  '\n", ">-\n  \"\n", "|2+\n  '\n\n", ">2-\n  \" *literal\n")) {
            val yaml = "description: $scalar$aliases"
            val root = BoundedYaml.load(yaml).asStringMap()!!
            val pair = listOf(1, 2)
            assertEquals(scalar, listOf(listOf(pair, pair), listOf(pair, pair)), root["c"])
            assertNotNull(BoundedYaml.compose(yaml))
        }
    }

    @Test
    fun scalarSyntaxAndRegexStarsRemainLiteral() {
        val yaml =
            "block: |\n  ' *fake ? [1]:\n" +
                "folded: >-\n  \" *fake\n" +
                "plain: regex ' *fake and \" *fake\n  continued *fake # trailing comment\n" +
                "quoted: \"escaped \\\" *fake and \\\\ *fake\"\n" +
                "single: 'escaped '' *fake'\n" +
                "regex:\n  - xform/^(.*)\$/\$1/\n  - derive/^([a-z]+)*\$/x/\n  - xform/ :* /x/\n  - xform/#*?/'/\n" +
                "keys:\n  a*b: one\n  axb: two\n  \"*quoted\": three\n"
        val root = BoundedYaml.load(yaml).asStringMap()!!
        assertEquals("' *fake ? [1]:\n", root["block"])
        assertEquals(mapOf("a*b" to "one", "axb" to "two", "*quoted" to "three"), root["keys"])
        assertNotNull(BoundedYaml.load(yaml + "\nbase: &base [1]\ncopy: *base\n"))
    }

    @Test
    fun aliasesAreResolvedInFlowCollectionsAndBesideComments() {
        for (yaml in listOf(
            "base: &base [1]\nflow: {\"copy\":*base}\n",
            "base: &base [1]\nflow: [*base,*base]\n",
            "base: &base [1]\ncopy: *base # comment\n",
            "description: |\n  '\nbase: &base [1]\ncopy: *base\n",
        )) {
            val root = BoundedYaml.load(yaml).asStringMap()!!
            assertEquals(yaml, listOf(1), root["base"])
        }
        val flow = BoundedYaml.load("base: &base [1]\nflow: [*base,*base]\n").asStringMap()!!
        assertEquals(listOf(listOf(1), listOf(1)), flow["flow"])
    }

    @Test
    fun collectionKeysContainingAliasesAreRejected() {
        val base = "description: |\n  '\nbase: &base [1, 2]\n"
        for (key in listOf(
            "? *base\n: value\n",
            "mapping: {*base : value}\n",
            "? [*base, tail]\n: value\n",
            "mapping: {[head, *base]: value}\n",
            "? {nested: *base}\n: value\n",
            "? [head, {nested: [*base]}]\n: value\n",
        )) {
            assertLimitExceeded(base + key) { BoundedYaml.load(it) }
            assertLimitExceeded(base + key) { BoundedYaml.compose(it) }
        }
        val loaded = BoundedYaml.load("base: &base [1]\n? scalar\n: *base\n").asStringMap()!!
        assertEquals(listOf(1), loaded["scalar"])
    }

    @Test
    fun theAliasCountBoundaryAppliesToAliasesNotToLiteralStars() {
        val limit = BoundedYaml.MAXIMUM_ALIASES
        val base = "base: &base 1\ncopy: ["
        val atLimit = base + List(limit) { "*base" }.joinToString(",") + "]\n"
        assertEquals(limit, (BoundedYaml.load(atLimit).asStringMap()!!["copy"] as List<*>).size)
        val beyond = base + List(limit + 1) { "*base" }.joinToString(",") + "]\n"
        try {
            BoundedYaml.load(beyond)
            fail("more aliases than the budget must be rejected")
        } catch (error: BoundedYaml.LimitExceeded) {
            assertTrue(error.reason, error.reason.contains(limit.toString()))
        }
        val literal = "pattern: '" + "*".repeat(limit + 1) + "'\n"
        assertEquals("*".repeat(limit + 1), BoundedYaml.load(literal).asStringMap()!!["pattern"])
    }

    @Test
    fun originalSyntaxValidationAndUnicodeSurviveTheAliasPreCheck() {
        for (invalid in listOf("base: &a*b value\n", "x: '*a\n")) {
            try {
                BoundedYaml.load(invalid)
                fail("invalid YAML must still fail: $invalid")
            } catch (_: Exception) {
                // Expected: syntax errors are reported even when the text contains stars.
            }
        }
        val unicode = "说明: |\n  ' 😀\n根: &base [你好]\n副本: *base\n".replace("\n", "\r\n")
        assertEquals(listOf("你好"), BoundedYaml.load(unicode).asStringMap()!!["副本"])
    }

    @Test
    fun finderMetadataIsClassifiedByPathComponent() {
        for (path in listOf(
            "__MACOSX",
            "__MACOSX/rime/._a.yaml",
            "._default.yaml",
            "lua/._x.lua",
            ".DS_Store",
            "lua/.DS_Store",
        )) {
            assertTrue(path, SchemeSourceDependencies.isFinderMetadata(path))
        }
        for (path in listOf("default.yaml", "lua/x.lua", "a__MACOSX/x", "lua/_x.lua", ".gitkeep")) {
            assertFalse(path, SchemeSourceDependencies.isFinderMetadata(path))
        }
    }

    private fun missing(vararg files: Pair<String, String>): List<String> {
        val bytes = files.associate { it.first to it.second.toByteArray() }
        return SchemeSourceDependencies(bytes.keys) { bytes[it] }.missing()
    }
}

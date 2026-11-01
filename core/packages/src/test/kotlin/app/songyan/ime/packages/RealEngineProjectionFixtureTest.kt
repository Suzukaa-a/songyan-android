package app.songyan.ime.packages

import app.songyan.ime.keyboard.display.SchemaDisplayCapability
import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.layout.CompactFold
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Real-librime evidence: the JSON fixtures were produced by a probe run against pinned macOS
 * librime 1.17.0. They show the re-expression mapping comes from the real source-algebra
 * projection. Ported from the iOS `RealEngineProjectionFixtureTests`.
 */
class RealEngineProjectionFixtureTest {
    private val builtin = File("../rime/src/main/assets/rime/builtin")
    private val adapter = CompactSchemaAdapter

    private fun loadFixture(name: String): CompactSchemaAdapter.SourceProjection {
        val stream = javaClass.getResourceAsStream("/package-fixtures/$name.json")
        assertNotNull("missing real-engine fixture $name", stream)
        return requireNotNull(CompactSchemaAdapter.SourceProjection.decode(stream!!.readBytes()))
    }

    private fun syllable(
        token: String,
        spellings: List<Triple<String, Boolean, Boolean>>,
    ) = CompactSchemaAdapter.ProjectedSyllable(
        token,
        spellings.map { CompactSchemaAdapter.ProjectedSpelling(it.first, it.second, it.third) },
    )

    /** A near-real projection: token domain plus one identity normal spelling each. */
    private fun projection(tokens: List<String>) =
        CompactSchemaAdapter.SourceProjection(
            sourceSchema = "t",
            translatorNamespace = "translator",
            dictionary = "d",
            alphabet = "abcdefghijklmnopqrstuvwxyz",
            algebra = emptyList(),
            separator = "'",
            syllables = tokens.map { syllable(it, listOf(Triple(it, true, false))) },
        )

    private fun yaml(text: String): Map<String, Any?> = requireNotNull(YamsYaml.loadMap(text))

    private fun builtinYaml(name: String): Pair<String, Map<String, Any?>> {
        val text = File(builtin, name).readText()
        return text to yaml(text)
    }

    private fun lock(root: Map<String, Any?>): String? = root["keyboard_rewrite"].asStringMap()?.get("lock") as? String

    @Suppress("UNCHECKED_CAST")
    private fun lockMapOf(root: Map<String, Any?>): Map<String, String>? =
        root["keyboard_rewrite"].asStringMap()?.get("lock_map") as? Map<String, String>

    private fun json(value: Any?): JsonElement =
        when (value) {
            null, YamsYaml.Null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to json(it.value) })
            is List<*> -> JsonArray(value.map { json(it) })
            else -> error("unsupported $value")
        }

    private val sourceCompiled =
        """
        schema: {schema_id: t, name: t}
        engine: {translators: [script_translator]}
        speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
        translator: {dictionary: t}
        """.trimIndent().toByteArray()

    private fun display() =
        CompactSchemaAdapter.DisplayCapability(
            version = 1,
            mode = "source_code",
            sourceSchema = "t",
            translatorNamespace = "translator",
            separator = "'",
            mappingPolicy = "unique_normal",
            syllables = emptyMap(),
        )

    private fun generate(
        compiled: ByteArray = sourceCompiled,
        id: String = "t",
        fold: CompactFold = CompactFold.MERGED14,
        display: CompactSchemaAdapter.DisplayCapability? = display(),
        projection: CompactSchemaAdapter.SourceProjection?,
    ): ByteArray = adapter.generate(compiled, id, fold, display, projection)

    @Test
    fun realFlypy26ProjectionYieldsAcceptanceSourceCoding() {
        val projection = loadFixture("songyan_flypy.projection")
        assertEquals("songyan_flypy", projection.sourceSchema)
        assertEquals("translator", projection.translatorNamespace)
        assertEquals("songyan_basic", projection.dictionary)
        assertEquals("canonical_pinyin", projection.declaration)
        assertFalse("the real source algebra must be projected", projection.algebra.isEmpty())

        val build = adapter.makeDisplayCapability(projection, displaySeparator = "'")
        assertEquals("source_code", build.capability.mode)
        assertEquals("'", build.capability.separator)
        assertEquals("zc", build.capability.syllables["zao"])
        assertEquals("uh", build.capability.syllables["shang"])
        assertEquals("hc", build.capability.syllables["hao"])
        assertNull(build.capability.syllables["qu"])
        assertEquals("ambiguous_normal", build.capability.rejectedTokens["qu"])
        try {
            adapter.makeDisplayCapability(projection, requireFullDomain = true)
            fail("a partial domain must throw when the full domain is required")
        } catch (_: CompactSchemaAdapter.DisplayCapabilityError.IncompleteDomain) {
        }
    }

    @Test
    fun realQuanpinProjectionIsExplicitIdentity() {
        val projection = loadFixture("songyan_quanpin.projection")
        assertTrue("the full-pinyin source algebra is empty", projection.algebra.isEmpty())
        val build = adapter.makeDisplayCapability(projection)
        assertTrue(build.rejections.isEmpty())
        assertEquals("zao", build.capability.syllables["zao"])
        assertEquals("shang", build.capability.syllables["shang"])
        assertEquals("hao", build.capability.syllables["hao"])
    }

    @Test
    fun realSecondDoublePinyinMspyUsesItsOwnAlgebra() {
        val projection = loadFixture("double_pinyin_mspy_test.projection")
        assertEquals("double_pinyin_mspy_test", projection.sourceSchema)
        val build = adapter.makeDisplayCapability(projection)
        assertEquals("zk", build.capability.syllables["zao"])
        assertEquals("hk", build.capability.syllables["hao"])
        assertNull(build.capability.syllables["qu"])
    }

    @Test
    fun flypy14MergedAlgebraMustNotBeUsedAsSource() {
        val evidence = File("src/test/resources/package-fixtures/README.md")
        // The README is not synced to the Android tree, so the evidence is pinned from the real run.
        if (evidence.exists()) {
            val text = evidence.readText()
            assertTrue(text.contains("14-key companion `zcuggc`: `preedit=\"zc ug gc\""))
            assertTrue(text.contains("canonical=\"zao shang hao\""))
        }
        val projection = loadFixture("songyan_flypy.projection")
        // The 14-key code `zc ug gc` is the merged form of the 26-key `zc uh hc`; the display
        // table must hold the 26-key source codes, never the merged ones.
        val syllables = adapter.makeDisplayCapability(projection, displaySeparator = "'").capability.syllables
        assertEquals("uh", syllables["shang"])
    }

    @Test
    fun builtinFlypy14CapabilityMatchesRealSourceProjection() {
        val projection = loadFixture("songyan_flypy.projection")
        val generated = adapter.makeDisplayCapability(projection, displaySeparator = "'").capability
        val (_, root) = builtinYaml("keyboard_3d7821f984693864ae91_14.schema.yaml")
        val block = requireNotNull(root["keyboard_adapter"].asStringMap()) { "built-in 14-key must declare display" }
        val embedded = requireNotNull(SchemaDisplayCapability.fromWire(json(block) as JsonObject))
        assertEquals(CompositionDisplayMode.SOURCE_CODE, embedded.mode)
        assertEquals(generated.syllables, embedded.syllables)
        assertEquals("keyboard_3d7821f984693864ae91_14", embedded.adapterSchema)
        assertEquals("'", embedded.separator)
        assertEquals("zc", embedded.syllables["zao"])
        assertEquals("uh", embedded.syllables["shang"])
        assertEquals("hc", embedded.syllables["hao"])
    }

    @Test
    fun builtinFlypy14CarriesGenericAliasLockMatchingProjection() {
        val projection = loadFixture("songyan_flypy.projection")
        val (text, root) = builtinYaml("keyboard_3d7821f984693864ae91_14.schema.yaml")
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        assertEquals(adapter.lockMap(projection), lockMapOf(root))
        assertTrue(adapter.tokenAliasLockAuthorized(projection, text.toByteArray()))
    }

    @Test
    fun builtinT9DeclaresCanonicalDisplayAndGenericAliasLock() {
        val (_, root) = builtinYaml("keyboard_47246213a538cb99d374_9.schema.yaml")
        val capability =
            requireNotNull(SchemaDisplayCapability.fromWire(json(root["keyboard_adapter"].asStringMap()) as JsonObject))
        assertEquals(CompositionDisplayMode.CANONICAL, capability.mode)
        assertEquals("songyan_quanpin", capability.sourceSchema)
        assertEquals("keyboard_47246213a538cb99d374_9", capability.adapterSchema)
        assertEquals(" ", capability.separator)
        assertTrue(capability.syllables.isEmpty())
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        assertNotNull(lockMapOf(root))
    }

    // ---- Schema-agnostic bounded token alias domain ----

    @Test
    fun tokenAliasDomainIsSchemaAgnostic() {
        assertEquals(listOf("bu", "ni", "shang"), adapter.tokenAliasDomain(projection(listOf("ni", "shang", "bu"))))
        assertEquals(
            listOf("ABC123", "abc", "abd", "english"),
            adapter.tokenAliasDomain(projection(listOf("abc", "abd", "english", "ABC123"))),
        )
        val bopomofo = listOf("ㄅㄧ", "ㄆㄧ")
        assertEquals(bopomofo, adapter.tokenAliasDomain(projection(bopomofo)))
        val assignments = adapter.tokenAliasAssignments(projection(listOf("ni", "shang", "A", "Z")))
        assertEquals(setOf("ni", "shang", "A", "Z"), assignments.map { it.first }.toSet())
        assertEquals(assignments.size, assignments.map { it.second }.toSet().size)
    }

    @Test
    fun tokenAliasDomainFailsClosedOnUnrepresentableTokens() {
        for (bad in listOf("ni x", "a/b", "a\tb", "a\nb", "a\u0007b", "a b", "a　b")) {
            assertNull("token $bad", adapter.tokenAliasDomain(projection(listOf("ni", bad))))
            assertTrue(adapter.tokenAliasAssignments(projection(listOf("ni", bad))).isEmpty())
        }
        assertNull(adapter.tokenAliasDomain(projection(emptyList())))
        assertNull(adapter.tokenAliasDomain(projection(listOf("ni", "ni"))))
        assertNull(adapter.tokenAliasDomain(projection(List(adapter.TOKEN_ALIAS_LIMIT + 1) { "ni" })))
    }

    @Test
    fun aliasPatternEscapesRegexMetacharacters() {
        assertEquals("^a\\.b$", adapter.aliasPattern("a.b"))
        assertEquals("^a\\$$", adapter.aliasPattern("a$"))
        assertEquals("^a\\[b\\]\\(c\\)\\*$", adapter.aliasPattern("a[b](c)*"))
        assertEquals("^\\\\$", adapter.aliasPattern("\\"))
        for (token in listOf("a.b", "a$", "x^y", "a|b", "a+b?", "a\\b", "ㄅㄧ")) {
            val pattern = requireNotNull(adapter.aliasPattern(token))
            assertEquals("round-trip", token, adapter.aliasToken(pattern.drop(1).dropLast(1)))
        }
        val derives = adapter.tokenAliasDerives(projection(listOf("a.b", "abd")))
        assertEquals(2, derives.size)
        assertTrue(derives.any { it.startsWith("derive/^a\\.b$/") })
        assertFalse(derives.any { it.contains("derive/^a.b$/") })
    }

    @Test
    fun aliasFixedWidthPrefixFreeAndDeterministic() {
        val projection = loadFixture("songyan_flypy.projection")
        val assignments = adapter.tokenAliasAssignments(projection)
        assertFalse(assignments.isEmpty())
        val width = adapter.tokenAliasWidth(projection.syllables.size)
        for ((_, alias) in assignments) {
            assertEquals(adapter.TOKEN_ALIAS_SENTINEL.length + width, alias.length)
            assertTrue(alias.startsWith(adapter.TOKEN_ALIAS_SENTINEL))
            assertTrue(alias.drop(adapter.TOKEN_ALIAS_SENTINEL.length).all { it.isDigit() })
        }
        val set = assignments.map { it.second }.toSet()
        assertEquals(assignments.size, set.size)
        for (a in set) for (b in set) if (a != b) assertFalse(b.startsWith(a))
        assertEquals(assignments.map { it.second }, adapter.tokenAliasAssignments(projection).map { it.second })
    }

    /** The alias holds a `0` that no compact physical layout can type, so physical input never forms one. */
    @Test
    fun tokenAliasIsNeverTypeableFromACompactLayout() {
        val physical14 = "qetuoadgjlzcbm".toSet()
        val physical9 = "23456789".toSet()
        for (count in listOf(1, 9, 10, 120, 999, 1000, 2166, 4096)) {
            val assigns = adapter.tokenAliasAssignments(projection(List(count) { "t$it" }))
            assertEquals(count, assigns.size)
            for ((_, alias) in assigns) {
                assertTrue("alias $alias (count=$count)", alias.contains('0'))
                assertFalse(alias.all { it in physical14 })
                assertFalse(alias.all { it in physical9 })
            }
            assertEquals("equal length (count=$count)", 1, assigns.map { it.second.length }.toSet().size)
        }
    }

    @Test
    fun tokenAliasDomainNearAndOverLimitFailClosed() {
        val limit = adapter.TOKEN_ALIAS_LIMIT
        assertEquals(4096, limit)
        val near = adapter.tokenAliasAssignments(projection(List(limit) { "t$it" }))
        assertEquals(limit, near.size)
        assertEquals(limit, near.map { it.second }.toSet().size)
        val over = projection(List(limit + 1) { "t$it" })
        assertNull(adapter.tokenAliasDomain(over))
        assertTrue(adapter.tokenAliasAssignments(over).isEmpty())
        assertFalse(adapter.tokenAliasLockAuthorized(over, "speller: {algebra: []}\n".toByteArray()))
        val root = yaml(String(generate(projection = over)))
        assertNull("over-limit must not write a rewrite capability", root["keyboard_rewrite"])
        assertNull(adapter.tokenAliasDomain(projection(listOf("ni", "a/b"))))
    }

    @Test
    fun existingAliasMapRoundTripsABakedCompanion() {
        val proj = projection(listOf("ni", "shang", "hao", "a.b"))
        val data = generate(projection = proj)
        assertEquals(adapter.lockMap(proj), adapter.existingAliasMap(data))
        assertNull(adapter.existingAliasMap("speller: {algebra: []}\n".toByteArray()))
        assertNull(
            "malformed alias width is rejected wholesale",
            adapter.existingAliasMap("speller:\n  algebra:\n    - derive/^ni\$/9/\n".toByteArray()),
        )
    }

    @Test
    fun tokenAliasLockAuthorizedIsSchemaAgnosticAndBindsBakedMap() {
        for (tokens in listOf(listOf("ni", "shang"), listOf("abc", "abd"), listOf("ㄅㄧ", "ㄆㄧ"))) {
            val proj = projection(tokens)
            val companion = generate(projection = proj)
            assertEquals("tokens $tokens", adapter.TOKEN_ALIAS_LOCK_VALUE, lock(yaml(String(companion))))
            assertTrue(adapter.tokenAliasLockAuthorized(proj, companion))
            assertFalse(adapter.tokenAliasLockAuthorized(projection(listOf("zzz", "yyy")), companion))
        }
    }

    @Test
    fun destructiveSourceStillGetsGenericAliasLock() {
        val destructive =
            CompactSchemaAdapter.SourceProjection(
                sourceSchema = "t",
                translatorNamespace = "translator",
                dictionary = "t",
                alphabet = "abcdefghijklmnopqrstuvwxyz",
                algebra = listOf("xform/^sh/U/"),
                separator = "'",
                syllables =
                    listOf(
                        syllable("shang", listOf(Triple("uh", true, false))),
                        syllable("ni", listOf(Triple("ni", true, false))),
                    ),
            )
        val root = yaml(String(generate(fold = CompactFold.PHONE9, projection = destructive)))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        assertNotNull(lockMapOf(root))
    }

    @Test
    fun noProjectionOrNoDisplayCapabilityEmitsNoLock() {
        val legacyRoot = yaml(String(generate(projection = null)))
        assertNull(legacyRoot["keyboard_rewrite"])
        assertNull(lock(legacyRoot))
        val root = yaml(String(generate(display = null, projection = projection(listOf("ni", "hao")))))
        assertNull("native display -> no display block", root["keyboard_adapter"])
        assertNotNull("rewrite is independent of display", root["keyboard_rewrite"])
    }

    // ---- Real rime-ice projection (shuangpin BU/NI + uppercase extras) ----

    @Test
    fun realDoublePinyinFlypyProjectionGetsBoundedSchemaAgnosticAliasLock() {
        val projection = loadFixture("double_pinyin_flypy.projection")
        assertEquals("double_pinyin_flypy", projection.sourceSchema)
        assertEquals("", projection.declaration)
        assertTrue(projection.syllables.size > 400)
        assertTrue(projection.syllables.any { it.token == "A" })
        val domain = requireNotNull(adapter.tokenAliasDomain(projection))
        assertTrue(domain.size > 400)
        for (token in listOf("bu", "bi", "ni", "nu", "A")) assertTrue(token, token in domain)
        val compiled =
            """
            schema: {schema_id: double_pinyin_flypy, name: double_pinyin_flypy}
            engine: {translators: [script_translator]}
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: rime_ice}
            """.trimIndent().toByteArray()
        val data = generate(compiled, "double_pinyin_flypy", projection = projection)
        val root = yaml(String(data))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        val lockMap = requireNotNull(lockMapOf(root))
        for (token in listOf("bu", "bi", "ni", "nu")) assertNotNull(token, lockMap[token])
        assertTrue(adapter.tokenAliasLockAuthorized(projection, data))
    }

    @Test
    fun shapeCodeAsciiProjectionGetsSchemaAgnosticAliasLock() {
        val projection =
            CompactSchemaAdapter.SourceProjection(
                sourceSchema = "shape_codes",
                translatorNamespace = "translator",
                dictionary = "shape_codes",
                alphabet = "abcdefghijklmnopqrstuvwxyz",
                algebra = listOf("derive/^abc$/ab/", "derive/^abd$/ab/"),
                separator = "'",
                syllables =
                    listOf(
                        syllable("abc", listOf(Triple("abc", true, false))),
                        syllable("abd", listOf(Triple("abd", true, false))),
                    ),
            )
        val compiled =
            """
            schema: {schema_id: shape_codes, name: shape_codes}
            engine: {translators: [script_translator]}
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: shape_codes}
            """.trimIndent().toByteArray()
        val data = generate(compiled, "shape_codes", projection = projection)
        val root = yaml(String(data))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        assertEquals(setOf("abc", "abd"), lockMapOf(root)?.keys)
        assertTrue(adapter.tokenAliasLockAuthorized(projection, data))
    }

    @Test
    fun bopomofoUnicodeProjectionGetsSchemaAgnosticAliasLock() {
        val tokens = listOf("ㄅㄧ", "ㄆㄧ")
        val projection =
            CompactSchemaAdapter.SourceProjection(
                sourceSchema = "bopomofo",
                translatorNamespace = "translator",
                dictionary = "bopomofo",
                alphabet = "abcdefghijklmnopqrstuvwxyz",
                algebra = listOf("xform/^ㄅㄧ$/bi/", "xform/^ㄆㄧ$/bi/"),
                separator = "'",
                syllables = tokens.map { syllable(it, listOf(Triple(it, true, false))) },
            )
        val compiled =
            """
            schema: {schema_id: bopomofo, name: bopomofo}
            engine: {translators: [script_translator]}
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: bopomofo}
            """.trimIndent().toByteArray()
        val data = generate(compiled, "bopomofo", projection = projection)
        val root = yaml(String(data))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, lock(root))
        assertEquals(tokens.toSet(), lockMapOf(root)?.keys)
        assertTrue(adapter.tokenAliasLockAuthorized(projection, data))
    }

    // ---- Canonical DISPLAY domain (9-key) stays pinyin-declared ----

    @Test
    fun canonicalPinyinDomainAllowingEvidencedExtras() {
        assertTrue(adapter.isCanonicalPinyinDomainAllowingBenignExtras(listOf("ni", "shang", "A", "Z")))
        assertFalse(adapter.isCanonicalPinyinDomainAllowingBenignExtras(listOf("A", "Z")))
        assertFalse(adapter.isCanonicalPinyinDomainAllowingBenignExtras(listOf("ni", "english")))
    }

    @Test
    fun realFlypy26TokensAreCanonicalPinyinDomain() {
        val projection = loadFixture("songyan_flypy.projection")
        assertTrue(adapter.isCanonicalPinyinDomain(projection.syllables.map { it.token }))
    }
}

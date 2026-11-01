package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Primary-producer contract (eligibility equals dispatch equals generator equals native), the
 * shared physical-fold contract, and the 18-key layout reusing the letter-group adapter. Ported
 * from the iOS `KeyboardAdapterProducerContractTests` and `EighteenKeyAdapterTests`.
 */
class KeyboardAdapterProducerContractTest {
    private val adapter = CompactSchemaAdapter
    private val builtin = File("../rime/src/main/assets/rime/builtin")
    private val latin = "abcdefghijklmnopqrstuvwxyz"

    private fun syllable(
        token: String,
        spelling: String = token,
    ) = CompactSchemaAdapter.ProjectedSyllable(
        token,
        listOf(CompactSchemaAdapter.ProjectedSpelling(spelling, true, false)),
    )

    private fun projection(
        schema: String,
        namespace: String = "translator",
        alphabet: String = latin,
        producerClass: String = "",
        syllables: List<CompactSchemaAdapter.ProjectedSyllable>,
    ) = CompactSchemaAdapter.SourceProjection(
        sourceSchema = schema,
        translatorNamespace = namespace,
        dictionary = "d",
        alphabet = alphabet,
        algebra = emptyList(),
        separator = "'",
        producerClass = producerClass,
        syllables = syllables,
    )

    private fun yaml(data: ByteArray): Map<String, Any?> = requireNotNull(YamsYaml.loadMap(String(data)))

    private fun tableSource(
        schemaID: String,
        alphabet: String,
    ) = "schema: {schema_id: $schemaID, name: $schemaID}\nengine: {translators: [table_translator]}\n" +
        "speller: {alphabet: \"$alphabet\"}\ntranslator: {dictionary: d}\n"

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is E) return
            throw error
        }
        fail("expected " + E::class.simpleName)
    }

    // ---- Primary-producer contract ----

    @Test
    fun primaryProducerMirrorsNativeSingleProducer() {
        val withAux =
            adapter.primaryProducer(
                listOf(
                    "punct_translator",
                    "script_translator",
                    "table_translator@custom_phrase",
                    "lua_translator@*date_translator",
                ),
            )
        assertEquals(CompactSchemaAdapter.ProducerClass.SCRIPT, withAux?.klass)
        assertEquals("translator", withAux?.namespace)
        assertEquals(true, withAux?.isDefaultNamespace)
        val tableDefault =
            adapter.primaryProducer(
                listOf(
                    "punct_translator",
                    "table_translator",
                    "table_translator@custom_phrase",
                    "reverse_lookup_translator",
                ),
            )
        assertEquals(CompactSchemaAdapter.ProducerClass.TABLE, tableDefault?.klass)
        assertNull(adapter.primaryProducer(listOf("script_translator", "table_translator")))
        assertNull(adapter.primaryProducer(listOf("script_translator", "script_translator@alt", "table_translator")))
        val namedOnly = adapter.primaryProducer(listOf("table_translator@custom_phrase"))
        assertEquals("custom_phrase", namedOnly?.namespace)
        assertEquals(false, namedOnly?.isDefaultNamespace)
        assertNull(adapter.primaryProducer(listOf("table_translator@a", "table_translator@b")))
        assertNull(adapter.primaryProducer(listOf("punct_translator")))
    }

    @Test
    fun defaultTableWithAuxiliariesIsAdaptableAndPreserved() {
        val source =
            """
            schema: {schema_id: tbl_aux, name: tbl_aux}
            engine:
              translators: [punct_translator, reverse_lookup_translator, table_translator, table_translator@custom_phrase, lua_translator@*calc]
              segmentors: [abc_segmentor, matcher, fallback_segmentor]
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: d}
            custom_phrase: {dictionary: "", user_dict: custom_phrase, db_class: stabledb}
            reverse_lookup: {dictionary: pinyin_simp, prefix: z}
            """.trimIndent().toByteArray()
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(source, "tbl_aux"))
        val projection =
            projection(
                "tbl_aux",
                producerClass = "table_translator",
                syllables = listOf(syllable("kw"), syllable("kk")),
            )
        val root = yaml(adapter.generate(source, "tbl_aux", CompactFold.MERGED14, null, projection))
        val engine = requireNotNull(root["engine"].asStringMap())
        assertEquals(
            listOf(
                "punct_translator",
                "reverse_lookup_translator",
                "table_translator",
                "table_translator@custom_phrase",
                "lua_translator@*calc",
            ),
            engine["translators"],
        )
        assertEquals(listOf("abc_segmentor", "matcher", "fallback_segmentor"), engine["segmentors"])
        assertNotNull("auxiliary config section preserved", root["custom_phrase"])
        assertNotNull("reverse_lookup section preserved", root["reverse_lookup"])
        assertEquals(
            adapter.identifier("tbl_aux", CompactFold.MERGED14),
            root["translator"].asStringMap()?.get("prism"),
        )
        val block = requireNotNull(root["keyboard_rewrite"].asStringMap())
        assertEquals(adapter.DIRECT_TOKEN_LOCK_VALUE, block["lock"])
        assertNull(block["lock_map"])
    }

    @Test
    fun defaultScriptWithNamedTableAuxiliaryGoesScriptNotMixed() {
        val source =
            """
            schema: {schema_id: scr_aux, name: scr_aux}
            engine:
              translators: [punct_translator, script_translator, table_translator@custom_phrase]
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: d}
            custom_phrase: {dictionary: "", user_dict: custom_phrase, db_class: stabledb}
            """.trimIndent().toByteArray()
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(source, "scr_aux"))
        val projection = projection("scr_aux", producerClass = "script_translator", syllables = listOf(syllable("ni")))
        val root = yaml(adapter.generate(source, "scr_aux", CompactFold.MERGED14, null, projection))
        assertEquals(
            listOf("punct_translator", "script_translator", "table_translator@custom_phrase"),
            root["engine"].asStringMap()?.get("translators"),
        )
        val block = requireNotNull(root["keyboard_rewrite"].asStringMap())
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, block["lock"])
        assertNotNull(block["lock_map"])
    }

    @Test
    fun rimeStyleCaseAliasedAlphabetIsAdaptableAndGenerates() {
        val alphabet = "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA`"
        val source =
            """
            schema: {schema_id: case_alias, name: Case Alias}
            engine:
              translators: [punct_translator, script_translator, table_translator@custom_phrase]
            speller: {alphabet: "$alphabet"}
            translator: {dictionary: d}
            custom_phrase: {dictionary: "", user_dict: custom_phrase, db_class: stabledb}
            """.trimIndent().toByteArray()
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(source, "case_alias"))
        val projection =
            projection(
                "case_alias",
                alphabet = alphabet,
                producerClass = "script_translator",
                syllables = listOf(syllable("ni")),
            )
        adapter.generate(source, "case_alias", CompactFold.MERGED14, null, projection)
        adapter.generate(source, "case_alias", CompactFold.PHONE9, null, projection)
    }

    @Test
    fun twoDefaultProducersFailClosedBeforeGeneration() {
        val source =
            """
            schema: {schema_id: mixed, name: mixed}
            engine: {translators: [script_translator, table_translator]}
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            translator: {dictionary: d}
            """.trimIndent().toByteArray()
        assertTrue(adapter.adaptability(source, "mixed") is CompactSchemaAdapter.Adaptability.Unsupported)
        val projection = projection("mixed", producerClass = "script_translator", syllables = listOf(syllable("ni")))
        assertThrows<RecipeInstallError.InvalidResource> {
            adapter.generate(source, "mixed", CompactFold.MERGED14, null, projection)
        }
    }

    @Test
    fun namedOnlyProducerIsExplicitlyUnsupportedNotAdaptable() {
        val source =
            """
            schema: {schema_id: named_only, name: named_only}
            engine: {translators: [punct_translator, table_translator@custom_phrase]}
            speller: {alphabet: "abcdefghijklmnopqrstuvwxyz"}
            custom_phrase: {dictionary: "", user_dict: custom_phrase, db_class: stabledb}
            """.trimIndent().toByteArray()
        val result = adapter.adaptability(source, "named_only")
        assertTrue(result is CompactSchemaAdapter.Adaptability.Unsupported)
        assertTrue((result as CompactSchemaAdapter.Adaptability.Unsupported).reason.contains("default-namespace"))
        val projection =
            projection(
                "named_only",
                namespace = "custom_phrase",
                producerClass = "table_translator",
                syllables = listOf(syllable("kw")),
            )
        assertThrows<RecipeInstallError.InvalidResource> {
            adapter.generate(source, "named_only", CompactFold.MERGED14, null, projection)
        }
    }

    @Test
    fun physicalFoldIsTheSingleSharedContract() {
        val full = requireNotNull(adapter.physicalFold("zyxwvutsrqponmlkjihgfedcba", CompactFold.MERGED14))
        assertEquals(24, full.rules.size)
        assertEquals(full.rules.take(12), full.exactTokenRules)
        assertTrue(full.exactTokenRules.contains("derive/d/>/"))
        assertEquals("xlit/<~|>#@^+!%*_/abcdegjoqtuz/", full.exactTokenPreedit)
        assertTrue(full.typeableAlphabet.contains(">"))
        assertTrue(full.rules.contains("derive/w/q/"))
        assertTrue(full.rules.contains("derive/s/a/"))
        assertFalse("a fixpoint rule is never emitted", full.rules.contains("derive/q/q/"))
        assertTrue(full.typeableAlphabet.contains("q"))
        val noZ = requireNotNull(adapter.physicalFold("abcdefghijklmnopqrstuvwxy", CompactFold.MERGED14))
        assertFalse("must not invent a key the source does not have", noZ.rules.any { it.startsWith("derive/z/") })
        val sparse = requireNotNull(adapter.physicalFold("abc", CompactFold.MERGED14))
        assertTrue("no partner letter, no exact token", sparse.rules.isEmpty())
        assertTrue(sparse.typeableAlphabet.contains("b"))
        assertNull(adapter.physicalFold("", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("abc中", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("ab c", CompactFold.MERGED14))

        val rimeAlphabet = "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA`"
        val caseAliases = requireNotNull(adapter.physicalFold(rimeAlphabet, CompactFold.MERGED14))
        assertEquals(50, caseAliases.rules.size)
        assertTrue(caseAliases.rules.contains("derive/W/q/"))
        assertTrue("uppercase fixpoints still need a compact-key derive", caseAliases.rules.contains("derive/Q/q/"))
        assertTrue(caseAliases.typeableAlphabet.contains("W"))
        assertTrue(caseAliases.typeableAlphabet.contains("`"))
        assertNull(adapter.physicalFold("abcZ", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("ABC", CompactFold.MERGED14))
    }

    /** A source missing letters must be judged adaptable AND generate without throwing. */
    @Test
    fun adaptabilityAndGenerateAgreeOnSparseAlphabets() {
        for (alphabet in listOf(
            "abcdefghijklmnopqrstuvwxy",
            "abc",
            "zyxwvutsrqponmlkjihgfedcba",
            "zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA`",
        )) {
            val source = tableSource("sparse_test", alphabet).toByteArray()
            assertEquals(
                alphabet,
                CompactSchemaAdapter.Adaptability.Adaptable,
                adapter.adaptability(source, "sparse_test"),
            )
            val projection =
                projection(
                    "sparse_test",
                    alphabet = alphabet,
                    producerClass = "table_translator",
                    syllables = listOf(syllable("abc")),
                )
            adapter.generate(source, "sparse_test", CompactFold.MERGED14, null, projection)
        }
        val bad = tableSource("bad_test", "abc中").toByteArray()
        assertEquals(
            CompactSchemaAdapter.Adaptability.Unsupported(
                "speller alphabet is not a foldable latin/numeric input alphabet",
            ),
            adapter.adaptability(bad, "bad_test"),
        )
        // A numeric key space is typeable on the 9-key layout only, so only that companion is generated.
        val numericAlphabet = "23456789abcdefghijklmnopqrstuvwxyz"
        val numeric = tableSource("numeric_test", numericAlphabet).toByteArray()
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(numeric, "numeric_test"))
        assertNull(adapter.physicalFold(numericAlphabet, CompactFold.MERGED14))
        assertNotNull(adapter.physicalFold(numericAlphabet, CompactFold.PHONE9))
    }

    @Test
    fun tableUppercaseTokenGetsAReachableCompactDeriveAndMatchingSample() {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val source =
            """
            schema: {schema_id: uppercase_table, name: uppercase_table}
            engine: {translators: [table_translator]}
            speller: {alphabet: "$alphabet"}
            translator: {dictionary: d}
            recognizer: {import_preset: default}
            """.trimIndent().toByteArray()
        val projection =
            projection(
                "uppercase_table",
                alphabet = alphabet,
                producerClass = "table_translator",
                syllables = listOf(syllable("W")),
            )
        assertEquals(listOf("W"), adapter.directTokenSample(projection, CompactFold.MERGED14))
        val root = yaml(adapter.generate(source, "uppercase_table", CompactFold.MERGED14, null, projection))
        val speller = requireNotNull(root["speller"].asStringMap())
        assertTrue(requireNotNull(speller["algebra"].asStringList()).contains("derive/W/q/"))
        assertTrue((speller["alphabet"] as String).contains("q"))
        assertTrue(
            "librime must accept the exact direct token during lock apply",
            (speller["alphabet"] as String).contains("W"),
        )
    }

    // ---- Numeric detection scans the complete projection, order-independent ----

    @Test
    fun isNumericCodeSourceScansWholeProjection() {
        fun proj(syllables: List<CompactSchemaAdapter.ProjectedSyllable>) =
            projection("n", producerClass = "script_translator", syllables = syllables)
        assertTrue(adapter.isNumericCodeSource(proj(listOf(syllable("ni", "64"), syllable("hao", "426")))))
        val many =
            (0 until 32).map { syllable("t$it", "4${it % 8 + 2}") } +
                CompactSchemaAdapter.ProjectedSyllable(
                    "late",
                    (0 until 8).map { CompactSchemaAdapter.ProjectedSpelling("p$it", true, false) } +
                        CompactSchemaAdapter.ProjectedSpelling("426", true, false),
                )
        assertTrue(
            "a numeric spelling past the 32nd token / 9th slot is detected",
            adapter.isNumericCodeSource(proj(many)),
        )
        assertTrue(adapter.isNumericCodeSource(proj(many.reversed())))
        assertFalse(adapter.isNumericCodeSource(proj(listOf(syllable("ni", "64"), syllable("x", "abc")))))
        assertFalse(adapter.isNumericCodeSource(proj(emptyList())))
    }

    // ---- Direct (table) mode: bounded sample and fold soundness ----

    @Test
    fun directTokenSampleIsBoundedAndUniquelyIdentifiable() {
        // `s` folds to `a`, so the code `s` collides with the code `a`; only a class-unique code is sampled.
        val projection =
            projection(
                "t",
                producerClass = "table_translator",
                syllables = listOf(syllable("a"), syllable("s"), syllable("kw")),
            )
        val sample = requireNotNull(adapter.directTokenSample(projection, CompactFold.MERGED14))
        assertTrue("a class-unique code is sampled", sample.contains("kw"))
        assertFalse("a code colliding with its fold partner is not sampled", sample.contains("a"))
        assertTrue("the unique side of a collision is sampled", sample.contains("s"))
        assertTrue(sample.size <= 16)
        assertNull(
            adapter.directTokenSample(
                projection("t", producerClass = "table_translator", syllables = emptyList()),
                CompactFold.MERGED14,
            ),
        )
    }

    @Test
    fun generateTableCompanionEmitsDirectLockWithoutLockMap() {
        val projection =
            projection("tbl", producerClass = "table_translator", syllables = listOf(syllable("kw"), syllable("kk")))
        val data =
            adapter.generate(
                tableSource("tbl", latin).toByteArray(),
                "tbl",
                CompactFold.MERGED14,
                null,
                projection,
            )
        val root = yaml(data)
        assertNull("table companions keep native display", root["keyboard_adapter"])
        val block = requireNotNull(root["keyboard_rewrite"].asStringMap())
        assertEquals(adapter.REWRITE_WIRE_VERSION_DIRECT.toLong(), (block["version"] as Number).toLong())
        assertEquals(adapter.DIRECT_TOKEN_LOCK_VALUE, block["lock"])
        assertNull("direct mode carries no per-code map", block["lock_map"])
        assertNotNull(block["direct_tokens"])
        assertTrue(requireNotNull(root["speller"].asStringMap()?.get("algebra").asStringList()).contains("derive/w/q/"))
    }

    @Test
    fun tableWithoutAUniquelyIdentifiableCodeFailsClosedWithoutCapability() {
        val projection = projection("tbl", producerClass = "table_translator", syllables = emptyList())
        val data =
            adapter.generate(
                tableSource("tbl", latin).toByteArray(),
                "tbl",
                CompactFold.MERGED14,
                null,
                projection,
            )
        assertNull("no bounded direct lock exists, so no capability is advertised", yaml(data)["keyboard_rewrite"])
        assertNotNull(yaml(data)["speller"])
    }

    // ---- 18-key layout (reuses the letter-group adapter; only the fold differs) ----

    private fun fixture(name: String): CompactSchemaAdapter.SourceProjection {
        val stream = javaClass.getResourceAsStream("/package-fixtures/$name.projection.json")
        return requireNotNull(CompactSchemaAdapter.SourceProjection.decode(requireNotNull(stream).readBytes()))
    }

    private fun generateBuiltin(
        schema: String,
        fold: CompactFold,
    ): Map<String, Any?> =
        yaml(adapter.generate(File(builtin, "$schema.schema.yaml").readBytes(), schema, fold, null, fixture(schema)))

    @Test
    fun layoutSuppliesTheFold() {
        assertEquals(CompactFold.MERGED18, KeyboardLayoutKind.MERGED18.compactFold)
        assertNull(KeyboardLayoutKind.QWERTY26.compactFold)
        assertTrue(KeyboardLayoutKind.MERGED18.requiresSpellingDisplay)
        assertEquals(
            "abfhijlmpqrsuvwxyz",
            CompactFold.MERGED18.keysSent
                .sorted()
                .joinToString(""),
        )
        assertFalse(CompactFold.MERGED18.isPhoneDigits)
        assertTrue(CompactFold.PHONE9.isPhoneDigits)
        val map = CompactFold.MERGED18.map
        for (key in CompactFold.MERGED18.keysSent) {
            assertEquals(
                "every key sends a letter that folds to itself",
                key,
                map[key],
            )
        }
    }

    @Test
    fun identifiersKeepTheirSuffixes() {
        assertTrue(adapter.identifier("s", CompactFold.MERGED14).endsWith("_14"))
        assertTrue(adapter.identifier("s", CompactFold.MERGED18).endsWith("_18"))
        assertTrue(adapter.identifier("s", CompactFold.PHONE9).endsWith("_9"))
        assertTrue(adapter.isGeneratedCompanionID(adapter.identifier("s", CompactFold.MERGED18)))
    }

    @Test
    fun quanpinSourceFoldsOntoEighteenKeys() {
        val root = generateBuiltin("songyan_quanpin", CompactFold.MERGED18)
        assertEquals(
            adapter.identifier("songyan_quanpin", CompactFold.MERGED18),
            root["schema"].asStringMap()?.get("schema_id"),
        )
        val speller = requireNotNull(root["speller"].asStringMap())
        val algebra = requireNotNull(speller["algebra"].asStringList())
        assertEquals(
            listOf(
                "derive/c/x/",
                "derive/d/s/",
                "derive/e/w/",
                "derive/g/f/",
                "derive/k/j/",
                "derive/n/b/",
                "derive/o/i/",
                "derive/t/r/",
            ),
            algebra.takeLast(8),
        )
        assertFalse(algebra.any { it.startsWith("xlit/") })
        assertTrue(CompactFold.MERGED18.keysSent.all { it in (speller["alphabet"] as String) })
        val rewrite = requireNotNull(root["keyboard_rewrite"].asStringMap())
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, rewrite["lock"])
        val lockMap = requireNotNull(rewrite["lock_map"].asStringMap())
        assertFalse(lockMap.isEmpty())
        // The lock alias layer stays out of reach of every key the layout sends.
        for (alias in lockMap.values) assertFalse(CompactFold.MERGED18.keysSent.containsAll((alias as String).toList()))
        // nihao is typed as BN IO H A IO.
        assertEquals("bihai", "nihao".map { CompactFold.MERGED18.map[it] ?: it }.joinToString(""))
    }

    @Test
    fun shuangpinSourceFoldsOntoEighteenKeys() {
        val root = generateBuiltin("songyan_flypy", CompactFold.MERGED18)
        val algebra = requireNotNull(root["speller"].asStringMap()?.get("algebra").asStringList())
        assertEquals(
            listOf(
                "derive/c/x/",
                "derive/d/s/",
                "derive/e/w/",
                "derive/g/f/",
                "derive/k/j/",
                "derive/n/b/",
                "derive/o/i/",
                "derive/t/r/",
            ),
            algebra.takeLast(8),
        )
        assertNotNull(root["keyboard_rewrite"].asStringMap()?.get("lock_map"))
        assertTrue((root["schema"].asStringMap()?.get("name") as String).endsWith("18键适配"))
    }

    /** Letter groups add one derive per folded letter, keeping exact letters spellable. */
    @Test
    fun letterGroupFoldsKeepExactLetters() {
        val root = generateBuiltin("songyan_quanpin", CompactFold.MERGED14)
        val speller = requireNotNull(root["speller"].asStringMap())
        val algebra = requireNotNull(speller["algebra"].asStringList())
        assertEquals(
            listOf(
                "derive/f/d/",
                "derive/h/g/",
                "derive/i/u/",
                "derive/k/j/",
                "derive/n/b/",
                "derive/p/o/",
                "derive/r/e/",
                "derive/s/a/",
                "derive/v/c/",
                "derive/w/q/",
                "derive/x/z/",
                "derive/y/t/",
            ),
            algebra.takeLast(12),
        )
        assertEquals(26, (speller["alphabet"] as String).toSet().intersect(latin.toSet()).size)
        for (rule in algebra.takeLast(12)) assertTrue(rule.split("/")[2][0] in CompactFold.MERGED14.keysSent)
    }
}

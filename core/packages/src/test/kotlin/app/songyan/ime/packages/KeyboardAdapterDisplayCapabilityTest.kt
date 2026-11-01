package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Generation-time capability: forward projection of the source algebra to a unique normal spelling
 * mapping. A missing, ambiguous or non-letter entry is rejected and recorded, never inverted,
 * never defaulted to identity, never first-picked. Ported from the iOS
 * `KeyboardAdapterDisplayCapabilityTests`.
 */
class KeyboardAdapterDisplayCapabilityTest {
    private val adapter = CompactSchemaAdapter

    private fun syllable(
        token: String,
        vararg spellings: Triple<String, Boolean, Boolean>,
    ) = CompactSchemaAdapter.ProjectedSyllable(
        token,
        spellings.map { CompactSchemaAdapter.ProjectedSpelling(it.first, it.second, it.third) },
    )

    private fun normal(text: String) = Triple(text, true, false)

    private fun projection(
        schema: String = "songyan_flypy",
        dictionary: String = "songyan_basic",
        alphabet: String = "zyxwvutsrqponmlkjihgfedcba",
        algebra: List<String> = listOf("xform/(.)ao$/\$1C/"),
        separator: String = "'",
        declaration: String = "",
        prefixDeclaration: String = "",
        syllables: List<CompactSchemaAdapter.ProjectedSyllable>,
    ) = CompactSchemaAdapter.SourceProjection(
        sourceSchema = schema,
        translatorNamespace = "translator",
        dictionary = dictionary,
        alphabet = alphabet,
        algebra = algebra,
        separator = separator,
        declaration = declaration,
        prefixDeclaration = prefixDeclaration,
        syllables = syllables,
    )

    private fun yaml(data: ByteArray): Map<String, Any?> = requireNotNull(YamsYaml.loadMap(String(data)))

    private fun rewriteBlock(root: Map<String, Any?>) = root["keyboard_rewrite"].asStringMap()

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (error: Throwable) {
            if (error is E) return error
            throw error
        }
        fail("expected " + E::class.simpleName)
        throw IllegalStateException()
    }

    // ---- Acceptance: Xiaohe zao/shang/hao -> zc/uh/hc ----

    @Test
    fun flypyAcceptanceMapIsSourceCodingNotIdentityOrMerged() {
        val source =
            projection(
                syllables =
                    listOf(
                        syllable("zao", normal("zc")),
                        syllable("shang", normal("uh")),
                        syllable("hao", normal("hc")),
                    ),
            )
        val build = adapter.makeDisplayCapability(source, displaySeparator = "'")
        assertEquals("source_code", build.capability.mode)
        assertEquals("unique_normal", build.capability.mappingPolicy)
        assertEquals("'", build.capability.separator)
        assertEquals(mapOf("zao" to "zc", "shang" to "uh", "hao" to "hc"), build.capability.syllables)
        assertTrue(build.rejections.isEmpty())
    }

    @Test
    fun displaySeparatorOverrideYieldsApostropheAcceptance() {
        val spaced = projection(separator = " ", syllables = listOf(syllable("zao", normal("zc"))))
        assertEquals(" ", adapter.makeDisplayCapability(spaced).capability.separator)
        assertEquals("'", adapter.makeDisplayCapability(spaced, displaySeparator = "'").capability.separator)
    }

    @Test
    fun emptyDisplaySeparatorRejected() {
        assertThrows<CompactSchemaAdapter.DisplayCapabilityError.EmptySeparator> {
            adapter.makeDisplayCapability(projection(separator = "", syllables = listOf(syllable("zao", normal("zc")))))
        }
    }

    @Test
    fun fullPinyinIdentityIsRecordedExplicitlyNotImplied() {
        val source =
            projection(
                schema = "songyan_quanpin",
                algebra = emptyList(),
                separator = " ",
                syllables =
                    listOf(
                        syllable("zao", normal("zao")),
                        syllable("shang", normal("shang")),
                        syllable("hao", normal("hao")),
                    ),
            )
        val build = adapter.makeDisplayCapability(source)
        assertEquals(mapOf("zao" to "zao", "shang" to "shang", "hao" to "hao"), build.capability.syllables)
        assertEquals(
            listOf("hao", "shang", "zao"),
            build.capability.syllables.keys
                .sorted(),
        )
    }

    // ---- Ambiguity / missing / erase: reject the entry ----

    @Test
    fun ambiguousNormalJuJvIsRejectedAndNotFirstPicked() {
        val source =
            projection(syllables = listOf(syllable("ju", normal("ju"), normal("jv")), syllable("zao", normal("zc"))))
        val build = adapter.makeDisplayCapability(source)
        assertNull(build.capability.syllables["ju"])
        assertEquals("ambiguous_normal", build.capability.rejectedTokens["ju"])
        assertEquals("zc", build.capability.syllables["zao"])
        assertEquals("ju,jv", build.rejections.first().detail)
        val error =
            assertThrows<CompactSchemaAdapter.DisplayCapabilityError.IncompleteDomain> {
                adapter.makeDisplayCapability(source, requireFullDomain = true)
            }
        assertEquals(listOf("ju"), error.tokens)
    }

    @Test
    fun erasedAndMissingTokensAreRejected() {
        val build =
            adapter.makeDisplayCapability(
                projection(syllables = listOf(syllable("zao", normal("zc")), syllable("xx"))),
            )
        assertNull(build.capability.syllables["xx"])
        assertEquals("missing_normal", build.capability.rejectedTokens["xx"])
    }

    @Test
    fun fuzzyAbbrevAndCorrectionAreNotUsedAsNormal() {
        val source =
            projection(
                syllables =
                    listOf(
                        syllable(
                            "zao",
                            normal("zc"),
                            Triple("zc", false, false),
                            Triple("z", false, false),
                            Triple("zc", true, true),
                        ),
                        syllable("shang", Triple("sh", false, false)),
                    ),
            )
        val build = adapter.makeDisplayCapability(source)
        assertEquals("zc", build.capability.syllables["zao"])
        assertNull(build.capability.syllables["shang"])
        assertEquals("missing_normal", build.capability.rejectedTokens["shang"])
    }

    @Test
    fun nonAlphabeticSpellingRejectedForSourceRendered() {
        val build =
            adapter.makeDisplayCapability(
                projection(syllables = listOf(syllable("ni", normal("ㄋㄧ")), syllable("hao", normal("hc")))),
            )
        assertNull(build.capability.syllables["ni"])
        assertEquals("non_alphabetic", build.capability.rejectedTokens["ni"])
        assertEquals("hc", build.capability.syllables["hao"])
    }

    @Test
    fun emptyDomainDuplicateAndAllRejected() {
        assertThrows<CompactSchemaAdapter.DisplayCapabilityError.EmptyTokenDomain> {
            adapter.makeDisplayCapability(projection(syllables = emptyList()))
        }
        val duplicated = projection(syllables = listOf(syllable("zao", normal("zc")), syllable("zao", normal("zc2"))))
        val error =
            assertThrows<CompactSchemaAdapter.DisplayCapabilityError.DuplicateToken> {
                adapter.makeDisplayCapability(
                    duplicated,
                )
            }
        assertEquals("zao", error.token)
        val allRejected = projection(syllables = listOf(syllable("ju", normal("ju"), normal("jv")), syllable("xx")))
        assertThrows<CompactSchemaAdapter.DisplayCapabilityError.EmptyResolvedDomain> {
            adapter.makeDisplayCapability(allRejected)
        }
    }

    // ---- Canonical authorization rests on the whole token domain ----

    @Test
    fun canonicalPinyinDomainValidation() {
        assertTrue(
            adapter.isCanonicalPinyinDomain(
                listOf("ni", "hao", "zhong", "guo", "er", "yu", "lve", "nv", "ju", "que", "xue"),
            ),
        )
        assertTrue(
            adapter.isCanonicalPinyinDomain(listOf("yi", "wu", "yue", "yuan", "yun", "a", "o", "e", "ang", "eng")),
        )
        assertFalse(adapter.isCanonicalPinyinDomain(listOf("xx")))
        assertFalse(adapter.isCanonicalPinyinDomain(listOf("ni", "xx")))
        assertFalse(adapter.isCanonicalPinyinDomain(listOf("wq", "abcd")))
        assertFalse(adapter.isCanonicalPinyinDomain(emptyList()))
        assertFalse(adapter.isCanonicalPinyinDomain(listOf("Nihao")))
    }

    // ---- Wire shape, native digest adoption, nil generation path ----

    @Test
    fun wireDictionaryCarriesRuntimeKeysIncludingAdapterSchema() {
        val build =
            adapter.makeDisplayCapability(
                projection(
                    syllables = listOf(syllable("zao", normal("zc")), syllable("ju", normal("ju"), normal("jv"))),
                ),
            )
        val wire = build.capability.settingAdapterSchema("keyboard_abc_14").wireDictionary()
        assertEquals(
            setOf(
                "version",
                "display_mode",
                "source_schema",
                "translator_namespace",
                "separator",
                "mapping_policy",
                "adapter_schema",
                "syllables",
                "rejected_tokens",
            ),
            wire.keys,
        )
        assertEquals("source_code", wire["display_mode"])
        assertEquals("keyboard_abc_14", wire["adapter_schema"])
        assertEquals(mapOf("zao" to "zc"), wire["syllables"])
        assertEquals(mapOf("ju" to "ambiguous_normal"), wire["rejected_tokens"])
    }

    @Test
    fun decodeNativeProjectionJSON() {
        val json =
            """
            {"source_schema":"songyan_flypy","translator_namespace":"translator","dictionary":"songyan_basic",
             "alphabet":"zyxwvutsrqponmlkjihgfedcba","algebra":["xform/(.)ao${'$'}/${'$'}1C/"],"separator":" ",
             "dictionary_checksum":1799550295,"fingerprint":"deadbeef",
             "syllables":{"zao":[{"text":"zc","normal":true,"correction":false}],
                          "ju":[{"text":"ju","normal":true,"correction":false},{"text":"jv","normal":true,"correction":false}]}}
            """.trimIndent()
        val decoded = requireNotNull(CompactSchemaAdapter.SourceProjection.decode(json.toByteArray()))
        assertEquals("songyan_flypy", decoded.sourceSchema)
        val build = adapter.makeDisplayCapability(decoded)
        assertEquals(mapOf("zao" to "zc"), build.capability.syllables)
        assertEquals(mapOf("ju" to "ambiguous_normal"), build.capability.rejectedTokens)
    }

    @Test
    fun decodeRejectsMissingRequiredFieldsAndMalformedSyllables() {
        assertNull(CompactSchemaAdapter.SourceProjection.decode("{}".toByteArray()))
        assertNull(CompactSchemaAdapter.SourceProjection.decode("not json".toByteArray()))
        val head = """{"source_schema":"s","translator_namespace":"translator","dictionary":"d","""
        val noText = head + """"syllables":{"a":[{"normal":true}]}}"""
        assertNull(CompactSchemaAdapter.SourceProjection.decode(noText.toByteArray()))
        val numeric = head + """"syllables":{"a":[{"text":"a","normal":1,"correction":0}]}}"""
        val decoded = requireNotNull(CompactSchemaAdapter.SourceProjection.decode(numeric.toByteArray()))
        assertTrue(decoded.syllables[0].spellings[0].isNormal)
        assertFalse(decoded.syllables[0].spellings[0].isCorrection)
    }

    @Test
    fun nilGenerationStripsInheritedCapabilityBlock() {
        val compiled =
            """
            schema:
              schema_id: flypy_test
              name: Flypy Test
            engine:
              translators:
                - script_translator
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
              algebra: []
            translator:
              dictionary: songyan_basic
            keyboard_adapter:
              version: 1
              display_mode: canonical
              source_schema: flypy_test
              translator_namespace: translator
              separator: "'"
              adapter_schema: flypy_test
            """.trimIndent().toByteArray()
        val without = adapter.generate(compiled, "flypy_test", CompactFold.MERGED14, displayCapability = null)
        assertNull("the nil path must explicitly remove the inherited block", yaml(without)["keyboard_adapter"])

        val build =
            adapter.makeDisplayCapability(
                projection(schema = "flypy_test", syllables = listOf(syllable("zao", normal("zc")))),
            )
        val with = adapter.generate(compiled, "flypy_test", CompactFold.MERGED14, build.capability)
        val block = requireNotNull(yaml(with)["keyboard_adapter"].asStringMap())
        assertEquals("source_code", block["display_mode"])
        assertEquals("flypy_test", block["source_schema"])
        assertEquals(adapter.identifier("flypy_test", CompactFold.MERGED14), block["adapter_schema"])
        assertEquals(mapOf("zao" to "zc"), block["syllables"])
    }

    @Test
    fun canonicalWireBindsTarget() {
        val canonical = adapter.canonicalDisplayCapability("keyboard_47246213a538cb99d374_9", "'")
        assertEquals("canonical", canonical.mode)
        assertEquals("generate is responsible for binding the target", "", canonical.adapterSchema)
    }

    @Test
    fun canonicalPinyinSourceDeclaration() {
        val declared =
            "schema: { schema_id: t, name: T }\nengine: { translators: [script_translator] }\nkeyboard_adapter_source: canonical_pinyin\n"
        val undeclared = "schema: { schema_id: t, name: T }\nengine: { translators: [script_translator] }\n"
        assertTrue(adapter.declaresCanonicalPinyinSource(declared.toByteArray()))
        assertFalse(adapter.declaresCanonicalPinyinSource(undeclared.toByteArray()))
        assertTrue(
            adapter.declaresCanonicalPinyinSource(
                projection(declaration = "canonical_pinyin", syllables = listOf(syllable("ni", normal("ni")))),
            ),
        )
        assertFalse(adapter.declaresCanonicalPinyinSource(projection(syllables = listOf(syllable("ni", normal("ni"))))))
    }

    @Test
    fun injectingCapabilityStripsInheritedBlock() {
        val schema =
            """
            schema: { schema_id: keyboard_3d7821f984693864ae91_14, name: F }
            keyboard_adapter:
              version: 1
              display_mode: source_code
              source_schema: songyan_flypy
              translator_namespace: translator
              separator: "'"
              mapping_policy: unique_normal
              adapter_schema: keyboard_3d7821f984693864ae91_14
              syllables: { zao: zc }
            """.trimIndent().toByteArray()
        val stripped = adapter.injectingCapability(schema, "keyboard_3d7821f984693864ae91_14", displayCapability = null)
        assertNull(yaml(stripped)["keyboard_adapter"])
    }

    @Test
    fun tableTranslatorSourceWithoutProjectionGeneratesNoAdapterSoItStaysNative() {
        val wubi =
            """
            schema:
              schema_id: wubi_test
              name: Wubi Test
            engine:
              translators:
                - table_translator
            speller:
              alphabet: zyxwvutsrqponmlkjihgfedcba
            translator:
              dictionary: wubi_test
            """.trimIndent().toByteArray()
        assertThrows<RecipeInstallError.InvalidResource> {
            adapter.generate(wubi, "wubi_test", CompactFold.MERGED14, displayCapability = null)
        }
    }

    // ---- 9-key / 14-key independent rewrite capability ----

    private val canonicalT9Source =
        """
        schema:
          schema_id: t9_test
          name: T9 Test
        engine:
          translators:
            - script_translator
        speller:
          alphabet: zyxwvutsrqponmlkjihgfedcba
          algebra:
            - derive/[abc]/2/
            - derive/[def]/3/
            - derive/[hgi]/4/
            - derive/[jkl]/5/
            - derive/[omn]/6/
            - derive/[pqrs]/7/
            - derive/[tuv]/8/
            - derive/[wxyz]/9/
        translator:
          dictionary: songyan_basic
        keyboard_adapter_source: canonical_pinyin
        """.trimIndent().toByteArray()

    private fun t9Projection(
        algebra: List<String> = emptyList(),
        syllables: List<CompactSchemaAdapter.ProjectedSyllable>,
    ) = projection(
        schema = "t9_test",
        alphabet = "abcdefghijklmnopqrstuvwxyz",
        algebra = algebra,
        declaration = "canonical_pinyin",
        syllables = syllables,
    )

    private val identityProjection get() =
        t9Projection(
            syllables = listOf(syllable("ni", normal("ni")), syllable("shang", normal("shang"))),
        )
    private val destructiveProjection
        get() =
            t9Projection(
                algebra = listOf("xform/^sh/U/"),
                syllables = listOf(syllable("shang", normal("uh")), syllable("ni", normal("ni"))),
            )

    private fun displayCapability() =
        CompactSchemaAdapter.DisplayCapability(
            version = 1,
            mode = "source_code",
            sourceSchema = "t9_test",
            translatorNamespace = "translator",
            separator = "'",
            mappingPolicy = "unique_normal",
            syllables = emptyMap(),
        )

    @Test
    fun injectingCapabilityWritesIndependentRewriteBlock() {
        val schema =
            """
            schema: { schema_id: keyboard_3d7821f984693864ae91_14, name: F }
            keyboard_adapter:
              version: 1
              display_mode: source_code
              source_schema: songyan_flypy
              translator_namespace: translator
              separator: "'"
              mapping_policy: unique_normal
              adapter_schema: keyboard_3d7821f984693864ae91_14
              syllables: { zao: zc }
            keyboard_rewrite:
              version: 2
              lock: bounded_token_alias_rewrite_v2
              adapter_schema: stale_adapter
            """.trimIndent().toByteArray()
        val target = "keyboard_3d7821f984693864ae91_14"
        val stripped =
            yaml(adapter.injectingCapability(schema, target, displayCapability = null, rewriteCapability = null))
        assertNull(stripped["keyboard_adapter"])
        assertNull(stripped["keyboard_rewrite"])

        val rewrite =
            CompactSchemaAdapter.RewriteCapability(
                version = adapter.REWRITE_WIRE_VERSION,
                lock = adapter.TOKEN_ALIAS_LOCK_VALUE,
                sourceSchema = "songyan_flypy",
                translatorNamespace = "translator",
                adapterSchema = target,
                lockMap = mapOf("shang" to "9084"),
            )
        val root =
            yaml(adapter.injectingCapability(schema, target, displayCapability = null, rewriteCapability = rewrite))
        assertNull("native display writes no display block", root["keyboard_adapter"])
        val block = requireNotNull(rewriteBlock(root))
        assertEquals(adapter.REWRITE_WIRE_VERSION.toLong(), (block["version"] as Number).toLong())
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, block["lock"])
        assertEquals(target, block["adapter_schema"])
        assertEquals("songyan_flypy", block["source_schema"])
        assertEquals("translator", block["translator_namespace"])
        assertEquals(mapOf("shang" to "9084"), block["lock_map"])
    }

    @Test
    fun generateCarriesIndependentRewriteForBothLayouts() {
        for (nine in listOf(true, false)) {
            val fold = if (nine) CompactFold.PHONE9 else CompactFold.MERGED14
            val root = yaml(adapter.generate(canonicalT9Source, "t9_test", fold, null, identityProjection))
            assertNull("nineKey=$nine", root["keyboard_adapter"])
            val block = requireNotNull(rewriteBlock(root)) { "rewrite must be carried even with native display" }
            assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, block["lock"])
            assertEquals("t9_test", block["source_schema"])
            assertEquals("translator", block["translator_namespace"])
            assertEquals(setOf("ni", "shang"), block["lock_map"].asStringMap()?.keys)
            assertEquals(adapter.REWRITE_WIRE_VERSION.toLong(), (block["version"] as Number).toLong())
        }
    }

    @Test
    fun rewriteLockIsTokenAliasForBothFolds() {
        for (nine in listOf(true, false)) {
            val fold = if (nine) CompactFold.PHONE9 else CompactFold.MERGED14
            val root =
                yaml(adapter.generate(canonicalT9Source, "t9_test", fold, displayCapability(), identityProjection))
            assertEquals("nineKey=$nine", adapter.TOKEN_ALIAS_LOCK_VALUE, rewriteBlock(root)?.get("lock"))
        }
    }

    @Test
    fun destructiveSourceStillGetsRewriteLock() {
        val root = yaml(adapter.generate(canonicalT9Source, "t9_test", CompactFold.PHONE9, null, destructiveProjection))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, rewriteBlock(root)?.get("lock"))
    }

    @Test
    fun noProjectionGetsNoRewriteBlock() {
        val root = yaml(adapter.generate(canonicalT9Source, "t9_test", CompactFold.PHONE9, null, null))
        assertNull(root["keyboard_rewrite"])
        assertNull(root["keyboard_adapter"])
    }

    // ---- Exact domain / exact mapping binding ----

    private fun companion(
        source: CompactSchemaAdapter.SourceProjection,
        nine: Boolean = false,
    ): ByteArray =
        adapter.generate(
            canonicalT9Source,
            "t9_test",
            if (nine) CompactFold.PHONE9 else CompactFold.MERGED14,
            null,
            source,
        )

    @Test
    fun tokenAliasLockAuthorizedRequiresExactDomainAndMap() {
        val source = identityProjection
        for (nine in listOf(false, true)) {
            val data = companion(source, nine)
            assertTrue("nine=$nine", adapter.tokenAliasLockAuthorized(source, data))
            val superset =
                t9Projection(
                    syllables =
                        listOf(
                            syllable("ni", normal("ni")),
                            syllable("shang", normal("shang")),
                            syllable("hao", normal("hao")),
                        ),
                )
            assertFalse("live superset must fail closed (nine=$nine)", adapter.tokenAliasLockAuthorized(superset, data))
            val subset = t9Projection(syllables = listOf(syllable("ni", normal("ni"))))
            assertFalse("live subset must fail closed (nine=$nine)", adapter.tokenAliasLockAuthorized(subset, data))
        }
    }

    @Test
    fun tokenAliasLockAuthorizedRejectsWrongAliasAndWrongDomain() {
        val source = identityProjection
        val expected = requireNotNull(adapter.tokenAliasAssignmentMap(source))
        val alias = requireNotNull(expected["shang"])
        for (nine in listOf(false, true)) {
            val good = companion(source, nine)
            val corrupted = String(good).replace("derive/^shang$/$alias/", "derive/^shang$/9zzz/")
            assertFalse(
                "a wrong alias must fail closed (nine=$nine)",
                adapter.tokenAliasLockAuthorized(source, corrupted.toByteArray()),
            )
            val other = t9Projection(syllables = listOf(syllable("hao", normal("hao")), syllable("zao", normal("zao"))))
            assertFalse("nine=$nine", adapter.tokenAliasLockAuthorized(source, companion(other, nine)))
        }
    }

    @Test
    fun generatorVersionAndWireConstants() {
        assertTrue(adapter.DISPLAY_GENERATOR_VERSION >= 7)
        assertEquals(4096, adapter.TOKEN_ALIAS_LIMIT)
        assertEquals("90", adapter.TOKEN_ALIAS_SENTINEL)
        assertEquals(3, adapter.REWRITE_WIRE_VERSION_DIRECT)
        assertEquals("direct_token_rewrite_v3", adapter.DIRECT_TOKEN_LOCK_VALUE)
        assertEquals(4, adapter.REWRITE_WIRE_VERSION_PREFIX)
        assertEquals("prefix_token_rewrite_v4", adapter.PREFIX_TOKEN_LOCK_VALUE)
        assertEquals("letter_class_v1", adapter.LETTER_CLASS_PREFIX_LAYER_VALUE)
        assertEquals("abbrev/^([a-z]).+$/\$1/", adapter.LETTER_CLASS_PREFIX_RULE)
        assertEquals(26, adapter.LETTER_CLASS_PREFIX_KEY_LIMIT)
        val keyboardOrder = "qwertyuiopasdfghjklzxcvbnm"
        assertEquals("qqeettuuooaaddggjjlzzccbbm", keyboardOrder.map { CompactFold.MERGED14.map[it] }.joinToString(""))
        assertEquals("qwwrryuiipassffhjjlzxxvbbm", keyboardOrder.map { CompactFold.MERGED18.map[it] }.joinToString(""))
        assertEquals(
            "22233344455566677778889999",
            "abcdefghijklmnopqrstuvwxyz".map { CompactFold.PHONE9.map[it] }.joinToString(""),
        )
    }

    // ---- Letter-class prefix layer (v4) ----

    private val optedNumericProjection
        get() =
            projection(
                schema = "keyboard_47246213a538cb99d374_9",
                alphabet = "23456789abcdefghijklmnopqrstuvwxyz",
                algebra = emptyList(),
                prefixDeclaration = "letter_class_v1",
                syllables =
                    listOf(
                        syllable("a", normal("a"), normal("2")),
                        syllable("ba", normal("ba"), normal("22")),
                        syllable("ca", normal("ca"), normal("22")),
                        syllable("de", normal("de"), normal("33")),
                    ),
            )

    private fun numericNineKeySource(id: String) =
        """
        schema: {schema_id: $id, name: $id}
        engine:
          translators:
            - script_translator
        speller:
          alphabet: "23456789abcdefghijklmnopqrstuvwxyz"
          algebra:
            - 'derive/[abc]/2/'
            - 'derive/[def]/3/'
        translator: {dictionary: d, prism: $id, user_dict: d}
        """.trimIndent().toByteArray()

    @Test
    fun prefixKeysComeFromTheRealTokenInventoryOnly() {
        assertEquals(listOf("a", "b", "c", "d"), adapter.letterClassPrefixKeys(optedNumericProjection))
        val empty =
            projection(
                schema = "t9",
                alphabet = "23456789",
                algebra = emptyList(),
                prefixDeclaration = "letter_class_v1",
                syllables = emptyList(),
            )
        assertNull(adapter.letterClassPrefixKeys(empty))
        assertFalse(
            adapter.declaresLetterClassPrefixSource(
                projection(
                    schema = "t9",
                    alphabet = "23456789",
                    algebra = emptyList(),
                    syllables = listOf(syllable("a", normal("2"))),
                ),
            ),
        )
    }

    @Test
    fun optedNumericSourceGeneratesV4PrefixCompanion() {
        val id = "keyboard_47246213a538cb99d374_9"
        val root =
            yaml(adapter.generate(numericNineKeySource(id), id, CompactFold.PHONE9, null, optedNumericProjection))
        val block = requireNotNull(rewriteBlock(root))
        assertEquals(4L, (block["version"] as Number).toLong())
        assertEquals("prefix_token_rewrite_v4", block["lock"])
        assertEquals("letter_class_v1", block["prefix_layer"])
        assertEquals(listOf("a", "b", "c", "d"), block["prefix_keys"])
        assertNull("v4 carries no per-token alias map", block["lock_map"])
        assertNull(block["direct_tokens"])
        val speller = requireNotNull(root["speller"].asStringMap())
        val algebra = requireNotNull(speller["algebra"].asStringList())
        assertEquals("abbrev/^([a-z]).+$/\$1/", algebra.first())
        val alphabet = speller["alphabet"] as String
        assertTrue(alphabet.contains("a") && alphabet.contains("2"))
    }

    @Test
    fun unOptedSourceKeepsTheAliasLock() {
        val unOpted =
            projection(
                schema = "keyboard_47246213a538cb99d374_9",
                alphabet = "23456789abcdefghijklmnopqrstuvwxyz",
                algebra = emptyList(),
                syllables = listOf(syllable("a", normal("a"), normal("2")), syllable("ba", normal("ba"), normal("22"))),
            )
        val id = "keyboard_47246213a538cb99d374_9"
        val root = yaml(adapter.generate(numericNineKeySource(id), id, CompactFold.PHONE9, null, unOpted))
        val block = requireNotNull(rewriteBlock(root))
        assertEquals(adapter.TOKEN_ALIAS_LOCK_VALUE, block["lock"])
        assertNull(block["prefix_keys"])
        val algebra = requireNotNull(root["speller"].asStringMap()?.get("algebra").asStringList())
        assertFalse(algebra.contains(adapter.LETTER_CLASS_PREFIX_RULE))
    }

    @Test
    fun prefixDeclarationIsFoldedIntoTheSourceContract() {
        assertEquals("letter_class_v1", optedNumericProjection.prefixDeclaration)
        val opted =
            projection(
                schema = "t9",
                alphabet = "23456789",
                algebra = emptyList(),
                prefixDeclaration = "letter_class_v1",
                syllables = emptyList(),
            )
        val revoked = projection(schema = "t9", alphabet = "23456789", algebra = emptyList(), syllables = emptyList())
        assertTrue(adapter.declaresLetterClassPrefixSource(opted))
        assertFalse(adapter.declaresLetterClassPrefixSource(revoked))
    }

    @Test
    fun prefixLockAuthorizedRequiresTheBakedLayer() {
        val id = "keyboard_47246213a538cb99d374_9"
        val keys = requireNotNull(adapter.letterClassPrefixKeys(optedNumericProjection))
        val data = adapter.generate(numericNineKeySource(id), id, CompactFold.PHONE9, null, optedNumericProjection)
        assertTrue(adapter.prefixLockAuthorized(optedNumericProjection, data, keys, layerAlreadyPresent = false))
        val altered = String(data).replace(adapter.LETTER_CLASS_PREFIX_RULE, "abbrev/^([a-z]).+$/\$2/")
        assertFalse(
            adapter.prefixLockAuthorized(
                optedNumericProjection,
                altered.toByteArray(),
                keys,
                layerAlreadyPresent = false,
            ),
        )
        assertFalse(
            adapter.prefixLockAuthorized(optedNumericProjection, data, listOf("a", "Z"), layerAlreadyPresent = false),
        )
        val notOpted =
            projection(
                schema = id,
                alphabet = "23456789",
                algebra = emptyList(),
                syllables = listOf(syllable("a", normal("a"))),
            )
        assertFalse(adapter.prefixLockAuthorized(notOpted, data, keys, layerAlreadyPresent = false))
    }

    @Test
    fun rewriteCapabilityWireShapeForPrefixMode() {
        val capability =
            CompactSchemaAdapter.RewriteCapability(
                version = adapter.REWRITE_WIRE_VERSION_PREFIX,
                lock = adapter.PREFIX_TOKEN_LOCK_VALUE,
                sourceSchema = "keyboard_47246213a538cb99d374_9",
                translatorNamespace = "translator",
                adapterSchema = "adapter9",
                lockMap = emptyMap(),
                prefixKeys = listOf("a", "b"),
                prefixLayer = "letter_class_v1",
            )
        val wire = capability.wireDictionary()
        assertEquals(4, wire["version"])
        assertEquals("prefix_token_rewrite_v4", wire["lock"])
        assertEquals(listOf("a", "b"), wire["prefix_keys"])
        assertEquals("letter_class_v1", wire["prefix_layer"])
        assertNull(wire["lock_map"])
        assertNull(wire["direct_tokens"])
        assertEquals("other", capability.settingAdapterSchema("other").adapterSchema)
    }

    // ---- Shared physical-alphabet contract (adaptability == generate) ----

    @Test
    fun adaptabilityAndGenerationShareThePhysicalFoldContract() {
        val script = String(canonicalT9Source)
        assertEquals(CompactSchemaAdapter.Adaptability.Adaptable, adapter.adaptability(script.toByteArray(), "t9_test"))
        // A numeric alphabet folds for the 9-key layout only.
        assertNotNull(adapter.physicalFold("23456789abc", CompactFold.PHONE9))
        assertNull("no present letter, nothing to fold", adapter.physicalFold("23456789", CompactFold.PHONE9))
        assertNull(adapter.physicalFold("23456789abc", CompactFold.MERGED14))
        // Uppercase needs its lowercase key declared; an uppercase-only alphabet cannot be typed.
        assertNotNull(adapter.physicalFold("abcA", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("ABC", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("", CompactFold.MERGED14))
        assertNull(adapter.physicalFold("abcㄅ", CompactFold.MERGED14))
        val bopomofoKeys =
            "schema: {schema_id: b, name: b}\nengine: {translators: [script_translator]}\n" +
                "speller: {alphabet: \"ㄅㄆ\"}\ntranslator: {dictionary: d}\n"
        assertTrue(
            adapter.adaptability(bopomofoKeys.toByteArray(), "b") is CompactSchemaAdapter.Adaptability.Unsupported,
        )
        val named =
            "schema: {schema_id: n, name: n}\nengine: {translators: [\"script_translator@foo\"]}\n" +
                "speller: {alphabet: abc}\nfoo: {dictionary: d}\n"
        assertTrue(adapter.adaptability(named.toByteArray(), "n") is CompactSchemaAdapter.Adaptability.Unsupported)
        assertThrows<RecipeInstallError.InvalidResource> { adapter.adaptability(script.toByteArray(), "other_id") }
    }
}

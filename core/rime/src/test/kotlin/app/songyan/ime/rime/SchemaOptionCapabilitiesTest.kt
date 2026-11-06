package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Which switches of a compiled schema the keyboard offers, and what each is called. */
class SchemaOptionCapabilitiesTest {
    private fun parse(
        yaml: String,
        installed: Set<String> = setOf("s2t.json", "t2s.json", "emoji.json", "s2tw.json"),
    ) = SchemaOptionCapabilities.parse("pinyin", "luna", yaml, installed::contains)

    @Test
    fun theDeployedSpellerAlphabetIsReadForLayoutSelection() {
        val build = Files.createTempDirectory("speller-alphabet").toFile()
        try {
            File(build, "dachen.schema.yaml").writeText(
                "schema:\n  schema_id: dachen\nspeller:\n  alphabet: \"1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-\"\n",
            )
            assertEquals(
                "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-",
                SchemaOptionCapabilities.spellerAlphabet(build, "dachen"),
            )
            // A schema without the key declares no alphabet, and a missing file is not an error.
            File(build, "plain.schema.yaml").writeText("schema:\n  schema_id: plain\n")
            assertEquals("", SchemaOptionCapabilities.spellerAlphabet(build, "plain"))
            assertEquals("", SchemaOptionCapabilities.spellerAlphabet(build, "missing"))
        } finally {
            build.deleteRecursively()
        }
    }

    private val traditional =
        """
        schema:
          schema_id: luna
        engine:
          filters:
            - simplifier@traditionalize
            - uniquifier
        switches:
          - name: ascii_mode
            reset: 0
            states: [ 中文, 西文 ]
          - name: traditionalize
            reset: 1
            states: [ 简, 繁 ]
          - name: ascii_punct
            states: [ 。，, ．， ]
        traditionalize:
          option_name: traditionalize
          opencc_config: s2t.json
        """.trimIndent()

    @Test
    fun aSimplifierSwitchIsOfferedWithItsKindAndResetValue() {
        val found = parse(traditional).single()
        assertEquals("traditionalize", found.name)
        assertEquals(EngineOptionKind.TRADITIONAL, found.kind)
        assertTrue(found.defaultValue)
        assertEquals("pinyin", found.resourceID)
        assertEquals("luna", found.schemaID)
    }

    @Test
    fun aSwitchCarriesTheOpenccConfigItDeclares() {
        assertEquals("s2t", parse(traditional).single().scriptConfig)
    }

    @Test
    fun aSwitchWithoutAScriptConversionCarriesNoConfig() {
        val fullShape = traditional.replace("  - name: ascii_punct", "  - name: full_shape")
        assertNull(parse(fullShape).last().scriptConfig)
        assertNull(parse(traditional.replace("s2t.json", "emoji.json")).single().scriptConfig)
    }

    @Test
    fun theBareSimplifierCarriesItsDefaultDeclaredConfig() {
        val yaml =
            """
            engine:
              filters:
              - simplifier
            switches:
            - name: simplification
            simplifier:
              opencc_config: t2s.json
            """.trimIndent()
        assertEquals("t2s", parse(yaml).single().scriptConfig)
    }

    @Test
    fun aRadioGroupMemberCarriesTheConfigOfItsOwnFilter() {
        val yaml =
            """
            engine:
              filters:
                - simplifier@trad
            switches:
              - options: [ trad, simp ]
                states: [ 繁, 简 ]
            trad:
              option_name: trad
              opencc_config: s2t.json
            """.trimIndent()
        assertEquals(listOf("s2t", null), parse(yaml).map { it.scriptConfig })
    }

    @Test
    fun theAsciiModeKeyAndOtherSwitchesNoFilterDrivesAreNotListed() {
        assertEquals(listOf("traditionalize"), parse(traditional).map { it.name })
    }

    @Test
    fun theFullShapeSwitchIsOfferedWithoutAFilterBecauseLibrimeAlwaysShapes() {
        val yaml = traditional.replace("  - name: ascii_punct", "  - name: full_shape")
        val found = parse(yaml).last()
        assertEquals("full_shape", found.name)
        assertEquals(EngineOptionKind.FULL_SHAPE, found.kind)
        assertEquals(false, found.defaultValue)
        val reset = yaml.replace("  - name: full_shape", "  - name: full_shape\n    reset: 1")
        assertTrue(parse(reset).last().defaultValue)
    }

    @Test
    fun theBareSimplifierUsesTheDefaultSwitchName() {
        val yaml =
            """
            engine:
              filters:
              - simplifier
            switches:
            - name: simplification
              reset: 0
            simplifier:
              opencc_config: t2s.json
            """.trimIndent()
        val found = parse(yaml).single()
        assertEquals("simplification", found.name)
        assertEquals(EngineOptionKind.SIMPLIFIED, found.kind)
        assertEquals(false, found.defaultValue)
    }

    @Test
    fun anEmojiFilterIsOfferedAsEmojiCandidates() {
        val yaml = traditional.replace("s2t.json", "emoji.json")
        assertEquals(EngineOptionKind.EMOJI, parse(yaml).single().kind)
    }

    @Test
    fun aConfigTheKeyboardCannotNameIsSkipped() {
        assertTrue(parse(traditional.replace("s2t.json", "t2jp.json"), setOf("t2jp.json")).isEmpty())
    }

    @Test
    fun aConfigThatIsNotInstalledIsSkipped() {
        assertTrue(parse(traditional, installed = emptySet()).isEmpty())
    }

    @Test
    fun aFilterWhoseSwitchIsNotDeclaredIsSkipped() {
        assertTrue(parse(traditional.replace("- name: traditionalize", "- name: other")).isEmpty())
    }

    @Test
    fun aRadioGroupKeepsItsNativeNamesOrderAndLabels() {
        val yaml =
            """
            engine:
              filters:
                - simplifier@trad
            switches:
              - options: [ trad, simp ]
                states: [ 繁, 简 ]
            trad:
              option_name: trad
              opencc_config: s2t.json
            """.trimIndent()
        val found = parse(yaml)
        assertEquals(listOf("trad", "simp"), found.map { it.name })
        assertTrue(found.all { it.choiceGroup == listOf("trad", "simp") })
        assertEquals(listOf("繁", "简"), found.map { it.choiceLabel })
    }

    @Test
    fun twoFiltersOnOneSwitchOfferItOnce() {
        val yaml =
            """
            engine:
              filters:
                - simplifier@a
                - simplifier@b
            switches:
              - name: shared
            a:
              option_name: shared
              opencc_config: s2t.json
            b:
              option_name: shared
              opencc_config: emoji.json
            """.trimIndent()
        assertEquals(EngineOptionKind.TRADITIONAL, parse(yaml).single().kind)
    }

    @Test
    fun quotedValuesAreUnquoted() {
        val yaml =
            traditional
                .replace("opencc_config: s2t.json", "opencc_config: \"s2t.json\"")
                .replace("option_name: traditionalize", "option_name: 'traditionalize'")
        assertEquals("traditionalize", parse(yaml).single().name)
    }

    @Test
    fun anUnreadableOrEmptySchemaOffersNothing() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("engine:\n  filters:\n").isEmpty())
    }

    @Test
    fun aBareSimplifierUsesTheNativeOpenccDefault() {
        val yaml =
            traditional
                .replace("simplifier@traditionalize", "simplifier")
                .replace("traditionalize", "simplification")
                .replace("  opencc_config: s2t.json", "")
        assertEquals(EngineOptionKind.SIMPLIFIED, parse(yaml).single().kind)
    }

    @Test
    fun aCompiledBlockRadioGroupKeepsTheNoConversionChoiceAndReset() {
        val yaml =
            """
            switches:
              - options:
                  - original
                  - simplified
                  - hongkong
                  - taiwan
                states:
                  - 原字
                  - 简体
                  - 港繁
                  - 臺繁
                reset: 2
            engine:
              filters:
                - simplifier@cn
                - simplifier@hk
                - simplifier@tw
            cn:
              option_name: simplified
              opencc_config: t2s.json
            hk:
              option_name: hongkong
              opencc_config: t2hk.json
            tw:
              option_name: taiwan
              opencc_config: t2tw.json
            """.trimIndent()
        val found = parse(yaml, setOf("t2s.json", "t2hk.json", "t2tw.json"))
        assertEquals(listOf("original", "simplified", "hongkong", "taiwan"), found.map { it.name })
        assertEquals(listOf("原字", "简体", "港繁", "臺繁"), found.map { it.choiceLabel })
        assertEquals(listOf(false, false, true, false), found.map { it.defaultValue })
        assertEquals(
            listOf(
                true,
                false,
                false,
                false,
            ),
            parse(yaml.replace("    reset: 2", ""), setOf("t2s.json", "t2hk.json", "t2tw.json")).map {
                it.defaultValue
            },
        )
        assertTrue(parse(yaml, setOf("t2s.json", "t2tw.json")).isEmpty())
    }

    /** The bundled schemes' real declaration, as the compiled build carries it. */
    private val canonicalFourStates =
        """
        schema:
          schema_id: songyan_quanpin
        engine:
          filters:
            - simplifier@zht
            - simplifier@zh_tw
            - simplifier@zh_hk
            - uniquifier
        switches:
          - options: [simplification, zht, zh_tw, zh_hk]
            states: [简体, 繁體, 臺灣, 香港]
            reset: 0
        zht:
          option_name: zht
          opencc_config: s2t.json
        zh_tw:
          option_name: zh_tw
          opencc_config: s2twp.json
        zh_hk:
          option_name: zh_hk
          opencc_config: s2hk.json
        """.trimIndent()

    private fun parseBuiltin(
        yaml: String,
        schemaID: String = BuiltinTemplateSchemas.QUANPIN,
    ) = SchemaOptionCapabilities.parse(
        "pinyin",
        schemaID,
        yaml,
        { true },
    ) { config ->
        if (config in setOf("s2t.json", "s2twp.json", "s2hk.json")) EngineOptionKind.TRADITIONAL else null
    }

    @Test
    fun theBundledSchemesFourStateGroupCarriesItsKnownStates() {
        val found = parseBuiltin(canonicalFourStates)
        assertEquals(listOf("simplification", "zht", "zh_tw", "zh_hk"), found.map { it.name })
        assertEquals(
            listOf(
                BuiltinScriptState.SIMPLIFIED,
                BuiltinScriptState.TRADITIONAL,
                BuiltinScriptState.TAIWAN,
                BuiltinScriptState.HONG_KONG,
            ),
            found.map { it.knownScriptState },
        )
        // The group itself is unchanged: same labels, same members, same default.
        assertEquals(listOf("简体", "繁體", "臺灣", "香港"), found.map { it.choiceLabel })
        assertEquals(listOf(true, false, false, false), found.map { it.defaultValue })
    }

    @Test
    fun theFourStatesAreKnownOnlyForTheBundledSchemes() {
        // An author's scheme with the same option names keeps its own labels and is never reworded.
        val found = parseBuiltin(canonicalFourStates, schemaID = "rime_ice")
        assertEquals(4, found.size)
        assertTrue(found.all { it.knownScriptState == null })
        assertEquals(listOf("简体", "繁體", "臺灣", "香港"), found.map { it.choiceLabel })
    }

    @Test
    fun customLabelsOnTheBundledGroupKeepTheirOwnWording() {
        val custom = canonicalFourStates.replace("[简体, 繁體, 臺灣, 香港]", "[简, 繁, 台, 港]")
        val found = parseBuiltin(custom)
        assertEquals(listOf("简", "繁", "台", "港"), found.map { it.choiceLabel })
        assertTrue(found.all { it.knownScriptState == null })
    }

    @Test
    fun onlyTheCompleteGroupInItsDeclaredOrderIsTheBundledFourStates() {
        val incomplete =
            canonicalFourStates
                .replace("[simplification, zht, zh_tw, zh_hk]", "[simplification, zht, zh_tw]")
                .replace("[简体, 繁體, 臺灣, 香港]", "[简体, 繁體, 臺灣]")
        assertTrue(parseBuiltin(incomplete).all { it.knownScriptState == null })

        val reordered =
            canonicalFourStates
                .replace("[simplification, zht, zh_tw, zh_hk]", "[simplification, zh_tw, zht, zh_hk]")
                .replace("[简体, 繁體, 臺灣, 香港]", "[简体, 臺灣, 繁體, 香港]")
        assertTrue(parseBuiltin(reordered).all { it.knownScriptState == null })
    }

    @Test
    fun customOpenccNamesUseRealConfigSemanticsAndRejectMissingNestedDependencies() {
        val root =
            java.nio.file.Files
                .createTempDirectory("schema-opencc")
                .toFile()
        try {
            val opencc = java.io.File(root, "opencc").apply { mkdirs() }
            val dictionary = java.io.File(opencc, "data.txt").apply { writeText("开心\t😄\n") }
            java.io.File(opencc, "arbitrary.json").writeText(
                """
                {"name":"Emoji","segmentation":{"type":"mmseg","dict":{"type":"text","file":"data.txt"}},
                "conversion_chain":[{"dict":{"type":"group","dicts":[{"type":"text","file":"data.txt"}]}}]}
                """.trimIndent(),
            )
            assertEquals(EngineOptionKind.EMOJI, SchemaOptionCapabilities.verifiedOpencc("arbitrary.json", root))
            dictionary.delete()
            assertEquals(null, SchemaOptionCapabilities.verifiedOpencc("arbitrary.json", root))
            java.io.File(opencc, "arbitrary.json").writeText("{broken")
            assertEquals(null, SchemaOptionCapabilities.verifiedOpencc("arbitrary.json", root))
        } finally {
            root.deleteRecursively()
        }
    }
}

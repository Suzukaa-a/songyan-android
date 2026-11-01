package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TypoCorrectionTest {
    // MARK: - Eligibility

    @Test
    fun fullAlphabetScriptTranslatorIsEligibleOnItsPrimaryNamespace() {
        assertEquals(
            TypoCorrection.Eligibility.Eligible(TypoCorrection.Target("any_pinyin", "translator")),
            TypoCorrection.eligibility(compiledSchema(id = "any_pinyin"), "any_pinyin"),
        )
        // Auxiliary entries never make the schema look mixed or move the namespace.
        val withAux =
            compiledSchema(
                id = "p",
                translators =
                    listOf(
                        "punct_translator",
                        "script_translator",
                        "table_translator@custom_phrase",
                        "lua_translator@*date",
                    ),
            )
        assertEquals(
            "translator",
            (TypoCorrection.eligibility(withAux, "p") as TypoCorrection.Eligibility.Eligible).target.namespace,
        )
        // A named-only primary producer binds its own namespace, never a hard-coded translator.
        val named = compiledSchema(id = "n", translators = listOf("script_translator@main"), namespace = "main")
        assertEquals(
            "main/enable_correction",
            (TypoCorrection.eligibility(named, "n") as TypoCorrection.Eligibility.Eligible).target.configKey,
        )
        // Missing speller/alphabet means librime's default full alphabet.
        assertTrue(
            TypoCorrection.eligibility(
                compiledSchema(id = "d", alphabet = null),
                "d",
            ) is TypoCorrection.Eligibility.Eligible,
        )
        // Extra punctuation keys are fine as long as a-z are all present.
        assertTrue(
            TypoCorrection.eligibility(
                compiledSchema(id = "e", alphabet = "\"zyxwvutsrqponmlkjihgfedcba;'\""),
                "e",
            ) is TypoCorrection.Eligibility.Eligible,
        )
    }

    @Test
    fun ineligibleSchemasFailClosed() {
        fun reason(
            compiled: ByteArray,
            id: String,
        ): TypoCorrection.Reason? =
            (TypoCorrection.eligibility(compiled, id) as? TypoCorrection.Eligibility.Ineligible)?.reason

        assertEquals(
            TypoCorrection.Reason.NOT_SCRIPT_TRANSLATOR,
            reason(compiledSchema(id = "t", translators = listOf("table_translator")), "t"),
        )
        assertEquals(
            TypoCorrection.Reason.NO_PRIMARY_PRODUCER,
            reason(compiledSchema(id = "m", translators = listOf("script_translator", "table_translator")), "m"),
        )
        assertEquals(
            TypoCorrection.Reason.ALPHABET_NOT_FULL,
            reason(compiledSchema(id = "nine", alphabet = "23456789"), "nine"),
        )
        assertEquals(
            TypoCorrection.Reason.ALPHABET_NOT_FULL,
            reason(compiledSchema(id = "c14", alphabet = "qwertyuiopasdfg"), "c14"),
        )
        assertEquals(TypoCorrection.Reason.IDENTITY_MISMATCH, reason(compiledSchema(id = "a"), "b"))
        assertEquals(TypoCorrection.Reason.CONFIG_UNREADABLE, reason("key: [unterminated".toByteArray(), "a"))
        assertEquals(TypoCorrection.Reason.INVALID_SCHEMA_ID, reason(compiledSchema(id = "../x"), "../x"))
        // Compact companions, by minted id or by carried capability block.
        val companion = CompactSchemaAdapter.identifier("any_pinyin", CompactFold.MERGED14)
        assertTrue(CompactSchemaAdapter.isGeneratedCompanionID(companion))
        assertEquals(
            TypoCorrection.Reason.GENERATED_COMPANION,
            reason(compiledSchema(id = companion), companion),
        )
        assertEquals(
            TypoCorrection.Reason.COMPACT_ADAPTER,
            reason(compiledSchema(id = "tpl", extra = "keyboard_rewrite:\n  version: 2"), "tpl"),
        )
    }

    // MARK: - Recipe generation

    @Test
    fun recipePatchesOnlyTheNamedSchemasUnderOneManagedBlock() {
        val alpha = TypoCorrection.Target("alpha", "translator")
        val beta = TypoCorrection.Target("beta", "main")
        val recipe = TypoCorrection.make(enabling = listOf(alpha), disabling = listOf(beta))
        assertEquals(TypoCorrection.RECIPE_NAME, recipe.declaredName)
        assertTrue(recipe.installPatterns.isEmpty())
        assertTrue(recipe.downloads.isEmpty())
        assertEquals(listOf("alpha.custom.yaml", "beta.custom.yaml"), recipe.patchTargets.sorted())
        // The generated recipe text is part of the cross-platform data format.
        assertEquals(
            """
            recipe:
              Rx: typo-correction
              description: 错键纠正
            patch_files:
              alpha.custom.yaml:
                - patch/+:
                    translator/enable_correction: true
              beta.custom.yaml:
                - patch/+:
                    main/enable_correction: false

            """.trimIndent(),
            String(recipe.source, Charsets.UTF_8),
        )
        assertThrows(RecipeInstallError.InvalidResource::class.java) { TypoCorrection.make(enabling = emptyList()) }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            TypoCorrection.make(enabling = listOf(TypoCorrection.Target("../bad", "translator")))
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            TypoCorrection.make(enabling = listOf(TypoCorrection.Target("ok", "a/b")))
        }
        val companion = CompactSchemaAdapter.identifier("alpha", CompactFold.PHONE9)
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            TypoCorrection.make(enabling = listOf(TypoCorrection.Target(companion, "translator")))
        }

        val userCustom = "patch:\n  menu/page_size: 7\n".toByteArray()
        val existing =
            mapOf(
                "alpha.schema.yaml" to compiledSchema(id = "alpha"),
                "beta.schema.yaml" to compiledSchema(id = "beta"),
                "alpha.custom.yaml" to userCustom,
            )
        val plan = applyRecipe(recipe, existing)
        val alphaText = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertTrue("user custom content is preserved", alphaText.contains("menu/page_size: 7"))
        assertTrue(alphaText.contains("# Rx: " + TypoCorrection.rx("user/pkg") + " {"))
        assertTrue(alphaText.contains("translator/enable_correction: true"))
        val betaText = String(plan.files.getValue("beta.custom.yaml"), Charsets.UTF_8)
        assertTrue(betaText.contains("main/enable_correction: false"))
        assertTrue(
            "source schema untouched",
            existing.getValue("alpha.schema.yaml").contentEquals(plan.files["alpha.schema.yaml"]),
        )

        // Re-running replaces the managed block instead of stacking a second one.
        val off = TypoCorrection.make(enabling = emptyList(), disabling = listOf(alpha))
        val replan = applyRecipe(off, plan.files)
        val again = String(replan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertEquals(1, again.split("enable_correction").size - 1)
        assertTrue(again.contains("translator/enable_correction: false"))
        assertTrue(again.contains("menu/page_size: 7"))
        assertFalse(
            "our own managed block is not a user setting",
            TypoCorrection.userOwnsSetting(replan.files.getValue("alpha.custom.yaml"), "user/pkg"),
        )
        // The YAML Rime will read really carries the bool through __patch.
        val root = BoundedYaml.load(again).asStringMap()
        val patches = root?.get("__patch") as List<*>
        val merged = patches.mapNotNull { it.asStringMap()?.get("patch/+").asStringMap() }
        assertEquals(false, merged.last()["translator/enable_correction"])
    }

    @Test
    fun userManagedSettingIsDetectedAndSkipped() {
        val own = "patch:\n  translator/enable_correction: false\n".toByteArray()
        assertTrue(TypoCorrection.userOwnsSetting(own, "p"))
        assertFalse(TypoCorrection.userOwnsSetting("# enable_correction note\npatch: {}\n".toByteArray(), "p"))
        assertFalse(TypoCorrection.userOwnsSetting(null, "p"))
        // An unclosed block is damaged input: count it as user-managed so the save is refused
        // instead of rewriting the file around another invocation's block.
        val damaged =
            "__patch:\n# Rx: user/pkg:" + TypoCorrection.RECIPE_NAME + ": {\n  - patch/+:\n      x: 1\n" +
                "# Rx: other/pkg:other: {\n  - patch/+:\n      translator/enable_correction: true\n# }\n"
        assertTrue(TypoCorrection.userOwnsSetting(damaged.toByteArray(), "user/pkg"))
        // A damaged compiled default is reported as unreadable, never silently as "no schemas".
        val unreadable =
            listOf(
                "broken".toByteArray(),
                "schema_list: [unterminated".toByteArray(),
                "other_key: 1\n".toByteArray(),
                "schema_list:\n  - name: x\n".toByteArray(),
            )
        for (defaults in unreadable) {
            assertEquals(
                TypoCorrection.EnabledSchemas.Unreadable,
                TypoCorrection.enabledSchemaResult(defaults),
            )
        }
        assertEquals(
            TypoCorrection.EnabledSchemas.Listed(listOf("a", "b")),
            TypoCorrection.enabledSchemaResult(
                "schema_list:\n  - schema: a\n  - schema: b\n  - schema: a\n".toByteArray(),
            ),
        )
        val a = TypoCorrection.Target("a", "translator")
        val b = TypoCorrection.Target("b", "translator")
        val c = TypoCorrection.Target("c", "translator")
        assertEquals(
            listOf(a),
            TypoCorrection.changes(
                desired = true,
                targets = listOf(c, a, b),
                current = mapOf("b" to true),
                userManaged = setOf("c"),
            ),
        )
        assertEquals(
            listOf(b),
            TypoCorrection.changes(
                desired = false,
                targets = listOf(a, b, c),
                current = mapOf("b" to true),
                userManaged = emptySet(),
            ),
        )
    }

    @Test
    fun compiledValueAndEnabledList() {
        val target = TypoCorrection.Target("x", "translator")
        assertNull(TypoCorrection.compiledValue(compiledSchema(id = "x"), target))
        assertEquals(
            true,
            TypoCorrection.compiledValue(
                "translator:\n  dictionary: d\n  enable_correction: true\n".toByteArray(),
                target,
            ),
        )
        assertEquals(
            listOf("one", "two"),
            TypoCorrection.enabledSchemaIDs(
                "schema_list:\n  - schema: one\n  - schema: two\n  - schema: one\n".toByteArray(),
            ),
        )
        assertEquals(emptyList<String>(), TypoCorrection.enabledSchemaIDs("x: 1\n".toByteArray()))
    }
}

/** JUnit 4 throw assertion helper (the module has no assertion library beyond JUnit). */
internal fun assertThrows(
    expected: Class<out Throwable>,
    block: () -> Unit,
) {
    try {
        block()
    } catch (error: Throwable) {
        if (expected.isInstance(error)) return
        throw AssertionError("expected " + expected.name + " but got " + error, error)
    }
    fail("expected " + expected.name + " to be thrown")
}

/** Same assertion with a label describing the input under test. */
internal fun assertThrows(
    message: String,
    expected: Class<out Throwable>,
    block: () -> Unit,
) {
    try {
        block()
    } catch (error: Throwable) {
        if (expected.isInstance(error)) return
        throw AssertionError(message + ": expected " + expected.name + " but got " + error, error)
    }
    fail(message + ": expected " + expected.name + " to be thrown")
}

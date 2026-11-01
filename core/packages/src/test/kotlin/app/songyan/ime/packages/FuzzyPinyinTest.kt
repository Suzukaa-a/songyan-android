package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FuzzyPinyinTest {
    private fun target(
        id: String,
        namespace: String = "translator",
    ): FuzzyPinyin.Target = FuzzyPinyin.Target(id, namespace)

    private fun source(
        id: String,
        algebra: List<String>?,
        alphabet: String? = "zyxwvutsrqponmlkjihgfedcba",
    ): ByteArray {
        val lines = mutableListOf("schema:", "  schema_id: " + id, "  name: Test")
        if (alphabet != null || algebra != null) {
            lines.add("speller:")
            if (alphabet != null) lines.add("  alphabet: " + alphabet)
            if (algebra != null) {
                if (algebra.isEmpty()) {
                    lines.add("  algebra: []")
                } else {
                    lines.add("  algebra:")
                    for (rule in algebra) lines.add("    - \"" + rule + "\"")
                }
            }
        }
        return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    // MARK: - Eligibility

    @Test
    fun fullAlphabetScriptTranslatorIsEligible() {
        assertEquals(
            FuzzyPinyin.Eligibility.Eligible(target("any_pinyin")),
            FuzzyPinyin.eligibility(compiledSchema(id = "any_pinyin"), "any_pinyin"),
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
            (FuzzyPinyin.eligibility(withAux, "p") as FuzzyPinyin.Eligibility.Eligible).target.namespace,
        )
        // The patched key is schema-level, whatever namespace the producer uses.
        assertEquals("speller/algebra", target("n", namespace = "main").configKey)
        assertEquals("speller/algebra", target("n").configKey)
        // Missing speller/alphabet means librime's default full alphabet.
        assertNotNull(FuzzyPinyin.eligibility(compiledSchema(id = "d", alphabet = null), "d").target)
    }

    @Test
    fun ineligibleSchemasFailClosed() {
        fun reason(
            compiled: ByteArray,
            id: String,
        ): FuzzyPinyin.Reason? = (FuzzyPinyin.eligibility(compiled, id) as? FuzzyPinyin.Eligibility.Ineligible)?.reason

        // Shape codes: a table_translator produces no pinyin readings.
        assertEquals(
            FuzzyPinyin.Reason.NOT_PINYIN,
            reason(compiledSchema(id = "t", translators = listOf("table_translator")), "t"),
        )
        // Mixed default provenance.
        assertEquals(
            FuzzyPinyin.Reason.NOT_PINYIN,
            reason(compiledSchema(id = "m", translators = listOf("script_translator", "table_translator")), "m"),
        )
        // Numeric (native 9-key) or merged (14-key) alphabets are not 26-key input.
        assertEquals(
            FuzzyPinyin.Reason.SPELLER_ALPHABET_UNSUPPORTED,
            reason(compiledSchema(id = "nine", alphabet = "23456789"), "nine"),
        )
        assertEquals(
            FuzzyPinyin.Reason.SPELLER_ALPHABET_UNSUPPORTED,
            reason(compiledSchema(id = "c14", alphabet = "qwertyuiopasdfg"), "c14"),
        )
        // Identity mismatch, garbage and bad ids.
        assertEquals(FuzzyPinyin.Reason.INCOMPLETE_CONFIG, reason(compiledSchema(id = "a"), "b"))
        assertEquals(FuzzyPinyin.Reason.UNREADABLE_CONFIG, reason("key: [unterminated".toByteArray(), "a"))
        assertEquals(FuzzyPinyin.Reason.INVALID_SCHEMA_ID, reason(compiledSchema(id = "../x"), "../x"))
        // A primary producer without a usable dictionary cannot produce readings.
        val noDict =
            (
                "schema:\n  schema_id: nodict\nengine:\n  translators:\n    - script_translator\n" +
                    "translator:\n  dictionary: \"\"\n"
            ).toByteArray()
        assertEquals(FuzzyPinyin.Reason.NO_DICTIONARY, reason(noDict, "nodict"))
        // Compact companions, by minted id or by carried capability block.
        val companion =
            CompactSchemaAdapter.identifier(
                "any_pinyin",
                app.songyan.ime.keyboard.layout.CompactFold.MERGED14,
            )
        assertEquals(FuzzyPinyin.Reason.GENERATED_COMPANION, reason(compiledSchema(id = companion), companion))
        assertEquals(
            FuzzyPinyin.Reason.COMPACT_KEYBOARD_ONLY,
            reason(compiledSchema(id = "tpl", extra = "keyboard_rewrite:\n  version: 2"), "tpl"),
        )
    }

    // MARK: - Pairs and their rule text

    @Test
    fun pairsAreBoundedBidirectionalAndDeterministic() {
        assertEquals(8, FuzzyPinyin.allPairs.size)
        assertEquals(
            listOf("z-zh", "c-ch", "s-sh", "an-ang", "en-eng", "in-ing", "n-l", "f-h"),
            FuzzyPinyin.allPairs.map { it.id },
        )
        // The generated rules are a fixed set: every one of them is a native fuzz derivation.
        assertEquals(
            listOf(
                "fuzz/^zh/z/",
                "fuzz/^z(?!h)/zh/",
                "fuzz/^ch/c/",
                "fuzz/^c(?!h)/ch/",
                "fuzz/^sh/s/",
                "fuzz/^s(?!h)/sh/",
                "fuzz/an(?!g)/ang/",
                "fuzz/ang/an/",
                "fuzz/en(?!g)/eng/",
                "fuzz/eng/en/",
                "fuzz/in(?!g)/ing/",
                "fuzz/ing/in/",
                "fuzz/^n/l/",
                "fuzz/^l/n/",
                "fuzz/^f/h/",
                "fuzz/^h/f/",
            ),
            FuzzyPinyin.allPairs.flatMap { it.rules },
        )
        for (pair in FuzzyPinyin.allPairs) {
            assertEquals(pair.id + " is bidirectional", 2, pair.rules.size)
            assertTrue(pair.rules.all { it.startsWith("fuzz/") && it.endsWith("/") })
            assertEquals(pair, FuzzyPinyin.pair(pair.id))
        }
        assertNull(FuzzyPinyin.pair("zh-z"))

        // A set has no order of its own: the generated list must not depend on insertion order.
        assertEquals(
            FuzzyPinyin.allPairs.flatMap { it.rules },
            FuzzyPinyin.rules(FuzzyPinyin.allPairs.map { it.id }.toSet()),
        )
        assertEquals(
            listOf(
                "fuzz/^zh/z/",
                "fuzz/^z(?!h)/zh/",
                "fuzz/^n/l/",
                "fuzz/^l/n/",
                "fuzz/^f/h/",
                "fuzz/^h/f/",
            ),
            FuzzyPinyin.rules(setOf("f-h", "z-zh", "n-l")),
        )
        assertEquals(emptyList<String>(), FuzzyPinyin.rules(emptySet()))
        assertNull("an unknown identifier is refused, never ignored", FuzzyPinyin.rules(setOf("bogus")))
        // Reading a block back only accepts exactly what we generate.
        assertEquals(setOf("an-ang"), FuzzyPinyin.pairIDs(FuzzyPinyin.rules(setOf("an-ang"))!!))
        assertEquals(emptySet<String>(), FuzzyPinyin.pairIDs(emptyList()))
        assertNull("an edited block is not a state we act on", FuzzyPinyin.pairIDs(listOf("fuzz/^x/y/")))
    }

    /**
     * The rule text really derives the intended spellings, run through a real regex engine with
     * librime's replace direction (fuzz is a regex replace plus a credibility penalty; the engine's
     * own proof stays with the native projection at deployment).
     */
    @Test
    fun ruleTextDerivesBothDirectionsWithoutRematching() {
        // Retroflex: a short onset gains h, an already-long one never gains a second h.
        assertEquals("zhong", applyFuzzRule("fuzz/^z(?!h)/zh/", "zong"))
        assertNull(applyFuzzRule("fuzz/^z(?!h)/zh/", "zhong"))
        assertEquals("zong", applyFuzzRule("fuzz/^zh/z/", "zhong"))
        assertNull(applyFuzzRule("fuzz/^zh/z/", "zong"))
        assertEquals("chai", applyFuzzRule("fuzz/^c(?!h)/ch/", "cai"))
        assertNull(applyFuzzRule("fuzz/^c(?!h)/ch/", "chai"))
        assertEquals("shan", applyFuzzRule("fuzz/^s(?!h)/sh/", "san"))
        assertNull(applyFuzzRule("fuzz/^s(?!h)/sh/", "shan"))
        // Nasal finals reach a syllable with an onset, not only a bare final.
        assertEquals("ang", applyFuzzRule("fuzz/an(?!g)/ang/", "an"))
        assertEquals("bang", applyFuzzRule("fuzz/an(?!g)/ang/", "ban"))
        assertEquals("zhuang", applyFuzzRule("fuzz/an(?!g)/ang/", "zhuan"))
        // A final that already ends in "ang" is left alone: "nang" must not become "nangg".
        assertNull(applyFuzzRule("fuzz/an(?!g)/ang/", "nang"))
        assertNull(applyFuzzRule("fuzz/an(?!g)/ang/", "shang"))
        assertEquals("ban", applyFuzzRule("fuzz/ang/an/", "bang"))
        assertEquals("nan", applyFuzzRule("fuzz/ang/an/", "nang"))
        assertNull(applyFuzzRule("fuzz/ang/an/", "an"))
        assertEquals("beng", applyFuzzRule("fuzz/en(?!g)/eng/", "ben"))
        assertNull(applyFuzzRule("fuzz/en(?!g)/eng/", "sheng"))
        assertEquals("ling", applyFuzzRule("fuzz/in(?!g)/ing/", "lin"))
        assertNull(applyFuzzRule("fuzz/in(?!g)/ing/", "ling"))
        assertEquals("chen", applyFuzzRule("fuzz/eng/en/", "cheng"))
        // Initials.
        assertEquals("li", applyFuzzRule("fuzz/^n/l/", "ni"))
        assertEquals("na", applyFuzzRule("fuzz/^l/n/", "la"))
        assertEquals("hang", applyFuzzRule("fuzz/^f/h/", "fang"))
        assertNull(applyFuzzRule("fuzz/^h/f/", "fang"))
        // No rule of ours ever erases a spelling: they only add one.
        assertTrue(FuzzyPinyin.allPairs.flatMap { it.rules }.none { it.contains("erase/") })
    }

    // MARK: - Recipe generation

    @Test
    fun recipeInsertsBeforeTheSchemasOwnAlgebraWithoutRestatingIt() {
        val alpha = target("alpha")
        val beta = target("beta", namespace = "main")
        val originals = mapOf("alpha" to listOf("xform/^ng$/n/", "derive/^([zcs])h/\$1/"), "beta" to emptyList())
        val recipe = FuzzyPinyin.make(setOf("an-ang", "z-zh"), listOf(beta, alpha))
        assertEquals(FuzzyPinyin.RECIPE_NAME, recipe.declaredName)
        assertTrue(recipe.installPatterns.isEmpty())
        assertTrue(recipe.downloads.isEmpty())
        assertEquals(listOf("alpha.custom.yaml", "beta.custom.yaml"), recipe.patchTargets.sorted())
        // Insertion keys are what keeps the schema's own rules out of our block.
        val alphaBody = String(recipe.source, Charsets.UTF_8)
        assertTrue(alphaBody.contains("\"speller/algebra/@before 00\": \"fuzz/^zh/z/\""))

        val userCustom = "patch:\n  menu/page_size: 7\n".toByteArray()
        val existing =
            mapOf(
                "alpha.schema.yaml" to source(id = "alpha", algebra = originals.getValue("alpha")),
                "beta.schema.yaml" to source(id = "beta", algebra = emptyList()),
                "alpha.custom.yaml" to userCustom,
            )
        val plan = applyRecipe(recipe, existing)
        val alphaText = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertTrue("user custom content is preserved", alphaText.contains("menu/page_size: 7"))
        assertTrue(alphaText.contains("# Rx: " + FuzzyPinyin.rx("user/pkg") + " {"))
        assertEquals(FuzzyPinyin.rules(setOf("z-zh", "an-ang")), appended(alphaText))
        assertFalse("the schema's own rules are never restated", alphaText.contains("xform/^ng$/n/"))
        assertFalse(alphaText.contains("\$1"))
        assertTrue("rules go in front of the schema's own", alphaText.contains("speller/algebra/@before 00"))
        assertFalse("nothing is appended at the end any more", alphaText.contains("speller/algebra/+"))
        assertEquals(
            FuzzyPinyin.rules(setOf("z-zh", "an-ang")),
            appended(String(plan.files.getValue("beta.custom.yaml"), Charsets.UTF_8)),
        )
        assertTrue(
            "source schema untouched",
            existing.getValue("alpha.schema.yaml").contentEquals(plan.files["alpha.schema.yaml"]),
        )

        // Re-running replaces the managed block instead of stacking a second one.
        val one = FuzzyPinyin.make(setOf("z-zh"), listOf(alpha))
        val replan = applyRecipe(one, plan.files)
        val again = String(replan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertEquals(1, again.split("# Rx: user/pkg:" + FuzzyPinyin.RECIPE_NAME + ":").size - 1)
        assertEquals(FuzzyPinyin.pair("z-zh")!!.rules, appended(again))
        assertTrue(again.contains("menu/page_size: 7"))
        // Our own managed block is never read as a user setting.
        assertFalse(FuzzyPinyin.userOwnsSetting(replan.files.getValue("alpha.custom.yaml"), "user/pkg"))
    }

    /**
     * Input methods are configured separately: one method's pairs must never leak into another's
     * file, and changing one must leave the others byte-for-byte alone.
     */
    @Test
    fun perSchemaPairsKeepEachInputMethodsOwnAssignments() {
        val alpha = target("alpha")
        val beta = target("beta", namespace = "main")
        val recipe =
            FuzzyPinyin.make(
                mapOf("alpha" to setOf("z-zh"), "beta" to setOf("n-l")),
                listOf(beta, alpha),
            )
        assertEquals(listOf("alpha.custom.yaml", "beta.custom.yaml"), recipe.patchTargets.sorted())

        val existing =
            mapOf(
                "alpha.schema.yaml" to source(id = "alpha", algebra = emptyList()),
                "beta.schema.yaml" to source(id = "beta", algebra = emptyList()),
                "beta.custom.yaml" to "patch:\n  menu/page_size: 7\n".toByteArray(),
            )
        val plan = applyRecipe(recipe, existing)
        val alphaText = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        val betaText = String(plan.files.getValue("beta.custom.yaml"), Charsets.UTF_8)
        assertEquals(FuzzyPinyin.pair("z-zh")!!.rules, appended(alphaText))
        assertEquals(FuzzyPinyin.pair("n-l")!!.rules, appended(betaText))
        assertTrue("each input method carries its own pairs", appended(alphaText) != appended(betaText))
        assertTrue("the other method's own content is kept", betaText.contains("menu/page_size: 7"))

        // The user changes alpha only; beta's current assignment is passed through untouched.
        val updated =
            FuzzyPinyin.make(
                mapOf("alpha" to setOf("z-zh", "an-ang"), "beta" to setOf("n-l")),
                listOf(alpha, beta),
            )
        val replan = applyRecipe(updated, plan.files)
        assertTrue(
            "an untouched input method is not rewritten",
            plan.files.getValue("beta.custom.yaml").contentEquals(replan.files["beta.custom.yaml"]),
        )
        val alphaAgain = String(replan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertEquals(FuzzyPinyin.pair("z-zh")!!.rules + FuzzyPinyin.pair("an-ang")!!.rules, appended(alphaAgain))
        assertFalse("alpha never gains beta's pair", alphaAgain.contains("n-l"))

        // Switching one method off appends nothing for it, and only for it.
        val offAlpha =
            FuzzyPinyin.make(
                mapOf("alpha" to emptySet(), "beta" to setOf("n-l")),
                listOf(alpha, beta),
            )
        val offPlan = applyRecipe(offAlpha, replan.files)
        val offAlphaText = String(offPlan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertEquals(emptyList<String>(), appended(offAlphaText))
        assertEquals(
            emptyList<String>(),
            FuzzyPinyin.managedAlgebra(offPlan.files.getValue("alpha.custom.yaml"), "user/pkg"),
        )
        assertEquals(
            FuzzyPinyin.pair("n-l")!!.rules,
            appended(String(offPlan.files.getValue("beta.custom.yaml"), Charsets.UTF_8)),
        )

        // The common form is the degenerate case: every method gets the same list.
        val common = FuzzyPinyin.make(setOf("n-l"), listOf(alpha, beta))
        val commonPlan = applyRecipe(common, emptyMap())
        for (path in listOf("alpha.custom.yaml", "beta.custom.yaml")) {
            assertEquals(
                FuzzyPinyin.pair("n-l")!!.rules,
                appended(String(commonPlan.files.getValue(path), Charsets.UTF_8)),
            )
        }
    }

    @Test
    fun perSchemaPairsRefuseAnIncompleteAssignment() {
        val alpha = target("alpha")
        val beta = target("beta")
        // A schema missing from the map would silently be written as "no pairs", i.e. off.
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(mapOf("alpha" to setOf("z-zh")), listOf(alpha, beta))
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(
                mapOf("alpha" to setOf("z-zh"), "beta" to setOf("n-l"), "gamma" to setOf("n-l")),
                listOf(alpha, beta),
            )
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(emptyMap(), emptyList())
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(mapOf("alpha" to setOf("bogus")), listOf(alpha))
        }
    }

    @Test
    fun recipeRefusesInputsItCannotApply() {
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(setOf("z-zh"), emptyList())
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(setOf("zh-z"), listOf(target("alpha")))
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(setOf("z-zh"), listOf(FuzzyPinyin.Target("../bad", "translator")))
        }
        val companion =
            CompactSchemaAdapter.identifier("alpha", app.songyan.ime.keyboard.layout.CompactFold.PHONE9)
        assertThrows("a compact companion is never patched", RecipeInstallError.InvalidResource::class.java) {
            FuzzyPinyin.make(setOf("z-zh"), listOf(FuzzyPinyin.Target(companion, "translator")))
        }
    }

    // MARK: - Turning pairs off restores the schema exactly

    @Test
    fun disableAppendsNothingAndLeavesTheSchemasRulesAlone() {
        val originals = mapOf("alpha" to listOf("derive/^([zcs])h/\$1/"), "beta" to listOf("erase/^xx$/"))
        val on = FuzzyPinyin.make(FuzzyPinyin.allPairs.map { it.id }.toSet(), listOf(target("alpha"), target("beta")))
        val off = FuzzyPinyin.make(emptySet(), listOf(target("alpha"), target("beta")))
        assertEquals(
            listOf("alpha.custom.yaml", "beta.custom.yaml"),
            on.patchTargets.sorted(),
        )

        // Deploy "everything on" first, so the file we switch off really carries our block.
        val onPlan =
            applyRecipe(
                on,
                mapOf(
                    "alpha.schema.yaml" to source(id = "alpha", algebra = originals.getValue("alpha")),
                    "beta.custom.yaml" to "patch:\n  menu/page_size: 7\n".toByteArray(),
                ),
            )
        assertTrue(
            "the schema really carries our derivations before we switch them off",
            String(onPlan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8).contains("fuzz/"),
        )

        val plan = applyRecipe(off, onPlan.files)
        val alpha = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertEquals("nothing is appended once every pair is off", emptyList<String>(), appended(alpha))
        assertFalse("no leftover derivation of ours", alpha.contains("fuzz/"))
        assertFalse("and the schema's own rule is not restated", alpha.contains("derive/^([zcs])h/\$1/"))
        assertEquals(emptyList<String>(), FuzzyPinyin.managedAlgebra(alpha.toByteArray(), "user/pkg"))
        // The compiled schema the engine then reads is the schema's own algebra, unchanged.
        val beta = String(plan.files.getValue("beta.custom.yaml"), Charsets.UTF_8)
        assertTrue(beta.contains("menu/page_size: 7"))
        assertEquals(emptyList<String>(), appended(beta))
    }

    // MARK: - Compiled algebra

    @Test
    fun compiledAlgebraReadsTheSchemasList() {
        val own = listOf("derive/^([zcs])h/\$1/", "xlit/ü/v/")
        assertEquals(own, FuzzyPinyin.compiledAlgebra(compiledSchema(id = "alpha", algebra = own), "alpha"))
        assertEquals(emptyList<String>(), FuzzyPinyin.compiledAlgebra(compiledSchema(id = "alpha"), "alpha"))
        // Unreadable, mismatched or non-list algebra read as null.
        assertNull(FuzzyPinyin.compiledAlgebra("key: [unterminated".toByteArray(), "alpha"))
        assertNull(FuzzyPinyin.compiledAlgebra(compiledSchema(id = "alpha", algebra = own), "beta"))
        val notAList =
            (
                "schema:\n  schema_id: alpha\nspeller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\n" +
                    "  algebra: 5\n"
            ).toByteArray()
        assertNull(FuzzyPinyin.compiledAlgebra(notAList, "alpha"))
    }

    // MARK: - User ownership and invocation isolation

    @Test
    fun userOwnedAlgebraIsNeverTouched() {
        val own = "patch:\n  speller/algebra: [\"xform/^a$/b/\"]\n".toByteArray()
        assertTrue(FuzzyPinyin.userOwnsSetting(own, "p"))
        assertFalse(FuzzyPinyin.userOwnsSetting("# algebra note\npatch: {}\n".toByteArray(), "p"))
        assertFalse(FuzzyPinyin.userOwnsSetting(null, "p"))
        // Only OUR marker is skipped. Another invocation's block owns its rules exactly as the user
        // does, and patching the same key under it would overwrite those rules.
        val foreign =
            (
                "__patch:\n# Rx: other/pkg:" + FuzzyPinyin.RECIPE_NAME + ": {\n" +
                    "  - patch/+:\n      speller/algebra/+: [\"fuzz/^z(?!h)/zh/\"]\n# }\n"
            ).toByteArray()
        assertTrue(FuzzyPinyin.userOwnsSetting(foreign, "p"))
        assertTrue(FuzzyPinyin.userOwnsSetting(foreign, "other/pkg2"))
        assertFalse(
            "the invocation that wrote that block is not blocked by its own rules",
            FuzzyPinyin.userOwnsSetting(foreign, "other/pkg"),
        )
        // The bare word is not a setting: neither a value nor prose counts.
        assertFalse(FuzzyPinyin.userOwnsSetting("patch:\n  schema/name: algebra\n".toByteArray(), "p"))
        assertFalse(FuzzyPinyin.userOwnsSetting("patch:\n  menu/page_size: 7\n".toByteArray(), "p"))
        assertTrue(
            "flow style still names the key",
            FuzzyPinyin.userOwnsSetting("patch:\n  speller: {algebra: [\"xform/^a$/b/\"]}\n".toByteArray(), "p"),
        )

        val recipe = FuzzyPinyin.make(setOf("z-zh"), listOf(target("alpha")))
        val existing =
            mapOf(
                "alpha.schema.yaml" to source(id = "alpha", algebra = emptyList()),
                "alpha.custom.yaml" to own,
            )
        val plan = applyRecipe(recipe, existing)
        val merged = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertTrue("the user's own algebra survives", merged.contains("xform/^a$/b/"))
        assertTrue(
            "and the schema stays user-managed",
            FuzzyPinyin.userOwnsSetting(plan.files.getValue("alpha.custom.yaml"), "user/pkg"),
        )
    }

    @Test
    fun recipeIdentityIsPerPackageReference() {
        val recipe = FuzzyPinyin.make(setOf("z-zh"), listOf(target("alpha")))
        assertEquals("user/pkg:" + FuzzyPinyin.RECIPE_NAME + ":", FuzzyPinyin.rx("user/pkg"))
        assertTrue(FuzzyPinyin.rx("other/pkg") != FuzzyPinyin.rx("user/pkg"))

        val mine =
            applyRecipe(
                recipe,
                mapOf("alpha.schema.yaml" to source(id = "alpha", algebra = emptyList())),
            )
        val alpha = mine.files.getValue("alpha.custom.yaml")
        assertEquals(FuzzyPinyin.pair("z-zh")!!.rules, FuzzyPinyin.managedAlgebra(alpha, "user/pkg"))
        assertNull(
            "another invocation identity does not read our block as its own",
            FuzzyPinyin.managedAlgebra(alpha, "other/pkg"),
        )
        assertNull(FuzzyPinyin.managedAlgebra(null, "user/pkg"))
        assertEquals(
            setOf("z-zh"),
            FuzzyPinyin.pairIDs(FuzzyPinyin.managedAlgebra(alpha, "user/pkg")!!),
        )
    }

    // MARK: - Save planning

    /** Our managed block for this reference, inserting the given rules, as the recipe writes it. */
    private fun managedBlock(
        rules: List<String>,
        reference: String = "user/pkg",
    ): ByteArray {
        val body =
            if (rules.isEmpty()) {
                "  - patch/+: {}\n"
            } else {
                "  - patch/+:\n" +
                    rules
                        .mapIndexed { index, rule ->
                            "      \"" + FuzzyPinyin.insertKey(index) + "\": \"" + rule + "\"\n"
                        }.joinToString("")
            }
        return ("__patch:\n# Rx: " + FuzzyPinyin.rx(reference) + " {\n" + body + "# }\n").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun planTellsRefusalApartFromNothingToChange() {
        val zzh = FuzzyPinyin.Pair.Z_ZH.rules
        val build =
            buildDirectory(
                enabled = listOf("alpha", "shape"),
                schemas =
                    mapOf(
                        "alpha" to compiledSchema(id = "alpha", algebra = listOf("xform/^a$/b/") + zzh),
                        "shape" to compiledSchema(id = "shape", translators = listOf("table_translator")),
                        "idle" to compiledSchema(id = "idle"),
                    ),
            )

        fun plan(
            id: String,
            pairs: Set<String>,
            custom: Map<String, ByteArray> = emptyMap(),
        ): FuzzyPinyin.SavePlan =
            FuzzyPinyin.plan(
                schemaID = id,
                enabledPairIDs = pairs,
                build = build,
                customFiles = custom,
                packageReference = "user/pkg",
            )
        val ours = mapOf("alpha.custom.yaml" to managedBlock(zzh))
        // Asking for what is already applied is "nothing to change", never a refusal.
        assertEquals(FuzzyPinyin.SavePlan.NoChange, plan("alpha", setOf("z-zh"), ours))
        // Every reason the save cannot be applied is a refusal with a reason to show.
        assertEquals(
            FuzzyPinyin.SavePlan.Refused(FuzzyPinyin.RefusalReason.SCHEMA_NOT_ENABLED),
            plan("idle", setOf("z-zh")),
        )
        assertEquals(
            FuzzyPinyin.SavePlan.Refused(FuzzyPinyin.RefusalReason.NOT_SUPPORTED),
            plan("shape", setOf("z-zh")),
        )
        assertEquals(
            FuzzyPinyin.SavePlan.Refused(FuzzyPinyin.RefusalReason.USER_MANAGED),
            plan(
                "alpha",
                setOf("z-zh"),
                mapOf("alpha.custom.yaml" to "patch:\n  speller/algebra: [\"xform/^a$/b/\"]\n".toByteArray()),
            ),
        )
        // A real change is planned.
        val changed = plan("alpha", setOf("z-zh", "n-l"), ours)
        assertTrue(changed is FuzzyPinyin.SavePlan.Planned)
        changed as FuzzyPinyin.SavePlan.Planned
        assertEquals(target("alpha"), changed.changed)
        assertEquals(mapOf("alpha" to setOf("z-zh", "n-l")), changed.pairIDsBySchema)
        assertEquals(listOf(target("alpha")), changed.targets)
    }

    @Test
    fun aDamagedDeploymentIsReportedAsUnreadableNotAsNotEnabled() {
        val missing = Files.createTempDirectory("fuzzy-missing-")
        try {
            assertEquals(
                FuzzyPinyin.SavePlan.Refused(FuzzyPinyin.RefusalReason.CONFIG_UNREADABLE),
                FuzzyPinyin.plan(
                    schemaID = "alpha",
                    enabledPairIDs = setOf("z-zh"),
                    build = missing,
                    customFiles = emptyMap(),
                    packageReference = "user/pkg",
                ),
            )
            Files.write(missing.resolve("default.yaml"), "broken: [unterminated".toByteArray())
            assertEquals(
                FuzzyPinyin.SavePlan.Refused(FuzzyPinyin.RefusalReason.CONFIG_UNREADABLE),
                FuzzyPinyin.plan(
                    schemaID = "alpha",
                    enabledPairIDs = setOf("z-zh"),
                    build = missing,
                    customFiles = emptyMap(),
                    packageReference = "user/pkg",
                ),
            )
        } finally {
            missing.toFile().deleteRecursively()
        }
    }

    @Test
    fun planKeepsOtherInputMethodsSavedPairs() {
        val build =
            buildDirectory(
                enabled = listOf("alpha", "beta", "gamma"),
                schemas =
                    mapOf(
                        "alpha" to compiledSchema(id = "alpha"),
                        "beta" to compiledSchema(id = "beta", algebra = FuzzyPinyin.Pair.C_CH.rules),
                        "gamma" to compiledSchema(id = "gamma"),
                    ),
            )
        val result =
            FuzzyPinyin.plan(
                schemaID = "alpha",
                enabledPairIDs = setOf("f-h"),
                build = build,
                customFiles = mapOf("beta.custom.yaml" to managedBlock(FuzzyPinyin.Pair.C_CH.rules)),
                packageReference = "user/pkg",
            )
        assertTrue(result is FuzzyPinyin.SavePlan.Planned)
        result as FuzzyPinyin.SavePlan.Planned
        assertEquals(
            mapOf("alpha" to setOf("f-h"), "beta" to setOf("c-ch")),
            result.pairIDsBySchema,
        )
        assertEquals(setOf(target("alpha"), target("beta")), result.targets.toSet())
    }

    @Test
    fun anUnclosedManagedBlockIsTreatedAsUserManaged() {
        // Our block lost its terminator before another invocation's block: the file is damaged, so
        // it must count as user-managed (refuse the save) instead of being rewritten around it.
        val damaged =
            "__patch:\n# Rx: user/pkg:" + FuzzyPinyin.RECIPE_NAME + ": {\n  - patch/+:\n      x: 1\n" +
                "# Rx: other/pkg:other: {\n  - patch/+:\n      \"speller/algebra/+\":\n" +
                "        - \"xform/^a$/b/\"\n# }\n"
        assertTrue(FuzzyPinyin.userOwnsSetting(damaged.toByteArray(), "user/pkg"))
        assertNull(FuzzyPinyin.managedAlgebra(damaged.toByteArray(), "user/pkg"))
    }

    @Test
    fun algebraKeyIsRecognizedQuotedAndAsAListEntry() {
        val algebraLines =
            listOf(
                "\"speller/algebra\": []",
                "'speller/algebra/+': []",
                "- \"speller/algebra/+\": []",
                "- speller/algebra: []",
                "  -  speller/algebra/+: []",
                "\"algebra\": []",
                "speller: {\"algebra\": []}",
            )
        for (line in algebraLines) assertTrue(line, FuzzyPinyin.touchesAlgebraKey(line))
        val unrelatedLines =
            listOf(
                "\"speller/alphabet\": abc",
                "- \"schema/name\": algebra",
                "\"algebra_note\": x",
                "- patch/+:",
                "\"speller/algebra",
            )
        for (line in unrelatedLines) assertFalse(line, FuzzyPinyin.touchesAlgebraKey(line))
        assertTrue(
            FuzzyPinyin.userOwnsSetting(
                "__patch:\n  - \"speller/algebra/+\": [\"xform/^a$/b/\"]\n".toByteArray(),
                "p",
            ),
        )
    }
}

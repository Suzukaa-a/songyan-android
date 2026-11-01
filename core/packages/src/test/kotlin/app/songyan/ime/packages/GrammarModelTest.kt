package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

/**
 * The pure parts of the iOS `GrammarModelManagementTests` plus the `GrammarModel` path and install
 * rules. The cases that drive a whole recipe-plan transaction (`managedGrammar`, restore plans,
 * installed recipe sources) belong to the import pipeline and are not ported here.
 */
class GrammarModelTest {
    private fun invalid(block: () -> Unit) {
        try {
            block()
        } catch (_: RecipeInstallError.InvalidResource) {
            return
        }
        fail("expected InvalidResource")
    }

    @Test
    fun modelPathRules() {
        assertTrue(GrammarModelPath.isValid("one.gram"))
        assertTrue(GrammarModelPath.isValid("models/中文模型.gram"))
        assertFalse(GrammarModelPath.isValid(".gram"))
        assertFalse(GrammarModelPath.isValid("/abs.gram"))
        assertFalse(GrammarModelPath.isValid("a/../b.gram"))
        assertFalse(GrammarModelPath.isValid("a//b.gram"))
        assertFalse(GrammarModelPath.isValid("a:b.gram"))
        assertFalse(GrammarModelPath.isValid("a\\b.gram"))
        assertFalse(GrammarModelPath.isValid("a\tb.gram"))
        assertFalse("format characters count as control characters", GrammarModelPath.isValid("a​b.gram"))
        assertFalse(GrammarModelPath.isValid("model.gram.txt"))
        assertEquals("zh.gram", GrammarModelPath.path("zh"))
        assertNull(GrammarModelPath.path("a/../b"))
    }

    @Test
    fun installPathKeepsCompatibleNamesAndHashesTheRest() {
        assertEquals("one.gram", GrammarModelRecipe.installPath("one.gram"))
        // Only a lowercase `.gram` suffix is a literal model path, so an uppercase suffix is renamed.
        assertTrue(requireNotNull(GrammarModelRecipe.installPath("ONE.GRAM")).startsWith("model-"))
        assertNull(GrammarModelRecipe.installPath(".gram"))
        assertNull(GrammarModelRecipe.installPath("model.bin"))
        for (name in listOf("my model.gram", "a'b.gram", "a#b.gram", "a/b.gram", "a*.gram")) {
            val path = requireNotNull(GrammarModelRecipe.installPath(name))
            assertTrue(path, Regex("^model-[0-9a-f]{20}\\.gram$").matches(path))
            assertEquals("stable for the same name", path, GrammarModelRecipe.installPath(name))
        }
    }

    @Test
    fun recipeInstallsOnceAndBindsEachNamedSchemaSeparately() {
        val recipe = GrammarModelRecipe.make("one.gram", listOf("b", "a"), clearingSchemaIDs = listOf("c", "a"))
        val text = String(recipe.source)
        assertEquals("songyan-models/model-one", recipe.declaredName)
        assertEquals(listOf("one.gram"), recipe.installPatterns)
        assertEquals(listOf("a.custom.yaml", "b.custom.yaml", "c.custom.yaml"), recipe.patchTargets)
        assertTrue(text.contains("  a.custom.yaml:\n    - patch/+:\n        grammar/language: \"one\"\n"))
        assertTrue(
            "a schema that is both bound and cleared is bound",
            !text.contains("  a.custom.yaml:\n    - patch/+:\n        grammar/language: \"\""),
        )
        assertTrue(text.contains("  c.custom.yaml:\n    - patch/+:\n        grammar/language: \"\"\n"))
        invalid { GrammarModelRecipe.make("one.gram", listOf("bad id")) }
        invalid { GrammarModelRecipe.make("my model.gram", listOf("a")) }
        // An empty change set installs the file without binding an input method.
        val resourceOnly = GrammarModelRecipe.make("one.gram", emptyList())
        assertEquals(emptyList<String>(), resourceOnly.patchTargets)
    }

    @Test
    fun scalarShapedLanguageNamesRemainExactStrings() {
        for (name in listOf("true", "false", "123", "2026-01-01")) {
            val schema = "grammar: {language: $name}\nengine: {translators: [script_translator]}\n".toByteArray()
            assertEquals(name, GrammarBinding.read("scalar", schema).language)
            val recipe = GrammarModelRecipe.make("$name.gram", listOf("a"))
            assertTrue(String(recipe.source).contains("grammar/language: \"$name\""))
        }
    }

    @Test
    fun bindingSupportUsesActualTranslatorNamespaceAndDoesNotForceContextualSuggestions() {
        val fixtures =
            listOf(
                Triple(
                    "script",
                    "engine: {translators: [script_translator]}\ntranslator: {contextual_suggestions: false}\n",
                    GrammarBinding.Support.SUPPORTED,
                ),
                Triple(
                    "table",
                    "engine: {translators: [table_translator]}\ntranslator: {enable_sentence: false, sentence_over_completion: false, contextual_suggestions: false}\n",
                    GrammarBinding.Support.UNSUPPORTED,
                ),
                Triple(
                    "named",
                    "engine: {translators: [table_translator@words]}\nwords: {enable_sentence: true}\ntranslator: {enable_sentence: false}\n",
                    GrammarBinding.Support.SUPPORTED,
                ),
                Triple("custom", "engine: {translators: [lua_translator@custom]}\n", GrammarBinding.Support.UNKNOWN),
                Triple("empty", "engine: {translators: ['']}\n", GrammarBinding.Support.UNKNOWN),
            )
        val directory = Files.createTempDirectory("grammar-binding")
        try {
            for ((id, text, support) in fixtures) {
                Files.write(directory.resolve("$id.schema.yaml"), text.toByteArray())
                val result = GrammarBinding.read(id, directory)
                assertEquals(id, support, result.support)
                assertFalse(result.contextualSuggestions)
            }
            invalid { GrammarBinding.read("bad id", directory) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun bindingReadsTheRealLanguageAndMatchesTheModelStem() {
        val binding =
            GrammarBinding.read(
                "a",
                "grammar: {language: upstream, collocation_max_length: 7}\ntranslator: {contextual_suggestions: true}\n"
                    .toByteArray(),
            )
        assertEquals("upstream", binding.language)
        assertTrue(binding.contextualSuggestions)
        assertTrue(binding.binds("upstream.gram"))
        assertFalse(binding.binds("other.gram"))
        assertNull(GrammarBinding.read("b", "schema: {schema_id: b}\n".toByteArray()).language)
    }

    @Test
    fun displayNameIsReadFromTheRecipeFirstLine() {
        val recipe = GrammarModelRecipe.make("one.gram", listOf("a"), displayName = "中文 模型")
        val source = recipe.source
        assertTrue(
            String(source).startsWith(
                "# model-display: " + Base64.getEncoder().encodeToString("中文 模型".toByteArray()),
            ),
        )
        assertEquals(
            "中文 模型",
            GrammarModelRecipe.displayName(
                "one.gram",
                mapOf(
                    "songyan-models/model-one.recipe.yaml" to source,
                ),
            ),
        )
        assertEquals("中文 模型", GrammarModelRecipe.displayName("one.gram", mapOf("model-one.recipe.yaml" to source)))
        assertNull(GrammarModelRecipe.displayName("one.gram", null))
        assertNull(
            GrammarModelRecipe.displayName("one.gram", mapOf("model-one.recipe.yaml" to "recipe:\n".toByteArray())),
        )
        val noName = GrammarModelRecipe.make("one.gram", listOf("a")).source
        assertNull(GrammarModelRecipe.displayName("one.gram", mapOf("model-one.recipe.yaml" to noName)))
    }

    @Test
    fun disablingAndRestoringRecipesAreRejectedForEmptyOrInvalidSchemas() {
        val disable = GrammarModelRecipe.disabling(listOf("b", "a", "a"))
        assertTrue(disable.installPatterns.isEmpty())
        assertEquals(listOf("a.custom.yaml", "b.custom.yaml"), disable.patchTargets)
        assertTrue(String(disable.source).contains("grammar/language: \"\""))
        invalid { GrammarModelRecipe.disabling(emptyList()) }
        invalid { GrammarModelRecipe.disabling(listOf("bad id")) }
        val restore = GrammarModelRecipe.restorationRecipe(listOf("b", "a"))
        assertTrue(String(restore.source).startsWith("# restore-grammar: a,b\n"))
        invalid { GrammarModelRecipe.restorationRecipe(emptyList()) }
    }

    private fun block(
        rx: String,
        language: String,
    ) = "# Rx: $rx {\n  - patch/+:\n      grammar/language: $language\n# }\n"

    @Test
    fun removingAManagedBlockKeepsTheUserPatchAndANeutralFile() {
        val override =
            GrammarManagedOverride(
                "a",
                "test:songyan-models/model-one:",
                "  - patch/+:\n      grammar/language: \"one\"\n",
            )
        val user = "# my comment\npatch:\n  translator/contextual_suggestions: false\n"
        val withBlock = user + "__patch:\n" + "# Rx: ${override.rx} {\n${override.body}# }\n"
        val removed = String(override.removing(withBlock.toByteArray()))
        assertEquals(user, removed)
        // Nothing else left: a neutral empty mapping, so a copied user custom is not resurrected.
        val only = "__patch:\n# Rx: ${override.rx} {\n${override.body}# }\n"
        assertEquals("{}\n", String(override.removing(only.toByteArray())).let { it.substring(it.length - 3) })
        // An edited block, a duplicate block, CRLF or a missing trailing newline are never touched.
        invalid { override.removing(withBlock.replace("\"one\"", "user-changed").toByteArray()) }
        invalid { override.removing((withBlock + withBlock).toByteArray()) }
        invalid { override.removing(withBlock.replace("\n", "\r\n").toByteArray()) }
        invalid { override.removing(withBlock.trimEnd('\n').toByteArray()) }
    }

    @Test
    fun restorationOnlyRemovesOwnedRecordsAndRefusesUnknownOrEditedSettings() {
        val one =
            GrammarManagedOverride(
                "a",
                "test:songyan-models/model-one:",
                "  - patch/+:\n      grammar/language: \"one\"\n",
            )
        val two =
            GrammarManagedOverride(
                "b",
                "test:songyan-models/model-one:",
                "  - patch/+:\n      grammar/language: \"one\"\n",
            )
        val files =
            mapOf(
                "a.custom.yaml" to ("__patch:\n# Rx: ${one.rx} {\n${one.body}# }\n").toByteArray(),
                "b.custom.yaml" to ("__patch:\n# Rx: ${two.rx} {\n${two.body}# }\n").toByteArray(),
            )
        val records = listOf(two, one)
        assertEquals(listOf(one, two), GrammarModelRecipe.overrides(records, files))
        assertEquals(listOf(one), GrammarModelRecipe.restorationOverrides(listOf("a"), records, files, "test"))
        // A schema with no recorded model needs no restore.
        invalid { GrammarModelRecipe.restorationOverrides(listOf("c"), records, files, "test") }
        // Another package's model prefix is never removed and never blocks a restore.
        val foreign =
            "__patch:\n# Rx: foreign:model-one: {\n  - patch/+:\n      grammar/language: foreign\n# }\n"
                .toByteArray()
        assertEquals(
            listOf(one),
            GrammarModelRecipe.restorationOverrides(
                listOf("a"),
                records,
                files + ("a.custom.yaml" to files.getValue("a.custom.yaml") + foreign),
                "test",
            ),
        )
        // Our own prefix with a block the records do not know is refused.
        val unknown =
            ("__patch:\n# Rx: test:model-old: {\n  - patch/+:\n      grammar/language: old\n# }\n")
                .toByteArray()
        invalid {
            GrammarModelRecipe.restorationOverrides(
                listOf("a"),
                records,
                files + ("a.custom.yaml" to files.getValue("a.custom.yaml") + unknown),
                "test",
            )
        }
        // An edited block fails verification.
        val edited = String(files.getValue("a.custom.yaml")).replace("\"one\"", "user-changed").toByteArray()
        invalid {
            GrammarModelRecipe.restorationOverrides(
                listOf("a"),
                records,
                files + ("a.custom.yaml" to edited),
                "test",
            )
        }
        assertNotNull(block("x", "y"))
    }
}

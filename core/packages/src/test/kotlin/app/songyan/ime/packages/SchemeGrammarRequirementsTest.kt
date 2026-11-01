package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Cases ported from the iOS grammar reference projection tests. */
class SchemeGrammarRequirementsTest {
    private val model = "wanxiang-lts-zh-hans.gram"
    private val entry = "schema: {schema_id: main, name: Projection, version: '1'}\n"

    private fun discover(files: Map<String, String>) =
        SchemeGrammarRequirements.discover(files.keys) { files[it]?.toByteArray() }

    private val scalar =
        mapOf(
            "default.yaml" to "schema_list: [{schema: main}]\n",
            "main.schema.yaml" to entry + "grammar:\n  language:\n    __include: grammar:/language\n",
            "grammar.yaml" to "language: wanxiang-lts-zh-hans\n",
        )

    private val custom =
        mapOf(
            "default.yaml" to "schema_list: [{schema: main}]\n",
            "main.schema.yaml" to "__include: base.schema:/\n" + entry,
            "base.schema.yaml" to "schema: {schema_id: base, name: Base, version: '1'}\n",
            "base.custom.yaml" to "patch:\n  grammar/language: wanxiang-lts-zh-hans\n",
        )

    @Test
    fun aLanguageIncludedFromAnotherFileIsFound() {
        val found = discover(scalar)
        assertEquals(listOf(model), found.paths)
        assertEquals(emptyList<String>(), found.diagnostics)
    }

    @Test
    fun aLanguagePatchedInAnIncludedSchemesCustomFileIsFound() {
        val found = discover(custom)
        assertEquals(listOf(model), found.paths)
        assertEquals(emptyList<String>(), found.diagnostics)
    }

    @Test
    fun onlyTheEnabledInputMethodsCountNotInventoryFilesOrRecipes() {
        val unrelated =
            custom +
                mapOf(
                    "main.schema.yaml" to entry,
                    "inactive.schema.yaml" to scalar.getValue("main.schema.yaml"),
                    "unused.recipe.yaml" to
                        "recipe:\n  Rx: unused\npatch_files:\n  main.custom.yaml:\n    - patch/+:\n" +
                        "        grammar/language: unselected\n",
                )
        assertEquals(emptyList<String>(), discover(unrelated).paths)
    }

    @Test
    fun theReferencingSchemasCustomFileOverridesTheBaseCustomFile() {
        val overridden = custom + ("main.custom.yaml" to "patch:\n  grammar/language: ''\n")
        assertEquals(emptyList<String>(), discover(overridden).paths)
    }

    @Test
    fun aChainOfIncludesEndingInAScalarIsFollowed() {
        val chain =
            scalar +
                mapOf(
                    "grammar.yaml" to "language:\n  __include: language:/name\n",
                    "language.yaml" to "name: wanxiang-lts-zh-hans\n",
                )
        assertEquals(listOf(model), discover(chain).paths)
    }

    @Test
    fun anIncludeCycleIsADiagnosticNotASilentAbsence() {
        val files =
            mapOf(
                "default.yaml" to "schema_list: [{schema: main}]\n",
                "main.schema.yaml" to entry + "grammar:\n  language:\n    __include: grammar:/language\n",
                "grammar.yaml" to "language:\n  __include: grammar:/language\n",
            )
        val found = discover(files)
        assertEquals(emptyList<String>(), found.paths)
        assertFalse(found.diagnostics.isEmpty())
        val mapCycle =
            files +
                mapOf(
                    "main.schema.yaml" to entry + "grammar:\n  __include: grammar:/loop\n",
                    "grammar.yaml" to "loop:\n  __include: grammar:/loop\n",
                )
        assertFalse(discover(mapCycle).diagnostics.isEmpty())
    }

    @Test
    fun withoutADefaultFileEveryRootSchemaIsConsidered() {
        val files =
            mapOf(
                "main.schema.yaml" to entry + "grammar:\n  language: wanxiang-lts-zh-hant\n",
            )
        assertEquals(listOf("wanxiang-lts-zh-hant.gram"), discover(files).paths)
    }

    @Test
    fun aLanguageThatIsNotAValidModelNameIsADiagnostic() {
        val files =
            mapOf(
                "default.yaml" to "schema_list: [{schema: main}]\n",
                "main.schema.yaml" to entry + "grammar:\n  language: ../escape\n",
            )
        val found = discover(files)
        assertTrue(found.paths.isEmpty())
        assertFalse(found.diagnostics.isEmpty())
    }

    @Test
    fun onlyTheKnownModelsHaveASuggestedDownload() {
        assertEquals(
            "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
            SchemeGrammarRequirements.suggestedUrl("wanxiang-lts-zh-hans.gram"),
        )
        assertNull(SchemeGrammarRequirements.suggestedUrl("other.gram"))
    }

    @Test
    fun aSourceFolderIsMissingTheModelItNamesUntilItHoldsTheFile() {
        val root = Files.createTempDirectory("grammar-requirements").toFile()
        try {
            for ((path, text) in scalar) File(root, path).apply { parentFile.mkdirs() }.writeText(text)
            assertEquals(listOf(model), SchemeGrammarRequirements.missingModels(root))
            File(root, model).writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(emptyList<String>(), SchemeGrammarRequirements.missingModels(root))
        } finally {
            root.deleteRecursively()
        }
    }
}

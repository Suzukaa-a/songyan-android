package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

/** Ported from the iOS `PredictionTests`; the scheme-package import cases belong to the import pipeline. */
class PredictionTest {
    private fun compiled(
        id: String,
        processors: List<String> = listOf("speller", "selector", "express_editor"),
        translators: List<String> = listOf("script_translator"),
        alphabet: String? = "zyxwvutsrqponmlkjihgfedcba",
        extra: String = "",
    ): ByteArray {
        val lines = mutableListOf("schema:", "  schema_id: $id", "  name: Test", "engine:", "  processors:")
        lines += processors.map { "    - \"$it\"" }
        lines.add("  translators:")
        lines += translators.map { "    - \"$it\"" }
        lines.add("speller:")
        if (alphabet != null) lines.add("  alphabet: $alphabet")
        lines += listOf("translator:", "  dictionary: test_dict")
        if (extra.isNotEmpty()) lines.add(extra)
        return (lines.joinToString("\n") + "\n").toByteArray()
    }

    /** Byte layout of librime-predict `predict::Metadata`, with both regions right after the header. */
    private fun database(
        keyUnits: Int = 4,
        valueBytes: Int = 16,
        format: String = "Rime::Predict/1.0",
        truncate: Int = 0,
    ): ByteArray {
        val bytes = ByteArray(52)
        format.toByteArray().take(31).forEachIndexed { i, b -> bytes[i] = b }

        fun put(
            value: Int,
            at: Int,
        ) {
            for (i in 0 until 4) bytes[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        val keyStart = 52
        val valueStart = 52 + keyUnits * 4
        put(keyStart - 36, 36)
        put(keyUnits, 40)
        put(valueStart - 44, 44)
        put(valueBytes, 48)
        val all = bytes + ByteArray(keyUnits * 4 + valueBytes) { 1 }
        return all.copyOf(all.size - truncate)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
        } catch (_: RecipeInstallError.InvalidResource) {
            return
        }
        fail("expected InvalidResource")
    }

    @Test
    fun databaseHeaderIsStructurallyChecked() {
        val valid = database()
        Prediction.validateHeader(valid.copyOf(52), valid.size.toLong())
        val bad = database(format = "Rime::Table/4.0")
        assertInvalid { Prediction.validateHeader(bad.copyOf(52), bad.size.toLong()) }
        val truncated = database(truncate = 1)
        assertInvalid { Prediction.validateHeader(truncated.copyOf(52), truncated.size.toLong()) }
        val emptyTrie = database(keyUnits = 0)
        assertInvalid { Prediction.validateHeader(emptyTrie.copyOf(52), emptyTrie.size.toLong()) }
        assertInvalid { Prediction.validateHeader("Rime::Predict/".toByteArray(), 14) }

        val file = Files.createTempFile("predict", ".db")
        try {
            Files.write(file, valid)
            Prediction.validateDatabase(file)
            Files.write(file, ByteArray(128) { 7 })
            assertInvalid { Prediction.validateDatabase(file) }
            assertInvalid { Prediction.validateDatabases(mapOf("predict.db" to file)) }
            Prediction.validateDatabases(mapOf("not_a_db.txt" to file))
            val directory = Files.createTempDirectory("predict-dir")
            try {
                assertInvalid { Prediction.validateDatabase(directory) }
            } finally {
                Files.delete(directory)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun eligibilityIsSchemeAgnosticAndFailsClosed() {
        assertEquals(
            Prediction.Eligibility.Eligible(Prediction.Target("any")),
            Prediction.eligibility(compiled("any"), "any"),
        )
        assertTrue(Prediction.eligibility(compiled("t", translators = listOf("table_translator")), "t").target != null)
        assertTrue(
            "a native 9-key (digit) scheme predicts too",
            Prediction.eligibility(compiled("nine", alphabet = "23456789"), "nine").target != null,
        )
        assertNull(Prediction.eligibility(compiled("n", translators = listOf("echo_translator")), "n").target)
        assertNull(Prediction.eligibility(compiled("a"), "b").target)
        val companion = CompactSchemaAdapter.identifier("any", CompactFold.MERGED14)
        assertNull(Prediction.eligibility(compiled(companion), companion).target)
        assertNull(Prediction.eligibility(compiled("c", extra = "keyboard_adapter:\n  version: 1"), "c").target)
    }

    @Test
    fun schemesWithTheirOwnPredictionAreLeftAlone() {
        assertEquals(
            Prediction.Eligibility.SourceManaged,
            Prediction.eligibility(compiled("p", processors = listOf("predictor", "speller")), "p"),
        )
        assertEquals(
            Prediction.Eligibility.SourceManaged,
            Prediction.eligibility(compiled("q", translators = listOf("predict_translator", "script_translator")), "q"),
        )
        assertEquals(
            Prediction.Eligibility.SourceManaged,
            Prediction.eligibility(compiled("r", processors = listOf("predictor@custom", "speller")), "r"),
        )
        // Our own managed block is not "the scheme's own" prediction.
        val ours =
            compiled(
                "o",
                processors = listOf("predictor", "speller"),
                translators = listOf("predict_translator", "script_translator"),
                extra = "songyan_prediction: true\npredictor:\n  db: songyan-predict.db",
            )
        assertEquals(Prediction.Eligibility.Eligible(Prediction.Target("o")), Prediction.eligibility(ours, "o"))
        assertTrue(Prediction.compiledEnabled(ours))
        assertFalse(
            Prediction.compiledEnabled(
                compiled("x", processors = listOf("predictor"), translators = listOf("predict_translator")),
            ),
        )
    }

    @Test
    fun recipeWiresComponentsInstallsDatabaseAndDisablesByReplacement() {
        val recipe =
            Prediction.make(
                enabling = listOf(Prediction.Target("b"), Prediction.Target("a")),
                disabling = listOf(Prediction.Target("c")),
                installDatabase = true,
            )
        val text = String(recipe.source)
        assertEquals(listOf(Prediction.DATABASE_PATH), recipe.installPatterns)
        assertEquals(listOf("a.custom.yaml", "b.custom.yaml", "c.custom.yaml"), recipe.patchTargets)
        assertTrue(text.contains("engine/processors/@before 0: predictor"))
        assertTrue(text.contains("engine/translators/@before 0: predict_translator"))
        assertTrue(text.contains("switches/@next: {name: prediction, reset: 1}"))
        assertTrue(text.contains("predictor/db: songyan-predict.db"))
        assertTrue(text.contains("  c.custom.yaml:\n    - patch/+:\n        songyan_prediction: false\n"))
        assertInvalid { Prediction.make(enabling = emptyList()) }
        assertInvalid {
            Prediction.make(
                enabling = listOf(Prediction.Target(CompactSchemaAdapter.identifier("a", CompactFold.PHONE9))),
            )
        }
        Prediction.make(enabling = emptyList(), installDatabase = true)

        // The patch body is valid for the shared managed-block writer; re-running replaces it.
        val on = Prediction.make(enabling = listOf(Prediction.Target("a")))
        val applied = applyRecipe(on, mapOf("a.custom.yaml" to "patch:\n  menu/page_size: 7\n".toByteArray()))
        val custom = String(requireNotNull(applied.files["a.custom.yaml"]))
        assertTrue(custom.contains("# Rx: pkg:prediction: {") || custom.contains("# Rx: user/pkg:prediction: {"))
        val off = Prediction.make(enabling = emptyList(), disabling = listOf(Prediction.Target("a")))
        val disabled = String(requireNotNull(applyRecipe(off, applied.files).files["a.custom.yaml"]))
        assertFalse(disabled.contains("predictor"))
        assertTrue(disabled.contains("menu/page_size: 7"))
        assertFalse(Prediction.userOwnsSetting(applied.files["a.custom.yaml"], "user/pkg"))
        assertTrue(
            Prediction.userOwnsSetting("patch:\n  engine/processors/@before 0: predictor\n".toByteArray(), "pkg"),
        )
    }

    /** Companions are generated from the compiled source and keep its prediction wiring unchanged. */
    @Test
    fun companionsInheritThePredictionWiring() {
        val source =
            compiled(
                "src",
                processors = listOf("predictor", "speller", "selector", "express_editor"),
                translators = listOf("predict_translator", "script_translator"),
                extra =
                    "songyan_prediction: true\npredictor:\n  db: songyan-predict.db\n  max_candidates: 8\n" +
                        "switches:\n  - name: prediction\n    reset: 1",
            )
        assertTrue(Prediction.compiledEnabled(source))
        for (nine in listOf(false, true)) {
            val fold = if (nine) CompactFold.PHONE9 else CompactFold.MERGED14
            val data = CompactSchemaAdapter.generate(source, "src", fold, displayCapability = null)
            assertTrue("nineKey=$nine", Prediction.compiledEnabled(data))
            val engine = requireNotNull(YamsYaml.loadMap(String(data))?.get("engine").asStringMap())
            assertEquals("predictor", engine["processors"].asStringList()?.first())
            assertEquals("predict_translator", engine["translators"].asStringList()?.first())
            assertNull(
                "a companion is never patched itself",
                Prediction.eligibility(data, CompactSchemaAdapter.identifier("src", fold)).target,
            )
        }
        val plain = CompactSchemaAdapter.generate(compiled("src"), "src", CompactFold.PHONE9, displayCapability = null)
        assertFalse(String(plain).contains("predict"))
        assertTrue(RecipeResourceBudget.isModel(Prediction.DATABASE_PATH))
    }

    @Test
    fun rootLevelDatabasesAreFileResources() {
        assertTrue(RecipeResourceBudget.isModel("predict.db"))
        assertTrue(Prediction.isDatabasePath("my_predict.db"))
        assertFalse(Prediction.isDatabasePath("lua/predict.db"))
        assertFalse(Prediction.isDatabasePath(".db"))
        assertFalse(Prediction.isDatabasePath("predict.db.yaml"))
    }

    @Test
    fun sameWiringAndChangesFollowTheComponents() {
        assertTrue(Prediction.sameWiring(listOf("predictor@x", "speller"), listOf("predictor", "selector")))
        assertFalse(Prediction.sameWiring(listOf("predictor"), listOf("speller")))
        val targets = listOf(Prediction.Target("b"), Prediction.Target("a"), Prediction.Target("c"))
        assertEquals(
            listOf(Prediction.Target("a")),
            Prediction.changes(true, targets, current = mapOf("b" to true), userManaged = setOf("c")),
        )
    }
}

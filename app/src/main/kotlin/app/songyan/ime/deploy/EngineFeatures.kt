package app.songyan.ime.deploy

import android.util.Log
import app.songyan.ime.packages.BoundedYaml
import app.songyan.ime.packages.CompactSchemaAdapter
import app.songyan.ime.packages.DeclarativeRecipe
import app.songyan.ime.packages.EngineFeatureReport
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.packages.EngineFeatureSettings
import app.songyan.ime.packages.FuzzyPinyin
import app.songyan.ime.packages.GrammarBinding
import app.songyan.ime.packages.GrammarModelRecipe
import app.songyan.ime.packages.LuaComponents
import app.songyan.ime.packages.Prediction
import app.songyan.ime.packages.RecipeInstallError
import app.songyan.ime.packages.TypoCorrection
import java.io.File

/**
 * Applies [EngineFeatureSettings] to the input methods of one deployment, on its still open engine.
 *
 * Each input method's freshly compiled schema decides what it can take (the same eligibility rules
 * as iOS). The patches go into `<id>.custom.yaml` in the work directory as managed recipe blocks,
 * on top of the custom file librime would otherwise apply (the user's own, else the package's), so the stored source tree stays
 * as imported. A patched input method is then compiled again with librime's `deploy_schema`, and
 * the result is read back into the build's [EngineFeatureReport], which is what the settings screen
 * shows. A feature the package's own custom file already sets is left to the package.
 *
 * [predictionDatabase] and [grammarModels] say which feature files the deployment could link into
 * its work directory; a choice naming a missing file is not applied.
 */
internal class EngineFeatures(
    private val settings: EngineFeatureSettings,
    private val sourceDir: File,
    private val workDir: File,
    private val schemaIds: List<String>,
    private val predictionDatabase: Boolean,
    private val grammarModels: Set<String>,
) {
    /** Patches and recompiles; returns the input methods whose compiled schema changed. */
    suspend fun apply(engine: DeployedEngine): Set<String> {
        val report = LinkedHashMap<String, EngineFeatureReport.SchemaFeatures>()
        val changed = LinkedHashSet<String>()
        for (id in schemaIds.distinct()) {
            if (CompactSchemaAdapter.isGeneratedCompanionID(id)) continue
            val compiledFile = File(engine.compiledDir, "$id.schema.yaml")
            if (!compiledFile.isFile) continue
            val plan = plan(id, compiledFile.readBytes(), engine)
            if (plan.recipes.isNotEmpty() && recompile(id, plan.recipes, engine)) changed += id
            report[id] = plan.report(compiledFile.readBytes())
        }
        File(engine.compiledDir, EngineFeatureReport.FILE_NAME)
            .writeText(EngineFeatureReport(schemas = report).encode(), Charsets.UTF_8)
        return changed
    }

    private inner class Plan(
        val id: String,
        val typo: Availability,
        val typoTarget: TypoCorrection.Target?,
        val fuzzy: Availability,
        val fuzzyRules: List<String>,
        val prediction: Availability,
        val lua: Availability,
        val grammar: Availability,
        val recipes: List<DeclarativeRecipe>,
    ) {
        /** What the final compiled schema does. */
        fun report(compiled: ByteArray): EngineFeatureReport.SchemaFeatures {
            val algebra = FuzzyPinyin.compiledAlgebra(compiled, id).orEmpty()
            val fuzzyApplied = fuzzyRules.isNotEmpty() && algebra.take(fuzzyRules.size) == fuzzyRules
            val luaState = LuaComponents.state(compiled, id)
            return EngineFeatureReport.SchemaFeatures(
                typoCorrection = typo,
                typoCorrectionOn = typoTarget?.let { TypoCorrection.compiledValue(compiled, it) } == true,
                fuzzyPinyin = fuzzy,
                fuzzyPairs = if (fuzzyApplied) FuzzyPinyin.pairIDs(fuzzyRules).orEmpty().toList() else emptyList(),
                prediction = prediction,
                predictionOn = Prediction.compiledEnabled(compiled),
                lua = lua,
                luaComponents =
                    luaState?.components.orEmpty().map {
                        EngineFeatureReport.LuaComponent(it.list, it.prescription, it.displayName, it.enabled)
                    },
                grammar = grammar,
                grammarLanguage =
                    try {
                        GrammarBinding.read(id, compiled).language
                    } catch (_: RecipeInstallError) {
                        null
                    },
            )
        }
    }

    private suspend fun plan(
        id: String,
        compiled: ByteArray,
        engine: DeployedEngine,
    ): Plan {
        val packageCustom = customFile(id)?.readBytes()
        val recipes = ArrayList<DeclarativeRecipe>()

        var typo = Availability.UNSUPPORTED
        var typoTarget: TypoCorrection.Target? = null
        val typoEligibility = TypoCorrection.eligibility(compiled, id)
        if (typoEligibility is TypoCorrection.Eligibility.Eligible) {
            typoTarget = typoEligibility.target
            if (TypoCorrection.userOwnsSetting(packageCustom, REFERENCE)) {
                typo = Availability.SCHEME
            } else {
                typo = Availability.AVAILABLE
                val current = TypoCorrection.compiledValue(compiled, typoEligibility.target) ?: false
                if (current != settings.typoCorrection) {
                    recipes +=
                        if (settings.typoCorrection) {
                            TypoCorrection.make(enabling = listOf(typoEligibility.target))
                        } else {
                            TypoCorrection.make(enabling = emptyList(), disabling = listOf(typoEligibility.target))
                        }
                }
            }
        }

        var fuzzy = Availability.UNSUPPORTED
        var fuzzyRules = emptyList<String>()
        val fuzzyTarget = FuzzyPinyin.eligibility(compiled, id).target
        if (fuzzyTarget != null) {
            val algebra = FuzzyPinyin.compiledAlgebra(compiled, id)
            fuzzy =
                when {
                    FuzzyPinyin.userOwnsSetting(packageCustom, REFERENCE) -> Availability.SCHEME
                    algebra == null || algebra.any(::isFuzzyRule) -> Availability.SCHEME
                    !isFullPinyin(id, engine) -> Availability.UNSUPPORTED
                    else -> Availability.AVAILABLE
                }
            val pairs = settings.fuzzyPairs(id)
            if (fuzzy == Availability.AVAILABLE && pairs.isNotEmpty()) {
                fuzzyRules = FuzzyPinyin.rules(pairs).orEmpty()
                recipes += FuzzyPinyin.make(mapOf(id to pairs), listOf(fuzzyTarget))
            }
        }

        // Lua goes before prediction: its block assigns whole engine lists, prediction inserts into them.
        var lua = Availability.UNSUPPORTED
        val luaState = LuaComponents.state(compiled, id)
        if (luaState != null && luaState.components.isNotEmpty()) {
            lua = if (LuaComponents.userOwnsSetting(packageCustom)) Availability.SCHEME else Availability.AVAILABLE
            val known = luaState.components.map { it.prescription }.toSet()
            val disabled =
                settings.disabledLua[id]
                    .orEmpty()
                    .filter { it in known }
                    .toSet()
            if (lua == Availability.AVAILABLE && disabled.isNotEmpty()) {
                recipes += LuaComponents.make(id, luaState.baseline, disabled)
            }
        }

        val prediction =
            when (val eligibility = Prediction.eligibility(compiled, id)) {
                is Prediction.Eligibility.Eligible ->
                    if (Prediction.userOwnsSetting(packageCustom, REFERENCE)) {
                        Availability.SCHEME
                    } else {
                        if (settings.prediction && predictionDatabase) {
                            recipes += Prediction.make(enabling = listOf(eligibility.target))
                        }
                        Availability.AVAILABLE
                    }
                Prediction.Eligibility.SourceManaged -> Availability.SCHEME
                else -> Availability.UNSUPPORTED
            }

        var grammar = Availability.UNSUPPORTED
        val binding =
            try {
                GrammarBinding.read(id, compiled)
            } catch (_: RecipeInstallError) {
                null
            }
        if (binding?.support == GrammarBinding.Support.SUPPORTED) {
            grammar = if (touchesGrammar(packageCustom)) Availability.SCHEME else Availability.AVAILABLE
            val choice = settings.grammar[id]
            if (grammar == Availability.AVAILABLE && choice != null) {
                val model = choice.model
                when {
                    model == null -> recipes += GrammarModelRecipe.disabling(listOf(id))
                    model in grammarModels -> recipes += GrammarModelRecipe.make(model, listOf(id))
                }
            }
        }
        return Plan(id, typo, typoTarget, fuzzy, fuzzyRules, prediction, lua, grammar, recipes)
    }

    /** Writes the patched custom file and compiles the input method again; false when there is no source schema. */
    private suspend fun recompile(
        id: String,
        recipes: List<DeclarativeRecipe>,
        engine: DeployedEngine,
    ): Boolean {
        val schemaFile = File(sourceDir, "$id.schema.yaml")
        if (!schemaFile.isFile) {
            Log.i(TAG, "$id has no schema file of its own; its features stay as the scheme sets them")
            return false
        }
        val target = "$id.custom.yaml"
        var files: Map<String, ByteArray> = mapOf(target to (customFile(id)?.readBytes() ?: ByteArray(0)))
        for (recipe in recipes) {
            files = recipe.applyPatches(files, REFERENCE, recipe.declaredName.orEmpty()).files
        }
        File(workDir, target).also {
            it.writeBytes(files.getValue(target))
            CompiledReuse.stampConfigTime(it)
        }
        if (!engine.deploySchema(schemaFile)) {
            throw DeployException("librime did not compile $id with its feature settings")
        }
        return true
    }

    /**
     * The custom file librime applies to [id]: the user's own in the work directory (a user data
     * folder file shadows the shared one, as in any Rime), else the package's.
     */
    private fun customFile(id: String): File? =
        File(workDir, "$id.custom.yaml").takeIf { it.isFile } ?: File(sourceDir, "$id.custom.yaml").takeIf { it.isFile }

    private suspend fun isFullPinyin(
        id: String,
        engine: DeployedEngine,
    ): Boolean {
        val projection =
            engine.sourceProjection(id)?.let(CompactSchemaAdapter.SourceProjection::decode) ?: return false
        return CompactSchemaAdapter.isCanonicalPinyinDomainAllowingBenignExtras(projection.syllables.map { it.token })
    }

    private fun isFuzzyRule(rule: String): Boolean =
        rule.startsWith("fuzz/") || (rule.startsWith("derive/") && rule.endsWith("/fuzz/"))

    /**
     * Whether the custom file, outside our own managed blocks (which a choice writes there), picks
     * the grammar model itself: a patch of the root `grammar/language`. Tuning the model's parameters
     * (`grammar/collocation_penalty` and the like) leaves the choice of model to the settings.
     * A file that does not parse is treated as the user's.
     */
    private fun touchesGrammar(custom: ByteArray?): Boolean {
        val text = custom?.toString(Charsets.UTF_8) ?: return false
        var inManaged = false
        val own =
            text.lineSequence().filter { line ->
                when {
                    inManaged -> {
                        if (line == "# }") inManaged = false
                        false
                    }
                    line.startsWith("# Rx: ") && line.endsWith(" {") -> {
                        inManaged = true
                        false
                    }
                    else -> true
                }
            }
        val root =
            try {
                BoundedYaml.load(own.joinToString("\n"))
            } catch (_: Exception) {
                return true
            }
        return patchesGrammarLanguage(root)
    }

    private fun patchesGrammarLanguage(node: Any?): Boolean =
        when (node) {
            is Map<*, *> ->
                node.any { (key, value) ->
                    when (key.toString().removeSuffix("/+")) {
                        "patch" -> setsGrammarLanguage(value, "")
                        "__patch" -> patchesGrammarLanguage(value)
                        else -> {
                            val path = key.toString().removePrefix("patch/").removeSuffix("/+")
                            key.toString().startsWith("patch/") &&
                                (path == "grammar/language" || setsGrammarLanguage(value, path))
                        }
                    }
                }
            is List<*> -> node.any(::patchesGrammarLanguage)
            else -> false
        }

    private fun setsGrammarLanguage(
        node: Any?,
        path: String,
    ): Boolean =
        when (node) {
            is Map<*, *> ->
                node.any { (key, value) ->
                    val part = key.toString().removeSuffix("/+")
                    val child = if (path.isEmpty()) part else "$path/$part"
                    child == "grammar/language" || setsGrammarLanguage(value, child)
                }
            is List<*> -> node.any { setsGrammarLanguage(it, path) }
            else -> false
        }

    companion object {
        /** The package reference in the managed blocks' Rx markers. */
        const val REFERENCE = "songyan"
        private const val TAG = "EngineFeatures"
    }
}

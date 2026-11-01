package app.songyan.ime.packages

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Predictive text after commit (librime-predict): the `predictor` processor plus
 * `predict_translator`, reading a `Rime::Predict/1.0` database (`predictor/db`, resolved from the
 * user directory first, then the shared directory). Ported from the iOS `Prediction`.
 *
 * Pure logic, independent of any source or schema name, and ships no database:
 *  - [validateDatabase] reads only the header and the two internal table bounds, never the whole
 *    database;
 *  - [eligibility] decides from the **compiled** schema whether prediction can be wired. It needs
 *    a single primary producer, a schema that is not a generated companion, and one that does not
 *    already declare predictor / predict_translator (those are left to the source). Companions
 *    inherit the components as written, because prediction only sees committed text;
 *  - [make] builds a declarative patch as a separate Rx-managed block in `<schema>.custom.yaml`
 *    (a rerun replaces only that block), and optionally installs a user-imported database.
 *
 * The caller proves it took effect with the real engine after deploy (components registered,
 * compiled config read back); otherwise it counts as unavailable (fail closed).
 */
object Prediction {
    /** Recipe identity (`Rx`) of the managed block. Not tied to any source. */
    const val RECIPE_NAME = "prediction"

    /** Install path of the user-imported database inside the installation's resources. */
    const val DATABASE_PATH = "songyan-predict.db"

    /** Managed marker read back from the compiled schema (`true` = our block is active). */
    const val MARKER_KEY = "songyan_prediction"

    /** librime option consulted by `Predictor::OnContextUpdate`. */
    const val OPTION_NAME = "prediction"
    const val PROCESSOR = "predictor"
    const val TRANSLATOR = "predict_translator"

    /** Candidates offered after each commit (the candidate bar shows a single row). */
    const val MAX_CANDIDATES = 8
    private const val FORMAT_PREFIX = "Rime::Predict/"
    private const val METADATA_SIZE = 52
    private val NAMESPACE = Regex("^[A-Za-z0-9_]+$")

    data class Target(
        val schemaID: String,
    )

    sealed interface Eligibility {
        val target: Target? get() = null

        data class Eligible(
            override val target: Target,
        ) : Eligibility

        /** The schema (or its source) already declares prediction itself. */
        data object SourceManaged : Eligibility

        data class Ineligible(
            val reason: String,
        ) : Eligibility
    }

    // ------------------------------------------------------------------------------------
    // Database
    // ------------------------------------------------------------------------------------

    /**
     * A root-level `*.db` resource: where librime-predict resolves `predictor/db`. Such files
     * (ours, or one a source ships for its own `predictor`) travel as file resources and must pass
     * [validateDatabase] before an installation takes them.
     */
    fun isDatabasePath(path: String): Boolean =
        path.endsWith(".db") && path.codePointCount(0, path.length) > 3 && !path.contains('/') && !path.startsWith(".")

    /** Validates every prediction database among newly supplied file resources (fail closed). */
    fun validateDatabases(resources: Map<String, Path>) {
        for ((path, file) in resources) {
            if (!isDatabasePath(path)) continue
            try {
                validateDatabase(file)
            } catch (_: Exception) {
                throw RecipeInstallError.InvalidResource(path + " is not a valid prediction database")
            }
        }
    }

    /**
     * Structural check of a `Rime::Predict` file: header prefix, and both the key double-array and
     * the value string table lie inside the file. Reads 52 bytes. Only regular files are accepted
     * (symlinks are followed, as Files providers use them), so a FIFO or directory is rejected before
     * it is opened.
     */
    fun validateDatabase(file: Path) {
        val resolved =
            try {
                file.toRealPath()
            } catch (_: IOException) {
                throw RecipeInstallError.InvalidResource("prediction database is unreadable")
            }
        if (!Files.isRegularFile(
                resolved,
            )
        ) {
            throw RecipeInstallError.InvalidResource("prediction database is not a regular file")
        }
        val size = Files.size(resolved)
        if (size >
            RecipeResourceBudget.MODEL_FILE
        ) {
            throw RecipeInstallError.InvalidResource("prediction database is too large")
        }
        val header = ByteArray(minOf(METADATA_SIZE.toLong(), size).toInt())
        RandomAccessFile(resolved.toFile(), "r").use { it.readFully(header) }
        validateHeader(header, size)
    }

    internal fun validateHeader(
        header: ByteArray,
        fileSize: Long,
    ) {
        val formatBytes = FORMAT_PREFIX.toByteArray(Charsets.US_ASCII)
        if (header.size != METADATA_SIZE ||
            !header.take(formatBytes.size).toByteArray().contentEquals(formatBytes) ||
            header.take(32).none { it == 0.toByte() }
        ) {
            throw RecipeInstallError.InvalidResource("not a valid prediction database (Rime::Predict format required)")
        }

        fun u32(at: Int): Long =
            (header[at].toLong() and 0xFF) or ((header[at + 1].toLong() and 0xFF) shl 8) or
                ((header[at + 2].toLong() and 0xFF) shl 16) or ((header[at + 3].toLong() and 0xFF) shl 24)

        // OffsetPtr<char> is an int32 offset relative to the field itself.
        fun region(
            pointer: Int,
            size: Long,
        ): Boolean {
            val offset = u32(pointer).toInt().toLong()
            if (offset == 0L || size <= 0) return false
            val start = pointer + offset
            return start >= METADATA_SIZE && start <= fileSize && size <= fileSize - start
        }
        // Darts units are 4 bytes.
        if (!region(36, u32(40) * 4) || !region(44, u32(48))) {
            throw RecipeInstallError.InvalidResource("prediction database is incomplete or damaged")
        }
    }

    // ------------------------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------------------------

    fun eligibility(
        compiled: ByteArray,
        schemaID: String,
    ): Eligibility {
        if (!TypoCorrection.validIdentifier(schemaID)) return Eligibility.Ineligible("invalid schema id")
        if (CompactSchemaAdapter.isGeneratedCompanionID(
                schemaID,
            )
        ) {
            return Eligibility.Ineligible("generated compact companion")
        }
        val root =
            (if (compiled.size <= RecipeResourceBudget.SOURCE_FILE) decodeUtf8(compiled) else null)
                ?.let { YamsYaml.loadMap(it) }
                ?: return Eligibility.Ineligible("compiled schema unreadable")
        if (root["schema"].asStringMap()?.get("schema_id") !=
            schemaID
        ) {
            return Eligibility.Ineligible("schema identity mismatch")
        }
        if (root.containsKey("keyboard_rewrite") || root.containsKey("keyboard_adapter")) {
            return Eligibility.Ineligible("compact keyboard adapter")
        }
        val engine = root["engine"].asStringMap()
        val processors = engine?.get("processors").asStringList()
        val translators = engine?.get("translators").asStringList()
        if (processors == null || translators == null) return Eligibility.Ineligible("engine lists unreadable")
        val ours = root[MARKER_KEY] is Boolean
        if (!ours &&
            (processors.any { component(it) == PROCESSOR } || translators.any { component(it) == TRANSLATOR })
        ) {
            return Eligibility.SourceManaged
        }
        val producer = CompactSchemaAdapter.primaryProducer(translators)
        val section =
            producer
                ?.takeIf {
                    NAMESPACE.containsMatchIn(
                        it.namespace,
                    )
                }?.let { root[it.namespace].asStringMap() }
        val dictionary = section?.get("dictionary") as? String
        if (dictionary.isNullOrEmpty()) return Eligibility.Ineligible("no single primary producer with a dictionary")
        // No spelling requirement: prediction keys on commits, so native 9-key (digit) schemes and
        // 14/9-key companions predict like 26-key ones.
        return Eligibility.Eligible(Target(schemaID))
    }

    /** True only when the compiled schema runs our managed block: marker, both components and database name. */
    fun compiledEnabled(compiled: ByteArray): Boolean {
        val root = decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) } ?: return false
        if (root[MARKER_KEY] != true) return false
        val engine = root["engine"].asStringMap()
        val processors = engine?.get("processors").asStringList() ?: return false
        val translators = engine?.get("translators").asStringList() ?: return false
        if (root["predictor"].asStringMap()?.get("db") != DATABASE_PATH) return false
        return processors.contains(PROCESSOR) && translators.contains(TRANSLATOR)
    }

    /** Both component lists carry the same prediction components (or both carry none). */
    fun sameWiring(
        lhs: List<String>,
        rhs: List<String>,
    ): Boolean =
        listOf(PROCESSOR, TRANSLATOR).all { name ->
            lhs.any { component(it) == name } == rhs.any { component(it) == name }
        }

    internal fun component(prescription: String): String = prescription.substringBefore('@')

    /** The Rx identity the recipe plan assigns to this recipe for [packageReference]. */
    fun rx(packageReference: String): String = packageReference + ":" + RECIPE_NAME + ":"

    /**
     * True when the user's own custom content (outside this recipe's managed block) touches
     * prediction. Such a schema is user-managed and is never patched.
     */
    fun userOwnsSetting(
        customYaml: ByteArray?,
        packageReference: String,
    ): Boolean {
        val text = customYaml?.let { decodeUtf8(it) } ?: return false
        val marker = "# Rx: " + rx(packageReference) + " {"
        var inManaged = false
        for (line in splitLinesKeepingEmpty(text)) {
            if (line == marker) {
                inManaged = true
                continue
            }
            if (inManaged) {
                if (line == "# }") inManaged = false
                continue
            }
            val content = line.substringBefore('#')
            if (content.contains(PROCESSOR) || content.contains(TRANSLATOR) || content.contains(MARKER_KEY)) return true
        }
        return false
    }

    /** Targets whose compiled state differs from [desired] and that the user does not manage. */
    fun changes(
        desired: Boolean,
        targets: List<Target>,
        current: Map<String, Boolean>,
        userManaged: Set<String>,
    ): List<Target> =
        targets
            .filter { it.schemaID !in userManaged && (current[it.schemaID] ?: false) != desired }
            .sortedWith { a, b -> YamsYaml.compareStrings(a.schemaID, b.schemaID) }

    /**
     * One declarative recipe: [installDatabase] installs the imported database once; [enabling]
     * gets the full wiring, [disabling] gets a block that only records the managed marker as
     * `false` (the earlier block is replaced, so nothing is inserted).
     */
    fun make(
        enabling: List<Target>,
        disabling: List<Target> = emptyList(),
        installDatabase: Boolean = false,
    ): DeclarativeRecipe {
        val byID = Comparator<Target> { a, b -> YamsYaml.compareStrings(a.schemaID, b.schemaID) }
        val on = enabling.toSet().sortedWith(byID)
        val onIDs = on.map { it.schemaID }.toSet()
        val off = disabling.toSet().filter { it.schemaID !in onIDs }.sortedWith(byID)
        if (!installDatabase && on.isEmpty() && off.isEmpty()) {
            throw RecipeInstallError.InvalidResource("no input method needs the prediction switch adjusted")
        }
        for (target in on + off) {
            if (!TypoCorrection.validIdentifier(target.schemaID) ||
                CompactSchemaAdapter.isGeneratedCompanionID(target.schemaID)
            ) {
                throw RecipeInstallError.InvalidResource("invalid input method id: " + target.schemaID)
            }
        }
        // Recipe metadata that ends up in the generated recipe file, kept identical to iOS.
        val lines = arrayListOf("recipe:", "  Rx: $RECIPE_NAME", "  description: 上屏联想")
        if (installDatabase) {
            lines.add("install_files:")
            lines.add("  - $DATABASE_PATH")
        }
        lines.add("patch_files:")
        for (target in on) {
            lines.addAll(
                listOf(
                    "  ${target.schemaID}.custom.yaml:",
                    "    - patch/+:",
                    "        engine/processors/@before 0: $PROCESSOR",
                    "        engine/translators/@before 0: $TRANSLATOR",
                    "        switches/@next: {name: $OPTION_NAME, reset: 1}",
                    "        predictor/db: $DATABASE_PATH",
                    "        predictor/max_candidates: $MAX_CANDIDATES",
                    "        predictor/max_iterations: 0",
                    "        $MARKER_KEY: true",
                ),
            )
        }
        for (target in off) {
            lines.addAll(listOf("  ${target.schemaID}.custom.yaml:", "    - patch/+:", "        $MARKER_KEY: false"))
        }
        return DeclarativeRecipe.parse(
            "$RECIPE_NAME.recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }
}

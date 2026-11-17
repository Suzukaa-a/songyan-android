package app.songyan.ime.deploy

import android.util.Log
import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.packages.CompactSchemaAdapter
import app.songyan.ime.packages.RecipeInstallError
import app.songyan.ime.packages.SchemaKeyProfiles
import java.io.File

/**
 * A scheme package cannot get its 14-, 18- and 9-key companions; the import is refused rather
 * than published with schemas the generator rejected.
 */
class KeyboardCompanionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Generates and compiles the compact-layout companions of an imported scheme package's input
 * methods, in the deployment that compiled the package itself.
 *
 * Each source schema is read from the fresh build, its spelling projection from the engine, and
 * [CompactSchemaAdapter] writes one companion per layout the source's alphabet folds onto. The
 * companions go into the deployment's work directory (never the stored package), are compiled into
 * the same build without joining `schema_list`, and are recorded in the build's
 * [EncodingAdapterRegistry] file, which the keyboard reads with that build.
 *
 * A source the generator does not cover (no single default primary script translator, no provable
 * projection, a projection past [CompactSchemaAdapter.foldBudgetExceeded]) stays a 26-key input method. A schema the generator refuses as invalid fails the
 * deployment ([KeyboardCompanionException]); so does a companion librime will not compile.
 *
 * The sources in [reuse] already have their companions compiled in this build (a deployment
 * continuing an earlier one); their entries are taken from the build's registry as they are.
 *
 * [replaceBundled] regenerates companions a source ships (the bundled input methods, after a
 * feature patch changed them); otherwise a source file or any of [packageSchemaIds] with a
 * companion's name is refused.
 */
internal class KeyboardCompanions(
    private val sourceDir: File,
    private val workDir: File,
    private val schemaIds: List<String>,
    private val replaceBundled: Boolean = false,
    private val cancelled: () -> Boolean = { false },
    private val packageSchemaIds: List<String> = schemaIds,
    private val timer: DeployTimer? = null,
    private val reuse: Set<String> = emptySet(),
    /**
     * The folded shapes to build per input method (see [CompanionRequests]); null builds every shape,
     * as the bundled input methods do. What is asked for and cannot be built is recorded as
     * unavailable and never tried again for this build.
     */
    private val requested: Map<String, Set<KeyboardLayoutKind>>? = null,
) {
    suspend fun generate(engine: DeployedEngine) {
        val registry = File(engine.compiledDir, EncodingAdapterRegistry.FILE_NAME)
        val existing =
            if (reuse.isEmpty()) {
                emptyList()
            } else {
                registry
                    .takeIf { it.isFile }
                    ?.let { EncodingAdapterRegistry.decode(it.readText(Charsets.UTF_8)) }
                    .orEmpty()
            }
        val earlier = if (reuse.isEmpty()) emptyMap() else CompanionStatus.readUnavailable(engine.compiledDir)
        val unavailable = LinkedHashMap<String, Set<KeyboardLayoutKind>>()
        val adapters = ArrayList<KeyboardResourceSet.EncodingAdapter>()
        for (source in schemaIds.distinct()) {
            if (cancelled()) throw InterruptedException("companion generation cancelled")
            val kinds = COMPACT_LAYOUTS.filter { requested == null || it in requested[source].orEmpty() }
            if (kinds.isEmpty()) continue
            if (source in reuse) {
                adapters += existing.filter { it.sourceSchemaID == source }
                earlier[source]?.let { unavailable[source] = it }
                continue
            }
            val family = InputLayoutPolicy.familyOf(source, engine.schemaValue(source, "speller/alphabet").orEmpty())
            // A forbidden layout cannot be used; exporting a shape dictionary's full spelling map can exhaust the heap.
            // Only a scheme spelled syllable by syllable folds: a shape code or a stroke code would
            // fold into keys that mean nothing to its speller.
            val built =
                if (kinds.none { InputLayoutPolicy.allows(it, family) } || !profileOf(source, engine).allowsFolding) {
                    emptyList()
                } else {
                    companionsOf(source, engine, kinds)
                }
            adapters += built
            val refused = kinds.filter { kind -> built.none { it.layout == kind } }
            if (requested != null && refused.isNotEmpty()) unavailable[source] = refused.toSet()
        }
        registry.writeText(EncodingAdapterRegistry.encode(adapters), Charsets.UTF_8)
        if (requested != null) CompanionStatus.writeUnavailable(engine.compiledDir, unavailable)
    }

    private fun profileOf(
        source: String,
        engine: DeployedEngine,
    ): SchemaKeyProfile {
        val file = File(engine.compiledDir, "$source.schema.yaml")
        return if (file.isFile) {
            SchemaKeyProfiles.compute(
                file.readText(Charsets.UTF_8),
            )
        } else {
            SchemaKeyProfile(SchemaKeyProfile.Kind.UNKNOWN)
        }
    }

    private suspend fun companionsOf(
        source: String,
        engine: DeployedEngine,
        kinds: List<KeyboardLayoutKind>,
    ): List<KeyboardResourceSet.EncodingAdapter> {
        val compiledFile = File(engine.compiledDir, "$source.schema.yaml")
        if (!compiledFile.isFile) return emptyList()
        val compiled = compiledFile.readBytes()
        val adaptability =
            try {
                CompactSchemaAdapter.adaptability(compiled, source)
            } catch (error: RecipeInstallError) {
                throw KeyboardCompanionException("cannot adapt $source: ${error.message}", error)
            }
        if (adaptability is CompactSchemaAdapter.Adaptability.Unsupported) {
            Log.i(TAG, "$source stays on 26 keys: ${adaptability.reason}")
            return emptyList()
        }
        val bytes = timed("projection") { engine.sourceProjection(source) }
        if (bytes != null && bytes.size > CompactSchemaAdapter.FOLD_PROJECTION_BYTES) {
            Log.w(TAG, "$source stays on 26 keys: its spelling projection is ${bytes.size} bytes")
            return emptyList()
        }
        val projection = bytes?.let(CompactSchemaAdapter.SourceProjection::decode)
        if (projection == null || projection.syllables.isEmpty()) {
            Log.i(TAG, "$source stays on 26 keys: no spelling projection")
            return emptyList()
        }
        CompactSchemaAdapter.foldBudgetExceeded(projection)?.let { reason ->
            Log.w(TAG, "$source stays on 26 keys: $reason")
            return emptyList()
        }
        val result = ArrayList<KeyboardResourceSet.EncodingAdapter>()
        // A source whose own spellings are already telephone digits runs on 9 keys as it is.
        val nativeDigits =
            CompactSchemaAdapter.tokenAliasDomain(projection) != null &&
                CompactSchemaAdapter.isNumericCodeSource(projection) &&
                !CompactSchemaAdapter.declaresLetterClassPrefixSource(projection)
        if (nativeDigits && KeyboardLayoutKind.PHONE9 in kinds) {
            result += adapter(source, source, KeyboardLayoutKind.PHONE9, engine)
        }
        val delimiter =
            engine.schemaValue(source, "translator/delimiter") ?: engine.schemaValue(source, "speller/delimiter")
        val plan =
            try {
                CompactSchemaAdapter.CompanionPlan(compiled, source, projection, delimiter)
            } catch (error: Exception) {
                throw KeyboardCompanionException("cannot plan the companions of $source", error)
            }
        for (layout in kinds) {
            if (nativeDigits && layout == KeyboardLayoutKind.PHONE9) continue
            if (cancelled()) throw InterruptedException("companion generation cancelled")
            val fold = layout.compactFold ?: continue
            val body =
                try {
                    plan.companion(fold)
                } catch (error: RecipeInstallError) {
                    throw KeyboardCompanionException("cannot generate the ${fold.id}-key companion of $source", error)
                } ?: continue
            val target = CompactSchemaAdapter.identifier(source, fold)
            if (!replaceBundled && (File(sourceDir, "$target.schema.yaml").exists() || target in packageSchemaIds)) {
                throw KeyboardCompanionException("the package already has a schema named $target")
            }
            val file = File(workDir, "$target.schema.yaml")
            file.writeBytes(body)
            CompiledReuse.stampConfigTime(file)
            if (!timed("companion compile") { engine.deploySchema(file) } ||
                !File(engine.compiledDir, "$target.schema.yaml").isFile
            ) {
                throw DeployException("librime did not compile the companion $target of $source")
            }
            if (engine.schemaValue(target, "schema/schema_id") != target) {
                throw DeployException("the compiled companion $target does not load")
            }
            // Only the 9-key layout sends digits, which a scheme's own processors may claim (a Lua
            // processor that picks candidates with them); letter keys are ones the source composes with.
            if (fold.isPhoneDigits && !engine.composesKeys(target, fold.keysSent.sorted().joinToString(""))) {
                Log.w(TAG, "$source cannot use the ${fold.id}-key layout: it uses digit keys for other functions")
                File(engine.compiledDir, "$target.schema.yaml").delete()
                file.delete()
                continue
            }
            result += adapter(source, target, layout, engine)
        }
        return result
    }

    private inline fun <T> timed(
        name: String,
        block: () -> T,
    ): T = if (timer == null) block() else timer.phase(name, block)

    private suspend fun adapter(
        source: String,
        target: String,
        layout: KeyboardLayoutKind,
        engine: DeployedEngine,
    ) = KeyboardResourceSet.EncodingAdapter(
        sourceSchemaID = source,
        schemaID = target,
        layout = layout,
        schemaName = engine.schemaValue(source, "schema/name"),
    )

    private companion object {
        const val TAG = "KeyboardCompanions"
        val COMPACT_LAYOUTS = KeyboardLayoutKind.entries.filter { it.compactFold != null }
    }
}

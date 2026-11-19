package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutFold
import app.songyan.ime.layout.UserKeyboards
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Which folded (14, 18, 9 key) keyboards the user has asked for, as the deploy process needs them.
 * The process cannot read the preferences, so the keyboard list and the two hidden sets are
 * mirrored into one small file whenever they change. A fold is built only for a keyboard in the
 * list; an input method the list does not speak for (never edited since keyboards were listed) is
 * asked for the folds the old menu offered, which is every one it did not hide.
 */
internal data class CompanionRequests(
    val stored: UserKeyboards.Stored = UserKeyboards.Stored(),
    val hiddenSchemas: Set<String> = emptySet(),
    val hiddenLayouts: Set<String> = emptySet(),
    /**
     * The grouping of each listed keyboard whose layout folds its own way, by keyboard id, as
     * [LayoutFold.encode] writes it. A keyboard on a bundled grouping is not listed, so an older
     * mirror reads the same.
     */
    val folds: Map<String, String> = emptyMap(),
) {
    /** The folded shapes asked for [schema]. */
    fun shapes(schema: String): Set<CompanionShape> =
        when {
            schema in stored.schemes ->
                stored.keyboards.filter { it.schemaID == schema }.mapNotNullTo(LinkedHashSet(), ::shapeOf)
            schema in hiddenSchemas -> emptySet()
            else ->
                KeyboardLayoutKind.entries
                    .filter { it.wire !in hiddenLayouts }
                    .mapNotNullTo(LinkedHashSet(), CompanionShape::builtin)
        }

    /** The layouts of [shapes]. */
    fun kinds(schema: String): Set<KeyboardLayoutKind> = shapes(schema).mapTo(LinkedHashSet()) { it.layout }

    fun requested(schemas: Collection<String>): Map<String, Set<CompanionShape>> =
        schemas.associateWith { shapes(it) }.filterValues { it.isNotEmpty() }

    private fun shapeOf(keyboard: PublishedKeyboard): CompanionShape? {
        val own = folds[keyboard.id]?.let(LayoutFold::decode)?.let { CompactFold.of(keyboard.layout, it) }
        return if (own != null) CompanionShape(keyboard.layout, own) else CompanionShape.builtin(keyboard.layout)
    }

    fun encode(): String =
        buildJsonObject {
            put("keyboards", JsonArray(stored.keyboards.map { JsonPrimitive(it.id) }))
            put("schemes", JsonArray(stored.schemes.sorted().map(::JsonPrimitive)))
            put("hiddenSchemas", JsonArray(hiddenSchemas.sorted().map(::JsonPrimitive)))
            put("hiddenLayouts", JsonArray(hiddenLayouts.sorted().map(::JsonPrimitive)))
            if (folds.isNotEmpty()) {
                put("folds", buildJsonObject { folds.toSortedMap().forEach { (id, target) -> put(id, target) } })
            }
        }.toString()

    companion object {
        fun decode(text: String): CompanionRequests? =
            runCatching {
                val root = Json.parseToJsonElement(text).jsonObject

                fun strings(name: String) = root[name]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                CompanionRequests(
                    UserKeyboards.Stored(
                        strings("keyboards").mapNotNull { PublishedKeyboard.parse(it) },
                        strings("schemes").toSet(),
                    ),
                    strings("hiddenSchemas").toSet(),
                    strings("hiddenLayouts").toSet(),
                    root["folds"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            }.getOrNull()

        /**
         * Marks [schemas] as spoken for with no keyboards, in the mirror: a package installed for the
         * first time asks for no fold until the user picks keyboards. The preferences take over when
         * they are written.
         */
        fun cover(
            file: File,
            schemas: Collection<String>,
        ) {
            val current = read(file)
            val updated = current.copy(stored = current.stored.copy(schemes = current.stored.schemes + schemas))
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(updated.encode())
                if (!temp.renameTo(file)) temp.delete()
            }
        }

        /**
         * Writes [requests] from the preferences. Input methods a first install covered stay covered:
         * the preferences never spoke for them, and dropping them would ask for every fold.
         */
        fun mirror(
            file: File,
            requests: CompanionRequests,
        ) {
            val previous = read(file)
            val merged =
                requests.copy(
                    stored = requests.stored.copy(schemes = requests.stored.schemes + previous.stored.schemes),
                )
            val text = merged.encode()
            if (file.isFile && file.readText() == text) return
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.delete()
        }

        /** The mirrored requests; without the file, nothing was ever edited, so every input method is legacy. */
        fun read(file: File): CompanionRequests =
            runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let(::decode) ?: CompanionRequests()
    }
}

/** What the build of a generation holds of the folded keyboards asked for. */
internal object CompanionStatus {
    const val UNAVAILABLE_FILE = "keyboard-companions-unavailable.json"

    /**
     * The shapes the generator refused for this build, by [CompanionShape.wire]: asked for, never
     * built, never retried. A bundled fold's wire is its layout's (`phone9`), as before shapes.
     */
    fun readUnavailable(build: File): Map<String, Set<String>> =
        runCatching {
            Json.parseToJsonElement(File(build, UNAVAILABLE_FILE).readText()).jsonObject.mapValues { (_, wires) ->
                wires.jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }
            }
        }.getOrDefault(emptyMap())

    fun writeUnavailable(
        build: File,
        unavailable: Map<String, Set<String>>,
    ) {
        val json =
            buildJsonObject {
                for ((schema, wires) in unavailable.toSortedMap()) {
                    if (wires.isNotEmpty()) put(schema, JsonArray(wires.sorted().map(::JsonPrimitive)))
                }
            }
        File(build, UNAVAILABLE_FILE).writeText(json.toString())
    }

    /** The asked-for shapes the build neither holds a companion for nor refused. */
    fun missing(
        requested: Map<String, Set<CompanionShape>>,
        adapters: List<KeyboardResourceSet.EncodingAdapter>,
        unavailable: Map<String, Set<String>>,
    ): Map<String, Set<CompanionShape>> =
        requested
            .mapValues { (schema, shapes) ->
                shapes.filterTo(LinkedHashSet()) { shape ->
                    adapters.none { it.sourceSchemaID == schema && shape.isBuiltBy(it) } &&
                        shape.wire !in unavailable[schema].orEmpty()
                }
            }.filterValues { it.isNotEmpty() }

    /** [missing] for the build in [build], reading its registry and refusals. */
    fun missing(
        requested: Map<String, Set<CompanionShape>>,
        build: File,
    ): Map<String, Set<CompanionShape>> {
        val adapters =
            runCatching { EncodingAdapterRegistry.decode(File(build, EncodingAdapterRegistry.FILE_NAME).readText()) }
                .getOrNull()
                .orEmpty()
        return missing(requested, adapters, readUnavailable(build))
    }
}

/** Whether the generation in use still lacks folded keyboards, by the keyboards asked for. */
internal object CompanionWork {
    /** Asked-for folds of [manifest]'s input methods that [active]'s build neither holds nor refused. */
    fun requestedMissing(
        storage: AppStorage,
        active: PublicationRecord,
        manifest: SourceManifest,
    ): Boolean {
        val requested = CompanionRequests.read(storage.keyboardCompanionsFile).requested(manifest.schemaIds)
        if (requested.isEmpty()) return false
        val build = File(storage.generationDir(active.generation), "build")
        return CompanionStatus.missing(requested, build).isNotEmpty()
    }

    /** Whether a deployment completing the layouts has work: the first phase left some, or the list asks for more. */
    fun pending(
        storage: AppStorage,
        record: PublicationRecord? = GenerationPublisher(storage).usablePublication(),
        catalog: SourceCatalog = SourceCatalog(storage),
    ): Boolean {
        if (record == null) return false
        if (record.companionsPending) return true
        val manifest = catalog.read(record.sourceId) ?: return false
        return manifest.kind == SourceKind.SCHEME && requestedMissing(storage, record, manifest)
    }
}

/** One folded keyboard a companion is built for: a layout and the fold its keys make. */
internal data class CompanionShape(
    val layout: KeyboardLayoutKind,
    val fold: CompactFold,
) {
    /** `phone9` for a bundled fold, `merged14:14u1a2b3c4d` for a layout's own grouping. */
    val wire: String get() = if (fold.isBuiltin) layout.wire else layout.wire + ":" + fold.id

    /** Whether [adapter] is the companion of this shape. */
    fun isBuiltBy(adapter: KeyboardResourceSet.EncodingAdapter): Boolean =
        adapter.layout == layout && adapter.fold == fold.id.takeUnless { fold.isBuiltin }

    companion object {
        /** The bundled fold of [layout]; null for a layout that does not fold. */
        fun builtin(layout: KeyboardLayoutKind): CompanionShape? =
            layout.compactFold?.let { CompanionShape(layout, it) }
    }
}

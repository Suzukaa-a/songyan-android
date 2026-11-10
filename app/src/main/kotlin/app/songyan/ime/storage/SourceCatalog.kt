package app.songyan.ime.storage

import app.songyan.ime.content.ImportedDictionaryMetadataReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** The bundled input methods every built-in and dictionary source offers, primary first. */
object BuiltinSchemes {
    const val QUANPIN = "songyan_quanpin"
    const val FLYPY = "songyan_flypy"
    val IDS: List<String> = listOf(QUANPIN, FLYPY)
}

/**
 * The stored sources the keyboard menu and its settings offer to switch to. The built-in source is
 * a recovery path, not a choice: it stays out unless it is the one running.
 */
object SwitchTargets {
    fun of(
        stored: List<SourceManifest>,
        activeSourceId: String?,
    ): List<SourceManifest> = stored.filter { it.sourceId != activeSourceId && it.kind != SourceKind.BUILTIN }
}

/** Fresh installs wait for a scheme; existing and restored data retain the bundled recovery path. Read on IO. */
object InputSchemeSetup {
    const val INSTALLING = ".songyan-installing"

    fun needsDownload(storage: AppStorage): Boolean =
        !storage.activeFile.exists() &&
            storage.sourcesDir.listFiles().orEmpty().none {
                it.isDirectory && SourceIds.isValid(it.name) && !File(it, INSTALLING).exists()
            } &&
            GenerationPublisher(storage).publishedRecords().isEmpty() &&
            storage.userRoot
                .listFiles()
                .orEmpty()
                .none { it.isDirectory }
}

/** What kind of content a stored source holds. */
enum class SourceKind { BUILTIN, DICTIONARY, SCHEME }

/** One selectable input method of a source, with the name its schema declares. */
data class SourceScheme(
    val schemaId: String,
    val name: String,
)

/**
 * What a stored source is, written next to its files when it is installed: the deploy process
 * needs the input methods and revision to publish (or re-publish) it, settings needs the name.
 */
data class SourceManifest(
    val sourceId: String,
    val kind: SourceKind,
    val displayName: String,
    /** Selectable input methods, primary first. */
    val schemes: List<SourceScheme>,
    val revision: String,
    /** Entries of an imported dictionary; 0 for other kinds. */
    val entryCount: Int = 0,
    /** The HTTPS link a scheme package was downloaded from; what "update" downloads again. Null for files. */
    val origin: String? = null,
) {
    val schemaIds: List<String> get() = schemes.map { it.schemaId }
    val primarySchemaId: String get() = schemes.first().schemaId

    fun toJson(): String =
        buildJsonObject {
            put("version", 1)
            put("sourceId", sourceId)
            put("kind", kind.name)
            put("displayName", displayName)
            put(
                "schemes",
                JsonArray(
                    schemes.map {
                        buildJsonObject {
                            put("id", it.schemaId)
                            put("name", it.name)
                        }
                    },
                ),
            )
            put("revision", revision)
            put("entryCount", entryCount)
            origin?.let { put("origin", it) }
        }.toString()

    companion object {
        fun parse(json: String): SourceManifest? {
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val schemes =
                (root["schemes"] as? JsonArray)
                    ?.mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val id = item.string("id") ?: return@mapNotNull null
                        SourceScheme(id, item.string("name") ?: id)
                    }.orEmpty()
            if (schemes.isEmpty()) return null
            return SourceManifest(
                sourceId = root.string("sourceId") ?: return null,
                kind =
                    root.string("kind")?.let { name -> SourceKind.entries.firstOrNull { it.name == name } }
                        ?: return null,
                displayName = root.string("displayName") ?: "",
                schemes = schemes,
                revision = root.string("revision") ?: return null,
                entryCount = (root["entryCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                origin = root.string("origin")?.takeIf { it.isNotBlank() },
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/**
 * The sources stored under `rime/sources/`. Every import adds a source (or replaces the one with
 * the same identity) instead of replacing whatever was imported before, so the user can switch
 * between them and delete them individually.
 */
class SourceCatalog(
    private val storage: AppStorage,
) {
    fun read(sourceId: String): SourceManifest? {
        if (!SourceIds.isValid(sourceId)) return null
        return readFrom(sourceId, storage.sourceDir(sourceId))
    }

    /**
     * Every input method something on this device still provides: the built-in ones, every stored
     * source and a deleted source in the trash (it can come back, and its keyboards with it). Null
     * when a source cannot be read, because an input method that is still installed would then look
     * gone; the caller must not drop anything on a null.
     */
    fun declaredInputMethods(): Set<String>? {
        val ids = BuiltinSchemes.IDS.toMutableSet()
        val sources = storage.sourcesDir
        val stored = sources.listFiles() ?: if (sources.exists()) return null else emptyArray()
        for (dir in stored) {
            // The built-in tree is rewritten by every built-in deploy; its input methods are the bundled ones.
            if (!dir.isDirectory || !SourceIds.isValid(dir.name) || dir.name == SourceIds.BUILTIN) continue
            ids += readFrom(dir.name, dir)?.schemaIds ?: return null
        }
        val trash = storage.trashDir
        val trashed = trash.listFiles() ?: if (trash.exists()) return null else emptyArray()
        for (dir in trashed) {
            val tree = File(dir, TRASHED_TREE)
            if (!dir.isDirectory || !tree.isDirectory) continue
            ids += readFrom(dir.name, tree)?.schemaIds ?: return null
        }
        return ids
    }

    private fun readFrom(
        sourceId: String,
        dir: File,
    ): SourceManifest? {
        if (!dir.isDirectory) return null
        val file = File(dir, MANIFEST)
        if (!file.isFile || file.length() > MAX_BYTES) return legacy(sourceId, dir)
        return try {
            SourceManifest.parse(file.readText())?.takeIf { it.sourceId == sourceId }
        } catch (_: IOException) {
            null
        }
    }

    /** Every stored source with a readable manifest: built-in first, then by id. */
    fun list(): List<SourceManifest> =
        storage.sourcesDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && SourceIds.isValid(it.name) }
            .mapNotNull { read(it.name) }
            .sortedWith(compareBy<SourceManifest> { it.kind != SourceKind.BUILTIN }.thenBy { it.sourceId })

    /**
     * Source directories that cannot be used: no readable manifest, or files the manifest names are
     * gone. The built-in tree is rewritten by every built-in deploy, so it never counts.
     */
    fun damaged(): List<String> =
        storage.sourcesDir
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && SourceIds.isValid(it.name) && it.name != SourceIds.BUILTIN }
            .filter { dir -> read(dir.name)?.let { !isIntact(it) } ?: true }
            .map { it.name }
            .sorted()

    /** Whether the files a deploy of [manifest] needs are all there. */
    fun isIntact(manifest: SourceManifest): Boolean {
        val dir = storage.sourceDir(manifest.sourceId)
        if (!File(dir, "default.yaml").isFile) return false
        return manifest.schemaIds.all { File(dir, "$it.schema.yaml").isFile }
    }

    /**
     * Sources installed before manifests existed. Their revision is unknown, so a stored generation
     * never matches them and activating one rebuilds it.
     */
    private fun legacy(
        sourceId: String,
        dir: File,
    ): SourceManifest? {
        if (!File(dir, "default.yaml").isFile) return null
        return when {
            sourceId == SourceIds.BUILTIN -> null
            sourceId == SourceIds.DICTIONARY ->
                ImportedDictionaryMetadataReader.read(dir)?.let {
                    SourceManifest(
                        sourceId = sourceId,
                        kind = SourceKind.DICTIONARY,
                        displayName = it.displayName,
                        schemes = BuiltinSchemes.IDS.map { id -> SourceScheme(id, id) },
                        revision = LEGACY_REVISION,
                        entryCount = it.entryCount,
                    )
                }
            SourceIds.isScheme(sourceId) -> legacyScheme(sourceId, dir)
            else -> null
        }
    }

    private fun legacyScheme(
        sourceId: String,
        dir: File,
    ): SourceManifest? {
        val file = File(dir, LEGACY_SCHEME_METADATA)
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val root =
            runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject ?: return null
        val schemes =
            (root["schemes"] as? JsonArray)
                ?.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    SourceScheme(id, (item["name"] as? JsonPrimitive)?.contentOrNull ?: id)
                }.orEmpty()
        if (schemes.isEmpty()) return null
        return SourceManifest(
            sourceId = sourceId,
            kind = SourceKind.SCHEME,
            displayName = (root["displayName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            schemes = schemes,
            revision = LEGACY_REVISION,
        )
    }

    companion object {
        /** Written into every installed source tree. */
        const val MANIFEST = "songyan-source.json"

        /** Where a deleted source's tree waits in the trash, under its id; the deploy process moves it there. */
        const val TRASHED_TREE = "tree"
        const val LEGACY_REVISION = "legacy"
        private const val LEGACY_SCHEME_METADATA = "user-scheme.json"
        private const val MAX_BYTES = 256L * 1024

        fun write(
            sourceDir: File,
            manifest: SourceManifest,
        ) {
            File(sourceDir, MANIFEST).writeText(manifest.toJson())
        }
    }
}

/**
 * [SourceCatalog.declaredInputMethods] read while no deployment runs: the deploy process moves sources
 * in and out of the trash under the deploy lock, and a read between the two moves would miss one.
 * Null when a deployment holds the lock or the inventory cannot be read; it is asked again later.
 */
fun declaredInputMethodsWhileIdle(storage: AppStorage): Set<String>? {
    if (storage.deployLockFile.parentFile?.isDirectory != true) return null
    return try {
        FileChannel
            .open(storage.deployLockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel ->
                val lock = channel.tryLock() ?: return null
                lock.use { SourceCatalog(storage).declaredInputMethods() }
            }
    } catch (_: OverlappingFileLockException) {
        null
    } catch (_: IOException) {
        null
    }
}

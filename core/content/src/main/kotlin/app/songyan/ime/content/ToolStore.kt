package app.songyan.ime.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import kotlin.concurrent.withLock

/** Where a listed tool comes from: the app's built-in examples or the user's own directory. */
enum class ToolOrigin {
    BUILTIN,
    USER,
}

/**
 * The state the user controls for one tool, stored apart from the definition file so importing
 * the same tool again (or exporting it) never loses it.
 */
data class ToolMetadata(
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val order: Int = 0,
)

/** One tool as settings and the toolbar see it: the definition plus its user-controlled state. */
data class StoredTool(
    val definition: ToolDefinition,
    val metadata: ToolMetadata,
    val origin: ToolOrigin,
) {
    val id: String
        get() = definition.id
}

/**
 * One consistent read of the tools directory.
 *
 * [tools] is in toolbar order. [deletedToolId] names the one user tool whose deletion can still be
 * undone; it is null when nothing was deleted or the user already used the undo slot.
 */
data class ToolCatalog(
    val tools: List<StoredTool>,
    val deletedToolId: String? = null,
) {
    val canUndo: Boolean
        get() = deletedToolId != null

    /** The tools pinned to the toolbar, in toolbar order; at most [ToolStore.MAXIMUM_PINNED]. */
    val pinnedTools: List<StoredTool>
        get() = tools.filter { it.metadata.pinned }
}

/**
 * Why a tool-storage operation was refused.
 *
 * The reasons are the vocabulary the settings UI maps onto localized strings; no user-facing
 * sentence is compiled into this module. [Exception.message] is developer-facing only.
 */
class ToolStoreError(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.toString(), cause) {
    sealed class Reason {
        data class UnknownTool(
            val id: String,
        ) : Reason()

        data class BuiltinIdentifier(
            val id: String,
        ) : Reason()

        data class BuiltinNotDeletable(
            val id: String,
        ) : Reason()

        data class DuplicateIdentifier(
            val id: String,
        ) : Reason()

        data class PinLimit(
            val maximum: Int,
        ) : Reason()

        data class TooManyTools(
            val maximum: Int,
        ) : Reason()

        data object NothingToUndo : Reason()

        data class ToolFileUnreadable(
            val file: String,
            val error: ContentException,
        ) : Reason()

        data class ToolFileRejected(
            val file: String,
            val error: ToolDefinitionError,
        ) : Reason()

        data class MetadataRejected(
            val error: ContentException,
        ) : Reason()
    }
}

/**
 * Persists keyboard tools and their metadata under a caller-supplied directory.
 *
 * The caller owns the location (the app passes `filesDir/tools`) and the built-in example
 * definitions (the simplification example needs the OpenCC config the input language selects, which
 * this module must not choose). Every user tool is one `<id>.songyantool.json` file, so a
 * definition stays a portable file; one `index.json` carries enabled/pinned/order and the single
 * delete-undo slot, so a damaged index cannot take the definitions down with it (they fall back to
 * defaults and are reported through [load]'s callback).
 *
 * All IO is suspend and runs on [Dispatchers.IO]; nothing is read or written from a constructor or
 * property getter. Writes are serialized inside this process and across processes with a lock file,
 * like the other content stores. Reads never write and tolerate a concurrent writer.
 *
 * Contracts worth spelling out:
 * - Saving a definition whose id already exists updates the definition but keeps that tool's
 *   enabled, pinned and order state, because the state belongs to the tool, not to a version of it.
 * - An id that belongs to a built-in example is refused rather than shadowed.
 * - Deleting a user tool keeps exactly one undo: the most recent deletion. Undoing restores the
 *   exact stored bytes plus enabled/pinned/order; a tool that is pinned again but is the third pin
 *   comes back unpinned, because the pin limit wins.
 * - Import and copy decode and encode through [ToolDefinition], so both platforms accept the same
 *   files and every authored name survives a round trip.
 * - A mutation that fails leaves the definitions, the metadata and the undo slot as they were: a save
 *   the caller was told failed is not on the next listing, a refused delete keeps the tool and the
 *   undo that was already stored, and a refused undo keeps the slot so the user can retry it.
 * - A mutation is refused while the metadata file or one user tool file cannot be read or decoded:
 *   writing the defaults this build fell back to would replace the enabled, pinned and order state
 *   it never saw, and the index it wrote would drop the state of a tool whose definition it could
 *   not decode. [load] still lists what it could read and reports the rest, so the user can see
 *   which file keeps the tools from changing.
 */
class ToolStore(
    val directory: Path,
    builtinTools: List<ToolDefinition>,
) {
    /** The built-in examples this store lists before the user's tools. */
    val builtinTools: List<ToolDefinition> = builtinTools.toList()

    private val builtinIdentifiers: Set<String> = this.builtinTools.map { it.id }.toSet()

    init {
        require(
            this.builtinTools
                .map { it.id }
                .distinct()
                .size == this.builtinTools.size,
        ) {
            "built-in tool identifiers must be distinct"
        }
        this.builtinTools.forEach { it.validate() }
    }

    constructor(
        root: File,
        builtinTools: List<ToolDefinition>,
    ) : this(root.toPath(), builtinTools)

    private val indexFile: Path
        get() = directory.resolve(INDEX_FILE_NAME)

    private val deletedFile: Path
        get() = directory.resolve(DELETED_FILE_NAME)

    private val lockFile: Path
        get() = directory.resolve(LOCK_FILE_NAME)

    /**
     * The tools in toolbar order. A missing directory is the built-in-only catalog and is not
     * created as a side effect of reading; unreadable user files and metadata are skipped and
     * reported through [onError] instead of failing the whole listing.
     */
    suspend fun load(onError: ((ToolStoreError) -> Unit)? = null): ToolCatalog =
        withContext(Dispatchers.IO) { catalogOf(readState(onError)) }

    /** Decodes and saves a user tool; see the class contract for duplicate identifiers. */
    suspend fun importDefinition(bytes: ByteArray): StoredTool =
        withContext(Dispatchers.IO) {
            val definition = ToolDefinition.decode(bytes)
            saveDefinition(definition)
        }

    /** Same as [importDefinition], read through the bounded real file decoder. */
    suspend fun importDefinitionFile(path: Path): StoredTool =
        withContext(Dispatchers.IO) {
            val definition = ToolDefinition.decode(contentsOf = path)
            saveDefinition(definition)
        }

    /**
     * Creates a user tool from an example, keeping the example's steps and (unless [name] is given)
     * its authored names. The new definition validates through the same rules as an imported file.
     */
    suspend fun copyExample(
        example: ToolDefinition,
        newId: String,
        name: Map<String, String>? = null,
    ): StoredTool =
        withContext(Dispatchers.IO) {
            val copy =
                ToolDefinition(
                    id = newId,
                    name = name ?: example.name,
                    steps = example.steps,
                ).also { it.validate() }
            saveDefinition(copy)
        }

    /**
     * The canonical songyantool.json bytes of the tool, decoded from storage and re-encoded through
     * [ToolDefinition.encoded], so exports stay portable and every authored name survives.
     */
    suspend fun export(id: String): ByteArray =
        withContext(Dispatchers.IO) {
            val builtin = builtinTools.firstOrNull { it.id == id }
            if (builtin != null) {
                return@withContext builtin.encoded()
            }
            val tool =
                readState(null).tools.firstOrNull { it.definition.id == id }
                    ?: throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
            val file = tool.file ?: throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
            ToolDefinition.decode(BoundedFile.read(file, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)).encoded()
        }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): ToolCatalog =
        withContext(Dispatchers.IO) {
            writeTransaction {
                val state = writableState()
                val tool = state.tool(id) ?: throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
                tool.metadata.enabled = enabled
                normalizeAndWrite(state)
                catalogOf(state)
            }
        }

    suspend fun setPinned(
        id: String,
        pinned: Boolean,
    ): ToolCatalog =
        withContext(Dispatchers.IO) {
            writeTransaction {
                val state = writableState()
                val tool = state.tool(id) ?: throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
                if (tool.metadata.pinned != pinned) {
                    if (pinned && state.tools.count { it.metadata.pinned } >= MAXIMUM_PINNED) {
                        throw ToolStoreError(ToolStoreError.Reason.PinLimit(MAXIMUM_PINNED))
                    }
                    tool.metadata.pinned = pinned
                }
                normalizeAndWrite(state)
                catalogOf(state)
            }
        }

    /** Moves [id] to [toIndex] (clamped to the list), which also defines the persisted order. */
    suspend fun move(
        id: String,
        toIndex: Int,
    ): ToolCatalog =
        withContext(Dispatchers.IO) {
            writeTransaction {
                val state = writableState()
                val position = state.tools.indexOfFirst { it.definition.id == id }
                if (position < 0) throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
                val target = toIndex.coerceIn(0, state.tools.size - 1)
                if (position != target) {
                    val tool = state.tools.removeAt(position)
                    state.tools.add(target, tool)
                }
                normalizeAndWrite(state)
                catalogOf(state)
            }
        }

    /**
     * Deletes a user tool, keeping its exact stored bytes plus enabled/pinned/order as the one undo
     * slot. Built-in examples cannot be deleted. Undoing a deletion whose stored bytes are no longer
     * a valid definition reports the codec error instead of restoring a broken tool.
     */
    suspend fun delete(id: String): ToolCatalog =
        withContext(Dispatchers.IO) {
            writeTransaction {
                val state = writableState()
                val position = state.tools.indexOfFirst { it.definition.id == id }
                if (position < 0) {
                    if (id in builtinIdentifiers) {
                        throw ToolStoreError(ToolStoreError.Reason.BuiltinNotDeletable(id))
                    }
                    throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
                }
                val tool = state.tools[position]
                if (tool.origin != ToolOrigin.USER) {
                    throw ToolStoreError(ToolStoreError.Reason.BuiltinNotDeletable(id))
                }
                val file = tool.file ?: throw ToolStoreError(ToolStoreError.Reason.UnknownTool(id))
                val bytes = BoundedFile.read(file, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                val previousDeletion = bytesOrNull(deletedFile, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                val previousIndex = bytesOrNull(indexFile, MAXIMUM_INDEX_BYTES)
                BoundedFile.writeAtomically(deletedFile, bytes)
                state.deleted = tool.metadata.copyOf()
                state.tools.removeAt(position)
                try {
                    normalizeAndWrite(state)
                } catch (error: Throwable) {
                    // The undo slot moved before the metadata was written; the deletion that was
                    // there is the one the user can still undo, so it has to come back.
                    restoreFile(deletedFile, previousDeletion, error)
                    throw error
                }
                try {
                    deleteFile(file)
                } catch (error: Throwable) {
                    // The metadata committed the deletion but the definition is still stored, so both
                    // files go back to what the caller already had.
                    restoreFile(indexFile, previousIndex, error)
                    restoreFile(deletedFile, previousDeletion, error)
                    throw error
                }
                catalogOf(state)
            }
        }

    /** Restores the most recent deletion, or reports [ToolStoreError.Reason.NothingToUndo]. */
    suspend fun undoDelete(): ToolCatalog =
        withContext(Dispatchers.IO) {
            writeTransaction {
                val state = writableState()
                val deleted = state.deleted ?: throw ToolStoreError(ToolStoreError.Reason.NothingToUndo)
                if (!Files.isRegularFile(deletedFile)) {
                    throw ToolStoreError(ToolStoreError.Reason.NothingToUndo)
                }
                val bytes = BoundedFile.read(deletedFile, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                val definition = ToolDefinition.decode(bytes)
                val file = fileOf(definition.id)
                val previousDefinition = bytesOrNull(file, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                BoundedFile.writeAtomically(file, bytes)
                val record = LoadedTool(definition, file, ToolOrigin.USER, deleted)
                state.tools.add(deleted.order.coerceIn(0, state.tools.size), record)
                state.deleted = null
                try {
                    normalizeAndWrite(state)
                } catch (error: Throwable) {
                    // The definition was written back before the metadata could name it, so the
                    // restore is undone: the undo slot stays and a retry can still bring the tool back.
                    restoreFile(file, previousDefinition, error)
                    throw error
                }
                runCatching { Files.deleteIfExists(deletedFile) }
                catalogOf(state)
            }
        }

    // Saving

    private fun saveDefinition(definition: ToolDefinition): StoredTool =
        writeTransaction {
            val state = writableState()
            if (definition.id in builtinIdentifiers) {
                throw ToolStoreError(ToolStoreError.Reason.BuiltinIdentifier(definition.id))
            }
            val bytes = definition.encoded()
            val file = fileOf(definition.id)
            val previousDefinition = bytesOrNull(file, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
            val position = state.tools.indexOfFirst { it.definition.id == definition.id }
            if (position >= 0) {
                val existing = state.tools[position]
                if (existing.origin != ToolOrigin.USER) {
                    throw ToolStoreError(ToolStoreError.Reason.BuiltinIdentifier(definition.id))
                }
                BoundedFile.writeAtomically(file, bytes)
                state.tools[position] = LoadedTool(definition, file, ToolOrigin.USER, existing.metadata)
            } else {
                if (state.tools.count { it.origin == ToolOrigin.USER } >= MAXIMUM_TOOLS) {
                    throw ToolStoreError(ToolStoreError.Reason.TooManyTools(MAXIMUM_TOOLS))
                }
                BoundedFile.writeAtomically(file, bytes)
                state.tools.add(
                    LoadedTool(definition, file, ToolOrigin.USER, IndexedMetadata(definition.id, true, false, 0)),
                )
            }
            if (state.deleted?.id == definition.id) {
                state.deleted = null
            }
            try {
                normalizeAndWrite(state)
            } catch (error: Throwable) {
                // The caller is told the save failed, so the definition that was just written must not
                // stay visible: the previous bytes come back, or the new file is removed again.
                restoreFile(file, previousDefinition, error)
                throw error
            }
            if (state.deleted == null) runCatching { Files.deleteIfExists(deletedFile) }
            val saved = state.tools.first { it.definition.id == definition.id }
            StoredTool(saved.definition, saved.metadata.toMetadata(), saved.origin)
        }

    // Reading

    /**
     * The state a mutation builds on.
     *
     * [load] may fall back to defaults and skip what it cannot read because a listing only shows
     * what this build could read, but a write built on that partial state would replace the
     * enabled, pinned and order state the user still has on disk with values this build never
     * read, and the index it wrote would no longer name a tool whose definition it could not
     * decode, so the mutation is refused instead.
     */
    private fun writableState(): LoadedState = readState(null, refuseUnreadableState = true)

    private fun readState(
        onError: ((ToolStoreError) -> Unit)?,
        refuseUnreadableState: Boolean = false,
    ): LoadedState {
        val index =
            try {
                readIndex()
            } catch (error: ContentException) {
                val refused = ToolStoreError(ToolStoreError.Reason.MetadataRejected(error), error)
                if (refuseUnreadableState) throw refused
                onError?.invoke(refused)
                StoredIndex()
            }
        val state = LoadedState()
        this.builtinTools.forEachIndexed { position, definition ->
            val metadata =
                index.metadata[definition.id]?.copyOf()
                    ?: IndexedMetadata(definition.id, true, false, position)
            state.tools.add(LoadedTool(definition, null, ToolOrigin.BUILTIN, metadata))
        }
        val seen = builtinIdentifiers.toMutableSet()
        val files = userFiles()
        for ((position, file) in files.withIndex()) {
            val name = file.fileName?.toString() ?: "$"
            val bytes =
                try {
                    BoundedFile.read(file, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                } catch (error: ContentException) {
                    val refused = ToolStoreError(ToolStoreError.Reason.ToolFileUnreadable(name, error), error)
                    if (refuseUnreadableState) throw refused
                    onError?.invoke(refused)
                    continue
                }
            val definition =
                try {
                    ToolDefinition.decode(bytes)
                } catch (error: ToolDefinitionError) {
                    val refused = ToolStoreError(ToolStoreError.Reason.ToolFileRejected(name, error), error)
                    if (refuseUnreadableState) throw refused
                    onError?.invoke(refused)
                    continue
                }
            if (definition.id in builtinIdentifiers) {
                onError?.invoke(ToolStoreError(ToolStoreError.Reason.BuiltinIdentifier(definition.id)))
                continue
            }
            if (!seen.add(definition.id)) {
                onError?.invoke(ToolStoreError(ToolStoreError.Reason.DuplicateIdentifier(definition.id)))
                continue
            }
            val metadata =
                index.metadata[definition.id]?.copyOf()
                    ?: IndexedMetadata(definition.id, true, false, this.builtinTools.size + position)
            state.tools.add(LoadedTool(definition, file, ToolOrigin.USER, metadata))
        }
        sortTools(state)
        limitPins(state)
        state.deleted = resolvedDeletion(index, state)
        return state
    }

    private fun sortTools(state: LoadedState) {
        val natural = state.tools.withIndex().associate { it.value to it.index }
        state.tools.sortWith(compareBy({ it.metadata.order }, { natural[it] ?: Int.MAX_VALUE }))
    }

    private fun limitPins(state: LoadedState) {
        var pinned = 0
        for (tool in state.tools) {
            if (!tool.metadata.pinned) continue
            if (pinned < MAXIMUM_PINNED) {
                pinned++
            } else {
                tool.metadata.pinned = false
            }
        }
    }

    private fun resolvedDeletion(
        index: StoredIndex,
        state: LoadedState,
    ): IndexedMetadata? {
        val deleted = index.deleted ?: return null
        if (deleted.id in builtinIdentifiers) return null
        if (!Files.isRegularFile(deletedFile)) return null
        if (state.tools.any { it.definition.id == deleted.id }) return null
        return deleted.copyOf()
    }

    private fun readIndex(): StoredIndex {
        if (!Files.exists(indexFile)) return StoredIndex()
        val bytes = BoundedFile.read(indexFile, MAXIMUM_INDEX_BYTES)
        val root = ContentJson.parseObject(ContentJson.parse(ContentJson.decodeText(bytes)))
        val version = ContentJson.requireInt(root, "version", "$")
        if (version != INDEX_VERSION) throw ContentException(ContentErrorCode.UNSUPPORTED_VERSION, "$.version")
        val metadata = LinkedHashMap<String, IndexedMetadata>()
        val entries = root["tools"] as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.tools")
        for ((position, entry) in entries.withIndex()) {
            val path = "$.tools[" + position + "]"
            val source = ContentJson.parseObject(entry)
            val id = validIdentifier(ContentJson.requireString(source, "id", path), path + ".id")
            metadata[id] =
                IndexedMetadata(
                    id = id,
                    enabled = ContentJson.requireBoolean(source, "enabled", path),
                    pinned = ContentJson.requireBoolean(source, "pinned", path),
                    order = ContentJson.requireInt(source, "order", path),
                )
        }
        val deleted =
            root["deleted"]?.let { element ->
                val path = "$.deleted"
                val source = ContentJson.parseObject(element)
                IndexedMetadata(
                    id = validIdentifier(ContentJson.requireString(source, "id", path), path + ".id"),
                    enabled = ContentJson.requireBoolean(source, "enabled", path),
                    pinned = ContentJson.requireBoolean(source, "pinned", path),
                    order = ContentJson.requireInt(source, "order", path),
                )
            }
        return StoredIndex(metadata, deleted)
    }

    private fun userFiles(): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return try {
            Files.list(directory).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName?.toString() ?: return@filter false
                        name.endsWith(TOOL_FILE_SUFFIX) && !name.startsWith(".") && Files.isRegularFile(path)
                    }.sorted()
                    .collect(Collectors.toList())
            }
        } catch (error: IOException) {
            throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, DIRECTORY_NAME, error)
        }
    }

    // Writing

    private fun normalizeAndWrite(state: LoadedState) {
        limitPins(state)
        state.tools.forEachIndexed { position, tool -> tool.metadata.order = position }
        writeIndex(state)
    }

    private fun writeIndex(state: LoadedState) {
        val element: JsonElement =
            buildJsonObject {
                put("version", INDEX_VERSION)
                put(
                    "tools",
                    buildJsonArray {
                        for (tool in state.tools) {
                            add(tool.metadata.toJsonObject())
                        }
                    },
                )
                state.deleted?.let { put("deleted", it.toJsonObject()) }
            }
        val bytes = ContentJson.pretty.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_INDEX_BYTES) {
            throw ContentException(ContentErrorCode.OVERSIZE, INDEX_FILE_NAME)
        }
        BoundedFile.writeAtomically(indexFile, bytes)
    }

    private fun IndexedMetadata.toJsonObject() =
        buildJsonObject {
            put("id", id)
            put("enabled", enabled)
            put("pinned", pinned)
            put("order", order)
        }

    /** The exact bytes of [file], or null when there is nothing that a rollback has to preserve. */
    private fun bytesOrNull(
        file: Path,
        maximumBytes: Int,
    ): ByteArray? = if (Files.isRegularFile(file)) BoundedFile.read(file, maximumBytes) else null

    /**
     * Puts [file] back to the bytes it held before a mutation, or removes it when it did not exist.
     *
     * Retain the original failure and record an incomplete restoration alongside it; deletion may
     * need multiple restorations, and failure of one must not prevent the remaining attempts.
     */
    private fun restoreFile(
        file: Path,
        bytes: ByteArray?,
        failure: Throwable,
    ) {
        try {
            if (bytes != null) {
                BoundedFile.writeAtomically(file, bytes)
            } else {
                Files.deleteIfExists(file)
            }
        } catch (rollback: Exception) {
            failure.addSuppressed(rollback)
        }
    }

    private fun deleteFile(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (error: IOException) {
            throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, file.fileName?.toString() ?: "$", error)
        }
    }

    private fun fileOf(id: String): Path = directory.resolve(id + TOOL_FILE_SUFFIX)

    private fun catalogOf(state: LoadedState): ToolCatalog =
        ToolCatalog(
            tools =
                state.tools.map {
                    StoredTool(it.definition, it.metadata.toMetadata(), it.origin)
                },
            deletedToolId = state.deleted?.id,
        )

    private fun IndexedMetadata.toMetadata(): ToolMetadata = ToolMetadata(enabled, pinned, order)

    /**
     * Serializes read-modify-write transactions: one in-process lock (two threads of this process
     * cannot interleave, where a file lock alone would throw) plus an advisory OS lock on the lock
     * file, so the settings process and the keyboard process agree. A symlinked lock file is refused
     * rather than followed.
     */
    private fun <T> writeTransaction(body: () -> T): T =
        PROCESS_LOCK.withLock {
            try {
                Files.createDirectories(directory)
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, DIRECTORY_NAME, error)
            }
            if (Files.isSymbolicLink(lockFile)) {
                throw ContentException(ContentErrorCode.LOCK_FAILED, LOCK_FILE_NAME)
            }
            val options: Set<OpenOption> =
                setOf(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            try {
                FileChannel.open(lockFile, options).use { channel ->
                    val lock =
                        try {
                            channel.lock()
                        } catch (error: IOException) {
                            throw ContentException(ContentErrorCode.LOCK_FAILED, LOCK_FILE_NAME, error)
                        }
                    try {
                        body()
                    } finally {
                        lock.release()
                    }
                }
            } catch (error: ContentException) {
                throw error
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, LOCK_FILE_NAME, error)
            }
        }

    private class IndexedMetadata(
        val id: String,
        var enabled: Boolean,
        var pinned: Boolean,
        var order: Int,
    ) {
        fun copyOf(): IndexedMetadata = IndexedMetadata(id, enabled, pinned, order)
    }

    private class LoadedTool(
        val definition: ToolDefinition,
        val file: Path?,
        val origin: ToolOrigin,
        val metadata: IndexedMetadata,
    )

    private class LoadedState {
        val tools: MutableList<LoadedTool> = ArrayList()
        var deleted: IndexedMetadata? = null

        fun tool(id: String): LoadedTool? = tools.firstOrNull { it.definition.id == id }
    }

    private class StoredIndex(
        val metadata: Map<String, IndexedMetadata> = emptyMap(),
        val deleted: IndexedMetadata? = null,
    )

    private fun validIdentifier(
        value: String,
        path: String,
    ): String {
        val valid =
            value.isNotEmpty() &&
                value.toByteArray(Charsets.UTF_8).size <= ToolDefinitionLimits.MAXIMUM_IDENTIFIER_UTF8_BYTES &&
                value.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }
        if (!valid) throw ContentException(ContentErrorCode.INVALID_VALUE, path)
        return value
    }

    companion object {
        /** Recommended directory name below the app's files directory. */
        const val DIRECTORY_NAME: String = "tools"

        /** Suffix of one user tool definition file. */
        const val TOOL_FILE_SUFFIX: String = ".songyantool.json"

        /** Metadata file inside [DIRECTORY_NAME]. */
        const val INDEX_FILE_NAME: String = "index.json"

        /** The one definition kept for undo after a deletion. */
        const val DELETED_FILE_NAME: String = ".deleted.songyantool.json"

        /** Lock file serializing writers; never backed up. */
        const val LOCK_FILE_NAME: String = ".lock"

        /** Tools the user may pin to the toolbar. */
        const val MAXIMUM_PINNED: Int = 2

        /** User tools this store accepts, so Auto Backup stays small. */
        const val MAXIMUM_TOOLS: Int = 100

        /** Byte budget of the metadata file. */
        const val MAXIMUM_INDEX_BYTES: Int = 256 * 1024

        /** The only metadata format version this build reads and writes. */
        private const val INDEX_VERSION: Int = 1

        /** One lock for every instance in this process; the file lock covers other processes. */
        private val PROCESS_LOCK = ReentrantLock()
    }
}

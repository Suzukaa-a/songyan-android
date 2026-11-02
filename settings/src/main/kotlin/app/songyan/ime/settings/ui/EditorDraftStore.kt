package app.songyan.ime.settings.ui

import androidx.lifecycle.SavedStateHandle
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

// Only the token enters the activity bundle; model snapshots stay in app-private, non-backed-up storage.
class EditorDraftStore(
    directory: File,
    private val state: SavedStateHandle,
) {
    private val token = state.get<String>(TOKEN) ?: UUID.randomUUID().toString().also { state[TOKEN] = it }
    private val root = File(directory, "editor-drafts")
    private val file = File(root, UUID.fromString(token).toString())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<(() -> Unit)>(Channel.CONFLATED)

    private val writer =
        scope.launch {
            try {
                for (write in pending) write()
            } finally {
                scope.cancel()
            }
        }

    suspend fun <T> restore(read: (DataInputStream) -> T): T? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.isFile || file.length() > MAX_BYTES) return@withContext null
                file.inputStream().buffered().use { stream -> DataInputStream(stream).use(read) }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    fun persist(write: (DataOutputStream) -> Unit) {
        pending.trySend {
            try {
                root.mkdirs()
                val bytes =
                    java.io.ByteArrayOutputStream().use { buffer ->
                        DataOutputStream(buffer).use(write)
                        buffer.toByteArray()
                    }
                if (bytes.size <= MAX_BYTES) {
                    val temporary = File(root, "$token.tmp")
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(file)) throw IOException("Cannot replace editor draft")
                }
                // Limit abandoned drafts across navigation entries and old tasks.
                root
                    .listFiles()
                    ?.filter { it != file }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_FILES - 1)
                    ?.forEach { it.delete() }
            } catch (_: IOException) {
                // Editing remains available when private storage is full; never report a catalog save here.
            }
        }
    }

    fun clear() {
        state.remove<String>(TOKEN)
        pending.trySend { file.delete() }
    }

    fun close(): kotlinx.coroutines.Job {
        // Drain the latest snapshot even when popping the editor cancels its ViewModel scope.
        pending.close()
        return writer
    }

    companion object {
        private const val TOKEN = "editorDraftToken"
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_FILES = 8
    }
}

private fun DataOutputStream.text(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.text(): String {
    val size = readInt()
    require(size in 0..(8 * 1024 * 1024))
    return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
}

private fun DataOutputStream.selection(
    selection: KeySelection?,
    rail: RailSelection?,
) {
    writeInt(selection?.row ?: -1)
    writeInt(selection?.key ?: -1)
    writeInt(rail?.position?.ordinal ?: -1)
    writeInt(rail?.index ?: -1)
}

private fun DataInputStream.selection(): Pair<KeySelection?, RailSelection?> {
    val row = readInt()
    val key = readInt()
    val position = readInt()
    val index = readInt()
    return (if (row >= 0 && key >= 0) KeySelection(row, key) else null) to
        LayoutAuxiliaryArea.Position.entries
            .getOrNull(position)
            ?.let { RailSelection(it, index) }
}

internal fun DataOutputStream.writeLayoutDraft(draft: LayoutDraft) {
    writeInt(1)
    text(LayoutJson.compact.encodeToString(LayoutDefinition.serializer(), draft.original))
    text(LayoutJson.compact.encodeToString(LayoutDefinition.serializer(), draft.layout))
    selection(draft.selection, draft.rail)
    writeBoolean(draft.splitView)
}

internal fun DataInputStream.readLayoutDraft(): LayoutDraft {
    require(readInt() == 1)
    val original = LayoutJson.compact.decodeFromString(LayoutDefinition.serializer(), text())
    val layout = LayoutJson.compact.decodeFromString(LayoutDefinition.serializer(), text())
    val (selection, rail) = selection()
    return LayoutDraft(original, layout, selection, rail, readBoolean())
}

internal fun DataOutputStream.writePageDraft(draft: PageDraft) {
    writeInt(1)
    text(LayoutJson.compact.encodeToString(KeyboardPageDefinition.serializer(), draft.original))
    text(LayoutJson.compact.encodeToString(KeyboardPageDefinition.serializer(), draft.page))
    selection(draft.selection, draft.rail)
    writeInt(draft.categoryIndex)
    writeInt(draft.categoryKey)
}

internal fun DataInputStream.readPageDraft(): PageDraft {
    require(readInt() == 1)
    val original = LayoutJson.compact.decodeFromString(KeyboardPageDefinition.serializer(), text())
    val page = LayoutJson.compact.decodeFromString(KeyboardPageDefinition.serializer(), text())
    val (selection, rail) = selection()
    return PageDraft(original, page, selection, readInt(), readInt(), rail)
}

internal fun DataOutputStream.writeConfigDraft(value: String?) {
    writeBoolean(value != null)
    if (value != null) text(value)
}

internal fun DataInputStream.readConfigDraft(): String? = if (readBoolean()) text() else null

package app.songyan.ime.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The outcome of [RecentSymbolStore.record]: the list to show and the save failure, if any. */
data class RecentSymbolsUpdate(
    /** The list as the panel should display it, even when saving failed. */
    val texts: List<String>,
    /** Why the list could not be persisted; the old file and its backup are intact. */
    val error: ContentException? = null,
)

/**
 * Recently used symbols: same text deduplicated and moved to the front (MRU), capped at
 * [limit].
 *
 * Entries are the symbol text (what got inserted), not item ids, so editing a custom catalog
 * never invalidates the list. `recent.json` is the current value and `recent-last.json` the
 * previous good value, kept so a damaged current file can still be recovered: reads fall back
 * to the backup, and a corrupt current file never replaces a valid backup.
 *
 * The directory is injected by the caller and is conventionally shared with [SymbolCatalogStore].
 * The keyboard and settings create separate stores over the same files. A process-wide writer
 * lock keeps each read-modify-write intact; all production writers live in this process.
 */
class RecentSymbolStore(
    val directory: Path,
    requestedLimit: Int = DEFAULT_LIMIT,
) {
    /** The effective cap, clamped to [1, 100]. */
    val limit: Int = requestedLimit.coerceIn(1, MAX_ENTRIES)

    val activeFile: Path
        get() = directory.resolve(ACTIVE_FILE_NAME)

    val backupFile: Path
        get() = directory.resolve(BACKUP_FILE_NAME)

    /**
     * Reads the current list: current file, else backup, else empty. Writes nothing.
     *
     * A missing file is the ordinary empty state; corruption, permission and oversize errors
     * are reported through [onError] even when the backup recovers the data.
     */
    fun read(onError: ((ContentException) -> Unit)? = null): List<String> {
        decodeFile(activeFile, onError)?.let { return it }
        decodeFile(backupFile, onError)?.let { return it }
        return emptyList()
    }

    /**
     * Records a use: deduplicates to the front, trims to [limit], backs up the previous value
     * and saves atomically.
     *
     * A damaged list is reported through [onError] (it is replaced by the new use, because it cannot
     * be recovered by reading again), and a failed save keeps the old files and returns the expected
     * in-memory list so the panel still shows the use that just happened.
     */
    fun record(
        text: String,
        onError: ((ContentException) -> Unit)? = null,
    ): RecentSymbolsUpdate =
        writeLock.withLock {
            val trimmed = text.trim { it.isWhitespace() }
            val current = read(onError)
            if (trimmed.isEmpty()) return@withLock RecentSymbolsUpdate(current)
            val texts = current.filter { it != trimmed }.toMutableList()
            texts.add(0, trimmed)
            val bounded = if (texts.size > limit) texts.subList(0, limit).toList() else texts
            RecentSymbolsUpdate(bounded, save(bounded))
        }

    /**
     * Clears the list: first protects a recoverable current value into the active file, then
     * removes the backup, then commits an empty list.
     *
     * The order matters: the backup is deleted before the empty list is written, so a failing
     * deletion leaves the old list readable instead of losing it behind an empty `[]`.
     */
    fun clear() =
        writeLock.withLock {
            if (validData(activeFile) == null) {
                val recovered =
                    try {
                        readValidated(backupFile).bytes
                    } catch (error: ContentException) {
                        null
                    }
                if (recovered != null) BoundedFile.writeAtomically(activeFile, recovered)
            }
            try {
                Files.deleteIfExists(backupFile)
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, BACKUP_FILE_NAME, error)
            }
            if (Files.exists(activeFile)) BoundedFile.writeAtomically(activeFile, EMPTY_LIST)
        }

    /**
     * Saves [texts] atomically. Every failure is returned; the old files are then untouched,
     * and a still-recoverable backup is never overwritten by a corrupt current file.
     */
    fun save(texts: List<String>): ContentException? =
        writeLock.withLock {
            try {
                val element: JsonElement = buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } }
                val bytes =
                    ContentJson.pretty.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
                if (texts.size > MAX_ENTRIES || bytes.size > MAX_BYTES) {
                    throw ContentException(ContentErrorCode.OVERSIZE, ACTIVE_FILE_NAME)
                }
                Files.createDirectories(directory)
                validData(activeFile)?.let { BoundedFile.writeAtomically(backupFile, it) }
                BoundedFile.writeAtomically(activeFile, bytes)
                null
            } catch (error: ContentException) {
                error
            } catch (error: IOException) {
                ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, ACTIVE_FILE_NAME, error)
            }
        }

    private class ValidatedRecent(
        val bytes: ByteArray,
        val texts: List<String>,
    )

    private fun validData(file: Path): ByteArray? =
        try {
            readValidated(file).bytes
        } catch (error: ContentException) {
            null
        }

    private fun readValidated(file: Path): ValidatedRecent {
        val bytes = BoundedFile.read(file, MAX_BYTES)
        // Invalid UTF-8 is refused instead of being repaired into a list we would save back.
        val element = ContentJson.parse(ContentJson.decodeText(bytes))
        val array = element as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$")
        val texts =
            array.mapIndexed { index, entry ->
                val path = "$[" + index + "]"
                val primitive = entry as? JsonPrimitive ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path)
                if (!primitive.isString) throw ContentException(ContentErrorCode.INVALID_VALUE, path)
                primitive.content
            }
        if (texts.size > MAX_ENTRIES) throw ContentException(ContentErrorCode.INVALID_VALUE, "$")
        return ValidatedRecent(bytes, texts)
    }

    private fun decodeFile(
        file: Path,
        onError: ((ContentException) -> Unit)?,
    ): List<String>? {
        if (!Files.exists(file)) return null
        return try {
            readValidated(file).texts
        } catch (error: ContentException) {
            onError?.invoke(error)
            null
        }
    }

    companion object {
        /** Default cap of the recent list. */
        const val DEFAULT_LIMIT: Int = 20

        /** Hard cap accepted from a file, independent of the configured [limit]. */
        const val MAX_ENTRIES: Int = 100

        /** Byte budget of either recent file. */
        const val MAX_BYTES: Int = 16 * 1024

        /** File name of the current value. */
        const val ACTIVE_FILE_NAME: String = "recent.json"

        /** File name of the previous good value. */
        const val BACKUP_FILE_NAME: String = "recent-last.json"

        private val EMPTY_LIST: ByteArray = "[]".toByteArray(Charsets.UTF_8)

        /**
         * One lock for every store in the process, because separate instances write the same files;
         * a per-instance lock would let two of them read one list and overwrite each other's use.
         * Reentrant so [record] can write through [save] inside its own critical section.
         */
        private val writeLock = ReentrantLock()
    }
}

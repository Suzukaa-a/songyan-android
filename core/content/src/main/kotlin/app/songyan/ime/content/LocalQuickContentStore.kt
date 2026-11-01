package app.songyan.ime.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One user phrase: the text to insert plus an optional search shortcut.
 *
 * [id] is a stable UUID string so editing an entry keeps its identity across platforms. New ids
 * are lowercase canonical UUIDs; the iOS app writes uppercase, so ids are normalized while
 * decoding and a phrase keeps its identity whichever platform wrote the file.
 */
data class CommonPhrase(
    val text: String,
    val shortcut: String = "",
    val id: String = UUID.randomUUID().toString(),
) {
    /** Whether [query] matches the text or the shortcut, normalized like symbol search. */
    fun matches(query: String): Boolean {
        val value = SymbolSearch.normalize(query)
        if (value.isEmpty()) return true
        return SymbolSearch.normalize(text).contains(value) || SymbolSearch.normalize(shortcut).contains(value)
    }
}

/**
 * The quick-content state: user phrases plus the opt-in clipboard history.
 *
 * [clipboard] is newest first and always empty while [clipboardEnabled] is false, so turning
 * the feature off erases what was collected.
 */
data class QuickContentState(
    val version: Int = 1,
    val phrases: List<CommonPhrase> = emptyList(),
    val clipboardEnabled: Boolean = false,
    val clipboard: List<String> = emptyList(),
)

/**
 * Local-only storage for user phrases and clipboard history: `content.json` inside an injected
 * directory, plus a `.lock` file that serializes read-modify-write transactions.
 *
 * This model knows nothing about Android on purpose: the caller reads the system clipboard,
 * decides what may be recorded and passes plain text in, and owns the Auto Backup rules (history
 * is excluded from backup, so it stays on this device). No pasteboard API is touched here.
 *
 * Invariants, enforced on every read and write so a hand-edited file cannot break the keyboard:
 * - at most [MAXIMUM_PHRASES] phrases with distinct ids and valid text;
 * - history off by default, at most [MAXIMUM_HISTORY] distinct entries of at most
 *   [MAXIMUM_TEXT_BYTES] bytes each, and empty while disabled;
 * - a file above [MAXIMUM_FILE_BYTES] is rejected rather than loaded.
 *
 * Failures are [ContentException]s; the UI maps the code to copy. A failed write leaves the
 * previous file intact and every write is atomic.
 */
class LocalQuickContentStore(
    val directory: Path,
) {
    val contentFile: Path
        get() = directory.resolve(FILE_NAME)

    /** The clipboard history lives apart from the phrases so that Auto Backup can take one and not the other. */
    val clipboardFile: Path
        get() = directory.resolve(CLIPBOARD_FILE_NAME)

    private val lockFile: Path
        get() = directory.resolve(LOCK_FILE_NAME)

    /**
     * The stored state. Missing storage is the disabled, empty state and is not created as a
     * side effect of reading.
     */
    fun read(): QuickContentState {
        if (!Files.exists(directory)) return QuickContentState()
        return load()
    }

    /** Turns history collection on or off; turning it off erases everything collected. */
    fun setClipboardEnabled(enabled: Boolean): QuickContentState =
        mutate { state ->
            state.copy(clipboardEnabled = enabled, clipboard = if (enabled) state.clipboard else emptyList())
        }

    /**
     * Records an explicit user paste. Returns false when history is disabled; oversized text is
     * rejected whole instead of truncated, so the caller should consult [canRecord] first and not
     * report a successful paste as a failure.
     */
    fun recordExplicitPaste(text: String): Boolean {
        if (!Files.exists(contentFile)) return false
        if (!read().clipboardEnabled) return false
        return locked {
            val state = load()
            if (!state.clipboardEnabled) return@locked false
            validateText(text)
            save(state.copy(clipboard = insertFront(state.clipboard, text)))
            true
        }
    }

    /**
     * Records a clipboard change the user asked history to keep.
     *
     * Text the host marked sensitive is never stored; neither is text the model refuses (blank,
     * oversized, history off). All of those return false without touching storage, so the caller
     * never surfaces a rejection for a copy that already succeeded. A storage failure is reported
     * through [onError] and also returns false.
     */
    fun recordCapturedClipboardText(
        text: String,
        isSensitive: Boolean,
        onError: ((ContentException) -> Unit)? = null,
    ): Boolean {
        if (isSensitive) return false
        if (!canRecord(text)) return false
        return try {
            recordExplicitPaste(text)
        } catch (error: ContentException) {
            onError?.invoke(error)
            false
        }
    }

    fun clearClipboard(): QuickContentState = mutate { it.copy(clipboard = emptyList()) }

    fun deleteClipboard(text: String): QuickContentState =
        mutate { it.copy(clipboard = it.clipboard.filter { entry -> entry != text }) }

    /** Adds [phrase], or replaces the entry that already has the same id. */
    fun savePhrase(phrase: CommonPhrase): QuickContentState =
        mutate { state ->
            val index = state.phrases.indexOfFirst { it.id == phrase.id }
            val phrases =
                if (index >= 0) {
                    state.phrases.toMutableList().also { it[index] = phrase }
                } else {
                    state.phrases + phrase
                }
            state.copy(phrases = phrases)
        }

    fun deletePhrase(id: String): QuickContentState =
        mutate { it.copy(phrases = it.phrases.filter { phrase -> phrase.id != id }) }

    /** Exports the phrases only; history and its opt-in flag are never transferred. */
    fun exportPhrases(): ByteArray = encodePhraseArchive(read().phrases)

    /**
     * Imports a phrase archive, replacing the stored phrases. A rejected archive (format,
     * version, limits) throws and leaves the stored phrases untouched.
     */
    fun importPhrases(bytes: ByteArray): QuickContentState {
        if (bytes.size > MAXIMUM_FILE_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$")
        val source = ContentJson.parseObject(ContentJson.parse(ContentJson.decodeText(bytes)))
        val version = ContentJson.requireInt(source, "version", "$")
        if (version != 1) throw ContentException(ContentErrorCode.UNSUPPORTED_VERSION, "$.version")
        val phrasesElement = source["phrases"] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.phrases")
        val phrases = decodePhrases(phrasesElement)
        validatePhrases(phrases)
        return mutate { it.copy(phrases = phrases) }
    }

    /** Same as [importPhrases], read from a user file within [MAXIMUM_FILE_BYTES]. */
    fun importPhrasesFile(path: Path): QuickContentState = importPhrases(BoundedFile.read(path, MAXIMUM_FILE_BYTES))

    private fun insertFront(
        entries: List<String>,
        text: String,
    ): List<String> {
        val updated = entries.filter { it != text }.toMutableList()
        updated.add(0, text)
        return updated.take(MAXIMUM_HISTORY)
    }

    private fun mutate(body: (QuickContentState) -> QuickContentState): QuickContentState =
        locked {
            val next = body(load())
            save(next)
            next
        }

    private fun load(): QuickContentState {
        if (!Files.exists(contentFile)) return QuickContentState()
        val bytes = BoundedFile.read(contentFile, MAXIMUM_FILE_BYTES)
        // Invalid UTF-8 is refused, never repaired: a save would otherwise persist U+FFFD.
        val source = ContentJson.parseObject(ContentJson.parse(ContentJson.decodeText(bytes)))
        val version = ContentJson.requireInt(source, "version", "$")
        val phrases =
            decodePhrases(source["phrases"] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.phrases"))
        val enabled = ContentJson.requireBoolean(source, "clipboardEnabled", "$")
        val legacy =
            decodeStringArray(
                source["clipboard"] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.clipboard"),
                "$.clipboard",
            )
        val clipboard = if (enabled) loadClipboard(legacy) else emptyList()
        val state =
            QuickContentState(version = version, phrases = phrases, clipboardEnabled = enabled, clipboard = clipboard)
        validate(state)
        return state
    }

    /**
     * The history file, or the history an older version kept inside `content.json` until the next
     * save moves it. History is disposable: an unreadable file is an empty history, never a failure
     * that would take the phrases down with it.
     */
    private fun loadClipboard(legacy: List<String>): List<String> {
        if (!Files.exists(clipboardFile)) return legacy
        return try {
            val bytes = BoundedFile.read(clipboardFile, MAXIMUM_FILE_BYTES)
            val source = ContentJson.parseObject(ContentJson.parse(ContentJson.decodeText(bytes)))
            val entries = decodeStringArray(source["clipboard"] ?: return emptyList(), "$.clipboard")
            if (entries.size > MAXIMUM_HISTORY || entries.distinct().size != entries.size) return emptyList()
            entries.forEach { validateText(it) }
            entries
        } catch (error: ContentException) {
            emptyList()
        }
    }

    private fun save(state: QuickContentState) {
        validate(state)
        // History first: a crash in between leaves the phrases as they were and an at worst newer history.
        val historyBytes =
            ContentJson.pretty
                .encodeToString(
                    JsonElement.serializer(),
                    buildJsonObject {
                        put("version", state.version)
                        put("clipboard", buildJsonArray { state.clipboard.forEach { add(JsonPrimitive(it)) } })
                    },
                ).toByteArray(Charsets.UTF_8)
        if (historyBytes.size > MAXIMUM_FILE_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$.clipboard")
        if (state.clipboard.isNotEmpty()) {
            BoundedFile.writeAtomically(clipboardFile, historyBytes)
            restrictPermissions(clipboardFile)
        } else {
            try {
                Files.deleteIfExists(clipboardFile)
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, CLIPBOARD_FILE_NAME, error)
            }
        }
        val element = encodeState(state)
        val bytes = ContentJson.pretty.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_FILE_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$")
        BoundedFile.writeAtomically(contentFile, bytes)
        restrictPermissions(contentFile)
    }

    private fun encodeState(state: QuickContentState): JsonObject =
        buildJsonObject {
            put("version", state.version)
            put(
                "phrases",
                buildJsonArray { state.phrases.forEach { add(encodePhrase(it)) } },
            )
            put("clipboardEnabled", state.clipboardEnabled)
            // Kept (empty) so the file stays readable by older versions; the history is in clipboard.json.
            put("clipboard", buildJsonArray { })
        }

    private fun encodePhrase(phrase: CommonPhrase): JsonObject =
        buildJsonObject {
            put("id", phrase.id)
            put("text", phrase.text)
            put("shortcut", phrase.shortcut)
        }

    private fun encodePhraseArchive(phrases: List<CommonPhrase>): ByteArray {
        validatePhrases(phrases)
        val element =
            buildJsonObject {
                put("version", 1)
                put(
                    "phrases",
                    buildJsonArray { phrases.forEach { add(encodePhrase(it)) } },
                )
            }
        val bytes = ContentJson.pretty.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_FILE_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$")
        return bytes
    }

    private fun decodePhrases(element: JsonElement): List<CommonPhrase> {
        val array = element as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.phrases")
        return array.mapIndexed { index, entry ->
            val path = "$.phrases[" + index + "]"
            val source = ContentJson.parseObject(entry)
            CommonPhrase(
                text = ContentJson.requireString(source, "text", path),
                shortcut = ContentJson.requireString(source, "shortcut", path),
                id = normalizePhraseId(ContentJson.requireString(source, "id", path), path + ".id"),
            )
        }
    }

    private fun decodeStringArray(
        element: JsonElement,
        path: String,
    ): List<String> {
        val array = element as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path)
        return array.mapIndexed { index, entry ->
            val entryPath = path + "[" + index + "]"
            val primitive = entry as? JsonPrimitive ?: throw ContentException(ContentErrorCode.INVALID_VALUE, entryPath)
            if (!primitive.isString) throw ContentException(ContentErrorCode.INVALID_VALUE, entryPath)
            primitive.content
        }
    }

    private fun normalizePhraseId(
        value: String,
        path: String,
    ): String =
        try {
            UUID.fromString(value).toString()
        } catch (error: IllegalArgumentException) {
            throw ContentException(ContentErrorCode.INVALID_VALUE, path, error)
        }

    private fun validate(state: QuickContentState) {
        requireField(state.version == 1, "$.version")
        requireField(state.clipboard.size <= MAXIMUM_HISTORY, "$.clipboard")
        requireField(state.clipboard.distinct().size == state.clipboard.size, "$.clipboard")
        requireField(state.clipboardEnabled || state.clipboard.isEmpty(), "$.clipboard")
        validatePhrases(state.phrases)
        state.clipboard.forEachIndexed { index, text -> validateText(text, "$.clipboard[" + index + "]") }
    }

    private fun validatePhrases(phrases: List<CommonPhrase>) {
        requireField(phrases.size <= MAXIMUM_PHRASES, "$.phrases")
        requireField(phrases.map { it.id }.distinct().size == phrases.size, "$.phrases")
        phrases.forEachIndexed { index, phrase ->
            val path = "$.phrases[" + index + "]"
            validateText(phrase.text, path + ".text")
            requireField(phrase.shortcut.toByteArray(Charsets.UTF_8).size <= MAXIMUM_SHORTCUT_BYTES, path + ".shortcut")
        }
    }

    private fun validateText(
        text: String,
        path: String = "$",
    ) {
        requireField(text.trim { it.isWhitespace() }.isNotEmpty(), path)
        requireField(text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_TEXT_BYTES, path)
        requireField(!text.contains('\u0000'), path)
    }

    /**
     * Serializes read-modify-write transactions: one in-process lock (so two threads of the same
     * app cannot interleave, where a file lock alone would throw) plus an advisory OS lock on
     * `.lock` (so the settings UI and the IME process agree). A symlinked `.lock` is refused
     * instead of followed.
     */
    private fun <T> locked(body: () -> T): T =
        PROCESS_LOCK.withLock {
            try {
                Files.createDirectories(directory)
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, DIRECTORY_NAME, error)
            }
            if (Files.isSymbolicLink(lockFile)) throw ContentException(ContentErrorCode.LOCK_FAILED, LOCK_FILE_NAME)
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
                        return body()
                    } finally {
                        lock.release()
                    }
                }
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, LOCK_FILE_NAME, error)
            }
        }

    /** History and phrases stay owner-only; the IME runs as the same app user. */
    private fun restrictPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (error: UnsupportedOperationException) {
            // A non-POSIX file system keeps its default permissions.
        } catch (error: IOException) {
            // Hardening is best-effort: the content was already committed atomically.
        }
    }

    companion object {
        /** Recommended directory name below the app's content directory. */
        const val DIRECTORY_NAME: String = "content"

        /** File name of the stored state. */
        const val FILE_NAME: String = "content.json"

        /** File name of the clipboard history, excluded from Auto Backup (it stays on this device). */
        const val CLIPBOARD_FILE_NAME: String = "clipboard.json"

        /** Lock file name inside the directory. */
        const val LOCK_FILE_NAME: String = ".lock"

        /** Phrase budget. */
        const val MAXIMUM_PHRASES: Int = 100

        /** Clipboard history budget. */
        const val MAXIMUM_HISTORY: Int = 20

        /** Byte budget of one phrase or clipboard entry (bytes, not characters). */
        const val MAXIMUM_TEXT_BYTES: Int = 4096

        /** Shortcut byte budget. */
        const val MAXIMUM_SHORTCUT_BYTES: Int = 64

        /** Byte budget of the whole state file and of an imported phrase archive. */
        const val MAXIMUM_FILE_BYTES: Int = 1024 * 1024

        /** Whether history could hold [text] at all; callers skip what fails instead of erroring. */
        fun canRecord(text: String): Boolean {
            if (text.trim { it.isWhitespace() }.isEmpty()) return false
            if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_TEXT_BYTES) return false
            return !text.contains('\u0000')
        }

        /** One lock for every instance in this process; the file lock covers other processes. */
        private val PROCESS_LOCK = ReentrantLock()
    }
}

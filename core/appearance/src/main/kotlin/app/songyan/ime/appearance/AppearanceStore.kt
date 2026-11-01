package app.songyan.ime.appearance

import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * Unified appearance snapshot: the selection and its content commit atomically, so one read yields a
 * skin and a candidate area from the same commit.
 */
@Serializable
data class AppearanceSnapshot(
    val selectedID: String,
    val document: AppearanceDocument,
) {
    /** Runtime consistency check, shared by the read path and tests. */
    val identityIsConsistent: Boolean get() = selectedID == document.id && document.identityIsConsistent

    val identityDescription: String get() = selectedID + "|" + document.identityDescription

    companion object {
        fun of(document: AppearanceDocument): AppearanceSnapshot =
            AppearanceSnapshot(selectedID = document.id, document = document)
    }
}

/** Where a read result came from. */
enum class AppearanceSnapshotSource {
    /** The applied snapshot was read successfully. */
    APPLIED,

    /** Nothing usable (missing or unreadable): the built-in system default. */
    SYSTEM_DEFAULT,
}

/** Runtime read result: never throws; an unreadable snapshot yields the built-in default plus a clear error. */
data class AppearanceReadResult(
    val snapshot: AppearanceSnapshot,
    val source: AppearanceSnapshotSource,
    /** The rejection that degraded the read; null on a clean read. */
    val error: AppearanceError? = null,
) {
    val identityIsConsistent: Boolean get() = snapshot.identityIsConsistent
}

/**
 * Persistence for appearance projects and the applied snapshot.
 *
 * Every call is synchronous by design: the host runs them off the main thread (the IME and the
 * settings app both do). The applied snapshot is written atomically so a reader never observes a
 * half-written document.
 */
class AppearanceStore(
    val directoryPath: String,
) {
    private val directory: Path get() = Paths.get(directoryPath)

    private val activePath: Path get() = directory.resolve(ACTIVE_FILE_NAME)

    /**
     * All appearances for a picker: the built-ins first, then the user's own, sorted by name.
     *
     * A file that cannot be read or decoded is skipped and reported through [onError]; it never
     * hides the appearances that are valid, and it can never make every selection fail.
     */
    fun list(onError: ((AppearanceError) -> Unit)? = null): List<AppearanceDocument> {
        val builtIns = AppearancePresets.ALL_BUILT_IN
        if (!Files.exists(directory)) return builtIns
        val files =
            try {
                Files.list(directory).use { stream ->
                    stream
                        .filter {
                            Files.isRegularFile(it) &&
                                it.fileName.toString().endsWith(AppearanceDocument.FILE_EXTENSION)
                        }.sorted(compareBy { it.fileName.toString() })
                        .collect(Collectors.toList())
                }
            } catch (error: IOException) {
                onError?.invoke(AppearanceError(AppearanceErrorCode.STORAGE_UNAVAILABLE, directoryName(), error))
                return builtIns
            }
        val documents = mutableListOf<AppearanceDocument>()
        for (file in files) {
            try {
                documents.add(readProject(file))
            } catch (error: AppearanceError) {
                onError?.invoke(error)
            } catch (error: IOException) {
                onError?.invoke(
                    AppearanceError(
                        AppearanceErrorCode.STORAGE_UNAVAILABLE,
                        file.fileName?.toString() ?: "$",
                        error,
                    ),
                )
            }
        }
        // A saved copy of a built-in is not listed twice.
        return builtIns + documents.filterNot { AppearancePresets.isBuiltIn(it) }.sortedBy { it.name }
    }

    /** One stored project; the file name must match the document identity. */
    @Throws(AppearanceError::class)
    private fun readProject(file: Path): AppearanceDocument {
        val text =
            readBounded(file, AppearanceDocument.MAX_BYTES)
                ?: throw AppearanceError(AppearanceErrorCode.UNREADABLE_SOURCE, file.fileName.toString())
        val document = AppearanceDocument.decode(text)
        val stem = file.fileName.toString().removeSuffix(AppearanceDocument.FILE_EXTENSION)
        if (stem != document.id) {
            throw AppearanceError(AppearanceErrorCode.IDENTITY_MISMATCH, file.fileName.toString())
        }
        return document
    }

    /** Validates and writes one project; an existing id is kept unless [replacing] is set. */
    @Throws(AppearanceError::class)
    fun save(
        document: AppearanceDocument,
        replacing: Boolean = false,
    ) {
        if (document.isSystemDefault) {
            throw AppearanceError(AppearanceErrorCode.READ_ONLY_BUILTIN, document.id)
        }
        val text = document.encode()
        val file = directory.resolve(document.id + AppearanceDocument.FILE_EXTENSION)
        if (!replacing && Files.exists(file)) {
            throw AppearanceError(AppearanceErrorCode.ALREADY_EXISTS, document.id)
        }
        writeAtomically(file, text)
    }

    /** Deletes one project; the applied snapshot keeps its content until the next apply. */
    @Throws(AppearanceError::class)
    fun remove(id: String) {
        if (id.isEmpty() || !id.all { it in SAFE_ID_CHARS }) {
            throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.id")
        }
        if (id == AppearanceDocument.SYSTEM_DEFAULT_ID) {
            throw AppearanceError(AppearanceErrorCode.READ_ONLY_BUILTIN, id)
        }
        val file = directory.resolve(id + AppearanceDocument.FILE_EXTENSION)
        if (!Files.exists(file)) throw AppearanceError(AppearanceErrorCode.NOT_FOUND, id)
        Files.delete(file)
    }

    /** Validates, then writes one snapshot atomically: skin, candidate area and display mode commit together. */
    @Throws(AppearanceError::class)
    fun apply(document: AppearanceDocument) {
        // Decoding the re-encoded document is the strictest validation available, so an invalid
        // document can never reach the keyboard.
        val validated = AppearanceDocument.decode(document.encode())
        val text =
            StrictJsonShared.encoder.encodeToString(
                AppearanceSnapshot.serializer(),
                AppearanceSnapshot.of(validated),
            )
        writeAtomically(activePath, text)
    }

    /** Strict read: a missing snapshot is the built-in default; a corrupt one throws. */
    @Throws(AppearanceError::class)
    fun readSnapshot(): AppearanceSnapshot {
        if (!Files.exists(activePath)) return AppearanceSnapshot.of(AppearanceDocument.systemDefault)
        return decodeSnapshot(activePath)
    }

    /**
     * Runtime read entry: never throws. A missing, unreadable or corrupt snapshot yields the
     * built-in default plus the error, because a broken file must never keep the keyboard from
     * painting (the caller is the paint path, not a diagnostic screen).
     */
    fun read(): AppearanceReadResult =
        try {
            if (!Files.exists(activePath)) {
                AppearanceReadResult(
                    snapshot = AppearanceSnapshot.of(AppearanceDocument.systemDefault),
                    source = AppearanceSnapshotSource.SYSTEM_DEFAULT,
                )
            } else {
                AppearanceReadResult(
                    snapshot = decodeSnapshot(activePath),
                    source = AppearanceSnapshotSource.APPLIED,
                )
            }
        } catch (error: AppearanceError) {
            degraded(error)
        } catch (error: Exception) {
            // Storage/filesystem failures are not AppearanceErrors yet must not escape this entry.
            degraded(AppearanceError(AppearanceErrorCode.STORAGE_UNAVAILABLE, ACTIVE_FILE_NAME, error))
        }

    private fun degraded(error: AppearanceError): AppearanceReadResult =
        AppearanceReadResult(
            snapshot = AppearanceSnapshot.of(AppearanceDocument.systemDefault),
            source = AppearanceSnapshotSource.SYSTEM_DEFAULT,
            error = error,
        )

    /** The applied palette for a light/dark decision, so the keyboard has one read for both. */
    fun readPalette(dark: Boolean): KeyboardPalette = KeyboardPalette.of(read().snapshot.document, dark)

    private fun decodeSnapshot(file: Path): AppearanceSnapshot {
        val text =
            readBounded(file, SNAPSHOT_MAX_BYTES)
                ?: throw AppearanceError(AppearanceErrorCode.UNREADABLE_SOURCE, file.fileName.toString())
        val snapshot =
            try {
                StrictJsonShared.decoder.decodeFromString(AppearanceSnapshot.serializer(), text)
            } catch (error: IllegalArgumentException) {
                throw AppearanceError(AppearanceErrorCode.MALFORMED_JSON, "$")
            }
        // Strict decoding of the embedded document rejects unknown fields, wrong versions and mixed identities.
        val decoded = AppearanceDocument.decode(snapshot.document.encode())
        if (decoded != snapshot.document.normalized()) {
            throw AppearanceError(AppearanceErrorCode.INCONSISTENT_SNAPSHOT, "$.document")
        }
        if (snapshot.selectedID != snapshot.document.id) {
            throw AppearanceError(AppearanceErrorCode.INCONSISTENT_SNAPSHOT, "$.selectedID")
        }
        if (!snapshot.document.identityIsConsistent) {
            throw AppearanceError(AppearanceErrorCode.INCONSISTENT_SNAPSHOT, "$.document")
        }
        return snapshot
    }

    private fun readBounded(
        file: Path,
        maximumBytes: Int,
    ): String? {
        val name = file.fileName?.toString() ?: "$"
        return try {
            if (!Files.isRegularFile(file)) return null
            // The size probe can fail like any other read (file removed meanwhile, EIO); it must
            // not escape as a raw IOException from a never-throwing read path.
            if (Files.size(file) > maximumBytes) {
                throw AppearanceError(AppearanceErrorCode.OVERSIZE, name)
            }
            // Files.readString is a Java 11 API that Android's java.nio.file does not ship.
            Files.readAllBytes(file).decodeToString()
        } catch (error: AppearanceError) {
            throw error
        } catch (error: IOException) {
            throw AppearanceError(AppearanceErrorCode.UNREADABLE_SOURCE, name, error)
        }
    }

    private fun writeAtomically(
        target: Path,
        text: String,
    ) {
        val name = target.fileName?.toString() ?: "$"
        var temporary: Path? = null
        try {
            Files.createDirectories(directory)
            temporary = Files.createTempFile(directory, "appearance", ".tmp")
            // Files.writeString is a Java 11 API that Android's java.nio.file does not ship.
            Files.write(temporary, text.encodeToByteArray())
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: IOException) {
            throw AppearanceError(AppearanceErrorCode.STORAGE_UNAVAILABLE, name, error)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun directoryName(): String = directory.fileName?.toString() ?: directoryPath

    companion object {
        /** The applied snapshot's file name. */
        const val ACTIVE_FILE_NAME: String = "active.json"

        /** Byte budget of the applied snapshot. */
        const val SNAPSHOT_MAX_BYTES: Int = 512 * 1024

        private const val SAFE_ID_CHARS: String =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
    }
}

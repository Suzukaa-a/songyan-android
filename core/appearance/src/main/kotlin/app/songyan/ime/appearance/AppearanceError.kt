package app.songyan.ime.appearance

/** Why a skin or appearance document was rejected. The UI maps it onto string resources. */
enum class AppearanceErrorCode {
    OVERSIZE,
    NOT_AN_OBJECT,
    UNKNOWN_FIELD,
    UNSUPPORTED_VERSION,
    INVALID_VALUE,
    MALFORMED_JSON,

    /** A skin and a candidate area with different identities were combined (one commit must own both). */
    IDENTITY_MISMATCH,

    /** The file is a snapshot (selection plus content), not an importable configuration. */
    SNAPSHOT_NOT_IMPORTABLE,

    /** The payload is not a skin, a candidate area or an appearance project. */
    UNRECOGNIZED_DOCUMENT,

    /** A user-supplied path is not a regular local file or is over the byte budget. */
    UNREADABLE_SOURCE,

    /** The document could not be read from or written to disk (an IO failure, not a bad document). */
    STORAGE_UNAVAILABLE,

    /** The target file already exists and replacement was not requested. */
    ALREADY_EXISTS,

    /** The built-in default cannot be written or deleted. */
    READ_ONLY_BUILTIN,

    /** Nothing exists under that id. */
    NOT_FOUND,

    /** A stored snapshot does not describe one consistent appearance. */
    INCONSISTENT_SNAPSHOT,
}

/**
 * A rejected appearance document: a stable [code] plus the offending JSON [path].
 *
 * The [Exception.message] is developer-facing English; user-facing copy comes from resources.
 */
class AppearanceError(
    val code: AppearanceErrorCode,
    val path: String = "$",
    cause: Throwable? = null,
) : Exception(code.name + " at " + path, cause)

/** Throws when [condition] is false; keeps validation readable and path-accurate. */
internal inline fun requireField(
    condition: Boolean,
    path: String,
) {
    if (!condition) throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, path)
}

package app.songyan.ime.content

/**
 * Why a quick-content document or file was rejected.
 *
 * Models never carry user-facing copy: the UI maps [ContentErrorCode] plus the JSON
 * [ContentException.path] onto localized strings from resources.
 */
enum class ContentErrorCode {
    /** The source is not a regular file: a directory, a pipe, or a dangling link. */
    NOT_A_REGULAR_FILE,

    /** The source exceeds the format byte budget. */
    OVERSIZE,

    /** The payload is not valid JSON. */
    MALFORMED_JSON,

    /** The JSON top level is not an object. */
    NOT_AN_OBJECT,

    /** A field the format does not know (unknown fields are rejected, never ignored). */
    UNKNOWN_FIELD,

    /** `version` is missing or names a version this build cannot read. */
    UNSUPPORTED_VERSION,

    /** A field is missing, or outside its documented range or shape. */
    INVALID_VALUE,

    /** The file or directory could not be read or written. */
    STORAGE_UNAVAILABLE,

    /** The cross-process lock could not be taken. */
    LOCK_FAILED,
}

/**
 * A rejected quick-content document or file: a stable [code] plus the offending JSON [path]
 * (`$` for the whole document, `$.items[2].text` for a field).
 *
 * The [Exception.message] is developer-facing English; it must never be shown to a user.
 */
class ContentException(
    val code: ContentErrorCode,
    val path: String,
    cause: Throwable? = null,
) : Exception(code.name + " at " + path, cause)

/** Throws when [condition] is false; keeps validation readable and path-accurate. */
internal fun requireField(
    condition: Boolean,
    path: String,
) {
    if (!condition) throw ContentException(ContentErrorCode.INVALID_VALUE, path)
}

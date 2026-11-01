package app.songyan.ime.candidate

/**
 * Why a candidate-area document was rejected.
 *
 * Models never carry user-facing copy: the UI maps [CandidateBarErrorCode] plus the JSON
 * [CandidateBarError.path] onto localized strings from resources.
 */
enum class CandidateBarErrorCode {
    /** The encoded document exceeds the format's byte budget. */
    OVERSIZE,

    /** The payload is not a JSON object. */
    NOT_AN_OBJECT,

    /** A field the format does not know (unknown fields are rejected, never ignored). */
    UNKNOWN_FIELD,

    /** `formatVersion` is missing or names a version this build cannot read. */
    UNSUPPORTED_VERSION,

    /** A field is outside its documented range or shape. */
    INVALID_VALUE,

    /** The document cannot be read at all (manifestly malformed JSON). */
    MALFORMED_JSON,
}

/**
 * A rejected candidate-area document: a stable [code] plus the offending JSON [path]
 * (`$` for the whole document, `$.collapsed.height` for a field).
 *
 * The [Exception.message] is developer-facing English; it must not be shown to a user.
 */
class CandidateBarError(
    val code: CandidateBarErrorCode,
    val path: String,
) : Exception(code.name + " at " + path)

/** Throws when [condition] is false; keeps validation readable and path-accurate. */
internal inline fun requireField(
    condition: Boolean,
    path: String,
) {
    if (!condition) throw CandidateBarError(CandidateBarErrorCode.INVALID_VALUE, path)
}

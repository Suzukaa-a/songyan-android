package app.songyan.ime.content

/**
 * Why a tool definition was rejected.
 *
 * The reasons are the shared vocabulary of the tool file format; the UI maps [Reason] onto
 * localized strings from resources, so no user-facing sentence is compiled into this module.
 * [Exception.message] is developer-facing only and must never be shown to a user.
 */
class ToolDefinitionError(
    val reason: Reason,
) : Exception(reason.toString()) {
    /** The failed rule, with the arguments the UI needs to point at the offending place. */
    sealed class Reason {
        /** The source is not a regular file: a directory, a pipe, or a missing file. */
        data object NotAFile : Reason()

        /** The file is larger than the byte budget, which is also the read limit. */
        data class FileTooLarge(
            val maximumKilobytes: Int,
        ) : Reason()

        /** The bytes are not a JSON object, or their shape is not a tool file at all. */
        data object Unreadable : Reason()

        /** The format version is one this build cannot read. */
        data class UnsupportedFormatVersion(
            val version: String,
        ) : Reason()

        /** A required field is absent. */
        data class MissingField(
            val path: String,
        ) : Reason()

        /** A field is outside its documented shape or range. */
        data class InvalidField(
            val path: String,
        ) : Reason()

        /** The definition has no steps, so it would do nothing. */
        data object NoSteps : Reason()

        /** The definition has more steps than the format allows. */
        data class TooManySteps(
            val count: Int,
        ) : Reason()

        /** A step names a type this build does not run. */
        data class UnsupportedStepType(
            val step: Int,
            val type: String,
        ) : Reason()

        /** An OpenCC step names a conversion the app does not ship. */
        data class UnavailableOpenCCConfig(
            val step: Int,
            val config: String,
        ) : Reason()

        /** A replace step's pattern does not compile. */
        data class InvalidRegex(
            val step: Int,
        ) : Reason()
    }
}

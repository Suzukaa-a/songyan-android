package app.songyan.ime.tools

/** Why a tool cannot run on this editor or this host. */
enum class UnavailableReason {
    /** The host lacks a capability the tool requires; [ToolFailure.Unavailable] names the set. */
    MISSING_CAPABILITIES,

    /** The editor is a password or otherwise secured field. */
    SECURE_ENTRY,

    /** The editor returned no text to work on. */
    NO_TEXT,
}

/** Why a two-phase write or undo was refused before the host touched the document. */
enum class WriteBlockReason {
    /** The document identity changed since the run read its input. */
    DOCUMENT_CHANGED,

    /** The focused editor is now a secured field. */
    SECURE_ENTRY,

    /** The focused editor no longer returns text, so a write cannot be aimed at it. */
    NO_TEXT,

    /** The text the run wrote has changed since: the user edited it, so undo must not overwrite. */
    EDITED_AFTER_WRITE,

    /**
     * The write needed a capability the gateway has not declared: replacing the verified tail of a
     * truncated preceding text (see [ToolDocumentCapabilities.replacePrecedingTail]). A truncated
     * selection is refused with the same reason as a temporary fail-closed rule until a gateway can
     * carry that write safely.
     */
    UNSUPPORTED_PARTIAL_REPLACEMENT,

    /**
     * An insert would have to keep an active selection's text, and the gateway has not declared
     * [ToolDocumentCapabilities.insertPreservingSelection]. The write is refused instead of
     * replacing the selection's content.
     */
    UNSUPPORTED_INSERTION,
}

/**
 * Why a run has no result. The model gives a type and, for a failing tool, the tool's own message;
 * user-visible wording (and prefixing the tool name) belongs to the UI.
 */
sealed interface ToolFailure {
    data class Unavailable(
        val reason: UnavailableReason,
        val missingCapabilities: Set<ToolCapability> = emptySet(),
    ) : ToolFailure

    data object Cancelled : ToolFailure

    data object TimedOut : ToolFailure

    data object NoResult : ToolFailure

    /** [code] names the failure when the host classified it (a script runtime's error name);
     *  null or [ToolRunException.TOOL_OWN_WORDS] means [message] is the tool's own words. */
    data class ToolError(
        val message: String?,
        val code: String? = null,
        val line: Int = 0,
    ) : ToolFailure
}

/**
 * A run that failed with a classified reason. A host throws it so the panel can word known reasons
 * itself and show a tool's own message as written, with the line it names when there is one.
 */
open class ToolRunException(
    val code: String,
    message: String,
    val line: Int = 0,
) : IllegalStateException(message) {
    companion object {
        /** The failure is the tool's own: its message is shown verbatim. */
        const val TOOL_OWN_WORDS = "script"
    }
}

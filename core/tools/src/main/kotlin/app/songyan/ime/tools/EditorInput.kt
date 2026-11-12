package app.songyan.ime.tools

/** How the host may treat the focused editor. A password field never hands text to a tool. */
enum class EditorPrivacy {
    NORMAL,

    /** The editor carries IME_FLAG_NO_PERSONALIZED_LEARNING: no learning, tools still work. */
    NO_PERSONALIZED_LEARNING,

    /** Password or otherwise secured field: no input and no content read. */
    PASSWORD,

    /** An editor that cannot return text, such as TYPE_NULL without an input connection. */
    NO_TEXT,
}

/** Where the processed text came from: the selection first, otherwise the text before the cursor. */
enum class TextScope {
    SELECTION,
    BEFORE_CURSOR,
}

/** A half-open document range, [start] inclusive and [end] exclusive, in UTF-16 code units. */
data class TextRange(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0) { "start must not be negative: " + start }
        require(end >= start) { "end " + end + " is before start " + start }
    }

    val length: Int get() = end - start
}

/**
 * What the gateway can do with the document. The two abilities are independent and both default to
 * "not declared": [replacePrecedingTail] replaces the verified tail of a truncated preceding text,
 * and [insertPreservingSelection] inserts while an active selection's text survives. A gateway that
 * can do one cannot be assumed to do the other, so each is declared and checked separately.
 */
data class ToolDocumentCapabilities(
    val replacePrecedingTail: Boolean = false,
    val insertPreservingSelection: Boolean = false,
)

/**
 * What the host read from the focused editor. [documentId] is opaque and changes with the
 * document; writes re-check it so an asynchronous result never lands in another input field.
 * A [EditorPrivacy.PASSWORD] snapshot must not carry text: the model rejects that field before it
 * looks at [selection] or [beforeCursor].
 */
data class EditorSnapshot(
    val documentId: String,
    val privacy: EditorPrivacy,
    val selection: String? = null,
    val selectionRange: TextRange? = null,
    val beforeCursor: String? = null,
    val cursorIndex: Int = 0,
    val capabilities: ToolDocumentCapabilities = ToolDocumentCapabilities(),
)

/** The acquired input: the text a tool runs on, where it sits, and the document it came from. */
data class TextSource(
    val documentId: String,
    val text: String,
    val scope: TextScope,
    /** True when the source was longer than the input limit and only its tail is processed. */
    val isTruncated: Boolean,
    /** The document range the processed text occupies, in UTF-16 code units. */
    val range: TextRange,
    /**
     * The whole source text when only its tail was processed, otherwise null because [text] is the
     * whole source. Replace safety compares this full text, never just a matching suffix.
     */
    val fullText: String?,
)

/** The result of reading a tool's input; unavailability carries a typed reason, never UI copy. */
sealed interface InputResult {
    data class Available(
        val source: TextSource,
    ) : InputResult

    data class Unavailable(
        val reason: UnavailableReason,
    ) : InputResult
}

/**
 * Text acquisition (D48): the selection wins, otherwise the text before the cursor. The same
 * injected unit limit applies to both, and only the trailing part that fits is processed: the
 * source nearest the cursor, never split inside a surrogate pair. A run on empty text reports
 * that the editor has no usable text instead of running the tool on "".
 */
object ToolInput {
    fun acquire(
        editor: EditorSnapshot,
        maxUnits: Int,
    ): InputResult {
        require(maxUnits > 0) { "maxUnits must be positive: " + maxUnits }
        if (editor.privacy == EditorPrivacy.PASSWORD) {
            return InputResult.Unavailable(UnavailableReason.SECURE_ENTRY)
        }
        if (editor.privacy == EditorPrivacy.NO_TEXT) {
            return InputResult.Unavailable(UnavailableReason.NO_TEXT)
        }
        val selection = editor.selection
        if (selection != null && selection.isNotEmpty()) {
            val documentRange = requireNotNull(editor.selectionRange) { "a selection needs its document range" }
            require(documentRange.length == selection.length) {
                "selection range " + documentRange.length + " does not match its text " + selection.length
            }
            val text = tailUnits(selection, maxUnits)
            if (text.isEmpty()) return InputResult.Unavailable(UnavailableReason.NO_TEXT)
            return InputResult.Available(
                TextSource(
                    documentId = editor.documentId,
                    text = text,
                    scope = TextScope.SELECTION,
                    isTruncated = text.length < selection.length,
                    range = TextRange(documentRange.end - text.length, documentRange.end),
                    fullText = if (text.length < selection.length) selection else null,
                ),
            )
        }
        val beforeCursor = editor.beforeCursor
        if (beforeCursor != null && beforeCursor.isNotEmpty()) {
            val text = tailUnits(beforeCursor, maxUnits)
            if (text.isEmpty()) return InputResult.Unavailable(UnavailableReason.NO_TEXT)
            require(editor.cursorIndex >= text.length) {
                "cursor " + editor.cursorIndex + " is before the processed text " + text.length
            }
            return InputResult.Available(
                TextSource(
                    documentId = editor.documentId,
                    text = text,
                    scope = TextScope.BEFORE_CURSOR,
                    isTruncated = text.length < beforeCursor.length,
                    range = TextRange(editor.cursorIndex - text.length, editor.cursorIndex),
                    fullText = if (text.length < beforeCursor.length) beforeCursor else null,
                ),
            )
        }
        return InputResult.Unavailable(UnavailableReason.NO_TEXT)
    }

    /** The trailing [maxUnits] UTF-16 code units, moved by one when the cut would split a pair. */
    internal fun tailUnits(
        text: String,
        maxUnits: Int,
    ): String {
        if (text.length <= maxUnits) return text
        var start = text.length - maxUnits
        if (start > 0 && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) {
            start += 1
        }
        return text.substring(start)
    }
}

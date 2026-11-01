package app.songyan.ime.content

/**
 * What the recorder can learn about a clipboard change without reading its content.
 *
 * Reading the description is free; reading the actual clip makes Android show its clipboard
 * toast, so every cheap reason to skip a change has to be decided on this data alone.
 */
data class ClipboardObservation(
    /** Whether the clipboard currently holds a clip at all. */
    val available: Boolean,
    /** Whether the description advertises a text MIME type; the store only keeps text. */
    val textMimeType: Boolean,
    /** Whether the host flagged the clip with `EXTRA_IS_SENSITIVE`. */
    val sensitive: Boolean,
    /** `ClipDescription.getTimestamp()`: identifies the clip version, not its content. */
    val timestamp: Long,
)

/** The outcome of [ClipboardCapturePolicy.decide]; only [READ] may touch the clip content. */
enum class ClipboardDecision {
    /** The change may be read and handed to the store. */
    READ,

    /** Clipboard history is off in the user's preferences. */
    DISABLED,

    /** The keyboard is hidden, or the focused field must not be observed. */
    NOT_ALLOWED,

    /** The clipboard is empty. */
    UNAVAILABLE,

    /** The host marked the clip as sensitive; its content is never touched. */
    SENSITIVE,

    /** The clip carries no text the keyboard could insert. */
    NOT_TEXT,

    /** This clip version was already handled; clipboard listeners may fire more than once. */
    ALREADY_HANDLED,
}

/**
 * The capture decision: history enabled, field allowed, clip worth reading, and not yet handled.
 *
 * Kept free of Android types so the privacy rules are unit-tested in plain JVM: the recorder only
 * maps `ClipDescription` onto [ClipboardObservation] and obeys [decide].
 */
class ClipboardCapturePolicy {
    private var enabled = false
    private var captureAllowed = false
    private var lastHandledTimestamp = NO_TIMESTAMP

    /**
     * Mirrors the stored opt-in flag. Turning history off also drops the handled timestamp, so a
     * clip copied while it was off is captured after turning it back on.
     */
    fun setHistoryEnabled(value: Boolean) {
        enabled = value
        if (!value) lastHandledTimestamp = NO_TIMESTAMP
    }

    /**
     * Mirrors "the keyboard is visible and the focused field is not private". The handled clip is
     * kept across hides: the keyboard looks at the clipboard again each time it shows, and a clip it
     * already took (and the user may have deleted since) must not come back.
     */
    fun setCaptureAllowed(value: Boolean) {
        captureAllowed = value
    }

    /** The handled clip a previous process recorded; a clip handled in this process wins. */
    fun restoreHandled(timestamp: Long) {
        if (lastHandledTimestamp == NO_TIMESTAMP) lastHandledTimestamp = timestamp
    }

    /** Orders the checks so the cheapest and most private one wins: never read a sensitive clip. */
    fun decide(observation: ClipboardObservation): ClipboardDecision =
        when {
            !enabled -> ClipboardDecision.DISABLED
            !captureAllowed -> ClipboardDecision.NOT_ALLOWED
            !observation.available -> ClipboardDecision.UNAVAILABLE
            observation.sensitive -> ClipboardDecision.SENSITIVE
            !observation.textMimeType -> ClipboardDecision.NOT_TEXT
            observation.timestamp == lastHandledTimestamp -> ClipboardDecision.ALREADY_HANDLED
            else -> ClipboardDecision.READ
        }

    /** Called once the clip of an observation has been read, so a repeat notification is skipped. */
    fun markHandled(observation: ClipboardObservation) {
        lastHandledTimestamp = observation.timestamp
    }

    companion object {
        /** `ClipDescription.getTimestamp()` of no clip; never equal to a real timestamp. */
        const val NO_TIMESTAMP: Long = -1L
    }
}

package app.songyan.ime.rime

/** One candidate shown by the engine. */
data class RimeCandidate(
    /** Candidate text (UTF-8, e.g. "你好"). */
    val text: String,
    /** Optional annotation, e.g. the pronunciation or code. */
    val comment: String? = null,
    /** Engine select key for this page slot; null when the schema declares none. */
    val selectKey: String? = null,
    /** Engine label for this page slot; null when the schema declares none. */
    val selectLabel: String? = null,
)

/** One candidate read read-only by absolute index, optionally with its proven pronunciations. */
data class RimeReadCandidate(
    val absoluteIndex: Int,
    val text: String,
    val comment: String,
    val readingPaths: List<List<String>>? = null,
    val readingsComplete: Boolean = false,
    val selectKey: String? = null,
    val selectLabel: String? = null,
)

/**
 * Immutable snapshot of the session state after an action.
 *
 * All strings are copies taken inside the single native call, so a snapshot stays
 * valid across later key presses and can be held by the UI across frames.
 */
data class RimeSnapshot(
    val schemaId: String = "",
    val schemaName: String = "",
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = false,
    val isFullShape: Boolean = false,
    val isAsciiPunct: Boolean = false,
    /** Raw key sequence librime has collected, "" when idle. */
    val input: String = "",
    /** Text shown in the composing area, "" when not composing. */
    val preedit: String = "",
    /** Caret position within [preedit] as reported by librime (UTF-8 bytes). */
    val preeditCursor: Int = 0,
    /** UTF-8 byte range of the highlighted segment inside [preedit]. */
    val preeditSelStart: Int = 0,
    val preeditSelEnd: Int = 0,
    val pageIndex: Int = 0,
    val pageSize: Int = 0,
    val isLastPage: Boolean = true,
    val highlightedIndex: Int = 0,
    val candidates: List<RimeCandidate> = emptyList(),
    /** Text committed by the triggering action; "" when nothing was committed. */
    val commitText: String = "",
    /**
     * Syllable tokens of the highlighted candidate, read in the same native call as the
     * snapshot; null unless [RimeEngine.spellingDemand] asked for it and it is provable.
     */
    val highlightedSyllables: RimeHighlightedSyllables? = null,
) {
    companion object {
        val EMPTY = RimeSnapshot()
    }
}

/** Result of feeding one key: whether librime consumed it and the state after. */
data class RimeActionResult(
    val consumed: Boolean,
    val snapshot: RimeSnapshot,
) {
    /** Text committed by this action, or null. */
    val commitText: String? get() = snapshot.commitText.ifEmpty { null }
}

/** Deployment state mirrored from the C bridge (`rb_deploy_state`). */
enum class RimeDeployState { NONE, START, SUCCESS, FAILURE }

/**
 * Observed engine state for the IME process.
 *
 * The UI waits for [Ready] before claiming it can take input and shows a preparation
 * state for everything else; a [Failed] engine never pretends to be usable.
 */
sealed interface RimeStatus {
    data object Stopped : RimeStatus

    data object Starting : RimeStatus

    data class Ready(
        val engineVersion: String,
    ) : RimeStatus

    data class Failed(
        val reason: String,
    ) : RimeStatus
}

/** Errors thrown by [RimeEngine]. */
class RimeException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** The engine was not started (call start() first). */
        NOT_STARTED,

        /** The engine failed to initialize or deploy. */
        INITIALIZATION_FAILED,

        /** No usable Rime session exists. */
        NO_SESSION,

        /** A native action may have completed, but its state was not observed. */
        SNAPSHOT_UNAVAILABLE,

        /** A key/candidate/option operation returned a failure from librime. */
        OPERATION_FAILED,
    }
}

/** A schema entry from the engine's enabled schema list. */
data class RimeSchema(
    val id: String,
    val name: String,
)

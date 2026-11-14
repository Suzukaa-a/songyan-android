package app.songyan.ime.tools

/** Where one tool run is in its life. Nothing here touches UI, threads or disk. */
enum class ToolPhase {
    IDLE,
    RUNNING,
    PREVIEWING,
    WRITING,
    UNDOING,
    WROTE,
    FAILED,
}

/** Effect: the host must cancel the underlying task it started for [runId]. */
data class CancelRun(
    val runId: String,
)

/** What closing the result did: a running tool is cancelled, a settled one is just cleared. */
sealed interface DismissOutcome {
    data object Dismissed : DismissOutcome

    /** The result was closed while the tool was still running; the host must cancel [effect]. */
    data class Cancelled(
        val effect: CancelRun,
    ) : DismissOutcome

    /** Nothing to close, or the host is writing/undoing and its acknowledgement is still owed. */
    data object Unavailable : DismissOutcome
}

/** What the preview shows: one result per entry, one selected entry, and where the input came from. */
data class ToolPreview(
    val runId: String,
    val toolTitle: String,
    val scope: TextScope,
    val isTruncated: Boolean,
    val results: List<String>,
    val selectedIndex: Int,
)

enum class WriteKind {
    REPLACE,
    INSERT,
}

/** How the source text compared at write time; "unreadable" is never reported as "unchanged". */
enum class ToolSourceChange {
    UNCHANGED,
    CHANGED,
    UNREADABLE,
}

/**
 * One half of the two-phase write: the host performs it and answers with
 * [ToolRun.writeSucceeded] or [ToolRun.writeFailed]. [replacedUnitCount] is how many UTF-16 code
 * units the gateway deletes at the end of [range] before inserting [text]; an insert replaces
 * nothing.
 */
data class WriteRequest(
    val runId: String,
    val documentId: String,
    val kind: WriteKind,
    val range: TextRange,
    val replacedUnitCount: Int,
    val text: String,
    /** How the whole original source compared to what the host re-read at write time. */
    val source: ToolSourceChange,
) {
    /** Only a confirmed change counts as changed; unreadable stays unreadable. */
    val sourceChanged: Boolean get() = source == ToolSourceChange.CHANGED
}

sealed interface WriteStart {
    data class Requested(
        val request: WriteRequest,
    ) : WriteStart

    data class Blocked(
        val reason: WriteBlockReason,
    ) : WriteStart

    /** No preview, or a write is already in flight. */
    data object Unavailable : WriteStart
}

enum class UndoKind {
    RESTORE_TEXT,
    REMOVE_INSERTED,
}

/**
 * One half of the two-phase undo. [text] is the original text put back for [UndoKind.RESTORE_TEXT];
 * for [UndoKind.REMOVE_INSERTED] the gateway deletes [replacedUnitCount] code units at the end of
 * [range] and writes nothing.
 */
data class UndoRequest(
    val runId: String,
    val documentId: String,
    val kind: UndoKind,
    val range: TextRange,
    val text: String?,
    val replacedUnitCount: Int,
)

sealed interface UndoStart {
    data class Requested(
        val request: UndoRequest,
    ) : UndoStart

    data class Blocked(
        val reason: WriteBlockReason,
    ) : UndoStart

    data object Expired : UndoStart

    data object Unavailable : UndoStart
}

/**
 * The pure tool run: it owns the run identity, the preview, the two-phase write and undo offers.
 * It never writes to a document itself; every write is a [WriteRequest] the host applies and
 * acknowledges, and every state change waits for that acknowledgement. Time is injected, and all
 * limits arrive through [ToolLimits]; no production limit is guessed here.
 */
class ToolRun private constructor(
    val tool: ToolDescriptor,
    val limits: ToolLimits,
    private val clock: () -> Long,
    private val session: ToolSession,
) {
    var phase: ToolPhase = ToolPhase.IDLE
        private set

    /** Identity of the current run; results and errors carrying another id are dropped. */
    var runId: String = ""
        private set

    var source: TextSource? = null
        private set

    var preview: ToolPreview? = null
        private set

    var failure: ToolFailure? = null
        private set

    var lastWrite: WriteRequest? = null
        private set

    var pendingWrite: WriteRequest? = null
        private set

    var pendingUndo: UndoRequest? = null
        private set

    private var startedAtMillis = 0L
    private var writtenAtMillis = 0L
    private var undoExpiresAtMillis: Long? = null
    private var undoTemplate: UndoRequest? = null
    private var lastWrittenText: String? = null
    private var documentCapabilities = ToolDocumentCapabilities()

    /** How long the current run has been in [ToolPhase.RUNNING]. */
    fun elapsedMillis(now: Long = clock()): Long = now - startedAtMillis

    /**
     * Drives the timeout with the injected clock. A run past its timeout ends as
     * [ToolFailure.TimedOut] and the returned effect tells the host to cancel the real task, so a
     * timed-out run cannot still deliver a result into the next preview.
     */
    fun tick(now: Long = clock()): CancelRun? {
        if (phase != ToolPhase.RUNNING) return null
        if (!pastDeadline(now)) return null
        failWithoutInput(ToolFailure.TimedOut)
        return CancelRun(runId)
    }

    /** The user cancelled the run; the host cancels the underlying task with the same effect. */
    fun cancel(): CancelRun? {
        if (phase != ToolPhase.RUNNING) return null
        failWithoutInput(ToolFailure.Cancelled)
        return CancelRun(runId)
    }

    /**
     * A tool result for [runId]. Results for an older id (before a retry or from another session) are
     * slow task can never overwrite the preview of the run that replaced it.
     */
    fun complete(
        runId: String,
        results: List<String>,
        now: Long = clock(),
    ): Boolean {
        if (phase != ToolPhase.RUNNING || runId != this.runId) return false
        if (pastDeadline(now)) {
            failWithoutInput(ToolFailure.TimedOut)
            return false
        }
        val currentSource = source ?: return false
        val kept = results.take(limits.maxResults)
        if (kept.isEmpty()) {
            failWithoutInput(ToolFailure.NoResult)
            return false
        }
        preview = ToolPreview(runId, tool.title, currentSource.scope, currentSource.isTruncated, kept, 0)
        phase = ToolPhase.PREVIEWING
        return true
    }

    /** A tool error for [runId]; stale errors are dropped and late ones count as a timeout. */
    fun fail(
        runId: String,
        message: String?,
        now: Long = clock(),
    ): Boolean {
        if (phase != ToolPhase.RUNNING || runId != this.runId) return false
        if (pastDeadline(now)) {
            failWithoutInput(ToolFailure.TimedOut)
            return false
        }
        failWithoutInput(ToolFailure.ToolError(message))
        return true
    }

    /** This run's budget: the tool's own when it declares one. */
    val timeoutMillis: Long get() = tool.timeoutMillis ?: limits.timeoutMillis

    /** The same deadline tick uses, so a late result cannot slip in when no tick has run. */
    private fun pastDeadline(now: Long): Boolean = now - startedAtMillis >= timeoutMillis

    /**
     * Starts another run of the same tool with the next id from the host's session. Old results are stale:
     * they can no longer enter a preview, and the host must treat the previous one as cancelled.
     */
    fun retry(now: Long = clock()): String? {
        if (phase != ToolPhase.PREVIEWING && phase != ToolPhase.FAILED) return null
        if (source == null) return null
        runId = session.nextRunId()
        startedAtMillis = now
        phase = ToolPhase.RUNNING
        preview = null
        failure = null
        lastWrite = null
        pendingWrite = null
        pendingUndo = null
        undoTemplate = null
        lastWrittenText = null
        undoExpiresAtMillis = null
        return runId
    }

    fun selectResult(index: Int): Boolean {
        val current = preview ?: return false
        if (index !in current.results.indices) return false
        preview = current.copy(selectedIndex = index)
        return true
    }

    fun selectedResult(): String? = preview?.let { it.results.getOrNull(it.selectedIndex) }

    /** The selected result for the clipboard; nothing is written to the document. */
    fun copy(): String? {
        if (phase != ToolPhase.PREVIEWING) return null
        return selectedResult()
    }

    /**
     * Preview action "replace": replaces the processed range only when the host re-read exactly the
     * whole original text on the same document; a changed document or a secured field is refused.
     * An untruncated whole-source replace needs no capability. A truncated preceding source may
     * replace only its verified tail, which needs
     * [ToolDocumentCapabilities.replacePrecedingTail]. A truncated selection is refused as a
     * fail-closed, temporary rule: only its tail was read and no document gateway has yet been
     * verified to replace just that tail, so processing a truncated selection is not part of the
     * current deliverable; reopen it once a gateway supports and verifies safe partial selection
     * replacement. [privacy] is the field's state at write time: a password field is refused as
     * [WriteBlockReason.SECURE_ENTRY] and an editor that no longer returns text as
     * [WriteBlockReason.NO_TEXT]. No refusal touches the preview.
     */
    fun replace(
        currentDocumentId: String,
        currentSourceText: String?,
        cursorIndex: Int,
        privacy: EditorPrivacy = EditorPrivacy.NORMAL,
    ): WriteStart = requestWrite(replacing = true, currentDocumentId, currentSourceText, cursorIndex, privacy)

    /**
     * Preview action "insert": writes the selected result at the cursor. With no selection this
     * needs no capability, even from a truncated source, because nothing is replaced. A live
     * selection must survive the insert, which needs
     * [ToolDocumentCapabilities.insertPreservingSelection]; this is independent of replacing a
     * truncated preceding tail. [privacy] is the field's state at write time and is refused like a
     * replace.
     */
    fun insert(
        currentDocumentId: String,
        cursorIndex: Int,
        privacy: EditorPrivacy = EditorPrivacy.NORMAL,
    ): WriteStart = requestWrite(replacing = false, currentDocumentId, null, cursorIndex, privacy)

    /** The host wrote the document: now the result is shown as written and undo becomes possible. */
    fun writeSucceeded(now: Long = clock()): Boolean {
        val request = pendingWrite ?: return false
        if (phase != ToolPhase.WRITING) return false
        lastWrite = request
        pendingWrite = null
        writtenAtMillis = now
        undoExpiresAtMillis = limits.undoWindowMillis?.let { now + it }
        // The result may be longer or shorter than the original text: undo addresses the span the
        // write actually occupies, never the original range.
        val writtenSpan = TextRange(request.range.start, request.range.start + request.text.length)
        lastWrittenText = request.text
        undoTemplate =
            when (request.kind) {
                WriteKind.REPLACE ->
                    UndoRequest(
                        runId = runId,
                        documentId = request.documentId,
                        kind = UndoKind.RESTORE_TEXT,
                        range = writtenSpan,
                        text = source?.text,
                        replacedUnitCount = request.text.length,
                    )
                WriteKind.INSERT ->
                    UndoRequest(
                        runId = runId,
                        documentId = request.documentId,
                        kind = UndoKind.REMOVE_INSERTED,
                        range = writtenSpan,
                        text = null,
                        replacedUnitCount = request.text.length,
                    )
            }
        phase = ToolPhase.WROTE
        return true
    }

    /** The host refused or failed the write: the preview survives so the user can try again. */
    fun writeFailed(): Boolean {
        if (phase != ToolPhase.WRITING) return false
        pendingWrite = null
        phase = ToolPhase.PREVIEWING
        return true
    }

    /**
     * Offers the undo for the last write. It is refused while the document or the field changed
     * (password as [WriteBlockReason.SECURE_ENTRY], no-text as [WriteBlockReason.NO_TEXT]) and
     * expires after the injected window; the "wrote" state and the preview-adjacent result survive
     * either way, so the user is never trapped.
     */
    fun beginUndo(
        currentDocumentId: String,
        currentWrittenText: String?,
        privacy: EditorPrivacy = EditorPrivacy.NORMAL,
        now: Long = clock(),
    ): UndoStart {
        if (phase != ToolPhase.WROTE) return UndoStart.Unavailable
        val template = undoTemplate ?: return UndoStart.Unavailable
        val expiresAt = undoExpiresAtMillis
        if (expiresAt != null && now > expiresAt) {
            undoTemplate = null
            undoExpiresAtMillis = null
            return UndoStart.Expired
        }
        blockReason(template.documentId, currentDocumentId, privacy)?.let { return UndoStart.Blocked(it) }
        if (currentWrittenText != lastWrittenText) {
            // The user kept typing into the written result: restoring the original would overwrite
            // that new input, so the undo is refused and the written result stays undoable.
            return UndoStart.Blocked(WriteBlockReason.EDITED_AFTER_WRITE)
        }
        phase = ToolPhase.UNDOING
        pendingUndo = template
        return UndoStart.Requested(template)
    }

    /** The host put the original text back: the run is over with nothing left to undo. */
    fun undoSucceeded(): Boolean {
        if (phase != ToolPhase.UNDOING) return false
        pendingUndo = null
        undoTemplate = null
        undoExpiresAtMillis = null
        lastWrite = null
        lastWrittenText = null
        phase = ToolPhase.IDLE
        return true
    }

    /** The host failed the undo: the written result stays undoable. */
    fun undoFailed(): Boolean {
        if (phase != ToolPhase.UNDOING) return false
        pendingUndo = null
        phase = ToolPhase.WROTE
        return true
    }

    fun canUndo(now: Long = clock()): Boolean {
        if (phase != ToolPhase.WROTE || undoTemplate == null) return false
        val expiresAt = undoExpiresAtMillis ?: return true
        return now <= expiresAt
    }

    /** The user closed the result: the run ends and its undo offer goes away. */
    fun dismiss(): DismissOutcome {
        if (phase == ToolPhase.IDLE || phase == ToolPhase.WRITING || phase == ToolPhase.UNDOING) {
            return DismissOutcome.Unavailable
        }
        val wasRunning = phase == ToolPhase.RUNNING
        phase = ToolPhase.IDLE
        preview = null
        failure = null
        lastWrite = null
        pendingWrite = null
        pendingUndo = null
        undoTemplate = null
        lastWrittenText = null
        undoExpiresAtMillis = null
        // Closing a running result must also stop the real task; a settled result just closes.
        return if (wasRunning) DismissOutcome.Cancelled(CancelRun(runId)) else DismissOutcome.Dismissed
    }

    private fun requestWrite(
        replacing: Boolean,
        currentDocumentId: String,
        currentSourceText: String?,
        cursorIndex: Int,
        privacy: EditorPrivacy,
    ): WriteStart {
        val currentSource = source ?: return WriteStart.Unavailable
        val text = selectedResult() ?: return WriteStart.Unavailable
        if (phase != ToolPhase.PREVIEWING) return WriteStart.Unavailable
        blockReason(currentSource.documentId, currentDocumentId, privacy)?.let { return WriteStart.Blocked(it) }
        if (replacing && currentSource.isTruncated && currentSource.scope == TextScope.SELECTION) {
            // Fail closed for now: no document gateway has yet proven that it can replace only the
            // read tail of a selection, so a replace would either clobber the unread prefix or
            // claim a range write that does not exist. This is a temporary behaviour to reopen
            // once the gateway supports and verifies safe partial selection replacement; until
            // then a truncated selection is never replaced (the tail is not silently rewritten)
            // and an explicit insert stays available.
            return WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT)
        }
        // Compare the whole original source, never just the processed tail: a changed prefix with a
        // matching tail is still a changed document.
        val fullOriginal = currentSource.fullText ?: currentSource.text
        val unchanged = replacing && currentSourceText != null && currentSourceText == fullOriginal
        // A truncated preceding source can only have its verified tail replaced, which is its own
        // gateway ability; an untruncated whole-source replace needs no capability.
        val partialReplace = replacing && unchanged && currentSource.isTruncated
        if (partialReplace && !documentCapabilities.replacePrecedingTail) {
            return WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT)
        }
        // Inserting while a selection is live must keep that selection's text. This is independent
        // of replacing a preceding tail, so it has its own capability and its own refusal.
        val insertingWithSelection = (!replacing || !unchanged) && currentSource.scope == TextScope.SELECTION
        if (insertingWithSelection && !documentCapabilities.insertPreservingSelection) {
            return WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION)
        }
        val request =
            if (unchanged) {
                WriteRequest(
                    runId = runId,
                    documentId = currentSource.documentId,
                    kind = WriteKind.REPLACE,
                    range = currentSource.range,
                    replacedUnitCount = currentSource.text.length,
                    text = text,
                    source = ToolSourceChange.UNCHANGED,
                )
            } else {
                WriteRequest(
                    runId = runId,
                    documentId = currentSource.documentId,
                    kind = WriteKind.INSERT,
                    range = TextRange(cursorIndex, cursorIndex + text.length),
                    replacedUnitCount = 0,
                    text = text,
                    // An unreadable source is inserted but never reported as "unchanged"; an
                    // explicit insert compares nothing and stays unchanged.
                    source =
                        when {
                            !replacing -> ToolSourceChange.UNCHANGED
                            currentSourceText == null -> ToolSourceChange.UNREADABLE
                            else -> ToolSourceChange.CHANGED
                        },
                )
            }
        pendingWrite = request
        phase = ToolPhase.WRITING
        return WriteStart.Requested(request)
    }

    private fun blockReason(
        expectedDocumentId: String,
        currentDocumentId: String,
        privacy: EditorPrivacy,
    ): WriteBlockReason? {
        // A password field and an editor that no longer returns text are different failures: the UI
        // must be able to say which one stopped the write.
        if (privacy == EditorPrivacy.PASSWORD) return WriteBlockReason.SECURE_ENTRY
        if (privacy == EditorPrivacy.NO_TEXT) return WriteBlockReason.NO_TEXT
        if (currentDocumentId != expectedDocumentId) return WriteBlockReason.DOCUMENT_CHANGED
        return null
    }

    private fun failWithoutInput(failure: ToolFailure) {
        this.failure = failure
        preview = null
        pendingWrite = null
        phase = ToolPhase.FAILED
    }

    companion object {
        /**
         * Starts a run: the host capability table answers the tool's requirements first, then the
         * editor is read (a password field is refused before any text is looked at).
         */
        fun begin(
            tool: ToolDescriptor,
            capabilities: CapabilityTable,
            editor: EditorSnapshot,
            limits: ToolLimits,
            session: ToolSession,
            clock: () -> Long = System::currentTimeMillis,
        ): ToolRun {
            val run = ToolRun(tool, limits, clock, session)
            // The host owns the session identity and the session hands out run ids, so a callback
            // from a task started before a keyboard reload carries an id this session never issued.
            run.runId = session.nextRunId()
            val missing = capabilities.missing(tool.requiredCapabilities)
            if (missing.isNotEmpty()) {
                run.failWithoutInput(ToolFailure.Unavailable(UnavailableReason.MISSING_CAPABILITIES, missing))
                return run
            }
            when (val input = ToolInput.acquire(editor, limits.maxInputUnits)) {
                is InputResult.Available -> {
                    run.source = input.source
                    run.documentCapabilities = editor.capabilities
                    run.startedAtMillis = clock()
                    run.phase = ToolPhase.RUNNING
                }
                is InputResult.Unavailable -> run.failWithoutInput(ToolFailure.Unavailable(input.reason))
            }
            return run
        }
    }
}

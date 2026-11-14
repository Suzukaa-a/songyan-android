package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.songyan.ime.ime.tools.ToolUndoResult
import app.songyan.ime.ime.tools.ToolWriteResult
import app.songyan.ime.tools.CapabilityTable
import app.songyan.ime.tools.EditorPrivacy
import app.songyan.ime.tools.EditorSnapshot
import app.songyan.ime.tools.TextRange
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolDocumentCapabilities
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolRun
import app.songyan.ime.tools.ToolSession
import app.songyan.ime.tools.UndoKind
import app.songyan.ime.tools.UndoStart
import app.songyan.ime.tools.WriteKind
import app.songyan.ime.tools.WriteRequest
import app.songyan.ime.tools.WriteStart
import java.util.concurrent.atomic.AtomicLong

/**
 * The single gateway between the shared tool model and one live editor.
 *
 * It reads what a tool may see (selection first, otherwise the text before the caret), names the
 * document so an asynchronous result cannot land in another field, and applies a write or undo only
 * after re-reading the editor: a change the user made since the preview is never overwritten, it
 * becomes an insert, and an undo whose written text is no longer exactly there is refused. Password
 * fields and editors that cannot return text are unavailable; [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]
 * editors stay usable without learning anything.
 *
 * Reads are bounded by [ToolLimits.maxInputUnits]: a selection longer than that is not pulled into
 * memory in one piece, it makes the editor unavailable instead (fail closed). The write itself is a
 * read-then-write on one [InputConnection]; when an editor implements that connection over a Binder
 * proxy another process can change the document between the re-read and the edit. The platform
 * offers no atomic compare-and-swap there, so this gateway narrows that window only by keeping the
 * verification and the edit adjacent: it does not claim to restore text the host itself lost.
 *
 * The gateway never composes: a live composition makes it refuse the write instead of committing
 * text under the engine's marked text.
 */
class EditorToolGateway(
    private val editor: EditorConnection,
    private val limits: ToolLimits,
    private val session: ToolSession,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val instanceId = "g" + INSTANCES.incrementAndGet()
    private var lastDocumentKey: String? = null
    private var documentSerial = 0

    /**
     * The service calls this on every new input session. The next snapshot then gets a new document
     * id even when the platform reuses the same [EditorInfo] and connection objects.
     */
    fun startInput() {
        lastDocumentKey = null
    }

    fun snapshot(info: EditorInfo?): EditorSnapshot {
        val documentId = documentId(info)
        val privacy = privacy(info)
        if (privacy == EditorPrivacy.PASSWORD || privacy == EditorPrivacy.NO_TEXT) {
            return EditorSnapshot(documentId, privacy)
        }
        val ic = editor.connection() ?: return EditorSnapshot(documentId, EditorPrivacy.NO_TEXT)
        if (!editor.selection.known) return EditorSnapshot(documentId, EditorPrivacy.NO_TEXT)
        val start = editor.selection.start
        val end = editor.selection.end
        if (end > start) {
            // Bounded: a long selection is not pulled in whole; unavailable is the honest answer.
            val selected = selectedText(ic, start, end) ?: return EditorSnapshot(documentId, EditorPrivacy.NO_TEXT)
            return EditorSnapshot(
                documentId = documentId,
                privacy = privacy,
                selection = selected,
                selectionRange = TextRange(start, end),
                beforeCursor = null,
                cursorIndex = end,
                capabilities = CAPABILITIES,
            )
        }
        val before =
            ic.getTextBeforeCursor(readWindow(), 0)?.toString()
                ?: return EditorSnapshot(documentId, EditorPrivacy.NO_TEXT)
        return EditorSnapshot(
            documentId = documentId,
            privacy = privacy,
            selection = null,
            selectionRange = null,
            beforeCursor = before,
            cursorIndex = start,
            capabilities = CAPABILITIES,
        )
    }

    /** Starts one run of [tool] over the current editor. */
    fun beginRun(
        tool: ToolDescriptor,
        capabilities: CapabilityTable,
        info: EditorInfo?,
    ): ToolRun =
        ToolRun.begin(
            tool = tool,
            capabilities = capabilities,
            editor = snapshot(info),
            limits = limits,
            session = session,
            clock = clock,
        )

    /**
     * Preview action "replace": replaces the processed range only when the editor still shows the
     * text the run read. A changed document, a changed source, a new field or a live composition
     * turns into an insert or a refusal, never into an overwrite.
     */
    fun replace(
        run: ToolRun,
        info: EditorInfo?,
    ): ToolWriteResult {
        val current = currentRead(run, info)
        return when (
            val start = run.replace(current.documentId, current.text, current.cursorIndex, current.privacy)
        ) {
            is WriteStart.Requested -> applyWrite(run, start.request, current)
            is WriteStart.Blocked -> ToolWriteResult.Blocked(start.reason)
            WriteStart.Unavailable -> ToolWriteResult.Unavailable
        }
    }

    /** Preview action "insert": writes the selected result at the caret, leaving existing text alone. */
    fun insert(
        run: ToolRun,
        info: EditorInfo?,
    ): ToolWriteResult {
        val current = currentRead(run, info)
        return when (val start = run.insert(current.documentId, current.cursorIndex, current.privacy)) {
            is WriteStart.Requested -> applyWrite(run, start.request, current)
            is WriteStart.Blocked -> ToolWriteResult.Blocked(start.reason)
            WriteStart.Unavailable -> ToolWriteResult.Unavailable
        }
    }

    /**
     * Offers and applies the undo for the last write. The written span is re-read first: if the user
     * edited it, [ToolRun.beginUndo] refuses with EDITED_AFTER_WRITE and nothing is overwritten. An
     * empty written span is valid: it is re-read as empty only while the caret still sits exactly at
     * the end of the span, and undo then inserts the original text again.
     */
    fun undo(
        run: ToolRun,
        info: EditorInfo?,
    ): ToolUndoResult {
        val written = run.lastWrite ?: return ToolUndoResult.Unavailable
        if (editor.composing) return ToolUndoResult.NotApplied
        val currentDocumentId = documentId(info)
        val privacy = privacy(info)
        val reseen =
            if (privacy == EditorPrivacy.PASSWORD ||
                privacy == EditorPrivacy.NO_TEXT
            ) {
                null
            } else {
                readWritten(written)
            }
        return when (val start = run.beginUndo(currentDocumentId, reseen, privacy, clock())) {
            is UndoStart.Requested -> applyUndo(run, start.request, written)
            is UndoStart.Blocked -> ToolUndoResult.Blocked(start.reason)
            UndoStart.Expired -> ToolUndoResult.Expired
            UndoStart.Unavailable -> ToolUndoResult.Unavailable
        }
    }

    private fun applyWrite(
        run: ToolRun,
        request: WriteRequest,
        current: Read,
    ): ToolWriteResult {
        val source = run.source ?: return failWrite(run)
        if (current.documentId != request.documentId) return failWrite(run)
        if (current.privacy == EditorPrivacy.PASSWORD || current.privacy == EditorPrivacy.NO_TEXT) {
            return failWrite(run)
        }
        // Never commit text under the engine's marked text, and never over a live selection.
        if (editor.composing) return failWrite(run)
        val written =
            when (request.kind) {
                WriteKind.REPLACE ->
                    when (source.scope) {
                        app.songyan.ime.tools.TextScope.SELECTION ->
                            // Only a whole, fully read selection may be replaced: a truncated one
                            // would leave its unprocessed prefix behind the replacement.
                            !source.isTruncated && editor.replaceSelection(source.text, request.text)
                        app.songyan.ime.tools.TextScope.BEFORE_CURSOR ->
                            editor.replaceBeforeCursor(source.text, request.text)
                    }
                WriteKind.INSERT -> {
                    val ic = editor.connection()
                    when {
                        ic == null -> false
                        ic.getSelectedText(0).isNullOrEmpty() -> editor.update("", listOf(request.text))
                        // After a run on a selection the result goes right after it and the selected
                        // text stays: the caret collapses to the selection's end, where the model
                        // placed the inserted span, and the text is committed there.
                        editor.selection.known && editor.selection.end == request.range.start -> {
                            ic.beginBatchEdit()
                            try {
                                ic.setSelection(request.range.start, request.range.start) &&
                                    editor.update("", listOf(request.text))
                            } finally {
                                ic.endBatchEdit()
                            }
                        }
                        else -> false
                    }
                }
            }
        return if (written) {
            run.writeSucceeded(clock())
            ToolWriteResult.Applied
        } else {
            failWrite(run)
        }
    }

    private fun applyUndo(
        run: ToolRun,
        request: app.songyan.ime.tools.UndoRequest,
        written: WriteRequest,
    ): ToolUndoResult {
        val restored =
            when (request.kind) {
                UndoKind.REMOVE_INSERTED -> replaceWritten(written, "")
                UndoKind.RESTORE_TEXT -> {
                    val original = request.text ?: return failUndo(run)
                    replaceWritten(written, original)
                }
            }
        return if (restored) {
            run.undoSucceeded()
            ToolUndoResult.Applied
        } else {
            failUndo(run)
        }
    }

    /**
     * Puts [text] where exactly [written] was. A non-empty span is verified by the connection's own
     * before-caret read; an empty span only needs the caret to still sit at its known end.
     */
    private fun replaceWritten(
        written: WriteRequest,
        text: String,
    ): Boolean {
        val ic = editor.connection() ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        if (written.text.isEmpty()) {
            val end = written.range.start + written.text.length
            if (!caretAt(end)) return false
            return editor.update("", listOf(text))
        }
        return editor.replaceBeforeCursor(written.text, text)
    }

    private fun failWrite(run: ToolRun): ToolWriteResult {
        run.writeFailed()
        return ToolWriteResult.NotApplied
    }

    private fun failUndo(run: ToolRun): ToolUndoResult {
        run.undoFailed()
        return ToolUndoResult.NotApplied
    }

    private fun currentRead(
        run: ToolRun,
        info: EditorInfo?,
    ): Read {
        val documentId = documentId(info)
        val privacy = privacy(info)
        val source = run.source
        val start = if (editor.selection.known) minOf(editor.selection.start, editor.selection.end) else 0
        val ic = editor.connection()
        val text =
            when {
                source == null || ic == null -> null
                privacy == EditorPrivacy.PASSWORD || privacy == EditorPrivacy.NO_TEXT -> null
                source.scope == app.songyan.ime.tools.TextScope.SELECTION ->
                    selectedText(ic, editor.selection.start, editor.selection.end)
                else -> ic.getTextBeforeCursor(readWindow(), 0)?.toString()
            }
        return Read(documentId, privacy, text, start)
    }

    private fun selectedText(
        ic: InputConnection,
        start: Int,
        end: Int,
    ): String? {
        val length = end - start
        if (length <= 0) return ""
        // Known bounds first: never ask for a selection longer than the injected window.
        if (length > readWindow()) return null
        val text = ic.getSelectedText(0)?.toString() ?: return null
        return text.takeIf { it.length == length }
    }

    /** The written span as the editor shows it now; empty is legal and only confirmed by the caret. */
    private fun readWritten(written: WriteRequest): String? {
        val ic = editor.connection() ?: return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        if (written.text.isEmpty()) {
            return if (caretAt(written.range.start + written.text.length)) "" else null
        }
        return ic.getTextBeforeCursor(written.text.length, 0)?.toString()?.takeIf { it == written.text }
    }

    private fun caretAt(position: Int): Boolean =
        editor.selection.known && editor.selection.start == position && editor.selection.end == position

    private fun privacy(info: EditorInfo?): EditorPrivacy {
        if (info == null || editor.keyEventEditor || editor.connection() == null) {
            return EditorPrivacy.NO_TEXT
        }
        val policy = EditorPolicy.from(info)
        return when {
            policy.fieldKind == FieldKind.PASSWORD -> EditorPrivacy.PASSWORD
            policy.privateInput -> EditorPrivacy.NO_PERSONALIZED_LEARNING
            else -> EditorPrivacy.NORMAL
        }
    }

    /**
     * Opaque, per gateway instance and per document: rebuilding the gateway (a new tool session)
     * can never reuse an id an older run still holds, so a stale run cannot write into the new
     * field even when its text happens to look the same.
     */
    private fun documentId(info: EditorInfo?): String {
        val key =
            listOf(
                info?.packageName,
                info?.fieldId,
                System.identityHashCode(info),
                System.identityHashCode(editor.connection()),
            ).joinToString("|")
        if (key != lastDocumentKey) {
            lastDocumentKey = key
            documentSerial += 1
        }
        return "doc-" + instanceId + "-" + documentSerial
    }

    private fun readWindow(): Int =
        if (limits.maxInputUnits == Int.MAX_VALUE) Int.MAX_VALUE else limits.maxInputUnits + 1

    private data class Read(
        val documentId: String,
        val privacy: EditorPrivacy,
        val text: String?,
        val cursorIndex: Int,
    )

    private companion object {
        private val INSTANCES = AtomicLong()

        /**
         * Only the abilities this gateway actually implements and verifies: it replaces the tail it
         * re-read before the caret (replacePrecedingTail), and it inserts after a live selection
         * without touching the selected text (insertPreservingSelection, see [applyWrite]). A
         * truncated selection is refused rather than written.
         */
        val CAPABILITIES =
            ToolDocumentCapabilities(
                replacePrecedingTail = true,
                insertPreservingSelection = true,
            )
    }
}

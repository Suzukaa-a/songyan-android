package app.songyan.ime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWriteBackTest {
    private val tool = ToolDescriptor("polish", "润色", setOf(ToolCapability.TEXT))
    private val capabilities = CapabilityTable(setOf(ToolCapability.TEXT))
    private var now = 0L

    private fun limits(undoWindowMillis: Long? = null): ToolLimits =
        ToolLimits(maxInputUnits = 100, timeoutMillis = 1_000, undoWindowMillis = undoWindowMillis, maxResults = 5)

    private fun begin(
        editor: EditorSnapshot = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "hello", cursorIndex = 5),
        limits: ToolLimits = limits(),
        documentCapabilities: ToolDocumentCapabilities = ToolDocumentCapabilities(),
    ): ToolRun =
        ToolRun.begin(
            tool = tool,
            capabilities = capabilities,
            editor = editor.copy(capabilities = documentCapabilities),
            limits = limits,
            session = ToolSession("session"),
            clock = { now },
        )

    private fun previewing(
        limits: ToolLimits = limits(),
        documentCapabilities: ToolDocumentCapabilities = ToolDocumentCapabilities(),
        result: String = "HELLO",
    ): ToolRun {
        val run = begin(limits = limits, documentCapabilities = documentCapabilities)
        assertTrue(run.complete(run.runId, listOf(result)))
        return run
    }

    private fun limitedBeforeCursor(
        text: String,
        maxUnits: Int,
        documentCapabilities: ToolDocumentCapabilities = ToolDocumentCapabilities(),
    ): ToolRun {
        val run =
            ToolRun.begin(
                tool = tool,
                capabilities = capabilities,
                editor =
                    EditorSnapshot(
                        "doc",
                        EditorPrivacy.NORMAL,
                        beforeCursor = text,
                        cursorIndex = text.length,
                        capabilities = documentCapabilities,
                    ),
                limits =
                    ToolLimits(
                        maxInputUnits = maxUnits,
                        timeoutMillis = 1_000,
                        undoWindowMillis = null,
                        maxResults = 5,
                    ),
                session = ToolSession("session"),
                clock = { now },
            )
        assertTrue(run.complete(run.runId, listOf("RESULT")))
        return run
    }

    private fun truncatedSelectionRun(
        documentCapabilities: ToolDocumentCapabilities = ToolDocumentCapabilities(),
    ): ToolRun {
        val run =
            ToolRun.begin(
                tool = tool,
                capabilities = capabilities,
                editor =
                    EditorSnapshot(
                        documentId = "doc",
                        privacy = EditorPrivacy.NORMAL,
                        selection = "abcdef",
                        selectionRange = TextRange(0, 6),
                        cursorIndex = 6,
                        capabilities = documentCapabilities,
                    ),
                limits = ToolLimits(maxInputUnits = 3, timeoutMillis = 1_000, undoWindowMillis = null, maxResults = 5),
                session = ToolSession("session"),
                clock = { now },
            )
        assertTrue(run.complete(run.runId, listOf("DEF")))
        return run
    }

    private fun requested(start: WriteStart): WriteRequest {
        assertTrue("expected a write request, got " + start, start is WriteStart.Requested)
        return (start as WriteStart.Requested).request
    }

    private fun requestedUndo(start: UndoStart): UndoRequest {
        assertTrue("expected an undo request, got " + start, start is UndoStart.Requested)
        return (start as UndoStart.Requested).request
    }

    @Test
    fun replaceRequestsTheProcessedRangeWhenTheDocumentStillHoldsTheSource() {
        val run = previewing()
        val request = requested(run.replace("doc", "hello", cursorIndex = 5))
        assertEquals(run.runId, request.runId)
        assertEquals("doc", request.documentId)
        assertEquals(WriteKind.REPLACE, request.kind)
        assertEquals(TextRange(0, 5), request.range)
        assertEquals(5, request.replacedUnitCount)
        assertEquals("HELLO", request.text)
        assertEquals(ToolSourceChange.UNCHANGED, request.source)
        assertFalse(request.sourceChanged)
        assertEquals(ToolPhase.WRITING, run.phase)
    }

    @Test
    fun noStateChangesBeforeTheHostAcknowledgesTheWrite() {
        val run = previewing()
        requested(run.replace("doc", "hello", cursorIndex = 5))
        assertEquals(ToolPhase.WRITING, run.phase)
        assertFalse(run.canUndo())
        assertEquals(DismissOutcome.Unavailable, run.dismiss())
        assertNull(run.lastWrite)
    }

    @Test
    fun aFailedWriteReturnsToThePreviewSoTheUserCanTryAgain() {
        val run = previewing()
        requested(run.replace("doc", "hello", cursorIndex = 5))
        assertTrue(run.writeFailed())
        assertEquals(ToolPhase.PREVIEWING, run.phase)
        assertNotNull(run.preview)
        assertFalse(run.writeSucceeded())
        assertFalse(run.canUndo())
    }

    @Test
    fun aChangedDocumentTurnsTheReplaceIntoAnInsert() {
        val run = previewing()
        val request = requested(run.replace("doc", "hello there", cursorIndex = 5))
        assertEquals(WriteKind.INSERT, request.kind)
        assertEquals(TextRange(5, 10), request.range)
        assertEquals(0, request.replacedUnitCount)
        assertEquals(ToolSourceChange.CHANGED, request.source)
        assertTrue(request.sourceChanged)
    }

    @Test
    fun anUnreadableSourceIsAnInsertButNeverReportedAsUnchanged() {
        val run = previewing()
        val request = requested(run.replace("doc", null, cursorIndex = 5))
        assertEquals(WriteKind.INSERT, request.kind)
        assertEquals(ToolSourceChange.UNREADABLE, request.source)
        assertFalse("an unreadable source is not evidence of a change", request.sourceChanged)
    }

    @Test
    fun anExplicitInsertComparesNothingAndStaysUnchanged() {
        val run = previewing()
        val request = requested(run.insert("doc", cursorIndex = 5))
        assertEquals(ToolSourceChange.UNCHANGED, request.source)
        assertFalse(request.sourceChanged)
    }

    @Test
    fun aDifferentDocumentBlocksTheWriteAndKeepsThePreview() {
        val run = previewing()
        assertEquals(WriteStart.Blocked(WriteBlockReason.DOCUMENT_CHANGED), run.replace("other", "hello", 5))
        assertEquals(ToolPhase.PREVIEWING, run.phase)
        assertNotNull(run.preview)
        assertEquals(WriteKind.REPLACE, requested(run.replace("doc", "hello", 5)).kind)
    }

    @Test
    fun aSecuredFieldBlocksTheWrite() {
        val run = previewing()
        assertEquals(
            WriteStart.Blocked(WriteBlockReason.SECURE_ENTRY),
            run.replace("doc", "hello", 5, privacy = EditorPrivacy.PASSWORD),
        )
        assertEquals(
            WriteStart.Blocked(WriteBlockReason.SECURE_ENTRY),
            run.insert("doc", 5, privacy = EditorPrivacy.PASSWORD),
        )
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun aFieldThatNoLongerReturnsTextHasItsOwnBlockReason() {
        val run = previewing()
        assertEquals(
            WriteStart.Blocked(WriteBlockReason.NO_TEXT),
            run.replace("doc", "hello", 5, privacy = EditorPrivacy.NO_TEXT),
        )
        assertEquals(
            WriteStart.Blocked(WriteBlockReason.NO_TEXT),
            run.insert("doc", 5, privacy = EditorPrivacy.NO_TEXT),
        )
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun undoIsBlockedWithTheNoTextReasonAndKeepsTheOffer() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        assertTrue(run.writeSucceeded())
        assertEquals(
            UndoStart.Blocked(WriteBlockReason.NO_TEXT),
            run.beginUndo("doc", "HELLO", privacy = EditorPrivacy.NO_TEXT),
        )
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo())
    }

    @Test
    fun insertRequestsTheCursorPositionAndReplacesNothing() {
        val run = previewing()
        val request = requested(run.insert("doc", cursorIndex = 5))
        assertEquals(WriteKind.INSERT, request.kind)
        assertEquals(TextRange(5, 10), request.range)
        assertEquals(0, request.replacedUnitCount)
    }

    @Test
    fun aSecondWriteIsRefusedWhileTheFirstIsInFlight() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        assertEquals(WriteStart.Unavailable, run.insert("doc", 5))
        assertEquals(WriteStart.Unavailable, run.replace("doc", "hello", 5))
    }

    @Test
    fun undoRestoresTheOriginalTextAfterAReplace() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        now = 10
        assertTrue(run.writeSucceeded())
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo())

        val undo = requestedUndo(run.beginUndo("doc", "HELLO"))
        assertEquals(UndoKind.RESTORE_TEXT, undo.kind)
        assertEquals(TextRange(0, 5), undo.range)
        assertEquals("hello", undo.text)
        assertEquals(5, undo.replacedUnitCount)
        assertEquals(ToolPhase.UNDOING, run.phase)

        assertTrue(run.undoSucceeded())
        assertEquals(ToolPhase.IDLE, run.phase)
        assertFalse(run.canUndo())
        assertEquals(UndoStart.Unavailable, run.beginUndo("doc", "HELLO"))
    }

    @Test
    fun undoOfALongerResultTargetsTheWrittenSpan() {
        val run = begin(editor = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "hi", cursorIndex = 2))
        assertTrue(run.complete(run.runId, listOf("LONGER")))
        val write = requested(run.replace("doc", "hi", cursorIndex = 2))
        assertEquals(TextRange(0, 2), write.range)
        assertEquals(2, write.replacedUnitCount)
        assertTrue(run.writeSucceeded())
        val undo = requestedUndo(run.beginUndo("doc", "LONGER"))
        // The written text is six units long, so undo must address 0..6, not the original 0..2.
        assertEquals(TextRange(0, 6), undo.range)
        assertEquals(6, undo.replacedUnitCount)
        assertEquals("hi", undo.text)
    }

    @Test
    fun undoOfAShorterResultTargetsTheWrittenSpan() {
        val run = previewing(result = "HI")
        val write = requested(run.replace("doc", "hello", cursorIndex = 5))
        assertEquals(TextRange(0, 5), write.range)
        assertEquals(5, write.replacedUnitCount)
        assertTrue(run.writeSucceeded())
        val undo = requestedUndo(run.beginUndo("doc", "HI"))
        assertEquals(TextRange(0, 2), undo.range)
        assertEquals(2, undo.replacedUnitCount)
        assertEquals("hello", undo.text)
    }

    @Test
    fun undoIsRefusedAfterTheUserEditedTheWrittenText() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        assertTrue(run.writeSucceeded())
        assertEquals(
            UndoStart.Blocked(WriteBlockReason.EDITED_AFTER_WRITE),
            run.beginUndo("doc", "HELLO there"),
        )
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo())
        // Once the written text is back, the undo is offered again.
        assertEquals(UndoKind.RESTORE_TEXT, requestedUndo(run.beginUndo("doc", "HELLO")).kind)
    }

    @Test
    fun undoIsRefusedWhenTheDocumentChanged() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        assertTrue(run.writeSucceeded())
        assertEquals(UndoStart.Blocked(WriteBlockReason.DOCUMENT_CHANGED), run.beginUndo("other", "HELLO"))
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo())
    }

    @Test
    fun undoExpiresAfterTheInjectedWindow() {
        val run = previewing(limits = limits(undoWindowMillis = 50))
        requested(run.replace("doc", "hello", 5))
        now = 100
        assertTrue(run.writeSucceeded())
        assertTrue(run.canUndo(150))
        assertFalse(run.canUndo(151))

        assertEquals(UndoStart.Expired, run.beginUndo("doc", "HELLO", now = 151))
        assertEquals(ToolPhase.WROTE, run.phase)
        assertFalse(run.canUndo(151))
        assertEquals(UndoStart.Unavailable, run.beginUndo("doc", "HELLO", now = 152))
    }

    @Test
    fun aNullUndoWindowStaysOpenUntilTheResultIsClosed() {
        val run = previewing(limits = limits(undoWindowMillis = null))
        requested(run.replace("doc", "hello", 5))
        assertTrue(run.writeSucceeded())
        requestedUndo(run.beginUndo("doc", "HELLO", now = 1_000_000))
        assertTrue(run.undoFailed())
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo(2_000_000))
        assertEquals(DismissOutcome.Dismissed, run.dismiss())
        assertEquals(ToolPhase.IDLE, run.phase)
    }

    @Test
    fun aFailedUndoKeepsTheWrittenResult() {
        val run = previewing()
        requested(run.replace("doc", "hello", 5))
        assertTrue(run.writeSucceeded())
        requestedUndo(run.beginUndo("doc", "HELLO"))
        assertEquals(ToolPhase.UNDOING, run.phase)
        assertTrue(run.undoFailed())
        assertEquals(ToolPhase.WROTE, run.phase)
        assertTrue(run.canUndo())
        assertNull(run.pendingUndo)
    }

    @Test
    fun undoRemovesAnInsertedResult() {
        val run = previewing()
        requested(run.insert("doc", 5))
        assertTrue(run.writeSucceeded())
        val undo = requestedUndo(run.beginUndo("doc", "HELLO"))
        assertEquals(UndoKind.REMOVE_INSERTED, undo.kind)
        assertEquals(TextRange(5, 10), undo.range)
        assertNull(undo.text)
        assertEquals(5, undo.replacedUnitCount)
        assertTrue(run.undoSucceeded())
        assertEquals(ToolPhase.IDLE, run.phase)
    }

    private val selectionEditor =
        EditorSnapshot(
            documentId = "doc",
            privacy = EditorPrivacy.NORMAL,
            selection = "abc",
            selectionRange = TextRange(0, 3),
            cursorIndex = 3,
        )

    @Test
    fun aTruncatedSelectionIsNeverReplacedWhateverTheGatewayDeclares() {
        for (replaceTail in listOf(false, true)) {
            for (insertSelection in listOf(false, true)) {
                val label = "replacePrecedingTail=" + replaceTail + " insertPreservingSelection=" + insertSelection
                val run = truncatedSelectionRun(ToolDocumentCapabilities(replaceTail, insertSelection))
                assertEquals(label, "def", run.source?.text)
                assertEquals(label, TextRange(3, 6), run.source?.range)
                // Unchanged, changed or unreadable: only part of the selection was read.
                assertEquals(
                    label,
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT),
                    run.replace("doc", "abcdef", cursorIndex = 6),
                )
                assertEquals(
                    label,
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT),
                    run.replace("doc", "abXdef", cursorIndex = 6),
                )
                assertEquals(
                    label,
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT),
                    run.replace("doc", null, cursorIndex = 6),
                )
                assertEquals(ToolPhase.PREVIEWING, run.phase)
                assertNotNull(run.preview)
            }
        }
    }

    @Test
    fun aTruncatedSelectionCanStillInsertOnlyWithInsertPreservingSelection() {
        for (insertSelection in listOf(false, true)) {
            val run = truncatedSelectionRun(ToolDocumentCapabilities(insertPreservingSelection = insertSelection))
            if (insertSelection) {
                val request = requested(run.insert("doc", cursorIndex = 6))
                assertEquals(WriteKind.INSERT, request.kind)
                assertEquals(TextRange(6, 9), request.range)
            } else {
                assertEquals(
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
                    run.insert("doc", cursorIndex = 6),
                )
                assertEquals(ToolPhase.PREVIEWING, run.phase)
            }
        }
    }

    @Test
    fun aTruncatedPrecedingTailIsReplacedOnlyWithReplacePrecedingTail() {
        for (replaceTail in listOf(false, true)) {
            val run =
                limitedBeforeCursor(
                    "0123456789",
                    maxUnits = 4,
                    documentCapabilities = ToolDocumentCapabilities(replacePrecedingTail = replaceTail),
                )
            assertEquals("6789", run.source?.text)
            if (replaceTail) {
                val request = requested(run.replace("doc", "0123456789", cursorIndex = 10))
                assertEquals(WriteKind.REPLACE, request.kind)
                assertEquals(TextRange(6, 10), request.range)
                assertEquals(4, request.replacedUnitCount)
            } else {
                assertEquals(
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT),
                    run.replace("doc", "0123456789", cursorIndex = 10),
                )
                assertEquals(ToolPhase.PREVIEWING, run.phase)
                // An insert at the caret replaces nothing and needs no capability.
                assertEquals(WriteKind.INSERT, requested(run.insert("doc", cursorIndex = 10)).kind)
            }
        }
    }

    @Test
    fun aChangedTruncatedPrecedingTextInsertsWithoutAnyCapability() {
        val run = limitedBeforeCursor("0123456789", maxUnits = 4)
        val request = requested(run.replace("doc", "012345678X", cursorIndex = 10))
        assertEquals(WriteKind.INSERT, request.kind)
        assertEquals(TextRange(10, 16), request.range)
        assertEquals(ToolSourceChange.CHANGED, request.source)
        assertTrue(request.sourceChanged)
    }

    @Test
    fun anInsertIntoALiveSelectionNeedsInsertPreservingSelection() {
        for (insertSelection in listOf(false, true)) {
            val run =
                begin(
                    editor = selectionEditor,
                    documentCapabilities = ToolDocumentCapabilities(insertPreservingSelection = insertSelection),
                )
            assertTrue(run.complete(run.runId, listOf("ABC")))
            if (insertSelection) {
                assertEquals(WriteKind.INSERT, requested(run.insert("doc", cursorIndex = 3)).kind)
            } else {
                assertEquals(
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
                    run.insert("doc", cursorIndex = 3),
                )
                // The selection's text must not be rewritten by an insert either.
                assertEquals(
                    WriteStart.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
                    run.replace("doc", "abcX", cursorIndex = 3),
                )
                assertEquals(ToolPhase.PREVIEWING, run.phase)
            }
        }
    }

    @Test
    fun anUntruncatedReplaceNeverNeedsACapability() {
        val preceding = previewing()
        assertEquals(WriteKind.REPLACE, requested(preceding.replace("doc", "hello", cursorIndex = 5)).kind)

        val selection = begin(editor = selectionEditor, documentCapabilities = ToolDocumentCapabilities())
        assertTrue(selection.complete(selection.runId, listOf("ABC")))
        val request = requested(selection.replace("doc", "abc", cursorIndex = 3))
        assertEquals(WriteKind.REPLACE, request.kind)
        assertEquals(TextRange(0, 3), request.range)
        assertEquals(3, request.replacedUnitCount)
    }

    @Test
    fun theTwoCapabilitiesAreIndependent() {
        for (replaceTail in listOf(false, true)) {
            for (insertSelection in listOf(false, true)) {
                val label = "replacePrecedingTail=" + replaceTail + " insertPreservingSelection=" + insertSelection
                val capabilities = ToolDocumentCapabilities(replaceTail, insertSelection)

                // 1. Truncated preceding, unchanged: only replacePrecedingTail matters.
                val preceding = limitedBeforeCursor("0123456789", maxUnits = 4, documentCapabilities = capabilities)
                assertEquals(label, replaceTail, preceding.replace("doc", "0123456789", 10) is WriteStart.Requested)

                // 2. Truncated selection: always refused while the gateway cannot carry it.
                val truncatedSelection = truncatedSelectionRun(capabilities)
                assertTrue(label, truncatedSelection.replace("doc", "abcdef", 6) is WriteStart.Blocked)

                // 3. Insert into a live selection: only insertPreservingSelection matters.
                val selectionRun = begin(editor = selectionEditor, documentCapabilities = capabilities)
                assertTrue(selectionRun.complete(selectionRun.runId, listOf("ABC")))
                assertEquals(label, insertSelection, selectionRun.insert("doc", 3) is WriteStart.Requested)

                // 4. Preceding insert: never needs a capability.
                val precedingInsert =
                    limitedBeforeCursor("0123456789", maxUnits = 4, documentCapabilities = capabilities)
                assertTrue(label, precedingInsert.insert("doc", 10) is WriteStart.Requested)
            }
        }
    }

    @Test
    fun aCompleteSelectionCanStillBeReplaced() {
        val run =
            begin(
                editor = selectionEditor,
                documentCapabilities = ToolDocumentCapabilities(),
            )
        assertTrue(run.complete(run.runId, listOf("ABC")))
        val request = requested(run.replace("doc", "abc", cursorIndex = 3))
        assertEquals(WriteKind.REPLACE, request.kind)
        assertEquals(TextRange(0, 3), request.range)
        assertEquals(3, request.replacedUnitCount)
    }
}

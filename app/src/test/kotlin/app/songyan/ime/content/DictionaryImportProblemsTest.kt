package app.songyan.ime.content

import app.songyan.ime.packages.DictionaryImportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryImportProblemsTest {
    @Test
    fun everyEntryRowReasonKeepsItsOwnKindAndNamesTheLine() {
        val rowReasons =
            listOf(
                DictionaryImportError.Reason.INVALID_ROW to DictionaryImportProblemKind.INVALID_ROW,
                DictionaryImportError.Reason.NON_HAN_WORD to DictionaryImportProblemKind.INVALID_WORD,
                DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH to
                    DictionaryImportProblemKind.SYLLABLE_COUNT_MISMATCH,
                DictionaryImportError.Reason.UNKNOWN_SYLLABLE to DictionaryImportProblemKind.UNKNOWN_SYLLABLE,
                DictionaryImportError.Reason.INVALID_WEIGHT to DictionaryImportProblemKind.INVALID_WEIGHT,
            )
        for ((reason, kind) in rowReasons) {
            assertEquals(kind, DictionaryImportProblems.kindOf(reason))
            assertTrue(DictionaryImportProblems.namesLine(kind))
        }
        assertFalse(DictionaryImportProblems.namesLine(DictionaryImportProblemKind.EMPTY))
    }

    @Test
    fun theLocationIsTheFileNameAndTheLine() {
        assertEquals("a.dict.yaml" to 42, DictionaryImportProblems.parseLocation("dir/a.dict.yaml:42"))
        assertNull(DictionaryImportProblems.parseLocation(null))
        assertNull(DictionaryImportProblems.parseLocation("a.dict.yaml"))
        assertNull(DictionaryImportProblems.parseLocation("a.dict.yaml:x"))
    }
}

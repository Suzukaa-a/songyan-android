package app.songyan.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure boundary logic: librime key mapping, the flat snapshot
 * decode and the strict JSON decoders. None of this needs the native library, so a
 * malformed payload is rejected here instead of on a device.
 */
class RimeKeyTest {
    @Test
    fun reservedKeyValuesMapToLibrimeKeysyms() {
        assertEquals(RimeKeyCode.BACKSPACE, RimeKey.fromKeyValue("\u0008")?.keycode)
        assertEquals(RimeKeyCode.SPACE, RimeKey.fromKeyValue(" ")?.keycode)
        assertEquals(RimeKeyCode.RETURN, RimeKey.fromKeyValue("\n")?.keycode)
        assertEquals(RimeKeyCode.ESCAPE, RimeKey.fromKeyValue("Esc")?.keycode)
    }

    @Test
    fun lowercaseLettersAreBaseKeysAndCapitalsCarryShift() {
        assertEquals(RimeKeyCode.LOWERCASE_A, RimeKey.fromKeyValue("a")?.keycode)
        assertEquals(RimeKey(RimeKeyCode.Z, RimeModifier.SHIFT), RimeKey.fromKeyValue("Z"))
        assertEquals(RimeKeyCode.DIGIT_0, RimeKey.fromKeyValue("0")?.keycode)
        assertEquals(RimeKeyCode.APOSTROPHE, RimeKey.fromKeyValue("'")?.keycode)
    }

    @Test
    fun nonAsciiAndLongInputIsNotAnEngineKey() {
        assertNull(RimeKey.fromKeyValue(""))
        assertNull(RimeKey.fromKeyValue("你"))
        assertNull(RimeKey.fromKeyValue("abc"))
        assertNull(RimeKey.fromKeyValue("\u0001"))
    }
}

class RimeSnapshotDecodeTest {
    private fun ints(
        composing: Int = 0,
        ascii: Int = 0,
        fullShape: Int = 0,
        asciiPunct: Int = 0,
        cursor: Int = 0,
        selStart: Int = 0,
        selEnd: Int = 0,
        page: Int = 0,
        pageSize: Int = 5,
        lastPage: Int = 1,
        highlighted: Int = 0,
        labels: Int = 0,
        candidates: Int = 0,
    ) = intArrayOf(
        composing,
        ascii,
        fullShape,
        asciiPunct,
        cursor,
        selStart,
        selEnd,
        page,
        pageSize,
        lastPage,
        highlighted,
        labels,
        candidates,
    )

    @Test
    fun decodesPrimitivesTextAndCandidates() {
        val snapshot =
            RimeNative.decodeSnapshot(
                ints(
                    composing = 1,
                    asciiPunct = 1,
                    cursor = 3,
                    selEnd = 2,
                    highlighted = 1,
                    labels = 2,
                    candidates = 2,
                ),
                arrayOf(
                    "songyan_quanpin",
                    "内置全拼",
                    "nihao",
                    "ni hao",
                    "你好",
                    "asdf",
                    """{"separator":" ","syllables":["ni","hao"]}""",
                    "甲",
                    "乙",
                    "你好",
                    "ni hao",
                    "拟好",
                    null,
                ),
            )
        assertEquals("songyan_quanpin", snapshot.schemaId)
        assertEquals("内置全拼", snapshot.schemaName)
        assertTrue(snapshot.isComposing)
        assertFalse(snapshot.isAsciiMode)
        assertTrue(snapshot.isAsciiPunct)
        assertEquals("nihao", snapshot.input)
        assertEquals("ni hao", snapshot.preedit)
        assertEquals(3, snapshot.preeditCursor)
        assertEquals(0, snapshot.preeditSelStart)
        assertEquals(2, snapshot.preeditSelEnd)
        assertEquals(5, snapshot.pageSize)
        assertTrue(snapshot.isLastPage)
        assertEquals(1, snapshot.highlightedIndex)
        assertEquals("你好", snapshot.commitText)
        assertEquals(RimeHighlightedSyllables(" ", listOf("ni", "hao")), snapshot.highlightedSyllables)
        assertEquals(2, snapshot.candidates.size)
        assertEquals(RimeCandidate("你好", "ni hao", "a", "甲"), snapshot.candidates[0])
        assertEquals(RimeCandidate("拟好", null, "s", "乙"), snapshot.candidates[1])
    }

    @Test
    fun astralTextAndNegativeIndexSurvive() {
        val snapshot =
            RimeNative.decodeSnapshot(
                ints(highlighted = -1, candidates = 1),
                arrayOf("s", "n", "", "", "", null, null, "\uD83D\uDE00", null),
            )
        assertEquals(-1, snapshot.highlightedIndex)
        assertEquals("\uD83D\uDE00", snapshot.candidates.single().text)
        assertNull(snapshot.highlightedSyllables)
        assertNull(snapshot.candidates.single().selectKey)
    }

    @Test
    fun undeclaredSelectMetadataStaysNull() {
        val snapshot =
            RimeNative.decodeSnapshot(ints(), arrayOf("s", "n", "", "", "", null, null))
        assertFalse(snapshot.isComposing)
        assertTrue(snapshot.candidates.isEmpty())
    }

    @Test
    fun aShortStringArrayDropsCandidatesInsteadOfInventingThem() {
        // Declares three candidates but carries one pair: the bridge is wrong, the decoder
        // must neither throw nor fabricate the missing ones.
        val snapshot =
            RimeNative.decodeSnapshot(
                ints(candidates = 3),
                arrayOf("s", "n", "", "", "", null, null, "a", null),
            )
        assertEquals(listOf("a"), snapshot.candidates.map { it.text })
        val none = RimeNative.decodeSnapshot(ints(labels = 4, candidates = 2), arrayOf("s"))
        assertTrue(none.candidates.isEmpty())
        assertEquals("s", none.schemaId)
        assertEquals("", none.input)
    }
}

class RimeAmbiguitySpellingsTest {
    @Test
    fun decodesTokenAliasReport() {
        val json =
            """
            {"revision_input":"bugc","segment":{"start":0,"end":4},
             "lock_mode":"bounded_token_alias","spellings":["bu","pu"],"lockable":["bu"],
             "complete":true}
            """.trimIndent()
        val report = RimeAmbiguitySpellings.fromJson(json)
        assertEquals("bugc", report?.revisionInput)
        assertEquals(0, report?.segmentStart)
        assertEquals(4, report?.segmentEnd)
        assertEquals(listOf("bu", "pu"), report?.spellings)
        assertEquals(listOf("bu"), report?.lockable)
        assertEquals(RimeLockMode.TOKEN_ALIAS, report?.lockMode)
        assertTrue(report?.isComplete == true)
        assertTrue(report?.isAmbiguous == true)
    }

    @Test
    fun rejectsUnknownLockModeAndUnsortedLists() {
        val unknownMode =
            """
            {"revision_input":"ab","segment":{"start":0,"end":2},
             "lock_mode":"future_mode","spellings":[],"lockable":[],"complete":true}
            """.trimIndent()
        assertNull(RimeAmbiguitySpellings.fromJson(unknownMode))

        val unsorted =
            """
            {"revision_input":"ab","segment":{"start":0,"end":2},
             "lock_mode":"none","spellings":["b","a"],"lockable":[],"complete":true}
            """.trimIndent()
        assertNull(RimeAmbiguitySpellings.fromJson(unsorted))
    }

    @Test
    fun rejectsSegmentOutsideRevisionInput() {
        val json =
            """
            {"revision_input":"ab","segment":{"start":0,"end":99},
             "lock_mode":"none","spellings":[],"lockable":[],"complete":true}
            """.trimIndent()
        assertNull(RimeAmbiguitySpellings.fromJson(json))
    }

    @Test
    fun prefixModeRequiresSemanticsForEveryLockableOption() {
        val valid =
            """
            {"revision_input":"26","segment":{"start":0,"end":2},
             "lock_mode":"prefix_token","spellings":["an","ao"],"lockable":["a"],
             "prefix_options":["a"],"option_semantics":{"a":"mixed"},"complete":true}
            """.trimIndent()
        val report = RimeAmbiguitySpellings.fromJson(valid)
        assertEquals(RimeLockMode.PREFIX_TOKEN, report?.lockMode)
        assertEquals(listOf("a"), report?.prefixOptions)

        val missingSemantics = valid.replace("\"option_semantics\":{\"a\":\"mixed\"},", "")
        assertNull(RimeAmbiguitySpellings.fromJson(missingSemantics))
    }

    @Test
    fun prefixFieldsAreIllegalForOtherModes() {
        val json =
            """
            {"revision_input":"ab","segment":{"start":0,"end":2},
             "lock_mode":"direct_token","spellings":["a"],"lockable":["a"],
             "prefix_options":["a"],"complete":true}
            """.trimIndent()
        assertNull(RimeAmbiguitySpellings.fromJson(json))
    }

    @Test
    fun applyResultMapsCodes() {
        assertEquals(RimeAmbiguityApplyResult.APPLIED, RimeAmbiguityApplyResult.fromCode(0))
        assertEquals(RimeAmbiguityApplyResult.FAILED_ROLLBACK_UNVERIFIED, RimeAmbiguityApplyResult.fromCode(5))
        assertEquals(RimeAmbiguityApplyResult.FAILED, RimeAmbiguityApplyResult.fromCode(42))
    }
}

class RimePageSelectMetadataTest {
    @Test
    fun decodesDeclaredMetadata() {
        val json = """{"page_size":3,"select_keys":["a","s","d"],"select_labels":["甲","乙","丙"]}"""
        val metadata = RimePageSelectMetadata.fromJson(json)
        assertEquals(3, metadata?.pageSize)
        assertEquals("a", metadata?.selectKey(0))
        assertEquals("丙", metadata?.selectLabel(2))
        assertNull(metadata?.selectLabel(3))
    }

    @Test
    fun undeclaredListsStayNull() {
        val metadata = RimePageSelectMetadata.fromJson("""{"page_size":5,"select_keys":null,"select_labels":null}""")
        assertEquals(5, metadata?.pageSize)
        assertNull(metadata?.selectKeys)
        assertNull(metadata?.selectKey(0))
    }

    @Test
    fun partialLabelListIsRejected() {
        assertNull(RimePageSelectMetadata.fromJson("""{"page_size":5,"select_labels":["甲"]}"""))
    }
}

class RimeSyllablesJsonTest {
    @Test
    fun decodesHighlightedSyllables() {
        val value = RimeHighlightedSyllables.fromJson("""{"separator":"'","syllables":["zao","shang"]}""")
        assertEquals("'", value?.separator)
        assertEquals(listOf("zao", "shang"), value?.syllables)
    }

    @Test
    fun emptySyllableListIsNotAProof() {
        assertNull(RimeHighlightedSyllables.fromJson("""{"syllables":[]}"""))
        assertNull(RimeHighlightedSyllables.fromJson("not json"))
    }

    @Test
    fun candidateSyllablesRequireCompletePaths() {
        val complete = RimeCandidateSyllables.fromJson("""{"paths":[["ni","hao"]],"complete":true}""")
        assertEquals(listOf(listOf("ni", "hao")), complete?.paths)
        assertTrue(complete?.isComplete == true)

        val incomplete = RimeCandidateSyllables.fromJson("""{"paths":[["ni","hao"]],"complete":false}""")
        assertFalse(incomplete?.isComplete == true)
    }
}

class RimeRewriteVerificationTest {
    @Test
    fun mapsTheFourOutcomes() {
        assertEquals(
            RimeRewriteVerification.Absent,
            RimeRewriteVerification.fromJson("""{"present":false,"verified":false,"checked":0}"""),
        )
        assertEquals(
            RimeRewriteVerification.Verified,
            RimeRewriteVerification.fromJson("""{"present":true,"verified":true,"checked":3}"""),
        )
        assertEquals(
            RimeRewriteVerification.Failed(3, listOf("bu")),
            RimeRewriteVerification.fromJson("""{"present":true,"verified":false,"checked":3,"failures":["bu"]}"""),
        )
        assertEquals(RimeRewriteVerification.Unavailable, RimeRewriteVerification.fromJson("{}"))
    }
}

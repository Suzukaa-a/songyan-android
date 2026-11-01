package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinHintsTest {
    private val diy =
        ShuangpinHintPreset(
            id = "my-diy",
            name = "我的提示",
            hints =
                mapOf(
                    'q' to "iq",
                    'w' to "ua",
                    'e' to "e",
                    'z' to "ei",
                ),
        )

    private fun problem(block: () -> Unit): ShuangpinHintException =
        try {
            block()
            throw AssertionError("expected a refusal")
        } catch (e: ShuangpinHintException) {
            e
        }

    @Test
    fun theBuiltinPresetCarriesTheXiaoheTable() {
        val builtin = ShuangpinHintPresets.builtinXiaohe
        assertEquals("xiaohe-26", builtin.id)
        assertEquals(24, builtin.hints.size)
        assertEquals("iang/uang", builtin.hints['l'])
        assertEquals(LetterGroupLayout.ALPHABET - setOf('a', 'e'), builtin.hints.keys)
        builtin.validate()
    }

    @Test
    fun theDefaultIsOffAndPointsAtTheBuiltinPreset() {
        val snapshot = ShuangpinHintSnapshot()
        assertFalse(snapshot.enabled)
        assertNull(snapshot.activePreset)
        assertEquals(listOf("xiaohe-26"), snapshot.presets.map { it.id })
        assertTrue(snapshot.userPresets.isEmpty())
    }

    @Test
    fun aSnapshotRoundTripsThroughTheStrictDecoder() {
        val snapshot =
            ShuangpinHintSnapshot(
                enabled = true,
                selectedPresetId = diy.id,
                presets = listOf(ShuangpinHintPresets.builtinXiaohe, diy),
            )
        val decoded = ShuangpinHintSnapshot.decode(snapshot.encoded())
        assertEquals(snapshot, decoded)
        assertEquals(diy.hints, decoded.activePreset?.hints)
        assertEquals(diy, ShuangpinHintPreset.decode(diy.encoded()))
    }

    /** The document an iOS export writes, field names and all. */
    @Test
    fun anIosSettingsFileImportsThePresetInUse() {
        val text =
            """
            {"enabled": true, "presets": [
              {"hints": {"q": "iu"}, "id": "xiaohe-26", "name": "小鹤双拼提示", "version": 1},
              {"hints": {"Q": "iq"}, "id": "my-diy", "name": "我的提示", "version": 1}
            ], "selectedPresetID": "my-diy", "version": 1}
            """.trimIndent()
        assertEquals(mapOf('q' to "iq"), ShuangpinHintSnapshot.decodePresetFile(text).hints)
        val off =
            text
                .replace(
                    "\"enabled\": true",
                    "\"enabled\": false",
                ).replace("\"selectedPresetID\": \"my-diy\"", "\"selectedPresetID\": \"xiaohe-26\"")
        assertEquals("my-diy", ShuangpinHintSnapshot.decodePresetFile(off).id)
    }

    @Test
    fun anInconsistentSnapshotIsRefusedNotRepaired() {
        val unknown = ShuangpinHintSnapshot(selectedPresetId = "gone")
        assertEquals(ShuangpinHintProblem.MISSING_SELECTION, problem { unknown.validate() }.problem)
        val twice =
            ShuangpinHintSnapshot(
                presets = listOf(ShuangpinHintPresets.builtinXiaohe, ShuangpinHintPresets.builtinXiaohe),
            )
        assertEquals(ShuangpinHintProblem.DUPLICATE_PRESET, problem { twice.validate() }.problem)
    }

    @Test
    fun badFieldsAreRefusedWithTheFieldNamed() {
        fun doc(body: String) = "{\"id\": \"x\", \"name\": \"n\", $body}"
        assertEquals("extra", problem { ShuangpinHintPreset.decode(doc("\"extra\": 1")) }.field)
        assertEquals(
            ShuangpinHintProblem.UNSUPPORTED_VERSION,
            problem {
                ShuangpinHintPreset.decode(doc("\"version\": 2"))
            }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.WRONG_TYPE,
            problem { ShuangpinHintPreset.decode(doc("\"version\": true")) }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.WRONG_TYPE,
            problem { ShuangpinHintPreset.decode(doc("\"version\": 1.5")) }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.INVALID_LETTER,
            problem {
                ShuangpinHintPreset.decode(doc("\"hints\": {\"ab\": \"x\"}"))
            }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.DUPLICATE_LETTER,
            problem {
                ShuangpinHintPreset.decode(doc("\"hints\": {\"a\": \"x\", \"A\": \"y\"}"))
            }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.HINT_TOO_LONG,
            problem {
                ShuangpinHintPreset.decode(doc("\"hints\": {\"a\": \"${"x".repeat(17)}\"}"))
            }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.INVALID_ID,
            problem {
                ShuangpinHintPreset.decode("{\"id\": \"a b\", \"name\": \"n\"}")
            }.problem,
        )
        assertEquals(
            ShuangpinHintProblem.MISSING_FIELD,
            problem { ShuangpinHintPreset.decode("{\"id\": \"a\"}") }.problem,
        )
        assertEquals(ShuangpinHintProblem.MALFORMED, problem { ShuangpinHintPreset.decode("[1]") }.problem)
        assertEquals(
            ShuangpinHintProblem.WRONG_TYPE,
            problem {
                ShuangpinHintSnapshot.decode("{\"enabled\": 1, \"selectedPresetID\": \"x\"}")
            }.problem,
        )
    }

    @Test
    fun blankHintsAreDroppedAndSixteenBytesAreAccepted() {
        val preset =
            ShuangpinHintPreset(
                id = "a",
                name = "n",
                hints = mapOf('a' to "  ", 'b' to "x".repeat(16)),
            ).normalized()
        assertEquals(setOf('b'), preset.hints.keys)
        preset.validate()
    }

    @Test
    fun aDuplicateGetsANewIdentityAndKeepsTheHints() {
        val copy = diy.duplicate("我的提示 副本")
        assertNotEquals(diy.id, copy.id)
        assertEquals(diy.hints, copy.hints)
        copy.validate()
    }

    @Test
    fun aMergedKeyGetsOneHintPerLetterAndAnAuthoredCornerLabelWins() {
        val qw = LayoutKey("QW", secondaryLabel = "1", action = LayoutAction.code("q"))
        val labels = ShuangpinHintPresets.builtinXiaohe.hints
        assertEquals(listOf("iu", "ei"), ShuangpinHintOverlay.hints(qw, labels, preservingSecondaryLabel = false))
        assertEquals(listOf(null, null), ShuangpinHintOverlay.hints(qw, labels, preservingSecondaryLabel = true))
        val a = LayoutKey("a", action = LayoutAction.code("a"))
        assertEquals(listOf<String?>(null), ShuangpinHintOverlay.hints(a, labels, preservingSecondaryLabel = false))
        val space = LayoutKey("空格", action = LayoutAction(LayoutActionKind.SPACE))
        assertTrue(ShuangpinHintOverlay.hints(space, labels, preservingSecondaryLabel = false).isEmpty())
    }
}

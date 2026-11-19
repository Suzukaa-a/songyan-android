package app.songyan.ime.layout

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One reading of merged keys for 14, 18 and 9 keys: the codes written on the keys, never the titles. */
class LayoutFoldTest {
    private val merged14 = KeyboardLayoutKind.MERGED14

    private fun LayoutDefinition.firstRowKey(index: Int) = rows[0].keys[index]

    /** [this] with its first row's keys at the given indexes covering the given codes, as the editor writes them. */
    private fun LayoutDefinition.withCodes(
        kind: KeyboardLayoutKind,
        vararg changes: Pair<LayoutKey, String>,
    ): LayoutDefinition {
        var definition = LayoutFold.writtenOut(this, kind)
        for ((original, codes) in changes) {
            definition =
                definition.copy(
                    rows =
                        definition.rows.map { row ->
                            LayoutRow(
                                row.keys.map { key ->
                                    if (key.label == original.label &&
                                        key.action == original.action
                                    ) {
                                        LayoutFold.keyCovering(key, codes)
                                    } else {
                                        key
                                    }
                                },
                            )
                        },
                )
        }
        return definition
    }

    @Test
    fun theBundledLayoutsFoldIntoTheBundledFolds() {
        assertSame(CompactFold.MERGED14, LayoutFold.of(BuiltinLayouts.merged14, merged14))
        assertSame(CompactFold.MERGED18, LayoutFold.of(BuiltinLayouts.merged18, KeyboardLayoutKind.MERGED18))
        assertSame(CompactFold.PHONE9, LayoutFold.of(BuiltinLayouts.phone9, KeyboardLayoutKind.PHONE9))
        // Writing out the bundled grouping changes nothing.
        assertSame(
            CompactFold.MERGED14,
            LayoutFold.of(LayoutFold.writtenOut(BuiltinLayouts.merged14, merged14), merged14),
        )
    }

    @Test
    fun titlesOnlySayWhatTheKeysShow() {
        val first = BuiltinLayouts.merged14.firstRowKey(0)
        val retitled =
            BuiltinLayouts.merged14.copy(
                rows =
                    listOf(
                        LayoutRow(
                            listOf(first.copy(label = "Q")) +
                                BuiltinLayouts.merged14.rows[0]
                                    .keys
                                    .drop(1),
                        ),
                    ) +
                        BuiltinLayouts.merged14.rows.drop(1),
            )
        assertSame(CompactFold.MERGED14, LayoutFold.of(retitled, merged14))
        assertEquals(listOf('q', 'w'), LayoutFold.codesOn(retitled, merged14, retitled.firstRowKey(0)))
        val five =
            BuiltinLayouts.phone9.rows
                .flatMap { it.keys }
                .first { it.action.value == "5" }
        val shown =
            BuiltinLayouts.phone9.copy(
                rows =
                    BuiltinLayouts.phone9.rows.map { row ->
                        LayoutRow(
                            row.keys.map {
                                if (it ==
                                    five
                                ) {
                                    it.copy(label = "我的")
                                } else {
                                    it
                                }
                            },
                        )
                    },
            )
        assertSame(CompactFold.PHONE9, LayoutFold.of(shown, KeyboardLayoutKind.PHONE9))
    }

    @Test
    fun aRegroupedFourteenKeyLayoutFoldsAsWritten() {
        val qw = BuiltinLayouts.merged14.firstRowKey(0)
        val er = BuiltinLayouts.merged14.firstRowKey(1)
        val moved = BuiltinLayouts.merged14.withCodes(merged14, qw to "q", er to "wer")
        val fold = LayoutFold.of(moved, merged14)!!
        assertFalse(fold.isBuiltin)
        assertEquals('e', fold.map['w'])
        assertEquals('q', fold.map['q'])
        // ER kept sending e, which is still among its codes.
        assertEquals("e", moved.firstRowKey(1).action.value)
        assertTrue(LayoutFold.problems(moved, merged14).isEmpty)
    }

    @Test
    fun aKeyWhoseCodeLeftItsCodesSendsTheFirstOfThem() {
        val er = BuiltinLayouts.merged14.firstRowKey(1)
        assertEquals("w", LayoutFold.keyCovering(er, "wr").action.value)
        // A 9-key key keeps sending its digit.
        val two =
            BuiltinLayouts.phone9.rows
                .flatMap { it.keys }
                .first { it.action.value == "2" }
        assertEquals("2", LayoutFold.keyCovering(two, "ab").action.value)
    }

    @Test
    fun aRegroupedNineKeyLayoutFoldsAsWritten() {
        val keys = BuiltinLayouts.phone9.rows.flatMap { it.keys }
        val two = keys.first { it.action.value == "2" }
        val three = keys.first { it.action.value == "3" }
        var moved = LayoutFold.writtenOut(BuiltinLayouts.phone9, KeyboardLayoutKind.PHONE9)
        moved =
            moved.copy(
                rows =
                    moved.rows.map { row ->
                        LayoutRow(
                            row.keys.map { key ->
                                when (key.action) {
                                    two.action -> LayoutFold.keyCovering(key, "ab")
                                    three.action -> LayoutFold.keyCovering(key, "cdef")
                                    else -> key
                                }
                            },
                        )
                    },
            )
        val fold = LayoutFold.of(moved, KeyboardLayoutKind.PHONE9)!!
        assertEquals('3', fold.map['c'])
        assertTrue(fold.isPhoneDigits)
    }

    @Test
    fun repeatedCodesAndMissingLettersAreNamed() {
        val qw = BuiltinLayouts.merged14.firstRowKey(0)
        val er = BuiltinLayouts.merged14.firstRowKey(1)
        val repeated = BuiltinLayouts.merged14.withCodes(merged14, qw to "qwe")
        assertNull(LayoutFold.of(repeated, merged14))
        assertEquals(listOf('e'), LayoutFold.problems(repeated, merged14).repeated)
        val missing = BuiltinLayouts.merged14.withCodes(merged14, er to "r")
        assertEquals(listOf('e'), LayoutFold.problems(missing, merged14).missing)
    }

    @Test
    fun strokesAreCodesLikeAnyOther() {
        // Codes that are not latin letters are not checked against a-z.
        val qw = BuiltinLayouts.merged14.firstRowKey(0)
        val withStroke = BuiltinLayouts.merged14.withCodes(merged14, qw to "qw;")
        assertTrue(LayoutFold.problems(withStroke, merged14).missing.isEmpty())
        assertEquals('q', LayoutFold.of(withStroke, merged14)!!.map[';'])
    }

    @Test
    fun anImportedLayoutThatNamesNoKindIsKnownByWhatItsKeysSend() {
        val unnamed = BuiltinLayouts.merged14.copy(input = BuiltinLayouts.merged14.input.copy(builtinLayout = null))
        assertEquals(merged14, LayoutFold.kindOf(unnamed))
        assertSame(CompactFold.MERGED14, LayoutFold.of(unnamed, merged14))
    }
}

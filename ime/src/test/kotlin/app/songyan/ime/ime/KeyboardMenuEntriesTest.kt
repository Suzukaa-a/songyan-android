package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardMenuEntriesTest {
    private val ice = listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼", "double_pinyin" to "自然码双拼")

    private fun schema(id: String) = KeyboardSchemaDescriptor(id = id, name = id)

    @Test
    fun theMenuOffersEveryInputMethodTheEngineListsLessTheHiddenOnes() {
        assertEquals(ice, KeyboardMenuEntries.schemas(ice, emptySet(), setOf("rime_ice")))
        assertEquals(
            listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼"),
            KeyboardMenuEntries.schemas(ice, setOf("double_pinyin"), setOf("rime_ice")),
        )
    }

    /** Japanese types on romaji and kana boards; hiding one in settings drops its chip, never the one drawn. */
    @Test
    fun aHiddenBoardLeavesTheMenuUnlessTheKeyboardDrawsIt() {
        val boards = listOf("romaji26", "kana12")
        assertEquals(boards, KeyboardMenuEntries.boards(boards, emptySet(), "romaji26"))
        assertEquals(listOf("romaji26"), KeyboardMenuEntries.boards(boards, setOf("kana12"), "romaji26"))
        assertEquals(boards, KeyboardMenuEntries.boards(boards, setOf("kana12"), "kana12"))
        // A hidden shape id never matches a board.
        assertEquals(boards, KeyboardMenuEntries.boards(boards, setOf("qwerty26"), "romaji26"))
    }

    @Test
    fun theInputMethodInUseStaysEvenWhenHidden() {
        val shown = KeyboardMenuEntries.schemas(ice, setOf("rime_ice", "double_pinyin"), setOf("rime_ice"))
        assertEquals(listOf("rime_ice", "double_pinyin_flypy"), shown.map { it.first })
    }

    @Test
    fun beforeTheEngineListsTheBundledOnesStandIn() {
        assertEquals(
            BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS,
            KeyboardMenuEntries
                .schemas(
                    emptyList(),
                    emptySet(),
                    emptySet(),
                ).map {
                    it.first
                },
        )
    }

    @Test
    fun hiddenLayoutsLeaveTheMenuButTheCurrentOneStays() {
        val shown = KeyboardMenuEntries.layouts(setOf("merged14", "phone9"), KeyboardLayoutKind.PHONE9)
        assertEquals(listOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9), shown)
    }

    private val all = KeyboardLayoutKind.entries

    @Test
    fun theLayoutChipsFollowTheShapesTheInputMethodCanUse() {
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema("jyut6ping3"), schema("bopomofo_tw")),
                ),
            )
        val cantonese = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "jyut6ping3")
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, cantonese),
        )
        val zhuyin = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "bopomofo_tw")
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, zhuyin),
        )
        // A bundled scheme keeps every chip.
        val bundled = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, BuiltinTemplateSchemas.QUANPIN)
        assertEquals(
            KeyboardLayoutKind.entries.toList(),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, bundled),
        )
    }

    @Test
    fun nextLayoutSkipsTheDisabledOnes() {
        val offered = KeyboardMenuEntries.layouts(setOf("merged14"), KeyboardLayoutKind.QWERTY26)
        val seen = mutableListOf<KeyboardLayoutKind>()
        var at = KeyboardLayoutKind.QWERTY26
        repeat(all.size * 2) {
            at = KeyboardMenuEntries.nextLayout(offered, at)!!
            seen += at
        }
        assertEquals(false, KeyboardLayoutKind.MERGED14 in seen)
        assertEquals(offered.toSet(), seen.toSet())
    }

    @Test
    fun nextLayoutWrapsAround() {
        assertEquals(all.first(), KeyboardMenuEntries.nextLayout(all, all.last()))
    }

    @Test
    fun nextLayoutDoesNothingWithOneEntryOrNone() {
        assertEquals(null, KeyboardMenuEntries.nextLayout(listOf(KeyboardLayoutKind.PHONE9), KeyboardLayoutKind.PHONE9))
        assertEquals(null, KeyboardMenuEntries.nextLayout(emptyList(), KeyboardLayoutKind.PHONE9))
    }

    @Test
    fun nextLayoutStartsAtTheFirstWhenTheCurrentOneIsNotOffered() {
        val offered = listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.PHONE9)
        assertEquals(KeyboardLayoutKind.MERGED14, KeyboardMenuEntries.nextLayout(offered, KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun nextLayoutSkipsLayoutsTheInputMethodCannotUse() {
        // rime_ice runs on 26 keys only, so only the running layout is left: nothing to switch to.
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(
                        app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor(
                            BuiltinTemplateSchemas.QUANPIN,
                            BuiltinTemplateSchemas.QUANPIN,
                        ),
                        app.songyan.ime.keyboard.engine
                            .KeyboardSchemaDescriptor("rime_ice", "rime_ice"),
                    ),
                ),
            )
        val usable = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "rime_ice")
        val offered = KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26).filter { it in usable }
        assertEquals(null, KeyboardMenuEntries.nextLayout(offered, KeyboardLayoutKind.QWERTY26))
    }

    private fun chips(
        family: InputSchemeFamily,
        main: String?,
        offered: List<KeyboardLayoutKind> = KeyboardLayoutKind.entries,
    ) = KeyboardMenuEntries.layoutChips(offered, InputLayoutPolicy.boardsOf(family), main)

    @Test
    fun japaneseOffersBothBoardsInPlaceOfTheIdentityShape() {
        val shown = chips(InputSchemeFamily.JAPANESE, "kana12")
        assertEquals(
            listOf("romaji26", "kana12"),
            shown.filter { it.kind == KeyboardLayoutKind.QWERTY26 }.map { it.boardId },
        )
        assertEquals(
            listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
            shown.filter { it.kind != KeyboardLayoutKind.QWERTY26 }.map { it.kind },
        )
    }

    @Test
    fun zhuyinKeepsOneChipNamedByItsBoard() {
        assertEquals(
            listOf(KeyboardMenuEntries.LayoutChip(KeyboardLayoutKind.QWERTY26, "zhuyin41")),
            chips(InputSchemeFamily.ZHUYIN, "zhuyin41", listOf(KeyboardLayoutKind.QWERTY26)),
        )
    }

    @Test
    fun pinyinKeepsTheShapes() {
        assertEquals(
            KeyboardLayoutKind.entries.map { KeyboardMenuEntries.LayoutChip(it) },
            chips(InputSchemeFamily.GENERIC, "qwerty26"),
        )
    }

    @Test
    fun aUsersOwnMainLayoutKeepsTheShapeChipAfterTheBoards() {
        val shown = chips(InputSchemeFamily.JAPANESE, "my-keys", listOf(KeyboardLayoutKind.QWERTY26))
        assertEquals(
            listOf(
                KeyboardMenuEntries.LayoutChip(KeyboardLayoutKind.QWERTY26, "romaji26"),
                KeyboardMenuEntries.LayoutChip(KeyboardLayoutKind.QWERTY26, "kana12"),
                KeyboardMenuEntries.LayoutChip(KeyboardLayoutKind.QWERTY26),
            ),
            shown,
        )
    }

    @Test
    fun aHiddenIdentityShapeLeavesNoBoardChips() {
        val offered = KeyboardMenuEntries.layouts(setOf("qwerty26"), KeyboardLayoutKind.PHONE9)
        assertEquals(
            listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
            chips(InputSchemeFamily.JAPANESE, "romaji26", offered).map { it.kind },
        )
    }

    private val kana = OtherInputMethod("scheme-kana", "kagiroi", "かな", "Kagiroi")
    private val romaji = OtherInputMethod("scheme-kana", "kagiroi_romaji", "ローマ字", "Kagiroi")
    private val running = ice.map { it.first }.toSet()

    @Test
    fun inputMethodsOfOtherPackagesFollowTheRunningOnesWithAnIdThatCannotCollide() {
        val chips = KeyboardMenuEntries.otherSchemas(listOf(kana, romaji), emptySet(), running, ice)
        assertEquals(listOf("かな", "ローマ字"), chips.map { it.label })
        assertEquals(listOf(kana.menuId, romaji.menuId), chips.map { it.menuId })
        assertEquals(true, chips.all { it.menuId.startsWith(OtherInputMethod.MENU_ID_PREFIX) && it.menuId !in running })
    }

    @Test
    fun hiddenOnesAndOnesTheRunningEngineAlreadyListsAreLeftOut() {
        val same = OtherInputMethod("scheme-2", "rime_ice", "雾凇拼音", "Other")
        val chips = KeyboardMenuEntries.otherSchemas(listOf(kana, romaji, same), setOf("kagiroi_romaji"), running, ice)
        assertEquals(listOf(kana), chips.map { it.method })
    }

    @Test
    fun aNameThatReadsLikeAnotherChipGetsItsPackageName() {
        val clash = OtherInputMethod("scheme-2", "other_ice", "雾凇拼音", "Other")
        val chips = KeyboardMenuEntries.otherSchemas(listOf(clash, kana), emptySet(), running, ice)
        assertEquals(listOf("雾凇拼音 · Other", "かな"), chips.map { it.label })
        val twins =
            KeyboardMenuEntries.otherSchemas(
                listOf(clash, clash.copy(sourceId = "scheme-3")),
                emptySet(),
                setOf(),
                emptyList(),
            )
        assertEquals(2, twins.map { it.menuId }.toSet().size)
        assertEquals(true, twins.all { it.label.endsWith(" · Other") })
    }
}

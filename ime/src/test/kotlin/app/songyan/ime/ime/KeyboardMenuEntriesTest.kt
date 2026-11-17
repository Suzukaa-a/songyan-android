package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardScheme
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.UserKeyboards
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardMenuEntriesTest {
    private val ice = listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼", "double_pinyin" to "自然码双拼")
    private val all = KeyboardLayoutKind.entries.toSet()
    private val identity = setOf(KeyboardLayoutKind.QWERTY26)

    private fun k(id: String) = requireNotNull(PublishedKeyboard.parse(id)) { id }

    private fun keyboards(
        schemes: List<KeyboardScheme>,
        current: PublishedKeyboard,
        stored: UserKeyboards.Stored = UserKeyboards.Stored(),
        hiddenSchemas: Set<String> = emptySet(),
        hiddenLayouts: Set<String> = emptySet(),
        registry: LayoutRegistry = LayoutRegistry.create(),
    ) = KeyboardMenuEntries.keyboards(
        schemes,
        stored,
        hiddenSchemas,
        hiddenLayouts,
        registry,
        LayoutFamilyContext.UNKNOWN,
        current,
    )

    private val iceSchemes = ice.map { KeyboardScheme(it.first, all) }

    @Test
    fun theMenuOffersEveryInputMethodThatHasAKeyboard() {
        val current = k("rime_ice:qwerty26")
        assertEquals(ice, KeyboardMenuEntries.schemas(ice, keyboards(iceSchemes, current), setOf("rime_ice")))
        assertEquals(
            listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼"),
            KeyboardMenuEntries.schemas(
                ice,
                keyboards(iceSchemes, current, hiddenSchemas = setOf("double_pinyin")),
                setOf("rime_ice"),
            ),
        )
        // An input method whose keyboards the user removed leaves the menu the same way.
        val none = UserKeyboards.Stored(schemes = setOf("double_pinyin_flypy"))
        assertEquals(
            listOf("rime_ice", "double_pinyin"),
            KeyboardMenuEntries.schemas(ice, keyboards(iceSchemes, current, none), setOf("rime_ice")).map { it.first },
        )
    }

    @Test
    fun theInputMethodInUseStaysEvenWhenHidden() {
        val shown =
            KeyboardMenuEntries.schemas(
                ice,
                keyboards(iceSchemes, k("rime_ice:qwerty26"), hiddenSchemas = setOf("rime_ice", "double_pinyin")),
                setOf("rime_ice"),
            )
        assertEquals(listOf("rime_ice", "double_pinyin_flypy"), shown.map { it.first })
    }

    @Test
    fun beforeTheEngineListsTheBundledOnesStandIn() {
        val bundled = BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS.map { KeyboardScheme(it, all) }
        val shown =
            KeyboardMenuEntries.schemas(
                emptyList(),
                keyboards(bundled, k(BuiltinTemplateSchemas.QUANPIN + ":qwerty26")),
                emptySet(),
            )
        assertEquals(BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS, shown.map { it.first })
    }

    @Test
    fun hiddenLayoutsLeaveTheMenuButTheCurrentOneStays() {
        val shown = keyboards(iceSchemes, k("rime_ice:phone9"), hiddenLayouts = setOf("merged14", "phone9"))
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:merged18#merged18", "rime_ice:phone9#phone9"),
            shown.of("rime_ice").map { it.id },
        )
        assertEquals("rime_ice:phone9#phone9", shown.current.id)
        // Another input method has no keyboard in use to keep.
        assertEquals(
            listOf("double_pinyin:qwerty26#qwerty26", "double_pinyin:merged18#merged18"),
            shown.of("double_pinyin").map { it.id },
        )
    }

    private fun schema(id: String) = KeyboardSchemaDescriptor(id = id, name = id)

    @Test
    fun theLayoutChipsFollowTheShapesTheInputMethodCanUse() {
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema("jyut6ping3"), schema("bopomofo_tw")),
                ),
            )
        val shapes = set.availableCombinations.groupBy({ it.schemaID }, { it.layout })
        val schemes =
            listOf(BuiltinTemplateSchemas.QUANPIN, "jyut6ping3", "bopomofo_tw").map {
                KeyboardScheme(it, shapes[it].orEmpty().toSet())
            }
        val shown = keyboards(schemes, k(BuiltinTemplateSchemas.QUANPIN + ":qwerty26"))
        assertEquals(listOf("jyut6ping3:qwerty26#qwerty26"), shown.of("jyut6ping3").map { it.id })
        assertEquals(listOf("bopomofo_tw:qwerty26#zhuyin41"), shown.of("bopomofo_tw").map { it.id })
        // A bundled scheme keeps every chip.
        assertEquals(
            KeyboardLayoutKind.entries.map { it.wire },
            shown.of(BuiltinTemplateSchemas.QUANPIN).map { it.board },
        )
    }

    @Test
    fun japaneseOffersBothBoardsAndAUsersOwnMainLayoutFollowsThem() {
        val japanese = listOf(KeyboardScheme("kagiroi", identity))
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12"),
            keyboards(japanese, k("kagiroi:qwerty26")).of("kagiroi").map { it.id },
        )
        val mine =
            LayoutRegistry
                .create()
                .copy(BuiltinLayouts.qwerty26, "my-keys", "mine")
                .first
                .withComposition(KeyboardComposition(mainLayoutID = "my-keys"), "kagiroi:qwerty26")
        val shown = keyboards(japanese, k("kagiroi:qwerty26"), registry = mine)
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12", "kagiroi:qwerty26#my-keys"),
            shown.of("kagiroi").map { it.id },
        )
        assertEquals("kagiroi:qwerty26#my-keys", shown.current.id)
    }

    @Test
    fun theKeyboardsTheUserEditedReplaceTheDerivedOnesOfThatInputMethodOnly() {
        val mine = LayoutRegistry.create().copy(BuiltinLayouts.qwerty26, "my-26", "mine").first
        val stored = UserKeyboards.Stored(listOf(k("rime_ice:qwerty26#my-26")), setOf("rime_ice"))
        val shown = keyboards(iceSchemes, k("rime_ice:qwerty26#my-26"), stored, registry = mine)
        assertEquals(listOf("rime_ice:qwerty26#my-26"), shown.of("rime_ice").map { it.id })
        assertEquals(4, shown.of("double_pinyin").size)
    }

    @Test
    fun aStoredKeyboardThatCannotRunOrBeDrawnIsLeftOut() {
        val stored =
            UserKeyboards.Stored(
                listOf(k("rime_ice:qwerty26#gone"), k("rime_ice:merged14#merged14"), k("rime_ice:qwerty26#qwerty26")),
                setOf("rime_ice"),
            )
        val shown = keyboards(listOf(KeyboardScheme("rime_ice", identity)), k("rime_ice:qwerty26#qwerty26"), stored)
        assertEquals(listOf("rime_ice:qwerty26#qwerty26"), shown.of("rime_ice").map { it.id })
    }

    @Test
    fun theKeyboardInUseIsAmongItsInputMethodsEvenWhenTheListLacksIt() {
        val stored = UserKeyboards.Stored(listOf(k("rime_ice:phone9#phone9")), setOf("rime_ice"))
        val shown = keyboards(iceSchemes, k("rime_ice:qwerty26"), stored)
        assertEquals(
            listOf("rime_ice:phone9#phone9", "rime_ice:qwerty26#qwerty26"),
            shown.of("rime_ice").map { it.id },
        )
        assertEquals(1, shown.all.count { it.schemaID == "rime_ice" })
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

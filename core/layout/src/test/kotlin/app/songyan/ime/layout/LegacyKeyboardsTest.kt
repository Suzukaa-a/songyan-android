package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyboards of a user who never edited the list are the ones the old menu offered: the input
 * methods not hidden, on the layouts not hidden, each drawing what its shape's pairing said.
 */
class LegacyKeyboardsTest {
    private val all = KeyboardLayoutKind.entries.toSet()
    private val identity = setOf(KeyboardLayoutKind.QWERTY26)

    private fun ids(
        schemes: List<KeyboardScheme>,
        hiddenSchemas: Set<String> = emptySet(),
        hiddenLayouts: Set<String> = emptySet(),
        registry: LayoutRegistry = LayoutRegistry.create(),
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
        current: PublishedKeyboard? = null,
    ): List<String> =
        LegacyKeyboards.of(schemes, hiddenSchemas, hiddenLayouts, registry, families, current).map { it.id }

    private fun withMine(): LayoutRegistry =
        LayoutRegistry.create().copy(BuiltinLayouts.qwerty26, "my-26", "mine").first

    @Test
    fun withoutAPairingEveryShapeDrawsItsOwnLayout() {
        assertEquals(
            listOf(
                "rime_ice:qwerty26#qwerty26",
                "rime_ice:merged14#merged14",
                "rime_ice:merged18#merged18",
                "rime_ice:phone9#phone9",
            ),
            ids(listOf(KeyboardScheme("rime_ice", all))),
        )
    }

    @Test
    fun aShapeTheEngineCannotRunIsLeftOut() {
        assertEquals(listOf("wubi86:qwerty26#qwerty26"), ids(listOf(KeyboardScheme("wubi86", identity))))
    }

    @Test
    fun aPairingWithThePresetAsItsMainLayoutChangesNothing() {
        val registry =
            LayoutRegistry.create().withComposition(
                KeyboardComposition(mainLayoutID = "phone9", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID),
                "rime_ice:phone9",
            )
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:phone9#phone9"),
            ids(
                listOf(KeyboardScheme("rime_ice", setOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.PHONE9))),
                registry = registry,
            ),
        )
    }

    @Test
    fun aPairingWithTheUsersLayoutMakesThatLayoutTheKeyboard() {
        val registry = withMine().withComposition(KeyboardComposition(mainLayoutID = "my-26"), "rime_ice:qwerty26")
        assertEquals(
            listOf("rime_ice:qwerty26#my-26", "flypy:qwerty26#qwerty26"),
            ids(listOf(KeyboardScheme("rime_ice", identity), KeyboardScheme("flypy", identity)), registry = registry),
        )
    }

    @Test
    fun aFamilyWithOneBoardTypesOnIt() {
        assertEquals(
            listOf("bopomofo:qwerty26#zhuyin41", "cangjie5:qwerty26#cangjie26"),
            ids(listOf(KeyboardScheme("bopomofo", identity), KeyboardScheme("cangjie5", identity))),
        )
        // An alias the id alone does not place follows the family the caller resolved.
        assertEquals(
            listOf("dachen:qwerty26#zhuyin41"),
            ids(
                listOf(KeyboardScheme("dachen", identity)),
                families = LayoutFamilyContext.of("dachen" to InputSchemeFamily.ZHUYIN),
            ),
        )
    }

    @Test
    fun aSavedPairingOfABoardFamilyKeepsItsOwnChoiceNextToTheBoard() {
        // The plain 26 keys were saved for a zhuyin scheme: the menu showed the board and the shape.
        val registry =
            LayoutRegistry.create().withComposition(KeyboardComposition(mainLayoutID = "qwerty26"), "bopomofo:qwerty26")
        assertEquals(
            listOf("bopomofo:qwerty26#zhuyin41", "bopomofo:qwerty26#qwerty26"),
            ids(listOf(KeyboardScheme("bopomofo", identity)), registry = registry),
        )
    }

    @Test
    fun japaneseHasOneKeyboardPerBoardWhicheverThePairingChose() {
        val expected = listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12")
        assertEquals(expected, ids(listOf(KeyboardScheme("kagiroi", identity))))
        val kana =
            LayoutRegistry.create().withComposition(
                KeyboardComposition(mainLayoutID = "kana12"),
                "kagiroi:qwerty26",
            )
        assertEquals(expected, ids(listOf(KeyboardScheme("kagiroi", identity)), registry = kana))
    }

    @Test
    fun aShapeTheFamilyForbidsNeverBecomesAKeyboard() {
        // The engine facts may still list folded shapes for a family that cannot use them.
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12"),
            ids(listOf(KeyboardScheme("kagiroi", identity)), hiddenLayouts = emptySet()),
        )
    }

    @Test
    fun hiddenInputMethodsAndLayoutsAreLeftOut() {
        val schemes =
            listOf(KeyboardScheme("rime_ice", all), KeyboardScheme("flypy", all), KeyboardScheme("kagiroi", identity))
        assertEquals(
            listOf(
                "rime_ice:qwerty26#qwerty26",
                "rime_ice:merged18#merged18",
                "kagiroi:qwerty26#romaji26",
            ),
            ids(schemes, hiddenSchemas = setOf("flypy"), hiddenLayouts = setOf("merged14", "phone9", "kana12")),
        )
    }

    @Test
    fun hidingTheIdentityShapeHidesItsBoardsToo() {
        assertEquals(
            emptyList<String>(),
            ids(listOf(KeyboardScheme("kagiroi", identity)), hiddenLayouts = setOf("qwerty26")),
        )
    }

    @Test
    fun theKeyboardInUseStaysEvenWhenHidden() {
        val schemes = listOf(KeyboardScheme("rime_ice", all), KeyboardScheme("flypy", all))
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "flypy:qwerty26#qwerty26", "flypy:phone9#phone9"),
            ids(
                schemes,
                hiddenSchemas = setOf("flypy"),
                hiddenLayouts = setOf("merged14", "merged18", "phone9"),
                current = PublishedKeyboard("flypy", KeyboardLayoutKind.PHONE9),
            ),
        )
    }

    @Test
    fun theBoardInUseStaysEvenWhenHidden() {
        val kana =
            LayoutRegistry.create().withComposition(
                KeyboardComposition(mainLayoutID = "kana12"),
                "kagiroi:qwerty26",
            )
        val schemes = listOf(KeyboardScheme("kagiroi", identity))
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12"),
            ids(
                schemes,
                hiddenLayouts = setOf("kana12"),
                registry = kana,
                current = PublishedKeyboard("kagiroi", KeyboardLayoutKind.QWERTY26),
            ),
        )
        // The same holds once the keyboard in use names its board.
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12"),
            ids(
                schemes,
                hiddenLayouts = setOf("kana12"),
                current = PublishedKeyboard("kagiroi", KeyboardLayoutKind.QWERTY26, "kana12"),
            ),
        )
    }

    @Test
    fun aShapeInUseThatTheEngineNoLongerListsStays() {
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:merged14#merged14"),
            ids(
                listOf(KeyboardScheme("rime_ice", identity)),
                current = PublishedKeyboard("rime_ice", KeyboardLayoutKind.MERGED14),
            ),
        )
    }
}

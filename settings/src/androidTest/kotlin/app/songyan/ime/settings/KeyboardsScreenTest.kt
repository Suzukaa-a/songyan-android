package app.songyan.ime.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.UserKeyboards
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.keyboardList
import app.songyan.ime.settings.ui.screens.KeyboardsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 我的键盘 lists the keyboards the menu switches between, grouped by input method, each named by the
 * layout it draws; a keyboard can be used, added and removed, and the one in use stays.
 */
class KeyboardsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val all = setOf("qwerty26", "merged14", "merged18", "phone9")
    private val pinyin = InstalledScheme("quanpin", "全拼", supportedLayouts = all)
    private val zhuyin = InstalledScheme("bopomofo_tw", "注音", family = InputSchemeFamily.ZHUYIN)
    private val japanese = InstalledScheme("kagiroi", "カギロイ", family = InputSchemeFamily.JAPANESE)
    private val registry = LayoutRegistry.create().copy(BuiltinLayouts.qwerty26, "my-26", "我的布局").first
    private val preferences = InMemorySettingsPreferences()

    private var stored by mutableStateOf(UserKeyboards.Stored())
    private var inUse by mutableStateOf(
        PublishedKeyboard("quanpin", app.songyan.ime.keyboard.layout.KeyboardLayoutKind.QWERTY26),
    )
    private val used = mutableListOf<String>()

    private fun k(id: String) = requireNotNull(PublishedKeyboard.parse(id)) { id }

    /** The screen over real state: an edit is stored and the list is derived again, as the view model does. */
    private fun show(
        schemes: List<InstalledScheme>,
        current: PublishedKeyboard,
        hiddenLayouts: Set<String> = emptySet(),
        otherSources: List<StoredSourceInfo> = emptyList(),
    ) {
        inUse = current
        compose.setContent {
            val list =
                keyboardList(
                    schemes = schemes,
                    inUseSchemeId = inUse.schemaID,
                    layoutId = inUse.layout.wire,
                    layoutDefinitionId = inUse.board,
                    stored = stored,
                    hiddenSchemas = emptySet(),
                    hiddenLayouts = hiddenLayouts,
                    registry = registry,
                )
            SongyanTheme(dynamicColor = false) {
                KeyboardsScreen(
                    list = list,
                    onUse = {
                        used += it.id
                        inUse = it
                    },
                    onAdd = { stored = UserKeyboards.adding(it, list.stored, list.legacy) },
                    onRemove = { stored = UserKeyboards.removing(it, list.stored, list.legacy) },
                    onBack = null,
                    otherSources = otherSources,
                    preferences = preferences,
                )
            }
        }
    }

    private fun removeLabel(
        method: String,
        layout: String,
    ) = str(R.string.keyboards_remove, str(R.string.keyboard_title, method, layout))

    @Test
    fun aPhysicalBoardNamesTheKeyboardInsteadOfTheShapeItRidesOn() {
        show(listOf(zhuyin), k("bopomofo_tw:qwerty26"))
        compose.onNodeWithText(str(R.string.layout_name_zhuyin41)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(str(R.string.layout_name_26)).assertDoesNotExist()
    }

    @Test
    fun anUneditedListShowsWhatTheMenuOfferedLessTheHiddenLayouts() {
        show(listOf(pinyin), k("quanpin:qwerty26"), hiddenLayouts = setOf("merged14"))
        compose.onNodeWithText(str(R.string.layout_name_26)).assertIsSelected()
        compose.onNodeWithText(str(R.string.layout_name_18)).assertExists()
        compose.onNodeWithText(str(R.string.layout_name_9)).assertExists()
        compose.onNodeWithText(str(R.string.layout_name_14)).assertDoesNotExist()
    }

    @Test
    fun aJapaneseKeyboardOffersRomajiAndKanaAndNoShapeItCannotUse() {
        show(listOf(japanese), k("kagiroi:qwerty26"))
        compose.onNodeWithText(str(R.string.layout_name_romaji26)).assertIsSelected()
        // The kana board is its own keyboard, one tap away.
        compose.onNodeWithText(str(R.string.layout_name_kana12)).performClick()
        assertEquals(listOf("kagiroi:qwerty26#kana12"), used)
        compose.onNodeWithText(str(R.string.layout_name_kana12)).assertIsSelected()
        for (shape in listOf(R.string.layout_name_14, R.string.layout_name_18, R.string.layout_name_9)) {
            compose.onNodeWithText(str(shape)).assertDoesNotExist()
        }
        compose.onNodeWithText(str(R.string.layout_name_26)).assertDoesNotExist()
    }

    @Test
    fun aLayoutOfTheUsersOwnIsAddedAsAKeyboardAndCanBeUsed() {
        show(listOf(pinyin), k("quanpin:qwerty26"))
        compose.onNodeWithText("我的布局").assertDoesNotExist()
        compose.onNodeWithText(str(R.string.keyboards_add)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.keyboards_add_title, "全拼")).assertExists()
        compose.onNodeWithText("我的布局").performClick()
        compose.waitForIdle()
        assertEquals(setOf("quanpin"), stored.schemes)
        assertEquals("quanpin:qwerty26#my-26", stored.keyboards.last().id)
        // The keyboards the menu already offered stay, and the dialog is gone.
        assertEquals(5, stored.keyboards.size)
        compose.onNodeWithText(str(R.string.keyboards_add_title, "全拼")).assertDoesNotExist()
        compose.onNodeWithText("我的布局").performScrollTo().performClick()
        assertEquals(listOf("quanpin:qwerty26#my-26"), used)
        compose.onNodeWithText("我的布局").assertIsSelected()
    }

    @Test
    fun aKeyboardIsRemovedButNeverTheOneInUse() {
        show(listOf(pinyin), k("quanpin:qwerty26"))
        compose.onNodeWithContentDescription(removeLabel("全拼", str(R.string.layout_name_26))).assertDoesNotExist()
        compose
            .onNodeWithContentDescription(removeLabel("全拼", str(R.string.layout_name_9)))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.layout_name_9)).assertDoesNotExist()
        assertEquals(
            listOf("quanpin:qwerty26#qwerty26", "quanpin:merged14#merged14", "quanpin:merged18#merged18"),
            stored.keyboards.map { it.id },
        )
    }

    @Test
    fun anInputMethodWithoutKeyboardsSaysSoAndCanGetOneBack() {
        stored = UserKeyboards.Stored(schemes = setOf("bopomofo_tw"))
        show(listOf(pinyin, zhuyin), k("quanpin:qwerty26"))
        compose.onNodeWithText(str(R.string.keyboards_none_for_method)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.keyboards_none_for_method)).performClick()
        compose.onNodeWithText(str(R.string.layout_name_zhuyin41)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("bopomofo_tw:qwerty26#zhuyin41"), stored.keyboards.map { it.id })
        assertEquals(0, compose.onAllNodesWithText(str(R.string.keyboards_none_for_method)).fetchSemanticsNodes().size)
    }

    @Test
    fun inputMethodsOfOtherInputSchemesAreRowsThatCanBeTakenOutAndAddedBack() {
        val other =
            StoredSourceInfo(
                id = "scheme-kana",
                name = "Kana Pack",
                kind = StoredSourceKind.SCHEME,
                inUse = false,
                schemes = listOf(InstalledScheme("kana", "Kana"), InstalledScheme("romaji", "Romaji")),
            )
        show(listOf(pinyin), k("quanpin:qwerty26"), otherSources = listOf(other))
        compose.onNodeWithText("Kana Pack").performScrollTo().assertExists()
        compose.onNodeWithText("Kana").performScrollTo().assertExists()
        compose
            .onNodeWithContentDescription(
                removeLabel("Romaji", str(R.string.layout_name_26)),
            ).performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(setOf("romaji"), runBlocking { preferences.menuHiddenSchemas.first() })
        compose.onNodeWithContentDescription(removeLabel("Romaji", str(R.string.layout_name_26))).assertDoesNotExist()
        compose
            .onAllNodesWithText(str(R.string.keyboards_add))
            .onLast()
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Romaji").performClick()
        compose.waitForIdle()
        assertEquals(emptySet<String>(), runBlocking { preferences.menuHiddenSchemas.first() })
    }
}

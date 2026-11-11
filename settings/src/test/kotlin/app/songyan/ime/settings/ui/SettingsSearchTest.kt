package app.songyan.ime.settings.ui

import app.songyan.ime.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val index =
        listOf(
            SearchResult("options", "输入选项", "键盘", "输入显示与按键反馈"),
            SearchResult("features", "模糊音", "纠错与联想"),
            SearchResult("features", "错键纠正", "纠错与联想"),
            SearchResult("options", "按键音", "输入选项"),
            SearchResult("about", "Export diagnostics", "关于"),
        )

    private fun titles(query: String) = SettingsSearch.filter(index, query).map { it.title }

    @Test
    fun aBlankQueryFindsNothing() {
        assertTrue(titles("").isEmpty())
        assertTrue(titles("   ").isEmpty())
    }

    @Test
    fun everyWordMustMatchSomewhereAndCaseIsIgnored() {
        assertEquals(listOf("模糊音"), titles("模糊"))
        assertEquals(listOf("Export diagnostics"), titles("EXPORT diag"))
        assertEquals(listOf("错键纠正"), titles("纠错 错键"))
    }

    @Test
    fun titleMatchesComeBeforeMatchesInTheSectionOrKeywords() {
        // "输入选项" is the title of one entry and the section of another.
        assertEquals(listOf("输入选项", "按键音"), titles("输入选项"))
    }

    @Test
    fun theSectionAndTheKeywordsAreSearchedToo() {
        assertEquals(listOf("模糊音", "错键纠正"), titles("联想"))
        assertEquals(listOf("输入选项"), titles("按键反馈"))
    }

    @Test
    fun theIdleToolbarSwitchIsFoundByItsName() {
        assertTrue(SettingsSearch.entries.any { it.route == SettingsRoutes.CANDIDATE_BAR })
    }

    @Test
    fun theIndexPointsAtRealPagesOnly() {
        val routes =
            setOf(
                SettingsRoutes.APPEARANCE,
                SettingsRoutes.OPTIONS,
                SettingsRoutes.FEATURES,
                SettingsRoutes.CANDIDATE_BAR,
                SettingsRoutes.TOOLBAR,
                SettingsRoutes.SHUANGPIN,
                SettingsRoutes.KEYBOARD_MENU,
                SettingsRoutes.NINE_KEY_EDITOR,
                SettingsRoutes.TRY_OUT,
                SettingsRoutes.SCHEMES,
                SettingsRoutes.ADD_SCHEME,
                SettingsRoutes.KEYBOARDS,
                SettingsRoutes.LAYOUTS,
                SettingsRoutes.PAGES,
                SettingsRoutes.COMPOSITION,
                SettingsRoutes.LEARNING,
                SettingsRoutes.quickContent(),
                SettingsRoutes.ONBOARDING,
                SettingsRoutes.ABOUT,
                SettingsRoutes.LICENSES,
            )
        assertTrue(SettingsSearch.entries.all { it.route in routes })
    }

    @Test
    fun theNineKeyQuickSymbolsAreFoundUnderMyKeyboards() {
        val entry = SettingsSearch.entries.single { it.route == SettingsRoutes.NINE_KEY_EDITOR }
        assertEquals(SettingsRoutes.KEYBOARDS, SettingsRoutes.topLevelOf(entry.route))
        assertEquals(R.string.layouts_title, entry.section)
    }

    @Test
    fun everydayWordsFindHapticsAndTheSwitchKey() {
        // People say 震动 and 切换输入法 rather than the titles 振动 and 显示切换输入法键.
        val keywords = SettingsSearch.entries.associate { it.title to it.keywords }
        assertEquals(R.string.search_keywords_haptics, keywords[R.string.options_haptics_title])
        assertEquals(R.string.search_keywords_switch_key, keywords[R.string.layouts_switch_key_title])
    }

    @Test
    fun theKeyGestureOptionsAreFoundByName() {
        val titles = SettingsSearch.entries.map { it.title }
        assertTrue(R.string.options_key_flick_title in titles)
        assertTrue(R.string.options_double_space_title in titles)
        assertTrue(R.string.options_long_press_title in titles)
    }
}

package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.panels.EmojiPanelView
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.ime.panels.SymbolPanelView
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutPageTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Automatic key names follow the schemas input language on every phone language, also in English
 * mode; a keycap the user wrote keeps its own label. Panels not keyed by input language yet keep
 * the Chinese names.
 */
@RunWith(AndroidJUnit4::class)
class KeyNamesDeviceTest {
    private fun context(locale: Locale): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration().apply { setLocale(locale) })
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun names(locale: Locale): KeyNames = KeyNames(context(locale))

    private fun space() = LayoutKey("x", action = LayoutAction(LayoutActionKind.SPACE))

    private fun enter() = LayoutKey("x", action = LayoutAction(LayoutActionKind.ENTER))

    private fun languageKey() = LayoutKey("中/英", action = LayoutAction.languageSwitch())

    private fun symbolPageKey() = LayoutKey("符号", action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))

    @Test
    fun automaticNamesFollowTheInputLanguageOnEveryPhoneLanguage() {
        for (locale in listOf(Locale.ENGLISH, Locale.JAPANESE, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            onMain {
                val view = KeyboardView(context(locale))
                view.faces = KeyboardFaces(language = InputLanguage.ZH_HANS)
                assertEquals("空格", view.face(space(), false))
                assertEquals("空格", view.face(space(), true))
                view.faces = KeyboardFaces(language = InputLanguage.ZH_HANT)
                assertEquals("空白", view.face(space(), false))
                assertEquals("空白", view.face(space(), true))
                view.faces = KeyboardFaces(language = InputLanguage.JA)
                assertEquals("空白", view.face(space(), false))
                assertEquals("空白", view.face(space(), true))
            }
        }
    }

    @Test
    fun englishModeKeepsTheSchemasInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            view.faces = KeyboardFaces(language = InputLanguage.JA, english = true)
            assertEquals("空白", view.face(space(), false))
            assertEquals("空白", view.face(space(), true))
        }
    }

    @Test
    fun composingNamesFollowTheInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            view.faces = KeyboardFaces(language = InputLanguage.JA, composing = true)
            assertEquals("変換", view.face(space(), false))
            assertEquals("変換", view.face(space(), true))
            view.actionLabel = requireNotNull(names(Locale.ENGLISH).enterComposing(InputLanguage.JA))
            assertEquals("確定", view.face(enter(), false))
            view.faces = KeyboardFaces(language = InputLanguage.ZH_HANS, composing = true)
            assertEquals("空格", view.face(space(), false))
        }
    }

    @Test
    fun enterActionNamesFollowTheInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            for (
            (language, label) in
            listOf(
                InputLanguage.ZH_HANS to "搜索",
                InputLanguage.ZH_HANT to "搜尋",
                InputLanguage.JA to "検索",
            )
            ) {
                view.faces = KeyboardFaces(language = language)
                view.actionLabel = names(Locale.ENGLISH).enterAction(language, R.string.ime_search)
                assertEquals(label, view.face(enter(), false))
                assertEquals(label, view.face(enter(), true))
            }
        }
    }

    @Test
    fun theSymbolPageKeyAndTheLanguageKeyUseTheTableFaces() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            view.faces = KeyboardFaces(language = InputLanguage.ZH_HANS)
            assertEquals("符", view.face(symbolPageKey(), false))
            assertEquals("打开符号页", view.face(symbolPageKey(), true))
            view.faces = KeyboardFaces(language = InputLanguage.ZH_HANT)
            assertEquals("符", view.face(symbolPageKey(), false))
            assertEquals("開啟符號頁", view.face(symbolPageKey(), true))
            assertEquals("中/英", view.face(languageKey(), false))
            view.faces = KeyboardFaces(language = InputLanguage.JA)
            assertEquals("記号", view.face(symbolPageKey(), false))
            assertEquals("記号ページを開く", view.face(symbolPageKey(), true))
            assertEquals("あ/A", view.face(languageKey(), false))
            assertEquals("中国語（タップで英語に切り替え）", view.face(languageKey(), true))
        }
    }

    @Test
    fun onlyAutomaticFacesFollowTheInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            view.faces = KeyboardFaces(language = InputLanguage.JA, symbolMarksAscii = true)
            // An automatic literal shows the branch text; a legacy key without a declared mode keeps
            // the label the layout saved, exactly as LayoutKey.face already decides.
            val automatic =
                LayoutKey(
                    label = "a",
                    action = LayoutAction.literal("a", asciiValue = "A"),
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                )
            val legacy = LayoutKey(label = "a", action = LayoutAction.literal("a", asciiValue = "A"))
            assertEquals("A", view.face(automatic, false))
            assertEquals("a", view.face(legacy, false))
        }
    }

    @Test
    fun aCustomKeycapIsNeverRenamed() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            val custom =
                LayoutKey(
                    label = "space",
                    action = LayoutAction(LayoutActionKind.SPACE),
                    titleMode = LayoutKeyTitleMode.CUSTOM,
                )
            for (language in InputLanguage.entries) {
                view.faces = KeyboardFaces(language = language, composing = true)
                assertEquals("space", view.face(custom, false))
                assertEquals("space", view.face(custom, true))
            }
        }
    }

    private fun generatedFooter(
        page: KeyboardPageKind,
        language: InputLanguage,
    ) = KeyboardPages
        .definition(
            page,
            KeyboardPages.Config(
                layout = KeyboardLayoutKind.QWERTY26,
                englishDirectInput = false,
                symbolMarksAscii = false,
                languageSwitch = true,
                lettersReachable = true,
                everydayMarksPage = false,
                inputLanguage = language,
            ),
        ).rows
        .last()
        .keys

    @Test
    fun theNativePageFootersFollowTheInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            val expected =
                listOf(
                    Triple(InputLanguage.ZH_HANS, "返回", "更多"),
                    Triple(InputLanguage.ZH_HANT, "返回", "更多"),
                    Triple(InputLanguage.JA, "戻る", "その他"),
                )
            for ((language, back, more) in expected) {
                view.faces = KeyboardFaces(language = language, page = KeyboardPageKind.NUMERIC)
                val numericBack =
                    generatedFooter(KeyboardPageKind.NUMERIC, language)
                        .single { it.action.pageSwitchTarget == LayoutPageTarget.LETTERS }
                assertEquals("123 page back, $language", back, view.face(numericBack, false))

                view.faces = KeyboardFaces(language = language, page = KeyboardPageKind.SYMBOLS)
                val symbols = generatedFooter(KeyboardPageKind.SYMBOLS, language)
                val symbolsBack = symbols.single { it.action.pageSwitchTarget == LayoutPageTarget.LETTERS }
                val moreKey =
                    symbols.single {
                        it.action.type == LayoutActionKind.MODE_SWITCH &&
                            it.action.value == KeyboardPages.SYMBOL_PANEL_MODE
                    }
                assertEquals("symbol page back, $language", back, view.face(symbolsBack, false))
                assertEquals("symbol panel key, $language", more, view.face(moreKey, false))
            }
        }
    }

    @Test
    fun anAuthoredFooterTitleSurvivesTheInputLanguage() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            view.faces = KeyboardFaces(language = InputLanguage.JA, page = KeyboardPageKind.SYMBOLS)
            val customBack =
                LayoutKey(
                    label = "back",
                    action = LayoutAction.pageSwitch(LayoutPageTarget.LETTERS),
                    titleMode = LayoutKeyTitleMode.CUSTOM,
                )
            val customMore =
                LayoutKey(
                    label = "more",
                    action = LayoutAction(LayoutActionKind.MODE_SWITCH, KeyboardPages.SYMBOL_PANEL_MODE),
                    titleMode = LayoutKeyTitleMode.CUSTOM,
                )
            // A page saved before title modes existed is authored content too.
            val legacyBack = LayoutKey("落回", action = LayoutAction.pageSwitch(LayoutPageTarget.LETTERS))
            assertEquals("back", view.face(customBack, false))
            assertEquals("more", view.face(customMore, false))
            assertEquals("落回", view.face(legacyBack, false))
        }
    }

    @Test
    fun theNineKeyShellBackKeySharesTheSameFaces() {
        val names = names(Locale.ENGLISH)
        assertEquals("返回", names.returnToLettersDrawn(InputLanguage.ZH_HANS))
        assertEquals("返回", names.returnToLettersDrawn(InputLanguage.ZH_HANT))
        assertEquals("戻る", names.returnToLettersDrawn(InputLanguage.JA))
        assertEquals("ABC", names.returnToLettersDrawn(InputLanguage.JA, english = true))
    }

    @Test
    fun theSymbolAndEmojiPanelsKeepTheirChineseKeyNames() {
        onMain {
            val english = context(Locale.ENGLISH)
            val symbols = SymbolPanelView(english).bottomKeyLabels()
            assertEquals(listOf("返回", "搜索", "空格", "⌫"), symbols)
            assertEquals(listOf("返回", "搜索"), PhraseClipboardPanelView(english).bottomKeyLabels())
            val emoji = EmojiPanelView(english).bottomKeyLabels()
            assertEquals(listOf("返回", "搜索", "⌫"), emoji)
        }
    }
}

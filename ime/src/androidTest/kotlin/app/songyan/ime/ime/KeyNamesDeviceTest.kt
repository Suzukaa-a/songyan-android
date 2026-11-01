package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.panels.EmojiPanelView
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.ime.panels.SymbolPanelView
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The drawn key names are Chinese in every mode and on any phone language, as on iOS; TalkBack follows the phone. */
@RunWith(AndroidJUnit4::class)
class KeyNamesDeviceTest {
    private fun context(locale: Locale): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration().apply { setLocale(locale) })
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun aChineseKeyboardOnAnEnglishPhoneDrawsChineseKeyNames() {
        onMain {
            val view = KeyboardView(context(Locale.ENGLISH))
            val space = LayoutKey(label = "x", action = LayoutAction(LayoutActionKind.SPACE))
            assertEquals("空格", view.face(space, spoken = false))
            assertEquals("Space", view.face(space, spoken = true))
            view.faces = KeyboardFaces(english = true)
            assertEquals("空格", view.face(space, spoken = false))
        }
    }

    @Test
    fun anEnglishKeyboardOnAChinesePhoneStillDrawsChineseKeyNames() {
        onMain {
            val view = KeyboardView(context(Locale.SIMPLIFIED_CHINESE))
            val space = LayoutKey(label = "x", action = LayoutAction(LayoutActionKind.SPACE))
            view.faces = KeyboardFaces(english = true)
            assertEquals("空格", view.face(space, spoken = false))
            assertEquals("空格", view.face(space, spoken = true))
        }
    }

    @Test
    fun theEnterKeyIsDrawnInChineseAndSpokenInThePhonesLanguage() {
        onMain {
            val english = context(Locale.ENGLISH)
            val view = KeyboardView(english)
            val enter = LayoutKey(label = "x", action = LayoutAction(LayoutActionKind.ENTER))
            view.actionLabel = KeyNames(english).chinese().getString(app.songyan.ime.ime.R.string.ime_search)
            assertEquals("搜索", view.face(enter, spoken = false))
            assertEquals("Search", view.face(enter, spoken = true))
        }
    }

    @Test
    fun theSymbolAndEmojiPanelsDrawChineseKeyNamesWithBackOnTheLeftAndDeleteOnTheRight() {
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

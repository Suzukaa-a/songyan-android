package app.songyan.ime.ime

import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** A phone field's pad against the shipped engine: the dialer characters the first page lacks are one tap away. */
@RunWith(AndroidJUnit4::class)
class PhonePadDeviceTest {
    @Test
    fun aPhoneFieldTypesTheDialersPauseWaitAndBracketCharactersFromItsSecondPage() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_PHONE)
            val first = host.labelsOf(KeyboardView::class.java)
            assertFalse("the first page has no bracket: $first", "(" in first)
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.type("(")
            host.type("N")
            host.type(",")
            host.type(";")
            host.type("-")
            host.type(")")
            host.tap(host.string(R.string.ime_space))
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.type("5")
            host.assertText("(N,;-) 5")
            assertTrue("back on the first page", "+" in host.labelsOf(KeyboardView::class.java))
        }

    @Test
    fun aNewPhoneFieldStartsOnTheFirstPage() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_PHONE)
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.main { host.controller.startInput(EditorPolicy.from(host.info), false) }
            assertEquals(false, host.main2 { host.controller.phoneSymbols })
        }

    private fun <T> withHost(block: (FeatureHost) -> T): T {
        val host = FeatureHost(engine.backend())
        try {
            return block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("phonepad", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}

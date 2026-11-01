package app.songyan.ime.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The nine-key quick symbols survive a write and read on the real DataStore; the stored value is restored. */
@RunWith(AndroidJUnit4::class)
class T9QuickSymbolsPreferenceTest {
    @Test
    fun theListRoundTripsAndClearingItReadsAsTheDefault() =
        runBlocking {
            val store = KeyboardPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
            val original = store.t9QuickSymbols.first().ifEmpty { null }
            try {
                store.setT9QuickSymbols("；：")
                assertEquals("；：", store.t9QuickSymbols.first())
                store.setT9QuickSymbols(null)
                assertEquals("", store.t9QuickSymbols.first())
            } finally {
                store.setT9QuickSymbols(original)
            }
        }
}

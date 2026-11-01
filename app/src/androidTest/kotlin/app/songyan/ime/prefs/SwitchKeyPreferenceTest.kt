package app.songyan.ime.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The switch-key choice survives a write and read on the real DataStore; the stored value is restored. */
@RunWith(AndroidJUnit4::class)
class SwitchKeyPreferenceTest {
    @Test
    fun theChoiceRoundTrips() =
        runBlocking {
            val store = KeyboardPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
            val original = store.showSwitchKey.first()
            try {
                store.setShowSwitchKey(true)
                assertEquals(true, store.showSwitchKey.first())
                store.setShowSwitchKey(false)
                assertEquals(false, store.showSwitchKey.first())
            } finally {
                store.setShowSwitchKey(original)
            }
        }
}

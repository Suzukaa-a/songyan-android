package app.songyan.ime.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.UserKeyboards
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The keyboard list and the layout in use survive a write and read on the real DataStore; stored values are restored. */
@RunWith(AndroidJUnit4::class)
class MyKeyboardsPreferenceTest {
    private val store = KeyboardPreferences(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun theListRoundTrips() =
        runBlocking {
            val original = store.myKeyboards.first()
            try {
                val stored =
                    UserKeyboards.Stored(
                        listOf(
                            PublishedKeyboard("rime_ice", KeyboardLayoutKind.QWERTY26, "qwerty26"),
                            PublishedKeyboard("rime_ice", KeyboardLayoutKind.PHONE9, "my:layout#1"),
                        ),
                        setOf("rime_ice"),
                    )
                store.setMyKeyboards(stored)
                assertEquals(stored, store.myKeyboards.first())
            } finally {
                store.setMyKeyboards(original)
            }
        }

    @Test
    fun aBareShapeClearsTheLayoutInUse() =
        runBlocking {
            val schema = store.activeSchemaId.first()
            val shape = store.layoutId.first()
            val definition = store.layoutDefinitionId.first()
            try {
                store.setKeyboard(PublishedKeyboard("rime_ice", KeyboardLayoutKind.QWERTY26, "my-26"))
                assertEquals("my-26", store.layoutDefinitionId.first())
                store.setLayoutId("merged14")
                assertNull(store.layoutDefinitionId.first())
                assertEquals("merged14", store.layoutId.first())
            } finally {
                store.setKeyboard(
                    PublishedKeyboard(
                        schema,
                        KeyboardLayoutKind.entries.firstOrNull { it.wire == shape } ?: KeyboardLayoutKind.QWERTY26,
                        definition,
                    ),
                )
            }
        }
}

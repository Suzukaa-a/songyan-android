package app.songyan.ime.settings.state

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsAppStateTest {
    private val schemes = listOf(InstalledScheme("songyan_quanpin", "全拼"))

    @Test
    fun `in use scheme resolves from the installed list`() {
        val state = SettingsAppState(schemes = schemes, inUseSchemeId = "songyan_quanpin")
        assertEquals("全拼", state.inUseScheme?.name)
    }

    @Test
    fun `unknown in use scheme is not invented`() {
        val state = SettingsAppState(schemes = schemes, inUseSchemeId = "missing")
        assertNull(state.inUseScheme)
    }

    @Test
    fun `current keyboard resolves from the keyboard list`() {
        val keyboard = AppKeyboardInfo("default", "拼音", "26 键")
        val state = SettingsAppState(keyboards = listOf(keyboard), currentKeyboardId = "default")
        assertEquals(keyboard, state.currentKeyboard)
    }

    @Test
    fun `in memory source publishes updates`() =
        runTest {
            val source = InMemorySettingsAppStateSource()
            assertEquals(EngineStatus.Unknown, source.state.value.engine)
            source.update { it.copy(engine = EngineStatus.Ready) }
            assertEquals(EngineStatus.Ready, source.state.value.engine)
        }
}

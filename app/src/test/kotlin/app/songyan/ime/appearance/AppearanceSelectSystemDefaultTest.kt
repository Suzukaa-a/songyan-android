package app.songyan.ime.appearance

import app.songyan.ime.appearance.AppearanceDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class AppearanceSelectSystemDefaultTest {
    @Test
    fun selectingSystemDefaultAppliesIt() {
        val store = AppearanceStore(Files.createTempDirectory("appearance").toString())
        val controller = AppearanceStoreController(store)
        val state = controller.select(AppearanceDocument.SYSTEM_DEFAULT_ID, dynamicColor = false)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, state.document.id)
    }
}

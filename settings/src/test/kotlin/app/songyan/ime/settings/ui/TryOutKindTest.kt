package app.songyan.ime.settings.ui

import androidx.compose.ui.text.input.KeyboardType
import app.songyan.ime.settings.ui.components.TryOutKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TryOutKindTest {
    @Test
    fun eachKindAsksTheKeyboardForItsOwnInputType() {
        assertEquals(
            listOf(
                KeyboardType.Text,
                KeyboardType.Uri,
                KeyboardType.Email,
                KeyboardType.Number,
                KeyboardType.Phone,
                KeyboardType.Password,
            ),
            TryOutKind.entries.map { it.keyboardType },
        )
    }
}

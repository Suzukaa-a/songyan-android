package app.songyan.ime.settings.ui.screens

import androidx.compose.material3.SnackbarDuration
import app.songyan.ime.settings.ui.LibraryEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEventDurationTest {
    @Test
    fun theResultOfAnImportStaysLongEnoughToRead() {
        assertEquals(SnackbarDuration.Long, libraryEventDuration(LibraryEvent.Imported("大千 自制", copied = false)))
        assertEquals(SnackbarDuration.Short, libraryEventDuration(LibraryEvent.Exported))
    }
}

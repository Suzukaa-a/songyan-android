package app.songyan.ime.settings.state

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A tool file another app handed to the settings activity (open or share). The tools page offers
 * it; the screen asks before anything is imported, because the sender is not trusted.
 */
object IncomingToolHolder {
    private val pending = MutableStateFlow<Uri?>(null)

    val uri: StateFlow<Uri?> get() = pending

    fun offer(uri: Uri) {
        pending.value = uri
    }

    fun clear() {
        pending.value = null
    }
}

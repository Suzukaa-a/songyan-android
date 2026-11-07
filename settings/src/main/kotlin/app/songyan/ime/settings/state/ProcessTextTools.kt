package app.songyan.ime.settings.state

import android.graphics.drawable.Drawable

/**
 * One app installed on this device that handles text through [android.content.Intent.ACTION_PROCESS_TEXT],
 * as the keyboard tools page lists it: the icon and the name the system shows for it.
 */
data class ProcessTextApp(
    /** The activity that handles the intent, flattened as package/class. */
    val component: String,
    /** The name the system shows for that activity. */
    val label: String,
    /** The app's icon, or null when the system offers none. */
    val icon: Drawable?,
)

/**
 * What the keyboard tools page needs from the app about the other apps that handle text.
 *
 * The app owns this source because only it sees the manifest's package query and the file the
 * keyboard reads; the settings page only shows the answer and flips one switch. Implementations run
 * their package queries and file IO off the main thread.
 */
interface ProcessTextToolSource {
    /** Every app this app may hand text to, in label order, named the way the system shows it. */
    suspend fun available(): List<ProcessTextApp>

    /** The components the user turned on; none is on by default. */
    suspend fun enabled(): Set<String>

    /** Turns [component] on or off and returns the enabled components afterwards. */
    suspend fun setEnabled(
        component: String,
        enabled: Boolean,
    ): Set<String>
}

/** Process-wide holder of the app-provided source, like [SettingsAppStateHolder]. */
object ProcessTextToolHolder {
    @Volatile
    private var installed: ProcessTextToolSource? = null

    val current: ProcessTextToolSource?
        get() = installed

    fun install(source: ProcessTextToolSource) {
        installed = source
    }
}

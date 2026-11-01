package app.songyan.ime.settings.platform

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Reports changes to the system input-method selection and to the set of enabled input methods.
 *
 * Both are secure settings that can change while this app is in front (the system keyboard
 * switcher, another IME being enabled, an OEM settings shortcut), so re-reading the state only on
 * resume would leave the home banner and the onboarding steps showing a state that no longer
 * exists. Registration lives as long as the composable that owns it, so the observer is not left
 * running while the app is in the background.
 */
class ImeStatusObserver(
    context: Context,
    private val onChange: () -> Unit,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }
        }

    fun start() {
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            observer,
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            false,
            observer,
        )
    }

    fun stop() {
        runCatching { resolver.unregisterContentObserver(observer) }
    }
}

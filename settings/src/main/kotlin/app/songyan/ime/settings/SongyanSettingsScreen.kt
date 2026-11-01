package app.songyan.ime.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.settings.platform.AndroidImeStatusSource
import app.songyan.ime.settings.platform.ImeStatusObserver
import app.songyan.ime.settings.state.ImeId
import app.songyan.ime.settings.ui.NotificationPrompt
import app.songyan.ime.settings.ui.SettingsHost
import app.songyan.ime.settings.ui.SettingsViewModel
import app.songyan.ime.settings.ui.theme.SongyanTheme
import app.songyan.ime.settings.ui.theme.ThemePreferencesHolder

/**
 * Entry point of the settings experience. The host provides its own input-method component, so
 * the settings UI never assumes the component name.
 */
@Composable
fun SongyanSettingsScreen(
    imeComponent: ImeId,
    versionName: String?,
    target: SettingsTarget? = null,
    onTargetHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val imeStatusSource = remember(imeComponent) { AndroidImeStatusSource(context, imeComponent) }
    val viewModel: SettingsViewModel =
        viewModel(
            factory =
                remember(imeStatusSource) {
                    SettingsViewModel.Factory(application, imeStatusSource)
                },
        )
    val dynamicColor by ThemePreferencesHolder.current.dynamicColor.collectAsStateWithLifecycle()

    // Enablement and selection change outside the app. The nav host re-reads them on every resume,
    // and the observer keeps the banner right when the change happens while this screen is open.
    DisposableEffect(context, viewModel) {
        val observer = ImeStatusObserver(context, viewModel::refresh)
        observer.start()
        onDispose { observer.stop() }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SongyanTheme(dynamicColor = dynamicColor) {
        SettingsHost(
            viewModel = viewModel,
            versionName = versionName,
            target = target,
            onTargetHandled = onTargetHandled,
        )
        NotificationPrompt(
            engine = state.engine,
            preferencesLoaded = state.preferencesLoaded,
            alreadyShown = state.notificationRationaleShown,
            onShown = viewModel::markNotificationRationaleShown,
        )
    }
}

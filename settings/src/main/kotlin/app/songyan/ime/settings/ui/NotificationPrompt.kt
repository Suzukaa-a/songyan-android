package app.songyan.ime.settings.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.EngineStatus

/**
 * Whether to explain the notification permission now: only once, only on Android 13+, and only
 * when a deployment is actually running (so the explanation is about something happening), never
 * before the stored "already shown" flag has been read.
 */
fun shouldExplainNotifications(
    engine: EngineStatus,
    preferencesLoaded: Boolean,
    alreadyShown: Boolean,
    sdkInt: Int,
    granted: Boolean,
): Boolean =
    preferencesLoaded &&
        !alreadyShown &&
        sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        !granted &&
        engine is EngineStatus.Preparing

/**
 * Asks for POST_NOTIFICATIONS the first time input schemes are being prepared, with the reason in
 * plain words, instead of at launch before the user has seen anything.
 */
@Composable
fun NotificationPrompt(
    engine: EngineStatus,
    preferencesLoaded: Boolean,
    alreadyShown: Boolean,
    onShown: () -> Unit,
) {
    val context = LocalContext.current
    val granted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (!shouldExplainNotifications(engine, preferencesLoaded, alreadyShown, Build.VERSION.SDK_INT, granted)) return
    AlertDialog(
        onDismissRequest = onShown,
        title = { Text(stringResource(R.string.notification_prompt_title)) },
        text = { Text(stringResource(R.string.notification_prompt_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onShown()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            ) {
                Text(stringResource(R.string.notification_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onShown) { Text(stringResource(R.string.notification_prompt_later)) }
        },
    )
}

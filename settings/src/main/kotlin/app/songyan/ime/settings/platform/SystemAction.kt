package app.songyan.ime.settings.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Outcome of an action that leaves the app for a system screen. */
enum class SystemAction { OPENED, FAILED }

/** Opens the system screen where input methods are enabled. */
fun openInputMethodSettings(context: Context): SystemAction =
    startSafely(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

/** Shows the system input-method picker. */
fun showInputMethodPicker(context: Context): SystemAction {
    val manager = context.getSystemService(InputMethodManager::class.java) ?: return SystemAction.FAILED
    return try {
        manager.showInputMethodPicker()
        SystemAction.OPENED
    } catch (e: RuntimeException) {
        SystemAction.FAILED
    }
}

/** Offers [text] to the system share sheet (mail, messages, notes...). */
fun shareText(
    context: Context,
    subject: String,
    text: String,
    chooserTitle: String,
): SystemAction {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
    return startSafely(context, Intent.createChooser(send, chooserTitle))
}

private fun startSafely(
    context: Context,
    intent: Intent,
): SystemAction {
    val launch = if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
    return try {
        context.startActivity(launch)
        SystemAction.OPENED
    } catch (e: ActivityNotFoundException) {
        SystemAction.FAILED
    } catch (e: SecurityException) {
        SystemAction.FAILED
    }
}

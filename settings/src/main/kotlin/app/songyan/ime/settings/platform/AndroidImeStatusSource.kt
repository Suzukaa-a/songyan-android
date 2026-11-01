package app.songyan.ime.settings.platform

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.songyan.ime.settings.state.ImeId
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.selectedImeMatches

/**
 * Reads enablement from [InputMethodManager.getEnabledInputMethodList] and the current selection
 * from [Settings.Secure.DEFAULT_INPUT_METHOD].
 */
class AndroidImeStatusSource(
    private val context: Context,
    private val target: ImeId,
) : ImeStatusSource {
    override fun read(): ImeStatus =
        try {
            val manager = context.getSystemService(InputMethodManager::class.java)
            if (manager == null) {
                ImeStatus.failed()
            } else {
                val enabled =
                    manager.enabledInputMethodList.any { info ->
                        info.packageName == target.packageName &&
                            info.serviceInfo?.name == target.className
                    }
                val selected =
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD,
                    )
                ImeStatus(enabled = enabled, selected = selectedImeMatches(selected, target))
            }
        } catch (e: RuntimeException) {
            ImeStatus.failed()
        }
}

package app.songyan.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import app.songyan.ime.ime.ImeEntry
import app.songyan.ime.ime.InputMethodAccess
import app.songyan.ime.ime.InputMethodOptions
import app.songyan.ime.ime.VoiceInputMethods

/**
 * The keyboard's way out to other input methods, against the platform and guarded by API level
 * (minSdk 26): the 28+ service calls where they exist, the input method manager before them. Voice
 * input methods are discovered, never assumed: any enabled input method with a `voice` subtype counts.
 */
class SystemInputMethods(
    private val service: InputMethodService,
) : InputMethodAccess {
    private val manager = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun enabled(): List<InputMethodInfo> =
        runCatching { manager.enabledInputMethodList }.getOrDefault(emptyList())

    private fun entry(info: InputMethodInfo) =
        ImeEntry(
            id = info.id,
            packageName = info.packageName,
            label = runCatching { info.loadLabel(service.packageManager).toString() }.getOrDefault(info.id),
            subtypeModes = (0 until info.subtypeCount).map { info.getSubtypeAt(it).mode.orEmpty() },
        )

    override fun options(): InputMethodOptions {
        val list = enabled()
        val others = list.any { it.packageName != service.packageName }
        val offered = service.shouldOfferSwitchingToNextInputMethod()
        val voice = VoiceInputMethods.find(list.map(::entry), service.packageName)
        return InputMethodOptions(canSwitch = others || offered, voiceLabel = voice?.label)
    }

    override fun switchToOther() {
        if (service.switchToPreviousInputMethod()) return
        if (service.shouldOfferSwitchingToNextInputMethod() && service.switchToNextInputMethod(false)) return
        pick()
    }

    override fun pick() = manager.showInputMethodPicker()

    override fun startVoice(): Boolean {
        val list = enabled()
        val target = VoiceInputMethods.find(list.map(::entry), service.packageName) ?: return false
        val info = list.firstOrNull { it.id == target.imeId } ?: return false
        val subtype = info.getSubtypeAt(target.subtypeIndex)
        // A system that refuses the switch (no window yet, the input method vanished) is not a crash.
        return runCatching {
            service.switchInputMethod(info.id, subtype)
        }.isSuccess
    }
}

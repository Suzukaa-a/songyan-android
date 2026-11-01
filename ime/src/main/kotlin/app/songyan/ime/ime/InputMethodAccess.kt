package app.songyan.ime.ime

/** One input method the system lists as enabled, reduced to what the voice search needs. */
data class ImeEntry(
    val id: String,
    val packageName: String,
    val label: String,
    /** The `InputMethodSubtype.getMode()` of each subtype, in the system's order. */
    val subtypeModes: List<String>,
)

/** The input method and subtype (by index) that does voice input. */
data class VoiceTarget(
    val imeId: String,
    val subtypeIndex: Int,
    val label: String,
)

/**
 * Finds a voice input method among the enabled ones. Nothing is assumed about vendors: an input method
 * counts when one of its subtypes declares mode `voice`, the platform's own convention. Ours never
 * counts, and neither does one without a subtype of that mode. When several qualify the system's
 * order decides, which is the order the user sees in the system's own list.
 */
object VoiceInputMethods {
    const val MODE = "voice"

    fun find(
        enabled: List<ImeEntry>,
        ownPackage: String,
    ): VoiceTarget? {
        for (ime in enabled) {
            if (ime.packageName == ownPackage) continue
            val index = ime.subtypeModes.indexOfFirst { it.equals(MODE, ignoreCase = true) }
            if (index >= 0) return VoiceTarget(ime.id, index, ime.label)
        }
        return null
    }
}

/** What the keyboard may offer about other input methods right now. */
data class InputMethodOptions(
    /** The system has another input method to switch to (or at least a picker to open). */
    val canSwitch: Boolean = false,
    /** A voice input method exists; its name is for the label, null when none is enabled. */
    val voiceLabel: String? = null,
)

/**
 * The keyboard's way to other input methods. The service implements it against the platform, guarding
 * every call by API level; the controller only asks and asks it to act.
 */
interface InputMethodAccess {
    /** Re-reads the system's input method list; called when the menu opens or the notice row is shown. */
    fun options(): InputMethodOptions

    /** To the previously used input method, else the next one, else the system's picker. */
    fun switchToOther()

    /** The system's input method picker. */
    fun pick()

    /** Switches to the voice input method; false when none is available or the system refused. */
    fun startVoice(): Boolean

    companion object {
        val NONE =
            object : InputMethodAccess {
                override fun options() = InputMethodOptions()

                override fun switchToOther() = Unit

                override fun pick() = Unit

                override fun startVoice() = false
            }
    }
}

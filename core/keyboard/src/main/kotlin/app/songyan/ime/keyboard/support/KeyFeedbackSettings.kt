package app.songyan.ime.keyboard.support

/**
 * The user's own key feedback choices. A null field means "follow the system", which is the
 * default: nothing is stored until the user touches the control.
 *
 * [soundVolume] and [hapticStrength] are 0..1; out-of-range input is clamped so a corrupted
 * preference can never produce a deafening click or a negative amplitude.
 */
data class KeyFeedbackSettings(
    val soundEnabled: Boolean? = null,
    val soundVolume: Float? = null,
    val hapticsEnabled: Boolean? = null,
    val hapticStrength: Float? = null,
) {
    init {
        require(soundVolume == null || soundVolume in 0f..1f) { "soundVolume out of range: $soundVolume" }
        require(hapticStrength == null || hapticStrength in 0f..1f) { "hapticStrength out of range: $hapticStrength" }
    }

    /** Whether a key press may make a sound, given the system's touch-sounds switch. */
    fun playsSound(systemSoundEnabled: Boolean): Boolean = systemSoundEnabled && (soundEnabled ?: true)

    /** Whether a key press may vibrate, given the system's touch-haptics switch. */
    fun vibrates(systemHapticsEnabled: Boolean): Boolean = hapticsEnabled ?: systemHapticsEnabled

    companion object {
        /** The system's own key-click volume; `AudioManager.playSoundEffect` takes this for "default". */
        const val SYSTEM_VOLUME = -1f

        fun clamp(value: Float): Float = value.coerceIn(0f, 1f)
    }
}

/** Candidate text size choices; the factor multiplies the skin's candidate size. */
enum class CandidateTextSize(
    val id: String,
    val factor: Float,
) {
    SMALL("small", 0.85f),
    STANDARD("standard", 1f),
    LARGE("large", 1.15f),
    EXTRA_LARGE("extra_large", 1.3f),
    ;

    companion object {
        val DEFAULT = STANDARD

        /** An unknown stored id falls back to the default instead of failing the keyboard. */
        fun fromId(id: String?): CandidateTextSize = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

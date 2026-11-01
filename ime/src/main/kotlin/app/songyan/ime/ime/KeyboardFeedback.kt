package app.songyan.ime.ime

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import app.songyan.ime.keyboard.support.KeyFeedbackSettings

/** The kind of key event a tick belongs to; the sound (if any) comes from the key's own action. */
enum class FeedbackKind {
    /** A key went down. */
    PRESS,

    /** One more step of a held key (repeated delete). */
    REPEAT,

    /** A hold opened a popup, a menu or caret mode. */
    HOLD,

    /** One caret step of a space-bar drag. */
    TICK,
}

/** Which platform haptic each key event uses; one place, so the constants are tested, not guessed. */
internal object KeyHaptics {
    /** Key down: the dedicated press tick where the platform has it, otherwise the older tap. */
    fun press(sdk: Int): Int =
        if (sdk >=
            29
        ) {
            HapticFeedbackConstants.KEYBOARD_PRESS
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    /** Key up completes the press/release pair; the platform only knows it from API 27. */
    fun release(sdk: Int): Int? = if (sdk >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else null

    /** Repeated deletes and caret steps use the light clock tick, so held keys do not buzz. */
    const val REPEAT: Int = HapticFeedbackConstants.CLOCK_TICK

    /** A hold that opens a popup, a menu or caret mode. */
    const val HOLD: Int = HapticFeedbackConstants.LONG_PRESS
}

/**
 * Key sound and vibration. Without a user choice both follow the system: haptics through
 * `performHapticFeedback` (which honours the touch-vibration setting) and the key sound through
 * "touch sounds". The system values are read once and kept current by an observer instead of being
 * queried on every key press. A user choice ([settings]) can turn either off, force haptics on,
 * set a sound volume, or set a vibration strength.
 */
class KeyboardFeedback(
    context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val resolver = context.contentResolver
    private val sdk = Build.VERSION.SDK_INT

    /** The release tick is opt-in: some systems already answer a press with two vibrations. */
    @Volatile
    var releaseEnabled = false

    /** The user's key sound and vibration choices; replaced whenever the preference changes. */
    @Volatile
    var settings: KeyFeedbackSettings = KeyFeedbackSettings()

    @Volatile
    private var systemSound = readSoundSetting()

    @Volatile
    private var systemHaptics = readHapticSetting()

    /** Whether a key press makes a sound right now: the user's choice and the system's touch-sounds switch. */
    val soundActive: Boolean get() = settings.playsSound(systemSound)

    /** The system's touch-sounds switch; when it is off no choice of the user's makes a sound. */
    val soundAllowedBySystem: Boolean get() = systemSound

    /** Whether a key press vibrates right now. */
    val hapticsActive: Boolean get() = settings.vibrates(systemHaptics)

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemSound = readSoundSetting()
                systemHaptics = readHapticSetting()
            }
        }

    init {
        for (name in listOf(Settings.System.SOUND_EFFECTS_ENABLED, Settings.System.HAPTIC_FEEDBACK_ENABLED)) {
            resolver.registerContentObserver(Settings.System.getUriFor(name), false, observer)
        }
    }

    private fun readSoundSetting(): Boolean =
        Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0

    private fun readHapticSetting(): Boolean =
        Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    /** Key down. */
    fun press(
        view: View,
        sound: Int = AudioManager.FX_KEYPRESS_STANDARD,
    ) {
        vibrate(view, KeyHaptics.press(sdk))
        playSound(sound)
    }

    /** One step of a held key (repeated delete): the light tick, plus the key sound. */
    fun repeat(
        view: View,
        sound: Int = AudioManager.FX_KEYPRESS_STANDARD,
    ) {
        vibrate(view, KeyHaptics.REPEAT)
        playSound(sound)
    }

    /** A hold that opens something (popup, menu, caret mode). */
    fun hold(view: View) {
        vibrate(view, KeyHaptics.HOLD)
    }

    /** One caret step while dragging on the space bar; silent, haptic only. */
    fun tick(view: View) {
        vibrate(view, KeyHaptics.REPEAT)
    }

    /** Key up: completes the press/release pair so the platform can pick its release tick. */
    fun release(view: View) {
        if (!releaseEnabled) return
        KeyHaptics.release(sdk)?.let { vibrate(view, it) }
    }

    private fun playSound(sound: Int) {
        val current = settings
        if (current.playsSound(systemSound)) {
            audio.playSoundEffect(sound, current.soundVolume ?: KeyFeedbackSettings.SYSTEM_VOLUME)
        }
    }

    /**
     * A user-chosen strength cannot be expressed through haptic constants, so it drives the
     * vibrator directly; without one the platform's own tick is used, and an explicit "on" also
     * ignores the system switch.
     */
    private fun vibrate(
        view: View,
        constant: Int,
    ) {
        val current = settings
        if (!current.vibrates(systemHaptics)) return
        val strength = current.hapticStrength
        if (strength == null || !vibrator.hasVibrator()) {
            val flags = if (current.hapticsEnabled == true) HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING else 0
            view.performHapticFeedback(constant, flags)
            return
        }
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                MIN_PULSE_MS + (strength * PULSE_RANGE_MS).toLong(),
                if (vibrator.hasAmplitudeControl()) {
                    1 + (strength * (MAX_AMPLITUDE - 1)).toInt()
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                },
            ),
        )
    }

    private companion object {
        const val MIN_PULSE_MS = 6L
        const val PULSE_RANGE_MS = 18f
        const val MAX_AMPLITUDE = 255
    }

    /** Stops observing the system setting; the keyboard is going away. */
    fun close() {
        resolver.unregisterContentObserver(observer)
    }
}

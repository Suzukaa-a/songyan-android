package app.songyan.ime.ime.panels

import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.ime.ColorMode
import app.songyan.ime.ime.KeyHeight
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/** The settings the keyboard settings panel offers, in the order of its grid. */
enum class QuickSetting {
    ONE_HAND,
    KEY_HEIGHT,
    LAYOUT,
    APPEARANCE,
    KEY_SOUND,
    KEY_HAPTICS,
    KEY_PREVIEW,
    PHRASES,

    /** A schema switch of the input method in use (traditional output, emoji, ...); one tile per switch. */
    ENGINE_OPTION,
}

/** One change the panel asks the keyboard to apply and store. */
sealed interface QuickSettingChange {
    data class OneHand(
        val mode: KeyboardOneHandMode,
    ) : QuickSettingChange

    data class Height(
        val height: KeyHeight,
    ) : QuickSettingChange

    data class Appearance(
        val mode: ColorMode,
    ) : QuickSettingChange

    data class Sound(
        val on: Boolean,
    ) : QuickSettingChange

    data class Haptics(
        val on: Boolean,
    ) : QuickSettingChange

    data class Preview(
        val on: Boolean,
    ) : QuickSettingChange

    data class EngineOption(
        val name: String,
        val on: Boolean,
    ) : QuickSettingChange
}

/** A schema switch of the input method in use, as the panel shows it. */
data class EngineOptionTile(
    val name: String,
    val kind: EngineOptionKind,
    val on: Boolean,
)

/**
 * What the panel shows: the live value of every setting, read from the keyboard when the panel opens and
 * advanced with [apply] as the user changes things, so the panel never waits for the store to answer.
 */
data class QuickSettingsState(
    val oneHand: KeyboardOneHandMode = KeyboardOneHandMode.OFF,
    /** False where the keyboard never narrows (tablets, landscape, narrow panes): the setting would do nothing. */
    val oneHandAvailable: Boolean = true,
    val keyHeight: KeyHeight = KeyHeight.DEFAULT,
    val layout: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
    val colorMode: ColorMode = ColorMode.SYSTEM,
    /** Whether key presses make a sound right now (the user's choice and the system's switch together). */
    val soundOn: Boolean = true,
    /** The system's touch-sounds switch is off, which no choice here can override. */
    val soundBlockedBySystem: Boolean = false,
    val hapticsOn: Boolean = true,
    val previewOn: Boolean = true,
    /** The switches of the input method in use; empty when it has none. */
    val engineOptions: List<EngineOptionTile> = emptyList(),
) {
    fun apply(change: QuickSettingChange): QuickSettingsState =
        when (change) {
            is QuickSettingChange.OneHand -> copy(oneHand = change.mode)
            is QuickSettingChange.Height -> copy(keyHeight = change.height)
            is QuickSettingChange.Appearance -> copy(colorMode = change.mode)
            is QuickSettingChange.Sound -> copy(soundOn = change.on)
            is QuickSettingChange.Haptics -> copy(hapticsOn = change.on)
            is QuickSettingChange.Preview -> copy(previewOn = change.on)
            is QuickSettingChange.EngineOption ->
                copy(engineOptions = engineOptions.map { if (it.name == change.name) it.copy(on = change.on) else it })
        }
}

/** One choice of a setting that has several values. */
data class QuickOption(
    /** The stored value's wire name. */
    val key: String,
    val selected: Boolean,
    /** What choosing it asks for. */
    val change: QuickSettingChange,
)

/** One tile of the grid. */
data class QuickTile(
    val setting: QuickSetting,
    val kind: Kind,
    /** A toggle's state; false for the other kinds. */
    val on: Boolean = false,
    /** The wire name of the current value of a choice or the layout; null for toggles and plain actions. */
    val valueKey: String? = null,
    val options: List<QuickOption> = emptyList(),
    val enabled: Boolean = true,
    /** The schema switch a [QuickSetting.ENGINE_OPTION] tile flips. */
    val engineOption: EngineOptionTile? = null,
) {
    enum class Kind {
        /** A tap flips it. */
        TOGGLE,

        /** A tap opens the options of the setting. */
        CHOICE,

        /** A tap leaves the panel for another surface. */
        ACTION,
    }
}

/** The grid of the keyboard settings panel as data, so what exists and what it says is testable without a view. */
object QuickSettingsModel {
    fun tiles(state: QuickSettingsState): List<QuickTile> =
        listOf(
            QuickTile(
                setting = QuickSetting.ONE_HAND,
                kind = QuickTile.Kind.CHOICE,
                valueKey = state.oneHand.wire,
                options =
                    KeyboardOneHandMode.entries.map {
                        QuickOption(it.wire, it == state.oneHand, QuickSettingChange.OneHand(it))
                    },
                enabled = state.oneHandAvailable,
            ),
            QuickTile(
                setting = QuickSetting.KEY_HEIGHT,
                kind = QuickTile.Kind.CHOICE,
                valueKey = state.keyHeight.wire,
                options =
                    KeyHeight.entries.map {
                        QuickOption(it.wire, it == state.keyHeight, QuickSettingChange.Height(it))
                    },
            ),
            QuickTile(QuickSetting.LAYOUT, QuickTile.Kind.ACTION, valueKey = state.layout.wire),
            QuickTile(
                setting = QuickSetting.APPEARANCE,
                kind = QuickTile.Kind.CHOICE,
                valueKey = state.colorMode.wire,
                options =
                    ColorMode.entries.map {
                        QuickOption(it.wire, it == state.colorMode, QuickSettingChange.Appearance(it))
                    },
            ),
            QuickTile(
                setting = QuickSetting.KEY_SOUND,
                kind = QuickTile.Kind.TOGGLE,
                on = state.soundOn,
                enabled = !state.soundBlockedBySystem,
            ),
            QuickTile(QuickSetting.KEY_HAPTICS, QuickTile.Kind.TOGGLE, on = state.hapticsOn),
            QuickTile(QuickSetting.KEY_PREVIEW, QuickTile.Kind.TOGGLE, on = state.previewOn),
            QuickTile(QuickSetting.PHRASES, QuickTile.Kind.ACTION),
        ) +
            state.engineOptions.map {
                QuickTile(QuickSetting.ENGINE_OPTION, QuickTile.Kind.TOGGLE, on = it.on, engineOption = it)
            }

    /** What a tap on a toggle asks for; null for tiles that are not toggles or are unavailable. */
    fun toggled(tile: QuickTile): QuickSettingChange? {
        if (!tile.enabled || tile.kind != QuickTile.Kind.TOGGLE) return null
        return when (tile.setting) {
            QuickSetting.KEY_SOUND -> QuickSettingChange.Sound(!tile.on)
            QuickSetting.KEY_HAPTICS -> QuickSettingChange.Haptics(!tile.on)
            QuickSetting.KEY_PREVIEW -> QuickSettingChange.Preview(!tile.on)
            QuickSetting.ENGINE_OPTION -> tile.engineOption?.let { QuickSettingChange.EngineOption(it.name, !tile.on) }
            else -> null
        }
    }
}

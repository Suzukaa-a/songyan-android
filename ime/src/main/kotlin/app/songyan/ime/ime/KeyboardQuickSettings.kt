package app.songyan.ime.ime

import app.songyan.ime.ime.panels.EngineOptionTile
import app.songyan.ime.ime.panels.QuickSettingChange
import app.songyan.ime.ime.panels.QuickSettingsState
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The live values the keyboard settings panel shows, read from the keyboard itself rather than the store. */
internal fun KeyboardController.quickSettingsState(): QuickSettingsState =
    QuickSettingsState(
        oneHand = oneHandMode,
        oneHandAvailable = shell.isAvailable(),
        keyHeight = KeyboardGeometry.keyHeight,
        layout = layoutKind,
        layoutName = layoutName(context, mainDefinition(), layoutKind),
        colorMode = colorMode,
        soundOn = feedback.soundActive,
        soundBlockedBySystem = !feedback.soundAllowedBySystem,
        hapticsOn = feedback.hapticsActive,
        previewOn = keyboard.keyPreviewEnabled,
        engineOptions =
            session
                ?.takeIf { ready }
                ?.options
                .orEmpty()
                .map {
                    EngineOptionTile(
                        it.descriptor.name,
                        it.descriptor.kind,
                        it.value,
                        it.descriptor.choiceGroup,
                        it.descriptor.choiceLabel,
                        it.descriptor.knownScriptState,
                    )
                },
    )

/**
 * Applies [change] to the running keyboard at once and stores it through the same preferences the
 * settings app writes, so both always show the same value. The store's own update arrives later and
 * finds the keyboard already there.
 */
internal fun KeyboardController.applyQuickSetting(change: QuickSettingChange) {
    when (change) {
        is QuickSettingChange.OneHand -> {
            oneHandMode = change.mode
            applyOneHand()
            scope.launch { preferences?.setOneHand(change.mode.wire) }
        }
        is QuickSettingChange.Height -> {
            applyKeyHeight(change.height.wire)
            scope.launch { preferences?.setKeyHeight(change.height.wire) }
        }
        is QuickSettingChange.Appearance -> {
            colorMode = change.mode
            scope.launch { preferences?.setColorMode(change.mode.wire) }
        }
        is QuickSettingChange.Sound -> storeFeedback(feedback.settings.copy(soundEnabled = change.on))
        is QuickSettingChange.Haptics -> storeFeedback(feedback.settings.copy(hapticsEnabled = change.on))
        is QuickSettingChange.EngineOption -> {
            // On the engine queue, like every engine call; the session stores the choice and re-reads the switches.
            enqueue {
                session?.takeIf { ready }?.let { active ->
                    try {
                        active.setOption(change.name, change.on)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        callbacks.error(error)
                    }
                    render()
                }
            }
        }
        is QuickSettingChange.Preview -> {
            keyboard.keyPreviewEnabled = change.on
            scope.launch { preferences?.setKeyPreview(change.on) }
        }
    }
}

private fun KeyboardController.storeFeedback(next: KeyFeedbackSettings) {
    feedback.settings = next
    scope.launch { preferences?.setKeyFeedback(next) }
}

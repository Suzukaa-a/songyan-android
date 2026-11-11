package app.songyan.ime.state

import android.content.Context
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.settings.state.SettingsPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Persisted [SettingsPreferences] shared by the settings screens and the keyboard.
 *
 * Both sides read the same DataStore, so a choice made in settings reaches the keyboard
 * without a second copy of the state.
 */
class AppSettingsPreferences(
    context: Context,
) : SettingsPreferences {
    private val preferences = KeyboardPreferences(context.applicationContext)

    override val activeSchemaId: Flow<String> = preferences.activeSchemaId
    override val layoutId: Flow<String> = preferences.layoutId

    override val keyHeight: Flow<String> = preferences.keyHeight
    override val bottomGapDp: Flow<Int> = preferences.bottomGapDp
    override val longPressDelayMs: Flow<Int?> = preferences.longPressDelayMs
    override val showSwitchKey: Flow<Boolean> = preferences.showSwitchKey
    override val keyPreview: Flow<Boolean> = preferences.keyPreview
    override val keyFlickDirection: Flow<String> = preferences.keyFlickDirection
    override val doubleSpacePeriod: Flow<Boolean> = preferences.doubleSpacePeriod
    override val hideIdleToolbar: Flow<Boolean> = preferences.hideIdleToolbar
    override val toolbarLayout: Flow<String> = preferences.toolbarLayout
    override val releaseHaptic: Flow<Boolean> = preferences.releaseHaptic
    override val oneHand: Flow<String> = preferences.oneHand

    override val widePosition: Flow<String> = preferences.widePosition
    override val widePositionCustom: Flow<Int> = preferences.widePositionCustom
    override val splitMode: Flow<String> = preferences.splitMode
    override val splitWidthDp: Flow<Int> = preferences.splitWidthDp
    override val sideMarginPerMille: Flow<Int> = preferences.sideMarginPerMille

    override val readingPlacement: Flow<String> = preferences.readingPlacement

    override val shuangpinHintDocument: Flow<String?> = preferences.shuangpinHintDocument

    override val t9QuickSymbols: Flow<String> = preferences.t9QuickSymbols

    override suspend fun setT9QuickSymbols(symbols: String?) = preferences.setT9QuickSymbols(symbols)

    override val menuHiddenSchemas: Flow<Set<String>> = preferences.menuHiddenSchemas

    override val menuHiddenLayouts: Flow<Set<String>> = preferences.menuHiddenLayouts

    override suspend fun setMenuHiddenSchemas(ids: Set<String>) = preferences.setMenuHiddenSchemas(ids)

    override suspend fun setMenuHiddenLayouts(ids: Set<String>) = preferences.setMenuHiddenLayouts(ids)

    override suspend fun setShuangpinHintDocument(text: String?) = preferences.setShuangpinHintDocument(text)

    override suspend fun setReadingPlacement(value: String) = preferences.setReadingPlacement(value)

    override suspend fun setWidePosition(value: String) = preferences.setWidePosition(value)

    override suspend fun setWidePositionCustom(percent: Int) = preferences.setWidePositionCustom(percent)

    override suspend fun setSplitMode(value: String) = preferences.setSplitMode(value)

    override suspend fun setSplitWidthDp(value: Int) = preferences.setSplitWidthDp(value)

    override suspend fun setSideMarginPerMille(value: Int) = preferences.setSideMarginPerMille(value)

    override suspend fun setKeyHeight(value: String) = preferences.setKeyHeight(value)

    override suspend fun setBottomGapDp(value: Int) = preferences.setBottomGapDp(value)

    override suspend fun setLongPressDelayMs(value: Int?) = preferences.setLongPressDelayMs(value)

    override suspend fun setShowSwitchKey(value: Boolean) = preferences.setShowSwitchKey(value)

    override suspend fun setKeyPreview(value: Boolean) = preferences.setKeyPreview(value)

    override suspend fun setKeyFlickDirection(value: String) = preferences.setKeyFlickDirection(value)

    override suspend fun setDoubleSpacePeriod(value: Boolean) = preferences.setDoubleSpacePeriod(value)

    override suspend fun setHideIdleToolbar(value: Boolean) = preferences.setHideIdleToolbar(value)

    override suspend fun setToolbarLayout(wire: String?) = preferences.setToolbarLayout(wire)

    override suspend fun setReleaseHaptic(value: Boolean) = preferences.setReleaseHaptic(value)

    override suspend fun setOneHand(value: String) = preferences.setOneHand(value)

    override val candidateTextSize: Flow<String> = preferences.candidateTextSize
    override val keyFeedback: Flow<KeyFeedbackSettings> = preferences.keyFeedback

    override val onboardingShown: Flow<Boolean> = preferences.onboardingShown
    override val notificationRationaleShown: Flow<Boolean> = preferences.notificationRationaleShown

    override suspend fun setOnboardingShown() {
        preferences.setOnboardingShown()
    }

    override suspend fun setNotificationRationaleShown() {
        preferences.setNotificationRationaleShown()
    }

    override suspend fun setCandidateTextSize(value: String) {
        preferences.setCandidateTextSize(value)
    }

    override suspend fun setKeyFeedback(value: KeyFeedbackSettings) {
        preferences.setKeyFeedback(value)
    }

    override suspend fun setActiveSchemaId(value: String) {
        preferences.setActiveSchemaId(value)
    }

    override suspend fun setLayoutId(value: String) {
        preferences.setLayoutId(value)
    }
}

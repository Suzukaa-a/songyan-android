package app.songyan.ime.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.songyan.ime.candidate.KeyboardOneHandLayoutPolicy
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.layout.ShuangpinHintSnapshot
import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.learning.LearningSnapshotInterval
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File

private val Context.keyboardDataStore: DataStore<Preferences> by preferencesDataStore(
    "keyboard",
    corruptionHandler = preferencesCorruptionHandler("keyboard"),
)

/** User preferences the keyboard and settings screen share. */
class KeyboardPreferences(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Built-in layout id the keyboard renders (qwerty26/merged14/merged18/phone9). */
    val layoutId: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_LAYOUT_ID] ?: DEFAULT_LAYOUT_ID }
            .distinctUntilChanged()

    /** Engine schema id the keyboard activates (songyan_quanpin / songyan_flypy / ...). */
    val activeSchemaId: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_ACTIVE_SCHEMA] ?: DEFAULT_SCHEMA_ID }
            .distinctUntilChanged()

    /** [CandidateTextSize.id]; the candidate text grows or shrinks relative to the skin's size. */
    val candidateTextSize: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { CandidateTextSize.fromId(it[KEY_CANDIDATE_TEXT_SIZE]).id }
            .distinctUntilChanged()

    /** Whether the single-row candidate bar shows comments; the expanded bar always does. */
    val collapsedCandidateComments: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_COLLAPSED_CANDIDATE_COMMENTS] ?: true }
            .distinctUntilChanged()

    /** Key sound and vibration choices; a missing key follows the system. */
    val keyFeedback: Flow<KeyFeedbackSettings> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { prefs ->
                KeyFeedbackSettings(
                    soundEnabled = prefs[KEY_SOUND_ENABLED],
                    soundVolume = prefs[KEY_SOUND_VOLUME]?.let(KeyFeedbackSettings::clamp),
                    hapticsEnabled = prefs[KEY_HAPTICS_ENABLED],
                    hapticStrength = prefs[KEY_HAPTIC_STRENGTH]?.let(KeyFeedbackSettings::clamp),
                )
            }.distinctUntilChanged()

    /** Whether the first-launch guide has been shown. */
    val onboardingShown: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_ONBOARDING_SHOWN] ?: false }
            .distinctUntilChanged()

    /** Whether the notification permission explanation has been shown. */
    val notificationRationaleShown: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_NOTIFICATION_RATIONALE_SHOWN] ?: false }
            .distinctUntilChanged()

    /** "off" / "left" / "right"; one-handed mode is a keyboard presentation choice. */
    val oneHand: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_ONE_HAND] ?: ONE_HAND_OFF }
            .distinctUntilChanged()

    /** The one-handed keyboard's width in percent of the panel. */
    val oneHandWidthPercent: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { prefs ->
                KeyboardOneHandLayoutPolicy.clampedWidthPercent(
                    prefs[KEY_ONE_HAND_WIDTH] ?: KeyboardOneHandLayoutPolicy.DEFAULT_WIDTH_PERCENT,
                )
            }.distinctUntilChanged()

    /** Sliding sideways over the keys moves the cursor while nothing is being typed; off by default. */
    val cursorSwipeAnywhere: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_CURSOR_SWIPE_ANYWHERE] ?: false }
            .distinctUntilChanged()

    /** Days between automatic learning copies ([LearningSnapshotInterval]); 0 is off. */
    val learningSnapshotDays: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { LearningSnapshotInterval.resolve(it[KEY_LEARNING_SNAPSHOT_DAYS]) }
            .distinctUntilChanged()

    suspend fun setLearningSnapshotDays(value: Int) {
        appContext.keyboardDataStore.edit { it[KEY_LEARNING_SNAPSHOT_DAYS] = LearningSnapshotInterval.resolve(value) }
    }

    /** Wide boards widen the skin's key gaps with their columns; on by default. */
    val keyGapScaling: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_KEY_GAP_SCALING] ?: true }
            .distinctUntilChanged()

    /** "left" / "center" / "right" / "custom": where the key area sits on a surface wider than 600 dp. */
    val widePosition: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_WIDE_POSITION] ?: DEFAULT_WIDE_POSITION }
            .distinctUntilChanged()

    /** The custom position, percent 0..100; stays stored while another choice is in use. */
    val widePositionCustom: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map {
                SettingsPreferences.clampWidePosition(
                    it[KEY_WIDE_POSITION_CUSTOM] ?: SettingsPreferences.WIDE_POSITION_CENTER,
                )
            }.distinctUntilChanged()

    /** What the keyboard applies: the share of free space before the key area, from the choice and the custom number. */
    val widePositionPercent: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { SettingsPreferences.widePositionPercent(it[KEY_WIDE_POSITION], it[KEY_WIDE_POSITION_CUSTOM]) }
            .distinctUntilChanged()

    /** Engine ids of the input methods the user took out of the keyboard menu. */
    val menuHiddenSchemas: Flow<Set<String>> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_MENU_HIDDEN_SCHEMAS].orEmpty() }
            .distinctUntilChanged()
            // Every collector brings the mirror up to date, including for choices made before it existed.
            .onEach { mirrorMenuHiddenSchemas(it) }
            .flowOn(Dispatchers.IO)

    /** Wire ids of the layouts the user took out of the keyboard menu. */
    val menuHiddenLayouts: Flow<Set<String>> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_MENU_HIDDEN_LAYOUTS].orEmpty() }
            .distinctUntilChanged()

    suspend fun setMenuHiddenSchemas(ids: Set<String>) {
        appContext.keyboardDataStore.edit { it[KEY_MENU_HIDDEN_SCHEMAS] = ids }
        withContext(Dispatchers.IO) { mirrorMenuHiddenSchemas(ids) }
    }

    /** The deploy process cannot read the preferences; see [AppStorage.menuHiddenSchemasFile]. Off the main thread. */
    private fun mirrorMenuHiddenSchemas(ids: Set<String>) {
        runCatching {
            val file = AppStorage(appContext.filesDir).menuHiddenSchemasFile
            val text = ids.sorted().joinToString("\n")
            if (file.isFile && file.readText() == text) return
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.delete()
        }.onFailure { Log.w(TAG, "cannot mirror the hidden input methods", it) }
    }

    suspend fun setMenuHiddenLayouts(ids: Set<String>) {
        appContext.keyboardDataStore.edit { it[KEY_MENU_HIDDEN_LAYOUTS] = ids }
    }

    /** The shuangpin hint settings document (the iOS hints.json format); null until the user changes them. */
    val shuangpinHintDocument: Flow<String?> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_SHUANGPIN_HINTS] }
            .distinctUntilChanged()

    /**
     * The hint per letter the keyboard draws; empty while hints are off. A document that no longer
     * decodes draws nothing: the settings screen offers to reset it.
     */
    val shuangpinHints: Flow<Map<Char, String>> =
        shuangpinHintDocument
            .map { text ->
                text
                    ?.let {
                        runCatching {
                            ShuangpinHintSnapshot.decode(
                                it,
                            )
                        }.getOrNull()
                    }?.activePreset
                    ?.hints
                    .orEmpty()
            }.flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    suspend fun setShuangpinHintDocument(text: String?) {
        appContext.keyboardDataStore.edit {
            if (text == null) it.remove(KEY_SHUANGPIN_HINTS) else it[KEY_SHUANGPIN_HINTS] = text
        }
    }

    /**
     * The user's nine-key quick symbols in order; empty follows the default sequence. A stored value
     * that is no longer a valid list reads as empty, so the keyboard never draws a half-valid list.
     */
    val t9QuickSymbols: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { preferences ->
                preferences[KEY_T9_QUICK_SYMBOLS]
                    ?.takeIf { T9QuickSymbolStore.isValid(it) && it.toSet().size == it.length }
                    .orEmpty()
            }.distinctUntilChanged()

    /** Null returns to the default sequence. */
    suspend fun setT9QuickSymbols(symbols: String?) {
        appContext.keyboardDataStore.edit {
            if (symbols == null) it.remove(KEY_T9_QUICK_SYMBOLS) else it[KEY_T9_QUICK_SYMBOLS] = symbols
        }
    }

    /** "layout" / "on" / "off": whether layouts split into two halves on a wide surface. */
    val splitMode: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_SPLIT_MODE] ?: DEFAULT_SPLIT_MODE }
            .distinctUntilChanged()

    /** Key area width in dp above which the automatic split shows; 400..1200 in steps of 20, 600 by default. */
    val splitWidthDp: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { clampSplitWidth(it[KEY_SPLIT_WIDTH] ?: SPLIT_WIDTH_DEFAULT_DP) }
            .distinctUntilChanged()

    /**
     * Key area width in dp above which the side margin shows, in the split width's range. Until it is
     * set it follows the stored split width, which used to decide both.
     */
    val marginWidthDp: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { clampSplitWidth(it[KEY_MARGIN_WIDTH] ?: it[KEY_SPLIT_WIDTH] ?: SPLIT_WIDTH_DEFAULT_DP) }
            .distinctUntilChanged()

    /** Blank margin on each side of a large surface in per mille of its width; 0..100 in steps of 5, 50 by default. */
    val sideMarginPerMille: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { clampSideMargin(it[KEY_SIDE_MARGIN] ?: SIDE_MARGIN_DEFAULT_PER_MILLE) }
            .distinctUntilChanged()

    /** "row" (own row above the candidates) / "inline" (start of the candidate row): 14 / 18-key readings. */
    val readingPlacement: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_READING_PLACEMENT] ?: DEFAULT_READING_PLACEMENT }
            .distinctUntilChanged()

    suspend fun setReadingPlacement(value: String) {
        require(value in READING_PLACEMENTS) { "unknown reading placement: $value" }
        appContext.keyboardDataStore.edit { it[KEY_READING_PLACEMENT] = value }
    }

    suspend fun setWidePosition(value: String) {
        require(value in WIDE_POSITIONS) { "unknown wide position: $value" }
        appContext.keyboardDataStore.edit { it[KEY_WIDE_POSITION] = value }
    }

    suspend fun setWidePositionCustom(percent: Int) {
        appContext.keyboardDataStore.edit {
            it[KEY_WIDE_POSITION_CUSTOM] = SettingsPreferences.clampWidePosition(percent)
        }
    }

    suspend fun setSplitMode(value: String) {
        require(value in SPLIT_MODES) { "unknown split mode: $value" }
        appContext.keyboardDataStore.edit { it[KEY_SPLIT_MODE] = value }
    }

    /** "compact" / "standard" / "tall": the portrait key row pitch (52 / 56 / 60 dp). */
    val keyHeight: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_HEIGHT] ?: DEFAULT_KEY_HEIGHT }
            .distinctUntilChanged()

    /** Extra empty band below the keys in dp ([BOTTOM_GAP_MAX_DP] at most, in [BOTTOM_GAP_STEP_DP] steps); 0 by default. */
    val bottomGapDp: Flow<Int> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { clampBottomGap(it[KEY_BOTTOM_GAP] ?: 0) }
            .distinctUntilChanged()

    /** Whether the keyboard draws the input-method switch key; off by default, the system's own switcher is used. */
    val showSwitchKey: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_SHOW_SWITCH_KEY] ?: false }
            .distinctUntilChanged()

    /** Key hold delay in ms ([LONG_PRESS_MIN_MS]..[LONG_PRESS_MAX_MS]); null follows the system's touch & hold delay. */
    val longPressDelayMs: Flow<Int?> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { prefs -> prefs[KEY_LONG_PRESS_MS]?.let { clampLongPress(it) } }
            .distinctUntilChanged()

    /** Magnified preview of the pressed key; on by default, the Android keyboard convention. */
    val keyPreview: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_PREVIEW] ?: true }
            .distinctUntilChanged()

    /**
     * Which vertical flick types the hint drawn on a key: `off`, `up` or `down`; up by default. A store
     * that only has the earlier on/off switch turned off reads as `off`, so that choice survives.
     */
    val keyFlickDirection: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { prefs ->
                prefs[KEY_FLICK_DIRECTION]?.takeIf { it in FLICK_DIRECTIONS }
                    ?: if (prefs[KEY_FLICK] == false) "off" else DEFAULT_FLICK_DIRECTION
            }.distinctUntilChanged()

    /** Two quick spaces after an English word type a period and a space; on by default. */
    val doubleSpacePeriod: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_DOUBLE_SPACE_PERIOD] ?: true }
            .distinctUntilChanged()

    /** Whether the idle candidate row hides its toolbar; off by default, the toolbar shows. */
    val hideIdleToolbar: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_HIDE_IDLE_TOOLBAR] ?: false }
            .distinctUntilChanged()

    suspend fun setHideIdleToolbar(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_HIDE_IDLE_TOOLBAR] = value }
    }

    /** The idle toolbar's entries (the wire form of the core toolbar layout); empty until the user changes them. */
    val toolbarLayout: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_TOOLBAR_LAYOUT].orEmpty() }
            .distinctUntilChanged()

    /** Null returns to the default entries. */
    suspend fun setToolbarLayout(wire: String?) {
        appContext.keyboardDataStore.edit {
            if (wire == null) it.remove(KEY_TOOLBAR_LAYOUT) else it[KEY_TOOLBAR_LAYOUT] = wire
        }
    }

    /** A second haptic tick on key release; off by default because some systems answer a press twice. */
    val releaseHaptic: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_RELEASE_HAPTIC] ?: false }
            .distinctUntilChanged()

    /** The symbol page stays open after a symbol is typed. */
    val symbolLock: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_SYMBOL_LOCK] ?: false }
            .distinctUntilChanged()

    suspend fun setSymbolLock(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_SYMBOL_LOCK] = value }
    }

    /**
     * The Japanese twelve-key keys only flick: a repeated tap types the row's next kana instead of
     * cycling the row. Off by default, which keeps the reference keyboard's tap cycle.
     */
    val kana12FlickOnly: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_KANA12_FLICK_ONLY] ?: false }
            .distinctUntilChanged()

    suspend fun setKana12FlickOnly(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_KANA12_FLICK_ONLY] = value }
    }

    suspend fun setKeyHeight(value: String) {
        require(value in KEY_HEIGHTS) { "unknown key height: $value" }
        appContext.keyboardDataStore.edit { it[KEY_HEIGHT] = value }
    }

    suspend fun setSplitWidthDp(value: Int) {
        appContext.keyboardDataStore.edit { it[KEY_SPLIT_WIDTH] = clampSplitWidth(value) }
    }

    suspend fun setMarginWidthDp(value: Int) {
        appContext.keyboardDataStore.edit { it[KEY_MARGIN_WIDTH] = clampSplitWidth(value) }
    }

    suspend fun setSideMarginPerMille(value: Int) {
        appContext.keyboardDataStore.edit { it[KEY_SIDE_MARGIN] = clampSideMargin(value) }
    }

    suspend fun setBottomGapDp(value: Int) {
        appContext.keyboardDataStore.edit { it[KEY_BOTTOM_GAP] = clampBottomGap(value) }
    }

    suspend fun setShowSwitchKey(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_SHOW_SWITCH_KEY] = value }
    }

    /** Null removes the choice so the hold follows the system again. */
    suspend fun setLongPressDelayMs(value: Int?) {
        appContext.keyboardDataStore.edit {
            if (value == null) it.remove(KEY_LONG_PRESS_MS) else it[KEY_LONG_PRESS_MS] = clampLongPress(value)
        }
    }

    suspend fun setKeyPreview(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_PREVIEW] = value }
    }

    suspend fun setCursorSwipeAnywhere(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_CURSOR_SWIPE_ANYWHERE] = value }
    }

    suspend fun setKeyGapScaling(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_KEY_GAP_SCALING] = value }
    }

    suspend fun setOneHandWidthPercent(value: Int) {
        appContext.keyboardDataStore.edit {
            it[KEY_ONE_HAND_WIDTH] = KeyboardOneHandLayoutPolicy.clampedWidthPercent(value)
        }
    }

    suspend fun setKeyFlickDirection(value: String) {
        require(value in FLICK_DIRECTIONS) { "unknown flick direction: $value" }
        appContext.keyboardDataStore.edit {
            it[KEY_FLICK_DIRECTION] = value
            it.remove(KEY_FLICK)
        }
    }

    suspend fun setDoubleSpacePeriod(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_DOUBLE_SPACE_PERIOD] = value }
    }

    suspend fun setReleaseHaptic(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_RELEASE_HAPTIC] = value }
    }

    suspend fun setLayoutId(value: String) {
        appContext.keyboardDataStore.edit { it[KEY_LAYOUT_ID] = value }
    }

    suspend fun setActiveSchemaId(value: String) {
        appContext.keyboardDataStore.edit { it[KEY_ACTIVE_SCHEMA] = value }
        mirrorActiveSchema(value)
    }

    /** The deploy process builds this input method's layouts first; see [AppStorage.keyboardSchemaFile]. */
    private suspend fun mirrorActiveSchema(value: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = AppStorage(appContext.filesDir).keyboardSchemaFile
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(value)
                if (!temp.renameTo(file)) temp.delete()
            }.onFailure { Log.w(TAG, "cannot mirror the input method in use", it) }
        }
    }

    /**
     * Aligns the active schema with a newly active generation: active.json decides, not
     * whoever started the import. Runs once per generation, atomically with the write.
     */
    suspend fun reconcileSchema(
        generation: String,
        namespace: String,
        primarySchemaId: String,
        schemaIds: List<String>,
        sourceId: String = "",
    ) {
        val updated =
            appContext.keyboardDataStore.edit { prefs ->
                if (prefs[KEY_RECONCILED_GENERATION] == generation) return@edit
                val chosen =
                    SchemaReconciliation.choose(
                        current = prefs[KEY_ACTIVE_SCHEMA] ?: DEFAULT_SCHEMA_ID,
                        previousNamespace = prefs[KEY_RECONCILED_NAMESPACE],
                        namespace = namespace,
                        primarySchemaId = primarySchemaId,
                        schemaIds = schemaIds,
                        sourceId = sourceId,
                        request = InputMethodRequest.decode(prefs[KEY_REQUESTED_INPUT_METHOD]),
                    )
                // Honoured or superseded: either way this reconcile settles the request.
                prefs.remove(KEY_REQUESTED_INPUT_METHOD)
                prefs[KEY_ACTIVE_SCHEMA] = chosen
                prefs[KEY_RECONCILED_GENERATION] = generation
                prefs[KEY_RECONCILED_NAMESPACE] = namespace
            }
        updated[KEY_ACTIVE_SCHEMA]?.let { mirrorActiveSchema(it) }
    }

    /**
     * The keyboard menu switched to another package: the next generation of [sourceId] starts on
     * [schemaId] instead of the package's primary input method. Settled by [reconcileSchema].
     */
    suspend fun requestInputMethod(
        sourceId: String,
        schemaId: String,
    ) {
        appContext.keyboardDataStore.edit {
            it[KEY_REQUESTED_INPUT_METHOD] = InputMethodRequest(sourceId, schemaId).encode()
        }
    }

    suspend fun setCandidateTextSize(value: String) {
        appContext.keyboardDataStore.edit { it[KEY_CANDIDATE_TEXT_SIZE] = CandidateTextSize.fromId(value).id }
    }

    suspend fun setCollapsedCandidateComments(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_COLLAPSED_CANDIDATE_COMMENTS] = value }
    }

    suspend fun setOnboardingShown() {
        appContext.keyboardDataStore.edit { it[KEY_ONBOARDING_SHOWN] = true }
    }

    suspend fun setNotificationRationaleShown() {
        appContext.keyboardDataStore.edit { it[KEY_NOTIFICATION_RATIONALE_SHOWN] = true }
    }

    suspend fun setKeyFeedback(value: KeyFeedbackSettings) {
        appContext.keyboardDataStore.edit { prefs ->
            prefs.putOrRemove(KEY_SOUND_ENABLED, value.soundEnabled)
            prefs.putOrRemove(KEY_SOUND_VOLUME, value.soundVolume)
            prefs.putOrRemove(KEY_HAPTICS_ENABLED, value.hapticsEnabled)
            prefs.putOrRemove(KEY_HAPTIC_STRENGTH, value.hapticStrength)
        }
    }

    private fun <T> MutablePreferences.putOrRemove(
        key: Preferences.Key<T>,
        value: T?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    suspend fun setOneHand(value: String) {
        require(value in setOf(ONE_HAND_OFF, ONE_HAND_LEFT, ONE_HAND_RIGHT)) { "unknown one-hand mode: $value" }
        appContext.keyboardDataStore.edit { it[KEY_ONE_HAND] = value }
    }

    companion object {
        private const val TAG = "KeyboardPreferences"
        const val DEFAULT_LAYOUT_ID = "qwerty26"
        const val DEFAULT_SCHEMA_ID = "songyan_quanpin"
        const val ONE_HAND_OFF = "off"
        const val ONE_HAND_LEFT = "left"
        const val ONE_HAND_RIGHT = "right"
        const val DEFAULT_KEY_HEIGHT = "standard"
        const val BOTTOM_GAP_MAX_DP = 48
        const val BOTTOM_GAP_STEP_DP = 4
        const val SPLIT_WIDTH_MIN_DP = 400
        const val SPLIT_WIDTH_MAX_DP = 1200
        const val SPLIT_WIDTH_STEP_DP = 20
        const val SPLIT_WIDTH_DEFAULT_DP = 600
        const val SIDE_MARGIN_MAX_PER_MILLE = 100
        const val SIDE_MARGIN_STEP_PER_MILLE = 5
        const val SIDE_MARGIN_DEFAULT_PER_MILLE = 50
        const val LONG_PRESS_MIN_MS = 150
        const val LONG_PRESS_MAX_MS = 1000
        const val LONG_PRESS_STEP_MS = 50

        /** Snaps to the range and the step, so a hand-edited store cannot draw an odd band. */
        fun clampBottomGap(value: Int): Int =
            value.coerceIn(0, BOTTOM_GAP_MAX_DP) / BOTTOM_GAP_STEP_DP * BOTTOM_GAP_STEP_DP

        fun clampSplitWidth(value: Int): Int =
            (value.coerceIn(SPLIT_WIDTH_MIN_DP, SPLIT_WIDTH_MAX_DP) - SPLIT_WIDTH_MIN_DP) / SPLIT_WIDTH_STEP_DP *
                SPLIT_WIDTH_STEP_DP + SPLIT_WIDTH_MIN_DP

        fun clampSideMargin(value: Int): Int =
            value.coerceIn(0, SIDE_MARGIN_MAX_PER_MILLE) / SIDE_MARGIN_STEP_PER_MILLE * SIDE_MARGIN_STEP_PER_MILLE

        fun clampLongPress(value: Int): Int =
            (value.coerceIn(LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS) - LONG_PRESS_MIN_MS) / LONG_PRESS_STEP_MS *
                LONG_PRESS_STEP_MS +
                LONG_PRESS_MIN_MS

        const val DEFAULT_WIDE_POSITION = "center"
        const val DEFAULT_SPLIT_MODE = "layout"
        const val DEFAULT_READING_PLACEMENT = "row"
        private val READING_PLACEMENTS = setOf("row", "inline")
        private val SPLIT_MODES = setOf("layout", "on", "off")
        private val WIDE_POSITIONS = setOf("left", "center", "right", "custom")
        private val KEY_HEIGHTS = setOf("compact", "standard", "tall")
        const val DEFAULT_FLICK_DIRECTION = "up"
        private val FLICK_DIRECTIONS = setOf("off", "up", "down")

        private val KEY_WIDE_POSITION = stringPreferencesKey("wide_position")
        private val KEY_WIDE_POSITION_CUSTOM = intPreferencesKey("wide_position_custom")
        private val KEY_SPLIT_MODE = stringPreferencesKey("split_mode")
        private val KEY_SHUANGPIN_HINTS = stringPreferencesKey("shuangpin_hints")
        private val KEY_TOOLBAR_LAYOUT = stringPreferencesKey("toolbar_layout")
        private val KEY_T9_QUICK_SYMBOLS = stringPreferencesKey("t9_quick_symbols")
        private val KEY_MENU_HIDDEN_SCHEMAS = stringSetPreferencesKey("menu_hidden_schemas")
        private val KEY_MENU_HIDDEN_LAYOUTS = stringSetPreferencesKey("menu_hidden_layouts")
        private val KEY_READING_PLACEMENT = stringPreferencesKey("reading_placement")
        private val KEY_LAYOUT_ID = stringPreferencesKey("layout_id")
        private val KEY_SYMBOL_LOCK = booleanPreferencesKey("symbol_lock")
        private val KEY_KANA12_FLICK_ONLY = booleanPreferencesKey("kana12_flick_only")
        private val KEY_HEIGHT = stringPreferencesKey("key_height")
        private val KEY_BOTTOM_GAP = intPreferencesKey("bottom_gap_dp")
        private val KEY_SPLIT_WIDTH = intPreferencesKey("split_width_dp")
        private val KEY_MARGIN_WIDTH = intPreferencesKey("margin_width_dp")
        private val KEY_SIDE_MARGIN = intPreferencesKey("side_margin_per_mille")
        private val KEY_SHOW_SWITCH_KEY = booleanPreferencesKey("show_switch_key")
        private val KEY_LONG_PRESS_MS = intPreferencesKey("long_press_delay_ms")

        /** The earlier on/off flick switch: read so an "off" carries over, removed on the next write. */
        private val KEY_FLICK = booleanPreferencesKey("key_flick")
        private val KEY_FLICK_DIRECTION = stringPreferencesKey("key_flick_direction")
        private val KEY_DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        private val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        private val KEY_HIDE_IDLE_TOOLBAR = booleanPreferencesKey("hide_idle_toolbar")
        private val KEY_RELEASE_HAPTIC = booleanPreferencesKey("release_haptic")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        private val KEY_NOTIFICATION_RATIONALE_SHOWN = booleanPreferencesKey("notification_rationale_shown")
        private val KEY_CANDIDATE_TEXT_SIZE = stringPreferencesKey("candidate_text_size")
        private val KEY_COLLAPSED_CANDIDATE_COMMENTS = booleanPreferencesKey("collapsed_candidate_comments")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("key_sound_enabled")
        private val KEY_SOUND_VOLUME = floatPreferencesKey("key_sound_volume")
        private val KEY_HAPTICS_ENABLED = booleanPreferencesKey("key_haptics_enabled")
        private val KEY_HAPTIC_STRENGTH = floatPreferencesKey("key_haptic_strength")
        private val KEY_ONE_HAND = stringPreferencesKey("one_hand")
        private val KEY_ONE_HAND_WIDTH = intPreferencesKey("one_hand_width_percent")
        private val KEY_CURSOR_SWIPE_ANYWHERE = booleanPreferencesKey("cursor_swipe_anywhere")
        private val KEY_KEY_GAP_SCALING = booleanPreferencesKey("key_gap_scaling")
        private val KEY_LEARNING_SNAPSHOT_DAYS = intPreferencesKey("learning_snapshot_days")
        private val KEY_ACTIVE_SCHEMA = stringPreferencesKey("active_schema_id")
        private val KEY_RECONCILED_GENERATION = stringPreferencesKey("reconciled_generation")
        private val KEY_RECONCILED_NAMESPACE = stringPreferencesKey("reconciled_namespace")
        private val KEY_REQUESTED_INPUT_METHOD = stringPreferencesKey("requested_input_method")
    }
}

/** An input method of a specific package that the user picked from the keyboard menu. */
data class InputMethodRequest(
    val sourceId: String,
    val schemaId: String,
) {
    fun encode(): String = "$sourceId\t$schemaId"

    companion object {
        fun decode(value: String?): InputMethodRequest? {
            val parts = value?.split('\t') ?: return null
            return if (parts.size == 2 &&
                parts.all { it.isNotEmpty() }
            ) {
                InputMethodRequest(parts[0], parts[1])
            } else {
                null
            }
        }
    }
}

/** Which input method to use after the active generation changed. Pure for unit tests. */
object SchemaReconciliation {
    fun choose(
        current: String,
        previousNamespace: String?,
        namespace: String,
        primarySchemaId: String,
        schemaIds: List<String>,
        sourceId: String = "",
        request: InputMethodRequest? = null,
    ): String {
        if (request != null && request.sourceId == sourceId && request.schemaId in schemaIds) return request.schemaId
        // Switching to another scheme package (or back to the built-in one) starts on its primary
        // input method; within the same family the user's choice stays while it still exists.
        if (previousNamespace != null && previousNamespace != namespace) return primarySchemaId
        val offered = schemaIds.ifEmpty { listOf(primarySchemaId) }
        return if (current in offered) current else primarySchemaId
    }
}

/** [KeyboardPreferences.reconcileSchema] for one publication record. */
suspend fun KeyboardPreferences.reconcileWith(record: app.songyan.ime.storage.PublicationRecord) =
    reconcileSchema(
        generation = record.generation,
        namespace =
            app.songyan.ime.storage.LearningData
                .namespace(record.sourceId),
        primarySchemaId = record.schemaId,
        schemaIds = record.schemaIds,
        sourceId = record.sourceId,
    )

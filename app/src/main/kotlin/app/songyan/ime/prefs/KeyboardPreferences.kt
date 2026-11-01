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
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.layout.ShuangpinHintSnapshot
import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    /** "left" / "center" / "right": where the key area sits on a surface wider than 600 dp. */
    val widePosition: Flow<String> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_WIDE_POSITION] ?: DEFAULT_WIDE_POSITION }
            .distinctUntilChanged()

    /** Engine ids of the input methods the user took out of the keyboard menu. */
    val menuHiddenSchemas: Flow<Set<String>> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_MENU_HIDDEN_SCHEMAS].orEmpty() }
            .distinctUntilChanged()

    /** Wire ids of the layouts the user took out of the keyboard menu. */
    val menuHiddenLayouts: Flow<Set<String>> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_MENU_HIDDEN_LAYOUTS].orEmpty() }
            .distinctUntilChanged()

    suspend fun setMenuHiddenSchemas(ids: Set<String>) {
        appContext.keyboardDataStore.edit { it[KEY_MENU_HIDDEN_SCHEMAS] = ids }
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

    /** Vertical flicks on character keys type the key's first and second long-press option; on by default. */
    val keyFlick: Flow<Boolean> =
        appContext.keyboardDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_FLICK] ?: true }
            .distinctUntilChanged()

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

    suspend fun setKeyHeight(value: String) {
        require(value in KEY_HEIGHTS) { "unknown key height: $value" }
        appContext.keyboardDataStore.edit { it[KEY_HEIGHT] = value }
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

    suspend fun setKeyFlick(value: Boolean) {
        appContext.keyboardDataStore.edit { it[KEY_FLICK] = value }
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
                    )
                prefs[KEY_ACTIVE_SCHEMA] = chosen
                prefs[KEY_RECONCILED_GENERATION] = generation
                prefs[KEY_RECONCILED_NAMESPACE] = namespace
            }
        updated[KEY_ACTIVE_SCHEMA]?.let { mirrorActiveSchema(it) }
    }

    suspend fun setCandidateTextSize(value: String) {
        appContext.keyboardDataStore.edit { it[KEY_CANDIDATE_TEXT_SIZE] = CandidateTextSize.fromId(value).id }
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
        const val LONG_PRESS_MIN_MS = 150
        const val LONG_PRESS_MAX_MS = 1000
        const val LONG_PRESS_STEP_MS = 50

        /** Snaps to the range and the step, so a hand-edited store cannot draw an odd band. */
        fun clampBottomGap(value: Int): Int =
            value.coerceIn(0, BOTTOM_GAP_MAX_DP) / BOTTOM_GAP_STEP_DP * BOTTOM_GAP_STEP_DP

        fun clampLongPress(value: Int): Int =
            (value.coerceIn(LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS) - LONG_PRESS_MIN_MS) / LONG_PRESS_STEP_MS *
                LONG_PRESS_STEP_MS +
                LONG_PRESS_MIN_MS

        const val DEFAULT_WIDE_POSITION = "center"
        const val DEFAULT_SPLIT_MODE = "layout"
        const val DEFAULT_READING_PLACEMENT = "row"
        private val READING_PLACEMENTS = setOf("row", "inline")
        private val WIDE_POSITIONS = setOf("left", "center", "right")
        private val SPLIT_MODES = setOf("layout", "on", "off")
        private val KEY_HEIGHTS = setOf("compact", "standard", "tall")

        private val KEY_WIDE_POSITION = stringPreferencesKey("wide_position")
        private val KEY_SPLIT_MODE = stringPreferencesKey("split_mode")
        private val KEY_SHUANGPIN_HINTS = stringPreferencesKey("shuangpin_hints")
        private val KEY_T9_QUICK_SYMBOLS = stringPreferencesKey("t9_quick_symbols")
        private val KEY_MENU_HIDDEN_SCHEMAS = stringSetPreferencesKey("menu_hidden_schemas")
        private val KEY_MENU_HIDDEN_LAYOUTS = stringSetPreferencesKey("menu_hidden_layouts")
        private val KEY_READING_PLACEMENT = stringPreferencesKey("reading_placement")
        private val KEY_LAYOUT_ID = stringPreferencesKey("layout_id")
        private val KEY_SYMBOL_LOCK = booleanPreferencesKey("symbol_lock")
        private val KEY_HEIGHT = stringPreferencesKey("key_height")
        private val KEY_BOTTOM_GAP = intPreferencesKey("bottom_gap_dp")
        private val KEY_SHOW_SWITCH_KEY = booleanPreferencesKey("show_switch_key")
        private val KEY_LONG_PRESS_MS = intPreferencesKey("long_press_delay_ms")
        private val KEY_FLICK = booleanPreferencesKey("key_flick")
        private val KEY_DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        private val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        private val KEY_HIDE_IDLE_TOOLBAR = booleanPreferencesKey("hide_idle_toolbar")
        private val KEY_RELEASE_HAPTIC = booleanPreferencesKey("release_haptic")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        private val KEY_NOTIFICATION_RATIONALE_SHOWN = booleanPreferencesKey("notification_rationale_shown")
        private val KEY_CANDIDATE_TEXT_SIZE = stringPreferencesKey("candidate_text_size")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("key_sound_enabled")
        private val KEY_SOUND_VOLUME = floatPreferencesKey("key_sound_volume")
        private val KEY_HAPTICS_ENABLED = booleanPreferencesKey("key_haptics_enabled")
        private val KEY_HAPTIC_STRENGTH = floatPreferencesKey("key_haptic_strength")
        private val KEY_ONE_HAND = stringPreferencesKey("one_hand")
        private val KEY_ACTIVE_SCHEMA = stringPreferencesKey("active_schema_id")
        private val KEY_RECONCILED_GENERATION = stringPreferencesKey("reconciled_generation")
        private val KEY_RECONCILED_NAMESPACE = stringPreferencesKey("reconciled_namespace")
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
    ): String {
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
    )

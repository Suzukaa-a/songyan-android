package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User choices the keyboard and the settings screens share. The app module implements this on
 * top of its persisted preferences; settings only reads flows and writes through the setters.
 */
interface SettingsPreferences {
    /** Engine schema the keyboard activates. */
    val activeSchemaId: Flow<String>

    /** Built-in keyboard layout id: qwerty26, merged14, merged18 or phone9. */
    val layoutId: Flow<String>

    /** Portrait key row pitch: compact (52 dp), standard (56 dp) or tall (60 dp). */
    val keyHeight: Flow<String>

    /** Extra empty band below the keys in dp, 0..[BOTTOM_GAP_MAX_DP] in [BOTTOM_GAP_STEP_DP] steps. */
    val bottomGapDp: Flow<Int>

    /** Key hold delay in ms, [LONG_PRESS_MIN_MS]..[LONG_PRESS_MAX_MS]; null follows the system. */
    val longPressDelayMs: Flow<Int?>

    /** Whether the keyboard draws the input-method switch key; off unless the user wants it. */
    val showSwitchKey: Flow<Boolean>

    /** Vertical flicks on character keys type their first and second long-press option (on by default). */
    val keyFlick: Flow<Boolean>

    suspend fun setKeyFlick(value: Boolean)

    /** Two quick spaces after an English word type a period and a space (on by default). */
    val doubleSpacePeriod: Flow<Boolean>

    suspend fun setDoubleSpacePeriod(value: Boolean)

    /** Magnified preview above a pressed key. */
    val keyPreview: Flow<Boolean>

    /** Whether the idle candidate row hides its toolbar (off by default). */
    val hideIdleToolbar: Flow<Boolean>

    suspend fun setHideIdleToolbar(value: Boolean)

    /** Haptic tick when a key is released (off by default). */
    val releaseHaptic: Flow<Boolean>

    /** One-handed mode: off, left or right. */
    val oneHand: Flow<String>

    /** Where the key area sits on a wide surface: left, center, right or custom (the number in [widePositionCustom]). */
    val widePosition: Flow<String>

    /** The custom position, percent 0..100 of the free space before the key area; kept while another choice is in use. */
    val widePositionCustom: Flow<Int>

    /** Whether layouts split into two halves on a wide surface: layout (each layout decides), on or off. */
    val splitMode: Flow<String>

    /** Where 14 / 18-key readings show while composing: row (own row above the candidates) or inline. */
    val readingPlacement: Flow<String>

    suspend fun setReadingPlacement(value: String)

    /** The shuangpin hint settings document (the iOS hints.json format); null until the user changes them. */
    val shuangpinHintDocument: Flow<String?>

    /** Null returns to the default: hints off, the built-in preset only. */
    suspend fun setShuangpinHintDocument(text: String?)

    /** The nine-key quick symbols in order; empty follows the default sequence. */
    val t9QuickSymbols: Flow<String>

    /** Null returns to the default sequence. */
    suspend fun setT9QuickSymbols(symbols: String?)

    /** Engine ids of the input methods the keyboard menu leaves out; a new one shows until hidden. */
    val menuHiddenSchemas: Flow<Set<String>>

    /** Layout ids ([KeyboardLayoutOption.id]) the keyboard menu leaves out. */
    val menuHiddenLayouts: Flow<Set<String>>

    suspend fun setMenuHiddenSchemas(ids: Set<String>)

    suspend fun setMenuHiddenLayouts(ids: Set<String>)

    suspend fun setWidePosition(value: String)

    suspend fun setWidePositionCustom(percent: Int)

    suspend fun setSplitMode(value: String)

    suspend fun setKeyHeight(value: String)

    suspend fun setBottomGapDp(value: Int)

    /** Null returns to following the system. */
    suspend fun setLongPressDelayMs(value: Int?)

    suspend fun setShowSwitchKey(value: Boolean)

    suspend fun setKeyPreview(value: Boolean)

    suspend fun setReleaseHaptic(value: Boolean)

    suspend fun setOneHand(value: String)

    /** Candidate text size choice; [CandidateTextSize.id]. */
    val candidateTextSize: Flow<String>

    /** The user's key sound / vibration choices; unset fields follow the system. */
    val keyFeedback: Flow<KeyFeedbackSettings>

    /** Whether the first-launch guide has already been shown. */
    val onboardingShown: Flow<Boolean>

    /** Whether the notification permission explanation has already been shown. */
    val notificationRationaleShown: Flow<Boolean>

    suspend fun setActiveSchemaId(value: String)

    suspend fun setLayoutId(value: String)

    suspend fun setCandidateTextSize(value: String)

    suspend fun setOnboardingShown()

    suspend fun setNotificationRationaleShown()

    /** Replaces the stored feedback choices; null fields are removed so they follow the system again. */
    suspend fun setKeyFeedback(value: KeyFeedbackSettings)

    companion object {
        const val DEFAULT_SCHEMA_ID = "songyan_quanpin"
        const val DEFAULT_LAYOUT_ID = "qwerty26"

        val KEY_HEIGHTS = listOf("compact", "standard", "tall")
        const val DEFAULT_KEY_HEIGHT = "standard"

        const val BOTTOM_GAP_MAX_DP = 48
        const val BOTTOM_GAP_STEP_DP = 4
        const val LONG_PRESS_MIN_MS = 150
        const val LONG_PRESS_MAX_MS = 1000
        const val LONG_PRESS_STEP_MS = 50

        val ONE_HAND_MODES = listOf("off", "left", "right")
        val WIDE_POSITIONS = listOf("left", "center", "right", "custom")
        const val WIDE_POSITION_MIN = 0
        const val WIDE_POSITION_MAX = 100
        const val WIDE_POSITION_CENTER = 50

        /** Snaps a typed or stored number to the 0..100 range. */
        fun clampWidePosition(percent: Int): Int = percent.coerceIn(WIDE_POSITION_MIN, WIDE_POSITION_MAX)

        /** The share of free space before the key area for a stored choice; unknown or missing means centred. */
        fun widePositionPercent(
            choice: String?,
            custom: Int?,
        ): Int =
            when (choice) {
                "left" -> WIDE_POSITION_MIN
                "right" -> WIDE_POSITION_MAX
                "custom" -> clampWidePosition(custom ?: WIDE_POSITION_CENTER)
                else -> WIDE_POSITION_CENTER
            }

        val SPLIT_MODES = listOf("layout", "on", "off")
        val READING_PLACEMENTS = listOf("row", "inline")
        const val DEFAULT_READING_PLACEMENT = "row"
    }
}

/** Fallback used before the app module installs its implementation; keeps the UI functional. */
class InMemorySettingsPreferences(
    schemaId: String = SettingsPreferences.DEFAULT_SCHEMA_ID,
    layoutId: String = SettingsPreferences.DEFAULT_LAYOUT_ID,
) : SettingsPreferences {
    private val schema = MutableStateFlow(schemaId)
    private val layout = MutableStateFlow(layoutId)
    private val textSize = MutableStateFlow(CandidateTextSize.DEFAULT.id)
    private val feedback = MutableStateFlow(KeyFeedbackSettings())
    private val onboarding = MutableStateFlow(false)
    private val rationale = MutableStateFlow(false)

    override val activeSchemaId: Flow<String> = schema.asStateFlow()
    override val layoutId: Flow<String> = layout.asStateFlow()
    private val height = MutableStateFlow(SettingsPreferences.DEFAULT_KEY_HEIGHT)
    private val preview = MutableStateFlow(true)
    private val flick = MutableStateFlow(true)
    private val doubleSpace = MutableStateFlow(true)
    private val hideToolbar = MutableStateFlow(false)
    private val release = MutableStateFlow(false)
    private val bottomGap = MutableStateFlow(0)
    private val longPress = MutableStateFlow<Int?>(null)
    private val switchKey = MutableStateFlow(false)
    private val hand = MutableStateFlow("off")

    private val wide = MutableStateFlow("center")
    private val wideCustom = MutableStateFlow(SettingsPreferences.WIDE_POSITION_CENTER)
    private val split = MutableStateFlow("layout")
    private val placement = MutableStateFlow(SettingsPreferences.DEFAULT_READING_PLACEMENT)

    override val readingPlacement: Flow<String> = placement.asStateFlow()

    private val hintDocument = MutableStateFlow<String?>(null)

    override val shuangpinHintDocument: Flow<String?> = hintDocument.asStateFlow()

    override suspend fun setShuangpinHintDocument(text: String?) {
        hintDocument.value = text
    }

    private val quickSymbols = MutableStateFlow("")

    override val t9QuickSymbols: Flow<String> = quickSymbols.asStateFlow()

    override suspend fun setT9QuickSymbols(symbols: String?) {
        quickSymbols.value = symbols.orEmpty()
    }

    private val hiddenSchemas = MutableStateFlow<Set<String>>(emptySet())
    private val hiddenLayouts = MutableStateFlow<Set<String>>(emptySet())

    override val menuHiddenSchemas: Flow<Set<String>> = hiddenSchemas.asStateFlow()
    override val menuHiddenLayouts: Flow<Set<String>> = hiddenLayouts.asStateFlow()

    override suspend fun setMenuHiddenSchemas(ids: Set<String>) {
        hiddenSchemas.value = ids
    }

    override suspend fun setMenuHiddenLayouts(ids: Set<String>) {
        hiddenLayouts.value = ids
    }

    override suspend fun setReadingPlacement(value: String) {
        require(value in SettingsPreferences.READING_PLACEMENTS) { "unknown reading placement: $value" }
        placement.value = value
    }

    override val widePosition: Flow<String> = wide.asStateFlow()
    override val widePositionCustom: Flow<Int> = wideCustom.asStateFlow()
    override val splitMode: Flow<String> = split.asStateFlow()

    override suspend fun setWidePositionCustom(percent: Int) {
        wideCustom.value = SettingsPreferences.clampWidePosition(percent)
    }

    override suspend fun setWidePosition(value: String) {
        require(value in SettingsPreferences.WIDE_POSITIONS) { "unknown wide position: $value" }
        wide.value = value
    }

    override suspend fun setSplitMode(value: String) {
        require(value in SettingsPreferences.SPLIT_MODES) { "unknown split mode: $value" }
        split.value = value
    }

    override val keyHeight: Flow<String> = height.asStateFlow()
    override val bottomGapDp: Flow<Int> = bottomGap.asStateFlow()
    override val longPressDelayMs: Flow<Int?> = longPress.asStateFlow()
    override val showSwitchKey: Flow<Boolean> = switchKey.asStateFlow()

    override suspend fun setShowSwitchKey(value: Boolean) {
        switchKey.value = value
    }

    override val keyPreview: Flow<Boolean> = preview.asStateFlow()
    override val keyFlick: Flow<Boolean> = flick.asStateFlow()

    override suspend fun setKeyFlick(value: Boolean) {
        flick.value = value
    }

    override val doubleSpacePeriod: Flow<Boolean> = doubleSpace.asStateFlow()

    override suspend fun setDoubleSpacePeriod(value: Boolean) {
        doubleSpace.value = value
    }

    override val hideIdleToolbar: Flow<Boolean> = hideToolbar.asStateFlow()

    override suspend fun setHideIdleToolbar(value: Boolean) {
        hideToolbar.value = value
    }

    override val releaseHaptic: Flow<Boolean> = release.asStateFlow()
    override val oneHand: Flow<String> = hand.asStateFlow()

    override suspend fun setKeyHeight(value: String) {
        require(value in SettingsPreferences.KEY_HEIGHTS) { "unknown key height: $value" }
        height.value = value
    }

    override suspend fun setBottomGapDp(value: Int) {
        require(
            value in 0..SettingsPreferences.BOTTOM_GAP_MAX_DP && value % SettingsPreferences.BOTTOM_GAP_STEP_DP == 0,
        ) {
            "bad bottom gap: $value"
        }
        bottomGap.value = value
    }

    override suspend fun setLongPressDelayMs(value: Int?) {
        require(
            value == null || (value in SettingsPreferences.LONG_PRESS_MIN_MS..SettingsPreferences.LONG_PRESS_MAX_MS),
        ) {
            "bad long-press delay: $value"
        }
        longPress.value = value
    }

    override suspend fun setKeyPreview(value: Boolean) {
        preview.value = value
    }

    override suspend fun setReleaseHaptic(value: Boolean) {
        release.value = value
    }

    override suspend fun setOneHand(value: String) {
        require(value in SettingsPreferences.ONE_HAND_MODES) { "unknown one-hand mode: $value" }
        hand.value = value
    }

    override val candidateTextSize: Flow<String> = textSize.asStateFlow()
    override val keyFeedback: Flow<KeyFeedbackSettings> = feedback.asStateFlow()
    override val onboardingShown: Flow<Boolean> = onboarding.asStateFlow()
    override val notificationRationaleShown: Flow<Boolean> = rationale.asStateFlow()

    override suspend fun setActiveSchemaId(value: String) {
        schema.value = value
    }

    override suspend fun setLayoutId(value: String) {
        layout.value = value
    }

    override suspend fun setCandidateTextSize(value: String) {
        textSize.value = CandidateTextSize.fromId(value).id
    }

    override suspend fun setKeyFeedback(value: KeyFeedbackSettings) {
        feedback.value = value
    }

    override suspend fun setOnboardingShown() {
        onboarding.value = true
    }

    override suspend fun setNotificationRationaleShown() {
        rationale.value = true
    }
}

/** Process-wide holder; the app module installs its DataStore-backed implementation once. */
object SettingsPreferencesHolder {
    private val fallback = InMemorySettingsPreferences()

    @Volatile
    private var installed: SettingsPreferences? = null

    val current: SettingsPreferences
        get() = installed ?: fallback

    fun install(preferences: SettingsPreferences) {
        installed = preferences
    }
}

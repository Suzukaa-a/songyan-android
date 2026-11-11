package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Preferences the keyboard reads and writes. The app owns the storage (DataStore); the keyboard only
 * needs the two selection values, so it never links against app code.
 */
interface KeyboardPreferencesSource {
    val layoutId: Flow<String>
    val activeSchemaId: Flow<String>

    /** [KeyHeight.wire]: the portrait key row pitch. */
    val keyHeight: Flow<String> get() = flowOf(KeyHeight.DEFAULT.wire)

    /** Extra empty band below the keys in dp, [KeyboardGeometry.clampBottomGapDp] steps. */
    val bottomGapDp: Flow<Int> get() = flowOf(0)

    /** Whether the keyboard draws the input-method switch key; off unless the user wants it. */
    val showSwitchKey: Flow<Boolean> get() = flowOf(false)

    /** Key hold delay in ms ([HoldDelay] range); null follows the system's touch & hold delay. */
    val longPressDelayMs: Flow<Int?> get() = flowOf(null)

    /** Which vertical flick types the hint drawn on a key ([FlickDirection.wire]). */
    val keyFlickDirection: Flow<String> get() = flowOf(FlickDirection.UP.wire)

    /** Two quick spaces after an English word type a period and a space. */
    val doubleSpacePeriod: Flow<Boolean> get() = flowOf(true)

    /** Magnified preview of the pressed key. */
    val keyPreview: Flow<Boolean> get() = flowOf(true)

    /** Whether the idle candidate row hides its toolbar; the menu then carries its entries. */
    val hideIdleToolbar: Flow<Boolean> get() = flowOf(false)

    /** [ToolbarLayout.toWire]: the idle toolbar's entries in order; empty is the default. */
    val toolbarLayout: Flow<String> get() = flowOf("")

    /** A second haptic tick when a key is released. */
    val releaseHaptic: Flow<Boolean> get() = flowOf(false)

    /** [KeyboardOneHandMode.wire]: off, left or right. */
    val oneHand: Flow<String> get() = flowOf(KeyboardOneHandMode.OFF.wire)

    /** [CandidateTextSize.id]; the standard size when the owner stores no choice. */
    val candidateTextSize: Flow<String> get() = flowOf(CandidateTextSize.DEFAULT.id)

    /** Whether the single-row candidate bar shows comments; on unless the owner says otherwise. */
    val collapsedCandidateComments: Flow<Boolean> get() = flowOf(true)

    /** The user's key sound and vibration choices; unset fields follow the system. */
    val keyFeedback: Flow<KeyFeedbackSettings> get() = flowOf(KeyFeedbackSettings())

    /** Percent 0..100 of the free space before the key area on a surface wider than 600 dp; 50 is centred. */
    val widePosition: Flow<Int> get() = flowOf(KeyboardContentDock.CENTER.percent)

    /** [SplitMode.wire]: whether layouts split into two halves on a wide surface. */
    val splitMode: Flow<String> get() = flowOf(SplitMode.LAYOUT.wire)

    /** Key area width in dp above which the automatic split shows, [KeyboardGeometry.clampSplitWidthDp] steps. */
    val splitWidthDp: Flow<Int> get() = flowOf(KeyboardGeometry.SPLIT_WIDTH_DEFAULT_DP)

    /** Blank margin on each side of a large surface in per mille of its width, [KeyboardGeometry.clampSideMarginPerMille] steps. */
    val sideMarginPerMille: Flow<Int> get() = flowOf(KeyboardGeometry.SIDE_MARGIN_DEFAULT_PER_MILLE)

    /** [ReadingPlacement.wire]: where 14 / 18-key readings show while composing. */
    val readingPlacement: Flow<String> get() = flowOf(ReadingPlacement.ROW.wire)

    /** Engine ids of the input methods the user hid from the keyboard menu. */
    val menuHiddenSchemas: Flow<Set<String>> get() = flowOf(emptySet())

    /** [KeyboardLayoutKind.wire] of the layouts the user hid from the keyboard menu. */
    val menuHiddenLayouts: Flow<Set<String>> get() = flowOf(emptySet())

    /** The shuangpin hint for each letter, empty while the user has hints off. */
    val shuangpinHints: Flow<Map<Char, String>> get() = flowOf(emptyMap())

    /** The user's nine-key quick symbols in order; empty follows the default sequence. */
    val t9QuickSymbols: Flow<String> get() = flowOf("")

    /** [ColorMode.wire]: follow the system or force the light or dark keyboard. */
    val colorMode: Flow<String> get() = flowOf(ColorMode.SYSTEM.wire)

    suspend fun setLayoutId(id: String)

    suspend fun setActiveSchemaId(id: String)

    /**
     * Saves [boardId] as the main layout of the pairing for [schemaId] on the identity (26-key) shape,
     * off the main thread; true when it was stored. The owner publishes the stored catalog to
     * [KeyboardLayoutCatalog], which is what redraws the keyboard.
     */
    suspend fun setMainLayout(
        schemaId: String,
        families: LayoutFamilyContext,
        boardId: String,
    ): Boolean = false

    suspend fun setOneHand(value: String) = Unit

    suspend fun setKeyHeight(value: String) = Unit

    suspend fun setKeyPreview(value: Boolean) = Unit

    suspend fun setKeyFeedback(value: KeyFeedbackSettings) = Unit

    suspend fun setColorMode(value: String) = Unit

    /** Keep the symbol page open after a symbol is typed; persisted across sessions. */
    val symbolLock: Flow<Boolean> get() = flowOf(false)

    suspend fun setSymbolLock(value: Boolean) = Unit

    /**
     * The Japanese twelve-key keys only flick: a repeated tap types the row's next kana instead of
     * cycling the row. Off by default, which keeps the reference keyboard's tap cycle.
     */
    val kana12FlickOnly: Flow<Boolean> get() = flowOf(false)

    suspend fun setKana12FlickOnly(value: Boolean) = Unit
}

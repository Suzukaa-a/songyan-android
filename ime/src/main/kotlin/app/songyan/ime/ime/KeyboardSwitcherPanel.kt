package app.songyan.ime.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput

/**
 * The in-keyboard "输入方式 × 键盘布局" menu, opened by a long press on the language key.
 *
 * It is plain Views on purpose: the key area must never pay for a Compose host on a cold keyboard
 * start, and this panel is a short list of switches where a Compose tree buys nothing.
 *
 * The chrome is the same one the quick-content panels use: a compact header with the title and a
 * close button, so dismissing the menu is one idiom everywhere in the keyboard. The two selection
 * groups are chips, because a chip states its own selection; the actions are rows that end in a
 * chevron, because a chip there would read as another toggle.
 */
internal class KeyboardSwitcherPanel(
    context: Context,
    private val defaultSchemaId: String,
    private val defaultLayout: KeyboardLayoutKind,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = KeyboardPalette.from(context)
    private val titles = mutableListOf<TextView>()
    private var headerTitle: TextView? = null
    private var headerClose: ImageView? = null

    /** Labels, chevrons, dividers and row containers of the action section, restyled on a theme change. */
    private val actionLabels = mutableListOf<TextView>()
    private val actionChevrons = mutableListOf<ImageView>()
    private val actionRows = mutableListOf<LinearLayout>()
    private val actionDividers = mutableListOf<View>()
    private val schemaChips = linkedMapOf<String, TextView>()
    private val schemaNames = mutableMapOf<String, String>()
    private lateinit var schemaRow: LinearLayout
    private val layoutChips = mutableListOf<Pair<KeyboardMenuEntries.LayoutChip, TextView>>()
    private var layoutRow: LinearLayout
    private var offeredLayouts: List<KeyboardLayoutKind> = KeyboardLayoutKind.entries
    private var layoutBoards: List<String> = emptyList()
    private var mainLayoutId: String? = null
    private var currentSchema = defaultSchemaId
    private var currentLayout = defaultLayout

    /** The name each chip shows; a kind without one keeps the shape's own name. */
    private var layoutNames: Map<KeyboardLayoutKind, String> = emptyMap()

    var onSchema: (String) -> Unit = {}
    var onLayout: (KeyboardLayoutKind) -> Unit = {}

    /** A physical board of the running input method was picked; the keyboard moves to its shape. */
    var onBoard: (String) -> Unit = {}
    var onQuickContent: () -> Unit = {}
    var onKeyboardSettings: () -> Unit = {}
    var onEmoji: () -> Unit = {}
    var onEdit: () -> Unit = {}
    var onOneHand: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onSwitchInputMethod: () -> Unit = {}
    var onPickInputMethod: () -> Unit = {}
    var onVoiceInput: () -> Unit = {}
    internal val scroll: ScrollView
    private var switchRows: List<View> = emptyList()
    private var voiceRows: List<View> = emptyList()
    private var shortcutRows: List<View> = emptyList()
    private var oneHandRows: List<View> = emptyList()
    private var oneHandLabel: TextView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        setPadding(padding(16), 0, padding(16), padding(8))
        addView(header())
        // The menu has the fixed height of the key surface. With 48 dp touch targets and a large
        // system font its content is taller than that, so everything under the header scrolls. The
        // scroll bar stays on and the bottom edge fades out, so the rows below the fold (settings,
        // other input methods, voice) are visibly there instead of hidden behind a plain cut.
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        scroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(padding(FADE_EDGE_DP))
                overScrollMode = OVER_SCROLL_NEVER
                addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(title(context.getString(R.string.ime_menu_input_method)))
        val schemaScroll = chipRow(emptyList())
        schemaRow = schemaScroll.getChildAt(0) as LinearLayout
        body.addView(schemaScroll)
        setSchemas(BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS.map { it to "" })
        body.addView(title(context.getString(R.string.ime_menu_layout)))
        val layoutScroll = chipRow(emptyList())
        layoutRow = layoutScroll.getChildAt(0) as LinearLayout
        body.addView(layoutScroll)
        rebuildLayoutChips()
        // The action rows state their own role with a chevron; a third group title would push the
        // menu past the surface height, which is fixed so opening it never resizes the window.
        body.addView(actionRow(context.getString(R.string.ime_menu_quick_content)) { onQuickContent() })
        // With the idle toolbar hidden this menu is the way to everything the toolbar offered.
        val emojiRow = actionRow(context.getString(R.string.ime_toolbar_emoji)) { onEmoji() }
        val editRow = actionRow(context.getString(R.string.ime_toolbar_edit)) { onEdit() }
        val oneHandRow = actionRow(context.getString(R.string.ime_qs_one_hand), chevron = false) { onOneHand() }
        oneHandLabel = oneHandRow.getChildAt(0) as TextView
        body.addView(emojiRow)
        body.addView(editRow)
        body.addView(oneHandRow)
        shortcutRows = listOf(emojiRow, editRow)
        oneHandRows = listOf(oneHandRow)
        setToolbarShortcuts(shown = false, oneHandAvailable = false, oneHand = KeyboardOneHandMode.OFF)
        body.addView(divider())
        body.addView(actionRow(context.getString(R.string.ime_menu_keyboard_settings)) { onKeyboardSettings() })
        // Other input methods and voice: the same place every Android keyboard keeps its way out of
        // itself. The rows only exist while the system has something to switch to.
        val switchRow = actionRow(context.getString(R.string.ime_menu_switch_previous)) { onSwitchInputMethod() }
        val pickRow = actionRow(context.getString(R.string.ime_menu_pick_input_method)) { onPickInputMethod() }
        val voiceRow = actionRow(context.getString(R.string.ime_menu_voice_input)) { onVoiceInput() }
        val switchDivider = divider()
        val voiceDivider = divider()
        body.addView(switchDivider)
        body.addView(switchRow)
        body.addView(pickRow)
        body.addView(voiceDivider)
        body.addView(voiceRow)
        switchRows = listOf(switchDivider, switchRow, pickRow)
        voiceRows = listOf(voiceDivider, voiceRow)
        setInputMethodOptions(InputMethodOptions())
        update(defaultSchemaId, defaultLayout)
    }

    /** Mirrors the running keyboard so the selected chips are always the live ones. */
    fun update(
        schemaId: String,
        layout: KeyboardLayoutKind,
    ) {
        currentSchema = schemaId
        currentLayout = layout
        for ((id, view) in schemaChips) {
            style(view, id == schemaId)
            view.contentDescription = chipDescription(schemaLabel(id), id == schemaId)
        }
        for ((entry, view) in layoutChips) {
            val selected = isSelected(entry)
            style(view, selected)
            view.contentDescription = chipDescription(layoutLabel(entry), selected)
        }
    }

    /** A board chip is on only while the keyboard is on the identity shape drawing that board. */
    private fun isSelected(entry: KeyboardMenuEntries.LayoutChip): Boolean =
        entry.kind == currentLayout && (entry.boardId == null || entry.boardId == mainLayoutId)

    fun selectLayout(kind: KeyboardLayoutKind) = update(currentSchema, kind)

    /**
     * The input methods the menu offers, in order, as (id, engine name); the chips are rebuilt only
     * when the list changes. A bundled one keeps its translated name.
     */
    fun setSchemas(entries: List<Pair<String, String>>) {
        if (entries.map { it.first } == schemaChips.keys.toList() &&
            entries.all { (id, name) -> schemaNames[id] == name }
        ) {
            return
        }
        schemaChips.clear()
        schemaNames.clear()
        schemaRow.removeAllViews()
        for ((id, name) in entries) {
            schemaNames[id] = name
            val view = chip(schemaLabel(id)) { onSchema(id) }
            schemaChips[id] = view
            schemaRow.addView(view)
        }
        update(currentSchema, currentLayout)
    }

    /** The input methods the menu offers, in order. */
    internal val schemaIds: List<String> get() = schemaChips.keys.toList()

    /**
     * The names of the layouts the menu offers, resolved for the running input method: a keyboard
     * that draws a physical board names that board instead of the shape it shares.
     */
    fun setLayoutNames(names: Map<KeyboardLayoutKind, String>) {
        layoutNames = names
        for ((entry, view) in layoutChips) {
            view.text = layoutLabel(entry)
            view.contentDescription = chipDescription(layoutLabel(entry), isSelected(entry))
        }
    }

    /**
     * The layouts the menu offers, in shape order. When the input method types on several physical
     * [boards] (they share the identity shape's slot) each gets its own chip, selected by the
     * [mainLayoutId] the keyboard draws; the chips are rebuilt only when that list changes.
     */
    fun setLayouts(
        kinds: Collection<KeyboardLayoutKind>,
        boards: List<String> = emptyList(),
        mainLayoutId: String? = null,
    ) {
        val offered = KeyboardLayoutKind.entries.filter { it in kinds }
        this.mainLayoutId = mainLayoutId
        if (offered != offeredLayouts || boards != layoutBoards) {
            offeredLayouts = offered
            layoutBoards = boards
            rebuildLayoutChips()
        }
        update(currentSchema, currentLayout)
    }

    private fun rebuildLayoutChips() {
        layoutChips.clear()
        layoutRow.removeAllViews()
        for (entry in KeyboardMenuEntries.layoutChips(offeredLayouts, layoutBoards, mainLayoutId)) {
            val boardId = entry.boardId
            val view = chip(layoutLabel(entry)) { if (boardId != null) onBoard(boardId) else onLayout(entry.kind) }
            layoutChips += entry to view
            layoutRow.addView(view)
        }
        update(currentSchema, currentLayout)
    }

    /** Shows the input method rows the system can serve: switching, and voice when a voice input method exists. */
    fun setInputMethodOptions(options: InputMethodOptions) {
        for (row in switchRows) row.visibility = if (options.canSwitch) VISIBLE else GONE
        for (row in voiceRows) row.visibility = if (options.voiceLabel != null) VISIBLE else GONE
    }

    /**
     * Adds the toolbar's own entries when the idle toolbar is hidden: emoji, edit and one-handed mode
     * (the last only where the window allows it, labelled with its state).
     */
    fun setToolbarShortcuts(
        shown: Boolean,
        oneHandAvailable: Boolean,
        oneHand: KeyboardOneHandMode,
    ) {
        for (row in shortcutRows) row.visibility = if (shown) VISIBLE else GONE
        for (row in oneHandRows) row.visibility = if (shown && oneHandAvailable) VISIBLE else GONE
        val value =
            when (oneHand) {
                KeyboardOneHandMode.LEFT -> R.string.ime_qs_left
                KeyboardOneHandMode.RIGHT -> R.string.ime_qs_right
                KeyboardOneHandMode.OFF -> R.string.ime_qs_off
            }
        oneHandLabel?.text =
            context.getString(
                R.string.ime_qs_label_value,
                context.getString(R.string.ime_qs_one_hand),
                context.getString(value),
            )
    }

    /** Re-reads the palette after a day/night change and restyles every part of the panel. */
    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        setBackgroundColor(palette.background)
        for (title in titles) title.setTextColor(palette.muted)
        headerTitle?.setTextColor(palette.text)
        for (label in actionLabels) label.setTextColor(palette.text)
        for (chevron in actionChevrons) chevron.imageTintList = ColorStateList.valueOf(palette.muted)
        headerClose?.imageTintList = ColorStateList.valueOf(palette.muted)
        for (row in actionRows) row.background = pressedBackground()
        headerClose?.background = pressedBackground()
        for (divider in actionDividers) divider.setBackgroundColor(palette.divider)
        update(currentSchema, currentLayout)
    }

    private fun style(
        view: TextView,
        selected: Boolean,
    ) {
        view.isSelected = selected
        val radius = 10 * density
        view.background =
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (selected) palette.accent else palette.control)
                if (!selected) setStroke(padding(1), palette.controlBorder)
            }
        view.setTextColor(if (selected) palette.accentText else palette.text)
    }

    private fun chip(
        text: String,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = padding(MIN_TOUCH_TARGET_DP)
            setPadding(padding(14), padding(9), padding(14), padding(9))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            layoutParams =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = padding(8)
                }
        }

    /**
     * The compact header: the menu's title and one close button, matching the quick-content panels.
     * The whole header is the title band, so the menu opens at the top of the keyboard window rather
     * than under a blank strip.
     */
    private fun header(): LinearLayout {
        val bar =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, padding(48)).apply {
                        bottomMargin = padding(4)
                    }
            }
        bar.addView(
            TextView(context).apply {
                text = context.getString(R.string.ime_menu_title)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.text)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                headerTitle = this
            },
        )
        bar.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = ColorStateList.valueOf(palette.muted)
                background = pressedBackground()
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.ime_menu_close)
                headerClose = this
                layoutParams = LayoutParams(padding(48), LayoutParams.MATCH_PARENT)
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onClose()
                }
            },
        )
        return bar
    }

    /**
     * One action entry: a label, an optional trailing chevron and a press state. The row spans the
     * panel width, so its touch target is not limited to the text.
     */
    private fun actionRow(
        text: String,
        chevron: Boolean = true,
        onClick: () -> Unit,
    ): LinearLayout {
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = padding(MIN_TOUCH_TARGET_DP)
                setPadding(padding(12), padding(8), padding(12), padding(8))
                background = pressedBackground()
                layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = padding(2)
                    }
            }
        row.addView(
            TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(palette.text)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                actionLabels += this
            },
        )
        if (chevron) {
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(palette.muted)
                    actionChevrons += this
                },
            )
        }
        actionRows += row
        row.setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        return row
    }

    private fun divider(): View =
        View(context).apply {
            setBackgroundColor(palette.divider)
            layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, padding(1) / 2)).apply {
                    marginStart = padding(12)
                    marginEnd = padding(12)
                }
            actionDividers += this
        }

    /** Pressed state only: the row itself has no idle container of its own. */
    private fun pressedBackground(): StateListDrawable {
        val radius = 10 * density
        val shape =
            GradientDrawable().apply {
                cornerRadius = radius
                setColor(palette.pressed)
            }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape)
        }
    }

    /** The chips in one row that scrolls sideways, so a large system font never clips the last one. */
    private fun chipRow(chips: List<TextView>): HorizontalScrollView =
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    chips.forEach { addView(it) }
                },
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

    private fun title(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(padding(2), padding(6), padding(2), 0)
            titles += this
        }

    private fun chipDescription(
        label: String,
        selected: Boolean,
    ): String =
        if (selected) {
            context.getString(R.string.ime_item_selected, label)
        } else {
            label
        }

    private fun schemaLabel(id: String): String =
        when (id) {
            BuiltinTemplateSchemas.QUANPIN -> context.getString(R.string.ime_schema_quanpin)
            BuiltinTemplateSchemas.FLYPY -> context.getString(R.string.ime_schema_flypy)
            else -> schemaNames[id]?.takeIf { it.isNotBlank() } ?: BuiltinTemplateSchemas.NAMES[id] ?: id
        }

    private fun layoutLabel(kind: KeyboardLayoutKind): String = layoutNames[kind] ?: layoutName(context, kind)

    private fun layoutLabel(entry: KeyboardMenuEntries.LayoutChip): String =
        entry.boardId?.let(::bundledLayoutNameRes)?.let(context::getString) ?: layoutLabel(entry.kind)

    private fun padding(dp: Int): Int = (dp * density).toInt()

    private companion object {
        const val MIN_TOUCH_TARGET_DP = 48
        const val FADE_EDGE_DP = 28
    }

    /** Kept for the controller's back handling: the panel never owns state the keyboard does not. */
    val isOpen: Boolean get() = visibility == View.VISIBLE
}

/** The user-visible name of a keyboard layout, as the keyboard menu lists it. */
internal fun layoutName(
    context: Context,
    kind: KeyboardLayoutKind,
): String =
    context.getString(
        when (kind) {
            KeyboardLayoutKind.QWERTY26 -> R.string.ime_layout_qwerty26
            KeyboardLayoutKind.MERGED14 -> R.string.ime_layout_merged14
            KeyboardLayoutKind.MERGED18 -> R.string.ime_layout_merged18
            KeyboardLayoutKind.PHONE9 -> R.string.ime_layout_phone9
        },
    )

/** The resource that words a bundled physical board by id; null keeps the shape's own name. */
internal fun bundledLayoutNameRes(layoutId: String?): Int? =
    when (layoutId) {
        InputLayoutPolicy.ZHUYIN_LAYOUT -> R.string.ime_layout_zhuyin41
        InputLayoutPolicy.CANGJIE_LAYOUT -> R.string.ime_layout_cangjie26
        InputLayoutPolicy.ROMAJI_LAYOUT -> R.string.ime_layout_romaji26
        InputLayoutPolicy.KANA_LAYOUT -> R.string.ime_layout_kana12
        else -> null
    }

/**
 * The name a keyboard drawing [definition] calls its layout: a bundled board whose name was never
 * changed is worded by resources, since its stored name is the shipped data's Chinese; a renamed
 * board or the user's own layout keeps the name it was saved with.
 */
internal fun layoutName(
    context: Context,
    definition: LayoutDefinition?,
    kind: KeyboardLayoutKind,
): String {
    if (definition != null) {
        if (definition.name != BuiltinLayouts.byId(definition.id)?.name) return definition.name
        bundledLayoutNameRes(definition.id)?.let { return context.getString(it) }
    }
    return layoutName(context, kind)
}

/**
 * The name each layout chip shows for the running input method. The keyboard reads the same catalog,
 * so a chip names the board the keyboard behind it would draw, not just the shape it is paired with.
 */
internal fun KeyboardController.layoutNamesByKind(): Map<KeyboardLayoutKind, String> {
    val registry = KeyboardLayoutCatalog.snapshot.value.registry
    val families = layoutFamilies()
    return KeyboardLayoutKind.entries.associateWith { kind ->
        val definition =
            LayoutInput.Builtin
                .fromWire(kind.wire)
                ?.let { shape ->
                    registry.resolvedLayout(
                        registry.composition(KeyboardCompositionKey(schemaId, shape), families).mainLayoutID,
                    )
                }
        layoutName(context, definition, kind)
    }
}

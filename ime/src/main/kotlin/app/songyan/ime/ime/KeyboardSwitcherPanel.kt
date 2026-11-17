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
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutDefinition

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
    private var otherChips: Map<String, OtherInputMethod> = emptyMap()
    private lateinit var schemaRow: LinearLayout
    private val keyboardChips = mutableListOf<Pair<KeyboardChip, TextView>>()
    private var layoutRow: LinearLayout
    private var currentSchema = defaultSchemaId
    private var currentKeyboardId = PublishedKeyboard(defaultSchemaId, defaultLayout, defaultLayout.wire).id

    /** One keyboard of the running input method, with the name of the layout it draws. */
    data class KeyboardChip(
        val keyboard: PublishedKeyboard,
        val label: String,
    )

    var onSchema: (String) -> Unit = {}

    /** A keyboard of the running input method was picked. */
    var onKeyboard: (PublishedKeyboard) -> Unit = {}
    var onQuickContent: () -> Unit = {}
    var onKeyboardSettings: () -> Unit = {}
    var onEmoji: () -> Unit = {}
    var onEdit: () -> Unit = {}
    var onOneHand: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onSwitchInputMethod: () -> Unit = {}
    var onPickInputMethod: () -> Unit = {}
    var onVoiceInput: () -> Unit = {}

    /** [packageName] of an extension whose own panel the user picked. */
    var onExtensionPanel: (String) -> Unit = {}
    internal val scroll: ScrollView
    private var switchRows: List<View> = emptyList()
    private var voiceRows: List<View> = emptyList()
    private var shortcutRows: List<View> = emptyList()
    private var oneHandRows: List<View> = emptyList()
    private var oneHandLabel: TextView? = null

    /** Extensions that offer their own panel; the section only exists while there are any. */
    private var extensionSection: LinearLayout? = null
    private var extensionDivider: View? = null
    private var extensionTitle: TextView? = null
    private var extensionEntries: List<Pair<String, String>> = emptyList()

    /** True while the section stands for a list that has not arrived yet (a placeholder row). */
    private var extensionLoading = false
    private var extensionPlaceholder: TextView? = null

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
        setKeyboards(
            KeyboardLayoutKind.entries.map {
                KeyboardChip(PublishedKeyboard(defaultSchemaId, it, it.wire), layoutName(context, it))
            },
        )
        // Extensions with their own panel sit right under the chip groups: the menu's height is the
        // fixed key surface, and the rows below (quick content, settings, input methods) are long
        // enough that anything put under them needs a scroll to be seen at all.
        val extensionsDivider = divider()
        val extensionsTitle = title(context.getString(R.string.ime_menu_extensions))
        val extensionsSection = LinearLayout(context).apply { orientation = VERTICAL }
        extensionsDivider.visibility = View.GONE
        extensionsTitle.visibility = View.GONE
        extensionsSection.visibility = View.GONE
        body.addView(extensionsDivider)
        body.addView(extensionsTitle)
        body.addView(extensionsSection)
        extensionDivider = extensionsDivider
        extensionTitle = extensionsTitle
        extensionSection = extensionsSection
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
        update(defaultSchemaId, currentKeyboardId)
    }

    /** Mirrors the running keyboard so the selected chips are always the live ones. */
    fun update(
        schemaId: String,
        keyboardId: String,
    ) {
        currentSchema = schemaId
        currentKeyboardId = keyboardId
        for ((id, view) in schemaChips) {
            style(view, id == schemaId)
            view.contentDescription =
                otherChips[id]?.let { context.getString(R.string.ime_menu_switch_package, it.packageName, it.name) }
                    ?: chipDescription(schemaLabel(id), id == schemaId)
        }
        for ((entry, view) in keyboardChips) {
            val selected = entry.keyboard.id == keyboardId
            style(view, selected)
            view.contentDescription = chipDescription(entry.label, selected)
        }
    }

    /** Lights [menuId] at once while the input method it names gets ready; [update] settles it. */
    fun markPicked(menuId: String) {
        for ((id, view) in schemaChips) style(view, id == menuId)
    }

    /**
     * Opening the menu shows the selected chips. A row keeps the offset of the last visit, which
     * could leave another input method where the selected one was and put it under the finger.
     */
    fun revealSelection() {
        reveal(schemaRow, schemaChips[currentSchema])
        reveal(layoutRow, keyboardChip(currentKeyboardId))
    }

    private fun reveal(
        row: LinearLayout,
        chip: View?,
    ) {
        val scroll = row.parent as? HorizontalScrollView ?: return
        val apply = {
            val offset =
                if (chip == null || chip.right <= scroll.width) 0 else chip.left - (scroll.width - chip.width) / 2
            scroll.scrollTo(offset, 0)
        }
        // Chips rebuilt for this opening have no positions until the next layout pass.
        if (scroll.isLaidOut && !row.isLayoutRequested) apply() else scroll.post(apply)
    }

    /** The input method chips' row, for tests. */
    internal val schemaScroll: HorizontalScrollView get() = schemaRow.parent as HorizontalScrollView

    internal fun schemaChip(id: String): View? = schemaChips[id]

    /** The running input method as the menu names it. */
    fun currentSchemaLabel(): String = schemaLabel(currentSchema)

    /**
     * The input methods the menu offers, in order, as (id, engine name); the chips are rebuilt only
     * when the list changes. A bundled one keeps its translated name. [others] are the entries that
     * switch to another package, by id.
     */
    fun setSchemas(
        entries: List<Pair<String, String>>,
        others: Map<String, OtherInputMethod> = emptyMap(),
    ) {
        if (entries.map { it.first } == schemaChips.keys.toList() &&
            entries.all { (id, name) -> schemaNames[id] == name } &&
            others == otherChips
        ) {
            return
        }
        otherChips = others
        schemaChips.clear()
        schemaNames.clear()
        schemaRow.removeAllViews()
        for ((id, name) in entries) {
            schemaNames[id] = name
            val view = chip(schemaLabel(id)) { onSchema(id) }
            schemaChips[id] = view
            schemaRow.addView(view)
        }
        update(currentSchema, currentKeyboardId)
    }

    /** The input methods the menu offers, in order. */
    internal val schemaIds: List<String> get() = schemaChips.keys.toList()

    /**
     * The keyboards of the running input method, in the order of the user's list; the chips are
     * rebuilt only when the keyboards or their names change.
     */
    fun setKeyboards(entries: List<KeyboardChip>) {
        if (entries != keyboardChips.map { it.first }) {
            keyboardChips.clear()
            layoutRow.removeAllViews()
            for (entry in entries) {
                val view = chip(entry.label) { onKeyboard(entry.keyboard) }
                keyboardChips += entry to view
                layoutRow.addView(view)
            }
        }
        update(currentSchema, currentKeyboardId)
    }

    /** The keyboards the menu offers, in order. */
    internal val keyboards: List<KeyboardChip> get() = keyboardChips.map { it.first }

    /** The chip of the keyboard with this id, when the menu offers it. */
    internal fun keyboardChip(id: String): View? = keyboardChips.firstOrNull { it.first.keyboard.id == id }?.second

    /**
     * The extensions with their own panel, as (package name, label). Rebuilt when the list changes;
     * an empty list hides the whole section so the menu stays as short as before.
     *
     * With [loading] and no rows yet, the section shows a single placeholder line: the rows that
     * arrive with the menu's background scan then replace it instead of making the whole section
     * appear out of nowhere under the user.
     */
    fun setExtensionPanels(
        entries: List<Pair<String, String>>,
        loading: Boolean = false,
    ) {
        val section = extensionSection ?: return
        if (entries == extensionEntries && loading == extensionLoading) return
        extensionEntries = entries
        extensionLoading = loading
        val removed = mutableListOf<View>()
        for (index in section.childCount - 1 downTo 0) {
            removed += section.getChildAt(index)
            section.removeViewAt(index)
        }
        if (removed.isNotEmpty()) {
            val removedSet = removed.toSet()
            actionRows.removeAll(removedSet)
            actionLabels.removeAll(removedSet)
            actionChevrons.removeAll(removedSet)
            actionDividers.removeAll(removedSet)
        }
        extensionPlaceholder = null
        val visible = entries.isNotEmpty() || loading
        extensionDivider?.visibility = if (visible) VISIBLE else GONE
        extensionTitle?.visibility = if (visible) VISIBLE else GONE
        section.visibility = if (visible) VISIBLE else GONE
        if (loading && entries.isEmpty()) {
            // The waiting row uses the same metrics as an action row, so the rows that replace it
            // land in the same place instead of shrinking or growing the section.
            val placeholder = staticRow(context.getString(R.string.ime_menu_extensions_loading))
            extensionPlaceholder = placeholder.getChildAt(0) as TextView
            section.addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        for ((packageName, label) in entries) {
            section.addView(actionRow(label) { onExtensionPanel(packageName) })
        }
    }

    /** The rows currently drawn for the extension section; the controller keeps them stable. */
    internal val extensionRows: List<Pair<String, String>> get() = extensionEntries

    /** Whether the section is still waiting for its first list. */
    internal val extensionWaiting: Boolean get() = extensionLoading

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
        extensionPlaceholder?.setTextColor(palette.muted)
        headerTitle?.setTextColor(palette.text)
        for (label in actionLabels) label.setTextColor(palette.text)
        for (chevron in actionChevrons) chevron.imageTintList = ColorStateList.valueOf(palette.muted)
        headerClose?.imageTintList = ColorStateList.valueOf(palette.muted)
        for (row in actionRows) row.background = pressedBackground()
        headerClose?.background = pressedBackground()
        for (divider in actionDividers) divider.setBackgroundColor(palette.divider)
        update(currentSchema, currentKeyboardId)
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

    /**
     * A row with the exact metrics of [actionRow] but no press state and no chevron: the waiting
     * placeholder, so replacing it with real rows never moves the menu's other items.
     */
    private fun staticRow(text: String): LinearLayout {
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = padding(MIN_TOUCH_TARGET_DP)
                setPadding(padding(12), padding(8), padding(12), padding(8))
                layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = padding(2)
                    }
            }
        row.addView(
            TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(palette.muted)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        return row
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

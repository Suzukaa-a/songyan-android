package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.ColorMode
import app.songyan.ime.ime.KeyHeight
import app.songyan.ime.ime.PanelIcon
import app.songyan.ime.ime.R
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlin.math.floor

/** Narrowest a tile may get; the grid has as many columns as fit, between [MIN_COLUMNS] and [MAX_COLUMNS]. */
private const val TILE_MIN_WIDTH_DP = 84.0
private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 8

/** Tile height: roomy when the panel has the height, never under a comfortable touch target. */
private const val TILE_MIN_HEIGHT_DP = 72.0
private const val TILE_MAX_HEIGHT_DP = 96.0
private const val TILE_INSET_DP = 4f
private const val ICON_DP = 24f
private const val OPTION_ICON_DP = 32f
private const val LABEL_SP = 13f
private const val VALUE_SP = 11.5f
private const val SLASH_DP = 2f
private const val DISABLED_ALPHA = 0.45f

/**
 * The in-keyboard settings panel: a grid of tiles for the settings people change while typing, and a
 * 「更多设置」 key that opens the settings app.
 *
 * A toggle flips with one tap. A setting with several values opens a page of option tiles of the same size
 * (the current one filled); choosing one applies it, stores it and returns to the grid, where the tile now
 * names the new value. Every change goes out through [onChange]; the panel does not touch preferences, the
 * engine or the editor. Its state is pushed with [update] and advanced locally with each change, so a tile
 * answers at once.
 */
class KeyboardSettingsPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    var onChange: (QuickSettingChange) -> Unit = {}
    var onChooseLayout: () -> Unit = {}
    var onOpenQuickContent: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}

    /** What one cell draws and does; the same shape serves a tile of the grid and an option of a page. */
    private class Cell(
        @DrawableRes val icon: Int,
        val iconDp: Float,
        val label: String,
        val value: String?,
        /** Filled in the accent colour: a toggle that is on, one-hand mode in use, the option in use. */
        val active: Boolean,
        /** Drawn with a slash through the icon: a toggle that is off. */
        val struck: Boolean,
        val enabled: Boolean,
        /** The option in use on an option page: marked with a check, an outline and a bold label. */
        val checked: Boolean = false,
        val spoken: String,
        val action: () -> Unit,
    )

    private var state = QuickSettingsState()
    private var openSetting: QuickSetting? = null
    private var cells: List<Cell> = emptyList()

    /** Where the input method's own switches begin on the grid; they sit under their own heading. */
    private var switchesStart = -1
    private val icons = HashMap<Int, PanelIcon>()

    override val titleText: String
        get() =
            openSetting?.let { context.getString(labelOf(it)) }
                ?: context.getString(R.string.ime_panel_settings_title)

    /** Pushes the current values and shows the grid. */
    fun update(newState: QuickSettingsState) {
        state = newState
        openSetting = null
        rebuild()
    }

    /** Leaves an open option page for the grid; false when the grid was already showing. */
    fun backToGrid(): Boolean {
        if (openSetting == null) return false
        openSetting = null
        rebuild()
        return true
    }

    private fun rebuild() {
        val tiles = QuickSettingsModel.tiles(state)
        val open = openSetting?.let { setting -> tiles.first { it.setting == setting } }
        cells = if (open == null) tiles.map(::tileCell) else open.options.map { optionCell(open.setting, it) }
        switchesStart = if (open == null) tiles.indexOfFirst { it.setting == QuickSetting.ENGINE_OPTION } else -1
        resetScroll()
        invalidate()
    }

    private fun apply(change: QuickSettingChange) {
        state = state.apply(change)
        openSetting = null
        rebuild()
        onChange(change)
    }

    private fun tileCell(tile: QuickTile): Cell {
        val label =
            tile.engineOption?.let { context.getString(engineOptionLabel(it.kind)) }
                ?: context.getString(labelOf(tile.setting))
        val spokenLabel =
            if (tile.setting == QuickSetting.PHRASES) context.getString(R.string.ime_panel_quick_content) else label
        val value = tileValue(tile)
        return Cell(
            icon = tileIcon(tile),
            iconDp = ICON_DP,
            label = label,
            value = value,
            active = tile.enabled && tileActive(tile),
            struck = tile.kind == QuickTile.Kind.TOGGLE && !tile.on,
            enabled = tile.enabled,
            spoken =
                if (value == null || tile.setting == QuickSetting.PHRASES) {
                    spokenLabel
                } else {
                    context.getString(R.string.ime_qs_label_value, spokenLabel, value)
                },
            action = {
                when (tile.kind) {
                    QuickTile.Kind.TOGGLE -> QuickSettingsModel.toggled(tile)?.let(::apply)
                    QuickTile.Kind.CHOICE -> {
                        openSetting = tile.setting
                        rebuild()
                    }
                    QuickTile.Kind.ACTION ->
                        if (tile.setting == QuickSetting.LAYOUT) onChooseLayout() else onOpenQuickContent()
                }
            },
        )
    }

    private fun optionCell(
        setting: QuickSetting,
        option: QuickOption,
    ): Cell {
        val label = context.getString(optionLabel(setting, option.key))
        return Cell(
            icon = optionIcon(setting, option.key),
            iconDp = if (setting == QuickSetting.KEY_HEIGHT) heightIconDp(option.key) else OPTION_ICON_DP,
            label = label,
            value = null,
            active = option.selected,
            struck = false,
            enabled = true,
            checked = option.selected,
            spoken = if (option.selected) context.getString(R.string.ime_qs_option_selected, label) else label,
            action = { apply(option.change) },
        )
    }

    private fun tileActive(tile: QuickTile): Boolean =
        when (tile.kind) {
            QuickTile.Kind.TOGGLE -> tile.on
            QuickTile.Kind.CHOICE ->
                tile.setting == QuickSetting.ONE_HAND && tile.valueKey != KeyboardOneHandMode.OFF.wire
            QuickTile.Kind.ACTION -> false
        }

    private fun tileValue(tile: QuickTile): String? =
        when (tile.setting) {
            QuickSetting.ONE_HAND ->
                context.getString(
                    if (tile.enabled) optionLabel(tile.setting, tile.valueKey) else R.string.ime_qs_unavailable,
                )
            QuickSetting.KEY_HEIGHT, QuickSetting.APPEARANCE ->
                context.getString(optionLabel(tile.setting, tile.valueKey))
            QuickSetting.LAYOUT -> context.getString(layoutName(tile.valueKey))
            QuickSetting.KEY_SOUND ->
                context.getString(
                    when {
                        !tile.enabled -> R.string.ime_qs_system_off
                        tile.on -> R.string.ime_qs_on
                        else -> R.string.ime_qs_off
                    },
                )
            QuickSetting.KEY_HAPTICS, QuickSetting.KEY_PREVIEW, QuickSetting.ENGINE_OPTION ->
                context.getString(if (tile.on) R.string.ime_qs_on else R.string.ime_qs_off)
            QuickSetting.PHRASES -> context.getString(R.string.ime_qs_phrases_more)
        }

    @StringRes
    private fun labelOf(setting: QuickSetting): Int =
        when (setting) {
            QuickSetting.ONE_HAND -> R.string.ime_qs_one_hand
            QuickSetting.KEY_HEIGHT -> R.string.ime_qs_key_height
            QuickSetting.LAYOUT -> R.string.ime_qs_layout
            QuickSetting.APPEARANCE -> R.string.ime_qs_appearance
            QuickSetting.KEY_SOUND -> R.string.ime_qs_key_sound
            QuickSetting.KEY_HAPTICS -> R.string.ime_qs_key_haptics
            QuickSetting.KEY_PREVIEW -> R.string.ime_qs_key_preview
            QuickSetting.PHRASES -> R.string.ime_qs_phrases
            // A switch tile is worded by its own kind; see labelOfTile.
            QuickSetting.ENGINE_OPTION -> R.string.ime_panel_settings_title
        }

    @StringRes
    private fun engineOptionLabel(kind: EngineOptionKind): Int =
        when (kind) {
            EngineOptionKind.TRADITIONAL -> R.string.ime_qs_engine_traditional
            EngineOptionKind.SIMPLIFIED -> R.string.ime_qs_engine_simplified
            EngineOptionKind.EMOJI -> R.string.ime_qs_engine_emoji
            EngineOptionKind.FULL_SHAPE -> R.string.ime_qs_engine_full_shape
        }

    @StringRes
    private fun optionLabel(
        setting: QuickSetting,
        key: String?,
    ): Int =
        when (setting) {
            QuickSetting.ONE_HAND ->
                when (key) {
                    KeyboardOneHandMode.LEFT.wire -> R.string.ime_qs_left
                    KeyboardOneHandMode.RIGHT.wire -> R.string.ime_qs_right
                    else -> R.string.ime_qs_off
                }
            QuickSetting.KEY_HEIGHT ->
                when (key) {
                    KeyHeight.COMPACT.wire -> R.string.ime_qs_height_compact
                    KeyHeight.TALL.wire -> R.string.ime_qs_height_tall
                    else -> R.string.ime_qs_height_standard
                }
            else ->
                when (key) {
                    ColorMode.LIGHT.wire -> R.string.ime_qs_mode_light
                    ColorMode.DARK.wire -> R.string.ime_qs_mode_dark
                    else -> R.string.ime_qs_mode_system
                }
        }

    @StringRes
    private fun layoutName(key: String?): Int =
        when (key) {
            KeyboardLayoutKind.MERGED14.wire -> R.string.ime_layout_merged14
            KeyboardLayoutKind.MERGED18.wire -> R.string.ime_layout_merged18
            KeyboardLayoutKind.PHONE9.wire -> R.string.ime_layout_phone9
            else -> R.string.ime_layout_qwerty26
        }

    @DrawableRes
    private fun tileIcon(tile: QuickTile): Int =
        when (tile.setting) {
            QuickSetting.ONE_HAND -> optionIcon(tile.setting, if (tile.enabled) tile.valueKey else null)
            QuickSetting.KEY_HEIGHT -> R.drawable.ic_qs_height
            QuickSetting.LAYOUT -> R.drawable.ic_toolbar_keyboard
            QuickSetting.APPEARANCE -> optionIcon(tile.setting, tile.valueKey)
            QuickSetting.KEY_SOUND -> R.drawable.ic_qs_sound
            QuickSetting.KEY_HAPTICS -> R.drawable.ic_qs_vibration
            QuickSetting.KEY_PREVIEW -> R.drawable.ic_qs_preview
            QuickSetting.PHRASES -> R.drawable.ic_toolbar_phrases
            QuickSetting.ENGINE_OPTION -> R.drawable.ic_qs_engine_option
        }

    @DrawableRes
    private fun optionIcon(
        setting: QuickSetting,
        key: String?,
    ): Int =
        when (setting) {
            QuickSetting.ONE_HAND ->
                when (key) {
                    KeyboardOneHandMode.LEFT.wire -> R.drawable.ic_qs_one_hand_left
                    KeyboardOneHandMode.RIGHT.wire -> R.drawable.ic_qs_one_hand_right
                    else -> R.drawable.ic_qs_one_hand_off
                }
            QuickSetting.APPEARANCE ->
                when (key) {
                    ColorMode.LIGHT.wire -> R.drawable.ic_qs_sun
                    ColorMode.DARK.wire -> R.drawable.ic_qs_moon
                    else -> R.drawable.ic_qs_contrast
                }
            else -> R.drawable.ic_toolbar_keyboard
        }

    /** The three heights are shown as three sizes of keyboard, so the choice reads without a label. */
    private fun heightIconDp(key: String): Float =
        when (key) {
            KeyHeight.COMPACT.wire -> 22f
            KeyHeight.TALL.wire -> 34f
            else -> 28f
        }

    private fun icon(
        @DrawableRes res: Int,
    ): PanelIcon = icons.getOrPut(res) { PanelIcon(context, res) }

    override fun bottomKeys(): List<BottomKey> {
        val label = context.getString(if (openSetting == null) R.string.ime_qs_more else R.string.ime_qs_back)
        return listOf(BottomKey(label, label, 1.0) { if (openSetting == null) onOpenSettings() else backToGrid() })
    }

    override fun cellCount(): Int = cells.size

    override fun sectionStart(): Int = switchesStart

    override fun sectionTitle(): String = context.getString(R.string.ime_qs_engine_section)

    override fun contentColumns(layout: QuickContentPanelLayout): Int =
        floor(contentWidthDp() / TILE_MIN_WIDTH_DP).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    override fun cellHeightDp(layout: QuickContentPanelLayout): Double {
        val content = contentHeightDp() - sectionTitleDp()
        val rows = PanelGeometry.gridRows(cells.size, contentColumns(layout), switchesStart).coerceAtLeast(1)
        val fit = content / rows
        if (fit >= TILE_MIN_HEIGHT_DP) return fit.coerceAtMost(TILE_MAX_HEIGHT_DP)
        // Too short for every row: whole rows only, and the rest is a scroll away.
        val visible = floor(content / TILE_MIN_HEIGHT_DP).toInt().coerceAtLeast(1)
        return content / visible
    }

    override fun cellAccessibilityLabel(index: Int): String = cells.getOrNull(index)?.spoken.orEmpty()

    override fun onCellActivated(index: Int) {
        val cell = cells.getOrNull(index) ?: return
        if (cell.enabled) cell.action()
    }

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        val item = cells.getOrNull(index) ?: return
        val radius = metrics.keyRadiusDp * density
        rect.set(
            px(cell.left) + TILE_INSET_DP * density,
            px(cell.top) + TILE_INSET_DP * density,
            px(cell.right) - TILE_INSET_DP * density,
            px(cell.bottom) - TILE_INSET_DP * density,
        )
        paint.color = if (item.active) palette.accent else palette.key
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (item.checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = palette.accentText
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        val textColor = if (item.active) palette.accentText else palette.text
        val mutedColor = if (item.active) palette.accentText else palette.muted
        val fade = if (item.enabled) 1f else DISABLED_ALPHA
        val room = rect.width() - 8f * density

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = LABEL_SP * textScale
        // textScale already carries the density: text sizes are pixels.
        val labelHeight = LABEL_SP * textScale
        val valueHeight = if (item.value != null) VALUE_SP * textScale else 0f
        // The tallest icon of the page sets the block, so labels of one row share a baseline.
        val iconBlock = cells.maxOf { it.iconDp } * density
        val gap = 6f * density

        fun blockHeight(lines: Int) = iconBlock + gap + labelHeight * LINE_SPACING * lines + valueHeight * LINE_SPACING
        // A long name takes a second line when the tile has the height for it, else it is cut short.
        val fits = blockHeight(2) <= rect.height() - 2f * density
        val lines = wrapLabel(item.label, room, mayWrap = fits)
        // Every tile of the page reserves the tallest label, so the icons of a row stay level.
        val reserved = if (fits) cells.maxOf { wrapLabel(it.label, room, mayWrap = true).size } else 1
        val top = (rect.centerY() - blockHeight(reserved) / 2f).coerceAtLeast(rect.top)
        val iconCentre = top + iconBlock / 2f
        icon(item.icon).draw(canvas, rect.centerX(), iconCentre, item.iconDp * density, withAlpha(textColor, fade))
        if (item.struck) {
            paint.color = withAlpha(textColor, fade)
            paint.strokeWidth = SLASH_DP * density
            paint.strokeCap = Paint.Cap.ROUND
            val half = item.iconDp * density * 0.42f
            canvas.drawLine(rect.centerX() - half, iconCentre - half, rect.centerX() + half, iconCentre + half, paint)
            paint.strokeWidth = 0f
        }
        paint.color = withAlpha(textColor, fade)
        paint.isFakeBoldText = item.checked
        var baseline = top + iconBlock + gap + labelHeight * 0.85f
        for (line in lines) {
            canvas.drawText(line, rect.centerX(), baseline, paint)
            baseline += labelHeight * LINE_SPACING
        }
        paint.isFakeBoldText = false
        if (item.checked) {
            drawCheck(
                canvas,
                rect.right - 14f * density,
                rect.top + 14f * density,
                palette.accentText,
            )
        }
        item.value?.let { value ->
            paint.textSize = VALUE_SP * textScale
            paint.color = withAlpha(mutedColor, fade)
            val fitted = PanelText.ellipsize(value, room.coerceAtLeast(1f)) { paint.measureText(it) }
            canvas.drawText(fitted, rect.centerX(), baseline, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    /** One line when it fits; a long name without a value may take a second one, otherwise it is cut short. */
    private fun wrapLabel(
        name: String,
        room: Float,
        mayWrap: Boolean,
    ): List<String> {
        val label = name
        if (paint.measureText(label) <= room) return listOf(label)
        if (!mayWrap) return listOf(PanelText.ellipsize(label, room.coerceAtLeast(1f)) { paint.measureText(it) })
        var split = label.length
        while (split > 1 && paint.measureText(label, 0, split) > room) split--
        // Break between words when the name has any; names without spaces (Chinese) break anywhere.
        val space = label.lastIndexOf(' ', split)
        if (space > 0) split = space + 1
        val rest = PanelText.ellipsize(label.substring(split).trim(), room.coerceAtLeast(1f)) { paint.measureText(it) }
        return listOf(label.substring(0, split).trim(), rest)
    }

    private fun drawCheck(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
    ) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val u = density
        val path = android.graphics.Path()
        path.moveTo(x - 5f * u, y)
        path.lineTo(x - 1.5f * u, y + 4f * u)
        path.lineTo(x + 5f * u, y - 4f * u)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
    }

    private fun withAlpha(
        color: Int,
        alpha: Float,
    ): Int = (color and 0x00FFFFFF) or ((((color ushr 24) * alpha).toInt() and 0xFF) shl 24)

    private companion object {
        const val LINE_SPACING = 1.15f
    }
}

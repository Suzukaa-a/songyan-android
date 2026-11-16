package app.songyan.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.View
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import kotlin.math.roundToInt

/**
 * The keyboard's own key view showing a layout that is being edited, for the layout editor: the
 * same pages, shell, appearance and geometry the keyboard draws, so the preview cannot differ from
 * it. It has no engine and does nothing when touched; the editor puts its own selection on top of
 * [keys], which says which drawn key stands for which key of the draft.
 */
class LayoutPreviewKeyboard(
    context: Context,
) {
    /** A key of the draft: a key of its rows, of its bottom row, or of a side column. */
    sealed interface Target {
        data class Grid(
            val row: Int,
            val index: Int,
        ) : Target

        data class Bottom(
            val index: Int,
        ) : Target

        data class Rail(
            val position: LayoutAuxiliaryArea.Position,
            val index: Int,
        ) : Target
    }

    /** A drawn key the editor can select: what it stands for, its face and its keycap in view pixels. */
    class Key(
        val target: Target,
        val label: String,
        val cap: RectF,
        val radius: Float,
    )

    private val keyboard =
        KeyboardView(context).also { view ->
            view.keyPreviewEnabled = false
            view.extraRows = 0
            view.syllableHints = emptyMap()
            // The editor speaks and selects the keys itself; the keyboard's own nodes would type.
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            swallowTouches(view)
        }

    /** The view to place in the editor. */
    val view: View get() = keyboard

    /** The narrowest width (dp) at which the keyboard draws a layout split. */
    val splitWidthDp: Int get() = keyboard.splitWidthDp + SPLIT_MARGIN_DP

    /** Called with the selectable keys each time they are placed again. */
    var onKeys: (List<Key>) -> Unit = {}

    private var sourceRows: List<LayoutRow> = emptyList()
    private var sourceBottom: List<LayoutKey> = emptyList()
    private var declaredRails: Map<LayoutAuxiliaryArea.Position, Int> = emptyMap()
    private var phone = false
    private var lastReported: List<Key>? = null

    init {
        keyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> report() }
    }

    /**
     * Draws [layout] as the keyboard would in a plain text field: whole or, with [split], as the
     * split keyboard. [rows] and [bottom] are the draft's rows and bottom row as the editor indexes
     * them (the split arrangement in a split view), [quickSymbols] the nine-key's effective symbols.
     */
    fun show(
        layout: LayoutDefinition,
        split: Boolean,
        rows: List<LayoutRow>,
        bottom: List<LayoutKey>,
        quickSymbols: String,
    ) {
        sourceRows = rows
        sourceBottom = bottom
        val enter = keyboard.keyNames.enterAction(InputLanguage.ZH_HANS, R.string.ime_done)
        val faces = KeyboardFaces(language = InputLanguage.ZH_HANS, mainLayoutId = layout.id)
        val kind = layout.input.builtinLayout?.let { KeyboardLayoutKind.fromWire(it.wire) }
        phone = kind == KeyboardLayoutKind.PHONE9 && PhoneKeypadLayout.draws(layout)
        if (phone) {
            declaredRails =
                if (PhoneKeypadRailPolicy.usesBundledRails(layout)) {
                    emptyMap()
                } else {
                    layout.auxiliaryAreas.orEmpty().associate { it.position to it.keys.size }
                }
            keyboard.splitMode = SplitMode.OFF
            keyboard.updatePhone(
                config = phoneConfig(layout, quickSymbols, enter),
                shift = false,
                enterLabel = enter,
                faces = faces.copy(phone = true),
            )
        } else {
            declaredRails = emptyMap()
            keyboard.splitMode = if (split) SplitMode.ON else SplitMode.OFF
            keyboard.update(
                definition = KeyboardPages.previewLetters(layout),
                shift = false,
                enterLabel = enter,
                faces = faces,
            )
        }
        report()
    }

    private fun phoneConfig(
        layout: LayoutDefinition,
        quickSymbols: String,
        enter: String,
    ): PhoneKeypadLayout.Config {
        val names = keyboard.keyNames
        val language = InputLanguage.ZH_HANS
        return PhoneKeypadLayout.Config(
            width = 0f,
            height = 0f,
            composing = false,
            asciiPunctuation = false,
            savedQuickSymbols = quickSymbols,
            definition = layout,
            labels =
                PhoneKeypadLayout.Labels(
                    page = names.chinese().getString(R.string.ime_numbers),
                    language = names.languageKeyDrawn(language) ?: "中/英",
                    space = names.space(language, composing = false),
                    symbols = names.symbolPageDrawn(language),
                    back = names.returnToLettersDrawn(language, false),
                    zero = "0",
                    enter = enter,
                    clear = names.chinese().getString(R.string.ime_clear),
                    delete = keyboard.context.getString(R.string.ime_glyph_delete),
                ),
        )
    }

    private fun report() {
        val keys = placedKeys()
        if (keys.size == lastReported?.size && keys.indices.all { same(keys[it], lastReported!![it]) }) return
        lastReported = keys
        onKeys(keys)
    }

    private fun same(
        a: Key,
        b: Key,
    ) = a.target == b.target && a.label == b.label && a.cap == b.cap

    private fun placedKeys(): List<Key> {
        val view = keyboard
        if (view.width <= 0 || view.bounds.size != view.keys.size) return emptyList()
        val targets = if (phone) phoneTargets() else rowTargets()
        val radius = view.keyRadiusPx()
        return view.keys.indices.mapNotNull { index ->
            val target = targets.getOrNull(index) ?: return@mapNotNull null
            val box = view.bounds[index]
            Key(
                target = target,
                label = view.labels.getOrElse(index) { view.keys[index].label },
                cap =
                    RectF(
                        box.left + view.insetX,
                        box.top + view.insetY,
                        box.right - view.insetX,
                        box.bottom - view.insetY,
                    ),
                radius = radius,
            )
        }
    }

    /**
     * The row pages place each row on its own band, left to right, so the drawn keys sharing a top
     * are one row in order. The keys the page adds around the last letter row (Shift or the merged
     * pads' separator, Delete) are the page's, not the draft's, and select nothing.
     */
    private fun rowTargets(): List<Target?> {
        val view = keyboard
        val targets = arrayOfNulls<Target>(view.keys.size)
        if (view.kana12 != null) return targets.toList()
        val bands =
            view.keys.indices
                .groupBy { view.bounds[it].top.roundToInt() }
                .values
                .toList()
        val expected = sourceRows.size + if (sourceBottom.isEmpty()) 0 else 1
        if (bands.size != expected) return targets.toList()
        for ((row, band) in bands.withIndex()) {
            val isBottom = row == sourceRows.size
            val drawn = band.filterNot { isShellKey(view.keys[it]) }
            val source = if (isBottom) sourceBottom else sourceRows[row].keys
            val shown = source.indices.filter { drawnByPreview(source[it]) }
            if (drawn.size != shown.size) continue
            for ((n, index) in drawn.withIndex()) {
                targets[index] = if (isBottom) Target.Bottom(shown[n]) else Target.Grid(row, shown[n])
            }
        }
        return targets.toList()
    }

    /**
     * The phone shell's regions: the 3x3 grid in reading order, the side columns top to bottom (only
     * declared ones are the draft's keys) and the bottom row left to right.
     */
    private fun phoneTargets(): List<Target?> {
        val view = keyboard
        val regions = view.shellRegions
        if (regions.size != view.keys.size) return List(view.keys.size) { null }
        val grid = sourceRows.flatMapIndexed { row, keys -> keys.keys.indices.map { Target.Grid(row, it) } }
        val bottomShown = sourceBottom.indices.filter { drawnByPreview(sourceBottom[it]) }
        var main = 0
        var leading = 0
        var trailing = 0
        var bottom = 0
        val bottomCount = regions.count { it == PhoneKeypadLayout.Region.BOTTOM }
        val leadingDeclared = declaredRails[LayoutAuxiliaryArea.Position.LEADING]
        val trailingDeclared = declaredRails[LayoutAuxiliaryArea.Position.TRAILING]
        return regions.map { region ->
            when (region) {
                PhoneKeypadLayout.Region.MAIN -> grid.getOrNull(main++)
                PhoneKeypadLayout.Region.LEADING_RAIL ->
                    (leading++).takeIf { leadingDeclared != null && it < leadingDeclared }?.let {
                        Target.Rail(LayoutAuxiliaryArea.Position.LEADING, it)
                    }
                PhoneKeypadLayout.Region.TRAILING_RAIL ->
                    (trailing++).takeIf { trailingDeclared != null && it < trailingDeclared }?.let {
                        Target.Rail(LayoutAuxiliaryArea.Position.TRAILING, it)
                    }
                PhoneKeypadLayout.Region.BOTTOM ->
                    (bottom++).takeIf { bottomCount == bottomShown.size }?.let { Target.Bottom(bottomShown[it]) }
            }
        }
    }

    /** The keys the letters page adds itself; the shared instances tell them from a draft's own Shift. */
    private fun isShellKey(key: LayoutKey): Boolean =
        key === LetterPageKeys.SHIFT || key === LetterPageKeys.MERGED_SEPARATOR || key === LetterPageKeys.DELETE

    /** Whether the keyboard draws [key]: the switch key is off by default, and a jump needs a target it knows. */
    private fun drawnByPreview(key: LayoutKey): Boolean =
        when (key.action.type) {
            LayoutActionKind.GLOBE -> false
            LayoutActionKind.LAYOUT_PAGE_SWITCH ->
                key.action.layoutPageJump?.let { KeyboardLayoutKind.fromWire(it.layout.wire) } != null
            else -> true
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun swallowTouches(view: View) {
        // No key acts in a preview: a touch reaches neither the keys nor the popups and feedback.
        view.setOnTouchListener { _, _ -> true }
    }

    private companion object {
        /** Past the automatic split threshold, so the split keyboard is what is drawn. */
        const val SPLIT_MARGIN_DP = 80
    }
}

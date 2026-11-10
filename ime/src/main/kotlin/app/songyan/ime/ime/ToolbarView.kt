package app.songyan.ime.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.candidate.ToolbarCapacity
import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout

/**
 * The idle row of the keyboard's top band: while nothing is being composed the candidate row shows
 * these shortcuts instead, so the band keeps its height and the keyboard never jumps when the first
 * key starts a composition. Each entry is a 24 dp icon in a 48 dp-tall touch area; the entries share
 * the width evenly.
 */
class ToolbarView(
    context: Context,
) : AccessibleCanvasView(context),
    BarContent {
    /** What a toolbar entry does; the owner decides how each is carried out. */
    enum class Action(
        val iconRes: Int,
        val labelRes: Int,
    ) {
        CLIPBOARD(R.drawable.ic_toolbar_clipboard, R.string.ime_panel_clipboard),
        INPUT_METHODS(R.drawable.ic_toolbar_keyboard, R.string.ime_toolbar_input_methods),
        EMOJI(R.drawable.ic_toolbar_emoji, R.string.ime_toolbar_emoji),
        EDIT(R.drawable.ic_toolbar_edit, R.string.ime_toolbar_edit),
        ONE_HAND(R.drawable.ic_qs_one_hand_off, R.string.ime_qs_one_hand),
        SETTINGS(R.drawable.ic_toolbar_settings, R.string.ime_menu_keyboard_settings),
        HIDE(R.drawable.ic_toolbar_hide, R.string.ime_toolbar_hide),
        SWITCH_INPUT_METHOD(R.drawable.ic_toolbar_globe, R.string.ime_toolbar_switch_input_method),
        VOICE_INPUT(R.drawable.ic_toolbar_mic, R.string.ime_toolbar_voice_input),
        ;

        /** The user-chosen item this action stands for; null for the collapse button and system-driven entries. */
        val item: ToolbarItem? get() = ToolbarItem.entries.firstOrNull { it.name == name }

        /** Whether overflow may drop it: only the user's own choices, never the system-driven ones. */
        val chosen: Boolean get() = item != null
    }

    /** An entry at the right end of the engine notice: a text button or one of the icon actions. */
    enum class NoticeItem(
        val labelRes: Int,
        val icon: Action?,
    ) {
        RETRY(R.string.ime_engine_retry, null),
        SETTINGS(R.string.ime_engine_settings, null),
        SWITCH_INPUT_METHOD(R.string.ime_toolbar_switch_input_method, Action.SWITCH_INPUT_METHOD),
    }

    /** The engine's state in the row's place: [text] on the left, [items] at the right end. */
    data class Notice(
        val text: String,
        val items: List<NoticeItem>,
    )

    var onNoticeItem: (NoticeItem) -> Unit = {}

    /**
     * While set, the notice takes the whole row, like the chip: same height, so nothing moves when the
     * engine comes back. A notice outranks a chip.
     */
    var notice: Notice? = null
        set(value) {
            if (field == value) return
            field = value
            down = -1
            noticeLayout = null
            requestLayout()
            invalidate()
            if (value != null) contentDescription = value.text else contentDescription = null
        }

    var onAction: (Action) -> Unit = {}

    /** A finger landed on an entry: the same tick a key gives at touch-down. */
    var onPressFeedback: () -> Unit = {}

    /** A tap on the chip pastes it; its close button dismisses it. */
    var onChipPaste: (ToolbarChip) -> Unit = {}
    var onChipDismiss: (ToolbarChip) -> Unit = {}

    /**
     * A suggestion to paste. While one is set it takes the whole row, so the row does not jump:
     * the chip and its close button replace the shortcuts until it is used, closed or expires.
     */
    var chip: ToolbarChip? = null
        set(value) {
            if (field == value) return
            field = value
            down = -1
            chipLayout = null
            requestLayout()
            invalidate()
        }

    /** The one-handed state the [Action.ONE_HAND] icon shows: the side it hugs, in the accent while on. */
    var oneHand: KeyboardOneHandMode = KeyboardOneHandMode.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The entries in order; the owner may leave out what it cannot do. */
    var actions: List<Action> = DEFAULT_ACTIONS
        set(value) {
            if (field == value) return
            field = value
            resetSlots()
            requestLayout()
            invalidate()
        }

    /**
     * The entries from left to right. What does not fit the width is dropped from the end of the
     * user's order, so the first choices stay.
     */
    private fun entries(): List<Action> {
        val cached = entryCache
        if (cached != null && entryCacheWidth == width) return cached
        val kept =
            if (width <= 0) {
                actions
            } else {
                val slots =
                    actions.map { action ->
                        ToolbarCapacity.Slot(ToolbarCapacity.MIN_ICON_SLOT_DP, droppable = action.chosen)
                    }
                val keep = ToolbarCapacity.keep(width / density, slots)
                actions.filterIndexed { index, _ -> keep[index] }
            }
        entryCache = kept
        entryCacheWidth = width
        return kept
    }

    private var entryCache: List<Action>? = null
    private var entryCacheWidth = -1

    private fun resetSlots() {
        slotCache = null
        entryCache = null
    }

    /** Slot boxes of the drawn entries: the width shared equally. */
    private var slotCache: List<Rect>? = null
    private var slotCacheWidth = -1
    private var slotCacheHeight = -1

    private fun slots(): List<Rect> {
        val cached = slotCache
        if (cached != null && slotCacheWidth == width && slotCacheHeight == height) return cached
        val entries = entries()
        if (entries.isEmpty() || width <= 0) return emptyList()
        val iconWidth = (width / entries.size).coerceAtLeast(1)
        var left = 0
        val result = ArrayList<Rect>(entries.size)
        for (index in entries.indices) {
            result += Rect(left, 0, left + iconWidth, height)
            left += iconWidth
        }
        slotCache = result
        slotCacheWidth = width
        slotCacheHeight = height
        return result
    }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawables = HashMap<Int, Drawable>()
    private var palette = KeyboardPalette.from(context)
    private var metrics = palette.tokens
    private val bounds = RectF()
    private var down = -1
    private var fadeIndex = -1
    private var fadeAlpha = 0f
    private var fadeAnimator: ValueAnimator? = null
    private var chipLayout: ChipLayout? = null
    private var noticeLayout: NoticeLayout? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override val itemCount: Int
        get() = notice?.items?.size ?: if (chip != null) CHIP_ITEMS else entries().size

    /** The reading row's height while one sits above; the toolbar then shrinks to it ([ReadingRowBudget.bandDp]). */
    var readingRowDp: Float? = null
        set(value) {
            if (field == value) return
            field = value
            chipLayout = null
            noticeLayout = null
            resetSlots()
            requestLayout()
            invalidate()
        }

    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        metrics = palette.tokens
        drawables.clear()
        chipLayout = null
        noticeLayout = null
        resetSlots()
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        chipLayout = null
        noticeLayout = null
        resetSlots()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    /** Icons, chip and notice centre here: the bar's centre plus the shared drop ([BarContentBox]). */
    private val dropPx: Float get() = BarContentBox.dropDp(readingRowDp != null) * density

    override fun contentCentreY(): Float = height / 2f + dropPx

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val heightDp = ReadingRowBudget.bandDp(metrics.candidateBarHeightDp, readingRowDp)
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (heightDp * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        notice?.let {
            drawNotice(canvas, it)
            return
        }
        chip?.let {
            drawChip(canvas, it)
            return
        }
        // Icons, chip and notice share the candidates' content line (BarContentLine).
        val drop = dropPx
        val entries = entries()
        val boxes = slots()
        for ((index, entry) in entries.withIndex()) {
            val slot = boxes.getOrNull(index) ?: break
            val pressedNow = index == down
            val fading = index == fadeIndex && fadeAlpha > 0f
            if (pressedNow || fading) {
                val radius = metrics.candidateChipRadiusDp * density
                bounds.set(
                    slot.left + 4 * density,
                    slot.top + 5 * density,
                    slot.right - 4 * density,
                    slot.bottom - 5 * density,
                )
                bounds.offset(0f, drop)
                paint.color = palette.pressed
                val base = paint.alpha
                if (!pressedNow) paint.alpha = (base * fadeAlpha).toInt()
                canvas.drawRoundRect(bounds, radius, radius, paint)
                paint.alpha = base
            }
            val icon = drawable(iconOf(entry)) ?: continue
            icon.setTint(
                if (entry == Action.ONE_HAND &&
                    oneHand != KeyboardOneHandMode.OFF
                ) {
                    palette.accent
                } else {
                    palette.muted
                },
            )
            val half = (ICON_DP * density / 2).toInt()
            val cy = (slot.exactCenterY() + drop).toInt()
            icon.setBounds(slot.centerX() - half, cy - half, slot.centerX() + half, cy + half)
            icon.draw(canvas)
        }
    }

    private fun iconOf(action: Action): Int =
        when {
            action != Action.ONE_HAND -> action.iconRes
            oneHand == KeyboardOneHandMode.LEFT -> R.drawable.ic_qs_one_hand_left
            oneHand == KeyboardOneHandMode.RIGHT -> R.drawable.ic_qs_one_hand_right
            else -> action.iconRes
        }

    private fun drawable(action: Action): Drawable? = drawable(action.iconRes)

    private fun drawable(iconRes: Int): Drawable? =
        drawables.getOrPut(iconRes) { ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return null }

    private class ChipLayout(
        val geometry: ToolbarChipGeometry,
        val text: String,
        val textLeft: Float,
    )

    /** The chip's text is cut to the room; the geometry follows the cut text's width. */
    private fun chipLayout(chip: ToolbarChip): ChipLayout {
        chipLayout?.let { if (it.geometry.height == height) return it }
        val margin = (CHIP_MARGIN_DP * density).toInt()
        val dismiss = (metrics.minTouchTargetDp * density).toInt()
        val pad = (metrics.candidateChipPaddingDp * density).toInt()
        val icon = if (chip.kind == ToolbarChipKind.COPIED) ((ICON_SMALL_DP + ICON_GAP_DP) * density).toInt() else 0
        textPaint.textSize = metrics.candidateTextSizeSp * resources.configuration.fontScale * density
        val room = (ToolbarChipGeometry.maxWidth(width, margin, dismiss) - 2 * pad - icon).coerceAtLeast(0)
        val label = chipDisplayText(chip)
        val text = TextUtils.ellipsize(label, TextPaint(textPaint), room.toFloat(), TextUtils.TruncateAt.END).toString()
        val content = pad + icon + textPaint.measureText(text).toInt() + pad
        val geometry = ToolbarChipGeometry.of(width, height, content, margin, dismiss)
        return ChipLayout(geometry, text, geometry.chipLeft + pad + icon.toFloat()).also { chipLayout = it }
    }

    private fun chipDisplayText(chip: ToolbarChip): String =
        if (chip.kind == ToolbarChipKind.CODE) {
            context.getString(R.string.ime_chip_code_label, chip.label())
        } else {
            chip.label()
        }

    private fun chipLabel(chip: ToolbarChip): String =
        context.getString(
            if (chip.kind == ToolbarChipKind.CODE) R.string.ime_chip_code_paste else R.string.ime_chip_copied_paste,
            chip.text,
        )

    private fun drawChip(
        canvas: Canvas,
        chip: ToolbarChip,
    ) {
        val layout = chipLayout(chip)
        val g = layout.geometry
        val radius = metrics.candidateChipRadiusDp * density
        val inset = CHIP_INSET_DP * density
        val drop = dropPx
        val centerY = height / 2f + drop
        bounds.set(g.chipLeft.toFloat(), inset + drop, g.chipRight.toFloat(), height - inset + drop)
        paint.style = Paint.Style.FILL
        paint.color = if (down == 0) palette.pressed else palette.candidateSelectedBackground
        canvas.drawRoundRect(bounds, radius, radius, paint)
        textPaint.color = palette.candidateSelectedText
        if (chip.kind == ToolbarChipKind.COPIED) {
            drawable(Action.CLIPBOARD)?.let { icon ->
                icon.setTint(palette.candidateSelectedText)
                val half = (ICON_SMALL_DP * density / 2).toInt()
                val x = (g.chipLeft + metrics.candidateChipPaddingDp * density).toInt() + half
                val y = centerY.toInt()
                icon.setBounds(x - half, y - half, x + half, y + half)
                icon.draw(canvas)
            }
        }
        canvas.drawText(layout.text, layout.textLeft, BarContentLine.hanCentredBaseline(textPaint, centerY), textPaint)
        // The close button: a thin cross in a full touch target.
        val cx = (g.dismissLeft + g.dismissRight) / 2f
        val cy = centerY
        val arm = CROSS_DP * density / 2
        paint.color = if (down == 1) palette.text else palette.muted
        paint.strokeWidth = 1.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, paint)
        paint.style = Paint.Style.FILL
    }

    private class NoticeLayout(
        val text: String,
        val textLeft: Float,
        val slots: List<Rect>,
    )

    /** Items are laid out from the right edge, each at least one touch target wide; the text gets what is left. */
    private fun noticeLayout(notice: Notice): NoticeLayout {
        noticeLayout?.let { if (it.slots.size == notice.items.size) return it }
        val margin = (CHIP_MARGIN_DP * density).toInt()
        val target = (metrics.minTouchTargetDp * density).toInt()
        val pad = (metrics.candidateChipPaddingDp * density).toInt()
        textPaint.textSize = metrics.candidateTextSizeSp * resources.configuration.fontScale * density
        var right = width - margin
        val slots = ArrayList<Rect>(notice.items.size)
        for (item in notice.items.asReversed()) {
            val wide =
                if (item.icon != null) {
                    target
                } else {
                    maxOf(target, textPaint.measureText(context.getString(item.labelRes)).toInt() + 2 * pad)
                }
            slots.add(0, Rect(right - wide, 0, right, height))
            right -= wide
        }
        val room = (right - margin - pad).coerceAtLeast(0)
        val text =
            TextUtils.ellipsize(notice.text, TextPaint(textPaint), room.toFloat(), TextUtils.TruncateAt.END).toString()
        return NoticeLayout(text, (margin + pad).toFloat(), slots).also { noticeLayout = it }
    }

    private fun drawNotice(
        canvas: Canvas,
        notice: Notice,
    ) {
        val layout = noticeLayout(notice)
        textPaint.color = palette.text
        val drop = dropPx
        val baseline = BarContentLine.hanCentredBaseline(textPaint, height / 2f + drop)
        canvas.drawText(layout.text, layout.textLeft, baseline, textPaint)
        val radius = metrics.candidateChipRadiusDp * density
        for ((index, item) in notice.items.withIndex()) {
            val slot = layout.slots[index]
            if (index == down) {
                bounds.set(
                    slot.left + 2 * density,
                    slot.top + 5 * density,
                    slot.right - 2 * density,
                    slot.bottom - 5 * density,
                )
                bounds.offset(0f, drop)
                paint.color = palette.pressed
                canvas.drawRoundRect(bounds, radius, radius, paint)
            }
            val icon = item.icon
            if (icon != null) {
                val drawable = drawable(icon) ?: continue
                drawable.setTint(palette.muted)
                val half = (ICON_DP * density / 2).toInt()
                val cy = (slot.exactCenterY() + drop).toInt()
                drawable.setBounds(slot.centerX() - half, cy - half, slot.centerX() + half, cy + half)
                drawable.draw(canvas)
            } else {
                textPaint.color = palette.accent
                val label = context.getString(item.labelRes)
                canvas.drawText(label, slot.centerX() - textPaint.measureText(label) / 2f, baseline, textPaint)
            }
        }
    }

    override fun itemBounds(index: Int): Rect {
        notice?.let { return noticeLayout(it).slots.getOrNull(index) ?: Rect() }
        chip?.let { shown ->
            val g = chipLayout(shown).geometry
            return if (index ==
                0
            ) {
                Rect(g.chipLeft, 0, g.chipRight, height)
            } else {
                Rect(g.dismissLeft, 0, g.dismissRight, height)
            }
        }
        return slots().getOrNull(index) ?: Rect()
    }

    override fun itemLabel(index: Int): String {
        notice?.let { return context.getString(it.items[index].labelRes) }
        chip?.let { shown ->
            return if (index == 0) chipLabel(shown) else context.getString(R.string.ime_chip_dismiss)
        }
        return entries().getOrNull(index)?.let(::actionLabel) ?: ""
    }

    /** The one-handed button says its state, the way the settings panel's tile does. */
    private fun actionLabel(action: Action): String {
        val label = context.getString(action.labelRes)
        if (action != Action.ONE_HAND) return label
        val value =
            when (oneHand) {
                KeyboardOneHandMode.LEFT -> R.string.ime_qs_left
                KeyboardOneHandMode.RIGHT -> R.string.ime_qs_right
                KeyboardOneHandMode.OFF -> R.string.ime_qs_off
            }
        return context.getString(R.string.ime_qs_label_value, label, context.getString(value))
    }

    override fun activateItem(index: Int) {
        notice?.let { shown ->
            shown.items.getOrNull(index)?.let(onNoticeItem)
            return
        }
        val shown = chip
        if (shown != null) {
            if (index == 0) {
                onChipPaste(shown)
            } else if (index == 1) {
                onChipDismiss(shown)
            }
            return
        }
        entries().getOrNull(index)?.let(onAction)
    }

    override fun itemAt(
        x: Float,
        y: Float,
    ): Int {
        notice?.let { shown ->
            if (y < 0f || y >= height) return -1
            return noticeLayout(shown).slots.indexOfFirst { x >= it.left && x < it.right }
        }
        chip?.let { return chipLayout(it).geometry.hit(x, y) }
        if (x < 0f || x >= width || y < 0f || y >= height) return -1
        return slots().indexOfFirst { x >= it.left && x < it.right }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = itemAt(event.x, event.y)
                if (down >= 0) onPressFeedback()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding off the entry cancels it, like any button.
                if (down >= 0 && itemAt(event.x, event.y) != down) release()
            }
            MotionEvent.ACTION_UP -> {
                val pressedIndex = down
                val hit = itemAt(event.x, event.y)
                release()
                if (pressedIndex >= 0 && hit == pressedIndex) {
                    activateItem(pressedIndex)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    /** The pressed fill is on at touch-down; after the finger leaves it fades out over 150 ms. */
    private fun release() {
        val index = down
        down = -1
        fadeAnimator?.cancel()
        fadeAnimator = null
        if (index < 0 || !ImeMotion.enabled || !isAttachedToWindow) {
            fadeIndex = -1
            fadeAlpha = 0f
            invalidate()
            return
        }
        fadeIndex = index
        fadeAlpha = 1f
        fadeAnimator =
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = FADE_MS
                addUpdateListener {
                    fadeAlpha = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            fadeIndex = -1
                            fadeAlpha = 0f
                            fadeAnimator = null
                            invalidate()
                        }
                    },
                )
                start()
            }
    }

    override fun onDetachedFromWindow() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        val DEFAULT_ACTIONS: List<Action> =
            ToolbarLayout.DEFAULT.shown
                .map { Action.valueOf(it.name) } + Action.HIDE

        private const val ICON_DP = 24f
        private const val ICON_SMALL_DP = 18f
        private const val ICON_GAP_DP = 6f

        private const val CHIP_MARGIN_DP = 8f
        private const val CHIP_INSET_DP = 6f
        private const val CROSS_DP = 12f
        private const val CHIP_ITEMS = 2
        private const val FADE_MS = 150L
    }
}

package app.songyan.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the readings and the spelling picker of a 14 / 18-key layout show while composing.
 * The nine-key layout keeps its leading rail and ignores this.
 */
enum class ReadingPlacement(
    val wire: String,
) {
    /** A row of its own above the candidate row, kept for as long as the layout is active. */
    ROW("row"),

    /** The start of the candidate row, scrolling together with the candidates. */
    INLINE("inline"),
    ;

    companion object {
        fun resolve(stored: String?): ReadingPlacement = entries.firstOrNull { it.wire == stored } ?: ROW
    }
}

/**
 * The height rule of the reading row. The row is reserved (empty when nothing is composed) whenever
 * a 14 / 18-key layout is active, so the keyboard never changes height while typing. It is added on
 * top of the key rows, which keep their own size; the row under it shrinks to its height ([bandDp]).
 *
 * A window taller than [KeyboardGeometry.SHORT_WINDOW_DP] gets [ROW_DP]. A short window (phone
 * landscape, split-screen pane) gets [SHORT_ROW_DP], but only while the whole keyboard, with the row
 * and the key rows at the pitch the window already has, stays within the share of the window the
 * short-window budget allows; otherwise the row does not fit and the readings use the inline
 * placement instead of squeezing the key rows or the candidate row.
 */
object ReadingRowBudget {
    const val ROW_DP = 36f
    const val SHORT_ROW_DP = 32f

    /** The row's height in dp, or 0 when it does not fit and the inline placement must be used. */
    fun rowDp(
        screenHeightDp: Int,
        barDp: Float,
        pitchDp: Float,
        bottomGapDp: Float,
    ): Float {
        if (screenHeightDp !in 1..KeyboardGeometry.SHORT_WINDOW_DP) return ROW_DP
        val keyboardDp = barDp + SHORT_ROW_DP + pitchDp * KeyboardDesignTokens.KEY_ROWS + bottomGapDp
        return if (keyboardDp <= KeyboardGeometry.shortKeyboardMaxDp(screenHeightDp)) SHORT_ROW_DP else 0f
    }

    /**
     * The height of the row under a reserved reading row (candidates, toolbar or autofill): no taller
     * than the reading row, so the pair reads as one two-line area and the height a full bar would
     * add goes back to the app. [rowDp] is null when no reading row is shown.
     */
    fun bandDp(
        barDp: Float,
        rowDp: Float?,
    ): Float = if (rowDp == null) barDp else min(barDp, rowDp)

    /** The placement that actually applies: the row when it was chosen and fits, the inline one otherwise. */
    fun effective(
        chosen: ReadingPlacement,
        rowDp: Float,
    ): ReadingPlacement =
        if (chosen == ReadingPlacement.ROW &&
            rowDp > 0f
        ) {
            ReadingPlacement.ROW
        } else {
            ReadingPlacement.INLINE
        }
}

/** Cell geometry shared by the reading row and the inline readings: width follows the text, never below the touch minimum. */
internal object ReadingCells {
    const val MIN_WIDTH_DP = 48f
    const val PADDING_DP = 12f
    const val START_DP = 4f

    class Result(
        val lefts: List<Float>,
        val widths: List<Float>,
        val end: Float,
    )

    fun layout(
        textWidths: List<Float>,
        start: Float,
        padding: Float,
        minWidth: Float,
    ): Result {
        val widths = textWidths.map { max(minWidth, it + padding * 2) }
        val lefts = ArrayList<Float>(widths.size)
        var left = start
        for (width in widths) {
            lefts += left
            left += width
        }
        return Result(lefts, widths, left)
    }
}

/**
 * The reading row above the candidate row: the spelling choices and readings of the running
 * composition, drawn like the nine-key rail's items. Every cell is tappable over its whole height
 * and at least 48 dp wide; more items than the row holds scroll sideways. Applied choices (a locked
 * spelling, chosen readings) are drawn selected and do nothing on tap, because Delete undoes them.
 */
internal class ReadingRowView(
    context: Context,
) : AccessibleCanvasView(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var palette = KeyboardPalette.from(context)
    private val configuration = ViewConfiguration.get(context)
    private val chip = RectF()

    /** The row's height in dp; the owner sets it from [ReadingRowBudget]. */
    var heightDp = ReadingRowBudget.ROW_DP
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var items: List<RailItem> = emptyList()
        private set

    /** A tap on an item: its index in [items] and its label as drawn. */
    var onChoice: (index: Int, label: String) -> Unit = { _, _ -> }

    /** A finger landed on a reading: the same tick a key gives at touch-down. */
    var onPressFeedback: () -> Unit = {}

    private var lefts = emptyList<Float>()
    private var widths = emptyList<Float>()
    private var contentEnd = 0f
    private var scroll = 0f
    private var down = -1
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false

    private fun applyTypeface() {
        val face = KeyboardTypefaces.of(palette.tokens.fontFamily, palette.tokens.fontWeight)
        paint.typeface = face
        textPaint.typeface = face
    }

    init {
        applyTypeface()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = context.getString(R.string.ime_reading_row)
    }

    fun setItems(value: List<RailItem>) {
        if (value == items) return
        val grew = value.size != items.size
        items = value
        importantForAccessibility =
            if (value.isEmpty()) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        if (grew || value.isEmpty()) scroll = 0f
        down = -1
        rebuild()
        invalidate()
    }

    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        applyTypeface()
        rebuild()
        invalidate()
    }

    private fun textSizePx(): Float = (palette.tokens.candidateTextSizeSp - 2f) * CandidateBarView.textScaleFor(context)

    private fun rebuild() {
        textPaint.textSize = textSizePx()
        val layout =
            ReadingCells.layout(
                items.map { textPaint.measureText(it.label) },
                ReadingCells.START_DP * density,
                ReadingCells.PADDING_DP * density,
                ReadingCells.MIN_WIDTH_DP * density,
            )
        lefts = layout.lefts
        widths = layout.widths
        contentEnd = layout.end + ReadingCells.START_DP * density
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun maxScroll(): Float = (contentEnd - width).coerceAtLeast(0f)

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (heightDp * density).toInt())
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        if (items.isEmpty()) return
        paint.textSize = textSizePx()
        paint.textAlign = Paint.Align.CENTER
        val radius = palette.tokens.candidateChipRadiusDp * density
        val inset = 4 * density
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2 + TEXT_DROP_DP * density
        canvas.save()
        canvas.translate(-scroll, 0f)
        for (i in items.indices) {
            val left = lefts[i]
            val right = left + widths[i]
            if (right < scroll || left > scroll + width) continue
            val item = items[i]
            chip.set(left + 2 * density, inset, right - 2 * density, height - inset)
            if (item.selected) {
                paint.color = palette.candidateSelectedBackground
                canvas.drawRoundRect(chip, radius, radius, paint)
            } else if (i == down) {
                paint.color = palette.pressed
                canvas.drawRoundRect(chip, radius, radius, paint)
            }
            paint.color = if (item.selected) palette.candidateSelectedText else palette.text
            canvas.drawText(item.label, (left + right) / 2, baseline, paint)
        }
        canvas.restore()
    }

    // ---- touch -----------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                dragging = false
                down = itemAt(event.x, event.y)
                if (down >= 0 && items[down].selected) down = -1
                if (down >= 0) onPressFeedback()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > configuration.scaledTouchSlop && maxScroll() > 0f) {
                    dragging = true
                    down = -1
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    scroll = (scroll + lastX - event.x).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                val pressed = down
                down = -1
                if (!dragging && pressed >= 0 && itemAt(event.x, event.y) == pressed) {
                    onChoice(pressed, items[pressed].label)
                }
                dragging = false
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                down = -1
                dragging = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- accessibility ---------------------------------------------------------------------------

    override val itemCount: Int get() = items.size

    override fun itemBounds(index: Int): Rect {
        val left = (lefts[index] - scroll).toInt()
        return Rect(left, 0, left + widths[index].toInt(), height)
    }

    override fun itemLabel(index: Int): String = readingLabel(context, items[index])

    override fun onItemAccessibilityFocused(index: Int) {
        val near = lefts.getOrNull(index) ?: return
        val far = near + widths[index]
        when {
            near - scroll < 0f -> scroll = near.coerceIn(0f, maxScroll())
            far - scroll > width -> scroll = (far - width).coerceIn(0f, maxScroll())
        }
        invalidate()
    }

    override fun activateItem(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!item.selected) onChoice(index, item.label)
    }

    override fun itemAt(
        x: Float,
        y: Float,
    ): Int {
        if (y < 0f || y > height) return -1
        val cx = x + scroll
        return lefts.indices.firstOrNull { cx >= lefts[it] && cx < lefts[it] + widths[it] } ?: -1
    }

    private companion object {
        /**
         * The text sits below the row's centre, toward the candidate row it filters, leaving a clear gap
         * above so a reading is not taken for a candidate comment (iOS `CandidateReadingRowPolicy.textDrop`).
         */
        const val TEXT_DROP_DP = 4f
    }
}

/** What TalkBack says for one reading item; shared by the row and the inline readings. */
internal fun readingLabel(
    context: Context,
    item: RailItem,
): String =
    when (item.kind) {
        RailKind.LOCKED, RailKind.PREFIX -> context.getString(R.string.ime_rail_chosen, item.label)
        RailKind.LOCK -> context.getString(R.string.ime_reading_lock, item.label)
        RailKind.READING -> context.getString(R.string.ime_reading_pick, item.label)
        RailKind.ECHO -> item.label
    }

/** Whether this layout shows readings and the spelling picker while composing (nine-key rail, or 14 / 18-key row). */
internal val KeyboardLayoutKind.showsReadings: Boolean
    get() = requiresSpellingDisplay

/** The merged-key layouts, which have no rail and so use the reading row or the inline readings. */
internal val KeyboardLayoutKind.usesReadingSurface: Boolean
    get() = this == KeyboardLayoutKind.MERGED14 || this == KeyboardLayoutKind.MERGED18

/** Re-derives the row's height for this window; the placement it implies is read from [effectivePlacement]. */
internal fun KeyboardController.recomputeReadingRow() {
    val tokens = KeyboardPalette.from(context).tokens
    readingRowDp =
        ReadingRowBudget.rowDp(
            context.resources.configuration.screenHeightDp,
            tokens.candidateBarHeightDp,
            tokens.rowHeightDp,
            tokens.keyAreaBottomGapDp + tokens.keyAreaExtraBottomDp,
        )
}

internal val KeyboardController.effectivePlacement: ReadingPlacement
    get() = ReadingRowBudget.effective(readingPlacement, readingRowDp)

/**
 * Shows the readings where the placement puts them: the reserved row (empty while idle, so the height
 * never changes), or the start of the candidate row. A dial pad has no composition and no row.
 */
internal fun KeyboardController.updateReadingSurface(
    active: InputSession?,
    dialPad: Boolean,
) {
    val merged = layoutKind.usesReadingSurface && !dialPad
    val row = merged && effectivePlacement == ReadingPlacement.ROW
    val items = if (railEligible(active)) railItems() else emptyList()
    if (row) readingRow.heightDp = readingRowDp
    val visibility = if (row) View.VISIBLE else View.GONE
    if (readingRow.visibility != visibility) readingRow.visibility = visibility
    readingRow.setItems(if (row) items else emptyList())
    candidates.underReadingRow = row
    val rowDp = if (row) readingRowDp else null
    candidates.readingRowDp = rowDp
    toolbar.readingRowDp = rowDp
    inlineSuggestions.readingRowDp = rowDp
    candidates.showReadings(searchEcho(active) ?: if (merged && !row) items else emptyList())
}

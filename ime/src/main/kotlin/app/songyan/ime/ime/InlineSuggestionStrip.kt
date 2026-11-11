package app.songyan.ime.ime

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Which of the inflated suggestion views actually took up room. A view the system never sized or
 * attached (zero width or height) is invisible, and a band showing only such views would be blank;
 * pure so the fallback to the toolbar is unit-tested.
 */
object InlineChipCheck {
    /** Indices of the views whose laid-out size is positive in both directions. */
    fun visible(sizes: List<Pair<Int, Int>>): List<Int> =
        sizes.indices.filter {
            sizes[it].first > 0 &&
                sizes[it].second > 0
        }

    /** The band keeps the strip only while at least one chip is visible; otherwise the toolbar returns. */
    fun keepsStrip(sizes: List<Pair<Int, Int>>): Boolean = visible(sizes).isNotEmpty()
}

/**
 * The row that holds the system's inline autofill suggestions in the candidate bar's idle position.
 * The views are drawn by the autofill service; this only lines them up and lets them scroll.
 */
class InlineSuggestionStrip(
    context: Context,
) : HorizontalScrollView(context),
    BarContent {
    /** Moves on with every response and field change, so views that arrive late are dropped. */
    val generation = InlineGeneration()

    private val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

    /** Called when suggestions appear or go, so the owner can swap the band's content. */
    var onChanged: () -> Unit = {}

    val hasSuggestions: Boolean get() = row.childCount > 0

    private var palette = KeyboardPalette.from(context)

    init {
        clipToPadding = true
        setBackgroundColor(palette.surface)
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        setBackgroundColor(palette.surface)
        requestLayout()
    }

    /** The reading row's height while one sits above; the strip then shrinks to it ([ReadingRowBudget.bandDp]). */
    var readingRowDp: Float? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val box: BarContentBox.Box
        get() = BarContentBox.of(palette.tokens.candidateBarHeightDp, readingRowDp)

    /** The height of the chips the system draws: the shared content box of the bar row. */
    val contentHeightDp: Float get() = box.heightDp

    /**
     * One bar row tall, like the candidates and the toolbar, so swapping them never moves the keys.
     * The padding leaves exactly the shared content box, in which the chips are centred.
     */
    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val density = resources.displayMetrics.density
        val box = box
        setPadding(0, (box.topDp * density).toInt(), 0, (box.bottomInsetDp * density).toInt())
        val height = (box.rowDp * density).toInt()
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    /** Where the chips actually sit once laid out; the box's centre while there are none. */
    override fun contentCentreY(): Float {
        val chip =
            (0 until row.childCount).map { row.getChildAt(it) }.firstOrNull { it.height > 0 }
                ?: return box.centreDp * resources.displayMetrics.density
        return row.top + chip.top + chip.height / 2f
    }

    fun show(views: List<View>) {
        handler.removeCallbacks(settle)
        // Every response gets its own "give unsized chips a width" step.
        settleStep = 0
        row.removeAllViews()
        if (views.isEmpty()) {
            onChanged()
            return
        }
        settleToken = generation.current()
        val gap = (GAP_DP * resources.displayMetrics.density).toInt()
        for (view in views) {
            // The autofill service sizes each chip to its content and hands the size over in the
            // view's layout params; the remote surface has no intrinsic size, so replacing them with
            // WRAP_CONTENT measures the chip as empty and the fallback width then cuts its text.
            val given = view.layoutParams
            val width = given?.width?.takeIf { it > 0 } ?: LayoutParams.WRAP_CONTENT
            val height = given?.height?.takeIf { it > 0 }?.coerceAtMost(boxHeightPx()) ?: LayoutParams.MATCH_PARENT
            row.addView(
                view,
                LinearLayout.LayoutParams(width, height).apply {
                    marginStart = gap
                    marginEnd = gap
                },
            )
        }
        onChanged()
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun boxHeightPx(): Int = (box.heightDp * resources.displayMetrics.density).toInt()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var settleToken = 0
    private var settleStep = 0

    /**
     * A chip the system has not sized by now is given a minimum width once; one that still has no
     * size after that is dropped, and when none is left the band goes back to the toolbar. The
     * toolbar must never be replaced by an empty strip.
     */
    private val settle =
        object : Runnable {
            override fun run() {
                if (!generation.isCurrent(settleToken) || !hasSuggestions) return
                val views = (0 until row.childCount).map { row.getChildAt(it) }
                val sizes = views.map { it.width to it.height }
                if (settleStep == 0 && InlineChipCheck.visible(sizes).size < views.size) {
                    settleStep = 1
                    val fallback = (FALLBACK_WIDTH_DP * resources.displayMetrics.density).toInt()
                    for ((i, view) in views.withIndex()) {
                        if (i !in InlineChipCheck.visible(sizes)) {
                            view.layoutParams = view.layoutParams.apply { width = fallback }
                        }
                    }
                    handler.postDelayed(this, SETTLE_MS)
                    return
                }
                val keep = InlineChipCheck.visible(sizes).toSet()
                if (keep.size == views.size) return
                if (!InlineChipCheck.keepsStrip(sizes)) {
                    clear()
                    return
                }
                views.forEachIndexed { i, view -> if (i !in keep) row.removeView(view) }
                onChanged()
            }
        }

    /** A pending settle must not run against a strip that is no longer on screen. */
    override fun onDetachedFromWindow() {
        cancelSettle()
        super.onDetachedFromWindow()
    }

    internal fun cancelSettle() {
        handler.removeCallbacks(settle)
        settleStep = 0
        generation.invalidate()
    }

    /** Drops the suggestions and anything still being inflated. */
    fun clear() {
        handler.removeCallbacks(settle)
        settleStep = 0
        generation.invalidate()
        if (!hasSuggestions) return
        row.removeAllViews()
        onChanged()
    }

    private companion object {
        const val GAP_DP = 4f
        const val FALLBACK_WIDTH_DP = 80f
        const val SETTLE_MS = 700L
    }
}

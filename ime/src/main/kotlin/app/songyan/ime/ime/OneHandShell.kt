package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.songyan.ime.candidate.KeyboardOneHandLayoutPolicy
import app.songyan.ime.candidate.KeyboardOneHandMode

/**
 * The keyboard's outer frame: the content column plus, in one-handed mode, a gutter on the free
 * side with the two controls that leave the mode.
 *
 * Only the laid-out width of the content shrinks (no scale transform), so every key keeps its real
 * touch geometry. The width plan comes from [KeyboardOneHandLayoutPolicy], which switches itself off
 * on tablets, in landscape and in narrow panes.
 */
internal class OneHandShell(
    context: Context,
    private val content: View,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val gutter = Gutter(context)

    var mode: KeyboardOneHandMode = KeyboardOneHandMode.OFF
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Called after a width change, once laid out; whether narrowing is possible may have changed. */
    var onWidthChanged: () -> Unit = {}

    /** The gutter's two actions: move the content to the other side, or restore the full width. */
    var onSwapSide: () -> Unit = {}
    var onRestore: () -> Unit = {}

    init {
        orientation = HORIZONTAL
        gutter.visibility = GONE
        gutter.onSwap = { onSwapSide() }
        gutter.onRestore = { onRestore() }
        addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(gutter, LayoutParams(0, LayoutParams.MATCH_PARENT))
    }

    /** Re-reads the colours after a theme change. */
    fun refreshAppearance(palette: KeyboardPalette) {
        gutter.palette = palette
        gutter.invalidate()
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val plan = planFor(MeasureSpec.getSize(widthSpec))
        if (plan.active) {
            val params = gutter.layoutParams as LayoutParams
            params.width = (plan.gutterWidth * density).toInt()
            gutter.layoutParams = params
            gutter.visibility = VISIBLE
            // The content keeps its place beside the gutter: gutter first when it sits on the left.
            val gutterFirst = plan.gutterOnLeft
            if ((indexOfChild(gutter) == 0) != gutterFirst) {
                removeView(gutter)
                addView(gutter, if (gutterFirst) 0 else 1, params)
            }
        } else {
            gutter.visibility = GONE
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Posted: listeners change other views, which must not happen inside this layout pass.
        if (w != oldw) post { onWidthChanged() }
    }

    /** Whether narrowing can happen on this surface at all (phone portrait); the stored mode is irrelevant. */
    fun isAvailable(): Boolean {
        val widthPx = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return planFor(widthPx, KeyboardOneHandMode.LEFT).active
    }

    private fun planFor(
        widthPx: Int,
        mode: KeyboardOneHandMode = this.mode,
    ) = KeyboardOneHandLayoutPolicy.plan(
        mode = mode,
        panelWidth = widthPx / density.toDouble(),
        isPhonePortrait =
            resources.configuration.smallestScreenWidthDp < TABLET_DP &&
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
    )

    /** The restore column: a side-swap and an expand button, each a real 48 dp view. */
    private class Gutter(
        context: Context,
    ) : LinearLayout(context) {
        var onSwap: () -> Unit = {}
        var onRestore: () -> Unit = {}
        var palette: KeyboardPalette = KeyboardPalette.from(context)
            set(value) {
                field = value
                setBackgroundColor(value.background)
                swap.palette = value
                restore.palette = value
            }
        private val swap = IconButton(context, swapSide = true, label = R.string.ime_one_hand_swap)
        private val restore = IconButton(context, swapSide = false, label = R.string.ime_one_hand_restore)

        init {
            orientation = VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(palette.background)
            val target = (TARGET_DP * resources.displayMetrics.density).toInt()
            addView(swap, LayoutParams(LayoutParams.MATCH_PARENT, target))
            addView(restore, LayoutParams(LayoutParams.MATCH_PARENT, target))
            swap.setOnClickListener { onSwap() }
            restore.setOnClickListener { onRestore() }
        }
    }

    private class IconButton(
        context: Context,
        private val swapSide: Boolean,
        label: Int,
    ) : View(context) {
        var palette: KeyboardPalette = KeyboardPalette.from(context)
            set(value) {
                field = value
                invalidate()
            }
        private val density = resources.displayMetrics.density
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        private val path = Path()

        init {
            contentDescription = context.getString(label)
            isClickable = true
            // A platform ripple keeps the touch feedback Android users expect from a button.
            val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            val styled = context.obtainStyledAttributes(attrs)
            try {
                background = styled.getDrawable(0)
            } finally {
                styled.recycle()
            }
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = palette.muted
            paint.strokeWidth = 2f * density
            val cx = width / 2f
            val cy = height / 2f
            val half = 8f * density
            val tip = 4f * density
            path.reset()
            if (swapSide) {
                path.moveTo(cx - half, cy - 3 * density)
                path.lineTo(cx + half, cy - 3 * density)
                path.moveTo(cx + half - tip, cy - 7 * density)
                path.lineTo(cx + half, cy - 3 * density)
                path.lineTo(cx + half - tip, cy + density)
                path.moveTo(cx + half, cy + 4 * density)
                path.lineTo(cx - half, cy + 4 * density)
                path.moveTo(cx - half + tip, cy)
                path.lineTo(cx - half, cy + 4 * density)
                path.lineTo(cx - half + tip, cy + 8 * density)
            } else {
                // Two outward chevrons: the content grows back to full width.
                path.moveTo(cx - half + tip, cy - 5 * density)
                path.lineTo(cx - half, cy)
                path.lineTo(cx - half + tip, cy + 5 * density)
                path.moveTo(cx + half - tip, cy - 5 * density)
                path.lineTo(cx + half, cy)
                path.lineTo(cx + half - tip, cy + 5 * density)
            }
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val TABLET_DP = 600
        const val TARGET_DP = 48f
    }
}

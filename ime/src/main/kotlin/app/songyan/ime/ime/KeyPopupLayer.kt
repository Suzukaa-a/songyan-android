package app.songyan.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * The surface the key preview, the long-press popup and the merged-key swipe letter are drawn on.
 *
 * It covers the candidate bar and the key area, above both, so the popup of a first-row key is not
 * clipped by the key area and sits above the finger. It never takes touches: the finger that opened
 * a popup keeps sending its events to the key view, which also does the selecting. Coordinates are
 * the layer's own; [KeyboardView] converts.
 */
internal class KeyPopupLayer(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var textScale = ImeText.scale(resources)
    private var palette = KeyboardPalette.from(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val clip = Path()

    private var previewLabel: String? = null
    private var previewBounds = KeyBounds(0f, 0f, 0f, 0f)
    private val timers = android.os.Handler(android.os.Looper.getMainLooper())
    private val hidePreviewTask = Runnable { clearPreview() }

    private var labels: List<String> = emptyList()
    private var popup = KeyBounds(0f, 0f, 0f, 0f)
    private var mirrored = false
    private var selected = -1
    private var previousSelected = -1
    private var selectedAt = 0L
    private var openedAt = 0L
    private var closedAt = 0L

    private var swipeLabel: String? = null
    private var swipeBounds = KeyBounds(0f, 0f, 0f, 0f)

    private var flick: FlickMorph? = null
    private var flickFrom: String? = null
    private var flickTo = ""
    private var flickBounds = KeyBounds(0f, 0f, 0f, 0f)
    private var flickTextFactor = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    /** True while an alternates popup is open (not merely fading out). */
    val alternatesOpen: Boolean get() = labels.isNotEmpty() && closedAt == 0L

    /** Whether any popup content is on screen, including one that is fading out. */
    val showing: Boolean get() = labels.isNotEmpty() || previewLabel != null || swipeLabel != null || flick != null

    /**
     * The layer only covers what the column beside it measured; it never asks for height of its own.
     * A plain view would take the whole `AT_MOST` bound, and since the keyboard is the content of a
     * wrap-content IME window, that made the window, its insets and its touchable region the full screen.
     * The frame measures its match-parent children a second time with the column's exact height.
     */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(
            getDefaultSize(0, widthMeasureSpec),
            if (MeasureSpec.getMode(heightMeasureSpec) ==
                MeasureSpec.EXACTLY
            ) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                0
            },
        )
    }

    /** Never a touch target: events fall through to the keyboard underneath. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        textScale = ImeText.scale(resources)
        invalidate()
    }

    // --- key preview ---------------------------------------------------------------------------

    fun showPreview(
        label: String,
        bounds: KeyBounds,
    ) {
        timers.removeCallbacks(hidePreviewTask)
        val old = previewBounds
        val had = previewLabel != null
        previewLabel = label
        previewBounds = bounds
        if (had) dirty(old)
        dirty(bounds)
    }

    /** Removes the preview [afterMs] after the finger lifted; a new press replaces it at once. */
    fun hidePreview(afterMs: Long) {
        if (previewLabel == null) return
        timers.removeCallbacks(hidePreviewTask)
        if (afterMs <= 0L) clearPreview() else timers.postDelayed(hidePreviewTask, afterMs)
    }

    private fun clearPreview() {
        timers.removeCallbacks(hidePreviewTask)
        if (previewLabel == null) return
        previewLabel = null
        dirty(previewBounds)
    }

    // --- long-press popup ----------------------------------------------------------------------

    fun open(
        options: List<String>,
        geometry: PopupGeometry.Alternates,
        selectedIndex: Int,
    ) {
        clearPreview()
        if (labels.isNotEmpty()) dirty(popup)
        labels = options
        popup = geometry.bounds
        mirrored = geometry.mirrored
        selected = selectedIndex
        previousSelected = -1
        selectedAt = 0L
        openedAt = SystemClock.uptimeMillis()
        closedAt = 0L
        dirty(popup)
    }

    fun select(index: Int) {
        if (index == selected || labels.isEmpty()) return
        previousSelected = selected
        selected = index
        selectedAt = SystemClock.uptimeMillis()
        dirty(popup)
    }

    /** Closes the popup: faded out over 80 ms, or at once when [animated] is false. */
    fun close(animated: Boolean) {
        if (labels.isEmpty()) return
        if (animated && ImeMotion.scale > 0f) {
            if (closedAt == 0L) closedAt = SystemClock.uptimeMillis()
            dirty(popup)
            return
        }
        clearPopup()
    }

    private fun clearPopup() {
        if (labels.isEmpty()) return
        val gone = popup
        labels = emptyList()
        selected = -1
        previousSelected = -1
        closedAt = 0L
        openedAt = 0L
        dirty(gone)
    }

    // --- merged-key swipe letter ---------------------------------------------------------------

    fun showSwipe(
        label: String,
        bounds: KeyBounds,
    ) {
        if (swipeLabel == label && swipeBounds == bounds) return
        if (swipeLabel != null) dirty(swipeBounds)
        swipeLabel = label
        swipeBounds = bounds
        dirty(bounds)
    }

    fun hideSwipe() {
        if (swipeLabel == null) return
        swipeLabel = null
        dirty(swipeBounds)
    }

    // --- flick to the corner hint --------------------------------------------------------------

    /**
     * The bubble of a key being flicked, following [morph]: the key's preview label rolls out the
     * way the finger goes while [to] rolls in behind it. Without a preview ([from] null) the bubble
     * fades in with the hint instead. It replaces the plain preview of the same key.
     */
    fun showFlick(
        morph: FlickMorph,
        from: String?,
        to: String,
        bounds: KeyBounds,
    ) {
        val textFactor = if (previewLabel != null) PREVIEW_TEXT_SCALE else 1f
        clearPreview()
        if (flick != null) dirty(flickBounds)
        flick = morph
        flickFrom = from
        flickTo = to
        flickBounds = bounds
        flickTextFactor = textFactor
        dirty(bounds)
    }

    /** The finger moved: redraw the bubble at the morph's new progress. */
    fun updateFlick() {
        if (flick != null) dirty(flickBounds)
    }

    fun hideFlick() {
        if (flick == null) return
        flick = null
        flickFrom = null
        dirty(flickBounds)
    }

    /** Drops everything at once: a new editor or a hidden keyboard starts clean. */
    fun clear() {
        clearPreview()
        clearPopup()
        hideSwipe()
        hideFlick()
    }

    // --- drawing -------------------------------------------------------------------------------

    /** Invalidates only [bounds] grown by the shadow, never the whole keyboard. */
    private fun dirty(bounds: KeyBounds) {
        val pad = (palette.tokens.popupElevationDp + 4f) * density
        invalidate(
            (bounds.left - pad).toInt(),
            (bounds.top - pad).toInt(),
            (bounds.right + pad).toInt() + 1,
            (bounds.bottom + pad).toInt() + 1,
        )
    }

    private fun frame(bounds: KeyBounds) {
        val pad = (palette.tokens.popupElevationDp + 4f) * density
        postInvalidateOnAnimation(
            (bounds.left - pad).toInt(),
            (bounds.top - pad).toInt(),
            (bounds.right + pad).toInt() + 1,
            (bounds.bottom + pad).toInt() + 1,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        previewLabel?.let { drawBubble(canvas, it, previewBounds, 1f, 1f, PREVIEW_TEXT_SCALE) }
        swipeLabel?.let { drawBubble(canvas, it, swipeBounds, 1f, 1f, 1f) }
        drawFlick(canvas, now)
        if (labels.isNotEmpty()) drawPopup(canvas, now)
    }

    private fun drawBubble(
        canvas: Canvas,
        label: String,
        bounds: KeyBounds,
        scale: Float,
        alpha: Float,
        textFactor: Float,
    ) {
        val channel = drawBubbleBox(canvas, bounds, scale, alpha)
        paint.color = ColorUtils.setAlphaComponent(palette.text, channel)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = palette.tokens.keyLabelTextSizeSp * textScale * textFactor
        canvas.drawText(label, box.centerX(), box.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun drawFlick(
        canvas: Canvas,
        now: Long,
    ) {
        val morph = flick ?: return
        val motion = ImeMotion.scale
        val progress = morph.value(now, motion)
        if (morph.animating(now, motion)) frame(flickBounds)
        val from = flickFrom
        val alpha = if (from == null) progress else 1f
        if (alpha <= 0f) return
        val channel = drawBubbleBox(canvas, flickBounds, 1f, alpha)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = palette.tokens.keyLabelTextSizeSp * textScale * flickTextFactor
        val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2
        // The content scrolls with the finger: up for an upward flick, down for a downward one.
        val travel = box.height() * if (morph.up) -1f else 1f
        canvas.save()
        canvas.clipRect(box)
        if (from != null) {
            paint.color = ColorUtils.setAlphaComponent(palette.text, (channel * (1f - progress)).toInt())
            canvas.drawText(from, box.centerX(), baseline + travel * progress, paint)
        }
        paint.color = ColorUtils.setAlphaComponent(palette.text, (channel * progress).toInt())
        canvas.drawText(flickTo, box.centerX(), baseline - travel * (1f - progress), paint)
        canvas.restore()
    }

    /** The bubble's shadowed body at [bounds] (left in [box]); returns the alpha channel it used. */
    private fun drawBubbleBox(
        canvas: Canvas,
        bounds: KeyBounds,
        scale: Float,
        alpha: Float,
    ): Int {
        val metrics = palette.tokens
        val channel = (255 * alpha).toInt().coerceIn(0, 255)
        scaled(bounds, scale)
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(palette.popupBackground, channel)
        paint.setShadowLayer(
            metrics.popupElevationDp * density,
            0f,
            2 * density,
            ColorUtils.setAlphaComponent(palette.popupShadow, (Color.alpha(palette.popupShadow) * alpha).toInt()),
        )
        val radius = metrics.popupRadiusDp * density
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.clearShadowLayer()
        return channel
    }

    private fun drawPopup(
        canvas: Canvas,
        now: Long,
    ) {
        val metrics = palette.tokens
        val motion = ImeMotion.scale
        val closing = closedAt > 0L
        val scale: Float
        val alpha: Float
        var animating = false
        if (closing) {
            val progress = KeyMotion.popupCloseProgress(now, closedAt, motion)
            if (progress >= 1f || motion <= 0f) {
                clearPopup()
                return
            }
            scale = KeyMotion.popupCloseScale(progress)
            alpha = KeyMotion.popupCloseAlpha(progress)
            animating = true
        } else {
            val value = KeyMotion.popupOpenValue(now, openedAt, motion)
            scale = KeyMotion.popupScale(value)
            alpha = KeyMotion.popupAlpha(value)
            animating = KeyMotion.popupOpening(now, openedAt, motion)
        }
        val channel = (255 * alpha).toInt().coerceIn(0, 255)
        scaled(popup, scale)
        val radius = metrics.popupRadiusDp * density
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(palette.popupBackground, channel)
        paint.setShadowLayer(
            metrics.popupElevationDp * density,
            0f,
            2 * density,
            ColorUtils.setAlphaComponent(palette.popupShadow, (Color.alpha(palette.popupShadow) * alpha).toInt()),
        )
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.clearShadowLayer()
        val cell = box.width() / labels.size
        val fade = KeyMotion.highlightProgress(now, selectedAt, motion)
        if (fade < 1f) animating = true
        // The highlight is clipped to the popup's rounded outline so the end cells keep its corners.
        canvas.save()
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        highlight(canvas, selected, cell, channel, fade)
        if (previousSelected >= 0) highlight(canvas, previousSelected, cell, channel, 1f - fade)
        canvas.restore()
        paint.color = ColorUtils.setAlphaComponent(palette.text, channel)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = metrics.keyLabelTextSizeSp * textScale
        for (cellIndex in labels.indices) {
            val option = if (mirrored) labels.size - 1 - cellIndex else cellIndex
            canvas.drawText(
                labels[option],
                box.left + cell * (cellIndex + .5f),
                box.centerY() - (paint.ascent() + paint.descent()) / 2,
                paint,
            )
        }
        if (animating) frame(popup)
    }

    private fun highlight(
        canvas: Canvas,
        option: Int,
        cell: Float,
        channel: Int,
        strength: Float,
    ) {
        if (option !in labels.indices || strength <= 0f) return
        val cellIndex = if (mirrored) labels.size - 1 - option else option
        paint.color = ColorUtils.setAlphaComponent(palette.popupSelected, (channel * strength).toInt())
        canvas.drawRect(box.left + cell * cellIndex, box.top, box.left + cell * (cellIndex + 1), box.bottom, paint)
    }

    /** [box] set to [bounds] scaled around its centre; drawing only. */
    private fun scaled(
        bounds: KeyBounds,
        scale: Float,
    ) {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val halfWidth = (bounds.right - bounds.left) / 2 * scale
        val halfHeight = (bounds.bottom - bounds.top) / 2 * scale
        box.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
    }

    private companion object {
        /** The preview enlarges the label relative to the keycap's own size. */
        const val PREVIEW_TEXT_SCALE = 1.4f
    }
}

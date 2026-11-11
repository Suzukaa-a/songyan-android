package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import app.songyan.ime.appearance.BackgroundFill

/**
 * The whole keyboard window's surface: the panel colour, then the skin's gradient or picture over the
 * candidate bar, the keys, the one-handed gutter and the navigation bar area alike. The views inside
 * stay see-through while the skin has a background, so the picture is one piece, the way the theme
 * editor previews it.
 */
class KeyboardBackgroundDrawable(
    palette: KeyboardPalette,
) : Drawable() {
    var palette: KeyboardPalette = palette
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidateSelf()
        }

    /**
     * Whether the bottom of what is drawn is light: the navigation bar's icons sit on it, and a picture
     * or gradient can be dark under a light palette or the other way round.
     */
    var bottomIsLight: Boolean = palette.isLight
        private set

    /** Called on the main thread when [bottomIsLight] changes, such as when a picture finishes decoding. */
    var onBottomToneChanged: (() -> Unit)? = null

    private val fillPaint = Paint()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageMatrix = Matrix()
    private var image: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    private fun rebuild() {
        val bounds = bounds
        fillPaint.shader = null
        image = null
        val fill = palette.tokens.backgroundFill
        if (fill !is BackgroundFill.Image) BackgroundImageLoader.release()
        when (fill) {
            null -> setTone(isLight(palette.background))
            is BackgroundFill.Solid -> {
                fillPaint.color = fill.color
                setTone(isLight(over(fill.color)))
            }
            is BackgroundFill.Gradient -> {
                val endX = if (fill.direction == BackgroundFill.Direction.VERTICAL) bounds.left else bounds.right
                val endY = if (fill.direction == BackgroundFill.Direction.HORIZONTAL) bounds.top else bounds.bottom
                fillPaint.color = 0xFF000000.toInt()
                fillPaint.shader =
                    LinearGradient(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        endX.toFloat(),
                        endY.toFloat(),
                        fill.colors.toIntArray(),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                // A vertical gradient ends at the bottom; across, the bar sees every stop.
                val bottom =
                    if (fill.direction ==
                        BackgroundFill.Direction.VERTICAL
                    ) {
                        fill.colors.takeLast(1)
                    } else {
                        fill.colors
                    }
                setTone(bottom.map { luminance(over(it)) }.average() > LIGHT_LUMINANCE)
            }
            is BackgroundFill.Image -> {
                setTone(isLight(palette.background))
                placeImage(fill)
            }
        }
    }

    /** Fits the decoded picture to cover the bounds, cropped by its alignment; the panel colour shows until then. */
    private fun placeImage(fill: BackgroundFill.Image) {
        val bounds = bounds
        val bitmap =
            BackgroundImageLoader.request(fill, bounds.width(), bounds.height()) {
                if (palette.tokens.backgroundFill == fill) {
                    placeImage(fill)
                    invalidateSelf()
                }
            }
        image = bitmap
        if (bitmap == null) return
        val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val left = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
        val spare = bounds.height() - bitmap.height * scale
        val top =
            bounds.top +
                when (fill.align) {
                    BackgroundFill.Align.TOP -> 0f
                    BackgroundFill.Align.CENTER -> spare / 2f
                    BackgroundFill.Align.BOTTOM -> spare
                }
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(left, top)
        setTone(bottomOf(bitmap, scale, left, top) > LIGHT_LUMINANCE)
    }

    /** Mean luminance of the picture across the bottom tenth of the bounds, sampled on a coarse grid. */
    private fun bottomOf(
        bitmap: Bitmap,
        scale: Float,
        left: Float,
        top: Float,
    ): Double {
        val bounds = bounds
        var sum = 0.0
        var count = 0
        for (row in 0 until TONE_ROWS) {
            val y = bounds.bottom - bounds.height() * BOTTOM_SHARE * (row + 0.5f) / TONE_ROWS
            for (column in 0 until TONE_COLUMNS) {
                val x = bounds.left + bounds.width() * (column + 0.5f) / TONE_COLUMNS
                val px = ((x - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
                val py = ((y - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
                sum += luminance(over(bitmap.getPixel(px, py)))
                count++
            }
        }
        return sum / count
    }

    private fun setTone(light: Boolean) {
        if (light == bottomIsLight) return
        bottomIsLight = light
        onBottomToneChanged?.invoke()
    }

    /** [color] composited over the panel colour, which is always painted first. */
    private fun over(color: Int): Int = ColorUtils.compositeColors(color, palette.background)

    private fun isLight(color: Int): Boolean = luminance(color) > LIGHT_LUMINANCE

    private fun luminance(color: Int): Double = ColorUtils.calculateLuminance(color)

    override fun draw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        when (palette.tokens.backgroundFill) {
            null -> Unit
            is BackgroundFill.Image -> image?.let { canvas.drawBitmap(it, imageMatrix, imagePaint) }
            else -> canvas.drawRect(bounds, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val LIGHT_LUMINANCE = 0.5
        const val BOTTOM_SHARE = 0.1f
        const val TONE_ROWS = 3
        const val TONE_COLUMNS = 8
    }
}

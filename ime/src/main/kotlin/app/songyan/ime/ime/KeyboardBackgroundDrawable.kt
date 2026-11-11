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
            null -> Unit
            is BackgroundFill.Solid -> fillPaint.color = fill.color
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
            }
            is BackgroundFill.Image -> placeImage(fill)
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
    }

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
}

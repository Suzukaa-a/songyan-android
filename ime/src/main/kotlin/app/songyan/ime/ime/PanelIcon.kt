package app.songyan.ime.ime

import android.content.Context
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/**
 * A vector icon drawn from a canvas-based view. The drawable is inflated once and re-tinted per
 * draw, because inflating inside `onDraw` would allocate on every frame.
 */
class PanelIcon(
    context: Context,
    @DrawableRes res: Int,
) {
    private val drawable =
        DrawableCompat.wrap(checkNotNull(ContextCompat.getDrawable(context, res)).mutate())

    /** Draws the icon centred on ([centerX], [centerY]) in a square of [sizePx]. */
    fun draw(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        sizePx: Float,
        color: Int,
    ) {
        val half = (sizePx / 2f).toInt()
        DrawableCompat.setTint(drawable, color)
        drawable.setBounds(
            centerX.toInt() - half,
            centerY.toInt() - half,
            centerX.toInt() + half,
            centerY.toInt() + half,
        )
        drawable.draw(canvas)
    }
}

package app.songyan.ime.ime

import android.graphics.Paint
import android.graphics.Rect

/**
 * The one vertical standard for what the keyboard's top band draws: candidates, the idle toolbar's
 * icons, its paste chip and its notices all centre on the same line, so switching between them never
 * makes the content jump.
 * The centre line itself comes from [BarContentBox]; this adds the text baseline on it.
 */
internal object BarContentLine {
    /**
     * The baseline that centres a Han character's ink on [centerY]. Centring on the font's ascent and
     * descent reserves room for Latin descenders that Han glyphs don't have, so text sat visibly high;
     * the ink of 中 is the reference for every label, so a row keeps one baseline.
     */
    fun hanCentredBaseline(
        paint: Paint,
        centerY: Float,
    ): Float {
        paint.getTextBounds(HAN_REFERENCE, 0, HAN_REFERENCE.length, hanInk)
        return centerY - hanInk.exactCenterY()
    }

    private const val HAN_REFERENCE = "中"

    // Drawing happens on the main thread only.
    private val hanInk = Rect()
}

package app.songyan.ime.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import app.songyan.ime.ime.KeyboardView.Companion.CURSOR_CUE_INSET_DP
import app.songyan.ime.ime.KeyboardView.Companion.CURSOR_CUE_STROKE_DP
import app.songyan.ime.ime.KeyboardView.Companion.HINT_ALPHA
import app.songyan.ime.ime.KeyboardView.Companion.ICON_STROKE_WIDTH_DP
import app.songyan.ime.ime.KeyboardView.Companion.SYLLABLE_HINT_ALPHA
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey

/** Scales [box] around its centre; drawing only, so no touch target moves. */
internal fun KeyboardView.scaleAroundCenter(
    box: RectF,
    scale: Float,
) {
    val cx = box.centerX()
    val cy = box.centerY()
    val halfWidth = box.width() / 2 * scale
    val halfHeight = box.height() / 2 * scale
    box.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
}

/** The label of one key at its pre-fitted size; nothing is measured while drawing. */
internal fun KeyboardView.drawKeyLabel(
    canvas: Canvas,
    index: Int,
    box: RectF,
    color: Int,
) {
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = labelSizes.getOrElse(index) { metrics.keyLabelTextSizeSp * textScale }
    val inkX = labelInkX.getOrElse(index) { Float.NaN }
    if (!inkX.isNaN()) {
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(labels[index], box.centerX() - inkX, box.centerY() - labelInkY[index], paint)
        return
    }
    canvas.drawText(
        labels[index],
        box.centerX(),
        box.centerY() - (paint.ascent() + paint.descent()) / 2,
        paint,
    )
}

/**
 * The 中/英 key: both names always drawn, the current side in the text colour and the other at
 * 60%, so the key says where you are and what a tap does without changing its face.
 */
internal fun KeyboardView.drawLanguageLabel(
    canvas: Canvas,
    index: Int,
    box: RectF,
    color: Int,
) {
    val label = labels[index]
    val slash = label.indexOf('/')
    val first = label.substring(0, slash)
    val second = label.substring(slash + 1)
    val letters = faces.page == null
    val firstActive = if (letters) !faces.english else !faces.symbolMarksAscii
    val dim = ColorUtils.setAlphaComponent(color, HINT_ALPHA)
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.LEFT
    paint.textSize = labelSizes.getOrElse(index) { metrics.languageKeyTextSizeSp * textScale }
    val firstWidth = paint.measureText(first)
    val slashWidth = paint.measureText("/")
    val total = firstWidth + slashWidth + paint.measureText(second)
    var x = box.centerX() - total / 2
    val y = box.centerY() - (paint.ascent() + paint.descent()) / 2
    paint.color = if (firstActive) color else dim
    canvas.drawText(first, x, y, paint)
    x += firstWidth
    paint.color = dim
    canvas.drawText("/", x, y, paint)
    x += slashWidth
    paint.color = if (firstActive) dim else color
    canvas.drawText(second, x, y, paint)
}

/** The role-defined label size before fitting: every key class has one predictable size. */
internal fun KeyboardView.labelTextSizeSp(
    key: LayoutKey,
    label: String,
): Float =
    when (key.action.type) {
        LayoutActionKind.SPACE -> metrics.spaceKeyTextSizeSp
        LayoutActionKind.LANGUAGE_SWITCH -> metrics.languageKeyTextSizeSp
        LayoutActionKind.PAGE_SWITCH, LayoutActionKind.LAYOUT_PAGE_SWITCH -> metrics.pageKeyTextSizeSp
        LayoutActionKind.ENTER -> metrics.enterKeyTextSizeSp
        LayoutActionKind.SHIFT,
        LayoutActionKind.BACKSPACE,
        -> metrics.keyLabelSmallTextSizeSp
        LayoutActionKind.CODE, LayoutActionKind.LITERAL ->
            // One size per key class: a merged two-letter key reads at the same size as a
            // single letter, so a row never mixes sizes.
            if (isPhoneDigitGroup(key, label)) metrics.phoneDigitTextSizeSp else metrics.keyLabelTextSizeSp
        // The twelve-key surface's kana faces and its arrow-only caret key read as keycap text.
        LayoutActionKind.KANA, LayoutActionKind.KANA_MODIFIER, LayoutActionKind.CURSOR_LEFT ->
            metrics.keyLabelTextSizeSp
        else -> metrics.keyLabelSmallTextSizeSp
    }

/** A nine-key digit group ("ABC" on the 2 key) is one compact label, not a letter keycap. */
internal fun KeyboardView.isPhoneDigitGroup(
    key: LayoutKey,
    label: String,
): Boolean = faces.phone && key.action.type == LayoutActionKind.CODE && label.length > 2 && label.all { it.isLetter() }

/** The corner hint sits inside the keycap, top-right, never over the key's own label. */
internal fun KeyboardView.drawHint(
    canvas: Canvas,
    index: Int,
    hint: String,
    box: RectF,
) {
    val inset = metrics.secondaryLabelInsetDp * density
    paint.style = Paint.Style.FILL
    paint.color = ColorUtils.setAlphaComponent(palette.muted, HINT_ALPHA)
    paint.textAlign = Paint.Align.RIGHT
    paint.textSize = hintSizes.getOrElse(index) { metrics.keySecondaryLabelTextSizeSp * textScale }
    val top = box.top + inset - paint.ascent()
    val cut = hintBreaks.getOrElse(index) { -1 }
    // The hint never paints outside its own keycap, whatever its length.
    canvas.save()
    canvas.clipRect(box)
    if (cut in 1 until hint.length) {
        canvas.drawText(hint, 0, cut, box.right - inset, top, paint)
        canvas.drawText(hint, cut, hint.length, box.right - inset, top + paint.fontSpacing * 0.9f, paint)
    } else {
        canvas.drawText(hint, box.right - inset, top, paint)
    }
    canvas.restore()
}

/** A merged key's letters, each centred over its own shuangpin hint column. */
internal fun KeyboardView.drawSpreadLetters(
    canvas: Canvas,
    index: Int,
    box: RectF,
    color: Int,
) {
    val label = labels[index]
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = labelSizes.getOrElse(index) { metrics.keyLabelTextSizeSp * textScale }
    val y = box.centerY() - (paint.ascent() + paint.descent()) / 2
    val width = box.width() / label.length
    for (column in label.indices) {
        canvas.drawText(label, column, column + 1, box.left + width * (column + 0.5f), y, paint)
    }
}

/** The shuangpin hint line under a letter: one centred column per letter on the key, clipped to the cap. */
internal fun KeyboardView.drawSyllableHint(
    canvas: Canvas,
    line: SyllableHintLine,
    box: RectF,
) {
    paint.style = Paint.Style.FILL
    paint.color = ColorUtils.setAlphaComponent(palette.muted, SYLLABLE_HINT_ALPHA)
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = line.textSize
    val bottom = box.bottom - metrics.secondaryLabelInsetDp * density - paint.descent()
    val spacing = paint.fontSpacing * 0.9f
    val width = box.width() / line.columns.size
    canvas.save()
    canvas.clipRect(box)
    for ((index, text) in line.columns.withIndex()) {
        if (text == null) continue
        val x = box.left + width * (index + 0.5f)
        val cut = line.breaks[index]
        if (cut > 0) {
            canvas.drawText(text, 0, cut, x, bottom - spacing, paint)
            canvas.drawText(text, cut, text.length, x, bottom, paint)
        } else {
            canvas.drawText(text, x, bottom, paint)
        }
    }
    canvas.restore()
}

/**
 * The control key outlines as dp offsets from the key's centre, x then y in turn. Pure numbers so
 * their direction and balance are unit-tested.
 */
internal object ControlIcons {
    private const val SHIFT_HALF_WIDTH = 8f
    private const val SHIFT_HEIGHT = 8.5f
    private const val SHIFT_STEM = 0.42f
    private const val SHIFT_FOOT = 0.72f

    /** Centres the arrow (tip to foot) on the key; the Caps Lock bar hangs below without moving it. */
    private const val SHIFT_TOP = -(SHIFT_HEIGHT + SHIFT_HEIGHT * SHIFT_FOOT) / 2

    val shift =
        floatArrayOf(
            0f,
            SHIFT_TOP,
            -SHIFT_HALF_WIDTH,
            SHIFT_TOP + SHIFT_HEIGHT,
            -SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_TOP + SHIFT_HEIGHT,
            -SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_TOP + SHIFT_HEIGHT * (1 + SHIFT_FOOT),
            SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_TOP + SHIFT_HEIGHT * (1 + SHIFT_FOOT),
            SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_TOP + SHIFT_HEIGHT,
            SHIFT_HALF_WIDTH,
            SHIFT_TOP + SHIFT_HEIGHT,
        )

    /** The Caps Lock bar: left x, right x, y. */
    val capsLockBar =
        floatArrayOf(
            -SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_HALF_WIDTH * SHIFT_STEM,
            SHIFT_TOP + SHIFT_HEIGHT * (1 + SHIFT_FOOT) + SHIFT_HEIGHT * 0.33f,
        )

    private const val BACK_HALF_WIDTH = 9f
    private const val BACK_HEIGHT = 6.5f

    /** Backspace points left, toward the text it deletes, like the system ⌫. */
    val backspace =
        floatArrayOf(
            -BACK_HALF_WIDTH,
            0f,
            -BACK_HALF_WIDTH * 0.55f,
            -BACK_HEIGHT,
            BACK_HALF_WIDTH * 0.8f,
            -BACK_HEIGHT,
            BACK_HALF_WIDTH * 0.8f,
            BACK_HEIGHT,
            -BACK_HALF_WIDTH * 0.55f,
            BACK_HEIGHT,
        )

    private const val CROSS = 2.6f

    /** The two strokes of the cross inside the Backspace body: x1, y1, x2, y2 each. */
    val backspaceCross =
        floatArrayOf(
            -CROSS * 0.85f,
            -CROSS,
            CROSS * 0.85f,
            CROSS,
            CROSS * 0.85f,
            -CROSS,
            -CROSS * 0.85f,
            CROSS,
        ).also { cross -> for (i in cross.indices step 2) cross[i] += BACK_HALF_WIDTH * 0.12f }
}

/**
 * The Shift key's arrow, drawn instead of a font glyph so it stays crisp at every size. It is
 * outlined with the same stroke as Backspace so both control icons carry one weight.
 */
internal fun KeyboardView.drawShiftIcon(
    canvas: Canvas,
    box: RectF,
    color: Int,
) {
    strokeOutline(canvas, box, color, ControlIcons.shift)
    if (capsLocked) {
        val bar = ControlIcons.capsLockBar
        val y = box.centerY() + bar[2] * density
        canvas.drawLine(box.centerX() + bar[0] * density, y, box.centerX() + bar[1] * density, y, iconPaint)
    }
    iconPaint.style = Paint.Style.FILL
}

/** The Backspace key's outlined eraser, drawn instead of a font glyph. */
internal fun KeyboardView.drawBackspaceIcon(
    canvas: Canvas,
    box: RectF,
    color: Int,
) {
    strokeOutline(canvas, box, color, ControlIcons.backspace)
    val cross = ControlIcons.backspaceCross
    for (i in cross.indices step 4) {
        canvas.drawLine(
            box.centerX() + cross[i] * density,
            box.centerY() + cross[i + 1] * density,
            box.centerX() + cross[i + 2] * density,
            box.centerY() + cross[i + 3] * density,
            iconPaint,
        )
    }
    iconPaint.style = Paint.Style.FILL
}

/** The input-method switch key's globe: the toolbar's own icon, so both switches look alike. */
internal fun KeyboardView.drawGlobeIcon(
    canvas: Canvas,
    box: RectF,
    color: Int,
) {
    val drawable = globeIcon ?: return
    drawable.setTint(color)
    val half = GLOBE_ICON_DP * density / 2
    drawable.setBounds(
        (box.centerX() - half).toInt(),
        (box.centerY() - half).toInt(),
        (box.centerX() + half).toInt(),
        (box.centerY() + half).toInt(),
    )
    drawable.draw(canvas)
}

private const val GLOBE_ICON_DP = 22f

private fun KeyboardView.strokeOutline(
    canvas: Canvas,
    box: RectF,
    color: Int,
    points: FloatArray,
) {
    iconPaint.color = color
    iconPaint.style = Paint.Style.STROKE
    iconPaint.strokeWidth = ICON_STROKE_WIDTH_DP * density
    iconPaint.strokeJoin = Paint.Join.ROUND
    iconPaint.strokeCap = Paint.Cap.ROUND
    iconPath.reset()
    iconPath.moveTo(box.centerX() + points[0] * density, box.centerY() + points[1] * density)
    for (i in 2 until points.size step 2) {
        iconPath.lineTo(box.centerX() + points[i] * density, box.centerY() + points[i + 1] * density)
    }
    iconPath.close()
    canvas.drawPath(iconPath, iconPaint)
}

/** A short accent outline on the space bar while a space hold is moving the caret. */
internal fun KeyboardView.drawCursorCue(canvas: Canvas) {
    if (!cursorDragging) return
    for (i in keys.indices) {
        if (keys[i].action.type != LayoutActionKind.SPACE) continue
        val box = bounds.getOrNull(i) ?: return
        drawBounds.set(
            box.left + insetX + CURSOR_CUE_INSET_DP * density,
            box.top + insetY + CURSOR_CUE_INSET_DP * density,
            box.right - insetX - CURSOR_CUE_INSET_DP * density,
            box.bottom - insetY - CURSOR_CUE_INSET_DP * density,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = CURSOR_CUE_STROKE_DP * density
        paint.color = palette.accent
        canvas.drawRoundRect(drawBounds, metrics.keyRadiusDp * density, metrics.keyRadiusDp * density, paint)
        paint.style = Paint.Style.FILL
        return
    }
}

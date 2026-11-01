package app.songyan.ime.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import androidx.core.content.ContextCompat
import app.songyan.ime.ime.CandidateBarView.Companion.FADE_MS
import app.songyan.ime.ime.CandidateBarView.Control
import app.songyan.ime.ime.CandidateBarView.Entry
import kotlin.math.max
import kotlin.math.min

/** A message in an otherwise empty row (preparing, unavailable). */
internal fun CandidateBarView.drawStatus(canvas: Canvas) {
    val text = status ?: return
    if (snapshot.candidates.isNotEmpty()) return
    paint.color = palette.muted
    paint.textSize = metrics.pinyinTextSizeSp * textScale
    paint.textAlign = Paint.Align.LEFT
    canvas.drawText(text, startPaddingPx, barPx / 2 - (paint.ascent() + paint.descent()) / 2, paint)
}

internal fun CandidateBarView.drawEntries(canvas: Canvas) {
    // The strip's viewport starts at the view's left edge; the grid's at its top.
    val origin = if (expanded) 0f else viewport.left.toFloat()
    val visibleStart = scroll + origin
    val visibleEnd = visibleStart + viewportExtent()
    // Only the cells on screen are visited, however many pages the list has read.
    val count = minOf(entries.size, cells.size)
    var i = firstCellReaching(visibleStart)
    while (i < count) {
        val box = cells[i]
        if ((if (expanded) box.top else box.left) > visibleEnd) break
        drawChip(canvas, i)
        i++
    }
}

/**
 * The inline readings' section: its own clip and scroll, a fade at its end while more readings
 * follow, and the hairline fixed at its end, where the candidates begin.
 */
internal fun CandidateBarView.drawReadingSection(canvas: Canvas) {
    if (readingCells.isEmpty() || readingViewport.isEmpty) return
    val section = readingViewport.width().toFloat()
    val checkpoint = canvas.save()
    canvas.clipRect(readingViewport)
    canvas.translate(-readingScroll, 0f)
    drawReadings(canvas, readingScroll, readingScroll + section)
    canvas.restoreToCount(checkpoint)
    if (readingScroll < readingMaxScroll - 0.5f) drawFade(canvas, readingViewport.right.toFloat())
    val drop = candidateDropDp() * density
    val x = readingViewport.right.toFloat()
    paint.color = palette.divider
    paint.strokeWidth = density
    canvas.drawLine(x, 12 * density + drop, x, height - 12 * density + drop, paint)
}

/** The inline readings: drawn like chips, applied choices selected. */
internal fun CandidateBarView.drawReadings(
    canvas: Canvas,
    visibleStart: Float,
    visibleEnd: Float,
) {
    if (readingCells.isEmpty()) return
    val radius = metrics.candidateChipRadiusDp * density
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = metrics.candidateTextSizeSp * textScale
    val drop = candidateDropDp() * density
    for (i in readingCells.indices) {
        val box = readingCells[i]
        if (box.right < visibleStart || box.left > visibleEnd) continue
        val item = readings[i]
        // The same pill as a candidate's, so a chosen reading and the highlight look alike.
        chipBounds.set(
            box.left + chipGapPx / 2,
            box.top + 5 * density,
            box.right - chipGapPx / 2,
            box.bottom - 5 * density,
        )
        chipBounds.offset(0f, drop)
        if (item.selected) {
            paint.color = palette.candidateSelectedBackground
            canvas.drawRoundRect(chipBounds, radius, radius, paint)
        } else if (i == downReading) {
            paint.color = palette.pressed
            canvas.drawRoundRect(chipBounds, radius, radius, paint)
        }
        paint.color = if (item.selected) palette.candidateSelectedText else palette.text
        // Readings share the candidates' baseline, so the row reads as one line of text.
        canvas.drawText(
            item.label,
            box.centerX(),
            BarContentLine.hanCentredBaseline(paint, box.centerY()) + drop,
            paint,
        )
    }
    paint.textAlign = Paint.Align.LEFT
}

internal fun CandidateBarView.drawChip(
    canvas: Canvas,
    index: Int,
) {
    val box = cells[index]
    val entry = entries[index]
    val highlighted = highlightedAbsolute >= 0 && entry.absoluteIndex == highlightedAbsolute
    val pressedNow = index == down
    val fading = index == fadeCell && fadeAlpha > 0f
    val radius = metrics.candidateChipRadiusDp * density
    val inset = if (expanded) chipGapPx / 2 else 5 * density
    chipBounds.set(box.left + chipGapPx / 2, box.top + inset, box.right - chipGapPx / 2, box.bottom - inset)
    // The chip follows the text drop so the label stays centred in it.
    chipBounds.offset(0f, candidateDropDp() * density)
    if (highlighted) {
        paint.color = palette.candidateSelectedBackground
        canvas.drawRoundRect(chipBounds, radius, radius, paint)
    }
    if (pressedNow || fading) {
        paint.color = palette.pressed
        val base = paint.alpha
        // Pressed is on at once; it only fades out after the finger lifts.
        if (!pressedNow) paint.alpha = (base * fadeAlpha).toInt()
        canvas.drawRoundRect(chipBounds, radius, radius, paint)
        paint.alpha = base
    }
    val checkpoint = canvas.save()
    canvas.clipRect(box)
    drawCandidateText(canvas, box, entry, if (highlighted) palette.candidateSelectedText else palette.text)
    canvas.restoreToCount(checkpoint)
    paint.textAlign = Paint.Align.LEFT
}

/**
 * One candidate: its text with the reading comment right behind it, centred in the cell. A text
 * is cut only when the cell was capped to the row's width, and then at the cell's real edge; the
 * measure that sized the cell is the same one that draws the text, so a candidate that fits is
 * never ellipsized.
 */
internal fun CandidateBarView.drawCandidateText(
    canvas: Canvas,
    box: RectF,
    entry: Entry,
    textColor: Int,
) {
    paint.textAlign = Paint.Align.LEFT
    paint.textSize = metrics.candidateTextSizeSp * textScale
    paint.color = textColor
    // Measured once when the entry joined the list, with this size and the paint's metrics.
    val textWidth = entry.textWidth
    val available = box.width() - chipPaddingPx * 2
    val baseline = BarContentLine.hanCentredBaseline(paint, box.centerY()) + candidateDropDp() * density
    if (textWidth > available + 0.5f) {
        textPaint.textSize = paint.textSize
        val fitted = TextUtils.ellipsize(entry.text, textPaint, available, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, box.left + chipPaddingPx, baseline, paint)
        return
    }
    val commentSize = metrics.candidateCommentTextSizeSp * textScale
    val commentWidth = entry.commentWidth
    val groupGap = if (entry.comment.isEmpty()) 0f else chipGapPx / 2
    val total = textWidth + groupGap + commentWidth
    val x = max(box.left + chipPaddingPx, box.centerX() - total / 2)
    paint.textSize = metrics.candidateTextSizeSp * textScale
    canvas.drawText(entry.text, x, baseline, paint)
    if (entry.comment.isEmpty()) return
    paint.textSize = commentSize
    paint.color = if (textColor == palette.candidateSelectedText) textColor else palette.muted
    val commentBaseline = BarContentLine.hanCentredBaseline(paint, box.centerY()) + candidateDropDp() * density
    canvas.drawText(entry.comment, x + textWidth + groupGap, commentBaseline, paint)
}

/** How far collapsed candidate text sits below its row's centre; see [BarContentLine]. */
internal fun CandidateBarView.candidateDropDp(): Float =
    when {
        // Expanded cells are a grid of their own; their text stays centred in each cell.
        expanded -> 0f
        else -> BarContentBox.dropDp(underReadingRow)
    }

internal fun CandidateBarView.drawControls(canvas: Canvas) {
    if (expanded && controlCells.isNotEmpty()) {
        // A hairline sets the fixed column apart from the scrolling grid.
        paint.color = palette.divider
        paint.strokeWidth = density
        canvas.drawLine(viewport.right.toFloat(), 0f, viewport.right.toFloat(), height.toFloat(), paint)
    }
    // The controls drop with the candidate text so the expand control lines up with it.
    val checkpoint = canvas.save()
    canvas.translate(0f, candidateDropDp() * density)
    for ((i, control) in controls.withIndex()) {
        val box = controlCells.getOrNull(i) ?: continue
        val pressedNow = downControl == i
        val fading = fadeControl == i && fadeAlpha > 0f
        if (pressedNow || fading) {
            val radius = metrics.candidateChipRadiusDp * density
            chipBounds.set(
                box.left + 4 * density,
                box.top + 5 * density,
                box.right - 4 * density,
                box.bottom - 5 * density,
            )
            paint.color = palette.pressed
            val base = paint.alpha
            if (!pressedNow) paint.alpha = (base * fadeAlpha).toInt()
            canvas.drawRoundRect(chipBounds, radius, radius, paint)
            paint.alpha = base
        }
        drawControlIcon(canvas, box, control)
    }
    canvas.restoreToCount(checkpoint)
}

internal fun CandidateBarView.drawControlIcon(
    canvas: Canvas,
    box: Rect,
    control: Control,
) {
    if (control == Control.EXPAND) {
        paint.color = palette.muted
        drawPanelIcon(canvas, box)
        return
    }
    val drawable = icon(control.iconRes) ?: return
    drawable.setTint(palette.muted)
    val half = iconPx / 2
    drawable.setBounds(box.centerX() - half, box.centerY() - half, box.centerX() + half, box.centerY() + half)
    drawable.draw(canvas)
}

internal fun CandidateBarView.icon(res: Int): Drawable? =
    icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() ?: return null }

/** Three columns by two rows of dots: the whole candidate page. */
internal fun CandidateBarView.drawPanelIcon(
    canvas: Canvas,
    box: Rect,
) {
    val dot = 1.5f * density
    val gap = 4.5f * density
    for (row in 0..1) {
        for (column in 0..2) {
            canvas.drawCircle(
                box.exactCenterX() + (column - 1) * gap,
                box.exactCenterY() + (row - 0.5f) * gap,
                dot,
                paint,
            )
        }
    }
}

/**
 * While the strip continues past its viewport, its last [END_FADE_DP] fade into the keyboard colour.
 * The cells are clipped at the expand control's edge and never drawn under it; the fade makes a cut
 * candidate read as one that continues rather than as a glyph hidden behind the control.
 */
internal fun CandidateBarView.drawEndFade(canvas: Canvas) {
    if (expanded || entries.isEmpty() || scroll >= maxScroll() - 0.5f) return
    drawFade(canvas, viewport.right.toFloat())
}

/** Once the strip has scrolled, its first [END_FADE_DP] fade too, so a cut cell never abuts the hairline. */
internal fun CandidateBarView.drawStartFade(canvas: Canvas) {
    if (expanded || entries.isEmpty() || scroll <= 0.5f) return
    val edge = viewport.left.toFloat()
    val checkpoint = canvas.save()
    canvas.scale(-1f, 1f, edge, 0f)
    drawFade(canvas, edge)
    canvas.restoreToCount(checkpoint)
}

/**
 * A fade into the keyboard colour over the [END_FADE_DP] left of [edge], the full row height. The
 * shader lives in the fade's own coordinates, so it is rebuilt only when the colour or the density
 * changes, never per frame or per edge.
 */
internal fun CandidateBarView.drawFade(
    canvas: Canvas,
    edge: Float,
) {
    val fade = END_FADE_DP * density
    if (fade != endFadeWidth || palette.background != endFadeColor) {
        endFadeWidth = fade
        endFadeColor = palette.background
        endFadePaint.shader =
            LinearGradient(
                -fade,
                0f,
                0f,
                0f,
                palette.background and 0x00FFFFFF,
                palette.background,
                Shader.TileMode.CLAMP,
            )
    }
    val checkpoint = canvas.save()
    canvas.translate(edge, 0f)
    canvas.drawRect(-fade, 0f, 0f, height.toFloat(), endFadePaint)
    canvas.restoreToCount(checkpoint)
}

/** How much of a section's end fades out while more of it follows. */
private const val END_FADE_DP = 16f

internal fun CandidateBarView.drawEdgeEffects(canvas: Canvas) {
    if (startEdge.isFinished && endEdge.isFinished) return
    if (Build.VERSION.SDK_INT >= 29) {
        startEdge.color = palette.muted
        endEdge.color = palette.muted
    }
    var more = false
    val checkpoint = canvas.save()
    canvas.clipRect(viewport)
    if (expanded) {
        val w = viewport.width()
        val h = viewport.height()
        if (!startEdge.isFinished) {
            startEdge.setSize(w, h)
            more = startEdge.draw(canvas) || more
        }
        if (!endEdge.isFinished) {
            val save = canvas.save()
            canvas.translate(-w.toFloat(), h.toFloat())
            canvas.rotate(180f, w.toFloat(), 0f)
            endEdge.setSize(w, h)
            more = endEdge.draw(canvas) || more
            canvas.restoreToCount(save)
        }
    } else {
        val w = viewport.width()
        val h = viewport.height()
        if (!startEdge.isFinished) {
            val save = canvas.save()
            canvas.rotate(270f)
            canvas.translate(-h.toFloat(), 0f)
            startEdge.setSize(h, w)
            more = startEdge.draw(canvas) || more
            canvas.restoreToCount(save)
        }
        if (!endEdge.isFinished) {
            val save = canvas.save()
            canvas.rotate(90f)
            canvas.translate(0f, -w.toFloat())
            endEdge.setSize(h, w)
            more = endEdge.draw(canvas) || more
            canvas.restoreToCount(save)
        }
    }
    canvas.restoreToCount(checkpoint)
    if (more) postInvalidateOnAnimation()
}

internal fun CandidateBarView.drawNotice(
    canvas: Canvas,
    text: String,
) {
    paint.textSize = metrics.pinyinTextSizeSp * textScale
    val width = min(paint.measureText(text) + startPaddingPx * 2, this.width.toFloat())
    val left = (this.width - width) / 2
    chipBounds.set(left, barPx * 0.15f, left + width, barPx * 0.85f)
    paint.color = palette.popupBackground
    paint.setShadowLayer(metrics.popupElevationDp * density / 2, 0f, density, palette.popupShadow)
    canvas.drawRoundRect(chipBounds, metrics.popupRadiusDp * density, metrics.popupRadiusDp * density, paint)
    paint.clearShadowLayer()
    paint.color = palette.text
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(text, this.width / 2f, barPx / 2 - (paint.ascent() + paint.descent()) / 2, paint)
    paint.textAlign = Paint.Align.LEFT
}

/** The pressed candidate's characters, floating over the chips it came from. */
internal fun CandidateBarView.drawStrip(canvas: Canvas) {
    val open = popup ?: return
    val cells = popupCells
    if (cells.isEmpty()) return
    val strip = RectF(cells.first())
    val last = cells.last()
    strip.set(
        cells.first().left - chipGapPx / 2,
        3 * density,
        last.right + chipGapPx / 2,
        barPx - 3 * density,
    )
    paint.color = palette.popupBackground
    paint.setShadowLayer(metrics.popupElevationDp * density, 0f, 2 * density, palette.popupShadow)
    canvas.drawRoundRect(strip, metrics.popupRadiusDp * density, metrics.popupRadiusDp * density, paint)
    paint.clearShadowLayer()
    for ((i, cell) in cells.withIndex()) {
        if (i == popupDown) {
            paint.color = palette.popupSelected
            chipBounds.set(
                cell.left + 2 * density,
                cell.top + 2 * density,
                cell.right - 2 * density,
                cell.bottom - 2 * density,
            )
            canvas.drawRoundRect(
                chipBounds,
                metrics.candidateChipRadiusDp * density,
                metrics.candidateChipRadiusDp * density,
                paint,
            )
        }
        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = metrics.candidateTextSizeSp * textScale
        canvas.drawText(
            open.chars[i],
            cell.exactCenterX(),
            cell.exactCenterY() - (paint.ascent() + paint.descent()) / 2,
            paint,
        )
    }
    paint.textAlign = Paint.Align.LEFT
}

/** The one-action menu a long press leaves where the candidate was. */
internal fun CandidateBarView.drawMenu(canvas: Canvas) {
    val open = menu ?: return
    paint.color = palette.popupBackground
    paint.setShadowLayer(metrics.popupElevationDp * density, 0f, 2 * density, palette.popupShadow)
    canvas.drawRoundRect(open.rect, metrics.popupRadiusDp * density, metrics.popupRadiusDp * density, paint)
    paint.clearShadowLayer()
    if (menuDown) {
        paint.color = palette.popupSelected
        chipBounds.set(open.rect)
        chipBounds.inset(2 * density, 2 * density)
        canvas.drawRoundRect(
            chipBounds,
            metrics.candidateChipRadiusDp * density,
            metrics.candidateChipRadiusDp * density,
            paint,
        )
    }
    paint.color = palette.text
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = metrics.candidateTextSizeSp * textScale
    canvas.drawText(
        context.getString(R.string.ime_candidate_delete_word),
        open.rect.centerX(),
        open.rect.centerY() - (paint.ascent() + paint.descent()) / 2,
        paint,
    )
    paint.textAlign = Paint.Align.LEFT
}

// ---- press feedback ------------------------------------------------------------------------

/** The pressed fill of a released cell or control fades out over 150 ms. */
internal fun CandidateBarView.beginFade(
    cell: Int,
    control: Int,
) {
    if (cell < 0 && control < 0) return
    fadeAnimator?.cancel()
    fadeAnimator = null
    if (!ImeMotion.enabled || !isAttachedToWindow) {
        fadeCell = -1
        fadeControl = -1
        fadeAlpha = 0f
        return
    }
    fadeCell = cell
    fadeControl = control
    fadeAlpha = 1f
    fadeAnimator =
        ValueAnimator.ofFloat(1f, 0f).apply {
            // A platform animator already applies the system animator duration scale.
            duration = FADE_MS
            addUpdateListener {
                fadeAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        fadeCell = -1
                        fadeControl = -1
                        fadeAlpha = 0f
                        fadeAnimator = null
                        invalidate()
                    }
                },
            )
            start()
        }
}

internal val Control.iconRes: Int
    get() =
        when (this) {
            Control.EXPAND -> 0
            Control.BACK -> R.drawable.ic_candidate_collapse
            Control.BACKSPACE -> R.drawable.ic_candidate_delete
            Control.RETYPE -> R.drawable.ic_candidate_clear
        }

internal val Control.labelRes: Int
    get() =
        when (this) {
            Control.EXPAND -> R.string.ime_expand
            Control.BACK -> R.string.ime_candidate_back
            Control.BACKSPACE -> R.string.ime_delete
            Control.RETYPE -> R.string.ime_clear
        }

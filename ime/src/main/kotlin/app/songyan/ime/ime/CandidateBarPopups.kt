package app.songyan.ime.ime

import android.graphics.Rect
import android.graphics.RectF
import app.songyan.ime.ime.CandidateBarView.Companion.MENU_GAP_DP
import app.songyan.ime.ime.CandidateBarView.Companion.MENU_MIN_DP
import app.songyan.ime.ime.CandidateBarView.DeleteRequest
import app.songyan.ime.ime.CandidateBarView.Entry
import app.songyan.ime.ime.CandidateBarView.Menu
import app.songyan.ime.ime.CandidateBarView.Popup
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/** Opens the character strip of [cell]'s candidate. */
internal fun CandidateBarView.showPopup(
    cell: Int,
    text: String,
) {
    val chars = graphemes(text)
    if (chars.isEmpty()) return
    val anchor = viewCell(cell) ?: return
    menu = null
    val entry = entries.getOrNull(cell) ?: return
    popup = Popup(text, chars, entry, listRevision)
    popupCells = layoutPopupCells(chars, anchor)
    popupDown = -1
    popupOpenedByGesture = false
    announceContentChanged()
    invalidate()
    onPopupChanged()
}

/** The cell as it is on screen right now (content position minus the scroll). */
internal fun CandidateBarView.viewCell(cell: Int): Rect? {
    val box = cells.getOrNull(cell) ?: return null
    val dx = if (expanded) 0f else scroll
    val dy = if (expanded) scroll else 0f
    return Rect(
        (box.left - dx).toInt(),
        (box.top - dy).toInt(),
        (box.right - dx).toInt(),
        (box.bottom - dy).toInt(),
    )
}

/** An inline reading's box as it is on screen right now, scrolled with its own section. */
internal fun CandidateBarView.viewRect(box: RectF): Rect =
    Rect((box.left - readingScroll).toInt(), box.top.toInt(), (box.right - readingScroll).toInt(), box.bottom.toInt())

/** One cell per character, as wide as the character needs and never wider than the bar. */
internal fun CandidateBarView.layoutPopupCells(
    chars: List<String>,
    anchor: Rect,
): List<Rect> {
    paint.textSize = metrics.candidateTextSizeSp * textScale
    val top = if (expanded) anchor.top + 3 * density else 3 * density
    val bottom = top + barPx - 6 * density
    val wanted =
        chars.map { char ->
            max(metrics.popupCellWidthDp * density, paint.measureText(char) + chipPaddingPx * 2)
        }
    val total = wanted.sum()
    val widthPx = width.toFloat()
    val scale = if (total > widthPx && total > 0f) widthPx / total else 1f
    var left = anchor.left.toFloat().coerceIn(0f, max(0f, widthPx - total * scale))
    return wanted.map { wantedWidth ->
        val cellWidth = wantedWidth * scale
        Rect(left.toInt(), top.toInt(), (left + cellWidth).toInt(), bottom.toInt()).also {
            left += cellWidth
        }
    }
}

/** Opens the menu of [entry]: where its chip was, one action wide. */
internal fun CandidateBarView.showMenu(
    entry: Entry,
    revision: Int,
) {
    val anchor = viewCell(entries.indexOf(entry)) ?: return
    paint.textSize = metrics.candidateTextSizeSp * textScale
    val label = context.getString(R.string.ime_candidate_delete_word)
    val wanted = paint.measureText(label) + chipPaddingPx * 4
    val widthPx = min(max(wanted, MENU_MIN_DP * density), width.toFloat())
    val gap = MENU_GAP_DP * density
    // Beside the candidate, not over it: the user must still see which word the action will forget.
    val left =
        when {
            anchor.right + gap + widthPx <= width -> anchor.right + gap
            anchor.left - gap - widthPx >= 0f -> anchor.left - gap - widthPx
            else -> anchor.left.toFloat().coerceIn(0f, max(0f, width - widthPx))
        }
    val top = if (expanded) anchor.top + 3 * density else 3 * density
    val rect = RectF(left, top, left + widthPx, top + barPx - 6 * density)
    menu = Menu(entry, revision, rect)
    menuDown = false
    announceContentChanged()
    invalidate()
    onPopupChanged()
}

internal fun CandidateBarView.requestDelete(
    entry: Entry,
    revision: Int,
) {
    dismissPopup()
    onDeleteWord(DeleteRequest(entry.absoluteIndex, entry.text, revision))
}

/** Grapheme clusters: a character with its combining marks must be taken as one cell. */
internal fun CandidateBarView.graphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

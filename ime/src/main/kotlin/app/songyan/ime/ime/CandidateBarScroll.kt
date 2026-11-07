package app.songyan.ime.ime

import android.graphics.RectF
import androidx.core.widget.EdgeEffectCompat
import app.songyan.ime.ime.CandidateBarView.Companion.LOAD_AHEAD_ROWS
import kotlin.math.abs
import kotlin.math.max

/** Brings a chip fully into view, for TalkBack and keyboard navigation. */
internal fun CandidateBarView.revealChip(cell: Int) {
    revealBox(cells.getOrNull(cell) ?: return)
}

/** Brings any cell of the row (a chip or an inline reading) fully into view. */
internal fun CandidateBarView.revealBox(box: RectF) {
    val extent = viewportExtent()
    val origin = if (expanded) 0f else viewport.left.toFloat()
    val near = (if (expanded) box.top else box.left) - origin
    val far = (if (expanded) box.bottom else box.right) - origin
    when {
        near - scroll < 0f -> scrollTo(near)
        far - scroll > extent -> scrollTo(far - extent)
    }
}

internal fun CandidateBarView.scrollTo(target: Float) {
    val next = target.coerceIn(0f, maxScroll())
    if (next == scroll) return
    scroll = next
    invalidate()
    maybeLoadMore(userBrowsing = true)
}

/**
 * Whether the next engine page is due at [visibleEnd] (where the viewport ends in content
 * coordinates) for content running to [contentExtent].
 *
 * A call caused by the view itself (a new list, a page that landed, a size change) only fills what
 * can be seen: it asks while the content still ends before the viewport does, so a list that already
 * runs past the viewport reads nothing off screen. A real scroll keeps the [lookAhead] lead, so the
 * page lands before the finger gets there.
 */
internal fun loadMoreDue(
    visibleEnd: Float,
    contentExtent: Float,
    lookAhead: Float,
    userBrowsing: Boolean,
): Boolean = if (userBrowsing) visibleEnd >= contentExtent - lookAhead else visibleEnd > contentExtent

/**
 * Asks the owner for the next engine page the view can put to use: an automatic refresh (see
 * [loadMoreDue]) only fills the visible area, while a scroll, drag, fling or reveal by the user
 * reads [LOAD_AHEAD_ROWS] row heights ahead, along the strip or down the grid. A list that does not
 * even fill the viewport is filled at once either way.
 */
internal fun CandidateBarView.maybeLoadMore(userBrowsing: Boolean = false) {
    if (width <= 0 || entries.isEmpty() || !pages.hasMore) return
    val nearEnd = loadMoreDue(scroll + viewportExtent(), contentExtent, barPx * LOAD_AHEAD_ROWS, userBrowsing)
    val page = pages.nextRead(nearEnd) ?: return
    onLoadMore(listRevision, page)
}

// ---- scrolling -----------------------------------------------------------------------------

/**
 * Moves the content by [delta] px (positive reveals later content). What the content cannot take
 * becomes an edge pull, and a pull that is already showing absorbs the reverse direction first,
 * so a drag that turns around never jumps.
 */
internal fun CandidateBarView.dragBy(delta: Float) {
    val extent = viewportExtent()
    if (extent <= 0f) return
    var remaining = delta
    if (remaining > 0f && EdgeEffectCompat.getDistance(startEdge) > 0f) {
        remaining += EdgeEffectCompat.onPullDistance(startEdge, -remaining / extent, 0.5f) * extent
    } else if (remaining < 0f && EdgeEffectCompat.getDistance(endEdge) > 0f) {
        remaining -= EdgeEffectCompat.onPullDistance(endEdge, remaining / extent, 0.5f) * extent
    }
    val max = maxScroll()
    val target = scroll + remaining
    val clamped = target.coerceIn(0f, max)
    val overshoot = target - clamped
    scroll = clamped
    if (overshoot > 0f) {
        EdgeEffectCompat.onPullDistance(endEdge, overshoot / extent, 0.5f)
    } else if (overshoot < 0f) {
        EdgeEffectCompat.onPullDistance(startEdge, -overshoot / extent, 0.5f)
    }
    invalidate()
    maybeLoadMore(userBrowsing = true)
}

internal fun CandidateBarView.releaseEdges() {
    startEdge.onRelease()
    endEdge.onRelease()
}

internal fun CandidateBarView.startFling(velocityPx: Float) {
    val max = maxScroll()
    if (max <= 0f || abs(velocityPx) < minFling) {
        releaseEdges()
        return
    }
    scroller.forceFinished(true)
    // A positive velocity drags the finger forward, which moves the content backwards.
    if (expanded) {
        scroller.fling(0, scroll.toInt(), 0, (-velocityPx).toInt(), 0, 0, 0, max.toInt())
    } else {
        scroller.fling(scroll.toInt(), 0, (-velocityPx).toInt(), 0, 0, max.toInt(), 0, 0)
    }
    postInvalidateOnAnimation()
}

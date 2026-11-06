package app.songyan.ime.ime

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EdgeEffect
import android.widget.OverScroller
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import app.songyan.ime.candidate.BrowserCandidate
import app.songyan.ime.candidate.CandidateBrowser.CandidatePageReadResult
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot

/**
 * The candidate row: one row of the keyboard's top band, [KeyboardDesignTokens.candidateBarHeightDp]
 * tall in every state, so the keyboard window never changes height when a composition starts, a page
 * flips or the bar expands. Spelling is shown only in the input field, never here.
 *
 * Collapsed, the row is a strip: every candidate takes the width of its own text plus a fixed
 * padding, from the start of the row, and the strip scrolls sideways (with fling and an edge effect)
 * through the engine's later pages, which are read read-only as the user nears the end
 * ([CandidatePages]). The expand control at the trailing end opens the same list as a vertically
 * scrolling grid over the whole keyboard surface, with Back, Backspace and Retype in a fixed column
 * on the right. Paging the engine itself is left to the engine's own keys; the strip follows the
 * engine's highlight wherever it moves.
 *
 * The bar paints the keyboard canvas colour, not a separate strip: it reads as the top of the
 * keyboard, with no divider between the candidates and the keys.
 *
 * A touch press captures the whole candidate identity (engine index and text) plus the revision of
 * the candidate list it was drawn in. The release only commits that identity: when the engine
 * replaced the page under the finger the pressed text is looked up again, and when it is gone the
 * release is a visible no-op, never the word that now occupies the same cell.
 */
class CandidateBarView(
    context: Context,
) : AccessibleCanvasView(context),
    BarContent {
    /**
     * The candidate the user chose: page-local engine index, text and the list revision it was drawn
     * in. [absoluteIndex] is set for a candidate of the engine's paged list, which may sit on any page
     * the bar has read; it is -1 for a row that does not page (suggestions, a reading filter).
     */
    data class Choice(
        val index: Int,
        val text: String,
        val revision: Int,
        val absoluteIndex: Int = -1,
        /** The reading shown with the word; with [text] it finds the word again on a newer page. */
        val comment: String = "",
    )

    /** One character taken from the strip of [candidateText], with the revision it opened in. */
    data class CharacterPick(
        val text: String,
        val candidateText: String,
        val revision: Int,
    )

    /** A request to forget a word the user's typing taught the engine. */
    data class DeleteRequest(
        val absoluteIndex: Int,
        val text: String,
        val revision: Int,
    )

    /**
     * One candidate the bar can show: its place in the engine's full list and what it says, with the
     * widths of its text and comment, measured once per text size rather than on every frame.
     */
    internal class Entry(
        val absoluteIndex: Int,
        val localIndex: Int,
        val text: String,
        val comment: String,
    ) {
        var textWidth = 0f
        var commentWidth = 0f

        /** The [CandidateBarView.measureGeneration] the widths belong to; 0 is never measured. */
        var measuredFor = 0
    }

    /** What a press was over; [revision] is the candidate list revision captured on touch down. */
    internal class Press(
        val cell: Int,
        val absoluteIndex: Int,
        val text: String,
        val revision: Int,
        val downX: Float,
        val downY: Float,
        val comment: String,
    )

    /** The characters of one long-pressed candidate, shown as a strip of its own cells. */
    internal class Popup(
        val text: String,
        val chars: List<String>,
        val entry: Entry,
        val revision: Int,
    )

    /** The menu a long press leaves behind once the strip closes: one action on one candidate. */
    internal class Menu(
        val entry: Entry,
        val revision: Int,
        val rect: RectF,
    )

    internal val density = resources.displayMetrics.density
    internal val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    internal var palette = KeyboardPalette.from(context)
    internal var metrics = palette.tokens.withCandidateTextFactor(1f)

    /** The applied appearance's candidate area: page-button placement and the expanded arrangement. */
    internal var style = CandidateBarStyle.current()
    internal var textScale = textScaleFor(context)

    /** A reading row sits right above (14 / 18 keys): the candidate text then keeps a little distance from it. */
    internal var underReadingRow = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    internal val chipBounds = RectF()

    /** The fade at a section's end, and the width and colour its shader was built for. */
    internal val endFadePaint = Paint()
    internal var endFadeWidth = Float.NaN
    internal var endFadeColor = 0
    internal var snapshot = EngineSnapshot()

    /** The engine's list as far as it has been read; the strip and the expanded grid both show it. */
    internal val pages = CandidatePages()

    /** What the bar lists right now, one entry per candidate of [pages]. */
    internal var entries = emptyList<Entry>()

    /** The absolute index of the engine's highlighted candidate, or -1. */
    internal var highlightedAbsolute = -1

    /** Bumped when the text size changes, so every entry measures its text again once. */
    internal var measureGeneration = 1

    /** The candidate and comment text sizes the entries of [measureGeneration] were measured at. */
    internal var measuredTextSize = 0f
    internal var measuredCommentSize = 0f

    /** One rectangle per entry, in content coordinates (before [scroll]). */
    internal var cells = emptyList<RectF>()

    /**
     * The inline readings (the "inline" placement of a 14 / 18-key layout): a section of its own at
     * the start of the collapsed row ([readingViewport]) that scrolls by [readingScroll], apart from
     * the candidates. Cells are in view coordinates at no scroll.
     */
    internal var readings = emptyList<RailItem>()
    internal var readingCells = emptyList<RectF>()
    internal var downReading = -1
    internal val readingViewport = Rect()
    internal var readingScroll = 0f
    internal var readingMaxScroll = 0f

    /** The current gesture drags the readings section rather than the candidates. */
    internal var scrollingReadings = false

    /** A tap on an inline reading: its index in the reading list and its label as drawn. */
    var onReading: (index: Int, label: String) -> Unit = { _, _ -> }
    internal var controls = emptyList<Control>()
    internal var controlCells = emptyList<Rect>()

    /** The scrolling region in view coordinates. */
    internal val viewport = Rect()
    internal var down = -1
    internal var downControl = -1
    internal var backspaceStartedComposing = false

    /** The revision of the list on screen; a replaced list gets a new one, a grown list keeps it. */
    internal val listRevision: Int get() = pages.revision
    internal var pressed: Press? = null
    internal var downRevision = -1

    /** The popup was opened by this gesture, so the release that opened it must not close it. */
    internal var popupOpenedByGesture = false

    /** The opening gesture slid onto a character: its release picks that character. */
    internal var popupGestureSlid = false

    /** Where the current touch went down, so a slide is told apart from a stationary hold. */
    internal var gestureX = 0f
    internal var gestureY = 0f

    internal var popup: Popup? = null
    internal var popupCells: List<Rect> = emptyList()
    internal var popupDown = -1
    internal var menu: Menu? = null
    internal var menuDown = false
    private var notice: String? = null

    /** The expand control at the right end of the row, and Back / Backspace / Retype expanded. */
    internal enum class Control { EXPAND, BACK, BACKSPACE, RETYPE }

    var expanded = false
        private set

    /**
     * The height the expanded bar takes, asked for as it expands: the whole keyboard surface (this
     * row plus the key area it hides), so the window keeps its height.
     */
    var expandedHeight: () -> Int = { 0 }
    private var expandedHeightPx = 0

    /** A transient notice (preparing, unavailable); null keeps a ready, empty bar silent. */
    var status: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The candidate-list revision currently drawn; a callback carries the one it was captured in. */
    val revision: Int get() = listRevision

    /** The character popup or the delete menu opened or closed: Back must close it before hiding. */
    var onPopupChanged: () -> Unit = {}

    /** True while a candidate's character strip or its menu is open. */
    val popupOpen: Boolean get() = popup != null || menu != null

    /**
     * The row lists the ABC page's English suggestions, not engine candidates: a tap hands the word to
     * the owner, and a long press opens nothing because there is no engine word to forget.
     */
    var suggestionRow = false

    var onCandidate: (Choice) -> Unit = {}

    /** A finger landed on something that acts: the same tick a key gives at touch-down. */
    var onPressFeedback: () -> Unit = {}
    var onCandidateCharacter: (CharacterPick) -> Unit = {}
    var onDeleteWord: (DeleteRequest) -> Unit = {}
    var onExpanded: (Boolean) -> Unit = {}

    /**
     * The strip or the grid is near the end of what was read and the engine has more: read engine
     * [page] read-only and hand it to [appendPage] (or [loadFailed]) with the same [revision].
     */
    var onLoadMore: (revision: Int, page: Int) -> Unit = { _, _ -> }
    var onBackspace: (startedComposing: Boolean) -> Unit = {}
    var onRetype: () -> Unit = {}

    /** Scroll offset in px: horizontal while collapsed, vertical while expanded; 0 is the start. */
    internal var scroll = 0f
    internal var contentExtent = 0f
    internal var scrollingGesture = false
    internal var lastTouch = 0f
    internal var velocity: VelocityTracker? = null
    internal val scroller = OverScroller(context)
    internal val startEdge = EdgeEffect(context)
    internal val endEdge = EdgeEffect(context)
    private val configuration = ViewConfiguration.get(context)
    internal val touchSlop = configuration.scaledTouchSlop
    internal val minFling = configuration.scaledMinimumFlingVelocity
    internal val maxFling = configuration.scaledMaximumFlingVelocity

    /** The cell whose pressed state is fading out after release (150 ms), and how visible it still is. */
    internal var fadeCell = -1
    internal var fadeControl = -1
    internal var fadeAlpha = 0f
    internal var fadeAnimator: ValueAnimator? = null

    /** The reading row's height while one sits above; the collapsed row then shrinks to it ([ReadingRowBudget.bandDp]). */
    internal var readingRowDp: Float? = null
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            requestLayout()
        }

    /** The collapsed row's height; expanded cells keep the full bar height as their touch target. */
    internal val barPx: Float
        get() =
            if (expanded) {
                metrics.candidateBarHeightDp * density
            } else {
                ReadingRowBudget.bandDp(metrics.candidateBarHeightDp, readingRowDp) * density
            }

    override fun contentCentreY(): Float = barPx / 2f + candidateDropDp() * density

    internal val startPaddingPx: Float get() = metrics.candidateStartPaddingDp * density
    internal val chipGapPx: Float get() = metrics.candidateChipGapDp * density
    internal val chipPaddingPx: Float get() = metrics.candidateChipPaddingDp * density
    internal val cellSpacingPx: Float get() = metrics.candidateCellSpacingDp * density

    /** Where the strip's first cell starts, so its text lines up with the row's start padding. */
    internal val stripInsetPx: Float get() = (startPaddingPx - chipPaddingPx).coerceAtLeast(0f)
    internal val controlWidthPx: Float get() = CONTROL_TOUCH_DP * density
    internal val iconPx: Int get() = (ICON_DP * density).toInt()
    internal val minChipWidthPx: Float get() = metrics.candidateMinChipWidthDp * density
    internal val columnWidthPx: Float get() = EXPANDED_COLUMN_DP * density

    /** Chips and controls count as items only while a candidate page is drawn. */
    private val barItemCount: Int
        get() = candidateItemCount + readingCells.size

    private val candidateItemCount: Int
        get() = if (snapshot.candidates.isEmpty()) 0 else entries.size + controlCells.size

    /** The item index of the first inline reading: they follow the chips and controls in the index space. */
    internal val readingBase: Int get() = candidateItemCount

    /** While the strip or menu is open it is the whole accessible surface: no chip underneath. */
    override val itemCount: Int
        get() =
            when {
                popup != null -> popupCells.size
                menu != null -> 1
                else -> barItemCount
            }

    internal val icons = HashMap<Int, Drawable>()

    /**
     * The expansion's progress, 0..1. Only the drawn content is animated: the measured slot switches
     * to the whole keyboard surface on one frame together with the key area, so the IME's total
     * content height never changes and the editor is never resized.
     */
    private var revealProgress = 1f
    private var revealAnimator: SpringAnimation? = null

    private val revealProperty =
        object : FloatPropertyCompat<CandidateBarView>("reveal") {
            override fun getValue(view: CandidateBarView): Float = view.revealProgress

            override fun setValue(
                view: CandidateBarView,
                value: Float,
            ) {
                view.revealProgress = value
                view.invalidate()
            }
        }

    /** Delayed work runs on the main looper directly, so it also runs for a view not yet attached. */
    internal val ui = Handler(Looper.getMainLooper())

    internal val canRepeatBackspace: Boolean
        get() =
            expanded &&
                controls.getOrNull(downControl) == Control.BACKSPACE &&
                (!backspaceStartedComposing || snapshot.isComposing)

    internal val repeatBackspace =
        object : Runnable {
            override fun run() {
                if (!canRepeatBackspace) return
                onBackspace(backspaceStartedComposing)
                if (canRepeatBackspace) ui.postDelayed(this, KeyboardView.BACKSPACE_REPEAT_MS)
            }
        }

    internal val longPress =
        Runnable {
            val press = pressed ?: return@Runnable
            if (suggestionRow) return@Runnable
            val entry = entries.getOrNull(press.cell) ?: return@Runnable
            // The page changed under the finger: opening a popup for a different word would be a
            // silent substitution, so nothing opens.
            if (listRevision != press.revision) return@Runnable
            if (entry.absoluteIndex != press.absoluteIndex || entry.text != press.text) return@Runnable
            beginFade(press.cell, -1)
            pressed = null
            down = -1
            if (popup?.text == press.text) {
                dismissPopup()
            } else {
                showPopup(press.cell, press.text)
                popupOpenedByGesture = true
                popupGestureSlid = false
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

    private val clearNotice =
        Runnable {
            notice = null
            invalidate()
        }

    /** No hold survives the pointer leaving its control or the control disappearing. */
    internal fun cancelPress() {
        ui.removeCallbacks(longPress)
        ui.removeCallbacks(repeatBackspace)
        downReading = -1
        beginFade(down, downControl)
        pressed = null
        down = -1
        downControl = -1
    }

    /** Shows the readings of the running composition at the start of the row; empty removes them. */
    internal fun showReadings(value: List<RailItem>) {
        if (value == readings) return
        readings = value
        if (value.isEmpty()) downReading = -1
        scroll = 0f
        // A new composition starts both sections at their start; an applied reading stays in view.
        readingScroll = 0f
        rebuild()
        val chosen = value.indexOfLast { it.selected }
        if (chosen >= 0) revealReading(chosen)
        invalidate()
    }

    /** Brings inline reading [index] fully into its section. */
    internal fun revealReading(index: Int) {
        val box = readingCells.getOrNull(index) ?: return
        val section = readingViewport.width().toFloat()
        val target =
            when {
                box.left - readingScroll < 0f -> box.left
                box.right - readingScroll > section -> box.right - section
                else -> return
            }
        readingScroll = target.coerceIn(0f, readingMaxScroll)
        invalidate()
    }

    /**
     * Shows [value]. [browsable] says the row is the engine's own candidate menu, so later pages may
     * be read into it; suggestion rows and a reading filter's matches are shown exactly as given.
     */
    fun update(
        value: EngineSnapshot,
        browsable: Boolean = true,
    ) {
        val highlightBefore = highlightedAbsolute
        val replaced = pages.update(value, browsable)
        snapshot = value
        highlightedAbsolute = highlightOf(value)
        if (replaced) {
            // A new list starts at its first candidate, not where the last one was scrolled to.
            scroll = 0f
            scroller.abortAnimation()
            menu = null
            menuDown = false
        }
        syncEntries()
        if (value.candidates.isEmpty()) {
            dismissPopup()
            if (expanded) setExpanded(false)
        }
        rebuild()
        // The engine moved its highlight (a page or arrow key it handles): keep it in sight.
        if (!replaced && highlightedAbsolute != highlightBefore) revealHighlight()
        maybeLoadMore()
        invalidate()
    }

    /** The highlight in the numbering of [Entry.absoluteIndex]. */
    private fun highlightOf(value: EngineSnapshot): Int =
        when {
            value.highlightedIndex < 0 -> -1
            value.pageSize > 0 -> value.pageIndex * value.pageSize + value.highlightedIndex
            else -> value.highlightedIndex
        }

    /** The source list [entries] was built from, so an unchanged list is not rebuilt. */
    private var entrySource: List<BrowserCandidate> = emptyList()

    /** Rebuilds [entries] from [pages] when the list changed, keeping the measured widths of known entries. */
    private fun syncEntries() {
        val source = pages.entries
        if (source === entrySource) return
        entrySource = source
        val known = HashMap<Int, Entry>(entries.size * 2)
        for (entry in entries) known[entry.absoluteIndex] = entry
        entries =
            source.map { item ->
                val absolute = pages.absoluteIndex(item)
                known[absolute]?.takeIf { it.text == item.text && it.comment == item.comment }
                    ?: Entry(absolute, item.pageLocalIndex, item.text, item.comment)
            }
    }

    /** Brings the engine's highlighted candidate fully into view, when the list shows it. */
    internal fun revealHighlight() {
        if (highlightedAbsolute < 0) return
        val cell = entries.indexOfFirst { it.absoluteIndex == highlightedAbsolute }
        if (cell >= 0) revealChip(cell)
    }

    /**
     * A transient message over the candidates: a stale release is visible instead of silent. It never
     * replaces [status] and never blocks the next touch.
     */
    fun flash(message: String) {
        notice = message
        ui.removeCallbacks(clearNotice)
        ui.postDelayed(clearNotice, NOTICE_TIMEOUT_MS)
        invalidate()
    }

    /** The user's candidate text size choice, applied on top of the skin's size. */
    var textSizeFactor: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            refreshAppearance()
        }

    private fun KeyboardDesignTokens.withCandidateTextFactor(factor: Float): KeyboardDesignTokens =
        copy(
            candidateTextSizeSp = candidateTextSizeSp * factor,
            candidateCommentTextSizeSp = candidateCommentTextSizeSp * factor,
        )

    /** Picks up a day/night or font-scale change without losing the current candidates. */
    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        metrics = palette.tokens.withCandidateTextFactor(textSizeFactor)
        style = CandidateBarStyle.current()
        textScale = textScaleFor(context)
        icons.clear()
        rebuild()
        requestLayout()
        invalidate()
    }

    /**
     * Gives back what can be rebuilt: the icon drawables, and the pages read beyond the engine's
     * current one while the bar is collapsed (an expanded grid being browsed keeps its pages). The
     * engine's page, the composition and the selection are untouched.
     */
    fun trimMemory() {
        icons.clear()
        if (expanded) return
        pages.trim()
        syncEntries()
        scroll = 0f
        rebuild()
    }

    /** The engine's list changed under the same code (a deleted word): start over from the engine's page. */
    fun listChanged() {
        pages.invalidate()
        syncEntries()
        scroll = 0f
        rebuild()
        maybeLoadMore()
        invalidate()
    }

    /** Collapses an expanded bar; false when it was already collapsed. */
    fun collapse(): Boolean {
        if (!expanded) return false
        setExpanded(false)
        return true
    }

    /** Closes the character strip and the delete menu; true when one was open. */
    fun dismissPopup(): Boolean {
        val open = popup != null || menu != null
        popup = null
        popupCells = emptyList()
        popupDown = -1
        popupOpenedByGesture = false
        popupGestureSlid = false
        menu = null
        menuDown = false
        if (open) {
            announceContentChanged()
            invalidate()
            onPopupChanged()
        }
        return open
    }

    /**
     * Engine [page], read read-only for list [revision], arrived. A read asked for under an older
     * list is dropped. The strip keeps what is on screen in place: pages land after it, and an
     * earlier page that lands before it shifts the scroll by its own width.
     */
    fun appendPage(
        revision: Int,
        page: Int,
        candidates: List<Candidate>,
    ) {
        val anchor = firstVisibleCell()
        val anchorIndex = entries.getOrNull(anchor)?.absoluteIndex ?: -1
        val anchorOffset = if (anchor >= 0) cellStart(anchor) - scroll else 0f
        if (!pages.append(revision, page, CandidatePageReadResult.Page(candidates))) {
            maybeLoadMore()
            return
        }
        syncEntries()
        rebuild()
        if (anchorIndex >= 0) {
            val moved = entries.indexOfFirst { it.absoluteIndex == anchorIndex }
            if (moved >= 0) scroll = (cellStart(moved) - anchorOffset).coerceIn(0f, maxScroll())
        }
        announceContentChanged()
        invalidate()
        maybeLoadMore()
    }

    /** The read for [revision] failed: no new read until the list changes or the user scrolls again. */
    fun loadFailed(revision: Int) {
        pages.failed(revision)
    }

    /** A requested read will not be answered (the input session ended or the read was cancelled). */
    fun abandonLoad() {
        pages.abandonRead()
    }

    /** The accessible surface changed wholesale (the strip replaced the chips, or went away). */
    internal fun announceContentChanged() {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return
        if (!manager.isEnabled) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event.setSource(this)
        event.packageName = context.packageName
        event.className = javaClass.name
        parent?.requestSendAccessibilityEvent(this, event)
    }

    internal fun setExpanded(value: Boolean) {
        if (expanded != value) cancelPress()
        expanded = value
        if (value) expandedHeightPx = expandedHeight()
        scroll = 0f
        scroller.abortAnimation()
        dismissPopup()
        pages.resume()
        // The slot height changes with the state. Without an explicit request the parent reuses this
        // view's cached measurement when the key area goes GONE, so the window would keep the
        // collapsed row and leave the rest of the keyboard surface empty.
        requestLayout()
        rebuild()
        animateReveal()
        onExpanded(value)
        maybeLoadMore()
    }

    /**
     * Unrolls the expanded grid below its first row with a critically-damped-ish spring. The slot
     * height is applied instantly and exactly complementary to the key area, so the IME window keeps
     * its height throughout. Collapsing is instant: the row is already where the grid's first row
     * was, and hiding it to reveal it again read as a flash.
     */
    private fun animateReveal() {
        revealAnimator?.cancel()
        revealAnimator = null
        revealProgress = 1f
        if (!expanded || !ImeMotion.enabled || width <= 0 || !isLaidOut) {
            invalidate()
            return
        }
        revealProgress = 0f
        revealAnimator =
            SpringAnimation(this, revealProperty).apply {
                spring = ImeMotion.revealSpring(1f)
                setStartValue(0f)
                setMinimumVisibleChange(1f / REVEAL_RESOLUTION)
                addEndListener { _, _, _, _ ->
                    revealProgress = 1f
                    revealAnimator = null
                    invalidate()
                }
                start()
            }
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val height = if (expanded && expandedHeightPx > 0) expandedHeightPx else barPx.toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        rebuild()
        // A list shown before the first layout, or a window that grew, may not fill the row yet.
        maybeLoadMore()
    }

    override fun onDetachedFromWindow() {
        cancelPress()
        ui.removeCallbacks(clearNotice)
        revealAnimator?.cancel()
        revealAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        scroller.abortAnimation()
        super.onDetachedFromWindow()
    }

    internal fun rebuild() {
        if (width <= 0) return
        controls = controlList()
        measureEntries()
        if (expanded) layoutExpanded() else layoutCollapsed()
        scroll = scroll.coerceIn(0f, maxScroll())
        if (entries.isEmpty() || !ImePerf.isLogging || cells.isEmpty()) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val first = cells[0]
        ImePerf.candidateBound(
            ((first.left + first.right) / 2).toInt() + location[0],
            ((first.top + first.bottom) / 2).toInt() + location[1],
        )
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val position = (if (expanded) scroller.currY else scroller.currX).toFloat()
        scroll = position.coerceIn(0f, maxScroll())
        val atStart = position <= 0f
        val atEnd = position >= maxScroll()
        if ((atStart || atEnd) && !scroller.isFinished) {
            val speed = scroller.currVelocity.toInt()
            if (atStart) startEdge.onAbsorb(speed) else endEdge.onAbsorb(speed)
            scroller.abortAnimation()
        }
        maybeLoadMore(userBrowsing = true)
        postInvalidateOnAnimation()
    }

    // ---- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        // Same colour as the keyboard canvas: the candidate bar is the top of the keyboard, not a
        // separate grey strip.
        canvas.drawColor(palette.background)
        drawStatus(canvas)
        val noticeText = notice
        val reveal = revealProgress
        val checkpoint = canvas.save()
        // Only the drawn content is revealed: the slot height already switched together with the key
        // area, so the IME window height never changes during the transition. The first row never
        // moves or hides, so the candidates under the finger stay put.
        canvas.clipRect(0f, 0f, width.toFloat(), revealedHeight(height.toFloat(), barPx, reveal))
        val rows = canvas.save()
        canvas.clipRect(viewport)
        if (expanded) canvas.translate(0f, -scroll) else canvas.translate(-scroll, 0f)
        drawEntries(canvas)
        canvas.restoreToCount(rows)
        drawStartFade(canvas)
        drawEndFade(canvas)
        if (!expanded) drawReadingSection(canvas)
        drawEdgeEffects(canvas)
        if (snapshot.candidates.isNotEmpty()) drawControls(canvas)
        canvas.restoreToCount(checkpoint)
        if (noticeText != null) drawNotice(canvas, noticeText)
        drawStrip(canvas)
        drawMenu(canvas)
    }

    // ---- the strip and the menu ----------------------------------------------------------------

    // ---- accessibility -------------------------------------------------------------------------

    override fun itemBounds(index: Int): Rect {
        if (popup != null) return popupCells[index]
        menu?.let { return Rect().also { rect -> it.rect.roundOut(rect) } }
        if (index >= readingBase) return viewRect(readingCells[index - readingBase])
        if (index < entries.size) return viewCell(index) ?: Rect()
        return controlCells[index - entries.size]
    }

    override fun itemLabel(index: Int): String {
        popup?.let { open -> return open.chars.getOrNull(index).orEmpty() }
        if (menu != null) return context.getString(R.string.ime_candidate_delete_word)
        if (index >= readingBase) return readingLabel(context, readings[index - readingBase])
        if (index < entries.size) {
            return entries[index].let { entry ->
                if (entry.comment.isEmpty()) entry.text else entry.text + " " + entry.comment
            }
        }
        return context.getString(controls.getOrNull(index - entries.size)?.labelRes ?: R.string.ime_expand)
    }

    override fun onItemAccessibilityFocused(index: Int) {
        if (popup != null || menu != null) return
        if (index >= readingBase) revealReading(index - readingBase) else revealChip(index)
    }

    /** Visual order: the inline readings come first, then the chips and the controls. */
    override fun itemOrder(): List<Int> =
        if (popup != null || menu != null || readingCells.isEmpty()) {
            super.itemOrder()
        } else {
            (readingBase until itemCount).toList() + (0 until readingBase).toList()
        }

    override fun activateItem(index: Int) {
        if (popup != null) {
            commitPopupCharacter(index)
            return
        }
        menu?.let {
            requestDelete(it.entry, it.revision)
            return
        }
        if (index >= readingBase) {
            val item = readings.getOrNull(index - readingBase) ?: return
            if (!item.selected) onReading(index - readingBase, item.label)
            return
        }
        if (index < entries.size) {
            choose(entries[index], listRevision)
            return
        }
        activateControl(index - entries.size)
    }

    /** A candidate chip opens its characters on long press; controls only click. */
    override fun itemLongClickable(index: Int): Boolean =
        popup == null && menu == null && index < entries.size && snapshot.candidates.isNotEmpty()

    override fun performItemLongClick(index: Int): Boolean {
        if (!itemLongClickable(index)) return false
        val entry = entries.getOrNull(index) ?: return false
        if (popup?.text == entry.text) dismissPopup() else showPopup(index, entry.text)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    /** Forgetting a word is also offered as a named action, for assistive technology. */
    override fun itemCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> =
        if (popup == null && menu == null && index < entries.size && snapshot.candidates.isNotEmpty()) {
            listOf(
                AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_DELETE_WORD,
                    context.getString(R.string.ime_candidate_delete_word),
                ),
            )
        } else {
            emptyList()
        }

    override fun performItemCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean {
        if (actionId != ACTION_DELETE_WORD) return false
        val entry = entries.getOrNull(index) ?: return false
        requestDelete(entry, listRevision)
        return true
    }

    /**
     * The strip and the grid scroll for assistive technology too, a viewport at a time; scrolling
     * forward near the end reads the engine's next page, so every candidate can be reached.
     */
    override fun hostScrollActions(): List<AccessibilityNodeInfo.AccessibilityAction> {
        if (popup != null || menu != null || entries.isEmpty()) return emptyList()
        return buildList {
            if (scroll > 0f) add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (scroll < maxScroll() ||
                pages.hasMore
            ) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        }
    }

    override fun performHostAction(action: Int): Boolean {
        val step = viewportExtent() * ACCESSIBILITY_SCROLL_FRACTION
        val target =
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> scroll + step
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> scroll - step
                else -> return false
            }
        if (hostScrollActions().none { it.id == action }) return false
        pages.resume()
        scroller.abortAnimation()
        scrollTo(target)
        maybeLoadMore(userBrowsing = true)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        return true
    }

    /** [itemAt] is protected, so the touch extensions reach it through this. */
    internal fun hitTest(
        x: Float,
        y: Float,
    ): Int = itemAt(x, y)

    override fun itemAt(
        x: Float,
        y: Float,
    ): Int {
        if (popup != null) return popupAt(x, y)
        if (menu != null) return if (menu?.rect?.contains(x, y) == true) 0 else -1
        val withCandidates = snapshot.candidates.isNotEmpty()
        val control = if (withCandidates) controlCells.indexOfFirst { it.contains(x.toInt(), y.toInt()) } else -1
        if (control >= 0) return entries.size + control
        if (!expanded && readingViewport.contains(x.toInt(), y.toInt())) {
            val reading =
                InlineStrip.hit(x, readingScroll, readingCells.map { it.left }, readingCells.map { it.width() })
            return if (reading >= 0) readingBase + reading else -1
        }
        if (!viewport.contains(x.toInt(), y.toInt())) return -1
        if (!withCandidates) return -1
        val cx = if (expanded) x else x + scroll
        val cy = if (expanded) y + scroll else y
        return cells.indexOfFirst { it.contains(cx, cy) }
    }

    internal fun popupAt(
        x: Float,
        y: Float,
    ): Int = popupCells.indexOfFirst { it.contains(x.toInt(), y.toInt()) }

    // ---- touch ---------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onDown(event)
            MotionEvent.ACTION_MOVE -> return onMove(event)
            MotionEvent.ACTION_UP -> return onUp(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (controls.getOrNull(downControl) == Control.BACKSPACE) cancelPress()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPress()
                scrollingGesture = false
                scrollingReadings = false
                releaseEdges()
                recycleVelocity()
                dismissPopup()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal companion object {
        /** How long a notice stays over the candidates. */
        const val NOTICE_TIMEOUT_MS = 1500L

        /** Resolution of the reveal spring (progress runs 0..1). */
        const val REVEAL_RESOLUTION = 1000f

        /** The drawn height at [progress] of the reveal: the first [rowPx] always, the rest unrolling. */
        fun revealedHeight(
            heightPx: Float,
            rowPx: Float,
            progress: Float,
        ): Float {
            val row = minOf(rowPx, heightPx)
            return row + (heightPx - row) * progress.coerceIn(0f, 1f)
        }

        /** A control's touch area: the Android touch-target minimum. */
        const val CONTROL_TOUCH_DP = 48f

        /** Material icons are 24 dp. */
        const val ICON_DP = 24f

        /** Width of the fixed column (Back, Backspace, Retype) beside the expanded grid. */
        const val EXPANDED_COLUMN_DP = 56f

        /** Pressed fill fade-out after release (iOS: 150 ms for candidates). */
        const val FADE_MS = 150L

        /**
         * A scroll, drag, fling or reveal asks for the next engine page while the end of what was
         * read is this many candidate rows (row heights) short of the viewport's end: far enough
         * that a read lands before an ordinary scroll gets there. The view's own refreshes never
         * read off screen; they only fill a row that does not cover the viewport yet.
         */
        const val LOAD_AHEAD_ROWS = 2f

        /** An accessibility scroll moves this much of the viewport, so one item stays in sight across it. */
        const val ACCESSIBILITY_SCROLL_FRACTION = 0.8f

        /** The delete menu is never narrower than this. */
        const val MENU_MIN_DP = 120f

        /** The delete menu sits this far from the candidate it belongs to. */
        const val MENU_GAP_DP = 4f

        /** Accessibility action id of "delete this word". */
        const val ACTION_DELETE_WORD = 0x53594431

        /** The font scale the candidate text follows, capped so a huge system scale cannot break the row. */
        const val MAX_FONT_SCALE = ImeText.MAX_FONT_SCALE

        fun textScaleFor(context: Context): Float = ImeText.scale(context.resources)
    }
}

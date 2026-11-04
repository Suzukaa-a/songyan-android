package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.AccessibleCanvasView
import app.songyan.ime.ime.ImeText
import app.songyan.ime.ime.KeyNames
import app.songyan.ime.ime.KeyboardDesignTokens
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.ime.PanelIcon
import app.songyan.ime.ime.R
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared chrome of the quick-content panels that replace the key area.
 *
 * Every panel is a title bar with a close button, an optional leading rail of choices (the way
 * the nine-key symbol page does it) and a scrolling content area. The vertical budget comes from
 * [QuickContentPanelLayout], the single place that decides how much room each band gets, so no
 * panel can overflow a short landscape window.
 *
 * Subclasses only supply the rail labels, the content cells and how a cell is drawn; measuring,
 * scrolling, hit testing and the accessibility node per item live here. The view never reads
 * files: the owner pushes data in and calls [invalidate].
 */
abstract class QuickContentPanelView(
    context: Context,
) : AccessibleCanvasView(context) {
    protected val density: Float = resources.displayMetrics.density

    /** Key names drawn on the panel's own keys; see [KeyNames]. */
    internal val keyNames = KeyNames(context)
    protected val textScale: Float = ImeText.scale(resources)

    /** Re-read by [refreshAppearance]: a day/night switch must reach a panel built earlier. */
    protected var palette: KeyboardPalette = KeyboardPalette.from(context)
    protected var metrics: KeyboardDesignTokens = palette.tokens
    protected val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val closeIcon = PanelIcon(context, R.drawable.ic_close)

    /** Shared drawing rectangle; subclasses own it inside their draw call. */
    protected val rect: RectF = RectF()

    /** The user asked to close the panel (title bar button, back navigation or TalkBack). */
    var onClose: () -> Unit = {}

    /** Rail choices; an empty list gives the content the full width. */
    protected var railLabels: List<String> = emptyList()
        private set

    /** Which rail choice is open. */
    protected var selectedRail: Int = 0
        private set

    /** The item under the finger, or -1; drawn as pressed so a tap answers before the commit. */
    private var pressedItem: Int = -1

    /** Fade masks that mark a scrollable rail or grid, rebuilt only when the panel resizes. */
    private var scrimTop: Shader? = null
    private var scrimBottom: Shader? = null
    private var scrimWidth: Int = -1
    private var scrimHeight: Int = -1
    private val scrimPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var railScrollDp: Double = 0.0
    private var contentScrollDp: Double = 0.0

    private var layoutCache: QuickContentPanelLayout? = null
    private var layoutForHeightDp: Double = -1.0
    private var layoutForRailCount: Int = -1

    private var lastTapX = 0f
    private var lastTapY = 0f

    private var downX = 0f
    private var downY = 0f
    private var downRailScrollDp = 0.0
    private var downContentScrollDp = 0.0
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /**
     * Owned per gesture: obtained on ACTION_DOWN and returned to the pool exactly once, because
     * the panel view outlives a single attachment and a pooled tracker must never be recycled twice.
     */
    private var velocityTracker: VelocityTracker? = null

    /** Fling physics of the vertical scroll: the finger's own velocity, then platform friction. */
    private val flingScroller = OverScroller(context)
    private var flingingRail = false
    private var longPressHandled = false

    /** The main looper directly, so a hold also fires for a view that is not attached to a window. */
    private val ui = Handler(Looper.getMainLooper())
    private val longPress =
        Runnable {
            val item = pressedItem
            val cell = item - cellItemBase()
            if (dragging || item < 0 || cell !in 0 until cellCount()) return@Runnable
            if (onCellLongPressed(cell)) {
                longPressHandled = true
                pressedItem = -1
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

    /** The held cell whose repeat is running, or -1; its release or cancel stops it. */
    private var repeatingCell = -1
    private val repeatTick =
        object : Runnable {
            override fun run() {
                val item = pressedItem
                if (repeatingCell < 0 || item != repeatingCell + cellItemBase()) return
                activateTouchedItem(item)
                ui.postDelayed(this, cellRepeatIntervalMs())
            }
        }

    private fun stopRepeat() {
        ui.removeCallbacks(repeatTick)
        repeatingCell = -1
    }

    protected abstract val titleText: String

    /** One key of the optional bar along the panel's bottom edge. */
    protected class BottomKey(
        val label: String,
        val spokenLabel: String,
        val weight: Double,
        val action: () -> Unit,
    )

    /** The keys of the bottom bar, left to right; empty gives the content the whole height. */
    protected open fun bottomKeys(): List<BottomKey> = emptyList()

    /** The drawn names of the bottom keys, left to right. */
    internal fun bottomKeyLabels(): List<String> = bottomKeys().map { it.label }

    private fun bottomBarDp(): Double = if (bottomKeys().isEmpty()) 0.0 else BOTTOM_BAR_DP.toDouble()

    /** The y (px) where the content ends and the bottom bar begins. */
    private fun contentBottomPx(): Float = height - px(bottomBarDp())

    private fun bottomKeyRects(): List<RectF> {
        val keys = bottomKeys()
        if (keys.isEmpty()) return emptyList()
        val gap = BOTTOM_GAP_DP * density
        val total = keys.sumOf { it.weight }.toFloat()
        val unit = (width - gap * (keys.size + 1)) / total
        val top = contentBottomPx() + gap / 2
        val bottom = height - gap / 2
        var x = gap
        return keys.map { key ->
            val box = RectF(x, top, x + key.weight.toFloat() * unit, bottom)
            x = box.right + gap
            box
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val keys = bottomKeys()
        if (keys.isEmpty()) return
        paint.color = palette.background
        canvas.drawRect(0f, contentBottomPx(), width.toFloat(), height.toFloat(), paint)
        val radius = metrics.keyRadiusDp * density
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = BOTTOM_TEXT_SP * textScale
        val boxes = bottomKeyRects()
        for (index in keys.indices) {
            val box = boxes[index]
            paint.color = palette.control
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.color = palette.text
            canvas.drawText(
                keys[index].label,
                box.centerX(),
                box.centerY() - (paint.ascent() + paint.descent()) / 2,
                paint,
            )
        }
        paint.textAlign = Paint.Align.LEFT
    }

    protected abstract fun cellCount(): Int

    /** Equal content columns for the current panel width and budget. */
    protected abstract fun contentColumns(layout: QuickContentPanelLayout): Int

    /** Height of one content cell; the grid tiles downwards from the content top. */
    protected open fun cellHeightDp(layout: QuickContentPanelLayout): Double = layout.symbolCellMinHeight

    /** The first cell of a titled section that starts its own row; -1 when the grid has none. */
    protected open fun sectionStart(): Int = -1

    protected open fun sectionTitle(): String = ""

    /** Room the section's heading takes above its first row. */
    protected fun sectionTitleDp(): Double = if (sectionStart() in 1 until cellCount()) SECTION_TITLE_DP else 0.0

    protected abstract fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    )

    /**
     * A symbol keycap with [text] fitted into it: the look of every panel that lists marks. A whole
     * phrase (a kaomoji) is set smaller and ellipsized rather than overflowing its neighbour.
     */
    protected fun drawSymbolCap(
        canvas: Canvas,
        cell: PanelBox,
        text: String,
    ) {
        // The same keycap radius as the keyboard, so a panel reads as the keyboard's own surface.
        val radius = metrics.keyRadiusDp * density
        paint.style = Paint.Style.FILL
        paint.color = palette.key
        rect.set(
            px(cell.left) + SYMBOL_CAP_INSET_DP * density,
            px(cell.top) + SYMBOL_CAP_INSET_DP * density,
            px(cell.right) - SYMBOL_CAP_INSET_DP * density,
            px(cell.bottom) - SYMBOL_CAP_INSET_DP * density,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.CENTER
        val wholePhrase = text.codePointCount(0, text.length) > 2
        val natural = (if (wholePhrase) SYMBOL_PHRASE_TEXT_SP else SYMBOL_TEXT_SP) * textScale
        val room = rect.width() - SYMBOL_TEXT_PADDING_DP * density
        paint.textSize =
            PanelText.fitSize(natural, PanelText.MIN_FIT_SIZE_SP * textScale, room) { size ->
                paint.textSize = size
                paint.measureText(text)
            }
        val fitted = PanelText.ellipsize(text, room.coerceAtLeast(1f)) { measured -> paint.measureText(measured) }
        canvas.drawText(fitted, px(cell.centerX), px(cell.centerY) - (paint.ascent() + paint.descent()) / 2, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** TalkBack label of one cell. */
    protected abstract fun cellAccessibilityLabel(index: Int): String

    protected open fun onCellActivated(index: Int) {}

    /** A dimmed cell neither answers a touch nor shows the press layer. */
    protected open fun cellEnabled(index: Int): Boolean = true

    /** Named TalkBack actions of one cell, for what a held cell does that a screen reader cannot hold. */
    protected open fun cellCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> = emptyList()

    protected open fun performCellCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean = false

    /** Drops what a hidden panel can rebuild when it is shown again; the layout is solved again on demand. */
    open fun releaseData() {
        releaseLayoutCache()
    }

    fun releaseLayoutCache() {
        layoutCache = null
    }

    /** A cell was held down; true when the panel handled it, which also swallows the tap that follows. */
    protected open fun onCellLongPressed(index: Int): Boolean = false

    protected open fun onRailSelected(index: Int) {}

    /** Copy shown instead of cells when the panel has nothing to show. */
    protected open fun emptyMessage(): String? = null

    /** One action offered with [emptyMessage]; null keeps the empty state informational only. */
    protected open fun emptyActionLabel(): String? = null

    /** Runs [emptyActionLabel]'s action; the owner routes it (e.g. to the settings screen). */
    protected open fun onEmptyAction() {}

    /**
     * What the user is browsing: the open rail choice and both scroll offsets. A data refresh pushes
     * new lists into an open panel, so it must not throw the user back to the first category.
     */
    protected class PanelBrowsingState(
        val rail: Int,
        val railScrollDp: Double,
        val contentScrollDp: Double,
    )

    protected fun captureBrowsingState(): PanelBrowsingState =
        PanelBrowsingState(selectedRail, railScrollDp, contentScrollDp)

    /** Replaces the rail choices and reopens [state]'s choice at the same scroll offsets. */
    protected fun updateRail(
        labels: List<String>,
        state: PanelBrowsingState,
    ) {
        railLabels = labels
        selectedRail = state.rail.coerceIn(0, maxOf(0, labels.lastIndex))
        railScrollDp = state.railScrollDp.coerceAtLeast(0.0)
        contentScrollDp = state.contentScrollDp.coerceAtLeast(0.0)
        layoutCache = null
        clampScrollOffsets()
        // A refresh can shift the open choice (a new first slot); keep it in view.
        if (height > 0) {
            railScrollDp =
                PanelGeometry
                    .railScrollRevealing(selectedRail, layoutNow().railSlotHeight, railScrollDp, contentHeightDp())
                    .coerceIn(0.0, maxRailScrollDp())
        }
        invalidate()
    }

    /** Replaces the rail choices and returns to the first one. */
    protected fun updateRail(labels: List<String>) {
        if (labels == railLabels) return
        railLabels = labels
        selectedRail = 0
        railScrollDp = 0.0
        contentScrollDp = 0.0
        layoutCache = null
        invalidate()
    }

    /** Opens a rail choice and notifies the subclass, which may reload its cells. */
    protected fun selectRail(index: Int) {
        if (index !in railLabels.indices || index == selectedRail) return
        selectedRail = index
        contentScrollDp = 0.0
        invalidate()
        onRailSelected(index)
    }

    /** Back to the top of the content. The rail stays where it is: the user just tapped something in it. */
    protected fun resetScroll() {
        contentScrollDp = 0.0
        invalidate()
    }

    protected fun px(dp: Double): Float = (dp * density).toFloat()

    protected fun titleBarHeightDp(): Double = layoutNow().titleBarHeight

    protected fun contentTopDp(): Double = layoutNow().let { it.titleBarHeight + it.separatorHeight }

    protected fun railWidthDp(): Double = if (railLabels.isEmpty()) 0.0 else layoutNow().railWidth

    protected fun contentHeightDp(): Double =
        (height / density.toDouble() - contentTopDp() - bottomBarDp()).coerceAtLeast(0.0)

    protected fun contentWidthDp(): Double = (width / density.toDouble() - railWidthDp()).coerceAtLeast(0.0)

    /** Where the activation came from, so a wide cell can offer a secondary hit zone (a delete button). */
    protected fun activationX(): Float = lastTapX

    protected fun activationY(): Float = lastTapY

    private fun layoutNow(): QuickContentPanelLayout {
        val heightDp = height / density.toDouble()
        val cached = layoutCache
        if (cached != null && layoutForHeightDp == heightDp && layoutForRailCount == railLabels.size) return cached
        val resolved = QuickContentPanelLayout.resolve(heightDp, railLabels.size)
        layoutCache = resolved
        layoutForHeightDp = heightDp
        layoutForRailCount = railLabels.size
        return resolved
    }

    private fun railBoxes(): List<PanelBox> {
        if (railLabels.isEmpty()) return emptyList()
        val slots = PanelGeometry.railSlots(railLabels.size, contentTopDp(), layoutNow().railSlotHeight, railScrollDp)
        val right = railWidthDp()
        return slots.map { it.copy(right = right) }
    }

    /** Boxes of a panel whose keys do not tile a uniform grid (and never scroll); null uses the grid. */
    protected open fun fixedCellBoxes(): List<PanelBox>? = null

    /** Whether holding the cell repeats it, like the keyboard's Backspace; the first press fires at once. */
    protected open fun cellRepeatsWhenHeld(index: Int): Boolean = false

    /** Wait before a held cell's first repeat (ms). */
    protected open fun cellRepeatDelayMs(): Long = ViewConfiguration.getLongPressTimeout().toLong()

    /** Gap between repeats of a held cell (ms). */
    protected open fun cellRepeatIntervalMs(): Long = ViewConfiguration.getLongPressTimeout().toLong()

    private fun cellBoxes(): List<PanelBox> {
        fixedCellBoxes()?.let { return it }
        val layout = layoutNow()
        val rail = railWidthDp()
        return PanelGeometry.gridCells(
            itemCount = cellCount(),
            columns = contentColumns(layout).coerceAtLeast(1),
            contentWidthDp = (width / density.toDouble() - rail).coerceAtLeast(0.0),
            topDp = contentTopDp(),
            cellHeightDp = cellHeightDp(layout),
            scrollDp = contentScrollDp,
            leftDp = rail,
            sectionStart = sectionStart(),
            sectionTitleDp = SECTION_TITLE_DP,
        )
    }

    private fun maxRailScrollDp(): Double {
        if (railLabels.isEmpty()) return 0.0
        val extent = railLabels.size * layoutNow().railSlotHeight
        return (extent - contentHeightDp()).coerceAtLeast(0.0)
    }

    /** The box of one content cell, including the current scroll offset. */
    protected fun cellBoxAt(index: Int): PanelBox? = cellBoxes().getOrNull(index)

    /** A refreshed list can be shorter than the offset that was kept; never scroll past its end. */
    private fun clampScrollOffsets() {
        railScrollDp = railScrollDp.coerceIn(0.0, maxRailScrollDp())
        contentScrollDp = contentScrollDp.coerceIn(0.0, maxContentScrollDp())
    }

    private fun maxContentScrollDp(): Double {
        if (fixedCellBoxes() != null) return 0.0
        val layout = layoutNow()
        return PanelGeometry.contentScrollRange(
            itemCount = cellCount(),
            columns = contentColumns(layout).coerceAtLeast(1),
            cellHeightDp = cellHeightDp(layout),
            contentHeightDp = contentHeightDp(),
            sectionStart = sectionStart(),
            sectionTitleDp = SECTION_TITLE_DP,
        )
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthSpec)
        val fallback = (metrics.totalHeightDp * density).toInt()
        val height =
            when (MeasureSpec.getMode(heightSpec)) {
                MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightSpec)
                MeasureSpec.AT_MOST -> min(MeasureSpec.getSize(heightSpec), fallback)
                else -> fallback
            }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        val layout = layoutNow()
        drawTitleBar(canvas)
        drawRail(canvas, layout)
        if (railLabels.isNotEmpty()) {
            paint.color = palette.divider
            canvas.drawRect(px(railWidthDp()), px(contentTopDp()), px(railWidthDp()) + 1f, height.toFloat(), paint)
        }
        val cells = cellBoxes()
        if (cells.isEmpty()) {
            drawEmptyState(canvas)
            drawScrollMasks(canvas)
            drawBottomBar(canvas)
            drawPressed(canvas)
            return
        }
        canvas.save()
        canvas.clipRect(px(railWidthDp()), px(contentTopDp()), width.toFloat(), contentBottomPx())
        val bottomDp = height / density.toDouble() - bottomBarDp()
        for (index in cells.indices) {
            val cell = cells[index]
            if (cell.bottom <= contentTopDp() || cell.top >= bottomDp) continue
            drawCell(canvas, cell, index)
        }
        cells.getOrNull(sectionStart())?.takeIf { sectionTitleDp() > 0.0 }?.let { drawSectionTitle(canvas, it) }
        canvas.restore()
        drawScrollMasks(canvas)
        drawBottomBar(canvas)
        drawPressed(canvas)
    }

    /**
     * Fades the rail's own edges when the category list holds more than the panel shows.
     *
     * The content grid never needs this: its rows are sized to the viewport (whole rows only), so a
     * fade over it would only erase readable glyphs. The rail is text in narrow slots, where a short
     * edge fade is the standard "there is more" cue.
     */
    private fun drawScrollMasks(canvas: Canvas) {
        if (maxRailScrollDp() <= 0.0) return
        ensureScrims()
        val railMask = RAIL_SCRIM_DP * density
        val top = px(contentTopDp())
        val bottom = contentBottomPx()
        if (top + railMask > bottom) return
        val railRight = px(railWidthDp())
        if (railScrollDp > 0.0) {
            scrimTop?.let { shader ->
                scrimPaint.shader = shader
                canvas.drawRect(0f, top, railRight, top + railMask, scrimPaint)
                scrimPaint.shader = null
            }
        }
        if (railScrollDp < maxRailScrollDp()) {
            scrimBottom?.let { shader ->
                scrimPaint.shader = shader
                canvas.drawRect(0f, bottom - railMask, railRight, bottom, scrimPaint)
                scrimPaint.shader = null
            }
        }
    }

    /**
     * The empty state: one line of copy and, when the owner offers one, a compact action button.
     *
     * An empty list that only points at the settings app is a dead end inside the keyboard; the
     * button routes through the owner's existing settings entry point instead.
     */
    private fun drawEmptyState(canvas: Canvas) {
        val contentLeft = px(railWidthDp())
        val contentRight = width.toFloat()
        if (contentRight <= contentLeft) return
        val centreX = (contentLeft + contentRight) / 2f
        val centreY = px(contentTopDp()) + (height - px(contentTopDp())) / 2f
        val actionLabel = if (emptyActionVisible()) emptyActionLabel() else null
        val button = emptyActionRect()
        val lines = emptyMessage()?.let { wrapCentre(it, contentRight - contentLeft - 48f * density) }.orEmpty()
        val lineHeight = EMPTY_TEXT_SP * textScale * 1.45f
        val actionBlock =
            if (actionLabel != null) EMPTY_ACTION_GAP_DP * density + button.height() else 0f
        val block = lines.size * lineHeight + actionBlock
        var y = centreY - block / 2f + lineHeight * 0.8f
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = EMPTY_TEXT_SP * textScale
        paint.color = palette.muted
        for (line in lines) {
            canvas.drawText(line, centreX, y, paint)
            y += lineHeight
        }
        if (actionLabel == null || button.isEmpty) return
        y += EMPTY_ACTION_GAP_DP * density
        paint.color = palette.control
        canvas.drawRoundRect(rectOf(button), metrics.keyRadiusDp * density, metrics.keyRadiusDp * density, paint)
        paint.color = palette.text
        paint.textSize = EMPTY_ACTION_TEXT_SP * textScale
        canvas.drawText(
            actionLabel,
            button.exactCenterX(),
            button.exactCenterY() - (paint.ascent() + paint.descent()) / 2,
            paint,
        )
        paint.textAlign = Paint.Align.LEFT
    }

    private fun rectOf(box: Rect): RectF {
        rect.set(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
        return rect
    }

    /** Splits [text] into lines that fit [maxWidthPx], breaking on characters (CJK) and is as-is. */
    private fun wrapCentre(
        text: String,
        maxWidthPx: Float,
    ): List<String> {
        if (maxWidthPx <= 0f) return listOf(text)
        paint.textSize = EMPTY_TEXT_SP * textScale
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (ch in text) {
            line.append(ch)
            if (paint.measureText(line.toString()) > maxWidthPx && line.length > 1) {
                line.deleteCharAt(line.length - 1)
                lines += line.toString()
                line.setLength(0)
                line.append(ch)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** Whether the empty state offers an action (only when there is nothing else to do in the panel). */
    private fun emptyActionVisible(): Boolean = cellCount() == 0 && !emptyActionLabel().isNullOrBlank()

    /** One compact button centred in the content area, just under the copy. */
    private fun emptyActionRect(): Rect {
        val label = emptyActionLabel()
        if (label.isNullOrBlank() || !emptyActionVisible()) return Rect()
        val contentLeft = px(railWidthDp())
        val contentRight = width.toFloat()
        val contentHeight = height - px(contentTopDp())
        if (contentHeight <= 0f || contentRight <= contentLeft) return Rect()
        paint.textSize = EMPTY_ACTION_TEXT_SP * textScale
        val wanted = paint.measureText(label) + EMPTY_ACTION_PADDING_DP * 2 * density
        val buttonWidth = min(wanted, contentRight - contentLeft - 32f * density)
        val height = EMPTY_ACTION_HEIGHT_DP * density
        val centreX = (contentLeft + contentRight) / 2f
        val centreY = px(contentTopDp()) + contentHeight / 2f + EMPTY_ACTION_OFFSET_DP * density
        return Rect(
            (centreX - buttonWidth / 2f).toInt(),
            (centreY - height / 2f).toInt(),
            (centreX + buttonWidth / 2f).toInt(),
            (centreY + height / 2f).toInt(),
        )
    }

    /** The item under the finger gets a state layer, so every panel answers a touch immediately. */
    private fun drawPressed(canvas: Canvas) {
        val index = pressedItem
        if (index !in 0 until itemCount || !itemEnabled(index)) return
        val box = itemBounds(index)
        if (box.isEmpty) return
        paint.color = palette.pressed
        paint.style = Paint.Style.FILL
        rect.set(
            box.left + PRESS_INSET_DP * density,
            box.top + PRESS_INSET_DP * density,
            box.right - PRESS_INSET_DP * density,
            box.bottom - PRESS_INSET_DP * density,
        )
        val radius = metrics.keyRadiusDp * density
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    /** Rebuilds the two edge fades for the current panel size and background colour. */
    private fun ensureScrims() {
        val w = width
        val h = height
        if (scrimWidth == w && scrimHeight == h && scrimTop != null) return
        scrimWidth = w
        scrimHeight = h
        val mask = RAIL_SCRIM_DP * density
        val top = px(contentTopDp())
        val opaque = palette.background
        val clear = opaque and 0x00FFFFFF
        scrimTop =
            LinearGradient(
                0f,
                top,
                0f,
                top + mask,
                intArrayOf(opaque, clear),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        scrimBottom =
            LinearGradient(
                0f,
                h - mask,
                0f,
                h.toFloat(),
                intArrayOf(clear, opaque),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    /** The heading in the gap above [first], the section's first cell, aligned with the tiles' edge. */
    private fun drawSectionTitle(
        canvas: Canvas,
        first: PanelBox,
    ) {
        paint.color = palette.muted
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = SECTION_TEXT_SP * textScale
        val baseline = px(first.top) - px(SECTION_TITLE_DP) / 2 - (paint.ascent() + paint.descent()) / 2
        val left = px(first.left) + SECTION_INSET_DP * density
        val room = (width - left - SECTION_INSET_DP * density).coerceAtLeast(1f)
        canvas.drawText(PanelText.ellipsize(sectionTitle(), room) { paint.measureText(it) }, left, baseline, paint)
    }

    private fun drawTitleBar(canvas: Canvas) {
        val heightPx = px(titleBarHeightDp())
        paint.color = palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), heightPx, paint)
        paint.color = palette.text
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = TITLE_TEXT_SP * textScale
        paint.isFakeBoldText = true
        // The close button owns the right end; the title never runs under it.
        val titleRoom = width - CLOSE_HIT_WIDTH_DP * density - 16f * density
        val title =
            PanelText.ellipsize(titleText, titleRoom) { measured ->
                paint.measureText(measured)
            }
        canvas.drawText(title, 16f * density, heightPx / 2 - (paint.ascent() + paint.descent()) / 2, paint)
        paint.isFakeBoldText = false
        closeIcon.draw(canvas, width - CLOSE_HIT_WIDTH_DP / 2 * density, heightPx / 2, ICON_DP * density, palette.muted)
        paint.color = palette.divider
        canvas.drawRect(0f, heightPx, width.toFloat(), heightPx + px(layoutNow().separatorHeight), paint)
    }

    private fun drawRail(
        canvas: Canvas,
        layout: QuickContentPanelLayout,
    ) {
        if (railLabels.isEmpty()) return
        canvas.save()
        canvas.clipRect(0f, px(contentTopDp()), px(railWidthDp()), contentBottomPx())
        val slots = railBoxes()
        paint.textAlign = Paint.Align.CENTER
        val radius = metrics.keyRadiusDp * density
        val labelRoom = px(railWidthDp()) - RAIL_LABEL_PADDING_DP * density * 2
        for (index in slots.indices) {
            val slot = slots[index]
            val label = railLabels[index]
            if (index == selectedRail) {
                paint.color = palette.candidateSelectedBackground
                rect.set(
                    px(slot.left) + RAIL_PILL_INSET_DP * density,
                    px(slot.top) + RAIL_PILL_INSET_DP * density,
                    px(slot.right) - RAIL_PILL_INSET_DP * density,
                    px(slot.bottom) - RAIL_PILL_INSET_DP * density,
                )
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            paint.color = if (index == selectedRail) palette.candidateSelectedText else palette.muted
            // A long category name shrinks first and only then loses its tail, never the rail edge.
            paint.textSize =
                PanelText.fitSize(RAIL_TEXT_SP * textScale, PanelText.MIN_FIT_SIZE_SP * textScale, labelRoom) { size ->
                    paint.textSize = size
                    paint.measureText(label)
                }
            val fitted =
                PanelText.ellipsize(label, labelRoom.coerceAtLeast(1f)) { measured ->
                    paint.measureText(measured)
                }
            canvas.drawText(
                fitted,
                px(slot.centerX),
                px(slot.centerY) - (paint.ascent() + paint.descent()) / 2,
                paint,
            )
        }
        paint.textAlign = Paint.Align.LEFT
        canvas.restore()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCache = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A new touch stops a fling: direct manipulation always wins over physics.
                flingScroller.forceFinished(true)
                releaseVelocityTracker()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                downRailScrollDp = railScrollDp
                downContentScrollDp = contentScrollDp
                dragging = false
                longPressHandled = false
                pressedItem = itemAt(event.x, event.y)
                ui.removeCallbacks(longPress)
                stopRepeat()
                val heldCell = pressedItem - cellItemBase()
                if (heldCell in 0 until cellCount() && cellRepeatsWhenHeld(heldCell)) {
                    // Like Backspace: the press acts at once, the hold repeats it, the release does nothing more.
                    lastTapX = event.x
                    lastTapY = event.y
                    repeatingCell = heldCell
                    longPressHandled = true
                    activateTouchedItem(pressedItem)
                    ui.postDelayed(repeatTick, cellRepeatDelayMs())
                } else if (pressedItem in cellItemBase() until bottomItemBase()) {
                    ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = event.y - downY
                if (!dragging && repeatingCell < 0 && abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    ui.removeCallbacks(longPress)
                    if (pressedItem >= 0) {
                        pressedItem = -1
                        invalidate()
                    }
                    if (railLabels.isNotEmpty() && downX < px(railWidthDp())) {
                        railScrollDp = (downRailScrollDp - dy / density).coerceIn(0.0, maxRailScrollDp())
                    } else {
                        contentScrollDp = (downContentScrollDp - dy / density).coerceIn(0.0, maxContentScrollDp())
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(longPress)
                stopRepeat()
                if (longPressHandled) {
                    longPressHandled = false
                } else if (dragging) {
                    startFling(event)
                } else {
                    lastTapX = event.x
                    lastTapY = event.y
                    val index = itemAt(event.x, event.y)
                    if (index >= 0) activateTouchedItem(index)
                }
                releaseVelocityTracker()
                dragging = false
                pressedItem = -1
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                releaseVelocityTracker()
                ui.removeCallbacks(longPress)
                longPressHandled = false
                dragging = false
                pressedItem = -1
                invalidate()
                return true
            }
        }
        return true
    }

    /** Continues the scroll with the finger's velocity and the platform's own friction. */
    private fun startFling(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        tracker.addMovement(event)
        tracker.computeCurrentVelocity(1000)
        val velocity = -tracker.yVelocity
        if (abs(velocity) < minimumFlingVelocity) return
        flingingRail = railLabels.isNotEmpty() && downX < px(railWidthDp())
        val maximum = if (flingingRail) maxRailScrollDp() else maxContentScrollDp()
        if (maximum <= 0.0) return
        val from = ((if (flingingRail) railScrollDp else contentScrollDp) * density).toInt()
        flingScroller.fling(
            0,
            from,
            0,
            velocity.toInt(),
            0,
            0,
            0,
            (maximum * density).toInt(),
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!flingScroller.computeScrollOffset()) return
        val dp = flingScroller.currY / density.toDouble()
        if (flingingRail) {
            railScrollDp = dp.coerceIn(0.0, maxRailScrollDp())
        } else {
            contentScrollDp = dp.coerceIn(0.0, maxContentScrollDp())
        }
        invalidate()
        postInvalidateOnAnimation()
    }

    /** Picks up a day/night or font-scale change without losing the open section. */
    open fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        metrics = palette.tokens
        scrimWidth = -1
        scrimHeight = -1
        scrimTop = null
        scrimBottom = null
        invalidate()
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        releaseVelocityTracker()
        flingScroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    protected companion object {
        /** A section heading: a short muted line between rows, like a list's subheader. */
        private const val SECTION_TITLE_DP = 28.0
        private const val SECTION_TEXT_SP = 12f
        private const val SECTION_INSET_DP = 8f

        /** Height of the edge fade that marks a scrollable rail. */
        private const val RAIL_SCRIM_DP = 10f

        /** How far the pressed layer retreats inside an item so neighbours stay distinct. */
        private const val PRESS_INSET_DP = 2f

        /** Gap between a symbol cap and its grid box, so neighbours never touch; text never reaches the cap's edge. */
        private const val SYMBOL_CAP_INSET_DP = 2f
        private const val SYMBOL_TEXT_PADDING_DP = 6f
        private const val SYMBOL_TEXT_SP = 21f
        private const val SYMBOL_PHRASE_TEXT_SP = 13f

        /** Title size of the panel bar; larger than the rail so the two tiers read apart. */
        private const val TITLE_TEXT_SP = 16f

        /** The close button keeps a 48 dp touch target; the glyph is drawn inside it. */
        private const val CLOSE_HIT_WIDTH_DP = 48f

        private const val ICON_DP = 24f

        /** Above the key page and language labels (14 sp): the rail is the page's main navigation. */
        private const val RAIL_TEXT_SP = 15f

        /** The optional bottom bar: a 48 dp touch row with a small gutter between keys. */
        private const val BOTTOM_BAR_DP = 48f
        private const val BOTTOM_GAP_DP = 4f
        private const val BOTTOM_TEXT_SP = 16f

        /** A rail label never touches the pill's edge. */
        private const val RAIL_LABEL_PADDING_DP = 4f

        private const val RAIL_PILL_INSET_DP = 4f

        /** Empty-state copy size and the compact action button under it. */
        private const val EMPTY_TEXT_SP = 13f
        private const val EMPTY_ACTION_TEXT_SP = 14f
        private const val EMPTY_ACTION_HEIGHT_DP = 44f
        private const val EMPTY_ACTION_PADDING_DP = 20f
        private const val EMPTY_ACTION_GAP_DP = 16f
        private const val EMPTY_ACTION_OFFSET_DP = 46f
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override val itemCount: Int
        get() = 1 + (if (emptyActionVisible()) 1 else 0) + railLabels.size + cellCount() + bottomKeys().size

    /** The item index of the empty-state action, or -1 when the panel has no such action. */
    private fun actionItemIndex(): Int = if (emptyActionVisible()) 1 else -1

    private fun railItemBase(): Int = 1 + (if (emptyActionVisible()) 1 else 0)

    private fun cellItemBase(): Int = railItemBase() + railLabels.size

    private fun bottomItemBase(): Int = cellItemBase() + cellCount()

    override fun itemBounds(index: Int): Rect {
        val bottomIndex = index - bottomItemBase()
        if (bottomIndex in bottomKeys().indices) {
            val box = bottomKeyRects()[bottomIndex]
            return Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        }
        if (index == 0) {
            return Rect((width - 48f * density).toInt(), 0, width, px(titleBarHeightDp()).toInt())
        }
        if (index == actionItemIndex()) return emptyActionRect()
        val railIndex = index - railItemBase()
        if (railIndex in railLabels.indices) {
            return boxToRect(railBoxes()[railIndex], clipToContent = true)
        }
        val cellIndex = index - cellItemBase()
        val boxes = cellBoxes()
        if (cellIndex !in boxes.indices) return Rect()
        return boxToRect(boxes[cellIndex], clipToContent = true)
    }

    private fun boxToRect(
        box: PanelBox,
        clipToContent: Boolean,
    ): Rect {
        val topDp = if (clipToContent) maxOf(box.top, contentTopDp()) else box.top
        val bottomDp = maxOf(topDp, minOf(box.bottom, height / density.toDouble() - bottomBarDp()))
        rect.set(px(box.left), px(topDp), px(box.right), px(bottomDp))
        return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
    }

    override fun itemLabel(index: Int): String {
        val bottomIndex = index - bottomItemBase()
        if (bottomIndex in bottomKeys().indices) return bottomKeys()[bottomIndex].spokenLabel
        if (index == 0) return context.getString(R.string.ime_panel_close)
        if (index == actionItemIndex()) return emptyActionLabel().orEmpty()
        val railIndex = index - railItemBase()
        if (railIndex in railLabels.indices) return railLabels[railIndex]
        val cellIndex = index - cellItemBase()
        return if (cellIndex >= 0) cellAccessibilityLabel(cellIndex) else ""
    }

    override fun itemAt(
        x: Float,
        y: Float,
    ): Int {
        if (bottomKeys().isNotEmpty() && y >= contentBottomPx()) {
            val hit = bottomKeyRects().indexOfFirst { x >= it.left && x <= it.right }
            return if (hit >= 0) bottomItemBase() + hit else -1
        }
        if (y < px(titleBarHeightDp()) && x >= width - 48f * density) return 0
        if (railLabels.isNotEmpty() && x < px(railWidthDp()) && y >= px(contentTopDp())) {
            val hit = PanelGeometry.hitIndex(railBoxes(), x / density.toDouble(), y / density.toDouble())
            if (hit >= 0) return railItemBase() + hit
        }
        if (y < px(contentTopDp())) return -1
        val action = actionItemIndex()
        if (action >= 0 && emptyActionRect().contains(x.toInt(), y.toInt())) return action
        val hit = PanelGeometry.hitIndex(cellBoxes(), x / density.toDouble(), y / density.toDouble())
        return if (hit >= 0) cellItemBase() + hit else -1
    }

    override fun itemCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> {
        val cellIndex = index - cellItemBase()
        return if (cellIndex in 0 until cellCount()) cellCustomActions(cellIndex) else emptyList()
    }

    override fun performItemCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean {
        val cellIndex = index - cellItemBase()
        return cellIndex in 0 until cellCount() && performCellCustomAction(cellIndex, actionId)
    }

    override fun itemEnabled(index: Int): Boolean {
        val cellIndex = index - cellItemBase()
        return cellIndex !in 0 until cellCount() || cellEnabled(cellIndex)
    }

    override fun activateItem(index: Int) {
        // Accessibility and keyboard activation must not inherit a previous touch's delete zone.
        val bounds = itemBounds(index)
        lastTapX = bounds.centerX().toFloat()
        lastTapY = bounds.centerY().toFloat()
        activateTouchedItem(index)
    }

    private fun activateTouchedItem(index: Int) {
        if (!itemEnabled(index)) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val bottomIndex = index - bottomItemBase()
        if (bottomIndex in bottomKeys().indices) {
            bottomKeys()[bottomIndex].action()
            return
        }
        if (index == 0) {
            onClose()
            return
        }
        if (index == actionItemIndex()) {
            onEmptyAction()
            return
        }
        val railIndex = index - railItemBase()
        if (railIndex in railLabels.indices) {
            selectRail(railIndex)
            return
        }
        val cellIndex = index - cellItemBase()
        if (cellIndex >= 0) onCellActivated(cellIndex)
    }
}

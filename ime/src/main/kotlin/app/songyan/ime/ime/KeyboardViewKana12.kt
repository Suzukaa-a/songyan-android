package app.songyan.ime.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import kotlin.math.roundToInt

/** Where the kana surface's own dp coordinates sit in the view: its content's top-left and size. */
internal class Kana12Frame(
    val leftPx: Float,
    val topPx: Float,
    val widthDp: Double,
    val heightDp: Double,
)

/** The kana row at key [index], as the last layout placed it; null for a control key. */
internal fun KeyboardView.kana12RowIdAt(index: Int): String? = kana12RowIds.getOrNull(index)

/**
 * Emits the surface's intent for [action]; false keeps the ordinary layout dispatch.
 *
 * Backspace reaches this once at its press and once per repeat tick, because Delete deletes on the
 * press itself (as on every other page); every other key reaches it exactly once, on release.
 */
internal fun KeyboardView.activateKana12(action: LayoutAction): Boolean {
    if (kana12 == null) return false
    val intent = kana12Intent(action) ?: return false
    onKana12Action(intent)
    return true
}

/** True while [press] holds a kana grid key, whose release types the direction it resolved to. */
internal fun KeyboardView.isKana12FlickPress(press: KeyboardView.Press): Boolean =
    kana12 != null && kana12RowIdAt(press.key) != null

/**
 * Follows a kana press's finger and moves the cross preview; false for every other press.
 *
 * The threshold is [Kana12Flick.THRESHOLD_DP], the same 18 dp the merged swipe keys lock at, so a
 * finger roll during a tap never becomes a flick. Leaving the key does not cancel the press: the
 * release's direction is what types, which lets the finger rest on the key and flick from it.
 */
internal fun KeyboardView.trackKana12Flick(
    press: KeyboardView.Press,
    x: Float,
    y: Float,
): Boolean {
    if (!isKana12FlickPress(press)) return false
    val direction =
        Kana12Flick.direction(
            ((x - press.startX) / density).toDouble(),
            ((y - press.startY) / density).toDouble(),
        )
    if (direction == press.kanaDirection) return true
    invalidateKana12Cross(press)
    press.kanaDirection = direction
    // A press that has left the tap is no longer a tap: a second finger must not commit it as one.
    if (direction != Kana12Direction.Center) press.moved = true
    invalidateKana12Cross(press)
    return true
}

/**
 * Types the kana of the release direction. A direction the row has no glyph for (わ行 down) types
 * nothing, because the reference keyboard has no face there.
 */
internal fun KeyboardView.releaseKana12Flick(press: KeyboardView.Press) {
    val rowId = kana12RowIdAt(press.key) ?: return
    if (Kana12Rows.glyph(rowId, press.kanaDirection) == null) return
    onKana12Action(Kana12Action.Kana(rowId, press.kanaDirection))
}

/** Redraws the cross preview's own area while it follows the finger. */
internal fun KeyboardView.invalidateKana12Cross(press: KeyboardView.Press) {
    val rect = kana12CrossRect(press) ?: return
    invalidate(rect.left, rect.top, rect.right, rect.bottom)
}

/** The pressed key's cross preview, in view pixels; null when the preview cannot be placed. */
private fun KeyboardView.kana12CrossRect(press: KeyboardView.Press): Rect? {
    val frame = kana12Frame ?: return null
    if (frame.widthDp <= 0.0 || frame.heightDp <= 0.0) return null
    val surface = Kana12Rect(0.0, 0.0, frame.widthDp, frame.heightDp)
    val pivotX = ((press.startX - frame.leftPx) / density).toDouble()
    val pivotY = ((press.startY - frame.topPx) / density).toDouble()
    val cells = Kana12Flick.previewBounds(pivotX, pivotY, surface)
    val left = cells.values.minOf { it.left }
    val top = cells.values.minOf { it.top }
    val right = cells.values.maxOf { it.right }
    val bottom = cells.values.maxOf { it.bottom }
    val padding = 1f
    return Rect(
        (frame.leftPx + left.toFloat() * density - padding).toInt(),
        (frame.topPx + top.toFloat() * density - padding).toInt(),
        (frame.leftPx + right.toFloat() * density + padding).toInt() + 1,
        (frame.topPx + bottom.toFloat() * density + padding).toInt() + 1,
    )
}

/** The press whose finger is on a kana grid key, or null; the cross preview follows it. */
private fun KeyboardView.kana12Press(): KeyboardView.Press? {
    for (index in 0 until pointers.size()) {
        val press = pointers.valueAt(index)
        if (kana12RowIdAt(press.key) != null) return press
    }
    return null
}

/**
 * The cross under a held kana key: the tap glyph in the centre and one cell per flick target, the
 * selected direction painted as pressed. The whole cross moves to stay on the surface, so a top-row
 * key never draws over the candidate bar. Nothing is measured here: the cells are fixed dp.
 */
internal fun KeyboardView.drawKana12Preview(canvas: Canvas) {
    if (kana12 == null) return
    if (!keyPreviewEnabled || previewSuppressed) return
    val press = kana12Press() ?: return
    val frame = kana12Frame ?: return
    val rowId = kana12RowIdAt(press.key) ?: return
    val surface = Kana12Rect(0.0, 0.0, frame.widthDp, frame.heightDp)
    val pivotX = ((press.startX - frame.leftPx) / density).toDouble()
    val pivotY = ((press.startY - frame.topPx) / density).toDouble()
    val cells = Kana12Flick.previewBounds(pivotX, pivotY, surface)
    val selected = press.kanaDirection
    val radius = metrics.keyRadiusDp * density
    val centerGlyph = Kana12Rows.rowFor(rowId)?.center ?: labels.getOrNull(press.key)
    for ((direction, rect) in cells) {
        val box =
            RectF(
                frame.leftPx + rect.left.toFloat() * density,
                frame.topPx + rect.top.toFloat() * density,
                frame.leftPx + rect.right.toFloat() * density,
                frame.topPx + rect.bottom.toFloat() * density,
            )
        val glyph =
            if (direction == Kana12Direction.Center) {
                centerGlyph
            } else {
                Kana12Rows.glyph(rowId, direction)
            }
        paint.style = Paint.Style.FILL
        if (glyph.isNullOrEmpty()) {
            // The cross keeps its shape where the row has no target, so a missing direction reads
            // as "nothing here" instead of a broken preview.
            paint.color = palette.control
            canvas.drawRoundRect(box, radius, radius, paint)
            continue
        }
        paint.color = if (direction == selected) palette.pressed else palette.key
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.color = if (direction == selected) palette.accentText else palette.text
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = minOf(metrics.keyLabelTextSizeSp * textScale, box.height() * 0.5f)
        val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2
        canvas.save()
        canvas.clipRect(box)
        canvas.drawText(glyph, box.centerX(), baseline, paint)
        canvas.restore()
    }
    paint.style = Paint.Style.FILL
}

/** One named TalkBack action per direction the key's row actually carries. */
internal fun KeyboardView.kana12CustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> {
    if (kana12 == null) return emptyList()
    val rowId = kana12RowIdAt(index) ?: return emptyList()
    return KANA12_DIRECTIONS.mapIndexedNotNull { ordinal, direction ->
        Kana12Rows.glyph(rowId, direction)?.let { glyph ->
            AccessibilityNodeInfo.AccessibilityAction(KANA12_DIRECTION_ACTION_IDS[ordinal], glyph)
        }
    }
}

/** Performs one of [kana12CustomActions]; false when the key or the action is not one of them. */
internal fun KeyboardView.performKana12CustomAction(
    index: Int,
    actionId: Int,
): Boolean {
    if (kana12 == null) return false
    val ordinal = KANA12_DIRECTION_ACTION_IDS.indexOf(actionId)
    if (ordinal < 0) return false
    val rowId = kana12RowIdAt(index) ?: return false
    val direction = KANA12_DIRECTIONS[ordinal]
    if (Kana12Rows.glyph(rowId, direction) == null) return false
    val action = keys.getOrNull(index)?.action ?: return false
    // A named action is a press of its own: it ticks like a tap of the same key.
    onFeedback(action, FeedbackKind.PRESS)
    onKana12Action(Kana12Action.Kana(rowId, direction))
    onReleaseFeedback()
    return true
}

/**
 * The spoken labels of the surface. Kana keys read their input language's row name (あ行) plus the
 * authored face, so TalkBack names both the row and what a tap types. Control keys keep the names
 * the caller's resources and the current input title already give them; only the caret key, which
 * draws an arrow, is renamed for the ear.
 */
internal fun KeyboardView.applyKana12KeyFaces() {
    if (kana12 == null) return
    spokenLabels =
        spokenLabels.mapIndexed { index, spoken ->
            val action = keys.getOrNull(index)?.action
            when {
                action == null -> spoken
                isKana12CaretKey(action) -> context.getString(R.string.ime_cursor_left)
                else -> {
                    val rowName = kana12RowIdAt(index)?.let(Kana12Rows::rowFor)?.spokenName
                    when {
                        rowName == null -> spoken
                        spoken.isEmpty() || spoken == rowName -> rowName
                        else -> rowName + " " + spoken
                    }
                }
            }
        }
}

/**
 * The twelve-key surface of [definition], or null for every other page.
 *
 * The page builder keeps a letter page's rows but not the main layout's side rails, so when the page
 * carries none the selected main layout supplies them; the controller names it in [KeyboardFaces].
 */
internal fun KeyboardView.kana12SurfaceFor(definition: LayoutDefinition): Kana12Surface? {
    if (definition.rows.none { row -> row.keys.any { isKana12KanaRow(it.action) } }) return null
    val mainLayout = if (definition.auxiliaryAreas.isNullOrEmpty()) kana12MainLayout() else null
    return Kana12Surface.of(definition, mainLayout)
}

/** The selected main layout, read by the id the controller reports; null while it is not resolved. */
private fun KeyboardView.kana12MainLayout(): LayoutDefinition? {
    val id = faces.mainLayoutId ?: return null
    return KeyboardLayoutCatalog.snapshot.value.registry
        .resolvedLayout(id)
}

/**
 * Places the twelve-key surface on the key area and hands its slots to the drawing, touch and
 * accessibility passes that every page uses. The grid and the rails come from the layout's own
 * keys; [Kana12Geometry] only says where each slot sits.
 */
internal fun KeyboardView.rebuildKana12(surface: Kana12Surface) {
    if (width <= 0) return
    val frame = contentFrame(null)
    val widthDp = (frame.width / density).toDouble()
    val heightDp = keyAreaHeightDp(heightRows).toDouble()
    if (widthDp <= 0.0 || heightDp <= 0.0) return
    val leading = kana12LeadingRail(surface.leading, showsGlobe)
    val trailing = surface.trailing
    val geometry =
        Kana12Geometry.resolve(
            widthDp = widthDp,
            heightDp = heightDp,
            keyGapHorizontalDp = (scaledKeyGapHorizontal(null) / density).toDouble(),
            keyGapVerticalDp = metrics.keyGapVerticalDp.toDouble(),
            leftRailBands = railBands(leading, Kana12Geometry.DEFAULT_LEFT_BANDS),
            rightRailBands = railBands(trailing, Kana12Geometry.DEFAULT_RIGHT_BANDS),
        )
    rowOffsetY = metrics.keyAreaBottomGapDp / 2 * density
    insetX = (geometry.capInsetHorizontal * density).toFloat()
    insetY = (geometry.capInsetVertical * density).toFloat()
    kana12Frame = Kana12Frame(frame.left, rowOffsetY, widthDp, heightDp)
    val placedKeys = ArrayList<LayoutKey>(geometry.slots.size)
    val placedRowIds = ArrayList<String?>(geometry.slots.size)
    val placedBounds = ArrayList<KeyBounds>(geometry.slots.size)
    var leadingIndex = 0
    var gridIndex = 0
    var trailingIndex = 0
    for (slot in geometry.slots) {
        val key: LayoutKey
        val rowId: String?
        when (slot.region) {
            Kana12Region.LeftRail -> {
                key = leading.getOrNull(leadingIndex++) ?: continue
                rowId = null
            }
            Kana12Region.Grid -> {
                val index = gridIndex++
                key = surface.gridKeys.getOrNull(index) ?: continue
                rowId = surface.gridRowIds.getOrNull(index)
            }
            Kana12Region.RightRail -> {
                key = trailing.getOrNull(trailingIndex++) ?: continue
                rowId = null
            }
        }
        placedKeys += key
        placedRowIds += rowId
        placedBounds +=
            KeyBounds(
                frame.left + slot.bounds.left.toFloat() * density,
                rowOffsetY + slot.bounds.top.toFloat() * density,
                frame.left + slot.bounds.right.toFloat() * density,
                rowOffsetY + slot.bounds.bottom.toFloat() * density,
            )
    }
    keys = placedKeys
    kana12RowIds = placedRowIds
    bounds = placedBounds
    applyKeyFaces()
    // The whole content width is owned, including the outer padding, so no tap is dead.
    hitBounds = KeyHitZones.fill(bounds, frame.left, 0f, frame.left + frame.width, hitBottom())
    settleKeySet(kana12KeySetSignature())
}

/**
 * The rail spans the layout asks for: an authored width weight is a vertical span, and a rail with
 * no authored weights keeps its default split. A rail with fewer keys than bands (the system switch
 * hidden) gives the surplus band to its first key, the shell's own rail rule.
 */
private fun railBands(
    keys: List<LayoutKey>,
    defaults: List<Int>,
): List<Int> {
    if (keys.isEmpty()) return emptyList()
    if (keys.size == defaults.size) {
        val authored = keys.map { it.widthWeight.roundToInt() }
        if (authored.all { span -> span > 0 } && authored.sum() == Kana12Geometry.BAND_COUNT) return authored
        return defaults
    }
    if (keys.size > Kana12Geometry.BAND_COUNT) return List(keys.size) { 1 }
    return List(keys.size) { index -> if (index == 0) Kana12Geometry.BAND_COUNT - (keys.size - 1) else 1 }
}

/** The physical key set of the twelve-key surface: page and layout, never a label. */
private fun KeyboardView.kana12KeySetSignature(): KeySetSignature =
    KeySetSignature(
        phone = false,
        page = null,
        layoutId = layout?.id.orEmpty(),
        numeric = false,
        slots = bounds,
    )

/** The four flick directions TalkBack offers; the tap is the node's own click. */
private val KANA12_DIRECTIONS =
    listOf(
        Kana12Direction.Left,
        Kana12Direction.Up,
        Kana12Direction.Right,
        Kana12Direction.Down,
    )

/**
 * Custom action ids are resource ids because Android 10 rejects anything else; the alternate
 * actions the popup already declares are reused, since their labels are supplied per action and a
 * kana key never opens that popup.
 */
private val KANA12_DIRECTION_ACTION_IDS =
    intArrayOf(
        R.id.ime_alternate_action_0,
        R.id.ime_alternate_action_1,
        R.id.ime_alternate_action_2,
        R.id.ime_alternate_action_3,
    )

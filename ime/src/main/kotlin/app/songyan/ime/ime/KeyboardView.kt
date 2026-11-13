package app.songyan.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SparseArray
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LetterSwipeTracker

/** What the key faces must reflect beyond the layout data itself. */
data class KeyboardFaces(
    /** English direct input: Shift applies and the language key names the other language. */
    val english: Boolean = false,
    /** The page above the letters (123 / symbols); null is the letter page. */
    val page: KeyboardPageKind? = null,
    /** The symbol branch the 123 / symbol pages are drawn in. */
    val symbolMarksAscii: Boolean = false,
    /** The nine-key shell: a digit key's corner badge is part of its spoken label. */
    val phone: Boolean = false,
    /** The symbol page's corner lock is on. */
    val symbolLocked: Boolean = false,
    /** Input language of the running schema; it decides the automatic key names. */
    val language: InputLanguage = InputLanguage.ZH_HANS,
    /** A composition is open: the space and return keys name what they do next. */
    val composing: Boolean = false,
    /** The main layout the keyboard draws, so a jump name can say which board its page belongs to. */
    val mainLayoutId: String? = null,
    /** The keyboard just switched to, named on the space bar for a moment; null shows its usual face. */
    val switchNotice: String? = null,
)

class KeyboardView(
    context: Context,
) : AccessibleCanvasView(context) {
    internal val density = resources.displayMetrics.density
    internal var palette = KeyboardPalette.from(context)
    internal val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.typeface =
                KeyboardTypefaces.of(palette.tokens.fontFamily, palette.tokens.fontWeight)
        }
    internal val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val iconPath = Path()
    internal var textScale = ImeText.scale(resources)
    internal var metrics = palette.tokens
    internal val drawBounds = RectF()
    internal val pointers = SparseArray<Press>()
    internal var keys = emptyList<LayoutKey>()
    internal var bounds = emptyList<KeyBounds>()

    /** Touch targets: the same slots grown to tile the key area with no dead strip. */
    internal var hitBounds = emptyList<KeyBounds>()
    internal var labels = emptyList<String>()
    internal var spokenLabels = emptyList<String>()
    internal var identities = emptyList<String>()
    internal var swipeCodes = emptyList<List<String>?>()
    internal var swipeLetters = emptyList<List<String>?>()
    internal var layout: LayoutDefinition? = null

    /** The twelve-key kana surface the selected layout describes; null for every other layout. */
    internal var kana12: Kana12Surface? = null

    /** The kana row each placed key types, aligned with [keys]; null for the surface's controls. */
    internal var kana12RowIds: List<String?> = emptyList()

    /** Where the kana surface's own dp coordinates sit in this view. */
    internal var kana12Frame: Kana12Frame? = null

    /** The authored side rails drawn beside [layout]'s rows; null for every page without any. */
    internal var rails: KeyboardPages.PageRails? = null

    /** Where the key area sits on a surface wider than a phone. */
    var dock: KeyboardContentDock = KeyboardContentDock.CENTER
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** The key area width (dp) above which an automatic split shows. */
    var splitWidthDp: Int = KeyboardGeometry.SPLIT_WIDTH_DEFAULT_DP
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** The blank margin on each side of a large surface, in per mille of its width; the candidate bar and toolbar keep the full width. */
    var sideMarginPerMille: Int = KeyboardGeometry.SIDE_MARGIN_DEFAULT_PER_MILLE
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** Whether pages split into two halves on a wide surface. */
    var splitMode: SplitMode = SplitMode.LAYOUT
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** Wide boards grow the skin's gaps, labels and corners with their columns ([KeyGapScale]); on by default. */
    var keyGapScaling: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** The widest an unsplit key area grows on a tablet (dp); see [WideSurface.contentCapDp]. */
    var wideContentCapDp: Double = Double.POSITIVE_INFINITY
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    /** The column scale of the board last laid out ([columnScale]); labels and corners follow it. */
    internal var boardColumnScale = 1.0

    /** The keycap corner radius in px on this board. */
    internal fun keyRadiusPx(): Float = (metrics.keyRadiusDp * KeyGapScale.corner(boardColumnScale)).toFloat() * density

    /** The globe icon, loaded when the first switch key draws. */
    internal val globeIcon: Drawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_toolbar_globe)?.mutate()
    }

    /** Whether a layout's input-method switch key is drawn; off unless the user turned it on. */
    var showsGlobe: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            relayout()
        }

    private fun relayout() {
        rebuild()
        invalidate()
    }

    internal val keyNames = KeyNames(context)
    internal var faces = KeyboardFaces()

    /** Input language the key paints last applied; null until the first face pass. */
    internal var textLanguage: InputLanguage? = null
    internal var shifted = false
    internal var capsLocked = false
    internal var releases = LongArray(0)
    internal var actionLabel = context.getString(R.string.ime_enter)
    internal var lastShift = 0L

    /** Drawn keycap inset inside its slot; it turns the slot gutter into the visual gutter (px). */
    internal var insetX = 0f
    internal var insetY = 0f

    /** Half the band kept below the last row, so the key area is optically centred (px). */
    internal var rowOffsetY = 0f

    /** Signature of the physical key set; a change means a page or layout switch, not a relabel. */
    internal var keySet: KeySetSignature? = null

    /** Space cursor-drag cue; driven by the view while a space press moves the caret. */
    internal var cursorDragging = false

    /** Where the preview, the long-press popup and the swipe letter are drawn; null draws none. */
    internal var popupLayer: KeyPopupLayer? = null

    /** The user's key hold delay in ms, or null to follow the system; backspace repeat ignores it. */
    var longPressOverrideMs: Int? = null

    /** Sliding sideways over any key moves the cursor while nothing is being typed (a user choice). */
    var cursorSwipeAnywhere = false

    /** Which vertical flick types the hint drawn on a character key; off leaves only taps and long presses. */
    var flickDirection = FlickDirection.UP

    /** The flicked key turning into its hint; the popup layer's bubble reads the same progress. */
    internal val flickMorph = FlickMorph()

    /** Fades every label while the space bar drags the caret. */
    internal val labelFade = LabelFade()

    /** Magnified preview of a tapped key; the keyboard turns it off in a private editor. */
    var keyPreviewEnabled = true

    /** True while the editor must not echo what is typed (a password field): no preview. */
    var previewSuppressed = false

    internal var popupKey = -1
    internal var popupOwner = NO_POINTER
    internal var popupIndex = -1
    internal var popupOptions: List<LayoutOption> = emptyList()
    internal var popupBounds = KeyBounds(0f, 0f, 0f, 0f)
    internal var popupMirrored = false

    /** Layer position of this view, for converting key bounds into the popup layer's coordinates. */
    internal val layerOffset = IntArray(2)
    internal val ownLocation = IntArray(2)
    internal val layerLocation = IntArray(2)

    /** Fitted label size of every key (px), solved once per layout instead of every frame. */
    internal var labelSizes = FloatArray(0)

    /**
     * Ink-centring of a full-width punctuation label (，。？): its glyph sits in one corner of
     * a square em box, so centring the box leaves the mark off-centre. NaN when the label is
     * drawn centred on its advance like any other text.
     */
    internal var labelInkX = FloatArray(0)
    internal var labelInkY = FloatArray(0)
    internal var hintSizes = FloatArray(0)

    /** Where a corner hint too wide for one line breaks onto a second (after its '/'), or -1. */
    internal var hintBreaks = IntArray(0)

    /**
     * The user's shuangpin hints, drawn as a small line under each letter (one column per letter on
     * a merged key) with the letter lifted above it; the corner keeps the long-press mark.
     */
    var syllableHints: Map<Char, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            fitLabels()
            invalidate()
        }

    private val liftedBounds = RectF()

    /** Per key: the solved shuangpin hint line, or null when the key carries none. */
    internal var syllableLines: Array<SyllableHintLine?> = emptyArray()

    /** Black shadow under a resting keycap; only its alpha changes per key, so nothing allocates in onDraw. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.BLACK }
    private val shadowBounds = RectF()
    private val strokeBounds = RectF()

    private fun applyTypeface() {
        val face = KeyboardTypefaces.of(metrics.fontFamily, metrics.fontWeight)
        paint.typeface = face
        measurePaint.typeface = face
    }

    internal val measurePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.typeface =
                KeyboardTypefaces.of(palette.tokens.fontFamily, palette.tokens.fontWeight)
        }
    internal var pressedFlags = BooleanArray(0)

    /**
     * Press timers (long press, repeat). A plain Handler rather than View.postDelayed: the view queue
     * only runs while attached to a window, and a hold must also work for a view that is laid out
     * off-window.
     */
    internal val timers = android.os.Handler(android.os.Looper.getMainLooper())
    private val clipRect = Rect()
    internal var lastTick = 0L

    /** Accessibility-only caret mode: a virtual space node cannot be held and dragged. */
    private var accessibleCursorMode = false

    /** Accessibility-only repeat of the Backspace key; a virtual node cannot hold a finger. */
    private var accessibleRepeatKey = -1
    private var accessibleRepeatSteps = 0

    /** Bumped by every burst start and stop: a queued step only runs for the burst that queued it. */
    private var accessibleRepeatToken = 0

    /** Whether the composition the burst started in was still there; the burst stops when it ends. */
    private var accessibleRepeatComposing = false
    private val accessibleRepeat =
        object : Runnable {
            override fun run() {
                val index = accessibleRepeatKey
                if (index !in keys.indices ||
                    accessibleRepeatSteps >= MAX_ACCESSIBILITY_REPEAT_STEPS ||
                    (accessibleRepeatComposing && !composing())
                ) {
                    stopAccessibilityRepeat()
                    return
                }
                accessibleRepeatSteps += 1
                // A step belongs to the burst that queued it: once that burst is stopped (another
                // action, touch exploration, its composition gone) a newer burst on the same key
                // must not run it, and it must not delete text the composition left behind.
                val token = accessibleRepeatToken
                val startedComposing = accessibleRepeatComposing
                onRepeatAction(keySnapshot(index).action) {
                    token == accessibleRepeatToken &&
                        !(startedComposing && !composing())
                }
                timers.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }

    var onAction: (LayoutAction) -> Unit = {}
    var onT9FirstKey: (LayoutAction) -> Unit = { onAction(it) }

    /**
     * One press on the twelve-key kana surface, emitted once per release (Delete once per press and
     * once per repeat tick, like every other page's Delete). The surface never touches the engine:
     * the caller's kana model decides what to send, the same way [onAction] carries other pages.
     */
    var onKana12Action: (Kana12Action) -> Unit = {}

    /**
     * One step of a held key. [stillHeld] is asked when the step runs, so a hold that ended while
     * the step was queued (a release, a cancel, a second finger, the composition it was deleting)
     * types nothing more. The default treats a step as a plain press.
     */
    var onRepeatAction: (action: LayoutAction, stillHeld: () -> Boolean) -> Unit =
        { action, _ -> onAction(action) }
    internal var t9FirstKeyIndex = -1
    var onCursor: (Int) -> Unit = {}

    /** What a key event feels like: [FeedbackKind] says which tick, the action picks the sound. */
    var onFeedback: (LayoutAction, FeedbackKind) -> Unit = { _, _ -> }

    /** True while the engine holds a composition; a held delete stops when it has erased one. */
    var composing: () -> Boolean = { false }

    /** True while anything is being typed, including raw input with no composition yet. */
    var typing: () -> Boolean = { false }

    // Panels and menus own their touches even while the underlying keys remain in the view tree.
    internal var cursorDragIdle: () -> Boolean = { true }
    var onDoubleShift: () -> Unit = {}
    var onLanguageMenu: () -> Unit = {}

    /** Long press on the input-method switch key: the system's picker. */
    var onPickInputMethod: () -> Unit = {}

    /** Long press on the 符号 key: the quick-content symbol panel instead of another page. */
    var onSymbolPanel: () -> Unit = {}
    var onReleaseFeedback: () -> Unit = {}

    /** A nine-key rail choice: the slot it sits in and its label, as drawn. */
    var onRailChoice: (position: Int, label: String) -> Unit = { _, _ -> }

    /** The rail was dragged vertically by whole slots (positive reveals later choices). */
    var onRailScroll: (steps: Int) -> Unit = {}

    /** The alternates popup opened or closed: Back must close it before hiding the keyboard. */
    var onPopupChanged: () -> Unit = {}

    /** True while an alternates popup is open. */
    val popupOpen: Boolean get() = popupKey >= 0

    /** Non-null while the 9-key shell draws instead of the row-based pages. */
    internal var shell: ShellModel? = null

    /** Screen position the key boxes were last logged at; see [logKeyBoundsIfMoved]. */
    internal var loggedBoundsAt = Long.MIN_VALUE

    /**
     * Rows added on top of the usual four: a password field's digit row gets room of its own
     * instead of squeezing the letter rows. Only that page is taller; 123 and symbols keep their
     * own height rather than stretching their keys.
     */
    var extraRows: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** The rows the key area is tall. */
    internal val heightRows: Int get() = PHONE_ROWS + extraRows

    /** A shell of placed keys: the nine-key shell or a dial pad. [key] identifies it for equality. */
    internal class ShellModel(
        val key: Any,
        val numeric: Boolean,
        val build: (widthDp: Float, heightDp: Float) -> PhoneKeypadLayout.Shell?,
    )

    internal var railIndices: Set<Int> = emptySet()
    internal var railChoices: List<String> = emptyList()

    /** Every engine-backed rail choice, including the ones the three-slot window does not show. */
    internal var railChoiceCount: Int = 0

    /** The first choice the window shows; assistive technology scrolls this by whole slots. */
    internal var railChoiceFirst: Int = 0

    /** How many leading rail choices are already applied: drawn as chosen, not tappable. */
    internal var railSelectedCount: Int = 0
    internal var swipeConfig: MergedSwipeConfig? = null

    internal class Press(
        val pointerId: Int,
        val snapshot: KeyPressSnapshot,
        val startedAt: Long,
        val startX: Float,
        val startY: Float,
        var key: Int,
        var lastX: Float,
        var moved: Boolean = false,
        var held: Boolean = false,
        /** Caret mode is on for this press: a hold armed it, or a deliberate sideways drag started it. */
        var cursorEntered: Boolean = false,
        // Closing an overlay must not let the same finger start or resume a caret drag.
        var cursorDragBlocked: Boolean = false,
        /** The caret drag's last speed sample: where and when, and whether it was fast. */
        var cursorSampleX: Float = 0f,
        var cursorSampleAt: Long = 0L,
        var cursorFast: Boolean = false,
        /** What this key's flick types (its drawn hint), fixed at the press; null when it has none. */
        var flickTarget: LayoutOption? = null,
        /** The flick is past its threshold: release types [flickTarget]. */
        var flickArmed: Boolean = false,
        /**
         * A vertical flick past the threshold that has nothing to type that way: the release types
         * nothing, not the key the finger ended on.
         */
        var flickVoid: Boolean = false,
        /** A rail press that turned into a vertical scroll: it never selects on release. */
        var railDragged: Boolean = false,
        var railSteps: Int = 0,
        var task: Runnable? = null,
        var tracker: LetterSwipeTracker? = null,
        /** A Delete press deleted at its DOWN while a composition was in progress (see [handleDown]). */
        var startedComposing: Boolean = false,
        /** The flick direction a kana press currently resolves to, for the cross preview. */
        var kanaDirection: Kana12Direction = Kana12Direction.Center,
    ) {
        /** Re-points the pressed visual after a rebuild that only relabelled the same keys. */
        fun rebase(index: Int) {
            key = index
        }
    }

    override val itemCount: Int get() = keys.size

    internal fun update(
        definition: LayoutDefinition,
        rails: KeyboardPages.PageRails? = null,
        shift: Boolean,
        enterLabel: String,
        locked: Boolean = false,
        faces: KeyboardFaces = KeyboardFaces(),
        swipe: MergedSwipeConfig? = null,
    ) {
        // An opening or closing composition, or the switch notice, only renames keys; it must not
        // solve the rows again.
        val sameKeys =
            shell == null &&
                layout === definition &&
                this.faces.copy(composing = faces.composing, switchNotice = faces.switchNotice) == faces &&
                swipeConfig === swipe
        if (sameKeys &&
            this.faces.composing == faces.composing &&
            this.faces.switchNotice == faces.switchNotice &&
            shifted == shift &&
            capsLocked == locked &&
            actionLabel == enterLabel
        ) {
            // The controller renders after every key; an unchanged page must cost nothing.
            return
        }
        shifted = shift
        capsLocked = locked
        actionLabel = enterLabel
        if (sameKeys) {
            // Only the faces changed (shift, caps lock, the editor's action label, the composition, the
            // switch notice):
            // the same keys in the same places, so there is nothing to solve again.
            this.faces = faces
            applyKeyFaces()
            invalidate()
            announceItemsChanged()
            return
        }
        shell = null
        t9FirstKeyIndex = -1
        railIndices = emptySet()
        railChoices = emptyList()
        railSelectedCount = 0
        railChoiceCount = 0
        railChoiceFirst = 0
        layout = definition
        this.faces = faces
        // A twelve-key kana board is placed slot by slot from its authored grid and rails; every
        // other page keeps the row solver.
        val kanaSurface = kana12SurfaceFor(definition)
        kana12 = kanaSurface
        this.rails = if (kanaSurface == null) rails else null
        swipeConfig = swipe
        keys =
            if (kanaSurface == null) {
                KeyRowSolver.columns(definition.rows, showsGlobe).flatten()
            } else {
                emptyList()
            }
        rebuild()
        logKeyBoundsOnce()
        invalidate()
        announceItemsChanged()
    }

    /**
     * Draws the nine-key shell: one 3x3 grid, both rails and the phone bottom row, all positioned by
     * [PhoneKeypadLayout]. [railChoices] replace the leading-rail symbol slots while composing.
     */
    fun updatePhone(
        config: PhoneKeypadLayout.Config,
        shift: Boolean,
        enterLabel: String,
        locked: Boolean = false,
        faces: KeyboardFaces = KeyboardFaces(),
        railChoices: List<String> = emptyList(),
        railSelected: Int = 0,
        railChoiceCount: Int = railChoices.size,
        railChoiceFirst: Int = 0,
    ) {
        if (shell?.key == config &&
            layout == null &&
            this.railChoices == railChoices &&
            railSelectedCount == railSelected &&
            this.railChoiceCount == railChoiceCount &&
            this.railChoiceFirst == railChoiceFirst &&
            shifted == shift &&
            capsLocked == locked &&
            actionLabel == enterLabel &&
            this.faces == faces
        ) {
            return
        }
        layout = null
        kana12 = null
        rails = null
        shell =
            ShellModel(config, config.numeric) { width, height ->
                PhoneKeypadLayout.build(config.copy(width = width, height = height))
            }
        this.railChoices = railChoices
        railSelectedCount = railSelected
        this.railChoiceCount = railChoiceCount
        this.railChoiceFirst = railChoiceFirst
        shifted = shift
        capsLocked = locked
        actionLabel = enterLabel
        this.faces = faces
        swipeConfig = null
        rebuild()
        logKeyBoundsOnce()
        invalidate()
        announceItemsChanged()
    }

    /** Draws the dial pad of a number, phone or date editor. */
    fun updateDial(
        pad: EditorDigitLayouts.Pad,
        enterLabel: String,
        faces: KeyboardFaces = KeyboardFaces(),
    ) {
        if (shell?.key == pad && layout == null && actionLabel == enterLabel && this.faces == faces) return
        layout = null
        kana12 = null
        rails = null
        shell = ShellModel(pad, numeric = true) { width, height -> EditorDigitLayouts.build(pad, width, height) }
        railChoices = emptyList()
        railSelectedCount = 0
        railChoiceCount = 0
        railChoiceFirst = 0
        shifted = false
        capsLocked = false
        actionLabel = enterLabel
        this.faces = faces
        swipeConfig = null
        rebuild()
        logKeyBoundsOnce()
        invalidate()
        announceItemsChanged()
    }

    /**
     * Space cursor-drag mode, for a host that wants to force the cue (tests); the view normally
     * drives it itself while a held space press moves the caret. Decorative only: it changes no
     * layout, size, key set or touch route.
     */
    fun setCursorDragging(active: Boolean) {
        if (cursorDragging == active) return
        cursorDragging = active
        labelFade.set(active, SystemClock.uptimeMillis(), motionScale())
        invalidate()
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthSpec)
        // Every page has the height of four rows (five with a password digit row); a page with more
        // rows than that splits it.
        val heightDp = keyAreaHeightDp(heightRows) + metrics.keyAreaBottomGapDp + metrics.keyAreaExtraBottomDp
        setMeasuredDimension(w, (heightDp * density).toInt())
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        rebuild()
        logKeyBoundsOnce()
    }

    /**
     * The system's animator duration scale: 0 draws every final state immediately and starts no
     * frame loop, 0.5 halves every duration, 1 is normal.
     */
    private fun motionScale(): Float = ImeMotion.scale

    /**
     * What stands in place of the keys while the engine cannot take input, or null. Keys are neither
     * drawn, touched nor announced then, so nothing can be typed; the height stays the key area's.
     */
    var notice: String? = null
        set(value) {
            if (field == value) return
            field = value
            cancelTouches()
            importantForAccessibility =
                if (value == null) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            invalidate()
        }

    private fun drawNotice(
        canvas: Canvas,
        text: String,
    ) {
        val noticePaint =
            TextPaint(paint).apply {
                style = Paint.Style.FILL
                color = palette.muted
                textAlign = Paint.Align.LEFT
                textSize = metrics.candidateTextSizeSp * resources.configuration.fontScale * this@KeyboardView.density
            }
        val inset = 16f * density
        val textWidth = (width - 2 * inset).toInt().coerceAtLeast(1)
        val layout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, noticePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        val saved = canvas.save()
        canvas.translate(inset, ((height - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onDraw(canvas: Canvas) {
        if (ImePerf.isLogging) logKeyBoundsIfMoved()
        canvas.drawColor(palette.surface)
        notice?.let {
            drawNotice(canvas, it)
            return
        }
        val now = SystemClock.uptimeMillis()
        val motionScale = motionScale()
        canvas.getClipBounds(clipRect)
        java.util.Arrays.fill(pressedFlags, false)
        for (p in 0 until pointers.size()) {
            val key = pointers.valueAt(p).key
            if (key in pressedFlags.indices) pressedFlags[key] = true
        }
        var animating = false
        drawRailColumn(canvas)
        for (i in keys.indices) {
            if (i >= bounds.size) break
            val box = bounds[i]
            // Frames are requested per animating key, drawn or not, so a culled key cannot stall.
            if (!pressedFlags[i] && KeyMotion.releaseAnimating(now, releases[i], motionScale)) {
                animating = true
                dirtyUnion(box)
            }
            if (labelFade.animating(now, motionScale)) {
                animating = true
                dirtyUnion(box)
            }
            val flicked = i == flickMorph.key
            if (flicked && flickMorph.animating(now, motionScale)) {
                animating = true
                dirtyUnion(box)
            }
            // A dirty-rect invalidate (one key pressed or released) skips the keys outside it.
            if (box.right < clipRect.left ||
                box.left > clipRect.right ||
                box.bottom < clipRect.top ||
                box.top > clipRect.bottom
            ) {
                continue
            }
            val key = keys[i]
            val pressed = pressedFlags[i]
            val isLetter =
                key.action.type == LayoutActionKind.CODE ||
                    key.action.type == LayoutActionKind.LITERAL ||
                    isKana12KeyFace(key.action)
            val isSpace = key.action.type == LayoutActionKind.SPACE
            val emphasized =
                key.action.type == LayoutActionKind.ENTER ||
                    (key.action.type == LayoutActionKind.SHIFT && shifted) ||
                    (key.action.type == LayoutActionKind.SYMBOL_LOCK && faces.symbolLocked)
            val railKey = i in railIndices
            val railChosen = railKey && railPosition(i) < railSelectedCount
            val baseColor =
                when {
                    railChosen || emphasized -> palette.accent
                    !isLetter && !isSpace -> palette.control
                    else -> palette.key
                }
            // The pressed fill appears with the finger and fades out over 120 ms after the release.
            val fade = if (pressed) 1f else KeyMotion.releaseColor(now, releases[i], motionScale)
            // A reading sits on the rail column's one surface; only a press or the chosen reading
            // gets a cap of its own.
            val columnBase = if (railKey && !railChosen) Color.TRANSPARENT else baseColor
            val fill = if (pressed) palette.pressed else ColorUtils.blendARGB(columnBase, palette.pressed, fade)
            drawBounds.set(
                box.left + insetX,
                box.top + insetY,
                box.right - insetX,
                box.bottom - insetY,
            )
            // The keycap is pressed the instant the finger lands and springs back on release; the
            // touch target and the dispatch never move.
            val scale = if (pressed) KeyMotion.pressScale() else KeyMotion.releaseScale(now, releases[i], motionScale)
            if (scale < 1f) scaleAroundCenter(drawBounds, scale)
            val radius = keyRadiusPx()
            // A translucent cap would show its own shadow through itself.
            if (metrics.keyShadowOpacity > 0f && !(railKey && !railChosen) && Color.alpha(baseColor) == 0xFF) {
                // The shadow tightens while the key is pressed, as on iOS: less opacity, half the offset.
                val tighten = if (pressed) 1f else fade
                val alpha = (metrics.keyShadowOpacity * (1f - SHADOW_PRESS_OPACITY_DROP * tighten) * 255f).toInt()
                if (alpha > 0) {
                    shadowPaint.alpha = alpha
                    shadowBounds.set(drawBounds)
                    shadowBounds.offset(0f, density * (1f - SHADOW_PRESS_OFFSET_DROP * tighten))
                    canvas.drawRoundRect(shadowBounds, radius, radius, shadowPaint)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(drawBounds, radius, radius, paint)
            val border = if (isLetter || isSpace) palette.keyBorder else palette.controlBorder
            if (border != 0 && (!railKey || railChosen)) {
                paint.style = Paint.Style.STROKE
                val stroke = metrics.keyBorderWidthDp * density
                paint.strokeWidth = stroke
                paint.color = border
                // Inside the cap, as iOS layer borders and the web editor's CSS borders are, so the
                // gap between keys keeps the skin's width.
                strokeBounds.set(drawBounds)
                strokeBounds.inset(stroke / 2f, stroke / 2f)
                val strokeRadius = (radius - stroke / 2f).coerceAtLeast(0f)
                canvas.drawRoundRect(strokeBounds, strokeRadius, strokeRadius, paint)
                paint.style = Paint.Style.FILL
            }
            val labelAlpha = labelFade.value(now, motionScale)
            if (labelAlpha <= 0f) continue
            val fadeLayer = if (labelAlpha < 1f) canvas.saveLayerAlpha(drawBounds, (labelAlpha * 255).toInt()) else -1
            val onAccent = (emphasized || railChosen) && !pressed
            val contentColor = if (onAccent) palette.accentText else palette.text
            val morph = if (flicked) flickMorph.value(now, motionScale) else 0f
            // A flick turns the key into its hint; a merged key's sideways swipe into one of its letters.
            val flick = if (flickMorph.towardY != 0) morph else 0f
            val swipe = if (flickMorph.towardY == 0) morph else 0f
            val labelLayer = if (flick > 0f) beginFlickLabel(canvas, drawBounds, flick) else -1
            when {
                key.action.type == LayoutActionKind.SHIFT && !railKey -> drawShiftIcon(canvas, drawBounds, contentColor)
                key.action.type == LayoutActionKind.BACKSPACE && !railKey ->
                    drawBackspaceIcon(canvas, drawBounds, contentColor)
                key.action.type == LayoutActionKind.GLOBE -> drawGlobeIcon(canvas, drawBounds, contentColor)
                key.action.type == LayoutActionKind.LANGUAGE_SWITCH && '/' in labels[i] ->
                    drawLanguageLabel(canvas, i, drawBounds, contentColor)
                else -> {
                    val syllable = syllableLines.getOrNull(i)
                    if (syllable == null) {
                        if (swipe > 0f) {
                            drawSwipeLetters(canvas, i, drawBounds, contentColor, false, flickMorph.towardX, swipe)
                        } else {
                            drawKeyLabel(canvas, i, drawBounds, contentColor)
                        }
                    } else {
                        // The letter moves up so the hint line under it reads as its own.
                        liftedBounds.set(drawBounds)
                        liftedBounds.offset(0f, -SYLLABLE_LIFT_DP * density)
                        if (syllable.columns.size > 1 && labels[i].length == syllable.columns.size) {
                            if (swipe > 0f) {
                                drawSwipeLetters(canvas, i, liftedBounds, contentColor, true, flickMorph.towardX, swipe)
                            } else {
                                drawSpreadLetters(canvas, i, liftedBounds, contentColor)
                            }
                        } else {
                            if (swipe > 0f) {
                                drawSwipeLetters(
                                    canvas,
                                    i,
                                    liftedBounds,
                                    contentColor,
                                    false,
                                    flickMorph.towardX,
                                    swipe,
                                )
                            } else {
                                drawKeyLabel(canvas, i, liftedBounds, contentColor)
                            }
                        }
                        drawSyllableHint(canvas, syllable, drawBounds)
                    }
                }
            }
            if (labelLayer >= 0) canvas.restoreToCount(labelLayer)
            val hint = key.secondaryLabel
            if (!hint.isNullOrEmpty()) {
                if (flick > 0f) {
                    drawFlickHint(canvas, i, hint, drawBounds, contentColor, flick)
                } else {
                    drawHint(canvas, i, hint, drawBounds)
                }
            }
            if (fadeLayer >= 0) canvas.restoreToCount(fadeLayer)
        }
        drawKana12Preview(canvas)
        drawCursorCue(canvas)
        if (animating) {
            val dirty = animationDirty
            postInvalidateOnAnimation(dirty.left, dirty.top, dirty.right, dirty.bottom)
            dirty.setEmpty()
        }
    }

    /**
     * The composing nine-key rail as one control-coloured column behind its readings, as iOS draws
     * it: readings rarely fill all three slots, and separate caps would leave empty keys below.
     */
    private fun drawRailColumn(canvas: Canvas) {
        if (railIndices.isEmpty()) return
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in railIndices) {
            val box = bounds.getOrNull(i) ?: continue
            left = minOf(left, box.left)
            top = minOf(top, box.top)
            right = maxOf(right, box.right)
            bottom = maxOf(bottom, box.bottom)
        }
        if (left >= right || top >= bottom) return
        drawBounds.set(left + insetX, top + insetY, right - insetX, bottom - insetY)
        val radius = keyRadiusPx()
        paint.style = Paint.Style.FILL
        paint.color = palette.control
        canvas.drawRoundRect(drawBounds, radius, radius, paint)
    }

    /** The union of the keys that still animate this frame: the next frame redraws only that. */
    private val animationDirty = Rect()

    private fun dirtyUnion(box: KeyBounds) {
        animationDirty.union(
            box.left.toInt() - 1,
            box.top.toInt() - 1,
            box.right.toInt() + 2,
            box.bottom.toInt() + 2 + shadowExtentPx(),
        )
    }

    /** Room below a key's slot that its shadow can reach. */
    private fun shadowExtentPx(): Int = if (metrics.keyShadowOpacity > 0f) kotlin.math.ceil(density).toInt() else 0

    /** Redraws only [index]'s slot; a press never repaints the whole keyboard. */
    internal fun invalidateKey(index: Int) {
        val box = bounds.getOrNull(index)
        if (box == null) {
            invalidate()
            return
        }
        invalidate(
            box.left.toInt() - 1,
            box.top.toInt() - 1,
            box.right.toInt() + 2,
            box.bottom.toInt() + 2 + shadowExtentPx(),
        )
    }

    /** Picks up a day/night or font-scale change: colors and text scale are read at draw time only. */
    fun refreshAppearance() {
        palette = KeyboardPalette.from(context)
        metrics = palette.tokens
        applyTypeface()
        textScale = ImeText.scale(resources)
        labels = keys.map { face(it, false) }
        spokenLabels = keys.map { face(it, true) }
        identities = KeyIdentity.list(keys, railIndices, labels)
        rebuild()
        requestLayout()
        invalidate()
    }

    override fun itemBounds(index: Int): Rect =
        hitBounds.getOrElse(index) { bounds[index] }.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }

    override fun itemLabel(index: Int): String = spokenLabels.getOrElse(index) { "" }

    override fun itemAt(
        x: Float,
        y: Float,
    ): Int = KeyGeometry.hit(hitBounds, x, y)

    override fun activateItem(index: Int) {
        if (index !in keys.indices) return
        // An accessibility or slide activation on a new key ends an open popup without committing it.
        if (popupKey >= 0 && popupOwner == NO_POINTER) dismissPopup(animated = false)
        val snapshot = keySnapshot(index)
        // No touch DOWN happens on this path, so the tick belongs to the acceptance itself.
        feedback(snapshot)
        if (snapshot.isBackspace) BackspaceTrace.record(BackspaceTrace.Event.ACCESSIBILITY_DELETE, composing())
        activate(snapshot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (notice != null) return true
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handleDown(event, index)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleUp(event, index)
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    fun closePopup(): Boolean {
        val open = popupKey >= 0
        if (open) {
            dismissPopup(animated = false)
            cancelTouches()
        }
        return open
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        accessibleCursorMode = false
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The long-press behaviour a virtual key node can advertise, if any. */
    private enum class LongAction {
        LANGUAGE_MENU,
        PICK_INPUT_METHOD,
        SYMBOL_PANEL,
        ALTERNATES,
        BACKSPACE_REPEAT,
        CURSOR_MODE,
    }

    private fun longAction(index: Int): LongAction? {
        val key = keys.getOrNull(index) ?: return null
        return when {
            key.action.type == LayoutActionKind.LANGUAGE_SWITCH -> LongAction.LANGUAGE_MENU
            key.action.type == LayoutActionKind.GLOBE -> LongAction.PICK_INPUT_METHOD
            key.action.type == LayoutActionKind.PAGE_SWITCH &&
                key.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS -> LongAction.SYMBOL_PANEL
            !key.longPress.isNullOrEmpty() -> LongAction.ALTERNATES
            key.action.type == LayoutActionKind.BACKSPACE -> LongAction.BACKSPACE_REPEAT
            key.action.type == LayoutActionKind.SPACE -> LongAction.CURSOR_MODE
            else -> null
        }
    }

    override fun itemLongClickable(index: Int): Boolean = longAction(index) != null

    override fun performItemLongClick(index: Int): Boolean {
        val action = longAction(index) ?: return false
        stopAccessibilityRepeat()
        if (popupKey >= 0) {
            val sameKey = popupKey == index
            dismissPopup(animated = false)
            if (sameKey) return true
        }
        when (action) {
            LongAction.LANGUAGE_MENU -> onLanguageMenu()
            LongAction.PICK_INPUT_METHOD -> onPickInputMethod()
            LongAction.SYMBOL_PANEL -> onSymbolPanel()
            LongAction.ALTERNATES -> openAccessiblePopup(index)
            LongAction.BACKSPACE_REPEAT -> startAccessibilityRepeat(index)
            LongAction.CURSOR_MODE -> setAccessibleCursorMode(!accessibleCursorMode)
        }
        return true
    }

    /** Opens the alternates popup for a virtual node; its options become custom actions. */
    private fun openAccessiblePopup(index: Int) {
        val options = keys.getOrNull(index)?.longPress.orEmpty()
        val slot = bounds.getOrNull(index) ?: return
        if (options.isEmpty()) return
        popupKey = index
        popupOwner = NO_POINTER
        showPopup(slot, options)
        // The key node just grew custom actions (one per alternate); services must re-read it.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        onPopupChanged()
    }

    /** Accessibility caret mode: while on, the space node offers one-step cursor moves. */
    private fun setAccessibleCursorMode(active: Boolean) {
        if (accessibleCursorMode == active) return
        accessibleCursorMode = active
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /**
     * The nine-key rail shows three slots of a longer engine-backed list. Assistive technology cannot
     * drag it, so the surface offers the same one-slot step a finger takes: forward only while a whole
     * window is still hidden, backward only once the window has left the first choice.
     */
    override fun hostScrollActions(): List<AccessibilityNodeInfo.AccessibilityAction> {
        if (railChoices.isEmpty()) return emptyList()
        return buildList {
            if (railChoiceFirst > 0) add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (railChoiceFirst < railChoiceCount - T9RailPolicy.VISIBLE_SLOTS) {
                add(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
        }
    }

    /**
     * Scrolls the rail one slot through the same route a drag takes, then re-reads the labels the
     * slots were given. A step the rail does not take reports failure instead of a scroll.
     */
    override fun performHostAction(action: Int): Boolean {
        val steps =
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
                else -> return false
            }
        if (hostScrollActions().none { it.id == action }) return false
        val first = railChoiceFirst
        onRailScroll(steps)
        if (railChoiceFirst == first) return false
        // Every slot swapped the choice it draws; clients must not reuse the labels they cached.
        announceItemsChanged()
        invalidate()
        return true
    }

    override fun itemCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> {
        // A kana key offers each flick direction the row carries as a named action.
        val kanaActions = kana12CustomActions(index)
        if (kanaActions.isNotEmpty()) return kanaActions
        val key = keys.getOrNull(index) ?: return emptyList()
        if (popupKey == index) {
            return popupOptions.mapIndexed { ordinal, option ->
                AccessibilityNodeInfo.AccessibilityAction(A11Y_ALTERNATE_ACTION_IDS[ordinal], option.label)
            }
        }
        if (accessibleCursorMode && key.action.type == LayoutActionKind.SPACE) {
            return listOf(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.ime_cursor_left_action,
                    context.getString(R.string.ime_cursor_left),
                ),
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.ime_cursor_right_action,
                    context.getString(R.string.ime_cursor_right),
                ),
            )
        }
        return emptyList()
    }

    override fun performItemCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean {
        if (performKana12CustomAction(index, actionId)) return true
        if (actionId == R.id.ime_cursor_left_action || actionId == R.id.ime_cursor_right_action) {
            if (keys.getOrNull(index)?.action?.type != LayoutActionKind.SPACE) return false
            onCursor(if (actionId == R.id.ime_cursor_left_action) -1 else 1)
            return true
        }
        if (popupKey != index) return false
        val ordinal = A11Y_ALTERNATE_ACTION_IDS.indexOf(actionId)
        val option = popupOptions.getOrNull(ordinal) ?: return false
        dismissPopup(animated = true)
        onFeedback(option.action, FeedbackKind.PRESS)
        onAction(option.action)
        onReleaseFeedback()
        return true
    }

    /**
     * Backspace repeat for a virtual node. At most [MAX_ACCESSIBILITY_REPEAT_STEPS] deletes, and
     * any further touch, action or detach stops it: the node cannot be held, so the burst must
     * always end on its own.
     */
    private fun startAccessibilityRepeat(index: Int) {
        stopAccessibilityRepeat()
        val key = keys.getOrNull(index) ?: return
        if (key.action.type != LayoutActionKind.BACKSPACE) return
        accessibleRepeatKey = index
        accessibleRepeatSteps = 0
        accessibleRepeatToken++
        accessibleRepeatComposing = composing()
        timers.post(accessibleRepeat)
    }

    internal fun stopAccessibilityRepeat() {
        if (accessibleRepeatKey < 0) return
        accessibleRepeatKey = -1
        accessibleRepeatSteps = 0
        // Invalidates steps of this burst that are still queued.
        accessibleRepeatToken++
        timers.removeCallbacks(accessibleRepeat)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        // Touch exploration moving on is the accessibility equivalent of lifting the finger.
        stopAccessibilityRepeat()
        return super.dispatchHoverEvent(event)
    }

    internal companion object {
        /** Every row page and the phone shell keep the same four-row key area. */
        const val PHONE_ROWS = 4

        /** The key count a split gutter is solved for when no character row says otherwise. */
        const val SPLIT_REFERENCE_KEYS = 10

        /** The least height of an authored rail key, as a share of a row, before the rail shrinks to fit. */
        const val RAIL_MIN_KEY_ROWS = 0.6f

        /** Stroke width shared by the Shift and Backspace icons (dp). */
        const val ICON_STROKE_WIDTH_DP = 1.7f

        /** Floor of the label fit loop; below this the text would be unreadable anyway. */
        const val MIN_LABEL_TEXT_SIZE_SP = 9f

        /** Corner hints are the secondary text colour at 60% (alpha channel). */
        const val HINT_ALPHA = 153

        /** A shuangpin hint is read, not glanced at, so it is darker than a corner mark. */
        const val SYLLABLE_HINT_ALPHA = 217

        /** Floor of the corner-hint fit loop. */
        const val MIN_HINT_TEXT_SIZE_SP = 8f

        /** How far a letter rises over its shuangpin hint; one value so a row keeps one baseline. */
        const val SYLLABLE_LIFT_DP = 4f

        /** A pressed key's shadow keeps this share less opacity and offset (iOS: 0.35 and 0.5 of rest). */
        const val SHADOW_PRESS_OPACITY_DROP = 0.65f
        const val SHADOW_PRESS_OFFSET_DROP = 0.5f

        /** A shuangpin hint shrinks to this before it wraps onto a second line. */
        const val MIN_SYLLABLE_TEXT_SIZE_SP = 7f

        const val STACK_SCALE_STEP = 0.05f

        /** No pointer owns the popup while an accessibility node opened it. */
        const val NO_POINTER = -1

        /** Marker for "no finger is on this slot". */
        const val NO_PRESS_TIME = 0L

        /** Layout-defined long-press delay when the page does not override it (s). */
        const val DEFAULT_LONG_PRESS_DELAY_S = 0.35

        /** First Backspace delete after the hold delay, then one every repeat tick (ms). */
        const val BACKSPACE_FIRST_DELAY_MS = 350L
        const val BACKSPACE_REPEAT_MS = 65L

        /** A virtual Backspace node deletes at most this many times before it stops itself. */
        const val MAX_ACCESSIBILITY_REPEAT_STEPS = 40

        /** Sideways finger travel that turns a press on the space bar into a caret drag (dp). */
        const val CURSOR_START_DP = 14f

        /** A flick: travel needed (dp), share of the key's height, and the way back that disarms it (dp). */
        const val FLICK_DISTANCE_DP = 24f
        const val FLICK_KEY_SHARE = 0.4f
        const val FLICK_RETURN_DP = 10f

        /** A character key's preview is shown for faces up to this long. */
        const val PREVIEW_MAX_CHARS = 2

        /** The preview grows this much beyond the keycap on each side (dp). */
        const val PREVIEW_GROW_DP = 4f

        /** Finger travel that turns a tap into a slide (dp). */
        const val TOUCH_SLOP_DP = 8f

        /** Cursor cue outline: inset into the space key and stroke width (dp). */
        const val CURSOR_CUE_INSET_DP = 2f
        const val CURSOR_CUE_STROKE_DP = 1.5f

        // Android 10 requires resource ids; automatic punctuation can extend authored alternates.
        private val A11Y_ALTERNATE_ACTION_IDS =
            intArrayOf(
                R.id.ime_alternate_action_0,
                R.id.ime_alternate_action_1,
                R.id.ime_alternate_action_2,
                R.id.ime_alternate_action_3,
                R.id.ime_alternate_action_4,
                R.id.ime_alternate_action_5,
                R.id.ime_alternate_action_6,
                R.id.ime_alternate_action_7,
                R.id.ime_alternate_action_8,
                R.id.ime_alternate_action_9,
                R.id.ime_alternate_action_10,
                R.id.ime_alternate_action_11,
                R.id.ime_alternate_action_12,
                R.id.ime_alternate_action_13,
                R.id.ime_alternate_action_14,
                R.id.ime_alternate_action_15,
                R.id.ime_alternate_action_16,
                R.id.ime_alternate_action_17,
                R.id.ime_alternate_action_18,
                R.id.ime_alternate_action_19,
                R.id.ime_alternate_action_20,
                R.id.ime_alternate_action_21,
                R.id.ime_alternate_action_22,
                R.id.ime_alternate_action_23,
                R.id.ime_alternate_action_24,
                R.id.ime_alternate_action_25,
                R.id.ime_alternate_action_26,
                R.id.ime_alternate_action_27,
                R.id.ime_alternate_action_28,
                R.id.ime_alternate_action_29,
                R.id.ime_alternate_action_30,
                R.id.ime_alternate_action_31,
            )
    }
}

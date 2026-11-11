package app.songyan.ime.ime

import android.os.LocaleList
import app.songyan.ime.ime.KeyboardView.Companion.MIN_HINT_TEXT_SIZE_SP
import app.songyan.ime.ime.KeyboardView.Companion.MIN_LABEL_TEXT_SIZE_SP
import app.songyan.ime.ime.KeyboardView.Companion.MIN_SYLLABLE_TEXT_SIZE_SP
import app.songyan.ime.ime.KeyboardView.Companion.PHONE_ROWS
import app.songyan.ime.ime.KeyboardView.Companion.RAIL_MIN_KEY_ROWS
import app.songyan.ime.ime.KeyboardView.Companion.SPLIT_REFERENCE_KEYS
import app.songyan.ime.ime.KeyboardView.Companion.STACK_SCALE_STEP
import app.songyan.ime.ime.KeyboardView.Companion.SYLLABLE_LIFT_DP
import app.songyan.ime.ime.KeyboardView.ShellModel
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardPageChromeMetrics
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutLanguageSwitchBehavior
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.ShuangpinHintOverlay
import kotlin.math.max

internal fun KeyboardView.face(
    key: LayoutKey,
    spoken: Boolean,
): String {
    val names = keyNames.chinese()
    if (key.titleMode == LayoutKeyTitleMode.CUSTOM) return key.label
    return when (key.action.type) {
        LayoutActionKind.SPACE ->
            when {
                spoken -> context.getString(R.string.ime_space)
                else -> faces.switchNotice ?: keyNames.space(faces.language, faces.composing)
            }
        LayoutActionKind.BACKSPACE ->
            if (spoken) context.getString(R.string.ime_delete) else names.getString(R.string.ime_glyph_delete)
        LayoutActionKind.ENTER -> if (spoken) keyNames.spokenEnter(context, actionLabel) else actionLabel
        LayoutActionKind.SHIFT ->
            if (spoken) {
                context.getString(if (capsLocked) R.string.ime_shift_locked else R.string.ime_shift)
            } else {
                names.getString(R.string.ime_glyph_shift)
            }
        // Drawn as an icon; the name is for TalkBack.
        LayoutActionKind.GLOBE -> if (spoken) context.getString(R.string.ime_toolbar_switch_input_method) else key.label
        LayoutActionKind.SYMBOL_LOCK -> (if (spoken) context else names).getString(R.string.ime_symbol_lock)
        LayoutActionKind.LANGUAGE_SWITCH -> languageFace(key, spoken)
        LayoutActionKind.PAGE_SWITCH -> pageSwitchFace(key.action, key, spoken)
        // Only the keyboard's own key is renamed for the ear; an author's label is what they wrote.
        LayoutActionKind.CLEAR_INPUT ->
            if (spoken && isKeyName(key.label, R.string.ime_clear)) context.getString(R.string.ime_clear) else key.label
        // The generated symbol-panel key is a control key; a page the author wrote keeps its label.
        LayoutActionKind.MODE_SWITCH ->
            if (key.titleMode == LayoutKeyTitleMode.AUTOMATIC && key.action.value == KeyboardPages.SYMBOL_PANEL_MODE) {
                if (spoken) {
                    context.getString(
                        R.string.ime_panel_symbols_title,
                    )
                } else {
                    keyNames.symbolPanelDrawn(faces.language)
                }
            } else {
                key.face(faces.symbolMarksAscii)
            }
        LayoutActionKind.LAYOUT_PAGE_SWITCH -> if (spoken) jumpName(key) else key.label
        else -> {
            val base =
                when {
                    shifted && key.action.type == LayoutActionKind.CODE -> key.label.uppercase()
                    // The key types the branch's text, so it shows that text, not the label saved with the page.
                    else -> key.face(faces.symbolMarksAscii)
                }
            // On the phone pad the corner digit is part of what the key types, so TalkBack reads it.
            val hint = key.secondaryLabel
            if (spoken && faces.phone && key.action.type == LayoutActionKind.CODE && !hint.isNullOrEmpty()) {
                base + " " + hint
            } else {
                base
            }
        }
    }
}

private fun KeyboardView.isKeyName(
    drawn: String,
    id: Int,
): Boolean = keyNames.chinese().getString(id) == drawn

/** The language key names the language it switches to, never the current one. */
internal fun KeyboardView.languageFace(
    key: LayoutKey,
    spoken: Boolean,
): String {
    val action = key.action
    if (action.languageSwitchBehavior == LayoutLanguageSwitchBehavior.LETTERS) {
        return if (spoken) {
            context.getString(
                R.string.ime_return_to_letters,
            )
        } else {
            keyNames.chinese().getString(R.string.ime_back)
        }
    }
    // Chinese is drawn "中/英" with the current side emphasised (see drawLanguageLabel); Japanese
    // names its two sides あ and A. Only TalkBack gets words otherwise.
    if (!spoken) return keyNames.languageKeyDrawn(faces.language) ?: key.label
    val symbols = faces.page != null
    val targetChinese = if (symbols) faces.symbolMarksAscii else faces.english
    return context.getString(KeyNames.languageKeySpoken(faces.language, symbols, targetChinese))
}

/** TalkBack names where a jump key goes, since its drawn label (often "123") does not say which layout. */
internal fun KeyboardView.jumpName(key: LayoutKey): String {
    val jump = key.action.layoutPageJump ?: return key.label
    val kind = KeyboardLayoutKind.fromWire(jump.layout.wire) ?: return key.label
    val id = if (jump.page == LayoutPageTarget.SYMBOLS) R.string.ime_jump_to_symbols else R.string.ime_jump_to_numbers
    // The page belongs to the keyboard being drawn: a physical board names itself, not the shape it shares.
    val definition =
        faces.mainLayoutId?.let {
            KeyboardLayoutCatalog.snapshot.value.registry
                .resolvedLayout(it)
        }
    return context.getString(id, layoutName(context, definition, kind))
}

internal fun KeyboardView.pageSwitchFace(
    action: LayoutAction,
    key: LayoutKey,
    spoken: Boolean,
): String {
    val names = keyNames.chinese()
    return when (action.pageSwitchTarget) {
        // The Chinese keyboard says "返回", Japanese 戻る; the English page names where it goes, "ABC".
        // A page the author wrote keeps the label it saved.
        LayoutPageTarget.LETTERS ->
            when {
                spoken -> context.getString(R.string.ime_return_to_letters)
                faces.english -> names.getString(R.string.ime_back)
                key.titleMode == LayoutKeyTitleMode.AUTOMATIC -> keyNames.returnToLettersDrawn(faces.language)
                else -> key.label
            }
        LayoutPageTarget.NUMERIC ->
            if (spoken) context.getString(R.string.ime_open_numbers_page) else names.getString(R.string.ime_numbers)
        // On the symbol page itself the switch turns from its extra page of everyday marks to the rest.
        LayoutPageTarget.SYMBOLS ->
            if (faces.page == KeyboardPageKind.SYMBOLS) {
                (if (spoken) context else names).getString(
                    if (spoken) R.string.ime_open_more_symbols else R.string.ime_more_symbols,
                )
            } else {
                if (spoken) {
                    context.getString(
                        R.string.ime_open_symbols_page,
                    )
                } else {
                    keyNames.symbolPageDrawn(faces.language)
                }
            }
        null -> key.label
    }
}

/** The key area height of [rows] rows in dp: every page above the letters uses the same one. */
internal fun KeyboardView.keyAreaHeightDp(rows: Int): Float = rows * metrics.rowHeightDp

/** Where the key area sits inside the view, in px. */
internal class ContentFrame(
    val left: Float,
    val width: Float,
)

/**
 * The key area of a view [width] px wide. Only tablets (and unfolded foldables) cap and dock it
 * at 600 dp: a phone turned sideways has no room to waste, and a centred narrow keyboard there reads
 * as a floating slab. A split keyboard takes the whole width, its halves kept within reach by the gutter.
 * The user's side margin applies only when the surface is wider than the split width. In one-handed
 * mode this view is already the narrowed column, so the margin comes off that column and the minimum
 * key area still holds.
 */
internal fun KeyboardView.contentFrame(split: LayoutSplitConfiguration?): ContentFrame {
    val surfaceDp = width / density.toDouble()
    val tablet = resources.configuration.smallestScreenWidthDp >= WideSurface.PHONE_MAXIMUM_DP
    val plan =
        WideSurface.plan(
            surfaceDp,
            split,
            dock,
            capDp = if (tablet) WideSurface.CONTENT_MAXIMUM_DP else Double.POSITIVE_INFINITY,
            automaticAboveDp = splitWidthDp.toDouble(),
            sideMarginPerMille = sideMarginPerMille,
        )
    return ContentFrame(plan.leadingInset.toFloat() * density, plan.contentWidth.toFloat() * density)
}

/**
 * The split that applies to the page being drawn at the current width, or null for an unsplit keyboard.
 * Judged on the full surface width, as [contentFrame] judges it. The returned copy is resolved so the
 * row solvers, which see a narrower inner width, do not apply the default threshold a second time.
 */
internal fun KeyboardView.activeSplit(): LayoutSplitConfiguration? {
    val surfaceDp = width / density.toDouble()
    return WideSurface
        .effectiveSplit(layout?.split, splitMode)
        ?.resolvedForWidth(surfaceDp, splitWidthDp.toDouble())
}

/**
 * The key columns of a split keyboard: the space bar of the footer becomes one half under each
 * thumb, so each half keeps a usable space key. Other rows are cut where the split says.
 */
internal fun splitColumns(
    columns: List<List<LayoutKey>>,
    split: LayoutSplitConfiguration,
    showsGlobe: Boolean = true,
): Pair<List<List<LayoutKey>>, List<Int>> {
    val halves = split.halves
    if (halves != null && halves.size == columns.size) {
        // The layout's own split arrangement: each half is already the keys to draw.
        val rows = ArrayList<List<LayoutKey>>(halves.size)
        val breaks = ArrayList<Int>(halves.size)
        for (row in halves) {
            val left = if (showsGlobe) row.left else KeyRowSolver.withoutGlobe(row.left)
            val right = if (showsGlobe) row.right else KeyRowSolver.withoutGlobe(row.right)
            rows += left + right
            breaks += left.size
        }
        return rows to breaks
    }
    val rows = ArrayList<List<LayoutKey>>(columns.size)
    val breaks = ArrayList<Int>(columns.size)
    for ((index, row) in columns.withIndex()) {
        val cut = split.cut(index, row)
        rows += cut.keys
        breaks += cut.left.size
    }
    return rows to breaks
}

/** Debug-gated: the adb driver needs exact key centres once per show, no other client does. */
internal fun KeyboardView.logKeyBoundsOnce() {
    if (width <= 0 || height <= 0) return
    if (!ImePerf.consumeKeyBounds()) return
    val location = IntArray(2)
    getLocationOnScreen(location)
    for (i in keys.indices) {
        if (i >= bounds.size) break
        val box = bounds[i]
        val action = keys[i].action
        ImePerf.keyBound(
            i,
            action.type.name,
            action.value ?: action.asciiValue,
            ((box.left + box.right) / 2).toInt() + location[0],
            ((box.top + box.bottom) / 2).toInt() + location[1],
        )
    }
}

/**
 * The window can still be settling (insets, resize) when the first boxes are logged, so the driver would
 * tap where the keys were. Logs them again once the view is drawn somewhere else.
 */
internal fun KeyboardView.logKeyBoundsIfMoved() {
    val location = IntArray(2)
    getLocationOnScreen(location)
    val at = (location[0].toLong() shl 40) xor (location[1].toLong() shl 20) xor width.toLong()
    if (at == loggedBoundsAt) return
    loggedBoundsAt = at
    ImePerf.rearmKeyBounds()
    logKeyBoundsOnce()
}

internal fun KeyboardView.rebuild() {
    val model = shell
    if (model != null) {
        rebuildPhone(model)
        return
    }
    val kanaSurface = kana12
    if (kanaSurface != null) {
        rebuildKana12(kanaSurface)
        return
    }
    val rows = layout?.rows ?: return
    if (width <= 0) return
    rails?.let {
        rebuildWithRails(rows, it)
        return
    }
    val padding = metrics.keyAreaHorizontalPaddingDp * density
    val split = activeSplit()
    val frame = contentFrame(split)
    var left = frame.left + padding
    var inner = frame.width - 2 * padding
    if (inner <= 0f) return
    val gap = metrics.keyGapHorizontalDp * density
    val rowHeight = rowHeightFor(rows.size)
    rowOffsetY = metrics.keyAreaBottomGapDp / 2 * density
    insetX = KeyInsets.rowHorizontal()
    insetY = KeyInsets.rowVertical(metrics.keyGapVerticalDp * density)
    var columns = KeyRowSolver.columns(rows, showsGlobe)
    var rowSplit: KeyRowSolver.RowSplit? = null
    if (split != null) {
        // Every character row shares one gutter, solved for the widest of them, so the halves line up.
        // The split arrangement can carry more keys than the whole rows, so the cut rows decide.
        val (cut, breaks) = splitColumns(columns, split, showsGlobe)
        val widest =
            cut
                .filter { row ->
                    row.none { it.action.type == LayoutActionKind.SPACE }
                }.maxOfOrNull { it.size }
        val plan =
            SplitFrame.plan(
                split,
                inner / density.toDouble(),
                widest ?: SPLIT_REFERENCE_KEYS,
                spacing = gap / density.toDouble(),
            )
        if (plan.gutter > 0.0) {
            columns = cut
            rowSplit = KeyRowSolver.RowSplit(plan.gutter.toFloat() * density, breaks)
            left += plan.inset.toFloat() * density
            inner -= 2 * plan.inset.toFloat() * density
        }
    }
    // The solved keys are the dispatch keys: a split footer carries two space halves.
    keys = columns.flatten()
    val rowSlots = KeyRowSolver.slots(columns, left, rowOffsetY, rowHeight, inner, gap, rowSplit)
    bounds = rowSlots.flatten()
    applyKeyFaces()
    // The whole content width is owned, including the outer padding, so no tap is dead.
    hitBounds =
        KeyHitZones
            .fillRows(rowSlots, frame.left, 0f, frame.left + frame.width, hitBottom())
            .flatten()
    settleKeySet(rowKeySetSignature())
}

/**
 * One row's height for a page of [rowCount] rows: a page taller than the key area splits it, and a
 * shorter page in a field with an extra row (a password's digit row) stretches to fill it.
 */
private fun KeyboardView.rowHeightFor(rowCount: Int): Float =
    metrics.rowHeightDp * heightRows / max(rowCount, PHONE_ROWS) * density

/**
 * Rows plus the authored side rails of a page. The content rows sit between the rails and the
 * footer under them; the trailing rail may run down beside the footer, which then gives up that
 * width. A split cuts the content rows and the footer inside their own areas, so the rails stay at
 * the outer edges, within reach of the thumbs.
 */
internal fun KeyboardView.rebuildWithRails(
    rows: List<LayoutRow>,
    rails: KeyboardPages.PageRails,
) {
    val padding = metrics.keyAreaHorizontalPaddingDp * density
    val split = activeSplit()
    val frame = contentFrame(split)
    val outerLeft = frame.left + padding
    val outerInner = frame.width - 2 * padding
    if (outerInner <= 0f) return
    val gap = metrics.keyGapHorizontalDp * density
    val rowHeight = rowHeightFor(rows.size)
    rowOffsetY = metrics.keyAreaBottomGapDp / 2 * density
    insetX = KeyInsets.rowHorizontal()
    insetY = KeyInsets.rowVertical(metrics.keyGapVerticalDp * density)
    val chrome = rails.chrome
    val across =
        KeyboardPageChromeMetrics.resolve(
            outerInner / density.toDouble(),
            metrics.rowHeightDp.toDouble(),
            chrome,
            gap / density.toDouble(),
        )
    var mainLeft = outerLeft + across.mainLeft.toFloat() * density
    var mainInner = across.mainWidth.toFloat() * density
    var footerLeft = outerLeft
    var footerInner = across.bottomWidth.toFloat() * density
    var columns = KeyRowSolver.columns(rows, showsGlobe)
    var rowSplit: KeyRowSolver.RowSplit? = null
    if (split != null) {
        val (cut, breaks) = splitColumns(columns, split, showsGlobe)
        val widest =
            cut
                .take(rails.contentRows)
                .filter { row -> row.none { it.action.type == LayoutActionKind.SPACE } }
                .maxOfOrNull { it.size }
        val plan =
            SplitFrame.plan(
                split,
                mainInner / density.toDouble(),
                widest ?: SPLIT_REFERENCE_KEYS,
                spacing = gap / density.toDouble(),
            )
        if (plan.gutter > 0.0) {
            columns = cut
            rowSplit = KeyRowSolver.RowSplit(plan.gutter.toFloat() * density, breaks)
            val inset = plan.inset.toFloat() * density
            mainLeft += inset
            mainInner -= 2 * inset
            footerLeft += inset
            footerInner -= 2 * inset
        }
    }
    val contentColumns = columns.take(rails.contentRows)
    val footerColumns = columns.drop(rails.contentRows)
    val contentSplit = rowSplit?.let { KeyRowSolver.RowSplit(it.gap, it.breaks.take(rails.contentRows)) }
    val footerSplit = rowSplit?.let { KeyRowSolver.RowSplit(it.gap, it.breaks.drop(rails.contentRows)) }
    val contentSlots = KeyRowSolver.slots(contentColumns, mainLeft, rowOffsetY, rowHeight, mainInner, gap, contentSplit)
    val footerTop = rowOffsetY + rails.contentRows * rowHeight
    val leadingKeys = chrome.leading
    val trailingKeys = chrome.trailing
    val footerRow = footerColumns.singleOrNull()
    // Under a leading rail the footer's first key stands below the rail, and under a trailing rail
    // that stops above the footer its last key stands below that rail; the rest stand below the
    // content, so a pad's bottom row lines up with the columns above it.
    val underLeading = leadingKeys.isNotEmpty()
    val underTrailing = trailingKeys.isNotEmpty() && !chrome.trailingSpansBottom
    val outerKeys = (if (underLeading) 1 else 0) + (if (underTrailing) 1 else 0)
    val footerSlots =
        if (outerKeys > 0 && rowSplit == null && footerRow != null && footerRow.size > outerKeys) {
            fun solve(
                keys: List<LayoutKey>,
                left: Float,
                width: Float,
            ) = KeyRowSolver.slots(listOf(keys), left, footerTop, rowHeight, width, gap).single()
            val leadingWidth = across.leadingWidth.toFloat() * density
            val trailingWidth = across.trailingWidth.toFloat() * density
            val middle = footerRow.subList(if (underLeading) 1 else 0, footerRow.size - if (underTrailing) 1 else 0)
            listOf(
                (if (underLeading) solve(footerRow.take(1), footerLeft, leadingWidth) else emptyList()) +
                    solve(middle, mainLeft, mainInner) +
                    if (underTrailing) {
                        solve(footerRow.takeLast(1), outerLeft + outerInner - trailingWidth, trailingWidth)
                    } else {
                        emptyList()
                    },
            )
        } else {
            KeyRowSolver.slots(footerColumns, footerLeft, footerTop, rowHeight, footerInner, gap, footerSplit)
        }
    val minimumKey = rowHeight * RAIL_MIN_KEY_ROWS
    val leadingSlots =
        KeyRailSolver.slots(
            outerLeft,
            across.leadingWidth.toFloat() * density,
            rowOffsetY,
            rails.contentRows * rowHeight,
            leadingKeys.map { it.widthWeight },
            minimumKey,
        )
    val trailingRows = rails.contentRows + if (rails.hasFooter && chrome.trailingSpansBottom) 1 else 0
    val trailingSlots =
        KeyRailSolver.slots(
            outerLeft + outerInner - across.trailingWidth.toFloat() * density,
            across.trailingWidth.toFloat() * density,
            rowOffsetY,
            trailingRows * rowHeight,
            trailingKeys.map { it.widthWeight },
            minimumKey,
        )
    keys = columns.flatten() + leadingKeys + trailingKeys
    val boxes = contentSlots.flatten() + footerSlots.flatten() + leadingSlots + trailingSlots
    bounds = boxes
    applyKeyFaces()
    // The whole content width is owned, including the outer padding, so no tap is dead.
    hitBounds = KeyHitZones.fill(boxes, frame.left, 0f, frame.left + frame.width, hitBottom())
    settleKeySet(rowKeySetSignature())
}

/** Positions the phone shell's keys; the shell model owns every rectangle. */
internal fun KeyboardView.rebuildPhone(model: ShellModel) {
    if (width <= 0) return
    val padding = metrics.keyAreaHorizontalPaddingDp * density
    val frame = contentFrame(null)
    val fullWidth = frame.width
    val contentWidth = fullWidth - 2 * padding
    if (contentWidth <= 0f) return
    val built = model.build(contentWidth / density, keyAreaHeightDp(heightRows)) ?: return
    // The shell stops growing at its preferred key widths; on a wider window it is centred, not left-aligned.
    val shellInset = frame.left + padding + built.metrics.outerInset.toFloat() * density
    rowOffsetY = metrics.keyAreaBottomGapDp / 2 * density
    // The shell solves its own gutters; the drawn keycap is inset so the visual gutter still
    // matches the row pages (5 dp horizontally, 8 dp vertically).
    val spacing = built.metrics.spacing.toFloat() * density
    insetX = KeyInsets.phoneHorizontal(metrics.keyGapHorizontalDp * density, spacing)
    // The shell's bands have no vertical gutter of their own: the drawn keycap supplies it.
    insetY = KeyInsets.phoneVertical(metrics.keyGapVerticalDp * density)
    // The physical shell is what identifies the key set: composition only relabels rail slots.
    val physical =
        built.keys.map { placed ->
            KeyBounds(
                placed.left * density + shellInset,
                placed.top * density + rowOffsetY,
                placed.right * density + shellInset,
                placed.bottom * density + rowOffsetY,
            )
        }
    val keys = mutableListOf<LayoutKey>()
    val boxes = mutableListOf<KeyBounds>()
    val rails = mutableSetOf<Int>()
    var railIndex = 0
    var firstKeyIndex = -1
    for (placed in built.keys) {
        if (!showsGlobe && placed.key.action.type == LayoutActionKind.GLOBE) continue
        if (placed.region == PhoneKeypadLayout.Region.LEADING_RAIL && railChoices.isNotEmpty()) {
            // While composing the whole rail is the reading column: a slot the readings do not
            // reach stays an empty, inert part of it (a tap finds no item), never a leftover quick
            // symbol that would commit the composition with a mark.
            val choice = railChoices.getOrNull(railIndex)
            railIndex += 1
            keys += LayoutKey(choice.orEmpty(), action = LayoutAction.literal(choice.orEmpty()))
            rails += keys.lastIndex
        } else if (placed.readingSlot) {
            // A slot reserved for readings holds nothing while the layout's own rail is empty.
            continue
        } else {
            keys += placed.key
        }
        if (placed.t9FirstKey) firstKeyIndex = keys.lastIndex
        boxes +=
            KeyBounds(
                placed.left * density + shellInset,
                placed.top * density + rowOffsetY,
                placed.right * density + shellInset,
                placed.bottom * density + rowOffsetY,
            )
    }
    this.keys = keys
    t9FirstKeyIndex = firstKeyIndex
    bounds = boxes
    railIndices = rails
    applyKeyFaces()
    // The whole content width is owned, including the outer padding, so no tap is dead.
    hitBounds =
        KeyHitZones
            .fill(boxes, frame.left, 0f, frame.left + fullWidth, hitBottom())
    settleKeySet(phoneKeySetSignature(model.numeric, physical))
}

/** Layouts and labels are pure functions of the current keys and faces. */
internal fun KeyboardView.applyKeyFaces() {
    applyTextLanguage()

    swipeCodes = keys.map { key -> swipeConfig?.codesFor(key) }
    swipeLetters = keys.map { key -> swipeConfig?.lettersFor(key) }
    if (releases.size != keys.size) releases = LongArray(keys.size)
    if (pressedFlags.size != keys.size) pressedFlags = BooleanArray(keys.size)
    labels = keys.map { face(it, false) }
    spokenLabels = keys.map { face(it, true) }
    if (railSelectedCount > 0) {
        // An applied rail choice reads as chosen: Delete undoes it, a tap does nothing.
        spokenLabels =
            spokenLabels.mapIndexed { index, label ->
                if (index in railIndices && railPosition(index) < railSelectedCount) {
                    context.getString(R.string.ime_rail_chosen, label)
                } else {
                    label
                }
            }
    }
    // The kana surface speaks each row's name and renames the arrow-only caret key.
    applyKana12KeyFaces()
    identities = KeyIdentity.list(keys, railIndices, labels)
    fitLabels()
}

/**
 * Applies the running scheme's language to the paints that fit and draw key labels. The locale is
 * materialised only when it changes: this pass runs on every relabel and must not allocate per key.
 */
private fun KeyboardView.applyTextLanguage() {
    val language = faces.language
    if (language == textLanguage) return
    textLanguage = language
    val locales = LocaleList.forLanguageTags(language.languageTag)
    paint.setTextLocales(locales)
    measurePaint.setTextLocales(locales)
}

/**
 * Solves every label's size once per layout: text is measured here, never in the draw loop, so a
 * press only redraws the keycaps. Runs whenever the labels or the key rectangles change.
 */
internal fun KeyboardView.fitLabels() {
    if (labelSizes.size != keys.size) {
        labelSizes = FloatArray(keys.size)
        hintSizes = FloatArray(keys.size)
        hintBreaks = IntArray(keys.size)
        syllableLines = arrayOfNulls(keys.size)
        labelInkX = FloatArray(keys.size)
        labelInkY = FloatArray(keys.size)
    }
    if (bounds.size < keys.size) return
    for (i in keys.indices) {
        val capWidth = bounds[i].right - bounds[i].left - 2 * insetX
        val key = keys[i]
        labelSizes[i] =
            if (i in railIndices) {
                // Readings are what the user picks from, so they read at candidate size.
                fitSize(labels[i], metrics.candidateTextSizeSp, capWidth - 6 * density)
            } else {
                fitSize(
                    labels[i],
                    labelTextSizeSp(key, labels[i]),
                    capWidth - metrics.keyLabelPaddingDp * density,
                )
            }
        inkCentre(i)
        syllableLines[i] = solveSyllableHint(key, capWidth)
        val hint = key.secondaryLabel
        val hintRoom = capWidth - metrics.secondaryLabelInsetDp * density * 2
        hintBreaks[i] = -1
        hintSizes[i] =
            if (hint.isNullOrEmpty()) {
                0f
            } else {
                val single = fitSize(hint, metrics.keySecondaryLabelTextSizeSp, hintRoom, MIN_HINT_TEXT_SIZE_SP)
                measurePaint.textSize = single
                // A shuangpin hint with alternatives ("iang/uang") or a merged key's pair ("iu ei")
                // wraps onto a second line rather than running past the keycap, as iOS wraps it.
                val slash = hint.indexOf(' ').takeIf { it > 0 } ?: hint.indexOf('/')
                if (measurePaint.measureText(hint) > hintRoom && slash in 1 until hint.length - 1) {
                    hintBreaks[i] = slash + 1
                    val longer = maxOf(hint.substring(0, slash + 1), hint.substring(slash + 1), compareBy { it.length })
                    fitSize(longer, metrics.keySecondaryLabelTextSizeSp, hintRoom, MIN_HINT_TEXT_SIZE_SP)
                } else {
                    single
                }
            }
    }
    shrinkStacks()
}

/**
 * Width fitting alone lets a large system font push a letter into its shuangpin line or under its
 * corner mark, since the keycap does not grow with the font. Character keys, their marks and
 * their hint lines shrink by one shared factor until nothing touches, so a row keeps one size.
 */
private fun KeyboardView.shrinkStacks() {
    val stacked = keys.indices.filter { isCharacterKey(it) && (syllableLines[it] != null || hintSizes[it] > 0f) }
    if (stacked.isEmpty()) return
    // Never below the default font's size: the shrink only takes back what the system font added.
    val floor = minOf(1f, density / textScale)
    var scale = 1f
    while (scale > floor && stacked.any { stackCollides(it, scale) }) {
        scale = maxOf(scale - STACK_SCALE_STEP, floor)
    }
    if (scale == 1f) return
    for (i in keys.indices) {
        if (!isCharacterKey(i)) continue
        labelSizes[i] *= scale
        hintSizes[i] *= scale
        // Re-solved rather than scaled: at the smaller size a wrapped hint may fit on one line again.
        if (syllableLines[i] != null) syllableLines[i] = solveSyllableHint(keys[i], capWidth(i), scale)
        inkCentre(i)
    }
}

private fun KeyboardView.isCharacterKey(i: Int): Boolean =
    i !in railIndices && keys[i].action.type.let { it == LayoutActionKind.CODE || it == LayoutActionKind.LITERAL }

private fun KeyboardView.capWidth(i: Int): Float = bounds[i].right - bounds[i].left - 2 * insetX

/**
 * Whether key [i], with its text at [scale], has its letter touching its hint line or its corner
 * mark. Positions follow drawKeyLabel, drawSyllableHint and drawHint exactly; ink comes from the font.
 */
internal fun KeyboardView.stackCollides(
    i: Int,
    scale: Float,
): Boolean {
    val capWidth = capWidth(i)
    val capHeight = bounds[i].bottom - bounds[i].top - 2 * insetY
    val inset = metrics.secondaryLabelInsetDp * density
    val line =
        if (scale ==
            1f
        ) {
            syllableLines[i]
        } else {
            syllableLines[i]?.let { solveSyllableHint(keys[i], capWidth, scale) }
        }
    val label = labels[i]
    if (label.isEmpty()) return false
    val ink = android.graphics.Rect()
    measurePaint.textSize = labelSizes[i] * scale
    measurePaint.getTextBounds(label, 0, label.length, ink)
    val centreY = capHeight / 2 - if (line != null) SYLLABLE_LIFT_DP * density else 0f
    val letterTop: Float
    val letterBottom: Float
    if (FullWidthMark.matches(label)) {
        letterTop = centreY - ink.height() / 2f
        letterBottom = centreY + ink.height() / 2f
    } else {
        val baseline = centreY - (measurePaint.ascent() + measurePaint.descent()) / 2
        letterTop = baseline + ink.top
        letterBottom = baseline + ink.bottom
    }
    val spread = line != null && line.columns.size > 1 && label.length == line.columns.size
    // Ink, not advance: side bearings would count a mark beside a letter's ascender as touching it.
    val letterRight =
        if (spread) {
            measurePaint.getTextBounds(label, label.length - 1, label.length, ink)
            val advance = measurePaint.measureText(label, label.length - 1, label.length)
            capWidth * (label.length - 0.5f) / label.length - advance / 2 + ink.right
        } else {
            capWidth / 2 - measurePaint.measureText(label) / 2 + ink.right
        }
    if (line != null) {
        measurePaint.textSize = line.textSize
        val bottom = capHeight - inset - measurePaint.descent()
        var lineTop = Float.MAX_VALUE
        for ((index, text) in line.columns.withIndex()) {
            if (text == null) continue
            val cut = line.breaks[index]
            val first = if (cut > 0) text.substring(0, cut) else text
            val baseline = if (cut > 0) bottom - measurePaint.fontSpacing * 0.9f else bottom
            measurePaint.getTextBounds(first, 0, first.length, ink)
            lineTop = minOf(lineTop, baseline + ink.top)
        }
        if (letterBottom > lineTop) return true
    }
    val hint = keys[i].secondaryLabel
    if (hint.isNullOrEmpty() || hintSizes[i] == 0f) return false
    measurePaint.textSize = hintSizes[i] * scale
    val cut = hintBreaks[i]
    val wraps = cut in 1 until hint.length
    val hintLines = if (wraps) listOf(hint.substring(0, cut), hint.substring(cut)) else listOf(hint)
    val last = hintLines.last()
    measurePaint.getTextBounds(last, 0, last.length, ink)
    val hintBottom = inset - measurePaint.ascent() + (hintLines.size - 1) * measurePaint.fontSpacing * 0.9f + ink.bottom
    val hintLeft =
        hintLines.minOf {
            measurePaint.getTextBounds(it, 0, it.length, ink)
            capWidth - inset - measurePaint.measureText(it) + ink.left
        }
    return hintBottom > letterTop && hintLeft < letterRight
}

/** The largest size up to [spSize] at which [text] fits [available] px, not below [floorSp]. */
internal fun KeyboardView.fitSize(
    text: String,
    spSize: Float,
    available: Float,
    floorSp: Float = MIN_LABEL_TEXT_SIZE_SP,
): Float {
    var size = spSize * textScale
    val floor = floorSp * textScale
    measurePaint.textSize = size
    while (size > floor && measurePaint.measureText(text) > available) {
        size -= 0.5f * textScale
        measurePaint.textSize = size
    }
    return size
}

/**
 * A page or layout switch replaces the physical key set, so held fingers can no longer be
 * routed and are cancelled, and release fades are dropped; a relabel (shift, composition, rail choices) keeps them and only
 * re-resolves their slot.
 */
internal fun KeyboardView.settleKeySet(signature: KeySetSignature) {
    if (signature == keySet) {
        rebasePresses()
        return
    }
    keySet = signature
    if (pointers.size() > 0) cancelTouches()
    // Fades are per slot: the key that sat under the finger on the new page must not light up.
    java.util.Arrays.fill(releases, 0L)
}

internal fun KeyboardView.rebasePresses() {
    if (pointers.size() == 0 || bounds.isEmpty()) return
    val index = KeyIdentity.index(identities)
    for (i in 0 until pointers.size()) {
        val press = pointers.valueAt(i)
        press.rebase(index[press.snapshot.identity] ?: -1)
    }
}

/**
 * The physical key set a press belongs to: page, layout and slot rectangles, never a label.
 * Composition, shift and rail updates change labels only, so they keep this signature and the
 * held fingers; a page or layout switch changes it and the fingers are cancelled.
 */
internal data class KeySetSignature(
    val phone: Boolean,
    val page: KeyboardPageKind?,
    val layoutId: String,
    val numeric: Boolean,
    val slots: List<KeyBounds>,
)

internal fun KeyboardView.rowKeySetSignature(): KeySetSignature =
    KeySetSignature(
        phone = false,
        page = faces.page,
        layoutId = layout?.id.orEmpty(),
        numeric = false,
        slots = bounds,
    )

internal fun KeyboardView.phoneKeySetSignature(
    numeric: Boolean,
    physical: List<KeyBounds>,
): KeySetSignature =
    KeySetSignature(
        phone = true,
        page = null,
        layoutId = "",
        numeric = numeric,
        slots = physical,
    )

/** The slot of rail key [index] among the rail's keys, top to bottom. */
internal fun KeyboardView.railPosition(index: Int): Int = railIndices.count { it < index }

internal fun KeyboardView.keySnapshot(index: Int): KeyPressSnapshot =
    KeyPressSnapshot(
        identity = identities.getOrNull(index).orEmpty(),
        action = keys[index].action,
        t9FirstKey = index == t9FirstKeyIndex,
        railLabel = if (index in railIndices) labels.getOrNull(index) else null,
        railPosition = if (index in railIndices) railPosition(index) else -1,
        railApplied = index in railIndices && railPosition(index) < railSelectedCount,
        longPress = keys[index].longPress.orEmpty(),
        swipeCodes = swipeCodes.getOrNull(index),
        hint = keys[index].secondaryLabel,
    )

/** Solves [KeyboardView.labelInkX]/[KeyboardView.labelInkY] for key [i] at its fitted size. */
private fun KeyboardView.inkCentre(i: Int) {
    val label = labels[i]
    if (!FullWidthMark.matches(label)) {
        labelInkX[i] = Float.NaN
        labelInkY[i] = Float.NaN
        return
    }
    measurePaint.textSize = labelSizes[i]
    val ink = android.graphics.Rect()
    measurePaint.getTextBounds(label, 0, label.length, ink)
    // Offsets from the advance's left edge and the baseline to the ink's centre.
    labelInkX[i] = ink.exactCenterX()
    labelInkY[i] = ink.exactCenterY()
}

/** A label that is one full-width punctuation mark, whose ink does not fill its em box. */
internal object FullWidthMark {
    fun matches(label: String): Boolean {
        if (label.length != 1) return false
        val c = label[0]
        return c in '\u3000'..'\u303F' ||
            c in '\uFF01'..'\uFF0F' ||
            c in '\uFF1A'..'\uFF20' ||
            c in '\uFF3B'..'\uFF40' ||
            c in '\uFF5B'..'\uFF65' ||
            c == '\u2026' ||
            c == '\u2014'
    }
}

/**
 * A key's shuangpin hints, solved once per layout: one column per letter on the key, each split
 * into at most two lines after its '/' ("iang/" over "uang") when one line would not fit.
 */
internal class SyllableHintLine(
    val columns: List<String?>,
    /** Per column, where the second line starts, or -1 for one line. */
    val breaks: IntArray,
    val textSize: Float,
)

internal fun KeyboardView.solveSyllableHint(
    key: LayoutKey,
    capWidth: Float,
    scale: Float = 1f,
): SyllableHintLine? {
    if (syllableHints.isEmpty()) return null
    // A letter without a hint still gets an (empty) line, so every letter in a row shares one baseline.
    val columns = ShuangpinHintOverlay.hints(key, syllableHints, preservingSecondaryLabel = false)
    if (columns.isEmpty()) return null
    val room = capWidth / columns.size - 2 * density
    val breaks = IntArray(columns.size) { -1 }
    val sizeSp = metrics.keySecondaryLabelTextSizeSp * scale
    var size = sizeSp * textScale
    for ((index, text) in columns.withIndex()) {
        if (text == null) continue
        var fitted = fitSize(text, sizeSp, room, MIN_SYLLABLE_TEXT_SIZE_SP * scale)
        measurePaint.textSize = fitted
        val slash = text.indexOf('/')
        if (measurePaint.measureText(text) > room && slash in 1 until text.length - 1) {
            breaks[index] = slash + 1
            val longer = maxOf(text.substring(0, slash + 1), text.substring(slash + 1), compareBy { it.length })
            fitted = fitSize(longer, sizeSp, room, MIN_HINT_TEXT_SIZE_SP * scale)
        }
        size = minOf(size, fitted)
    }
    return SyllableHintLine(columns, breaks, size)
}

/**
 * The lowest point a key takes touches from: the view's bottom less the user's bottom gap. The gap
 * is there to keep fingers off the system's gesture area, so a touch in it is no key press.
 */
internal fun KeyboardView.hitBottom(): Float = height - metrics.keyAreaExtraBottomDp * density

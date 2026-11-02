package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.T9PunctuationCycle
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutBottomRow
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.PhoneKeypadMetrics
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.layout.PhoneKeypadStructure
import kotlin.math.max

/**
 * The nine-key shell as positioned key data: one 3x3 digit grid, a leading quick-symbol/reading
 * rail, an editing rail and the phone bottom row.
 *
 * It is a pure model (no views, no engine): the renderer only draws [PlacedKey] rectangles, so the
 * grid and both rails share one geometry ([PhoneKeypadMetrics]) and one hit-testing truth. The grid
 * is drawn from the layout's own data (any definition with the telephone structure,
 * [PhoneKeypadStructure], never judged by id), so a layout authored on iOS or copied and edited here
 * shows what its author made. The rails are the bundled ones while the layout still declares the
 * preset's rails ([PhoneKeypadRailPolicy.usesBundledRails]) and the declared keys otherwise. The
 * bottom row uses authored keys when changed; otherwise it follows the native phone shell.
 *
 * The shell has four equal bands (three grid rows and the bottom row) of [Config.height] / 4, so
 * its rows keep exactly the 60 dp pitch of the row pages: the nine-key keyboard is never shorter
 * or tighter than the 26-key one.
 */
object PhoneKeypadLayout {
    enum class Region { MAIN, LEADING_RAIL, TRAILING_RAIL, BOTTOM }

    /** One drawn key in shell coordinates (the shell is placed at the keyboard's content left). */
    data class PlacedKey(
        val region: Region,
        val key: LayoutKey,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** Rows a trailing-rail key spans; 1 for everything else. */
        val rowSpan: Int = 1,
        /**
         * A leading-rail slot of a layout that declares no keys there: nothing is drawn or typed
         * while idle, but the slot exists so a composition's readings have the same three slots
         * every time and the key set never changes under a held finger.
         */
        val readingSlot: Boolean = false,
    )

    /** User-visible labels, resolved by the caller so this model stays platform-free. */
    data class Labels(
        val page: String,
        val language: String,
        val space: String,
        val symbols: String,
        /** The digit pad's return-to-letters key. */
        val back: String,
        /** The digit pad's zero key. */
        val zero: String,
        /** The bottom row's Return key. */
        val enter: String,
        /** The editing rail's clear key and delete glyph; both are also spoken by TalkBack. */
        val clear: String,
        val delete: String,
    )

    data class Config(
        /**
         * Content width in dp (PhoneKeypadMetrics works in dp like the rest of the design tokens);
         * the renderer converts the placed rectangles to px.
         */
        val width: Float,
        /**
         * Height of the whole key area in dp: the three grid rows and the bottom row share it in
         * four equal bands, so the nine-key shell is exactly as tall as the row pages.
         */
        val height: Float,
        /** The 123 digit pad instead of the letter grid (same rails and bottom geometry). */
        val numeric: Boolean = false,
        /** A composition is running: the first key separates syllables and the leading rail reads. */
        val composing: Boolean,
        val asciiPunctuation: Boolean,
        /** The user's saved quick-symbol order; missing slots fall back to the default sequence. */
        val savedQuickSymbols: String = "",
        val languageSwitch: Boolean = true,
        /** False for numeric editors, which have no letter keyboard to return to. */
        val lettersReachable: Boolean = true,
        /** The layout the grid and the rails are drawn from; the bundled nine-key by default. */
        val definition: LayoutDefinition = BUNDLED,
        /** The user asked for the input-method switch key: the generated bottom row carries it. */
        val showsGlobe: Boolean = false,
        val labels: Labels,
    )

    data class Shell(
        val metrics: PhoneKeypadMetrics,
        val keys: List<PlacedKey>,
        /** Total shell height in dp (grid + control row). */
        val height: Float,
    )

    /** The four bands every page shares: three grid rows and the bottom row. */
    private const val BAND_COUNT = PhoneKeypadMetrics.MAIN_ROW_COUNT + 1

    /** The bundled nine-key, built once: the shell compares configs after every key. */
    val BUNDLED: LayoutDefinition = BuiltinLayouts.phone9

    /** Whether [definition] has the telephone structure this shell can draw. */
    fun draws(definition: LayoutDefinition): Boolean = PhoneKeypadStructure.matches(definition)

    fun build(config: Config): Shell? {
        val metrics = PhoneKeypadMetrics.resolve(config.width.toDouble(), config.height.toDouble())
        val pitch = bandPitch(config)
        if (config.numeric) return buildNumeric(config, metrics, pitch)
        val definition = config.definition
        val rows = PhoneKeypadStructure.mainRows(definition) ?: return null
        val bundledRails = PhoneKeypadRailPolicy.usesBundledRails(definition)
        val keys = mutableListOf<PlacedKey>()
        keys +=
            if (bundledRails) leadingRail(metrics, config, pitch) else declaredLeadingRail(definition, metrics, pitch)
        keys += digitGrid(definition, metrics, rows, config, pitch)
        keys +=
            if (bundledRails) {
                trailingRail(metrics, pitch, config.labels)
            } else {
                declaredTrailingRail(definition, metrics, pitch)
            }
        val authored = LayoutBottomRow.authored(definition)
        keys +=
            if (authored != null) {
                authoredBottomRow(metrics, config, pitch, authored, bundledRails)
            } else if (bundledRails) {
                phoneBottomRow(metrics, config, pitch)
            } else {
                bottomRow(metrics, config, pitch)
            }
        return Shell(metrics, keys, (pitch * BAND_COUNT).toFloat())
    }

    /**
     * The row pitch of one band: the whole key area divided by the four bands. It must not come
     * from PhoneKeypadMetrics' own row height, or the nine-key rows would drift from the pages.
     */
    private fun bandPitch(config: Config): Double = max(config.height.toDouble() / BAND_COUNT, 1.0)

    /** One digit cell: the solved column geometry at the band's own vertical position. */
    private fun digitCell(
        metrics: PhoneKeypadMetrics,
        pitch: Double,
        row: Int,
        column: Int,
    ): PhoneKeypadMetrics.Slot {
        val cell = metrics.columnRect(PhoneKeypadMetrics.Column.Digit(column))
        return PhoneKeypadMetrics.Slot(cell.x, row * pitch, metrics.digitWidth, pitch)
    }

    /** The bottom band, spanning exactly the shell content width. */
    private fun controlBand(
        metrics: PhoneKeypadMetrics,
        pitch: Double,
    ): PhoneKeypadMetrics.Slot =
        PhoneKeypadMetrics.Slot(0.0, PhoneKeypadMetrics.MAIN_ROW_COUNT * pitch, metrics.contentWidth, pitch)

    /** Rail slots laid out top to bottom at the shared band pitch; a span is in grid rows. */
    private fun railSlots(
        metrics: PhoneKeypadMetrics,
        pitch: Double,
        rail: PhoneKeypadMetrics.Rail,
        spans: List<Double>,
    ): List<PhoneKeypadMetrics.Slot> {
        val x = if (rail == PhoneKeypadMetrics.Rail.LEADING) 0.0 else metrics.contentWidth - metrics.railWidth
        var y = 0.0
        val slots = mutableListOf<PhoneKeypadMetrics.Slot>()
        for (span in spans) {
            val height = max(MINIMUM_SPAN, span) * pitch
            slots.add(PhoneKeypadMetrics.Slot(x, y, metrics.railWidth, height))
            y += height
        }
        return slots
    }

    private const val MINIMUM_SPAN = 0.5

    /** Deterministic row spans for a rail with [keyCount] keys, at most one key per row. */
    private fun railSpans(keyCount: Int): List<Double> {
        val count = max(0, keyCount)
        if (count == 0) return emptyList()
        if (count >= BAND_COUNT - 1) return List(count) { 1.0 }
        return listOf((BAND_COUNT - 1 - (count - 1)).toDouble()) + List(count - 1) { 1.0 }
    }

    /**
     * The 123 page as a dial pad on the letter shell's grid (the user's own arrangement): the three
     * quick marks beside the three digit rows; on the right, Delete, then 0 and the comma one row
     * each; and the bottom row cell by cell under the rail, the three digit columns and the right
     * column: 返回, 中/英, the space bar, 符号 and a one-row Return. The input-method switch, when
     * shown, takes the cell under the rail and 返回 and 中/英 share the next one, so no key gets
     * narrower than half a digit; a cell left without its key goes to the key beside it, so the row
     * never shows a hole.
     */
    private fun buildNumeric(
        config: Config,
        metrics: PhoneKeypadMetrics,
        pitch: Double,
    ): Shell {
        val keys = mutableListOf<PlacedKey>()
        val ascii = config.asciiPunctuation
        val comma = KeyboardPages.commaKey(ascii)
        val marks = BuiltinLayouts.phoneNumericRailValues(ascii)
        val railSlots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.LEADING, railSpans(NUMERIC_RAIL_MARKS))
        marks.take(NUMERIC_RAIL_MARKS).forEachIndexed { index, mark ->
            // The marks the rail has no room for stay one long press away on its first key; the
            // comma has a key of its own.
            val more =
                if (index == 0) {
                    marks
                        .drop(
                            NUMERIC_RAIL_MARKS,
                        ).filter { it != comma.label }
                        .map { LayoutOption(it, LayoutAction.literal(it)) }
                } else {
                    emptyList()
                }
            val key = LayoutKey(mark, action = LayoutAction.literal(mark), longPress = more.ifEmpty { null })
            keys += placed(Region.LEADING_RAIL, key, railSlots[index])
        }
        for (index in 1..9) {
            val key = LayoutKey(index.toString(), action = LayoutAction.literal(index.toString()))
            keys += placed(Region.MAIN, key, digitCell(metrics, pitch, (index - 1) / 3, (index - 1) % 3))
        }
        val zero = LayoutKey(config.labels.zero, action = LayoutAction.literal(config.labels.zero))
        val trailing =
            listOf(
                LayoutKey(config.labels.delete, action = LayoutAction(LayoutActionKind.BACKSPACE)),
                zero,
                comma.copy(width = null),
            )
        val trailingSlots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.TRAILING, railSpans(trailing.size))
        trailing.forEachIndexed { index, key -> keys += placed(Region.TRAILING_RAIL, key, trailingSlots[index]) }

        val bottomRow = BAND_COUNT - 1
        val top = (bottomRow * pitch).toFloat()
        val bottom = (BAND_COUNT * pitch).toFloat()
        val gap = metrics.spacing
        val cells = (0..2).map { digitCell(metrics, pitch, bottomRow, it) }
        val railRight = metrics.railWidth
        val enterLeft = metrics.contentWidth - metrics.railWidth
        val left =
            buildList {
                if (config.showsGlobe) add(LayoutKey("🌐", action = LayoutAction(LayoutActionKind.GLOBE)))
                if (config.lettersReachable) {
                    add(LayoutKey(config.labels.back, action = LayoutAction.pageSwitch(LayoutPageTarget.LETTERS)))
                }
                if (config.languageSwitch) {
                    add(
                        LayoutKey(config.labels.language, action = LayoutAction.languageSwitch()),
                    )
                }
            }
        val space = LayoutKey(config.labels.space, action = LayoutAction(LayoutActionKind.SPACE))
        val symbols = LayoutKey(config.labels.symbols, action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
        when (left.size) {
            0 -> keys += bottomKey(space, 0.0, top, cells[1].maxX, bottom)
            1 -> keys += bottomKey(left[0], 0.0, top, cells[0].maxX, bottom)
            2 -> {
                keys += bottomKey(left[0], 0.0, top, railRight, bottom)
                keys += bottomKey(left[1], cells[0].minX, top, cells[0].maxX, bottom)
            }
            else -> {
                keys += bottomKey(left[0], 0.0, top, railRight, bottom)
                val half = (cells[0].width - gap) / 2
                keys += bottomKey(left[1], cells[0].minX, top, cells[0].minX + half, bottom)
                keys += bottomKey(left[2], cells[0].maxX - half, top, cells[0].maxX, bottom)
            }
        }
        if (left.isNotEmpty()) keys += bottomKey(space, cells[1].minX, top, cells[1].maxX, bottom)
        keys += bottomKey(symbols, cells[2].minX, top, cells[2].maxX, bottom)
        val enter = LayoutKey(config.labels.enter, action = LayoutAction(LayoutActionKind.ENTER))
        keys += bottomKey(enter, enterLeft, top, metrics.contentWidth, bottom)
        return Shell(metrics, keys, (pitch * BAND_COUNT).toFloat())
    }

    /** The quick marks beside the digit rows: one per row, so the rail and the grid share one grid. */
    private const val NUMERIC_RAIL_MARKS = PhoneKeypadMetrics.MAIN_ROW_COUNT

    private fun placed(
        region: Region,
        key: LayoutKey,
        slot: PhoneKeypadMetrics.Slot,
        rowSpan: Int = 1,
    ) = PlacedKey(
        region = region,
        key = key,
        left = slot.minX.toFloat(),
        top = slot.minY.toFloat(),
        right = slot.maxX.toFloat(),
        bottom = slot.maxY.toFloat(),
        rowSpan = rowSpan,
    )

    /**
     * The first key of the grid: while composing it separates syllables (the apostrophe), idle it
     * types the first quick symbol and cycling happens on repeated taps
     * ([T9FirstKeyState] owns that rule); a long press types 1 either way.
     */
    fun firstKey(
        composing: Boolean,
        savedQuickSymbols: String,
        asciiPunctuation: Boolean,
    ): LayoutKey =
        if (composing) {
            BuiltinLayouts.t9SeparatorKey()
        } else {
            BuiltinLayouts.t9CycleKey(BuiltinLayouts.t9QuickCycleValues(savedQuickSymbols, asciiPunctuation))
                ?: BuiltinLayouts.t9PunctuationKey()
        }

    /**
     * The symbol an idle first-key tap types right now, or null while composing. The controller
     * compares the dispatched literal with this value to recognise a repeat tap that must replace
     * the previously typed symbol instead of appending another one.
     */
    fun firstKeyLiteral(
        composing: Boolean,
        savedQuickSymbols: String,
        asciiPunctuation: Boolean,
    ): String? =
        if (composing) {
            null
        } else {
            BuiltinLayouts.t9QuickCycleValues(savedQuickSymbols, asciiPunctuation).firstOrNull()
        }

    private fun leadingRail(
        metrics: PhoneKeypadMetrics,
        config: Config,
        pitch: Double,
    ): List<PlacedKey> {
        // The engine-backed reading choices replace these slots while composing; that rail needs the
        // ambiguity APIs and is wired by the controller, so this model always carries the symbols.
        val values = BuiltinLayouts.t9QuickRailValues(config.savedQuickSymbols, config.asciiPunctuation)
        val slots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.LEADING, railSpans(values.size))
        return values.mapIndexed { index, value ->
            val slot = slots[index]
            PlacedKey(
                region = Region.LEADING_RAIL,
                key = LayoutKey(value, action = LayoutAction.literal(value)),
                left = slot.minX.toFloat(),
                top = slot.minY.toFloat(),
                right = slot.maxX.toFloat(),
                bottom = slot.maxY.toFloat(),
            )
        }
    }

    /**
     * The 3x3 grid, drawn from the layout's own keys. The telephone "1"/punctuation slot is the live
     * first key while the layout still carries the bundled primary semantics; any other layout
     * types what its author declared there. The eight digit keys keep their declared faces, digit
     * code and long-press digit.
     */
    private fun digitGrid(
        definition: LayoutDefinition,
        metrics: PhoneKeypadMetrics,
        rows: List<app.songyan.ime.layout.LayoutRow>,
        config: Config,
        pitch: Double,
    ): List<PlacedKey> {
        val cycles = cyclesFirstKey(definition)
        val result = mutableListOf<PlacedKey>()
        rows.forEachIndexed { rowIndex, row ->
            row.keys.forEachIndexed { columnIndex, key ->
                val slot = digitCell(metrics, pitch, rowIndex, columnIndex)
                val placedKey =
                    if (cycles && rowIndex == 0 && columnIndex == 0) {
                        liveFirstKey(key, config.composing, config.savedQuickSymbols, config.asciiPunctuation)
                    } else {
                        key
                    }
                result += placed(Region.MAIN, placedKey, slot)
            }
        }
        return result
    }

    /** Whether the first key of [definition] is the live punctuation-cycling key rather than authored data. */
    fun cyclesFirstKey(definition: LayoutDefinition): Boolean = BuiltinLayouts.matchesPhone9PrimarySemantics(definition)

    /**
     * The live first key wearing the author's own face: an edited title shows, and the corner hint
     * and long-press menu are always the author's (the bundled ones type 1).
     */
    private fun liveFirstKey(
        authored: LayoutKey,
        composing: Boolean,
        savedQuickSymbols: String,
        asciiPunctuation: Boolean,
    ): LayoutKey {
        val live = firstKey(composing, savedQuickSymbols, asciiPunctuation)
        val retitled = !composing && authored.label != BUNDLED_FIRST_LABEL
        return live.copy(
            label = if (retitled) authored.label else live.label,
            secondaryLabel = authored.secondaryLabel,
            longPress = authored.longPress,
        )
    }

    private val BUNDLED_FIRST_LABEL: String = BUNDLED.rows[0].keys[0].label

    /** The leading rail of a layout that declares its own: those keys, or reading slots when it has none. */
    private fun declaredLeadingRail(
        definition: LayoutDefinition,
        metrics: PhoneKeypadMetrics,
        pitch: Double,
    ): List<PlacedKey> {
        val declared =
            PhoneKeypadRailPolicy
                .declaredKeys(definition, LayoutAuxiliaryArea.Position.LEADING)
                .take(PhoneKeypadRailPolicy.MAX_RAIL_KEYS)
        if (declared.isEmpty()) {
            val slots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.LEADING, railSpans(READING_SLOTS))
            return slots.map { placed(Region.LEADING_RAIL, READING_SLOT_KEY, it).copy(readingSlot = true) }
        }
        val spans = PhoneKeypadRailPolicy.declaredSpans(declared)
        val slots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.LEADING, spans)
        return declared.mapIndexed { index, key -> placed(Region.LEADING_RAIL, key, slots[index]) }
    }

    /** The trailing rail of a layout that declares its own keys; nothing when it declares none. */
    private fun declaredTrailingRail(
        definition: LayoutDefinition,
        metrics: PhoneKeypadMetrics,
        pitch: Double,
    ): List<PlacedKey> {
        val declared =
            PhoneKeypadRailPolicy
                .declaredKeys(definition, LayoutAuxiliaryArea.Position.TRAILING)
                .take(PhoneKeypadRailPolicy.MAX_RAIL_KEYS)
        val spans = PhoneKeypadRailPolicy.declaredSpans(declared)
        val slots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.TRAILING, spans)
        return declared.mapIndexed { index, key ->
            placed(Region.TRAILING_RAIL, key, slots[index], rowSpan = max(1, Math.round(spans[index]).toInt()))
        }
    }

    private const val READING_SLOTS = 3

    /** The inert key of a reading slot; never typed, never drawn while the slot is empty. */
    private val READING_SLOT_KEY = LayoutKey(" ", action = LayoutAction.literal(" "))

    /**
     * The editing rail on the right of the grid: Delete and 重输 one band each, and the action key
     * covering the last grid band and the bottom band, so the most used of the three is the
     * biggest and the bottom row under the grid keeps the space bar wide.
     */
    private fun trailingRail(
        metrics: PhoneKeypadMetrics,
        pitch: Double,
        labels: Labels,
    ): List<PlacedKey> {
        val keys =
            listOf(
                LayoutKey(labels.delete, action = LayoutAction(LayoutActionKind.BACKSPACE)),
                LayoutKey(labels.clear, action = LayoutAction(LayoutActionKind.CLEAR_INPUT)),
                LayoutKey(labels.enter, action = LayoutAction(LayoutActionKind.ENTER)),
            )
        val spans = TRAILING_SPANS
        val slots = railSlots(metrics, pitch, PhoneKeypadMetrics.Rail.TRAILING, spans.map { it.toDouble() })
        return keys.mapIndexed { index, key -> placed(Region.TRAILING_RAIL, key, slots[index], rowSpan = spans[index]) }
    }

    /** Delete, 重输, and the action key over the last grid band and the bottom band. */
    private val TRAILING_SPANS = listOf(1, 1, 2)

    /**
     * The bottom row of the bundled shell, left of the action key: the page key under the leading
     * rail, then 中/英, the space bar and 0 under the grid. The space bar takes most of the grid's
     * width (side : space : side = 1 : 2.4 : 1, as iFlytek's and Sogou's nine-key do) and sits on
     * the grid's centre line, which is the keyboard's. With no 中/英 the space bar takes its share.
     */
    private fun phoneBottomRow(
        metrics: PhoneKeypadMetrics,
        config: Config,
        pitch: Double,
    ): List<PlacedKey> {
        val band = controlBand(metrics, pitch)
        val top = band.minY.toFloat()
        val bottom = band.maxY.toFloat()
        val gap = metrics.spacing
        val keys = mutableListOf<PlacedKey>()
        if (config.lettersReachable) {
            val page = LayoutKey(config.labels.page, action = LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC))
            keys += bottomKey(page, band.minX, top, band.minX + metrics.railWidth, bottom)
        }
        val gridLeft = band.minX + metrics.railWidth + gap
        val gridRight = band.maxX - metrics.railWidth - gap
        val sides = 1 + (if (config.languageSwitch) 1 else 0) + (if (config.showsGlobe) 1 else 0)
        val span = gridRight - gridLeft - sides * gap
        val side = span / (2.4 + sides)
        var x = gridLeft
        if (config.showsGlobe) {
            val globe = LayoutKey("🌐", action = LayoutAction(LayoutActionKind.GLOBE))
            keys += bottomKey(globe, x, top, x + side, bottom)
            x += side + gap
        }
        if (config.languageSwitch) {
            val language = LayoutKey(config.labels.language, action = LayoutAction.languageSwitch())
            keys += bottomKey(language, x, top, x + side, bottom)
            x += side + gap
        }
        val space = LayoutKey(config.labels.space, action = LayoutAction(LayoutActionKind.SPACE))
        val spaceWidth = span - sides * side
        keys += bottomKey(space, x, top, x + spaceWidth, bottom)
        x += spaceWidth + gap
        val zero = LayoutKey(config.labels.zero, action = LayoutAction.literal(config.labels.zero))
        keys += bottomKey(zero, x, top, gridRight, bottom)
        return keys
    }

    /**
     * The bottom row across the whole content width, with the space bar exactly on the content
     * centre line: the keys left of it and the keys right of it are laid out symmetrically, so the
     * two sides keep the same rhythm however many entries the editor allows.
     */
    private fun bottomRow(
        metrics: PhoneKeypadMetrics,
        config: Config,
        pitch: Double,
    ): List<PlacedKey> {
        val rect = controlBand(metrics, pitch)
        val top = rect.minY.toFloat()
        val bottom = rect.maxY.toFloat()
        // The same footer, with the same width shares, as the row pages: the space bar sits in the
        // same place and has the same width on every layout.
        val footer =
            KeyboardPages.lettersFooter(
                KeyboardPages.Config(
                    layout = KeyboardLayoutKind.PHONE9,
                    englishDirectInput = false,
                    symbolMarksAscii = config.asciiPunctuation,
                    languageSwitch = config.languageSwitch,
                    lettersReachable = config.lettersReachable,
                    showsGlobe = config.showsGlobe,
                ),
            )
        val gap = metrics.spacing
        val unit = (rect.width - (footer.size - 1) * gap) / footer.sumOf { it.widthWeight }
        var x = rect.minX
        return footer.map { key ->
            val width = key.widthWeight * unit
            val placed = bottomKey(key, x, top, x + width, bottom)
            x += width + gap
            placed
        }
    }

    private fun authoredBottomRow(
        metrics: PhoneKeypadMetrics,
        config: Config,
        pitch: Double,
        authored: List<LayoutKey>,
        bundledRails: Boolean,
    ): List<PlacedKey> {
        val keys = authored.filter { config.showsGlobe || it.action.type != LayoutActionKind.GLOBE }
        val right =
            if (bundledRails) {
                metrics
                    .columnRect(
                        PhoneKeypadMetrics.Column.Digit(2),
                    ).maxX
            } else {
                metrics.contentWidth
            }
        val unit = right / keys.sumOf { it.widthWeight }
        var x = 0.0
        return keys.map { key ->
            val placed = bottomKey(key, x, (3 * pitch).toFloat(), x + key.widthWeight * unit, (4 * pitch).toFloat())
            x += key.widthWeight * unit
            placed
        }
    }

    private fun bottomKey(
        key: LayoutKey,
        left: Double,
        top: Float,
        right: Double,
        bottom: Float,
    ): PlacedKey =
        PlacedKey(
            region = Region.BOTTOM,
            key = key,
            left = left.toFloat(),
            top = top,
            right = right.toFloat(),
            bottom = bottom,
        )
}

/**
 * The bundled 9-key first key: an idle tap cycles the quick symbols in place (a repeat within
 * [T9PunctuationCycle.INTERVAL] replaces the previously typed symbol), a composing tap separates
 * syllables, and either way a long press types 1.
 *
 * The document context comes from the host editor, so this class only decides; it never reads or
 * writes the field itself.
 */
class T9FirstKeyState {
    sealed class Decision {
        /** Insert the separator code (a composition is running). */
        data object Separator : Decision()

        /** No quick symbol is available; the key must not look typeable. */
        data object Nothing : Decision()

        /** Insert/replace one quick symbol. */
        data class Cycle(
            val edit: T9PunctuationCycle.Edit,
        ) : Decision()
    }

    /**
     * The last symbol this state itself wrote. It is the fallback proof when the host editor cannot
     * report its text (some Compose/host editors return null for getTextBeforeCursor): the write was
     * only remembered when the commit was actually accepted.
     */
    private var writtenSymbol: String? = null
    private var writtenAt = 0.0
    private var writtenRevision = -1

    /**
     * One idle tap. The cycle uses this state's own accepted-write record; the host text is only a
     * second check, so a host that cannot report its editor still gets in-place replacement while a
     * symbol that never reached the field is never replaced. [now] is in seconds, matching
     * [T9PunctuationCycle.INTERVAL].
     */
    fun tap(
        composing: Boolean,
        savedQuickSymbols: String,
        asciiPunctuation: Boolean,
        contextBeforeCursor: String?,
        now: Double,
        revision: Int,
    ): Decision {
        if (composing) {
            writtenSymbol = null
            return Decision.Separator
        }
        val symbols = BuiltinLayouts.t9QuickCycleValues(savedQuickSymbols, asciiPunctuation)
        if (symbols.isEmpty()) return Decision.Nothing
        val previous = writtenSymbol
        val recent =
            previous != null &&
                now - writtenAt < T9PunctuationCycle.INTERVAL &&
                revision == writtenRevision &&
                (contextBeforeCursor == null || contextBeforeCursor.endsWith(previous))
        val index =
            if (recent) {
                val at = symbols.indexOf(previous)
                if (at < 0) 0 else (at + 1) % symbols.size
            } else {
                0
            }
        return Decision.Cycle(
            T9PunctuationCycle.Edit(
                index = index,
                text = symbols[index],
                replacing = if (recent) previous else null,
                expectedContext = contextBeforeCursor,
            ),
        )
    }

    /**
     * Remembers a written symbol. [accepted] is the editor's own acknowledgement, so the cycle never
     * replaces a symbol that never reached the field.
     */
    fun recordAccepted(
        text: String,
        accepted: Boolean,
        now: Double,
        revision: Int,
    ) {
        if (!accepted) {
            writtenSymbol = null
            return
        }
        writtenSymbol = text
        writtenAt = now
        writtenRevision = revision
    }

    fun reset() {
        writtenSymbol = null
    }
}

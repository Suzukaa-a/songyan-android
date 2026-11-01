package app.songyan.ime.candidate

import kotlin.math.max
import kotlin.math.min

/**
 * Vertical budget of the keyboard panel: reading row + candidate header + main key area + control row.
 *
 * The height derives from the selected physical layout, the size class and the candidate bar config,
 * never from transient language, page or candidate state, so candidates appearing and disappearing
 * only swap the content inside a fixed band.
 */
data class KeyboardPanelGeometry(
    /** Header budget derived from the config and the primary candidate font size (at least 36 dp). */
    val headerHeight: Double,
    /** Standalone reading row budget: 0 when not granted. */
    val readingRowHeight: Double,
    val keyHeight: Double,
    val visibleMainRows: Int,
    /** Extra height a page without a candidate area gets, kept as top blank space. */
    val topSlack: Double = 0.0,
) {
    val mainAreaHeight: Double get() = visibleMainRows * (keyHeight + ROW_SPACING) - ROW_SPACING

    /** Mathematical base height without header and without reading row. */
    val basePanelHeight: Double
        get() = mainAreaHeight + CONTROL_SPACING + keyHeight + VERTICAL_PADDING * 2

    /** Panel height including the header and the granted reading row. */
    val panelHeight: Double
        get() = readingRowHeight + headerHeight + basePanelHeight - VERTICAL_PADDING + CANDIDATE_TOP_PADDING

    /** Real panel height for a state: without a header the base height plus the top slack. */
    fun panelHeight(showsHeader: Boolean): Double = if (showsHeader) panelHeight else basePanelHeight + topSlack

    /** The exact vertical layout plan for one state; shared by the panel view and by tests. */
    fun layoutPlan(showsHeader: Boolean): KeyboardPanelLayoutPlan =
        KeyboardPanelLayoutPlan(geometry = this, showsHeader = showsHeader)

    companion object {
        /** The panel's standard main key area budget (three rows). */
        const val STANDARD_MAIN_ROW_COUNT: Int = 3

        /** Top/bottom padding of the panel body. */
        const val VERTICAL_PADDING: Double = 4.0

        /** Top padding when the candidate area sits at the top: the candidate row owns its touch margin. */
        const val CANDIDATE_TOP_PADDING: Double = 0.0

        /** Fixed gap between the main key area and the control row. */
        const val CONTROL_SPACING: Double = 4.0

        /** Gap between two key rows inside the main area. */
        const val ROW_SPACING: Double = 4.0

        /** Gap between the candidate text and the keys, shared by every layout. */
        const val CANDIDATE_BOTTOM_SPACE: Double = 3.0

        /** Space above the first text row, shared by every layout. */
        const val CANDIDATE_TOP_SPACE: Double = 9.0

        /** Extra height the candidate area gets beyond the configured budget: both shared gaps. */
        const val CANDIDATE_AREA_BONUS: Double = CANDIDATE_TOP_SPACE + CANDIDATE_BOTTOM_SPACE

        /** 14/18-key: the reading row's share of the spare height. */
        const val READING_ROW_SPARE_SHARE: Double = 10.0

        /** 14/18-key: space above the candidate text under the reading row. */
        const val READING_CANDIDATE_SPACE: Double = 2.0

        /** How much less candidate-area bonus a definition with a reading row gets (negative: more). */
        const val READING_LAYOUT_BONUS_CUT: Double =
            CANDIDATE_TOP_SPACE - READING_ROW_SPARE_SHARE - READING_CANDIDATE_SPACE

        /**
         * Builds the geometry for one presentation. [fittedHeaderHeight] and [topSlack] come from
         * [fit]; the extra height of the shell goes to the candidate area (or to top blank space
         * when there is none), and key rows never exceed the standard key height.
         */
        fun create(
            candidateHeight: Double,
            keyHeight: Double,
            selectedMainRows: Int,
            maximumVisibleRows: Int = 3,
            readingRowReserved: Boolean = false,
            candidateSize: Double = 17.0,
            compactReadingRow: Boolean = false,
            fittedHeaderHeight: Double? = null,
            topSlack: Double = 0.0,
            fittedReadingRowHeight: Double? = null,
        ): KeyboardPanelGeometry {
            val header =
                max(
                    CandidateBarDefinition.Collapsed.effectiveHeight(candidateHeight, candidateSize),
                    fittedHeaderHeight ?: 0.0,
                )
            val reading =
                if (readingRowReserved) {
                    fittedReadingRowHeight
                        ?: CandidateReadingRowPolicy.height(reserved = true, compact = compactReadingRow)
                } else {
                    0.0
                }
            // Latin and numeric pages need three rows even when a compact Chinese layout is shorter.
            val rows = max(3, min(max(1, selectedMainRows), max(3, maximumVisibleRows)))
            return KeyboardPanelGeometry(
                headerHeight = header,
                readingRowHeight = reading,
                keyHeight = keyHeight,
                visibleMainRows = rows,
                topSlack = max(0.0, topSlack),
            )
        }

        /** Panel height from "candidate budget + key height" only (host and panel share this entry). */
        fun panelHeight(
            candidateHeight: Double,
            keyHeight: Double,
            selectedMainRows: Int,
            showsHeader: Boolean,
            maximumVisibleRows: Int = 3,
            readingRowReserved: Boolean = false,
            candidateSize: Double = 17.0,
        ): Double =
            create(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                selectedMainRows = selectedMainRows,
                maximumVisibleRows = maximumVisibleRows,
                readingRowReserved = readingRowReserved,
                candidateSize = candidateSize,
            ).layoutPlan(showsHeader).panelHeight

        /** Panel height when only "candidate budget + key height" are needed. */
        fun panelHeight(
            candidateHeight: Double,
            keyHeight: Double,
            showsHeader: Boolean,
            candidateSize: Double = 17.0,
        ): Double =
            panelHeight(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                selectedMainRows = STANDARD_MAIN_ROW_COUNT,
                showsHeader = showsHeader,
                candidateSize = candidateSize,
            )

        /**
         * Production fixed outer budget: the candidate bar budget is kept in every phase and state,
         * so the keyboard shell height does not change with candidates appearing, expand/collapse or
         * Chinese/English switching.
         */
        fun fixedPanelHeight(
            candidateHeight: Double,
            keyHeight: Double,
            maximumVisibleRows: Int = 3,
            readingRowReserved: Boolean = false,
            candidateAreaReserved: Boolean = true,
            candidateSize: Double = 17.0,
        ): Double =
            panelHeight(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                selectedMainRows = STANDARD_MAIN_ROW_COUNT,
                showsHeader = candidateAreaReserved,
                maximumVisibleRows = maximumVisibleRows,
                readingRowReserved = readingRowReserved,
                candidateSize = candidateSize,
            )

        /**
         * Shell height of one presentation: standard key height plus the selected layout's reading
         * row and candidate area. Switching layout during a presentation only redistributes inside
         * this shell (see [fit]); the next presentation starts at the new layout's height.
         */
        fun presentationShellHeight(
            candidateHeight: Double,
            keyHeight: Double,
            candidateSize: Double = 17.0,
            readingRow: Boolean,
            candidateArea: Boolean = true,
            compactReadingRow: Boolean = false,
        ): Double {
            val reading = readingRow && candidateArea
            return fixedPanelHeight(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                readingRowReserved = reading,
                candidateAreaReserved = candidateArea,
                candidateSize = candidateSize,
            ) -
                CandidateReadingRowPolicy.height(reserved = reading) +
                CandidateReadingRowPolicy.height(reserved = reading, compact = compactReadingRow) +
                (if (candidateArea) CANDIDATE_AREA_BONUS - (if (reading) READING_LAYOUT_BONUS_CUT else 0.0) else 0.0)
        }

        /** How far above the keys candidate text is centred: half the candidate row plus the bottom gap. */
        fun candidateTextCentreAboveKeys(
            candidateHeight: Double,
            candidateSize: Double,
        ): Double =
            CandidateBarDefinition.Collapsed.effectiveHeight(candidateHeight, candidateSize) / 2 +
                CANDIDATE_BOTTOM_SPACE

        /**
         * Lays a page out inside [shellHeight]: the rows share the remaining height equally but never
         * exceed [standardKeyHeight]; extra height goes to the candidate area, or stays at the top
         * when there is none. When height is short, the reading row yields first and the key height is
         * squeezed only if that is still not enough.
         */
        fun fit(
            shellHeight: Double,
            candidateHeight: Double,
            standardKeyHeight: Double,
            readingRowReserved: Boolean,
            candidateAreaReserved: Boolean,
            candidateSize: Double = 17.0,
            compactReadingRow: Boolean = false,
        ): KeyboardPanelFit {
            val header =
                if (candidateAreaReserved) {
                    CandidateBarDefinition.Collapsed.effectiveHeight(candidateHeight, candidateSize)
                } else {
                    0.0
                }
            val fullReading =
                if (candidateAreaReserved) {
                    CandidateReadingRowPolicy.height(reserved = readingRowReserved, compact = compactReadingRow)
                } else {
                    0.0
                }
            val top = if (candidateAreaReserved) CANDIDATE_TOP_PADDING else VERTICAL_PADDING
            val rows = (STANDARD_MAIN_ROW_COUNT + 1).toDouble()
            val fixedWithoutReading =
                top + header + (STANDARD_MAIN_ROW_COUNT - 1) * ROW_SPACING + CONTROL_SPACING + VERTICAL_PADDING
            val shortfall = max(0.0, standardKeyHeight * rows - (shellHeight - fixedWithoutReading - fullReading))
            val reading =
                fullReading -
                    min(shortfall, max(0.0, fullReading - CandidateReadingRowPolicy.MINIMUM_HEIGHT))
            val available = shellHeight - fixedWithoutReading - reading
            val key = max(1.0, min(standardKeyHeight, available / rows))
            val slack = max(0.0, available - key * rows)
            // A reading row takes its share of the spare height as touch budget; the candidate row takes the rest.
            val readingExtra = if (reading > 0.0) min(slack, READING_ROW_SPARE_SHARE) else 0.0
            return KeyboardPanelFit(
                keyHeight = key,
                headerHeight = if (candidateAreaReserved) header + slack - readingExtra else 0.0,
                topSlack = if (candidateAreaReserved) 0.0 else slack,
                readingRowHeight = reading + readingExtra,
            )
        }

        /** Base height without header; only a mathematical difference, never the production idle budget. */
        fun idlePanelHeight(
            candidateHeight: Double,
            keyHeight: Double,
            selectedMainRows: Int = STANDARD_MAIN_ROW_COUNT,
            maximumVisibleRows: Int = 3,
            candidateSize: Double = 17.0,
        ): Double =
            panelHeight(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                selectedMainRows = selectedMainRows,
                showsHeader = false,
                maximumVisibleRows = maximumVisibleRows,
                candidateSize = candidateSize,
            )
    }
}

/** A bounded physical key row height, independent of candidate fonts and engine state. */
enum class KeyboardHeightPreset(
    val wire: String,
) {
    COMPACT("compact"),
    STANDARD("standard"),
    COMFORTABLE("comfortable"), ;

    /** Key height in dp; a compact-height surface uses the shorter row. */
    fun keyHeight(compact: Boolean): Double =
        when {
            compact -> 44.0
            this == COMPACT -> 48.0
            this == STANDARD -> 54.0
            else -> 60.0
        }

    companion object {
        /** Unknown or missing stored values fall back to [STANDARD]. */
        fun resolve(storedValue: String?): KeyboardHeightPreset =
            entries.firstOrNull { it.wire == storedValue } ?: STANDARD
    }
}

/** Immutable external height inputs; live content is fitted inside this shell. */
data class KeyboardPanelPresentationBudget(
    val candidateHeight: Double,
    val candidateSize: Double,
    val readingRow: Boolean,
    val candidateArea: Boolean,
    val compact: Boolean,
    val heightPreset: KeyboardHeightPreset = KeyboardHeightPreset.STANDARD,
) {
    val keyHeight: Double get() = heightPreset.keyHeight(compact)

    val shellHeight: Double
        get() =
            KeyboardPanelGeometry.presentationShellHeight(
                candidateHeight = candidateHeight,
                keyHeight = keyHeight,
                candidateSize = candidateSize,
                readingRow = readingRow,
                candidateArea = candidateArea,
                compactReadingRow = compact,
            )

    fun withCompact(compact: Boolean): KeyboardPanelPresentationBudget = copy(compact = compact)
}

/**
 * Result of [KeyboardPanelGeometry.fit]: the key height, the actual candidate area height and the top
 * slack, shared by the height owner and the panel.
 */
data class KeyboardPanelFit(
    val keyHeight: Double,
    /** Actual candidate area (header) height; 0 when there is no candidate area. */
    val headerHeight: Double,
    /** Blank space left at the top when there is no candidate area. */
    val topSlack: Double,
    /** Actual reading row height including its share of the spare height; 0 when not granted. */
    val readingRowHeight: Double = 0.0,
    /** How much was cut off the top of the topmost band, so only blank space above the text goes. */
    val topTrim: Double = 0.0,
)

/**
 * The exact vertical layout of the panel in one state, in content coordinates with the origin at the
 * panel's top edge. There is no bottom reserve: the panel is exactly the reading row (may be 0), the
 * header, the main area, the control row and the top/bottom padding.
 */
class KeyboardPanelLayoutPlan(
    geometry: KeyboardPanelGeometry,
    showsHeader: Boolean,
) {
    /** Header budget chosen by the config and the primary candidate font size. */
    val configuredHeaderHeight: Double = geometry.headerHeight

    /** Panel top to the first band's top edge. */
    val contentTop: Double =
        if (showsHeader) {
            KeyboardPanelGeometry.CANDIDATE_TOP_PADDING
        } else {
            KeyboardPanelGeometry.VERTICAL_PADDING + geometry.topSlack
        }

    val readingRowHeight: Double = if (showsHeader) geometry.readingRowHeight else 0.0
    val headerHeight: Double = if (showsHeader) geometry.headerHeight else 0.0
    val mainAreaHeight: Double = geometry.mainAreaHeight
    val keyHeight: Double = geometry.keyHeight
    val panelHeight: Double = geometry.panelHeight(showsHeader)

    val readingRowFrame: PanelVerticalFrame get() = PanelVerticalFrame(top = contentTop, height = readingRowHeight)

    val headerFrame: PanelVerticalFrame
        get() = PanelVerticalFrame(top = readingRowFrame.maxY, height = headerHeight)

    /** Main area viewport; its top edge is the first key row's top. */
    val mainAreaFrame: PanelVerticalFrame
        get() = PanelVerticalFrame(top = headerFrame.maxY, height = mainAreaHeight)

    /** Top y of the first key row (= the main area's top). */
    val firstKeyRowTop: Double get() = mainAreaFrame.minY

    val controlRowFrame: PanelVerticalFrame
        get() = PanelVerticalFrame(top = mainAreaFrame.maxY + KeyboardPanelGeometry.CONTROL_SPACING, height = keyHeight)

    /** Panel bottom y; always equal to [panelHeight]. */
    val contentBottom: Double get() = controlRowFrame.maxY + KeyboardPanelGeometry.VERTICAL_PADDING

    /**
     * Adjacent bands meet exactly without overlapping: reading row bottom == header top, header
     * bottom == main area top, main area bottom + spacing == control row top, content bottom == panel
     * height.
     */
    val bandsAreContiguous: Boolean
        get() =
            readingRowFrame.minY == contentTop &&
                readingRowFrame.maxY == headerFrame.minY &&
                headerFrame.maxY == mainAreaFrame.minY &&
                controlRowFrame.minY == mainAreaFrame.maxY + KeyboardPanelGeometry.CONTROL_SPACING &&
                contentBottom == panelHeight

    /** Distance from the top edge of [band] to the panel bottom. */
    fun distanceToPanelBottomFromTop(band: PanelVerticalFrame): Double = panelHeight - band.minY

    /** Distance from the bottom edge of [band] to the panel bottom. */
    fun distanceToPanelBottomFromBottom(band: PanelVerticalFrame): Double = panelHeight - band.maxY
}

/** A one-dimensional (vertical) frame: top edge plus height. */
data class PanelVerticalFrame(
    val top: Double,
    val height: Double,
) {
    val maxY: Double get() = top + height
    val minY: Double get() = top

    fun intersects(other: PanelVerticalFrame): Boolean = minY < other.maxY && other.minY < maxY
}

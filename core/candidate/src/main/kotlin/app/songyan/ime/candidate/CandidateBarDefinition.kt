package app.songyan.ime.candidate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Candidate area presentation config (`*.songyancand.json`), wire-compatible with the iOS
 * `CandidateBarDefinition`: same field names, same enum raw values, same defaults and the same
 * strict decoding discipline (unknown fields rejected, omitted fields filled with the v1 defaults).
 *
 * The config only describes *how* to draw, never *which* candidates to draw: candidate data always
 * comes from the engine snapshot and the renderer forwards the engine's own index untouched.
 *
 * Built-in entries carry their id as [name]; the UI resolves their display names from string
 * resources, so no user-facing copy lives in this module.
 */
@Serializable
data class CandidateBarDefinition(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String,
    val name: String,
    val author: String? = null,
    val compositionRow: CompositionRow = CompositionRow(),
    val collapsed: Collapsed = Collapsed(),
    val expanded: Expanded = Expanded(),
    val controls: Controls = Controls(),
) {
    /** Visibility rule of the composition (preedit) row; hidden without input by default. */
    @Serializable
    data class CompositionRow(
        val visible: Visibility = Visibility.COMPOSING_ONLY,
    ) {
        @Serializable
        enum class Visibility {
            /** Shown only while composing (the default, cleanest UX). */
            @SerialName("composingOnly")
            COMPOSING_ONLY,

            /** Shown while composing; when the composition is empty a faded raw-code row is shown. */
            @SerialName("composingWithIdleRaw")
            COMPOSING_WITH_IDLE_RAW,

            /** Never shown (compact). */
            @SerialName("hidden")
            HIDDEN,
        }
    }

    /** Parameters of the collapsed candidate strip. The height is the full touch budget. */
    @Serializable
    data class Collapsed(
        /** Strip height in dp. 36..56; the struct default is 44 while [systemDefault] uses 40. */
        val height: Double = 44.0,
        /** Horizontal padding of a candidate cell in dp. 4..24. */
        val cellPaddingX: Double = 12.0,
        /** Spacing between adjacent candidates in dp. 0..16. */
        val cellSpacing: Double = 4.0,
        /**
         * null keeps the legacy even-split strategy; false uses the natural text width.
         * Preserved through copy/encode so an omitted strategy stays omitted.
         */
        val fillsAvailableWidth: Boolean? = null,
    ) {
        companion object {
            /**
             * Render-time clamp of the *config value* (36..56). This is not yet a usable surface
             * height; see [effectiveHeight].
             */
            fun clampedHeight(value: Double): Double =
                if (value.isFinite()) value.coerceIn(MIN_HEIGHT, MAX_HEIGHT) else MIN_HEIGHT

            /** The configured range is topped up for the primary candidate font size. */
            fun effectiveHeight(
                value: Double,
                candidateSize: Double = 17.0,
            ): Double {
                val safeCandidateSize = if (candidateSize.isFinite()) maxOf(0.0, candidateSize) else 17.0
                val textBudget = kotlin.math.ceil(safeCandidateSize * 1.25 + 3.0)
                return maxOf(clampedHeight(value), maxOf(textBudget, MIN_SURFACE_HEIGHT))
            }

            const val MIN_HEIGHT: Double = 36.0
            const val MAX_HEIGHT: Double = 56.0

            /** The candidate top bar's base height before the primary font size tops it up. */
            const val MIN_SURFACE_HEIGHT: Double = 36.0
        }
    }

    /** Parameters of the "expand current page candidates" area, which replaces the main key area. */
    @Serializable
    data class Expanded(
        /** Expanded layout mode; the default flows by actual text width. */
        val mode: Mode = Mode.ADAPTIVE,
        /** Grid column count 1..4 (used only by [Mode.GRID]). */
        val columns: Int = 2,
        /** Row spacing in dp. 0..16. */
        val rowSpacing: Double = 4.0,
        /** Column spacing in dp. 0..16. */
        val columnSpacing: Double = 4.0,
        /**
         * A candidate whose text is longer than this (or whose comment is longer than 3x this)
         * takes its own row so long phrases stay readable. 1..40.
         */
        val fullRowTextLength: Int = 8,
    ) {
        @Serializable
        enum class Mode {
            /** Adaptive flow: laid out left to right by actual text width, wrapping when a row is full. */
            @SerialName("adaptive")
            ADAPTIVE,

            /** One-column compact list (left-aligned, comments follow). */
            @SerialName("list")
            LIST,

            /** Fixed column-count grid (respects an explicitly configured column count). */
            @SerialName("grid")
            GRID,
        }

        /** Whether the expanded layout adapts to text width rather than a fixed column count. */
        val isAdaptive: Boolean get() = mode == Mode.ADAPTIVE

        val effectiveColumns: Int get() = if (mode == Mode.LIST) 1 else columns
    }

    /** Placement of the paging and expand controls; the degradation order is the renderer's. */
    @Serializable
    data class Controls(
        val pagePosition: PagePosition = PagePosition.BOTH,
        /** Whether the "expand/collapse current page candidates" button is shown. */
        val showsExpand: Boolean = true,
    ) {
        @Serializable
        enum class PagePosition {
            /** Previous on the left, next on the right (symmetric at both ends). */
            @SerialName("both")
            BOTH,

            /** Only "previous/next page" on the right. */
            @SerialName("trailing")
            TRAILING,

            /** Left side only. */
            @SerialName("leading")
            LEADING,
        }
    }

    /** Layout budget of the collapsed strip, shared with the surface and reported height. */
    val collapsedHeightBudget: Double get() = Collapsed.effectiveHeight(collapsed.height)

    val expandedRowSpacing: Double get() = expanded.rowSpacing.coerceIn(0.0, 16.0)

    val expandedColumnSpacing: Double get() = expanded.columnSpacing.coerceIn(0.0, 16.0)

    val expandedEffectiveColumns: Int get() = expanded.effectiveColumns.coerceIn(1, 4)

    /**
     * A duplicate with a fresh identity. [nameSuffix] is passed in because the localized
     * "copy" suffix belongs to the UI layer.
     */
    fun duplicate(nameSuffix: String): CandidateBarDefinition =
        copy(
            id = UUID.randomUUID().toString(),
            name = name + nameSuffix,
        )

    /** Rejects values the format cannot represent; called before every encode and after every decode. */
    fun validate() {
        requireField(formatVersion == FORMAT_VERSION, "$.formatVersion")
        requireField(id.isNotEmpty() && id.all { it in ID_CHARS }, "$.id")
        requireField(name.isNotBlank(), "$.name")
        requireField(collapsed.height.isFinite(), "$.collapsed.height")
        requireField(
            collapsed.height >= Collapsed.MIN_HEIGHT && collapsed.height <= Collapsed.MAX_HEIGHT,
            "$.collapsed.height",
        )
        requireField(
            collapsed.cellPaddingX.isFinite() && collapsed.cellPaddingX in 4.0..24.0,
            "$.collapsed.cellPaddingX",
        )
        requireField(
            collapsed.cellSpacing.isFinite() && collapsed.cellSpacing in 0.0..16.0,
            "$.collapsed.cellSpacing",
        )
        requireField(expanded.columns in 1..4, "$.expanded.columns")
        requireField(
            expanded.rowSpacing.isFinite() && expanded.rowSpacing in 0.0..16.0,
            "$.expanded.rowSpacing",
        )
        requireField(
            expanded.columnSpacing.isFinite() && expanded.columnSpacing in 0.0..16.0,
            "$.expanded.columnSpacing",
        )
        requireField(expanded.fullRowTextLength in 1..40, "$.expanded.fullRowTextLength")
    }

    /** Encodes with every default written, so a re-import sees exactly what was exported. */
    fun encode(): String {
        validate()
        val text = StrictJson.encoder.encodeToString(serializer(), this)
        if (text.encodeToByteArray().size > MAX_BYTES) {
            throw CandidateBarError(CandidateBarErrorCode.OVERSIZE, "$")
        }
        return text
    }

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val SYSTEM_DEFAULT_ID: String = "system-default"

        /** Byte budget of one candidate-area document (over-budget inputs are rejected, not truncated). */
        const val MAX_BYTES: Int = 128 * 1024

        private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

        private val TOP_LEVEL_FIELDS =
            setOf("formatVersion", "id", "name", "author", "compositionRow", "collapsed", "expanded", "controls")
        private val COMPOSITION_ROW_FIELDS = setOf("visible")
        private val COLLAPSED_FIELDS = setOf("height", "cellPaddingX", "cellSpacing", "fillsAvailableWidth")
        private val EXPANDED_FIELDS = setOf("mode", "columns", "rowSpacing", "columnSpacing", "fullRowTextLength")
        private val CONTROLS_FIELDS = setOf("pagePosition", "showsExpand")

        /**
         * The built-in system default: a 40 dp collapsed strip. The touch budget of the 21 dp
         * primary candidate is about 30 dp, so 40 dp keeps ~5 dp above and below. It writes the page
         * buttons at the trailing end; the file-format default of [Controls.pagePosition] stays
         * iOS's "both ends".
         */
        val systemDefault: CandidateBarDefinition =
            CandidateBarDefinition(
                id = SYSTEM_DEFAULT_ID,
                name = SYSTEM_DEFAULT_ID,
                collapsed =
                    Collapsed(height = 40.0, cellPaddingX = 7.0, cellSpacing = 0.0, fillsAvailableWidth = false),
                expanded = Expanded(rowSpacing = 0.0, columnSpacing = 0.0),
                controls = Controls(pagePosition = Controls.PagePosition.TRAILING),
            )

        /**
         * Android's built-in candidate area: a 48 dp row (the platform's touch target), 10 dp cell
         * padding and no spacing. Built-in appearances carry these numbers so the file means what
         * the keyboard draws.
         */
        val androidDefault: CandidateBarDefinition =
            systemDefault.copy(collapsed = systemDefault.collapsed.copy(height = 48.0, cellPaddingX = 10.0))

        private fun allowedFields(path: String): Set<String> =
            when (path) {
                "$" -> TOP_LEVEL_FIELDS
                "$.compositionRow" -> COMPOSITION_ROW_FIELDS
                "$.collapsed" -> COLLAPSED_FIELDS
                "$.expanded" -> EXPANDED_FIELDS
                "$.controls" -> CONTROLS_FIELDS
                else -> emptySet()
            }

        /**
         * Strict decode: unknown fields and unsupported versions are rejected, omitted fields keep
         * the v1 defaults (an omitted `collapsed.height` stays 44 dp), then [validate] runs.
         */
        fun decode(text: String): CandidateBarDefinition {
            if (text.encodeToByteArray().size > MAX_BYTES) {
                throw CandidateBarError(CandidateBarErrorCode.OVERSIZE, "$")
            }
            val source =
                try {
                    StrictJson.decoder.parseToJsonElement(text) as? JsonObject
                } catch (error: IllegalArgumentException) {
                    throw CandidateBarError(CandidateBarErrorCode.MALFORMED_JSON, "$")
                } ?: throw CandidateBarError(CandidateBarErrorCode.NOT_AN_OBJECT, "$")

            StrictJson.rejectUnknownFields(source, "$", ::allowedFields) { path ->
                throw CandidateBarError(CandidateBarErrorCode.UNKNOWN_FIELD, path)
            }
            val version = (source["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            if (version != FORMAT_VERSION.toString()) {
                throw CandidateBarError(CandidateBarErrorCode.UNSUPPORTED_VERSION, "$.formatVersion")
            }

            val defaults = defaultWireForm()
            val merged = mergeIntoDefaults(source, defaults)
            val decoded =
                try {
                    StrictJson.decoder.decodeFromJsonElement(serializer(), merged)
                } catch (error: IllegalArgumentException) {
                    throw CandidateBarError(CandidateBarErrorCode.INVALID_VALUE, "$")
                }
            decoded.validate()
            return decoded
        }

        /** The wire form of a struct-default document, minus the identity fields a document must supply. */
        private fun defaultWireForm(): JsonObject {
            val full =
                StrictJson.encoder
                    .encodeToJsonElement(
                        serializer(),
                        CandidateBarDefinition(id = "", name = ""),
                    ).jsonObject
            return buildJsonObject {
                for ((key, value) in full) {
                    if (key == "id" || key == "name") continue
                    put(key, value)
                }
            }
        }

        /** Input overrides defaults field by field; nested objects merge recursively, exactly as the iOS reader does. */
        private fun mergeIntoDefaults(
            input: JsonObject,
            base: JsonObject,
        ): JsonObject =
            buildJsonObject {
                for ((key, value) in base) put(key, input[key] ?: value)
                for ((key, value) in input) {
                    val baseValue = base[key]
                    if (value is JsonObject && baseValue is JsonObject) {
                        put(key, mergeIntoDefaults(value, baseValue))
                    } else {
                        put(key, value)
                    }
                }
            }
    }
}

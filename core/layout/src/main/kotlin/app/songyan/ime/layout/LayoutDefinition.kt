package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Machine-readable reasons a layout (or one of its keys) is rejected. The settings UI maps these
 * to localized strings; the model itself never carries user-visible text.
 */
enum class LayoutInvalidCode {
    ASCII_VALUE_ONLY_ON_LITERAL,
    CODE_REQUIRES_ONE_PRINTABLE_ASCII_CHARACTER,
    LITERAL_REQUIRES_NONEMPTY_VALUE,
    ASCII_VALUE_MUST_NOT_BE_EMPTY,
    MODE_SWITCH_SUPPORTS_ONLY_NEXT_LAYOUT,
    PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
    LAYOUT_PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
    LANGUAGE_SWITCH_REQUIRES_KNOWN_BEHAVIOR,
    FUNCTION_KEY_TAKES_NO_VALUE,
    KEY_OF_KIND_TAKES_NO_VALUE,
    LONG_PRESS_DELAY_MUST_BE_POSITIVE_FINITE,
    ID_AND_NAME_REQUIRED,
    SCHEMA_ID_MUST_NOT_BE_EMPTY,
    INPUT_REQUIRES_SCHEMA_OR_BUILTIN,
    ROWS_MUST_CONTAIN_KEYS,
    READING_ROW_REQUIRES_CANDIDATE_AREA,
    PHONE_LAYOUT_USES_LEADING_READING_RAIL,
    CODE_KEYS_REQUIRE_CANDIDATE_AREA,
    DISAMBIGUATION_KEYS_MUST_BE_ENGINE_SUPPLIED,
    KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH,
    LONG_PRESS_REQUIRES_LABEL,
    SPLIT_GAP_FRACTION_OUT_OF_RANGE,
    SPLIT_BREAK_OUT_OF_RANGE,

    /** The explicit split arrangement does not have one entry for every row of the layout. */
    SPLIT_HALVES_ROW_COUNT_MISMATCH,

    /** A row of the explicit split arrangement has no key in one of its halves. */
    SPLIT_HALF_EMPTY,

    /** Only a main layout carries its own split arrangement; pages follow the layout's split. */
    SPLIT_HALVES_ONLY_ON_LAYOUT,
    RESOURCE_BUDGET_EXCEEDED,
    MALFORMED_JSON,

    /** The file could not be read (an IO failure, not invalid content). */
    UNREADABLE_SOURCE,
    DUPLICATE_LAYOUT_IDS,
    CATALOG_TOTAL_EXCEEDED,
    PAGE_NUMERIC_PRESENTATION_ONLY_ON_NUMERIC_PAGE,
    PAGE_AUTOMATIC_NUMERIC_TAKES_NO_CONTENT,
    PAGE_CATEGORIES_ONLY_ON_SYMBOL_PAGE,
    PAGE_CATEGORIES_NEED_CATEGORY_PRESENTATION,
    PAGE_CATEGORY_PAGE_TAKES_NO_LEADING_RAIL,
    PAGE_ID_AND_NAME_REQUIRED,
    PAGE_CATEGORIES_REQUIRED,
    PAGE_CATEGORY_IDS_DUPLICATED,
    PAGE_KEYPAD_REQUIRES_ROWS,
    PAGE_KEY_MUST_NOT_SEND_A_CODE,
    PAGE_RAIL_WIDTH_MUST_BE_POSITIVE,
    PAGE_CATEGORY_ID_AND_NAME_REQUIRED,
    PAGE_CATEGORY_COLUMNS_OUT_OF_RANGE,
    PAGE_CATEGORY_KEYS_REQUIRED,
    PAGE_DUPLICATE_IDS,
    PAGE_UNKNOWN,
    PAGE_IN_USE,
    PAGE_PRESET_IS_READ_ONLY,

    /** A layout that ships with the app cannot be deleted; it can be copied and edited instead. */
    LAYOUT_PRESET_IS_READ_ONLY,
    LAYOUT_IN_USE,
    COMPOSITION_KEYBOARD_ID_INVALID,
    COMPOSITION_INPUT_METHOD_INCOMPATIBLE,
    COMPOSITION_PAGE_KIND_MISMATCH,
    REGISTRY_PAGE_LIMIT,
    REGISTRY_COMPOSITION_LIMIT,
}

/** Raised by [LayoutDefinition.validate] and the registry operations. */
sealed class LayoutException(
    message: String,
) : Exception(message) {
    /** The layout is malformed. [code] is stable; [detail] is developer-facing. */
    class Invalid(
        val code: LayoutInvalidCode,
        val detail: String = "",
    ) : LayoutException("Invalid layout: " + code.name + (if (detail.isEmpty()) "" else " (" + detail + ")"))

    class DuplicateId(
        val layoutId: String,
    ) : LayoutException("Layout already exists: " + layoutId)

    class UnknownId(
        val layoutId: String,
    ) : LayoutException("Unknown layout: " + layoutId)
}

/** Data only: actions are dispatched by the keyboard host, never evaluated as scripts. */
@Serializable
enum class LayoutActionKind {
    @SerialName("code")
    CODE,

    @SerialName("literal")
    LITERAL,

    @SerialName("space")
    SPACE,

    @SerialName("backspace")
    BACKSPACE,

    @SerialName("enter")
    ENTER,

    @SerialName("globe")
    GLOBE,

    @SerialName("modeSwitch")
    MODE_SWITCH,

    @SerialName("shift")
    SHIFT,

    @SerialName("pageSwitch")
    PAGE_SWITCH,

    @SerialName("languageSwitch")
    LANGUAGE_SWITCH,

    @SerialName("symbolLock")
    SYMBOL_LOCK,

    @SerialName("clearInput")
    CLEAR_INPUT,

    /**
     * Opens a non-letter page of another keyboard layout and makes that layout the running one. A
     * kind of its own (not a [PAGE_SWITCH] value) so a decoder that predates it rejects the file
     * instead of opening a page of the wrong layout.
     */
    @SerialName("layoutPageSwitch")
    LAYOUT_PAGE_SWITCH,
}

/** The only pages a [LayoutActionKind.PAGE_SWITCH] key may open. */
enum class LayoutPageTarget(
    val wire: String,
) {
    LETTERS("letters"),
    NUMERIC("numeric"),
    SYMBOLS("symbols"),
    ;

    companion object {
        fun fromWire(value: String?): LayoutPageTarget? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a [LayoutActionKind.LAYOUT_PAGE_SWITCH] key goes: the 123 or symbol page of one keyboard layout.
 * A layout's letter page is no target; its 123 / symbol page is the one its pairing chooses for the
 * running input method. Wire form: `"<layout>:<page>"`, e.g. `"phone9:numeric"`.
 */
data class LayoutPageJump(
    val layout: LayoutInput.Builtin,
    val page: LayoutPageTarget,
) {
    init {
        require(page != LayoutPageTarget.LETTERS) { "A layout's letter page is not a jump target" }
    }

    val wire: String get() = layout.wire + SEPARATOR + page.wire

    companion object {
        private const val SEPARATOR = ':'

        /** The pages a jump may open, in the order an editor lists them. */
        val PAGES: List<LayoutPageTarget> = listOf(LayoutPageTarget.NUMERIC, LayoutPageTarget.SYMBOLS)

        /** Every target, layout by layout. */
        val all: List<LayoutPageJump> =
            LayoutInput.Builtin.entries.flatMap { layout -> PAGES.map { LayoutPageJump(layout, it) } }

        /** Null for anything but exactly one known layout and one non-letter page. */
        fun fromWire(value: String?): LayoutPageJump? {
            if (value == null) return null
            val parts = value.split(SEPARATOR)
            if (parts.size != 2) return null
            val layout = LayoutInput.Builtin.fromWire(parts[0]) ?: return null
            val page = LayoutPageTarget.fromWire(parts[1])?.takeIf { it in PAGES } ?: return null
            return LayoutPageJump(layout, page)
        }
    }
}

/** Explicit behavior of an authored language key. */
enum class LayoutLanguageSwitchBehavior(
    val wire: String,
) {
    /** Switch only the panel symbol language; the page does not change. */
    SYMBOLS("symbols"),

    /** Return to the main letter keyboard, keeping the Chinese/English input state. */
    LETTERS("letters"),
    ;

    companion object {
        fun fromWire(value: String?): LayoutLanguageSwitchBehavior? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class LayoutAction(
    val type: LayoutActionKind,
    val value: String? = null,
    /**
     * ASCII counterpart of a bilingual literal (Chinese is [value]). Null keeps the literal
     * fixed; only [LayoutActionKind.LITERAL] may carry it.
     */
    val asciiValue: String? = null,
) {
    /** The page a [LayoutActionKind.PAGE_SWITCH] key opens, or null for every other kind. */
    val pageSwitchTarget: LayoutPageTarget?
        get() = if (type != LayoutActionKind.PAGE_SWITCH) null else LayoutPageTarget.fromWire(value)

    /** Where a [LayoutActionKind.LAYOUT_PAGE_SWITCH] key goes, or null for every other kind. */
    val layoutPageJump: LayoutPageJump?
        get() = if (type != LayoutActionKind.LAYOUT_PAGE_SWITCH) null else LayoutPageJump.fromWire(value)

    /** The explicit behavior authored on a language key, or null when the key follows the page. */
    val languageSwitchBehavior: LayoutLanguageSwitchBehavior?
        get() =
            if (type != LayoutActionKind.LANGUAGE_SWITCH) {
                null
            } else {
                LayoutLanguageSwitchBehavior.fromWire(value)
            }

    /** The text a literal key types for the current punctuation state; null for other kinds. */
    fun literalValue(usesAsciiPunctuation: Boolean): String? {
        if (type != LayoutActionKind.LITERAL) return null
        if (usesAsciiPunctuation && asciiValue != null) return asciiValue
        return value
    }

    /** Replaces only the ASCII counterpart, keeping the kind and the Chinese value. */
    fun withAsciiValue(asciiValue: String?): LayoutAction = copy(asciiValue = asciiValue)

    @Throws(LayoutException::class)
    fun validate() {
        if (type != LayoutActionKind.LITERAL && asciiValue != null) {
            throw LayoutException.Invalid(LayoutInvalidCode.ASCII_VALUE_ONLY_ON_LITERAL)
        }
        when (type) {
            LayoutActionKind.CODE -> {
                val text = value
                if (text == null || !isSinglePrintableAscii(text)) {
                    throw LayoutException.Invalid(
                        LayoutInvalidCode.CODE_REQUIRES_ONE_PRINTABLE_ASCII_CHARACTER,
                        "code=" + text,
                    )
                }
            }
            LayoutActionKind.LITERAL -> {
                val text = value
                if (text == null || text.isEmpty()) {
                    throw LayoutException.Invalid(LayoutInvalidCode.LITERAL_REQUIRES_NONEMPTY_VALUE)
                }
                if (asciiValue != null && asciiValue.isEmpty()) {
                    throw LayoutException.Invalid(LayoutInvalidCode.ASCII_VALUE_MUST_NOT_BE_EMPTY)
                }
            }
            LayoutActionKind.MODE_SWITCH -> {
                if (value != null && value != NEXT_LAYOUT) {
                    throw LayoutException.Invalid(
                        LayoutInvalidCode.MODE_SWITCH_SUPPORTS_ONLY_NEXT_LAYOUT,
                        "value=" + value,
                    )
                }
            }
            LayoutActionKind.PAGE_SWITCH -> {
                if (pageSwitchTarget == null) {
                    throw LayoutException.Invalid(
                        LayoutInvalidCode.PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
                        "value=" + value,
                    )
                }
            }
            LayoutActionKind.LAYOUT_PAGE_SWITCH -> {
                if (layoutPageJump == null) {
                    throw LayoutException.Invalid(
                        LayoutInvalidCode.LAYOUT_PAGE_SWITCH_REQUIRES_KNOWN_TARGET,
                        "value=" + value,
                    )
                }
            }
            LayoutActionKind.LANGUAGE_SWITCH -> {
                if (value != null && languageSwitchBehavior == null) {
                    throw LayoutException.Invalid(
                        LayoutInvalidCode.LANGUAGE_SWITCH_REQUIRES_KNOWN_BEHAVIOR,
                        "value=" + value,
                    )
                }
            }
            LayoutActionKind.SHIFT,
            LayoutActionKind.SYMBOL_LOCK,
            LayoutActionKind.CLEAR_INPUT,
            ->
                if (value != null) {
                    throw LayoutException.Invalid(LayoutInvalidCode.FUNCTION_KEY_TAKES_NO_VALUE, type.name)
                }
            LayoutActionKind.SPACE,
            LayoutActionKind.BACKSPACE,
            LayoutActionKind.ENTER,
            LayoutActionKind.GLOBE,
            ->
                if (value != null) {
                    throw LayoutException.Invalid(LayoutInvalidCode.KEY_OF_KIND_TAKES_NO_VALUE, type.name)
                }
        }
    }

    companion object {
        private const val NEXT_LAYOUT = "nextLayout"

        fun pageSwitch(target: LayoutPageTarget): LayoutAction =
            LayoutAction(LayoutActionKind.PAGE_SWITCH, value = target.wire)

        fun layoutPageSwitch(target: LayoutPageJump): LayoutAction =
            LayoutAction(LayoutActionKind.LAYOUT_PAGE_SWITCH, value = target.wire)

        fun languageSwitch(behavior: LayoutLanguageSwitchBehavior? = null): LayoutAction =
            LayoutAction(LayoutActionKind.LANGUAGE_SWITCH, value = behavior?.wire)

        fun code(value: String): LayoutAction = LayoutAction(LayoutActionKind.CODE, value = value)

        fun literal(
            value: String,
            asciiValue: String? = null,
        ): LayoutAction = LayoutAction(LayoutActionKind.LITERAL, value = value, asciiValue = asciiValue)

        /** A `code` payload must be exactly one printable ASCII character. */
        fun isSinglePrintableAscii(value: String): Boolean = value.length == 1 && value[0].code in 33..126
    }
}

@Serializable data class LayoutOption(
    val label: String,
    val action: LayoutAction,
)

/** How a key face derives its title; null keeps the class semantics (host draws the live face). */
@Serializable
enum class LayoutKeyTitleMode {
    @SerialName("automatic")
    AUTOMATIC,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class LayoutKey(
    val label: String,
    val secondaryLabel: String? = null,
    /**
     * Positive relative weight. In a key row or the footer it is a relative width; in a leading
     * or trailing rail it is a relative vertical span. Null means 1.
     */
    val width: Double? = null,
    val action: LayoutAction,
    val longPress: List<LayoutOption>? = null,
    val titleMode: LayoutKeyTitleMode? = null,
) {
    val widthWeight: Double get() = width ?: 1.0

    /**
     * What the key shows when the symbol marks are [asciiMarks]. A literal with an automatic title
     * shows the text it types in that branch; every other key keeps its saved label.
     */
    fun face(asciiMarks: Boolean): String {
        if (titleMode != LayoutKeyTitleMode.AUTOMATIC) return label
        return action.literalValue(asciiMarks) ?: label
    }

    /** Field-preserving edits: editors must not rebuild a key and silently drop [titleMode]. */
    fun withLabel(label: String): LayoutKey = copy(label = label, titleMode = LayoutKeyTitleMode.CUSTOM)

    fun withTitleMode(mode: LayoutKeyTitleMode?): LayoutKey = copy(titleMode = mode)

    fun withSecondaryLabel(label: String?): LayoutKey = copy(secondaryLabel = label)

    fun withWidth(width: Double?): LayoutKey = copy(width = width)

    fun withAction(action: LayoutAction): LayoutKey = copy(action = action)

    fun withLongPress(options: List<LayoutOption>?): LayoutKey = copy(longPress = options)
}

@Serializable data class LayoutRow(
    val keys: List<LayoutKey>,
)

/** A schemaID is a reference to an installed Rime schema, not a translator implementation. */
@Serializable
data class LayoutInput(
    val schemaID: String? = null,
    val builtinLayout: Builtin? = null,
) {
    @Serializable
    enum class Builtin(
        val wire: String,
    ) {
        @SerialName("qwerty26")
        QWERTY26("qwerty26"),

        @SerialName("merged14")
        MERGED14("merged14"),

        @SerialName("merged18")
        MERGED18("merged18"),

        @SerialName("phone9")
        PHONE9("phone9"),
        ;

        companion object {
            fun fromWire(value: String?): Builtin? = entries.firstOrNull { it.wire == value }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayoutAuxiliaryArea(
    val position: Position,
    val role: Role,
    /** Always written, even when empty: the iOS decoder requires the field. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keys: List<LayoutKey> = emptyList(),
) {
    @Serializable
    enum class Position {
        @SerialName("leading")
        LEADING,

        @SerialName("trailing")
        TRAILING,
    }

    @Serializable
    enum class Role {
        @SerialName("punctuation")
        PUNCTUATION,

        @SerialName("editing")
        EDITING,

        @SerialName("disambiguation")
        DISAMBIGUATION,
    }

    /** The host must hide this region unless an engine-backed disambiguation API exists. */
    val requiresEngineDisambiguation: Boolean get() = role == Role.DISAMBIGUATION
}

/** Candidate surface requirements belong to the physical layout, not its name or schema. */
@Serializable data class LayoutCandidateArea(
    val candidates: Boolean,
    val reading: Boolean,
)

/**
 * A physical keyboard definition. Pure data: it never reads or writes files; see [LayoutStore]
 * for persistence.
 */
@Serializable
data class LayoutDefinition(
    val id: String,
    val name: String,
    val input: LayoutInput,
    val rows: List<LayoutRow>,
    val auxiliaryAreas: List<LayoutAuxiliaryArea>? = null,
    /** Nil keeps imported layouts compatible; their key structure supplies the default. */
    val candidateArea: LayoutCandidateArea? = null,
    val split: LayoutSplitConfiguration? = null,
    val note: String? = null,
    /** Seconds until the alternate picker opens. Release commits one selected alternate only. */
    val longPressDelay: Double? = null,
) {
    val effectiveLongPressDelay: Double get() = longPressDelay ?: DEFAULT_LONG_PRESS_DELAY

    val sendsEngineCode: Boolean
        get() {
            // The union of both arrangements: a key that only the split halves carry still sends codes.
            val keys =
                rows.flatMap { it.keys } +
                    (split?.halves ?: emptyList()).flatMap { it.keys } +
                    (auxiliaryAreas ?: emptyList()).flatMap { it.keys }
            return keys.any { key ->
                key.action.type == LayoutActionKind.CODE ||
                    (key.longPress ?: emptyList()).any { it.action.type == LayoutActionKind.CODE }
            }
        }

    val effectiveCandidateArea: LayoutCandidateArea
        get() =
            candidateArea
                ?: LayoutCandidateArea(
                    candidates = sendsEngineCode,
                    reading = CompactSpellingLayout.matches(this) && !PhoneKeypadStructure.matches(this),
                )

    /** Whether this layout can serve [inputMethodId] with the physical [layout]. */
    fun supportsComposition(
        inputMethodId: String,
        layout: LayoutInput.Builtin,
    ): Boolean =
        (input.schemaID == null || input.schemaID == inputMethodId) &&
            (input.builtinLayout == null || input.builtinLayout == layout)

    fun renamed(name: String): LayoutDefinition = copy(name = name)

    /** A detached copy with a new id; the caller supplies the id so this type stays platform-free. */
    fun copyWithId(
        newId: String,
        name: String? = null,
    ): LayoutDefinition = copy(id = newId, name = name ?: this.name)

    fun key(at: LayoutKeyPath): LayoutKey? =
        when (at) {
            is LayoutKeyPath.Row -> rows.getOrNull(at.row)?.keys?.getOrNull(at.key)
            is LayoutKeyPath.Auxiliary ->
                auxiliaryAreas?.getOrNull(at.area)?.keys?.getOrNull(at.key)
        }

    /** Draft mutation deliberately allows invalid intermediate values; saving validates the draft. */
    fun replacingKey(
        at: LayoutKeyPath,
        with: LayoutKey,
    ): LayoutDefinition {
        if (key(at) == null) return this
        return when (at) {
            is LayoutKeyPath.Row -> {
                val newRows = rows.toMutableList()
                val keys = newRows[at.row].keys.toMutableList()
                keys[at.key] = with
                newRows[at.row] = LayoutRow(keys)
                copy(rows = newRows)
            }
            is LayoutKeyPath.Auxiliary -> {
                val areas = auxiliaryAreas?.toMutableList() ?: return this
                val area = areas[at.area]
                val keys = area.keys.toMutableList()
                keys[at.key] = with
                areas[at.area] = area.copy(keys = keys)
                copy(auxiliaryAreas = areas)
            }
        }
    }

    fun withCandidateArea(area: LayoutCandidateArea): LayoutDefinition = copy(candidateArea = area)

    fun withSplit(split: LayoutSplitConfiguration?): LayoutDefinition = copy(split = split)

    @Throws(LayoutException::class)
    fun validate() {
        LayoutResourceBudget.validate(this)
        split?.validate(rows.map { it.keys.size }, allowsHalves = true)
        val delay = effectiveLongPressDelay
        if (delay.isNaN() || delay.isInfinite() || delay <= 0.0) {
            throw LayoutException.Invalid(LayoutInvalidCode.LONG_PRESS_DELAY_MUST_BE_POSITIVE_FINITE)
        }
        if (id.isBlank() || name.isBlank()) {
            throw LayoutException.Invalid(LayoutInvalidCode.ID_AND_NAME_REQUIRED)
        }
        if (input.schemaID != null && input.schemaID.isBlank()) {
            throw LayoutException.Invalid(LayoutInvalidCode.SCHEMA_ID_MUST_NOT_BE_EMPTY)
        }
        if (input.schemaID == null && input.builtinLayout == null) {
            throw LayoutException.Invalid(LayoutInvalidCode.INPUT_REQUIRES_SCHEMA_OR_BUILTIN)
        }
        if (rows.isEmpty() || rows.any { it.keys.isEmpty() }) {
            throw LayoutException.Invalid(LayoutInvalidCode.ROWS_MUST_CONTAIN_KEYS)
        }
        val area = effectiveCandidateArea
        if (area.reading && !area.candidates) {
            throw LayoutException.Invalid(LayoutInvalidCode.READING_ROW_REQUIRES_CANDIDATE_AREA)
        }
        if (area.reading && PhoneKeypadStructure.matches(this)) {
            throw LayoutException.Invalid(LayoutInvalidCode.PHONE_LAYOUT_USES_LEADING_READING_RAIL)
        }
        if (!area.candidates && sendsEngineCode) {
            throw LayoutException.Invalid(LayoutInvalidCode.CODE_KEYS_REQUIRE_CANDIDATE_AREA)
        }
        for (auxiliary in auxiliaryAreas ?: emptyList()) {
            // This is a dynamic engine-provided region, never static fake letter filters.
            if (auxiliary.role == LayoutAuxiliaryArea.Role.DISAMBIGUATION && auxiliary.keys.isNotEmpty()) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.DISAMBIGUATION_KEYS_MUST_BE_ENGINE_SUPPLIED,
                )
            }
        }
        val halfKeys = (split?.halves ?: emptyList()).flatMap { it.keys }
        for (key in rows.flatMap { it.keys } + halfKeys + (auxiliaryAreas ?: emptyList()).flatMap { it.keys }) {
            if (key.label.isEmpty() || !key.widthWeight.isFinite() || key.widthWeight <= 0.0) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH,
                    key.label,
                )
            }
            key.action.validate()
            for (option in key.longPress ?: emptyList()) {
                if (option.label.isEmpty()) {
                    throw LayoutException.Invalid(LayoutInvalidCode.LONG_PRESS_REQUIRES_LABEL)
                }
                option.action.validate()
            }
        }
    }

    /** Pretty, sorted-field JSON of this layout; null fields are omitted. */
    @Throws(LayoutException::class)
    fun encoded(): String {
        validate()
        val text = LayoutJson.format.encodeToString(this)
        if (text.encodeToByteArray().size > LayoutResourceBudget.DEFINITION_BYTES) {
            throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, "definitionBytes")
        }
        return text
    }

    companion object {
        const val DEFAULT_LONG_PRESS_DELAY: Double = 0.35
    }
}

/** Stable structural address shared by the layout editor and engine-free preview. */
sealed class LayoutKeyPath {
    data class Row(
        val row: Int,
        val key: Int,
    ) : LayoutKeyPath()

    data class Auxiliary(
        val area: Int,
        val key: Int,
    ) : LayoutKeyPath()
}

/** The single JSON configuration for layout data. Unknown fields are rejected, like iOS. */
object LayoutJson {
    val format: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
            encodeDefaults = false
            ignoreUnknownKeys = false
        }

    /** Compact form used for canonical identity encodings. */
    val compact: Json =
        Json {
            prettyPrint = false
            explicitNulls = false
            encodeDefaults = false
            ignoreUnknownKeys = false
        }

    @Throws(LayoutException::class)
    fun decode(text: String): LayoutDefinition = decode(text.encodeToByteArray())

    @Throws(LayoutException::class)
    fun decode(bytes: ByteArray): LayoutDefinition {
        if (bytes.size > LayoutResourceBudget.DEFINITION_BYTES) {
            throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, "definitionBytes")
        }
        val layout =
            try {
                format.decodeFromString(LayoutDefinition.serializer(), bytes.decodeToString())
            } catch (error: IllegalArgumentException) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.MALFORMED_JSON,
                    error.message ?: "malformed",
                )
            }
        layout.validate()
        return layout
    }
}

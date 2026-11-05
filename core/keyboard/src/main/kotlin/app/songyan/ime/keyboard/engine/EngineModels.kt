package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A candidate on the engine's current page.
 *
 * [index] is page-local (unique only within the current page); [selectKey]/[selectLabel] are the
 * engine's own per-slot labels (librime `menu/alternative_select_keys` and `select_labels`).
 * Null means the schema declares none - never fabricate 1..9.
 */
data class Candidate(
    val index: Int,
    val text: String,
    val comment: String = "",
    val selectKey: String? = null,
    val selectLabel: String? = null,
)

/** A UTF-16 selection in a text field (start + length), the shape hosts need for marked text. */
data class TextSelection(
    val start: Int = 0,
    val length: Int = 0,
)

/** The engine's mode observations (librime `RimeStatus`). Pure observations, never written back. */
data class EngineStatusFlags(
    val isAsciiMode: Boolean = false,
    val isFullShape: Boolean = false,
    val isAsciiPunct: Boolean = false,
) {
    companion object {
        val NONE = EngineStatusFlags()
    }
}

/**
 * The engine state handed to the front end after a key press or a selection.
 *
 * Byte offsets ([preeditSelStart], [preeditSelEnd], [preeditCaretByte]) index the UTF-8 encoding of
 * [preedit], exactly as librime reports them; [preeditSelection] is the same selection converted to
 * UTF-16 for host marked text.
 */
data class EngineSnapshot(
    val schemaID: String = "",
    val commitTextPreview: String = "",
    val isAsciiMode: Boolean = false,
    val isFullShape: Boolean = false,
    val isAsciiPunct: Boolean = false,
    val preeditSelection: TextSelection = TextSelection(),
    /** Text committed by this operation only. */
    val committed: String = "",
    /** Candidate texts of the current page while composing. */
    val candidates: List<Candidate> = emptyList(),
    /** Composition text as the user should see it. */
    val preedit: String = "",
    /** Raw code as typed (26-key `nihao`, 14-key `bugc`, 9-key `64426`). */
    val rawInput: String = "",
    val isComposing: Boolean = false,
    /** In-page index of the highlighted candidate; -1 = none. */
    val highlightedIndex: Int = -1,
    val pageIndex: Int = 0,
    /** Candidates per page; 0 = no paging. */
    val pageSize: Int = 0,
    val isLastPage: Boolean = true,
    /** UTF-8 byte range in [preedit] of the unconfirmed highlighted segment (librime sel_start/sel_end). */
    val preeditSelStart: Int = 0,
    val preeditSelEnd: Int = 0,
    /** UTF-8 byte offset of the composition caret (librime cursor_pos). */
    val preeditCaretByte: Int = 0,
    /**
     * The highlighted candidate's real spelling decoded by the engine from its own syllable codes.
     * Display metadata only: [rawInput]/[preedit] stay as the engine reported them.
     */
    val highlightedSpelling: String = "",
    /**
     * The highlighted candidate's one complete reading, proven against the highlighted preedit range,
     * for a compact layout whose schema yields no [highlightedDisplay]. Empty when unproven; display
     * only, never committed.
     */
    val highlightedReading: String = "",
    /** Display value resolved from the schema's declared `keyboard_adapter` capability. */
    val highlightedDisplay: String = "",
    val displayMode: CompositionDisplayMode = CompositionDisplayMode.NATIVE,
    /** Whether the current schema declares a `keyboard_adapter` block (authorized or not). */
    val hasDisplayCapability: Boolean = false,
    /** 9-key numeric schema: the engine preedit is a transport code and must not be shown as typed text. */
    val hidesNumericTransportCode: Boolean = false,
    /** Post-commit prediction: candidates with no composition. Never marked text. */
    val isPrediction: Boolean = false,
) {
    val canPageBackward: Boolean get() = pageSize > 0 && pageIndex > 0
    val canPageForward: Boolean get() = pageSize > 0 && !isLastPage

    fun statusFlags(): EngineStatusFlags =
        EngineStatusFlags(isAsciiMode = isAsciiMode, isFullShape = isFullShape, isAsciiPunct = isAsciiPunct)
}

/** Composition display mode declared by a schema's `keyboard_adapter` block. */
@Serializable
enum class CompositionDisplayMode {
    @SerialName("native")
    NATIVE,

    @SerialName("canonical")
    CANONICAL,

    @SerialName("source_code")
    SOURCE_CODE,

    /** Reserved; decoded as unsupported and falls back to native. */
    @SerialName("source_rendered")
    SOURCE_RENDERED,
}

/** A schema the running engine can activate, with its optional speller alphabet. */
@Serializable
data class KeyboardSchemaDescriptor(
    val id: String,
    val name: String,
    val alphabet: String = "",
) {
    /**
     * Presentation name for the UI: only a real, non-empty name that differs from the id is used;
     * otherwise known built-in ids get a friendly name or nil (callers fall back to generic copy).
     */
    val humanReadableName: String?
        get() {
            val trimmed = name.trim()
            return if (trimmed.isEmpty() || trimmed == id) null else trimmed
        }

    val displayName: String
        get() =
            humanReadableName
                ?: KNOWN_NAMES[id]
                ?: BuiltinTemplateSchemas.NAMES[id]
                ?: (if (name.isEmpty()) id else name)

    companion object {
        private val KNOWN_NAMES =
            mapOf(
                "rime_ice" to "雾凇拼音",
                "double_pinyin_flypy" to "小鹤双拼",
                "double_pinyin" to "自然码双拼",
                "double_pinyin_abc" to "智能 ABC 双拼",
                "double_pinyin_mspy" to "微软双拼",
                "double_pinyin_sogou" to "搜狗双拼",
                "double_pinyin_ziguang" to "紫光双拼",
                "double_pinyin_pyjj" to "拼音加加双拼",
                "double_pinyin_jiajia" to "拼音加加双拼",
                "luna_pinyin" to "朙月拼音",
                "luna_pinyin_simp" to "朙月拼音·简化字",
            )
    }
}

/** Engine implementation kind; the UI must label demo candidates as demo data. */
enum class EngineBackendKind {
    RIME,
    DEMO_SIMULATOR,
    ;

    val isRealRime: Boolean get() = this == RIME
}

/** Failures raised by an engine backend. */
sealed class EngineBackendError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class UnsupportedSchema(
        val schemaID: String,
    ) : EngineBackendError("unsupported schema: $schemaID")

    /**
     * The schema exists in the engine, but the current publication/install does not enable it.
     * Typed so the host can localize the copy and offer recovery; no user-facing text here.
     */
    class SchemaNotEnabled(
        val schemaID: String,
        val enabledSchemaIDs: List<String>,
    ) : EngineBackendError("input scheme is not enabled: " + schemaID)

    class NotReady : EngineBackendError("engine not ready")

    class SetupFailed(
        val detail: String,
    ) : EngineBackendError("engine setup failed: $detail")

    class OperationFailed(
        val detail: String,
    ) : EngineBackendError(detail)

    class EngineNotStarted : EngineBackendError("engine not started")
}

/** What a verified schema switch does; the UI words it from this, so no user text lives in the model. */
enum class EngineOptionKind {
    TRADITIONAL,
    SIMPLIFIED,
    EMOJI,
    FULL_SHAPE,
}

/**
 * A verified schema switch or native radio-group member. [resourceID] scopes the saved choice to the installed scheme
 * source, [schemaID] to the input method it belongs to.
 */
data class EngineOptionDescriptor(
    val resourceID: String,
    val schemaID: String,
    val name: String,
    val kind: EngineOptionKind,
    /** The schema's own reset value: what the switch is when the user never chose. */
    val defaultValue: Boolean = false,
    val choiceGroup: List<String> = emptyList(),
    val choiceLabel: String? = null,
    /** The OpenCC config (`s2t`, `s2twp`) a script switch declares; null for other switches. */
    val scriptConfig: String? = null,
) {
    val id: String get() = name
}

data class EngineOptionState(
    val descriptor: EngineOptionDescriptor,
    val value: Boolean,
) {
    val id: String get() = descriptor.id
}

/** Lock mode of a composition ambiguity rewrite. */
@Serializable
enum class AmbiguityLockMode(
    val wire: String,
) {
    @SerialName("none")
    NONE("none"),

    @SerialName("bounded_token_alias")
    TOKEN_ALIAS("bounded_token_alias"),

    @SerialName("direct_token")
    DIRECT_TOKEN("direct_token"),

    @SerialName("prefix_token")
    PREFIX_TOKEN("prefix_token"),
}

/**
 * Read-only report of the spellings the engine will accept for the tail segment of the current
 * composition. Nothing here is guessed from pinyin tables or candidate comments.
 */
data class AmbiguitySpellingsReport(
    /** The full raw input the tail segment belongs to (rejects stale taps). */
    val revisionInput: String,
    /** UTF-8 byte range of the tail segment in raw input. */
    val segment: IntRange,
    /** Spellings the engine will apply. */
    val options: List<String>,
    /** All reachable spellings; diagnostics only, never tappable. */
    val allSpellings: List<String>,
    val lockMode: AmbiguityLockMode,
    /** Whether the engine truncated the reachable set. */
    val isComplete: Boolean,
    val prefixOptions: List<String> = emptyList(),
    val optionSemantics: Map<String, String> = emptyMap(),
) {
    val isAmbiguous: Boolean get() = options.size >= 2
    val isLockable: Boolean get() = lockMode != AmbiguityLockMode.NONE && options.isNotEmpty()
}

/** Result of a composition rewrite; failure is never folded into success. */
enum class AmbiguityApplyOutcome {
    APPLIED,
    STALE,
    UNSUPPORTED,
    INVALID,
    FAILED,
    FAILED_ROLLBACK_UNVERIFIED,
    ;

    val isSuccess: Boolean get() = this == APPLIED
}

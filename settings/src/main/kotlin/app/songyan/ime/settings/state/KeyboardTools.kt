package app.songyan.ime.settings.state

import android.os.LocaleList
import app.songyan.ime.content.ContentErrorCode
import app.songyan.ime.content.ContentException
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.StoredTool
import app.songyan.ime.content.ToolDefinition
import app.songyan.ime.content.ToolDefinitionError
import app.songyan.ime.content.ToolDefinitionLimits
import app.songyan.ime.content.ToolOrigin
import app.songyan.ime.content.ToolStep
import app.songyan.ime.content.ToolStoreError
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import java.util.Locale

/**
 * Why a keyboard-tool action did not complete.
 *
 * The content model only reports typed reasons; this vocabulary is what the settings UI maps onto
 * localized sentences, so no user-facing wording lives in the model or in the view model.
 */
sealed interface ToolProblem {
    /** The picked file is a directory, a pipe, or cannot be read at all. */
    data object Unreadable : ToolProblem

    /** The bytes are not a songyantool document. */
    data object NotAToolFile : ToolProblem

    /** The file is larger than the format's byte budget. */
    data class TooLarge(
        val maximumKilobytes: Int,
    ) : ToolProblem

    /** The file was written by a newer build than this one. */
    data class UnsupportedVersion(
        val version: String,
    ) : ToolProblem

    /** A step names a type this build does not run. */
    data class UnsupportedStep(
        val step: Int,
        val type: String,
    ) : ToolProblem

    /** A conversion step names an OpenCC configuration this build does not ship. */
    data class UnavailableConfig(
        val step: Int,
        val config: String,
    ) : ToolProblem

    /** A find-and-replace step's pattern does not compile. */
    data class InvalidRegex(
        val step: Int,
    ) : ToolProblem

    /** A step's parameters are outside the format's shape or range. */
    data class InvalidStep(
        val step: Int,
    ) : ToolProblem

    /** A name is missing or too long. */
    data object InvalidName : ToolProblem

    /** The identifier is empty or contains characters the format does not allow. */
    data object InvalidIdentifier : ToolProblem

    /** A field is outside the document's shape but cannot be named more precisely. */
    data object InvalidShape : ToolProblem

    /** The document has no steps, so it would do nothing. */
    data object NoSteps : ToolProblem

    /** The document declares more steps than the format allows. */
    data class TooManySteps(
        val count: Int,
    ) : ToolProblem

    /** The toolbar already holds the maximum number of pinned tools. */
    data class PinLimit(
        val maximum: Int,
    ) : ToolProblem

    /** The store already holds the maximum number of user tools. */
    data class TooManyTools(
        val maximum: Int,
    ) : ToolProblem

    /** Built-in examples are read-only; the user can copy one instead. */
    data object BuiltinImmutable : ToolProblem

    /** The most recent deletion was already used up. */
    data object NothingToUndo : ToolProblem

    /** The file could not be read or written on this device. */
    data object StorageUnavailable : ToolProblem

    /** Anything the store refused without a more specific reason. */
    data object Unknown : ToolProblem
}

/** The one converter the built-in script-conversion example runs for the input language in use. */
fun simplificationConfigOf(state: SettingsAppState): OpenCCToolConfig =
    when (state.inUseScheme?.family) {
        // The example converts the text the user is about to handle; each interface language gets
        // the direction its own input produces. Generic schemes carry no declared script switch in
        // this state, so they keep the simplified default instead of guessing.
        InputSchemeFamily.JAPANESE -> OpenCCToolConfig.T2S
        InputSchemeFamily.ZHUYIN,
        InputSchemeFamily.CANGJIE_RADICALS,
        InputSchemeFamily.LATIN_PHONETIC,
        -> OpenCCToolConfig.S2TWP
        else -> OpenCCToolConfig.S2T
    }

/**
 * Language tags to try when picking a tool's display name, in the device's own order.
 *
 * A tool file names its languages with script tags (zh-Hans, zh-Hant), while system locales are
 * usually region tags (zh-TW), so both spellings are offered before the bare language.
 */
fun toolNameTags(locales: LocaleList): List<String> {
    val tags = LinkedHashSet<String>()
    for (index in 0 until locales.size()) {
        val locale = locales[index]
        tags += locale.toLanguageTag()
        val script = locale.script.ifEmpty { scriptOfRegion(locale.country) }
        if (script.isNotEmpty()) tags += locale.language + "-" + script
        tags += locale.language
    }
    return tags.toList()
}

/** The script a Chinese region writes in; other regions carry no useful script guess. */
private fun scriptOfRegion(country: String): String =
    when (country.uppercase(Locale.ROOT)) {
        "TW", "HK", "MO" -> "Hant"
        "CN", "SG" -> "Hans"
        else -> ""
    }

/** Maps a format rejection onto the vocabulary the settings UI can word. */
fun toolProblemOf(error: ToolDefinitionError): ToolProblem =
    when (val reason = error.reason) {
        ToolDefinitionError.Reason.NotAFile -> ToolProblem.Unreadable
        is ToolDefinitionError.Reason.FileTooLarge -> ToolProblem.TooLarge(reason.maximumKilobytes)
        ToolDefinitionError.Reason.Unreadable -> ToolProblem.NotAToolFile
        is ToolDefinitionError.Reason.UnsupportedFormatVersion -> ToolProblem.UnsupportedVersion(reason.version)
        is ToolDefinitionError.Reason.MissingField -> problemOfPath(reason.path)
        is ToolDefinitionError.Reason.InvalidField -> problemOfPath(reason.path)
        ToolDefinitionError.Reason.NoSteps -> ToolProblem.NoSteps
        is ToolDefinitionError.Reason.TooManySteps -> ToolProblem.TooManySteps(reason.count)
        is ToolDefinitionError.Reason.UnsupportedStepType -> ToolProblem.UnsupportedStep(reason.step, reason.type)
        is ToolDefinitionError.Reason.UnavailableOpenCCConfig ->
            ToolProblem.UnavailableConfig(reason.step, reason.config)
        is ToolDefinitionError.Reason.InvalidRegex -> ToolProblem.InvalidRegex(reason.step)
    }

/** Maps a storage refusal onto the same vocabulary. */
fun toolProblemOf(error: ToolStoreError): ToolProblem =
    when (val reason = error.reason) {
        is ToolStoreError.Reason.PinLimit -> ToolProblem.PinLimit(reason.maximum)
        is ToolStoreError.Reason.TooManyTools -> ToolProblem.TooManyTools(reason.maximum)
        is ToolStoreError.Reason.BuiltinIdentifier -> ToolProblem.BuiltinImmutable
        is ToolStoreError.Reason.BuiltinNotDeletable -> ToolProblem.BuiltinImmutable
        is ToolStoreError.Reason.NothingToUndo -> ToolProblem.NothingToUndo
        is ToolStoreError.Reason.DuplicateIdentifier -> ToolProblem.InvalidShape
        is ToolStoreError.Reason.UnknownTool -> ToolProblem.Unknown
        is ToolStoreError.Reason.MetadataRejected -> ToolProblem.StorageUnavailable
        is ToolStoreError.Reason.ToolFileUnreadable -> ToolProblem.Unreadable
        is ToolStoreError.Reason.ToolFileRejected -> toolProblemOf(reason.error)
    }

/** Maps a content-layer refusal (oversized or unreadable streams) onto the same vocabulary. */
fun toolProblemOf(error: ContentException): ToolProblem =
    when (error.code) {
        ContentErrorCode.OVERSIZE -> ToolProblem.TooLarge(ToolDefinitionLimits.MAXIMUM_FILE_BYTES / 1024)
        ContentErrorCode.NOT_A_REGULAR_FILE -> ToolProblem.Unreadable
        ContentErrorCode.STORAGE_UNAVAILABLE, ContentErrorCode.LOCK_FAILED -> ToolProblem.StorageUnavailable
        else -> ToolProblem.InvalidShape
    }

private fun problemOfPath(path: String): ToolProblem {
    val step =
        STEP_PATH
            .find(path)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    if (step != null) return ToolProblem.InvalidStep(step + 1)
    if (path == "$.name" || path.startsWith("$.name.")) return ToolProblem.InvalidName
    if (path == "$.id") return ToolProblem.InvalidIdentifier
    return ToolProblem.InvalidShape
}

private val STEP_PATH = Regex("^\\$\\.steps\\[(\\d+)\\]")

/** The step types the editor can add; each starts with parameters that only need filling in. */
enum class ToolStepKind {
    OPENCC,
    REPLACE,
    MAP,
    CASE,
    WIDTH,
    TEMPLATE,
    ;

    fun defaultStep(): ToolStep =
        when (this) {
            OPENCC -> ToolStep.OpenCC(OpenCCToolConfig.S2T)
            REPLACE -> ToolStep.Replace(pattern = "", replacement = "")
            MAP -> ToolStep.Map(emptyMap())
            CASE -> ToolStep.CaseTransform(ToolStep.CaseMode.UPPER)
            WIDTH -> ToolStep.Width(ToolStep.WidthMode.TO_HALF)
            TEMPLATE -> ToolStep.Template("「{text}」")
        }
}

/**
 * One tool being edited: the four authored names and the step list, independent of storage.
 *
 * Names always hold an entry per format language so the editor can show all of them; blank values
 * are dropped when the draft becomes a definition.
 */
data class ToolDraft(
    val id: String,
    val names: Map<String, String>,
    val steps: List<ToolStep>,
) {
    val canAddStep: Boolean
        get() = steps.size < ToolDefinitionLimits.MAXIMUM_STEPS

    fun withName(
        tag: String,
        value: String,
    ): ToolDraft = copy(names = names + (tag to value))

    fun withStep(
        index: Int,
        step: ToolStep,
    ): ToolDraft =
        if (index !in steps.indices) {
            this
        } else {
            copy(steps = steps.toMutableList().also { it[index] = step })
        }

    fun withoutStep(index: Int): ToolDraft =
        if (index !in steps.indices) this else copy(steps = steps.filterIndexed { position, _ -> position != index })

    fun withAddedStep(kind: ToolStepKind): ToolDraft =
        if (!canAddStep) this else copy(steps = steps + kind.defaultStep())

    /** The document to validate and save; blank names are left out, as the format allows. */
    fun toDefinition(): ToolDefinition =
        ToolDefinition(
            id = id,
            name = names.filterValues { it.isNotBlank() },
            steps = steps,
        )

    companion object {
        /** A draft of [definition] with every format language present, so the editor can show them. */
        fun of(definition: ToolDefinition): ToolDraft {
            val names = LinkedHashMap<String, String>()
            for (tag in ToolDefinition.NAME_LANGUAGE_TAGS) names[tag] = definition.name[tag].orEmpty()
            return ToolDraft(id = definition.id, names = names, steps = definition.steps)
        }
    }
}

/** Which tool the editor page is editing, with its draft and the outcome of the last save. */
data class ToolEditorState(
    val draft: ToolDraft,
    val saving: Boolean = false,
    val problem: ToolProblem? = null,
) {
    val canAddStep: Boolean
        get() = draft.canAddStep
}

/** The outcome of one action, shown once as a snackbar; the screen clears it after showing it. */
sealed interface KeyboardToolsMessage {
    /** A user tool was deleted; the definition lets the screen name it for the undo offer. */
    data class Deleted(
        val definition: ToolDefinition,
    ) : KeyboardToolsMessage

    data class Imported(
        val definition: ToolDefinition,
    ) : KeyboardToolsMessage

    data class Exported(
        val definition: ToolDefinition,
    ) : KeyboardToolsMessage

    data class Failed(
        val problem: ToolProblem,
    ) : KeyboardToolsMessage
}

/** Everything the keyboard-tools page renders. */
data class KeyboardToolsUiState(
    val loading: Boolean = true,
    val tools: List<StoredTool> = emptyList(),
    val message: KeyboardToolsMessage? = null,
    /** Non-null while the editor page is open. */
    val editor: ToolEditorState? = null,
    /**
     * The other apps that handle text, as of the last read; null while they are loading or when the
     * app provides no source at all (the section is then left out).
     */
    val processTextApps: List<ProcessTextApp>? = null,
    /** The components the user turned on; none is on by default. */
    val processTextEnabled: Set<String> = emptySet(),
    /** The apps that handle text could not be read, so the section says why. */
    val processTextUnavailable: Boolean = false,
) {
    val pinnedCount: Int
        get() = tools.count { it.metadata.pinned }

    val builtinExamples: List<ToolDefinition>
        get() = tools.filter { it.origin == ToolOrigin.BUILTIN }.map { it.definition }
}

/** The name to show for [tool] in the interface languages the device asks for. */
fun storedToolName(
    tool: StoredTool,
    tags: List<String>,
): String = tool.definition.displayName(tags)

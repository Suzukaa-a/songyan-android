package app.songyan.ime.ime.tools

import app.songyan.ime.content.BuiltinToolDefinitions
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.ToolOpenCCConverter
import app.songyan.ime.content.ToolRegexReplacementError
import app.songyan.ime.content.ToolRegexReplacementProvider
import app.songyan.ime.content.ToolRunner
import app.songyan.ime.content.ToolRunnerError
import app.songyan.ime.content.ToolStore
import app.songyan.ime.keyboard.engine.InputLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Where the text a tool works on came from. */
enum class KeyboardToolSourceKind {
    /** The text the user selected. */
    SELECTION,

    /** The paragraph the caret is in, from its start to the caret. */
    PARAGRAPH,

    /** What the clipboard holds; the user picked it explicitly. */
    CLIPBOARD,
}

/** One piece of text a tool may be handed, and where it came from. */
data class KeyboardToolText(
    val text: String,
    val kind: KeyboardToolSourceKind,
)

/** One tool the panel lists and the toolbar may pin. */
data class KeyboardTool(
    val id: String,
    /** The tool's own name in the interface language. */
    val label: String,
    val pinned: Boolean,
)

/**
 * Why a tool produced no text. The panel words every reason from resources; the values here are the
 * vocabulary it maps, never English copy.
 */
enum class KeyboardToolFailure {
    /** The definition the panel listed is gone (deleted in settings while the panel was open). */
    UNKNOWN_TOOL,

    /** The stored definition no longer passes the format rules. */
    INVALID_TOOL,

    /** A platform part the tool needs is missing: no OpenCC data, no bounded regular expressions. */
    UNAVAILABLE,

    /** A step exceeded its deadline; the tool was stopped instead of blocking the keyboard. */
    TIMED_OUT,

    /** The text is longer than a step accepts. */
    TOO_LONG,

    /** A step failed. */
    FAILED,

    /** The tool list itself could not be read. */
    STORE_UNAVAILABLE,
}

/** What running one tool produced. */
sealed interface KeyboardToolResult {
    /**
     * The transformed text. [inputTruncated] is true when only the leading part of the source was
     * processed: replacing the whole source with it would drop the untransformed tail, so the panel
     * only offers inserting or copying then.
     */
    data class Text(
        val text: String,
        val inputTruncated: Boolean = false,
    ) : KeyboardToolResult

    data class Failed(
        val failure: KeyboardToolFailure,
    ) : KeyboardToolResult
}

/** The enabled tools, or why the list could not be read. */
sealed interface KeyboardToolList {
    data class Read(
        val tools: List<KeyboardTool>,
    ) : KeyboardToolList

    data object Unavailable : KeyboardToolList
}

/** One other app the user turned on: an ACTION_PROCESS_TEXT handler the panel lists. */
data class KeyboardToolApp(
    /** The flattened activity component; the app opens it, the panel only names it. */
    val id: String,
    /** The app's own name, as the system shows it. */
    val label: String,
)

/** An answer from another app, waiting for the user to look at it and apply it. */
data class KeyboardToolAnswer(
    val text: String,
    /** The app that answered, as the request named it; empty when it went in unnamed. */
    val appLabel: String,
    /** True when the answer may still replace the text it came from; false offers insert or copy only. */
    val canReplace: Boolean,
)

/** What applying an answer from another app did. */
enum class KeyboardToolAnswerOutcome {
    /** Written over the text it came from. */
    REPLACED,

    /** Only copied: the editor that asked is gone or its text changed. */
    COPIED,

    /** Nothing was waiting any more. */
    DROPPED,
}

/**
 * The joins to the other apps that handle text. The app implements it, because starting the relay
 * activity and confirming an editor binding are things only the input method's owner can do.
 *
 * Every method runs on the main thread: the relay keeps the editor binding it was started from.
 */
interface KeyboardToolsProcessText {
    /** The enabled handlers, in label order; empty when the user turned none on. */
    suspend fun apps(): List<KeyboardToolApp>

    /**
     * Hands the panel's text to the app [appId]; false when this editor may not run a tool or the
     * app could not be opened.
     */
    fun open(
        appId: String,
        label: String,
    ): Boolean

    /** The answer waiting for the user; null while the other app is open or when it returned nothing. */
    fun pending(): KeyboardToolAnswer?

    /** Writes the waiting answer over its source, or copies it; only ever on an explicit action. */
    fun apply(): KeyboardToolAnswerOutcome

    /** Copies the waiting answer to the clipboard. */
    fun copy(): Boolean

    /** Drops the waiting answer without writing anything. */
    fun dismiss()
}

/**
 * What the keyboard's tools panel needs from the app: the stored tools and the runner.
 *
 * The interface lives here, in the keyboard UI, and the app implements it over the content store, so
 * the panel never sees files and the app never sees the panel.
 */
interface KeyboardToolsGateway {
    /** The enabled tools in toolbar order, named for the current interface language. */
    suspend fun catalog(inputLanguage: InputLanguage): KeyboardToolList

    /** Runs [toolId] on [text]; the input language decides the script-conversion direction. */
    suspend fun run(
        toolId: String,
        text: String,
        inputLanguage: InputLanguage,
    ): KeyboardToolResult

    /** The other apps that handle text; null when this build cannot hand text to them. */
    val processText: KeyboardToolsProcessText?
        get() = null
}

/**
 * The production gateway: the app's tool store plus the shared runner.
 *
 * The converter and the bounded regular-expression provider are injected because both need platform
 * machinery this module does not own; a definition that needs a missing provider fails closed inside
 * the runner instead of silently keeping the text as it is.
 *
 * One store per conversion, because the built-in script-conversion example embeds the conversion its
 * input language asks for. The stores share one directory, so the user's tools and their metadata are
 * the same whichever input language listed them.
 */
class KeyboardToolsRuntime(
    /** Where the user's tool files live; the path is resolved off the main thread on first use. */
    private val directory: () -> Path,
    private val openCC: ToolOpenCCConverter?,
    private val regex: ToolRegexReplacementProvider?,
    /** The interface languages a tool name is resolved in, most preferred first. */
    private val interfaceLanguageTags: () -> List<String>,
    /** The relay to the other apps that handle text; null leaves their entries out. */
    processTextGateway: KeyboardToolsProcessText? = null,
) : KeyboardToolsGateway {
    override val processText: KeyboardToolsProcessText? = processTextGateway

    private val stores = ConcurrentHashMap<OpenCCToolConfig, ToolStore>()
    private val runner = ToolRunner(openCCConverter = openCC, regexReplacement = regex)

    override suspend fun catalog(inputLanguage: InputLanguage): KeyboardToolList =
        try {
            val tags = interfaceLanguageTags()
            KeyboardToolList.Read(
                storeFor(inputLanguage)
                    .load()
                    .tools
                    .filter { it.metadata.enabled }
                    .map { KeyboardTool(it.definition.id, it.definition.displayName(tags), it.metadata.pinned) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            KeyboardToolList.Unavailable
        }

    override suspend fun run(
        toolId: String,
        text: String,
        inputLanguage: InputLanguage,
    ): KeyboardToolResult {
        val definition =
            try {
                storeFor(inputLanguage)
                    .load()
                    .tools
                    .firstOrNull { it.definition.id == toolId }
                    ?.definition
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return KeyboardToolResult.Failed(KeyboardToolFailure.STORE_UNAVAILABLE)
            }
        if (definition == null) return KeyboardToolResult.Failed(KeyboardToolFailure.UNKNOWN_TOOL)
        return try {
            val result = runner.run(definition, text)
            KeyboardToolResult.Text(result.text, result.inputWasTruncated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolRunnerError) {
            KeyboardToolResult.Failed(failureOf(error))
        } catch (_: Exception) {
            KeyboardToolResult.Failed(KeyboardToolFailure.FAILED)
        }
    }

    /**
     * The store for one conversion. Creating it resolves the directory (and may create it), which is
     * disk work, so it never runs on the caller's thread; the cache is concurrent because two panel
     * loads may resolve the same language at once.
     */
    private suspend fun storeFor(inputLanguage: InputLanguage): ToolStore =
        withContext(Dispatchers.IO) {
            val config = configFor(inputLanguage)
            stores[config]
                ?: synchronized(stores) {
                    stores[config] ?: ToolStore(directory(), BuiltinToolDefinitions.all(config)).also {
                        stores[config] = it
                    }
                }
        }

    private fun failureOf(error: ToolRunnerError): KeyboardToolFailure =
        when (error) {
            is ToolRunnerError.InvalidDefinition -> KeyboardToolFailure.INVALID_TOOL
            is ToolRunnerError.ConverterUnavailable, is ToolRunnerError.RegexUnavailable ->
                KeyboardToolFailure.UNAVAILABLE
            is ToolRunnerError.RegexFailed ->
                if (error.reason is ToolRegexReplacementError.TimedOut) {
                    KeyboardToolFailure.TIMED_OUT
                } else {
                    KeyboardToolFailure.FAILED
                }
            is ToolRunnerError.OutputTooLong -> KeyboardToolFailure.TOO_LONG
            is ToolRunnerError.ConversionFailed, is ToolRunnerError.UnexpectedFailure -> KeyboardToolFailure.FAILED
        }

    /**
     * The conversion the script-conversion example runs for a scheme language.
     *
     * A tool converts text the user already typed in the direction their input language writes, so
     * simplified input converts traditional text back and traditional input writes Taiwan's forms
     * (the state the built-in scheme starts in). A Japanese scheme quotes Chinese as traditional
     * text, which is the remaining direction. A scheme's own four-state script switch is not read
     * here: the panel's source text is not part of the composition that switch applies to.
     */
    private fun configFor(language: InputLanguage): OpenCCToolConfig =
        when (language) {
            InputLanguage.ZH_HANS -> OpenCCToolConfig.T2S
            InputLanguage.ZH_HANT -> OpenCCToolConfig.S2TWP
            InputLanguage.JA -> OpenCCToolConfig.S2T
        }
}

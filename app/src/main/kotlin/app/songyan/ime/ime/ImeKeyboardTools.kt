package app.songyan.ime.ime

import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import app.songyan.ime.content.ToolOpenCCConverter
import app.songyan.ime.content.ToolRegexReplacementError
import app.songyan.ime.content.ToolRegexReplacementProvider
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import app.songyan.ime.ime.tools.EditorConnectionKeyboardTools
import app.songyan.ime.ime.tools.KeyboardToolsEditor
import app.songyan.ime.ime.tools.KeyboardToolsGateway
import app.songyan.ime.ime.tools.KeyboardToolsRuntime
import app.songyan.ime.rime.OpenCCConversion
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.tools.ProcessTextToolApps
import app.songyan.ime.tools.ProcessTextToolGateway
import app.songyan.ime.tools.ToolAppKind
import app.songyan.ime.tools.regex.RegexField
import app.songyan.ime.tools.regex.RegexLimits
import app.songyan.ime.tools.regex.RegexRun
import app.songyan.ime.tools.regex.ToolRegex
import java.io.File
import java.nio.file.Path

/**
 * Wires the keyboard tools into the input method service.
 *
 * Every part is the production one: the tools are the user's files under `filesDir/tools`, script
 * conversion runs on the app's own OpenCC instance over the published generation's data, and regular
 * expressions run in the app's private `:tools` process, whose death is what enforces their deadline.
 * None of it touches librime's single-thread scheduler, so a tool never delays typing.
 */
class ImeKeyboardTools(
    context: Context,
    private val storage: AppStorage,
) {
    private val appContext = context.applicationContext
    private val publisher = GenerationPublisher(storage)

    /**
     * Conversions are created on first use and kept; a new generation's data path rebuilds them. The
     * data lives beside the engine's shared data because that is where the deployment puts it.
     */
    private val openCC = OpenCCConversion { openccDirectory() }

    /**
     * The other apps the user turned on in settings: the same enabled list the settings page writes,
     * named the way the system names those apps.
     */
    private val processTextApps =
        ProcessTextToolApps(appContext, storage.toolsDir)
    private val speechApps =
        ProcessTextToolApps(appContext, storage.toolsDir, ToolAppKind.SPEECH)

    val gateway: KeyboardToolsGateway =
        KeyboardToolsRuntime(
            directory = { toolsDirectory() },
            openCC =
                ToolOpenCCConverter { text, config ->
                    openCC.convert(text, config.wireName)
                },
            regex = boundedRegex(),
            interfaceLanguageTags = { interfaceLanguageTags() },
            processTextGateway = ProcessTextToolGateway(appContext, processTextApps, speechApps),
        )

    /** The tools' editor side over the input method's own connection, policy and clipboard. */
    fun editor(
        editor: EditorConnection,
        info: () -> EditorInfo?,
        policy: () -> EditorPolicy,
    ): KeyboardToolsEditor =
        EditorConnectionKeyboardTools(
            editor = editor,
            info = info,
            policy = policy,
            clipboard = appContext.getSystemService(ClipboardManager::class.java),
        )

    private fun toolsDirectory(): Path = storage.toolsDir.toPath()

    /** The bundled OpenCC data of the active generation; null while nothing is published. */
    private fun openccDirectory(): File? {
        val active = publisher.readActive() ?: return null
        return File(File(storage.generationDir(active.generation), "resources"), "opencc")
    }

    /**
     * The interface languages a tool name is resolved in, most preferred first.
     *
     * A tool file names itself by language (`zh-Hans`, `zh-Hant`, `ja`, `en`), while the system
     * reports a full locale such as `en-US` or `zh-TW`. Both the full tag and the bare language are
     * offered, and a Chinese locale also names the script it reads, so a tool authored only in
     * `zh-Hant` is not shown with its Simplified name on a Taiwan phone.
     */
    private fun interfaceLanguageTags(): List<String> {
        val locales = appContext.resources.configuration.locales
        val tags = LinkedHashSet<String>()
        for (index in 0 until locales.size()) {
            val locale = locales.get(index)
            tags += locale.toLanguageTag()
            tags += locale.language
            if (locale.language == "zh") {
                val traditional =
                    locale.script == "Hant" ||
                        locale.country in setOf("TW", "HK", "MO")
                tags += if (traditional) "zh-Hant" else "zh-Hans"
            }
        }
        return tags.toList()
    }

    /**
     * [ToolRegexReplacementProvider] over the bounded worker: the deadline is enforced by killing a
     * separate process, because a Kotlin matcher backtracks inside native code and cannot be stopped.
     */
    private fun boundedRegex(): ToolRegexReplacementProvider =
        ToolRegexReplacementProvider { input, pattern, replacement, limits ->
            val run =
                ToolRegex.run(
                    context = appContext,
                    pattern = pattern,
                    input = input,
                    replacement = replacement,
                    budgetMs = limits.budgetMs,
                    limits =
                        RegexLimits(
                            pattern = limits.maximumPatternUtf16Length,
                            input = limits.maximumInputUtf16Length,
                            replacement = limits.maximumInputUtf16Length,
                        ),
                )
            when (run) {
                is RegexRun.Done -> run.text
                RegexRun.NoMatch -> input
                RegexRun.PatternInvalid -> throw ToolRegexReplacementError.InvalidPattern
                is RegexRun.TimedOut -> throw ToolRegexReplacementError.TimedOut(limits.budgetMs)
                is RegexRun.TooLong ->
                    when (run.field) {
                        RegexField.PATTERN ->
                            throw ToolRegexReplacementError.PatternTooLong(limits.maximumPatternUtf16Length)
                        else -> throw ToolRegexReplacementError.InputTooLong(limits.maximumInputUtf16Length)
                    }
                RegexRun.WorkerFailed -> throw ToolRegexReplacementError.WorkerFailed
            }
        }
}

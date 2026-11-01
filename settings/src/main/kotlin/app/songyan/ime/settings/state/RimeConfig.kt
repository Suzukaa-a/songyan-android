package app.songyan.ime.settings.state

import android.content.Intent

/** A file of the user's that replaces a file of the input scheme in use; [updated] when the scheme has a newer version of its own. */
data class RimeOverrideFile(
    val path: String,
    val updated: Boolean = false,
)

/** Something to edit: an input method of the scheme in use ([name] is its display name), or the global settings when [name] is null. */
data class RimeEditTarget(
    val key: String,
    val name: String?,
) {
    companion object {
        /** The key of the settings every input method shares. */
        const val GLOBAL_KEY = "default"

        val GLOBAL = RimeEditTarget(GLOBAL_KEY, null)
    }
}

/** Where the user's Rime configuration stands; messages are already localized by the app module. */
data class RimeConfigInfo(
    /** Files in the configuration folder. */
    val fileCount: Int,
    /** The folder differs from what the keyboard uses: there are changes to apply. */
    val pending: Boolean,
    /** Why the last attempt to apply the changes failed, while it still stands. */
    val failure: String? = null,
    /** The scheme in use was built without the configuration, and why. */
    val notice: String? = null,
    /** The user's files that replace a file of the scheme in use. */
    val overrides: List<RimeOverrideFile> = emptyList(),
    /** Of [overrides], the paths the user has not been told about yet. */
    val unacknowledged: List<String> = emptyList(),
    /** What "modify an input method" offers, the scheme's input methods first. */
    val editTargets: List<RimeEditTarget> = emptyList(),
)

/** How applying the changes ended; [Failure.message] is already localized. */
sealed interface RimeConfigApplyResult {
    data object Applied : RimeConfigApplyResult

    /**
     * Scripts ([scripts], as shown to the user) that are new or changed; nothing was applied, and
     * [RimeConfigGateway.apply] with `confirmScripts` goes ahead. [firstTime]: the user has not been
     * told what a script can do yet.
     */
    data class NeedsConfirmation(
        val scripts: List<String>,
        val firstTime: Boolean = false,
    ) : RimeConfigApplyResult

    data class Failure(
        val message: String?,
    ) : RimeConfigApplyResult
}

/** A file of the user's opened in the in-app editor: its [text], or why it cannot be. */
sealed interface RimeConfigFileResult {
    /** [title] is the input method's name; null for the settings every input method shares. */
    data class Loaded(
        val title: String?,
        val fileName: String,
        val text: String,
    ) : RimeConfigFileResult

    data class Failure(
        val message: String?,
    ) : RimeConfigFileResult
}

/** How saving the editor's text ended. */
sealed interface RimeConfigSaveResult {
    data object Saved : RimeConfigSaveResult

    /** The text is not valid; [line] is the 1-based line the reader stopped at, when it says. Nothing was written. */
    data class Invalid(
        val line: Int?,
    ) : RimeConfigSaveResult

    /** Refused or not written; [message] is already localized, null for generic copy. */
    data class Failure(
        val message: String?,
    ) : RimeConfigSaveResult
}

/** The file to open in another app is ready ([intents] open it, the most specific first), or why it is not. */
sealed interface RimeConfigEditResult {
    data class Ready(
        val intents: List<Intent>,
    ) : RimeConfigEditResult

    data class Failure(
        val message: String?,
    ) : RimeConfigEditResult
}

/**
 * The user's own Rime configuration (`*.custom.yaml` and the like), edited in the system Files app
 * and applied from the settings screen. The app module implements it.
 */
interface RimeConfigGateway {
    val available: Boolean

    /** Intents that open the configuration folder in the Files app, the most specific first. */
    fun openFolderIntents(): List<Intent>

    suspend fun info(): RimeConfigInfo

    /**
     * Checks the edited files and rebuilds the scheme in use with them; returns once that ended.
     * Scripts that are new or changed need the user's yes first: without [confirmScripts] the result
     * is [RimeConfigApplyResult.NeedsConfirmation] and nothing happens; with it they count as agreed to.
     */
    suspend fun apply(confirmScripts: Boolean = false): RimeConfigApplyResult

    /** Deletes the user's version of [path], so the scheme's own file is used again after the next apply. */
    suspend fun restore(path: String)

    /** Records that the user has been told about these [paths]. */
    suspend fun acknowledge(paths: List<String>)

    /**
     * The user's file for the input method [key] ([RimeEditTarget.GLOBAL_KEY] for all of them),
     * made from the scheme's own or a template when missing. With [grammarExample] the text ends
     * with commented-out grammar model parameters unless it has some.
     */
    suspend fun loadFile(
        key: String,
        grammarExample: Boolean = false,
    ): RimeConfigFileResult

    /** Saves [text] as that file if it is valid; the keyboard still waits for the next apply. */
    suspend fun saveFile(
        key: String,
        text: String,
    ): RimeConfigSaveResult

    /** How to open that file in another app, making the file first when needed. */
    suspend fun externalEditIntents(key: String): RimeConfigEditResult
}

/** Fallback used before the app module supplies a real gateway. */
class UnavailableRimeConfigGateway : RimeConfigGateway {
    override val available = false

    override fun openFolderIntents(): List<Intent> = emptyList()

    override suspend fun info() = RimeConfigInfo(fileCount = 0, pending = false)

    override suspend fun apply(confirmScripts: Boolean): RimeConfigApplyResult = RimeConfigApplyResult.Failure(null)

    override suspend fun restore(path: String) = Unit

    override suspend fun acknowledge(paths: List<String>) = Unit

    override suspend fun loadFile(
        key: String,
        grammarExample: Boolean,
    ): RimeConfigFileResult = RimeConfigFileResult.Failure(null)

    override suspend fun saveFile(
        key: String,
        text: String,
    ): RimeConfigSaveResult = RimeConfigSaveResult.Failure(null)

    override suspend fun externalEditIntents(key: String): RimeConfigEditResult = RimeConfigEditResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object RimeConfigHolder {
    private val fallback = UnavailableRimeConfigGateway()

    @Volatile
    private var installed: RimeConfigGateway? = null

    val current: RimeConfigGateway
        get() = installed ?: fallback

    fun install(gateway: RimeConfigGateway) {
        installed = gateway
    }
}

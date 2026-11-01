package app.songyan.ime.settings.state

import app.songyan.ime.candidate.CandidateBarDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One selectable keyboard appearance. */
data class AppearanceOption(
    val id: String,
    /** The name a user-authored appearance carries; null for built-ins, whose names come from resources. */
    val name: String? = null,
    /** Built-ins are read-only: they can be exported but not deleted. */
    val builtIn: Boolean = true,
)

/** Everything the appearance screen renders. */
data class AppearanceSettingsState(
    val appearances: List<AppearanceOption> = emptyList(),
    /** The appearance the keyboard paints with right now. */
    val inUseId: String? = null,
    /** Whether the keyboard follows the wallpaper palette. */
    val dynamicColor: Boolean = false,
    /** Whether this device can paint the wallpaper palette (Android 12+ with a readable palette). */
    val dynamicColorAvailable: Boolean = false,
    /** The candidate area of the appearance in use; the candidate bar screen edits a copy of it. */
    val candidateBar: CandidateBarDefinition = CandidateBarDefinition.systemDefault,
    /** False until the app module installs its source; the screen then says so instead of pretending. */
    val available: Boolean = false,
) {
    val inUse: AppearanceOption? get() = inUseId?.let { id -> appearances.firstOrNull { it.id == id } }

    /** The wallpaper entry of the colour list is the chosen one while the keyboard follows the wallpaper. */
    val wallpaperSelected: Boolean get() = dynamicColorAvailable && dynamicColor

    /** Whether [id] is the chosen colour entry; exactly one entry of the list is chosen at a time. */
    fun isColourSelected(id: String): Boolean = !wallpaperSelected && inUseId == id
}

/** Why an appearance action did not take effect; the screen maps it onto copy. */
enum class AppearanceFailure {
    APPLY_FAILED,
    EXPORT_FAILED,
    DELETE_FAILED,
}

/** What an appearance file import did; the screen maps it onto copy. */
sealed interface AppearanceImportOutcome {
    /** The file was stored as the appearance [name] and is now in the list. */
    data class Imported(
        val name: String,
    ) : AppearanceImportOutcome

    /** The file is not a usable appearance, candidate area or skin; [path] points at the first bad field. */
    data class Rejected(
        val reason: Reason,
        val path: String?,
    ) : AppearanceImportOutcome

    enum class Reason { UNREADABLE, TOO_LARGE, NOT_AN_APPEARANCE, INVALID_VALUE, UNSUPPORTED_VERSION, STORAGE }
}

/**
 * Bridge to the app module's appearance repository: settings reads [state] and writes through
 * [select] and [setDynamicColor]. The repository owns persistence and the keyboard hand-off, so
 * settings never touches appearance files.
 */
interface AppearanceSettingsSource {
    val state: StateFlow<AppearanceSettingsState>

    /** Reloads the stored appearance list and current selection. */
    fun refresh()

    /** Applies a stored appearance; false when it could not be applied. */
    suspend fun select(id: String): Boolean

    /** Turns the wallpaper palette on or off; false when it could not be stored. */
    suspend fun setDynamicColor(enabled: Boolean): Boolean

    /**
     * Stores [bar] as the candidate area of the appearance in use and applies it. A built-in
     * appearance is read-only, so its edit lands in a user copy that becomes the one in use.
     */
    suspend fun updateCandidateBar(bar: CandidateBarDefinition): Boolean = false

    /** Reads the picked file (an appearance, a skin or a candidate area) and adds it to the list. */
    suspend fun importFrom(uri: android.net.Uri): AppearanceImportOutcome =
        AppearanceImportOutcome.Rejected(AppearanceImportOutcome.Reason.STORAGE, null)

    /** Writes the appearance [id] to the document the user chose; false when it could not be written. */
    suspend fun exportTo(
        id: String,
        uri: android.net.Uri,
    ): Boolean = false

    /** Removes a user appearance; false for a built-in or when it could not be removed. */
    suspend fun delete(id: String): Boolean = false

    /** The file name the export suggests for [id]. */
    fun exportFileName(id: String): String = id + ".songyanappearance.json"
}

/** Fallback before the app module supplies the real source: nothing is selectable and it says so. */
class InMemoryAppearanceSettingsSource : AppearanceSettingsSource {
    private val mutable = MutableStateFlow(AppearanceSettingsState())
    override val state: StateFlow<AppearanceSettingsState> = mutable.asStateFlow()

    override fun refresh() = Unit

    override suspend fun select(id: String): Boolean = false

    override suspend fun setDynamicColor(enabled: Boolean): Boolean = false
}

/** Process-wide holder for the app-provided source, mirroring [SettingsAppStateHolder]. */
object AppearanceSettingsHolder {
    private val fallback = InMemoryAppearanceSettingsSource()

    @Volatile
    private var installed: AppearanceSettingsSource? = null

    val current: AppearanceSettingsSource
        get() = installed ?: fallback

    /** Installs the app-provided source. Replacing an installed source is allowed for tests. */
    fun install(source: AppearanceSettingsSource) {
        installed = source
    }
}

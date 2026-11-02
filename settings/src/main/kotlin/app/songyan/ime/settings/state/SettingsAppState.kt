package app.songyan.ime.settings.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An installed input scheme. Display names come from the app, never hard-coded here. */
data class InstalledScheme(
    val id: String,
    val name: String,
    val supportedLayouts: Set<String> = setOf("qwerty26"),
)

/** One keyboard the user added: an input method plus a keyboard layout, both display names. */
data class AppKeyboardInfo(
    val id: String,
    val inputMethod: String?,
    val layout: String?,
)

/** Everything the settings UI shows about the app. Populated by the app module. */
data class SettingsAppState(
    val engine: EngineStatus = EngineStatus.Unknown,
    val sourcesLoaded: Boolean = false,
    val schemes: List<InstalledScheme> = emptyList(),
    val inUseSchemeId: String? = null,
    val keyboards: List<AppKeyboardInfo> = emptyList(),
    val currentKeyboardId: String? = null,
    /** The user-imported dictionary that extends the built-in schemes, when one exists. */
    val importedDictionary: ImportedDictionaryInfo? = null,
    /** Dictionaries stacked onto imported scheme packages, one per package at most. */
    val stackedDictionaries: List<ImportedDictionaryInfo> = emptyList(),
    /** Every stored input scheme, the bundled one first. */
    val sources: List<StoredSourceInfo> = emptyList(),
) {
    val inUseScheme: InstalledScheme?
        get() = inUseSchemeId?.let { id -> schemes.firstOrNull { it.id == id } }

    val currentKeyboard: AppKeyboardInfo?
        get() = currentKeyboardId?.let { id -> keyboards.firstOrNull { it.id == id } }
}

/**
 * Bridge to app-level state. The app module provides an implementation backed by its
 * deployment/engine state; settings only reads it.
 */
interface SettingsAppStateSource {
    val state: StateFlow<SettingsAppState>

    /** Requests a re-read of app state (called on resume). */
    fun refresh()
}

/** Fallback used before the app module supplies a real source. */
class InMemorySettingsAppStateSource(
    initial: SettingsAppState = SettingsAppState(),
) : SettingsAppStateSource {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SettingsAppState> = mutable.asStateFlow()

    override fun refresh() = Unit

    fun update(transform: (SettingsAppState) -> SettingsAppState) {
        mutable.value = transform(mutable.value)
    }
}

/**
 * Process-wide holder for the app-level state source. The app module installs its implementation
 * once during Application creation; without one the UI shows honest "not available" state.
 */
object SettingsAppStateHolder {
    private val fallback = InMemorySettingsAppStateSource()

    @Volatile
    private var installed: SettingsAppStateSource? = null

    val current: SettingsAppStateSource
        get() = installed ?: fallback

    /** Installs the app-provided source. Replacing an installed source is allowed for tests. */
    fun install(source: SettingsAppStateSource) {
        installed = source
    }
}

package app.songyan.ime.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonSettingsGateway
import app.songyan.ime.settings.state.AddonSettingsHolder
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One voice source the page can pick: an audio script or an extension that recognizes speech itself. */
data class VoiceScriptOption(
    val id: String,
    val name: String,
    val isAddon: Boolean = false,
)

/** Everything the 语音输入 page renders. */
data class VoiceInputUiState(
    val loading: Boolean = true,
    /** The microphone button is offered in the keyboard toolbar. */
    val enabled: Boolean = false,
    /** The script the keyboard runs; null until the user picks one. */
    val selectedScriptId: String? = null,
    /** Stored scripts that declare `@input audio` and can run on this host. */
    val scripts: List<VoiceScriptOption> = emptyList(),
)

/**
 * State of the 设置 → 语音输入 page: the keyboard's own voice input switch and the audio script it
 * runs. The script list is the same catalog the 脚本 page manages ([ScriptRegistryHolder]); only
 * entries that declare audio input and that this host can run are offered
 * ([app.songyan.ime.script.ScriptEntry.availableOnThisHost]).
 *
 * The choice lives in the shared preferences, so the keyboard reads it from the same DataStore.
 */
class VoiceInputViewModel(
    private val registry: ScriptRegistry = ScriptRegistryHolder.current,
    private val preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    /** Installed extensions; one that supports voice is offered next to the audio scripts. */
    private val addons: AddonSettingsGateway = AddonSettingsHolder.current,
) : ViewModel() {
    private val mutable = MutableStateFlow(VoiceInputUiState())
    val uiState: StateFlow<VoiceInputUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.voiceInputEnabled, preferences.voiceInputScriptId) { enabled, id ->
                enabled to id
            }.collect { (enabled, id) ->
                mutable.update { it.copy(enabled = enabled, selectedScriptId = id) }
            }
        }
        refresh()
    }

    /** Re-reads the catalog: the 脚本 page may have changed it while this page was away. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { registry.reload() }
            val scripts =
                registry.entries
                    .filter { it.enabled && !it.confirmationPending && it.needsAudio && it.availableOnThisHost }
                    .sortedBy { it.order }
                    .map { VoiceScriptOption(it.id, it.name.ifBlank { it.id }) }
            // An enabled extension that offers voice records and recognizes in its own process; it
            // is offered in the same list, and its stored id carries the "addon:" prefix.
            val addonOptions =
                runCatching {
                    addons
                        .list()
                        .filter {
                            it.enabled &&
                                it.compatibility == AddonCompatibility.COMPATIBLE &&
                                it.supportsVoice
                        }.map {
                            VoiceScriptOption(
                                id = ADDON_VOICE_PREFIX + it.packageName,
                                name = it.label.ifBlank { it.packageName },
                                isAddon = true,
                            )
                        }.sortedBy { it.name.lowercase() }
                }.getOrDefault(emptyList())
            mutable.update { it.copy(loading = false, scripts = scripts + addonOptions) }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { preferences.setVoiceInputEnabled(value) }
    }

    fun selectScript(id: String) {
        viewModelScope.launch { preferences.setVoiceInputScriptId(id) }
    }

    private companion object {
        const val ADDON_VOICE_PREFIX = "addon:"
    }

    class Factory(
        private val registry: ScriptRegistry = ScriptRegistryHolder.current,
        private val preferences: SettingsPreferences = SettingsPreferencesHolder.current,
        private val addons: AddonSettingsGateway = AddonSettingsHolder.current,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return VoiceInputViewModel(registry, preferences, addons) as T
        }
    }
}

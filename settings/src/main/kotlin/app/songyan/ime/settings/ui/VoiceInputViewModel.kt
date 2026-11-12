package app.songyan.ime.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptSourceOrigin
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

/** Where a voice source came from, shown under its name; addons are installed APKs, not files. */
sealed interface VoiceSourceOrigin {
    data object Pasted : VoiceSourceOrigin

    data class File(
        val displayName: String,
    ) : VoiceSourceOrigin

    data object Link : VoiceSourceOrigin

    data object Edited : VoiceSourceOrigin

    /** An installed extension that records and recognizes speech in its own process. */
    data object Addon : VoiceSourceOrigin
}

/** One voice source the page can pick: an audio script or an extension that recognizes speech itself. */
data class VoiceScriptOption(
    val id: String,
    val name: String,
    val isAddon: Boolean = false,
    /** Where it came from; the list shows it under the name. */
    val origin: VoiceSourceOrigin = VoiceSourceOrigin.Pasted,
    /** Stored scripts are only listed when enabled; the row still states it. */
    val enabled: Boolean = true,
)

/** Everything the 语音输入 page renders. */
data class VoiceInputUiState(
    val loading: Boolean = true,
    /** The catalog could not be read; the page offers a retry instead of an empty list. */
    val failed: Boolean = false,
    /** The microphone button is offered in the keyboard toolbar. */
    val enabled: Boolean = false,
    /** The script the keyboard runs; null until the user picks one. */
    val selectedScriptId: String? = null,
    /** Stored scripts that declare `@input audio` and can run on this host, then voice addons. */
    val scripts: List<VoiceScriptOption> = emptyList(),
)

/**
 * State of the 设置 → 语音输入 page: the keyboard's own voice input switch and the audio script it
 * runs. The script list is the same catalog the 脚本 page manages ([ScriptRegistryHolder]); only
 * entries that declare audio input and that this host can run are offered
 * ([app.songyan.ime.script.ScriptEntry.availableOnThisHost]), followed by installed extensions that
 * support voice.
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
            mutable.update { it.copy(loading = it.scripts.isEmpty(), failed = false) }
            val reloaded = runCatching { registry.reload() }
            if (reloaded.isFailure) {
                mutable.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            val scripts =
                registry.entries
                    .filter { it.enabled && !it.confirmationPending && it.needsAudio && it.availableOnThisHost }
                    .sortedBy { it.order }
                    .map {
                        VoiceScriptOption(
                            id = it.id,
                            name = it.name.ifBlank { it.id },
                            origin = it.sourceOrigin.toVoiceOrigin(),
                            enabled = it.enabled,
                        )
                    }
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
                                origin = VoiceSourceOrigin.Addon,
                                enabled = it.enabled,
                            )
                        }.sortedBy { it.name.lowercase() }
                }.getOrDefault(emptyList())
            mutable.update { it.copy(loading = false, failed = false, scripts = scripts + addonOptions) }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { preferences.setVoiceInputEnabled(value) }
    }

    fun selectScript(id: String) {
        viewModelScope.launch { preferences.setVoiceInputScriptId(id) }
    }

    private fun ScriptSourceOrigin.toVoiceOrigin(): VoiceSourceOrigin =
        when (this) {
            ScriptSourceOrigin.Pasted -> VoiceSourceOrigin.Pasted
            is ScriptSourceOrigin.File -> VoiceSourceOrigin.File(displayName)
            is ScriptSourceOrigin.Link -> VoiceSourceOrigin.Link
            ScriptSourceOrigin.Edited -> VoiceSourceOrigin.Edited
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

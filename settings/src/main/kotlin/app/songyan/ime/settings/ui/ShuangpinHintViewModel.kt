package app.songyan.ime.settings.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.songyan.ime.layout.ShuangpinHintException
import app.songyan.ime.layout.ShuangpinHintPreset
import app.songyan.ime.layout.ShuangpinHintPresets
import app.songyan.ime.layout.ShuangpinHintProblem
import app.songyan.ime.layout.ShuangpinHintSnapshot
import app.songyan.ime.settings.state.LinkDownloadGateway
import app.songyan.ime.settings.state.LinkDownloadHolder
import app.songyan.ime.settings.state.LinkDownloadResult
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the 双拼提示 screen draws. */
data class ShuangpinHintUiState(
    val loaded: Boolean = false,
    val snapshot: ShuangpinHintSnapshot = ShuangpinHintSnapshot(),
    /** The stored settings no longer read; the keyboard shows no hints until the user resets them. */
    val damaged: Boolean = false,
    /** A preset is being downloaded from a link. */
    val downloading: Boolean = false,
)

/** A one-off result the screen shows once and then consumes. */
sealed interface ShuangpinHintMessage {
    data class Saved(
        val name: String,
    ) : ShuangpinHintMessage

    data class Imported(
        val name: String,
    ) : ShuangpinHintMessage

    data class Deleted(
        val name: String,
    ) : ShuangpinHintMessage

    data object Exported : ShuangpinHintMessage

    data object Reset : ShuangpinHintMessage

    data class Refused(
        val error: ShuangpinHintException,
    ) : ShuangpinHintMessage

    data object FileUnreadable : ShuangpinHintMessage

    /** The typed link is not an https address. */
    data object LinkInvalid : ShuangpinHintMessage

    /** [message] is the localized reason, or null for the generic one. */
    data class DownloadFailed(
        val message: String?,
    ) : ShuangpinHintMessage

    data object WriteFailed : ShuangpinHintMessage
}

/**
 * The edits the screen makes, pure so they are tested without storage. Each returns the next
 * snapshot or throws [ShuangpinHintException]; nothing is written for a refused edit.
 */
object ShuangpinHintEdits {
    /** An edited preset replaces the one with its id; a new one is appended. */
    fun save(
        snapshot: ShuangpinHintSnapshot,
        preset: ShuangpinHintPreset,
    ): ShuangpinHintSnapshot {
        val value = preset.normalized()
        value.validate()
        val presets = snapshot.presets.map { if (it.id == value.id) value else it }
        return snapshot.copy(presets = if (snapshot.preset(value.id) == null) presets + value else presets)
    }

    /** The built-in preset stays so the switch cannot point at nothing; deleting the one in use turns hints off. */
    fun delete(
        snapshot: ShuangpinHintSnapshot,
        id: String,
    ): ShuangpinHintSnapshot {
        if (id == ShuangpinHintPresets.BUILTIN_ID) return snapshot
        val presets = snapshot.presets.filter { it.id != id }
        if (snapshot.selectedPresetId != id) return snapshot.copy(presets = presets)
        val withBuiltin =
            if (presets.any { it.id == ShuangpinHintPresets.BUILTIN_ID }) {
                presets
            } else {
                listOf(ShuangpinHintPresets.builtinXiaohe) +
                    presets
            }
        return snapshot.copy(presets = withBuiltin, selectedPresetId = ShuangpinHintPresets.BUILTIN_ID, enabled = false)
    }

    /** Importing is additive: a preset whose id is taken comes in as a copy with [importedSuffix]. */
    fun import(
        snapshot: ShuangpinHintSnapshot,
        text: String,
        importedSuffix: String,
    ): Pair<ShuangpinHintSnapshot, ShuangpinHintPreset> {
        val decoded = ShuangpinHintSnapshot.decodePresetFile(text)
        val preset =
            if (snapshot.preset(decoded.id) ==
                null
            ) {
                decoded
            } else {
                decoded.duplicate(decoded.name + importedSuffix)
            }
        val next = snapshot.copy(presets = snapshot.presets + preset)
        next.validate()
        return next to preset
    }
}

/**
 * Reads and writes the 双拼提示 settings document. The keyboard follows the same stored value, so
 * every change shows on its next render.
 */
class ShuangpinHintViewModel(
    private val preferences: SettingsPreferences,
    private val downloads: LinkDownloadGateway = LinkDownloadHolder.current,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ShuangpinHintUiState())
    val uiState: StateFlow<ShuangpinHintUiState> = mutableState.asStateFlow()

    private val mutableMessage = MutableStateFlow<ShuangpinHintMessage?>(null)
    val message: StateFlow<ShuangpinHintMessage?> = mutableMessage.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.shuangpinHintDocument.collect { text ->
                val decoded = text?.let { runCatching { ShuangpinHintSnapshot.decode(it) }.getOrNull() }
                mutableState.update {
                    ShuangpinHintUiState(
                        loaded = true,
                        snapshot = decoded ?: if (text == null) ShuangpinHintSnapshot() else it.snapshot,
                        damaged = text != null && decoded == null,
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    fun setEnabled(enabled: Boolean) = edit(null) { it.copy(enabled = enabled) }

    fun select(id: String) = edit(null) { if (it.preset(id) == null) it else it.copy(selectedPresetId = id) }

    fun save(preset: ShuangpinHintPreset) =
        edit(ShuangpinHintMessage.Saved(preset.name.trim())) {
            ShuangpinHintEdits.save(it, preset)
        }

    fun duplicate(
        id: String,
        name: String,
    ) = edit(null) { snapshot ->
        val source = snapshot.preset(id) ?: return@edit snapshot
        ShuangpinHintEdits.save(snapshot, source.duplicate(name))
    }

    fun delete(id: String) {
        val name =
            uiState.value.snapshot
                .preset(id)
                ?.name ?: return
        edit(ShuangpinHintMessage.Deleted(name)) { ShuangpinHintEdits.delete(it, id) }
    }

    /** Back to the default (hints off, the built-in preset only); also the way out of damaged settings. */
    fun reset() {
        viewModelScope.launch {
            runCatching { preferences.setShuangpinHintDocument(null) }
                .onSuccess { mutableMessage.value = ShuangpinHintMessage.Reset }
                .onFailure { mutableMessage.value = ShuangpinHintMessage.WriteFailed }
        }
    }

    fun importFile(
        resolver: ContentResolver,
        uri: Uri,
        importedSuffix: String,
    ) {
        viewModelScope.launch {
            val text =
                withContext(ioDispatcher) { runCatching { readBounded(resolver, uri) }.getOrNull() }
                    ?: return@launch run { mutableMessage.value = ShuangpinHintMessage.FileUnreadable }
            var imported: ShuangpinHintPreset? = null
            commit(
                { snapshot ->
                    ShuangpinHintEdits.import(snapshot, text, importedSuffix).also { imported = it.second }.first
                },
                { ShuangpinHintMessage.Imported(imported?.name.orEmpty()) },
            )
        }
    }

    /** Downloads a preset file from an https [url] and adds it like a picked file. */
    fun importLink(
        url: String,
        importedSuffix: String,
    ) {
        if (mutableState.value.downloading) return
        val link = normalizeDictionaryUrl(url) ?: return run { mutableMessage.value = ShuangpinHintMessage.LinkInvalid }
        mutableState.update { it.copy(downloading = true) }
        viewModelScope.launch {
            val result = downloads.download(link, MAXIMUM_FILE_BYTES.toLong())
            mutableState.update { it.copy(downloading = false) }
            when (result) {
                is LinkDownloadResult.Downloaded -> {
                    var imported: ShuangpinHintPreset? = null
                    commit(
                        { snapshot ->
                            ShuangpinHintEdits
                                .import(snapshot, result.bytes.decodeToString(), importedSuffix)
                                .also { imported = it.second }
                                .first
                        },
                        { ShuangpinHintMessage.Imported(imported?.name.orEmpty()) },
                    )
                }

                is LinkDownloadResult.Failure ->
                    mutableMessage.value =
                        if (result.tooLarge) {
                            ShuangpinHintMessage.Refused(ShuangpinHintException(ShuangpinHintProblem.TOO_LARGE))
                        } else {
                            ShuangpinHintMessage.DownloadFailed(result.message)
                        }
            }
        }
    }

    /** Stops the running link download. */
    fun cancelLinkImport() = downloads.cancelRunning()

    /** One preset ([id]) or, when null, the whole settings document. */
    fun export(
        resolver: ContentResolver,
        uri: Uri,
        id: String?,
    ) {
        val snapshot = uiState.value.snapshot
        val text =
            try {
                if (id == null) snapshot.encoded() else snapshot.preset(id)?.encoded() ?: return
            } catch (e: ShuangpinHintException) {
                mutableMessage.value = ShuangpinHintMessage.Refused(e)
                return
            }
        viewModelScope.launch {
            val written =
                withContext(ioDispatcher) {
                    runCatching {
                        (resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")).use {
                            it.write(text.encodeToByteArray())
                        }
                    }.isSuccess
                }
            mutableMessage.value = if (written) ShuangpinHintMessage.Exported else ShuangpinHintMessage.WriteFailed
        }
    }

    private fun edit(
        success: ShuangpinHintMessage?,
        change: (ShuangpinHintSnapshot) -> ShuangpinHintSnapshot,
    ) {
        viewModelScope.launch { commit(change) { success } }
    }

    /**
     * Applies [change] to the latest stored settings, not a stale copy on screen. Damaged settings
     * are never overwritten by an edit: only an explicit reset replaces them.
     */
    private suspend fun commit(
        change: (ShuangpinHintSnapshot) -> ShuangpinHintSnapshot,
        success: () -> ShuangpinHintMessage?,
    ) {
        if (uiState.value.damaged) return
        val stored = preferences.shuangpinHintDocument.first()
        val current =
            try {
                stored?.let { ShuangpinHintSnapshot.decode(it) } ?: ShuangpinHintSnapshot()
            } catch (_: ShuangpinHintException) {
                return
            }
        val text =
            try {
                val next = change(current)
                if (next == current) return
                next.encoded()
            } catch (e: ShuangpinHintException) {
                mutableMessage.value = ShuangpinHintMessage.Refused(e)
                return
            }
        try {
            preferences.setShuangpinHintDocument(text)
            mutableMessage.value = success()
        } catch (_: IOException) {
            mutableMessage.value = ShuangpinHintMessage.WriteFailed
        }
    }

    private fun readBounded(
        resolver: ContentResolver,
        uri: Uri,
    ): String {
        val stream = resolver.openInputStream(uri) ?: throw IOException("no stream")
        val bytes =
            stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (out.size() <= MAXIMUM_FILE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        // An oversized file is refused by the decoder, which names the problem.
        return bytes.decodeToString()
    }

    class Factory(
        private val preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShuangpinHintViewModel(preferences, LinkDownloadHolder.current) as T
    }

    private companion object {
        const val MAXIMUM_FILE_BYTES = 256 * 1024
    }
}

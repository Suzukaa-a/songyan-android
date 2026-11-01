package app.songyan.ime.settings.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.EngineFeaturesGateway
import app.songyan.ime.settings.state.EngineFeaturesHolder
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the feature screen shows: the build's features, and one change in flight or its failure. */
data class EngineFeaturesUiState(
    val loading: Boolean = true,
    /** Null when there is no readable build (nothing deployed yet). */
    val snapshot: EngineFeaturesSnapshot? = null,
    val saving: Boolean = false,
    /** Already localized; null for none or for a failure without detail. */
    val failure: String? = null,
    val failed: Boolean = false,
    /** Whether the failed change offers "retry" (a download that failed on the network or server). */
    val retryable: Boolean = false,
    /** Whether the change in flight is a download that can be cancelled. */
    val cancellable: Boolean = false,
    /** How much of the download in flight has arrived; null before the first report. */
    val transfer: TransferProgress? = null,
    /** The last change was a grammar model that is now stored; cleared by the next change. */
    val grammarModelImported: Boolean = false,
)

/**
 * Actions of the engine feature screen for one input method. Each change waits for the deploy
 * process to publish it and then reads the published build again, so the screen only ever shows
 * what the keyboard does.
 */
class EngineFeaturesViewModel(
    private val gateway: EngineFeaturesGateway,
) : ViewModel() {
    private val mutable = MutableStateFlow(EngineFeaturesUiState())
    val uiState: StateFlow<EngineFeaturesUiState> = mutable.asStateFlow()

    private var schemaId: String? = null
    private var lastDownload: (() -> Unit)? = null

    /** Shows [id]'s features; reads again when the input method in use changed. */
    fun show(id: String?) {
        if (id == null || id == schemaId) return
        schemaId = id
        reload()
    }

    fun reload() {
        val id = schemaId ?: return
        viewModelScope.launch {
            val snapshot = gateway.load(id)
            mutable.update { it.copy(loading = false, snapshot = snapshot) }
        }
    }

    fun setTypoCorrection(enabled: Boolean) = launchChange { gateway.setTypoCorrection(enabled) }

    fun setFuzzyPinyin(pairs: Set<String>) = withSchema { gateway.setFuzzyPinyin(it, pairs) }

    fun setPrediction(enabled: Boolean) = launchChange { gateway.setPrediction(enabled) }

    fun importPredictionDatabase(uri: Uri) = launchChange { gateway.importPredictionDatabase(uri) }

    fun setLuaComponent(
        prescription: String,
        enabled: Boolean,
    ) = withSchema { gateway.setLuaComponent(it, prescription, enabled) }

    fun importGrammarModel(uri: Uri) = launchChange(grammarModel = true) { gateway.importGrammarModel(uri) }

    fun importPredictionDatabaseFromUrl(url: String) =
        launchDownload(url) { link, progress -> gateway.importPredictionDatabaseFromUrl(link, progress) }

    fun importGrammarModelFromUrl(url: String) =
        launchDownload(url, grammarModel = true) { link, progress ->
            gateway.importGrammarModelFromUrl(link, progress)
        }

    /** Stops the download in flight. */
    fun cancelDownload() = gateway.cancelRunning()

    /** Runs the failed download again. */
    fun retry() {
        val again = lastDownload ?: return
        mutable.update { it.copy(failure = null, failed = false, retryable = false) }
        again()
    }

    fun setGrammar(selection: GrammarSelection) = withSchema { gateway.setGrammar(it, selection) }

    fun consumeFailure() {
        mutable.update { it.copy(failure = null, failed = false, retryable = false) }
    }

    /** Forgets that a grammar model was just stored, once the page that reported it is closed. */
    fun consumeGrammarModelImported() {
        mutable.update { it.copy(grammarModelImported = false) }
    }

    private fun withSchema(change: suspend (String) -> FeatureChangeResult) {
        val id = schemaId ?: return
        launchChange { change(id) }
    }

    private fun launchDownload(
        url: String,
        grammarModel: Boolean = false,
        change: suspend (String, (TransferProgress) -> Unit) -> FeatureChangeResult,
    ) {
        if (mutable.value.saving) return
        lastDownload = { launchDownload(url, grammarModel, change) }
        launchChange(cancellable = true, grammarModel = grammarModel) {
            change(url) { transfer -> mutable.update { if (it.saving) it.copy(transfer = transfer) else it } }
        }
    }

    private fun launchChange(
        cancellable: Boolean = false,
        grammarModel: Boolean = false,
        change: suspend () -> FeatureChangeResult,
    ) {
        if (mutable.value.saving) return
        mutable.update {
            it.copy(
                saving = true,
                failure = null,
                failed = false,
                retryable = false,
                cancellable = cancellable,
                transfer = null,
                grammarModelImported = false,
            )
        }
        viewModelScope.launch {
            val result = change()
            val snapshot = schemaId?.let { gateway.load(it) }
            mutable.update {
                it.copy(
                    saving = false,
                    cancellable = false,
                    transfer = null,
                    snapshot = snapshot ?: it.snapshot,
                    failure = (result as? FeatureChangeResult.Failure)?.message,
                    failed = result is FeatureChangeResult.Failure,
                    retryable = (result as? FeatureChangeResult.Failure)?.retryable == true,
                    grammarModelImported = grammarModel && result is FeatureChangeResult.Success,
                )
            }
        }
    }

    /** Builds the view model against the process-wide bridge holder. */
    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return EngineFeaturesViewModel(EngineFeaturesHolder.current) as T
        }
    }
}

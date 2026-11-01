package app.songyan.ime.settings.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.settings.state.AppKeyboardInfo
import app.songyan.ime.settings.state.DictionaryImportGateway
import app.songyan.ime.settings.state.DictionaryImportHolder
import app.songyan.ime.settings.state.DictionaryImportResult
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.ImportedDictionaryInfo
import app.songyan.ime.settings.state.IncomingPackageHolder
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.OnboardingProgress
import app.songyan.ime.settings.state.PackageLearningResult
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.SettingsAppStateHolder
import app.songyan.ime.settings.state.SettingsAppStateSource
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesGateway
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.state.onboardingProgress
import app.songyan.ime.settings.ui.screens.KeyboardFeelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the settings UI renders, derived from app state plus the system IME state. */
data class SettingsUiState(
    val engine: EngineStatus = EngineStatus.Unknown,
    val schemes: List<InstalledScheme> = emptyList(),
    val inUseSchemeId: String? = null,
    val currentKeyboard: AppKeyboardInfo? = null,
    val layoutId: String = SettingsPreferences.DEFAULT_LAYOUT_ID,
    val candidateTextSize: CandidateTextSize = CandidateTextSize.DEFAULT,
    val keyFeedback: KeyFeedbackSettings = KeyFeedbackSettings(),
    /** Key height, one-handed mode, key preview and release vibration, shown on the options screen. */
    val keyboardFeel: KeyboardFeelState = KeyboardFeelState(),
    /**
     * False until every stored preference has been read. Writable controls are not drawn before
     * that, so the user never sees the defaults flash by and never writes over a stored choice.
     */
    val preferencesLoaded: Boolean = false,
    val onboardingShown: Boolean = false,
    val notificationRationaleShown: Boolean = false,
    val ime: ImeStatus = ImeStatus(enabled = false, selected = false),
    val progress: OnboardingProgress = onboardingProgress(ImeStatus(false, false), hasTyped = false),
    /** The user-imported dictionary, when one was installed. */
    val importedDictionary: ImportedDictionaryInfo? = null,
    /** Dictionaries stacked onto imported scheme packages. */
    val stackedDictionaries: List<ImportedDictionaryInfo> = emptyList(),
    val dictionaryImport: DictionaryImportUiState = DictionaryImportUiState.Idle,
    /** False until the app module installs its gateway; the import actions stay disabled. */
    val dictionaryImportAvailable: Boolean = false,
    /** The one in-flight scheme package import; a finished result is cleared after the screen shows it. */
    val schemeImport: SchemeImportUiState = SchemeImportUiState.Idle,
    /** False until the app module installs its gateway; the scheme import action stays hidden. */
    val schemeImportAvailable: Boolean = false,
    /** Every stored input scheme, the bundled one first. */
    val sources: List<StoredSourceInfo> = emptyList(),
    /** The one in-flight use or delete request of a stored input scheme. */
    val sourceAction: StoredSourceUiState = StoredSourceUiState.Idle,
    /** A scheme package another app handed over, waiting for the user's yes. */
    val incomingPackage: Uri? = null,
) {
    val currentLayout: KeyboardLayoutOption get() = KeyboardLayoutOption.fromId(layoutId)

    val inUseScheme: InstalledScheme? get() = schemes.firstOrNull { it.id == inUseSchemeId }
}

/** Stored choices as read so far; [pending] counts the preferences that have not emitted yet. */
private data class StoredPreferences(
    val layoutId: String = SettingsPreferences.DEFAULT_LAYOUT_ID,
    val candidateTextSize: CandidateTextSize = CandidateTextSize.DEFAULT,
    val keyFeedback: KeyFeedbackSettings = KeyFeedbackSettings(),
    val keyboardFeel: KeyboardFeelState = KeyboardFeelState(),
    val onboardingShown: Boolean = false,
    val notificationRationaleShown: Boolean = false,
    val pending: Int = Int.MAX_VALUE,
)

/**
 * Single source for the settings screens. App-level state comes from [appStateSource], user
 * choices from [preferences]; the system input-method state is re-read on every resume because
 * the user changes it outside the app.
 *
 * Dictionary and scheme package import are the requests the UI starts itself: both are slow
 * deploys, so their state stays local to the view model and the app state is re-read once the
 * gateway reports success.
 */
class SettingsViewModel(
    application: Application,
    private val appStateSource: SettingsAppStateSource,
    private val imeStatusSource: ImeStatusSource,
    private val preferences: SettingsPreferences,
    private val dictionaryGateway: DictionaryImportGateway = DictionaryImportHolder.current,
    private val schemeGateway: SchemeImportGateway = SchemeImportHolder.current,
    private val sourcesGateway: StoredSourcesGateway = StoredSourcesHolder.current,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val imeStatus = MutableStateFlow(imeStatusSource.read())
    private val hasTyped = MutableStateFlow(false)
    private val stored = MutableStateFlow(StoredPreferences())

    // Collected only once every reader is registered, so the pending count is always the real one.
    private val preferenceReaders = mutableListOf<() -> Unit>()
    private var lastDictionaryRequest: (() -> Unit)? = null
    private var lastSchemeRequest: (() -> Unit)? = null
    private val dictionaryImport = MutableStateFlow<DictionaryImportUiState>(DictionaryImportUiState.Idle)
    private val schemeImport = MutableStateFlow<SchemeImportUiState>(SchemeImportUiState.Idle)
    private val sourceAction = MutableStateFlow<StoredSourceUiState>(StoredSourceUiState.Idle)

    private val initialState =
        SettingsUiState(
            engine = appStateSource.state.value.engine,
            schemes = appStateSource.state.value.schemes,
            inUseSchemeId = appStateSource.state.value.inUseSchemeId,
            currentKeyboard = appStateSource.state.value.currentKeyboard,
            importedDictionary = appStateSource.state.value.importedDictionary,
            stackedDictionaries = appStateSource.state.value.stackedDictionaries,
            sources = appStateSource.state.value.sources,
            dictionaryImportAvailable = dictionaryGateway.available,
            schemeImportAvailable = schemeGateway.available,
            ime = imeStatus.value,
            progress = onboardingProgress(imeStatus.value, hasTyped = false),
        )

    init {
        collectPreference(preferences.layoutId) { copy(layoutId = it) }
        collectPreference(preferences.candidateTextSize) { copy(candidateTextSize = CandidateTextSize.fromId(it)) }
        collectPreference(preferences.keyFeedback) { copy(keyFeedback = it) }
        collectPreference(preferences.keyHeight) { copy(keyboardFeel = keyboardFeel.copy(keyHeight = it)) }
        collectPreference(preferences.bottomGapDp) { copy(keyboardFeel = keyboardFeel.copy(bottomGapDp = it)) }
        collectPreference(
            preferences.longPressDelayMs,
        ) { copy(keyboardFeel = keyboardFeel.copy(longPressDelayMs = it)) }
        collectPreference(preferences.showSwitchKey) { copy(keyboardFeel = keyboardFeel.copy(showSwitchKey = it)) }
        collectPreference(preferences.keyFlick) { copy(keyboardFeel = keyboardFeel.copy(keyFlick = it)) }
        collectPreference(preferences.doubleSpacePeriod) {
            copy(keyboardFeel = keyboardFeel.copy(doubleSpacePeriod = it))
        }
        collectPreference(preferences.keyPreview) { copy(keyboardFeel = keyboardFeel.copy(keyPreview = it)) }
        collectPreference(preferences.hideIdleToolbar) {
            copy(keyboardFeel = keyboardFeel.copy(hideIdleToolbar = it))
        }
        collectPreference(preferences.releaseHaptic) { copy(keyboardFeel = keyboardFeel.copy(releaseHaptic = it)) }
        collectPreference(preferences.oneHand) { copy(keyboardFeel = keyboardFeel.copy(oneHand = it)) }
        collectPreference(preferences.widePosition) { copy(keyboardFeel = keyboardFeel.copy(widePosition = it)) }
        collectPreference(preferences.widePositionCustom) {
            copy(keyboardFeel = keyboardFeel.copy(widePositionCustom = it))
        }
        collectPreference(preferences.splitMode) { copy(keyboardFeel = keyboardFeel.copy(splitMode = it)) }
        collectPreference(preferences.readingPlacement) {
            copy(keyboardFeel = keyboardFeel.copy(readingPlacement = it))
        }
        collectPreference(preferences.onboardingShown) { copy(onboardingShown = it) }
        collectPreference(preferences.notificationRationaleShown) { copy(notificationRationaleShown = it) }
        stored.update { it.copy(pending = preferenceReaders.size) }
        preferenceReaders.forEach { it() }
    }

    /** Applies each emission of one stored preference; the first one of every preference counts as loaded. */
    private fun <T> collectPreference(
        flow: Flow<T>,
        apply: StoredPreferences.(T) -> StoredPreferences,
    ) {
        preferenceReaders += { readPreference(flow, apply) }
    }

    private fun <T> readPreference(
        flow: Flow<T>,
        apply: StoredPreferences.(T) -> StoredPreferences,
    ) {
        viewModelScope.launch {
            var first = true
            flow.collect { value ->
                stored.update { current ->
                    val next = current.apply(value)
                    if (first) next.copy(pending = current.pending - 1) else next
                }
                first = false
            }
        }
    }

    private val baseState: Flow<SettingsUiState> =
        combine(
            appStateSource.state,
            imeStatus,
            hasTyped,
            stored,
        ) { app, ime, typed, prefs ->
            SettingsUiState(
                engine = app.engine,
                schemes = app.schemes,
                inUseSchemeId = app.inUseSchemeId,
                currentKeyboard = app.currentKeyboard,
                importedDictionary = app.importedDictionary,
                stackedDictionaries = app.stackedDictionaries,
                sources = app.sources,
                layoutId = prefs.layoutId,
                candidateTextSize = prefs.candidateTextSize,
                keyFeedback = prefs.keyFeedback,
                keyboardFeel = prefs.keyboardFeel,
                preferencesLoaded = prefs.pending == 0,
                onboardingShown = prefs.onboardingShown,
                notificationRationaleShown = prefs.notificationRationaleShown,
                ime = ime,
                progress = onboardingProgress(ime, typed),
            )
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            baseState,
            dictionaryImport,
            schemeImport,
            sourceAction,
            IncomingPackageHolder.uri,
        ) { state, import, scheme, action, incoming ->
            state.copy(
                incomingPackage = incoming,
                sourceAction = action,
                dictionaryImport = import,
                dictionaryImportAvailable = dictionaryGateway.available,
                schemeImport = scheme,
                schemeImportAvailable = schemeGateway.available,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState)

    fun setKeyHeight(value: String) {
        if (value !in SettingsPreferences.KEY_HEIGHTS) return
        viewModelScope.launch { preferences.setKeyHeight(value) }
    }

    fun setBottomGapDp(value: Int) {
        if (value !in 0..SettingsPreferences.BOTTOM_GAP_MAX_DP ||
            value % SettingsPreferences.BOTTOM_GAP_STEP_DP != 0
        ) {
            return
        }
        viewModelScope.launch { preferences.setBottomGapDp(value) }
    }

    fun setShowSwitchKey(value: Boolean) {
        viewModelScope.launch { preferences.setShowSwitchKey(value) }
    }

    /** Null returns to the system's touch & hold delay. */
    fun setLongPressDelayMs(value: Int?) {
        if (value != null &&
            value !in SettingsPreferences.LONG_PRESS_MIN_MS..SettingsPreferences.LONG_PRESS_MAX_MS
        ) {
            return
        }
        viewModelScope.launch { preferences.setLongPressDelayMs(value) }
    }

    fun setWidePosition(value: String) {
        if (value !in SettingsPreferences.WIDE_POSITIONS) return
        viewModelScope.launch { preferences.setWidePosition(value) }
    }

    /** A number typed or dragged for the custom position also makes the custom position the one in use. */
    fun setWidePositionCustom(percent: Int) {
        viewModelScope.launch {
            preferences.setWidePositionCustom(SettingsPreferences.clampWidePosition(percent))
            preferences.setWidePosition("custom")
        }
    }

    fun setReadingPlacement(value: String) {
        if (value !in SettingsPreferences.READING_PLACEMENTS) return
        viewModelScope.launch { preferences.setReadingPlacement(value) }
    }

    fun setSplitMode(value: String) {
        if (value !in SettingsPreferences.SPLIT_MODES) return
        viewModelScope.launch { preferences.setSplitMode(value) }
    }

    fun setOneHand(value: String) {
        if (value !in SettingsPreferences.ONE_HAND_MODES) return
        viewModelScope.launch { preferences.setOneHand(value) }
    }

    fun setKeyFlick(value: Boolean) {
        viewModelScope.launch { preferences.setKeyFlick(value) }
    }

    fun setDoubleSpacePeriod(value: Boolean) {
        viewModelScope.launch { preferences.setDoubleSpacePeriod(value) }
    }

    fun setKeyPreview(value: Boolean) {
        viewModelScope.launch { preferences.setKeyPreview(value) }
    }

    fun setHideIdleToolbar(value: Boolean) {
        viewModelScope.launch { preferences.setHideIdleToolbar(value) }
    }

    fun setReleaseHaptic(value: Boolean) {
        viewModelScope.launch { preferences.setReleaseHaptic(value) }
    }

    /** Re-reads system state and asks the app for fresh state; called whenever the screen resumes. */
    fun refresh() {
        appStateSource.refresh()
        imeStatus.value = imeStatusSource.read()
    }

    /** Remembers that the first-launch guide was shown, so it never opens by itself again. */
    fun markOnboardingShown() {
        viewModelScope.launch { preferences.setOnboardingShown() }
    }

    /** Remembers that the notification explanation was shown, so it is asked about only once. */
    fun markNotificationRationaleShown() {
        viewModelScope.launch { preferences.setNotificationRationaleShown() }
    }

    /** Records that the user typed in the try-out field, completing the last onboarding step. */
    fun onTypedInTryOut() {
        hasTyped.value = true
    }

    /** Switches the keyboard to an installed input scheme. */
    fun useScheme(schemeId: String) {
        viewModelScope.launch {
            preferences.setActiveSchemaId(schemeId)
            appStateSource.refresh()
        }
    }

    /** Switches the keyboard layout. */
    fun selectLayout(layout: KeyboardLayoutOption) {
        viewModelScope.launch {
            preferences.setLayoutId(layout.id)
            appStateSource.refresh()
        }
    }

    fun setCandidateTextSize(size: CandidateTextSize) {
        viewModelScope.launch { preferences.setCandidateTextSize(size.id) }
    }

    /** Stores the key feedback choices; fields left null follow the system again. */
    fun setKeyFeedback(value: KeyFeedbackSettings) {
        viewModelScope.launch { preferences.setKeyFeedback(value) }
    }

    /**
     * Imports a dictionary made of the files the user picked with the system file picker, stacked
     * onto the scheme package [target] or, when null, added to the built-in input methods.
     */
    fun importDictionary(
        uris: List<Uri>,
        target: String? = null,
    ) {
        if (uris.isEmpty()) return
        runDictionaryRequest(DictionaryImportAction.IMPORT) { dictionaryGateway.importFromUris(uris, target) }
    }

    /** Makes a stored input scheme the one the keyboard uses. */
    fun useSource(sourceId: String) {
        runSourceAction(sourceId) { sourcesGateway.use(sourceId) }
    }

    /** Deletes a stored input scheme; its learning data stays for a later re-import. */
    fun deleteSource(sourceId: String) {
        val source = uiState.value.sources.firstOrNull { it.id == sourceId }
        // A damaged scheme is removed for good; only a usable one can be taken back.
        val done =
            if (source != null && source.usable) StoredSourceUiState.Deleted(sourceId, source.name) else null
        runSourceAction(sourceId, done) { sourcesGateway.delete(sourceId) }
    }

    /** Clears a finished source request after the screen showed its result. */
    fun dismissSourceAction() {
        if (sourceAction.value !is StoredSourceUiState.Running) sourceAction.value = StoredSourceUiState.Idle
    }

    internal fun runSourceAction(
        sourceId: String,
        done: StoredSourceUiState? = null,
        request: suspend () -> StoredSourceResult,
    ) {
        if (sourceAction.value is StoredSourceUiState.Running) return
        if (!sourcesGateway.available) {
            sourceAction.value = StoredSourceUiState.Failed(null)
            return
        }
        sourceAction.value = StoredSourceUiState.Running(sourceId)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { request() }
            appStateSource.refresh()
            sourceAction.value =
                when (result) {
                    StoredSourceResult.Success -> done ?: StoredSourceUiState.Idle
                    is StoredSourceResult.Failure -> StoredSourceUiState.Failed(result.message)
                }
        }
    }

    /** Imports a dictionary downloaded from an https link. */
    fun importDictionaryFromUrl(
        url: String,
        target: String? = null,
    ) {
        val normalized = normalizeDictionaryUrl(url)
        if (normalized == null) {
            dictionaryImport.value =
                DictionaryImportUiState.Failed(
                    action = DictionaryImportAction.IMPORT,
                    reason = DictionaryImportFailure.INVALID_URL,
                    message = null,
                )
            return
        }
        runDictionaryRequest(DictionaryImportAction.IMPORT) { dictionaryGateway.importFromUrl(normalized, target) }
    }

    /** Stops the running dictionary import or removal. */
    fun cancelDictionaryImport() = dictionaryGateway.cancelRunning()

    /** Runs the failed dictionary request again. */
    fun retryDictionaryImport() {
        val again = lastDictionaryRequest ?: return
        dictionaryImport.value = DictionaryImportUiState.Idle
        again()
    }

    /** Stops the running scheme package import. */
    fun cancelSchemeImport() = schemeGateway.cancelRunning()

    /** Runs the failed scheme package import again. */
    fun retrySchemeImport() {
        val again = lastSchemeRequest ?: return
        schemeImport.value = SchemeImportUiState.Idle
        again()
    }

    /** Removes the imported dictionary of [target] (null: the one over the built-in input methods). */
    fun removeImportedDictionary(target: String? = null) {
        runDictionaryRequest(DictionaryImportAction.REMOVE) { dictionaryGateway.removeImportedDictionary(target) }
    }

    /** Clears a finished request after the screen showed its result. */
    fun dismissDictionaryImport() {
        if (dictionaryImport.value !is DictionaryImportUiState.Running) {
            dictionaryImport.value = DictionaryImportUiState.Idle
        }
    }

    /** Imports a scheme package the user picked with the system file picker. */
    fun importScheme(uri: Uri) {
        runSchemeImport { schemeGateway.importFromUri(uri) }
    }

    /** Imports the scheme package the user picked as a folder. */
    fun importSchemeFolder(uri: Uri) {
        runSchemeImport { schemeGateway.importFromTree(uri) }
    }

    /** Downloads and imports a scheme package from an https link. */
    fun importSchemeFromLink(url: String) {
        val link = normalizeSchemeUrl(url)
        if (link == null) {
            schemeImport.value = SchemeImportUiState.Failed(null)
            return
        }
        runSchemeImport { progress -> schemeGateway.importFromLink(link, progress) }
    }

    /** Downloads the newest version of a stored scheme package from the link it came from. */
    fun updateScheme(sourceId: String) {
        runSchemeImport(updated = true) { progress -> schemeGateway.update(sourceId, progress) }
    }

    /** The user agreed to import the package another app handed over. */
    fun importIncomingPackage() {
        val uri = IncomingPackageHolder.uri.value ?: return
        IncomingPackageHolder.clear()
        importScheme(uri)
    }

    /** The user declined the package another app handed over. */
    fun dismissIncomingPackage() = IncomingPackageHolder.clear()

    /** Takes back the last deletion of a stored input scheme. */
    fun restoreSource(sourceId: String) {
        runSourceAction(sourceId) { sourcesGateway.restore(sourceId) }
    }

    /** Clears a finished scheme request after the screen showed its result. */
    fun dismissSchemeImport() {
        val current = schemeImport.value
        if (current is SchemeImportUiState.Running || current is SchemeImportUiState.MergingLearning) return
        // Closing the card without answering leaves the learned words unmerged, like declining.
        if (current is SchemeImportUiState.Succeeded && current.offersLearning) discardPackageLearning(current)
        schemeImport.value = SchemeImportUiState.Idle
    }

    /** The user agreed to merge the learned words the imported package came with. */
    fun mergePackageLearning() {
        val current = schemeImport.value as? SchemeImportUiState.Succeeded ?: return
        val id = current.info?.sourceId?.takeIf { current.offersLearning } ?: return
        schemeImport.value = SchemeImportUiState.MergingLearning
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { schemeGateway.mergePackageLearning(id) }
            // The package may have been put in use on the way.
            appStateSource.refresh()
            schemeImport.value =
                when (result) {
                    is PackageLearningResult.Merged -> SchemeImportUiState.LearningMerged(result.message)
                    is PackageLearningResult.Failure -> SchemeImportUiState.Failed(result.message)
                }
        }
    }

    /** The user declined to merge them; the package stays as imported. */
    fun declinePackageLearning() {
        val current = schemeImport.value as? SchemeImportUiState.Succeeded ?: return
        if (!current.offersLearning) return
        discardPackageLearning(current)
        schemeImport.value = current.copy(info = current.info?.copy(learningDictionaries = 0))
    }

    private fun discardPackageLearning(state: SchemeImportUiState.Succeeded) {
        val id = state.info?.sourceId ?: return
        viewModelScope.launch { withContext(ioDispatcher) { schemeGateway.discardPackageLearning(id) } }
    }

    /**
     * Runs one scheme import request off the main thread. [request] is a seam for tests: a [Uri]
     * cannot be built in a JVM unit test, so the state machine is exercised through it directly.
     */
    internal fun runSchemeImport(
        updated: Boolean = false,
        request: suspend (progress: (TransferProgress) -> Unit) -> SchemeImportResult,
    ) {
        // A second tap while the deploy runs would stage the same source twice.
        if (schemeImport.value is SchemeImportUiState.Running) return
        lastSchemeRequest = { runSchemeImport(updated, request) }
        if (!schemeGateway.available) {
            schemeImport.value = SchemeImportUiState.Failed(null)
            return
        }
        schemeImport.value = SchemeImportUiState.Running()
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    request { transfer ->
                        schemeImport.update {
                            if (it is SchemeImportUiState.Running) {
                                it.copy(
                                    transfer = transfer,
                                )
                            } else {
                                it
                            }
                        }
                    }
                }
            schemeImport.value =
                when (result) {
                    is SchemeImportResult.Success -> {
                        appStateSource.refresh()
                        SchemeImportUiState.Succeeded(result.info, updated || result.updated)
                    }

                    is SchemeImportResult.UpToDate -> SchemeImportUiState.UpToDate(result.info)

                    is SchemeImportResult.Failure -> SchemeImportUiState.Failed(result.message, result.retryable)
                }
        }
    }

    private fun runDictionaryRequest(
        action: DictionaryImportAction,
        request: suspend () -> DictionaryImportResult,
    ) {
        // A second tap while the deploy runs would stage the same source twice.
        if (dictionaryImport.value is DictionaryImportUiState.Running) return
        lastDictionaryRequest = { runDictionaryRequest(action, request) }
        if (!dictionaryGateway.available) {
            dictionaryImport.value =
                DictionaryImportUiState.Failed(
                    action = action,
                    reason = DictionaryImportFailure.GATEWAY,
                    message = null,
                )
            return
        }
        dictionaryImport.value = DictionaryImportUiState.Running(action)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { request() }
            dictionaryImport.value =
                when (result) {
                    is DictionaryImportResult.Success -> {
                        appStateSource.refresh()
                        DictionaryImportUiState.Succeeded(action, result.info)
                    }

                    is DictionaryImportResult.Failure ->
                        DictionaryImportUiState.Failed(
                            action = action,
                            reason = DictionaryImportFailure.GATEWAY,
                            message = result.message,
                            retryable = result.retryable,
                        )
                }
        }
    }

    /** Builds the view model against the process-wide holders. */
    class Factory(
        private val application: Application,
        private val imeStatusSource: ImeStatusSource,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(
                application = application,
                appStateSource = SettingsAppStateHolder.current,
                imeStatusSource = imeStatusSource,
                preferences = SettingsPreferencesHolder.current,
            ) as T
        }
    }
}

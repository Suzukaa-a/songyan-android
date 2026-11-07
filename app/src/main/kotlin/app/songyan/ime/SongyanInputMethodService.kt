package app.songyan.ime

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.songyan.ime.appearance.AppearanceRepository
import app.songyan.ime.content.ClipboardHistoryRecorder
import app.songyan.ime.content.QuickContentEvents
import app.songyan.ime.content.QuickContentRepository
import app.songyan.ime.content.QuickInsertCore
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.content.QuickInsertPolicy
import app.songyan.ime.content.RepositoryQuickContentPanelDataSource
import app.songyan.ime.content.SharedPreferencesQuickInsertFormatPreferences
import app.songyan.ime.content.quickInsertState
import app.songyan.ime.deploy.BuildStamps
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.deploy.DeploymentStarter
import app.songyan.ime.engine.EngineRuntimeState
import app.songyan.ime.engine.EngineStartGuard
import app.songyan.ime.engine.ImeEngineRuntime
import app.songyan.ime.engine.ProcessExits
import app.songyan.ime.engine.SystemInputMethods
import app.songyan.ime.engine.engineNoticeFor
import app.songyan.ime.ime.ColorMode
import app.songyan.ime.ime.CopiedChipSource
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import app.songyan.ime.ime.EngineNoticeAction
import app.songyan.ime.ime.ImeKeyboardTools
import app.songyan.ime.ime.ImePerf
import app.songyan.ime.ime.ImeViewOwners
import app.songyan.ime.ime.InlineAutofill
import app.songyan.ime.ime.KeyboardController
import app.songyan.ime.ime.KeyboardGeometry
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.ime.KeyboardPreferencesSource
import app.songyan.ime.ime.keyboardCallbacks
import app.songyan.ime.ime.panels.QuickInsertHost
import app.songyan.ime.ime.panels.QuickInsertState
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.prefs.LogAndContinue
import app.songyan.ime.prefs.reconcileWith
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.settings.ui.SettingsRoutes
import app.songyan.ime.settings.ui.theme.ThemeMode
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.tools.ProcessTextKind
import app.songyan.ime.tools.ProcessTextOutcome
import app.songyan.ime.tools.ProcessTextRelay
import app.songyan.ime.tools.ProcessTextRequest
import app.songyan.ime.tools.ProcessTextSource
import app.songyan.ime.tools.sameEditorAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a first deployment may take before the engine notice offers the settings path. */
private const val SETTINGS_ACTION_DELAY_MS = 2_000L
private const val NO_INPUT = -1
private const val SNAPSHOT_QUIET_MS = 5_000L
private const val TAG = "SongyanIme"

/** How much of the field a tool may be handed: a paragraph, not the whole document. */
private const val MAX_PROCESS_TEXT = 1000

class SongyanInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + LogAndContinue)
    private val editor = EditorConnection { currentInputConnection }
    private val viewOwners by lazy { ImeViewOwners() }

    /** Scratch for [onComputeInsets], which runs on every traversal of the keyboard window. */
    private val insetsLocation = IntArray(2)
    private val preferences: KeyboardPreferencesSource by lazy {
        val store = (application as SongyanApplication).keyboardPreferences
        val appearance = (application as SongyanApplication).appearanceRepository
        object : KeyboardPreferencesSource {
            override val layoutId = store.layoutId
            override val activeSchemaId = store.activeSchemaId
            override val keyHeight = store.keyHeight
            override val bottomGapDp = store.bottomGapDp
            override val longPressDelayMs = store.longPressDelayMs
            override val showSwitchKey = store.showSwitchKey
            override val keyPreview = store.keyPreview
            override val keyFlickDirection = store.keyFlickDirection
            override val doubleSpacePeriod = store.doubleSpacePeriod
            override val hideIdleToolbar = store.hideIdleToolbar
            override val releaseHaptic = store.releaseHaptic
            override val oneHand = store.oneHand
            override val widePosition = store.widePositionPercent
            override val splitMode = store.splitMode
            override val readingPlacement = store.readingPlacement
            override val shuangpinHints = store.shuangpinHints
            override val t9QuickSymbols = store.t9QuickSymbols
            override val menuHiddenSchemas = store.menuHiddenSchemas
            override val menuHiddenLayouts = store.menuHiddenLayouts

            override suspend fun setOneHand(value: String) = store.setOneHand(value)

            override suspend fun setKeyHeight(value: String) = store.setKeyHeight(value)

            override suspend fun setKeyPreview(value: Boolean) = store.setKeyPreview(value)

            override suspend fun setKeyFeedback(value: KeyFeedbackSettings) = store.setKeyFeedback(value)

            // The appearance store owns the light/dark choice (the settings app writes it there too);
            // writing through it also repaints every keyboard view.
            override val colorMode =
                appearance.state
                    .map { state ->
                        when (state.themeMode) {
                            ThemeMode.SYSTEM -> ColorMode.SYSTEM
                            ThemeMode.LIGHT -> ColorMode.LIGHT
                            ThemeMode.DARK -> ColorMode.DARK
                        }.wire
                    }.distinctUntilChanged()

            override suspend fun setColorMode(value: String) =
                appearance.setThemeMode(
                    when (ColorMode.resolve(value)) {
                        ColorMode.SYSTEM -> ThemeMode.SYSTEM
                        ColorMode.LIGHT -> ThemeMode.LIGHT
                        ColorMode.DARK -> ThemeMode.DARK
                    },
                )

            override val symbolLock = store.symbolLock

            override suspend fun setSymbolLock(value: Boolean) = store.setSymbolLock(value)

            override val kana12FlickOnly = store.kana12FlickOnly

            override suspend fun setKana12FlickOnly(value: Boolean) = store.setKana12FlickOnly(value)

            override val candidateTextSize = store.candidateTextSize
            override val keyFeedback = store.keyFeedback

            override suspend fun setLayoutId(id: String) = store.setLayoutId(id)

            override suspend fun setActiveSchemaId(id: String) = store.setActiveSchemaId(id)
        }
    }

    /**
     * The keyboard tools: the user's tool files, the app's own OpenCC instance and the bounded
     * regular-expression worker. Built on the first keyboard view, and only released when it exists,
     * because opening its state would touch the disk from the thread that is tearing the service down.
     */
    private var keyboardTools: ImeKeyboardTools? = null

    private fun keyboardToolsForView(): ImeKeyboardTools =
        keyboardTools
            ?: ImeKeyboardTools(this, (application as SongyanApplication).storage).also { keyboardTools = it }

    /** Quick-content data of this process; every read and write happens on its own IO lane. */
    private val contentRepository by lazy {
        // The path comes from the application's storage: `filesDir` creates its directory, which the
        // first use on the main thread would turn into a disk write.
        QuickContentRepository((application as SongyanApplication).storage.contentDir)
    }
    private val panelDataSource by lazy {
        RepositoryQuickContentPanelDataSource(contentRepository) { clipboardRecorder.setHistoryEnabled(it) }
    }
    private val clipboardRecorder by lazy { ClipboardHistoryRecorder(this, contentRepository, scope) }

    /** Created on an IO thread: its preferences read is disk IO and must stay off the main thread. */
    @Volatile
    private var quickInsert: QuickInsertCore? = null

    /** What the in-keyboard settings panel can ask for; the core may still be warming up. */
    private val quickInsertHost =
        object : QuickInsertHost {
            /** Repeating is never offered in a password / no-personalized-learning editor. */
            override fun repeatLastInput(): Boolean =
                if (quickInsertPolicy.mayReplay()) quickInsert?.performRepeat() ?: false else false

            override fun insertDateFormat(format: QuickInsertDateFormat): Boolean =
                quickInsert?.insertDateFormat(format) ?: false

            override fun quickInsertState(): QuickInsertState =
                quickInsert
                    ?.let { quickInsertState(it) }
                    ?.let { state -> if (quickInsertPolicy.mayReplay()) state else state.copy(repeatPreview = null) }
                    ?: QuickInsertState()
        }

    private var controller: KeyboardController? = null

    private val inputMethods by lazy { SystemInputMethods(this) { window?.window?.attributes?.token } }

    /** Counts input starts, so a show never restarts the keyboard on an input it already started. */
    private var inputToken = 0
    private var controllerInput = NO_INPUT

    /** The restarted editor still shows the composition; the engine keeps it too. */
    private var keepComposition = false
    private var root: LinearLayout? = null
    private var policy = EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE)

    /**
     * The editor that has this keyboard right now, as the platform last said in onStartInput and
     * cleared in onFinishInput. Never the stale [currentInputEditorInfo] of a finished binding.
     */
    private var inputBinding: EditorInfo? = null

    /** The repeat-buffer rules that follow from [policy]; kept in step by [applyPolicy]. */
    private var quickInsertPolicy = QuickInsertPolicy()

    private fun applyPolicy(value: EditorPolicy) {
        policy = value
        quickInsertPolicy = QuickInsertPolicy.of(value)
    }

    /**
     * What the tool relay needs from this editor and the clipboard. A tool only handles text that is
     * already in the field, so a composition (and a private editor) never runs one: the composition
     * stays exactly as the user left it.
     */
    private val processTextHost =
        object : ProcessTextRelay.Host {
            override fun snapshotForProcessText(source: ProcessTextSource?): ProcessTextRequest? {
                val info = inputBinding ?: return null
                if (policy.privateInput || editor.composing) return null
                val connection = currentInputConnection ?: return null
                val selected = runCatching { connection.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
                val chosen =
                    source
                        ?: if (selected.isNotEmpty()) {
                            ProcessTextSource(selected, ProcessTextKind.SELECTION)
                        } else {
                            // Only the paragraph the caret is in; one unit past the limit tells a
                            // paragraph that fits from one the read window cut.
                            val paragraph =
                                (
                                    editor.contextBeforeCursor(
                                        MAX_PROCESS_TEXT + 1,
                                    ) ?: return null
                                ).substringAfterLast('\n')
                            ProcessTextSource(paragraph, ProcessTextKind.CURSOR)
                        }
                // Text that cannot be handed over whole is refused, not cut silently.
                if (chosen.text.isBlank() || chosen.text.length > MAX_PROCESS_TEXT) return null
                val stillShown =
                    when (chosen.kind) {
                        ProcessTextKind.SELECTION -> selected == chosen.text
                        // A paragraph is only replaced up to a bare caret, so a selection retires it.
                        ProcessTextKind.CURSOR ->
                            selected.isEmpty() &&
                                runCatching { connection.getTextBeforeCursor(chosen.text.length, 0)?.toString() }
                                    .getOrNull() == chosen.text
                        ProcessTextKind.CLIPBOARD -> true
                    }
                if (!stillShown) return null
                return ProcessTextRequest(chosen.text, info.packageName, info.fieldId, chosen.kind)
            }

            /** The bound editor is the one the request came from, and it may run a tool at all. */
            private fun boundTo(request: ProcessTextRequest): Boolean =
                !policy.privateInput &&
                    !editor.composing &&
                    currentInputConnection != null &&
                    request.sameEditorAs(inputBinding)

            /** The text the app was handed is still exactly where it was; false offers insert only. */
            private fun unchanged(request: ProcessTextRequest): Boolean {
                val connection = currentInputConnection ?: return false
                return when (request.kind) {
                    ProcessTextKind.SELECTION ->
                        runCatching { connection.getSelectedText(0)?.toString() }.getOrNull() == request.text

                    ProcessTextKind.CURSOR ->
                        runCatching { connection.getSelectedText(0) }.getOrNull().isNullOrEmpty() &&
                            runCatching { connection.getTextBeforeCursor(request.text.length, 0)?.toString() }
                                .getOrNull() == request.text
                    // Never in the field, so there is nothing to replace.
                    ProcessTextKind.CLIPBOARD -> false
                }
            }

            override fun canWriteProcessText(request: ProcessTextRequest): Boolean =
                // The field may have changed while the other app was open: never write into a binding
                // this keyboard does not have, and never over text the user has edited since.
                boundTo(request) && unchanged(request)

            override fun writeProcessText(
                request: ProcessTextRequest,
                text: String,
            ): ProcessTextOutcome {
                if (!canWriteProcessText(request)) {
                    copyProcessText(text)
                    return ProcessTextOutcome.COPIED
                }
                val written =
                    when (request.kind) {
                        ProcessTextKind.SELECTION -> editor.replaceSelection(request.text, text)
                        ProcessTextKind.CURSOR -> editor.replaceBeforeCursor(request.text, text)
                        ProcessTextKind.CLIPBOARD -> false
                    }
                if (!written) {
                    copyProcessText(text)
                    return ProcessTextOutcome.COPIED
                }
                return ProcessTextOutcome.REPLACED
            }

            override fun onProcessTextAnswer() {
                if (!isInputViewShown || policy.privateInput) return
                val pending = ProcessTextRelay.pendingResult()
                if (pending == null) {
                    // An app that returned nothing leaves no reason to keep the waiting panel up.
                    controller?.closeToolsPanel()
                } else if (pending.request.sameEditorAs(inputBinding)) {
                    // The keyboard is already back on the editor that asked: the preview opens now.
                    // Any other binding (the relay's own window) waits for that editor's next show.
                    controller?.openProcessTextResult()
                }
            }

            override fun copyProcessText(text: String) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.app_name), text),
                )
                Toast.makeText(this@SongyanInputMethodService, R.string.process_text_copied, Toast.LENGTH_SHORT).show()
            }
        }

    private var backend: EngineBackend? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private var panelBack: OnBackInvokedCallback? = null
    private var runtime: ImeEngineRuntime? = null
    private var runtimeState = EngineRuntimeState.NO_PUBLICATION
    private var settingsOffered = false

    /** The system asked for the keyboard to carry its own input method switcher (API 35+). */
    private var customSwitcherRequested = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The settings screen writes the quick-content files directly; re-read them when it says so. */
    private val quickContentReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != QuickContentEvents.ACTION_CHANGED) return
                scope.launch {
                    val state = contentRepository.refresh()
                    // Keep the capture gate in step right away: history turned off must stop
                    // clipboard reads from the next change on, not from the next process start.
                    clipboardRecorder.setHistoryEnabled(state.clipboardEnabled)
                }
            }
        }

    /** Repaints an already visible keyboard when the appearance changes in settings. */
    private val appearanceReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AppearanceRepository.ACTION_APPEARANCE_CHANGED) {
                    controller?.refreshAppearance()
                    applyWindowAppearance()
                }
            }
        }

    private val deployReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action ==
                    DeployContract.ACTION_DEPLOY_FINISHED
                ) {
                    // Deferred by the runtime while the keys are on screen; repeated broadcasts are no-ops.
                    runtime?.refreshIfChanged()
                }
            }
        }

    // The app applies the user's per-app locale to the service context. Language splits are
    // disabled in app/build.gradle.kts (bundle { language { enableSplit = false } }), which lint's
    // build-file scan does not read from the Kotlin DSL, so the warning is suppressed here.
    @SuppressLint("AppBundleLocaleChanges")
    override fun attachBaseContext(newBase: Context) {
        val locales =
            if (Build.VERSION.SDK_INT >= 33) {
                newBase.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            } else {
                android.os.LocaleList.getEmptyLocaleList()
            }
        val localized =
            if (locales.isEmpty) {
                newBase
            } else {
                val configuration = android.content.res.Configuration()
                configuration.setLocales(locales)
                newBase.createConfigurationContext(configuration)
            }
        super.attachBaseContext(localized)
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as SongyanApplication
        val factory = app.publishedEngineFactory
        if (factory == null) {
            runtimeState = EngineRuntimeState.FAILED
        } else {
            runtime =
                ImeEngineRuntime(
                    storage = app.storage,
                    publisher = GenerationPublisher(app.storage),
                    deployStatus = DeployStatusStore(app.storage),
                    factory = factory,
                    appName = app.rimeAppName,
                    appVersion = BuildConfig.VERSION_NAME,
                    // The runtime never holds a Context; the first deployment is started from here.
                    requestFirstDeploy = {
                        DeploymentStarter.requestEnsureDeployed(this@SongyanInputMethodService)
                    },
                    currentStamp = { BuildStamps.current(app) },
                    ownership = app.engineOwnership,
                    startGuard =
                        EngineStartGuard(
                            app.storage,
                            previousExit = { since -> ProcessExits.previousExit(app, since) },
                        ),
                    requestBuiltin = { DeploymentStarter.requestActivateBuiltin(this@SongyanInputMethodService) },
                    beforeEngine = { record -> app.keyboardPreferences.reconcileWith(record) },
                    learningMerger = { record ->
                        RimeBindings.learningMerger(
                            generationDir = app.storage.generationDir(record.generation),
                            appName = app.rimeAppName,
                            appVersion = BuildConfig.VERSION_NAME,
                        )
                    },
                    callbacks =
                        object : ImeEngineRuntime.Callbacks {
                            override fun onState(state: EngineRuntimeState) {
                                runtimeState = state
                                renderRuntimeState()
                            }

                            override fun onEngine(backend: EngineBackend) {
                                bindBackend(backend)
                            }

                            override suspend fun awaitInputIdle() {
                                controller?.awaitIdle()
                            }

                            override fun onEngineReleased() {
                                backend = null
                                controller?.unbind()
                                editor.abandon()
                            }
                        },
                ).also { it.ensureStarted() }
            DeploymentStarter.completeCompanionsIfPending(this)
        }
        ContextCompat.registerReceiver(
            this,
            deployReceiver,
            IntentFilter(DeployContract.ACTION_DEPLOY_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            appearanceReceiver,
            IntentFilter(AppearanceRepository.ACTION_APPEARANCE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            quickContentReceiver,
            IntentFilter(QuickContentEvents.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch(Dispatchers.IO) {
            contentRepository.refresh()
            quickInsert =
                QuickInsertCore(
                    preferences = SharedPreferencesQuickInsertFormatPreferences(this@SongyanInputMethodService),
                    confirmComposition = { confirmCompositionForInsert() },
                    insert = { text -> commitQuickText(text) },
                    onChange = { controller?.onTextInserted() },
                    // Dates follow the input language, so the text matches what the user types.
                    inputLanguage = { controller?.inputLanguage ?: InputLanguage.ZH_HANS },
                )
        }
        clipboardRecorder.start()
        ProcessTextRelay.attach(processTextHost)
    }

    /**
     * The keyboard confirms its composition through the engine before it asks for a quick insert;
     * text must never land inside (or ahead of) a spelling that is still in the field.
     */
    private fun confirmCompositionForInsert(): Boolean = !editor.composing

    /** Commits quick-insert text through the same editor path a candidate takes. */
    private fun commitQuickText(text: String): Boolean = editor.update("", listOf(text), false)

    /**
     * Shows the engine's state as the keyboard's notice. The keyboard window stays up, so the user can
     * switch input methods, but it draws no keys and commits nothing until the engine is ready.
     */
    private fun renderRuntimeState() {
        mainHandler.removeCallbacks(showSettingsAction)
        // The settings path always works: starting the :deploy service from the IME process can be
        // rejected by background foreground-service restrictions on Android 12+.
        if (runtimeState == EngineRuntimeState.READY) {
            settingsOffered = false
        } else if (runtimeState == EngineRuntimeState.PREPARING && !settingsOffered) {
            mainHandler.postDelayed(showSettingsAction, SETTINGS_ACTION_DELAY_MS)
        }
        controller?.setEngineNotice(engineNoticeFor(runtimeState, settingsOffered))
    }

    /** A slow first deployment should offer the settings path, not only a retry button. */
    private val showSettingsAction =
        Runnable {
            if (runtimeState == EngineRuntimeState.PREPARING) {
                settingsOffered = true
                renderRuntimeState()
            }
        }

    private fun retryEngine() {
        runtimeState = EngineRuntimeState.PREPARING
        renderRuntimeState()
        // Rebuild what the user is typing with, never silently the built-in package.
        DeploymentStarter.requestRedeployActive(this)
    }

    /**
     * Opens settings on [target]. CLEAR_TOP reuses a running settings screen instead of stacking a
     * second one, and the target makes it land on that page rather than wherever it was left.
     */
    private fun openSongyanSettings(target: SettingsTarget = SettingsTarget.HOME) {
        startActivity(
            Intent(this, SongyanSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SettingsTarget.EXTRA, target.id),
        )
    }

    /**
     * Opens the settings page where keyboard tools are managed. The page is addressed by route
     * because the settings target enum names only the pages the keyboard already links to.
     */
    private fun openToolSettings() {
        startActivity(
            Intent(this, SongyanSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SettingsRoutes.EXTRA_ROUTE, SettingsRoutes.TOOLS),
        )
    }

    private fun updatePanelBack(visible: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = panelBack ?: OnBackInvokedCallback { controller?.closePanels() }.also { panelBack = it }
        backDispatcher?.unregisterOnBackInvokedCallback(callback)
        backDispatcher = null
        if (visible) {
            backDispatcher = root?.findOnBackInvokedDispatcher()
            backDispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    override fun onCreateInputView(): View {
        val tools = keyboardToolsForView()
        val pad =
            KeyboardController(
                this,
                scope,
                editor.keyboardCallbacks(
                    action = { performAction() },
                    onCommitted = { text ->
                        // A private editor's text must not seed the repeat buffer: the buffer
                        // outlives the field and can be replayed into another app.
                        if (quickInsertPolicy.mayRecordCommit()) quickInsert?.recordCommitted(text)
                        controller?.onTextInserted()
                    },
                    panelsChanged = { updatePanelBack(it) },
                    openSettings = { openSongyanSettings() },
                    hideKeyboard = { requestHideSelf(0) },
                    automaticCapsWanted = { updateAutomaticCaps() },
                    openSettingsPage = { openSongyanSettings(it) },
                    openToolsSettings = { openToolSettings() },
                    clipboard = getSystemService(android.content.ClipboardManager::class.java),
                    inputMethods = inputMethods,
                    engineNoticeAction = { action ->
                        when (action) {
                            EngineNoticeAction.RETRY -> retryEngine()
                            EngineNoticeAction.SETTINGS -> openSongyanSettings()
                        }
                    },
                ),
                preferences,
                quickContentDataSource = panelDataSource,
                quickInsert = quickInsertHost,
                copiedChipSource = CopiedChipSource(getSystemService(android.content.ClipboardManager::class.java)),
                toolGateway = tools.gateway,
                toolEditor =
                    tools.editor(
                        editor = editor,
                        info = { inputBinding },
                        policy = { policy },
                    ),
            )
        controller?.retire()
        controller = pad
        pad.setCustomSwitcherRequested(customSwitcherRequested)
        controllerInput = NO_INPUT
        val palette = KeyboardPalette.from(this)
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(palette.background)
            }
        container.addView(pad.view, LinearLayout.LayoutParams(-1, -2))
        root = container
        viewOwners.install(container)
        // A view rebuilt for a configuration change continues the composition the editor still shows.
        backend?.let { pad.bind(it, keepComposition = editor.composing) }
        pad.configure(policy)
        if (Build.VERSION.SDK_INT >= 30) {
            window?.window?.setDecorFitsSystemWindows(false)
        }
        applyWindowAppearance()
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                // The cutout side is padded too, and the container's own background paints under it.
                val bars =
                    insets.getInsets(
                        WindowInsets.Type.navigationBars() or
                            WindowInsets.Type.captionBar() or
                            WindowInsets.Type.displayCutout(),
                    )
                view.setPadding(bars.left, 0, bars.right, bars.bottom)
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    0,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        renderRuntimeState()
        return container
    }

    override fun onEvaluateFullscreenMode(): Boolean =
        KeyboardGeometry.fullscreenEditor(super.onEvaluateFullscreenMode(), resources.configuration.screenHeightDp)

    /**
     * The window is wrap-content, so the keyboard's top is the window's top; the insets still come from
     * the keyboard view itself rather than from the window, the way AOSP LatinIME, Trime and FlorisBoard
     * do it: the app is resized to the keyboard's top, sees everything above it, and only the keyboard
     * takes touches. Fullscreen (extract) mode keeps the platform's own computation.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val keyboard = root ?: return
        if (isFullscreenMode || !keyboard.isShown || keyboard.height == 0) return
        val location = insetsLocation
        keyboard.getLocationInWindow(location)
        val top = location[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(location[0], top, location[0] + keyboard.width, top + keyboard.height)
    }

    /**
     * When the IME explicitly hides the system IME navigation bar, the system can request a replacement
     * switcher. This callback does not report whether a vendor provides a switcher in its navigation bar.
     */
    override fun onCustomImeSwitcherButtonRequestedVisible(visible: Boolean) {
        customSwitcherRequested = visible
        controller?.setCustomSwitcherRequested(visible)
    }

    /** Keeps the window bars in step with the keyboard palette in both themes. */
    private fun applyWindowAppearance() {
        val palette = KeyboardPalette.from(this)
        root?.setBackgroundColor(palette.background)
        window?.window?.apply {
            navigationBarColor = palette.background
            if (Build.VERSION.SDK_INT >= 30) {
                insetsController?.setSystemBarsAppearance(
                    // The bar sits on the keyboard's own surface: its icons follow that, not the system theme.
                    if (palette.isLight) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ServiceLocale.apply(resources, ServiceLocale.wanted(this))
        // The keyboard is a live window: a day/night or font-scale switch must not leave the
        // already-built views with yesterday's palette.
        controller?.refreshAppearance()
        applyWindowAppearance()
    }

    /** The application hands over its single prebuilt engine after asynchronous preparation. */
    fun bindBackend(value: EngineBackend) {
        backend = value
        controller?.bind(value)
        runtimeState = EngineRuntimeState.READY
        renderRuntimeState()
    }

    override fun onStartInput(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInput(info, restarting)
        inputBinding = info
        keepComposition = editor.startInput(info, restarting)
        inputToken++
        applyPolicy(EditorPolicy.from(info))
        // The repeat target is session state: a new input connection, or a private editor, must
        // never be able to replay text typed into another field, host or app.
        if (quickInsertPolicy.mustResetOnNewSession(restarting)) quickInsert?.resetContext()
        clipboardRecorder.setCaptureAllowed(false)
        startControllerInput()
        // The editor that a tool's answer belongs to may have come back, or another one may have.
        ProcessTextRelay.onEditorStarted(info)
    }

    /** Starts the keyboard on the current input exactly once, however many shows follow. */
    private fun startControllerInput() {
        val pad = controller ?: return
        if (controllerInput == inputToken) return
        controllerInput = inputToken
        pad.startInput(policy, keepComposition)
    }

    override fun onStartInputView(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        inputBinding = info
        snapshotJob?.cancel()
        ImePerf.refresh()
        ImePerf.showStart()
        armShowMetric()
        viewOwners.show()
        runtime?.setInputViewShown(true)
        runtime?.refreshIfChanged()
        applyPolicy(EditorPolicy.from(info))
        // The keyboard window lives across shows: re-resolve the palette here so a change made in
        // settings (appearance, dynamic colour, light/dark) is in effect on the next show. The
        // resolution is cached per appearance revision, so an unchanged show costs two volatile reads.
        controller?.refreshAppearance()
        controller?.configure(policy)
        startControllerInput()
        // The clipboard is only read while the keyboard is on screen in a field that may be observed;
        // a clip copied while it was hidden is taken now.
        clipboardRecorder.setCaptureAllowed(!policy.privateInput)
        if (!policy.privateInput) clipboardRecorder.catchUp()
        controller?.onWindowShown()
        updateAutomaticCaps()
        root?.requestApplyInsets()
        // A show after the relay is another chance for the editor to come back before the answer.
        ProcessTextRelay.onEditorStarted(info)
        // The answer waits for the user to look at it; coming back to the editor that handed the
        // text out is when the panel shows the preview. Nothing is written until they say so.
        if (!policy.privateInput) controller?.openProcessTextResult()
    }

    /** Ends the show metric on the first pre-draw of the IME window, then detaches. */
    private fun armShowMetric() {
        val view = window?.window?.decorView ?: root ?: return
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    ImePerf.showFirstFrame()
                    return true
                }
            },
        )
    }

    private fun updateAutomaticCaps() {
        // Chinese input has no automatic capital: do not ask the editor on every selection update.
        if (controller?.wantsAutomaticShift != true) return
        val caps = editor.automaticCapsMode(currentInputEditorInfo?.inputType ?: 0)
        controller?.setAutomaticShift(caps != 0)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val movedAway =
            editor.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (movedAway) controller?.discardComposition()
        if (editor.selectionMovedExternally) controller?.onSelectionMovedExternally()
        controller?.onEditorSelectionChanged(newSelStart, newSelEnd)
        updateAutomaticCaps()
    }

    override fun onFinishInput() {
        clipboardRecorder.setCaptureAllowed(false)
        controller?.onWindowHidden()
        editor.finishInput(currentInputEditorInfo)
        controller?.finishInput()
        quickInsert?.resetContext()
        // No editor has this keyboard any more: a tool's answer must not use the old connection.
        inputBinding = null
        ProcessTextRelay.onEditorStarted(null)
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clipboardRecorder.setCaptureAllowed(false)
        controller?.onWindowHidden()
        // Hidden while the editor stays (Back, a hardware keyboard): nothing may keep composing unseen.
        if (finishingInput) controller?.closeAll(preserveExternalAnswer = true) else controller?.hideInput()
        // A generation published while the keys were on screen is loaded now.
        runtime?.setInputViewShown(false)
        viewOwners.hide()
        scheduleLearningSnapshot()
        super.onFinishInputView(finishingInput)
    }

    private var snapshotJob: Job? = null

    /**
     * The keyboard is hidden: a quiet moment to keep a copy of what it learned (at most one a day,
     * only when the data changed). Waits a few seconds so a quick return to typing is never
     * disturbed; once the copy starts it runs to the end, because it sets the engine aside.
     */
    private fun scheduleLearningSnapshot() {
        snapshotJob?.cancel()
        val snapshots = (application as SongyanApplication).learningSnapshots
        snapshotJob =
            scope.launch {
                delay(SNAPSHOT_QUIET_MS)
                withContext(NonCancellable) {
                    try {
                        snapshots.snapshotIfDue()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "learning snapshot failed", error)
                    }
                }
            }
    }

    /** A language changed while the keyboard was hidden reaches the labels the next time it shows. */
    override fun onWindowShown() {
        super.onWindowShown()
        if (ServiceLocale.apply(resources, ServiceLocale.wanted(this))) controller?.refreshAppearance()
    }

    /** A hidden window takes no keys, whichever path hid it: a waiting engine swap may run. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        runtime?.setInputViewShown(false)
    }

    /** Inline autofill (Android 11+): the system's password and one-time-code suggestions in the bar. */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        val palette = KeyboardPalette.from(this)
        val width = resources.displayMetrics.widthPixels
        val heightDp =
            controller?.inlineSuggestions?.contentHeightDp
                ?: return InlineAutofill.buildRequest(this, palette, width)
        return InlineAutofill.buildRequest(this, palette, width, heightDp)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val strip = controller?.inlineSuggestions ?: return false
        return InlineAutofill.show(this, strip, response, ContextCompat.getMainExecutor(this))
    }

    private fun performAction() {
        val info = currentInputEditorInfo ?: return
        // An editor with no action (IME_ACTION_NONE, or a multi-line field: TextView forces the flag
        // so Enter stays a line break) and one that left the action unspecified both get a plain
        // Return. A custom action label (TextView#setImeActionLabel) is already resolved to that
        // actionId by EditorPolicy, so it takes the branch below.
        if (policy.action == EditorInfo.IME_ACTION_NONE || policy.action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            editor.sendReturn(policy.returnsNewline)
            return
        }
        currentInputConnection?.performEditorAction(if (info.actionId != 0) info.actionId else policy.action)
    }

    /**
     * UI_HIDDEN and the levels above it (BACKGROUND and up) mean the process is no longer the one the
     * user is looking at; the RUNNING_* levels are deprecated from API 34 and ignored. Only what
     * rebuilds itself is released, so a composition in progress survives.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) controller?.trimMemory()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && controller?.closePanels() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(showSettingsAction)
        clipboardRecorder.stop()
        unregisterReceiver(deployReceiver)
        unregisterReceiver(appearanceReceiver)
        unregisterReceiver(quickContentReceiver)
        runtime?.shutdown()
        updatePanelBack(false)
        controller?.retire()
        ProcessTextRelay.detach(processTextHost)
        scope.cancel()
        super.onDestroy()
        viewOwners.destroy()
    }
}

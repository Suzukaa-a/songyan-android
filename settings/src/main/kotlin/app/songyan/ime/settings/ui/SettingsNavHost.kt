package app.songyan.ime.settings.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.settings.R
import app.songyan.ime.settings.platform.SystemAction
import app.songyan.ime.settings.platform.openInputMethodSettings
import app.songyan.ime.settings.platform.shareText
import app.songyan.ime.settings.platform.showInputMethodPicker
import app.songyan.ime.settings.state.DiagnosticsHolder
import app.songyan.ime.settings.state.EngineRecoveryNotice
import app.songyan.ime.settings.state.familiesOfSchemes
import app.songyan.ime.settings.ui.components.GrammarModelActions
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.screens.AboutScreen
import app.songyan.ime.settings.ui.screens.AddSchemeScreen
import app.songyan.ime.settings.ui.screens.AppearanceScreen
import app.songyan.ime.settings.ui.screens.CandidateBarScreen
import app.songyan.ime.settings.ui.screens.CompositionScreen
import app.songyan.ime.settings.ui.screens.EngineFeaturesScreen
import app.songyan.ime.settings.ui.screens.GrammarModelCardInput
import app.songyan.ime.settings.ui.screens.HomeScreen
import app.songyan.ime.settings.ui.screens.InputOptionsScreen
import app.songyan.ime.settings.ui.screens.KeyboardMenuScreen
import app.songyan.ime.settings.ui.screens.KeyboardsScreen
import app.songyan.ime.settings.ui.screens.LayoutEditorScreen
import app.songyan.ime.settings.ui.screens.LayoutsScreen
import app.songyan.ime.settings.ui.screens.LearningBackupScreen
import app.songyan.ime.settings.ui.screens.LicenceDetailRoute
import app.songyan.ime.settings.ui.screens.LicensesScreen
import app.songyan.ime.settings.ui.screens.OnboardingScreen
import app.songyan.ime.settings.ui.screens.PageEditorScreen
import app.songyan.ime.settings.ui.screens.PagesScreen
import app.songyan.ime.settings.ui.screens.QuickContentScreen
import app.songyan.ime.settings.ui.screens.RimeFileEditorScreen
import app.songyan.ime.settings.ui.screens.SchemeDetailScreen
import app.songyan.ime.settings.ui.screens.SchemesScreen
import app.songyan.ime.settings.ui.screens.ShuangpinHintScreen
import app.songyan.ime.settings.ui.screens.ToolbarScreen
import app.songyan.ime.settings.ui.screens.TryOutScreen
import app.songyan.ime.settings.ui.screens.compositionKeyOf
import kotlinx.coroutines.launch

@Composable
private fun grammarModelInput(navController: NavHostController): GrammarModelCardInput {
    val owner = LocalActivity.current as? ComponentActivity
    val model: EngineFeaturesViewModel =
        if (owner != null) {
            viewModel(viewModelStoreOwner = owner, factory = EngineFeaturesViewModel.Factory())
        } else {
            viewModel(factory = EngineFeaturesViewModel.Factory())
        }
    val state by model.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.reload() }
    return GrammarModelCardInput(
        state = state,
        actions =
            GrammarModelActions(
                onDownload = model::importGrammarModelFromUrl,
                onPickFile = {},
                onCancel = model::cancelDownload,
                onRetry = model::retry,
                onDismissFailure = model::consumeFailure,
                onOpenFeatures = { navController.navigate(SettingsRoutes.FEATURES) },
                onDismiss = model::consumeGrammarModelImported,
            ),
        onImportFile = model::importGrammarModel,
    )
}

/** Routes; plain strings keep the graph readable and the back stack debuggable. */
object SettingsRoutes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val SCHEMES = "schemes"
    const val SCHEME_DETAIL = "schemes/{id}"
    const val ADD_SCHEME = "addScheme"
    const val KEYBOARDS = "keyboards"
    const val APPEARANCE = "appearance"
    const val OPTIONS = "options"
    const val FEATURES = "features"
    const val CANDIDATE_BAR = "candidateBar"
    const val TOOLBAR = "toolbar"
    const val SHUANGPIN = "shuangpinHints"
    const val T9_SYMBOLS = "t9QuickSymbols"
    const val NINE_KEY_EDITOR = "nineKeyEditor"
    const val KEYBOARD_MENU = "keyboardMenu"
    const val LAYOUTS = "layouts"
    const val LAYOUT_EDITOR = "layouts/edit/{id}"
    const val PAGES = "pages"
    const val PAGE_EDITOR = "pages/edit/{id}"
    const val COMPOSITION = "composition"
    const val USE_CONTENT = "useContent/{kind}/{id}"

    fun useContent(
        id: String,
        page: Boolean = false,
    ): String = "useContent/" + (if (page) "page" else "layout") + "/" + Uri.encode(id)

    const val QUICK_CONTENT = "quickContent?addPhrase={addPhrase}"
    const val TRY_OUT = "tryOut"
    const val LEARNING = "learning"
    const val ABOUT = "about"
    const val RIME_FILE = "rimeFile/{key}?grammar={grammar}"
    const val LICENSES = "licenses"
    const val LICENSE_DETAIL = "licenses/{index}"

    /** The home-list entry a route belongs to: sub-pages highlight the section they came from. */
    fun topLevelOf(route: String?): String? =
        when (route) {
            null, HOME -> null
            FEATURES, CANDIDATE_BAR, TOOLBAR, SHUANGPIN -> OPTIONS
            LAYOUTS,
            LAYOUT_EDITOR,
            PAGES,
            PAGE_EDITOR,
            COMPOSITION,
            USE_CONTENT,
            KEYBOARD_MENU,
            T9_SYMBOLS,
            NINE_KEY_EDITOR,
            -> KEYBOARDS
            SCHEME_DETAIL, ADD_SCHEME -> SCHEMES
            LICENSES, LICENSE_DETAIL -> ABOUT
            else -> route
        }

    fun quickContent(addPhrase: Boolean = false): String = "quickContent?addPhrase=$addPhrase"

    fun licenseDetail(index: Int): String = "licenses/$index"

    fun schemeDetail(sourceId: String): String = "schemes/" + Uri.encode(sourceId)

    /** The in-app editor for the user's file of an input method; [grammar] adds the commented grammar example. */
    fun rimeFile(
        key: String,
        grammar: Boolean = false,
    ): String = "rimeFile/" + android.net.Uri.encode(key) + "?grammar=" + grammar

    /** Ids may hold any character (an imported layout names its own), so they travel URL-encoded. */
    fun layoutEditor(id: String): String = "layouts/edit/" + android.net.Uri.encode(id)

    fun pageEditor(id: String): String = "pages/edit/" + android.net.Uri.encode(id)

    /** The page a keyboard request lands on. */
    fun of(target: SettingsTarget): String =
        when (target) {
            SettingsTarget.HOME -> HOME
            SettingsTarget.QUICK_CONTENT -> quickContent()
            SettingsTarget.ADD_PHRASE -> quickContent(addPhrase = true)
        }
}

/**
 * Opens the add page on top of the input scheme list, so starting a download or import lands on
 * the list, whose card shows its progress, and Back from the add page goes there too.
 */
private fun NavHostController.openAddScheme() {
    navigate(SettingsRoutes.SCHEMES) { launchSingleTop = true }
    navigate(SettingsRoutes.ADD_SCHEME) { launchSingleTop = true }
}

/** After a download or import starts: back to the list beneath, or onto it when the add page was opened alone (search). */
private fun NavHostController.showSchemesAfterAdding() {
    if (popBackStack(SettingsRoutes.SCHEMES, inclusive = false)) return
    navigate(SettingsRoutes.SCHEMES) { popUpTo(SettingsRoutes.ADD_SCHEME) { inclusive = true } }
}

/**
 * Whether the settings are drawn as list and detail side by side. Both layouts present the same
 * navigation graph and back stack; only how the home destination and the back arrows look differs.
 */
private val LocalTwoPane = staticCompositionLocalOf { false }

/**
 * Where a top-level page goes back to. Top-level pages have no back arrow in the two-pane layout
 * (the list beside them is the way back); sub-pages always do.
 */
@Composable
private fun topBack(navController: NavHostController): (() -> Unit)? =
    if (LocalTwoPane.current) null else ({ navController.popBackStack() })

/**
 * The one settings graph. Home is the list on a narrow window; beside the list it is the empty
 * detail, so a back stack built in either layout means the same pages in the other.
 */
private fun NavGraphBuilder.settingsGraph(
    viewModel: SettingsViewModel,
    state: State<SettingsUiState>,
    versionName: String?,
    navController: NavHostController,
    homeQuery: MutableState<String>,
) {
    composable(SettingsRoutes.HOME) {
        if (LocalTwoPane.current) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StateMessage(
                    title = stringResource(R.string.detail_empty_title),
                    icon = Icons.Outlined.Settings,
                )
            }
        } else {
            HomeScreen(
                state = state.value,
                onOpen = { route -> navController.navigate(route) },
                onRetryImeStatus = viewModel::refresh,
                onCancelImport = { kind -> cancelImport(viewModel, kind) },
                onRetryImport = { kind -> retryImport(viewModel, kind) },
                onDismissDictionaryImport = viewModel::dismissDictionaryImport,
                onDismissSchemeImport = viewModel::dismissSchemeImport,
                onAnswerSchemeLearning = { accept ->
                    if (accept) viewModel.mergePackageLearning() else viewModel.declinePackageLearning()
                },
                queryState = homeQuery,
            )
        }
    }
    composable(SettingsRoutes.ONBOARDING) {
        val context = LocalContext.current
        val openFailed = stringResource(R.string.settings_open_failed)
        OnboardingScreen(
            progress = state.value.progress,
            onOpenInputMethodSettings = {
                if (openInputMethodSettings(context) == SystemAction.FAILED) {
                    Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                }
            },
            onShowPicker = {
                if (showInputMethodPicker(context) == SystemAction.FAILED) {
                    Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                }
            },
            onRetry = viewModel::refresh,
            onTyped = viewModel::onTypedInTryOut,
            onBack = topBack(navController),
            schemeImport = state.value.schemeImport,
            downloadAvailable = state.value.schemeImportAvailable,
            onDownload = viewModel::importRecommendedScheme,
            onOtherSchemes = { navController.openAddScheme() },
            onCancelDownload = viewModel::cancelSchemeImport,
            onRetryDownload = viewModel::retrySchemeImport,
            onDismissDownload = viewModel::dismissSchemeImport,
            onAnswerSchemeLearning = { accept ->
                if (accept) viewModel.mergePackageLearning() else viewModel.declinePackageLearning()
            },
        )
    }

    composable(SettingsRoutes.SCHEMES) {
        // Owned by the activity: applying keeps going, and its outcome still shows, after the user
        // leaves the screen. The folder is edited in another app, so it is read again on every return.
        val owner = LocalActivity.current as? ComponentActivity
        val rimeConfigModel: RimeConfigViewModel =
            if (owner != null) {
                viewModel(viewModelStoreOwner = owner, factory = RimeConfigViewModel.Factory())
            } else {
                viewModel(factory = RimeConfigViewModel.Factory())
            }
        val rimeConfig = rimeConfigModel.uiState.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { rimeConfigModel.refresh() }
        // The same activity-owned model the 纠错与联想 page uses, so a model downloaded from the card
        // there is the one that page lists, and a download keeps going while the user looks elsewhere.
        val featuresModel: EngineFeaturesViewModel =
            if (owner != null) {
                viewModel(viewModelStoreOwner = owner, factory = EngineFeaturesViewModel.Factory())
            } else {
                viewModel(factory = EngineFeaturesViewModel.Factory())
            }
        val features = featuresModel.uiState.collectAsStateWithLifecycle()
        SchemesScreen(
            schemes = state.value.schemes,
            inUseSchemeId = state.value.inUseSchemeId,
            importedDictionary = state.value.importedDictionary,
            dictionaryImport = state.value.dictionaryImport,
            dictionaryImportAvailable = state.value.dictionaryImportAvailable,
            onImportDictionaryFile = viewModel::importDictionary,
            onImportDictionaryFromUrl = viewModel::importDictionaryFromUrl,
            onRemoveImportedDictionary = viewModel::removeImportedDictionary,
            onDismissDictionaryImport = viewModel::dismissDictionaryImport,
            schemeImport = state.value.schemeImport,
            onDismissSchemeImport = viewModel::dismissSchemeImport,
            onAnswerSchemeLearning = { accept ->
                if (accept) viewModel.mergePackageLearning() else viewModel.declinePackageLearning()
            },
            onCancelImport = { kind -> cancelImport(viewModel, kind) },
            onRetryImport = { kind -> retryImport(viewModel, kind) },
            onUseScheme = viewModel::useScheme,
            onBack = topBack(navController),
            sources = state.value.sources,
            sourceAction = state.value.sourceAction,
            onOpenSource = { id -> navController.navigate(SettingsRoutes.schemeDetail(id)) },
            onDismissSourceAction = viewModel::dismissSourceAction,
            stackedDictionaries = state.value.stackedDictionaries,
            onAddScheme = { navController.navigate(SettingsRoutes.ADD_SCHEME) },
            onRestoreSource = viewModel::restoreSource,
            incomingPackage = state.value.incomingPackage,
            onConfirmIncomingPackage = viewModel::importIncomingPackage,
            onDismissIncomingPackage = viewModel::dismissIncomingPackage,
            rimeConfig = rimeConfig.value,
            rimeConfigIntents = rimeConfigModel::openFolderIntents,
            onRimeConfigNoFilesApp = rimeConfigModel::noFilesApp,
            onApplyRimeConfig = rimeConfigModel::apply,
            onDismissRimeConfigMessage = rimeConfigModel::dismissMessage,
            onOpenRimeEditor = { key -> navController.navigate(SettingsRoutes.rimeFile(key)) },
            onRestoreRimeFile = rimeConfigModel::restore,
            onDismissRimeOverrideNotice = rimeConfigModel::dismissOverrideNotice,
            onCreateRimeScript = rimeConfigModel::createScript,
            onRimeEditorOpened = rimeConfigModel::editorOpened,
            onUseRimeScripts = rimeConfigModel::confirmScripts,
            onCancelRimeScripts = rimeConfigModel::cancelScripts,
            grammarModel =
                GrammarModelCardInput(
                    state = features.value,
                    actions =
                        GrammarModelActions(
                            onDownload = featuresModel::importGrammarModelFromUrl,
                            onPickFile = {},
                            onCancel = featuresModel::cancelDownload,
                            onRetry = featuresModel::retry,
                            onDismissFailure = featuresModel::consumeFailure,
                            onOpenFeatures = { navController.navigate(SettingsRoutes.FEATURES) },
                            onDismiss = featuresModel::consumeGrammarModelImported,
                        ),
                    onImportFile = featuresModel::importGrammarModel,
                ),
        )
    }

    composable(SettingsRoutes.ADD_SCHEME) {
        AddSchemeScreen(
            importAvailable = state.value.schemeImportAvailable,
            busy =
                state.value.schemeImport is SchemeImportUiState.Running ||
                    state.value.dictionaryImport is DictionaryImportUiState.Running,
            onImportScheme = viewModel::importScheme,
            onImportSchemeFolder = viewModel::importSchemeFolder,
            onImportSchemeFromLink = viewModel::importSchemeFromLink,
            onImportRecommendedScheme = viewModel::importRecommendedScheme,
            onStarted = { navController.showSchemesAfterAdding() },
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = SettingsRoutes.SCHEME_DETAIL,
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        SchemeDetailScreen(
            sourceId = entry.arguments?.getString("id").orEmpty(),
            sources = state.value.sources,
            sourceAction = state.value.sourceAction,
            schemeImport = state.value.schemeImport,
            dictionaryImport = state.value.dictionaryImport,
            onUse = viewModel::useSource,
            onSelectMethod = viewModel::useScheme,
            onImportScheme = viewModel::importScheme,
            grammarModel = grammarModelInput(navController),
            onUpdate = viewModel::updateScheme,
            onDelete = viewModel::deleteSource,
            onDismissSourceAction = viewModel::dismissSourceAction,
            onCancelImport = { kind -> cancelImport(viewModel, kind) },
            onRetryImport = { kind -> retryImport(viewModel, kind) },
            onDismissSchemeImport = viewModel::dismissSchemeImport,
            onAnswerSchemeLearning = { accept ->
                if (accept) viewModel.mergePackageLearning() else viewModel.declinePackageLearning()
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(SettingsRoutes.KEYBOARDS) {
        if (!state.value.preferencesLoaded) {
            LoadingScreen(stringResource(R.string.row_keyboards_title), topBack(navController))
        } else {
            KeyboardsScreen(
                inputMethodName = state.value.inUseScheme?.name,
                layoutId = state.value.layoutId,
                mainLayout = state.value.mainLayout,
                supportedLayouts = state.value.inUseScheme?.supportedLayouts,
                onSelectLayout = viewModel::selectLayout,
                boards =
                    state.value.inUseScheme
                        ?.family
                        ?.let(InputLayoutPolicy::boardsOf)
                        .orEmpty(),
                onSelectBoard = viewModel::selectBoard,
                onOpenLayouts = { navController.navigate(SettingsRoutes.LAYOUTS) },
                onOpenPages = { navController.navigate(SettingsRoutes.PAGES) },
                onOpenComposition = { navController.navigate(SettingsRoutes.COMPOSITION) },
                onOpenSchemes = { navController.openAddScheme() },
                onOpenMenu = { navController.navigate(SettingsRoutes.KEYBOARD_MENU) },
                onBack = topBack(navController),
            )
        }
    }

    composable(SettingsRoutes.LAYOUTS) {
        LayoutsScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(SettingsRoutes.layoutEditor(id)) },
            onUse = { id -> navController.navigate(SettingsRoutes.useContent(id)) },
            onOpenPages = { navController.navigate(SettingsRoutes.PAGES) },
            showSwitchKey = state.value.keyboardFeel.showSwitchKey,
            onShowSwitchKeyChange = viewModel::setShowSwitchKey,
            switchKeyEnabled = state.value.preferencesLoaded,
        )
    }

    composable(
        route = SettingsRoutes.LAYOUT_EDITOR,
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        LayoutEditorScreen(
            layoutId = entry.arguments?.getString("id").orEmpty(),
            onUse = { id -> navController.navigate(SettingsRoutes.useContent(id)) },
            onClose = { navController.popBackStack() },
        )
    }

    composable(
        route = SettingsRoutes.RIME_FILE,
        arguments =
            listOf(
                navArgument("key") { type = NavType.StringType },
                navArgument("grammar") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) { entry ->
        // The apply runs in the activity-owned model, so it carries on after this page closes.
        val owner = LocalActivity.current as? ComponentActivity
        val rimeConfigModel: RimeConfigViewModel =
            if (owner != null) {
                viewModel(viewModelStoreOwner = owner, factory = RimeConfigViewModel.Factory())
            } else {
                viewModel(factory = RimeConfigViewModel.Factory())
            }
        RimeFileEditorScreen(
            key = entry.arguments?.getString("key").orEmpty(),
            grammarExample = entry.arguments?.getBoolean("grammar") ?: false,
            onClose = { navController.popBackStack() },
            onApply = rimeConfigModel::apply,
        )
    }

    composable(SettingsRoutes.PAGES) {
        PagesScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(SettingsRoutes.pageEditor(id)) },
            onUse = { id -> navController.navigate(SettingsRoutes.useContent(id, page = true)) },
            inputMethodNames = BuiltinTemplateSchemas.NAMES + state.value.schemes.associate { it.id to it.name },
            families = familiesOfSchemes(state.value.schemes),
        )
    }

    composable(
        route = SettingsRoutes.PAGE_EDITOR,
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        PageEditorScreen(
            pageId = entry.arguments?.getString("id").orEmpty(),
            onUse = { id -> navController.navigate(SettingsRoutes.useContent(id, page = true)) },
            onClose = { navController.popBackStack() },
        )
    }

    composable(
        SettingsRoutes.USE_CONTENT,
        arguments =
            listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") {
                    type =
                        NavType.StringType
                },
            ),
    ) { entry ->
        app.songyan.ime.settings.ui.screens.UseContentScreen(
            contentId = entry.arguments?.getString("id").orEmpty(),
            isPage = entry.arguments?.getString("kind") == "page",
            schemes = state.value.schemes,
            sourcesLoaded = state.value.sourcesLoaded,
            onActivate = viewModel::activateKeyboard,
            onTryOut = { navController.navigate(SettingsRoutes.TRY_OUT) },
            onOpenSchemes = { navController.openAddScheme() },
            onChooseScheme = { navController.navigate(SettingsRoutes.SCHEMES) { launchSingleTop = true } },
            engine = state.value.engine,
            onBack = { navController.popBackStack() },
        )
    }

    composable(SettingsRoutes.COMPOSITION) {
        val key = compositionKeyOf(state.value.inUseSchemeId, state.value.layoutId)
        if (!state.value.sourcesLoaded && key == null) {
            LoadingScreen(stringResource(R.string.composition_title), topBack(navController))
        } else if (key == null) {
            app.songyan.ime.settings.ui.screens.CompositionEmptyScreen(
                engine = state.value.engine,
                hasSchemes = state.value.schemes.isNotEmpty(),
                onBack = topBack(navController),
                onAddScheme = { navController.openAddScheme() },
                onChooseScheme = { navController.navigate(SettingsRoutes.SCHEMES) { launchSingleTop = true } },
            )
        } else {
            CompositionScreen(
                key = key,
                families =
                    state.value.inUseScheme?.let { LayoutFamilyContext.of(it.id to it.family) }
                        ?: LayoutFamilyContext.UNKNOWN,
                keyboardName =
                    stringResource(
                        R.string.keyboard_title,
                        state.value.inUseScheme?.name ?: key.inputMethodId,
                        state.value.mainLayout
                            ?.displayName
                            ?.text()
                            ?: stringResource(state.value.currentLayout.nameRes),
                    ),
                onBack = { navController.popBackStack() },
            )
        }
    }

    composable(SettingsRoutes.APPEARANCE) {
        AppearanceScreen(
            onBack = topBack(navController),
            onOpenCandidateBar = { navController.navigate(SettingsRoutes.CANDIDATE_BAR) },
        )
    }

    composable(SettingsRoutes.OPTIONS) {
        if (!state.value.preferencesLoaded) {
            LoadingScreen(stringResource(R.string.row_options_title), topBack(navController))
        } else {
            val engineOptionsModel: EngineOptionsViewModel = viewModel(factory = EngineOptionsViewModel.Factory())
            val engineOptions by engineOptionsModel.uiState.collectAsStateWithLifecycle()
            val optionsSchema = state.value.inUseSchemeId
            LaunchedEffect(optionsSchema) { engineOptionsModel.show(optionsSchema) }
            // Back from the features page or another app: the stored choices may have moved on.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { engineOptionsModel.reload() }
            InputOptionsScreen(
                engineOptions = engineOptions,
                onEngineOptionChanged = engineOptionsModel::set,
                keyFeedback = state.value.keyFeedback,
                onKeyFeedbackChanged = viewModel::setKeyFeedback,
                keyboardFeel = state.value.keyboardFeel,
                onKeyHeightChanged = viewModel::setKeyHeight,
                onBottomGapChanged = viewModel::setBottomGapDp,
                onLongPressDelayChanged = viewModel::setLongPressDelayMs,
                onOneHandChanged = viewModel::setOneHand,
                onOneHandWidthChanged = viewModel::setOneHandWidthPercent,
                onKeyPreviewChanged = viewModel::setKeyPreview,
                onKeyFlickDirection = viewModel::setKeyFlickDirection,
                onDoubleSpacePeriodChanged = viewModel::setDoubleSpacePeriod,
                onReleaseHapticChanged = viewModel::setReleaseHaptic,
                onWidePositionChanged = viewModel::setWidePosition,
                onWidePositionCustomChanged = viewModel::setWidePositionCustom,
                onSplitModeChanged = viewModel::setSplitMode,
                onSplitWidthChanged = viewModel::setSplitWidthDp,
                onSideMarginChanged = viewModel::setSideMarginPerMille,
                onReadingPlacementChanged = viewModel::setReadingPlacement,
                onBack = topBack(navController),
                onOpenFeatures = { navController.navigate(SettingsRoutes.FEATURES) },
                onOpenToolbar = { navController.navigate(SettingsRoutes.TOOLBAR) },
                onOpenShuangpinHints = { navController.navigate(SettingsRoutes.SHUANGPIN) },
            )
        }
    }

    composable(
        route = SettingsRoutes.QUICK_CONTENT,
        arguments =
            listOf(
                navArgument("addPhrase") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) { entry ->
        QuickContentScreen(
            onBack = topBack(navController),
            startWithAddPhrase = entry.arguments?.getBoolean("addPhrase") == true,
        )
    }

    composable(SettingsRoutes.CANDIDATE_BAR) {
        CandidateBarScreen(
            hideIdleToolbar = state.value.keyboardFeel.hideIdleToolbar,
            onHideIdleToolbarChanged = viewModel::setHideIdleToolbar,
            candidateTextSize = state.value.candidateTextSize.takeIf { state.value.preferencesLoaded },
            onCandidateTextSizeChanged = viewModel::setCandidateTextSize,
            collapsedComments = state.value.collapsedCandidateComments.takeIf { state.value.preferencesLoaded },
            onCollapsedCommentsChanged = viewModel::setCollapsedCandidateComments,
            onBack = { navController.popBackStack() },
        )
    }

    composable(SettingsRoutes.TOOLBAR) {
        ToolbarScreen(onBack = { navController.popBackStack() })
    }

    composable(SettingsRoutes.KEYBOARD_MENU) {
        KeyboardMenuScreen(
            schemes = state.value.schemes,
            inUseSchemeId = state.value.inUseSchemeId,
            layoutId = state.value.layoutId,
            otherSources = state.value.otherSources,
            sourceName =
                state.value.sources
                    .firstOrNull { it.inUse }
                    ?.name,
            mainLayout = state.value.mainLayout,
            supportedLayouts = state.value.inUseScheme?.supportedLayouts,
            boards =
                state.value.inUseScheme
                    ?.family
                    ?.let(InputLayoutPolicy::boardsOf)
                    .orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(SettingsRoutes.T9_SYMBOLS) {
        LaunchedEffect(Unit) {
            navController.navigate(SettingsRoutes.NINE_KEY_EDITOR) {
                popUpTo(SettingsRoutes.T9_SYMBOLS) {
                    inclusive =
                        true
                }
            }
        }
    }
    composable(SettingsRoutes.NINE_KEY_EDITOR) {
        val library: LayoutLibraryViewModel = viewModel(factory = LayoutLibraryViewModel.Factory())
        val registry by library.registry.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { library.refresh() }
        LaunchedEffect(registry, state.value.inUseSchemeId, state.value.sourcesLoaded) {
            if (!state.value.sourcesLoaded && state.value.inUseSchemeId == null) return@LaunchedEffect
            val catalog = registry ?: return@LaunchedEffect
            val key = compositionKeyOf(state.value.inUseSchemeId, "phone9")
            val families =
                state.value.inUseScheme?.let { LayoutFamilyContext.of(it.id to it.family) }
                    ?: LayoutFamilyContext.UNKNOWN
            val paired = key?.let { catalog.resolvedLayout(catalog.composition(it, families).mainLayoutID) }
            val id =
                paired
                    ?.takeIf {
                        app.songyan.ime.layout.PhoneKeypadStructure
                            .matches(it)
                    }?.id ?: "phone9"
            navController.navigate(SettingsRoutes.layoutEditor(id)) {
                popUpTo(SettingsRoutes.NINE_KEY_EDITOR) {
                    inclusive =
                        true
                }
            }
        }
        LoadingScreen(stringResource(R.string.layout_editor_title), { navController.popBackStack() })
    }

    composable(SettingsRoutes.SHUANGPIN) {
        ShuangpinHintScreen(onBack = { navController.popBackStack() })
    }

    composable(SettingsRoutes.FEATURES) {
        // Owned by the activity, not this page: a download keeps showing its progress and its
        // outcome after the user leaves the page and comes back.
        val owner = LocalActivity.current as? ComponentActivity
        EngineFeaturesScreen(
            schemaId = state.value.inUseSchemeId,
            schemaName = state.value.inUseScheme?.name,
            onBack = { navController.popBackStack() },
            viewModel =
                if (owner != null) {
                    viewModel(viewModelStoreOwner = owner, factory = EngineFeaturesViewModel.Factory())
                } else {
                    viewModel(factory = EngineFeaturesViewModel.Factory())
                },
        )
    }

    composable(SettingsRoutes.LEARNING) {
        LearningBackupScreen(onBack = topBack(navController))
    }

    composable(SettingsRoutes.TRY_OUT) {
        val context = LocalContext.current
        val openFailed = stringResource(R.string.settings_open_failed)
        TryOutScreen(
            ime = state.value.ime,
            japanese = state.value.inUseScheme?.family == InputSchemeFamily.JAPANESE,
            onTyped = viewModel::onTypedInTryOut,
            onEnableIme = {
                if (openInputMethodSettings(context) == SystemAction.FAILED) {
                    Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
                }
            },
            onBack = topBack(navController),
        )
    }

    composable(SettingsRoutes.ABOUT) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val failed = stringResource(R.string.about_diagnostics_failed)
        val subject = stringResource(R.string.about_diagnostics_subject)
        val chooser = stringResource(R.string.about_diagnostics_chooser)
        val recovery by produceState<EngineRecoveryNotice?>(null) { value = DiagnosticsHolder.current.engineRecovery() }
        AboutScreen(
            versionName = versionName,
            engineRecovery = recovery,
            onOpenLearning = { navController.navigate(SettingsRoutes.LEARNING) },
            onOpenLicenses = { navController.navigate(SettingsRoutes.LICENSES) },
            onBack = topBack(navController),
            onShareDiagnostics =
                if (DiagnosticsHolder.current.available) {
                    {
                        scope.launch {
                            val report = DiagnosticsHolder.current.report()
                            if (report == null || shareText(context, subject, report, chooser) == SystemAction.FAILED) {
                                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    null
                },
        )
    }

    composable(SettingsRoutes.LICENSES) {
        LicensesScreen(
            onOpen = { index -> navController.navigate(SettingsRoutes.licenseDetail(index)) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = SettingsRoutes.LICENSE_DETAIL,
        arguments = listOf(navArgument("index") { type = NavType.IntType }),
    ) { entry ->
        LicenceDetailRoute(
            index = entry.arguments?.getInt("index") ?: 0,
            onBack = { navController.popBackStack() },
        )
    }
}

/** Reads the keyboard selection again when the user comes back from system settings, and opens the first-run guide. */
@Composable
private fun SettingsEffects(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    navController: NavHostController,
    target: SettingsTarget?,
    onTargetHandled: () -> Unit,
) {
    // Read the system keyboard selection again whenever the user comes back from system
    // settings, so the home banner never keeps an outdated "not the default keyboard" state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val editorNavigation = LocalEditorNavigationGuard.current
    LaunchedEffect(target) {
        if (target != null) {
            val navigate = {
                navController.popBackStack(SettingsRoutes.HOME, inclusive = false)
                if (target != SettingsTarget.HOME) navController.navigate(SettingsRoutes.of(target))
                Unit
            }
            if (editorNavigation != null) editorNavigation.request(navigate) else navigate()
            onTargetHandled()
        }
    }

    // A package another app handed over: show it on the page that asks before importing.
    LaunchedEffect(state.incomingPackage) {
        if (state.incomingPackage != null) {
            val navigate = {
                navController.popBackStack(SettingsRoutes.HOME, inclusive = false)
                navController.navigate(SettingsRoutes.SCHEMES)
            }
            if (editorNavigation != null) editorNavigation.request(navigate) else navigate()
        }
    }

    // First launch: the guide opens by itself once, unless the keyboard is already set up or the
    // user came here from the keyboard for something specific.
    LaunchedEffect(state.preferencesLoaded, state.onboardingShown, target) {
        if (state.preferencesLoaded && !state.onboardingShown) {
            viewModel.markOnboardingShown()
            if (target == null && !(state.ime.enabled && state.ime.selected)) {
                navController.navigate(SettingsRoutes.ONBOARDING)
            }
        }
    }
}

/** The settings on a narrow window: one page at a time, the home list first. */
@Composable
fun SettingsNavHost(
    viewModel: SettingsViewModel,
    versionName: String?,
    target: SettingsTarget? = null,
    onTargetHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) = SettingsHost(
    viewModel = viewModel,
    versionName = versionName,
    target = target,
    onTargetHandled = onTargetHandled,
    adaptiveInfo =
        WindowAdaptiveInfo(
            WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP),
            Posture(),
        ),
    navController = navController,
)

/** A phone-sized window for [SettingsNavHost]: only the width class matters to the layout. */
private const val COMPACT_WIDTH_DP = 400
private const val COMPACT_HEIGHT_DP = 800

/**
 * The settings in a Material list-detail scaffold. With room for two panes the home list stays on
 * the left and the page on top of the back stack fills the right; with room for one, only the
 * detail pane shows and its home destination is the list itself. The back stack and the page
 * content always live in the same navigation host at the same place in the composition, so a
 * rotation, fold or resize across the switch keeps the page, its saved state and its focus.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SettingsPanes(
    viewModel: SettingsViewModel,
    state: State<SettingsUiState>,
    versionName: String?,
    adaptiveInfo: WindowAdaptiveInfo,
    navController: NavHostController,
) {
    // A medium window shows one pane of a list-detail scaffold at a time, so the pane count, not
    // the width class, decides whether the list stands beside the page. The scaffold would also
    // focus the first focusable in its current pane whenever it is composed, and on home that is
    // the search field: every launch, rotation or fold would raise the keyboard by itself.
    val directive =
        calculatePaneScaffoldDirective(adaptiveInfo).let {
            PaneScaffoldDirective(
                maxHorizontalPartitions = it.maxHorizontalPartitions,
                horizontalPartitionSpacerSize = it.horizontalPartitionSpacerSize,
                maxVerticalPartitions = it.maxVerticalPartitions,
                verticalPartitionSpacerSize = it.verticalPartitionSpacerSize,
                defaultPanePreferredWidth = it.defaultPanePreferredWidth,
                defaultPanePreferredHeight = it.defaultPanePreferredHeight,
                excludedBounds = it.excludedBounds,
                shouldAutoFocusCurrentDestination = false,
            )
        }
    val twoPane = directive.maxHorizontalPartitions >= 2
    val editorNavigation = LocalEditorNavigationGuard.current
    // Home is drawn in a different place in each layout, so its search text lives here, above both.
    val homeQuery = rememberSaveable { mutableStateOf("") }
    val entry by navController.currentBackStackEntryAsState()
    val selected = SettingsRoutes.topLevelOf(entry?.destination?.route)
    val slidePx = with(LocalDensity.current) { SettingsMotion.SLIDE_DP.dp.roundToPx() }
    // The detail pane is always the destination: on one pane it is the only one shown.
    val navigator =
        rememberListDetailPaneScaffoldNavigator<Nothing>(
            scaffoldDirective = directive,
            initialDestinationHistory = listOf(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.Detail)),
        )
    CompositionLocalProvider(LocalTwoPane provides twoPane) {
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            // The gap between the panes is the scaffold's own and would show the window behind it.
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            listPane = {
                AnimatedPane {
                    if (twoPane) {
                        HomeScreen(
                            state = state.value,
                            onOpen = { route ->
                                val navigate = {
                                    navController.navigate(route) {
                                        popUpTo(SettingsRoutes.HOME)
                                        launchSingleTop = true
                                    }
                                }
                                if (editorNavigation != null) editorNavigation.request(navigate) else navigate()
                            },
                            onRetryImeStatus = viewModel::refresh,
                            onCancelImport = { kind -> cancelImport(viewModel, kind) },
                            onRetryImport = { kind -> retryImport(viewModel, kind) },
                            onDismissDictionaryImport = viewModel::dismissDictionaryImport,
                            onDismissSchemeImport = viewModel::dismissSchemeImport,
                            onAnswerSchemeLearning = { accept ->
                                if (accept) viewModel.mergePackageLearning() else viewModel.declinePackageLearning()
                            },
                            selectedRoute = selected,
                            queryState = homeQuery,
                        )
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    NavHost(
                        navController = navController,
                        startDestination = SettingsRoutes.HOME,
                        // Beside the list a page cross-fades; on its own it slides along the shared axis.
                        enterTransition = {
                            if (twoPane) {
                                fadeIn(tween(SettingsMotion.DURATION_MS))
                            } else {
                                SettingsMotion.enter(slidePx, forward = true)
                            }
                        },
                        exitTransition = {
                            if (twoPane) {
                                fadeOut(tween(SettingsMotion.FADE_OUT_MS))
                            } else {
                                SettingsMotion.exit(slidePx, forward = true)
                            }
                        },
                        popEnterTransition = {
                            if (twoPane) {
                                fadeIn(tween(SettingsMotion.DURATION_MS))
                            } else {
                                SettingsMotion.enter(slidePx, forward = false)
                            }
                        },
                        popExitTransition = {
                            if (twoPane) {
                                fadeOut(tween(SettingsMotion.FADE_OUT_MS))
                            } else {
                                SettingsMotion.exit(slidePx, forward = false)
                            }
                        },
                        // A back swipe would otherwise get the library's shrinking page; it scrubs the same motion instead.
                        predictivePopEnterTransition = { _ ->
                            if (twoPane) {
                                fadeIn(tween(SettingsMotion.DURATION_MS))
                            } else {
                                SettingsMotion.enter(slidePx, forward = false)
                            }
                        },
                        predictivePopExitTransition = { _ ->
                            if (twoPane) {
                                fadeOut(tween(SettingsMotion.FADE_OUT_MS))
                            } else {
                                SettingsMotion.exit(slidePx, forward = false)
                            }
                        },
                    ) {
                        settingsGraph(viewModel, state, versionName, navController, homeQuery)
                    }
                }
            },
        )
    }
}

/**
 * Picks the layout for the window: list and detail side by side when the Material scaffold has room
 * for two panes (the expanded width class), one page at a time otherwise. [target] is a page the
 * keyboard asked for; it replaces whatever was open, so the user lands where they asked to, not on
 * the page they happened to leave last. It is consumed through [onTargetHandled] once navigated.
 */
@Composable
fun SettingsHost(
    viewModel: SettingsViewModel,
    versionName: String?,
    target: SettingsTarget? = null,
    onTargetHandled: () -> Unit = {},
    adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo(),
    navController: NavHostController = rememberNavController(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val editorNavigation = remember { EditorNavigationController() }
    CompositionLocalProvider(LocalEditorNavigationGuard provides editorNavigation) {
        SettingsEffects(viewModel, state.value, navController, target, onTargetHandled)
        SettingsPanes(viewModel, state, versionName, adaptiveInfo, navController)
        EditorNavigationConfirmation(editorNavigation)
    }
}

private fun cancelImport(
    viewModel: SettingsViewModel,
    kind: ImportKind,
) = if (kind == ImportKind.DICTIONARY) viewModel.cancelDictionaryImport() else viewModel.cancelSchemeImport()

private fun retryImport(
    viewModel: SettingsViewModel,
    kind: ImportKind,
) = if (kind == ImportKind.DICTIONARY) viewModel.retryDictionaryImport() else viewModel.retrySchemeImport()

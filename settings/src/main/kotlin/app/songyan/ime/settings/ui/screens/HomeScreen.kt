package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.displayPercent
import app.songyan.ime.settings.state.hasProgress
import app.songyan.ime.settings.ui.SettingsRoutes
import app.songyan.ime.settings.ui.SettingsSearch
import app.songyan.ime.settings.ui.SettingsUiState
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.components.ImportStatusCard
import app.songyan.ime.settings.ui.components.PreparingIndicator
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.components.StatusCard
import app.songyan.ime.settings.ui.components.StatusLine
import app.songyan.ime.settings.ui.components.importCardOf
import app.songyan.ime.settings.ui.text

/**
 * Home: the two places to act (what is installed, what is on the keyboard) plus the remaining
 * entries. Only a condition that needs the user's action becomes a card; everything else stays a
 * quiet status line so the screen does not shout on every launch.
 */
@Composable
fun HomeScreen(
    state: SettingsUiState,
    onOpen: (route: String) -> Unit,
    onRetryImeStatus: () -> Unit,
    onCancelImport: (ImportKind) -> Unit,
    onRetryImport: (ImportKind) -> Unit,
    onDismissDictionaryImport: () -> Unit,
    onDismissSchemeImport: () -> Unit,
    onAnswerSchemeLearning: (Boolean) -> Unit = {},
    /** The section shown beside the list in the two-pane layout; null on a narrow window. */
    selectedRoute: String? = null,
    /**
     * The search text. The host passes one state to both layouts: a rotation or fold that swaps
     * between them would otherwise drop what the user typed.
     */
    queryState: MutableState<String> = rememberSaveable { mutableStateOf("") },
) {
    val importCard = importCardOf(state.dictionaryImport, state.schemeImport)
    val context = LocalContext.current
    val searchIndex = remember(context) { SettingsSearch.resolve(context) }
    var query by queryState
    val found = remember(query, searchIndex) { SettingsSearch.filter(searchIndex, query) }
    SettingsScaffold(
        title = stringResource(R.string.app_name_settings),
        onBack = null,
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "search") {
                SettingsSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (query.isNotBlank()) {
                if (found.isEmpty()) {
                    item(key = "search_empty") {
                        StateMessage(
                            title = stringResource(R.string.search_empty_title),
                            body = stringResource(R.string.search_empty_body),
                            icon = Icons.Outlined.Search,
                        )
                    }
                } else {
                    items(found.size, key = { "result_" + it }) { index ->
                        val result = found[index]
                        SettingsRow(
                            title = result.title,
                            subtitle = result.section,
                            onClick = { onOpen(result.route) },
                        )
                    }
                }
                return@LazyColumn
            }
            if (importCard != null) {
                // An import keeps running when the user leaves its page, so its progress and its
                // failure are shown here too, where they cannot be missed.
                item(key = "import_card") {
                    ImportStatusCard(
                        card = importCard,
                        onCancel = { onCancelImport(importCard.kind) },
                        onRetry = { onRetryImport(importCard.kind) },
                        onDismiss = {
                            if (importCard.kind == ImportKind.DICTIONARY) {
                                onDismissDictionaryImport()
                            } else {
                                onDismissSchemeImport()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onAnswerOffer = onAnswerSchemeLearning,
                    )
                }
            }
            item {
                HomeStatus(
                    state = state,
                    onOpenOnboarding = { onOpen(SettingsRoutes.ONBOARDING) },
                    onRetryImeStatus = onRetryImeStatus,
                )
            }

            item { SectionHeader(stringResource(R.string.section_keyboard)) }
            item {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.row_keyboards_title),
                        subtitle = keyboardSummary(state),
                        icon = Icons.Outlined.Keyboard,
                        onClick = { onOpen(SettingsRoutes.KEYBOARDS) },
                        selected = selectedRoute == SettingsRoutes.KEYBOARDS,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_appearance_title),
                        subtitle = stringResource(R.string.row_appearance_subtitle),
                        icon = Icons.Outlined.Palette,
                        onClick = { onOpen(SettingsRoutes.APPEARANCE) },
                        selected = selectedRoute == SettingsRoutes.APPEARANCE,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_options_title),
                        subtitle = stringResource(R.string.row_options_subtitle),
                        icon = Icons.Outlined.Settings,
                        onClick = { onOpen(SettingsRoutes.OPTIONS) },
                        selected = selectedRoute == SettingsRoutes.OPTIONS,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_tryout_title),
                        subtitle = stringResource(R.string.row_tryout_subtitle),
                        icon = Icons.Outlined.TextFields,
                        onClick = { onOpen(SettingsRoutes.TRY_OUT) },
                        selected = selectedRoute == SettingsRoutes.TRY_OUT,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_content)) }
            item {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.row_schemes_title),
                        subtitle = schemesSummary(state),
                        icon = Icons.Outlined.Book,
                        onClick = { onOpen(SettingsRoutes.SCHEMES) },
                        selected = selectedRoute == SettingsRoutes.SCHEMES,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_quick_content_title),
                        subtitle = stringResource(R.string.row_quick_content_subtitle),
                        icon = Icons.Outlined.Widgets,
                        onClick = { onOpen(SettingsRoutes.quickContent()) },
                        selected = selectedRoute == SettingsRoutes.QUICK_CONTENT,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_tools_title),
                        subtitle = stringResource(R.string.row_tools_subtitle),
                        icon = Icons.Outlined.Build,
                        onClick = { onOpen(SettingsRoutes.TOOLS) },
                        selected = selectedRoute == SettingsRoutes.TOOLS,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_learning_title),
                        subtitle = stringResource(R.string.row_learning_subtitle),
                        icon = Icons.Outlined.Backup,
                        onClick = { onOpen(SettingsRoutes.LEARNING) },
                        selected = selectedRoute == SettingsRoutes.LEARNING,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_other)) }
            item {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.row_onboarding_title),
                        subtitle = stringResource(R.string.row_onboarding_subtitle),
                        icon = Icons.Outlined.TaskAlt,
                        onClick = { onOpen(SettingsRoutes.ONBOARDING) },
                        selected = selectedRoute == SettingsRoutes.ONBOARDING,
                    )
                    SettingsRow(
                        title = stringResource(R.string.row_about_title),
                        subtitle = stringResource(R.string.row_about_subtitle),
                        icon = Icons.Outlined.Info,
                        onClick = { onOpen(SettingsRoutes.ABOUT) },
                        selected = selectedRoute == SettingsRoutes.ABOUT,
                    )
                }
            }
        }
    }
}

/** The search field on top of the settings list: a rounded field with a clear button, as in system settings. */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun HomeStatus(
    state: SettingsUiState,
    onOpenOnboarding: () -> Unit,
    onRetryImeStatus: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.ime.readFailed ->
                StatusCard(
                    headline = stringResource(R.string.home_ime_read_failed),
                    body = stringResource(R.string.onboarding_read_failed_body),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetryImeStatus,
                    icon = Icons.Outlined.ErrorOutline,
                )

            !state.progress.schemeDone ->
                StatusCard(
                    headline = stringResource(R.string.onboarding_step_download_title),
                    body = stringResource(R.string.onboarding_step_download_body),
                    actionLabel = stringResource(R.string.onboarding_step_download_title),
                    onAction = onOpenOnboarding,
                    icon = Icons.Outlined.Info,
                )

            !state.ime.enabled ->
                StatusCard(
                    headline = stringResource(R.string.home_enable_title),
                    body = stringResource(R.string.home_enable_body),
                    actionLabel = stringResource(R.string.home_enable_action),
                    onAction = onOpenOnboarding,
                    icon = Icons.Outlined.WarningAmber,
                )

            !state.ime.selected ->
                StatusCard(
                    headline = stringResource(R.string.home_select_title),
                    body = stringResource(R.string.home_select_body),
                    actionLabel = stringResource(R.string.home_select_action),
                    onAction = onOpenOnboarding,
                    icon = Icons.Outlined.WarningAmber,
                )
        }

        when (val engine = state.engine) {
            // "Ready" is only said when nothing above asks the user to act; next to a card that says
            // the keyboard is not set up it would contradict it.
            EngineStatus.Ready ->
                if (readyToType(state)) {
                    StatusLine(
                        text = stringResource(R.string.home_engine_ready),
                        icon = Icons.Outlined.CheckCircle,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

            EngineStatus.Unknown -> StatusLine(stringResource(R.string.home_engine_unknown), Icons.Outlined.Info)

            EngineStatus.Failed ->
                StatusLine(
                    text = stringResource(R.string.home_engine_failed),
                    icon = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                )

            is EngineStatus.Preparing -> {
                // A percentage is only shown when one was reported; otherwise the bar is indeterminate
                // instead of sitting at a made-up 0%.
                StatusLine(
                    text =
                        if (engine.hasProgress) {
                            stringResource(R.string.home_engine_preparing, engine.displayPercent)
                        } else {
                            stringResource(R.string.home_engine_preparing_unknown)
                        },
                    icon = Icons.Outlined.Sync,
                )
                PreparingIndicator(percent = engine.displayPercent.takeIf { engine.hasProgress })
            }
        }
    }
}

@Composable
private fun schemesSummary(state: SettingsUiState): String {
    val count = state.schemes.size
    if (count == 0) return stringResource(R.string.home_summary_schemes_none)
    val inUse = state.inUseScheme?.name
    return if (inUse.isNullOrEmpty()) {
        stringResource(R.string.home_summary_schemes, count)
    } else {
        stringResource(R.string.home_summary_schemes_in_use, count, inUse)
    }
}

/**
 * The current combination built from what the keyboard will actually read: the scheme the engine
 * selects and the layout preference. The app-supplied [AppKeyboardInfo] is not used here because
 * its input-method label cannot follow a scheme switch.
 */
@Composable
private fun keyboardSummary(state: SettingsUiState): String {
    val layoutName = state.mainLayout?.displayName?.text() ?: stringResource(state.currentLayout.nameRes)
    val schemeName = state.inUseScheme?.name.orEmpty()
    val combination = listOfNotNull(schemeName.takeIf { it.isNotEmpty() }, layoutName).joinToString(" · ")
    return stringResource(R.string.home_summary_keyboard, combination)
}

/** The keyboard is enabled, chosen as the current keyboard, and its engine is prepared. */
internal fun readyToType(state: SettingsUiState): Boolean =
    !state.ime.readFailed && state.ime.enabled && state.ime.selected && state.engine == EngineStatus.Ready

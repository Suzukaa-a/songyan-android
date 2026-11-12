package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.OnboardingProgress
import app.songyan.ime.settings.state.OnboardingStep
import app.songyan.ime.settings.state.RecommendedScheme
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.RecommendedSchemeChoice
import app.songyan.ime.settings.ui.RecommendedSchemeDownloadDetails
import app.songyan.ime.settings.ui.RecommendedSchemeSummary
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.components.ImportStatusCard
import app.songyan.ime.settings.ui.components.ImportStatusContent
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.TryOutField
import app.songyan.ime.settings.ui.components.importCardOf
import app.songyan.ime.settings.ui.openWebPage
import app.songyan.ime.settings.ui.recommendedSchemes

/**
 * Step-by-step enablement. Every step reflects the real system state, so a finished step is never
 * shown as pending and vice versa. Completion is expressed once, by the numbered badge turning into
 * a check: a finished step then collapses to its title, because the explanation is only useful while
 * the step is the one to do.
 */
@Composable
fun OnboardingScreen(
    progress: OnboardingProgress,
    onOpenInputMethodSettings: () -> Unit,
    onShowPicker: () -> Unit,
    onRetry: () -> Unit,
    onTyped: () -> Unit,
    onBack: (() -> Unit)?,
    schemeImport: SchemeImportUiState = SchemeImportUiState.Idle,
    downloadAvailable: Boolean = false,
    onDownload: (RecommendedScheme) -> Unit = {},
    onOtherSchemes: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRetryDownload: () -> Unit = {},
    onDismissDownload: () -> Unit = {},
    onAnswerSchemeLearning: (Boolean) -> Unit = {},
) {
    val nextStep = progress.nextStep
    var showWarning by rememberSaveable { mutableStateOf(false) }
    var recommendedId by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMoreSchemes by rememberSaveable { mutableStateOf(false) }
    // The guide and the 输入方案 page show the same order: the device's own language decides it.
    val recommendedCatalog = recommendedSchemes()
    val importCard = importCardOf(DictionaryImportUiState.Idle, schemeImport)
    val downloading = schemeImport is SchemeImportUiState.Running
    val inlineProgress = downloading && downloadingId != null && nextStep == OnboardingStep.DOWNLOAD_SCHEME
    SettingsScaffold(
        title = stringResource(R.string.onboarding_title),
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    if (progress.readFailed) {
                        stringResource(R.string.onboarding_status_read_failed)
                    } else {
                        stringResource(R.string.onboarding_intro)
                    },
                style = MaterialTheme.typography.bodyLarge,
            )

            if (progress.readFailed) {
                Text(
                    text = stringResource(R.string.onboarding_read_failed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.onboarding_refresh))
                }
                return@Column
            }

            if (importCard != null && !inlineProgress) {
                ImportStatusCard(
                    card = importCard,
                    onCancel = onCancelDownload,
                    onRetry = onRetryDownload,
                    onDismiss = onDismissDownload,
                    onAnswerOffer = onAnswerSchemeLearning,
                )
            }
            OnboardingStep(
                number = 1,
                title = stringResource(R.string.onboarding_step_download_title),
                body = stringResource(R.string.onboarding_step_download_body),
                done = progress.schemeDone,
                isCurrent = nextStep == OnboardingStep.DOWNLOAD_SCHEME,
                actionLabel = null,
                onAction = null,
            ) {
                val featured = recommendedCatalog.first()
                val inlineStatus: @Composable () -> Unit = {
                    if (importCard != null) {
                        ImportStatusContent(importCard, onCancelDownload, onRetryDownload, onDismissDownload)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecommendedSchemeChoice(
                        scheme = featured,
                        featured = true,
                        enabled = downloadAvailable && !downloading,
                        onDownload = { recommendedId = featured.id },
                        progress = inlineStatus.takeIf { inlineProgress && downloadingId == featured.id },
                    )
                    val expandedDescription =
                        stringResource(
                            if (showMoreSchemes) {
                                R.string.onboarding_schemes_expanded
                            } else {
                                R.string.onboarding_schemes_collapsed
                            },
                        )
                    TextButton(
                        onClick = { showMoreSchemes = !showMoreSchemes },
                        enabled = !(inlineProgress && downloadingId != featured.id),
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expandedDescription },
                    ) {
                        Text(
                            stringResource(R.string.onboarding_more_schemes, recommendedCatalog.size - 1),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showMoreSchemes) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    if (showMoreSchemes) {
                        Column {
                            for (entry in recommendedCatalog.drop(1)) {
                                RecommendedSchemeChoice(
                                    scheme = entry,
                                    featured = false,
                                    enabled = downloadAvailable && !downloading,
                                    onDownload = { recommendedId = entry.id },
                                    progress = inlineStatus.takeIf { inlineProgress && downloadingId == entry.id },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onOtherSchemes, enabled = !downloading) {
                    Text(stringResource(R.string.onboarding_other_schemes))
                }
            }

            OnboardingStep(
                number = 2,
                title = stringResource(R.string.onboarding_step_enable_title),
                body = stringResource(R.string.onboarding_step_enable_body),
                done = progress.enableDone,
                isCurrent = nextStep == OnboardingStep.ENABLE_IME,
                actionLabel = stringResource(R.string.onboarding_step_enable_action),
                // Android warns about third-party keyboards when one is switched on; saying so first
                // keeps that system dialog from looking like something went wrong.
                onAction = { showWarning = true },
            )

            OnboardingStep(
                number = 3,
                title = stringResource(R.string.onboarding_step_select_title),
                body = stringResource(R.string.onboarding_step_select_body),
                done = progress.selectDone,
                isCurrent = nextStep == OnboardingStep.SELECT_IME,
                actionLabel = stringResource(R.string.onboarding_step_select_action),
                onAction = onShowPicker,
            )

            OnboardingStep(
                number = 4,
                title = stringResource(R.string.onboarding_step_try_title),
                body = stringResource(R.string.onboarding_step_try_body),
                done = progress.tryOutDone,
                isCurrent = nextStep == OnboardingStep.TRY_OUT,
                actionLabel = null,
                onAction = null,
                // The field stays after the step is done: it holds what the user just typed.
                keepContentWhenDone = true,
            ) {
                TryOutField(onTyped = onTyped)
            }

            if (progress.allDone) {
                Text(
                    text = stringResource(R.string.onboarding_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.Start)) {
                    Text(stringResource(R.string.onboarding_refresh))
                }
            }
        }
    }

    val recommended = recommendedCatalog.firstOrNull { it.id == recommendedId }
    if (recommended != null) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { recommendedId = null },
            title = {
                Text(
                    stringResource(R.string.schemes_recommended_confirm_title, stringResource(recommended.nameRes)),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.schemes_recommended_confirm_body))
                    RecommendedSchemeSummary(recommended)
                    RecommendedSchemeDownloadDetails(
                        recommended,
                        onOpenHomepage = { openWebPage(context, it) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    recommendedId = null
                    downloadingId = recommended.id
                    onDownload(recommended)
                }) {
                    Text(stringResource(R.string.schemes_recommended_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { recommendedId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.onboarding_warning_title)) },
            text = { Text(stringResource(R.string.onboarding_warning_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWarning = false
                        onOpenInputMethodSettings()
                    },
                ) {
                    Text(stringResource(R.string.onboarding_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * One step. [body], the action and [content] are shown only while the step is current: once it is
 * done the title and the check badge are the whole state.
 */
@Composable
private fun OnboardingStep(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    isCurrent: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    keepContentWhenDone: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val doneDescription = stringResource(R.string.onboarding_status_done)
    // The badge grows with the system font size so a number never spills out of its circle.
    val badgeSize = with(LocalDensity.current) { maxOf(28.dp, 28.sp.toDp()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(badgeSize)
                    .background(
                        color =
                            when {
                                done -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        shape = CircleShape,
                    ).semantics(mergeDescendants = true) {
                        if (done) contentDescription = doneDescription
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (done || isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (!done && isCurrent) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
            if (content != null && (isCurrent || (done && keepContentWhenDone))) {
                content()
            }
        }
    }
}

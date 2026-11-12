package app.songyan.ime.settings.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.ui.DictionaryImportAction
import app.songyan.ime.settings.ui.DictionaryImportFailure
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.SchemeImportUiState

/** Which import the card reports, so the buttons reach the right request. */
enum class ImportKind { DICTIONARY, SCHEME }

enum class ImportPhase { RUNNING, SUCCEEDED, FAILED }

/** One import request as the card shows it; both kinds look the same to the user. */
data class ImportCard(
    val kind: ImportKind,
    val phase: ImportPhase,
    val text: String,
    val retryable: Boolean = false,
    /** A download in flight: the bar fills as it arrives and a line says how much. */
    val transfer: TransferProgress? = null,
    /** A line under the bar about what to expect, while running. */
    val note: String? = null,
    /** A question the user answers on the card itself; [ImportPhase.SUCCEEDED] only. */
    val offer: ImportOffer? = null,
    /** False for work that cannot be stopped halfway, which shows no cancel button. */
    val cancellable: Boolean = true,
)

/** A yes or no question under a finished import: the learned words a package came with. */
data class ImportOffer(
    val question: String,
    val accept: String,
    val decline: String,
)

/**
 * The import card of a request that is running or has just finished. It stays until the user
 * closes it, so leaving the page that started the import never hides its progress or its failure.
 */
@Composable
fun ImportStatusCard(
    card: ImportCard,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The answer to [ImportCard.offer]: true to accept. */
    onAnswerOffer: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val failed = card.phase == ImportPhase.FAILED
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (failed) colors.errorContainer else colors.secondaryContainer,
                contentColor = if (failed) colors.onErrorContainer else colors.onSecondaryContainer,
            ),
    ) {
        ImportStatusContent(
            card,
            onCancel,
            onRetry,
            onDismiss,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
            onAnswerOffer = onAnswerOffer,
        )
    }
}

/** The same import controls can sit inside an existing surface without nesting cards. */
@Composable
fun ImportStatusContent(
    card: ImportCard,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAnswerOffer: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier) {
        Text(text = card.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        card.offer?.let { offer ->
            Text(
                text = offer.question,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            )
        }
        if (card.phase == ImportPhase.RUNNING) {
            TransferIndicator(
                card.transfer,
                Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp, bottom = 4.dp),
            )
            card.note?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (card.phase) {
                ImportPhase.RUNNING ->
                    if (card.cancellable) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }

                ImportPhase.FAILED -> {
                    if (card.retryable) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                }

                ImportPhase.SUCCEEDED ->
                    if (card.offer != null) {
                        TextButton(onClick = { onAnswerOffer(false) }) { Text(card.offer.decline) }
                        TextButton(onClick = { onAnswerOffer(true) }) { Text(card.offer.accept) }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
                    }
            }
        }
    }
}

/**
 * The progress bar of a running request: it fills while a download with a known size arrives, with
 * a line under it saying how much has arrived; otherwise (a picked file, the install after a
 * download, a size the server did not announce) it runs without an end.
 */
@Composable
fun TransferIndicator(
    transfer: TransferProgress?,
    modifier: Modifier = Modifier,
) {
    val fraction = transfer?.takeIf { !it.finished }?.fraction
    Column(modifier = modifier) {
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (transfer != null && !transfer.finished) {
            val context = LocalContext.current
            val arrived = Formatter.formatShortFileSize(context, transfer.bytes)
            Text(
                text =
                    transfer.total?.let {
                        stringResource(
                            R.string.transfer_downloaded_of,
                            arrived,
                            Formatter.formatShortFileSize(context, it),
                        )
                    } ?: stringResource(R.string.transfer_downloaded, arrived),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The card for whichever import is running or finished; a dictionary request wins a tie. */
@Composable
fun importCardOf(
    dictionary: DictionaryImportUiState,
    scheme: SchemeImportUiState,
): ImportCard? = dictionaryCard(dictionary) ?: schemeCard(scheme)

@Composable
private fun dictionaryCard(state: DictionaryImportUiState): ImportCard? =
    when (state) {
        DictionaryImportUiState.Idle -> null
        is DictionaryImportUiState.Running ->
            ImportCard(
                ImportKind.DICTIONARY,
                ImportPhase.RUNNING,
                stringResource(
                    if (state.cancelling) {
                        R.string.import_cancelling
                    } else if (state.action == DictionaryImportAction.IMPORT) {
                        R.string.schemes_dictionary_importing
                    } else {
                        R.string.schemes_dictionary_removing
                    },
                ),
                cancellable = !state.cancelling,
            )

        is DictionaryImportUiState.Succeeded ->
            ImportCard(
                ImportKind.DICTIONARY,
                ImportPhase.SUCCEEDED,
                stringResource(
                    if (state.action == DictionaryImportAction.IMPORT) {
                        R.string.schemes_dictionary_import_success
                    } else {
                        R.string.schemes_dictionary_remove_success
                    },
                ),
            )

        is DictionaryImportUiState.Failed ->
            ImportCard(
                ImportKind.DICTIONARY,
                ImportPhase.FAILED,
                when (state.reason) {
                    // The user can fix this one without the app explaining the format.
                    DictionaryImportFailure.INVALID_URL -> stringResource(R.string.schemes_dictionary_url_invalid)
                    DictionaryImportFailure.GATEWAY ->
                        state.message
                            ?: stringResource(
                                if (state.action == DictionaryImportAction.IMPORT) {
                                    R.string.schemes_dictionary_import_failed
                                } else {
                                    R.string.schemes_dictionary_remove_failed
                                },
                            )
                },
                retryable = state.retryable,
            )
    }

@Composable
private fun schemeCard(state: SchemeImportUiState): ImportCard? =
    when (state) {
        SchemeImportUiState.Idle -> null
        is SchemeImportUiState.Running ->
            ImportCard(
                ImportKind.SCHEME,
                ImportPhase.RUNNING,
                stringResource(
                    if (state.cancelling) {
                        R.string.import_cancelling
                    } else if (state.transfer?.finished == false) {
                        R.string.schemes_scheme_downloading
                    } else {
                        R.string.schemes_scheme_importing
                    },
                ),
                transfer = state.transfer.takeUnless { state.cancelling },
                cancellable = !state.cancelling,
                note =
                    if (state.cancelling || state.transfer?.finished == false) {
                        null
                    } else {
                        stringResource(R.string.schemes_scheme_importing_note)
                    },
            )

        is SchemeImportUiState.Succeeded ->
            ImportCard(
                ImportKind.SCHEME,
                ImportPhase.SUCCEEDED,
                state.info?.let {
                    val named =
                        if (state.updated) {
                            R.string.schemes_update_success_named
                        } else {
                            R.string.schemes_scheme_import_success_named
                        }
                    stringResource(named, it.name)
                } ?: stringResource(R.string.schemes_scheme_import_success),
                offer =
                    state.info?.takeIf { state.offersLearning }?.let {
                        ImportOffer(
                            question =
                                pluralStringResource(
                                    R.plurals.schemes_scheme_learning_question,
                                    it.learningDictionaries,
                                    it.learningDictionaries,
                                ),
                            accept =
                                stringResource(
                                    if (it.inUse) {
                                        R.string.schemes_scheme_learning_merge
                                    } else {
                                        R.string.schemes_scheme_learning_use_and_merge
                                    },
                                ),
                            decline = stringResource(R.string.schemes_scheme_learning_decline),
                        )
                    },
            )

        SchemeImportUiState.MergingLearning ->
            ImportCard(
                ImportKind.SCHEME,
                ImportPhase.RUNNING,
                stringResource(R.string.schemes_scheme_learning_merging),
                // The merge cannot be stopped halfway; the card has no cancel to offer.
                cancellable = false,
            )

        is SchemeImportUiState.LearningMerged ->
            ImportCard(ImportKind.SCHEME, ImportPhase.SUCCEEDED, state.message)

        is SchemeImportUiState.UpToDate ->
            ImportCard(
                ImportKind.SCHEME,
                ImportPhase.SUCCEEDED,
                state.info?.let { stringResource(R.string.schemes_update_up_to_date_named, it.name) }
                    ?: stringResource(R.string.schemes_update_up_to_date),
            )

        is SchemeImportUiState.Failed ->
            ImportCard(
                ImportKind.SCHEME,
                ImportPhase.FAILED,
                state.message ?: stringResource(R.string.schemes_scheme_import_failed),
                retryable = state.retryable,
            )
    }

package app.songyan.ime.settings.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.packages.SchemeGrammarRequirements
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.EngineFeaturesUiState

/** What the card can ask for; each action runs in the settings process, on the user's tap. */
data class GrammarModelActions(
    /** Downloads the model from a suggested link. */
    val onDownload: (url: String) -> Unit,
    /** Opens the file picker for a model the user already has. */
    val onPickFile: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissFailure: () -> Unit,
    /** Opens the page where a stored model is chosen for the input method in use. */
    val onOpenFeatures: () -> Unit,
    /** Closes the card (and, once the model is stored, forgets that it was just stored). */
    val onDismiss: () -> Unit,
)

/**
 * The card under a finished scheme package import whose configuration names grammar models the
 * package did not ship. Without the model the input method still types, but its candidate ranking
 * is the plain one, so the card says what is missing and offers the two ways to add it.
 *
 * [paths] are the missing model files; a model with a known public location also gets a download
 * button that states its size, a link is never fetched without a tap.
 */
@Composable
fun GrammarModelCard(
    paths: List<String>,
    state: EngineFeaturesUiState,
    actions: GrammarModelActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val failed = state.failed && !state.saving
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (failed) colors.errorContainer else colors.secondaryContainer,
                contentColor = if (failed) colors.onErrorContainer else colors.onSecondaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            when {
                state.saving -> Saving(state, actions)
                state.grammarModelImported -> Imported(actions)
                else -> Needed(paths, state, failed, actions)
            }
        }
    }
}

@Composable
private fun Saving(
    state: EngineFeaturesUiState,
    actions: GrammarModelActions,
) {
    // Once the file has arrived the rest is the install, which cannot be stopped halfway.
    val downloading = state.cancellable && state.transfer?.finished != true
    Text(
        text = stringResource(if (downloading) R.string.features_downloading else R.string.features_saving),
        style = MaterialTheme.typography.bodyMedium,
    )
    TransferIndicator(state.transfer, Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp, bottom = 4.dp))
    if (downloading) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun Imported(actions: GrammarModelActions) {
    Text(
        text = stringResource(R.string.schemes_grammar_imported),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(end = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        TextButton(onClick = actions.onOpenFeatures) { Text(stringResource(R.string.schemes_grammar_choose)) }
    }
}

@Composable
private fun Needed(
    paths: List<String>,
    state: EngineFeaturesUiState,
    failed: Boolean,
    actions: GrammarModelActions,
) {
    Text(
        text = stringResource(R.string.schemes_grammar_needed),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(end = 8.dp),
    )
    if (failed) {
        Text(
            text = state.failure ?: stringResource(R.string.features_error_generic),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, end = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.retryable) {
                TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            TextButton(onClick = actions.onDismissFailure) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
    paths.forEachIndexed { index, path ->
        val url = SchemeGrammarRequirements.suggestedUrl(path) ?: return@forEachIndexed
        if (paths.size > 1) {
            Text(
                text = stringResource(R.string.schemes_grammar_model_n, index + 1),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        TextButton(onClick = { actions.onDownload(url) }) {
            Text(stringResource(R.string.schemes_grammar_download, SchemeGrammarRequirements.SUGGESTED_MODEL_MEGABYTES))
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.schemes_grammar_later)) }
        TextButton(onClick = actions.onPickFile) { Text(stringResource(R.string.schemes_grammar_from_file)) }
    }
}

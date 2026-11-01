package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.RimeConfigUiState
import app.songyan.ime.settings.ui.components.SectionHeader

/**
 * The user's own Rime configuration: where to edit it (the Files app) and the one action that
 * makes the keyboard use the edits. The keyboard never changes while the user is still editing.
 */
internal fun LazyListScope.rimeConfigRows(
    state: RimeConfigUiState,
    busy: Boolean,
    onOpen: () -> Unit,
    onApply: () -> Unit,
) {
    item(key = "rime_config_header") { SectionHeader(stringResource(R.string.rime_config_section)) }
    item(key = "rime_config_body") {
        Text(
            text = stringResource(R.string.rime_config_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    item(key = "rime_config_status") {
        val info = state.info
        val error = info?.failure
        val status =
            when {
                state.applying -> stringResource(R.string.rime_config_applying)
                error != null -> error
                info?.notice != null -> info.notice
                info == null -> null
                info.pending -> stringResource(R.string.rime_config_pending)
                info.fileCount == 0 -> stringResource(R.string.rime_config_empty)
                else -> pluralStringResource(R.plurals.rime_config_in_use, info.fileCount, info.fileCount)
            }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (error != null &&
                            !state.applying
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.testTag(RIME_CONFIG_STATUS_TAG),
                )
            }
            if (state.applying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    item(key = "rime_config_actions") {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onOpen,
                enabled = state.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rime_config_open))
            }
            Button(
                onClick = onApply,
                enabled = state.canApply && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rime_config_apply))
            }
        }
    }
}

/** Test tag of the status line under the configuration's description. */
internal const val RIME_CONFIG_STATUS_TAG = "rime_config_status"

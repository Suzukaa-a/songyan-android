package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.TryOutField
import app.songyan.ime.settings.ui.components.TryOutKind

/** Try-out page: the field raises whatever keyboard is current, so the user feels it at once. */
@Composable
fun TryOutScreen(
    ime: ImeStatus,
    onTyped: () -> Unit,
    onEnableIme: () -> Unit,
    onBack: (() -> Unit)?,
) {
    SettingsScaffold(
        title = stringResource(R.string.tryout_title),
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Keep the field above the keyboard: without this the landscape keyboard
                    // covers the very text the user is trying out.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                ime.readFailed || !ime.enabled ->
                    TryOutHint(
                        text = stringResource(R.string.tryout_needs_enable),
                        actionLabel = stringResource(R.string.tryout_action),
                        onAction = onEnableIme,
                    )

                !ime.selected ->
                    TryOutHint(
                        text = stringResource(R.string.tryout_needs_select),
                        actionLabel = null,
                        onAction = null,
                    )
            }

            var kind by rememberSaveable { mutableStateOf(TryOutKind.Text) }
            TryOutKindPicker(selected = kind, onSelect = { kind = it })
            TryOutField(onTyped = onTyped, kind = kind)

            Text(
                text = stringResource(R.string.tryout_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TryOutHint(
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Keep all field kinds discoverable on narrow screens and at larger font sizes. */
@Composable
private fun TryOutKindPicker(
    selected: TryOutKind,
    onSelect: (TryOutKind) -> Unit,
) {
    val label = stringResource(R.string.tryout_kind_label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TryOutKind.entries.forEach { kind ->
                FilterChip(
                    selected = kind == selected,
                    onClick = { onSelect(kind) },
                    label = { Text(stringResource(kind.labelRes)) },
                )
            }
        }
    }
}

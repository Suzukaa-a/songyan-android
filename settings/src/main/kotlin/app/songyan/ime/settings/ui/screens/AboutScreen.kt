package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.EngineRecoveryNotice
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import java.text.DateFormat
import java.util.Date

/** Version, privacy statement, license and the third-party notices entry. */
@Composable
fun AboutScreen(
    versionName: String?,
    onOpenLicenses: () -> Unit,
    onBack: (() -> Unit)?,
    /** Shares the diagnostic report; null hides the entry. */
    onShareDiagnostics: (() -> Unit)? = null,
    /** The latest engine start recovery, shown so the user knows why the keyboard changed. */
    engineRecovery: EngineRecoveryNotice? = null,
    /** Opens the learning backup screen, where set-aside learning data can be restored; null hides the entry. */
    onOpenLearning: (() -> Unit)? = null,
) {
    var confirmDiagnostics by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.row_about_title),
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.app_name_settings),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text =
                            if (versionName.isNullOrEmpty()) {
                                stringResource(R.string.about_version_unknown)
                            } else {
                                stringResource(R.string.about_version, versionName)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionHeader(stringResource(R.string.about_privacy_title))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_privacy_local),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_privacy_network),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_privacy_private),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsRow(
                title = stringResource(R.string.about_licenses),
                subtitle = stringResource(R.string.about_licenses_subtitle),
                icon = Icons.Outlined.Description,
                onClick = onOpenLicenses,
            )
            if (engineRecovery != null) EngineRecoverySection(engineRecovery, onOpenLearning)
            if (onShareDiagnostics != null) {
                SettingsRow(
                    title = stringResource(R.string.about_diagnostics),
                    subtitle = stringResource(R.string.about_diagnostics_subtitle),
                    icon = Icons.Outlined.BugReport,
                    onClick = { confirmDiagnostics = true },
                )
            }
        }
    }

    if (confirmDiagnostics && onShareDiagnostics != null) {
        AlertDialog(
            onDismissRequest = { confirmDiagnostics = false },
            title = { Text(stringResource(R.string.about_diagnostics_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_diagnostics_included))
                    Text(stringResource(R.string.about_diagnostics_excluded))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiagnostics = false
                        onShareDiagnostics()
                    },
                ) {
                    Text(stringResource(R.string.about_diagnostics_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiagnostics = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** What happened and what changed, in the order the user needs it; nothing about how. */
@Composable
private fun EngineRecoverySection(
    notice: EngineRecoveryNotice,
    onOpenLearning: (() -> Unit)?,
) {
    SectionHeader(stringResource(R.string.about_recovery_title))
    val time =
        remember(notice.timeEpochMs) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(notice.timeEpochMs))
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val lines =
            listOfNotNull(
                stringResource(R.string.about_recovery_body, time),
                if (notice.switchedToBuiltin) stringResource(R.string.about_recovery_switched) else null,
                if (notice.movedLearningData) stringResource(R.string.about_recovery_learning) else null,
            )
        for (line in lines) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (notice.movedLearningData && onOpenLearning != null) {
        SettingsRow(
            title = stringResource(R.string.about_recovery_restore),
            subtitle = stringResource(R.string.about_recovery_restore_subtitle),
            icon = Icons.Outlined.Restore,
            onClick = onOpenLearning,
        )
    }
}

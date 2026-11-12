package app.songyan.ime.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.LocaleManagerCompat
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.RecommendedScheme
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.downloadSizeLabel

/**
 * The recommended entries in the order the device's own language puts them. The app's language
 * takes no part: a phone set to Taiwanese Chinese offers 注音 first even while the app speaks
 * English, and the guide and the 输入方案 page show the same order.
 */
@Composable
fun recommendedSchemes(): List<RecommendedScheme> {
    val context = LocalContext.current
    return remember(context) {
        val locales = LocaleManagerCompat.getSystemLocales(context).toLanguageTags()
        RecommendedSchemes.ordered(locales.split(','))
    }
}

/** What a recommended entry is, with the licence its upstream files are distributed under. */
@Composable
fun RecommendedSchemeSummary(
    scheme: RecommendedScheme,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(scheme.detailRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.schemes_recommended_license, scheme.licenses.joinToString(" · ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The download size of an entry, and the link to the upstream project it comes from. */
@Composable
fun RecommendedSchemeDownloadDetails(
    scheme: RecommendedScheme,
    onOpenHomepage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.schemes_recommended_size, downloadSizeLabel(scheme.downloadBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.schemes_recommended_homepage),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenHomepage(scheme.homepage) },
        )
    }
}

/** Opens [url] in whatever app handles web links; false when the device has none. */
fun openWebPage(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/** A quiet row for alternatives; the first recommendation gets its own tonal surface. */
@Composable
fun RecommendedSchemeChoice(
    scheme: RecommendedScheme,
    featured: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
    progress: (@Composable () -> Unit)? = null,
) {
    val name = stringResource(scheme.nameRes)
    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(scheme.detailRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.onboarding_scheme_metadata,
                    downloadSizeLabel(scheme.downloadBytes),
                    scheme.licenses.joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (featured) {
                Text(
                    stringResource(R.string.schemes_recommended_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).size(24.dp),
                )
                Column(Modifier.weight(1f)) { details() }
                if (!featured && progress == null) {
                    IconButton(onClick = onDownload, enabled = enabled) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.onboarding_download_scheme, name),
                        )
                    }
                }
            }
            if (progress != null) {
                progress()
            } else if (featured) {
                Button(onClick = onDownload, enabled = enabled) {
                    Text(stringResource(R.string.schemes_recommended_confirm))
                }
            }
        }
    }
    if (featured) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { content() }
    }
}

package app.songyan.ime.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

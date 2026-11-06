package app.songyan.ime.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.LayoutDisplayName

/** The words of a layout name: a resource in the user's language, or the author's saved text. */
@Composable
fun LayoutDisplayName.text(): String =
    when (this) {
        is LayoutDisplayName.Worded -> stringResource(resource)
        is LayoutDisplayName.Authored -> name
    }

/** The keyboard's main layout name, or the shape's name while the catalog has not answered. */
@Composable
fun CurrentMainLayout?.textOr(shape: KeyboardLayoutOption): String =
    this?.displayName?.text() ?: stringResource(shape.nameRes)

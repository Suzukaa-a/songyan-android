package app.songyan.ime.settings.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import app.songyan.ime.settings.R

class EditorNavigationController {
    var owner: Any? = null
    var dirty by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var discard: () -> Unit = {}
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun request(action: () -> Unit) {
        if (dirty || saving) pending = action else action()
    }

    fun cancel() {
        pending = null
    }

    fun confirm() {
        if (saving) return
        val action = pending ?: return
        pending = null
        discard()
        action()
    }
}

val LocalEditorNavigationGuard = staticCompositionLocalOf<EditorNavigationController?> { null }

@Composable
fun EditorNavigationGuard(
    hasChanges: Boolean,
    onDiscard: () -> Unit = {},
    saving: Boolean = false,
) {
    val controller = LocalEditorNavigationGuard.current ?: return
    val owner = remember { Any() }
    SideEffect {
        controller.owner = owner
        controller.dirty = hasChanges
        controller.saving = saving
        controller.discard = onDiscard
    }
    DisposableEffect(controller) {
        onDispose {
            if (controller.owner === owner) {
                controller.owner = null
                controller.dirty = false
                controller.saving = false
                controller.discard = {}
            }
        }
    }
}

@Composable
fun EditorNavigationConfirmation(controller: EditorNavigationController) {
    if (controller.pending == null) return
    AlertDialog(
        onDismissRequest = controller::cancel,
        title = { Text(stringResource(R.string.layout_editor_discard_title)) },
        text = { Text(stringResource(R.string.layout_editor_discard_body)) },
        confirmButton = {
            TextButton(onClick = controller::confirm, enabled = !controller.saving) {
                Text(stringResource(R.string.layout_editor_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = controller::cancel) { Text(stringResource(R.string.layout_editor_keep_editing)) }
        },
    )
}

package app.songyan.ime.settings.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.songyan.ime.settings.R

/**
 * The kind of input box the try-out field pretends to be, so the user can feel how the keyboard
 * behaves in each of them (the layout switches for URL, email, number and phone fields).
 */
enum class TryOutKind(
    val keyboardType: KeyboardType,
    val labelRes: Int,
) {
    Text(KeyboardType.Text, R.string.tryout_kind_text),
    Url(KeyboardType.Uri, R.string.tryout_kind_url),
    Email(KeyboardType.Email, R.string.tryout_kind_email),
    Number(KeyboardType.Number, R.string.tryout_kind_number),
    Phone(KeyboardType.Phone, R.string.tryout_kind_phone),
    Password(KeyboardType.Password, R.string.tryout_kind_password),
}

/**
 * Plain text field for the try-out flow. A [BasicTextField] is used on purpose: Material text
 * fields draw their own focus chrome, which competes with the IME window that appears over it.
 */
@Composable
fun TryOutField(
    onTyped: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TryOutKind = TryOutKind.Text,
) {
    // The cursor is saved with the text: after a rotation the user carries on where they were typing.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // Compose does not give a text field its focus back when the activity is recreated (a rotation,
    // a fold, a resize). The keyboard would then go down and stay down, though the user was typing
    // here, so whether the field had focus is saved and taken again; focusing the field raises the
    // keyboard by itself.
    var focused by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Read before the field attaches: its first focus callback reports "not focused".
    val refocus = remember { focused }
    LaunchedEffect(Unit) {
        if (refocus) focusRequester.requestFocus()
    }
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = text,
            onValueChange = {
                // Only text that was added counts: focusing the field, or a keyboard that
                // re-sends the same value, must not complete the step.
                if (it.text.length > text.text.length) onTyped()
                text = it
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        // Tearing the activity down for a recreation takes the focus away too; only
                        // a loss while the page is in use means the user left the field.
                        if (it.isFocused) {
                            focused = true
                        } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            focused = false
                        }
                    },
            textStyle =
                LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = kind.keyboardType,
                    // A password box never offers corrections, which is what the keyboard must honour.
                    autoCorrectEnabled = kind != TryOutKind.Password,
                ),
            visualTransformation =
                if (kind == TryOutKind.Password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { innerTextField ->
                if (text.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tryout_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            },
        )
    }
}

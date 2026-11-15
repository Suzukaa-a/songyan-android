package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.songyan.ime.script.ScriptAddressPolicy
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.settings.R
import kotlinx.coroutines.delay

/**
 * One @setting or @secret of a script, shared by the confirmation page and the settings dialog:
 * a choice is a menu of its declared options, a secret is masked, and an address is checked with
 * the same rule the script's requests obey.
 */
@Composable
internal fun ScriptSettingInput(
    field: ScriptSettingField,
    value: String,
    clearSaved: Boolean,
    onChange: (String) -> Unit,
    onClearSaved: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (field.kind == ScriptSettingKind.CHOICE && field.options.isNotEmpty() && !field.secret) {
            ChoiceInput(field, value, onChange, modifier)
        } else {
            TextInput(field, value, onChange, modifier)
        }
        if (field.secret) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.fillMaxWidth().clickable { onClearSaved(!clearSaved) },
            ) {
                Checkbox(checked = clearSaved, onCheckedChange = onClearSaved)
                Text(stringResource(R.string.scripts_secret_clear))
            }
        }
    }
}

/** True when a filled address would be refused, so the form cannot save it. */
internal fun hasUnreachableAddress(
    fields: List<ScriptSettingField>,
    values: Map<String, String>,
): Boolean =
    fields.any { field ->
        val value = values[field.key].orEmpty()
        field.kind == ScriptSettingKind.URL && value.isNotBlank() && !ScriptAddressPolicy.isReachableSettingUrl(value)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceInput(
    field: ScriptSettingField,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    // The first option is shown as the default; [withChoiceDefaults] stores it on submit. Writing
    // it from here raced the saved values arriving and overwrote a saved choice.
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value.takeIf { it in field.options } ?: field.options.first(),
            onValueChange = {},
            readOnly = true,
            label = { Text(field.label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TextInput(
    field: ScriptSettingField,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    var reveal by rememberSaveable(field.key) { mutableStateOf(false) }
    // A field focused before the keyboard is up is still in view then; once the keyboard has
    // shrunk the form it may not be, so it asks to be shown again.
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardShown = WindowInsets.isImeVisible
    LaunchedEffect(focused, keyboardShown) {
        if (focused && keyboardShown) {
            delay(KEYBOARD_SETTLE_MS)
            requester.bringIntoView()
        }
    }
    val addressInvalid =
        field.kind == ScriptSettingKind.URL &&
            value.isNotBlank() &&
            !ScriptAddressPolicy.isReachableSettingUrl(value)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = {
            Text(field.label + if (field.secret) stringResource(R.string.scripts_secret_suffix) else "")
        },
        // The label already names the field; only a problem or the blank-keeps-the-secret rule is
        // worth a second line.
        supportingText =
            when {
                addressInvalid -> {
                    { Text(stringResource(R.string.scripts_setting_url_invalid)) }
                }
                field.secret -> {
                    { Text(stringResource(R.string.scripts_secret_keep_hint)) }
                }
                else -> null
            },
        isError = addressInvalid,
        singleLine = true,
        // The field type is what the input method reads: an address gets the URL keyboard with no
        // auto-capitals or corrections, and a secret is a password, so no keyboard learns or keeps it.
        keyboardOptions =
            when {
                field.secret -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                field.kind == ScriptSettingKind.URL ->
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    )
                else -> KeyboardOptions.Default
            },
        visualTransformation =
            if (field.secret && !reveal) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        trailingIcon =
            if (field.secret) {
                {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription =
                                stringResource(
                                    if (reveal) R.string.scripts_secret_hide else R.string.scripts_secret_show,
                                ),
                        )
                    }
                }
            } else {
                null
            },
        modifier =
            modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .onFocusChanged { focused = it.isFocused },
    )
}

/** Long enough for the keyboard's slide-in to finish resizing the form. */
private const val KEYBOARD_SETTLE_MS = 250L

/**
 * The values to send: a choice the user left untouched gets the first option, which is what the
 * form showed. A saved or picked option is kept as it is.
 */
internal fun withChoiceDefaults(
    fields: List<ScriptSettingField>,
    values: Map<String, String>,
): Map<String, String> {
    val defaults =
        fields
            .filter { it.kind == ScriptSettingKind.CHOICE && it.options.isNotEmpty() && values[it.key] !in it.options }
            .associate { it.key to it.options.first() }
    return values + defaults
}

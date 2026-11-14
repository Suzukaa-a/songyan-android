package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.songyan.ime.script.ScriptAddressPolicy
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.settings.R

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
    // The first option is the default, so the form never offers a value the script did not list.
    LaunchedEffect(field.key, field.options) {
        if (value !in field.options) onChange(field.options.first())
    }
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

@Composable
private fun TextInput(
    field: ScriptSettingField,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    var reveal by rememberSaveable(field.key) { mutableStateOf(false) }
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
        supportingText = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        addressInvalid -> stringResource(R.string.scripts_setting_url_invalid)
                        field.kind == ScriptSettingKind.URL -> stringResource(R.string.scripts_setting_kind_url)
                        else -> stringResource(R.string.scripts_setting_kind_text)
                    },
                )
                if (field.secret) Text(stringResource(R.string.scripts_secret_keep_hint))
            }
        },
        isError = addressInvalid,
        singleLine = true,
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
        modifier = modifier.fillMaxWidth(),
    )
}

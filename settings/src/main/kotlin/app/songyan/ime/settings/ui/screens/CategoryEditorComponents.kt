package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutResourceBudget
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader

/** What the category editor can do; one callback per structural edit, so the screen stays a thin layer. */
class CategoryActions(
    val onSelectCategory: (Int) -> Unit,
    val onAdd: () -> Unit,
    val onDuplicate: () -> Unit,
    val onRemove: () -> Unit,
    val onMove: (Int) -> Unit,
    val onName: (String) -> Unit,
    val onColumns: (Int) -> Unit,
    val onSource: (KeyboardPageCategory.Source) -> Unit,
    val onSelectKey: (Int) -> Unit,
    val onMoveKey: (Int) -> Unit,
    val onAddKey: () -> Unit,
    val onRemoveKey: () -> Unit,
    val onKey: (LayoutKey) -> Unit,
)

/**
 * The categories of a categorized symbol page: the tabs, the open category's name, columns and
 * content, and (for a category of the user's own keys) its keys, which type text.
 */
@Composable
fun CategorySections(
    categories: List<KeyboardPageCategory>,
    index: Int,
    keyIndex: Int,
    actions: CategoryActions,
) {
    SectionHeader(stringResource(R.string.page_editor_categories_section))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEachIndexed { at, category ->
            val description = stringResource(R.string.page_editor_category_description, at + 1, category.name)
            FilterChip(
                selected = at == index,
                onClick = { actions.onSelectCategory(at) },
                label = { Text(category.name) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val full = categories.size >= LayoutResourceBudget.PAGE_CATEGORIES
        ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.page_editor_category_move_earlier, index > 0) {
            actions.onMove(-1)
        }
        ToolbarButton(
            Icons.AutoMirrored.Filled.ArrowForward,
            R.string.page_editor_category_move_later,
            index < categories.lastIndex,
        ) { actions.onMove(+1) }
        ToolbarButton(Icons.Filled.Add, R.string.page_editor_category_add, !full, actions.onAdd)
        ToolbarButton(Icons.Filled.ContentCopy, R.string.page_editor_category_duplicate, !full, actions.onDuplicate)
        ToolbarButton(Icons.Filled.Delete, R.string.page_editor_category_remove, categories.size > 1, actions.onRemove)
    }
    val category = categories.getOrNull(index) ?: return
    OutlinedTextField(
        value = category.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.page_editor_category_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.page_editor_category_columns, category.columns),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row {
            ToolbarButton(
                Icons.Filled.Remove,
                R.string.page_editor_category_fewer_columns,
                category.columns > 1,
            ) { actions.onColumns(category.columns - 1) }
            ToolbarButton(
                Icons.Filled.Add,
                R.string.page_editor_category_more_columns,
                category.columns < LayoutResourceBudget.CATEGORY_COLUMNS,
            ) { actions.onColumns(category.columns + 1) }
        }
    }
    SectionHeader(stringResource(R.string.page_editor_category_content))
    val recent = category.source == KeyboardPageCategory.Source.RECENT
    androidx.compose.foundation.layout
        .Column(modifier = Modifier.selectableGroup()) {
            RadioRow(
                title = stringResource(R.string.page_editor_category_fixed),
                selected = !recent,
                onClick = { actions.onSource(KeyboardPageCategory.Source.FIXED) },
            )
            RadioRow(
                title = stringResource(R.string.page_editor_category_recent),
                selected = recent,
                onClick = { actions.onSource(KeyboardPageCategory.Source.RECENT) },
            )
        }
    if (recent) {
        Text(
            text = stringResource(R.string.page_editor_category_recent_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    SectionHeader(stringResource(R.string.layout_editor_keys_section))
    val columns = category.columns.coerceAtLeast(1)
    KeyGrid(
        rows = category.rows,
        selection = KeySelection(keyIndex / columns, keyIndex % columns),
        onSelect = { actions.onSelectKey(it.row * columns + it.key) },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.layout_editor_move_left, keyIndex > 0) {
            actions.onMoveKey(-1)
        }
        ToolbarButton(
            Icons.AutoMirrored.Filled.ArrowForward,
            R.string.layout_editor_move_right,
            keyIndex < category.keys.lastIndex,
        ) { actions.onMoveKey(+1) }
        ToolbarButton(
            Icons.Filled.Add,
            R.string.layout_editor_add_key,
            category.keys.size < LayoutResourceBudget.CATEGORY_KEYS,
            actions.onAddKey,
        )
        ToolbarButton(
            Icons.Filled.Delete,
            R.string.layout_editor_remove_key,
            category.keys.size > 1,
            actions.onRemoveKey,
        )
    }
    val key = category.keys.getOrNull(keyIndex) ?: return
    SectionHeader(stringResource(R.string.layout_editor_selected_section, key.label))
    androidx.compose.runtime.key(category.id, keyIndex) {
        KeyFields(key = key, allowCode = false, onChange = actions.onKey, literalOnly = true)
    }
}

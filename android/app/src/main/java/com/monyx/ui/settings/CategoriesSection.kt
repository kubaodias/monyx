package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.CategoryEntity
import com.monyx.ui.theme.Palette

/**
 * Categories grouped by kind (expense/income), subcategories nested exactly
 * one level under their parent. Add/edit/delete via the repository.
 */
@Composable
fun CategoriesSection(
    groups: List<CategoryGroup>,
    onAdd: (name: String, kind: String, parentId: String?, icon: String?, color: String?) -> Unit,
    onUpdate: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onReorder: (List<CategoryEntity>) -> Unit,
) {
    var addTarget by remember { mutableStateOf<AddCategoryTarget?>(null) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }

    SectionCard(title = stringResource(R.string.settings_categories), icon = Icons.Filled.Category) {
        val isEmpty = groups.all { it.roots.isEmpty() }
        if (isEmpty) {
            Text(
                stringResource(R.string.settings_no_categories),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            CategoryKindHeader(
                kind = group.kind,
                onAdd = { addTarget = AddCategoryTarget(kind = group.kind, parentId = null) },
            )
            // Siblings reorder among siblings: the roots of this kind here,
            // the children of one parent inside the node below. Dragging a
            // category out of its family would be a reparent, and nesting is
            // exactly one level deep by construction.
            ReorderableColumn(
                items = group.roots,
                keyOf = { it.entity.id },
                onReorder = { nodes -> onReorder(nodes.map { it.entity }) },
            ) { node, dragging ->
                CategoryNodeItem(
                    node = node,
                    dragging = dragging,
                    onEditRoot = { editing = node.entity },
                    onDeleteRoot = { deleting = node.entity },
                    onAddChild = { addTarget = AddCategoryTarget(kind = group.kind, parentId = node.entity.id) },
                    onEditChild = { editing = it },
                    onDeleteChild = { deleting = it },
                    onReorderChildren = onReorder,
                )
            }
        }
    }

    // The root a category hangs under, whatever list it came from. Only ever
    // one level up, because nesting is exactly one level deep.
    val parentOf: (String?) -> CategoryEntity? = { id ->
        id?.let { pid -> groups.flatMap { it.roots }.firstOrNull { it.entity.id == pid }?.entity }
    }

    addTarget?.let { target ->
        val rootOptions = if (target.parentId == null) {
            groups.firstOrNull { it.kind == target.kind }?.roots?.map { it.entity }.orEmpty()
        } else {
            emptyList()
        }
        CategoryEditDialog(
            parent = parentOf(target.parentId),
            title = if (target.parentId == null) {
                stringResource(R.string.settings_add_category)
            } else {
                stringResource(R.string.settings_add_subcategory)
            },
            kind = target.kind,
            parentOptions = rootOptions,
            fixedParentId = target.parentId,
            initialName = "",
            initialIcon = null,
            initialColor = null,
            onDismiss = { addTarget = null },
            onSave = { name, parentId, icon, color ->
                onAdd(name, target.kind, parentId, icon, color)
                addTarget = null
            },
        )
    }

    editing?.let { entity ->
        CategoryEditDialog(
            parent = parentOf(entity.parentId),
            title = stringResource(R.string.settings_edit_category),
            kind = entity.kind,
            // Editing never reparents — nesting stays exactly one level deep
            // by construction, so the parent field is fixed here.
            parentOptions = emptyList(),
            fixedParentId = entity.parentId,
            initialName = entity.name,
            initialIcon = entity.icon,
            initialColor = entity.color,
            onDismiss = { editing = null },
            onSave = { name, _, icon, color ->
                onUpdate(entity.copy(name = name, icon = icon, color = color))
                editing = null
            },
        )
    }

    deleting?.let { entity ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete),
            message = stringResource(R.string.settings_delete_category_confirm, entity.name),
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                onDelete(entity)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

private data class AddCategoryTarget(val kind: String, val parentId: String?)

@Composable
private fun CategoryKindHeader(kind: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (kind == "expense") stringResource(R.string.overview_expenses) else stringResource(R.string.overview_income),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_add_category))
        }
    }
}

@Composable
private fun CategoryNodeItem(
    node: CategoryNode,
    dragging: Boolean,
    onEditRoot: () -> Unit,
    onDeleteRoot: () -> Unit,
    onAddChild: () -> Unit,
    onEditChild: (CategoryEntity) -> Unit,
    onDeleteChild: (CategoryEntity) -> Unit,
    onReorderChildren: (List<CategoryEntity>) -> Unit,
) {
    Column {
        CategoryLeafRow(
            entity = node.entity,
            dragging = dragging,
            onEdit = onEditRoot,
            onDelete = onDeleteRoot,
            onAddChild = onAddChild,
        )
        ReorderableColumn(
            items = node.children,
            keyOf = { it.id },
            onReorder = onReorderChildren,
        ) { child, childDragging ->
            CategoryLeafRow(
                entity = child,
                // Its parent's colour unless it has been given one of its own —
                // the rule every other screen already draws these by. Settings
                // was the one place that hashed a colourless subcategory to a
                // colour of its own, so "Dom > Remonty" was brown in the picker
                // and pink in the list that names it.
                parentColor = node.entity.color,
                dragging = childDragging,
                onEdit = { onEditChild(child) },
                onDelete = { onDeleteChild(child) },
                indent = true,
            )
        }
    }
}

/**
 * One category, tappable.
 *
 * The row opens the editor; there is no pencil. A pencil beside a row whose
 * only other gesture was a long-press drag was a button competing with the name
 * for width to offer what tapping the row offers everywhere else in the app —
 * a transaction, a repeating rule, an account. Delete keeps its button because
 * it is destructive and must never be the thing a mis-tap does.
 *
 * @param parentColor the family's colour, for a child with none of its own.
 */
@Composable
private fun CategoryLeafRow(
    entity: CategoryEntity,
    dragging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    parentColor: String? = null,
    onAddChild: (() -> Unit)? = null,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 32.dp else 0.dp)
            // Picked up: shaded and rounded, so the row following the finger is
            // obviously not one of the ones holding still.
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            // Tap to edit. A plain click, not a combined one: ReorderableColumn
            // picks a row up on a LONG press, which is exactly why the short
            // one is free to mean something.
            .clickable(onClickLabel = stringResource(R.string.settings_edit), onClick = onEdit)
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    Palette.colorForChild(entity.color, parentColor, entity.parentId, entity.id),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Palette.icon(entity.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(entity.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onAddChild != null) {
            IconButton(onClick = onAddChild) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_add_subcategory),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * @param parent the root this category hangs under, when it has one. It supplies
 *   the colour a subcategory takes when it is given none — which is the state a
 *   new one starts in, so a subcategory belongs to its family by default and
 *   only leaves it if somebody says so.
 */
@Composable
private fun CategoryEditDialog(
    parent: CategoryEntity?,
    title: String,
    kind: String,
    parentOptions: List<CategoryEntity>,
    fixedParentId: String?,
    initialName: String,
    initialIcon: String?,
    initialColor: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, parentId: String?, icon: String?, color: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var parentId by remember { mutableStateOf(fixedParentId) }
    var icon by remember { mutableStateOf(initialIcon) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (parentOptions.isEmpty() && fixedParentId == null) {
                    Text(
                        "${stringResource(R.string.settings_kind)}: " +
                            if (kind == "expense") {
                                stringResource(R.string.settings_kind_expense)
                            } else {
                                stringResource(R.string.settings_kind_income)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_category_name)) },
                    singleLine = true,
                    // Sentences, not Words: the seeded names this sits alongside
                    // are "Zakupy spożywcze" and "Inne przychody", so capitalising
                    // every word would make a typed subcategory the odd one out.
                    // It is a keyboard hint, so shift still wins for "iPhone".
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parentOptions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_category_parent), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    ParentPicker(options = parentOptions, selected = parentId, onSelect = { parentId = it })
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_icon), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                IconSwatchRow(selected = icon, onSelect = { icon = it })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_color), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                ColorSwatchRow(
                    selected = color,
                    onSelect = { color = it },
                    inherit = parent?.let { Palette.colorFor(it.color, it.id) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), parentId, icon, color) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * Parent choice as a chip row rather than a dropdown, to stay clear of
 * ExposedDropdownMenuBox's experimental API surface. Only root categories are
 * offered — nesting is exactly one level deep, so a category that
 * itself has a parent is never a valid choice here.
 */
@Composable
private fun ParentPicker(options: List<CategoryEntity>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ParentChip(
                label = stringResource(R.string.settings_category_none),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(options) { option ->
            ParentChip(label = option.name, selected = selected == option.id, onClick = { onSelect(option.id) })
        }
    }
}

@Composable
private fun ParentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

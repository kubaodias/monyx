package com.monio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monio.R
import com.monio.data.AccountEntity
import com.monio.data.Money
import com.monio.ui.theme.Palette

/**
 * Accounts: list, add, edit, delete via the repository. Editing
 * offers name, opening balance, icon and colour.
 */
@Composable
fun AccountsSection(
    accounts: List<AccountRow>,
    onAdd: (name: String, initialBalanceMinor: Long, icon: String?, color: String?) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    onDelete: (AccountEntity) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }

    SectionCard(
        title = stringResource(R.string.settings_accounts),
        icon = Icons.Filled.AccountBalanceWallet,
        trailing = {
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_add_account))
            }
        },
    ) {
        if (accounts.isEmpty()) {
            Text(
                stringResource(R.string.settings_no_accounts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            accounts.forEach { row ->
                AccountRowItem(
                    row = row,
                    onEdit = { editing = row.entity },
                    onDelete = { deleting = row.entity },
                )
            }
        }
    }

    if (showAdd) {
        AccountEditDialog(
            title = stringResource(R.string.settings_add_account),
            initialName = "",
            initialBalanceMinor = 0,
            initialIcon = null,
            initialColor = null,
            onDismiss = { showAdd = false },
            onSave = { name, balanceMinor, icon, color ->
                onAdd(name, balanceMinor, icon, color)
                showAdd = false
            },
        )
    }

    editing?.let { entity ->
        AccountEditDialog(
            title = stringResource(R.string.settings_edit_account),
            initialName = entity.name,
            initialBalanceMinor = entity.initialBalanceMinor,
            initialIcon = entity.icon,
            initialColor = entity.color,
            onDismiss = { editing = null },
            onSave = { name, balanceMinor, icon, color ->
                onUpdate(entity.copy(name = name, initialBalanceMinor = balanceMinor, icon = icon, color = color))
                editing = null
            },
        )
    }

    deleting?.let { entity ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete),
            message = stringResource(R.string.settings_delete_account_confirm, entity.name),
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                onDelete(entity)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AccountRowItem(row: AccountRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Palette.colorFor(row.entity.color, row.entity.id), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Palette.icon(row.entity.icon), contentDescription = null, tint = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.entity.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                Money.formatWithCurrency(row.balanceMinor),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.settings_edit))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete))
        }
    }
}

@Composable
private fun AccountEditDialog(
    title: String,
    initialName: String,
    initialBalanceMinor: Long,
    initialIcon: String?,
    initialColor: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, balanceMinor: Long, icon: String?, color: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var balanceText by remember { mutableStateOf(if (initialBalanceMinor == 0L) "" else Money.format(initialBalanceMinor)) }
    var icon by remember { mutableStateOf(initialIcon) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_account_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text(stringResource(R.string.settings_account_balance)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_icon), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                IconSwatchRow(selected = icon, onSelect = { icon = it })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_color), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                ColorSwatchRow(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), Money.parseToMinor(balanceText), icon, color) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

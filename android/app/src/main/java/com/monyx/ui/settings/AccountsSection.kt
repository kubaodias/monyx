package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.Money
import com.monyx.ui.theme.Palette

/**
 * Accounts: list, add, edit, archive, restore and delete. Editing offers name,
 * balance, icon and colour.
 *
 * Archived accounts sit in their own section below the open ones rather than
 * behind a screen of their own — the whole point of an archive is that it is
 * visible enough to undo.
 */
@Composable
fun AccountsSection(
    accounts: List<AccountRow>,
    onAdd: (name: String, initialBalanceMinor: Long, icon: String?, color: String?, inSummary: Boolean) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    onArchive: (AccountEntity, Boolean) -> Unit,
    onDelete: (AccountEntity) -> Unit,
    onReorder: (List<AccountEntity>) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountRow?>(null) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }
    var archiving by remember { mutableStateOf<AccountEntity?>(null) }

    val (archived, open) = accounts.partition { it.entity.archived == 1 }

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
            // Long press and drag to reorder. Only the open accounts: an
            // archived one is not offered when adding a transaction, so its
            // position decides nothing.
            ReorderableColumn(
                items = open,
                keyOf = { it.entity.id },
                onReorder = { rows -> onReorder(rows.map { it.entity }) },
            ) { row, dragging ->
                AccountRowItem(
                    row = row,
                    dragging = dragging,
                    onEdit = { editing = row },
                    onArchive = { archiving = row.entity },
                    onRestore = null,
                    onDelete = { deleting = row.entity },
                )
            }
        }
        if (archived.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_archived_accounts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            archived.forEach { row ->
                AccountRowItem(
                    row = row,
                    dragging = false,
                    onEdit = { editing = row },
                    onArchive = null,
                    // Restoring is not destructive and is the whole reason the
                    // row is still here, so it needs no confirmation.
                    onRestore = { onArchive(row.entity, false) },
                    onDelete = { deleting = row.entity },
                )
            }
        }
    }

    archiving?.let { entity ->
        ConfirmDialog(
            title = stringResource(R.string.settings_archive),
            message = stringResource(R.string.settings_archive_account_confirm, entity.name),
            confirmLabel = stringResource(R.string.settings_archive),
            onConfirm = {
                onArchive(entity, true)
                archiving = null
            },
            onDismiss = { archiving = null },
        )
    }

    if (showAdd) {
        AccountEditDialog(
            title = stringResource(R.string.settings_add_account),
            initialName = "",
            balanceMinor = 0,
            // A brand new account has no transactions, so the balance being
            // typed IS the opening balance.
            movementsMinor = 0,
            initialIcon = null,
            initialColor = null,
            initialInSummary = true,
            onDismiss = { showAdd = false },
            onSave = { name, balanceMinor, icon, color, inSummary ->
                onAdd(name, balanceMinor, icon, color, inSummary)
                showAdd = false
            },
        )
    }

    editing?.let { row ->
        val entity = row.entity
        // Everything the transactions have done to this account since it was
        // opened. Balance = opening + movements, so a typed balance decides the
        // opening one and not the other way round.
        val movements = row.balanceMinor - entity.initialBalanceMinor
        AccountEditDialog(
            title = stringResource(R.string.settings_edit_account),
            initialName = entity.name,
            balanceMinor = row.balanceMinor,
            movementsMinor = movements,
            initialIcon = entity.icon,
            initialColor = entity.color,
            initialInSummary = entity.excludedFromSummary == 0,
            onDismiss = { editing = null },
            onSave = { name, balanceMinor, icon, color, inSummary ->
                onUpdate(
                    entity.copy(
                        name = name,
                        initialBalanceMinor = balanceMinor - movements,
                        icon = icon,
                        color = color,
                        excludedFromSummary = if (inSummary) 0 else 1,
                    ),
                )
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

/**
 * One account, tappable.
 *
 * The row opens the editor; there is no pencil, the same bargain the category
 * list makes. Archive and delete keep their buttons — one is not what editing
 * means and the other is destructive.
 */
@Composable
private fun AccountRowItem(
    row: AccountRow,
    dragging: Boolean,
    onEdit: () -> Unit,
    onArchive: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val archived = row.entity.archived == 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Picked up: shaded and rounded, so the row that is following the
            // finger is obviously not one of the ones holding still.
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            // A short tap edits; ReorderableColumn takes the long one.
            .clickable(onClickLabel = stringResource(R.string.settings_edit), onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    Palette.colorFor(row.entity.color, row.entity.id)
                        .copy(alpha = if (archived) 0.35f else 1f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Palette.icon(row.entity.icon), contentDescription = null, tint = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.entity.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (archived) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                if (row.entity.excludedFromSummary == 1) {
                    Money.formatWithCurrency(row.balanceMinor) + " · " +
                        stringResource(R.string.settings_account_outside_summary)
                } else {
                    Money.formatWithCurrency(row.balanceMinor)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onArchive?.let {
            IconButton(onClick = it) {
                Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.settings_archive))
            }
        }
        onRestore?.let {
            IconButton(onClick = it) {
                Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.settings_unarchive))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete))
        }
    }
}

/**
 * The balance field is the balance the account has NOW, not the one it opened
 * with.
 *
 * Nobody knows what their current account held on the day they started using
 * this app; they know what the banking app says this morning. So that is what
 * is asked for, and the opening balance is worked backwards from it —
 * `opening = typed - movements` — which lands the running total exactly on the
 * typed figure. Editing it to the same number it already shows is therefore a
 * no-op, which is the behaviour that was missing: typing today's balance into a
 * field holding a months-old opening figure moved the account by the difference
 * twice over.
 *
 * @param movementsMinor everything the transactions have added and taken away
 *   since. Zero for a new account, which is why the same dialog does both jobs.
 */
@Composable
private fun AccountEditDialog(
    title: String,
    initialName: String,
    balanceMinor: Long,
    movementsMinor: Long,
    initialIcon: String?,
    initialColor: String?,
    initialInSummary: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, balanceMinor: Long, icon: String?, color: String?, inSummary: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var balanceText by remember { mutableStateOf(if (balanceMinor == 0L) "" else Money.format(balanceMinor)) }
    var icon by remember { mutableStateOf(initialIcon) }
    var color by remember { mutableStateOf(initialColor) }
    var inSummary by remember { mutableStateOf(initialInSummary) }

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
                    // Same hint the category field carries: Sentences, because
                    // the seeded names alongside it read "Konto osobiste", not
                    // "Konto Osobiste".
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
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
                // The arithmetic, shown rather than explained, and only when
                // there is any: an account with no transactions has an opening
                // balance identical to the field above it, and repeating the
                // number would just look like a mistake.
                if (movementsMinor != 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.settings_account_opening,
                            Money.formatWithCurrency(Money.parseToMinor(balanceText) - movementsMinor),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_icon), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                IconSwatchRow(selected = icon, onSelect = { icon = it })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_color), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                ColorSwatchRow(selected = color, onSelect = { color = it })
                Spacer(Modifier.height(8.dp))
                // Savings held somewhere else: still an account you can book
                // on, just not money to add to what is there to spend.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = inSummary, role = Role.Switch, onValueChange = { inSummary = it }),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_account_in_summary),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.settings_account_in_summary_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = inSummary, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), Money.parseToMinor(balanceText), icon, color, inSummary) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

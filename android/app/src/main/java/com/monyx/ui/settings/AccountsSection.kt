package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
    onOpenTransactions: (accountId: String, period: String?) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountRow?>(null) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }
    var archiving by remember { mutableStateOf<AccountEntity?>(null) }

    var archivedExpanded by remember { mutableStateOf(false) }

    // Three groups, and their order carries the meaning: what the household
    // spends from, then money that is not theirs to spend, then what is
    // finished with. An account that is both excluded and archived belongs in
    // the archive — that is where you look for a trip that is over, whoever
    // paid for it — and keeps the "outside summary" mark on its own row.
    val archived = accounts.filter { it.entity.archived == 1 }
    val outside = accounts.filter { it.entity.archived == 0 && it.entity.excludedFromSummary == 1 }
    val open = accounts.filter { it.entity.archived == 0 && it.entity.excludedFromSummary == 0 }

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
                    muted = false,
                    onOpenTransactions = onOpenTransactions,
                    onEdit = { editing = row },
                    onArchive = { archiving = row.entity },
                    onRestore = null,
                    onDelete = { deleting = row.entity },
                )
            }
        }
        if (outside.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GroupHeader(stringResource(R.string.settings_outside_summary_accounts, outside.size))
            outside.forEach { row ->
                AccountRowItem(
                    row = row,
                    dragging = false,
                    muted = true,
                    onOpenTransactions = onOpenTransactions,
                    onEdit = { editing = row },
                    onArchive = { archiving = row.entity },
                    onRestore = null,
                    onDelete = { deleting = row.entity },
                )
            }
        }
        if (archived.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // Folded by default: an archive earns its place by being undoable,
            // not by being in the way of the accounts in use.
            GroupHeader(
                text = stringResource(R.string.settings_archived_accounts_count, archived.size),
                expanded = archivedExpanded,
                onToggle = { archivedExpanded = !archivedExpanded },
            )
            if (archivedExpanded) {
                archived.forEach { row ->
                    AccountRowItem(
                        row = row,
                        dragging = false,
                        muted = true,
                        onOpenTransactions = onOpenTransactions,
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
 * The label above a group of accounts, with the count in it.
 *
 * Given [onToggle] it folds, and the whole line is the target rather than the
 * chevron alone — a 16 dp icon is not a button on a phone held one-handed.
 */
@Composable
private fun GroupHeader(
    text: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggle == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        onClickLabel = stringResource(
                            if (expanded) R.string.settings_collapse else R.string.settings_expand,
                        ),
                        onClick = onToggle,
                    )
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One account, tappable.
 *
 * The row opens the editor; there is no pencil, the same bargain the category
 * list makes. Archive and delete keep their buttons — one is not what editing
 * means and the other is destructive.
 *
 * @param muted drawn quieter, for an account outside the summary or finished
 *   with. The group it sits under says which; the dimming says "not one of the
 *   ones above" at a glance, without the eye having to read a heading again.
 * @param onOpenTransactions the ledger, filtered to this account and aimed at
 *   the month it was last used. For an archived account this row is the ONLY way
 *   in — the filter on the Transactions tab no longer offers it.
 */
@Composable
private fun AccountRowItem(
    row: AccountRow,
    dragging: Boolean,
    muted: Boolean,
    onOpenTransactions: (accountId: String, period: String?) -> Unit,
    onEdit: () -> Unit,
    onArchive: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onDelete: () -> Unit,
) {
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
                        .copy(alpha = if (muted) 0.35f else 1f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Palette.icon(row.entity.icon), contentDescription = null, tint = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.entity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (muted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                // The same mark the chip on the Overview carries, so the two
                // screens say it the same way. On every account it is true of,
                // group heading or not: a row that has to be read together with
                // a heading four rows up is a row that does not say what it is.
                if (row.entity.excludedFromSummary == 1) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.RemoveCircleOutline,
                        contentDescription = stringResource(R.string.settings_account_outside_summary),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                Money.formatWithCurrency(row.balanceMinor),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onOpenTransactions(row.entity.id, row.lastActivityOn?.take(7)) },
            // Nothing has ever been booked on it, so there is no ledger to open.
            enabled = row.lastActivityOn != null,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = stringResource(R.string.settings_account_transactions),
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
 * A hairline between two parts of the account dialog.
 *
 * The dialog asks four unrelated things — what it is called and what is in it,
 * what it looks like, what colour, and whether it counts — and as one unbroken
 * column of controls the eye could not tell where one question ended and the
 * next began. Thin and faint: it is punctuation, not a border.
 */
@Composable
private fun SectionRule() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
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
            // Scrollable, because this dialog is eight controls tall and the
            // last of them is a switch with an explanation under it: on a short
            // screen, or with the font scaled up, AlertDialog simply cuts the
            // bottom off rather than letting it move.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                SectionRule()
                Text(stringResource(R.string.settings_icon), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                IconSwatchRow(selected = icon, onSelect = { icon = it })
                SectionRule()
                Text(stringResource(R.string.settings_color), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                ColorSwatchRow(selected = color, onSelect = { color = it })
                SectionRule()
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

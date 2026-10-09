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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.monyx.ui.rememberOfferedCurrencies
import com.monyx.ui.theme.Palette
import androidx.compose.foundation.layout.Arrangement
import com.monyx.data.Currency
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * What [AccountEditor] opens on: a new account, or the one being edited.
 *
 * A seed rather than a nullable row, for the reason [RuleSeed] is one — "add"
 * and "edit this account" are both open states and only one of them has a row
 * behind it. [accountId] is what tells them apart, and the editor itself never
 * touches an entity: it hands six values back and Settings decides whether that
 * is an insert or a copy.
 */
data class AccountSeed(
    val accountId: String? = null,
    val name: String = "",
    /** What the account holds NOW. See [AccountEditor]. */
    val balanceMinor: Long = 0,
    /**
     * Everything the transactions have added and taken away since it opened,
     * so the editor can work the opening balance backwards from a typed one.
     * Zero for a new account, which has no transactions yet.
     */
    val movementsMinor: Long = 0,
    val icon: String? = null,
    val color: String? = null,
    val inSummary: Boolean = true,
    val currency: Currency = Currency.PLN,
) {
    companion object {
        fun of(row: AccountRow): AccountSeed = AccountSeed(
            accountId = row.entity.id,
            name = row.entity.name,
            balanceMinor = row.balanceMinor,
            movementsMinor = row.balanceMinor - row.entity.initialBalanceMinor,
            icon = row.entity.icon,
            color = row.entity.color,
            inSummary = row.entity.excludedFromSummary == 0,
            currency = Currency.of(row.entity.currency),
        )
    }
}

/**
 * Accounts: list, add, edit, archive, restore and delete. Editing is
 * [AccountEditor], a screen of its own that Settings swaps itself for.
 *
 * Archived accounts sit in their own section below the open ones rather than
 * behind a screen of their own — the whole point of an archive is that it is
 * visible enough to undo.
 */
@Composable
fun AccountsSection(
    accounts: List<AccountRow>,
    /** A new account with no seed, or one of the rows below. Settings owns the
     *  open state, because the editor replaces the whole settings screen. */
    onOpenEditor: (AccountSeed) -> Unit,
    onArchive: (AccountEntity, Boolean) -> Unit,
    onDelete: (AccountEntity) -> Unit,
    onReorder: (List<AccountEntity>) -> Unit,
    onOpenTransactions: (accountId: String, period: String?) -> Unit,
) {
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
            IconButton(onClick = { onOpenEditor(AccountSeed()) }) {
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
                    onEdit = { onOpenEditor(AccountSeed.of(row)) },
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
                    onEdit = { onOpenEditor(AccountSeed.of(row)) },
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
                        onEdit = { onOpenEditor(AccountSeed.of(row)) },
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
            // In the account's OWN currency. This is the figure that is
            // legitimately not in złoty — a balance is one account's position,
            // not a total — and printing "zł" after a euro balance is the
            // misstatement this whole feature exists to stop.
            Text(
                Money.formatIn(row.balanceMinor, Currency.of(row.entity.currency)),
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
 * A hairline between two parts of the account editor.
 *
 * The form asks four unrelated things — what it is called and what is in it,
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
 * Adding or editing an account, full screen.
 *
 * It was an `AlertDialog`, and it was the same mistake the rule editor made
 * before ADR 0014: eight controls, two swatch rows and a dropdown inside a box
 * a third of the screen tall, with the body scrolling and the Save button
 * pinned outside that scroll — so the field being filled in and the button
 * being aimed for were never both on screen. A form this long is a screen.
 *
 * It REPLACES the settings content rather than floating over it, for the reason
 * [RecurringEditor] does: a full-screen `Dialog` has to be told how tall the
 * screen is and gets it wrong, placing its window below the status bar while
 * measuring its content against the whole display — which puts the save button
 * a status bar's worth below the bottom edge.
 *
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
 * @param seed what the form opens on; [AccountSeed.movementsMinor] is zero for
 *   a new account, which is why the same form does both jobs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditor(
    seed: AccountSeed,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        balanceMinor: Long,
        icon: String?,
        color: String?,
        inSummary: Boolean,
        currency: Currency,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(seed.name) }
    var balanceText by remember {
        mutableStateOf(if (seed.balanceMinor == 0L) "" else Money.format(seed.balanceMinor))
    }
    var icon by remember { mutableStateOf(seed.icon) }
    var color by remember { mutableStateOf(seed.color) }
    var inSummary by remember { mutableStateOf(seed.inSummary) }
    var currency by remember { mutableStateOf(seed.currency) }

    Scaffold(
        // Zero, because the navigation Scaffold this screen lives in has
        // already applied them — the same arrangement RecurringEditor needs,
        // for the same reason.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (seed.accountId == null) R.string.settings_add_account
                            else R.string.settings_edit_account,
                        ),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_cancel),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        bottomBar = {
            // Pinned, like the rule editor's and the keypad's. In the dialog
            // this button was outside the scroll, which meant the field being
            // filled in and the button being aimed for were never both on
            // screen — the same complaint that made the rule editor a screen.
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = {
                        onSave(name.trim(), Money.parseToMinor(balanceText), icon, color, inSummary, currency)
                    },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(52.dp),
                ) {
                    // Named rather than left dead, the bargain every save
                    // button in this app keeps: a control that refuses to act
                    // and refuses to explain is the bug, not the guard. A
                    // nameless account is the only thing this form will not
                    // take — everything else has a default.
                    Text(
                        stringResource(
                            if (name.isBlank()) R.string.settings_account_needs_name
                            else R.string.settings_save,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
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
                // The unit rides on the label, because this field is the
                // one place a wrong currency is silently expensive: typing
                // a euro balance under a label saying "zł" is a mistake
                // nothing downstream can detect.
                label = {
                    Text(
                        stringResource(R.string.settings_account_balance) +
                            " (" + currency.suffix + ")",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            // The arithmetic, shown rather than explained, and only when
            // there is any: an account with no transactions has an opening
            // balance identical to the field above it, and repeating the
            // number would just look like a mistake.
        if (seed.movementsMinor != 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.settings_account_opening,
                        Money.formatIn(Money.parseToMinor(balanceText) - seed.movementsMinor, currency),
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
            CurrencyField(
                selected = currency,
                // What this phone offers, plus whatever this account is already
                // denominated in — an account in a hidden currency has to stay
                // editable without silently changing what its money is. See
                // [com.monyx.data.Currencies].
                options = rememberOfferedCurrencies(listOf(seed.currency.code, currency.code)),
                onSelect = { currency = it },
            )
            // What picking a foreign currency costs, said on the screen
            // that does the picking rather than discovered on the Overview.
            if (!currency.isReporting) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_account_currency_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            // Room under the switch's own explanation, so the last control is
            // not flush against the save bar.
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The currencies this phone offers, as a dropdown.
 *
 * A grid of chips was the first attempt and it was wrong for this form: it is
 * already eight controls tall and scrolls, and two rows of chips pushed the
 * in-summary switch that much further down. A dropdown is one line whatever
 * the list length, and it also stops the currency competing for
 * attention with the icon and colour swatches above it — those are a choice
 * among equals, and this is a field with one answer.
 *
 * The suffix rides along with the code, because "EUR — €" is what makes the
 * row on the account list recognisable afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyField(
    selected: Currency,
    options: List<Currency>,
    onSelect: (Currency) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = currencyLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_account_currency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(currencyLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** "EUR — €", or just "CHF" where the code already IS the suffix. */
internal fun currencyLabel(currency: Currency): String =
    if (currency.suffix == currency.code) currency.code else "${currency.code} — ${currency.suffix}"

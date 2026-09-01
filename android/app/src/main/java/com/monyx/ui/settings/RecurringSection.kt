package com.monyx.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.Recurrence
import com.monyx.data.RecurringRuleEntity
import com.monyx.data.RecurringRuleListItem
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The fixed shape of a household's month: rent, the phone bill, the loan, the
 * salary that pays for them.
 *
 * It lives in Settings beside accounts and categories rather than as a sixth
 * tab, and that is a claim about how often it is touched. A repeating
 * transaction is set up roughly as often as an account is opened — twice a year,
 * not twice a day. The keypad stays the thing the app opens on.
 */
@Composable
fun RecurringSection(
    rules: List<RecurringRuleListItem>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onAdd: (RuleDraft) -> Unit,
    onUpdate: (String, RuleDraft) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecurringRuleListItem?>(null) }
    var deleting by remember { mutableStateOf<RecurringRuleListItem?>(null) }

    SectionCard(
        title = stringResource(R.string.settings_recurring),
        icon = Icons.Filled.Repeat,
        trailing = {
            IconButton(onClick = { showAdd = true }, enabled = accounts.isNotEmpty()) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_add_recurring))
            }
        },
    ) {
        if (rules.isEmpty()) {
            Text(
                stringResource(
                    if (accounts.isEmpty()) R.string.recurring_needs_account
                    else R.string.settings_no_recurring,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rules.forEach { rule ->
                RuleRow(
                    rule = rule,
                    onEdit = { editing = rule },
                    onDelete = { deleting = rule },
                )
            }
        }
    }

    if (showAdd) {
        RuleDialog(
            title = stringResource(R.string.recurring_add_title),
            initial = null,
            accounts = accounts,
            categories = categories,
            onDismiss = { showAdd = false },
            onSave = { draft ->
                onAdd(draft)
                showAdd = false
            },
        )
    }

    editing?.let { rule ->
        RuleDialog(
            title = stringResource(R.string.recurring_edit_title),
            initial = rule,
            accounts = accounts,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { draft ->
                onUpdate(rule.id, draft)
                editing = null
            },
        )
    }

    deleting?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete),
            // The count is in the message on purpose. Stopping a rule and
            // erasing what it did are two different things, and the only moment
            // that distinction matters is the moment before it is stopped.
            //
            // Plurals, not a format argument, and a separate string for none.
            // "The 1 transactions it has already added" is what a bare %1$d
            // gives you, and Polish needs three forms rather than two.
            message = if (rule.generatedCount == 0) {
                stringResource(R.string.recurring_delete_confirm_none)
            } else {
                pluralStringResource(
                    R.plurals.recurring_delete_confirm,
                    rule.generatedCount,
                    rule.generatedCount,
                )
            },
            confirmLabel = stringResource(R.string.settings_delete),
            onConfirm = {
                onDelete(rule.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RecurringRuleListItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = Palette.colorFor(rule.categoryColor, rule.categoryId ?: rule.id)
    val anchor = runCatching { LocalDate.parse(rule.startsOn) }.getOrNull()
    val next = anchor?.let {
        Recurrence.nextOccurrence(
            freq = rule.freq,
            anchor = it,
            endsOn = rule.endsOn?.let { end -> runCatching { LocalDate.parse(end) }.getOrNull() },
            after = Dates.today().minusDays(1),
        )
    }

    // The row itself opens the editor, so only one icon button competes with the
    // text for width. With two, "Every month · Next on 30 September" wrapped
    // mid-phrase, and it is the line that answers the only question anyone has
    // about a rule they set up months ago. Tapping a row to open it is how the
    // transaction list already behaves.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Palette.icon(rule.categoryIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rule.note?.takeIf { it.isNotBlank() }
                    ?: rule.categoryName
                    ?: rule.accountName.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResourceForFreq(rule.freq),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The schedule and the evidence, on their own line.
            //
            // "Next on ..." answers the only question anyone has about a rule
            // they set up in March — is it still going to happen — and the count
            // is what proves it already did. Both were on the frequency line
            // once, and the phrase wrapped in the middle of itself.
            Text(
                buildString {
                    append(
                        next?.let {
                            stringResource(R.string.recurring_next, Dates.shortDayLabel(it.toString()))
                        } ?: stringResource(R.string.recurring_finished),
                    )
                    if (rule.generatedCount > 0) {
                        append(" · ")
                        append(stringResource(R.string.recurring_added_count, rule.generatedCount))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Money.formatSigned(rule.amountMinor, rule.kind),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (rule.kind == "income") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete))
        }
    }
}

@Composable
private fun stringResourceForFreq(freq: String): String = stringResource(
    when (freq) {
        Recurrence.WEEKLY -> R.string.recurring_freq_weekly
        Recurrence.YEARLY -> R.string.recurring_freq_yearly
        else -> R.string.recurring_freq_monthly
    },
)

/** Everything a rule needs that the caller does not already know. */
data class RuleDraft(
    val kind: String,
    val amountMinor: Long,
    val accountId: String,
    val categoryId: String,
    val note: String?,
    val freq: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(
    title: String,
    initial: RecurringRuleListItem?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit,
) {
    var kind by remember { mutableStateOf(initial?.kind ?: "expense") }
    var amountText by remember {
        mutableStateOf(initial?.amountMinor?.let { Money.format(it) } ?: "")
    }
    var accountId by remember {
        mutableStateOf(initial?.accountId ?: accounts.firstOrNull()?.id)
    }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var freq by remember { mutableStateOf(initial?.freq ?: Recurrence.MONTHLY) }
    var startsOn by remember {
        mutableStateOf(
            initial?.startsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: Dates.today(),
        )
    }
    var endsOn by remember {
        mutableStateOf(initial?.endsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }
    var picking by remember { mutableStateOf<DateField?>(null) }

    /**
     * The earliest anchor the picker will offer.
     *
     * A rule is a statement about the future — it does not backfill — so a new
     * one cannot start before today. Left unbounded, a mis-scrolled year would
     * have the rule quietly write hundreds of transactions into a history nobody
     * asked for, sixty at a time, over many app opens.
     *
     * An existing rule keeps its own anchor as the floor instead. Otherwise
     * opening September's rent in November would show its start date greyed out
     * and unselectable, which reads as the app having broken the rule.
     */
    val anchorFloor = remember(initial) {
        val today = Dates.today()
        val original = initial?.startsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (original != null && original.isBefore(today)) original else today
    }

    val ofKind = remember(categories, kind) { categories.filter { it.kind == kind && it.deleted == 0 } }
    val amountMinor = Money.parseToMinor(amountText)

    // Which single thing is still missing, in the order a person fills the form
    // in. Naming it beats grey-and-silent: a disabled control that will not say
    // what it wants is the exact bug this screen's sibling had.
    val blocker: Int? = when {
        amountMinor <= 0 -> R.string.add_needs_amount
        accountId == null -> R.string.add_needs_account
        categoryId == null -> R.string.add_needs_category
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KindRow(
                    selected = kind,
                    onSelect = {
                        kind = it
                        // Expense and income categories are different lists, so
                        // a category chosen under one kind is meaningless under
                        // the other. Same rule as AddViewModel.setKind.
                        categoryId = null
                    },
                )
                // Above the first field, not below the last one.
                //
                // The dialog's content scrolls and its buttons do not, so a hint
                // sitting under the note field is off-screen at exactly the
                // moment someone is staring at a greyed-out Save wondering why —
                // which is the bug this hint exists to prevent, reintroduced one
                // layout lower down.
                blocker?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.recurring_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                PickerField(
                    label = stringResource(R.string.add_pick_account),
                    value = accounts.firstOrNull { it.id == accountId }?.name,
                    options = accounts.map { it.id to it.name },
                    onSelect = { accountId = it },
                )
                Spacer(Modifier.height(12.dp))
                PickerField(
                    label = stringResource(R.string.add_pick_category),
                    value = ofKind.firstOrNull { it.id == categoryId }?.name,
                    options = ofKind.map { it.id to it.name },
                    onSelect = { categoryId = it },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.recurring_how_often),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                // FlowRow, not Row. Three chips reading "Every month" / "Every
                // week" / "Every year" overflow a dialog's width, and a Row does
                // not wrap — it compresses the last child until "Every year"
                // renders as a vertical column of single letters. Wrapping also
                // survives translation, which a hand-tuned width would not.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Recurrence.FREQUENCIES.forEach { option ->
                        FilterChip(
                            selected = freq == option,
                            onClick = { freq = option },
                            label = { Text(stringResourceForFreq(option)) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                DateField(
                    label = stringResource(R.string.recurring_first_on),
                    value = Dates.dayLabel(startsOn.toString()),
                    onClick = { picking = DateField.Start },
                    onClear = null,
                )
                Spacer(Modifier.height(8.dp))
                DateField(
                    label = stringResource(R.string.recurring_until),
                    value = endsOn?.let { Dates.dayLabel(it.toString()) }
                        ?: stringResource(R.string.recurring_no_end),
                    onClick = { picking = DateField.End },
                    onClear = endsOn?.let { { endsOn = null } },
                )
                if (freq == Recurrence.MONTHLY && startsOn.dayOfMonth >= 29) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.recurring_month_end_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = blocker == null,
                onClick = {
                    onSave(
                        RuleDraft(
                            kind = kind,
                            amountMinor = amountMinor,
                            accountId = accountId!!,
                            categoryId = categoryId!!,
                            note = note.trim().takeIf { it.isNotBlank() },
                            freq = freq,
                            startsOn = startsOn,
                            endsOn = endsOn,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )

    picking?.let { field ->
        RuleDatePickerDialog(
            selected = if (field == DateField.Start) startsOn else endsOn ?: startsOn,
            // An end date before the anchor is a rule that never fires, and the
            // server rejects it outright. Cheaper to make it unselectable.
            notBefore = if (field == DateField.End) startsOn else anchorFloor,
            onPick = { date ->
                if (field == DateField.Start) {
                    startsOn = date
                    // Dragging the anchor past the end date would strand the
                    // rule in the state the server refuses.
                    if (endsOn != null && endsOn!!.isBefore(date)) endsOn = null
                } else {
                    endsOn = date
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private enum class DateField { Start, End }

@Composable
private fun KindRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("expense" to R.string.add_expense, "income" to R.string.add_income)
            .forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(stringResource(label)) },
                )
            }
    }
}

@Composable
private fun PickerField(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Box {
            Text(
                text = value ?: "—",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    value: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            onClear?.let {
                TextButton(onClick = it) { Text(stringResource(R.string.recurring_clear_end)) }
            }
        }
    }
}

/**
 * The mirror image of the add screen's picker, which blocks the future because
 * an expense has already happened. A rule is the opposite kind of thing — it is
 * about what has NOT happened yet, so "starts next month" is ordinary and the
 * floor sits at the near end instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleDatePickerDialog(
    selected: LocalDate,
    notBefore: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableYear(year: Int): Boolean =
                notBefore == null || year >= notBefore.year

            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                notBefore == null || !utcMillisToLocalDate(utcTimeMillis).isBefore(notBefore)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(utcMillisToLocalDate(it)) }
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/**
 * The picker reports UTC midnight for the day the user tapped. Reading it back
 * in the local zone can land on the day before, so it is read in UTC and only
 * then treated as a plain calendar date.
 */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

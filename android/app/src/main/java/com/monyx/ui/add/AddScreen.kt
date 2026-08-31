package com.monyx.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Launching the app must land directly on the numeric keypad. Type the amount,
 * tap a category, tap save. Account, date and author come from defaults.
 *
 * Target: two taps plus the amount, under five seconds from unlocking the phone.
 * Anything that stretches that — an animation, a save confirmation, a
 * network requirement — is a bug, not a matter of taste.
 */
@Composable
fun AddScreen(
    viewModel: AddViewModel,
    memberId: String?,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val expenseCategories by viewModel.expenseCategories.collectAsStateWithLifecycle()
    val incomeCategories by viewModel.incomeCategories.collectAsStateWithLifecycle()

    viewModel.ensureDefaultAccount(accounts)

    val categories = when (state.kind) {
        EntryKind.Income -> incomeCategories
        else -> expenseCategories
    }
    // Nesting is one level deep, and BOTH levels are tappable: a parent that has
    // children is still a category people file to ("Dom" as well as
    // "Dom > Remonty"). Children follow their parent so the grid reads in
    // family order rather than by raw sort key.
    val selectable = remember(categories) {
        val byParent = categories.filter { it.parentId != null }.groupBy { it.parentId }
        categories
            .filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .flatMap { parent ->
                listOf(parent) + byParent[parent.id].orEmpty().sortedBy { it.sortOrder }
            }
    }

    // A subcategory is drawn in its parent's colour, so a family of categories
    // reads as one group in the grid instead of a scatter of unrelated hues.
    val colorOf: (CategoryEntity) -> Color = remember(categories) {
        val byId = categories.associateBy { it.id }
        val resolve: (CategoryEntity) -> Color = { c ->
            Palette.colorForChild(c.color, byId[c.parentId]?.color, c.parentId, c.id)
        }
        resolve
    }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        KindSelector(selected = state.kind, onSelect = viewModel::setKind)

        AmountDisplay(state = state)

        ContextRow(
            state = state,
            accounts = accounts,
            onPickAccount = { showAccountPicker = true },
            onPickDate = { showDatePicker = true },
        )

        CategoryGrid(
            categories = selectable,
            selectedId = state.categoryId,
            colorOf = colorOf,
            onSelect = viewModel::selectCategory,
            modifier = Modifier.weight(1f),
        )

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::setNote,
            label = { Text(stringResource(R.string.add_note_hint)) },
            singleLine = true,
            // A note is a sentence fragment ("Zakupy na weekend"), so the
            // keyboard opens shifted. A hint only: shift still wins for "iPhone".
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        Keypad(
            onKey = { action ->
                if (action == KeyAction.Confirm) {
                    if (state.amount.hasPendingOperation) {
                        viewModel.onKey(action)
                    } else if (state.canSave && memberId != null) {
                        viewModel.save(memberId, onSaved)
                    }
                } else {
                    viewModel.onKey(action)
                }
            },
            confirmEnabled = state.amount.hasPendingOperation || (state.canSave && memberId != null),
            modifier = Modifier.height(280.dp),
        )
    }

    if (showAccountPicker) {
        AccountPickerDialog(
            accounts = accounts,
            onPick = { viewModel.selectAccount(it); showAccountPicker = false },
            onDismiss = { showAccountPicker = false },
        )
    }
    if (showDatePicker) {
        DayPickerDialog(
            selected = state.date,
            onPick = { viewModel.setDate(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun KindSelector(selected: EntryKind, onSelect: (EntryKind) -> Unit) {
    val options = listOf(
        EntryKind.Expense to R.string.add_expense,
        EntryKind.Income to R.string.add_income,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (kind, label) ->
            val active = kind == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .clickable { onSelect(kind) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * A Text, not a TextField. There is no focus to request and no keyboard to wait
 * for, which is the whole point.
 */
@Composable
private fun AmountDisplay(state: AddUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // The running total, not just the sign: "60,00 +" while the second
        // operand is being typed, so the amount so far never leaves the screen.
        state.amount.operatorLabel?.let { op ->
            val soFar = state.amount.pendingDisplay(LocalConfiguration.current.locales[0])
            Text(
                text = if (soFar == null) op else "$soFar $op",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.amount.display(LocalConfiguration.current.locales[0]),
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.currency_suffix),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ContextRow(
    state: AddUiState,
    accounts: List<AccountEntity>,
    onPickAccount: () -> Unit,
    onPickDate: () -> Unit,
) {
    val accountName = accounts.firstOrNull { it.id == state.accountId }?.name
        ?: stringResource(R.string.add_needs_account)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextChip(
            icon = { Icon(Icons.Filled.Wallet, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = accountName,
            onClick = onPickAccount,
        )
        ContextChip(
            icon = { Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = when (state.date) {
                Dates.today() -> stringResource(R.string.add_today)
                Dates.today().minusDays(1) -> stringResource(R.string.add_yesterday)
                else -> Dates.dayLabel(state.date.toString())
            },
            onClick = onPickDate,
        )
    }
}

@Composable
private fun ContextChip(
    icon: (@Composable () -> Unit)?,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    colorOf: (CategoryEntity) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            val color = colorOf(category)
            val selected = category.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(category.id) },
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (selected) color else color.copy(alpha = 0.16f))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, color, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Palette.icon(category.icon),
                        contentDescription = null,
                        tint = if (selected) Color.White else color,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category.name,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AccountPickerDialog(
    accounts: List<AccountEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pick_account)) },
        text = {
            Column {
                accounts.forEach { account ->
                    Text(
                        text = account.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(account.id) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // A month grid. The previous fourteen-day list could not reach a date at the
    // end of last month, which is exactly when someone is catching up on
    // receipts. Future days stay unselectable — an expense has already happened.
    val today = Dates.today()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !utcMillisToLocalDate(utcTimeMillis).isAfter(today)

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(utcMillisToLocalDate(it)) } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    ) {
        DatePicker(state = state, title = null)
    }
}

/**
 * DatePicker hands back a UTC midnight, always. Reading it back in the household
 * timezone would land on the previous day for anywhere east of Greenwich —
 * Warsaw included — so it is read as UTC and only then treated as a plain date.
 */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

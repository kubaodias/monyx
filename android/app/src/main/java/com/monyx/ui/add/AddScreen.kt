package com.monyx.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.monyx.data.Money
import com.monyx.ui.JumpToToday
import com.monyx.ui.settings.RecurringEditor
import com.monyx.ui.settings.RuleSeed
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
 *
 * The order down the screen is kind, then account and date, then the amount.
 * The amount sits directly above the categories and directly above the keypad
 * that types it, which is where the eye already is; the two chips that are
 * almost always left on their defaults are out of that path rather than
 * through the middle of it.
 */

/**
 * Which input the bottom of the screen is currently giving to.
 *
 * Only one of the two can be useful at a time, and neither is useful all of the
 * time. The keypad is 236dp of screen that means nothing once the amount is
 * typed, and it used to sit there through the category tap and under the note's
 * own keyboard — so the grid was scrolling four rows at a time in a window it
 * did not need to be sharing.
 */
private enum class Editing {
    /** Typing the amount. */
    Amount,

    /** Typing the note; the system keyboard is up and the keypad is not. */
    Note,

    /** Neither — the category grid has the screen to itself. */
    Nothing,
}
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
    var editing by remember { mutableStateOf(Editing.Amount) }
    var seed by remember { mutableStateOf<RuleSeed?>(null) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Going back to the keypad has to take the system keyboard down with it,
    // and the only way to do that is to drop the note field's focus — a
    // keyboard hidden while its field is still focused comes straight back on
    // the next recomposition.
    fun editAmount() {
        focusManager.clearFocus()
        keyboard?.hide()
        editing = Editing.Amount
    }

    // The keypad hands the half-typed transaction to the rule editor rather
    // than making anyone type it twice. A rule does not backfill, so an anchor
    // in the past is pulled forward to today; a date in the FUTURE is kept,
    // because "this starts next month" is an ordinary thing to mean.
    seed?.let { open ->
        RecurringEditor(
            seed = open,
            accounts = accounts,
            categories = expenseCategories + incomeCategories,
            onDismiss = { seed = null },
            onSave = { draft ->
                memberId?.let { viewModel.saveRecurring(draft, it, onSaved) }
                seed = null
                editing = Editing.Amount
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        KindSelector(selected = state.kind, onSelect = viewModel::setKind)

        ContextRow(
            state = state,
            accounts = accounts,
            onPickAccount = { showAccountPicker = true },
            onPickDate = { showDatePicker = true },
            onMakeRecurring = {
                focusManager.clearFocus()
                seed = RuleSeed(
                    kind = state.kind.wire,
                    amountMinor = state.amountMinor.takeIf { it > 0 },
                    accountId = state.accountId,
                    categoryId = state.categoryId,
                    note = state.note.trim().takeIf { it.isNotBlank() },
                    startsOn = maxOf(state.date, Dates.today()),
                )
            },
        )

        AmountDisplay(
            state = state,
            keypadHidden = editing != Editing.Amount,
            onClick = { editAmount() },
        )

        CategoryGrid(
            categories = selectable,
            selectedId = state.categoryId,
            colorOf = colorOf,
            onSelect = {
                viewModel.selectCategory(it)
                // The keypad only stands down once it has done its job. With an
                // amount already typed, the grid is what the screen is for and
                // the keys are 236dp in its way. With no amount, picking the
                // category first is somebody working in the other order — and
                // taking the keys away at exactly the moment they are needed
                // next would be the opposite of helping.
                if (state.amountMinor > 0) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    editing = Editing.Nothing
                } else {
                    editAmount()
                }
            },
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .onFocusChanged { if (it.isFocused) editing = Editing.Note },
        )

        if (editing == Editing.Amount) {
            Keypad(
                onKey = viewModel::onKey,
                equalsEnabled = state.amount.hasPendingOperation,
                modifier = Modifier.height(236.dp),
            )
        }

        SaveBar(
            state = state,
            hasMember = memberId != null,
            onSave = {
                memberId?.let {
                    viewModel.save(it, onSaved)
                    editAmount()
                }
            },
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
 * The one control that commits the expense, and it says so in words.
 *
 * It used to be the tick in the corner of the keypad — filled, primary-coloured,
 * sitting exactly where a calculator puts "=". So the key that ended the ENTRY
 * looked identical to the key that ended the SUM, and nothing on screen said
 * which of the two a tap was about to do. The tick is now honestly "=", grey
 * with the other function keys, and this is the only filled thing on the screen.
 *
 * When it cannot save it says what is missing instead of sitting there dead:
 * saving needs an amount, an account AND a category, and the third one used to
 * be invisible — you typed a price, typed a note, and the only feedback was a
 * grey button that would not explain itself.
 *
 * The amount is on the label because a save button is the last thing read before
 * money is written down, and "47,50 zł" there catches the mis-tap that the verb
 * alone never would. The verb does NOT name the kind: Expense/Income is a
 * segmented control at the top of the same screen, already showing which one is
 * selected, and repeating it on the button only made the button longer.
 */
@Composable
private fun SaveBar(state: AddUiState, hasMember: Boolean, onSave: () -> Unit) {
    // In the order the screen is filled in, so it names the NEXT thing to do
    // rather than an arbitrary one of several.
    val blocker = when {
        state.amountMinor <= 0 -> stringResource(R.string.add_needs_amount)
        state.accountId == null -> stringResource(R.string.add_needs_account)
        state.categoryId == null -> stringResource(R.string.add_needs_category)
        else -> null
    }
    val label = blocker ?: stringResource(
        R.string.add_save_amount,
        // The evaluated total, not the digits on screen: with "60 +" pending and
        // 40 typed, this reads 100,00 — which is what pressing it will write.
        Money.formatWithCurrency(state.amountMinor),
    )

    Button(
        onClick = onSave,
        enabled = blocker == null && hasMember,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            // Material greys disabled text to 38% opacity, which is fine for a
            // label nobody needs to read and wrong for one that is the entire
            // instruction. Disabled here means "unfinished", not "unavailable".
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(50.dp),
    ) {
        Text(text = label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A Text, not a TextField. There is no focus to request and no keyboard to wait
 * for, which is the whole point.
 *
 * It is tappable, and that tap is the only way back to a hidden keypad — so
 * when the keypad is hidden it grows a small dialpad glyph. Without it the
 * amount is a heading that happens to be a button, which nobody would guess.
 */
@Composable
private fun AmountDisplay(state: AddUiState, keypadHidden: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
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
            if (keypadHidden) {
                Icon(
                    imageVector = Icons.Filled.Dialpad,
                    contentDescription = stringResource(R.string.add_show_keypad),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).padding(bottom = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextRow(
    state: AddUiState,
    accounts: List<AccountEntity>,
    onPickAccount: () -> Unit,
    onPickDate: () -> Unit,
    onMakeRecurring: () -> Unit,
) {
    val accountName = accounts.firstOrNull { it.id == state.accountId }?.name
        ?: stringResource(R.string.add_needs_account)

    // FlowRow, not Row: three chips, one of which is a whole phrase in two
    // languages, overflow a narrow screen — and a Row does not wrap, it
    // squeezes the last child until its label breaks between letters.
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
        // Rent and the phone bill get typed once by hand before anyone thinks
        // "this happens every month". Catching that thought here, with the
        // amount and the category already filled in, is the difference between
        // setting up a rule and going to Settings to set up a rule.
        ContextChip(
            icon = { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = stringResource(R.string.add_make_recurring),
            onClick = onMakeRecurring,
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
    // A month grid, with no floor and no ceiling. It used to refuse the future
    // on the grounds that an expense has already happened — true of a receipt
    // and false of the standing order leaving on Friday, the deposit due next
    // week, the flights already booked. A household budget is as much about
    // what is coming as what went, and the month totals are the place it has
    // to show up.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
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
        DatePicker(state = state, title = { JumpToToday(state) })
    }
}

/**
 * DatePicker hands back a UTC midnight, always. Reading it back in the household
 * timezone would land on the previous day for anywhere east of Greenwich —
 * Warsaw included — so it is read as UTC and only then treated as a plain date.
 */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

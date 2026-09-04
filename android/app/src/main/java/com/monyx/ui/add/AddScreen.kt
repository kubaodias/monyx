package com.monyx.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.ui.settings.RecurringEditor
import com.monyx.ui.settings.RuleSeed
import com.monyx.ui.theme.Palette

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

        AmountDisplay(amount = state.amount, onClick = { editAmount() })

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
 * What the keypad screen is still missing, in the order the screen is filled
 * in, handed to the shared save bar. Naming the NEXT thing to do beats naming
 * an arbitrary one of several.
 */
@Composable
private fun SaveBar(state: AddUiState, hasMember: Boolean, onSave: () -> Unit) {
    SaveBar(
        blocker = when {
            state.amountMinor <= 0 -> R.string.add_needs_amount
            state.accountId == null -> R.string.add_needs_account
            state.categoryId == null -> R.string.add_needs_category
            else -> null
        },
        amountMinor = state.amountMinor,
        // No member id means enrolment has not landed; there is nobody to
        // credit the row to and saving would write an orphan.
        enabled = hasMember,
        onSave = onSave,
    )
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

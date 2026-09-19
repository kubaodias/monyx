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
import androidx.compose.material3.LocalContentColor
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
 * The keypad, first thing. Type the amount, tap a category, tap save. Account,
 * date and author come from defaults.
 *
 * It is no longer what the app opens on — that is the summary now, because
 * opening a ledger is a question more often than it is an entry — but it is one
 * tap from anywhere in the bottom bar and zero from the launcher long-press,
 * and nothing below this line changes because of that.
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
 * time. The keypad is 188dp of screen that means nothing once the amount is
 * typed, and it used to sit there through the category tap and under the note's
 * own keyboard — so the grid was scrolling four rows at a time in a window it
 * did not need to be sharing.
 *
 * The note FIELD is always on screen now; this is only about which keyboard is
 * up. Starting with the note is allowed — tapping it takes the keys down — and
 * so is starting with a category.
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
    // Handed to the grid whole, both levels of it. The grid shows the roots and
    // opens one family at a time — see CategoryGrid — and BOTH levels stay
    // tappable: a parent that has children is still a category people file to
    // ("Dom" as well as "Dom > Remonty").
    val selectable = remember(categories) { categories.sortedBy { it.sortOrder } }

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
        // Account first, then which way the money goes, then the amount.
        //
        // The account is the thing you check and almost never change, and it
        // belongs where the eye lands before anything has been typed — putting
        // Expense/Income above it made the first question on the screen the one
        // that is already answered nine times in ten. Everything below is
        // unchanged and still runs top to bottom in the order it is filled in:
        // amount, category, note.
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

        KindSelector(selected = state.kind, onSelect = viewModel::setKind)

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

        // Last, lowest, and ALWAYS there.
        //
        // It used to appear only once the keypad stood down, which made the
        // screen a sequence: amount first, then everything else. That is the
        // common order and it is not the only one — "wpisz co to było, zanim
        // zapomnisz" is a real way to start, and so is tapping the category you
        // are standing in front of. Nothing here needs to be filled in before
        // anything else, and the screen should not have implied otherwise.
        //
        // The space it costs came out of the keypad and the save bar rather
        // than out of the grid, which is what makes it affordable.
        //
        // It is also the same field as the edit sheet's, down to the padding.
        // Two screens that both mean "anything to add?" have to look the same
        // or the second one reads as a different question.
        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::setNote,
            label = { Text(stringResource(R.string.add_note_hint)) },
            singleLine = true,
            // A note is a sentence fragment ("Zakupy na weekend"), so the
            // keyboard opens shifted. A hint only: shift still wins for
            // "iPhone".
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                // The keys and the note are both bottom-of-screen inputs and
                // only one of them can be the one being answered. Tapping
                // the amount comes back the other way, and editAmount()
                // drops this field's focus first so the system keyboard goes
                // with it.
                .onFocusChanged { if (it.isFocused) editing = Editing.Note },
        )

        if (editing == Editing.Amount) {
            Keypad(
                onKey = viewModel::onKey,
                equalsEnabled = state.amount.hasPendingOperation,
                modifier = Modifier.height(188.dp),
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
            selectedId = state.accountId,
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
 * The save bar, once there is something to save.
 *
 * It used to stand there from the first frame saying "Podaj kwotę", then
 * "Wybierz kategorię" — narrating a screen that is already showing both. The
 * amount is the largest thing on it and the categories fill the middle of it;
 * a button spelling out that neither has been touched yet costs 62dp to tell
 * you what you are looking at. So while those two are what is missing, there is
 * no bar at all, and it arrives — filled, green, with the figure on it — at the
 * moment the transaction becomes savable. An arriving button is a better
 * signal than a dead one, and the space goes to the category grid.
 *
 * The two blockers that are NOT on screen still get said out loud: no account
 * and no member are states nothing else here would explain, and a screen that
 * simply refused to save would be a bug report.
 */
@Composable
private fun SaveBar(state: AddUiState, hasMember: Boolean, onSave: () -> Unit) {
    val unfinished = state.amountMinor <= 0 || state.categoryId == null
    if (unfinished && state.accountId != null && hasMember) return

    SaveBar(
        blocker = when {
            state.accountId == null -> R.string.add_needs_account
            state.amountMinor <= 0 -> R.string.add_needs_amount
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
    val account = accounts.firstOrNull { it.id == state.accountId }
    // Palette.colorFor, the same call the overview's account buttons make, so
    // "Gotówka" is one green in both places rather than a green and a blue.
    val accountColor = account?.let { Palette.colorFor(it.color, it.id) }

    // FlowRow, not Row: three chips, one of which is a whole phrase in two
    // languages, overflow a narrow screen — and a Row does not wrap, it
    // squeezes the last child until its label breaks between letters.
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ContextChip(
            icon = {
                Icon(
                    // The account's own icon where it has one — a card for the
                    // card, a wallet for the cash — falling back to the generic
                    // wallet rather than to Palette's Category catch-all.
                    imageVector = account?.icon?.let { Palette.icon(it) } ?: Icons.Filled.Wallet,
                    contentDescription = null,
                    tint = accountColor ?: LocalContentColor.current,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = account?.name ?: stringResource(R.string.add_needs_account),
            accent = accountColor,
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

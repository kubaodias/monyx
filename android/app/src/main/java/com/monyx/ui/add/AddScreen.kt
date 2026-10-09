package com.monyx.ui.add

import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.Currency
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.ui.rememberOfferedCurrencies
import com.monyx.ui.settings.RecurringEditor
import com.monyx.ui.settings.RuleSeed
import com.monyx.ui.theme.Palette
import kotlinx.coroutines.launch

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
    /**
     * The bottom bar's middle button, which this screen borrows as its save
     * button for as long as it is up. See [AddSaveSlot]: the save bar that used
     * to sit directly above it is gone, and the 62dp is the category grid's.
     */
    saveSlot: AddSaveSlot,
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
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(Editing.Amount) }

    // Tapping the note has to leave the note on screen. The keyboard comes up
    // and the grid's window shrinks under it, and with a family open the note
    // — the grid's last item — ends up below the fold, typed into blind. So
    // follow it down: on the tap, and again as the keyboard's height settles,
    // because every frame of its slide takes a little more of the window.
    val gridState = rememberLazyGridState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(editing, imeBottom) {
        if (editing == Editing.Note) {
            val last = gridState.layoutInfo.totalItemsCount - 1
            if (last >= 0) gridState.animateScrollToItem(last)
        }
    }
    var seed by remember { mutableStateOf<RuleSeed?>(null) }

    // Set by a tap on a save button that could not save, and never cleared by
    // hand: [blocker] goes null the moment the gap is filled, which takes the
    // line off screen with it.
    var refused by remember { mutableStateOf(false) }

    // The family the amount line is carrying, and the one the grid is not
    // listing. Hoisted because the mark's own tap needs its id.
    val mark = remember(state.categoryId, categories, colorOf) {
        categoryMarkOf(categories, state.categoryId, colorOf)
    }

    /**
     * What is still missing, in the order it is asked for.
     *
     * A missing account comes first and is the one case shown without being
     * asked for — a household with no account yet has nothing else on screen to
     * explain a button that will not act. The other two are both visible as
     * emptiness (the amount is the largest thing here, the categories fill the
     * middle), so they are named only once somebody has tapped and been
     * refused.
     */
    val blocker = when {
        state.accountId == null -> R.string.add_needs_account
        state.amountMinor <= 0 -> R.string.add_needs_amount
        state.categoryId == null -> R.string.add_needs_category
        else -> null
    }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Going back to the keypad has to take the system keyboard down with it,
    // and the only way to do that is to drop the note field's focus — a
    // keyboard hidden while its field is still focused comes straight back on
    // the next recomposition.
    fun editAmount() {
        // force, because a field that has the focus is entitled to refuse to
        // give it up, and a keyboard hidden over a field that still has it
        // comes straight back on the next recomposition.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        editing = Editing.Amount
    }

    // What the bar's middle button does while this screen is up.
    //
    // Saving needs an amount, an account AND a category, and it needs a member
    // to credit the row to — no member id means enrolment has not landed and
    // saving would write an orphan. The button is green throughout (see
    // [AddSaveSlot]); what changes is which of the two answers a tap gets.
    //
    // Withdrawn while the rule editor is open: it covers this screen with a
    // save button of its own, and the bar's would commit the transaction behind
    // it. On dispose too, so leaving the tab puts "Dodaj" back even though the
    // bar is composed by somebody else.
    SideEffect {
        if (seed == null) {
            saveSlot.offer(
                enabled = memberId != null && state.canSave,
                onSave = {
                    memberId?.let {
                        viewModel.save(it, onSaved)
                        editAmount()
                        refused = false
                    }
                },
                // The bar has nowhere to put a sentence, so the refusal is
                // answered down here. See [blocker].
                onRefused = { refused = true },
            )
        } else {
            saveSlot.withdraw()
        }
    }
    DisposableEffect(Unit) { onDispose { saveSlot.withdraw() } }

    // The keypad hands the half-typed transaction to the rule editor rather
    // than making anyone type it twice, date included — in both directions now.
    // A past date used to be pulled forward to today on the grounds that a rule
    // does not backfill, which was never true of the machinery and is not true
    // of the form any more either: an anchor in the past fills in every
    // occurrence through today, and the editor says how many before it saves.
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
                    startsOn = state.date,
                )
            },
        )

        // The shared control, so this screen and the rule editor ask the
        // question with one voice. See [KindSelector] in EntryControls.
        KindSelector(
            selected = state.kind.wire,
            onSelect = { viewModel.setKind(EntryKind.of(it)) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        // The ENTRY's unit, which follows the account until the symbol beside
        // the figure is tapped. Typing 50 for a euro purchase reads "50,00 €"
        // whichever card it settled on, and that symbol is the control that
        // changes it.
        AmountDisplay(
            amount = state.amount,
            onClick = { editAmount() },
            currency = state.currencyOr(
                Currency.of(accounts.firstOrNull { it.id == state.accountId }?.currency),
            ),
            onPickCurrency = { showCurrencyPicker = true },
            // The chosen family, beside the figure, where the grid cannot
            // scroll it away — and where the grid no longer lists it.
            categoryMark = mark,
            // Back to the families. The grid is scrolled past them whenever a
            // family is open, and this is the control that is about the family.
            //
            // It takes the note's keyboard down on the way, because a keyboard
            // is the other thing that can be standing between a thumb and the
            // families. It does NOT touch which input is live: changing that
            // re-pins the grid, and a pin and a scroll racing each other is
            // how a list ends up somewhere neither of them meant.
            onMarkClick = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                // Files the row on the family, which drops any subcategory
                // under it: the family is what the mark says, so a tap on it
                // is "this one, not one of its children". Harmless when the
                // family is already the choice — the same id goes back in.
                mark?.id?.let(viewModel::selectCategory)
                scope.launch { gridState.animateScrollToItem(0) }
            },
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
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    editing = Editing.Nothing
                } else {
                    editAmount()
                }
            },
            // Last, below the subcategories, and always there: amount, note and
            // category can be filled in in any order. It lives INSIDE the grid
            // so that it scrolls with the categories instead of covering them —
            // see CategoryGrid's footer.
            footer = {
                NoteField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    // The keys and the note are both bottom-of-screen inputs and
                    // only one of them can be the one being answered. Tapping the
                    // amount comes back the other way, and editAmount() drops
                    // this field's focus first so the system keyboard goes with it
                    // — which is also what takes [Editing.Note] off, so the blur
                    // is not acted on here.
                    onFocusChanged = { focused -> if (focused) editing = Editing.Note },
                )
            },
            // Anything at the bottom of the screen — the keys, or the note's
            // own keyboard — leaves the grid too short to show a family below
            // the roots, so the family takes a window of its own. See
            // [CategoryGrid.pinFamily].
            pinFamily = editing != Editing.Nothing,
            footerHeight = NoteFieldHeight,
            state = gridState,
            modifier = Modifier.weight(1f),
        )

        if (editing == Editing.Amount) {
            Keypad(
                onKey = viewModel::onKey,
                equalsEnabled = state.amount.hasPendingOperation,
                modifier = Modifier.height(KeypadHeight),
            )
        }

        // One line, and only when it has something to say: a tap went
        // unanswered, or there is no account to file anything to. A line of
        // text rather than the 62dp bar this replaced, which stood there
        // narrating an empty screen from the first frame.
        if (blocker != null && (refused || state.accountId == null)) {
            Text(
                text = stringResource(blocker),
                style = MaterialTheme.typography.labelLarge,
                // The error colour, because this line only ever appears when
                // something has refused to happen. In the theme's grey it read
                // as a caption of the screen rather than as the answer to the
                // tap that had just been ignored.
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showCurrencyPicker) {
        val account = accounts.firstOrNull { it.id == state.accountId }
        CurrencyPickerDialog(
            selected = state.currencyOr(Currency.of(account?.currency)),
            accountCurrency = Currency.of(account?.currency),
            // What this household has said it uses, plus whatever its accounts
            // are in and whatever this entry has already been set to.
            options = rememberOfferedCurrencies(
                accounts.map { it.currency } + state.currency?.code,
            ),
            onPick = {
                viewModel.selectCurrency(it)
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
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

/**
 * How tall [NoteField] is: its own 44dp plus the 4dp it is padded by, twice.
 *
 * Stated rather than measured because [CategoryGrid] has to leave room for it
 * in a window it is dividing up, and a lazy grid cannot ask an item its height
 * before it has one. The field's height is fixed, so this is a fact and not an
 * estimate — but it is a fact in two places, so they are named together.
 */
internal val NoteFieldHeight = 52.dp

/**
 * The note, at 44dp rather than a text field's usual 56.
 *
 * The full-size field carries a floating label, and the label is what costs the
 * height: it needs a line of its own once there is text under it. A note is one
 * short line wanted on one transaction in ten, so it gets a hint instead — the
 * word "Notatka" in the empty box, gone the moment anything is typed — and the
 * same outline every other field in the app has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    /**
     * Both edges, not just the gain.
     *
     * The caller has to lift this field above the keyboard, and the keyboard is
     * only there while the field has the focus — so a callback that fires on
     * focus and stays silent on blur leaves the caller holding a flag it cannot
     * clear. The edit sheet inferred the blur from the IME inset instead, and
     * got it wrong in a way that cost the whole behaviour; see its comment.
     */
    onFocusChanged: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        // A note is a sentence fragment ("Zakupy na weekend"), so the keyboard
        // opens shifted. A hint only: shift still wins for "iPhone".
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .height(44.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interaction,
            placeholder = { Text(stringResource(R.string.add_note_hint)) },
            colors = colors,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

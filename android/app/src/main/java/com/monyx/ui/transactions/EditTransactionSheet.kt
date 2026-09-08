package com.monyx.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.TransactionEntity
import com.monyx.ui.add.AccountPickerDialog
import com.monyx.ui.add.AmountDisplay
import com.monyx.ui.add.AmountInput
import com.monyx.ui.add.CategoryGrid
import com.monyx.ui.add.ContextChip
import com.monyx.ui.add.DayPickerDialog
import com.monyx.ui.add.KeyAction
import com.monyx.ui.add.Keypad
import com.monyx.ui.add.SaveBar
import com.monyx.ui.theme.Palette
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the caller gets back when Save is tapped. */
data class TransactionEdit(
    val amountMinor: Long,
    val categoryId: String?,
    val accountId: String,
    val note: String,
    val occurredAtMs: Long,
)

/**
 * Editing an existing transaction: amount, category, account, note and date.
 *
 * It is the add screen, and that is the whole design. Same amount, tapped to
 * bring up the same keypad full width along the bottom; same four-column grid
 * of categories in their own colours; same account and date chips opening the
 * same two pickers; same save bar that names what is missing. Editing a row
 * and writing one are the same job, and the editor used to answer it in a
 * different language — an amount in a text field with the system keyboard over
 * it, categories as a strip of 40dp circles you had to scroll sideways, the
 * account as a chip row that existed nowhere else in the app.
 *
 * A bottom sheet rather than a screen of its own, because it is reached from
 * three places that all want to stay where they are underneath: a row in
 * History, a row in the Overview's recent list, and any field on the voice
 * summary. It fills the height, so it is a screen in every way that matters
 * except that dismissing it puts you back exactly where you were.
 *
 * Delete lives here, and keeps its confirmation: a destructive change to a
 * ledger two people share does not go through on one tap, and the row it
 * removes may be one the other person entered.
 *
 * The pending and rejected badges came across from the dialog. They are sync
 * state and this is the only screen that shows it — a row the server refused
 * has to be visible somewhere.
 *
 * Kind is deliberately NOT editable. Switching an expense to income would move
 * the row to a different category list, and every category already chosen for
 * it would be wrong — delete and re-enter is both clearer and one tap shorter.
 * The spoken correction grammar DOES change it, because it re-chooses the
 * category in the same breath.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTransactionSheet(
    original: TransactionEntity,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionEdit) -> Unit,
    onDelete: (String) -> Unit,
) {
    // The same object the keypad builds on the add screen, so a typed amount
    // and an edited one go through identical arithmetic.
    var amount by remember(original.id) { mutableStateOf(AmountInput.ofMinor(original.amountMinor)) }
    var categoryId by remember(original.id) { mutableStateOf(original.categoryId) }
    var accountId by remember(original.id) { mutableStateOf(original.accountId) }
    var note by remember(original.id) { mutableStateOf(original.note.orEmpty()) }
    var date by remember(original.id) { mutableStateOf(LocalDate.parse(original.occurredOn)) }

    // The keys are down until the amount is tapped. The row already has an
    // amount — most edits are to the category or the note — so opening on the
    // keypad would put 236dp of digits over the thing usually being changed.
    var keypadUp by remember(original.id) { mutableStateOf(false) }
    var wantNoteFocus by remember(original.id) { mutableStateOf(false) }
    val noteFocus = remember(original.id) { FocusRequester() }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /**
     * Slide out, then tell the caller.
     *
     * Every one of the three ways out of here — close, save, delete — ends with
     * the caller setting its state to null, which takes this composable out of
     * the composition and makes a full-height sheet vanish on a single frame.
     * The swipe and the scrim animate because Material drives those itself;
     * these three have to be asked.
     */
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // Bringing the keypad up has to take the system keyboard down with it, and
    // the only way to do that is to drop the note field's focus — a keyboard
    // hidden while its field is still focused comes straight back on the next
    // recomposition. Same dance as the add screen, for the same reason.
    fun editAmount() {
        focusManager.clearFocus()
        keyboard?.hide()
        keypadUp = true
    }

    /**
     * The other direction, and the reason it exists.
     *
     * Tapping the amount put the keys up over the note field, and nothing on
     * screen took them down again: the only control that did was the category
     * grid, which means changing the category to get at the note — and a
     * transfer has no grid at all, so its note was unreachable for the rest of
     * the sheet's life. The button on the amount row is the way back.
     *
     * The field does not exist yet when this runs, so the focus is asked for
     * rather than taken; [wantNoteFocus] is picked up below, once the field is
     * in the composition and has something to attach a requester to.
     */
    fun editNote() {
        keypadUp = false
        wantNoteFocus = true
    }

    // Only the list matching this row's kind; an expense cannot be filed under a
    // salary category. Both levels are offered and children follow their parent,
    // so the grid reads in family order — exactly as it does on the keypad.
    val selectable = remember(categories, original.kind) {
        val ofKind = categories.filter { it.kind == original.kind }
        val byParent = ofKind.filter { it.parentId != null }.groupBy { it.parentId }
        ofKind
            .filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .flatMap { parent ->
                listOf(parent) + byParent[parent.id].orEmpty().sortedBy { it.sortOrder }
            }
    }

    val colorOf: (CategoryEntity) -> Color = remember(categories) {
        val byId = categories.associateBy { it.id }
        val resolve: (CategoryEntity) -> Color = { c ->
            Palette.colorForChild(c.color, byId[c.parentId]?.color, c.parentId, c.id)
        }
        resolve
    }

    // A closed account is not somewhere to move money to, but the one this row
    // already sits on stays offered — otherwise editing the note of an old
    // expense would silently show no account selected at all.
    val selectableAccounts = remember(accounts, original.accountId) {
        accounts.filter { it.archived == 0 || it.id == original.accountId }
    }

    // Recomputed as the picker changes it, so the chip repaints in the new
    // account's colour rather than staying the colour of the old one.
    val account = selectableAccounts.firstOrNull { it.id == accountId }
    val accountColor = account?.let { Palette.colorFor(it.color, it.id) }

    // Folded, so a sum left mid-entry ("60 + 40" with = never pressed) saves
    // as 100 rather than as the 40 sitting in the field.
    val amountMinor = amount.evaluate().toMinor()
    val isTransfer = original.kind == "transfer"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Full height in one movement. A sheet that opens half-way and has to
        // be dragged up is a sheet whose keypad is off screen.
        sheetState = sheetState,
        // The header below carries the close and the delete, and a handle above
        // it would be a third dismissal affordance stacked on the first two.
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background,
        // Zero here, and the padding applied on the Column below instead.
        //
        // The sheet fills the height, so its top edge is the top of the screen —
        // and the window is edge to edge, which puts the clock and the battery
        // exactly where the close button and the title are. Material's default
        // for this parameter has moved between versions; asking for nothing and
        // then insetting the content by hand is the one arrangement that cannot
        // come out either overlapped or double-padded.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // systemBars, not statusBars: the save bar is the last thing in
                // this Column and the gesture pill is drawn over it otherwise.
                .windowInsetsPadding(WindowInsets.systemBars)
                // The sheet is its own window and inherits nothing from the
                // NavHost, so the note field's keyboard would cover the save
                // button it sits directly above.
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { closeThen(onDismiss) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.settings_cancel),
                    )
                }
                Text(
                    stringResource(R.string.transactions_edit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.transactions_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Sync state, and the only place it is visible. Above the fields
            // rather than below them: "the server refused this" changes how you
            // read everything under it.
            if (original.pending == 1) {
                StatusRow(
                    icon = Icons.Filled.CloudUpload,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = stringResource(R.string.transactions_pending),
                )
            }
            if (original.rejected == 1) {
                StatusRow(
                    icon = Icons.Filled.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    text = stringResource(R.string.transactions_rejected),
                )
            }

            // Which way the money went, as a fact rather than a control, in the
            // slot where the add screen puts the segmented Expense/Income. The
            // reason it is read-only is a paragraph above: switching an expense
            // to income invalidates the category already chosen. No ripple and
            // no border, so nobody taps it expecting a choice.
            if (!isTransfer) {
                Text(
                    text = stringResource(
                        if (original.kind == "income") R.string.add_income else R.string.add_expense,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (original.kind == "income") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ContextChip(
                    icon = {
                        Icon(
                            imageVector = account?.icon?.let { Palette.icon(it) }
                                ?: Icons.Filled.Wallet,
                            contentDescription = null,
                            tint = accountColor ?: LocalContentColor.current,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = account?.name ?: stringResource(R.string.add_needs_account),
                    accent = accountColor,
                    onClick = { showAccountPicker = true },
                )
                ContextChip(
                    icon = {
                        Icon(
                            Icons.Filled.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = when (date) {
                        Dates.today() -> stringResource(R.string.add_today)
                        Dates.today().minusDays(1) -> stringResource(R.string.add_yesterday)
                        else -> Dates.dayLabel(date.toString())
                    },
                    onClick = { showDatePicker = true },
                )
            }

            AmountDisplay(
                amount = amount,
                onClick = { editAmount() },
                // Only while the keys are up — which is exactly when the note
                // is not on screen. A control offering to take you somewhere
                // you are already standing is noise.
                action = if (!keypadUp) {
                    null
                } else {
                    {
                        ContextChip(
                            icon = {
                                Icon(
                                    Icons.Filled.EditNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = stringResource(R.string.add_note_hint),
                            onClick = { editNote() },
                        )
                    }
                },
            )

            if (isTransfer) {
                // A transfer has no category and never had one. The grid would
                // be empty, so the space goes to the note instead.
                Spacer(Modifier.weight(1f))
            } else {
                CategoryGrid(
                    categories = selectable,
                    selectedId = categoryId,
                    colorOf = colorOf,
                    onSelect = {
                        categoryId = it
                        // The keys stand down once they have done their job,
                        // the same bargain the add screen makes: with an amount
                        // already there the grid is what the screen is for.
                        if (amountMinor > 0) {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            keypadUp = false
                        } else {
                            editAmount()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // One gate, and it is the keypad. Tapping the note and then tapping
            // the amount left the field sitting between the grid and the keys
            // with its label still lit and its cursor gone, which reads as a
            // field still taking input — the system keyboard had just been
            // dismissed out from under it. One question at a time at the bottom
            // of the screen: the keys, or the note, never both.
            //
            // It used to also require an amount and a category, which was the
            // add screen's rule imported into a screen it does not fit: a row
            // opened for editing HAS both, so the gates only ever fired on a
            // half-backspaced amount — and then the note button above would take
            // the keys down and reveal nothing.
            if (!keypadUp) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .focusRequester(noteFocus)
                        // The system keyboard and the keypad cannot both have
                        // the bottom of the screen.
                        .onFocusChanged { if (it.isFocused) keypadUp = false },
                )
                // After composition, not during it: the requester has to be
                // attached to a node that exists before it can be asked for
                // anything, and the tap that set this flag happened while the
                // field above was still gated out.
                LaunchedEffect(wantNoteFocus) {
                    if (wantNoteFocus) {
                        noteFocus.requestFocus()
                        keyboard?.show()
                        wantNoteFocus = false
                    }
                }
            }

            if (keypadUp) {
                Keypad(
                    onKey = { action -> amount = amount.press(action) },
                    equalsEnabled = amount.hasPendingOperation,
                    modifier = Modifier.height(236.dp),
                )
            }

            SaveBar(
                blocker = when {
                    amountMinor <= 0 -> R.string.add_needs_amount
                    !isTransfer && categoryId == null -> R.string.add_needs_category
                    else -> null
                },
                amountMinor = amountMinor,
                onSave = {
                    val edit = TransactionEdit(
                        amountMinor = amountMinor,
                        categoryId = categoryId,
                        accountId = accountId,
                        note = note.trim(),
                        occurredAtMs = occurredAtFor(date, original.occurredAt, original.occurredOn),
                    )
                    closeThen { onSave(edit) }
                },
            )
        }
    }

    if (showAccountPicker) {
        AccountPickerDialog(
            accounts = selectableAccounts,
            selectedId = accountId,
            onPick = { accountId = it; showAccountPicker = false },
            onDismiss = { showAccountPicker = false },
        )
    }

    if (showDatePicker) {
        // No floor and no ceiling, the same picker the keypad opens. It used to
        // refuse the future on the grounds that an expense has already
        // happened — true of a receipt and false of the standing order leaving
        // on Friday, the deposit due next week, the flights already booked.
        DayPickerDialog(
            selected = date,
            onPick = { date = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.transactions_delete_confirm_title)) },
            text = { Text(stringResource(R.string.transactions_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    closeThen { onDelete(original.id) }
                }) {
                    Text(
                        stringResource(R.string.transactions_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * Keep the original instant when the day has not changed, so an edit to the note
 * alone does not silently reorder the list. A moved row lands at midday, the
 * same convention the add flow uses.
 */
private fun occurredAtFor(date: LocalDate, originalAt: Long, originalOn: String): Long =
    if (date.toString() == originalOn) {
        originalAt
    } else {
        Dates.startOfDayMillis(date) + 12 * 60 * 60 * 1000
    }

@Composable
private fun StatusRow(icon: ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * One key press against the value. The add screen does the same thing through
 * its ViewModel; here there is no ViewModel to go through, and the mapping is
 * the whole of it.
 */
private fun AmountInput.press(action: KeyAction): AmountInput = when (action) {
    is KeyAction.Digit -> digit(action.value)
    KeyAction.Separator -> separator()
    KeyAction.Backspace -> backspace()
    is KeyAction.Operator -> operator(action.op)
    KeyAction.Equals -> evaluate()
}

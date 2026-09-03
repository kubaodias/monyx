package com.monyx.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.TransactionEntity
import com.monyx.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
 * The only editor in the app, reached from three places — a row in History, a
 * row in the Overview's recent list, and any field on the voice summary. There
 * used to be a detail sheet in front of it in History whose entire content was
 * two buttons, Edit and Delete; both are here now, so the tap that used to open
 * a menu opens the thing the menu led to.
 *
 * Delete lives here for that reason, and keeps its confirmation: a destructive
 * change to a ledger two people share does not go through on one tap, and the
 * row it removes may be one the other person entered.
 *
 * The pending and rejected badges came across with it. They are sync state and
 * this is now the only screen that shows it — a row the server refused has to
 * be visible somewhere, or a rejected change is silently dropped after all.
 *
 * Kind is deliberately NOT editable. Switching an expense to income would move
 * the row to a different category list, and every category already chosen for
 * it would be wrong — delete and re-enter is both clearer and one tap shorter.
 * The spoken correction grammar DOES change it, because it re-chooses the
 * category in the same breath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionDialog(
    original: TransactionEntity,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionEdit) -> Unit,
    onDelete: (String) -> Unit,
) {
    var amountText by remember(original.id) { mutableStateOf(Money.format(original.amountMinor)) }
    var categoryId by remember(original.id) { mutableStateOf(original.categoryId) }
    var accountId by remember(original.id) { mutableStateOf(original.accountId) }
    var note by remember(original.id) { mutableStateOf(original.note.orEmpty()) }
    var date by remember(original.id) { mutableStateOf(LocalDate.parse(original.occurredOn)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Only the list matching this row's kind; an expense cannot be filed under a
    // salary category.
    val ofKind = remember(categories, original.kind) {
        categories.filter { it.kind == original.kind }
    }

    // Which family the row is showing, or null for the roots. It opens on the
    // family the transaction is ALREADY in: somebody editing a row filed under
    // Dom > Remonty should see that, not have to go looking for where it
    // already is.
    var openParent by remember(original.id, ofKind) {
        mutableStateOf(CategoryDrill.openOn(ofKind, original.categoryId))
    }
    val shown = CategoryDrill.shown(ofKind, openParent)
    val colorOf: (CategoryEntity) -> Color = remember(categories) {
        val byId = categories.associateBy { it.id }
        val resolve: (CategoryEntity) -> Color = { c ->
            Palette.colorForChild(c.color, byId[c.parentId]?.color, c.parentId, c.id)
        }
        resolve
    }

    val amountMinor = Money.parseToMinor(amountText)
    val isTransfer = original.kind == "transfer"
    val canSave = amountMinor > 0 && (isTransfer || categoryId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.transactions_edit), modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.transactions_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Sync state, and the only place it is visible. Above the fields
                // rather than below them: "the server refused this" changes how
                // you read everything under it.
                if (original.pending == 1) {
                    StatusRow(
                        icon = Icons.Filled.CloudUpload,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = stringResource(R.string.transactions_pending),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (original.rejected == 1) {
                    StatusRow(
                        icon = Icons.Filled.ErrorOutline,
                        tint = MaterialTheme.colorScheme.error,
                        text = stringResource(R.string.transactions_rejected),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.transactions_edit_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Which way the money went, as a fact rather than a control.
                //
                // Beside the amount because that is what it qualifies: the same
                // "45,00" is money out or money in depending on this, and the
                // dialog has no sign in front of it to say so. It also explains
                // the category list further down, which only ever offers
                // categories of this kind.
                //
                // Read-only, deliberately, and the reason is two dozen lines
                // above: switching an expense to income invalidates the
                // category already chosen. Drawn as a label with no ripple and
                // no border so nobody taps it expecting a choice.
                if (!isTransfer) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            if (original.kind == "income") R.string.add_income else R.string.add_expense,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (original.kind == "income") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        // Labelled, because the row's only text is the date
                        // itself: without this TalkBack announces "3 września"
                        // and never says it can be changed.
                        .clickable(onClickLabel = stringResource(R.string.add_pick_date)) {
                            showDatePicker = true
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(Dates.dayLabel(date.toString()), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.add_pick_account),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A closed account is not somewhere to move money to, but the
                    // one this row already sits on stays offered — otherwise
                    // editing the note of an old expense would silently show no
                    // account selected at all.
                    val selectable = accounts.filter {
                        it.archived == 0 || it.id == original.accountId
                    }
                    items(selectable, key = { it.id }) { account ->
                        ChoiceChip(
                            label = account.name,
                            // The account's own icon key, the same one Settings
                            // draws it with. A wallet for everything would be a
                            // new convention; this is the existing one.
                            icon = Palette.icon(account.icon),
                            selected = account.id == accountId,
                            onClick = { accountId = account.id },
                        )
                    }
                }

                if (!isTransfer) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.add_pick_category),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Only while inside a family. At the top level there is
                        // nowhere to go back to, and a permanently disabled
                        // arrow is furniture.
                        if (openParent != null) {
                            IconButton(onClick = { openParent = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back),
                                )
                            }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 76.dp),
                        ) {
                            items(shown, key = { it.id }) { category ->
                                CategoryPill(
                                    category = category,
                                    tint = colorOf(category),
                                    selected = category.id == categoryId,
                                    // A parent with children opens; a parent
                                    // without them, and every child, selects.
                                    // Inside a family the parent is the first
                                    // pill and selects itself — "Dom" is a
                                    // category people file to, not a heading.
                                    onClick = {
                                        if (openParent == null && CategoryDrill.hasChildren(ofKind, category.id)) {
                                            openParent = category.id
                                        } else {
                                            categoryId = category.id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )




            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        TransactionEdit(
                            amountMinor = amountMinor,
                            categoryId = categoryId,
                            accountId = accountId,
                            note = note.trim(),
                            occurredAtMs = occurredAtFor(date, original.occurredAt, original.occurredOn),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.transactions_delete_confirm_title)) },
            text = { Text(stringResource(R.string.transactions_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(original.id)
                }) {
                    Text(stringResource(R.string.transactions_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showDatePicker) {
        // No floor and no ceiling, the same as the keypad's own picker. It used
        // to refuse the future on the grounds that an expense has already
        // happened — true of a receipt and false of the standing order leaving
        // on Friday, the deposit due next week, the flights already booked.
        // The keypad allows it and this refused it, so a date typed on one
        // screen could not be edited on the other.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            // Calendar only. The mode toggle offers a text field wanting
            // "03.09.2026", which is a keyboard, a format to guess at and a
            // validation error to get wrong, in place of tapping a day.
            DatePicker(state = state, title = null, showModeToggle = false)
        }
    }
}

/**
 * One level of categories at a time, and where to start.
 *
 * The flat list this replaced put every child inline after its parent, which is
 * right for the keypad's four-column grid and wrong for a single scrolling row
 * inside a dialog: a household with a few subcategories turned the row into a
 * horizontal scroll with no landmarks, and the only way to know a name was a
 * child was that it happened to sit after its parent.
 *
 * Pure, and separate from the composable, because "which family does this open
 * on" and "does tapping this drill or select" are the two things worth pinning
 * and neither of them needs a device.
 */
internal object CategoryDrill {

    /** Roots, or one family. A family leads with the parent itself, because a
     *  parent with children is still somewhere people file to — "Dom" as well
     *  as "Dom > Remonty" — and it would otherwise become unreachable the
     *  moment it grew its first child. */
    fun shown(ofKind: List<CategoryEntity>, openParent: String?): List<CategoryEntity> {
        if (openParent == null) {
            return ofKind.filter { it.parentId == null }.sortedBy { it.sortOrder }
        }
        val parent = ofKind.firstOrNull { it.id == openParent } ?: return shown(ofKind, null)
        return listOf(parent) + children(ofKind, openParent)
    }

    fun children(ofKind: List<CategoryEntity>, parentId: String): List<CategoryEntity> =
        ofKind.filter { it.parentId == parentId }.sortedBy { it.sortOrder }

    fun hasChildren(ofKind: List<CategoryEntity>, id: String): Boolean =
        ofKind.any { it.parentId == id }

    /** The family the dialog opens on: the one the row is already filed in.
     *  Nesting is exactly one level deep, so a child's parent is the answer. */
    fun openOn(ofKind: List<CategoryEntity>, selectedId: String?): String? =
        ofKind.firstOrNull { it.id == selectedId }?.parentId
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
private fun CategoryPill(
    category: CategoryEntity,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (selected) tint else tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Palette.icon(category.icon),
                contentDescription = null,
                tint = if (selected) Color.White else tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

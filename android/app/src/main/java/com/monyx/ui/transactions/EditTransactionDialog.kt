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
import androidx.compose.material3.SelectableDates
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
    // salary category. Children follow their parent so the row reads in family
    // order, and each is drawn in the parent's colour.
    val selectable = remember(categories, original.kind) {
        val ofKind = categories.filter { it.kind == original.kind }
        val byParent = ofKind.filter { it.parentId != null }.groupBy { it.parentId }
        ofKind.filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .flatMap { parent -> listOf(parent) + byParent[parent.id].orEmpty().sortedBy { it.sortOrder } }
    }
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

                if (!isTransfer) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.add_pick_category),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 76.dp),
                    ) {
                        items(selectable, key = { it.id }) { category ->
                            CategoryPill(
                                category = category,
                                tint = colorOf(category),
                                selected = category.id == categoryId,
                                onClick = { categoryId = category.id },
                            )
                        }
                    }
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
                            selected = account.id == accountId,
                            onClick = { accountId = account.id },
                        )
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

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showDatePicker = true }
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
        val today = Dates.today()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
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
            DatePicker(state = state, title = null)
        }
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
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
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
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

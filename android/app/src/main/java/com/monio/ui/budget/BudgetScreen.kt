package com.monio.ui.budget

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monio.MonioApp
import com.monio.R
import com.monio.data.BudgetUsage
import com.monio.data.CategoryEntity
import com.monio.data.Dates
import com.monio.data.Money
import com.monio.sync.SyncWorker
import com.monio.ui.theme.Palette
import kotlinx.coroutines.launch

/** What the edit dialog needs, whether it came from an existing budget row or
 *  from picking a not-yet-budgeted category. */
private data class EditTarget(
    val categoryId: String,
    val name: String,
    val icon: String?,
    val color: String?,
    /** Null means no limit is set yet for this period. */
    val limitMinor: Long?,
)

/**
 * Categories with usage bars, editing limits for the month (PRD §9).
 *
 * [initialCategoryId] and [initialPeriod] exist because a budget-alert
 * notification deep-links straight to a category's budget for a month (§8):
 * tapping it should land here with that category's edit dialog already open.
 */
@Composable
fun BudgetScreen(
    initialCategoryId: String? = null,
    initialPeriod: String? = null,
    onOpenCategoryTransactions: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MonioApp
    val viewModel: BudgetViewModel = viewModel(factory = BudgetViewModel.factory(app.repository))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val period by viewModel.period.collectAsStateWithLifecycle()
    val budgetUsage by viewModel.budgetUsage.collectAsStateWithLifecycle()
    val availableToAdd by viewModel.availableToAdd.collectAsStateWithLifecycle()

    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialPeriod) {
        if (!initialPeriod.isNullOrBlank()) viewModel.setInitialPeriod(initialPeriod)
    }

    // Auto-open the edit dialog for the deep-linked category, once its data
    // has arrived from either list — whichever one turns out to hold it.
    LaunchedEffect(budgetUsage, availableToAdd, initialCategoryId) {
        if (deepLinkHandled || initialCategoryId == null) return@LaunchedEffect
        val existing = budgetUsage.find { it.categoryId == initialCategoryId }
        if (existing != null) {
            editTarget = EditTarget(existing.categoryId, existing.name, existing.icon, existing.color, existing.limitMinor)
            deepLinkHandled = true
            return@LaunchedEffect
        }
        val notYetBudgeted = availableToAdd.find { it.id == initialCategoryId }
        if (notYetBudgeted != null) {
            editTarget = EditTarget(notYetBudgeted.id, notYetBudgeted.name, notYetBudgeted.icon, notYetBudgeted.color, null)
            deepLinkHandled = true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(stringResource(R.string.budget_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    MonthSwitcher(
                        period = period,
                        onPrevious = { viewModel.previousMonth() },
                        onNext = { viewModel.nextMonth() },
                    )
                }
            }

            item { NotificationPermissionGate(modifier = Modifier.fillMaxWidth()) }

            if (budgetUsage.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.budget_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(budgetUsage, key = { it.categoryId }) { usage ->
                    BudgetRow(
                        usage = usage,
                        onEdit = {
                            editTarget = EditTarget(usage.categoryId, usage.name, usage.icon, usage.color, usage.limitMinor)
                        },
                        onOpenTransactions = { onOpenCategoryTransactions(usage.categoryId, period) },
                    )
                }
            }

            if (availableToAdd.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddPicker = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.budget_add_category),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        EditBudgetLimitDialog(
            target = target,
            onDismiss = { editTarget = null },
            onSave = { minor ->
                scope.launch {
                    viewModel.setBudget(target.categoryId, minor)
                    SyncWorker.enqueue(context)
                }
                editTarget = null
            },
            onClear = {
                scope.launch {
                    viewModel.clearBudget(target.categoryId)
                    SyncWorker.enqueue(context)
                }
                editTarget = null
            },
        )
    }

    if (showAddPicker) {
        AddCategoryPickerDialog(
            categories = availableToAdd,
            onDismiss = { showAddPicker = false },
            onPick = { category ->
                showAddPicker = false
                editTarget = EditTarget(category.id, category.name, category.icon, category.color, null)
            },
        )
    }
}

@Composable
private fun MonthSwitcher(period: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.overview_previous_month))
        }
        Text(Dates.monthLabel(period), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.overview_next_month))
        }
    }
}

@Composable
private fun BudgetRow(
    usage: BudgetUsage,
    onEdit: () -> Unit,
    onOpenTransactions: () -> Unit,
) {
    val pct = if (usage.limitMinor > 0) usage.spentMinor.toFloat() / usage.limitMinor.toFloat() else 0f
    val barColor = when {
        pct >= 1f -> MaterialTheme.colorScheme.error
        pct >= 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val over = usage.spentMinor > usage.limitMinor
    val tint = Palette.colorFor(usage.color, usage.categoryId)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Palette.icon(usage.icon), contentDescription = null, tint = tint)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(usage.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.budget_spent_of, Money.format(usage.spentMinor), Money.format(usage.limitMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (over) {
                        stringResource(R.string.budget_over, Money.format(usage.spentMinor - usage.limitMinor))
                    } else {
                        stringResource(R.string.budget_left, Money.format(usage.limitMinor - usage.spentMinor))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onOpenTransactions) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.budget_view_transactions),
                )
            }
        }
    }
}

@Composable
private fun EditBudgetLimitDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var text by remember(target.categoryId) {
        mutableStateOf(
            if (target.limitMinor != null && target.limitMinor > 0) Money.format(target.limitMinor) else "",
        )
    }
    val hasExisting = target.limitMinor != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (hasExisting) R.string.budget_edit_limit else R.string.budget_set_limit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.name, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.budget_limit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.budget_carries_forward),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minor = Money.parseToMinor(text)
                if (minor > 0) onSave(minor)
            }) {
                Text(stringResource(R.string.budget_save))
            }
        },
        dismissButton = {
            Row {
                if (hasExisting) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.budget_remove_limit))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.budget_cancel))
                }
            }
        },
    )
}

@Composable
private fun AddCategoryPickerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_add_category)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(categories, key = { it.id }) { category ->
                    val tint = Palette.colorFor(category.color, category.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(imageVector = Palette.icon(category.icon), contentDescription = null, tint = tint)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(category.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.budget_cancel)) }
        },
    )
}

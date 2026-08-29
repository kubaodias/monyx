package com.monio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monio.MonioApp
import com.monio.R
import com.monio.data.AccountEntity
import com.monio.data.CategoryEntity
import com.monio.data.Dates
import com.monio.data.Money
import com.monio.data.TransactionListItem
import com.monio.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Chronological list, plain-LIKE search, filter by category / account / period
 * (PRD §9). `initialCategoryId` / `initialPeriod` let the Budget screen deep-
 * link into a filtered list, e.g. tapping "over budget" for a category.
 */
@Composable
fun TransactionsScreen(
    initialCategoryId: String? = null,
    initialPeriod: String? = null,
) {
    val app = LocalContext.current.applicationContext as MonioApp
    val viewModel: TransactionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(app.repository, app.applicationContext, initialCategoryId, initialPeriod)
            }
        },
    )

    val query by viewModel.query.collectAsStateWithLifecycle()
    val categoryId by viewModel.categoryId.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()

    var selectedItem by remember { mutableStateOf<TransactionListItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.transactions_deleted)

    val hasActiveFilters = query.isNotBlank() || categoryId != null || accountId != null || period.isNotBlank()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.transactions_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.transactions_clear_filters),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { viewModel.setQuery("") },
                        )
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryFilterChip(
                    categories = categories,
                    selectedId = categoryId,
                    onSelect = viewModel::setCategoryFilter,
                )
                AccountFilterChip(
                    accounts = accounts,
                    selectedId = accountId,
                    onSelect = viewModel::setAccountFilter,
                )
                PeriodFilterChip(
                    options = viewModel.periodOptions,
                    selectedPeriod = period,
                    onSelect = viewModel::setPeriodFilter,
                )
                if (hasActiveFilters) {
                    AssistChip(
                        onClick = viewModel::clearFilters,
                        label = { Text(stringResource(R.string.transactions_clear_filters)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            if (transactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.transactions_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val grouped = remember(transactions) { transactions.groupBy { it.occurredOn }.toList() }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
                    items(grouped, key = { it.first }) { (day, dayItems) ->
                        DayGroup(day = day, items = dayItems, onRowClick = { selectedItem = it })
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        TransactionDetailSheet(
            item = item,
            onDismiss = { selectedItem = null },
            onDelete = { id ->
                viewModel.deleteTransaction(id)
                selectedItem = null
                scope.launch { snackbarHostState.showSnackbar(deletedMessage) }
            },
        )
    }
}

@Composable
private fun CategoryFilterChip(
    categories: List<CategoryEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = categories.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.transactions_filter_category)
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transactions_filter_all)) },
                onClick = { onSelect(null); expanded = false },
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = { onSelect(category.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AccountFilterChip(
    accounts: List<AccountEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = accounts.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.transactions_filter_account)
    Box {
        FilterChip(
            selected = selectedId != null,
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transactions_filter_all)) },
                onClick = { onSelect(null); expanded = false },
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = { onSelect(account.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PeriodFilterChip(
    options: List<PeriodOption>,
    selectedPeriod: String,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.period == selectedPeriod }?.label
        ?: stringResource(R.string.transactions_filter_period)
    Box {
        FilterChip(
            selected = selectedPeriod.isNotBlank(),
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transactions_filter_all)) },
                onClick = { onSelect(null); expanded = false },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.period); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DayGroup(
    day: String,
    items: List<TransactionListItem>,
    onRowClick: (TransactionListItem) -> Unit,
) {
    // A transfer moves money, it does not spend it — it never enters the total (§6).
    val totalMinor = items.filter { it.kind != "transfer" }
        .sumOf { if (it.kind == "income") it.amountMinor else -it.amountMinor }
    val totalKind = if (totalMinor >= 0) "income" else "expense"

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Dates.dayLabel(day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Money.formatSigned(kotlin.math.abs(totalMinor), totalKind),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Column {
                    items.forEachIndexed { index, item ->
                        TransactionRow(item = item, onClick = { onRowClick(item) })
                        if (index != items.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(item: TransactionListItem, onClick: () -> Unit) {
    val isTransfer = item.kind == "transfer"
    val iconTint = if (isTransfer) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Palette.colorFor(item.categoryColor, item.categoryName ?: item.id)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isTransfer) Icons.Filled.SwapHoriz else Palette.icon(item.categoryIcon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isTransfer) {
                        "${item.accountName.orEmpty()} → ${item.transferAccountName.orEmpty()}"
                    } else {
                        item.categoryName ?: stringResource(R.string.common_none)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.pending == 1) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.CloudUpload,
                        contentDescription = stringResource(R.string.transactions_pending),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.rejected == 1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.transactions_rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        val amountColor = when {
            isTransfer -> MaterialTheme.colorScheme.onSurfaceVariant
            item.kind == "income" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(
            text = Money.formatSigned(item.amountMinor, item.kind),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = amountColor,
            textAlign = TextAlign.End,
        )
    }
}

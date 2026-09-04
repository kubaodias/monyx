package com.monyx.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.TransactionEntity
import com.monyx.data.TransactionListItem
import com.monyx.ui.MonthSwitcher
import com.monyx.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Chronological list, plain-LIKE search, filter by category / account / period.
 *
 * [filterCategoryId] and [filterPeriod] are the filter the NAVIGATOR wants
 * shown — a budget row, a pie slice, or the tab itself asking for everything.
 * They are applied reactively rather than through the constructor: this screen
 * keeps its ViewModel across a tab switch, so by the time a jump arrives the
 * ViewModel already exists and a constructor argument would be ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    filterCategoryId: String? = null,
    filterPeriod: String? = null,
    onSyncRequested: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: TransactionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(app.repository, app.applicationContext, app.selectedMonth, filterCategoryId)
            }
        },
    )

    LaunchedEffect(filterCategoryId, filterPeriod) {
        viewModel.applyFilter(filterCategoryId, filterPeriod)
    }

    val query by viewModel.query.collectAsStateWithLifecycle()
    val categoryId by viewModel.categoryId.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.transactions_deleted)
    val savedMessage = stringResource(R.string.add_saved)

    // The month is not in here. It is the scope of the screen, always set, the
    // way it is on Overview and Budget — a chip offering to clear it would be
    // offering to clear something that cannot be empty.
    val hasActiveFilters = query.isNotBlank() || categoryId != null || accountId != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Zero, because the bar this screen lives in has already applied them.
        // Taking the status bar inset a second time pushed the switcher a
        // centimetre below where the identical control sits on Overview.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            MonthSwitcher(
                period = period,
                onSelect = viewModel::setPeriod,
                // Matches Overview's contentPadding exactly. The switcher is the
                // same control on three screens; it has to start at the same x.
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )

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
                // Account first, then category. It reads as narrowing: which
                // money, then what it went on — and it is the order the edit
                // dialog puts the same two choices in.
                AccountFilterChip(
                    accounts = accounts,
                    selectedId = accountId,
                    onSelect = viewModel::setAccountFilter,
                )
                CategoryFilterChip(
                    categories = remember(categories) { categories.filter { it.parentId == null } },
                    selectedId = categoryId,
                    onSelect = viewModel::setCategoryFilter,
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
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        onSyncRequested()
                        scope.launch {
                            // The worker is fire-and-forget, so the spinner is
                            // time-boxed rather than tied to its result. It is
                            // an acknowledgement of the gesture, not a progress
                            // bar: rows arrive on their own when the pull lands.
                            kotlinx.coroutines.delay(1200)
                            refreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                    ) {
                        items(grouped, key = { it.first }) { (day, dayItems) ->
                            DayGroup(
                                day = day,
                                items = dayItems,
                                // Straight to the editor. What used to open here
                                // was a sheet whose whole content was two
                                // buttons, Edit and Delete, and both of them are
                                // on the editor now.
                                //
                                // The list projection is a join, not the row —
                                // load the real entity before handing it to
                                // something that will write it back.
                                onRowClick = { item -> scope.launch { editing = viewModel.load(item.id) } },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { original ->
        EditTransactionDialog(
            original = original,
            categories = categories,
            accounts = accounts,
            onDismiss = { editing = null },
            onSave = { edit ->
                viewModel.saveEdit(
                    original = original,
                    amountMinor = edit.amountMinor,
                    categoryId = edit.categoryId,
                    accountId = edit.accountId,
                    note = edit.note,
                    occurredAtMs = edit.occurredAtMs,
                )
                editing = null
                scope.launch { snackbarHostState.showSnackbar(savedMessage) }
            },
            onDelete = { id ->
                viewModel.deleteTransaction(id)
                editing = null
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
private fun DayGroup(
    day: String,
    items: List<TransactionListItem>,
    onRowClick: (TransactionListItem) -> Unit,
) {
    // A transfer moves money, it does not spend it — it never enters the total.
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
        // The colour is already resolved by the query; the key is only reached
        // when a whole family has never been given one. See categoryColorKey.
        Palette.colorFor(item.categoryColor, item.categoryColorKey ?: item.id)
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
                // A row nobody remembers typing is alarming. The mark is what
                // separates "the app invented an expense" from "the rule you
                // set up in March fired this morning".
                if (item.recurringRuleId != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = stringResource(R.string.recurring_from_rule),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
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

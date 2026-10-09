package com.monyx.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.monyx.data.Currency
import com.monyx.data.Dates
import com.monyx.data.accountsInListOrder
import com.monyx.data.defaultAccountId
import com.monyx.data.Money
import com.monyx.data.TransactionEntity
import com.monyx.data.TransactionListItem
import com.monyx.ui.MonthSwitcher
import com.monyx.ui.SyncPullToRefresh
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
 *
 * [scrollToDay] is a day the list should open AT rather than be narrowed to —
 * see the daily chart on the Overview. It is not a filter and does not belong in
 * the ViewModel: it is a one-off instruction to a LazyColumn, spent the moment
 * it is carried out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    filterCategoryId: String? = null,
    filterAccountId: String? = null,
    filterPeriod: String? = null,
    filterRevision: Int = 0,
    scrollToDay: String? = null,
    onSyncRequested: () -> Unit = {},
    /**
     * Which account the list is currently narrowed to, reported upwards.
     *
     * So that reaching for Dodaj while looking at one account's ledger starts
     * the new transaction on THAT account rather than on the household's
     * default. The filter lives in this screen's ViewModel, which the navigation
     * bar cannot see — and the bar is where the button is. See MonyxNav.
     */
    onAccountFilterChanged: (String?) -> Unit = {},
    /**
     * Which category the list is currently narrowed to, reported upwards, for
     * the same reason and to the same button as [onAccountFilterChanged].
     */
    onCategoryFilterChanged: (String?) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: TransactionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    repository = app.repository,
                    appContext = app.applicationContext,
                    selectedMonth = app.selectedMonth,
                    initialCategoryId = filterCategoryId,
                    initialAccountId = filterAccountId,
                )
            }
        },
    )

    LaunchedEffect(filterCategoryId, filterAccountId, filterPeriod, filterRevision) {
        viewModel.applyFilter(filterCategoryId, filterAccountId, filterPeriod)
    }

    val query by viewModel.query.collectAsStateWithLifecycle()
    val categoryId by viewModel.categoryId.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val totals by viewModel.filteredTotals.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    // What a row is compared against before it is marked as coming from
    // somewhere else: the household's default account, which is also the one the
    // keypad starts on. See [defaultAccount] — the two have to agree, or the
    // ledger marks rows as unusual that the app itself just made.
    //
    // Under an account filter it is that account instead, so the mark
    // disappears: every row matches, the chip overhead already names it, and a
    // pill on all of them would be the same word forty times down a list.
    val defaultAccountId = remember(accounts, accountId) {
        accountId ?: defaultAccountId(accounts)
    }
    // Each account's own colour, for the pill on a row from elsewhere. Resolved
    // once for the list rather than per row: Palette hashes when an account has
    // never been given a colour, and a long month is hundreds of rows.
    val accountColors = remember(accounts) {
        accounts.associate { it.id to Palette.colorFor(it.color, it.id) }
    }
    val accountActivity by viewModel.accountActivity.collectAsStateWithLifecycle()
    // Pinned to a finished account, which only Settings can do. The filter then
    // stops being a filter: there is one account to look at, it is named in the
    // chip, and offering to swap it for another would undo the only reason this
    // screen was opened.
    val pinnedArchived = remember(accounts, accountId) {
        accounts.firstOrNull { it.id == accountId && it.archived == 1 }
    }
    // Reported on every change, and on arrival: the Add button needs the answer
    // at the moment it is tapped, from a composition somewhere else entirely.
    LaunchedEffect(accountId) { onAccountFilterChanged(accountId) }
    LaunchedEffect(categoryId) { onCategoryFilterChanged(categoryId) }

    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
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
                // The archived-account note beside these chips is two lines of
                // small type against a row of 32dp chips; left to align at the
                // top it sat visibly high of the thing it is labelling.
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Account first, then category. It reads as narrowing: which
                // money, then what it went on — and it is the order the edit
                // dialog puts the same two choices in.
                pinnedArchived?.let { account ->
                    val span = accountActivity[account.id]
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.transactions_account_archived),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // How far back the account goes, because the list under
                        // it shows one month and cannot say that by itself.
                        span?.let {
                            val from = Dates.shortMonthLabel(Dates.periodOfDateString(it.firstOn))
                            val to = Dates.shortMonthLabel(Dates.periodOfDateString(it.lastOn))
                            Text(
                                // An account that only ever saw one month says
                                // that month once. "wrz 2026 – wrz 2026" is a
                                // range with nothing in it, and reads as a bug.
                                text = if (from == to) {
                                    from
                                } else {
                                    stringResource(R.string.transactions_account_span, from, to)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Archived accounts are deliberately absent — see
                // TransactionsViewModel.filterableAccounts for why.
                AccountFilterChip(
                    // In the household's own order, grouped the way Settings
                    // groups it. Straight from the table this menu put an
                    // account held outside the summary above the one everything
                    // is spent from, because the table interleaves the groups.
                    accounts = remember(accounts, accountId) {
                        accountsInListOrder(
                            TransactionsViewModel.filterableAccounts(accounts, accountId),
                        )
                    },
                    selectedId = accountId,
                    enabled = pinnedArchived == null,
                    onSelect = viewModel::setAccountFilter,
                )
                // The chosen category may be a child, in which case this chip
                // still shows its family: the two chips are one narrowing, and
                // a family chip that went blank the moment you picked something
                // inside it would read as having lost the filter.
                val chosen = remember(categories, categoryId) {
                    categories.firstOrNull { it.id == categoryId }
                }
                val rootId = chosen?.parentId ?: chosen?.id
                CategoryFilterChip(
                    // Families only, each kind in the household's own order and
                    // kept apart from the other kind. The table sorts
                    // `sortOrder, name` across both — and both start at zero —
                    // so straight from the query this menu alternated: the first
                    // expense family, the first income family, the second
                    // expense family. Neither list was in the order Settings
                    // shows, and nothing said which was which.
                    categories = remember(categories) { filterableFamilies(categories) },
                    selectedId = rootId,
                    // Picking a family drops any subcategory that was set: the
                    // old child belongs to a family you have just left.
                    onSelect = viewModel::setCategoryFilter,
                )
                // Only once a family is chosen, and only if it has anything
                // inside it. A second chip offering nothing is a dead control,
                // and on a row that already scrolls sideways it costs the width
                // of the one thing people came here to press.
                val children = remember(categories, rootId) {
                    categories.filter { it.parentId != null && it.parentId == rootId }
                        .sortedBy { it.sortOrder }
                }
                if (rootId != null && children.isNotEmpty()) {
                    SubcategoryFilterChip(
                        children = children,
                        selectedId = chosen?.takeIf { it.parentId != null }?.id,
                        // Clearing narrows back to the whole family rather than
                        // to everything — the family chip beside it is still set,
                        // and the two must not contradict each other.
                        onSelect = { viewModel.setCategoryFilter(it ?: rootId) },
                    )
                }
                // There was a third chip here, "Planowane", which added the rows
                // the repeating rules were going to write later in the month.
                // Removed at the owner's request: it answered "what is still
                // coming?" in the middle of the answer to "what did we spend?",
                // and it was the one control on the row that made the list
                // longer rather than shorter.
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

            // Only once a category is chosen. The question this answers is "how
            // much went on coffee", and without a category there is no "on
            // what" — the figure would just be the month's spending, which the
            // Summary card already carries and carries better, next to the
            // income it should be read against.
            if (categoryId != null && transactions.isNotEmpty()) {
                // On a card, like the totals on Overview and the plan on Budget.
                // Loose on the background it read as another filter chip's
                // caption — the one line on this screen that is an ANSWER
                // rather than a control, and nothing said so.
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.transactions_count,
                                totals.count,
                                totals.count,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // One number: what the rows on screen cost.
                        //
                        // Income used to sit beside it whenever the list held
                        // any, which on an unfiltered month meant a salary
                        // printed next to the spending — two figures that do not
                        // add up to anything, on a line whose whole job is to be
                        // read in one glance. The month's income is on the
                        // Summary card, where it has something to be subtracted
                        // from. Here the question is only ever "how much went
                        // out".
                        Text(
                            text = Money.formatWithCurrency(totals.expenseMinor),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
                val listState = rememberLazyListState()
                val targetIndex = remember(grouped, scrollToDay) {
                    if (scrollToDay == null) -1 else grouped.indexOfFirst { it.first == scrollToDay }
                }
                // The jump is held rather than fired once. Rows are still
                // arriving for a frame or two after the tap — the first attempt
                // landed the day halfway down the screen, because groups above it
                // appeared after the list had already moved — so the day is
                // re-taken to the top whenever its position changes.
                //
                // Until the list is touched. A drag is the reader taking over,
                // and after that nothing here moves the list again: being pulled
                // back to a day you have scrolled away from is the failure this
                // is written to avoid, and it would otherwise happen on the next
                // sync that added a row.
                var released by remember(scrollToDay) { mutableStateOf(false) }
                LaunchedEffect(listState, scrollToDay) {
                    listState.interactionSource.interactions.collect { interaction ->
                        if (interaction is DragInteraction.Start) released = true
                    }
                }
                LaunchedEffect(scrollToDay, targetIndex, released) {
                    if (targetIndex >= 0 && !released) listState.animateScrollToItem(targetIndex)
                }
                SyncPullToRefresh(onSyncRequested = onSyncRequested) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                    ) {
                        items(grouped, key = { it.first }) { (day, dayItems) ->
                            DayGroup(
                                day = day,
                                items = dayItems,
                                defaultAccountId = defaultAccountId,
                                accountColors = accountColors,
                                // The day that was asked for, marked. Scrolling
                                // to it is not enough on its own: near the end of
                                // the list there is nothing left to scroll, so
                                // the day arrives somewhere down the screen with
                                // nothing saying which one it was.
                                highlighted = day == scrollToDay,
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
        EditTransactionSheet(
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
                    currency = edit.currency,
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

/**
 * The families the category filter offers: expense ones, then income ones, each
 * in the order the household dragged them into.
 *
 * Two lists in one menu rather than two menus: the question is "which
 * category", and a household files a handful of things as income and everything
 * else as spending, so splitting the control in two would mean choosing a kind
 * first in order to answer a question that is not about kinds.
 *
 * `sortOrder` is per-kind and both sequences start at zero, which is why the
 * table's own order cannot be used here — see the call site. Expense first,
 * because that is the order every other screen in the app asks it in.
 */
internal fun filterableFamilies(categories: List<CategoryEntity>): List<CategoryEntity> =
    categories.filter { it.parentId == null }
        .sortedWith(
            compareBy({ if (it.kind == "income") 1 else 0 }, { it.sortOrder }, { it.name }),
        )

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
            // "Wszystkie" is a different kind of answer from the rows under it —
            // it clears the filter rather than setting one — so it gets a rule
            // of its own, and each kind gets a heading above its block. Without
            // them the menu was one column of names in which the point where
            // spending stopped and earning started was invisible.
            //
            // groupBy keeps the order it was handed, which is already grouped:
            // see [filterableFamilies], which is also what decides that spending
            // comes first.
            categories.groupBy { it.kind }.forEach { (kind, family) ->
                HorizontalDivider()
                MenuSectionLabel(
                    stringResource(
                        if (kind == "income") R.string.overview_income else R.string.overview_expenses,
                    ),
                )
                family.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = { onSelect(category.id); expanded = false },
                    )
                }
            }
        }
    }
}

/** A heading inside a dropdown. Not a DropdownMenuItem: it is not tappable. */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The second step of the category filter: what inside the chosen family.
 *
 * Deliberately a chip of its own rather than more rows on the family menu. A
 * flat list of every category and subcategory put "Paliwo" beside "Dom i ogród"
 * as an equal, and on a household with fifty of them the menu became the thing
 * you had to read rather than the thing you filtered with. Two chips say the
 * shape out loud: which family, then which part of it.
 *
 * Its first entry returns to the whole family, so the narrowing is undoable
 * without clearing the family chip and starting again.
 */
@Composable
private fun SubcategoryFilterChip(
    children: List<CategoryEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = children.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.transactions_filter_subcategory)
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
            children.forEach { category ->
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
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = accounts.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.transactions_filter_account)
    Box {
        FilterChip(
            selected = selectedId != null,
            enabled = enabled,
            onClick = { expanded = true },
            label = { Text(label) },
            // No arrow when there is nothing to open: a disabled control that
            // still advertises a menu reads as a control that is broken.
            trailingIcon = if (enabled) {
                { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
            } else {
                null
            },
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

/**
 * What one day came to, in złoty.
 *
 * [TransactionListItem.plnMinor], never amountMinor. The heading is one figure
 * over a day's rows, and a day can hold rows in two currencies — so adding up
 * each row's own amount produced a number that was not money: one euro counted
 * as one złoty. A day with three euro purchases on it printed a euro-sized
 * figure with "zł" beside it while every other day in the list was honest,
 * which is the version of this bug that is hardest to spot.
 *
 * The same rule and the same reason as the filtered total under the search box
 * — see TransactionsViewModel.totalsOf. Null plnMinor (no rate has synced for
 * that currency) contributes nothing, which is the degradation every total in
 * the app shares.
 *
 * Transfers are left out: moving money between two of your own accounts is not
 * spending it.
 */
internal fun dayTotalMinor(items: List<TransactionListItem>): Long = items
    .filter { it.kind != "transfer" }
    .sumOf { row ->
        val pln = row.plnMinor ?: 0L
        if (row.kind == "income") pln else -pln
    }

@Composable
private fun DayGroup(
    day: String,
    items: List<TransactionListItem>,
    defaultAccountId: String?,
    accountColors: Map<String, Color>,
    highlighted: Boolean = false,
    onRowClick: (TransactionListItem) -> Unit,
) {
    // A transfer moves money, it does not spend it — it never enters the total.
    val totalMinor = dayTotalMinor(items)
    val totalKind = if (totalMinor >= 0) "income" else "expense"

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Today and yesterday say so after the date rather than instead of
            // it: the date is what the eye scans down the list by.
            val date = Dates.dayLabel(day)
            // The heading is otherwise deliberately quiet — it is a separator,
            // not a reading. The day somebody jumped to is the exception, and it
            // borrows the emphasis rather than a background tint: a highlighted
            // band would still be sitting there an hour later looking selected.
            val headingColor = if (highlighted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val headingWeight = if (highlighted) FontWeight.Bold else null
            Text(
                text = when (day) {
                    Dates.today().toString() -> stringResource(R.string.transactions_day_today, date)
                    Dates.today().minusDays(1).toString() ->
                        stringResource(R.string.transactions_day_yesterday, date)
                    else -> date
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = headingWeight,
                color = headingColor,
            )
            Text(
                // In złoty, with "zł" on it. See [dayTotalMinor].
                text = Money.formatSignedIn(kotlin.math.abs(totalMinor), totalKind),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = headingWeight,
                color = headingColor,
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
                        TransactionRow(
                            item = item,
                            defaultAccountId = defaultAccountId,
                            accountColor = item.accountId?.let { accountColors[it] },
                            onClick = { onRowClick(item) },
                        )
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
private fun TransactionRow(
    item: TransactionListItem,
    /**
     * The household's first open account — the one the keypad starts on. Rows on
     * any other account say so; rows on this one do not, or the mark would be on
     * nearly every row and would mark nothing.
     */
    defaultAccountId: String? = null,
    /** This row's account's own colour, for the pill. Never resolved here. */
    accountColor: Color? = null,
    onClick: () -> Unit,
) {
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
            }
            // Which account it came out of, when it was not the usual one. A
            // transfer is exempt: its title line already names both ends, and
            // repeating one of them underneath says nothing new.
            val elsewhere = !isTransfer &&
                defaultAccountId != null &&
                item.accountId != null &&
                item.accountId != defaultAccountId
            if (elsewhere || !item.note.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (elsewhere) {
                        // In the account's own colour, the way the icon left of
                        // the row carries the category's: the pill then says
                        // WHICH other account at a glance, and a month with two
                        // of them in it stops being a column of identical grey
                        // tags you have to read one at a time. Tinted background,
                        // saturated text — the same recipe as the category
                        // circle, for the same reason: a solid fill this small
                        // would shout louder than the amount.
                        val tint = accountColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            text = item.accountName.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tint.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        if (!item.note.isNullOrBlank()) Spacer(Modifier.width(6.dp))
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
                }
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
        // Both units whenever the row has two: a euro row over its złoty value,
        // or — on a euro account — a złoty row over its euro value. Without the
        // unit, "-15,00" from a euro card and "-15,00" from a złoty one were the
        // same six characters for different money. See Money.ledgerRowFigure.
        val figure = Money.ledgerRowFigure(
            amountMinor = item.amountMinor,
            kind = item.kind,
            currency = Currency.of(item.currency),
            plnMinor = item.plnMinor,
            accountCurrency = Currency.of(item.accountCurrency),
            accountMinor = item.accountMinor,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = figure.main,
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = amountColor,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
            figure.aside?.let { converted ->
                Text(
                    text = converted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

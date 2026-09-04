package com.monyx.ui.overview

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.data.AccountBalance
import com.monyx.data.CategorySpend
import com.monyx.data.Dates
import com.monyx.data.TransactionEntity
import com.monyx.data.Money
import com.monyx.data.TransactionListItem
import com.monyx.ui.MonthSwitcher
import com.monyx.ui.theme.Palette
import com.monyx.ui.transactions.EditTransactionDialog
import kotlinx.coroutines.launch

/**
 * The Overview screen (Przegląd): month totals, the spending breakdown, account
 * balances and the recent ledger. Navigation is passed in as plain
 * callbacks, never a NavController.
 */
@Composable
fun OverviewScreen(
    onOpenTransactions: (categoryId: String?, period: String?) -> Unit,
    onSyncRequested: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: OverviewViewModel = viewModel(factory = OverviewViewModel.factory(app.repository, app.selectedMonth))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The row somebody tapped, loaded whole. Null closes the editor.
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }

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
                    onSaved = onSyncRequested,
                )
                editing = null
            },
            onDelete = { id ->
                viewModel.deleteTransaction(id, onSyncRequested)
                editing = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            MonthSwitcher(
                period = state.period,
                onSelect = viewModel::setPeriod,
            )
        }
        if (state.accounts.size > 1) {
            item {
                AccountFilter(
                    accounts = state.accounts,
                    selected = state.selectedAccountIds,
                    onToggle = viewModel::toggleAccount,
                    onClear = viewModel::clearAccountFilter,
                )
            }
        }
        item {
            SummaryCard(
                income = state.incomeMinor,
                expense = state.expenseMinor,
                balance = state.balanceMinor,
                trend = state.trend,
            )
        }
        item {
            BreakdownCard(
                breakdown = state.breakdown,
                // A slice is a question — "what made up that 340 zł?" — so it
                // opens that category's transactions for the month on screen,
                // not for today.
                onCategoryClick = { categoryId -> onOpenTransactions(categoryId, state.period) },
            )
        }
        item {
            RecentSection(
                recent = state.recent,
                // The row's own id, which this used to throw away: every row in
                // the list navigated to the same unfiltered month, so tapping
                // the third and the ninth did exactly the same thing. It opens
                // that transaction's editor, here, without a tab switch —
                // leaving the overview would lose the place somebody tapped
                // from. "See all" is the one that is meant to navigate.
                onOpenTransaction = { id -> scope.launch { editing = viewModel.load(id) } },
                onSeeAll = { onOpenTransactions(null, state.period) },
            )
        }
    }
}

/**
 * The month's balance on the front, how it got there on the back.
 *
 * A flip rather than a second card or a screen of its own, because it is one
 * fact seen two ways: the running total on the back is the number on the front,
 * arriving. Two cards side by side would have claimed two separate facts.
 *
 * Which is why the same three figures are printed on both faces. The back used
 * to headline the thirty-day net instead, so a September that had not started
 * yet read 0,00 on the front and 4 447,00 on the back — one card, two answers,
 * and no way to tell from either which question it had answered.
 */
@Composable
private fun SummaryCard(income: Long, expense: Long, balance: Long, trend: TrendSeries) {
    var showTrend by rememberSaveable { mutableStateOf(false) }
    // Dropped whenever the window itself changes — a day index means nothing
    // once the month switcher has moved the thirty days underneath it.
    var focused by remember(trend.from, trend.points.size) { mutableStateOf<Int?>(null) }

    val rotation by animateFloatAsState(
        targetValue = if (showTrend) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "balanceCardFlip",
    )
    val showingBack = rotation > 90f

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            // The two faces are not the same height. Halfway through the turn
            // the card is edge-on and effectively invisible, which is the one
            // moment the size can change without anyone seeing it happen — and
            // that is exactly when the faces swap.
            .animateContentSize()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            },
    ) {
        Box(
            // The back is painted on a surface already turned 180 degrees, so
            // without turning it back again every word on it reads mirrored.
            modifier = Modifier.graphicsLayer { rotationY = if (showingBack) 180f else 0f },
        ) {
            if (showingBack) {
                TrendFace(
                    trend = trend,
                    income = income,
                    expense = expense,
                    balance = balance,
                    focused = focused,
                    onFocus = { focused = it },
                    onBack = {
                        focused = null
                        showTrend = false
                    },
                )
            } else {
                TotalsFace(
                    income = income,
                    expense = expense,
                    balance = balance,
                    onOpenTrend = { showTrend = true },
                )
            }
        }
    }
}

@Composable
private fun TotalsFace(income: Long, expense: Long, balance: Long, onOpenTrend: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenTrend)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.overview_balance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            // The only sign that the card has a back. Without it the flip is a
            // feature nobody finds, because nothing else about a number
            // suggests it can be turned over.
            Icon(
                imageVector = Icons.Filled.ShowChart,
                contentDescription = stringResource(R.string.overview_show_trend),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = Money.formatWithCurrency(balance),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = amountColor(balance),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat(
                label = stringResource(R.string.overview_income),
                amountMinor = income,
                tint = MaterialTheme.colorScheme.primary,
            )
            SummaryStat(
                label = stringResource(R.string.overview_expenses),
                amountMinor = expense,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The back: where the balance stood, and what moved to put it there.
 *
 * Untouched, it prints the month's own three figures — the same three the front
 * prints — so turning the card over never changes a number. All the back adds
 * is the path: the line arrives at the headline rather than restating it.
 *
 * Touched, header and footer switch together to the one day being pointed at,
 * and the header says so. Letting the big number mean the month while the two
 * figures under the chart meant a single day would have been two answers to one
 * question.
 */
@Composable
private fun TrendFace(
    trend: TrendSeries,
    income: Long,
    expense: Long,
    balance: Long,
    focused: Int?,
    onFocus: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    val point = focused?.let { trend.points.getOrNull(it) }
    // "Through 25 August", not "25 August". The number under it is the balance
    // as it stood at the END of that day, while the two figures below are what
    // moved on the day itself — a bare date would have read as both.
    val label = point
        ?.let { stringResource(R.string.overview_trend_through, Dates.dayLabel(it.date.toString())) }
        ?: stringResource(R.string.overview_balance)
    val headline = if (point == null) balance else trend.runningAt(focused)
    val shownIncome = point?.incomeMinor ?: income
    val shownExpense = point?.expenseMinor ?: expense

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Money.formatWithCurrency(headline),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor(headline),
                )
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.overview_show_totals),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        TrendChart(series = trend, focused = focused, onFocus = onFocus)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat(
                label = stringResource(R.string.overview_income),
                amountMinor = shownIncome,
                tint = MaterialTheme.colorScheme.primary,
            )
            SummaryStat(
                label = stringResource(R.string.overview_expenses),
                amountMinor = shownExpense,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Negative money is the one thing on this card that must never read as neutral. */
@Composable
private fun amountColor(minor: Long): Color =
    if (minor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

@Composable
private fun SummaryStat(label: String, amountMinor: Long, tint: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatWithCurrency(amountMinor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

@Composable
private fun BreakdownCard(
    breakdown: List<CategorySpend>,
    onCategoryClick: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.overview_breakdown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            val slices = breakdown.map { spend ->
                PieSlice(
                    id = spend.categoryId,
                    label = spend.name,
                    amountMinor = spend.spentMinor,
                    color = Palette.colorFor(spend.color, spend.categoryId),
                )
            }
            PieChart(slices = slices, onSliceClick = onCategoryClick)
        }
    }
}

/**
 * Accounts as a filter rather than a row of read-only cards.
 *
 * The balance stays on the button, so nothing that used to be on screen is gone
 * — it just became a control. Selecting nothing means every account, which is
 * why "All" is a button and not the absence of one: an overview filtered down
 * to nothing has no honest reading.
 *
 * Hidden entirely below two accounts, where a filter can only ever be a no-op.
 *
 * Hand-drawn, not a Material `FilterChip`. A chip is a fixed 32.dp tall and
 * does not grow for its content, which is why the name and the balance used to
 * be crushed onto one line: it is the first thing on the screen and it was the
 * smallest. These are two lines — the account, then what is in it — because
 * that is the order the question is asked in, and they are tall enough to hit.
 */
@Composable
private fun AccountFilter(
    accounts: List<AccountBalance>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            AccountButton(
                name = stringResource(R.string.overview_all_accounts),
                // The total, which is what "all accounts" is a filter for. It
                // keeps this button the same shape as the ones beside it —
                // a short one among tall ones reads as a different kind of
                // control rather than as the first of the same kind.
                balanceMinor = accounts.sumOf { it.balanceMinor },
                icon = Icons.Filled.AllInclusive,
                // The theme's own accent, not a neutral: the ring is drawn in
                // this colour, and onSurfaceVariant made the selected "all"
                // button look outlined in black beside accounts ringed in
                // their own quiet greens and blues.
                color = MaterialTheme.colorScheme.primary,
                selected = selected.isEmpty(),
                onClick = onClear,
            )
        }
        items(accounts, key = { it.id }) { account ->
            AccountButton(
                name = account.name,
                balanceMinor = account.balanceMinor,
                icon = Palette.icon(account.icon),
                color = Palette.colorFor(account.color, account.id),
                selected = account.id in selected,
                onClick = { onToggle(account.id) },
            )
        }
    }
}

/**
 * One account in the filter strip: icon and name on top, balance underneath.
 *
 * Selected is carried by a filled tint AND a ring, not by the fill alone — the
 * account's own colour is already on the icon, so a tinted background on its
 * own would be one more shade of the same thing.
 */
@Composable
private fun AccountButton(
    name: String,
    balanceMinor: Long,
    icon: ImageVector,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) color else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            )
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = Money.formatWithCurrency(balanceMinor),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            color = if (balanceMinor < 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RecentSection(
    recent: List<TransactionListItem>,
    onOpenTransaction: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.overview_recent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onSeeAll) {
                Text(text = stringResource(R.string.overview_see_all))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (recent.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.transactions_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Dated delimiters rather than a date on every row: the same day
            // heading the full history uses, so a list of twelve rows reads as
            // days rather than as an undated pile.
            val grouped = remember(recent) { recent.groupBy { it.occurredOn }.toList() }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                grouped.forEach { (day, items) ->
                    Text(
                        text = Dates.dayLabel(day),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    items.forEach { tx ->
                        TransactionRow(tx = tx, onClick = { onOpenTransaction(tx.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: TransactionListItem, onClick: () -> Unit) {
    // Keyed on the category's family, never on the transaction: keyed on the
    // row id a colourless category came out a different colour on every line.
    val color = Palette.colorFor(tx.categoryColor, tx.categoryColorKey ?: tx.id)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Palette.icon(tx.categoryIcon),
                    contentDescription = null,
                    tint = color,
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.categoryName ?: tx.transferAccountName
                        ?: stringResource(R.string.common_none),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!tx.note.isNullOrBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = Money.formatSigned(tx.amountMinor, tx.kind),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = when (tx.kind) {
                    "income" -> MaterialTheme.colorScheme.primary
                    "expense" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

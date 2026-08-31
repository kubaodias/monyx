package com.monyx.ui.overview

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monyx.MonyxApp
import com.monyx.R
import com.monyx.data.AccountBalance
import com.monyx.data.CategorySpend
import com.monyx.data.Dates
import com.monyx.data.Money
import com.monyx.data.TransactionListItem
import com.monyx.ui.theme.Palette

/**
 * The Overview screen (Przegląd): month totals, the spending breakdown, account
 * balances and the recent ledger. Navigation is passed in as plain
 * callbacks, never a NavController.
 */
@Composable
fun OverviewScreen(
    onOpenTransactions: (categoryId: String?, period: String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MonyxApp
    val viewModel: OverviewViewModel = viewModel(factory = OverviewViewModel.factory(app.repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            MonthSwitcher(
                period = state.period,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
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
                onOpenTransaction = { onOpenTransactions(null, state.period) },
                onSeeAll = { onOpenTransactions(null, state.period) },
            )
        }
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
            Icon(
                imageVector = Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.overview_previous_month),
            )
        }
        Text(
            text = Dates.monthLabel(period),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.overview_next_month),
            )
        }
    }
}

@Composable
private fun SummaryCard(income: Long, expense: Long, balance: Long) {
    val balanceColor =
        if (balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.overview_balance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Money.formatWithCurrency(balance),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = balanceColor,
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
}

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
 * The balance stays on the chip, so nothing that used to be on screen is gone —
 * it just became a control. Selecting nothing means every account, which is why
 * "All" is a chip and not the absence of one: an overview filtered down to
 * nothing has no honest reading.
 *
 * Hidden entirely below two accounts, where a filter can only ever be a no-op.
 */
@Composable
private fun AccountFilter(
    accounts: List<AccountBalance>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = onClear,
                label = { Text(stringResource(R.string.overview_all_accounts)) },
            )
        }
        items(accounts, key = { it.id }) { account ->
            val color = Palette.colorFor(account.color, account.id)
            FilterChip(
                selected = account.id in selected,
                onClick = { onToggle(account.id) },
                leadingIcon = {
                    Icon(
                        imageVector = Palette.icon(account.icon),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                },
                // ONE line, not a stacked name and balance. A Material chip is a
                // fixed 32.dp tall and does not grow for its content, so two
                // lines were squeezed inside it and sat visibly off against the
                // single-line "all accounts" chip beside them. The balance keeps
                // its own colour through a span instead of a second row.
                label = {
                    val balanceColor = if (account.balanceMinor < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = buildAnnotatedString {
                            append(account.name)
                            withStyle(SpanStyle(color = balanceColor)) {
                                append("  ${Money.formatWithCurrency(account.balanceMinor)}")
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
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
    val color = Palette.colorFor(tx.categoryColor, tx.id)
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

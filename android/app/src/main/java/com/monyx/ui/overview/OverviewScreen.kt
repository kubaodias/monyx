package com.monyx.ui.overview

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PieChart
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
import androidx.compose.ui.draw.alpha
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
import com.monyx.ui.transactions.EditTransactionSheet
import kotlinx.coroutines.launch

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
    val viewModel: OverviewViewModel = viewModel(
        factory = OverviewViewModel.factory(app.repository, app.selectedMonth, app.chartPreferences),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val budgetHidden by viewModel.budgetHidden.collectAsStateWithLifecycle()
    val showsMonth by viewModel.showsMonth.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned, like the identical control on Transactions. It names the month
        // every figure below it belongs to, and once the balance card had
        // scrolled past there was nothing left on screen saying which month was
        // being read — on a screen whose whole content changes with it.
        MonthSwitcher(
            period = state.period,
            onSelect = viewModel::setPeriod,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        if (state.accounts.size > 1) {
            item {
                AccountFilter(
                    accounts = state.accounts,
                    selected = state.selectedAccountIds,
                    onToggle = viewModel::toggleAccount,
                )
            }
        }
        item {
            SummaryCard(
                income = state.incomeMinor,
                expense = state.expenseMinor,
                net = state.netMinor,
                windowIncome = state.windowIncomeMinor,
                windowExpense = state.windowExpenseMinor,
                balance = state.balanceMinor,
                trend = state.trend,
            )
        }
        item {
            BreakdownCard(
                breakdown = state.breakdown,
                history = history,
                hidden = hidden,
                budgetHidden = budgetHidden,
                onToggleHidden = viewModel::toggleHidden,
                onToggleBudget = viewModel::toggleBudgetLine,
                onToggleAllHidden = { viewModel.toggleAllHidden(history.categories.map { it.id }) },
                // A slice is a question — "what made up that 340 zł?" — so it
                // opens that category's transactions for the month on screen,
                // not for today.
                onCategoryClick = { categoryId -> onOpenTransactions(categoryId, state.period) },
                // Tapping a bar moves the whole screen to that month, which is
                // the same thing the switcher at the top does.
                onSelectMonth = viewModel::setPeriod,
                showsMonth = showsMonth,
                onSelectAmountMode = viewModel::setAmountMode,
            )
        }
        }
    }
}

/**
 * How the chosen month went on the front, where the money stands on the back.
 *
 * The two faces now answer two questions, and each names its own. The front is
 * the MONTH: earned, spent, and the difference, so moving the switcher above it
 * to March answers "did March come out ahead?". The back is the last thirty
 * days and the balance they arrive at, which is a position and does not belong
 * to any one month.
 *
 * Both faces used to print the same three figures, on the principle that one
 * card must not give two answers. The principle stands; what changed is that
 * the front's question was the wrong one — it printed the same balance whatever
 * month was chosen. The rule is now that each face LABELS its answer, which the
 * back already did whenever a day was focused.
 */
@Composable
private fun SummaryCard(
    income: Long,
    expense: Long,
    net: Long,
    windowIncome: Long,
    windowExpense: Long,
    balance: Long,
    trend: TrendSeries,
) {
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
                    income = windowIncome,
                    expense = windowExpense,
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
                    net = net,
                    onOpenTrend = { showTrend = true },
                )
            }
        }
    }
}

@Composable
private fun TotalsFace(income: Long, expense: Long, net: Long, onOpenTrend: () -> Unit) {
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
            text = Money.formatWithCurrency(net),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = amountColor(net),
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
    // Not "Bilans": the front of the card owns that word now, for the month's
    // net. This face headlines what is actually in the accounts, and a card
    // whose two sides print different numbers under one label is the confusion
    // the flip was built to avoid.
    val label = point
        ?.let { stringResource(R.string.overview_trend_through, Dates.dayLabel(it.date.toString())) }
        ?: stringResource(R.string.overview_account_total)
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

/**
 * Where the money goes this month, and — turned over — where it has been going
 * all year.
 *
 * One card with two faces rather than a second card below it, because the two
 * answer the same question over different spans, and because the legend is the
 * same list of categories either way: in the pie it says what each cost this
 * month and opens its transactions, in the history it says what each costs in
 * an average month and takes it in and out of the chart.
 *
 * Which face is showing survives a rotation but not the tab being left, which
 * is the right lifetime for a lens: coming back to the Overview should show the
 * month, since that is what every other card on the screen is showing.
 */
@Composable
private fun BreakdownCard(
    breakdown: List<CategorySpend>,
    history: CategoryHistory,
    hidden: Set<String>,
    budgetHidden: Boolean,
    onToggleHidden: (String) -> Unit,
    onToggleBudget: () -> Unit,
    onToggleAllHidden: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onSelectMonth: (String) -> Unit,
    showsMonth: Boolean,
    onSelectAmountMode: (LegendAmount) -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // Turning a card over is somewhere you went, so Back is the way out of it.
    // Without this the gesture leaves the Overview entirely and the card is
    // still showing its back when you come back to the tab.
    BackHandler(enabled = showHistory) { showHistory = false }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (showHistory) R.string.overview_breakdown_history else R.string.overview_breakdown,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // The only sign the card has a back, and the same bargain the
                // balance card makes with its little chart glyph: 24dp in the
                // corner, showing what you would get rather than what you have.
                IconButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (showHistory) Icons.Filled.PieChart else Icons.Filled.BarChart,
                        contentDescription = stringResource(
                            if (showHistory) {
                                R.string.overview_show_breakdown
                            } else {
                                R.string.overview_show_history
                            },
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (showHistory) {
                HistoryFace(
                    history = history,
                    hidden = hidden,
                    budgetHidden = budgetHidden,
                    onToggle = onToggleHidden,
                    onToggleBudget = onToggleBudget,
                    onToggleAll = onToggleAllHidden,
                    onCategoryClick = onCategoryClick,
                    onSelectMonth = onSelectMonth,
                    showsMonth = showsMonth,
                    onSelectAmountMode = onSelectAmountMode,
                )
            } else {
                // The same two questions the chart behind this card answers, so
                // "is this a lot?" can be asked of a normal month without
                // turning the card over and reading a different control.
                AmountToggle(showsMonth = showsMonth, onSelect = onSelectAmountMode)
                Spacer(modifier = Modifier.height(16.dp))
                val slices = if (!showsMonth) {
                    // Averages come from the twelve-month window, which is the
                    // only place they exist — the pie's own query knows one
                    // month and nothing else.
                    history.categories.map { category ->
                        PieSlice(
                            id = category.id,
                            label = category.name,
                            amountMinor = category.averageMinor,
                            color = Palette.colorFor(category.color, category.id),
                        )
                    }
                } else {
                    breakdown.map { spend ->
                        PieSlice(
                            id = spend.categoryId,
                            label = spend.name,
                            amountMinor = spend.spentMinor,
                            color = Palette.colorFor(spend.color, spend.categoryId),
                        )
                    }
                }
                PieChart(slices = slices, onSliceClick = onCategoryClick)
            }
        }
    }
}

/**
 * The back of the breakdown card: the bars, then the legend they are made of.
 *
 * Hiding every category is allowed and leaves an empty grid rather than being
 * refused. It is one tap to undo, it is visibly what was asked for, and the
 * alternative — a last row that will not turn off — is a control that lies
 * about being a control.
 */
@Composable
private fun HistoryFace(
    history: CategoryHistory,
    hidden: Set<String>,
    budgetHidden: Boolean,
    onToggle: (String) -> Unit,
    onToggleBudget: () -> Unit,
    onToggleAll: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onSelectMonth: (String) -> Unit,
    showsMonth: Boolean,
    onSelectAmountMode: (LegendAmount) -> Unit,
) {
    if (history.isEmpty) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.overview_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Above the chart, in the same place it sits on the pie face. It governs
    // both the legend below and which question the whole card is answering, so
    // finding it meant scrolling past a chart to a control that had been on
    // screen a moment earlier on the other side of the card.
    AmountToggle(showsMonth = showsMonth, onSelect = onSelectAmountMode)
    Spacer(modifier = Modifier.height(16.dp))
    CategoryHistoryChart(
        history = history,
        hidden = hidden,
        budgetHidden = budgetHidden,
        onSelectMonth = onSelectMonth,
    )
    // Only where there is a line to explain. A household that has never set a
    // limit is not told about a red line it cannot see — and the key stays put
    // when the line is switched off, dimmed, because it is the way back on.
    if (history.months.any { it.budgetMinor(hidden) != null }) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggleBudget)
                .alpha(if (budgetHidden) 0.38f else 1f)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.overview_history_budget),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    val mode = if (showsMonth) LegendAmount.SelectedMonth else LegendAmount.Average
    val amounts = remember(history, mode) { legendAmounts(history, mode) }
    CategoryHistoryLegend(
        // Ranked by the figure on show, so switching the column re-sorts the
        // list with it — the order is part of the answer, not decoration.
        categories = legendOrder(history.categories, hidden) { amounts[it.id] ?: 0L },
        hidden = hidden,
        amounts = amounts,
        onToggle = onToggle,
        onToggleAll = onToggleAll,
        onOpen = onCategoryClick,
    )
}

/**
 * Accounts as a filter rather than a row of read-only cards.
 *
 * The balance stays on the button, so nothing that used to be on screen is gone
 * — it just became a control.
 *
 * Every account starts selected, and there is no longer an "All accounts"
 * button in front of them. With two accounts it was a third button saying
 * exactly what both of the others being on already said, and it was the first
 * thing on the screen — a control whose only job was to undo the other
 * controls. [selected] empty still means all, which is what the queries mean by
 * unfiltered; it is simply drawn as every button being on.
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
    onToggle: (String, List<String>) -> Unit,
) {
    val allIds = remember(accounts) { accounts.map { it.id } }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(accounts, key = { it.id }) { account ->
            AccountButton(
                name = account.name,
                balanceMinor = account.balanceMinor,
                icon = Palette.icon(account.icon),
                color = Palette.colorFor(account.color, account.id),
                // Empty is the unfiltered query, and the unfiltered query
                // covers every account — so every button is on. See
                // OverviewViewModel.selectedAccounts.
                selected = selected.isEmpty() || account.id in selected,
                onClick = { onToggle(account.id, allIds) },
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


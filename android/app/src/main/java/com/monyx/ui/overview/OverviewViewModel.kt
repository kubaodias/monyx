package com.monyx.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.AccountBalance
import com.monyx.data.CategoryRef
import com.monyx.data.CategorySpend
import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import com.monyx.ui.SelectedMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the Overview screen (Przegląd) shows for one selected month:
 * totals, the spending breakdown, account balances, and the recent ledger.
 */
data class OverviewUiState(
    val period: String,
    /**
     * Thirty days back, not the calendar month.
     *
     * "What did we earn and spend this month" is a question whose answer is
     * worthless on the 1st and only becomes useful around the 20th — and the
     * card resets to nearly nothing on the day the household most wants to know
     * where it stands. A rolling window always describes a full month of
     * living. It is the SAME window the chart on the back of this card draws,
     * so turning the card over cannot change a number.
     */
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
    /**
     * Where the selected accounts stood when the month began: their position
     * at the end of the month before, counted the same way as [balanceMinor].
     *
     * The front of the card is a statement, not a rate. It opens with this,
     * adds what came in, takes away what went out, and lands on the same figure
     * as Stan kont on the back — the same money, laid out as the month that
     * produced it. Earned-minus-spent on its own hid the fact that a month can
     * start in the red, and read as room to spend that was not there.
     */
    val carryOverMinor: Long = 0,
    /**
     * Whatever else moved the position this month: transfers to or from an
     * account outside the selection (an archived trip fund, Poduszka when only
     * Portfel is selected), income or spending booked on an archived account,
     * and anything dated later this month. Zero most months, and not shown then.
     * It is what makes the statement add up, by definition, rather than by luck.
     */
    val otherMinor: Long = 0,
    /** carry-over + income − expenses + other, which is [balanceMinor]. */
    val netMinor: Long = 0,
    /** The thirty-day window, for the trend face only. See [balanceMinor]. */
    val windowIncomeMinor: Long = 0,
    val windowExpenseMinor: Long = 0,
    /**
     * What is actually in the accounts, not what moved through them — the
     * number the running line on the back of the card arrives at.
     *
     * Follows the account filter, so with everything selected — the default —
     * it is the household's total, and selecting one account narrows it to that
     * account's own balance.
     *
     * "Everything" is every OPEN account. An archived one is a closed envelope,
     * and its leftover is not part of where the household stands.
     */
    val balanceMinor: Long = 0,
    /**
     * The month [balanceMinor] is the closing position OF, or null when it is
     * simply where the accounts stand right now.
     *
     * Non-null for any month already over. A position is a running total, so
     * "what is in the accounts" under a switcher set to June has to mean what
     * was in them at the end of June — and the card has to say which of the two
     * it is showing, because the numbers are equally plausible either way.
     */
    val balanceThroughPeriod: String? = null,
    val breakdown: List<CategorySpend> = emptyList(),
    /** Everything still open, for the selector at the top. */
    val accounts: List<AccountBalance> = emptyList(),
    /** Empty means every account. Never a list of all ids — see the repository. */
    val selectedAccountIds: Set<String> = emptySet(),
    /** The back of the balance card: thirty days ending inside the selected
     *  month. Its window is NOT the month, which is why it carries its own
     *  dates and its own totals rather than reusing the ones above. */
    val trend: TrendSeries,
)

/**
 * A state for a period nothing has loaded for yet. The trend still gets a
 * properly shaped thirty-day window rather than an empty one, so the chart has
 * ends to label from its very first frame.
 */
private fun emptyState(period: String): OverviewUiState {
    val window = trendWindow(period)
    return OverviewUiState(
        period = period,
        trend = trendSeries(emptyList(), window.start, window.endInclusive),
    )
}

/**
 * The month's breakdown with every untouched category appended at zero.
 *
 * The query starts from transactions, so a category nothing was spent on in the
 * month has no row at all and drops out of the legend. That is a real answer —
 * "nothing" — and it was being shown as absence. The pie itself is unaffected: a
 * zero slice sweeps zero degrees.
 *
 * Order is preserved: the query already sorts by amount descending, and the
 * zeros go after it in the household's own category order.
 */
internal fun padBreakdown(
    spend: List<CategorySpend>,
    allCategories: List<CategoryRef>,
): List<CategorySpend> {
    val seen = spend.map { it.categoryId }.toSet()
    return spend + allCategories
        .filterNot { it.id in seen }
        .map { CategorySpend(categoryId = it.id, name = it.name, color = it.color, icon = it.icon, spentMinor = 0L) }
}

/** A window with its months but no spending in them yet, for the first frame. */
private fun emptyHistory(period: String): CategoryHistory =
    categoryHistory(emptyList(), historyWindow(period), period)

/**
 * Joins the app's [SelectedMonth] against the repository's reactive queries.
 * The month is NOT owned here — it is the same one Transactions and Budget are
 * showing, so stepping back on this screen steps all three back. Everything here
 * reads straight from Room — no network wait: the phone answers from its own
 * replica and the server is only ever a sync peer.
 */
class OverviewViewModel(
    private val repository: MonyxRepository,
    private val selectedMonth: SelectedMonth,
    private val chartPreferences: ChartPreferences,
) : ViewModel() {

    private val period = selectedMonth.period

    /**
     * The categories this phone keeps off the twelve-month chart, from disk.
     *
     * It starts empty for the frame or two DataStore takes to answer, so the
     * chart draws whole and then loses the hidden categories rather than the
     * other way round. That is the right way round: a chart that starts empty
     * and fills in looks broken, one that starts full looks like what it is.
     */
    val hidden: StateFlow<Set<String>> = chartPreferences.hidden
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun toggleHidden(id: String) {
        viewModelScope.launch { chartPreferences.toggle(id) }
    }

    /**
     * Whether the budget line is drawn, remembered like the hidden categories
     * and for the same reason: a household that does not budget by the month
     * should not have to dismiss the line every time it opens the chart.
     */
    val budgetHidden: StateFlow<Boolean> = chartPreferences.budgetHidden
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun toggleBudgetLine() {
        viewModelScope.launch { chartPreferences.toggleBudget() }
    }

    /**
     * Whether the breakdown card counts the selected month or a normal one —
     * one answer for both of its faces. See [ChartPreferences.showsMonth].
     */
    val showsMonth: StateFlow<Boolean> = chartPreferences.showsMonth
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true,
        )

    fun setAmountMode(mode: LegendAmount) {
        viewModelScope.launch {
            chartPreferences.setShowsMonth(mode == LegendAmount.SelectedMonth)
        }
    }

    /**
     * Hide all, or show all — from whatever the categories on screen are doing,
     * never from the stored set.
     *
     * The distinction is real now that the set outlives the screen: it can hold
     * ids for categories nobody has spent on in a year, and a control reading
     * those would offer "Show all" over a list where everything is already
     * showing. Show all clears the stored set outright, stale ids included,
     * which is the only housekeeping this preference will ever get.
     */
    fun toggleAllHidden(ids: List<String>) {
        viewModelScope.launch {
            val anyHidden = ids.any { it in hidden.value }
            chartPreferences.set(if (anyHidden) emptySet() else ids.toSet())
        }
    }

    /**
     * Which accounts the figures cover. **Empty means every one of them**, and
     * that is the state the screen starts in — it is not "nothing selected", it
     * is the unfiltered query, which is a different thing from a list of every
     * id (an archived account has no button and its transactions still belong
     * in an unfiltered total).
     *
     * There used to be an "All accounts" button carrying that state explicitly.
     * It is gone: with two accounts it was a third button that said the same
     * thing as both of the others being on, and the strip is the first thing on
     * the screen. Every account button is simply drawn selected while this is
     * empty. See [toggleAccount] for what a tap does then.
     */
    private val selectedAccounts = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<OverviewUiState> = combine(period, selectedAccounts, ::Pair)
        .flatMapLatest { (selectedPeriod, accountIds) ->
            val window = trendWindow(selectedPeriod)
            // A month already over gets its own closing position; the current one
            // — and any dated forward — keeps meaning "right now", which is what
            // the account chips beside it mean too.
            val closed = selectedPeriod < Dates.currentPeriod()
            val balances = if (closed) {
                repository.accountBalancesThrough(Dates.iso(Dates.lastDayOf(selectedPeriod)))
            } else {
                repository.accountBalances()
            }
            // Back to the 1st of the window's first month, which is usually a
            // few days before the window itself starts. Only the window is
            // drawn; the extra days are what let a point in the leading tail
            // report its OWN month to date rather than the part of it that
            // happens to be on screen.
            val dataStart = window.start.withDayOfMonth(1)
            combine(
                repository.spendByCategory(selectedPeriod, accountIds),
                // Three queries, zipped: the chips always show where the
                // accounts stand today, whatever month the card's headline is
                // about, and the deltas are how far the selected accounts moved
                // each day — transfers included, which income and expense are
                // not. One list cannot answer all three.
                combine(
                    repository.accountBalances(),
                    balances,
                    repository.dailyDeltas(
                        Dates.iso(window.start),
                        Dates.iso(window.endInclusive),
                        accountIds,
                    ),
                    ::Triple,
                ),
                repository.dailyTotals(
                    Dates.iso(dataStart),
                    Dates.iso(window.endInclusive),
                    accountIds,
                ),
                combine(
                    repository.monthTotals(selectedPeriod, accountIds),
                    repository.accountBalancesThrough(
                        Dates.iso(Dates.lastDayOf(Dates.shiftPeriod(selectedPeriod, -1))),
                    ),
                    ::Pair,
                ),
                repository.rootExpenseCategories(),
            ) { spend, (accounts, asOf, deltas), daily, (month, opening), allCategories ->
                val breakdown = padBreakdown(spend, allCategories)
                // Open accounts only, when nothing is filtered. An archived
                // account is a closed envelope — Wallet's per-trip funds come
                // across as these — and what is left in one is not money the
                // household can reach. Spending on it still counts everywhere
                // else on this screen; only the POSITION leaves it out.
                val counted: (AccountBalance) -> Boolean =
                    { if (accountIds.isEmpty()) it.archived == 0 else it.id in accountIds }
                val balanceMinor = asOf.filter(counted).sumOf { it.balanceMinor }
                val carryOverMinor = opening.filter(counted).sumOf { it.balanceMinor }
                val trend = trendSeries(
                    rows = daily,
                    from = window.start,
                    to = window.endInclusive,
                    // The line ENDS on the figure printed above it. Anchoring
                    // rather than agreeing: the two are the same number because
                    // one is counted back from the other.
                    endBalanceMinor = balanceMinor,
                    deltas = deltas,
                )
                OverviewUiState(
                    period = selectedPeriod,
                    // The MONTH, because that is the question the card is under:
                    // a month switcher sits directly above it, and a figure that
                    // ignores which month is chosen makes that control a lie for
                    // every month but this one. Judging a finished month against
                    // its budget is the whole reason to look back at one.
                    incomeMinor = month.incomeMinor,
                    expenseMinor = month.expenseMinor,
                    carryOverMinor = carryOverMinor,
                    otherMinor = balanceMinor - carryOverMinor - month.incomeMinor + month.expenseMinor,
                    netMinor = balanceMinor,
                    // The thirty days the chart on the back actually draws —
                    // the drawn window, not the query's, which reaches further
                    // back than the chart shows.
                    windowIncomeMinor = trend.points.sumOf { it.incomeMinor },
                    windowExpenseMinor = trend.points.sumOf { it.expenseMinor },
                    balanceMinor = balanceMinor,
                    balanceThroughPeriod = selectedPeriod.takeIf { closed },
                    breakdown = breakdown,
                    // An archived account is not offered as a chip, but its
                    // transactions are still in every unfiltered figure above.
                    accounts = accounts.filter { it.archived == 0 },
                    selectedAccountIds = accountIds,
                    trend = trend,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyState(period.value),
        )

    /**
     * The back of the breakdown card: twelve months of spending by category.
     *
     * Its own flow rather than a fifth source folded into [uiState], because it
     * is a different window — twelve months against one — and because the card
     * it feeds is usually turned to the pie chart. WhileSubscribed means the
     * query is live only while the Overview is on screen, and a screen that is
     * showing the pie is still cheap: one grouped query over a year of rows.
     *
     * Follows the account filter, so flipping the card cannot quietly widen
     * what is being counted.
     *
     * The budget limits it draws over the bars cannot follow that filter — a
     * limit is the household's, and there is no such thing as the grocery budget
     * for the current account — so they are drawn whatever the filter says.
     *
     * They used to be dropped instead, on the argument that a household line
     * above one account's bars reads as comfortably under budget all year. The
     * argument is sound and the behaviour was still wrong: turning an account
     * off made the red line vanish with no explanation on screen, which reads as
     * a bug rather than as a refusal to answer. The line is the one fixed
     * reference on the chart, and a reference that disappears when you narrow
     * the view is worse than one you have to interpret. Filtering accounts
     * narrows the bars; the household's limit is what it always was.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<CategoryHistory> = combine(period, selectedAccounts, ::Pair)
        .flatMapLatest { (selectedPeriod, accountIds) ->
            val periods = historyWindow(selectedPeriod)
            combine(
                repository.spendByCategoryPerMonth(periods.first(), periods.last(), accountIds),
                repository.budgetLimitsThrough(periods.last()),
                repository.rootExpenseCategories(),
            ) { rows, limits, allCategories ->
                categoryHistory(
                    rows = rows,
                    periods = periods,
                    selectedPeriod = selectedPeriod,
                    budgets = limits,
                    allCategories = allCategories,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyHistory(period.value),
        )

    fun setPeriod(period: String) {
        selectedMonth.set(period)
    }

    /**
     * Turn one account's button on or off, given the ids of every button on
     * screen.
     *
     * The list has to be passed in because the stored empty set means "all", and
     * the first tap on a screen where everything is on has to mean "all EXCEPT
     * this one" — with no button standing for "all", a tap on a lit button that
     * lit every other one would be the only tap that did nothing visible.
     *
     * Two states collapse back to empty, and both are deliberate. Everything
     * selected IS the unfiltered query and has to be stored as such, or an
     * archived account's rows would silently drop out of the totals. And
     * turning the last one off returns to all rather than leaving an empty
     * screen — an overview showing nothing is never what the tap meant.
     */
    fun toggleAccount(id: String, allIds: List<String>) {
        val all = allIds.toSet()
        val effective = selectedAccounts.value.ifEmpty { all }
        val next = if (id in effective) effective - id else effective + id
        selectedAccounts.value = if (next.isEmpty() || next == all) emptySet() else next
    }

    companion object {
        fun factory(
            repository: MonyxRepository,
            selectedMonth: SelectedMonth,
            chartPreferences: ChartPreferences,
        ) = viewModelFactory {
            initializer { OverviewViewModel(repository, selectedMonth, chartPreferences) }
        }
    }
}

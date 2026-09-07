package com.monyx.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.AccountBalance
import com.monyx.data.AccountEntity
import com.monyx.data.CategoryEntity
import com.monyx.data.CategorySpend
import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import com.monyx.data.TransactionEntity
import com.monyx.data.TransactionListItem
import com.monyx.ui.SelectedMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
     * What is actually in the accounts, not what moved through them.
     *
     * This used to be income minus expenses for the month, which is a rate
     * rather than a position: it said "we are 900 zł up in September" on a card
     * headed Bilans, next to a strip of account buttons whose balances added up
     * to something else entirely. The two numbers were both right and only one
     * of them was the one being asked for.
     *
     * Follows the account filter, so with everything selected — the default —
     * it is the household's total, and selecting one account narrows it to that
     * account's own balance.
     */
    val balanceMinor: Long = 0,
    val breakdown: List<CategorySpend> = emptyList(),
    /** Everything still open, for the selector at the top. */
    val accounts: List<AccountBalance> = emptyList(),
    /** Empty means every account. Never a list of all ids — see the repository. */
    val selectedAccountIds: Set<String> = emptySet(),
    /** The selected month's transactions, newest first — NOT the newest rows in
     *  the database. Stepping back a month must not keep showing today's. */
    val recent: List<TransactionListItem> = emptyList(),
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
 * Joins the app's [SelectedMonth] against the repository's reactive queries.
 * The month is NOT owned here — it is the same one Transactions and Budget are
 * showing, so stepping back on this screen steps all three back. Everything here
 * reads straight from Room — no network wait: the phone answers from its own
 * replica and the server is only ever a sync peer.
 */
class OverviewViewModel(
    private val repository: MonyxRepository,
    private val selectedMonth: SelectedMonth,
) : ViewModel() {

    private val period = selectedMonth.period

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
            combine(
                repository.spendByCategory(selectedPeriod, accountIds),
                repository.accountBalances(),
                repository.recentTransactions(selectedPeriod, 12, accountIds),
                repository.dailyTotals(
                    Dates.iso(window.start),
                    Dates.iso(window.endInclusive),
                    accountIds,
                ),
            ) { breakdown, accounts, recent, daily ->
                OverviewUiState(
                    period = selectedPeriod,
                    // Summed from the same rows the chart is drawn from rather
                    // than fetched again for the month. monthTotals is no longer
                    // asked for at all here: two queries answering one question
                    // is how the front and the back of a card start disagreeing.
                    incomeMinor = daily.sumOf { it.incomeMinor },
                    expenseMinor = daily.sumOf { it.expenseMinor },
                    balanceMinor = accounts
                        .filter { accountIds.isEmpty() || it.id in accountIds }
                        .sumOf { it.balanceMinor },
                    breakdown = breakdown,
                    // An archived account is not offered as a chip, but its
                    // transactions are still in every unfiltered figure above.
                    accounts = accounts.filter { it.archived == 0 },
                    selectedAccountIds = accountIds,
                    recent = recent,
                    trend = trendSeries(daily, window.start, window.endInclusive),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyState(period.value),
        )

    /** For the editor a recent row opens. Both kinds and every account it
     *  might already sit on, which is what EditTransactionSheet expects. */
    val categories: StateFlow<List<CategoryEntity>> = repository.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.accounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The recent list is a projection, not the row. */
    suspend fun load(id: String): TransactionEntity? = repository.transaction(id)

    /**
     * The same write History makes, through the same fold, so the two screens
     * cannot disagree about what an edit is. Sync arrives as a lambda rather
     * than a Context — nothing in this ViewModel knows WorkManager exists.
     */
    fun saveEdit(
        original: TransactionEntity,
        amountMinor: Long,
        categoryId: String?,
        accountId: String,
        note: String,
        occurredAtMs: Long,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.updateTransaction(
                MonyxRepository.applyEdit(
                    original = original,
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    accountId = accountId,
                    note = note,
                    occurredAtMs = occurredAtMs,
                ),
            )
            onSaved()
        }
    }

    fun deleteTransaction(id: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteTransaction(id)
            onDeleted()
        }
    }

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
        fun factory(repository: MonyxRepository, selectedMonth: SelectedMonth) = viewModelFactory {
            initializer { OverviewViewModel(repository, selectedMonth) }
        }
    }
}

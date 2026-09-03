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
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
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
) {
    /** Balance = income - expenses for the selected month. */
    val balanceMinor: Long get() = incomeMinor - expenseMinor
}

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
    private val selectedAccounts = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<OverviewUiState> = combine(period, selectedAccounts, ::Pair)
        .flatMapLatest { (selectedPeriod, accountIds) ->
            val window = trendWindow(selectedPeriod)
            combine(
                repository.monthTotals(selectedPeriod, accountIds),
                repository.spendByCategory(selectedPeriod, accountIds),
                repository.accountBalances(),
                repository.recentTransactions(selectedPeriod, 12, accountIds),
                repository.dailyTotals(
                    Dates.iso(window.start),
                    Dates.iso(window.endInclusive),
                    accountIds,
                ),
            ) { totals, breakdown, accounts, recent, daily ->
                OverviewUiState(
                    period = selectedPeriod,
                    incomeMinor = totals.incomeMinor,
                    expenseMinor = totals.expenseMinor,
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
     *  might already sit on, which is what EditTransactionDialog expects. */
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
     * Toggling the last selected chip off returns to "all accounts" rather than
     * leaving an empty screen — an overview showing nothing at all is never what
     * the tap meant.
     */
    fun toggleAccount(id: String) {
        val current = selectedAccounts.value
        selectedAccounts.value = if (id in current) current - id else current + id
    }

    fun clearAccountFilter() {
        selectedAccounts.value = emptySet()
    }

    companion object {
        fun factory(repository: MonyxRepository, selectedMonth: SelectedMonth) = viewModelFactory {
            initializer { OverviewViewModel(repository, selectedMonth) }
        }
    }
}

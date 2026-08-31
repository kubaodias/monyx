package com.monyx.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.AccountBalance
import com.monyx.data.CategorySpend
import com.monyx.data.Dates
import com.monyx.data.MonyxRepository
import com.monyx.data.TransactionListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

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
) {
    /** Balance = income - expenses for the selected month. */
    val balanceMinor: Long get() = incomeMinor - expenseMinor
}

/**
 * Holds the month switcher's selected period, defaulting to the current one,
 * and joins it against the repository's reactive queries. Everything here
 * reads straight from Room — no network wait: the phone answers from its own
 * replica and the server is only ever a sync peer.
 */
class OverviewViewModel(private val repository: MonyxRepository) : ViewModel() {

    private val period = MutableStateFlow(Dates.currentPeriod())
    private val selectedAccounts = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<OverviewUiState> = combine(period, selectedAccounts, ::Pair)
        .flatMapLatest { (selectedPeriod, accountIds) ->
            combine(
                repository.monthTotals(selectedPeriod, accountIds),
                repository.spendByCategory(selectedPeriod, accountIds),
                repository.accountBalances(),
                repository.recentTransactions(selectedPeriod, 12, accountIds),
            ) { totals, breakdown, accounts, recent ->
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
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OverviewUiState(period = period.value),
        )

    fun previousMonth() {
        period.value = Dates.shiftPeriod(period.value, -1)
    }

    fun nextMonth() {
        period.value = Dates.shiftPeriod(period.value, 1)
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
        fun factory(repository: MonyxRepository) = viewModelFactory {
            initializer { OverviewViewModel(repository) }
        }
    }
}

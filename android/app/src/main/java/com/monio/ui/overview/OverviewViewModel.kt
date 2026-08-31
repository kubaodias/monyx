package com.monio.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monio.data.AccountBalance
import com.monio.data.CategorySpend
import com.monio.data.Dates
import com.monio.data.MonioRepository
import com.monio.data.TransactionListItem
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
    val accounts: List<AccountBalance> = emptyList(),
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
class OverviewViewModel(private val repository: MonioRepository) : ViewModel() {

    private val period = MutableStateFlow(Dates.currentPeriod())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<OverviewUiState> = period
        .flatMapLatest { selectedPeriod ->
            combine(
                repository.monthTotals(selectedPeriod),
                repository.spendByCategory(selectedPeriod),
                repository.accountBalances(),
                repository.recentTransactions(8),
            ) { totals, breakdown, accounts, recent ->
                OverviewUiState(
                    period = selectedPeriod,
                    incomeMinor = totals.incomeMinor,
                    expenseMinor = totals.expenseMinor,
                    breakdown = breakdown,
                    accounts = accounts,
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

    companion object {
        fun factory(repository: MonioRepository) = viewModelFactory {
            initializer { OverviewViewModel(repository) }
        }
    }
}

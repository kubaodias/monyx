package com.monio.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monio.data.BudgetUsage
import com.monio.data.CategoryEntity
import com.monio.data.Dates
import com.monio.data.MonioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the Budget screen. Reads are always local — the phone's number is the
 * one displayed (PRD §7, "No /budgets/status"). Writes set `pending` locally
 * and return immediately; the caller enqueues sync separately, since kicking
 * off a WorkManager job is the composable's job, not the ViewModel's (no
 * Context is held here on purpose).
 */
class BudgetViewModel(private val repository: MonioRepository) : ViewModel() {

    private val _period = MutableStateFlow(Dates.currentPeriod())
    val period: StateFlow<String> = _period.asStateFlow()

    val budgetUsage: StateFlow<List<BudgetUsage>> = _period
        .flatMapLatest { p -> repository.budgetUsage(p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Expense categories that do not yet have a limit for the current period. */
    val availableToAdd: StateFlow<List<CategoryEntity>> = combine(
        repository.expenseCategories(),
        budgetUsage,
    ) { categories, usage ->
        val budgeted = usage.map { it.categoryId }.toSet()
        categories.filter { it.id !in budgeted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setInitialPeriod(period: String) {
        if (period.isNotBlank()) _period.value = period
    }

    fun previousMonth() {
        _period.value = Dates.shiftPeriod(_period.value, -1)
    }

    fun nextMonth() {
        _period.value = Dates.shiftPeriod(_period.value, 1)
    }

    /** Sets or changes the limit for the currently selected period. */
    suspend fun setBudget(categoryId: String, limitMinor: Long) {
        repository.setBudget(categoryId, _period.value, limitMinor)
    }

    /** Tombstones the limit for the currently selected period; carry-forward
     *  stops there (PRD §6) — earlier periods are unaffected. */
    suspend fun clearBudget(categoryId: String) {
        repository.clearBudget(categoryId, _period.value)
    }

    companion object {
        fun factory(repository: MonioRepository) = viewModelFactory {
            initializer { BudgetViewModel(repository) }
        }
    }
}

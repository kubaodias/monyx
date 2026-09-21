package com.monyx.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.BudgetUsage
import com.monyx.data.CategoryEntity
import com.monyx.data.MonyxRepository
import com.monyx.ui.SelectedMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

/**
 * Backs the Budget screen. Reads are always local — the phone's number is the
 * one displayed, and there is no /budgets/status endpoint. Writes set
 * `pending` locally and return immediately; the caller enqueues sync
 * separately, since kicking off a WorkManager job is the composable's job,
 * not the ViewModel's (no Context is held here on purpose).
 */
/**
 * The month's plan, as the screen needs it.
 *
 * Two different questions, deliberately both answered. "Left to assign" is
 * about the plan on paper — has every złoty been given a job. "Left to spend"
 * is about the month as it actually went, and it counts expenses in categories
 * with no budget at all, because unbudgeted spending empties the same pot.
 */
data class PlanState(
    val period: String,
    val plannedMinor: Long = 0,
    val hasPlan: Boolean = false,
    val assignedMinor: Long = 0,
    val spentMinor: Long = 0,
    /**
     * What last month left behind in the accounts it was paid from: their
     * combined balance on its last day. Negative when the month ended in the
     * red — that shortfall is already spent, so it comes out of this month's
     * room before anything new does. Zero when last month has no spending to
     * say which accounts count.
     */
    val carryOverMinor: Long = 0,
    /** The accounts [carryOverMinor] was read from, for the line that shows it. */
    val carryOverAccounts: List<String> = emptyList(),
) {
    /**
     * Null when the month has no plan, and that is not the same as zero.
     *
     * Subtracting assignments from a plan that does not exist produces a large
     * red negative — categories carry their limits forward from earlier months,
     * so an unplanned month starts out looking catastrophically over-assigned
     * against a total of nothing. There is no answer to "how much is left to
     * assign" until somebody says how much there is.
     */
    val leftToAssignMinor: Long? get() = if (hasPlan) plannedMinor - assignedMinor else null
    val leftToSpendMinor: Long? get() = if (hasPlan) plannedMinor + carryOverMinor - spentMinor else null
    val hasCarryOver: Boolean get() = carryOverAccounts.isNotEmpty()
    val overAssigned: Boolean get() = (leftToAssignMinor ?: 0) < 0
}

class BudgetViewModel(
    private val repository: MonyxRepository,
    private val selectedMonth: SelectedMonth,
) : ViewModel() {

    /** Shared with Overview and Transactions — see [SelectedMonth]. */
    private val _period = selectedMonth.period
    val period: StateFlow<String> = _period

    val budgetUsage: StateFlow<List<BudgetUsage>> = _period
        .flatMapLatest { p -> repository.budgetUsage(p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plan: StateFlow<PlanState> = _period
        .flatMapLatest { p ->
            val previous = YearMonth.parse(p).minusMonths(1)
            val carryOver = combine(
                repository.spendingAccountIds(previous.toString()),
                repository.accountBalancesThrough(previous.atEndOfMonth().toString()),
            ) { ids, balances ->
                balances.filter { it.id in ids }
            }
            combine(
                repository.monthPlan(p),
                repository.budgetUsage(p),
                repository.monthTotals(p),
                carryOver,
            ) { planRow, usage, totals, carried ->
                PlanState(
                    period = p,
                    plannedMinor = planRow?.plannedMinor ?: 0,
                    hasPlan = planRow != null,
                    assignedMinor = usage.sumOf { it.limitMinor },
                    spentMinor = totals.expenseMinor,
                    carryOverMinor = carried.sumOf { it.balanceMinor },
                    carryOverAccounts = carried.map { it.name },
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlanState(period = _period.value),
        )

    /**
     * Main categories that do not yet have a limit for the current period.
     *
     * Subcategories are deliberately not offered. A limit on a parent already
     * covers everything under it — budgetUsage sums the children into it — so a
     * second limit on one child is a budget inside a budget, and the two answer
     * the same question differently. Offering both is how a household ends up
     * with Dom i ogród capped at 1000 and Ogród capped at 400 underneath it,
     * with nothing on screen to say which one is being enforced.
     *
     * A limit already set on a subcategory is left alone rather than hidden:
     * budgetUsage still returns it and the list still shows it, so it can be
     * seen and removed. This narrows what can be CREATED, not what exists.
     */
    val availableToAdd: StateFlow<List<CategoryEntity>> = combine(
        repository.expenseCategories(),
        budgetUsage,
    ) { categories, usage ->
        val budgeted = usage.map { it.categoryId }.toSet()
        categories.filter { it.parentId == null && it.id !in budgeted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPeriod(period: String) {
        selectedMonth.set(period)
    }

    suspend fun setPlan(plannedMinor: Long) {
        repository.setMonthPlan(_period.value, plannedMinor)
    }

    suspend fun clearPlan() {
        repository.clearMonthPlan(_period.value)
    }

    /** What to prefill the plan field with when the month has none yet. */
    suspend fun suggestedPlanMinor(): Long = repository.suggestedPlanMinor(_period.value)

    /** Sets or changes the limit for the currently selected period. */
    suspend fun setBudget(categoryId: String, limitMinor: Long) {
        repository.setBudget(categoryId, _period.value, limitMinor)
    }

    /** Tombstones the limit for the currently selected period; carry-forward
     *  stops there — earlier periods are unaffected. */
    suspend fun clearBudget(categoryId: String) {
        repository.clearBudget(categoryId, _period.value)
    }

    companion object {
        fun factory(repository: MonyxRepository, selectedMonth: SelectedMonth) = viewModelFactory {
            initializer { BudgetViewModel(repository, selectedMonth) }
        }
    }
}

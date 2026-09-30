package com.monyx.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.BudgetUsage
import com.monyx.data.CategoryEntity
import com.monyx.data.MonyxRepository
import com.monyx.data.budgetAccountIds
import com.monyx.ui.SelectedMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

/**
 * How a limit is doing, for the colour the row is drawn in.
 *
 * Three bands and not four: the point of the band is the ALARM, and an alarm
 * that goes off for four złoty over 1 650 is an alarm the eye learns to skip.
 * [Close] therefore covers both "nearly there" and "over by a rounding error",
 * which keeps the escalation monotone — nothing may look calmer than the state
 * before it, so a hair over the limit can never come out gentler than 95%.
 */
enum class BudgetBand { Under, Close, Over }

/** Over the limit by at least this much of it before the row turns red. */
private const val OVER_MARGIN = 0.01

/**
 * The band [spentMinor] falls in against [limitMinor].
 *
 * A limit of nothing is a real limit — "do not spend here" — so any spending
 * against it is over, with no margin to be within: one per cent of zero is zero.
 */
fun overBudgetBand(spentMinor: Long, limitMinor: Long): BudgetBand = when {
    limitMinor <= 0L -> if (spentMinor > 0L) BudgetBand.Over else BudgetBand.Under
    spentMinor > limitMinor + (limitMinor * OVER_MARGIN) -> BudgetBand.Over
    spentMinor >= limitMinor * 0.8 -> BudgetBand.Close
    else -> BudgetBand.Under
}

class BudgetViewModel(
    private val repository: MonyxRepository,
    private val selectedMonth: SelectedMonth,
) : ViewModel() {

    /** Shared with Overview and Transactions — see [SelectedMonth]. */
    private val _period = selectedMonth.period
    val period: StateFlow<String> = _period

    /**
     * The accounts every figure on this screen counts: the default one — see
     * [budgetAccountIds]. A flow, not a value read once, because the household
     * can reorder its accounts and the answer has to move with them.
     */
    private val budgetAccounts: Flow<Set<String>> =
        repository.accounts().map(::budgetAccountIds)

    val budgetUsage: StateFlow<List<BudgetUsage>> =
        combine(_period, budgetAccounts, ::Scope)
            .flatMapLatest { (p, accounts) -> repository.budgetUsage(p, accounts) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The two things every query here is scoped by, so one flatMapLatest sees both. */
    private data class Scope(val period: String, val accounts: Set<String>)

    val plan: StateFlow<PlanState> = combine(_period, budgetAccounts, ::Scope)
        .flatMapLatest { (p, accounts) ->
            val previous = YearMonth.parse(p).minusMonths(1)
            val carryOver = combine(
                repository.spendingAccountIds(previous.toString()),
                repository.accountBalancesThrough(previous.atEndOfMonth().toString()),
            ) { ids, balances ->
                // Narrowed the same way the limits and the spend are. "Left to
                // spend" is this plus the plan minus the spend, so a carry-over
                // counting every account against a spend counting one made that
                // figure a mix of two questions: it told you there was money
                // left when the account it comes out of was empty.
                balances.filter { it.id in ids && (accounts.isEmpty() || it.id in accounts) }
            }
            combine(
                repository.monthPlan(p),
                repository.budgetUsage(p, accounts),
                // Scoped like the limits above it. Left unscoped, "spent this
                // month" counted every account while each limit under it counted
                // one, so the plan disagreed with the rows meant to add up to it.
                repository.monthTotals(p, accounts),
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

    /**
     * Sets or changes the limit for the currently selected period — and only
     * that one, unless [alsoFutureMonths]. See [com.monyx.data.BudgetEdit].
     */
    suspend fun setBudget(categoryId: String, limitMinor: Long, alsoFutureMonths: Boolean = false) {
        repository.setBudget(categoryId, _period.value, limitMinor, alsoFutureMonths)
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

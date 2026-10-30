package com.monyx.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monyx.data.BudgetUsage
import com.monyx.data.CategoryEntity
import com.monyx.data.Dates
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
import kotlinx.coroutines.launch
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
 * Two different questions, deliberately both answered — but against ONE pot.
 * "Left to assign" is about the plan on paper: has every złoty been given a
 * job. "Left to spend" is about the month as it actually went, and it counts
 * expenses in categories with no budget at all, because unbudgeted spending
 * empties the same pot. What they must never disagree about is how big the pot
 * is; see [availableMinor].
 */
data class PlanState(
    val period: String,
    val plannedMinor: Long = 0,
    val hasPlan: Boolean = false,
    val assignedMinor: Long = 0,
    val spentMinor: Long = 0,
    /**
     * What has actually arrived this month, on the accounts this screen counts.
     *
     * Not a figure anything else on the card is computed from — the plan is a
     * stored number and stays one, see ADR 0006. It is here because the card
     * had no answer to the one question somebody asks when it disagrees with
     * the account balance: the balance can still be comfortable while "left to
     * spend" is red, and the whole of that gap is income the plan never
     * assumed. A plan of 22 483,98 against 24 431,00 that came in is 1 947,02
     * of real money the month was not planning on, and until it was on screen
     * the two figures looked like a bug.
     */
    val incomeMinor: Long = 0,
    /**
     * How far the category limits were passed, added up, and how many were —
     * see [overLimits]. Zero and nothing when every limit held.
     */
    val overLimitMinor: Long = 0,
    val overLimitCount: Int = 0,
    /**
     * True once the whole month is behind us.
     *
     * Only the wording turns on this, but the wording matters: "left to spend"
     * on a month that ended is an offer that cannot be taken. What a closed
     * month has is spending that did or did not stay inside its means.
     */
    val closed: Boolean = false,
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
     * The money this month actually has: the plan, less whatever last month
     * ended up owing. Every other figure on the card divides or subtracts from
     * this one.
     *
     * The carry-over used to be left out of "left to assign" and included only
     * in "left to spend", which made them two different answers to how much
     * money exists. On a month opening 4 186,58 in the red with 16 355 handed
     * out against a 20 000 plan, "left to assign" read +3 645 — an invitation
     * to plan money that was already spent — while the figure three lines below
     * it was quietly counting the same hole. The shortfall is not a fact about
     * spending; it is a claim on the pot, and it has to be taken off the top
     * before anything is handed out.
     */
    val availableMinor: Long get() = plannedMinor + carryOverMinor

    /**
     * Null when the month has no plan, and that is not the same as zero.
     *
     * Subtracting assignments from a plan that does not exist produces a large
     * red negative — categories carry their limits forward from earlier months,
     * so an unplanned month starts out looking catastrophically over-assigned
     * against a total of nothing. There is no answer to "how much is left to
     * assign" until somebody says how much there is.
     */
    val leftToAssignMinor: Long? get() = if (hasPlan) availableMinor - assignedMinor else null
    val leftToSpendMinor: Long? get() = if (hasPlan) availableMinor - spentMinor else null
    val hasCarryOver: Boolean get() = carryOverAccounts.isNotEmpty()
    val overAssigned: Boolean get() = (leftToAssignMinor ?: 0) < 0

    /**
     * How far past the month's money the spending went — null while it is still
     * inside it, and null on a month with no plan to be outside of.
     *
     * The same figure as a negative [leftToSpendMinor], as a size. A row of this
     * screen has said "przekroczono o 40 zł" since the first version; the card
     * above them said "pozostało do wydania −767,74", which is the same
     * sentence with the reader doing the sign.
     */
    val overspentMinor: Long? get() = leftToSpendMinor?.takeIf { it < 0 }?.let { -it }

    /**
     * What came in against what the plan assumed — null on an unplanned month.
     *
     * Positive is money the month was not counting on. See [incomeMinor] for
     * why the card shows it at all.
     */
    val incomeVsPlanMinor: Long? get() = if (hasPlan) incomeMinor - plannedMinor else null

    /**
     * The same difference, but only where it is worth a line on the card.
     *
     * Money over the plan is news the day it lands. Money under it is not, until
     * the month has ended — a plan is written for a whole month, so on the third
     * of it every household on earth is "19 000 below plan", and a card that
     * says so every morning cries wolf about a month going exactly as intended.
     * Once the month is closed the same figure is a verdict, and then it belongs
     * on screen.
     */
    val incomeNoteMinor: Long? get() = incomeVsPlanMinor?.takeIf { it > 0 || (it < 0 && closed) }

    val hasOverLimits: Boolean get() = overLimitCount > 0
}

/** [overLimits]'s answer: how much over, across how many limits. */
data class OverLimits(val totalMinor: Long = 0, val count: Int = 0)

/**
 * How far the month's limits were passed, added up over the rows that passed
 * them.
 *
 * Counts a row only once it is actually [BudgetBand.Over] — the same test the
 * row's own colour uses, margin and all. That makes the total slightly smaller
 * than "spent minus limit, everywhere it is positive": four złoty over 1 650 is
 * inside the margin and is left out. The alternative is a card announcing a
 * category over its limit above a list where no row is red, and a figure
 * nothing on screen explains is worse than a figure that is 4 zł polite.
 *
 * A limit of nothing with spending against it is over by the whole amount,
 * which is what [overBudgetBand] already says about it.
 */
fun overLimits(rows: List<BudgetUsage>): OverLimits {
    val over = rows
        .filter { overBudgetBand(it.spentMinor, it.limitMinor) == BudgetBand.Over }
        .map { it.spentMinor - it.limitMinor }
    return OverLimits(totalMinor = over.sum(), count = over.size)
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
    private val preferences: BudgetPreferences,
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

    /**
     * The one account every figure on this screen counts, for the jump into the
     * ledger — null while the list has not loaded, which reads as "every counted
     * account" the way the queries read it.
     *
     * A budget row is the default account's spending against a limit — see
     * [budgetAccountIds] — so a ledger opened from that row has to be narrowed
     * the same way or it will not add up to the bar that was tapped. One euro
     * card and one insurance premium is all it takes for the two to disagree by
     * a figure nothing on either screen explains.
     */
    val budgetAccountId: StateFlow<String?> = budgetAccounts
        .map { it.singleOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How the list is ordered on this phone. See [BudgetSort]. */
    val sort: StateFlow<BudgetSort> = preferences.sort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetSort.Own)

    /**
     * The rows, in the chosen order.
     *
     * Sorted here rather than in SQL. Three of the four orders are arithmetic on
     * the spend the query has just computed — one of them a ratio — and the
     * query already carries a correlated subquery per row to get it; the rule is
     * also the one thing on this screen worth a unit test, which it cannot have
     * inside a @Query. See [sortedBudgets].
     */
    val budgetUsage: StateFlow<List<BudgetUsage>> =
        combine(_period, budgetAccounts, ::Scope)
            .flatMapLatest { (p, accounts) -> repository.budgetUsage(p, accounts) }
            .combine(preferences.sort, ::sortedBudgets)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(sort: BudgetSort) {
        viewModelScope.launch { preferences.setSort(sort) }
    }

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
                val over = overLimits(usage)
                PlanState(
                    period = p,
                    plannedMinor = planRow?.plannedMinor ?: 0,
                    hasPlan = planRow != null,
                    assignedMinor = usage.sumOf { it.limitMinor },
                    spentMinor = totals.expenseMinor,
                    incomeMinor = totals.incomeMinor,
                    overLimitMinor = over.totalMinor,
                    overLimitCount = over.count,
                    // Both are 'yyyy-MM', so this is the month comparison.
                    closed = p < Dates.currentPeriod(),
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
        fun factory(
            repository: MonyxRepository,
            selectedMonth: SelectedMonth,
            preferences: BudgetPreferences,
        ) = viewModelFactory {
            initializer { BudgetViewModel(repository, selectedMonth, preferences) }
        }
    }
}

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
 * The month's money, as the screen needs it.
 *
 * Two different questions, deliberately both answered — but against ONE pot.
 * "Left to assign" is about the plan on paper: has every złoty been given a
 * job. "Left to spend" is about the month as it actually went, and it counts
 * expenses in categories with no budget at all, because unbudgeted spending
 * empties the same pot. What they must never disagree about is how big the pot
 * is; see [availableMinor], which is where the stored plan stopped being the
 * answer and became a floor under it.
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
     * The larger of the two, plus last month's remainder — not the plan alone.
     *
     * A stored plan cannot be wrong about money that has already landed. The
     * household typed a round 22 000,00 for November; 24 450,00 arrived. The
     * card then said 3 288,19 was left to hand out while 5 738,19 was really
     * there, and the whole of the difference was already on screen one line
     * below, as a hint beside "Wpłynęło" — the screen knew the right figure and
     * subtracted it from itself.
     *
     * So the typed figure is a FORECAST: what the month expects before the
     * money is in. It can only raise the pot, never lower it, which is the
     * reason ADR 0006 refused derivation in the first place — "a plan that
     * changes on its own is not a plan". Under the max rule nothing a household
     * planned is ever taken away from it; the pot only catches up with reality.
     * And once pay has landed the forecast is spent, so a month nobody planned
     * at all still has a real pot — see [hasPot]. There is nothing to start.
     *
     * The carry-over is added to whichever figure wins. It used to be left out
     * of "left to assign" and included only in "left to spend", which made them
     * two different answers to how much money exists: a month opening 4 186,58
     * in the red with 16 355 handed out against a 20 000 plan read +3 645 — an
     * invitation to plan money that was already spent. A shortfall is not a
     * fact about spending; it is a claim on the pot, taken off the top before
     * anything is handed out.
     */
    val availableMinor: Long get() = maxOf(plannedMinor, incomeMinor) + carryOverMinor

    /**
     * Whether this month has a pot to divide at all.
     *
     * A typed plan, or money that has actually arrived. Either is an answer to
     * "how much is there"; neither being present is the only state where the
     * card has nothing to say and shows em dashes. This is what retired the
     * once-a-month ritual of opening a sheet to type a figure the app could
     * already see on the account.
     */
    val hasPot: Boolean get() = hasPlan || incomeMinor > 0

    /**
     * Null when the month has no pot at all, and that is not the same as zero.
     *
     * Subtracting assignments from a pot that does not exist produces a large
     * red negative — categories carry their limits forward from earlier months,
     * so such a month starts out looking catastrophically over-assigned against
     * a total of nothing. There is no answer to "how much is left to hand out"
     * until either somebody says how much there is or some of it arrives.
     */
    val leftToAssignMinor: Long? get() = if (hasPot) availableMinor - assignedMinor else null
    val leftToSpendMinor: Long? get() = if (hasPot) availableMinor - spentMinor else null
    val hasCarryOver: Boolean get() = carryOverAccounts.isNotEmpty()
    val overAssigned: Boolean get() = (leftToAssignMinor ?: 0) < 0

    /**
     * Whether being over is worth an alarm, for the two figures that can be.
     *
     * The rows under this card have drawn the distinction since 0.21: [over] is
     * a FACT and says itself in words, `seriouslyOver` is the ALARM and gets the
     * colour — one per cent of the limit, because a 1 650 zł limit missed by
     * four złoty is not the same event as one missed by four hundred, and
     * painting both red taught the eye to skip red. See [overBudgetBand].
     *
     * The card had no such distinction and turned red on the first grosz. On a
     * month whose limits were being filled in to the last grosz that meant
     * 24 837,97 handed out against 24 837,19 — over by 78 grosze — lighting up
     * exactly as loudly as a month 3 000 zł overdrawn. Same rule as the rows
     * now, from the same function, so there is one rule and not two.
     *
     * Both are false on a month with no pot: nothing to be over.
     */
    val seriouslyOverspent: Boolean
        get() = hasPot && overBudgetBand(spentMinor, availableMinor) == BudgetBand.Over
    val seriouslyOverAssigned: Boolean
        get() = hasPot && overBudgetBand(assignedMinor, availableMinor) == BudgetBand.Over

    /**
     * How far past the month's money the spending went — null while it is still
     * inside it, and null on a month with no pot to be outside of.
     *
     * The same figure as a negative [leftToSpendMinor], as a size. A row of this
     * screen has said "przekroczono o 40 zł" since the first version; the card
     * above them said "pozostało do wydania −767,74", which is the same
     * sentence with the reader doing the sign.
     */
    val overspentMinor: Long? get() = leftToSpendMinor?.takeIf { it < 0 }?.let { -it }

    /**
     * The typed forecast, but only while it is still the figure doing the work.
     *
     * Once what arrived has passed it, the forecast is a number nobody needs to
     * read: the pot is income plus the carry-over and the two lines below say
     * so. A line printing what the household typed, next to a larger figure it
     * no longer explains, is the one line on this card that was an echo rather
     * than an answer.
     *
     * "What came in against what the plan assumed" used to be a line of its own
     * (and a hint beside the income line, "1 947,02 ponad plan"). It is gone
     * because there is nothing left to reconcile — the difference is IN the pot
     * now instead of being printed next to it.
     */
    val forecastMinor: Long? get() = plannedMinor.takeIf { hasPlan && it > incomeMinor }
}

/**
 * The figure being typed into the plan sheet, against the month it is for.
 *
 * The sheet used to answer "left to plan" as `typed − assigned`, while the card
 * behind it answered `typed + carry-over − assigned`. Both were on screen at
 * once and they disagreed by the carry-over: a month opening 387,19 to the good
 * showed 2 901,00 under the keypad and 3 288,19 on the card. The pot is defined
 * once, in [PlanState.availableMinor], and this is the same arithmetic while the
 * number is still being pressed.
 *
 * It also carries what the month has actually received, for the same reason
 * [PlanState.availableMinor] does: a figure being typed cannot make the pot
 * smaller than the money already in the account. Type 15 000 in a month that
 * received 24 450,00 and the sheet keeps answering 24 450,00 + the carry-over,
 * which is the truth and is what the card behind it will show.
 */
data class PlanDraft(
    val typedMinor: Long,
    val assignedMinor: Long,
    val carryOverMinor: Long = 0,
    val incomeMinor: Long = 0,
) {
    /** The pot the typed figure would make: a floor under what arrived. */
    val availableMinor: Long get() = maxOf(typedMinor, incomeMinor) + carryOverMinor

    /** What would still be unassigned, which is what the card will say. */
    val leftToAssignMinor: Long get() = availableMinor - assignedMinor

    /** Typing less than has already been handed out: a real mistake, said in red. */
    val short: Boolean get() = leftToAssignMinor < 0

    /**
     * True when the figure being typed is below what the month already
     * received, so pressing keys moves nothing at all.
     *
     * The max rule's one bad moment, and it was reported from the phone within
     * the hour: "if I change the forecast I don't see it changing anything".
     * It is correct — a forecast cannot make the month poorer than the account
     * already is — but a control that silently ignores what is typed into it
     * owes the reader a sentence. The sheet prints one, in this state only; the
     * planning-ahead case it exists for has no income yet, and there every
     * keypress moves the figures.
     */
    val belowIncome: Boolean get() = typedMinor < incomeMinor
}

// A tappable "Wpłynęło w tym miesiącu: 24 450,00 · dotknij, aby wstawić" lived
// here for one release, with a line beneath it explaining that the carry-over
// was added separately. Both are gone: the max rule means income does not have
// to be typed to count, so neither the offer nor the warning about taking it has
// anything left to do. In git if the history is ever wanted.


// How far the limits themselves were passed, added up, was a line on this card
// in 0.29.0 and is gone in 0.29.1. The owner's reason is the right one: some
// categories run over and others run under, the two roughly cancel, and the
// card was carrying more lines than it had questions. Each row still says it
// for itself. In git if it is ever wanted back.

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
                PlanState(
                    period = p,
                    plannedMinor = planRow?.plannedMinor ?: 0,
                    hasPlan = planRow != null,
                    assignedMinor = usage.sumOf { it.limitMinor },
                    spentMinor = totals.expenseMinor,
                    incomeMinor = totals.incomeMinor,
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

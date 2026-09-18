package com.monyx.ui.overview

import com.monyx.data.BudgetLimit
import com.monyx.data.Dates
import com.monyx.data.MonthlyCategorySpend
import java.time.LocalDate

/** How many months the breakdown card's back shows. */
const val HISTORY_MONTHS = 12

/** One month of the history: what each category cost in it. */
data class HistoryMonth(
    val period: String,
    val byCategory: Map<String, Long>,
    /** Still being lived, so its bar is not comparable with the others. */
    val partial: Boolean,
    /**
     * The limit in effect this month, per category — already carried forward.
     * Empty for a month before the household set its first budget, and for a
     * month whose every limit has since been cleared.
     */
    val budgetByCategory: Map<String, Long> = emptyMap(),
) {
    /** The bar's height, once the hidden categories are taken out of it. */
    fun totalMinor(visible: Set<String>): Long =
        byCategory.entries.filter { it.key in visible }.sumOf { it.value }

    /**
     * Where the limit line sits over this bar, or null for no line at all.
     *
     * Hiding a category takes its limit out of the line as well as its colour
     * out of the bar, because the line's whole job is to be comparable with the
     * bar under it — a full household limit drawn over three categories' worth
     * of spending would say "well under budget" every month, which is a
     * reassuring way to be wrong.
     *
     * Null rather than zero when everything budgeted is hidden: a line along
     * the floor is a claim that nothing may be spent, and there is a real
     * difference between a limit of nothing and no limit.
     */
    fun budgetMinor(hidden: Set<String>): Long? {
        val kept = budgetByCategory.filterKeys { it !in hidden }
        return if (kept.isEmpty()) null else kept.values.sum()
    }
}

/**
 * One category across the window: what it cost per month on average, and its
 * colour, so the legend and the stack agree without looking anything up twice.
 */
data class HistoryCategory(
    val id: String,
    val name: String,
    val color: String?,
    val totalMinor: Long,
    /**
     * Divided by the months the ledger actually covers — never by twelve.
     *
     * Two months are left out of that count, for two different reasons. The
     * month being lived is a partial figure — on the 3rd it is two days of
     * spending — and dividing by a month that is one tenth over drags every
     * average down a little further every time the screen is opened. And a
     * month from before the household started keeping the ledger is not a month
     * it spent nothing in, it is a month there is no answer for: a family three
     * months in would otherwise see every average quartered, which is the kind
     * of wrong that looks plausible.
     *
     * A month that HAS other spending in it but none in this category counts
     * for the full month, because that is a real zero — the category genuinely
     * cost nothing that month.
     */
    val averageMinor: Long,
)

/**
 * The twelve-month breakdown: the bars, and the categories that make them up.
 *
 * Categories are ordered by average rather than by what they cost in the
 * selected month, because that is the order the legend prints and the order the
 * segments stack in — and a stack whose order changed from month to month would
 * be unreadable as a shape.
 */
data class CategoryHistory(
    val months: List<HistoryMonth>,
    val categories: List<HistoryCategory>,
    val selectedPeriod: String,
) {
    val isEmpty: Boolean get() = categories.isEmpty()

    /** Where the highlight goes, or null when the selection is off the window. */
    val selectedIndex: Int? get() = months.indexOfFirst { it.period == selectedPeriod }.takeIf { it >= 0 }
}

/**
 * The twelve periods the chart covers for [period].
 *
 * It ends at the current month, NOT at the month being shown, so that tapping a
 * bar to look at March does not slide the window three months back and leave
 * the bar you tapped somewhere else on the screen. The chart is a fixed window
 * you move the selection around inside.
 *
 * The exception is a selection outside that window — the month switcher reaches
 * anywhere, and a highlight has to be on the chart to mean anything. A month
 * beyond the current one (a standing order dated forward) extends the window
 * on; a month further back than twelve ends it there instead.
 */
fun historyWindow(period: String, today: LocalDate = Dates.today()): List<String> {
    val current = Dates.periodOf(today)
    val anchor = if (period > current) period else current
    val start = Dates.shiftPeriod(anchor, -(HISTORY_MONTHS - 1).toLong())
    val end = if (period < start) period else anchor
    return (0 until HISTORY_MONTHS).map { Dates.shiftPeriod(end, -(HISTORY_MONTHS - 1 - it).toLong()) }
}

/**
 * Fold the rows the database returned into the bars and the legend.
 *
 * Every period in [periods] gets a month whether or not anything was spent in
 * it: a month nothing happened in is a fact about the household, and dropping
 * it would leave the bars evenly spaced over an uneven stretch of time.
 *
 * A category is kept if it was spent on anywhere in the window, even when it is
 * zero in the selected month — the whole point of the view is the category that
 * used to cost something and stopped.
 */
fun categoryHistory(
    rows: List<MonthlyCategorySpend>,
    periods: List<String>,
    selectedPeriod: String,
    today: LocalDate = Dates.today(),
    budgets: List<BudgetLimit> = emptyList(),
): CategoryHistory {
    val currentPeriod = Dates.periodOf(today)
    val inWindow = periods.toSet()
    val kept = rows.filter { it.period in inWindow }
    val limits = limitsPerMonth(budgets, periods)

    val months = periods.map { p ->
        HistoryMonth(
            period = p,
            byCategory = kept.filter { it.period == p }.associate { it.categoryId to it.spentMinor },
            // Anything at or beyond the current month: the current one because
            // it is half lived, a future one because it holds only whatever has
            // been dated forward into it.
            partial = p >= currentPeriod,
            budgetByCategory = limits[p].orEmpty(),
        )
    }

    // The months anything at all was spent in — the ledger's own extent, which
    // is not the same as the window's.
    val withData = kept.map { it.period }.toSet()
    val completed = periods.filter { it < currentPeriod && it in withData }

    // A household whose only data is the month it is still living has no
    // completed month to average over. Rather than a column of zeros, the
    // months it does have answer for themselves — one month in, "the average"
    // and "this month" are the same claim anyway.
    val counted = completed.ifEmpty { periods.filter { it in withData } }.toSet()
    val divisor = counted.size.coerceAtLeast(1)

    val categories = kept
        .groupBy { it.categoryId }
        .map { (id, spends) ->
            val countedTotal = spends.filter { it.period in counted }.sumOf { it.spentMinor }
            HistoryCategory(
                id = id,
                name = spends.first().name,
                color = spends.first().color,
                totalMinor = spends.sumOf { it.spentMinor },
                averageMinor = countedTotal / divisor,
            )
        }
        .sortedByDescending { it.averageMinor }

    return CategoryHistory(months = months, categories = categories, selectedPeriod = selectedPeriod)
}

/**
 * The legend's order: what is on the chart first, dearest first, then what has
 * been hidden from it, also dearest first.
 *
 * The rows used to keep their place when hidden, on the grounds that a list
 * which reorders under the finger is a list where the next tap lands on
 * something nobody meant to touch. Sinking them wins anyway, because hiding is
 * now remembered across sessions: a household that permanently hides two
 * categories would otherwise open the screen forever afterwards onto two dimmed
 * rows sitting in the middle of the list it actually reads.
 *
 * Hidden rows are sunk, never dropped. They are the only way back — a category
 * with no row has no eye to turn on again.
 */
fun legendOrder(
    categories: List<HistoryCategory>,
    hidden: Set<String>,
    /**
     * What the rows are ranked by — the same figure they print. Ordering by one
     * number while showing another puts 400 above 900 with nothing on screen to
     * explain it, which reads as a broken sort rather than a different question.
     */
    amountOf: (HistoryCategory) -> Long = { it.averageMinor },
): List<HistoryCategory> =
    categories.sortedWith(
        compareBy<HistoryCategory> { it.id in hidden }.thenByDescending { amountOf(it) },
    )

/** Which figure the twelve-month legend prints beside each category. */
enum class LegendAmount { Average, SelectedMonth }

/**
 * The number each legend row shows, per category.
 *
 * Two questions the same list can answer: what a category usually costs, and
 * what it cost in the month the chart is pointing at. The average is the older
 * behaviour and stays the default — it is the one that makes sense of a
 * twelve-month chart at a glance — but once you have tapped a bar to ask about
 * March, a column of yearly averages is answering something else.
 *
 * A category with no spending in the chosen month is a real zero rather than a
 * missing row: it was on the chart all year and the answer for that month is
 * "nothing", which is worth seeing.
 */
fun legendAmounts(history: CategoryHistory, mode: LegendAmount): Map<String, Long> =
    when (mode) {
        LegendAmount.Average -> history.categories.associate { it.id to it.averageMinor }
        LegendAmount.SelectedMonth -> {
            val month = history.months.firstOrNull { it.period == history.selectedPeriod }
            history.categories.associate { it.id to (month?.byCategory?.get(it.id) ?: 0L) }
        }
    }

/**
 * The limit in effect in each of [periods], per category, from the raw budget
 * rows — the same carry-forward rule the budget screen resolves in SQL for one
 * month at a time.
 *
 * Three things this has to get right, all of which look identical on a chart
 * once they are wrong:
 *
 * A limit of zero is a limit, not a missing one: "spend nothing on this" is a
 * budget somebody set on purpose, and the only way to say "no limit here" is a
 * tombstone. Which is why the test below is `>= 0` and the deleted flag does
 * the other job — reading zero as absent would quietly drop the strictest
 * budget a household can set.
 *
 * A limit holds from its month **onward** until another row supersedes it, so
 * most months on the chart have no row of their own and inherit one. Clearing a
 * limit writes a tombstone, and inheritance stops there rather than skipping
 * back to the row before it. And two rows can exist for one category and month
 * — the server resolves an upsert onto a row that keeps its own id, so the next
 * pull brings the same budget back under a second id — which is why the newest
 * seq wins here, exactly as it does in budgetUsage: summing both would quietly
 * double a household's limit.
 *
 * Limits are then rolled up to the categories the bars are stacked from. A limit
 * on a parent already covers its subcategories, so when Home has one, a limit on
 * Home > Repairs is a sub-limit inside it and adding the two would count the
 * same money twice; with no limit on Home, its children's limits are the only
 * answer there is and they add up.
 */
internal fun limitsPerMonth(
    rows: List<BudgetLimit>,
    periods: List<String>,
): Map<String, Map<String, Long>> {
    if (rows.isEmpty() || periods.isEmpty()) return emptyMap()
    val byCategory = rows
        .groupBy { it.categoryId }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.period }, { it.seq }, { it.id })) }

    return periods.associateWith { period ->
        val inEffect = byCategory.values.mapNotNull { history ->
            history.lastOrNull { it.period <= period }
                ?.takeIf { it.deleted == 0 && it.limitMinor >= 0L }
        }
        val rolledUpAtParent = inEffect.filter { it.categoryId == it.rollupId }.map { it.rollupId }.toSet()
        inEffect
            .filterNot { it.categoryId != it.rollupId && it.rollupId in rolledUpAtParent }
            .groupBy { it.rollupId }
            .mapValues { (_, limits) -> limits.sumOf { it.limitMinor } }
    }
}

/**
 * The y axis: zero and three gridlines up to a round number above the tallest
 * bar.
 *
 * The step is rounded UP to a round multiple of a power of ten, so the labels
 * are numbers somebody can hold in their head and the tallest bar never touches
 * the ceiling. Divided into three rather than labelled at top and bottom only:
 * the whole question here is whether a month is bigger than its neighbours, and
 * that is read against the lines, not against the numbers.
 *
 * The ladder's spacing is the whole trick, and it was wrong. It ran
 * 1, 1.5, 2, 2.5, 3, 4, 5 and then jumped to 10, so every total from five to ten
 * times a power of ten produced the SAME axis: with three divisions, a chart
 * topping out at 15 000 and one topping out at 30 000 both drew a ceiling of
 * 30 000. Hiding a category halved the bars and moved nothing — the axis looked
 * frozen, because within that gap it was.
 *
 * 6, 7.5 and 8 fill it. They are less round than what surrounds them, which is
 * the price of an axis that answers when the bars change.
 */
internal fun axisTicks(maxMinor: Long, divisions: Int = 3): List<Long> {
    if (maxMinor <= 0L) return listOf(0L)
    val raw = maxMinor.toDouble() / divisions
    var magnitude = 1L
    while (magnitude * 10 <= raw) magnitude *= 10
    val step = listOf(10, 15, 20, 25, 30, 40, 50, 60, 75, 80, 100)
        .map { it * magnitude / 10 }
        .first { it >= raw }
    return (0..divisions).map { it * step }
}

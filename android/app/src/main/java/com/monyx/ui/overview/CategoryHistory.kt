package com.monyx.ui.overview

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
) {
    /** The bar's height, once the hidden categories are taken out of it. */
    fun totalMinor(visible: Set<String>): Long =
        byCategory.entries.filter { it.key in visible }.sumOf { it.value }
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
     * Divided by the COMPLETED months in the window, never by twelve.
     *
     * The month being lived is a partial figure — on the 3rd it is two days of
     * spending — and dividing by a month that is one tenth over drags every
     * average down a little further every time you open the screen. The bar for
     * it is still drawn; it just does not get a vote in the average.
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
): CategoryHistory {
    val currentPeriod = Dates.periodOf(today)
    val inWindow = periods.toSet()
    val kept = rows.filter { it.period in inWindow }

    val months = periods.map { p ->
        HistoryMonth(
            period = p,
            byCategory = kept.filter { it.period == p }.associate { it.categoryId to it.spentMinor },
            // Anything at or beyond the current month: the current one because
            // it is half lived, a future one because it holds only whatever has
            // been dated forward into it.
            partial = p >= currentPeriod,
        )
    }

    // Completed months only, and at least one — in a window whose every month is
    // the current one or later there is nothing to average over, and the total
    // is the honest answer rather than a division by zero.
    val completed = periods.count { it < currentPeriod }.coerceAtLeast(1)

    val categories = kept
        .groupBy { it.categoryId }
        .map { (id, spends) ->
            val completedTotal = spends.filter { it.period < currentPeriod }.sumOf { it.spentMinor }
            HistoryCategory(
                id = id,
                name = spends.first().name,
                color = spends.first().color,
                totalMinor = spends.sumOf { it.spentMinor },
                averageMinor = completedTotal / completed,
            )
        }
        .sortedByDescending { it.averageMinor }

    return CategoryHistory(months = months, categories = categories, selectedPeriod = selectedPeriod)
}

/**
 * The y axis: zero and three gridlines up to a round number above the tallest
 * bar.
 *
 * The step is rounded UP to 1, 2, 5 or 10 times a power of ten, so the labels
 * are numbers somebody can hold in their head and the tallest bar never touches
 * the ceiling. Divided into three rather than labelled at top and bottom only:
 * the whole question here is whether a month is bigger than its neighbours, and
 * that is read against the lines, not against the numbers.
 */
internal fun axisTicks(maxMinor: Long, divisions: Int = 3): List<Long> {
    if (maxMinor <= 0L) return listOf(0L)
    val raw = maxMinor.toDouble() / divisions
    var magnitude = 1L
    while (magnitude * 10 <= raw) magnitude *= 10
    // Halves and quarters as well as the round decade: a year topping out at
    // 4 500 gets 0/1 500/3 000/4 500 rather than an axis to 6 000 with a third
    // of the chart left empty above the tallest bar.
    val step = listOf(10, 15, 20, 25, 30, 40, 50, 100)
        .map { it * magnitude / 10 }
        .first { it >= raw }
    return (0..divisions).map { it * step }
}

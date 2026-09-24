package com.monyx.ui.overview

import com.monyx.data.DailyTotals
import com.monyx.data.Dates
import java.time.LocalDate

/**
 * How many days the breakdown card's third face covers.
 *
 * Thirty-one rather than thirty, so the window always holds a full month of
 * living whatever month it ends in: the question it answers is "which days cost
 * us the most", and a thirty-day window ending on the 31st would leave one day
 * of that month off the chart it is named after.
 *
 * It is NOT [TREND_DAYS]. The balance card's window has to reach the 1st of the
 * selected month to restart its month-to-date figures on it; this one has no
 * such obligation and is simply a fixed span, which is what lets its title state
 * a number.
 */
const val DAILY_DAYS = 31

/** One day of the window, and what was spent on it. */
data class DaySpend(
    val date: LocalDate,
    val expenseMinor: Long,
)

/**
 * A gap-filled run of days and the spending on each.
 *
 * Expenses only. Income is a different question and it would wreck this one: a
 * salary is ten times the size of the biggest shopping day, so a chart scaled to
 * hold it would flatten every bar the household came here to compare.
 */
data class DailySpend(
    val days: List<DaySpend>,
) {
    val isEmpty: Boolean = days.all { it.expenseMinor == 0L }

    val from: LocalDate get() = days.first().date
    val to: LocalDate get() = days.last().date

    val totalMinor: Long = days.sumOf { it.expenseMinor }
    val maxMinor: Long = days.maxOfOrNull { it.expenseMinor } ?: 0L

    /**
     * The costliest day, which the chart prints the figure above.
     *
     * The LAST of them when several tie, because the window runs left to right
     * in time and a tie is broken in favour of the more recent — and null when
     * nothing was spent at all, where a "biggest day" would be a label pointing
     * at an empty chart.
     */
    val busiestIndex: Int? = days.indexOfLast { it.expenseMinor == maxMinor }
        .takeIf { it >= 0 && maxMinor > 0L }
}

/**
 * The [DAILY_DAYS] days ending on the last day [period] can honestly show.
 *
 * Anchored to the selected month rather than to today, so stepping the switcher
 * back steps this chart back with it — the card sits under that switcher and
 * every other face on it moves.
 */
fun dailyWindow(period: String, today: LocalDate = Dates.today()): ClosedRange<LocalDate> {
    val end = Dates.windowEnd(period, today)
    return end.minusDays((DAILY_DAYS - 1).toLong())..end
}

/**
 * One bar per day of [from]..[to], whether or not money moved on it.
 *
 * Quiet days are drawn as gaps rather than dropped, for the reason the trend
 * gives at length: bars spaced by the days that happen to have rows would put
 * Tuesday and Friday next to each other and the week would stop being readable.
 */
fun dailySpend(rows: List<DailyTotals>, from: LocalDate, to: LocalDate): DailySpend {
    val byDay = rows.associateBy { it.day }
    val last = if (to.isBefore(from)) from else to
    val days = ArrayList<DaySpend>()
    var day = from
    while (!day.isAfter(last)) {
        days += DaySpend(day, byDay[day.toString()]?.expenseMinor ?: 0L)
        day = day.plusDays(1)
    }
    return DailySpend(days)
}

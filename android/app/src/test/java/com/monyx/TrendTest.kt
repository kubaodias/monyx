package com.monyx

import com.monyx.data.DailyTotals
import com.monyx.data.Dates
import com.monyx.ui.overview.TREND_DAYS
import com.monyx.ui.overview.dayIndexAt
import com.monyx.ui.overview.trendSeries
import com.monyx.ui.overview.trendWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The arithmetic behind the balance card's back face.
 *
 * A chart is the worst thing to verify by looking at it: a missing day and a
 * quiet day are the same picture, and a running total that drifts by one day is
 * invisible until someone reconciles it against a bank statement. Everything
 * that decides what the bars mean is decided here instead.
 */
class TrendTest {

    private val today = LocalDate.of(2026, 9, 1)

    private fun day(date: String, income: Long = 0, expense: Long = 0) =
        DailyTotals(date, income, expense)

    // ------------------------------------------------------------- the window

    @Test
    fun `the current month's window ends today, not at the end of the month`() {
        val window = trendWindow("2026-09", today)
        assertEquals(today, window.endInclusive)
    }

    @Test
    fun `a month that is over ends on its own last day`() {
        val window = trendWindow("2026-07", today)
        assertEquals(LocalDate.of(2026, 7, 31), window.endInclusive)
        assertEquals(LocalDate.of(2026, 7, 2), window.start)
    }

    /**
     * The switcher can be pushed into the future. Falling back to "the last 30
     * days" there would draw August under an October heading — an empty chart
     * labelled October is the honest answer.
     */
    @Test
    fun `a month in the future stays in its own month`() {
        val window = trendWindow("2026-10", today)
        assertEquals(LocalDate.of(2026, 10, 31), window.endInclusive)
        assertEquals(LocalDate.of(2026, 10, 2), window.start)
    }

    @Test
    fun `every window is exactly thirty days long, February included`() {
        listOf("2026-02", "2026-07", "2026-09", "2026-12").forEach { period ->
            val window = trendWindow(period, today)
            val series = trendSeries(emptyList(), window.start, window.endInclusive)
            assertEquals(period, TREND_DAYS, series.points.size)
        }
    }

    // ------------------------------------------------------------ gap filling

    @Test
    fun `a quiet day still gets a column`() {
        val series = trendSeries(
            listOf(day("2026-08-03", expense = 1_000), day("2026-08-06", expense = 2_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 6),
        )
        assertEquals(4, series.points.size)
        assertEquals(listOf(1_000L, 0L, 0L, 2_000L), series.points.map { it.expenseMinor })
    }

    @Test
    fun `the days come back in date order whatever order the rows arrived in`() {
        val series = trendSeries(
            listOf(day("2026-08-05", income = 500), day("2026-08-03", income = 100)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 5),
        )
        assertEquals(
            listOf("2026-08-03", "2026-08-04", "2026-08-05"),
            series.points.map { it.date.toString() },
        )
        assertEquals(listOf(100L, 0L, 500L), series.points.map { it.incomeMinor })
    }

    @Test
    fun `a row outside the window is not plotted inside it`() {
        val series = trendSeries(
            listOf(day("2026-07-31", expense = 9_999), day("2026-08-03", expense = 1_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(1_000L, series.expenseMinor)
    }

    /** Callers pass a window, not a list; being handed a backwards one must not
     *  produce a series with no first day for the axis to label. */
    @Test
    fun `a backwards window still has an end to label`() {
        val series = trendSeries(emptyList(), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1))
        assertEquals(1, series.points.size)
        assertEquals(LocalDate.of(2026, 8, 10), series.from)
        assertEquals(series.from, series.to)
    }

    // -------------------------------------------------------- running balance

    @Test
    fun `the running total is the balance as it stood at the end of each day`() {
        val series = trendSeries(
            listOf(
                day("2026-08-03", income = 10_000),
                day("2026-08-05", expense = 4_000),
                day("2026-08-06", expense = 1_000),
            ),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 6),
        )
        assertEquals(listOf(10_000L, 10_000L, 6_000L, 5_000L), series.runningMinor)
    }

    @Test
    fun `the last running figure is the window's net, which is what the card shows`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 3_000), day("2026-08-04", expense = 8_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(-5_000L, series.netMinor)
        assertEquals(series.netMinor, series.runningMinor.last())
        assertEquals(series.netMinor, series.runningAt(1))
    }

    @Test
    fun `pointing past the end of the series falls back to the window's net`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 3_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(series.netMinor, series.runningAt(7))
    }

    // ---------------------------------------------------------------- scaling

    /**
     * Both halves of the chart share one scale. If income and expense were
     * scaled separately, a 20 zł day would draw the same bar as a 4 000 zł one
     * and the two sides could not be compared by eye at all.
     */
    @Test
    fun `the tallest single amount on either side sets the scale`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 400_000), day("2026-08-04", expense = 12_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(400_000L, series.peakMinor)
    }

    /**
     * The scale exists so that one payday cannot flatten a month of shopping.
     * Six times a typical day, and the salary runs off the top rather than
     * squashing everything under it.
     */
    @Test
    fun `one payday does not flatten thirty days of groceries`() {
        val rows = (3..12).map { day("2026-08-%02d".format(it), expense = 5_000) } +
            day("2026-08-10", income = 850_000)
        val series = trendSeries(rows, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 12))
        assertEquals(850_000L, series.peakMinor)
        assertEquals(30_000L, series.scaleMinor)
        assertTrue(series.scaleMinor < series.peakMinor)
    }

    /** Nothing to protect against: an even month scales to its own tallest bar
     *  and no day is pushed off the chart. */
    @Test
    fun `an evenly spent month is not clipped at all`() {
        val rows = listOf(
            day("2026-08-03", expense = 10_000),
            day("2026-08-04", expense = 12_000),
            day("2026-08-05", expense = 14_000),
        )
        val series = trendSeries(rows, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5))
        assertEquals(series.peakMinor, series.scaleMinor)
    }

    @Test
    fun `a window with nothing in it has no scale to divide by`() {
        val series = trendSeries(emptyList(), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10))
        assertEquals(0L, series.scaleMinor)
    }

    /** The two halves share one number, so a payday and a weekly shop can never
     *  be drawn the same height. */
    @Test
    fun `income and expense are measured against the same scale`() {
        val rows = listOf(
            day("2026-08-03", income = 100_000),
            day("2026-08-04", expense = 100_000),
            day("2026-08-05", income = 4_000, expense = 6_000),
        )
        val series = trendSeries(rows, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5))
        // Pooled and sorted: 4 000, 6 000, 100 000, 100 000 -> median 100 000.
        assertEquals(100_000L, series.scaleMinor)
    }

    @Test
    fun `a window nobody spent in is empty, and a window with one coin is not`() {
        val from = LocalDate.of(2026, 8, 3)
        val to = LocalDate.of(2026, 8, 10)
        assertTrue(trendSeries(emptyList(), from, to).isEmpty)
        assertFalse(trendSeries(listOf(day("2026-08-04", expense = 1)), from, to).isEmpty)
    }

    /**
     * A day that took 4 000 in and paid 4 000 out nets to nothing. It is not a
     * quiet day, and the chart must not draw it as one — which is the whole
     * reason the bars diverge instead of being a single net column.
     */
    @Test
    fun `a day that balances out is still a day something happened`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 400_000, expense = 400_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 3),
        )
        assertEquals(0L, series.netMinor)
        assertFalse(series.isEmpty)
        assertFalse(series.points.first().isQuiet)
    }

    // ------------------------------------------------------------ hit testing

    @Test
    fun `each column owns its own slice of the width`() {
        assertEquals(0, dayIndexAt(1f, 300f, 30))
        assertEquals(1, dayIndexAt(11f, 300f, 30))
        assertEquals(29, dayIndexAt(299f, 300f, 30))
    }

    @Test
    fun `a boundary belongs to the column that starts there`() {
        assertEquals(1, dayIndexAt(10f, 300f, 30))
        assertEquals(2, dayIndexAt(20f, 300f, 30))
    }

    /** A finger dragged off the edge means "the end", not "no day at all". */
    @Test
    fun `a scrub past either edge stays on the first and last day`() {
        assertEquals(0, dayIndexAt(-40f, 300f, 30))
        assertEquals(29, dayIndexAt(340f, 300f, 30))
    }

    @Test
    fun `a chart with no width and no days is not touchable`() {
        assertNull(dayIndexAt(10f, 0f, 30))
        assertNull(dayIndexAt(10f, 300f, 0))
    }

    // ------------------------------------------------------------------ dates

    @Test
    fun `the window end never runs past today in the month that contains today`() {
        assertEquals(today, Dates.windowEnd("2026-09", today))
        assertEquals(LocalDate.of(2026, 2, 28), Dates.lastDayOf("2026-02"))
    }
}

package com.monyx

import com.monyx.data.DailyTotals
import com.monyx.data.Dates
import com.monyx.ui.overview.TREND_DAYS
import com.monyx.ui.overview.dayIndexAt
import com.monyx.ui.overview.trendSeries
import com.monyx.ui.overview.trendWindow
import com.monyx.ui.overview.yFraction
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

    private fun ClosedRange<LocalDate>.dayCount(): Int =
        (java.time.temporal.ChronoUnit.DAYS.between(start, endInclusive) + 1).toInt()

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
        // Not the 2nd. July has 31 days, so a plain thirty would start on the
        // 2nd and the run would never meet the 1st it has to restart on.
        assertEquals(LocalDate.of(2026, 7, 1), window.start)
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
        assertEquals(LocalDate.of(2026, 10, 1), window.start)
    }

    /**
     * Thirty is the floor, not the length. A 31-day month viewed on its last day
     * needs one more, or the window starts on the 2nd and never sees the 1st —
     * and the card's two faces would then disagree by whatever was spent on it.
     */
    @Test
    fun `a window is at least thirty days and always reaches the first of its month`() {
        listOf("2026-02", "2026-07", "2026-09", "2026-12").forEach { period ->
            val window = trendWindow(period, today)
            val series = trendSeries(emptyList(), window.start, window.endInclusive)
            assertTrue(period, series.points.size >= TREND_DAYS)
            assertTrue(period, series.points.size <= TREND_DAYS + 1)
            assertFalse(period, window.start.isAfter(Dates.firstDayOf(period)))
        }
    }

    @Test
    fun `thirty is enough for a short month and one too few for a long one`() {
        // February on the 28th: 30 January is already before the 1st.
        assertEquals(30, trendWindow("2026-02", today).let { it.start..it.endInclusive }.dayCount())
        // December on the 31st: thirty would start on the 2nd, so it is 31.
        assertEquals(31, trendWindow("2026-12", today).let { it.start..it.endInclusive }.dayCount())
        // September while today is the 1st: nothing to stretch for.
        assertEquals(30, trendWindow("2026-09", today).let { it.start..it.endInclusive }.dayCount())
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
        assertEquals(listOf(1_000L, 0L), series.points.map { it.expenseMinor })
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
    fun `the last running figure is where the line ends, which is what the card shows`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 3_000), day("2026-08-04", expense = 8_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(-5_000L, series.monthToDateMinor)
        assertEquals(series.monthToDateMinor, series.runningMinor.last())
        assertEquals(series.monthToDateMinor, series.runningAt(1))
    }

    @Test
    fun `pointing past the end of the series falls back to where the line ends`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 3_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(series.monthToDateMinor, series.runningAt(7))
    }

    // --------------------------------------------------- the turn of the month

    /**
     * The reason any of this exists. The card's front prints the SELECTED
     * month's balance; before the reset the back headlined the whole window's
     * net, so a September that had barely started read 0,00 on one face and
     * last month's payday on the other.
     */
    @Test
    fun `the run restarts on the first, so the line ends on the month's own balance`() {
        val series = trendSeries(
            listOf(
                day("2026-08-30", income = 800_000),
                day("2026-08-31", expense = 50_000),
                day("2026-09-01", expense = 12_000),
                day("2026-09-02", expense = 3_000),
            ),
            LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 9, 2),
        )
        assertEquals(
            listOf(800_000L, 750_000L, -12_000L, -15_000L),
            series.runningMinor,
        )
        // September's balance, not the four days' net of 735 000.
        assertEquals(-15_000L, series.monthToDateMinor)
    }

    @Test
    fun `the boundary is marked where the run restarts`() {
        val across = trendSeries(
            emptyList(),
            LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 9, 2),
        )
        assertEquals(2, across.monthStartIndex)
    }

    /** A window that never crosses a 1st has nothing to mark, and a window that
     *  BEGINS on one has already restarted before its first point. */
    @Test
    fun `a window inside one month has no boundary to draw`() {
        assertNull(
            trendSeries(emptyList(), LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 20))
                .monthStartIndex,
        )
        assertNull(
            trendSeries(emptyList(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20))
                .monthStartIndex,
        )
    }

    /** The stretch and the reset together: on the 31st the 1st is still in the
     *  window, so the month's opening day is counted. */
    @Test
    fun `the last day of a long month still counts the first`() {
        val period = "2026-07"
        val window = trendWindow(period, today)
        val series = trendSeries(
            listOf(day("2026-07-01", expense = 20_000), day("2026-07-15", expense = 5_000)),
            window.start,
            window.endInclusive,
        )
        assertEquals(-25_000L, series.monthToDateMinor)
    }

    // ----------------------------------------------------- the line's range

    /**
     * Break-even is the only threshold on this chart that means anything, so it
     * is forced inside the range from both directions — a month that never went
     * negative still shows the line it stayed above.
     */
    @Test
    fun `zero is always inside the range, above water and below it`() {
        val up = trendSeries(
            listOf(day("2026-08-03", income = 10_000), day("2026-08-04", income = 5_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(0L, up.lowRunningMinor)
        assertEquals(15_000L, up.highRunningMinor)

        val down = trendSeries(
            listOf(day("2026-08-03", expense = 10_000), day("2026-08-04", expense = 5_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
        )
        assertEquals(-15_000L, down.lowRunningMinor)
        assertEquals(0L, down.highRunningMinor)
    }

    /** A month that dipped under and climbed back out keeps both extremes. */
    @Test
    fun `a month that goes under and recovers keeps both ends of its swing`() {
        val series = trendSeries(
            listOf(
                day("2026-08-03", expense = 40_000),
                day("2026-08-05", income = 100_000),
            ),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 5),
        )
        assertEquals(listOf(-40_000L, -40_000L, 60_000L), series.runningMinor)
        assertEquals(-40_000L, series.lowRunningMinor)
        assertEquals(60_000L, series.highRunningMinor)
    }

    @Test
    fun `the bottom of the range is the bottom of the chart and the top is the top`() {
        assertEquals(0f, yFraction(-40_000, -40_000, 60_000), 0.0001f)
        assertEquals(1f, yFraction(60_000, -40_000, 60_000), 0.0001f)
        assertEquals(0.4f, yFraction(0, -40_000, 60_000), 0.0001f)
    }

    /** A flat month has no range to divide by. Drawing it along an edge would
     *  read as the best or the worst the month ever got; the middle claims
     *  neither. */
    @Test
    fun `a flat month is drawn down the middle rather than pinned to an edge`() {
        assertEquals(0.5f, yFraction(0, 0, 0), 0.0001f)
        assertEquals(0.5f, yFraction(5_000, 5_000, 5_000), 0.0001f)
    }

    @Test
    fun `a value outside the range cannot be drawn off the chart`() {
        assertEquals(0f, yFraction(-99_000, -40_000, 60_000), 0.0001f)
        assertEquals(1f, yFraction(99_000, -40_000, 60_000), 0.0001f)
    }

    @Test
    fun `a window nobody spent in is empty, and a window with one coin is not`() {
        val from = LocalDate.of(2026, 8, 3)
        val to = LocalDate.of(2026, 8, 10)
        assertTrue(trendSeries(emptyList(), from, to).isEmpty)
        assertFalse(trendSeries(listOf(day("2026-08-04", expense = 1)), from, to).isEmpty)
    }

    /**
     * A day that took 4 000 in and paid 4 000 out leaves the line exactly where
     * it was — correctly, because the balance did not move. It is still not a
     * day nothing happened, and the readout under the chart has to say so.
     */
    @Test
    fun `a day that balances out moves the line nowhere but is not a quiet window`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 400_000, expense = 400_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 3),
        )
        assertEquals(0L, series.monthToDateMinor)
        assertEquals(listOf(0L), series.runningMinor)
        assertFalse(series.isEmpty)
        assertEquals(400_000L, series.points.first().incomeMinor)
        assertEquals(400_000L, series.points.first().expenseMinor)
    }

    // ------------------------------------------------------------ hit testing

    /** Thirty vertices, the first on the left edge and the last on the right,
     *  so the spacing is width / 29 and not width / 30. */
    @Test
    fun `a touch lands on the nearest day, not the one it is standing in`() {
        assertEquals(0, dayIndexAt(0f, 290f, 30))
        assertEquals(0, dayIndexAt(4f, 290f, 30))
        assertEquals(1, dayIndexAt(6f, 290f, 30))
        assertEquals(29, dayIndexAt(290f, 290f, 30))
    }

    @Test
    fun `halfway between two days rounds to the later one`() {
        assertEquals(1, dayIndexAt(5f, 290f, 30))
        assertEquals(2, dayIndexAt(15f, 290f, 30))
    }

    /** A single-day window has no gap to divide by. */
    @Test
    fun `a one day window answers with its one day`() {
        assertEquals(0, dayIndexAt(0f, 290f, 1))
        assertEquals(0, dayIndexAt(280f, 290f, 1))
    }

    /** A finger dragged off the edge means "the end", not "no day at all". */
    @Test
    fun `a scrub past either edge stays on the first and last day`() {
        assertEquals(0, dayIndexAt(-40f, 290f, 30))
        assertEquals(29, dayIndexAt(340f, 290f, 30))
    }

    @Test
    fun `a chart with no width and no days is not touchable`() {
        assertNull(dayIndexAt(10f, 0f, 30))
        assertNull(dayIndexAt(10f, 290f, 0))
    }

    // ------------------------------------------------------------------ dates

    @Test
    fun `the window end never runs past today in the month that contains today`() {
        assertEquals(today, Dates.windowEnd("2026-09", today))
        assertEquals(LocalDate.of(2026, 2, 28), Dates.lastDayOf("2026-02"))
    }
}

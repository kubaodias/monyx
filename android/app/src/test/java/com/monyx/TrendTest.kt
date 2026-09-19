package com.monyx

import com.monyx.data.DailyDelta
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
            endBalanceMinor = 5_000,
        )
        assertEquals(listOf(10_000L, 10_000L, 6_000L, 5_000L), series.runningMinor)
    }

    /**
     * The whole point of anchoring. The card prints a balance above the chart
     * and the chart has to end on it — not approximately, not "the same if the
     * window happens to line up", but by construction, because the line is
     * counted backwards from that very figure.
     *
     * This is the bug the household actually hit: the headline read 7 975,30
     * and the last day of the line, tapped, answered 2 820,63 — September's net,
     * under a heading that said "Stan kont".
     */
    @Test
    fun `the line ends on the balance it was given, whatever moved during the window`() {
        val series = trendSeries(
            listOf(day("2026-09-10", income = 194_500), day("2026-09-15", expense = 40_000)),
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 19),
            endBalanceMinor = 797_530,
        )
        assertEquals(797_530L, series.closingMinor)
        assertEquals(797_530L, series.runningMinor.last())
        // And before the salary landed, it was 154 500 lower.
        assertEquals(797_530L - 194_500L + 40_000L, series.runningMinor.first())
    }

    /**
     * A transfer is neither earned nor spent, and it still empties an account.
     * With one account selected the daily net says nothing happened on the day
     * 2 000 zł left it for the savings account; the delta says it did.
     */
    @Test
    fun `transfers move the line even though they are neither income nor expense`() {
        val series = trendSeries(
            rows = listOf(day("2026-09-02", expense = 5_000)),
            from = LocalDate.of(2026, 9, 1),
            to = LocalDate.of(2026, 9, 3),
            endBalanceMinor = 93_000,
            deltas = listOf(
                DailyDelta("2026-09-02", -5_000),
                DailyDelta("2026-09-03", -200_000),
            ),
        )
        assertEquals(listOf(298_000L, 293_000L, 93_000L), series.runningMinor)
        // The readout under the chart is unchanged: nothing was SPENT on the 3rd.
        assertEquals(0L, series.points.last().expenseMinor)
    }

    @Test
    fun `pointing past the end of the series falls back to where the line ends`() {
        val series = trendSeries(
            listOf(day("2026-08-03", income = 3_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
            endBalanceMinor = 3_000,
        )
        assertEquals(series.closingMinor, series.runningAt(7))
    }

    // --------------------------------------------------- the turn of the month

    /**
     * A balance is not reset by the calendar. The line used to drop to zero on
     * the 1st — correct for a chart of the month's net, and a cliff nobody's
     * money ever went over for a chart of what is in the account.
     */
    @Test
    fun `the line runs straight through the first of the month`() {
        val series = trendSeries(
            listOf(
                day("2026-08-30", income = 800_000),
                day("2026-08-31", expense = 50_000),
                day("2026-09-01", expense = 12_000),
                day("2026-09-02", expense = 3_000),
            ),
            LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 9, 2),
            endBalanceMinor = 735_000,
        )
        assertEquals(
            listOf(800_000L, 750_000L, 738_000L, 735_000L),
            series.runningMinor,
        )
    }

    /**
     * The two figures under the chart, once a day is being pointed at: what the
     * MONTH had earned and spent by then. A single day's own pair is "0,00 and
     * 0,00" five times out of six.
     */
    @Test
    fun `the readout is the month to date, and it restarts on the first`() {
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
        assertEquals(listOf(800_000L, 800_000L, 0L, 0L), series.monthIncomeMinor)
        assertEquals(listOf(0L, 50_000L, 12_000L, 15_000L), series.monthExpenseMinor)
        assertEquals(15_000L, series.monthExpenseAt(3))
        assertEquals(0L, series.monthIncomeAt(3))
    }

    /**
     * The window usually starts in the middle of the previous month, and a day
     * in that leading tail still belongs to a month that began before the chart
     * did. The caller hands over the rows back to the 1st for exactly this; only
     * the window is plotted.
     */
    @Test
    fun `a day in the leading tail counts its whole month, not the visible part`() {
        val series = trendSeries(
            listOf(
                day("2026-08-04", expense = 70_000),
                day("2026-08-22", expense = 5_000),
            ),
            LocalDate.of(2026, 8, 21),
            LocalDate.of(2026, 8, 23),
        )
        assertEquals(3, series.points.size)
        assertEquals(LocalDate.of(2026, 8, 21), series.from)
        // 70 000 was spent before the window opened and is still August's.
        assertEquals(listOf(70_000L, 75_000L, 75_000L), series.monthExpenseMinor)
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
        assertEquals(25_000L, series.monthExpenseMinor.last())
    }

    // ----------------------------------------------------- the line's range

    /**
     * The range is the data's own. Forcing zero in was right when the line was
     * a month's net and is wrong for a balance: a household holding 8 000 zł
     * would get its line pinned along the top of the box with every move it
     * made flattened into it.
     */
    @Test
    fun `a balance well clear of zero is drawn against its own swing`() {
        val series = trendSeries(
            listOf(day("2026-08-03", expense = 10_000), day("2026-08-04", expense = 5_000)),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
            endBalanceMinor = 800_000,
        )
        assertEquals(listOf(805_000L, 800_000L), series.runningMinor)
        assertEquals(800_000L, series.lowRunningMinor)
        assertEquals(805_000L, series.highRunningMinor)
        assertFalse(series.crossesZero)
    }

    /** An account that actually went under keeps break-even on the chart, because
     *  the line reaches it. */
    @Test
    fun `an account that goes overdrawn still shows the line it crossed`() {
        val series = trendSeries(
            listOf(
                day("2026-08-03", expense = 40_000),
                day("2026-08-05", income = 100_000),
            ),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 5),
            endBalanceMinor = 60_000,
        )
        assertEquals(listOf(-40_000L, -40_000L, 60_000L), series.runningMinor)
        assertEquals(-40_000L, series.lowRunningMinor)
        assertEquals(60_000L, series.highRunningMinor)
        assertTrue(series.crossesZero)
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
            endBalanceMinor = 120_000,
        )
        assertEquals(120_000L, series.closingMinor)
        assertEquals(listOf(120_000L), series.runningMinor)
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

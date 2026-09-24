package com.monyx

import com.monyx.data.DailyTotals
import com.monyx.ui.budget.limitShare
import com.monyx.ui.overview.DAILY_DAYS
import com.monyx.ui.overview.dailySpend
import com.monyx.ui.overview.dailyWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The day-by-day face of the breakdown card, and the budget bar beside it.
 *
 * Both are pictures somebody will act on — "the 12th was the expensive one",
 * "we are miles over on groceries" — and both are drawn from arithmetic that is
 * easy to get quietly wrong by one day or one factor.
 */
class DailySpendTest {

    private val today = LocalDate.of(2026, 9, 24)

    @Test
    fun `window is thirty-one days ending today in the current month`() {
        val window = dailyWindow("2026-09", today)
        assertEquals(LocalDate.of(2026, 8, 25), window.start)
        assertEquals(today, window.endInclusive)
    }

    @Test
    fun `window on a finished month ends on its last day`() {
        val window = dailyWindow("2026-06", today)
        assertEquals(LocalDate.of(2026, 6, 30), window.endInclusive)
        assertEquals(LocalDate.of(2026, 5, 31), window.start)
    }

    @Test
    fun `every day of the window gets a bar`() {
        val window = dailyWindow("2026-09", today)
        val daily = dailySpend(emptyList(), window.start, window.endInclusive)
        assertEquals(DAILY_DAYS, daily.days.size)
        // Consecutive, with nothing skipped: the gaps ARE the chart's shape.
        daily.days.forEachIndexed { index, day ->
            assertEquals(window.start.plusDays(index.toLong()), day.date)
        }
    }

    @Test
    fun `quiet days are zeros rather than missing`() {
        val rows = listOf(DailyTotals(day = "2026-09-23", incomeMinor = 900000, expenseMinor = 4500))
        val daily = dailySpend(rows, LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 24))
        assertEquals(listOf(0L, 4500L, 0L), daily.days.map { it.expenseMinor })
        // Income is not on this chart at all — a 9 000 zł salary would dwarf
        // every bar the household came here to compare.
        assertEquals(4500L, daily.totalMinor)
    }

    @Test
    fun `the busiest day is the tallest bar`() {
        val rows = listOf(
            DailyTotals("2026-09-22", 0, 3000),
            DailyTotals("2026-09-23", 0, 51230),
            DailyTotals("2026-09-24", 0, 12000),
        )
        val daily = dailySpend(rows, LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 24))
        assertEquals(1, daily.busiestIndex)
        assertEquals(51230L, daily.maxMinor)
        assertEquals(66230L, daily.totalMinor)
        assertFalse(daily.isEmpty)
    }

    @Test
    fun `a tie goes to the later day and an empty window has no busiest day`() {
        val tied = dailySpend(
            listOf(DailyTotals("2026-09-22", 0, 5000), DailyTotals("2026-09-24", 0, 5000)),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 9, 24),
        )
        assertEquals(2, tied.busiestIndex)

        val quiet = dailySpend(emptyList(), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 24))
        assertNull(quiet.busiestIndex)
        assertTrue(quiet.isEmpty)
        assertEquals(0L, quiet.totalMinor)
    }

    @Test
    fun `a backwards window still draws one day`() {
        val daily = dailySpend(emptyList(), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 1))
        assertEquals(1, daily.days.size)
        assertEquals(LocalDate.of(2026, 9, 24), daily.from)
    }

    @Test
    fun `the overspent bar splits where the limit fell`() {
        // 1 200 spent against 1 000: the limit is five sixths of the bar and the
        // overspend is the last sixth.
        assertEquals(0.833f, limitShare(1.2f), 0.001f)
        // Twice the limit puts the split in the middle.
        assertEquals(0.5f, limitShare(2f), 0.0001f)
    }

    @Test
    fun `an extreme overspend still leaves both ends drawable`() {
        // Compose refuses a weight of zero, so a category a grosz over its limit
        // — or twenty times over it — must not round either end away.
        assertTrue(limitShare(1.0001f) < 1f)
        assertTrue(limitShare(1.0001f) >= 0.04f)
        assertTrue(limitShare(50f) >= 0.04f)
        assertTrue(1f - limitShare(50f) > 0f)
        assertTrue(1f - limitShare(1.0001f) > 0f)
    }
}

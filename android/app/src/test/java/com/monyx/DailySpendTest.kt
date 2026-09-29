package com.monyx

import com.monyx.data.DailyCategorySpend
import com.monyx.ui.budget.limitShare
import com.monyx.ui.overview.DAILY_DAYS
import com.monyx.ui.overview.dailySpend
import com.monyx.ui.overview.SpendPart
import com.monyx.ui.overview.dailyWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    /** One category's spending on one day, the shape the query returns. */
    private fun row(day: String, minor: Long, category: String = "jedzenie") =
        DailyCategorySpend(
            day = day,
            categoryId = category,
            name = category.replaceFirstChar { it.uppercase() },
            color = null,
            spentMinor = minor,
        )

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
        val daily = dailySpend(
            listOf(row("2026-09-23", 4500)),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 9, 24),
        )
        assertEquals(listOf(0L, 4500L, 0L), daily.days.map { it.expenseMinor })
        // Income is not on this chart at all — a 9 000 zł salary would dwarf
        // every bar the household came here to compare, which is why the query
        // behind it only ever asks about expenses.
        assertEquals(4500L, daily.totalMinor)
    }

    @Test
    fun `a day is the sum of its categories, biggest first`() {
        val daily = dailySpend(
            listOf(
                row("2026-09-23", 3000, "transport"),
                row("2026-09-23", 51230, "jedzenie"),
                row("2026-09-23", 12000, "dom"),
            ),
            LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 9, 23),
        )
        val day = daily.days.single()
        assertEquals(66230L, day.expenseMinor)
        // Sorted so the bar stacks its biggest block on the baseline; the
        // smallest is on top, which is where the rounded cap goes.
        assertEquals(listOf("jedzenie", "dom", "transport"), day.parts.map { it.categoryId })
        // The tallest BAR, which is a day's total and not its largest category:
        // the y axis is scaled to whole days.
        assertEquals(66230L, daily.maxMinor)
        assertFalse(daily.isEmpty)
    }

    @Test
    fun `the legend is the window's categories, biggest first`() {
        val daily = dailySpend(
            listOf(
                row("2026-09-22", 3000, "transport"),
                row("2026-09-23", 4000, "jedzenie"),
                row("2026-09-24", 9000, "transport"),
            ),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 9, 24),
        )
        // Transport is two days added together, and that is what puts it first:
        // the legend is about the window, not about any one bar in it.
        assertEquals(listOf("transport", "jedzenie"), daily.categories.map { it.categoryId })
        assertEquals(listOf(12000L, 4000L), daily.categories.map { it.minorAmount })
        assertEquals(16000L, daily.totalMinor)
    }

    @Test
    fun `an empty window has nothing to name`() {
        val quiet = dailySpend(emptyList(), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 24))
        assertTrue(quiet.isEmpty)
        assertEquals(0L, quiet.totalMinor)
        assertTrue(quiet.categories.isEmpty())
    }

    @Test
    fun `a day can be looked up by date, and only inside the window`() {
        val daily = dailySpend(
            listOf(row("2026-09-23", 4500)),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 9, 24),
        )
        // What the sheet opens on: the tap carries a date, not an index.
        assertEquals(4500L, daily.day(LocalDate.of(2026, 9, 23))?.expenseMinor)
        assertEquals(0L, daily.day(LocalDate.of(2026, 9, 22))?.expenseMinor)
        assertEquals(null, daily.day(LocalDate.of(2026, 9, 25)))
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

/**
 * Hiding a category has to change the chart, not just the legend.
 *
 * Every figure the face shows — the tallest bar the axis is scaled to, the
 * window total, a day's total in the sheet — is derived from the days, so this
 * is the one place that has to drop the parts for all of them to follow.
 */
class DailySpendHidingTest {

    private fun row(day: String, categoryId: String, minor: Long) =
        DailyCategorySpend(day = day, categoryId = categoryId, name = categoryId, color = null, spentMinor = minor)

    private fun window() = dailySpend(
        rows = listOf(
            row("2026-09-01", "food", 10_000),
            row("2026-09-01", "fuel", 40_000),
            row("2026-09-02", "food", 20_000),
        ),
        from = LocalDate.parse("2026-09-01"),
        to = LocalDate.parse("2026-09-02"),
    )

    @Test
    fun `hiding a category drops it from every day it appears on`() {
        val shown = window().excluding(setOf("fuel"))
        assertEquals(listOf("food"), shown.days[0].parts.map { it.categoryId })
        assertEquals(10_000L, shown.days[0].expenseMinor)
    }

    /** The axis is scaled to this, so the bars have to grow back into the space. */
    @Test
    fun `the tallest bar is recomputed, not merely redrawn`() {
        val full = window()
        assertEquals(50_000L, full.maxMinor)
        // Day one was the tall one only because of the fuel; without it day two is.
        assertEquals(20_000L, full.excluding(setOf("fuel")).maxMinor)
    }

    @Test
    fun `the window total counts only what is left`() {
        assertEquals(70_000L, window().totalMinor)
        assertEquals(30_000L, window().excluding(setOf("fuel")).totalMinor)
    }

    @Test
    fun `the day the sheet opens agrees with the bar above it`() {
        val shown = window().excluding(setOf("fuel"))
        assertEquals(10_000L, shown.day(LocalDate.parse("2026-09-01"))?.expenseMinor)
    }

    @Test
    fun `hiding everything empties the face rather than leaving zero-height bars`() {
        val shown = window().excluding(setOf("food", "fuel"))
        assertTrue(shown.isEmpty)
        assertEquals(emptyList<SpendPart>(), shown.categories)
    }

    /** Quiet days survive: the run of days is the chart's x-axis, not its data. */
    @Test
    fun `the days themselves are all still there`() {
        assertEquals(2, window().excluding(setOf("food", "fuel")).days.size)
    }

    @Test
    fun `hiding nothing returns the very same window`() {
        val full = window()
        assertSame(full, full.excluding(emptySet()))
    }
}

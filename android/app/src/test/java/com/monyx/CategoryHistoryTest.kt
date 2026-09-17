package com.monyx

import com.monyx.data.MonthlyCategorySpend
import com.monyx.ui.overview.HISTORY_MONTHS
import com.monyx.ui.overview.axisTicks
import com.monyx.ui.overview.categoryHistory
import com.monyx.ui.overview.historyWindow
import com.monyx.ui.overview.monthIndexAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The arithmetic behind the breakdown card's back face.
 *
 * Every number here is one somebody will compare against a bank statement —
 * "we spend about 900 a month on groceries" is a claim, and the average that
 * makes it is divided by a count that is easy to get quietly wrong. The chart
 * itself would show all of these mistakes as a plausible picture.
 */
class CategoryHistoryTest {

    private val today = LocalDate.of(2026, 9, 17)

    private fun spend(period: String, id: String, minor: Long) =
        MonthlyCategorySpend(period, id, id.replaceFirstChar { it.uppercase() }, null, minor)

    // ------------------------------------------------------------- the window

    @Test
    fun `the window is twelve months ending at the current one`() {
        val window = historyWindow("2026-09", today)
        assertEquals(HISTORY_MONTHS, window.size)
        assertEquals("2025-10", window.first())
        assertEquals("2026-09", window.last())
    }

    @Test
    fun `browsing back inside the window does not move it`() {
        // The whole point of the highlight: tapping March selects March, it
        // does not slide the chart three months and leave the bar elsewhere.
        assertEquals(historyWindow("2026-09", today), historyWindow("2026-03", today))
    }

    @Test
    fun `a month further back than the window ends the window there`() {
        val window = historyWindow("2024-02", today)
        assertEquals("2024-02", window.last())
        assertEquals("2023-03", window.first())
    }

    @Test
    fun `a month dated forward extends the window on`() {
        val window = historyWindow("2026-12", today)
        assertEquals("2026-12", window.last())
        assertEquals(HISTORY_MONTHS, window.size)
    }

    // ------------------------------------------------------------- the months

    @Test
    fun `a month nothing was spent in still gets a bar`() {
        val history = categoryHistory(
            rows = listOf(spend("2026-07", "food", 30000)),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertEquals(HISTORY_MONTHS, history.months.size)
        assertEquals(0L, history.months.first().totalMinor(setOf("food")))
    }

    @Test
    fun `a hidden category leaves the bar it was in`() {
        val history = categoryHistory(
            rows = listOf(spend("2026-07", "food", 30000), spend("2026-07", "fuel", 10000)),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        val july = history.months.first { it.period == "2026-07" }
        assertEquals(40000L, july.totalMinor(setOf("food", "fuel")))
        assertEquals(30000L, july.totalMinor(setOf("food")))
    }

    @Test
    fun `the current month is partial and the ones before it are not`() {
        val history = categoryHistory(
            rows = emptyList(),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertTrue(history.months.last { it.period == "2026-09" }.partial)
        assertTrue(history.months.none { it.period < "2026-09" && it.partial })
    }

    // ------------------------------------------------------------ the average

    @Test
    fun `the average divides by the months the ledger covers, not by twelve`() {
        // Three months of ledger, one of them the month still running: the
        // divisor is the two completed months that have data, never twelve.
        // A household three months in would otherwise see every average
        // quartered — the number is a claim about a normal month.
        val history = categoryHistory(
            rows = listOf(
                spend("2026-07", "food", 90000),
                spend("2026-08", "food", 110000),
                spend("2026-09", "food", 40000),
            ),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertEquals(100000L, history.categories.single().averageMinor)
    }

    @Test
    fun `a month with other spending but none of this category counts as a zero`() {
        // August is a real zero for fuel — the ledger was being kept and
        // nothing went on it — so fuel averages 200 over the two months, not
        // 400 over the one it appears in.
        val history = categoryHistory(
            rows = listOf(
                spend("2026-07", "fuel", 40000),
                spend("2026-07", "food", 50000),
                spend("2026-08", "food", 50000),
            ),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertEquals(20000L, history.categories.single { it.id == "fuel" }.averageMinor)
    }

    @Test
    fun `a household whose only month is the one it is living gets that month`() {
        // No completed month to average over. A column of zeros would be worse
        // than the partial figure, which is the only answer there is.
        val history = categoryHistory(
            rows = listOf(spend("2026-09", "food", 30000)),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertEquals(30000L, history.categories.single().averageMinor)
    }

    @Test
    fun `the month still running does not drag the average down`() {
        val base = listOf(spend("2026-07", "food", 90000), spend("2026-08", "food", 110000))
        val withoutCurrent = categoryHistory(
            rows = base,
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        ).categories.single().averageMinor
        val withCurrent = categoryHistory(
            rows = base + spend("2026-09", "food", 500),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        ).categories.single().averageMinor
        assertEquals(withoutCurrent, withCurrent)
    }

    @Test
    fun `categories come back in average order`() {
        val history = categoryHistory(
            rows = listOf(
                spend("2026-07", "fuel", 20000),
                spend("2026-07", "food", 90000),
                spend("2026-08", "fuel", 20000),
            ),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertEquals(listOf("food", "fuel"), history.categories.map { it.id })
    }

    @Test
    fun `rows outside the window are ignored`() {
        val history = categoryHistory(
            rows = listOf(spend("2024-01", "food", 500000)),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        assertTrue(history.isEmpty)
    }

    // --------------------------------------------------------- the highlight

    @Test
    fun `the selected month is found in the window`() {
        val history = categoryHistory(
            rows = emptyList(),
            periods = historyWindow("2026-03", today),
            selectedPeriod = "2026-03",
            today = today,
        )
        assertEquals("2026-03", history.months[history.selectedIndex!!].period)
    }

    // ------------------------------------------------------------- the y axis

    @Test
    fun `the axis tops out above the tallest bar, on a round number`() {
        val ticks = axisTicks(220000)
        assertEquals(0L, ticks.first())
        assertTrue("${ticks.last()} must clear 2 200 zł", ticks.last() >= 220000)
        // 0, 1 000, 2 000, 3 000 — a step somebody can read the bars against.
        assertEquals(listOf(0L, 100000L, 200000L, 300000L), ticks)
    }

    @Test
    fun `the axis does not leave a third of the chart empty`() {
        // A year topping out at 4 500 zł: three steps of 1 500, not of 2 000.
        assertEquals(listOf(0L, 150000L, 300000L, 450000L), axisTicks(450000))
    }

    @Test
    fun `an empty window still has an axis`() {
        assertEquals(listOf(0L), axisTicks(0))
    }

    @Test
    fun `small amounts do not get an axis in thousands`() {
        assertEquals(listOf(0L, 4000L, 8000L, 12000L), axisTicks(10000))
    }

    // -------------------------------------------------------------- the taps

    @Test
    fun `a tap belongs to the column it lands in`() {
        // 12 columns across the 360px left of a 40px gutter: 30px each.
        assertEquals(0, monthIndexAt(45f, 40f, 400f, 12))
        assertEquals(11, monthIndexAt(395f, 40f, 400f, 12))
        assertEquals(6, monthIndexAt(40f + 30f * 6.5f, 40f, 400f, 12))
    }

    @Test
    fun `a tap in the axis gutter is not a month`() {
        assertNull(monthIndexAt(12f, 40f, 400f, 12))
    }

    @Test
    fun `a tap past the last column stays on the last month`() {
        assertEquals(11, monthIndexAt(1000f, 40f, 400f, 12))
    }
}

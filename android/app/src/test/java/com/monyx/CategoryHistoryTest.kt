package com.monyx

import com.monyx.data.BudgetLimit
import com.monyx.data.MonthlyCategorySpend
import com.monyx.ui.overview.HISTORY_MONTHS
import com.monyx.ui.overview.LegendAmount
import com.monyx.ui.overview.HistoryCategory
import com.monyx.ui.overview.axisTicks
import com.monyx.ui.overview.categoryHistory
import com.monyx.ui.overview.historyWindow
import com.monyx.ui.overview.legendAmounts
import com.monyx.ui.overview.legendOrder
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
        // 0, 750, 1 500, 2 250 — a step somebody can read the bars against,
        // and a ceiling that sits just above them rather than a third higher.
        assertEquals(listOf(0L, 75000L, 150000L, 225000L), ticks)
    }

    @Test
    fun `hiding half the spending moves the ceiling`() {
        // The ladder used to run ...4, 5, 10, so every total between five and
        // ten times a power of ten drew the same axis: 15 000 and 30 000 both
        // topped out at 30 000. Hiding a category halved the bars and the chart
        // did not move, which is what it was reported as — a frozen axis.
        val whole = axisTicks(2_990_000)
        val halved = axisTicks(1_500_000)
        assertTrue(
            "30 000 zł and 15 000 zł must not draw the same ceiling",
            whole.last() != halved.last(),
        )
        assertTrue(halved.last() >= 1_500_000)
    }

    @Test
    fun `hiding always moves the ceiling, at every size`() {
        // The property the frozen axis broke, swept from 1 zł to 100 000 zł:
        // halve what is on the chart and the ceiling must come down. Checked
        // across the whole range because the old gap was invisible until the
        // household's totals happened to land inside it.
        var amount = 100L
        while (amount <= 10_000_000L) {
            val top = axisTicks(amount).last()
            assertTrue("$amount overflows its axis $top", top >= amount)
            // 1.5x is the ladder's widest rung, 1 to 1.5, and its worst case.
            assertTrue("$amount leaves too much air under $top", top <= amount * 3 / 2)
            assertTrue(
                "halving $amount left the ceiling at $top",
                axisTicks(amount / 2).last() < top,
            )
            amount += 700L
        }
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

    // ------------------------------------------------------- the budget line

    private fun limit(
        period: String,
        categoryId: String,
        minor: Long,
        parent: String? = null,
        deleted: Int = 0,
        seq: Long = 1,
    ) = BudgetLimit(
        id = "$categoryId:$period:$seq",
        period = period,
        categoryId = categoryId,
        rollupId = parent ?: categoryId,
        limitMinor = minor,
        deleted = deleted,
        seq = seq,
    )

    private fun historyWith(budgets: List<BudgetLimit>, selected: String = "2026-09") =
        categoryHistory(
            rows = listOf(spend("2026-09", "food", 40000)),
            periods = historyWindow(selected, today),
            selectedPeriod = selected,
            today = today,
            budgets = budgets,
        )

    @Test
    fun `a limit holds in every month after the one it was set in`() {
        val months = historyWith(listOf(limit("2026-07", "food", 90000))).months
        assertNull(months.first { it.period == "2026-06" }.budgetMinor(emptySet()))
        assertEquals(90000L, months.first { it.period == "2026-07" }.budgetMinor(emptySet()))
        assertEquals(90000L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `a newer limit supersedes the one before it`() {
        val months = historyWith(
            listOf(limit("2026-07", "food", 90000), limit("2026-08", "food", 120000)),
        ).months
        assertEquals(90000L, months.first { it.period == "2026-07" }.budgetMinor(emptySet()))
        assertEquals(120000L, months.first { it.period == "2026-08" }.budgetMinor(emptySet()))
    }

    @Test
    fun `clearing a limit stops it rather than falling back to the older one`() {
        // The tombstone is the newest row, and it carries no limit. Skipping it
        // would resurrect a limit the household deliberately removed.
        val months = historyWith(
            listOf(
                limit("2026-07", "food", 90000),
                limit("2026-08", "food", 0, deleted = 1),
            ),
        ).months
        assertEquals(90000L, months.first { it.period == "2026-07" }.budgetMinor(emptySet()))
        assertNull(months.first { it.period == "2026-08" }.budgetMinor(emptySet()))
        assertNull(months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `a limit set before the window still applies inside it`() {
        val months = historyWith(listOf(limit("2023-01", "food", 90000))).months
        assertEquals(90000L, months.first().budgetMinor(emptySet()))
    }

    @Test
    fun `two rows for one budget count once`() {
        // The same limit under two ids — the server keeps its own row id on an
        // upsert, so the next pull lands a second local row. Summing them would
        // double the household's budget for good.
        val months = historyWith(
            listOf(
                limit("2026-07", "food", 90000, seq = 4),
                limit("2026-07", "food", 95000, seq = 9),
            ),
        ).months
        assertEquals(95000L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `limits add up across categories`() {
        val months = historyWith(
            listOf(limit("2026-07", "food", 90000), limit("2026-07", "fuel", 45000)),
        ).months
        assertEquals(135000L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `a limit on the parent covers its subcategories`() {
        // Home is 1 600 and Home > Repairs is 400 inside it, not beside it.
        val months = historyWith(
            listOf(
                limit("2026-07", "home", 160000),
                limit("2026-07", "repairs", 40000, parent = "home"),
            ),
        ).months
        assertEquals(160000L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `subcategory limits add up when the parent has none`() {
        val months = historyWith(
            listOf(
                limit("2026-07", "repairs", 40000, parent = "home"),
                limit("2026-07", "garden", 20000, parent = "home"),
            ),
        ).months
        assertEquals(60000L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `hiding a category takes its limit out of the line`() {
        val months = historyWith(
            listOf(limit("2026-07", "food", 90000), limit("2026-07", "fuel", 45000)),
        ).months
        assertEquals(45000L, months.last().budgetMinor(setOf("food")))
    }

    @Test
    fun `hiding everything budgeted leaves no line at all`() {
        // Not a line along the floor: no limit and a limit of nothing are
        // different claims, and only one of them is true here.
        val months = historyWith(listOf(limit("2026-07", "food", 90000))).months
        assertNull(months.last().budgetMinor(setOf("food")))
    }

    @Test
    fun `no budgets means no line`() {
        val months = historyWith(emptyList()).months
        assertTrue(months.all { it.budgetMinor(emptySet()) == null })
    }

    // ------------------------------------------------------ the legend order

    private fun categoriesOf(vararg pairs: Pair<String, Long>) =
        pairs.map { (id, average) ->
            HistoryCategory(
                id = id,
                name = id,
                color = null,
                totalMinor = average * 12,
                averageMinor = average,
            )
        }

    @Test
    fun `visible categories come first, dearest first`() {
        val ordered = legendOrder(
            categoriesOf("home" to 160000, "food" to 90000, "fuel" to 45000),
            hidden = emptySet(),
        )
        assertEquals(listOf("home", "food", "fuel"), ordered.map { it.id })
    }

    @Test
    fun `hidden categories sink below every visible one`() {
        // Home is the dearest thing the household has and it goes to the
        // bottom: the list's job is the chart, and it is not on the chart.
        val ordered = legendOrder(
            categoriesOf("home" to 160000, "food" to 90000, "fuel" to 45000),
            hidden = setOf("home"),
        )
        assertEquals(listOf("food", "fuel", "home"), ordered.map { it.id })
    }

    @Test
    fun `the hidden ones are themselves in order`() {
        val ordered = legendOrder(
            categoriesOf("home" to 160000, "food" to 90000, "fuel" to 45000, "fun" to 30000),
            hidden = setOf("fuel", "home"),
        )
        assertEquals(listOf("food", "fun", "home", "fuel"), ordered.map { it.id })
    }

    @Test
    fun `an id for a category that is not on the list changes nothing`() {
        // The stored set outlives the window it was made in, so it can name a
        // category nobody has spent on in a year.
        val categories = categoriesOf("food" to 90000, "fuel" to 45000)
        assertEquals(
            categories.map { it.id },
            legendOrder(categories, hidden = setOf("boat")).map { it.id },
        )
    }

    @Test
    fun `hiding everything keeps the list and its order`() {
        val ordered = legendOrder(
            categoriesOf("home" to 160000, "food" to 90000),
            hidden = setOf("home", "food"),
        )
        assertEquals(listOf("home", "food"), ordered.map { it.id })
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

    // --------------------------------------- what the legend prints per row

    private fun twoMonths() = categoryHistory(
        rows = listOf(
            spend("2026-08", "food", 90000),
            spend("2026-08", "fun", 10000),
            spend("2026-09", "food", 20000),
            spend("2026-09", "fun", 50000),
        ),
        periods = historyWindow("2026-09", today),
        selectedPeriod = "2026-09",
        today = today,
    )

    @Test
    fun `the average is what it always was`() {
        val amounts = legendAmounts(twoMonths(), LegendAmount.Average)
        val categories = twoMonths().categories.associateBy { it.id }
        assertEquals(categories["food"]!!.averageMinor, amounts["food"])
        assertEquals(categories["fun"]!!.averageMinor, amounts["fun"])
    }

    @Test
    fun `the month shows that month, not the window`() {
        val amounts = legendAmounts(twoMonths(), LegendAmount.SelectedMonth)
        assertEquals(20000L, amounts["food"])
        assertEquals(50000L, amounts["fun"])
    }

    /**
     * The whole point of the toggle: September's biggest category is not the
     * window's biggest, and the list has to say so in its order as well as in
     * its numbers.
     */
    @Test
    fun `switching to the month re-sorts the legend with it`() {
        val history = twoMonths()
        val byAverage = legendAmounts(history, LegendAmount.Average)
        val byMonth = legendAmounts(history, LegendAmount.SelectedMonth)
        assertEquals(
            listOf("food", "fun"),
            legendOrder(history.categories, emptySet()) { byAverage[it.id] ?: 0L }.map { it.id },
        )
        assertEquals(
            listOf("fun", "food"),
            legendOrder(history.categories, emptySet()) { byMonth[it.id] ?: 0L }.map { it.id },
        )
    }

    @Test
    fun `a category with nothing in the chosen month is a zero, not a gap`() {
        val history = categoryHistory(
            rows = listOf(spend("2026-08", "food", 90000), spend("2026-09", "fun", 50000)),
            periods = historyWindow("2026-09", today),
            selectedPeriod = "2026-09",
            today = today,
        )
        val amounts = legendAmounts(history, LegendAmount.SelectedMonth)
        assertEquals(0L, amounts["food"])
        assertEquals(50000L, amounts["fun"])
    }

    @Test
    fun `hidden rows still sink, whichever figure is on show`() {
        val history = twoMonths()
        val byMonth = legendAmounts(history, LegendAmount.SelectedMonth)
        assertEquals(
            listOf("food", "fun"),
            legendOrder(history.categories, setOf("fun")) { byMonth[it.id] ?: 0L }.map { it.id },
        )
    }

    // ------------------------------------------- a limit of nothing is a limit

    @Test
    fun `a limit of zero draws a line on the floor, not no line`() {
        val months = historyWith(listOf(limit("2026-07", "food", 0))).months
        assertNull(months.first { it.period == "2026-06" }.budgetMinor(emptySet()))
        assertEquals(0L, months.first { it.period == "2026-07" }.budgetMinor(emptySet()))
    }

    @Test
    fun `zero carries forward like any other limit`() {
        val months = historyWith(listOf(limit("2026-07", "food", 0))).months
        assertEquals(0L, months.last().budgetMinor(emptySet()))
    }

    @Test
    fun `zero is superseded, and supersedes`() {
        val months = historyWith(
            listOf(
                limit("2026-07", "food", 90000),
                limit("2026-08", "food", 0, seq = 2),
                limit("2026-09", "food", 50000, seq = 3),
            ),
        ).months
        assertEquals(90000L, months.first { it.period == "2026-07" }.budgetMinor(emptySet()))
        assertEquals(0L, months.first { it.period == "2026-08" }.budgetMinor(emptySet()))
        assertEquals(50000L, months.first { it.period == "2026-09" }.budgetMinor(emptySet()))
    }

    /**
     * The distinction the `>= 0` test exists to keep: zero is a budget, a
     * tombstone is the absence of one, and they must not collapse into each
     * other just because both carry no money.
     */
    @Test
    fun `a cleared limit is still nothing at all, unlike a zero one`() {
        val zero = historyWith(listOf(limit("2026-07", "food", 0))).months
        val cleared = historyWith(
            listOf(limit("2026-07", "food", 90000), limit("2026-08", "food", 0, deleted = 1, seq = 2)),
        ).months
        assertEquals(0L, zero.last().budgetMinor(emptySet()))
        assertNull(cleared.last().budgetMinor(emptySet()))
    }

    @Test
    fun `a zero limit on one category still sums with a real one on another`() {
        val months = historyWith(
            listOf(limit("2026-07", "food", 0), limit("2026-07", "fun", 30000)),
        ).months
        assertEquals(30000L, months.last().budgetMinor(emptySet()))
        // Hiding the funded one leaves the zero behind, which is a line at the
        // floor rather than no line: the household still budgeted for food.
        assertEquals(0L, months.last().budgetMinor(setOf("fun")))
    }
}

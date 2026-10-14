package com.monyx

import com.monyx.data.BudgetUsage
import com.monyx.ui.budget.BudgetSort
import com.monyx.ui.budget.sortedBudgets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which limit the budget list puts first.
 *
 * The orders are arithmetic on figures the query has already computed, so this
 * is where they are decidable — the screen itself only draws the result. What is
 * worth pinning down is the awkward cases: a limit of nothing, a tie, and the
 * difference between "closest to the limit" and "least money left", which are
 * the same thing only when every limit is the same size.
 */
class BudgetSortTest {

    private fun usage(id: String, limitMinor: Long, spentMinor: Long) = BudgetUsage(
        categoryId = id,
        name = id,
        color = null,
        icon = null,
        parentId = null,
        parentColor = null,
        limitMinor = limitMinor,
        spentMinor = spentMinor,
    )

    // In the order the query returns them, which is the household's own.
    private val rows = listOf(
        usage("food", limitMinor = 200_000, spentMinor = 180_000),   // 90%
        usage("rent", limitMinor = 2_000_000, spentMinor = 1_000_000), // 50%
        usage("fun", limitMinor = 20_000, spentMinor = 19_000),      // 95%
    )

    private fun ids(sort: BudgetSort) = sortedBudgets(rows, sort).map { it.categoryId }

    @Test
    fun `the household's own order is the one the query came back in`() {
        assertEquals(listOf("food", "rent", "fun"), ids(BudgetSort.Own))
    }

    @Test
    fun `the largest limit leads, whatever has gone out of it`() {
        assertEquals(listOf("rent", "food", "fun"), ids(BudgetSort.Limit))
    }

    @Test
    fun `most spent is about the money, not the share of the limit`() {
        assertEquals(listOf("rent", "food", "fun"), ids(BudgetSort.Spent))
    }

    @Test
    fun `closest to the limit is a share, so a small limit can lead`() {
        // 19 000 of 20 000 is tighter than 1 000 000 of 2 000 000, and the
        // household wants to know about the first one. Ordering by the money
        // left over would have put it last.
        assertEquals(listOf("fun", "food", "rent"), ids(BudgetSort.Progress))
    }

    @Test
    fun `spending against a limit of nothing is as over as it gets`() {
        val withZero = rows + usage("gifts", limitMinor = 0, spentMinor = 5_000)
        assertEquals("gifts", sortedBudgets(withZero, BudgetSort.Progress).first().categoryId)
    }

    @Test
    fun `a limit of nothing with nothing spent is not an alarm`() {
        val withZero = rows + usage("gifts", limitMinor = 0, spentMinor = 0)
        assertEquals("gifts", sortedBudgets(withZero, BudgetSort.Progress).last().categoryId)
    }

    @Test
    fun `a tie keeps the household's order rather than shuffling`() {
        // Two categories at the same figure sit where they always sit. A sort
        // that swapped them between frames would make the list look alive.
        val tied = listOf(
            usage("a", limitMinor = 100_000, spentMinor = 50_000),
            usage("b", limitMinor = 100_000, spentMinor = 50_000),
            usage("c", limitMinor = 100_000, spentMinor = 90_000),
        )
        assertEquals(listOf("c", "a", "b"), sortedBudgets(tied, BudgetSort.Progress).map { it.categoryId })
        assertEquals(listOf("a", "b", "c"), sortedBudgets(tied, BudgetSort.Limit).map { it.categoryId })
    }

    @Test
    fun `an order this build has never heard of reads as the household's own`() {
        // A newer build's value, or a key written by hand. Falling back to the
        // stored order means a phone that cannot understand the preference
        // still shows a list somebody arranged.
        assertEquals(BudgetSort.Own, BudgetSort.of(null))
        assertEquals(BudgetSort.Own, BudgetSort.of("by-vibes"))
        BudgetSort.entries.forEach { sort ->
            assertEquals(sort, BudgetSort.of(sort.stored))
        }
    }
}

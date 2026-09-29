package com.monyx

import com.monyx.data.BudgetEdit
import com.monyx.ui.budget.BudgetBand
import com.monyx.ui.budget.overBudgetBand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two rules about limits that are easy to get quietly wrong, and expensive when
 * they are: one decides what a household is told about its month, the other
 * decides what an edit does to every month after this one.
 */
class BudgetEditTest {

    // ------------------------------------------------ what the colour means

    @Test
    fun `a limit missed by a rounding error is not an emergency`() {
        // 1 650 zł, four złoty over. Red here is what taught the eye to skip red.
        assertEquals(BudgetBand.Close, overBudgetBand(spentMinor = 165_400, limitMinor = 165_000))
        // Four hundred over is the event the colour exists for.
        assertEquals(BudgetBand.Over, overBudgetBand(spentMinor = 205_000, limitMinor = 165_000))
    }

    @Test
    fun `the margin is one per cent of the limit, not a fixed amount`() {
        assertEquals(BudgetBand.Close, overBudgetBand(spentMinor = 101_000, limitMinor = 100_000))
        assertEquals(BudgetBand.Over, overBudgetBand(spentMinor = 101_001, limitMinor = 100_000))
        // A big limit gets a proportionally bigger margin, which is the point.
        assertEquals(BudgetBand.Close, overBudgetBand(spentMinor = 1_009_000, limitMinor = 1_000_000))
    }

    @Test
    fun `the escalation never goes backwards`() {
        assertEquals(BudgetBand.Under, overBudgetBand(spentMinor = 50_000, limitMinor = 100_000))
        assertEquals(BudgetBand.Close, overBudgetBand(spentMinor = 80_000, limitMinor = 100_000))
        assertEquals(BudgetBand.Close, overBudgetBand(spentMinor = 100_000, limitMinor = 100_000))
        assertEquals(BudgetBand.Over, overBudgetBand(spentMinor = 300_000, limitMinor = 100_000))
    }

    @Test
    fun `a limit of nothing has no margin to be within`() {
        // "Do not spend here" is a real limit, and one per cent of zero is zero.
        assertEquals(BudgetBand.Over, overBudgetBand(spentMinor = 1, limitMinor = 0))
        assertEquals(BudgetBand.Under, overBudgetBand(spentMinor = 0, limitMinor = 0))
    }

    // --------------------------------------------- what an edit writes where

    @Test
    fun `changing a limit leaves the months after it alone`() {
        val writes = BudgetEdit.writes(
            period = "2026-09",
            limitMinor = 280_000,
            alsoFutureMonths = false,
            inForceMinor = 250_000,
            nextMonthHasRow = false,
        )
        // September gets the new figure, and October is handed the old one back
        // — without that second row, carry-forward would raise every month to
        // come because one month had a wedding in it.
        assertEquals(
            listOf(
                BudgetEdit.Write("2026-09", 280_000, deleted = false),
                BudgetEdit.Write("2026-10", 250_000, deleted = false),
            ),
            writes,
        )
    }

    @Test
    fun `a category with no limit before gets a tombstone after`() {
        val writes = BudgetEdit.writes(
            period = "2026-09",
            limitMinor = 50_000,
            alsoFutureMonths = false,
            inForceMinor = null,
            nextMonthHasRow = false,
        )
        // There is no old figure to restore, so inheritance has to be stopped
        // rather than handed a number that was never there.
        assertEquals(BudgetEdit.Write("2026-10", 0, deleted = true), writes.last())
    }

    @Test
    fun `a month that was set deliberately is not written over`() {
        val writes = BudgetEdit.writes(
            period = "2026-09",
            limitMinor = 280_000,
            alsoFutureMonths = false,
            inForceMinor = 250_000,
            nextMonthHasRow = true,
        )
        // Somebody set October on purpose. "Only this month" is a promise about
        // this month, not licence to overwrite a different one.
        assertEquals(listOf(BudgetEdit.Write("2026-09", 280_000, deleted = false)), writes)
    }

    @Test
    fun `carrying forward overwrites the later months that would contradict it`() {
        val writes = BudgetEdit.writes(
            period = "2026-09",
            limitMinor = 280_000,
            alsoFutureMonths = true,
            inForceMinor = 250_000,
            nextMonthHasRow = true,
            laterPeriods = listOf("2026-10", "2027-01"),
        )
        assertEquals(
            listOf(
                BudgetEdit.Write("2026-09", 280_000, deleted = false),
                BudgetEdit.Write("2026-10", 280_000, deleted = false),
                BudgetEdit.Write("2027-01", 280_000, deleted = false),
            ),
            writes,
        )
    }

    @Test
    fun `carrying forward across a year boundary still means the next month`() {
        val writes = BudgetEdit.writes(
            period = "2026-12",
            limitMinor = 90_000,
            alsoFutureMonths = false,
            inForceMinor = 80_000,
            nextMonthHasRow = false,
        )
        assertEquals("2027-01", writes.last().period)
    }
}

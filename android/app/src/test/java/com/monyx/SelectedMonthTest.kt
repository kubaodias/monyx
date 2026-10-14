package com.monyx

import com.monyx.data.Dates
import com.monyx.ui.SelectedMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * One month, three tabs.
 *
 * The bug this replaces is invisible in any single screen: each tab stepped
 * correctly, and they only disagreed with each other. So what is tested here is
 * the sharing itself — that two readers of the same holder cannot drift — and
 * the period arithmetic the picker grid does, which is the one place in the app
 * that builds a period out of loose integers rather than shifting an existing
 * one.
 */
class SelectedMonthTest {

    @Test
    fun `a step on one screen is a step on all of them`() {
        val month = SelectedMonth("2026-09")
        month.shift(-1)
        assertEquals("2026-08", month.period.value)
    }

    @Test
    fun `stepping crosses the new year in both directions`() {
        val month = SelectedMonth("2026-01")
        month.shift(-1)
        assertEquals("2025-12", month.period.value)
        month.shift(1)
        month.shift(11)
        assertEquals("2026-12", month.period.value)
    }

    @Test
    fun `a blank period is not a selection`() {
        // Tapping the transactions tab passes null, and a null must not be
        // mistaken for "go back to the present month" — that would throw away
        // the month the user chose on the screen they came from.
        val month = SelectedMonth("2026-03")
        month.set(null)
        month.set("")
        month.set("   ")
        assertEquals("2026-03", month.period.value)
    }

    @Test
    fun `a deep link's period wins`() {
        val month = SelectedMonth("2026-03")
        month.set("2025-11")
        assertEquals("2025-11", month.period.value)
    }

    @Test
    fun `the picker builds a period from a year and a month`() {
        assertEquals("2026-01", Dates.period(2026, 1))
        assertEquals("2026-12", Dates.period(2026, 12))
        assertEquals("0999-09", Dates.period(999, 9))
    }

    @Test
    fun `a period splits back into the year and month the grid marks`() {
        assertEquals(2026, Dates.yearOf("2026-09"))
        assertEquals(9, Dates.monthOf("2026-09"))
        // Leading zero, which parses as octal in more languages than it should.
        assertEquals(8, Dates.monthOf("2026-08"))
    }

    @Test
    fun `round tripping a period through the grid changes nothing`() {
        listOf("2024-01", "2025-06", "2026-10", "2026-12").forEach { period ->
            assertEquals(period, Dates.period(Dates.yearOf(period), Dates.monthOf(period)))
        }
    }

    @Test
    fun `the keypad opens on today while the month on screen is the running one`() {
        // The usual path, and the one that must not change: Dodaj from the
        // present month is Dodaj dated today, down to the hour it is saved at.
        val today = LocalDate.of(2026, 10, 14)
        assertEquals(today, Dates.dayInPeriod("2026-10", today))
    }

    @Test
    fun `a month stepped back carries its own day to the keypad`() {
        // Somebody reading September and reaching for Dodaj is entering
        // something that happened in September. See AddViewModel.pickUpMonth.
        assertEquals(
            LocalDate.of(2026, 9, 14),
            Dates.dayInPeriod("2026-09", LocalDate.of(2026, 10, 14)),
        )
    }

    @Test
    fun `a day the shorter month does not have lands on its last one`() {
        assertEquals(
            LocalDate.of(2026, 2, 28),
            Dates.dayInPeriod("2026-02", LocalDate.of(2026, 1, 31)),
        )
    }

    @Test
    fun `a month chosen either side of this one stays inside itself`() {
        // The one invariant that matters: whatever day comes back, it is in the
        // month the screen was pointing at — otherwise the row lands outside
        // the ledger that opens next.
        listOf("2024-01", "2026-09", "2026-12", "2027-03").forEach { period ->
            val date = Dates.dayInPeriod(period, LocalDate.of(2026, 10, 31))
            assertEquals(period, Dates.periodOf(date))
        }
    }

    @Test
    fun `twelve month names, capitalised, one per cell`() {
        val names = Dates.monthNames()
        assertEquals(12, names.size)
        assertEquals(12, names.toSet().size)
        names.forEach { name ->
            assertEquals(name.first().uppercase(), name.take(1))
        }
    }
}

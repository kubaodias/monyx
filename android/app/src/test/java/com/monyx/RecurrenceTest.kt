package com.monyx

import com.monyx.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Repeating transactions are calendar arithmetic, and every real bug in them is
 * a date nobody thought about: the 31st in February, the 29th in a common year,
 * a rule that silently stops being the 31st for the rest of its life.
 *
 * None of that is visible in an emulator without waiting a month, so it is
 * pinned here instead.
 */
class RecurrenceTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun monthly(anchor: String, through: String, endsOn: String? = null) =
        Recurrence.occurrences(Recurrence.MONTHLY, d(anchor), endsOn?.let(::d), d(through))
            .map { it.toString() }

    // ------------------------------------------------------------- monthly

    @Test
    fun `a monthly rule fires on the anchor's day each month`() {
        assertEquals(
            listOf("2026-09-10", "2026-10-10", "2026-11-10"),
            monthly("2026-09-10", "2026-11-30"),
        )
    }

    @Test
    fun `the anchor itself is the first occurrence`() {
        assertEquals(listOf("2026-09-10"), monthly("2026-09-10", "2026-09-10"))
    }

    @Test
    fun `nothing is ever generated before the anchor`() {
        assertEquals(emptyList<String>(), monthly("2026-09-10", "2026-09-09"))
    }

    @Test
    fun `a rule on the 31st falls on the last day of a shorter month`() {
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"),
            monthly("2027-01-31", "2027-04-30"),
        )
    }

    @Test
    fun `February borrows the day and gives it straight back`() {
        // The bug this exists to catch: stepping a month on from the CLAMPED
        // date instead of from the anchor. 31 Jan clamps to 28 Feb, and adding a
        // month to that gives 28 March — the rule quietly becomes "the 28th"
        // forever after. Every occurrence is computed from the anchor for
        // exactly this reason.
        val dates = monthly("2027-01-31", "2027-12-31")
        assertEquals("2027-03-31", dates[2])
        assertEquals("2027-04-30", dates[3])
        assertEquals("2027-05-31", dates[4])
    }

    @Test
    fun `a leap February takes the 29th`() {
        assertEquals(
            listOf("2028-01-31", "2028-02-29", "2028-03-31"),
            monthly("2028-01-31", "2028-03-31"),
        )
    }

    @Test
    fun `a rule on the 30th clamps in February only`() {
        val dates = monthly("2027-01-30", "2027-04-30")
        assertEquals(listOf("2027-01-30", "2027-02-28", "2027-03-30", "2027-04-30"), dates)
    }

    // -------------------------------------------------------------- weekly

    @Test
    fun `a weekly rule is every seventh day, weekday preserved`() {
        val dates = Recurrence.occurrences(
            Recurrence.WEEKLY, d("2026-09-03"), null, d("2026-09-30"),
        )
        assertEquals(
            listOf("2026-09-03", "2026-09-10", "2026-09-17", "2026-09-24"),
            dates.map { it.toString() },
        )
        assertTrue(dates.all { it.dayOfWeek == d("2026-09-03").dayOfWeek })
    }

    @Test
    fun `a weekly rule crosses a month boundary without resetting`() {
        val dates = Recurrence.occurrences(
            Recurrence.WEEKLY, d("2026-09-28"), null, d("2026-10-12"),
        )
        assertEquals(
            listOf("2026-09-28", "2026-10-05", "2026-10-12"),
            dates.map { it.toString() },
        )
    }

    // -------------------------------------------------------------- yearly

    @Test
    fun `a yearly rule keeps its month and day`() {
        val dates = Recurrence.occurrences(
            Recurrence.YEARLY, d("2026-03-15"), null, d("2029-01-01"),
        )
        assertEquals(listOf("2026-03-15", "2027-03-15", "2028-03-15"), dates.map { it.toString() })
    }

    @Test
    fun `a yearly rule anchored on 29 February clamps in common years`() {
        val dates = Recurrence.occurrences(
            Recurrence.YEARLY, d("2028-02-29"), null, d("2032-12-31"),
        )
        assertEquals(
            listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"),
            dates.map { it.toString() },
        )
    }

    // ---------------------------------------------------------- the ending

    @Test
    fun `the end date is inclusive`() {
        assertEquals(
            listOf("2026-09-10", "2026-10-10"),
            monthly("2026-09-10", "2026-12-31", endsOn = "2026-10-10"),
        )
    }

    @Test
    fun `an end date one day early drops that occurrence`() {
        assertEquals(
            listOf("2026-09-10"),
            monthly("2026-09-10", "2026-12-31", endsOn = "2026-10-09"),
        )
    }

    @Test
    fun `an end before the anchor produces nothing at all`() {
        assertEquals(emptyList<String>(), monthly("2026-09-10", "2026-12-31", endsOn = "2026-08-01"))
    }

    // -------------------------------------------------------- what is next

    @Test
    fun `the next occurrence can be the anchor itself`() {
        assertEquals(
            d("2026-09-10"),
            Recurrence.nextOccurrence(Recurrence.MONTHLY, d("2026-09-10"), null, d("2026-09-09")),
        )
    }

    @Test
    fun `the next occurrence is strictly after the date asked about`() {
        assertEquals(
            d("2026-10-10"),
            Recurrence.nextOccurrence(Recurrence.MONTHLY, d("2026-09-10"), null, d("2026-09-10")),
        )
    }

    @Test
    fun `a finished rule has no next occurrence`() {
        assertNull(
            Recurrence.nextOccurrence(
                Recurrence.MONTHLY, d("2026-09-10"), d("2026-10-31"), d("2026-11-01"),
            ),
        )
    }

    // ---------------------------------------------------------- the blast radius

    @Test
    fun `an absurd anchor cannot enumerate forever`() {
        // A year typed as 202 instead of 2026 is a plausible slip, and without
        // the cap it would ask for two million days of weekly occurrences.
        val dates = Recurrence.occurrences(
            Recurrence.WEEKLY, d("0202-01-01"), null, d("2026-09-01"),
        )
        assertEquals(Recurrence.HARD_LIMIT, dates.size)
    }

    @Test
    fun `a frequency nobody defined generates nothing rather than guessing`() {
        assertEquals(
            emptyList<LocalDate>(),
            Recurrence.occurrences("daily", d("2026-09-01"), null, d("2026-12-31")),
        )
        assertNull(Recurrence.nextOccurrence("daily", d("2026-09-01"), null, d("2026-09-01")))
    }

    // ----------------------------------------------------------- identity

    @Test
    fun `an occurrence id is stable across calls`() {
        // The whole convergence argument rests on this: two phones computing
        // September's rent must mint the same id, or the household sees rent
        // twice and no error anywhere says why.
        assertEquals(
            Recurrence.occurrenceId("rule-1", d("2026-09-01")),
            Recurrence.occurrenceId("rule-1", d("2026-09-01")),
        )
    }

    @Test
    fun `each date and each rule gets its own id`() {
        assertNotEquals(
            Recurrence.occurrenceId("rule-1", d("2026-09-01")),
            Recurrence.occurrenceId("rule-1", d("2026-10-01")),
        )
        assertNotEquals(
            Recurrence.occurrenceId("rule-1", d("2026-09-01")),
            Recurrence.occurrenceId("rule-2", d("2026-09-01")),
        )
    }

    @Test
    fun `an occurrence id is shaped like every other id the server accepts`() {
        // ID_RE on the server is [A-Za-z0-9_-]{1,64}. A UUID passes; anything
        // that did not would be rejected row by row, at sync time, on a phone.
        val id = Recurrence.occurrenceId("rule-1", d("2026-09-01"))
        assertTrue(id.matches(Regex("^[A-Za-z0-9_-]{1,64}$")))
    }

    @Test
    fun `the timestamp of an occurrence is a pure function of its date`() {
        // Not System.currentTimeMillis(): two phones agreeing on the id but not
        // on the contents would overwrite each other's row on every sync.
        assertEquals(
            Recurrence.occurredAt(d("2026-09-01")),
            Recurrence.occurredAt(d("2026-09-01")),
        )
        assertNotEquals(
            Recurrence.occurredAt(d("2026-09-01")),
            Recurrence.occurredAt(d("2026-09-02")),
        )
    }

    @Test
    fun `an occurrence lands on its own local date, not a neighbouring one`() {
        // Midday in Europe/Warsaw, so no timezone arithmetic downstream can push
        // the row into the day before or after — the same reason a back-dated
        // manual expense is stamped at midday.
        val date = d("2026-09-01")
        assertEquals(date.toString(), com.monyx.data.Dates.localDate(Recurrence.occurredAt(date)))
    }
}

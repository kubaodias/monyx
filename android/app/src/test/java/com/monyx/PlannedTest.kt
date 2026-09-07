package com.monyx

import com.monyx.data.Planned
import com.monyx.data.Recurrence
import com.monyx.data.RecurringRuleListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The projection behind the "Planned" chip on the history tab.
 *
 * Everything worth getting wrong here is invisible in an emulator without
 * waiting for a month boundary: whether today's occurrence is double-counted
 * against the real row that already exists, whether the horizon stops where it
 * says it does, whether the category filter still includes subcategories.
 */
class PlannedTest {

    private val today = LocalDate.parse("2026-09-07")

    private fun rule(
        id: String = "rule-rent",
        freq: String = Recurrence.MONTHLY,
        startsOn: String = "2026-01-05",
        endsOn: String? = null,
        amountMinor: Long = 250000,
        categoryId: String? = "cat-home",
        categoryColorKey: String? = "cat-home",
        categoryName: String? = "Dom i ogród",
        accountId: String = "acc-card",
        note: String? = "Czynsz",
    ) = RecurringRuleListItem(
        id = id,
        kind = "expense",
        amountMinor = amountMinor,
        note = note,
        freq = freq,
        startsOn = startsOn,
        endsOn = endsOn,
        categoryId = categoryId,
        accountId = accountId,
        categoryName = categoryName,
        categoryIcon = "home",
        categoryColor = "brown",
        categoryColorKey = categoryColorKey,
        accountName = "Karta",
        generatedCount = 8,
        pending = 0,
        rejected = 0,
    )

    private fun days(period: String, vararg rules: RecurringRuleListItem) =
        Planned.forPeriod(rules.toList(), period, today).map { it.occurredOn }

    // ------------------------------------------------------------- horizon

    @Test
    fun `this month and the next one can be projected`() {
        assertTrue(Planned.isAvailable("2026-09", today))
        assertTrue(Planned.isAvailable("2026-10", today))
    }

    @Test
    fun `the month after next cannot`() {
        assertFalse(Planned.isAvailable("2026-11", today))
    }

    @Test
    fun `a past month cannot — nothing in it is planned`() {
        assertFalse(Planned.isAvailable("2026-08", today))
        assertEquals(emptyList<String>(), days("2026-08", rule()))
    }

    @Test
    fun `the horizon holds across a year boundary`() {
        val newYearsEve = LocalDate.parse("2026-12-31")
        assertTrue(Planned.isAvailable("2027-01", newYearsEve))
        assertFalse(Planned.isAvailable("2027-02", newYearsEve))
    }

    // ----------------------------------------------------------- occurrences

    @Test
    fun `only the part of this month that has not happened yet`() {
        // Anchored on the 5th, and today is the 7th: September's is already a
        // real row, so projecting it would show the rent twice.
        assertEquals(emptyList<String>(), days("2026-09", rule(startsOn = "2026-01-05")))
        assertEquals(listOf("2026-10-05"), days("2026-10", rule(startsOn = "2026-01-05")))
    }

    @Test
    fun `an occurrence later this month is projected`() {
        assertEquals(listOf("2026-09-20"), days("2026-09", rule(startsOn = "2026-01-20")))
    }

    @Test
    fun `today itself is never projected — materialisation has already written it`() {
        assertEquals(emptyList<String>(), days("2026-09", rule(startsOn = "2026-01-07")))
    }

    @Test
    fun `a weekly rule fills the rest of the month and no further`() {
        assertEquals(
            listOf("2026-09-11", "2026-09-18", "2026-09-25"),
            days("2026-09", rule(freq = Recurrence.WEEKLY, startsOn = "2026-09-04")).sorted(),
        )
    }

    @Test
    fun `a rule that has ended stops`() {
        assertEquals(
            emptyList<String>(),
            days("2026-10", rule(startsOn = "2026-01-05", endsOn = "2026-09-30")),
        )
    }

    @Test
    fun `rows come back newest first, like the real ones`() {
        val rows = Planned.forPeriod(
            listOf(rule(id = "a", freq = Recurrence.WEEKLY, startsOn = "2026-09-04")),
            "2026-09",
            today,
        )
        assertEquals(listOf("2026-09-25", "2026-09-18", "2026-09-11"), rows.map { it.occurredOn })
    }

    // --------------------------------------------------------------- filters

    @Test
    fun `the account filter applies`() {
        val rules = listOf(
            rule(id = "a", startsOn = "2026-01-20", accountId = "acc-card"),
            rule(id = "b", startsOn = "2026-01-21", accountId = "acc-cash"),
        )
        assertEquals(
            listOf("2026-09-20"),
            Planned.forPeriod(rules, "2026-09", today, accountId = "acc-card").map { it.occurredOn },
        )
    }

    @Test
    fun `picking a parent category still includes its subcategories`() {
        // What the chip offers is roots only, and the SQL behind the real rows
        // matches `t.categoryId = :id OR c.parentId = :id`. A rule filed under
        // "Dom i ogród > Remonty" has to come with it.
        val child = rule(
            id = "child",
            startsOn = "2026-01-20",
            categoryId = "cat-repairs",
            categoryColorKey = "cat-home",
        )
        assertEquals(
            listOf("2026-09-20"),
            Planned.forPeriod(listOf(child), "2026-09", today, categoryId = "cat-home")
                .map { it.occurredOn },
        )
    }

    @Test
    fun `an unrelated category is left out`() {
        val other = rule(id = "o", startsOn = "2026-01-20", categoryId = "cat-food", categoryColorKey = "cat-food")
        assertEquals(
            emptyList<String>(),
            Planned.forPeriod(listOf(other), "2026-09", today, categoryId = "cat-home")
                .map { it.occurredOn },
        )
    }

    @Test
    fun `the search matches the note and the category name, ignoring case`() {
        val r = rule(startsOn = "2026-01-20", note = "Czynsz", categoryName = "Dom i ogród")
        assertEquals(1, Planned.forPeriod(listOf(r), "2026-09", today, query = "czynsz").size)
        assertEquals(1, Planned.forPeriod(listOf(r), "2026-09", today, query = "OGRÓD").size)
        assertEquals(0, Planned.forPeriod(listOf(r), "2026-09", today, query = "paliwo").size)
    }

    // ------------------------------------------------------------- the rows

    @Test
    fun `a projected row carries the id the real one will be given`() {
        val row = Planned.forPeriod(listOf(rule(startsOn = "2026-01-20")), "2026-09", today).single()
        assertEquals(Recurrence.occurrenceId("rule-rent", LocalDate.parse("2026-09-20")), row.id)
        assertEquals(Recurrence.occurredAt(LocalDate.parse("2026-09-20")), row.occurredAt)
    }

    @Test
    fun `a projected row is neither pending nor rejected — it was never written`() {
        val row = Planned.forPeriod(listOf(rule(startsOn = "2026-01-20")), "2026-09", today).single()
        assertEquals(0, row.pending)
        assertEquals(0, row.rejected)
        assertEquals("rule-rent", row.recurringRuleId)
        assertEquals(250000L, row.amountMinor)
    }

    @Test
    fun `a rule with an unparsable anchor is skipped, not thrown`() {
        assertEquals(emptyList<String>(), days("2026-10", rule(startsOn = "not a date")))
    }
}

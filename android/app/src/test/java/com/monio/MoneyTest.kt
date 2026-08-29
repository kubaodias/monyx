package com.monio

import com.monio.data.Dates
import com.monio.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Formatting follows the language the interface is set to, so every assertion
 * here names the locale it is making a claim about. Nothing may depend on the
 * machine's default: that is exactly the bug these tests exist to catch.
 */
private inline fun <T> withLocale(tag: String, block: () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(tag))
    try {
        return block()
    } finally {
        Locale.setDefault(previous)
    }
}

/** Polish groups with U+00A0; flattened so the expectation stays readable. */
private fun flat(s: String) = s.replace(' ', ' ').replace(' ', ' ')

class MoneyTest {

    @Test
    fun `Polish formats with a comma decimal and a space group`() = withLocale("pl-PL") {
        assertEquals("45,99", flat(Money.format(4599)))
        assertEquals("0,05", flat(Money.format(5)))
        assertEquals("1 324,00", flat(Money.format(132400)))
    }

    @Test
    fun `English formats with a full stop decimal and a comma group`() = withLocale("en-GB") {
        assertEquals("45.99", flat(Money.format(4599)))
        assertEquals("1,324.00", flat(Money.format(132400)))
    }

    /**
     * The formatter is cached, and the cache key is the locale. Switching
     * language inside a live process must not keep printing the old one — which
     * is precisely what a formatter built once at class-init would do.
     */
    @Test
    fun `changing locale changes the very next format call`() {
        val polish = withLocale("pl-PL") { Money.format(132400) }
        val english = withLocale("en-GB") { Money.format(132400) }
        assertNotEquals(flat(polish), flat(english))
        assertEquals("1 324,00", flat(polish))
        assertEquals("1,324.00", flat(english))
    }

    /** The household banks in złoty whatever it reads in — currency is not language. */
    @Test
    fun `the zloty sign carries its diacritic in every language`() {
        assertTrue(withLocale("pl-PL") { Money.formatWithCurrency(132400) }.endsWith("zł"))
        assertTrue(withLocale("en-GB") { Money.formatWithCurrency(132400) }.endsWith("zł"))
    }

    @Test
    fun `signs come from the kind, never from a negative amount`() = withLocale("pl-PL") {
        assertEquals("-45,99", flat(Money.formatSigned(4599, "expense")))
        assertEquals("+45,99", flat(Money.formatSigned(4599, "income")))
        // A transfer moves money, it does not spend it (§6) — so it is unsigned.
        assertEquals("45,99", flat(Money.formatSigned(4599, "transfer")))
    }

    @Test
    fun `Polish parsing accepts both separators and never loses grosze`() = withLocale("pl-PL") {
        assertEquals(4599L, Money.parseToMinor("45,99"))
        assertEquals(4599L, Money.parseToMinor("45.99"))
        assertEquals(150000L, Money.parseToMinor("1 500,00"))
        assertEquals(150000L, Money.parseToMinor("1 500,00"))
        assertEquals(0L, Money.parseToMinor(""))
    }

    /**
     * English is where the two separators swap roles, and where a naive parser
     * silently turns 1,234 into 1.234 — a hundredfold error in a money field.
     */
    @Test
    fun `English parsing tells a grouping comma from a decimal one`() = withLocale("en-GB") {
        assertEquals(4599L, Money.parseToMinor("45.99"))
        assertEquals(4599L, Money.parseToMinor("45,99"))
        assertEquals(123456L, Money.parseToMinor("1,234.56"))
        assertEquals(123400L, Money.parseToMinor("1,234"))
        assertEquals(150000L, Money.parseToMinor("1500"))
    }
}

class DatesTest {

    @Test
    fun `a period is the first seven characters of a local date`() {
        assertEquals("2026-08", Dates.periodOfDateString("2026-08-15"))
    }

    @Test
    fun `shifting a period crosses the year boundary correctly`() {
        assertEquals("2027-01", Dates.shiftPeriod("2026-12", 1))
        assertEquals("2026-12", Dates.shiftPeriod("2027-01", -1))
    }

    /**
     * Months are bucketed on a LOCAL date (§6). An expense entered at 01:30 on
     * 1 September in Warsaw must fall in September, not August — this is the
     * whole reason occurred_on exists as a separate column.
     */
    @Test
    fun `a Warsaw local date does not fall back into the previous month`() {
        // 2026-08-31T23:30Z is already 2026-09-01 01:30 in Warsaw (UTC+2).
        val instantMs = java.time.Instant.parse("2026-08-31T23:30:00Z").toEpochMilli()
        assertEquals("2026-09-01", Dates.localDate(instantMs))
        assertEquals("2026-09", Dates.periodOfDateString(Dates.localDate(instantMs)))
    }

    /** The zone is the household's and does not move; only the wording does. */
    @Test
    fun `the Warsaw zone holds whatever the language is`() {
        val instantMs = java.time.Instant.parse("2026-08-31T23:30:00Z").toEpochMilli()
        assertEquals("2026-09-01", withLocale("en-GB") { Dates.localDate(instantMs) })
        assertEquals("2026-09-01", withLocale("pl-PL") { Dates.localDate(instantMs) })
    }

    @Test
    fun `month and day labels follow the interface language`() {
        assertEquals("August 2026", withLocale("en-GB") { Dates.monthLabel("2026-08") })
        assertEquals("Sierpień 2026", withLocale("pl-PL") { Dates.monthLabel("2026-08") })
        assertEquals("15 August", withLocale("en-GB") { Dates.dayLabel("2026-08-15") })
        assertEquals("15 sierpnia", withLocale("pl-PL") { Dates.dayLabel("2026-08-15") })
    }
}

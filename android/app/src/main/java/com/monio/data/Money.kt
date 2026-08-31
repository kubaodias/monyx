package com.monio.data

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Money renders through NumberFormat in whatever locale the interface is set
 * to: 1 324,00 in Polish, 1,324.00 in English. What does NOT move with the
 * language is the currency — the household banks in złoty whichever language
 * it reads in, so "zł" is a symbol, not a translated word.
 *
 * Formatters are cached against the locale that built them rather than held in
 * a val, because AppCompatDelegate.setApplicationLocales changes the default
 * locale in a live process: a formatter built at class-init would keep printing
 * the old language until the app was force-stopped.
 */
object Money {
    /** Not translated. See values/strings.xml, currency_suffix. */
    const val CURRENCY = "zł"

    private var cachedLocale: Locale? = null
    private var cachedFormat: NumberFormat? = null

    private fun plain(): NumberFormat {
        val locale = Locale.getDefault()
        cachedFormat?.let { if (cachedLocale == locale) return it }
        val fresh = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        cachedLocale = locale
        cachedFormat = fresh
        return fresh
    }

    /** "1 324,00" — no currency symbol, for compact rows. */
    fun format(minor: Long): String = synchronized(this) { plain().format(minor / 100.0) }

    /** "1 324,00 zł" — the full form. */
    fun formatWithCurrency(minor: Long): String = "${format(minor)} $CURRENCY"

    /** Signed for a ledger row: "-45,99" for an expense. */
    fun formatSigned(minor: Long, kind: String): String = when (kind) {
        "income" -> "+${format(minor)}"
        "expense" -> "-${format(minor)}"
        else -> format(minor)
    }

    /**
     * Parse a typed amount into minor units.
     *
     * The keypad cannot produce a surprise here, but the account-balance and
     * budget-limit fields are ordinary TextFields, so this has to cope with
     * whatever a person types in either language: "45,99", "45.99", "1 500,00",
     * "1,234.56". Both separators are accepted, and which one is the decimal
     * point is decided rather than assumed:
     *
     *  - both kinds present  -> the LAST one is the decimal point
     *  - one kind, repeated  -> it is grouping ("1.234.567")
     *  - one kind, once      -> decimal, unless it is this locale's grouping
     *                           separator sitting in front of exactly three
     *                           digits, which is "1,234" in English
     */
    fun parseToMinor(text: String): Long {
        val grouping = DecimalFormatSymbols.getInstance(Locale.getDefault()).groupingSeparator
        val stripped = text.filter { it.isDigit() || it == ',' || it == '.' }
        val commas = stripped.count { it == ',' }
        val dots = stripped.count { it == '.' }

        val decimal: Char? = when {
            commas > 0 && dots > 0 ->
                if (stripped.lastIndexOf(',') > stripped.lastIndexOf('.')) ',' else '.'
            commas > 1 || dots > 1 -> null
            commas == 1 || dots == 1 -> {
                val sep = if (commas == 1) ',' else '.'
                val trailing = stripped.length - stripped.indexOf(sep) - 1
                if (sep == grouping && trailing == 3) null else sep
            }
            else -> null
        }

        val normalised = buildString {
            for (c in stripped) when {
                c.isDigit() -> append(c)
                c == decimal -> append('.')
            }
        }
        val value = normalised.toDoubleOrNull() ?: return 0
        return Math.round(value * 100)
    }
}

/**
 * Months are bucketed on a LOCAL date. The client authors occurred_on, and
 * every monthly aggregate groups on substr(occurred_on, 1, 7). Without it an
 * expense entered at 01:30 on 1 September in Warsaw falls into August for the
 * server and September for the phone.
 *
 * The zone is fixed to Warsaw and does NOT follow the interface language: it is
 * where the household lives, not what it reads. Only the wording of a label
 * moves with the locale.
 */
object Dates {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val PERIOD = DateTimeFormatter.ofPattern("yyyy-MM")

    private var cachedLocale: Locale? = null
    private var cachedDay: DateTimeFormatter? = null
    private var cachedMonth: DateTimeFormatter? = null

    private fun formatters(): Pair<DateTimeFormatter, DateTimeFormatter> = synchronized(this) {
        val locale = Locale.getDefault()
        val day = cachedDay
        val month = cachedMonth
        if (day != null && month != null && cachedLocale == locale) return day to month
        val freshDay = DateTimeFormatter.ofPattern("d MMMM", locale)
        val freshMonth = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        cachedLocale = locale
        cachedDay = freshDay
        cachedMonth = freshMonth
        return freshDay to freshMonth
    }

    fun today(): LocalDate = LocalDate.now(ZONE)

    fun localDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate().format(ISO)

    fun currentPeriod(): String = today().format(PERIOD)

    fun periodOf(date: LocalDate): String = date.format(PERIOD)

    fun periodOfDateString(occurredOn: String): String = occurredOn.take(7)

    fun dayLabel(occurredOn: String): String =
        LocalDate.parse(occurredOn).format(formatters().first)

    /** Polish month names are lowercase; a heading wants them capitalised. */
    fun monthLabel(period: String): String =
        LocalDate.parse("$period-01").format(formatters().second)
            .replaceFirstChar { it.uppercase() }

    fun shiftPeriod(period: String, months: Long): String =
        LocalDate.parse("$period-01").plusMonths(months).format(PERIOD)

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()
}

package com.monyx.data

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /**
     * "1 324" — whole złoty, no currency, for a chart axis.
     *
     * The grosze are noise on a gridline: four axis labels carrying ",00" are
     * eight characters of nothing, and they widen the gutter that the chart
     * itself has to fit beside.
     */
    fun formatWhole(minor: Long): String = synchronized(this) { whole().format(minor / 100) }

    private var cachedWholeLocale: Locale? = null
    private var cachedWhole: NumberFormat? = null

    private fun whole(): NumberFormat {
        val locale = Locale.getDefault()
        cachedWhole?.let { if (cachedWholeLocale == locale) return it }
        val fresh = NumberFormat.getIntegerInstance(locale)
        cachedWholeLocale = locale
        cachedWhole = fresh
        return fresh
    }

    /**
     * "4000", or "4000,5" — what a text field should be seeded with.
     *
     * [format] is for reading and this is for editing, and the two want
     * opposite things. Grouping is the problem: a field is re-parsed on every
     * keystroke, and the space in "4 000,00" is a character somebody has to
     * work around to turn four thousand into five. The trailing ",00" is the
     * same nuisance from the other end — nobody typing a budget wants to clear
     * two zeros first.
     *
     * The decimal separator stays the locale's, because that is the key the
     * keyboard offers. [parseToMinor] takes either one back, so a comma typed
     * into an English build still reads as a decimal point.
     */
    fun formatForEditing(minor: Long): String {
        val separator = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
        val sign = if (minor < 0) "-" else ""
        val absolute = if (minor < 0) -minor else minor
        val grosze = (absolute % 100).toInt()
        val fraction = when {
            grosze == 0 -> ""
            // 4000,50 is "4000,5": the second zero says nothing a person needs.
            grosze % 10 == 0 -> "$separator${grosze / 10}"
            else -> separator + grosze.toString().padStart(2, '0')
        }
        return "$sign${absolute / 100}$fraction"
    }

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
     *
     * A leading minus is honoured, because an account balance can be one: a
     * credit card that has been used is money owed, and typing what the banking
     * app shows has to be allowed to produce it.
     */
    fun parseToMinor(text: String): Long {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val grouping = symbols.groupingSeparator
        // The locale's own minus as well as the ASCII one — a figure copied
        // from elsewhere can carry U+2212, and NumberFormat may have printed it.
        val negative = text.trimStart().firstOrNull()
            ?.let { it == '-' || it == '\u2212' || it == symbols.minusSign } == true
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
        val minor = Math.round(value * 100)
        return if (negative) -minor else minor
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

    private var shortLocale: Locale? = null
    private var cachedShortDay: DateTimeFormatter? = null

    /**
     * "30 Sep", not "30 September".
     *
     * For rows where the date shares a line with other text. The full month name
     * wrapped "Next on 30 / September" across two lines in the repeating list,
     * which breaks the phrase in the middle of itself.
     */
    fun shortDayLabel(occurredOn: String): String {
        val formatter = synchronized(this) {
            val locale = Locale.getDefault()
            cachedShortDay?.takeIf { shortLocale == locale } ?: run {
                DateTimeFormatter.ofPattern("d MMM", locale).also {
                    shortLocale = locale
                    cachedShortDay = it
                }
            }
        }
        return LocalDate.parse(occurredOn).format(formatter)
    }

    private var timeLocale: Locale? = null
    private var cachedTime: DateTimeFormatter? = null

    /**
     * "18 września, 22:31" — a day with the time of day on it.
     *
     * For the sync row, which needs both. "Last sync: 18 September", read on
     * the 18th, tells you nothing you had not already assumed — and the whole
     * reason to look at it is to find out whether it ran a minute ago or this
     * morning, which is exactly the part the date alone leaves out.
     *
     * The time format is the locale's, not a hard-coded 24 hours: the same
     * build reads as 22:31 in Polish and 10:31 pm in English.
     */
    fun dayTimeLabel(epochMs: Long): String {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(ZONE)
        val formatter = synchronized(this) {
            val locale = Locale.getDefault()
            cachedTime?.takeIf { timeLocale == locale } ?: run {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).also {
                    timeLocale = locale
                    cachedTime = it
                }
            }
        }
        return "${dayLabel(zoned.toLocalDate().format(ISO))}, ${zoned.format(formatter)}"
    }

    /** Polish month names are lowercase; a heading wants them capitalised. */
    fun monthLabel(period: String): String =
        LocalDate.parse("$period-01").format(formatters().second)
            .replaceFirstChar { it.uppercase() }

    fun shiftPeriod(period: String, months: Long): String =
        LocalDate.parse("$period-01").plusMonths(months).format(PERIOD)

    fun yearOf(period: String): Int = period.take(4).toInt()

    /** The 1-based month of a period, so a grid can mark the one that is on. */
    fun monthOf(period: String): Int = period.takeLast(2).toInt()

    fun period(year: Int, month: Int): String = "%04d-%02d".format(year, month)

    /**
     * Twelve short month names in the interface language, for the picker grid.
     *
     * Standalone form (LLL, not MMM) because a grid cell is not part of a date:
     * Polish inflects the genitive "stycznia" when a day precedes it, and a
     * lone cell reading "stycznia" is a month name in the wrong case.
     */
    fun monthNames(): List<String> = synchronized(this) {
        val locale = Locale.getDefault()
        cachedMonthNames?.takeIf { monthNamesLocale == locale } ?: run {
            val formatter = DateTimeFormatter.ofPattern("LLL", locale)
            (1..12).map { month ->
                LocalDate.of(2000, month, 1).format(formatter)
                    .replaceFirstChar { it.uppercase() }
            }.also {
                monthNamesLocale = locale
                cachedMonthNames = it
            }
        }
    }

    private var monthNamesLocale: Locale? = null
    private var cachedMonthNames: List<String>? = null

    fun iso(date: LocalDate): String = date.format(ISO)

    fun firstDayOf(period: String): LocalDate = LocalDate.parse("$period-01")

    fun lastDayOf(period: String): LocalDate =
        LocalDate.parse("$period-01").plusMonths(1).minusDays(1)

    /**
     * The last day a chart may honestly plot for [period]: today while the month
     * is still running, its final day once it is over.
     *
     * Deliberately NOT min(today, month end). A month in the future gets its own
     * end, so a window anchored to it stays inside the month the switcher is
     * pointing at — an empty chart labelled with next month's dates is honest,
     * where silently showing the last thirty days of the present is not.
     */
    fun windowEnd(period: String, today: LocalDate = today()): LocalDate =
        if (periodOf(today) == period) today else lastDayOf(period)

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()
}

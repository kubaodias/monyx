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

    /** "1 324,00 zł" — the full form, in the reporting currency. */
    fun formatWithCurrency(minor: Long): String = "${format(minor)} $CURRENCY"

    /**
     * "1 240,00 €" — the full form for an account that holds something else.
     *
     * A separate function rather than a default argument on the one above, so
     * that every call site which prints a TOTAL keeps printing złoty and cannot
     * be quietly handed a currency. A total is always in the reporting
     * currency; only a single account's own figures are ever in another.
     */
    fun formatIn(minor: Long, currency: Currency): String =
        "${format(minor)} ${currency.suffix}"

    /** Signed for a ledger row: "-45,99" for an expense. */
    fun formatSigned(minor: Long, kind: String): String = when (kind) {
        "income" -> "+${format(minor)}"
        "expense" -> "-${format(minor)}"
        else -> format(minor)
    }

    /**
     * "-45,99 zł" — signed, with the unit on it.
     *
     * Every figure on the ledger carries its unit now, złoty included. The old
     * rule was that only the exceptions did: "zł" on five hundred rows to
     * disambiguate three is noise, and the month total above them already said
     * which unit it was in. That reads well on paper and it did not survive one
     * household holding a euro card — a day heading, a row and a filtered total
     * sitting in one column, two of them in złoty and one unmarked, is three
     * figures the eye has to attribute from memory. The owner asked for the unit
     * everywhere, and everywhere is the only version of this rule that cannot be
     * read wrong.
     *
     * @param currency the unit [minor] is in, defaulting to the reporting one.
     */
    fun formatSignedIn(minor: Long, kind: String, currency: Currency = Currency.PLN): String =
        "${formatSigned(minor, kind)} ${currency.suffix}"

    /**
     * One amount printed in two units: the figure to read, and the same money
     * in the other unit beside it.
     *
     * Which unit leads is the screen's question and not this type's — see the
     * two functions below, which disagree on purpose. [aside] is null when there
     * is only one unit to print, which is every figure in a household that
     * holds złoty only.
     */
    data class Figure(val main: String, val aside: String?)

    /**
     * An account on the Overview strip: its own money first, the złoty value in
     * brackets after it, on ONE line — "-15,00 € (-65,78 zł)".
     *
     * The account's own currency leads because a tile is that one account's
     * position, and "how many euro have I got" is the question somebody opens
     * the strip to answer. The złoty value stays beside it, because the strip is
     * also visibly what Bilans is the sum of. This is the reverse of the order
     * shipped in 0.23.1, which led with złoty on the grounds that the total is
     * what the strip adds up to — true of the total and not of the tile.
     *
     * One line either way: a strip where only the foreign tile is two lines tall
     * is a strip of unequal tiles, and the ragged one would be the odd account
     * rather than an important one.
     */
    fun accountStripFigure(balanceMinor: Long, currency: Currency, plnMinor: Long?): Figure =
        if (currency.isReporting) {
            Figure(formatWithCurrency(balanceMinor), null)
        } else {
            Figure(
                formatIn(balanceMinor, currency),
                // An em dash INSIDE the brackets when no rate has synced. The
                // account's own balance is the headline now, so a missing rate
                // no longer hides the money — but it still has to be visible
                // rather than silently absent, or the tile looks like a złoty
                // account.
                "(${plnMinor?.let { formatWithCurrency(it) } ?: "—"})",
            )
        }

    /**
     * A ledger row: the amount as entered, with the same money in the unit of
     * whatever else the row is being read against, underneath.
     *
     * The "other" unit is złoty for a foreign row — the reporting currency,
     * which is what the month total above it is in. For a złoty row sitting on a
     * foreign account it is the ACCOUNT's currency instead: 100 zł paid from a
     * euro card belongs to a list of euro rows and a euro balance, and "100,00"
     * among them says nothing about which of the two it is. That case prints
     * "-100,00 zł" over "-22,80 €" — złoty leading, because złoty is what
     * happened.
     *
     * A złoty row on a złoty account gets the unit too, and no second figure.
     * It used to get neither, on the rule that only the exceptions are marked —
     * see [formatSignedIn] for why that rule is gone. The second figure stays an
     * exception: there is nothing to convert it to.
     */
    fun ledgerRowFigure(
        amountMinor: Long,
        kind: String,
        currency: Currency,
        plnMinor: Long?,
        accountCurrency: Currency = Currency.PLN,
        accountMinor: Long? = null,
    ): Figure = when {
        !currency.isReporting -> Figure(
            formatSignedIn(amountMinor, kind, currency),
            // Null, not "—": the amount that happened is already on the line
            // above in the unit it happened in, so a missing rate costs the
            // reader nothing here.
            plnMinor?.let { formatSignedIn(it, kind) },
        )
        !accountCurrency.isReporting -> Figure(
            formatSignedIn(amountMinor, kind),
            accountMinor?.let { formatSignedIn(it, kind, accountCurrency) },
        )
        else -> Figure(formatSignedIn(amountMinor, kind), null)
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

    private var yearLocale: Locale? = null
    private var cachedYearDay: DateTimeFormatter? = null

    /**
     * "18 września 2026" — a date with its year on it.
     *
     * [dayLabel] leaves the year off, which is right for a ledger showing one
     * month: everything on screen is already in it. A changelog is the opposite
     * — it reaches back as far as the releases do, and "18 września" in a list
     * spanning years is a date you cannot place.
     */
    fun fullDayLabel(occurredOn: String): String {
        val formatter = synchronized(this) {
            val locale = Locale.getDefault()
            cachedYearDay?.takeIf { yearLocale == locale } ?: run {
                DateTimeFormatter.ofPattern("d MMMM yyyy", locale).also {
                    yearLocale = locale
                    cachedYearDay = it
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

    private var inMonthLocale: Locale? = null
    private var cachedInMonth: DateTimeFormatter? = null

    /**
     * "sierpnia 2026" — the month as it reads INSIDE a sentence.
     *
     * [monthLabel] is the standalone form ("Sierpień 2026"), which is what a
     * heading wants and what Polish gives for `LLLL`. Dropped into "Stan kont na
     * koniec …" it comes out as "na koniec Sierpień 2026", which is not a
     * sentence in Polish. `MMMM` is the format context and inflects — genitive
     * here — and in English the two forms are the same word, so nothing is lost
     * by using it wherever the month is part of a phrase.
     */
    fun monthInLabel(period: String): String {
        val formatter = synchronized(this) {
            val locale = Locale.getDefault()
            cachedInMonth?.takeIf { inMonthLocale == locale } ?: run {
                DateTimeFormatter.ofPattern("MMMM yyyy", locale).also {
                    inMonthLocale = locale
                    cachedInMonth = it
                }
            }
        }
        return LocalDate.parse("$period-01").format(formatter)
    }

    private var shortMonthLocale: Locale? = null
    private var cachedShortMonth: DateTimeFormatter? = null

    /**
     * "sie 2025" — the month where a full name would not fit.
     *
     * The year is in it on purpose. An account's span can cross one, and
     * "sie – wrz" for August 2025 to September 2026 is a thirteen-month history
     * printed as though it were six weeks.
     */
    fun shortMonthLabel(period: String): String {
        val formatter = synchronized(this) {
            val locale = Locale.getDefault()
            cachedShortMonth?.takeIf { shortMonthLocale == locale } ?: run {
                DateTimeFormatter.ofPattern("LLL yyyy", locale).also {
                    shortMonthLocale = locale
                    cachedShortMonth = it
                }
            }
        }
        return LocalDate.parse("$period-01").format(formatter)
    }

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

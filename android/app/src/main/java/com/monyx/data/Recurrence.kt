package com.monyx.data

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * When a repeating rule falls due, and what the transaction it produces is
 * called. Pure arithmetic over dates — no database, no clock — because every
 * interesting failure here is a calendar edge case and an emulator tests those
 * badly.
 *
 * THE ANCHOR IS THE SCHEDULE. A rule stores one date, `startsOn`, and every
 * occurrence is derived from it. There is no separate day-of-month or
 * day-of-week to disagree with it.
 */
object Recurrence {
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"

    /** Monthly first: rent, the phone bill and salary are why this exists. */
    val FREQUENCIES = listOf(MONTHLY, WEEKLY, YEARLY)

    /**
     * A rule can never produce more than this many dates in one calculation,
     * whatever it is asked. Not a schedule limit — a blast radius. An anchor
     * mistyped as the year 202 would otherwise enumerate two million days
     * before anything downstream got a chance to refuse them.
     */
    const val HARD_LIMIT = 1000

    fun isFrequency(value: String): Boolean = value in FREQUENCIES

    /**
     * Every occurrence from the anchor through [through], inclusive at both
     * ends, stopping early at [endsOn].
     *
     * Returns dates, not rows, and knows nothing about what already exists —
     * deciding which of these have already been written is the caller's job.
     */
    fun occurrences(
        freq: String,
        anchor: LocalDate,
        endsOn: LocalDate?,
        through: LocalDate,
    ): List<LocalDate> {
        if (!isFrequency(freq)) return emptyList()
        val last = if (endsOn != null && endsOn.isBefore(through)) endsOn else through
        if (last.isBefore(anchor)) return emptyList()

        val out = ArrayList<LocalDate>()
        var n = 0
        while (out.size < HARD_LIMIT) {
            val date = nth(freq, anchor, n)
            if (date.isAfter(last)) break
            out += date
            n += 1
        }
        return out
    }

    /**
     * The first occurrence strictly after [after], or null if the rule has
     * already finished. What the settings list shows as "Next on ...".
     */
    fun nextOccurrence(
        freq: String,
        anchor: LocalDate,
        endsOn: LocalDate?,
        after: LocalDate,
    ): LocalDate? {
        if (!isFrequency(freq)) return null
        for (n in 0 until HARD_LIMIT) {
            val date = nth(freq, anchor, n)
            if (date.isAfter(after)) {
                return if (endsOn != null && date.isAfter(endsOn)) null else date
            }
        }
        return null
    }

    /**
     * The n-th occurrence, always computed from the ANCHOR rather than from the
     * occurrence before it.
     *
     * That distinction is the whole bug. Stepping month by month from the
     * previous date walks a 31st rule off a cliff: 31 January clamps to 28
     * February, and adding a month to THAT gives 28 March. The rule quietly
     * becomes "the 28th" for the rest of its life. Deriving every occurrence
     * from the anchor's original day means February borrows the day and gives
     * it straight back.
     */
    private fun nth(freq: String, anchor: LocalDate, n: Int): LocalDate = when (freq) {
        WEEKLY -> anchor.plusWeeks(n.toLong())
        MONTHLY -> onDayOf(YearMonth.from(anchor).plusMonths(n.toLong()), anchor.dayOfMonth)
        else -> onDayOf(YearMonth.of(anchor.year + n, anchor.month), anchor.dayOfMonth)
    }

    /**
     * The anchor's day in a given month, clamped to the month's length.
     *
     * A rule anchored on the 29th, 30th or 31st means "the end of the month".
     * Skipping February — the other reading — is not what anyone setting up rent
     * intends, and a missing month is far harder to notice than an early one.
     */
    private fun onDayOf(month: YearMonth, day: Int): LocalDate =
        month.atDay(minOf(day, month.lengthOfMonth()))

    /**
     * The id of the transaction a rule produces on a given date — derived, not
     * random, for the same reason budget ids are (docs/decisions/0002).
     *
     * Two phones both materialising September's rent mint the SAME id, so the
     * server's upsert resolves them into one row instead of the household seeing
     * rent twice. It is also what lets materialisation be re-run safely: the
     * check is "does this id already exist", which needs no stored cursor and,
     * unlike one, does not resurrect an occurrence the user deliberately
     * deleted.
     */
    fun occurrenceId(ruleId: String, date: LocalDate): String =
        UUID.nameUUIDFromBytes("recurrence:$ruleId:$date".toByteArray(StandardCharsets.UTF_8))
            .toString()

    /**
     * The timestamp a generated transaction carries: midday on the occurrence,
     * in the household's zone.
     *
     * Deterministic on purpose. Every field of a generated row has to be a pure
     * function of (rule, date), because two phones computing the same id must
     * also agree on its contents — System.currentTimeMillis() here would have
     * them overwrite each other's row on every sync, burning a seq each time.
     * Midday, like a hand-entered back-dated expense, so that no timezone
     * arithmetic can push the row into a neighbouring day.
     */
    fun occurredAt(date: LocalDate): Long =
        Dates.startOfDayMillis(date) + 12 * 60 * 60 * 1000
}

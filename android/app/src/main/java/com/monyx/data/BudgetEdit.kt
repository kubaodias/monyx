package com.monyx.data

/**
 * What changing one limit writes to the budgets table.
 *
 * A budgets row holds from its period onward until a newer row supersedes it,
 * which made every edit retroactive to the rest of time: raising Żywność in
 * September because September had a wedding in it also raised it for October,
 * November and every month after, silently. Nobody asked for that, and there was
 * no way to say no to it.
 *
 * So a change now applies to the month you are looking at, and carrying it
 * forward is the thing you opt into. Neither is expressible in one row:
 *
 *  - **this month only** writes the new limit at P, and then re-states the OLD
 *    limit at P+1 so inheritance picks the old figure back up. Where there was
 *    no limit at all, P+1 gets a tombstone instead — inheritance has to be
 *    stopped, not handed a number that was never there.
 *  - **and the months after** writes the new limit at P and overwrites every
 *    later row with the same figure. Carrying forward is what the table does by
 *    itself; the later rows are the only thing that could contradict it.
 *
 * A month that already has a limit of its own is left alone by the first case.
 * Somebody set October deliberately, and "only this month" is a promise about
 * this month — it is not licence to overwrite a different one.
 *
 * Pure so it can be tested: the fiddly part is not the SQL, it is knowing which
 * months get written and what lands in them.
 */
object BudgetEdit {

    /** One row to write: [deleted] is a tombstone, which stops inheritance. */
    data class Write(val period: String, val limitMinor: Long, val deleted: Boolean)

    /**
     * @param period the month being edited.
     * @param limitMinor the new limit.
     * @param alsoFutureMonths what the checkbox in the dialog says.
     * @param inForceMinor the limit that applied at [period] BEFORE this edit,
     *   or null when the category had none. Read before anything is written, or
     *   it is the new figure being copied forward.
     * @param nextMonthHasRow whether the month after [period] already carries a
     *   row of its own — a limit or a tombstone, both of them deliberate.
     * @param laterPeriods the months after [period] that carry a live limit.
     */
    fun writes(
        period: String,
        limitMinor: Long,
        alsoFutureMonths: Boolean,
        inForceMinor: Long?,
        nextMonthHasRow: Boolean,
        laterPeriods: List<String> = emptyList(),
    ): List<Write> {
        val head = Write(period = period, limitMinor = limitMinor, deleted = false)
        if (alsoFutureMonths) {
            return listOf(head) + laterPeriods.map { Write(it, limitMinor, deleted = false) }
        }
        if (nextMonthHasRow) return listOf(head)
        val next = Dates.shiftPeriod(period, 1)
        return listOf(
            head,
            Write(
                period = next,
                limitMinor = inForceMinor ?: 0L,
                deleted = inForceMinor == null,
            ),
        )
    }
}

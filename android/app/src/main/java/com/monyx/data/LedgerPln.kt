package com.monyx.data

import androidx.room.DatabaseView

/**
 * Every transaction, with its amount also expressed in złoty.
 *
 * Złoty is the reporting currency (ADR 0022), so every figure that SUMS across
 * accounts has to sum one unit. This view is where that conversion happens —
 * once, rather than at each of the ten aggregates that need it. Those read
 * [plnMinor] where they used to read `amountMinor`, and the rule stays in one
 * place where it can be checked.
 *
 * **Converted at the rate on the transaction's own date**, not today's. A
 * transaction is an event that happened on a day; the rate that day is a fact.
 * Revaluing history every morning would make last month's closed total a
 * different number each time it is looked at, which is exactly what the budget
 * carry-over work in 0.20.13 and 0.20.14 was about. A BALANCE is the other
 * question — a position now, valued at one rate — and is converted in
 * [MonyxDao.accountBalances] instead.
 *
 * The lookup walks back to the newest rate on or before the date, which is what
 * makes weekends work even though the server already carries them forward: a
 * gap from any cause resolves to the last known rate rather than to nothing.
 *
 * **[plnMinor] is null when no rate is known at all**, and nothing may read that
 * as zero. SUM skips nulls, so a currency with no rates yet degrades to exactly
 * the behaviour before conversion existed: the account is simply not counted.
 * That is the honest failure — a missing rate silently worth nothing would
 * understate a total with nothing on screen admitting it.
 *
 * It carries every column of `transactions` plus the converted amounts, so a
 * query can read this in place of the table and lose nothing — which is what the
 * ledger list does, to show each row in the unit it was entered in and still
 * total the month in złoty.
 *
 * Integer division by 1000000 matches `convertMinor` on the server, which
 * rounds half away from zero. SQLite's integer division truncates toward zero
 * instead, so the two can differ by one grosz on a single row. They are never
 * compared: the server converts only for its own budget alerts and the phone
 * converts everything it displays. See the note in budgets.ts.
 */
@DatabaseView(
    viewName = "ledger_pln",
    value = """
        SELECT t.id AS id,
               t.kind AS kind,
               t.amountMinor AS amountMinor,
               t.accountId AS accountId,
               t.transferAccountId AS transferAccountId,
               t.categoryId AS categoryId,
               t.note AS note,
               t.occurredAt AS occurredAt,
               t.occurredOn AS occurredOn,
               t.createdBy AS createdBy,
               t.source AS source,
               t.recurringRuleId AS recurringRuleId,
               t.deleted AS deleted,
               t.pending AS pending,
               t.rejected AS rejected,
               COALESCE(a.currency, 'PLN') AS currency,
               CASE WHEN COALESCE(a.currency, 'PLN') = 'PLN' THEN t.amountMinor
                    ELSE (t.amountMinor * (
                            SELECT r.rateMicro FROM fx_rates r
                             WHERE r.currency = a.currency
                               AND r.effectiveOn <= t.occurredOn
                             ORDER BY r.effectiveOn DESC LIMIT 1
                         )) / 1000000
               END AS plnMinor,
               CASE WHEN COALESCE(ta.currency, 'PLN') = 'PLN' THEN t.amountMinor
                    ELSE (t.amountMinor * (
                            SELECT r2.rateMicro FROM fx_rates r2
                             WHERE r2.currency = ta.currency
                               AND r2.effectiveOn <= t.occurredOn
                             ORDER BY r2.effectiveOn DESC LIMIT 1
                         )) / 1000000
               END AS transferPlnMinor
          FROM transactions t
          LEFT JOIN accounts a  ON a.id  = t.accountId
          LEFT JOIN accounts ta ON ta.id = t.transferAccountId
    """,
)
data class LedgerPln(
    val id: String,
    val kind: String,
    /** The amount as entered, in the account's own currency. */
    val amountMinor: Long,
    val accountId: String,
    val transferAccountId: String?,
    val categoryId: String?,
    val note: String?,
    val occurredAt: Long,
    val occurredOn: String,
    val createdBy: String,
    val source: String,
    val recurringRuleId: String?,
    val deleted: Int,
    val pending: Int,
    val rejected: Int,
    /** The account's currency, so a row can print the unit it was entered in. */
    val currency: String,
    /** [amountMinor] in grosze, or null when the rate is unknown. */
    val plnMinor: Long?,
    /**
     * The same for the RECEIVING side of a transfer, which may be an account in
     * a different currency again. A transfer out of a złoty account into a euro
     * one is one row whose two legs are in two units, and the running-balance
     * line sums each leg against the account it touches.
     */
    val transferPlnMinor: Long?,
)

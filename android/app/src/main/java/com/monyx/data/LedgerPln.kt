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
 * **Converted from the transaction's OWN currency**, not its account's. A euro
 * account can hold a złoty row and a złoty account a euro one; the amount that
 * happened is what it was entered as. The account's currency is only the unit of
 * its opening balance and the default a new entry starts in.
 *
 * Both legs of a transfer use the one amount and so the one converted figure:
 * a transfer is a single row with a single amount, and this app has never
 * modelled "100 zł left and 23 € arrived" as two figures. [transferPlnMinor] is
 * gone with the account-based conversion that justified it.
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
        SELECT x.id AS id,
               x.kind AS kind,
               x.amountMinor AS amountMinor,
               x.accountId AS accountId,
               x.transferAccountId AS transferAccountId,
               x.categoryId AS categoryId,
               x.note AS note,
               x.occurredAt AS occurredAt,
               x.occurredOn AS occurredOn,
               x.createdBy AS createdBy,
               x.source AS source,
               x.recurringRuleId AS recurringRuleId,
               x.deleted AS deleted,
               x.pending AS pending,
               x.rejected AS rejected,
               x.currency AS currency,
               x.plnMinor AS plnMinor,
               COALESCE(a.currency, 'PLN') AS accountCurrency,
               CASE
                   -- The ordinary row: it is already in its account's money.
                   WHEN COALESCE(a.currency, 'PLN') = x.currency THEN x.amountMinor
                   -- A foreign row on a złoty account: the złoty figure IS the
                   -- account's figure, already computed.
                   WHEN COALESCE(a.currency, 'PLN') = 'PLN' THEN x.plnMinor
                   -- A złoty (or third-currency) row on a foreign account, which
                   -- is the case this column exists for: back OUT of złoty at
                   -- the account's own rate on the row's date. Multiplying
                   -- before dividing keeps it in integers; 1e9 grosze times 1e6
                   -- is 1e15, well inside Int64.
                   ELSE (x.plnMinor * 1000000) / (
                           SELECT r.rateMicro FROM fx_rates r
                            WHERE r.currency = a.currency
                              AND r.effectiveOn <= x.occurredOn
                            ORDER BY r.effectiveOn DESC LIMIT 1
                       )
               END AS accountMinor
          FROM (
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
               COALESCE(t.currency, 'PLN') AS currency,
               CASE WHEN COALESCE(t.currency, 'PLN') = 'PLN' THEN t.amountMinor
                    ELSE (t.amountMinor * (
                            SELECT r.rateMicro FROM fx_rates r
                             WHERE r.currency = t.currency
                               AND r.effectiveOn <= t.occurredOn
                             ORDER BY r.effectiveOn DESC LIMIT 1
                         )) / 1000000
               END AS plnMinor
          FROM transactions t
          ) x
          LEFT JOIN accounts a ON a.id = x.accountId
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
    /** The row's own currency, which is the unit [amountMinor] is in. */
    val currency: String,
    /** [amountMinor] in grosze, or null when the rate is unknown. */
    val plnMinor: Long?,
    /**
     * The currency of the account the row sits on, which is NOT [currency] — see
     * the class comment. Carried so a row can be shown in both the unit it was
     * entered in and the unit of the account it is listed under.
     */
    val accountCurrency: String,
    /**
     * [amountMinor] expressed in [accountCurrency], or null when the rate is
     * unknown. Equal to [amountMinor] for the ordinary row whose currency is its
     * account's, and to [plnMinor] on a złoty account.
     *
     * Converted THROUGH złoty, because złoty is the only currency fx_rates is
     * keyed on: 100 zł on a euro account is 100 zł divided by the euro rate. A
     * cross-rate between two foreign currencies therefore carries both roundings
     * and is not expected to reconcile to the grosz with a bank's own figure.
     * Nothing sums this column — it exists to be printed on one row.
     */
    val accountMinor: Long?,
)

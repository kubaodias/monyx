package com.monyx.data

/**
 * The household's default account: the first one on the list.
 *
 * Two screens need to agree on this. The keypad starts a new transaction on it,
 * and the ledger marks any row that is NOT on it — and if those two disagree,
 * the ledger is marking rows as unusual that the app itself has just made.
 *
 * "First on the list" means first as the household sees the list, which is not
 * the same as first in the table. Settings and the account strip both group
 * before they sort: the accounts that count come first, then the ones held
 * outside the summary, then the finished ones. So the ones in the later groups
 * cannot be the default, however they happen to sort.
 *
 * That is a rule about meaning, not about layout. An account kept out of the
 * summary is money held somewhere else — a pension, a deposit nobody spends
 * from — and it is the last account a new expense should land on by accident. An
 * archived one is finished with; defaulting to it would write new rows into a
 * closed account.
 *
 * Sort order is the household's own, dragged in Settings, and [order] is written
 * as position, so once anything has been dragged there is nothing to tie-break.
 * Until then several accounts sit at 0 and the tie falls to the name — which is
 * how a savings account called "Oszczędności" came to be the default over the
 * current account called "Portfel", and put a pill on 1703 rows saying they were
 * not where the money lives.
 *
 * @param accounts ordered as the DAO returns them: `sortOrder, name`.
 */
fun defaultAccount(accounts: List<AccountEntity>): AccountEntity? =
    accounts.firstOrNull { it.archived == 0 && it.excludedFromSummary == 0 }
    // Every open account is held outside the summary — unusual, but a household
    // that has said so about all of them still has to be able to add a
    // transaction. First open one, rather than nothing at all.
        ?: accounts.firstOrNull { it.archived == 0 }

/** [defaultAccount]'s id, for the callers that only compare. */
fun defaultAccountId(accounts: List<AccountEntity>): String? = defaultAccount(accounts)?.id

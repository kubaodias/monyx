package com.monyx.data

/**
 * Which group an account sits in, wherever accounts are listed.
 *
 * Settings and the account strip both draw these as three blocks: the accounts
 * that count, then the ones held outside the summary, then the finished ones.
 * The table does not — `sortOrder, name` interleaves all three — so anything
 * that lists accounts and does NOT group them contradicts the list the
 * household set up.
 */
private fun listGroup(account: AccountEntity): Int = when {
    account.archived == 1 -> 2
    account.excludedFromSummary == 1 -> 1
    else -> 0
}

/**
 * The accounts in the order the household actually sees them.
 *
 * Grouped as above, and inside a group left exactly as the DAO returned them —
 * the sort is stable, so `sortOrder, name` still decides. Anything that offers
 * a list of accounts to pick from should go through here, or it offers a
 * different order from the one Settings shows and the one a person dragged.
 */
fun accountsInListOrder(accounts: List<AccountEntity>): List<AccountEntity> =
    accounts.sortedBy(::listGroup)

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
    // Literally the first open row of the list, once the list is in the order
    // the household sees. Where every open account is held outside the summary
    // — unusual, but they still have to be able to add a transaction — that
    // group is simply next, and its first row wins.
    accountsInListOrder(accounts).firstOrNull { it.archived == 0 }

/** [defaultAccount]'s id, for the callers that only compare. */
fun defaultAccountId(accounts: List<AccountEntity>): String? = defaultAccount(accounts)?.id

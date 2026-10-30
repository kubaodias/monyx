# 0023 — Archiving hides an account; it does not stop counting it

**Date:** 2026-10-30 · **Status:** accepted

## Context

An account carries two flags, orthogonal by design:

| Flag | Question it answers |
|---|---|
| `archived` | Is this account finished with? (lifecycle) |
| `excludedFromSummary` | Is this money the household's to spend? (ownership) |

`applyArchive` flipped both. Archiving an account also set
`excludedFromSummary = 1`, and un-archiving deliberately did not set it back.

The case that was being patched was real: a holiday account that ended in August
rode along invisibly in the budget's carry-over for weeks, and because the
Overview's strip only offers chips for open accounts, there was nothing on screen
to switch it off with.

Then the owner archived a conference account — World Summit AI, nine expenses,
169,17 € — and Bilans went **up** by 743,51 zł, while October's spending went
**down** by 740,21 zł. The question that followed is the whole of this decision:
"te wydatki poniosłem" — I did spend that money.

## What the data said

Every per-trip account in this household is an expense bucket that nobody ever
funded. Not one of them has an income row or an incoming transfer:

| Account | Rows | Balance |
|---|---|---|
| World Summit AI | 9 | −169,17 € (−743,51 zł) |
| Tatry 2026 | 13 | −1 097,45 zł |
| Wakacje 2026 | 78 | −17 900,00 zł |

So the balance of one of these is not a pot of money — it is what the trip cost,
taken from a card that no transfer names. Which makes the old rule wrong in the
direction nobody would guess: *excluding* such an account removes a hole, so
archiving a finished trip made the household look richer by exactly the price of
the trip. The 2026-09 holiday that started all this was not leftover money riding
along in the carry-over; it was a 17 900 zł hole doing it.

And the patch had outlived its bug on both halves. The carry-over is now narrowed
to the accounts the budget actually counts — the default one — so no finished
account can reach it (`BudgetViewModel.plan`), and the Overview deliberately
carries archived-but-counted accounts along with its filters (`archivedIds`).
What was left was a switch nobody had touched moving the household's total, under
a confirmation dialog whose first sentence read "nothing already recorded on it
changes".

## Decision

**Archiving hides, and only hides.** `applyArchive` touches `archived` alone. An
archived account is not offered when adding a transaction, has no chip on the
strip, cannot be picked in the ledger's filter, and sits in its own folded
section in Settings. Whether its money is part of the household's figures stays
the one switch that says so — *Uwzględniaj w podsumowaniu* — in the editor, where
it can be read and changed, including on an archived row.

**Un-archiving is now symmetric**, because nothing was taken away: archive and
restore is a round trip, which it was not before.

**The ledger hides a row on both flags.** `MonyxDao.transactions` used to drop
the rows of any archived account from the month list. It now drops them only when
the account is archived *and* held outside the summary. An archived account that
still counts has its spending inside the month's figures on the Overview, and a
figure that counts money the list below it refuses to show is the one shape every
total in this app is written to avoid. Hidden from the sums, hidden from the list;
counted, and the rows are there to add up.

**The confirmation says what it does not do.** When the account being archived
still counts and its balance is not zero, the dialog adds a second sentence
naming that balance in the account's own currency, and naming the switch that
would take it out. The old dialog claimed nothing would change while quietly
changing the household's total.

## Consequences

- The state the owner asked for — "nie chcę widzieć tego konta, ale chcę żeby te
  transakcje nie zniknęły" — is now what archiving plainly does.
- Accounts archived **before** this change keep `excludedFromSummary = 1`;
  nothing is migrated. That is deliberate: switching the three per-trip accounts
  back on would drop Bilans by about 19 700 zł, and whether those holes are real
  or already absorbed by a typed-in balance ([0017](0017-the-balance-you-type-is-the-one-you-have.md))
  is a question only the household can answer, one account at a time.
- An account that really holds money and is finished with should be taken to zero
  before it is archived — a transfer to wherever the money went. A pot with money
  left in it is not finished, and leaving it counted is then correct rather than
  convenient.
- The honest alternative for a trip, left to the household: a trip is a
  *category*, not an account. A per-trip account earns its keep when there is a
  real pot behind it — a prepaid card, an envelope of cash — and not when the
  spending comes off the everyday card, which is also why four of the World
  Summit AI purchases were booked straight onto Portfel.

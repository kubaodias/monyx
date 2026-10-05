# 0022 — Złoty is the reporting currency, and a rate has a date

**Date:** 2026-10-05 · **Status:** accepted

## Context

A household that banks in złoty also keeps money elsewhere: a Revolut balance in
euro, a card left over from a trip, savings held abroad. Until now every account
was forced to pretend its balance was in złoty, which made the figure on screen
wrong by whatever the rate happened to be — and wrong silently, because nothing
on the row said which unit it was in.

"No multiple currencies" was in the README's list of things deliberately absent.
It is being removed from that list, which is worth saying plainly: this is a
reversal, not an omission being filled in.

Two facts about the existing code shape the decision.

`amount_minor` is an integer count of hundredths throughout — grosze — and 14
SQL aggregates sum it across accounts. The moment two accounts hold different
units, every one of those sums is adding quantities that are not the same kind
of thing. There is no version of this feature that touches only the account row.

The budget carry-over reads **last month's end-of-month balance**. Two releases
(0.20.13, 0.20.14) went into making that figure mean what it says. Anything that
makes a closed month's total move afterwards undoes that work.

## Decision

**Złoty is the reporting currency, and it is not configurable.** Every total the
app prints is in złoty. An account's `currency` says what the rows of that one
account are denominated in; it is not a choice about what the app reports in.
Making the reporting currency configurable would mean every stored aggregate,
every alert and every chart axis carrying a unit, for a household that has one
answer and will not change it.

**A transaction converts at the rate on its own date. A balance converts at
today's.** A transaction is an event that happened on a day, and the rate that
day is a fact; a balance is a position now. The alternative — converting
everything at today's rate — makes September's closed total a different number
every morning, which is precisely the property the carry-over work was about.

**Rates are stored per date, with closed days carried forward explicitly.** NBP
publishes nothing on weekends and holidays; the API returns 404, verified on
Sunday 2026-10-04. So Saturday, Sunday and a holiday all get a row carrying the
previous publication, and `published_on` records which day's rate it actually
is. The alternative is a read-time query that walks backwards looking for a row,
which costs a scan per conversion and, worse, makes the answer depend on what
has been fetched rather than on the date asked about.

**The source is NBP table A.** The Polish central bank's own mid rates: PLN-base,
free, no key to rotate, no vendor to outlive, and the rate the household's own
bank statements reconcile against. A commercial aggregator would be more
currencies and one more secret to hold for a household that needs nine.

**Rates are integers scaled by 1e6.** NBP publishes four to six decimals (EUR
4.3745, HUF 0.011898). Money in this codebase is integers all the way down and a
float here would be the only one. Conversion is
`amount_minor * rate_micro / 1_000_000`, rounded half away from zero and
symmetrically for negatives, so a purchase and the refund that reverses it
cannot leave a grosz behind in a total.

**Nine currencies, all two-decimal.** PLN, EUR, USD, GBP, CHF, CZK, SEK, NOK,
DKK. Every one is two-decimal in ISO 4217, which is what lets `amount_minor`
keep meaning "hundredths of the unit" with no per-currency minor-unit table. A
rate table carrying JPY is refused rather than stored, so an account cannot be
denominated in one by accident. There is no kuna: Croatia adopted the euro on
1 January 2023.

**An unrecognised currency on a push is rejected, not defaulted.** This is
deliberately unlike `archived` and `excluded_from_summary`, which default to 0
when absent so that an older client is never stranded. Absent still means PLN,
for the same reason. But a *present and unknown* currency has no rates, would
convert to nothing, and would drop the account out of every total without a word
on screen. Refusing the row tells the client instead.

**`fx_rates` has no `household_id`.** A rate is a fact about the world, not about
a family, and there is nothing personal in the table. This needed a fifth named
exception in `db.ts` and it is the first one that is about the table rather than
about a household not yet being known.

## Consequences

The client database version bumps, which under `fallbackToDestructiveMigration`
wipes every phone and re-pulls. That is the documented strategy here — the sync
cursor lives in the same database, so a wipe resets it and the next sync
restores everything the server has. What is actually lost is anything a phone
had not pushed yet, normally nothing.

Conversion depends on a rate having been fetched. A rate that is missing is a
figure that cannot be computed, and that has to read as "not known yet" rather
than as zero — a missing rate silently worth nothing would understate a total,
which is the failure mode this whole decision is arranged to avoid.

The migrations must be applied **before** the function that names the new column
ships, or every account push fails on a column that does not exist. The two are
not independent deploys.

Ordering of the three changes is forced by correctness rather than convenience:
storage and rates first with nothing reading them; then the client, with
non-PLN accounts held out of every aggregate so no total can be wrong while only
half of it exists; then conversion, which replaces that exclusion.

## Addendum, 2026-10-05: where conversion runs

Written after the third change, because the first two were planned on a wrong
assumption worth recording.

**Conversion happens on the phone, not the server.** Every figure in this app is
computed locally in Room — there is no endpoint that returns a total, and the
Budget screen's header says so explicitly. So the rates have to be ON the
device, and `GET /rates` replicates them during sync. Planning the server half
first made it look as though the server would convert; it does so only for its
own budget alert, which is a second implementation of one sum.

**The rates endpoint refreshes before answering.** Nothing drives
`POST /cron/daily`, so rates written only by that cron would never exist.
Hanging the refresh off a request the phone already makes on every sync is what
makes this self-driving rather than blocked on infrastructure that was never set
up.

**NBP is read by range, not by day.** One request per 93 days. The single-date
endpoint made backfill unaffordable — 365 requests a year — which would have
left every transaction older than the feature unconvertible.

**A missing rate yields null and is skipped, not zeroed.** This was the open
question in the original decision and this is the answer: the account falls out
of the total, exactly as it did before conversion existed, and keeps showing its
own balance so the money is not hidden.

**Correction to the rounding above.** The Decision section says conversion rounds
half away from zero. That is true of `convertMinor` in TypeScript, which is used
by nothing that displays a figure. Every figure anyone sees is produced by SQL —
`ledger_pln` on the phone, the alert query on the server — and SQLite integer
division **truncates toward zero**. The two implementations that matter agree
with each other, which is the property worth having; they differ from
`convertMinor` by at most one grosz on a single row. Said plainly rather than
quietly left as written, because "rounds half away from zero" is the kind of
sentence someone later builds a reconciliation on.

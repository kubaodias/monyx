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

## Addendum, 2026-10-05: the currency belongs to the transaction

The original decision put the currency on the **account** and assumed every row
on an account was in that account's money. That is wrong in the ordinary case
this feature exists for: you pay 15 EUR with a złoty card, or buy something in
dollars from a euro account. The amount that happened is 15 EUR either way.

So `transactions.currency` (migration 0010) is now the authority for conversion,
and `accounts.currency` shrinks to two smaller jobs: the unit of the account's
opening balance, and the currency a new entry on that account starts in.

**0010 backfills from the account rather than taking the `'PLN'` default.** This
is a refactor of where the currency lives, not a change to anyone's figures, and
the test of that is that no total moves. Between 0.22.0 and 0.22.1 a row on a
euro account was already converted as euro; leaving those rows at the default
would have silently restated every one as złoty and moved balances and budgets
somebody had already reconciled.

**Switching a row's account does not restate its amount.** Moving a purchase from
the euro card to the złoty one says where the money came from, not that 15 euro
became 15 złoty. Picking a different account on the ADD screen does clear a
hand-set override, because the next thing typed is almost certainly in the new
account's currency and a stale override is expensive to not notice.

**A transfer still has one amount and one currency.** This app has never modelled
"100 zł left and 23 € arrived" as two figures, so both legs convert identically.
`transferPlnMinor` is gone with the account-based conversion that justified it.

**Repeating rules do not carry a currency yet.** Their occurrences take the
account's, which is exactly what every row did before this change, so nothing
regressed — but a euro subscription paid from a złoty card is wrong until
`recurring_rules` gets the same column.

## Addendum, 2026-10-05: both units, on every figure that has two

Three reports, one rule.

**Each screen leads with the figure it is about, and prints the other unit
beside it.** The Overview strip is what Bilans is the sum of, so a tile leads
with złoty and carries the account's own money in brackets: `-65,78 zł
(-15,00 €)`. A ledger row is an event, so it leads with the amount as it was
entered — the figure on the receipt — and puts the złoty value underneath. The
two orders disagree on purpose; what they share is that neither leaves the
reader to work the other figure out.

**A tile keeps it to one line.** The account's own balance used to take a second
line, which made exactly one tile in a strip of equal tiles taller than the
rest, and the ragged one was the odd account rather than an important one.

**Both rules live in `Money.accountStripFigure` and `Money.ledgerRowFigure`.**
Written out at the call sites, the ledger half of this silently never shipped:
0.22.1 claimed a foreign row carried its symbol, the test asserted a copy of the
rule rather than calling it, and the production change was missing from
`TransactionsScreen` for two releases with nothing failing. A formatting rule
stated in a composable is a rule no test can reach.

**The currency is changed where it is shown**: the symbol beside the amount, on
the keypad and in the edit sheet. It was a chip up beside the account, which put
the control two inches from the figure it describes and a question-and-answer
apart. A missing rate still shows an em dash on the tile, where the converted
figure is the headline, and shows nothing on a row, where the amount that
happened is already on the line above in the unit it happened in.

## Addendum, 2026-10-06: a tile leads with its own money, and złoty is not always the second figure

Two corrections to the addendum above, both from use.

**The Overview tile now leads with the account's own currency**:
`-15,00 € (-65,78 zł)`. The previous order was argued from the total — the strip
is what Bilans is the sum of — which is true of the total and not of the tile. A
tile is one account's position, and the question it is opened to answer is how
much is in that account. The złoty figure stays in brackets, so the strip is
still visibly what the total adds up to. A missing rate is now an em dash
*inside* the brackets rather than in place of the headline: the money is no
longer the thing that goes missing.

**A złoty row on a foreign account shows both units too, with złoty leading.**
This was the real gap: conversion was only ever offered in one direction, so
100 zł paid from a euro card sat in a list of euro rows as a bare "100,00" and
read as euro. The row now prints `-100,00 zł` over `-22,80 €` — złoty first,
because złoty is what happened.

That second figure is `ledger_pln.accountMinor`, and it is converted **through
złoty**, because złoty is the only base `fx_rates` is keyed on: the amount goes
to grosze at its own currency's rate, then out again at the account's. A euro row
on a dollar account therefore carries two roundings and is not expected to
reconcile to the cent against a bank's own cross rate. Nothing sums this column —
it is printed on one row, next to the figure it is a restatement of.

The view grew a `LEFT JOIN accounts` to get there, which is the first time
`ledger_pln` has needed anything outside `transactions`. The ten aggregates read
`plnMinor` and are untouched by it.

**A projected row takes its account's currency**, since that is the unit the
occurrence will be written in when the rule fires. Without it the one bare figure
in a list of euro rows would have been the repeating one — the gap below made
visible in a new place rather than fixed.

## Addendum, 2026-10-06: an account's balance is in the account's currency, and that is a sum

The bug this addendum exists for: a euro account showed `-100,00 €` and
`-438,55 zł` for a single 100 zł purchase, four times the money that was spent.

`accountBalances` summed the raw `amount_minor` of every row on the account and
then multiplied the total by the account's rate. That was correct while a row was
necessarily in its account's currency, and became wrong the moment a transaction
carried its own — which is the change made two addenda above. The feature that
made a złoty row possible on a euro account is the feature that broke the
balance, and nothing failed in between.

**An account's balance is in the account's currency, so every row has to be
converted INTO that currency before being added.** That is `ledger_pln`'s
`accountMinor`, and the balance is the one sum in this app that deliberately is
not in złoty. The opening balance is already in that currency and is not
converted, which is what makes the two addable.

**A transfer's two legs now carry different figures.** `transferAccountMinor` is
the amount in the DESTINATION account's currency. The addendum that removed
`transferPlnMinor` argued that one amount means one converted figure; that is
true in złoty and false in the units of two accounts, because 100 zł leaving a
euro card arrives in a dollar account as neither of those numbers.

**The złoty value of a balance is still taken at one rate**, so it can differ by
a grosz from the same rows converted individually: 100 zł from a euro card is
22,80 € in the account and 99,99 zł valued back at today's rate. Summing each
row's own złoty figure instead would report what the position COST rather than
what it is worth, and a euro savings account funded three years ago would be
valued at a 2023 rate forever. The grosz is the cheaper error.

**Two conversion bugs have now shipped because the rule lived in SQL where no
test could reach it** — the ledger row that never carried its unit, and this. So
`LedgerViewTest` takes the view out of the exported Room schema and the balance
query out of its own `@Query` annotation, and runs both through the `sqlite3`
binary. It fails rather than skips when sqlite3 is missing: there is no CI here,
and a test that quietly stops running is the failure mode that produced both bugs.

## Addendum, 2026-10-09: every figure carries its unit, and a phone need not be offered nine

Three changes, all from one report: a household with a euro card looked at a day
in the ledger whose heading was a euro-sized number with "zł" beside it.

**A day heading sums `plnMinor`.** It summed `amountMinor` — each row's own
currency — so a day with three euro purchases on it added cents to grosze and
printed the answer as złoty. This is the third figure in the app to have had
exactly this bug: the month aggregates (fixed when this decision was written),
the total under the search box (fixed in 0.23.x), and now the day heading, which
is the one place the rule was implemented in a composable rather than in SQL or
a view model. Anything that ADDS rows together converts first. There is no
remaining exception, and `dayTotalMinor` is a function with a test rather than a
`sumOf` inside a `@Composable`.

**Every ledger figure prints its unit, złoty included.** The old rule was to
mark only the exceptions: "zł" on five hundred rows to disambiguate three is
noise, and the month total above them already says which unit it is in. That
reads well and it did not survive one foreign card — a day heading, a row and a
filtered total in one column, two of them marked and one not, is a figure the
eye has to attribute from memory. The owner asked for the unit everywhere, and
everywhere is the only version of this rule that cannot be read wrong. The
second line stays the exception it was: it appears when there is another unit to
show the same money in, and a złoty row on a złoty account has none.

**A phone offers the currencies its household uses, not all nine.** `Currency`
is the list the app can handle and the server agrees with; `Currencies` is the
list one phone wants to be asked about, and they are different questions. EUR
and USD are shown by default, the other six are a switch each in Settings, and
three things are always offered whatever is chosen: złoty, anything an account
is denominated in, and whatever the form being drawn has currently selected. So
hiding changes what is OFFERED and never what is stored, shown or converted — a
krona row stays readable, and the account holding krona cannot be edited into
another currency by accident, because its row in Settings will not switch off.

It is a DataStore preference, unsynced, like the chart's hidden categories: it
is not a fact about the household's money, it is how one phone is set up.

## Addendum, 2026-10-09 (later the same day): a currency is data, and nine of them have rates

The list above lasted an afternoon. The owner's answer to "which of the nine do
you want offered" was "two — and let me type in the rest", which is a better
question than the one the switches answered: the set of currencies a household
might hold is not the set the app happens to know rates for.

So the two are now separate things.

**`Currency` is no longer an enum.** It is a code and the text to print after a
figure. `Currency.KNOWN` is the nine that have NBP rates — the same list
`CURRENCIES` carries in `rates.ts`, and still the only list that converts — and
anything else is a code the household typed into Settings with a symbol beside
it. What stayed closed is the arithmetic: every currency here is assumed to be a
two-decimal one, which is what lets `amount_minor` mean "hundredths of the unit"
everywhere, and that is the one thing a typed-in code can get wrong (JPY,
HUF-as-used). The README says so and Settings does not, because a dialog that
explains ISO 4217 minor units is a dialog nobody reads.

**An unrecognised code reads as itself, not as złoty.** `Currency.of("THB")` used
to answer PLN, which was defensible while the nine were the only possibilities —
a row from a newer client beats a crash — and is the opposite of safe now: a baht
row resolving to złoty would print "zł" after a baht figure *and* claim to be the
reporting currency, so the summary would count it. It now answers a currency
called THB with no rate, which is the truth.

**The server validates the shape of a code, not a list.** `isCurrencyCode` —
three capitals — replaces `isCurrency` in `schema.ts`. The old rejection existed
to stop an account silently dropping out of every total for want of a rate; what
changed is that the drop is no longer silent. A rate-less account shows its own
balance and is visibly not converted, exactly as a rated one does before its
first rate syncs. Refusing the row instead would mean an account that cannot be
pushed at all, which is a worse answer to the same problem. `isCurrency` stays,
for the places that mean "has a rate".

**The symbols are local, and that is a real cost.** A currency added on one
phone prints its code on the other until it is added there too. Syncing them
would mean a table, a migration and a push path for a preference — and the
figure is never wrong meanwhile, only terse. `MonyxApp` publishes the symbols
into `Currency` once per change, because a ledger row resolves its unit
synchronously and cannot wait on DataStore.

The switch list is gone with it. Two of its three lines of text were explaining
why a row could not be switched off; the list now simply has no remove button on
those rows, which says the same thing in no words. Nine switches was also nine
hypotheticals — the thing being chosen from was the app's capability, not the
household's money.

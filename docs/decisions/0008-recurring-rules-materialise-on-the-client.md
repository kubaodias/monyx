# 0008 — Recurring rules materialise on the client, with derived ids

**Date:** 2026-09-01 · **Status:** accepted

## Context

Rent, the phone bill, the loan payment and salary are the fixed shape of a
household's month. Retyping them by hand every month is what stops a budget from
being planned at all: the plan screen can ask "how much is there altogether"
while the known, predictable costs still have to be re-entered before the answer
means anything.

Two questions had to be settled before any of it could be written: **who turns a
rule into transactions**, and **how a rule states its schedule**.

## Decision

### The client materialises, not the server

`/cron/daily` already exists and runs once a day across every household, so
generating rows there was the obvious option. It was rejected.

The app is offline-first, and everything else in it honours that: a local write
returns immediately with `pending = 1`, and the network catches up later.
Server-side generation would invert this for one feature only — September's rent
would not exist on a phone until that phone next reached the network. A family
on holiday with no signal would open the app to a month with no rent in it.

So materialisation runs on the client, from two call sites: `SyncWorker` after
its pull, and app open. App open is the one that matters, because it is the only
one with no network constraint.

### Occurrence ids are derived, not random

Client-side generation on four phones invites four copies of every occurrence.
It does not, because the id of the transaction a rule produces on a date is
`UUIDv3("recurrence:$ruleId:$date")` — the trick already used for budget ids
(see [0002](0002-budget-ids-are-derived.md)). Two phones materialising the same
occurrence mint the *same* id, so the server's upsert resolves them into one row
instead of the household seeing rent twice.

Every other field of a generated row is a pure function of `(rule, date)` for
the same reason, including `occurred_at` (midday on the occurrence) and
`created_by` (the rule's author, not whichever phone ran the pass).
`System.currentTimeMillis()` anywhere in that row would have two phones
overwrite each other on every sync, burning a seq each time.

### No cursor column

The obvious bookkeeping — `last_run_on` on the rule, advanced as occurrences are
written — was not added. The check is instead "does a row with this id already
exist locally, in any state".

That is not merely simpler; it is more correct. A cursor cannot distinguish
"not yet generated" from "generated, then deliberately deleted", so a rule with
one would resurrect an occurrence the user had thrown away. The existence check
sees the tombstone and leaves it alone. It also needs no synced mutable state,
which would itself have been a write-conflict between two phones.

### The anchor is the schedule

A rule stores `starts_on` and nothing else about timing. Weekly is anchor + 7n,
monthly is the anchor's day-of-month, yearly is its month and day.

The alternative — `day_of_month`, `day_of_week`, `month_of_year` beside `freq` —
permits `freq = 'weekly' AND day_of_month = 15`, a contradiction the validator
would then have to reject. A schema that cannot express the contradiction needs
no rule against it.

Every occurrence is computed from the anchor rather than from the occurrence
before it. Stepping forward from the previous date walks a rule off a cliff:
31 January clamps to 28 February, and a month past *that* is 28 March. The rule
quietly becomes "the 28th" for the rest of its life.

A monthly rule anchored on the 29th, 30th or 31st **clamps to the last day of a
shorter month**. A rule set to the 31st means "the end of the month", and
skipping February is not what anyone setting up rent intends — a missing month is
also far harder to notice than an early one.

### A rule does not backfill

Nothing is generated before `starts_on`, and the anchor picker will not offer a
date before today for a new rule. Catch-up is therefore bounded by how long the
phone was offline. Two further limits bound the damage from a mistyped year: at
most 1000 occurrences are ever enumerated, and at most 60 rows are written per
rule per pass, oldest first, so a genuine backlog still drains monotonically.

### `transactions.recurring_rule_id`, not a new `source`

`transactions.source` carries `CHECK (source IN ('manual','voice','receipt'))`
and SQLite cannot alter a CHECK — adding `'recurring'` would mean recreating the
table and copying the household's entire history to gain a label. A nullable
foreign key added with `ALTER TABLE ADD COLUMN` says more anyway: which rule, not
just that there was one. It is what lets the settings list say "8 added so far",
which is the only evidence a person has that a rule set up in March is working.

## Consequences

`recurring_rules` sits before `transactions` in `DEPENDENCY_ORDER`, on both
sides, because a generated row names its rule and the foreign key check would
otherwise reject a real expense while the user watched it save.

Editing a rule changes what it does next. Transactions it has already produced
keep the amount they were created with — they record money that moved, not a
view onto the rule. Editing the anchor likewise does not retract occurrences
already written under the old one.

There is a small race: a phone that materialises before pulling can briefly
rewrite an occurrence another phone deleted. `SyncWorker` pulls first for exactly
this reason; the app-open path cannot, and last write wins if it happens.

## Addendum, 2026-10-09: a rule may start in the past, and the projection is gone

Two changes in opposite directions, and they are the same decision: a repeating
rule is about what HAPPENS, never about what might.

**A new rule may be anchored before today, and it fills in every occurrence
through today.** Nothing in this decision ever said otherwise —
`materializeRecurring` has always asked for `occurrences(anchor … today)`, and
the idempotent occurrence id is what makes a backlog safe to write — but the
editor refused any anchor before today, on the grounds that "a rule is a
statement about the future". That sentence is true of the next occurrence and
false of the first one, and it made the ordinary case impossible: a subscription
that has been running since March is entered in October, and the seven months it
has already taken are exactly what the household wants in the ledger. The owner
asked for it in one line — "dodanie nowej reguły cyklicznej powinno być możliwe
wstecz i wszystkie kolejne miesiące aż do dzisiaj powinny uzupełnić dane".

The floor is replaced by a count. The editor says how many transactions an
anchor in the past is about to write, before the save, because those are real
rows in months that are already closed and **stopping the rule afterwards does
not take them back out** — that is a tombstone on the rule alone, which is this
decision's own rule and the right one. A mis-scrolled year is the one way to ask
for a hundred rows by accident, and naming the number is cheaper than any guard
that would also block the real case. `MAX_NEW_PER_PASS` still bounds the damage
per pass.

**The "Planned" chip on the ledger is removed.** It merged the occurrences a
rule had not written yet into the list, as faded untappable rows. It was honest
and it was in the wrong place: that tab answers "what did we spend", the chip
answered "what is still coming", and it answered it in the middle of the first
answer — the one control on a row of filters that made the list longer instead
of shorter. `Planned` is deleted along with it. Nothing about materialisation
changes; the rows still appear on their own dates, when they are facts.

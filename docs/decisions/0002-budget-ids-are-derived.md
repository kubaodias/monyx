# 0002 — Budget ids are derived from (category, period)

**Date:** 2026-08-29 · **Status:** accepted

## Context

Client-generated ids are UUIDv4 so rows can be created offline without
collisions. That is right for every table but one.

`budgets` also carries `UNIQUE (household_id, category_id, period)`. Two phones
that each set a limit for Groceries in September while offline would generate two
different v4 ids for what the schema considers the same row. On push, the second
one violates the unique constraint — and because `batch()` is all-or-nothing, a
single violating statement takes the entire push down with it, including every
unrelated expense in the same batch.

Nothing in the original design covers this case.

## Decision

Two changes, one on each side.

The client derives a budget's id from its natural key:
`UUID.nameUUIDFromBytes("budget:$categoryId:$period")`. Two devices creating the
same budget now produce the *same* id, so the push is a plain idempotent upsert
and the conflict never arises.

The server keeps a safety net: the budgets upsert carries a second
`ON CONFLICT(household_id, category_id, period) DO UPDATE` clause, so a row
arriving with a legacy or hand-written id resolves in place instead of failing
the batch. SQLite has allowed multiple ON CONFLICT targets since 3.35; the
platform runs 3.51 (verified).

## Consequences

The property actually wanted from v4 — offline creation without collisions —
is preserved, and for budgets it is strengthened: the id is now a function of
the thing it identifies, so independent devices agree by construction.

This deliberately does not extend to other tables. Transactions must be able to
repeat (two identical coffees on the same day are two rows), so their ids stay
random.

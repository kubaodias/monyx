# 0006 — The month's plan is a stored figure, not derived income

**Date:** 2026-08-31 · **Status:** accepted

## Context

Budgets answer "how much for groceries". They never answered the question that
has to come first: how much is there to hand out at all, and how much of it is
still unassigned. Without that figure the budget screen can say what each
category is allowed and still never say whether the allowances add up to money
that exists.

The obvious way to avoid a new table is to derive the month's total from data
already synced — income recorded in the month, or last month's income.

## Decision

A new synced table, `month_plans`: one row per household per month holding
`planned_minor`. The figure is set by hand.

Derivation was rejected for a reason that shows up in the first week of use.
Planning happens at the *start* of a month, when little or no income has been
recorded yet — a derived total would read near zero exactly when the plan is
being made, then drift upward all month as pay and reimbursements land, silently
moving "left to assign" underneath a plan somebody had already finished. A plan
that changes on its own is not a plan.

Income still has a job here: it seeds the field. A month with no plan of its own
offers the last plan made, falling back to last month's actual income. That is
the useful half of derivation, at the one moment it cannot mislead — a
suggestion in an editable field rather than a number the screen asserts.

The id is derived from the period, `UUID.nameUUIDFromBytes("month_plan:$period")`,
for the reason in [0002](0002-budget-ids-are-derived.md), and more sharply: the
table is `UNIQUE (household_id, period)` with no category to tell rows apart, so
two phones planning August offline collide on every field except a random id.
The server carries the matching `ON CONFLICT (household_id, period)` clause as
the same safety net budgets has.

## Consequences

Two figures on the card, deliberately not one.

**Left to assign** is `planned − assigned`, and answers whether the plan is
finished. Zero is the goal, so it is drawn as success, not as a warning.

**Left to spend** is `planned − spent`, where spent is *every* expense in the
month, including categories with no budget at all. The two disagree whenever
money goes somewhere unbudgeted, and that disagreement is the point: a screen
that counted only budgeted spending would report a comfortable month while the
account emptied.

A plan of zero is legal and distinct from having no plan — "there is nothing to
spend this month" is a real statement, and the server validates only against
negatives.

Nothing recalculates old months. A past month keeps the plan it was given, which
is what makes looking back at it worth anything.

## What summing exposed

Adding up the category limits surfaced a defect that had been invisible for as
long as nobody added them up.

A phone writes a budget under an id derived from (category, period). The server
resolves that push through `ON CONFLICT (household_id, category_id, period)` —
the safety net from [0002](0002-budget-ids-are-derived.md) — onto whatever row is
already there, and that row keeps its **own** id. The push is reported applied,
the client clears `pending`, and the next pull returns the canonical row under
the other id. The client is now holding two rows for one budget, permanently.

On a list that reads as a category appearing twice: odd, ignorable. In a total it
is simply a wrong number, and a wrong number is the whole product here.

`budgetUsage` now picks one row per category, newest `seq` first — the server's
copy, because that is the one every other phone is looking at. The orphaned local
rows are left alone rather than deleted: they are harmless once the read is
correct, and a migration that guesses which of two rows to destroy is a worse
risk than a stale row nothing reads.

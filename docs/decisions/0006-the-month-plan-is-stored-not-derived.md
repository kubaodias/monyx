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

## Which of the two figures leads (2026-10-30)

Above says "two figures on the card, deliberately not one", and left "left to
assign" as the headline because zero is the goal of planning. That was the right
pair and the wrong order.

Planning a month happens once, in an hour, at its start. Reading the card
happens every day of the rest of it, and the question then is never whether the
plan is finished — it is whether there is money. That answer was the fifth of
five small lines, in the same weight as "assigned to categories", under a
headline showing 0,00 in the colour of success. A household asked the obvious
question out loud: Portfel says 1 179,28 is left, the budget says −767,74, how
do those two relate.

So **left to spend is the headline**, printed as a size with the word above it
carrying the direction — "przekroczono o 767,74", which is how every category
row in the list below has always said it. The bar under it moved from
assignment to spending, because a bar belongs to the figure it sits beneath. The
planning pair keeps both exact values on one small line at the foot of the card,
still red when more has been handed out than exists. Nothing was dropped and no
arithmetic changed; the order now says which question the screen is for.

Two additions came out of the same question.

**What actually came in, against what the plan assumed.** This is the line that
reconciles the card with the account, and the household's own numbers are the
case for it: a plan of 22 483,98, income of 24 431,00, spending of 18 122,74 on
a Portfel that closed September 5 128,98 in the red. "Left to spend" is −767,74
while the wallet still holds 1 179,28, and the entire difference — 1 947,02 — is
income the plan never assumed. Both figures were right and the screen said
nothing that made them agree. Income still does not *derive* anything here; the
decision above stands, and it earns its place by being the thing a stored plan
can be wrong about.

Money **over** the plan is shown the day it lands. Money **under** it only once
the month has closed: a plan is written for a whole month, so on the third of it
every household is nineteen thousand "below plan", and a card that says so every
morning cries wolf about a month going exactly as intended.

**How far the limits were passed, added up.** This one lasted one release. The
argument for it was that a month can come out level with three categories well
over and three well under; the owner's answer, after using it, is that this is
exactly why the figure says little — the two sides roughly cancel — and the card
was carrying more lines than it had questions. Removed in 0.29.1. Each row still
says it for itself, which is where somebody looking at a category is looking.

## Both figures lead, side by side (2026-10-31)

The section above moved "left to spend" to the headline and left "left to plan"
in a footnote. One release of use said that was half right: the planning figure
is not a detail of the spending one, and a reader who wants it should not have
to read a sentence of small print to find it.

So the card leads with **both**, side by side, in the order the month is lived:
what is left to spend on the left, what is left to plan on the right. Same size,
same weight, neither the subtitle of the other. The right-hand figure keeps the
colour rule it has had since this decision was written — zero is the goal, drawn
as success, red when more has been handed out than exists. The bar stays with
the left-hand figure and so measures spending.

What that bought was room. Below the two figures there is now only the
arithmetic behind them — the plan, what came in, last month's remainder, what
has gone — and the footnote line is gone with the figure it carried.

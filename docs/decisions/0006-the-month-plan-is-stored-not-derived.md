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

## Where the figure comes from, said on the sheet that asks for it (2026-11-09)

This decision's first section ends with income seeding the field: "a suggestion
in an editable field rather than a number the screen asserts". For three months
that suggestion was the *last plan made*, which is last month with a nudge — and
a household that gets paid a different figure every month has nothing to nudge
it with.

The question that found it was asked on the ninth of a month: "some categories
already have transactions, and I cannot tell on the budget screen how much there
actually is to plan". The data behind it: Portfel closed October 387,19 to the
good, 1 950,00 and 22 500,00 came in on the 6th and the 9th, 6 266,36 had already
gone, nine limits added up to 19 099,00, and the plan was a round 22 000,00 typed
by hand. Every ingredient of the answer was on the card — *Wpłynęło*, *Z
poprzedniego miesiąca*, the limits — and the one screen that asks for the figure
showed none of them. 24 450,00 had arrived against a plan of 22 000,00, so the
card was quietly 2 450,00 pessimistic about a month going better than planned,
and said so only in a hint beside *Wpłynęło*.

So the plan sheet now says what there is to plan, in three small lines under the
keypad:

**What came in this month**, and tapping it types it. Still a suggestion and
still not a derivation — the plan stays a stored figure, and the sheet is the one
moment when offering income cannot mislead, because somebody is already looking
at the field. It is not offered at 0,00: early in the month that is the one
suggestion guaranteed to be wrong, which is the argument of the first section.

**What last month left, added on top.** The figure in this field is not the pot;
`plannedMinor + carryOverMinor` is. Anyone who reads "to spend this month" and
adds the carry-over in himself plans the same money twice, and the offer above
would have taught him to — so the offer is income alone and the line says where
the rest comes from.

**What is already assigned, against the same pot the card uses.** This line is
from the first version and it was wrong by exactly the carry-over: the sheet
computed `typed − assigned` while the card behind it computed `typed +
carry-over − assigned`. Both were on screen at once, 387,19 apart. The pot is
defined in one place now (`PlanState.availableMinor`, mirrored by `PlanDraft` for
the figure still being pressed).

What this does not change: already-spent money is still planned for. A plan
covers the whole month including the part of it that has happened, which is why
a month can be planned on its ninth day with nothing to undo — the limits count
spending from the first.

## The stored figure is a floor, not the pot (2026-11-09, later the same day)

The section above shipped three explanatory lines into the plan sheet. The
household read them and said, in one sentence, that the screen had become
over-complicated and he was getting lost in it. Four reviewers were asked for
simpler exits, independently, and they agreed on three things: the carry-over
defect was worth fixing, two of the three new lines were not information about
money but documentation of a field's arithmetic, and nobody was willing to
defend typing the figure by hand at all.

One of them found the sentence this decision had been avoiding for three
addenda. **The pot already changes on its own.** `availableMinor` was
`plannedMinor + carryOverMinor`, and the carry-over is last month's closing
balance, read live from `accountBalancesThrough`. Half the pot has been derived
since September. Everything patched since — the reconciling income line, the
"1 947,02 ponad plan" hint, the 387,19 disagreement between the sheet and the
card — is downstream of refusing to say that out loud.

So:

    availableMinor = max(plannedMinor, incomeMinor) + carryOverMinor

**What the stored figure now is.** A forecast: what the month expects before the
money is in. It can only ever RAISE the pot, which is what makes this compatible
with the paragraph this decision opens with. The objection there was never
really to derivation — it was that a figure somebody had finished planning
against must not move underneath them. Under a maximum, nothing a household
planned is ever taken away; the pot only catches up with reality. The field is
labelled for what it is, „Zakładam, że wpłynie", and it is the one place the
word "plan" still earns its keep: planning December in November is exactly what
a stored figure is for, and nothing else can do it.

**What this deletes.** *Income against the plan* as a concept — the difference
now lives IN the pot instead of being printed beside it, so `incomeVsPlanMinor`,
`incomeNoteMinor`, both hint strings, the tappable offer and the carry-over note
are all gone. The card's „Plan na miesiąc" line, which printed what somebody had
typed, becomes „Pula na miesiąc", which prints what there is — and the two lines
under it, what came in and what last month left, now add up to it. The typed
forecast returns as a line only while it is still the figure doing the work.
Every figure on the card is an answer; none is an echo.

**What this retires.** The once-a-month ritual. `hasPot` is `hasPlan ||
incomeMinor > 0`, so a month nobody has planned still shows real figures the
moment pay lands — no banner, no "start the month" button, no sheet to open. The
household's question was "how do I start making the budget", and the better
answer than teaching the ritual was to delete it: the month starts itself when
money arrives. A card of em dashes is now reserved for a month with neither a
plan nor a single złoty in, which is the only state where there is honestly
nothing to say.

**The price, stated.** A one-off `kind='income'` row inflates the pot and cannot
be typed back down — a repaid loan, or the 1 000,00 "Zwrot za AMS" that settled a
work trip. Transfers are already not income ([0022](0022-zloty-is-the-reporting-currency-and-a-rate-has-a-date.md)
scopes what counts; `monthTotals` counts `kind='income'` alone), so this is
rare, and it is money that really did arrive and really can be spent. The escape
hatch is the one that was already there: *Usuń plan* now means "let the month be
whatever came in".

Also fixed, and the reason the sheet opened on a stale figure at all:
`suggestedPlanMinor` offered the newest earlier plan — 23 431,00 from a closed
October — while November's own income sat at 24 450,00 two lines below it. Two
suggestions from two different months in one sheet. The sheet now opens on what
has arrived, scoped to the accounts this screen counts, and the repository's
suggestion is the fallback for a month nothing has landed in yet, which is the
one case where repeating last month is a sensible guess.

### Two notes from the first hour of use

**The figure is called „Budżet na miesiąc", not „Pula na miesiąc".** The
household's own word, offered as a question — *couldn't it just say Budżet?* —
and it is right: nothing else on screen claims that word for something else. A
category's cap is „Limit" everywhere it appears, so the only other uses are the
tab and the link to it from the Overview. The tab being called Budżet and its
first line saying how big the budget is reads as a screen agreeing with itself.
"Pula" was a word chosen for a technical distinction nobody outside this file
needs to make.

**A forecast below what arrived now says so.** The max rule's one bad moment,
reported from the phone within the hour: "if I change *Zakładam, że wpłynie* I
don't see it changing anything". It is correct — a forecast cannot make the
month poorer than the account already is — but a control that silently ignores
what is typed into it owes the reader a sentence, so the sheet prints one:
„Wpłynęło już 24 450,00 zł — niższa prognoza nic nie zmieni." In this state
only. The case the sheet exists for is a month whose pay has not landed, and
there every keypress moves every figure.

## The card is a column sum (2026-11-09)

The household's reading of the four figures under the headlines: couldn't it be
a column, like primary-school arithmetic, with rules that total and separate at
the same time. In the order the money arrives — what came in, plus what last
month left, the budget, less what has gone.

They are right, and the form is not decoration. The four lines were equal in
weight and nothing on screen said which of them added up to which; two of them
do, and a reader had to be told so in prose (this file) rather than shown.

    Wpłynęło                   24 450,00
  + Z poprzedniego miesiąca       387,19
    ──────────────────────────────────────
    Budżet na miesiąc          24 837,19
  − Wydane                      6 424,33
    ──────────────────────────────────────
    Zostało do wydania
    18 412,86

Three things the form settled that the old layout had left open.

**The first term must be the figure that won.** The pot is
`max(forecast, income) + carry-over`, so in a month whose forecast still leads —
December planned in November, 30 000 expected against 2 000 in — a column headed
"Wpłynęło 2 000" simply would not add up to the total under the rule. So the
first row is whichever figure the pot was taken from, with what has arrived so
far as a sub-line beneath it. A column cannot lie about its own addends, which
is the argument for drawing one.

**The operator is structural, not a warning.** A month that closed in the red
used to print "-5 128,98" in red, as if carrying a shortfall were an error. In a
column it is a minus sign in its own slot, and the sum still works.

**One figure leads, not two.** This reverses the 0.29.1 decision above, which
put "left to plan" beside "left to spend" as an equal headline, and it is
reversed for a reason the sum makes plain rather than a change of taste:
spending and assigning are two branches off the SAME subtotal — both come off
the budget, neither off the other. A column that subtracted one after the other
would read as sequential arithmetic and produce nonsense. Planning is also a
once-a-month act against a daily one, so it keeps a line rather than a headline,
at the foot of the card, saying both halves at once.

**And what that line is called.** „Rozdane / nierozdane" lasted one release. The
household asked for „zaplanowane / niezaplanowane", their own verb, and the
first half is now exactly that — but not the second: *niezaplanowane wydatki* is
settled Polish for spending you did not foresee, and this screen HAS such
spending (315,60 zł of November went to categories with no limit, and it eats
precisely this pool). One word would have named two different things on one
card. With the preposition it is a task and not a property of an expense, so the
line reads „Zaplanowane 20 355,00 · do zaplanowania 4 482,19" — which also
returns the name this figure carried for three months.


### The right edge belongs to the figures (2026-11-10)

The column shipped in 0.29.5 and the household's first sentence about it was
„wyrównanie jest złe i ucina, jak np. 'budżet na miesi...'". Both halves had one
cause: the label was laid out with `weight(1f, fill = false)` next to a
`weight(1f)` spacer, so the free space split in two. The longest label was cut
off with room to spare, and the slack the shorter labels did not claim was left
standing — putting each row's amount at its own x. A column whose digits do not
stand under each other is not a sum; it is a list that merely looks like one.
Exactly one weighted child, and it is the label: the amount is measured first, at
whatever it needs, and gives way to nothing.

The same rule then settled where the pencil lives. The leading figure was
left-aligned with a 48 dp icon button beside it, which meant the one number
answering the column ended 48 dp short of every term in it. The figure is now
right-aligned on the card's edge like the terms, and the way into the editor sits
in the left margin, in space that was empty anyway. An affordance can move; the
answer cannot leave the column it answers.

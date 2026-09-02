# 0015 — The keypad is not always there

**Date:** 2026-09-02 · **Status:** accepted

## Context

The add screen showed everything at once: kind, amount, account and date, the
category grid, the note field, the keypad, the save button. The keypad is 236dp
— a quarter of the screen — and it means nothing once the amount is typed. It
sat there through the category tap, and it sat there *under the system
keyboard* while the note was being written, so the category grid was scrolling
four rows at a time in a window it did not need to be sharing.

The order was wrong too. The amount sat above the account and date chips, which
put two controls that are almost always left on their defaults directly between
the figure being typed and the keypad typing it.

Two other things the screen could not do. It refused future dates, on the
grounds that an expense has already happened — true of a receipt and false of
the standing order leaving on Friday, the deposit due next week, the flights
already booked. And rent gets typed by hand once before anyone thinks "this
happens every month", at which point the only way to act on that thought was to
go to Settings and type it all again.

## Decision

**Three input states, one at a time.**

| State | The bottom of the screen |
|---|---|
| Amount | The keypad |
| Note | The system keyboard |
| Nothing | Neither — the grid has the screen |

Tapping a category or the note field puts the keypad away. Tapping the amount
brings it back, and takes the system keyboard down with it — which means
dropping the note field's focus, because a keyboard hidden while its field is
still focused comes straight back on the next recomposition.

The amount grows a small dialpad glyph whenever the keypad is hidden. It is the
only way back, and without the glyph it is a heading that happens to be a
button, which nobody would guess.

**The order down the screen is kind, then account and date, then the amount.**
The amount now sits directly above the categories and directly above the keypad
that types it. The two chips nobody usually touches are out of that path
instead of through the middle of it.

**Any date, forward or back.** A household budget is as much about what is
coming as what went, and the month totals are where that has to show up. Both
date pickers also grow a "Today" button: a month grid can reach any date, which
is exactly what makes it easy to get lost in.

**"Make it repeat" is a chip beside account and date.** It hands the half-typed
transaction to the rule editor, prefilled. Saving there creates **one** thing —
a rule, not a rule and the transaction that seeded it. The rule's first
occurrence IS that transaction, and it is materialised on the spot rather than
at the next app open, so the row lands while the person who asked for it is
still looking at the screen. That is safe to do early because occurrence ids are
derived from (rule, date), so it cannot double up with the pass that runs on the
next sync.

## Consequences

- A rule does not backfill, so a seed anchored in the past is pulled forward to
  today. A date in the **future** is kept: "this starts next month" is an
  ordinary thing to mean, and it composes with future-dated expenses above.
- `RecurringEditor` now takes a `RuleSeed` rather than an existing rule, because
  it has two callers with nothing in common but the fields. `seed.ruleId` is
  what decides whether saving updates or creates.
- Saving a transaction returns the screen to the Amount state, so the next entry
  starts where the last one did.
- The context chips wrap. Three of them, one of which is a whole phrase in two
  languages, overflow a narrow screen — and a `Row` does not wrap, it squeezes
  the last child until its label breaks between letters.

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

## Addendum, 2026-10-06: while the keypad IS there, the open family takes the window

The other half of this decision, four releases on. The keypad standing down
gives the grid room for the roots, the subcategories and the note at once —
which is why, once an amount has been typed and a family tapped, everything is
simply on screen and nothing needs arranging.

While the keypad is up the window is about 250dp and the roots alone fill it.
Opening a family then happens below the fold: the subcategories are appended
after every root, because a lazy grid has one flat list of items and not a tree.
Scrolling them into view was the first answer and it put them at the bottom of
the window with the note under them, which reads as the bottom of a long list
rather than as the question being asked.

So the family block takes a window of its own. It is one grid item — the rule,
the subcategories laid out by hand in the same four columns, and empty space
after them — with a minimum height of one window less the note, which stays the
item after it.

The note stays its own item for a reason worth writing down: it was inside the
block at first, and so it was destroyed and rebuilt every time the block stopped
being a window tall — which is exactly when the keypad stands down, which is
exactly when somebody reaches for the note. A field rebuilt as it gains focus
loses it, so the first tap on the note did nothing and it took a second one.
Nothing about the note's node changes now; the block above it is what grows. Scrolled to, the block lands at the top: the subcategories sit directly under
the amount line that now carries their family, the note is where it always is at
the bottom, and the roots are one scroll up. The owner's own description: "no
other category is visible and view is scrolled down so that list of
subcategories stay at the top".

Opening a family is the only thing that scrolls it. Un-pinning does not: the
block stops being a window tall, the content stops overflowing, and a lazy list
clamps its own offset to zero when that happens — so the roots come back because
there is room for them rather than because something jumped. Scrolling there too
meant that tapping a subcategory, which is what takes the keypad down, threw the
grid back to the top of the list.

Two things worth stating, because both were wrong first:

- **A minimum height, not an exact one.** `fillParentMaxHeight` is the obvious
  modifier and it sets an exact height, so a family with three rows of children
  would be clipped by it rather than scrolling past it. The height comes from a
  `BoxWithConstraints` around the grid, which is also the only place that knows
  what a window is.
- **It is pinned only while something is taking the bottom of the screen** —
  the keys, or the note's keyboard. The flag comes from the screen, not from
  measuring: the screen is the thing that decided to give its lower half away.
  When it takes it back, the block is a block like any other and the space
  between the children and the note disappears with it.
- **Nothing animates its way there.** The scroll is instant and the roots do not
  animate out of the gap the chosen family leaves. One tap used to move three
  things at once — the cells closing over the gap, the grid scrolling, the mark
  rising — with the scroll chasing a target the reflow was still moving. One
  thing moves now, and it is the mark; the grid is simply where it belongs on
  the next frame. The one scroll that IS animated is the one a tap asks for
  directly, on the mark, where there is nothing else moving to race.

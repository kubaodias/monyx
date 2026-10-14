# 0013 — One month for the whole app, and a way to reach any of it

**Date:** 2026-09-01 · **Status:** accepted

## Context

[0011] gave Overview, Transactions and Budget the same switcher. It did not give
them the same month. Each screen's ViewModel still held a period of its own,
defaulting to the present one, so stepping back to March on the overview and
then tapping Budget showed September — the identical control, in the identical
place, disagreeing with the one you were just looking at.

Every tab switch reads as a step sideways, so the month has to survive it. The
three screens are not three questions about three months; they are three views
of one month: what it cost, what it was spent on, and what it was meant to cost.

Reaching a distant month was the other half of the problem. The arrows step one
month at a time, which is right for a neighbour and hopeless for last December:
eight taps, each one reloading the screen behind it.

## Decision

**One `SelectedMonth`, held by `MonyxApp`.** A `MutableStateFlow<String>` and two
methods. All three ViewModels take it and read their period from it; none of
them owns one. It is scoped to the application rather than to a ViewModel or a
back stack entry, because those are destroyed on the tab switch it has to
survive.

It is **not persisted**. A fresh launch starts on the current month: the first
thing anyone wants on opening a budgeting app tomorrow morning is tomorrow's
budget, not the month they were auditing last week.

**Tapping the month title opens a picker** — a year with arrows either side,
twelve months under it, and a button that jumps to the present month. Not a
Material date picker: those pick a *day*, so the grid would offer thirty-one
answers to a question with twelve and then throw the answer away.

`MonthSwitcher` now takes a **period**, not a label, reversing that part of
[0011]. The title is no longer only a label — it is the control that opens the
picker, and it needs the month it is editing. "All time" was the reason for the
label, and "All time" is gone.

## Consequences

- `applyFilter(categoryId, null)` no longer snaps Transactions back to the
  present month. That was defensible while every tab held its own month and was
  a bug the moment they shared one: tapping the tab after choosing March on the
  overview would have thrown March away on arrival.
- `previousMonth`, `nextMonth`, `stepMonth` and `setInitialPeriod` collapse into
  one `setPeriod` per ViewModel, all three forwarding to the same holder.
- A deep link from a budget notification still sets the month directly, and now
  sets it for every tab — which is what a notification about September's food
  budget meant in the first place.
- The grid marks the present month with a ring even while another month is
  filled, so it always says where "now" is relative to what is on screen.
- Month names in the grid are formatted with `LLL`, the standalone form, not
  `MMM`. Polish inflects the genitive "stycznia" when a day precedes it, and a
  lone grid cell reading "stycznia" is a month name in the wrong case.

[0011]: 0011-one-month-switcher.md

## Addendum, 2026-10-14: a filter is a statement, and it travels

The month was the first thing the three screens shared. The rest of what a
screen is narrowed to was still stuck to the screen it was set on, and every
jump out of it quietly widened the question:

- The **keypad** opened on today whatever month was on screen. Stepping back to
  September to enter a receipt from September and reaching for Dodaj filed the
  row in October, and then landed on a ledger — the September one — with nothing
  in it to show for the entry. `AddViewModel.pickUpMonth` now takes the shared
  period and `Dates.dayInPeriod` picks the day: today while the month is the
  running one, otherwise the same day of the month, clamped. The usual path
  through it changes nothing, and the date is on a chip above the keys, reading
  as a date precisely when it is not today.
- **Saving** goes to the month the row is in, not the one that happened to be
  selected. A back-dated row used to be followed by a jump to a ledger that
  could not contain it.
- The **overview's account strip** did not travel at all. A pie slice tapped
  with one account lit opened the whole household's ledger, so the rows did not
  add up to the slice. The screen reports its narrowing up to `MonyxNav` the way
  Transactions already reported its account and category chips, and a slice, a
  day and Dodaj all carry it. One account or none: two of five selected is a
  view the ledger has no filter for, and narrowing to the first of them would be
  a different question answered silently.
- A **budget row** counts the default account alone — that is what a limit
  governs, see `budgetAccountIds` — and *Zobacz transakcje* opened the ledger
  unnarrowed. One insurance premium is enough to make the list disagree with the
  bar that was tapped. It now carries the account.
- The **day sheet's legend** was drawn as something tappable, with a ripple, and
  did nothing: `onSliceClick = {}`. The button on that sheet opens the day; a
  line of its legend now opens that day for that category, which is the same
  gesture the pie's legend has on the other face of the card.

The rule behind all of it: a filter is somebody saying what they are looking at.
Any button that opens another view of the same money has to carry what has
already been said, and the pickups are guarded the same way — a category the
keypad cannot offer or an archived account it cannot file to is ignored rather
than guessed at.

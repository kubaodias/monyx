# 0011 — One month switcher, on every screen that has months

**Date:** 2026-09-01 · **Status:** accepted, amended by [0013](0013-one-month-for-the-whole-app.md)

## Context

Three tabs are scoped to a month, and all three said so differently. Overview
and Budget each had their own hand-rolled copy of the same arrows-and-label row,
already drifted apart into `titleLarge`/SemiBold and `titleMedium`. Transactions
had no arrows at all: its period was a dropdown chip offering the current month
and the five behind it, so a receipt from two years ago could not be filtered to
at all — which is roughly where "when did we last pay for that?" starts.

Budget also carried a `headlineSmall` "Budget" title above its switcher, naming
the tab that the navigation bar already names, highlighted, two centimetres
below.

## Decision

One `MonthSwitcher` in `com.monyx.ui`, used by all three. It takes a **label**
rather than a period, because Transactions has a state that is not a month —
"All time" — and still wants the arrows to step out of it. ([0013] reverses
this: "All time" did not survive the same decision, and the title has since
become a control that needs the period it is editing.)

The Transactions dropdown is gone. The arrows have no floor and no ceiling:
stepping is `LocalDate.plusMonths`, unbounded in both directions.

**Transactions always has a month**, defaulting to this one. The repository
still understands `""` as every month there has ever been, and the first cut of
this kept it as the tab's default with the label "All time" — but a switcher
reading "All time" between two month arrows is a control naming a state it
cannot step back to, and the tab is scoped the way Overview and Budget are
scoped. So the month is the screen's **scope**, not one of its filters:
`Clear filters` leaves it alone and the chip does not appear for it.

The Budget title is deleted. A screen does not need to introduce itself when the
bottom bar is already pointing at it.

## Consequences

- `PeriodOption` and `periodOptions` are gone, and with them the strings
  `transactions_filter_period` and `transactions_all_time`. `budget_title` is
  gone too.
- Search is now month-scoped, which is the one thing lost. Stepping is cheap and
  unbounded, so finding a receipt is arrows rather than a dropdown's six-month
  floor — but it is a real trade, made deliberately.
- All three switchers are placed identically: the arrows start 20dp in and 20dp
  down on every screen. Budget's list is padded 16 and Overview's 20, so the
  missing four are added to the switcher rather than to every budget row; and
  Transactions' own `Scaffold` now declares zero content insets, because the bar
  it lives in had already applied them and taking the status bar twice pushed it
  a centimetre below the others.
- Overview and Budget now share Overview's typography, which is the larger of
  the two — the switcher is the heading of those screens, so it should read like
  one.
- Deep links still pass a period directly through `applyFilter`; they never went
  through the dropdown.
- The three switchers still each held their own month. [0013] takes that away
  too.

[0013]: 0013-one-month-for-the-whole-app.md

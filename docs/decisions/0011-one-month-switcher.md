# 0011 — One month switcher, on every screen that has months

**Date:** 2026-09-01 · **Status:** accepted

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
"All time" — and still wants the arrows to step out of it.

The Transactions dropdown is gone. The arrows have no floor and no ceiling:
stepping is `LocalDate.plusMonths`, unbounded in both directions.

Blank stays the Transactions default and still means every month there has ever
been. An arrow pressed there has no month to move away from, so **both** arrows
drop into the current month rather than inventing a direction. Nothing can step
back to blank; the way back is the `Clear filters` chip, which appears the
moment a month is chosen because a chosen month is an active filter.

The Budget title is deleted. A screen does not need to introduce itself when the
bottom bar is already pointing at it.

## Consequences

- `PeriodOption` and `periodOptions` are gone, and with them the string
  `transactions_filter_period`. `budget_title` is gone too.
- Overview and Budget now share Overview's typography, which is the larger of
  the two — the switcher is the heading of those screens, so it should read like
  one.
- Deep links still pass a period directly through `applyFilter`; they never went
  through the dropdown.

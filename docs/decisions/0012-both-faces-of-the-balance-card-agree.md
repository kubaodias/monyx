# 0012 — Both faces of the balance card print the same figures

**Date:** 2026-09-01 · **Status:** accepted, amends [0009](0009-the-trend-is-a-running-balance-line.md)

## Context

On 1 September the card read **0,00 zł** under "Balance". Turning it over, the
same card read **4 447,00 zł**. Both were correct and they answered different
questions: the front totals the *selected month*, the back totalled the *last
thirty days*, which reach back past the 1st and pick up August's payday.

Nothing on the card said so. Two big numbers, same typeface, same position, one
flip apart — the only honest reading of that is that one of them is wrong.

## Decision

### The running total restarts on the first of the month

Not on the first day of the window. The line's last point is then the selected
month's balance, which is exactly the number on the front, and turning the card
over changes no figure at all — it only adds the path the figure took.

A window reaching back before the 1st draws the previous month's own run, and
the two runs are **not joined**. A segment from last month's closing total down
to zero would draw a plunge on a night nobody spent anything. There is a break,
a faint rule at the turn, and a month one day old is a single dot.

### The income and expense figures on the back are the month's too

They were the window's. Same disease: the front said 89,99 out and the back said
2 639,99 out, and neither admitted to covering a different stretch of days.
Pointing at a day still swaps all three figures to that day's, and the header
says "Through 25 August" while it does — a scope named out loud is fine, it is
the unnamed one that lies.

### The window stretches to reach the 1st

Thirty days is now a floor. On the 31st of a 31-day month a plain thirty would
start on the 2nd, the run would never meet the 1st it has to restart on, and the
two faces would disagree by whatever was spent that day. So the window is
`min(end − 29 days, first of the month) .. end` — thirty days usually, thirty-one
on the last day of a long month.

## Consequences

- `TrendSeries.incomeMinor`, `expenseMinor` and `netMinor` are gone; the card no
  longer has any use for a window-wide sum, and leaving them would have invited
  exactly the bug being fixed. `monthToDateMinor` replaces `netMinor` as the
  fallback for a point past the end of the series.
- The back's header is `overview_balance`, the same string as the front, so the
  two faces cannot drift apart in wording either. `overview_trend` — "Last 30
  days" — is deleted; the axis labels already carry the real dates.
- The chart draws one path per month rather than one per window. Two runs is the
  most a window ≤31 days can contain.
- A future month still draws an empty chart labelled with its own dates, as
  0009 decided. Nothing here changes the window's end.

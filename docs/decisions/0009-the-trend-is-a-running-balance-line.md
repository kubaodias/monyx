# 0009 — The trend is a running-balance line, not a chart of daily amounts

**Date:** 2026-09-01 · **Status:** accepted, amended by [0012](0012-both-faces-of-the-balance-card-agree.md)

## Context

The balance card on the overview answers "how did this month end up". It cannot
answer "how did it get there" — one figure cannot tell you the month was steady,
or that it was fine until one weekend. Turning the card over and showing thirty
days behind it is the natural home for the second question, because it is the
same number told over time rather than a separate fact.

Two things had to be settled: **what the window is**, and **what is plotted in
it**. The second one was got wrong first, and the wrongness was only visible on
a screen.

## Decision

### The window belongs to the month on screen, not to the wall clock

"The last 30 days" ending today is right only while the current month is
selected. The overview has a month switcher, and a card that keeps showing
August while its own front face reads July is two answers to one question.

So the window is thirty days ending on the last day the selected month can
honestly show: today when the month contains today, its final day when the month
is over, and — deliberately — its own final day when the month is in the future.
A future month therefore draws an empty chart labelled with future dates, which
is honest, where quietly falling back to the last thirty days of the present
would not be. Both ends carry a real date, so the card cannot be read as
claiming a window it is not showing.

### What is plotted is the running balance

The first implementation drew the daily amounts: income above a centre line,
expenses below it, one pair of bars per day. It was the wrong chart, for two
reasons that only became clear once it was on a phone.

The first is arithmetic. Scaled to the tallest bar, the chart was one salary and
thirty invisible stubs — with a peak of 8 500 and a grocery day of 40, the
grocery bar is half a pixel. Every day the household actually lives in had been
scaled out of existence by the one day it does not think about. That is fixable
(the fix was to scale to a multiple of the median and let the exceptional days
run off the edge), and the fix worked, and it was still the wrong chart.

The second is the question being asked. Thirty pairs of bars tell you what
happened on each day; they do not tell you where you *are*. "How did my budget
look over time" is answered by the accumulation, not by the increments — the
step up on payday, the long grind down to the next one. A reader can integrate
thirty bars by eye only badly, and that integration was the entire point.

So the line is the running total of income minus expenses across the window, and
the daily figures became the readout rather than the shape: touch a day and the
card shows the balance as it stood that evening, plus what came in and went out
on the day itself.

This also fixes the scaling problem outright rather than mitigating it. A line
is scaled by the range of the balance, not by the size of the largest single
transaction, so a payday sets the top of the chart by moving the balance — which
is exactly what it did in life.

### Break-even is always on the chart

The vertical range is forced to include zero from both directions. It is the one
threshold on this chart that means anything: above it the window is ahead, below
it the window is behind. Line and fill are cut at that line rather than coloured
by where the month ends, so a month that dipped under and climbed back out is
visibly a month that dipped under.

### Every day is a vertex, and the line is never smoothed

A line drawn only through the days that had transactions would put Tuesday and
Friday the same distance apart as Tuesday and Wednesday, and the slope — which
is the whole message — would be a lie about how fast the money went. Quiet days
are filled in with zeros by the client; the query returns only the days that
have rows.

Nor is the line curve-fitted. A spline between two daily balances draws balances
the household never had, including, at a step, a dip below the lower of the two
points. Straight segments between real values are the only honest join.

### Nothing new is stored

It is a `GROUP BY occurredOn` over transactions that already exist. No column,
no table, no migration, no server change — which is also why it works offline
and inside the account filter without any of that having to be arranged.

## Consequences

- The line starts at the first day's net, not at zero, and measures the window
  rather than the account: it is "how far ahead this month is", not "what is in
  the bank". That is the same quantity the front of the card shows, which is the
  point — the line ends exactly on the number it was flipped from.
- Reading a single day's spending off the chart is not possible; only its effect
  on the balance is. The tap readout is the instrument for that, and it gives
  exact figures rather than an eyeballed height.
- Transfers are excluded, as everywhere else in the statistics. Moving money
  between two of your own accounts is neither earned nor spent, and counting it
  would put a step in a line that never moved.

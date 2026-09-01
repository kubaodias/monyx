# 0009 — The trend chart scales to a typical day, not to its tallest bar

**Date:** 2026-09-01 · **Status:** accepted

## Context

The balance card on the overview answers "how did this month end up". It does
not answer "how did it get there" — a single figure cannot show that the month
was steady, or that it was fine until one weekend. Turning the card over and
plotting thirty days behind it is the natural place for the second question,
because it is the same number told over time rather than a separate fact.

Three things had to be settled: **what the window is**, **what a bar means**,
and **how tall a bar gets**. The third one is where the naive answer is wrong,
and it was wrong on screen before it was wrong in argument.

## Decision

### The window belongs to the month on screen, not to the wall clock

"Last 30 days" ending today is right only while the current month is selected.
The overview has a month switcher, and a card that keeps showing August while
its own front face reads July is two answers to one question.

So the window is thirty days ending on the last day the selected month can
honestly show: today when the month contains today, its final day when the
month is over, and — deliberately — its own final day when the month is in the
future. A future month therefore draws an empty chart labelled with future
dates, which is honest, where quietly falling back to the last thirty days of
the present would not be. The card labels both ends with real dates, so it can
never be read as claiming a window it is not showing.

### One shared scale, not one per side

Income above the line, expenses below it. It is tempting to scale each half to
its own maximum, because it makes both halves look busy. It also draws an
8 500 payday and a 200 zł weekly shop as exactly the same bar — which is worse
than an empty chart, because an empty chart is merely unhelpful and this one is
wrong. Both halves share one number.

### But the scale is six times a typical day, not the tallest one

This is the part that had to be seen to be believed. Scaled to the peak, the
first render was a chart of one salary and thirty invisible stubs: with a peak
of 8 500 and a grocery day of 40, the grocery bar is half a pixel. Every day the
household actually lives in had been scaled out of existence by the one day it
does not think about.

So half the chart's height is worth **six times the median non-zero daily
amount**, capped at the peak. Everyday spending gets most of the height, and the
two or three genuinely exceptional days — salary, rent — run past the scale.
A capped month is not silently clipped: a bar that exceeds the scale is drawn to
the full half-height and so touches the edge of the chart, and nothing within
the scale ever does, which makes "off the chart" a thing you can see rather than
a thing you have to be told. On an evenly spent month the cap never binds and
the scale is just the peak, so this costs nothing when it is not needed.

### Every day gets a column, including the quiet ones

A chart plotted only from days with transactions would put Tuesday and Friday
the same distance apart as Tuesday and Wednesday, which destroys the one thing
an over-time chart is for. Days with nothing in them are filled in with zeros by
the client; the query returns only the days that have rows.

### Nothing new is stored

The chart is a `GROUP BY occurredOn` over transactions that already exist. No
column, no table, no migration, no server change — the back of the card is a
second reading of rows the app already holds, which is also why it works
offline and inside the account filter without any of that being arranged.

## Consequences

- Bar heights are proportional to amounts **up to the scale** and not beyond it.
  Anyone reading two clipped bars against each other is reading two "off the
  scale" days as equal. The exact figures are one tap away — touching a day
  puts its income, its expenses, and the balance as it stood that evening in the
  header — and that readout, not the bar, is the measuring instrument.
- The median is over non-zero days only. A month with one transaction has a
  median equal to that transaction and nothing is clipped, which is correct: one
  bar is the whole month.
- Transfers are excluded, as they are everywhere else in the statistics. Moving
  money between two of your own accounts is neither earned nor spent, and
  counting it on both sides would draw a spike on a day nothing happened.

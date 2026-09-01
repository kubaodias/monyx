# 0010 — Committing an expense is a button, not a key on the keypad

**Date:** 2026-09-01 · **Status:** accepted

## Context

The add screen is a calculator: a hand-drawn keypad with digits, `+`, `−`,
backspace, and — until now — a filled green tick in the bottom-right corner that
did two different jobs. With a sum pending it folded the sum; otherwise it wrote
the expense to the database.

That is one key with two meanings, and nothing on screen said which one a tap
was about to have. Worse, it sat exactly where a calculator puts `=`, in the
exact colour and shape that every other app uses for a commit button. The
feedback that started this was "I don't know where to approve the expense
finally to have it added" — from someone who had been using the app for weeks.

There was a second control involved: a one-line hint above the keypad that named
whatever was still missing ("Pick a category"). It was correct and almost
invisible — 13sp of primary-coloured text in a 24dp strip, doing the explaining
for a control 300 pixels away that gave no sign of being connected to it.

## Decision

**The keypad is only ever a calculator.** The tick becomes `=`, drawn as text in
the same grey as `+`, `−` and backspace, and enabled only when there is a sum to
fold. It cannot save anything.

**Saving is a full-width filled button below the keypad**, and it is the only
filled thing on the screen. Its label is the action and the amount together —
"Save expense · 100,00 zł" — because a save button is the last thing read before
money is written down, and the figure there catches a mis-tap that the word
"Save" never would. It reads the *evaluated* total, so `60 +` with `40` typed
offers 100,00 rather than the 40 on the display.

**When it cannot save, the button says what is missing** rather than sitting
there grey: the blocker hint moves onto the control it was explaining. Material
greys disabled labels to 38% opacity, which is right for a label nobody needs to
read and wrong for one that is the entire instruction, so the disabled colours
are overridden — disabled here means "unfinished", not "unavailable".

The two-taps-plus-the-amount target is unchanged: amount, category, save.

## Consequences

- The category grid ends up 14dp *taller*, not shorter. The button costs 62dp;
  the keypad gives back 44dp (280 → 236, still 52dp keys), the deleted hint row
  24dp and the amount display 8dp. All six categories stay visible even while a
  pending operator line is on screen, which was the thing to protect.
- `KeyAction.Confirm` is now `KeyAction.Equals`, and `Emphasis.Confirm` is gone
  along with the only primary-coloured key. Nothing else referenced either.
- Anyone who had learned the tick loses one tap-in-place: the button is a
  thumb's width lower. That is the price of the key having meant two things.

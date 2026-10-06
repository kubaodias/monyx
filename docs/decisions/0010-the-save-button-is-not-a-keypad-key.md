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

## Addendum, 2026-10-06: the save button is the bar's own button

The bar this decision created was right about what a save control should say
and wrong about where one more of them could go. The add screen ended up with
two full-width controls stacked at the bottom, a few millimetres apart: this
62dp bar, and under it the navigation bar carrying a green **Dodaj** button —
which, on the add screen, is the tab you are already standing on and does
nothing at all. The screen with the least room to spare in the app was spending
its last two rows on one action and a no-op.

So there is one button now. While the keypad is up, the bar's middle item is the
save button: the word **Zapisz** over a list with a line being added to it, and
a tap that commits the transaction. Everywhere else it is the plus and **Dodaj**
and takes you to the keypad.

The glyph is `PlaylistAdd`, not a tick. A tick was the first choice and it was
wrong for this app: `Check` is already the selection mark in three places — the
chosen account in the picker, the chosen member in Settings, an active filter
chip — so it would have meant "this one is chosen" there and "commit this" here.
Which is a smaller version of the two-meanings problem this decision was written
about. A row joining a list is what the tap does.

`AddSaveSlot` is the handover — the nav owns it, the keypad fills it in and
withdraws it on the way out, because the question "can this be saved yet"
belongs to the screen that knows what is missing.

**It is green whether or not the entry is finished**, and that overrules this
decision's own rule about greying. Greying does not survive the move: the bar's
middle item is the one coloured thing in a row of five, so a grey slab sitting
there for as long as it takes to type an amount reads as a broken tab rather
than as a button waiting. The owner put it as "Zapisz button should have green
background and be rounded like Dodaj" — which it is, because it is the same
button, changing what it says rather than sharing a slot with a second one.

What the rule was actually protecting is kept:

- **A refused tap says what is missing.** One line of text above the bar, naming
  the first gap in the order it is asked for: the account, the amount, the
  category. A household with no account yet gets that line without asking,
  because nothing else on the screen would explain a button that will not act;
  the other two are visible as emptiness — the amount is the largest thing here
  and the categories fill the middle — so they are named only once somebody has
  tapped and been refused. The old bar narrated all three from the first frame,
  which cost 62dp to describe what you are looking at.
- **The keypad still cannot save.** `=` is still only `=`.

What it gives up is the figure on the label. "Zapisz · 47,50 zł" was there to
catch a mis-tap, and 76dp of navigation bar cannot hold it. The amount is still
the largest thing on the screen directly above the button, which is the next
best place for the last thing read before money is written down — and the edit
sheet, which has no navigation bar to borrow anything from, keeps the full bar
and its figure unchanged.

## Addendum, 2026-10-06: the chosen family sits beside the figure, and leaves the grid

A consequence of the grid being a grid: the children of a family are appended
after every root, the note follows them, and a household with thirty categories
scrolls the chosen circle off the top of its own window. The screen is then a
figure with no answer to "on what".

So the chosen category moves onto the amount line — the same circle the grid
draws it with, in the same colour, with its name under it — and **leaves the
grid**. Two copies of the one answer a thumb apart, with the grid's copy
scrolling away under the one that does not, is the grid answering a question the
line above has already settled. What is left in the list is what might be
chosen next, which the family you are standing inside is not.

**It is always the family, never the subcategory.** Picking "Prąd" does not
change the line to "Prąd": the line says what the spending is about, which is
"Rachunki" either way, and the grid immediately below is already showing which
of the family it is filed under. It also stops the line changing twice for what
is one decision taken in two taps. `categoryMarkOf` is the rule, and the grid
hides exactly what that function returns, so the two cannot disagree about
which category has left the list.

Three details that are not arbitrary:

- **The name is under the circle, at 10sp, in a 60dp column.** Beside it, on the
  line's own axis, it would have taken 100dp from a figure that is 52sp and
  ellipsises at about nine glyphs — four-figure amounts would have started
  truncating. Family names are short; "Rachunki" fits twice over.
- **The slot is reserved whether or not anything is in it.** The figure is the
  thing being typed and must not jump sideways when a category is picked. It is
  end-aligned, so an empty slot shows nothing.
- **A ring, and a tap, when a subcategory is chosen.** The grid's own language:
  filled is "this is the answer", ringed is "the answer came from in here".
  Tapping it files the row on the family itself, which is how a subcategory is
  undone now that the parent's cell is not in the grid to tap — the affordance
  the parent cell used to provide, moved with it.

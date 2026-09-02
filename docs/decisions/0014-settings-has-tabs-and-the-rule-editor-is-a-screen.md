# 0014 — Settings has tabs, and the rule editor is a screen

**Date:** 2026-09-02 · **Status:** accepted

## Context

Settings had grown to eleven cards on one scroll: accounts, categories, every
repeating rule, the household, the invite code, language, sync, backup,
re-upload, build identity. Finding the invite code meant scrolling past the
rent. Nothing on the screen was wrong; there was just no answer to "where is
it" other than "keep going".

Setting up a repeating transaction was an `AlertDialog` holding nine controls
and two date pickers, in a box a third of the screen tall. Its body scrolled and
its buttons did not, so the field being filled in and the button being aimed for
were rarely both on screen — which is why the "what is still missing" hint had
already been moved *above* the first field rather than under the last one.

The rule list also carried "3 added so far" on every row. What a rule has
produced is history; the list is asked one question, which is whether it is
still going to happen.

## Decision

**Four tabs**, grouped by what someone opened Settings to do rather than by what
the code calls things:

| Tab | Holds |
|---|---|
| General | Accounts, categories, language |
| Repeating | The rules |
| Household | Members, invite code |
| Advanced | Sync, backup, re-upload, build identity |

`ScrollableTabRow`, not `TabRow`: four Polish labels do not fit four equal
columns on a narrow phone, and a fixed row answers that by shrinking the text
until it wraps mid-word.

**The rule editor is a full screen**, and the category is picked from a grid of
its own icons in its own colours — the same control the keypad uses — instead of
a dropdown of names. The chosen colour then runs through the header, the chips,
the active kind and the save button, so the rule being set up looks like the row
it will produce. Picking "Rent" out of a menu of eleven words is the same number
of taps and tells you nothing on the way past.

**The count comes off the row** and stays in the delete confirmation, which is
the one moment the distinction between stopping a rule and erasing what it did
actually matters.

## Consequences

- `RecurringSection` is a list and nothing else. It reports which rule to open
  through `onOpen` and `SettingsScreen` owns the editor state, because a
  full-screen editor is the screen's business, not a card's.
- The editor **replaces** the settings content rather than floating over it. A
  full-screen `Dialog` was tried first and got the screen height wrong twice:
  the window is positioned below the status bar while its content is measured
  against the whole display, which put the save button below the bottom edge
  where nothing could reach it. `decorFitsSystemWindows = false` and then a
  single `safeDrawingPadding` each moved it without fixing it. Swapping the
  content has no window of its own to mismeasure.
- The app's own navigation bar stays visible under the editor. That is the same
  arrangement the keypad already has — a save bar above the tabs — and it is
  what makes the inset behaviour predictable.
- `recurring_added_count` is gone from both languages.
- The editor keeps the anchor floor, the end-before-start guard and the
  month-end note from the dialog it replaces. None of that was the problem.

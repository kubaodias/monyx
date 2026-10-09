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
| General | Accounts, categories |
| Repeating rules | The rules |
| Users | Members, invite code |
| Language | The interface language |
| Advanced | Sync, backup, re-upload, build identity |

Language began as a card under General and became a tab of its own, on the
grounds that it is a different kind of question from "what are my accounts".
It is named for what is in it rather than for what might join it: **Region**
would be naming the tab after a currency setting that [0004] says will not
exist, the currency being fixed and the timezone the household's. If that is
ever reversed, the rename is one string.

`ScrollableTabRow`, not `TabRow`: five Polish labels do not fit five equal
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

[0004]: 0004-language-moves-currency-and-timezone-do-not.md

## Addendum, 2026-10-06: the account editor is a screen too

Same form, same reasons, two releases later. Adding or editing an account asked
eight things — name, balance, a row of icons, a row of colours, a currency
dropdown, and a switch with an explanation under it — inside an `AlertDialog`
whose body scrolled and whose Save button sat outside that scroll. So the field
being filled in and the button being aimed for were never both on screen, which
is the complaint in the Context section above, written about the rule editor.

It is now `AccountEditor`: a `Scaffold` with the title and a Close in the top
bar, the controls in one scrolling column, and the save button pinned to the
bottom — swapped in for the settings content rather than floating over it, with
a `BackHandler` so the gesture closes the form and lands back on Settings.

Two things came out of the move rather than going in with it:

- **The open state moved up.** `AccountsSection` is drawn inside a `LazyColumn`
  item, and a full-screen `Scaffold` cannot live in one, so Settings holds the
  open editor and the section only reports that one was asked for. `AccountSeed`
  is what it reports — the same shape as `RuleSeed`, for the same reason: "add"
  and "edit this one" are both open states and only one has a row behind it.
- **The save button says what is missing**, like every other save button in the
  app. A nameless account is the one thing the form will not take, and the
  button now says so instead of greying out in silence.

The confirmations that live beside it — archive, delete — stay dialogs. A
question with two answers is what a dialog is for.

## Addendum, 2026-10-09: seven tabs, and the rule editor asks in the keypad's order

**"Ogólne" is two tabs now, Konta and Kategorie, and Waluty is a third.** The
first tab held accounts and categories together because they are both lists of
things the household names — a fact about their shape, not about why anybody
opens them. An account is edited when a bank balance has drifted; a category
when the way the household thinks about its spending has changed. Those errands
are months apart, and the accounts list has grown two more blocks since this
decision (outside the summary, archived), so the tab opened on three groups of
accounts with the categories somewhere below them.

Waluty is new rather than moved: which of the nine currencies the pickers bother
to offer. It is not the Region tab 0004 refused — the REPORTING currency is
still fixed and still złoty, and nothing on that tab can change a total. See
ADR 0022's addendum.

**The rule editor now asks its first three questions in the keypad's order,
with the keypad's controls.** Account, then expense-or-income, then the amount.
It had the account first already but as a grid of 72dp circles, the kind toggle
below the amount, and its own hand-rolled copy of that toggle:

- The account is the shared `ContextChip` + `AccountPickerDialog`, as on the
  keypad. The circle grid was right on a screen with three controls and wrong on
  one with nine: two rows of circles for the question that is already answered
  nine times in ten pushed the amount — the thing somebody came here to type —
  below the fold on a short phone.
- The kind toggle is one `KindSelector` shared with the keypad, which is also
  where it got its arrows (`NorthEast` out, `SouthWest` in). Two copies had
  already drifted in their padding, and the rule editor's is the one people meet
  second — so the second screen asking "wydatek or przychód" looked like a
  slightly different question.
- The amount sits directly under it, with the account's currency on its label.

**Its default account is the household's, not the first row of the table.** It
read `accounts.firstOrNull()`, which is `sortOrder, name` with the groups
interleaved — so it offered a savings account called "Oszczędności" ahead of the
current account everything is spent from. That is the third screen to make this
mistake (see `defaultAccount`) and the worst place to make it: a rule writes to
the wrong account every month until somebody notices.

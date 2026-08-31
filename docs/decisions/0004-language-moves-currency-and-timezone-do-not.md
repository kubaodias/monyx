# 0004 — Language is switchable; the currency and the timezone are not

**Date:** 2026-08-29 · **Status:** accepted

## Context

The interface became multilingual, with English as the source and default and
Polish as a translation, switchable from Settings.

Translating the strings is the obvious half. The half that decides whether the
result feels finished is everything shaped by a locale that is *not* a string:
thousands separators, decimal points, month names, the currency symbol, and the
timezone that decides which month an expense falls into.

Treating "locale" as one switch gets at least one of these wrong. Formatting
money in a fixed Polish locale under an English interface prints `5 127,00` on a
screen that says "Balance", which reads as a bug. Letting the locale carry
everything is worse: it would move the currency and the timezone too.

## Decision

Language governs **wording and number shape**. It does not govern **what the
money is** or **where the household lives**.

Follows the chosen language:

- Number grouping and decimals — `5 127,00` in Polish (with U+00A0), `5,127.00`
  in English. `Money` and `AmountInput.display()` both read this from the same
  `DecimalFormatSymbols`, so the keypad and every formatted amount agree
  character for character.
- Month and day names — `Sierpień 2026` / `August 2026`.

Does not follow it:

- **The currency.** `zł` is a symbol, not a word, and is identical in every
  translation. It is marked `translatable="false"`.
- **The timezone.** `Dates.ZONE` stays `Europe/Warsaw`. Months are bucketed on
  a local date precisely so an expense entered at 01:30 on 1 September in Warsaw
  does not fall into August; that reasoning is about geography and does not
  change because somebody switched the interface to English.
- **Category and account names.** These are rows that sync to every device, not
  resources. `new-household.mjs --lang` picks their language once, at creation.
  The family renames them anyway, and a rename must not be undone by another
  member switching language.

## Consequences

Formatters cannot be built once at class-init. `AppCompatDelegate.setApplicationLocales`
changes the default locale in a live process, so a `val NumberFormat` would keep
printing the previous language until the app was force-stopped. `Money` and
`Dates` therefore cache their formatters against the locale that built them and
rebuild on a miss.

`Money.parseToMinor` has to read what either language types, and the two
languages disagree about what a comma means. It decides rather than assumes: with
both separators present the last one is the decimal point; a lone separator that
matches the locale's grouping character and sits in front of exactly three digits
is grouping. Without that rule an English "1,234" parses as 1.234 — a
hundredfold error in a money field, silently.

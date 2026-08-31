# 0005 — One name after all: Monio everywhere

**Date:** 2026-08-31 · **Status:** accepted · **Supersedes:** 0003

## Context

0003 split the naming: `Monio` for every developer-facing identifier, `Monia`
for everything a person reads. Two days of the interface actually existing was
enough to show the split costing more than it returned.

The launcher said one thing and the repository, the deploy logs and every error
message said another. Asking "is Monia working?" and reading a log that only
ever says `monio-api` is a translation step in the middle of a sentence, for a
household of two people who are also the operators.

The Polish copy made it concrete. `Witaj w Monii` requires declining the name,
and `Monio` does not decline the same way — a masculine-sounding `-o` noun in
the locative is not `Monii`. Keeping the split meant either keeping a second
name purely to make one greeting scan, or writing a greeting that fights the
language.

## Decision

**Monio is the only name.** The launcher label, the notification title, the
permission copy and the onboarding screen all say Monio, matching the
identifiers that already did.

The welcome line no longer names the app at all: `Let's get started` /
`Zaczynajmy`. A first screen does not need to announce the name that the user
just tapped on the launcher to get there, and not naming it sidesteps the
declension entirely rather than working around it.

The reservation of a separate name for the future voice assistant is dropped.
If it turns out to want one, that is a decision to make when it exists and has
something to be called after.

Neither name's expansion is recorded, here or anywhere else in the repository.
That was deliberate in 0003 and remains so.

## Consequences

The mitigation 0003 relied on — "they are not interchangeable in any single
sentence" — was true and still insufficient: being unambiguous is not the same
as being effortless, and the reader pays the cost on every sentence rather than
the writer paying it once.

Nothing had to be recreated. The rename is `app_name`, `budget_alert_title`,
`notifications_permission_rationale`, `onboarding_title` in both locales, and
the fallback `notification.title` in `fcm.ts`. Every identifier, cloud resource,
package and keystore was already Monio, which is the half of 0003 that was
right and is what makes this reversal cheap.

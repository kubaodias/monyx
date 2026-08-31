# 0007 — The name is Monyx

**Date:** 2026-08-31 · **Status:** accepted · **Supersedes:** 0005

## Context

0005 settled the naming argument by collapsing 0003's two names into one:
Monio, identifiers and user-visible copy alike. That held for a day.

What it had not done was check whether the name was free. It is not. Monio and
Monia are both already published on Google Play in the same category, by other
people. For an app installed by sideloading onto four phones in one family that
is not a legal problem and is not being treated as one — nobody is competing
with anybody. It is a problem for a name that has to be said out loud and typed
into a search box by the people using it.

Monyx also fixes, rather than argues around, the flaw that produced both
earlier decisions. 0003 split Monio from Monia and 0005 reversed the split, and
the reason either was ever necessary is that the two names are one unstressed
vowel apart and nobody can hear the difference. `MO-nyks` cannot be confused
with anything the project has been called before. The Polish declension problem
that 0005 sidestepped by removing the name from the greeting does not arise
either: `Monyx` is an ordinary masculine noun there.

The `-yx` is a nod to Telnyx, which runs the backend. It is a joke, not an
architectural commitment, and it survives the platform if the platform is ever
left.

The name is not clear for publication. Monyx Wallet, a payments app owned by
Nayax, exists on both app stores and holds `monyx.com`. That is worth ten
minutes of a lawyer's time before anything is ever listed on Play, and worth
nothing at all while this stays a private build.

## Decision

**Monyx is the only name**, on both sides of the line 0003 tried to draw:
`applicationId com.monyx`, the Kotlin package `com.monyx.**`, the Gradle project,
the Room file `monyx.db`, the Telnyx function `monyx-api`, the SQL database
`monyx`, the KV namespace `monyx-kv`, the release keystore, the launcher label,
the notification title and every line of copy.

0003 and 0005 keep the old names in their own text. They are the record of
decisions that were actually taken, and rewriting them to say Monyx would make
0003's title describe a split that never happened under that name. A superseded
ADR is history, not documentation.

## Consequences

`applicationId` is the expensive half, exactly as 0003 warned it would be once
an APK had landed on a family phone. `com.monio` and `com.monyx` are two
different apps as far as Android is concerned: there is no update path. Every
phone uninstalls, reinstalls and enrols again, and enrolment consumes an invite,
so a fresh code has to be minted for each person first. The local Room database
goes with the uninstall and is re-pulled from the server, so nothing is lost
that had already synced — and the reason to check that everything *has* synced,
before anyone uninstalls, is that the pending queue is the one thing that
cannot be recovered.

The cloud resources still cannot be renamed. The CLI has no rename for
functions, SQL databases or KV namespaces on v0.5.1, which is the same wall
0003 hit and worked around by recreating the resources while they were empty.
They are not empty now, so the rename means creating `monyx`, `monyx-kv` and
`monyx-api` and copying the data across, with the old three left in place and
untouched as the rollback. Secrets are organisation-scoped and bind by name, so
they do not have to be re-entered.

Until that is done `Api.BASE_URL` still names `monio-api`, because a Telnyx
invoke URL is minted from a function's name and id and there is no monyx-api to
point at yet. That is the one place the old name survives on purpose.

This is the third naming decision in three days, and the last one that is cheap.
Doing it again after anything reaches Google Play costs an installed base rather
than an afternoon.

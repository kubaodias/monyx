# 0003 — Monio is the service, Monia is what the family sees

**Date:** 2026-08-29 · **Status:** accepted

## Context

The project shipped under a single name, `monia`, used for everything at once:
the Gradle project, the Android `applicationId`, the Telnyx function, the SQL
database, the KV namespace, the Room file, the launcher label and the wording in
the Polish copy.

Two problems surfaced together. The name is awkward to spell for an
English-speaking reader, which matters for the half of it developers type. And
PRD §12 introduces a voice assistant, which needs a name of its own and would
otherwise have inherited the same one, leaving no way to say which of the two
anything referred to.

Renaming was free at this exact moment and stops being free shortly after:
`applicationId` hardens the instant an APK lands on a family phone. Changing it
later is a different app — no update path, and an uninstall that destroys
anything not yet synced.

## Decision

Split the name along the line the architecture already draws.

**Monio** is the service and every developer-facing identifier: the repository,
the Telnyx function `monio-api`, the SQL database `monio`, the KV namespace
`monio-kv`, the Gradle project, `applicationId com.monio`, the Kotlin package
`com.monio.**`, the Room file `monio.db`, and the release keystore.

**Monia** is what a person sees and speaks to: the launcher label, the
notification title, the name in the onboarding and permission copy, and — when
PRD §12 is built — the voice assistant.

Neither name's expansion is recorded, here or anywhere else in the repository.
That is deliberate and is not an omission to be helpfully corrected later.

## Consequences

§4 draws a hard line between a server that is deliberately dumb — a sync relay,
with every read and write local — and the intelligence layer that §12 puts in a
Telnyx AI Assistant. The two names now sit on either side of that line, so which
half of the system a sentence is about is legible from the noun it uses.

The cloud resources could not be renamed in place; the Telnyx CLI has no rename
for functions, SQL databases or KV namespaces. They were recreated under the new
names while the old ones were still unshipped and empty, which is why this had to
happen before the first deploy rather than after.

The cost is that two names must be kept straight. The mitigation is that they
are not interchangeable in any single sentence: an identifier is always Monio, a
piece of user-visible copy is always Monia.

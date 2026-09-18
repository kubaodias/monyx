# 0003 — Monyx service identifiers

**Date:** 2026-08-29 · **Status:** accepted

## Context

The Android app and backend share one project identity. Consistent identifiers
make it easier to connect build output, deployment logs and local storage to
the application they belong to.

## Decision

Use `monyx` for developer-facing identifiers: the Gradle project, Android
application ID `com.monyx`, Kotlin package `com.monyx.**`, Room file `monyx.db`,
Telnyx function `monyx-api`, SQL database `monyx`, KV namespace `monyx-kv`,
and release keystore.

Deployment addresses come from local configuration rather than source code.
They identify a deployment, while these names identify the project.

## Consequences

Keep the application ID and signing key stable once the app is installed:
Android relies on both to recognise an update. Debug builds use
`com.monyx.debug` so they can coexist with release builds.

Interface wording can follow the selected language without changing these
identifiers. See [0005](0005-one-name-after-all.md) for interface naming and
[0007](0007-the-name-is-monyx.md) for the project name.

# 0007 — The name is Monyx

**Date:** 2026-08-31 · **Status:** accepted

## Context

The app, repository and backend need a consistent identity that people can
recognise in the interface, build configuration and deployment logs.

The `-yx` is a nod to Telnyx, which runs the backend. The name does not depend
on keeping that platform.

## Decision

**Monyx is the project name.** It identifies the Android application
`com.monyx`, the Kotlin package `com.monyx.**`, the Gradle project, the Room
file `monyx.db`, the Telnyx function `monyx-api`, the SQL database `monyx`,
the KV namespace `monyx-kv`, and the release keystore.

The interface uses Monyx in English and Portfel in Polish. The onboarding
heading is `Let's get started` / `Zaczynajmy`, so the greeting does not need
to repeat the launcher label.

The backend address comes from local build configuration. Deployment addresses
are not part of the project identity and are not hardcoded in the client.

## Consequences

Keep `applicationId` and the release signing key stable: Android updates rely
on both. Debug builds use `com.monyx.debug` so they can coexist with release
builds without replacing their local data.

Verify a new database with a write probe before trusting it with data. The first
Monyx database returned `ShipError: write committed but snapshot ship failed`:
writes could be read back from memory without ever persisting, while
`GET /health` continued returning 200. The end-to-end smoke test exposed the
failure, and the database had to be rebuilt. A successful health check alone
does not establish durable storage.

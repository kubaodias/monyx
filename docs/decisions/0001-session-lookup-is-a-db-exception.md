# 0001 — Session lookup is a fourth db.ts export

**Date:** 2026-08-29 · **Status:** accepted

## Context

`db.ts` is the single isolation boundary, and it was specified to export
exactly three things — `forHousehold(id)`, `resolveInvite(code)` and
`allHouseholds()` — with two honest exceptions: enrolment has no household
yet, and the daily sweep spans all of them.

Implementing `/sync/push` revealed a third, unavoidable pre-household lookup.
Every authenticated request carries only a bearer token. Turning that token into
a `household_id` requires reading `devices` joined to `members`, and there is no
household id available to scope that read — it is the very thing being derived.

## Decision

Add `resolveSession(token)` to `db.ts` as a fourth export, documented in the
same style as the other two exceptions. Nothing else changes: the function
returns a `household_id`, and every subsequent query goes through
`forHousehold()` with it.

## Consequences

The rule survives in the form that matters — the exceptions stay countable, and
nothing outside `db.ts` imports `env.DB`. The isolation property is unchanged:
`resolveSession` is keyed on a 256-bit token, and it is the only way to obtain a
household id from a request.

The alternative — resolving the token inside `auth.ts` against a raw binding —
would have put a second `env.DB` importer in the codebase, which is exactly what
the isolation boundary is written to prevent.

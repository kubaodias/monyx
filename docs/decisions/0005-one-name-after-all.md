# 0005 — A consistent name in the interface

**Date:** 2026-08-31 · **Status:** accepted

## Context

People encounter the app through its launcher label, notifications, permission
copy and onboarding. Those surfaces should use a consistent name within each
language.

## Decision

Use Monyx in English and Portfel in Polish. Resolve the app label and related
interface copy through the language resources; keep the developer-facing
identifiers defined in [0003](0003-monyx-service-identifiers.md) stable.

The onboarding heading is `Let's get started` / `Zaczynajmy`. It does not need
to repeat the launcher label or introduce language-specific declensions of the
project name.

## Consequences

Changing the interface language changes the displayed name without changing
the application ID, backend resources, database file or signing key.
Category and account names remain household data, as described in
[0004](0004-language-moves-currency-and-timezone-do-not.md).

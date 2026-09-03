# 0018 — The assistant gets a window, not a key

**Date:** 2026-09-03 · **Status:** accepted

## Context

A voice assistant on Telnyx AI Assistants answers a phone number and can be
asked, in Polish, what the household has and where it went. The assistant
itself is configuration held on Telnyx — model, voice, transcription,
instructions, the phone number binding — and none of that belongs in this
repository. What does belong here is the only part it cannot do alone: reaching
the data.

`env.DB` is bound to the function and there is no public SQL API, so the
assistant can only see a household through a route this codebase serves. That
route is a new kind of caller. Every existing one presents a device session
token minted at enrolment; a phone line presents a number that anyone can claim
to be calling from.

## Decision

**Two routes, and the split between them is the security design.**

`POST /voice/context` runs at call setup, before the assistant speaks. It knows
only the caller's number, so it returns only identity — a name to greet with —
and a **ticket**. `POST /voice/digest` exchanges that ticket plus a **PIN** for
the household's month.

**No financial data crosses the first boundary.** The obvious design preloads
the digest into the system prompt at call setup, and it is a mistake: caller ID
is not a credential, and anything placed in a prompt cannot be withdrawn from
it later. A PIN checked after the numbers are already in context protects
nothing.

**The PIN is compared in the function, never in the instructions.** A model told
to withhold something it has already been given will eventually be talked out of
it. A model that was never given it cannot be. This is the same reason the
server returns codes and the client owns every user-facing string: the boundary
has to be a mechanism, not a wording.

**One digest, not a tool per question.** A voice call charges for silence, and a
household's month is a few kilobytes — every account with its balance, the
month's income and spend against the plan and the previous month, every budget
with its percentage, spending per root category, and the last ten transactions.
It arrives in one response and every follow-up is answered from context. The
digest is computed during call setup and parked in the ticket, so redeeming it
costs one KV read; when that precompute fails, `/voice/digest` rebuilds it,
because the ticket is what the call cannot proceed without and the cache is only
an optimisation.

**XML, not prose and not JSON.** Prose invites the model to read a table down
the phone, which is unlistenable. Tags are unambiguous about which number
belongs to which label, survive being read back out of order, and cost fewer
tokens than JSON once every key is repeated per row. Amounts are decimal
strings, because the assistant says them out loud and nobody says "three
million five hundred eleven thousand and one minor units".

**Read-only, by construction.** This module issues `SELECT` and nothing else.
Adding a transaction by voice is the feature worth having, and it writes through
the sync epoch and needs a `created_by` member without a device — a design
question, not an increment.

**Three defences on a four-digit PIN, because four digits is not many.** Three
attempts per ticket; an allowlist that decides whether a ticket is minted at
all; and a global cap of ten failures an hour across every ticket. The third is
the one that matters: the first two make minting tickets expensive, and the
counter holds even if both are wrong.

**The PIN is keyed, not spoken.** Inbound DTMF reaches the assistant by default,
so the caller presses four digits rather than saying them. Tones cannot be
misheard, do not depend on the transcription model's Polish, and are not said
out loud in a room with other people in it. A spoken PIN is still accepted,
because a caller driving cannot look at the keypad — the server strips
everything that is not a digit either way, so `1986#` and "one nine eight six"
arrive as the same four characters.

**The allowlist is a secret, not a table.** It maps a caller to a household and
carries that caller's PIN. Phone numbers are personal data, and a secret keeps
them out of the repository, out of the database, and out of the backups that get
copied around — which is also why no number, PIN or persona appears in this
file.

**The call-setup token travels in the URL.** The assistant's webhook field is a
URL and there is nothing else to put a secret in. The tool webhook does support
headers, and uses one.

## Consequences

- Signature verification is the follow-up. Telnyx signs these webhooks
  (Ed25519), and `index.ts` has kept the raw request body intact since M1 for
  exactly that — the router deliberately does not parse a central body. It is
  not implemented yet because Ed25519 support in the edge runtime is unverified,
  and the URL token plus the failure counter is adequate for a proof of concept.
  Until then, anyone holding the URL can mint tickets; nobody can redeem one.
- The PIN costs the caller a turn. It is asked for before any number is fetched,
  so the first answer of a call is one round trip behind — and every answer
  after it is free.
- An unknown caller is told nothing: not the household's name, not that one
  exists. The assistant's own instructions handle the goodbye.
- Reordering, renaming or recolouring never reaches this path; the digest is
  rebuilt per call, so it has no staleness of its own.
- `budgetStatuses` now has a second caller. It stays the only implementation of
  the budget query — the assistant reports the same percentage the phone shows
  and the alert fires on, because there is one place it can come from.
- The digest rolls a subcategory up into its parent, matching how a budget
  covers its children. "How much on Home" and "what is left on Home" agree.
- The app carries a call button, and the number it dials is build configuration
  read from an untracked file rather than a constant in the source. Without it
  the button does not render — a checkout of this repository cannot dial
  anywhere, which is the point.

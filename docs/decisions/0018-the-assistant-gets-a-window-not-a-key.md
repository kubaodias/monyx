# 0018 — The assistant gets a window, not a key

**Date:** 2026-09-03 · **Status:** accepted · **Amended:** 2026-09-03, the PIN removed

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

**The allowlist is the only gate, and the month is preloaded.**

`POST /voice/context` runs at call setup and returns the household's month
whole, into the system prompt, before the assistant speaks. `POST /voice/digest`
re-reads the same digest mid-call. An unknown caller gets neither.

### The PIN, and why it is gone

The first version of this decision required a four-digit PIN, compared in the
function, before any figure was released. That was the right shape for the
threat — and it was **removed on request** on the same day it shipped, which is
the owner's call to make about their own household's numbers.

What matters is that the two designs are **mutually exclusive**, and the reason
is worth keeping written down:

- A secret placed in a system prompt cannot be withdrawn from it. Anything gated
  *after* a preload is guarded only by the model's willingness to keep it, and a
  model told to withhold what it has already been given will eventually be
  talked out of it. That is a wording, not a boundary.
- So a PIN that means anything forces the digest to arrive *after* it — which
  costs a round trip at the start of every call, and makes the assistant ask for
  four digits before it will answer a question about groceries.

Dropping the PIN therefore buys back the preload: the first question now costs
nothing, because the answer was in the prompt before the phone finished ringing.
It is a real trade, not a simplification. **Caller ID is not a credential** — a
spoofed ANI now reaches the household's finances, and nothing in this codebase
would notice. The mitigations that remain are the allowlist, the context token
on the webhook URL, and the fact that the routes are read-only.

**If it comes back, it comes back as a second factor before the preload** — a
PIN collected by the platform at call setup, not a value the model holds. That
is the only shape that gets both.

**One digest, not a tool per question.** A voice call charges for silence, and a
household's month is a few kilobytes — every account with its balance, the
month's income and spend against the plan and the previous month, every budget
with its percentage, spending per root category, and the last ten transactions.
It arrives in one response — the call-setup one — and every question of the call
is answered from what is already in the prompt.

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

**The allowlist fails closed.** A secret that will not parse, or parses to
nothing, admits no one rather than everyone — it is now the only gate, so the
failure mode of its own configuration is part of the design and has a test.

**The allowlist is a secret, not a table.** It maps a caller to a household.
Phone numbers are personal data, and a secret keeps them out of the repository,
out of the database, and out of the backups that get copied around — which is
also why no number or persona appears in this file.

**The call-setup token travels in the URL.** The assistant's webhook field is a
URL and there is nothing else to put a secret in. The tool webhook does support
headers, and uses one.

## Consequences

- Signature verification is the follow-up. Telnyx signs these webhooks
  (Ed25519), and `index.ts` has kept the raw request body intact since M1 for
  exactly that — the router deliberately does not parse a central body. It is
  not implemented yet because Ed25519 support in the edge runtime is unverified.
  It matters more since the PIN went: the URL token is now one of only two things
  standing in front of the data, so anyone holding that URL can read the month by
  claiming to call from an allowlisted number.
- The ticket survives the PIN's removal. It is no longer a capability worth
  anything on its own, but it is what lets her re-read mid-call, and what the
  tool needs on a call where the setup webhook missed its timeout and the prompt
  has no digest in it.
- The allowlist is re-checked on every mid-call re-read rather than trusted from
  the ticket, so a number removed from it stops working on the next question
  rather than the next call.
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

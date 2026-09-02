# 0017 — The balance you type is the one you have

**Date:** 2026-09-02 · **Status:** accepted

## Context

`accounts.initialBalanceMinor` is the figure an account is assumed to have held
before this app knew about it. Every balance shown anywhere is that number plus
what the transactions have done since:

```
balance = initialBalanceMinor + Σ(income) − Σ(expense) ± transfers
```

The accounts card in Settings showed the left-hand side of that equation and
the edit dialog asked for the right-hand one, under the label "Opening
balance". Both are correct and neither is wrong, which is exactly why it read
as broken: a row saying 35,110.01 opened a field saying 4,200.00, and typing
today's real balance into it moved the account by the difference all over
again. The account then showed a number nobody had typed anywhere.

Nobody knows what their current account held on the day they installed a budget
app. They know what the banking app says this morning.

## Decision

**The dialog asks for the current balance, and works the opening one out.**

```
initialBalanceMinor = typed − movements
movements           = balance − initialBalanceMinor
```

Saving the number already displayed is therefore a no-op, which is the property
that was missing. Typing what the bank says makes the app agree with the bank
immediately, whatever history is already in it.

**The opening balance is shown, not hidden.** A caption under the field —
"Opening balance: 4,200.00 zł" — recomputes as the figure is typed. The
derivation is the sort of thing that looks like a bug the first time an account
is reconciled and the number moves on its own, so it is on screen rather than in
this file. It appears only when there are movements: for a new account the two
figures are the same and repeating it would read as a mistake.

**A leading minus parses.** `Money.parseToMinor` now honours `-` and U+2212 at
the front. A card that has been used is money owed, and asking for the balance
the bank shows means accepting the one it shows for a card. Every other caller
of `parseToMinor` already refuses anything that is not positive, so a negative
budget limit or transaction is still impossible.

**Transactions are not touched.** The correction lands entirely on the opening
figure — the one number in the account that is an assumption rather than a
record. Rewriting history to make a total come out right is what a ledger must
never do.

## Consequences

The dialog can no longer be built from an `AccountEntity` alone: it needs the
running balance too, so the accounts section carries `AccountRow` (entity +
balance) into it rather than the entity. Adding an account is the same dialog
with `movements = 0`, which is true by construction — a new account has no
transactions.

An account whose balance is exactly zero opens with an empty field, and saving
it leaves the balance at zero. That is the right answer, and it is the one case
where the field cannot tell "I mean zero" from "I did not touch this".

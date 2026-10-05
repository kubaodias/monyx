-- What currency ONE transaction is denominated in.
--
-- 0008 put a currency on the account, which assumed every row on an account was
-- in that account's money. A household abroad breaks that in the ordinary case:
-- you pay 15 EUR with a złoty card, or buy something in dollars on a euro
-- account. The amount that happened is 15 EUR whatever account it settled on.
--
-- So this becomes the authority for conversion, and accounts.currency shrinks to
-- two smaller jobs: the unit of the account's OPENING balance, and the currency
-- a new entry on that account starts in. The client's ledger_pln view converts
-- on THIS column.
--
-- Old clients neither send nor understand it, and the DEFAULT is what lets them
-- keep pushing transactions through an INSERT that now names it.
ALTER TABLE transactions ADD COLUMN currency TEXT NOT NULL DEFAULT 'PLN';

-- Backfilled from the account, because that is what conversion ALREADY used.
--
-- This is a refactor of where the currency lives, not a change to anybody's
-- figures, and the test of that is that no total moves. Between 0.22.0 and now,
-- a row on a euro account was converted as euro — so leaving these at the 'PLN'
-- default would silently restate every one of them as złoty and move balances
-- and budgets that somebody has already looked at and reconciled.
--
-- For accounts in złoty this is a no-op, which is all of them but one.
UPDATE transactions
   SET currency = COALESCE(
         (SELECT a.currency FROM accounts a WHERE a.id = transactions.account_id),
         'PLN'
       );

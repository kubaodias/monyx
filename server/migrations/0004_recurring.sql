-- Recurring transactions: the fixed monthly shape of a household's money.
--
-- Rent, the phone bill, the loan payment, salary. Retyping those by hand every
-- month is the thing that stops a budget being planned at all — the plan screen
-- can ask "how much is there altogether" but the known, predictable costs have
-- to be re-entered before it means anything.
--
-- THE ANCHOR IS THE SCHEDULE. There is no day_of_month, no day_of_week and no
-- month_of_year: starts_on is the first occurrence, and every later one is
-- derived from it — weekly is anchor + 7n, monthly is the anchor's day in each
-- following month, yearly is the anchor's month and day. Three separate day
-- columns would let a row say freq = 'weekly' AND day_of_month = 15, and the
-- validator would then have to reject a state the schema should never have been
-- able to express.
--
-- A monthly rule anchored on the 29th, 30th or 31st CLAMPS to the last day of a
-- shorter month. That is a decision, not a rounding artefact: a rule anchored on
-- the 31st means "the end of the month", and skipping February is not what
-- anyone means by it.
CREATE TABLE recurring_rules (
  id            TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  kind          TEXT NOT NULL CHECK (kind IN ('expense','income')),
  amount_minor  INTEGER NOT NULL CHECK (amount_minor > 0),
  account_id    TEXT NOT NULL REFERENCES accounts(id),
  category_id   TEXT REFERENCES categories(id),
  note          TEXT,
  freq          TEXT NOT NULL CHECK (freq IN ('weekly','monthly','yearly')),
  starts_on     TEXT NOT NULL,              -- 'YYYY-MM-DD', the anchor
  ends_on       TEXT,                       -- 'YYYY-MM-DD' inclusive; NULL = forever
  created_by    TEXT NOT NULL REFERENCES members(id),
  created_at    INTEGER NOT NULL,
  seq           INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX rec_by_seq ON recurring_rules (household_id, seq);

-- Which rule produced a transaction, if any.
--
-- Added as a nullable column rather than by extending transactions.source,
-- because `source` carries CHECK (source IN ('manual','voice','receipt')) and
-- SQLite cannot ALTER a CHECK — adding 'recurring' there would mean recreating
-- the table and copying every row of the household's history to gain a label.
-- A foreign key says more anyway: it names the rule, not just the mechanism.
--
-- Old clients neither send nor understand it, and the absence of a DEFAULT is
-- fine where NULL is already the meaning of "nobody said".
ALTER TABLE transactions ADD COLUMN recurring_rule_id TEXT REFERENCES recurring_rules(id);

CREATE INDEX tx_by_rule ON transactions (household_id, recurring_rule_id);

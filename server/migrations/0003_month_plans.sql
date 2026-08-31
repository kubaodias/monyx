-- The monthly plan: how much there is to spend in a month, as a single figure.
--
-- Budgets answer "how much for groceries". This answers the question that has
-- to come first — "how much is there altogether, and how much of it have I not
-- assigned to anything yet". Without it the budget screen can show what each
-- category is allowed but never whether the allowances add up to money that
-- actually exists.
--
-- One row per household per month. UNIQUE, like budgets, so two phones planning
-- the same month offline resolve in place rather than duplicating; sync.ts
-- carries the matching ON CONFLICT clause.
CREATE TABLE month_plans (
  id            TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  period        TEXT NOT NULL,              -- 'YYYY-MM'
  planned_minor INTEGER NOT NULL,
  seq           INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  UNIQUE (household_id, period)
);

CREATE INDEX plan_by_seq ON month_plans (household_id, seq);

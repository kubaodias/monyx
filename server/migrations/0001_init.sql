-- Monyx initial schema.
-- Migrations are numbered and untouchable once applied: a schema fix is a new
-- file, never an edit to this one.

CREATE TABLE households (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  next_seq    INTEGER NOT NULL DEFAULT 1,
  epoch       INTEGER NOT NULL DEFAULT 1,   -- bumped by hand after a restore
  created_at  INTEGER NOT NULL
);

CREATE TABLE members (
  id            TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  name          TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  seq           INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE devices (
  id            TEXT PRIMARY KEY,
  member_id     TEXT NOT NULL REFERENCES members(id),
  session_token TEXT NOT NULL UNIQUE,
  fcm_token     TEXT,
  label         TEXT,
  last_seen_at  INTEGER,
  created_at    INTEGER NOT NULL
);

CREATE TABLE accounts (
  id                    TEXT PRIMARY KEY,
  household_id          TEXT NOT NULL REFERENCES households(id),
  name                  TEXT NOT NULL,
  icon                  TEXT,
  color                 TEXT,
  initial_balance_minor INTEGER NOT NULL DEFAULT 0,
  sort_order            INTEGER NOT NULL DEFAULT 0,
  seq                   INTEGER NOT NULL,
  deleted               INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE categories (
  id            TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  parent_id     TEXT REFERENCES categories(id),
  name          TEXT NOT NULL,
  icon          TEXT,
  color         TEXT,
  kind          TEXT NOT NULL CHECK (kind IN ('expense','income')),
  sort_order    INTEGER NOT NULL DEFAULT 0,
  seq           INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE transactions (
  id                  TEXT PRIMARY KEY,
  household_id        TEXT NOT NULL REFERENCES households(id),
  kind                TEXT NOT NULL CHECK (kind IN ('expense','income','transfer')),
  amount_minor        INTEGER NOT NULL CHECK (amount_minor > 0),
  account_id          TEXT NOT NULL REFERENCES accounts(id),
  transfer_account_id TEXT REFERENCES accounts(id),
  category_id         TEXT REFERENCES categories(id),
  note                TEXT,
  occurred_at         INTEGER NOT NULL,     -- epoch ms, for ordering
  occurred_on         TEXT NOT NULL,        -- 'YYYY-MM-DD' local date, for bucketing
  created_by          TEXT NOT NULL REFERENCES members(id),
  source              TEXT NOT NULL DEFAULT 'manual'
                        CHECK (source IN ('manual','voice','receipt')),
  created_at          INTEGER NOT NULL,
  seq                 INTEGER NOT NULL,
  deleted             INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE budgets (
  id            TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  category_id   TEXT NOT NULL REFERENCES categories(id),
  period        TEXT NOT NULL,              -- 'YYYY-MM'
  limit_minor   INTEGER NOT NULL,
  seq           INTEGER NOT NULL,
  deleted       INTEGER NOT NULL DEFAULT 0,
  UNIQUE (household_id, category_id, period)
);

-- Notification dedupe: one push per threshold, per category, per month.
-- Keyed on the category rather than the budget row: editing a limit mid-month
-- inserts a NEW budget row (see "Budgets carry forward"), and a budget_id key
-- would let every threshold fire a second time on the new id. The period is in
-- the key because one budget row serves many months — without it, August would
-- silence September.
CREATE TABLE budget_alerts (
  household_id TEXT NOT NULL REFERENCES households(id),
  category_id  TEXT NOT NULL REFERENCES categories(id),
  period       TEXT NOT NULL,               -- 'YYYY-MM'
  threshold    INTEGER NOT NULL,            -- 80 or 100
  notified_at  INTEGER NOT NULL DEFAULT 0,  -- 0 = claimed but not yet delivered
  PRIMARY KEY (household_id, category_id, period, threshold)
);

CREATE TABLE invites (
  code          TEXT PRIMARY KEY,
  household_id  TEXT NOT NULL REFERENCES households(id),
  expires_at    INTEGER NOT NULL,
  used_at       INTEGER
);

CREATE INDEX tx_by_seq      ON transactions (household_id, seq);
CREATE INDEX tx_by_period   ON transactions (household_id, occurred_on);
CREATE INDEX tx_by_category ON transactions (household_id, category_id, occurred_on);
CREATE INDEX cat_by_seq     ON categories   (household_id, seq);
CREATE INDEX acc_by_seq     ON accounts     (household_id, seq);
CREATE INDEX bud_by_seq     ON budgets      (household_id, seq);

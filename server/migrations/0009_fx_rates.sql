-- What one unit of a currency was worth in złoty, on a date.
--
-- Source is NBP table A (api.nbp.pl), the Polish central bank's own mid rates.
-- PLN-base and free, with no key to rotate and no vendor to outlive, which for
-- a household that banks in złoty is the rate that matters: it is the one their
-- own bank's statements are reconciled against.
--
-- NOT household-scoped. An exchange rate is a fact about the world, not about a
-- family, so this is the one table with no household_id — every household reads
-- the same rows. Nothing here is personal data.
--
-- rate_micro, not a REAL: NBP publishes four to six decimals (EUR 4.3745, HUF
-- 0.011898), and money in this codebase is integers all the way down. Scaled by
-- 1e6, a rate is exact and conversion stays integer arithmetic:
--
--   grosze = amount_minor * rate_micro / 1000000
--
-- effective_on is the date the rate is used FOR, and published_on is the date
-- NBP published it. They differ across weekends and holidays: the API returns
-- 404 for a Sunday, so Saturday, Sunday and Monday-before-a-holiday all carry
-- Friday's rate. Storing both, rather than resolving at read time, means a
-- conversion is one indexed lookup and the answer never depends on when the
-- question is asked.
CREATE TABLE fx_rates (
  currency      TEXT NOT NULL,
  effective_on  TEXT NOT NULL,
  published_on  TEXT NOT NULL,
  rate_micro    INTEGER NOT NULL,
  fetched_at    INTEGER NOT NULL,
  PRIMARY KEY (currency, effective_on)
);

-- Every read is "the rate for this currency on this date", and conversion of a
-- month of transactions asks it once per row.
CREATE INDEX fx_rates_lookup ON fx_rates (currency, effective_on);

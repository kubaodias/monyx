-- Keeping an account out of the household's summary.
--
-- Savings held somewhere else are still worth tracking, but not worth adding to
-- what is there to spend. The account stays open and bookable; the summary
-- figures (Overview totals, budget spending and carry-over, the voice digest)
-- leave it out unless it is picked explicitly.
--
-- Old clients neither send nor understand this column. The DEFAULT is what lets
-- them keep pushing accounts rows through an INSERT that now names it.
ALTER TABLE accounts ADD COLUMN excluded_from_summary INTEGER NOT NULL DEFAULT 0;

// Exchange rates: fetching, carrying across closed days, and converting.
//
// The criterion here is that a total in złoty never depends on WHEN it was
// asked for. A rate is stored per date, closed days carry the last publication
// forward, and conversion is integer arithmetic — so last month's figure is the
// same figure next month.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb, seedHousehold } from "./fake-db.ts";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { push } from "../src/sync.ts";
import { budgetStatuses } from "../src/budgets.ts";
import { expense } from "./fake-db.ts";
import { forHousehold } from "../src/db.ts";
import {
  BASE,
  CURRENCIES,
  NBP_RANGE_DAYS,
  rangeWindows,
  ratesSince,
  convertMinor,
  datesBetween,
  fetchNbpTable,
  isCurrency,
  isCurrencyCode,
  latestStoredDate,
  rateFor,
  refreshRates,
  RATE_SCALE,
} from "../src/rates.ts";

const NOW = 1_791_000_000_000;

/**
 * A fetch that answers NBP's RANGE endpoint from a table of dates.
 *
 * 404s when no date in the requested window has a publication, which is what
 * NBP really does for a closed weekend.
 */
function fakeNbp(byDate: Record<string, Record<string, number>>, seen?: string[]) {
  return async (input: string | URL | Request): Promise<Response> => {
    const url = String(input);
    const m = url.match(/tables\/A\/(\d{4}-\d{2}-\d{2})\/(\d{4}-\d{2}-\d{2})/);
    if (!m) return new Response("bad url", { status: 400 });
    const from = m[1]!;
    const to = m[2]!;
    seen?.push(`${from}..${to}`);
    const tables = Object.keys(byDate)
      .filter((d) => d >= from && d <= to)
      .sort()
      .map((date) => ({
        table: "A",
        no: "1/A/NBP/2026",
        effectiveDate: date,
        rates: Object.entries(byDate[date]!).map(([code, mid]) => ({ currency: code, code, mid })),
      }));
    if (tables.length === 0) return new Response("not found", { status: 404 });
    return new Response(JSON.stringify(tables), { status: 200 });
  };
}

test("conversion is integer arithmetic and rounds half away from zero", () => {
  // 1 240,00 € at 4,3745 = 5 424,38 zł. Verified by hand: 124000 * 4.3745
  // = 542 438,0 grosze.
  assert.equal(convertMinor(124_000, 4_374_500), 542_438);
  // A refund of the same amount must be the same figure negated, or converting
  // a purchase and its refund leaves a grosz behind in the totals.
  assert.equal(convertMinor(-124_000, 4_374_500), -542_438);
  // Exactly half a grosz goes away from zero in both directions.
  assert.equal(convertMinor(1, 1_500_000), 2);
  assert.equal(convertMinor(-1, 1_500_000), -2);
});

test("the base currency needs no stored rate", async () => {
  const fake = new FakeDb();
  const rate = await rateFor(fake, BASE, "2026-10-04");
  // An account in złoty must not depend on a rate fetch ever having succeeded.
  assert.equal(rate?.rate_micro, RATE_SCALE);
  assert.equal(convertMinor(123_45, rate!.rate_micro), 123_45);
});

test("a closed day carries the last publication forward", async () => {
  const fake = new FakeDb();
  // Friday publishes; Saturday and Sunday 404, as NBP really does.
  const fetchImpl = fakeNbp({ "2026-10-02": { EUR: 4.3745, USD: 3.8881 } });

  const result = await refreshRates(fake, "2026-10-02", "2026-10-04", NOW, fetchImpl);
  assert.equal(result.carried, 2, "Saturday and Sunday are carried");
  assert.deepEqual(result.missing, []);

  const sunday = await rateFor(fake, "EUR", "2026-10-04");
  assert.equal(sunday?.rate_micro, 4_374_500);
  // The row says which day's rate it actually is. Anyone reconciling a Sunday
  // purchase against a bank statement needs that, and it is why published_on
  // is stored rather than inferred.
  assert.equal(sunday?.published_on, "2026-10-02");
  assert.equal(sunday?.effective_on, "2026-10-04");
});

test("a range starting on a closed day reports what it could not fill", async () => {
  const fake = new FakeDb();
  // Sunday first, with nothing earlier stored and nothing to carry.
  const result = await refreshRates(fake, "2026-10-04", "2026-10-05", NOW, fakeNbp({}));
  // Guessing a rate here would be inventing one. The caller widens the range.
  assert.deepEqual(result.missing, ["2026-10-04", "2026-10-05"]);
  assert.equal(result.written, 0);
  assert.equal(await rateFor(fake, "EUR", "2026-10-05"), null);
});

test("re-running a refresh is idempotent", async () => {
  const fake = new FakeDb();
  const fetchImpl = fakeNbp({ "2026-10-02": { EUR: 4.3745 } });
  await refreshRates(fake, "2026-10-02", "2026-10-02", NOW, fetchImpl);
  await refreshRates(fake, "2026-10-02", "2026-10-02", NOW + 86_400_000, fetchImpl);

  const { results } = await fake
    .prepare(`SELECT COUNT(*) AS n FROM fx_rates WHERE currency='EUR' AND effective_on='2026-10-02'`)
    .all<{ n: number }>();
  // The cron re-asks for dates it already holds whenever a run is retried.
  assert.equal(results[0]?.n, 1, "one row per currency per date, not two");
});

test("a rate lookup walks back to the newest date not after the one asked for", async () => {
  const fake = new FakeDb();
  await refreshRates(fake, "2026-10-02", "2026-10-02", NOW, fakeNbp({ "2026-10-02": { EUR: 4.3745 } }));
  await refreshRates(fake, "2026-10-05", "2026-10-05", NOW, fakeNbp({ "2026-10-05": { EUR: 4.4012 } }));

  // A transaction dated between the two converts at the earlier rate, not the
  // later one: the money moved before the newer rate existed.
  assert.equal((await rateFor(fake, "EUR", "2026-10-03"))?.rate_micro, 4_374_500);
  assert.equal((await rateFor(fake, "EUR", "2026-10-05"))?.rate_micro, 4_401_200);
  // And a date before anything stored has no answer rather than a wrong one.
  assert.equal(await rateFor(fake, "EUR", "2026-10-01"), null);
});

test("the refresh skips currencies the app does not offer", async () => {
  const fake = new FakeDb();
  await refreshRates(
    fake,
    "2026-10-02",
    "2026-10-02",
    NOW,
    fakeNbp({ "2026-10-02": { EUR: 4.3745, THB: 0.1159, JPY: 0.024677 } }),
  );
  const { results } = await fake
    .prepare(`SELECT currency FROM fx_rates ORDER BY currency`)
    .all<{ currency: string }>();
  // JPY and THB are not two-decimal currencies in the way amount_minor assumes,
  // so storing them would invite an account denominated in one.
  assert.deepEqual(results.map((r) => r.currency), ["EUR"]);
});

test("a table carrying none of our currencies fails rather than writing an empty day", async () => {
  const fake = new FakeDb();
  // The shape changed under us. Keeping yesterday's rates beats writing a day
  // that conversion will find empty.
  await assert.rejects(
    () => fetchNbpTable("2026-10-02", fakeNbp({ "2026-10-02": { THB: 0.1159 } })),
    /nbp_no_known_currencies/,
  );
});

test("latestStoredDate is what the backfill starts from", async () => {
  const fake = new FakeDb();
  assert.equal(await latestStoredDate(fake), null, "empty table has no latest");
  await refreshRates(fake, "2026-10-02", "2026-10-02", NOW, fakeNbp({ "2026-10-02": { EUR: 4.3745 } }));
  assert.equal(await latestStoredDate(fake), "2026-10-02");
});

test("every offered currency is two-decimal and PLN is among them", () => {
  // amount_minor means hundredths of the unit for every account. A currency
  // with different minor units would need its own handling everywhere money is
  // parsed, formatted and summed.
  assert.ok(CURRENCIES.includes(BASE));
  assert.equal(CURRENCIES.length, 9);
  for (const code of ["EUR", "USD", "GBP", "CHF", "CZK", "SEK", "NOK", "DKK"]) {
    assert.ok(isCurrency(code), `${code} is offered`);
  }
  // Croatia adopted the euro in January 2023; the kuna is not a currency.
  assert.equal(isCurrency("HRK"), false);
  // Wider on purpose: the nine have rates, any code can be stored. See
  // isCurrencyCode — the household adds its own in Settings.
  assert.equal(isCurrencyCode("HRK"), true);
  assert.equal(isCurrencyCode("THB"), true);
  assert.equal(isCurrencyCode("thb"), false);
  assert.equal(isCurrencyCode("EU"), false);
  assert.equal(isCurrencyCode(null), false);
});

test("a long backfill is split into windows NBP will answer", () => {
  // The single-date endpoint would be 365 requests for a year. This is why the
  // range endpoint is used at all.
  const windows = rangeWindows("2025-01-01", "2026-12-31");
  assert.ok(windows.length <= 9, `two years is ${windows.length} requests, not 730`);
  for (const [from, to] of windows) {
    const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000 + 1;
    assert.ok(days <= NBP_RANGE_DAYS, `${from}..${to} is ${days} days, over NBP's cap`);
  }
  // Contiguous and complete: a gap between windows is a gap in the rates.
  assert.equal(windows[0]?.[0], "2025-01-01");
  assert.equal(windows[windows.length - 1]?.[1], "2026-12-31");
  for (let i = 1; i < windows.length; i += 1) {
    const prevEnd = Date.parse(`${windows[i - 1]![1]}T00:00:00Z`);
    const thisStart = Date.parse(`${windows[i]![0]}T00:00:00Z`);
    assert.equal(thisStart - prevEnd, 86_400_000, "windows must abut exactly");
  }
});

test("a range beginning on a closed day is seeded from before it", async () => {
  const fake = new FakeDb();
  // Asking for "just Sunday" used to write nothing: the window held no
  // publication and there was nothing stored to carry. The seed lookback is
  // what makes a Sunday-only refresh work.
  const seen: string[] = [];
  const result = await refreshRates(
    fake,
    "2026-10-04",
    "2026-10-04",
    NOW,
    fakeNbp({ "2026-10-02": { EUR: 4.3745 } }, seen),
  );
  assert.deepEqual(result.missing, [], "Sunday is filled from Friday");
  assert.equal((await rateFor(fake, "EUR", "2026-10-04"))?.published_on, "2026-10-02");
  // And it really did look back rather than asking only for the one day.
  assert.ok(seen.some((w) => w.startsWith("2026-09-24")), `looked back: ${seen.join()}`);
});

test("ratesSince gives the phone what it does not have", async () => {
  const fake = new FakeDb();
  await refreshRates(fake, "2026-10-01", "2026-10-05", NOW, fakeNbp({
    "2026-10-01": { EUR: 4.3770 },
    "2026-10-02": { EUR: 4.3745 },
    "2026-10-05": { EUR: 4.4012 },
  }));

  const since = await ratesSince(fake, "2026-10-04");
  // Only what was asked for, so a phone that already has history re-downloads
  // nothing. The 4th is a Sunday carrying Friday the 2nd's rate; the 5th has
  // its own. Rates for the 1st to the 3rd exist and are deliberately not here.
  assert.deepEqual(
    since.map((r) => `${r.effective_on}:${r.rate_micro}`),
    ["2026-10-04:4374500", "2026-10-05:4401200"],
  );
  // Nothing before the cutoff leaks in.
  assert.ok(since.every((r) => r.effective_on >= "2026-10-04"));
});

test("a push may set an account's currency, and omitting it means zloty", async () => {
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);

  const result = await push(db, [
    {
      table: "accounts",
      row: { id: "a-eur", name: "Revolut", initial_balance_minor: 0, sort_order: 1, currency: "EUR", deleted: 0 },
    },
    // A client built before this column existed. Rejecting it would strand it.
    { table: "accounts", row: { id: "a-pln", name: "Portfel", initial_balance_minor: 0, sort_order: 2, deleted: 0 } },
  ]);
  assert.deepEqual(result.rejected, []);

  const { results } = await fake
    .prepare(`SELECT id, currency FROM accounts WHERE household_id='hh1' ORDER BY id`)
    .all<{ id: string; currency: string }>();
  // acc1 and acc2 come from seedHousehold and predate the column entirely —
  // they read as PLN because of the migration's DEFAULT, which is the whole
  // reason it is 'PLN' rather than NULL.
  assert.deepEqual(
    results.map((r) => `${r.id}=${r.currency}`),
    ["a-eur=EUR", "a-pln=PLN", "acc1=PLN", "acc2=PLN"],
  );
});

test("a currency the server has no rates for is stored, not refused", async () => {
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);

  const result = await push(db, [
    { table: "accounts", row: { id: "a-x", name: "Jen", initial_balance_minor: 0, sort_order: 1, currency: "THB", deleted: 0 } },
  ]);

  // The household added it in Settings: a code and a symbol. Refusing the row
  // would mean an account that cannot be pushed at all, and the thing being
  // protected against — an account silently dropping out of every total — is
  // what both ends now SHOW, because there is no rate to convert it by.
  assert.equal(result.rejected.length, 0);
  assert.equal(result.applied, 1);
  assert.equal(
    fake.db.prepare("SELECT currency FROM accounts WHERE id = 'a-x'").get()?.["currency"],
    "THB",
  );
});

test("a currency that is not a code at all is still refused", async () => {
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);

  // Not a judgement about which currencies exist — a shape check, so the column
  // cannot end up holding something no client can resolve to a unit.
  for (const currency of ["zloty", "EU", "eur", "€", ""]) {
    const result = await push(db, [
      { table: "accounts", row: { id: "a-bad", name: "Jen", initial_balance_minor: 0, sort_order: 1, currency, deleted: 0 } },
    ]);
    assert.equal(result.rejected[0]?.reason, "bad_currency", `${currency} is refused`);
    assert.equal(result.applied, 0);
  }
});

test("a budget alert converts a foreign default account's spending", async () => {
  // The SECOND implementation of "what has been spent": budgets.ts for the
  // notification, ledger_pln on the phone for the screen. An unconverted alert
  // would compare euro cents against a złoty limit and fire far too late —
  // 100,00 € of a 500,00 zł budget would read as 100,00 zł spent, not 437,45.
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);

  // acc1 is the default account: first by excluded_from_summary, sort_order.
  // Its currency is now only a default for new entries — what converts is the
  // TRANSACTION's own currency, set below.
  fake.db.exec(`UPDATE accounts SET currency = 'EUR' WHERE id = 'acc1'`);
  await refreshRates(fake, "2026-08-15", "2026-08-15", NOW, fakeNbp({
    "2026-08-15": { EUR: 4.3745 },
  }));

  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '2026-08', 50000,
             (SELECT next_seq + 1 FROM households WHERE id='hh1'), 0);
     UPDATE households SET next_seq = next_seq + 1 WHERE id='hh1';`,
  );
  const row = expense("t1", 100_00, "cat1");
  await push(db, [{ ...row, row: { ...row.row, currency: "EUR" } }]);

  const statuses = await budgetStatuses(db, "2026-08");
  // 100,00 € at 4,3745 = 437,45 zł. Integer division, matching ledger_pln.
  assert.equal(statuses[0]?.spent_minor, 437_45);
});

test("a zloty default account is unaffected by the conversion", async () => {
  // The no-op path, which is every household today. If this moves, the
  // conversion has leaked into the ordinary case.
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '2026-08', 50000,
             (SELECT next_seq + 1 FROM households WHERE id='hh1'), 0);
     UPDATE households SET next_seq = next_seq + 1 WHERE id='hh1';`,
  );
  await push(db, [expense("t1", 100_00, "cat1")]);

  const statuses = await budgetStatuses(db, "2026-08");
  assert.equal(statuses[0]?.spent_minor, 100_00);
});

test("spending with no rate for its currency is not counted as zloty", async () => {
  // No rates at all. Counting 100_00 euro cents as 100,00 zł would understate
  // by the rate; the conversion yields null and SUM skips it, which is the same
  // degradation the phone has.
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '2026-08', 50000,
             (SELECT next_seq + 1 FROM households WHERE id='hh1'), 0);
     UPDATE households SET next_seq = next_seq + 1 WHERE id='hh1';`,
  );
  const row = expense("t1", 100_00, "cat1");
  await push(db, [{ ...row, row: { ...row.row, currency: "EUR" } }]);

  const statuses = await budgetStatuses(db, "2026-08");
  assert.equal(statuses[0]?.spent_minor, 0, "no rate means not counted, not counted wrongly");
});

test("the currency is the transaction's, not its account's", async () => {
  // The whole point of 0010. A euro account can hold a złoty row — you paid in
  // złoty from a euro card — and that row must not be multiplied by the rate.
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);
  fake.db.exec(`UPDATE accounts SET currency = 'EUR' WHERE id = 'acc1'`);
  await refreshRates(fake, "2026-08-15", "2026-08-15", NOW, fakeNbp({
    "2026-08-15": { EUR: 4.3745 },
  }));
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '2026-08', 500000,
             (SELECT next_seq + 1 FROM households WHERE id='hh1'), 0);
     UPDATE households SET next_seq = next_seq + 1 WHERE id='hh1';`,
  );

  const eur = expense("t1", 100_00, "cat1");
  const pln = expense("t2", 100_00, "cat1");
  await push(db, [
    { ...eur, row: { ...eur.row, currency: "EUR" } },
    { ...pln, row: { ...pln.row, currency: "PLN" } },
  ]);

  // 437,45 + 100,00. The account being EUR changes neither figure.
  const statuses = await budgetStatuses(db, "2026-08");
  assert.equal(statuses[0]?.spent_minor, 537_45);
});

test("a push without a currency is zloty, so an older client still works", async () => {
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);
  await push(db, [expense("t1", 100_00, "cat1")]);

  const { results } = await fake
    .prepare(`SELECT currency FROM transactions WHERE id = 't1'`)
    .all<{ currency: string }>();
  assert.equal(results[0]?.currency, "PLN");
});

test("a transaction in an unrated currency is stored; a malformed code is not", async () => {
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);

  const good = expense("t1", 100_00, "cat1");
  const stored = await push(db, [{ ...good, row: { ...good.row, currency: "THB" } }]);
  assert.equal(stored.rejected.length, 0);
  assert.equal(stored.applied, 1);

  const bad = expense("t2", 100_00, "cat1");
  const refused = await push(db, [{ ...bad, row: { ...bad.row, currency: "baht" } }]);
  assert.equal(refused.rejected[0]?.reason, "bad_currency");
  assert.equal(refused.applied, 0);
});

test("0010's backfill restates existing rows in their account's currency", async () => {
  // The upgrade path, which the fake cannot reach on its own: it replays the
  // migrations against an empty table, so the UPDATE runs over no rows.
  //
  // Reading the statement out of the migration file rather than retyping it —
  // a copy here would pass while the real one was wrong, which is the failure
  // mode these tests have already had twice.
  const fake = new FakeDb();
  seedHousehold(fake);
  const db = forHousehold("hh1", fake);
  fake.db.exec(`UPDATE accounts SET currency = 'EUR' WHERE id = 'acc1'`);

  // A row as it existed before 0010: no currency of its own, so the column
  // default made it złoty.
  await push(db, [expense("t1", 100_00, "cat1")]);
  const before = await fake
    .prepare(`SELECT currency FROM transactions WHERE id='t1'`)
    .all<{ currency: string }>();
  assert.equal(before.results[0]?.currency, "PLN");

  const sql = readFileSync(
    join(import.meta.dirname, "..", "migrations", "0010_transactions_currency.sql"),
    "utf8",
  );
  const backfill = sql.slice(sql.indexOf("UPDATE transactions"));
  fake.db.exec(backfill);

  // Conversion already treated this row as euro, via its account. The backfill
  // is what keeps that true after the authority moves to the row itself — no
  // figure anyone has looked at moves.
  const after = await fake
    .prepare(`SELECT currency FROM transactions WHERE id='t1'`)
    .all<{ currency: string }>();
  assert.equal(after.results[0]?.currency, "EUR");
});

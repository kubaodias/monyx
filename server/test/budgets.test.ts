// Budget resolution and the alert path.
//
// M2 is done when crossing a budget produces exactly one notification, on every
// phone, and correcting the mistake that caused it does not silence the
// category for the rest of the month. These tests are that criterion.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb, expense, seedHousehold } from "./fake-db.ts";
import { forHousehold } from "../src/db.ts";
import { push } from "../src/sync.ts";
import { budgetStatuses, claimAlerts, undeliveredAlerts, stamp } from "../src/budgets.ts";

const NOW = 1_756_000_000_000;

function setup() {
  const fake = new FakeDb();
  seedHousehold(fake);
  return { fake, db: forHousehold("hh1", fake) };
}

/** Insert a budget row directly, the way a push would. */
function budget(fake: FakeDb, id: string, categoryId: string, period: string, limitMinor: number, deleted = 0) {
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('${id}', 'hh1', '${categoryId}', '${period}', ${limitMinor},
             (SELECT next_seq + 1 FROM households WHERE id='hh1'), ${deleted});
     UPDATE households SET next_seq = next_seq + 1 WHERE id='hh1';`,
  );
}

test("a budget limit carries forward until a newer row supersedes it", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-06", 100_000);

  for (const period of ["2026-06", "2026-07", "2026-08"]) {
    const statuses = await budgetStatuses(db, period);
    assert.equal(statuses[0]?.limit_minor, 100_000, `${period} inherits the June limit`);
  }

  budget(fake, "b2", "cat1", "2026-08", 150_000);
  assert.equal((await budgetStatuses(db, "2026-07"))[0]?.limit_minor, 100_000);
  assert.equal((await budgetStatuses(db, "2026-08"))[0]?.limit_minor, 150_000);
  assert.equal((await budgetStatuses(db, "2026-09"))[0]?.limit_minor, 150_000);
});

test("a deleted budget row is still the newest row — inheritance stops there", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-06", 100_000);
  budget(fake, "b2", "cat1", "2026-08", 0, 1);

  assert.equal((await budgetStatuses(db, "2026-07"))[0]?.limit_minor, 100_000);
  assert.equal((await budgetStatuses(db, "2026-08")).length, 0, "no limit in August");
  assert.equal((await budgetStatuses(db, "2026-09")).length, 0, "and none after");
});

test("a budget includes its subcategories", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 30_000, "cat1"), expense("t2", 20_000, "cat2")]);

  const statuses = await budgetStatuses(db, "2026-08");
  assert.equal(statuses[0]?.spent_minor, 50_000, "Home > Repairs counts against Home");
});

test("a transfer never enters spending statistics", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [
    expense("t1", 10_000, "cat1"),
    { table: "transactions", row: { id: "tr", kind: "transfer", amount_minor: 90_000, account_id: "acc1", transfer_account_id: "acc2", occurred_at: NOW, occurred_on: "2026-08-15", created_by: "mem1", created_at: NOW, deleted: 0 } },
  ]);
  assert.equal((await budgetStatuses(db, "2026-08"))[0]?.spent_minor, 10_000);
});

test("a deleted transaction stops counting", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 40_000, "cat1")]);
  await push(db, [{ table: "transactions", row: { ...expense("t1", 40_000, "cat1").row, deleted: 1 } }]);
  assert.equal((await budgetStatuses(db, "2026-08"))[0]?.spent_minor, 0);
});

test("months are bucketed on the local date, so a neighbouring month does not leak in", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [
    expense("aug", 10_000, "cat1", "2026-08-31"),
    expense("sep", 70_000, "cat1", "2026-09-01"),
  ]);
  assert.equal((await budgetStatuses(db, "2026-08"))[0]?.spent_minor, 10_000);
  assert.equal((await budgetStatuses(db, "2026-09"))[0]?.spent_minor, 70_000);
});

test("a budget on a deleted category never fires", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat3", "2026-08", 10_000);
  await push(db, [expense("t1", 9_999, "cat3")]);
  fake.db.exec("UPDATE categories SET deleted = 1 WHERE id = 'cat3'");

  assert.equal((await budgetStatuses(db, "2026-08")).length, 0);
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
});

test("crossing 80% claims the alert exactly once, however many times it is checked", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 85_000, "cat1")]);

  const first = await claimAlerts(db, "2026-08", NOW);
  assert.equal(first.length, 1);
  assert.equal(first[0]!.threshold, 80);
  assert.equal(first[0]!.pct, 85);

  // Whoever inserts the row owns the notification; everyone else sees zero rows
  // changed and does nothing. This is the mutex.
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
});

test("one expense crossing BOTH thresholds sends one notification, for the higher one", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 120_000, "cat1")]);

  const alerts = await claimAlerts(db, "2026-08", NOW);
  assert.equal(alerts.length, 1, "one notification, not two");
  assert.equal(alerts[0]!.threshold, 100);

  // The lower threshold is marked notified with a REAL timestamp, never 0.
  // Writing 0 would leave it looking undelivered and the sweep would send it
  // hours later as a second notification for a single crossing.
  const row = fake.db
    .prepare("SELECT notified_at FROM budget_alerts WHERE threshold = 80")
    .get() as { notified_at: number };
  assert.equal(row.notified_at, NOW);
  assert.notEqual(row.notified_at, 0);

  // And the sweep must never resurrect the suppressed 80% alert. The 100% row
  // is legitimately still at 0 here — the caller stamps it once FCM accepts.
  const pending = await undeliveredAlerts(db, "2026-08");
  assert.deepEqual(pending.map((a) => a.threshold), [100]);
});

test("hysteresis: correcting a typo does not silence the category for the rest of the month", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);

  // Someone types 1500,00 instead of 15,00. Both alerts fire.
  await push(db, [expense("typo", 150_000, "cat1")]);
  const fired = await claimAlerts(db, "2026-08", NOW);
  assert.equal(fired[0]!.threshold, 100);

  // Thirty seconds later they delete it.
  await push(db, [{ table: "transactions", row: { ...expense("typo", 150_000, "cat1").row, deleted: 1 } }]);
  await push(db, [expense("real", 1_500, "cat1")]);

  // 1.5% — below both floors, so both thresholds clear.
  await claimAlerts(db, "2026-08", NOW);
  const remaining = fake.db.prepare("SELECT COUNT(*) AS c FROM budget_alerts").get() as { c: number };
  assert.equal(remaining.c, 0, "both thresholds cleared");

  // The category is NOT silent: real spending later in the month still alerts.
  await push(db, [expense("later", 84_000, "cat1")]);
  const again = await claimAlerts(db, "2026-08", NOW);
  assert.equal(again.length, 1);
  assert.equal(again[0]!.threshold, 80);
});

test("hysteresis holds the alert between the floor and the threshold", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 82_000, "cat1")]);
  assert.equal((await claimAlerts(db, "2026-08", NOW)).length, 1);

  // Drop to 78%: above the 75% floor, so the alert is NOT cleared and does not
  // re-fire when spending climbs back to 82%.
  await push(db, [{ table: "transactions", row: { ...expense("t1", 82_000, "cat1").row, amount_minor: 78_000 } }]);
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
  const rows = fake.db.prepare("SELECT COUNT(*) AS c FROM budget_alerts").get() as { c: number };
  assert.equal(rows.c, 1, "still claimed — no oscillation");
});

test("a claimed but undelivered alert is retried by the sweep, then stamped", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 85_000, "cat1")]);
  await claimAlerts(db, "2026-08", NOW); // claimed, delivery never stamped

  const pending = await undeliveredAlerts(db, "2026-08");
  assert.equal(pending.length, 1, "notified_at = 0 means claimed but not delivered");
  assert.equal(pending[0]!.threshold, 80);

  await stamp(db, "cat1", "2026-08", 80, NOW);
  assert.deepEqual(await undeliveredAlerts(db, "2026-08"), [], "stamped rows are not retried");
});

test("a category with no budget produces no status and no alert", async () => {
  const { db } = setup();
  await push(db, [expense("t1", 999_999, "cat3")]);
  assert.deepEqual(await budgetStatuses(db, "2026-08"), []);
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
});

test("percentages floor rather than round, so 79.9% does not fire an 80% alert", async () => {
  const { fake, db } = setup();
  budget(fake, "b1", "cat1", "2026-08", 100_000);
  await push(db, [expense("t1", 79_999, "cat1")]);
  assert.equal((await budgetStatuses(db, "2026-08"))[0]?.pct, 79);
  assert.deepEqual(await claimAlerts(db, "2026-08", NOW), []);
});

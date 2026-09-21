// The sync protocol. A sync bug that loses rows silently is the highest risk in
// this design: the failure mode is invisible by nature — no error, and each
// phone looks complete. These tests are the only thing standing in front of it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb, expense, seedHousehold } from "./fake-db.ts";
import { forHousehold } from "../src/db.ts";
import { pull, push, MAX_PUSH_CHANGES } from "../src/sync.ts";

function setup() {
  const fake = new FakeDb();
  const { householdId, now } = seedHousehold(fake);
  return { fake, db: forHousehold(householdId, fake), now };
}

test("seq is allocated per row and runs across the whole batch, not per table", async () => {
  const { fake, db } = setup();
  const result = await push(db, [
    { table: "accounts", row: { id: "a9", name: "Konto", initial_balance_minor: 0, sort_order: 0, deleted: 0 } },
    { table: "categories", row: { id: "c9", name: "Dom", kind: "expense", sort_order: 0, deleted: 0 } },
    expense("t9", 1000, "c9"),
  ]);

  assert.equal(result.applied, 3);
  assert.deepEqual(result.rejected, []);

  const rows = fake.db
    .prepare(
      `SELECT seq FROM accounts WHERE id='a9'
       UNION ALL SELECT seq FROM categories WHERE id='c9'
       UNION ALL SELECT seq FROM transactions WHERE id='t9'`,
    )
    .all() as { seq: number }[];
  const seqs = rows.map((r) => r.seq).sort((a, b) => a - b);

  // The likeliest implementation error is resetting the offset per table, which
  // produces duplicate seqs across tables and is silent.
  assert.equal(new Set(seqs).size, 3, "seqs must be distinct across tables");
  assert.equal(seqs[2]! - seqs[0]!, 2, "seqs must be consecutive");
  assert.equal(result.seq, seqs[2], "push returns the top of the reserved range");
});

test("successive pushes never reuse a seq", async () => {
  const { fake, db } = setup();
  const seen = new Set<number>();
  for (let i = 0; i < 20; i += 1) {
    await push(db, [expense(`t${i}`, 100 + i, "cat1")]);
  }
  const rows = fake.db.prepare("SELECT seq FROM transactions").all() as { seq: number }[];
  for (const row of rows) {
    assert.ok(!seen.has(row.seq), `seq ${row.seq} was reused`);
    seen.add(row.seq);
  }
  assert.equal(seen.size, 20);
});

test("a row touched twice in one batch ends in its final state and pull emits it once", async () => {
  const { db } = setup();
  await push(db, [
    expense("dup", 500, "cat1"),
    { table: "transactions", row: { ...expense("dup", 900, "cat1").row } },
  ]);
  const page = await pull(db, 0);
  const rows = page.changes.filter((c) => c.row["id"] === "dup");
  assert.equal(rows.length, 1);
  assert.equal(rows[0]!.row["amount_minor"], 900);
});

test("invalid rows are rejected and never reach the batch", async () => {
  const { db } = setup();
  const result = await push(db, [
    expense("ok1", 100, "cat1"),
    { table: "transactions", row: { ...expense("bad1", 0, "cat1").row, amount_minor: 0 } },
    { table: "transactions", row: { ...expense("bad2", 100, "cat1").row, amount_minor: 12.5 } },
    { table: "transactions", row: { ...expense("bad3", 100, "cat1").row, kind: "gift" } },
    { table: "unknown_table", row: { id: "x", deleted: 0 } },
  ]);

  assert.equal(result.applied, 1);
  assert.equal(result.rejected.length, 4);
  const reasons = result.rejected.map((r) => r.reason).sort();
  assert.deepEqual(reasons, ["bad_amount_minor", "bad_amount_minor", "bad_kind", "unknown_table"]);
});

test("money is an integer in minor units — a float amount is rejected, never rounded", async () => {
  const { fake, db } = setup();
  await push(db, [{ table: "transactions", row: { ...expense("f", 100, "cat1").row, amount_minor: 45.99 } }]);
  const count = fake.db.prepare("SELECT COUNT(*) AS c FROM transactions").get() as { c: number };
  assert.equal(count.c, 0);
});

test("a transfer may not carry a category, and may not target its own account", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "transactions", row: { id: "tr1", kind: "transfer", amount_minor: 100, account_id: "acc1", transfer_account_id: "acc2", category_id: "cat1", occurred_at: 1, occurred_on: "2026-08-15", created_by: "mem1", created_at: 1, deleted: 0 } },
    { table: "transactions", row: { id: "tr2", kind: "transfer", amount_minor: 100, account_id: "acc1", transfer_account_id: "acc1", occurred_at: 1, occurred_on: "2026-08-15", created_by: "mem1", created_at: 1, deleted: 0 } },
    { table: "transactions", row: { id: "tr3", kind: "transfer", amount_minor: 100, account_id: "acc1", transfer_account_id: "acc2", occurred_at: 1, occurred_on: "2026-08-15", created_by: "mem1", created_at: 1, deleted: 0 } },
  ]);
  assert.equal(result.applied, 1);
  assert.deepEqual(result.rejected.map((r) => r.reason).sort(), ["transfer_has_category", "transfer_to_self"]);
});

test("a foreign key outside the household is rejected rather than taking the push down", async () => {
  const { fake, db } = setup();
  seedHousehold(fake, "hh2");
  fake.db.exec(
    `INSERT INTO categories (id, household_id, name, kind, sort_order, seq, deleted)
     VALUES ('other-cat', 'hh2', 'Obce', 'expense', 0, 1, 0)`,
  );
  const result = await push(db, [expense("t1", 100, "other-cat"), expense("t2", 100, "cat1")]);
  assert.equal(result.applied, 1);
  assert.equal(result.rejected[0]!.reason, "missing_category_id");
});

test("a category and a transaction in it push together, in dependency order", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "categories", row: { id: "new-cat", name: "Nowa", kind: "expense", sort_order: 0, deleted: 0 } },
    expense("t-new", 250, "new-cat"),
  ]);
  assert.equal(result.applied, 2, "a row created earlier in the batch is a legitimate FK target");
});

test("household_id is stamped from the token, never taken from the request", async () => {
  const { fake, db } = setup();
  seedHousehold(fake, "hh2");
  await push(db, [
    { table: "transactions", row: { ...expense("spoof", 100, "cat1").row, household_id: "hh2" } },
  ]);
  const row = fake.db.prepare("SELECT household_id FROM transactions WHERE id='spoof'").get() as {
    household_id: string;
  };
  assert.equal(row.household_id, "hh1");
});

test("a deletion is a full-row upsert with deleted = 1, and pull carries the tombstone", async () => {
  const { db } = setup();
  await push(db, [expense("gone", 700, "cat1")]);
  await push(db, [{ table: "transactions", row: { ...expense("gone", 700, "cat1").row, deleted: 1 } }]);
  const page = await pull(db, 0);
  const row = page.changes.find((c) => c.row["id"] === "gone");
  assert.ok(row, "a tombstone must reach the other devices");
  assert.equal(row!.row["deleted"], 1);
});

test("pull orders by seq, paginates, and never splits a seq across a page boundary", async () => {
  const { db } = setup();
  for (let i = 0; i < 12; i += 1) {
    await push(db, [expense(`p${i}`, 100 + i, "cat1")]);
  }

  const first = await pull(db, 0, 5);
  assert.equal(first.changes.length, 5);
  assert.equal(first.has_more, true);

  const seqs = first.changes.map((c) => Number(c.row["seq"]));
  assert.deepEqual([...seqs].sort((a, b) => a - b), seqs, "page must be ordered by seq");
  assert.equal(new Set(seqs).size, seqs.length, "no two rows share a seq");
  assert.equal(first.seq, seqs[seqs.length - 1], "cursor is the highest seq actually applied");

  // Walk the whole stream and prove nothing is dropped or repeated.
  const collected: number[] = [];
  let cursor = 0;
  for (;;) {
    const page = await pull(db, cursor, 5);
    for (const change of page.changes) collected.push(Number(change.row["seq"]));
    cursor = page.seq;
    if (!page.has_more) break;
  }
  assert.equal(new Set(collected).size, collected.length, "no row is delivered twice");
  const txRows = collected.length;
  assert.ok(txRows >= 12, `expected at least the 12 transactions, saw ${txRows}`);
});

test("has_more is false on the last page", async () => {
  const { db } = setup();
  await push(db, [expense("only", 100, "cat1")]);
  const page = await pull(db, 0, 500);
  assert.equal(page.has_more, false);
});

test("the push seq is not a pull cursor", async () => {
  const { fake, db } = setup();
  const other = forHousehold("hh1", fake);

  const mine = await push(db, [expense("mine", 100, "cat1")]);
  // Another device commits in between.
  await push(other, [expense("theirs", 200, "cat1")]);

  // Advancing the pull cursor to the push seq would skip nothing here, but
  // pulling from 0 must still see both rows — the cursor only ever moves to the
  // highest seq actually applied.
  const page = await pull(db, 0);
  const ids = page.changes.map((c) => c.row["id"]);
  assert.ok(ids.includes("mine") && ids.includes("theirs"));
  assert.ok(mine.seq < page.seq, "another device's later commit sits above the push seq");
});

test("restore: bumping epoch tells every device to reset its cursor and re-pull", async () => {
  const { fake, db } = setup();
  await push(db, [expense("before", 100, "cat1")]);
  const before = await pull(db, 0);
  assert.equal(before.epoch, 1);

  // Step 2 of the restore runbook.
  fake.db.exec("UPDATE households SET epoch = 2 WHERE id = 'hh1'");

  const after = await pull(db, before.seq);
  assert.equal(after.epoch, 2, "the client compares this against its stored epoch");

  // A full re-pull from zero is safe because upserts are idempotent by id.
  const full = await pull(db, 0);
  assert.ok(full.changes.some((c) => c.row["id"] === "before"));
});

test("re-upload everything is idempotent — the same rows push twice without duplicating", async () => {
  const { fake, db } = setup();
  const change = expense("reup", 4599, "cat1");
  await push(db, [change]);
  await push(db, [change]);
  const count = fake.db.prepare("SELECT COUNT(*) AS c FROM transactions WHERE id='reup'").get() as {
    c: number;
  };
  assert.equal(count.c, 1, "upserts are idempotent by id");
});

test("a push over the 200-change cap reports the overflow instead of silently dropping it", async () => {
  const { db } = setup();
  const changes = [];
  for (let i = 0; i < MAX_PUSH_CHANGES + 3; i += 1) changes.push(expense(`c${i}`, 100, "cat1"));
  const result = await push(db, changes);
  assert.equal(result.applied, MAX_PUSH_CHANGES);
  assert.equal(result.rejected.length, 3);
  assert.equal(result.rejected[0]!.reason, "batch_too_large");
});

test("a rejected row is reported with its id so the client can flag it, not drop it", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "transactions", row: { ...expense("keeps-id", 100, "cat1").row, amount_minor: -5 } },
  ]);
  assert.equal(result.rejected[0]!.id, "keeps-id");
  assert.equal(result.rejected[0]!.table, "transactions");
});

// --------------------------------------------------------------- archiving

test("an account row omitting archived is accepted and lands as 0", async () => {
  const { fake, db } = setup();

  // Exactly the shape a phone built before archiving existed still pushes. It
  // must not be rejected, and the NOT NULL column must not take a NULL.
  const result = await push(db, [
    { table: "accounts", row: { id: "a1", name: "Karta", initial_balance_minor: 0, sort_order: 0, deleted: 0 } },
  ]);

  assert.deepEqual(result.rejected, []);
  const row = fake.db.prepare("SELECT archived FROM accounts WHERE id='a1'").get() as { archived: number };
  assert.equal(row.archived, 0);
});

test("archiving round-trips through push and pull", async () => {
  const { db } = setup();
  await push(db, [
    { table: "accounts", row: { id: "a1", name: "Karta", initial_balance_minor: 0, sort_order: 0, archived: 0, deleted: 0 } },
  ]);
  await push(db, [
    { table: "accounts", row: { id: "a1", name: "Karta", initial_balance_minor: 0, sort_order: 0, archived: 1, deleted: 0 } },
  ]);

  const page = await pull(db, 0, 100);
  const account = page.changes.filter((c) => c.table === "accounts" && c.row["id"] === "a1").at(-1);
  assert.ok(account, "the account must reach other devices");
  assert.equal(account.row["archived"], 1);
  // Archiving is not deleting: the row stays visible to every device.
  assert.equal(account.row["deleted"], 0);
});

test("archived must be a flag, not free text", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "accounts", row: { id: "a1", name: "Karta", initial_balance_minor: 0, sort_order: 0, archived: "yes", deleted: 0 } },
  ]);
  assert.equal(result.applied, 0);
  assert.equal(result.rejected[0]?.reason, "bad_archived");
});

// ------------------------------------------------------- the monthly plan

test("a month plan pushes, pulls, and is revised in place", async () => {
  const { db } = setup();
  await push(db, [
    { table: "month_plans", row: { id: "p1", period: "2026-08", planned_minor: 500000, deleted: 0 } },
  ]);
  await push(db, [
    { table: "month_plans", row: { id: "p1", period: "2026-08", planned_minor: 620000, deleted: 0 } },
  ]);

  const page = await pull(db, 0, 100);
  const plans = page.changes.filter((c) => c.table === "month_plans");
  assert.equal(plans.at(-1)?.row["planned_minor"], 620000);
});

test("two phones planning the same month offline do not take the push down", async () => {
  const { fake, db } = setup();
  // Distinct ids, same (household, period): exactly what UNIQUE forbids. The
  // ON CONFLICT clause has to resolve it, because batch() is all-or-nothing and
  // a raw constraint failure would reject the unrelated rows alongside it.
  const result = await push(db, [
    { table: "month_plans", row: { id: "p1", period: "2026-08", planned_minor: 500000, deleted: 0 } },
    { table: "month_plans", row: { id: "p2", period: "2026-08", planned_minor: 700000, deleted: 0 } },
    { table: "categories", row: { id: "c1", name: "Dom", kind: "expense", sort_order: 0, deleted: 0 } },
  ]);

  assert.deepEqual(result.rejected, []);
  assert.equal(result.applied, 3, "the unrelated category must survive the collision");
  const rows = fake.db.prepare("SELECT planned_minor FROM month_plans WHERE period='2026-08'").all();
  assert.equal(rows.length, 1, "one plan per month, last push wins");
});

test("a plan of zero is legal but a negative one is not", async () => {
  const { db } = setup();
  const zero = await push(db, [
    { table: "month_plans", row: { id: "p1", period: "2026-08", planned_minor: 0, deleted: 0 } },
  ]);
  assert.deepEqual(zero.rejected, []);

  const negative = await push(db, [
    { table: "month_plans", row: { id: "p2", period: "2026-09", planned_minor: -1, deleted: 0 } },
  ]);
  assert.equal(negative.rejected[0]?.reason, "bad_planned_minor");
});

test("a plan needs a well-formed period", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "month_plans", row: { id: "p1", period: "2026-8", planned_minor: 100, deleted: 0 } },
  ]);
  assert.equal(result.rejected[0]?.reason, "bad_period");
});

// -------------------------------------------------------- recurring rules

function rule(id: string, over: Record<string, unknown> = {}) {
  return {
    table: "recurring_rules",
    row: {
      id,
      kind: "expense",
      amount_minor: 250000,
      account_id: "acc1",
      category_id: "cat1",
      note: "Czynsz",
      freq: "monthly",
      starts_on: "2026-09-01",
      ends_on: null,
      created_by: "mem1",
      created_at: 1_756_000_000_000,
      deleted: 0,
      ...over,
    },
  };
}

/** A generated transaction: an ordinary expense that names the rule behind it. */
function fromRule(id: string, ruleId: string, occurredOn: string) {
  const base = expense(id, 250000, "cat1", occurredOn);
  return { ...base, row: { ...base.row, recurring_rule_id: ruleId } };
}

test("a recurring rule pushes, pulls, and is revised in place", async () => {
  const { db } = setup();
  await push(db, [rule("r1")]);
  await push(db, [rule("r1", { amount_minor: 270000 })]);

  const page = await pull(db, 0, 100);
  const rules = page.changes.filter((c) => c.table === "recurring_rules");
  assert.equal(rules.at(-1)?.row["amount_minor"], 270000);
  assert.equal(rules.at(-1)?.row["freq"], "monthly");
});

test("a rule is refused unless the anchor is a well-formed date", async () => {
  const { db } = setup();
  const result = await push(db, [rule("r1", { starts_on: "2026-9-1" })]);
  assert.equal(result.rejected[0]?.reason, "bad_starts_on");
});

test("only the three frequencies are accepted", async () => {
  const { db } = setup();
  for (const freq of ["weekly", "monthly", "yearly"]) {
    const ok = await push(db, [rule(`ok-${freq}`, { freq })]);
    assert.deepEqual(ok.rejected, [], `${freq} must be accepted`);
  }
  // Daily is deliberately absent: 365 rows a year is a footgun, not a feature.
  const daily = await push(db, [rule("r-daily", { freq: "daily" })]);
  assert.equal(daily.rejected[0]?.reason, "bad_freq");
});

test("a rule cannot be a transfer", async () => {
  const { db } = setup();
  const result = await push(db, [rule("r1", { kind: "transfer" })]);
  assert.equal(result.rejected[0]?.reason, "bad_kind");
});

test("a rule that ends before it starts is refused", async () => {
  const { db } = setup();
  const result = await push(db, [rule("r1", { ends_on: "2026-08-01" })]);
  assert.equal(result.rejected[0]?.reason, "ends_before_starts");

  const openEnded = await push(db, [rule("r2", { ends_on: null })]);
  assert.deepEqual(openEnded.rejected, [], "no end date means it runs forever");
});

test("a rule pointing at another household's account is refused", async () => {
  const { fake } = setup();
  seedHousehold(fake, "hh2");
  const db = forHousehold("hh1", fake);
  const result = await push(db, [rule("r1", { account_id: "hh2-acc1" })]);
  assert.equal(result.rejected[0]?.reason, "missing_account_id");
});

test("a generated transaction and the rule that made it push together", async () => {
  const { fake, db } = setup();
  // The client sends both in one batch on the very first materialisation. The
  // rule must be written before the transaction that references it, or the
  // foreign key check rejects a real expense while the user watches it save.
  const result = await push(db, [rule("r1"), fromRule("t1", "r1", "2026-09-01")]);
  assert.deepEqual(result.rejected, []);
  assert.equal(result.applied, 2);

  const row = fake.db
    .prepare("SELECT recurring_rule_id FROM transactions WHERE id='t1'")
    .get() as { recurring_rule_id: string };
  assert.equal(row.recurring_rule_id, "r1");
});

test("a transaction naming a rule that does not exist is refused, not orphaned", async () => {
  const { db } = setup();
  const tx = expense("t1", 1000, "cat1");
  const result = await push(db, [{ ...tx, row: { ...tx.row, recurring_rule_id: "nope" } }]);
  assert.equal(result.rejected[0]?.reason, "missing_recurring_rule_id");
});

test("an ordinary transaction still pushes with no rule attached", async () => {
  const { fake, db } = setup();
  const result = await push(db, [expense("t1", 1000, "cat1")]);
  assert.deepEqual(result.rejected, []);
  const row = fake.db
    .prepare("SELECT recurring_rule_id FROM transactions WHERE id='t1'")
    .get() as { recurring_rule_id: unknown };
  assert.equal(row.recurring_rule_id, null);
});

test("deleting a rule is a tombstone that pulls, leaving its transactions alone", async () => {
  const { db } = setup();
  await push(db, [rule("r1"), fromRule("t1", "r1", "2026-09-01")]);
  await push(db, [rule("r1", { deleted: 1 })]);

  const page = await pull(db, 0, 100);
  const rules = page.changes.filter((c) => c.table === "recurring_rules");
  assert.equal(rules.at(-1)?.row["deleted"], 1, "the rule stops, as a tombstone");

  const txs = page.changes.filter((c) => c.table === "transactions");
  assert.equal(txs.at(-1)?.row["deleted"], 0, "money already spent does not un-spend");
});

// ------------------------------------------------- kept out of the summary

test("excluded_from_summary round-trips, and an old client omitting it lands as 0", async () => {
  const { fake, db } = setup();
  await push(db, [
    { table: "accounts", row: { id: "a1", name: "PZU", initial_balance_minor: 0, sort_order: 0, deleted: 0 } },
  ]);
  const before = fake.db.prepare("SELECT excluded_from_summary AS x FROM accounts WHERE id='a1'").get() as { x: number };
  assert.equal(before.x, 0);

  await push(db, [
    { table: "accounts", row: { id: "a1", name: "PZU", initial_balance_minor: 0, sort_order: 0, excluded_from_summary: 1, deleted: 0 } },
  ]);
  const page = await pull(db, 0, 100);
  const account = page.changes.filter((c) => c.table === "accounts" && c.row["id"] === "a1").at(-1);
  assert.equal(account?.row["excluded_from_summary"], 1);
});

test("excluded_from_summary must be a flag", async () => {
  const { db } = setup();
  const result = await push(db, [
    { table: "accounts", row: { id: "a1", name: "PZU", initial_balance_minor: 0, sort_order: 0, excluded_from_summary: "yes", deleted: 0 } },
  ]);
  assert.equal(result.rejected[0]?.reason, "bad_excluded_from_summary");
});

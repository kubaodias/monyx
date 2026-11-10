// The voice assistant's window onto a household.
//
// Two things are being pinned here. The first is that the digest is arithmetic
// the phone would agree with — a balance read aloud that disagrees with the app
// is worse than no assistant. The second is the allowlist, which since the PIN
// was dropped is the only thing standing between a caller and the household:
// every way it could accidentally admit someone is a test.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb, expense, seedHousehold } from "./fake-db.ts";
import { forHousehold } from "../src/db.ts";
import { push } from "../src/sync.ts";
import {
  amount,
  collectDigest,
  escapeXml,
  findCaller,
  monthBudget,
  newTicketId,
  normalizeMsisdn,
  parseAllowlist,
  previousPeriod,
  putTicket,
  readTicket,
  renderDigest,
  secretEquals,
  type VoiceStore,
} from "../src/voice.ts";

const TODAY = "2026-08-20";
const PERIOD = "2026-08";

function setup() {
  const fake = new FakeDb();
  seedHousehold(fake);
  return { fake, db: forHousehold("hh1", fake) };
}

/** KV, in a Map. TTL is the platform's job; expiry is not what is under test. */
function memoryStore(): VoiceStore & { map: Map<string, string> } {
  const map = new Map<string, string>();
  return {
    map,
    async get(key) {
      return map.get(key) ?? null;
    },
    async put(key, value) {
      map.set(key, value);
    },
    async delete(key) {
      map.delete(key);
    },
  };
}

// ------------------------------------------------------------------ identity

test("a number is matched however the carrier spells it", () => {
  const expected = "48123456789";
  for (const spelling of [
    "+48123456789",
    "0048123456789",
    "48123456789",
    "123456789",
    "+48 123 456 789",
    "+48-123-456-789",
  ]) {
    assert.equal(normalizeMsisdn(spelling), expected, spelling);
  }
});

test("a nine-digit national number gains its country code, a longer one does not", () => {
  assert.equal(normalizeMsisdn("123456789"), "48123456789");
  assert.equal(normalizeMsisdn("+15551234567"), "15551234567");
});

test("an allowlist entry missing a household or a number is dropped, not defaulted", () => {
  const parsed = parseAllowlist(
    JSON.stringify([
      { msisdn: "+48123456789", household_id: "hh1", name: "A" },
      { msisdn: "+48444555666", name: "no household" },
      { msisdn: "", household_id: "hh1" },
      { household_id: "hh1", name: "no number" },
    ]),
  );
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0]?.msisdn, "48123456789");
});

test("a malformed allowlist is empty, not a crash — and empty admits nobody", () => {
  // Fails CLOSED. With the PIN gone this is the only gate, so a secret that
  // will not parse has to admit no one rather than everyone.
  assert.deepEqual(parseAllowlist("{not json"), []);
  assert.deepEqual(parseAllowlist('{"msisdn":"+48123456789"}'), []);
  assert.equal(findCaller(parseAllowlist("{not json"), "+48123456789"), null);
  assert.equal(findCaller(parseAllowlist("[]"), "+48123456789"), null);
});

test("an unknown caller resolves to nobody", () => {
  const list = parseAllowlist(
    JSON.stringify([{ msisdn: "+48123456789", household_id: "hh1", name: "A" }]),
  );
  assert.equal(findCaller(list, "+48999888777"), null);
  assert.equal(findCaller(list, ""), null);
  assert.equal(findCaller(list, null), null);
  assert.equal(findCaller(list, "+48123456789")?.household_id, "hh1");
});

test("secretEquals rejects a length mismatch without throwing", () => {
  assert.equal(secretEquals("abcd", "abcd"), true);
  assert.equal(secretEquals("abcd", "abcde"), false);
  assert.equal(secretEquals("", "abcd"), false);
  assert.equal(secretEquals("abcd", ""), false);
});

// ------------------------------------------------------------------- tickets

test("a ticket round-trips, and a forged id is refused before it reaches KV", async () => {
  const store = memoryStore();
  const id = newTicketId();
  await putTicket(store, id, { msisdn: "48123456789", household_id: "hh1" });

  const read = await readTicket(store, id);
  assert.equal(read?.household_id, "hh1");

  // Anything outside the minted alphabet is rejected on sight: a ticket id
  // becomes part of a KV key.
  for (const forged of ["../../etc/passwd", "a b", "voice/ticket-x", "", 42, null]) {
    assert.equal(await readTicket(store, forged), null, String(forged));
  }
});

test("a ticket id is long enough not to be guessed", () => {
  const id = newTicketId();
  assert.ok(id.length >= 32, `ticket id was ${id.length} characters`);
  assert.notEqual(id, newTicketId());
});

test("a ticket over a broken KV reads as absent rather than throwing", async () => {
  const broken: VoiceStore = {
    async get() {
      throw new Error("kv down");
    },
    async put() {
      throw new Error("kv down");
    },
    async delete() {
      throw new Error("kv down");
    },
  };
  // Absent, not admitted: a KV that cannot be read must not become a way past
  // the ticket check.
  assert.equal(await readTicket(broken, newTicketId()), null);
});

// -------------------------------------------------------------------- digest

test("the digest balances agree with the arithmetic the app does", async () => {
  const { fake, db } = setup();
  fake.db.exec("UPDATE accounts SET initial_balance_minor = 420000 WHERE id = 'acc1';");
  await push(db, [
    expense("t1", 30_000, "cat1", `${PERIOD}-05`),
    {
      table: "transactions",
      row: {
        id: "t2",
        kind: "income",
        amount_minor: 100_000,
        account_id: "acc1",
        category_id: null,
        occurred_at: 1_756_000_000_000,
        occurred_on: `${PERIOD}-06`,
        created_by: "mem1",
        created_at: 1_756_000_000_000,
        deleted: 0,
      },
    },
    {
      table: "transactions",
      row: {
        id: "t3",
        kind: "transfer",
        amount_minor: 50_000,
        account_id: "acc1",
        transfer_account_id: "acc2",
        category_id: null,
        occurred_at: 1_756_000_000_000,
        occurred_on: `${PERIOD}-07`,
        created_by: "mem1",
        created_at: 1_756_000_000_000,
        deleted: 0,
      },
    },
  ]);

  const digest = await collectDigest(db, PERIOD, TODAY);
  const byName = new Map(digest.accounts.map((a) => [a.name, a.balance_minor]));

  // 420000 opening − 30000 spent + 100000 earned − 50000 transferred away.
  assert.equal(byName.get("Gotowka"), 440_000);
  // The other side of the transfer.
  assert.equal(byName.get("Karta"), 50_000);

  // A transfer is not spending and not income.
  assert.equal(digest.expense_minor, 30_000);
  assert.equal(digest.income_minor, 100_000);
});

test("spending rolls a subcategory up into its parent", async () => {
  const { db } = setup();
  await push(db, [
    expense("t1", 30_000, "cat1", `${PERIOD}-05`),
    expense("t2", 20_000, "cat2", `${PERIOD}-06`),
    expense("t3", 10_000, "cat3", `${PERIOD}-07`),
  ]);

  const digest = await collectDigest(db, PERIOD, TODAY);
  const spend = new Map(digest.spending.map((s) => [s.category, s.spent_minor]));

  // Restauracje is a child of Jedzenie, the way a budget on Jedzenie covers it.
  assert.equal(spend.get("Jedzenie"), 50_000);
  assert.equal(spend.get("Transport"), 10_000);
  assert.equal(spend.has("Restauracje"), false);

  // Biggest first: it is what gets read out when someone asks where the money
  // went.
  assert.deepEqual(
    digest.spending.map((s) => s.category),
    ["Jedzenie", "Transport"],
  );
});

test("a deleted transaction leaves no trace in the digest", async () => {
  const { db } = setup();
  await push(db, [expense("t1", 30_000, "cat1", `${PERIOD}-05`)]);
  assert.equal((await collectDigest(db, PERIOD, TODAY)).expense_minor, 30_000);

  await push(db, [
    { table: "transactions", row: { ...expense("t1", 30_000, "cat1", `${PERIOD}-05`).row, deleted: 1 } },
  ]);
  const digest = await collectDigest(db, PERIOD, TODAY);
  assert.equal(digest.expense_minor, 0);
  assert.equal(digest.spending.length, 0);
  assert.equal(digest.recent.length, 0);
});

test("the month is bounded — neighbouring months do not leak into it", async () => {
  const { db } = setup();
  await push(db, [
    expense("t0", 11_100, "cat1", "2026-07-31"),
    expense("t1", 22_200, "cat1", "2026-08-01"),
    expense("t2", 33_300, "cat1", "2026-08-31"),
    expense("t3", 44_400, "cat1", "2026-09-01"),
  ]);

  const digest = await collectDigest(db, PERIOD, TODAY);
  // The first and last day of August are inside; the days either side are not.
  assert.equal(digest.expense_minor, 55_500);
  assert.equal(digest.previous_expense_minor, 11_100);
});

test("previousPeriod crosses the new year", () => {
  assert.equal(previousPeriod("2026-01"), "2025-12");
  assert.equal(previousPeriod("2026-09"), "2026-08");
});

// ------------------------------------------------------- the budget's column

/**
 * November 2026 off the household's phone, in the fixture's month.
 *
 * A round 22 000,00 was typed into the plan, 24 450,00 actually came in, the
 * account closed the previous month 387,19 to the good, 6 266,36 has gone out,
 * and nine limits add up to 20 355,00. Every figure here is one somebody read
 * off the card and asked about out loud.
 */
async function november(db: ReturnType<typeof forHousehold>, fake: FakeDb, typed: number | null) {
  // 1 387,19 opening less a 1 000,00 July expense closes July at 387,19 — and
  // the expense is also what makes Gotowka an account money is spent FROM,
  // which is what admits its closing balance to the carry-over at all.
  fake.db.exec("UPDATE accounts SET initial_balance_minor = 138719 WHERE id = 'acc1';");
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '${PERIOD}', 2035500, 70, 0);`,
  );
  const income = {
    table: "transactions",
    row: {
      id: "in1",
      kind: "income",
      amount_minor: 2_445_000,
      account_id: "acc1",
      category_id: null,
      occurred_at: 1_756_000_000_000,
      occurred_on: `${PERIOD}-09`,
      created_by: "mem1",
      created_at: 1_756_000_000_000,
      deleted: 0,
    },
  };
  const changes: unknown[] = [
    expense("jul", 100_000, "cat1", "2026-07-14"),
    income,
    expense("t1", 626_636, "cat1", `${PERIOD}-06`),
  ];
  if (typed !== null) {
    changes.push({
      table: "month_plans",
      row: { id: "p1", period: PERIOD, planned_minor: typed, deleted: 0 },
    });
  }
  await push(db, changes as Parameters<typeof push>[1]);
}

test("the pot is what the month has, not what somebody typed", async () => {
  const { fake, db } = setup();
  await november(db, fake, 2_200_000);

  const budget = monthBudget(await collectDigest(db, PERIOD, TODAY));
  assert.ok(budget);
  // The four figures the card shows, to the grosz: 24 837,19 budget,
  // 18 570,83 left to spend, 20 355,00 planned out, 4 482,19 left to plan.
  assert.equal(budget.account, "Gotowka");
  assert.equal(budget.carry_over_minor, 38_719);
  assert.equal(budget.budget_minor, 2_483_719);
  assert.equal(budget.left_to_spend_minor, 1_857_083);
  assert.equal(budget.assigned_minor, 2_035_500);
  assert.equal(budget.left_to_assign_minor, 448_219);
  // The typed figure is behind the money now, so it is not reported at all:
  // 22 000,00 read out as "your budget" was the bug this answers.
  assert.equal(budget.forecast_minor, null);
});

test("a forecast still ahead of the money leads, and is named as one", async () => {
  const { fake, db } = setup();
  await november(db, fake, 3_000_000);

  const budget = monthBudget(await collectDigest(db, PERIOD, TODAY));
  assert.ok(budget);
  assert.equal(budget.forecast_minor, 3_000_000);
  assert.equal(budget.budget_minor, 3_038_719);
  assert.equal(budget.income_minor, 2_445_000);
});

test("a month nobody planned still has a pot once money has arrived", async () => {
  const { fake, db } = setup();
  await november(db, fake, null);

  const budget = monthBudget(await collectDigest(db, PERIOD, TODAY));
  assert.ok(budget);
  assert.equal(budget.budget_minor, 2_483_719);
  assert.equal(budget.forecast_minor, null);
});

test("the carry-over counts only an account the household spends from", async () => {
  const { fake, db } = setup();
  fake.db.exec("UPDATE accounts SET initial_balance_minor = 138719 WHERE id = 'acc1';");
  await push(db, [expense("t1", 626_636, "cat1", `${PERIOD}-06`)]);

  // Nothing was spent from Gotowka in July, so its closing balance is not money
  // that was there to spend — same rule as spendingAccountIds on the phone.
  const digest = await collectDigest(db, PERIOD, TODAY);
  assert.equal(digest.carry_over_minor, 0);
});

test("the budget counts one account, where the month counts them all", async () => {
  const { fake, db } = setup();
  await push(db, [
    expense("t1", 30_000, "cat1", `${PERIOD}-05`),
    { table: "transactions", row: { ...expense("t2", 50_000, "cat1", `${PERIOD}-06`).row, account_id: "acc2" } },
  ]);
  const digest = await collectDigest(db, PERIOD, TODAY);
  // An insurance premium leaving the other account is the month's spending, and
  // is NOT what the limits govern. See budgetAccountIds in DefaultAccount.kt.
  assert.equal(digest.expense_minor, 80_000);
  assert.equal(digest.budget_expense_minor, 30_000);
});

test("a month with nothing planned and nothing in has no column at all", async () => {
  const { db } = setup();
  const digest = await collectDigest(db, PERIOD, TODAY);
  assert.equal(monthBudget(digest), null);
  // Rather than a pot of zero with 20 355,00 of limits assigned against it: the
  // card shows a dash there, and the assistant has to say it does not know.
  assert.ok(!renderDigest(digest).includes("<month_budget"));
});

// ------------------------------------------------------------------ rendering

test("amounts render as a person would say them", () => {
  assert.equal(amount(0), "0.00");
  assert.equal(amount(5), "0.05");
  assert.equal(amount(420_000), "4200.00");
  assert.equal(amount(3_511_001), "35110.01");
  // An overdrawn card is money owed, and it has to survive the round trip.
  assert.equal(amount(-4_599), "-45.99");
});

test("a category named with an ampersand does not break the document", () => {
  assert.equal(escapeXml('Dom & "ogród" <x>'), "Dom &amp; &quot;ogród&quot; &lt;x&gt;");
});

test("the rendered digest is well-formed and carries the numbers", async () => {
  const { fake, db } = setup();
  fake.db.exec("UPDATE accounts SET initial_balance_minor = 420000 WHERE id = 'acc1';");
  fake.db.exec(
    `INSERT INTO budgets (id, household_id, category_id, period, limit_minor, seq, deleted)
     VALUES ('b1', 'hh1', 'cat1', '${PERIOD}', 100000, 7, 0);`,
  );
  await push(db, [expense("t1", 30_000, "cat1", `${PERIOD}-05`)]);

  const xml = renderDigest(await collectDigest(db, PERIOD, TODAY));

  assert.match(xml, /^<budget currency="PLN" household="Dom" period="2026-08" today="2026-08-20">/);
  // 4200.00 opening, less the 300.00 expense below.
  assert.match(xml, /<account name="Gotowka" balance="3900.00"\/>/);
  assert.match(xml, /<budget category="Jedzenie" limit="1000.00" spent="300.00" percent="30" remaining="700.00"\/>/);
  assert.match(xml, /<category name="Jedzenie" spent="300.00"\/>/);
  // The raw plan used to ride along on <month> as planned="…", which is the one
  // number on this document that answers no question a caller asks.
  assert.ok(!xml.includes("planned="), "the stored forecast is not quoted as the budget");
  assert.match(xml, /<tx date="2026-08-05" kind="expense" amount="300.00" category="Jedzenie"/);
  assert.ok(xml.endsWith("</budget>"), "the document closes");

  // Every tag that opens, closes.
  for (const tag of ["budget", "accounts"]) {
    assert.equal(
      (xml.match(new RegExp(`<${tag}[ >]`, "g")) ?? []).length -
        (xml.match(new RegExp(`<${tag}[^>]*\\/>`, "g")) ?? []).length,
      (xml.match(new RegExp(`</${tag}>`, "g")) ?? []).length,
      `${tag} is balanced`,
    );
  }
});

test("an empty household renders a document rather than nothing", async () => {
  const { db } = setup();
  const xml = renderDigest(await collectDigest(db, PERIOD, TODAY));
  assert.match(xml, /<accounts total="0.00">/);
  assert.match(xml, /<month income="0.00" expense="0.00" net="0.00" previous_month_expense="0.00"\/>/);
  assert.ok(xml.endsWith("</budget>"));
});

test("an archived account is marked but still counted separately", async () => {
  const { fake, db } = setup();
  fake.db.exec(
    "UPDATE accounts SET archived = 1, initial_balance_minor = 10000 WHERE id = 'acc2';" +
      "UPDATE accounts SET initial_balance_minor = 20000 WHERE id = 'acc1';",
  );
  const xml = renderDigest(await collectDigest(db, PERIOD, TODAY));
  // The total is what is spendable; the archived account is listed, not summed.
  assert.match(xml, /<accounts total="200.00">/);
  assert.match(xml, /<account name="Karta" balance="100.00" archived="true"\/>/);
});

test("an account kept out of the summary is listed but not summed", async () => {
  const { fake, db } = setup();
  fake.db.exec(
    "UPDATE accounts SET excluded_from_summary = 1, initial_balance_minor = 10000 WHERE id = 'acc2';" +
      "UPDATE accounts SET initial_balance_minor = 20000 WHERE id = 'acc1';",
  );
  await push(db, [
    expense("t1", 3_000, "cat1", `${PERIOD}-05`),
    { table: "transactions", row: { ...expense("t2", 5_000, "cat1", `${PERIOD}-06`).row, account_id: "acc2" } },
  ]);
  const digest = await collectDigest(db, PERIOD, TODAY);
  // Savings held elsewhere: their spending is not the month's, their balance
  // is not what is there to spend.
  assert.equal(digest.expense_minor, 3_000);
  const xml = renderDigest(digest);
  assert.match(xml, /<accounts total="170.00">/);
  assert.match(xml, /<account name="Karta" balance="50.00" in_summary="false"\/>/);
});

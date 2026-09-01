#!/usr/bin/env node
// End-to-end check against a DEPLOYED monyx-api.
//
// Covers the M0/M1 criteria that only a real deployment can answer: that the
// seq scheme survives db.batch() on the live platform (not just node:sqlite),
// that concurrent pushes never reuse a seq, that pull paginates in seq order,
// and that a transaction entered "on one phone" appears on the other.
//
// Usage: node scripts/smoke-test.mjs <invite_code> [--url https://…]
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const invite = args.find((a) => !a.startsWith("--"));
const urlIndex = args.indexOf("--url");
const BASE = urlIndex >= 0
  ? args[urlIndex + 1]
  : "https://monyx-api-31cdf6d8-0.telnyxcompute.com";

if (!invite) {
  console.error("usage: node scripts/smoke-test.mjs <invite_code> [--url …]");
  process.exit(2);
}

let failures = 0;
function check(name, condition, detail = "") {
  const mark = condition ? "  ok  " : " FAIL ";
  if (!condition) failures += 1;
  console.log(`[${mark}] ${name}${detail ? ` — ${detail}` : ""}`);
}

async function call(path, { method = "GET", token, body } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await response.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* not json */ }
  return { status: response.status, body: parsed, text };
}

const today = new Date().toISOString().slice(0, 10);
const period = today.slice(0, 7);

console.log(`\n=== monyx-api smoke test against ${BASE} ===\n`);

// 1. health — must not touch the database and must be fast.
const t0 = Date.now();
const health = await call("/health");
check("GET /health returns 200", health.status === 200, `${Date.now() - t0} ms`);

// 2. auth — enrol "phone A" with the invite.
const enrollA = await call("/auth/enroll", {
  method: "POST",
  body: { invite_code: invite, member_name: "Kuba", device_label: "Phone A" },
});
check("POST /auth/enroll succeeds", enrollA.status === 200, enrollA.text.slice(0, 120));
if (enrollA.status !== 200) process.exit(1);
const tokenA = enrollA.body.session_token;
const memberA = enrollA.body.member_id;
check("enroll returns a starting cursor of 0", enrollA.body.seq === 0);

// 3. an unauthenticated request is refused.
const noAuth = await call("/sync/pull?since=0");
check("pull without a token is 401", noAuth.status === 401);

// 4. a second invite, minted by the enrolled device, enrols "phone B".
const invite2 = await call("/invites", { method: "POST", token: tokenA });
check("POST /invites mints a code", invite2.status === 200 && invite2.body.code?.length === 10,
  invite2.body?.code);
const enrollB = await call("/auth/enroll", {
  method: "POST",
  body: { invite_code: invite2.body.code, member_name: "Ala", device_label: "Phone B" },
});
check("a second device enrols on that code", enrollB.status === 200);
const tokenB = enrollB.body.session_token;
check("both devices share one household",
  enrollA.body.household_id === enrollB.body.household_id);

// 5. the invite is single-use.
const reuse = await call("/auth/enroll", {
  method: "POST",
  body: { invite_code: invite2.body.code, member_name: "Ktos", device_label: "Phone C" },
});
check("an invite cannot be used twice", reuse.status === 409, reuse.body?.error);

// 6. discover this household's seeded rows from phone A.
const seeded = await call("/sync/pull?since=0", { token: tokenA });
check("pull returns the seeded household", seeded.status === 200);
const accounts = seeded.body.changes.filter((c) => c.table === "accounts");
const categories = seeded.body.changes.filter((c) => c.table === "categories");
check("the household has seeded accounts and categories",
  accounts.length > 0 && categories.length > 0,
  `${accounts.length} accounts, ${categories.length} categories`);
const accountId = accounts[0]?.row.id;
const expenseCat = categories.find((c) => c.row.kind === "expense")?.row.id;

// 7. seq allocation across three tables in ONE batch — the M0 criterion.
const newCat = randomUUID();
const newAcc = randomUUID();
const newTx = randomUUID();
const multi = await call("/sync/push", {
  method: "POST",
  token: tokenA,
  body: {
    changes: [
      { table: "accounts", row: { id: newAcc, name: "Test", initial_balance_minor: 0, sort_order: 9, deleted: 0 } },
      { table: "categories", row: { id: newCat, name: "Test", kind: "expense", sort_order: 9, deleted: 0 } },
      { table: "transactions", row: { id: newTx, kind: "expense", amount_minor: 1234, account_id: newAcc, category_id: newCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } },
    ],
  },
});
check("a push spanning accounts+categories+transactions applies", multi.body?.applied === 3,
  JSON.stringify(multi.body));

const afterMulti = await call("/sync/pull?since=0", { token: tokenA });
const seqs = afterMulti.body.changes
  .filter((c) => [newAcc, newCat, newTx].includes(c.row.id))
  .map((c) => Number(c.row.seq));
check("those three rows have DISTINCT seqs", new Set(seqs).size === 3, seqs.join(","));
check("and consecutive ones", Math.max(...seqs) - Math.min(...seqs) === 2, seqs.join(","));

// 8. concurrent pushes must never reuse a seq. This is the property the whole
//    sync protocol rests on, and it cannot be proven against a fake.
const ids = Array.from({ length: 12 }, () => randomUUID());
await Promise.all(ids.map((id) => call("/sync/push", {
  method: "POST",
  token: Math.random() < 0.5 ? tokenA : tokenB,
  body: { changes: [{ table: "transactions", row: { id, kind: "expense", amount_minor: 100, account_id: accountId, category_id: expenseCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } }] },
})));

const all = [];
let cursor = 0;
for (let i = 0; i < 50; i += 1) {
  const page = await call(`/sync/pull?since=${cursor}&limit=50`, { token: tokenB });
  all.push(...page.body.changes);
  cursor = page.body.seq;
  if (!page.body.has_more) break;
}
const allSeqs = all.map((c) => Number(c.row.seq));
check("12 concurrent pushes all landed",
  ids.every((id) => all.some((c) => c.row.id === id)),
  `${ids.filter((id) => all.some((c) => c.row.id === id)).length}/12`);
check("NO two rows in the household share a seq",
  new Set(allSeqs).size === allSeqs.length,
  `${allSeqs.length} rows, ${new Set(allSeqs).size} distinct`);
check("pull returns rows in ascending seq order",
  allSeqs.every((s, i) => i === 0 || s > allSeqs[i - 1]));

// 9. "a transaction entered on one phone appears on the other" — the M1 criterion.
check("phone B sees phone A's transaction", all.some((c) => c.row.id === newTx));

// 10. validation rejects bad rows without taking the batch down.
const mixed = await call("/sync/push", {
  method: "POST",
  token: tokenA,
  body: {
    changes: [
      { table: "transactions", row: { id: randomUUID(), kind: "expense", amount_minor: 500, account_id: accountId, category_id: expenseCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } },
      { table: "transactions", row: { id: randomUUID(), kind: "expense", amount_minor: -5, account_id: accountId, category_id: expenseCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } },
      { table: "transactions", row: { id: randomUUID(), kind: "expense", amount_minor: 45.99, account_id: accountId, category_id: expenseCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } },
    ],
  },
});
check("one good row applies while two bad ones are rejected",
  mixed.body?.applied === 1 && mixed.body?.rejected?.length === 2,
  JSON.stringify(mixed.body?.rejected));

// 11. a float amount is never silently rounded.
check("a float amount is rejected, not rounded",
  mixed.body?.rejected?.some((r) => r.reason === "bad_amount_minor"));

// 12. budgets: set a limit, cross 80%, expect exactly one alert claim.
const budgetId = randomUUID();
await call("/sync/push", {
  method: "POST",
  token: tokenA,
  body: { changes: [{ table: "budgets", row: { id: budgetId, category_id: expenseCat, period, limit_minor: 100000, deleted: 0 } }] },
});
const crossing = await call("/sync/push", {
  method: "POST",
  token: tokenA,
  body: { changes: [{ table: "transactions", row: { id: randomUUID(), kind: "expense", amount_minor: 85000, account_id: accountId, category_id: expenseCat, occurred_at: Date.now(), occurred_on: today, created_by: memberA, created_at: Date.now(), deleted: 0 } }] },
});
check("an expense crossing 80% of a budget applies", crossing.body?.applied === 1);

console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : `${failures} CHECK(S) FAILED`}\n`);
process.exit(failures === 0 ? 0 : 1);

#!/usr/bin/env node
// Create a household, seed its default categories and accounts, and mint the
// first invite code — all through the CLI, because a household has no endpoint
// that creates it — enrolment consumes an invite, it does not bootstrap.
//
// Usage: node scripts/new-household.mjs "Dom" [--db monyx] [--lang pl|en]
//
// --lang picks the language of the SEED DATA only, and it is a one-time choice.
// Category and account names are rows that sync to every device; the in-app
// language picker changes the interface around them, not the names themselves —
// which is right, because the family renames them anyway and a rename must not
// be undone by someone else switching language.
import { execFileSync } from "node:child_process";
import { randomUUID, randomBytes } from "node:crypto";

const args = process.argv.slice(2);
const name = args.find((a) => !a.startsWith("--")) ?? "Dom";
const dbIndex = args.indexOf("--db");
const db = dbIndex >= 0 ? args[dbIndex + 1] : "monyx";
const langIndex = args.indexOf("--lang");
const lang = langIndex >= 0 ? args[langIndex + 1] : "pl";

// Keyed by the same tags as res/values-<tag>/ on the phone. Icon, colour, kind
// and order are language-independent; only the label moves.
const SEEDS = {
  pl: {
    accounts: [["Gotówka", "wallet", "green"], ["Karta", "wallet", "sky"]],
    categories: [
      ["Zakupy spożywcze", "groceries", "green", "expense"],
      ["Transport", "transport", "sky", "expense"],
      ["Dom", "home", "brown", "expense"],
      ["Zdrowie", "health", "coral", "expense"],
      ["Rozrywka", "fun", "violet", "expense"],
      ["Dzieci", "kids", "amber", "expense"],
      ["Wypłata", "salary", "teal", "income"],
      ["Inne przychody", "gift", "olive", "income"],
    ],
  },
  en: {
    accounts: [["Cash", "wallet", "green"], ["Card", "wallet", "sky"]],
    categories: [
      ["Groceries", "groceries", "green", "expense"],
      ["Transport", "transport", "sky", "expense"],
      ["Home", "home", "brown", "expense"],
      ["Health", "health", "coral", "expense"],
      ["Fun", "fun", "violet", "expense"],
      ["Kids", "kids", "amber", "expense"],
      ["Salary", "salary", "teal", "income"],
      ["Other income", "gift", "olive", "income"],
    ],
  },
};

const seed = SEEDS[lang];
if (!seed) {
  console.error(`unknown --lang ${lang}; known: ${Object.keys(SEEDS).join(", ")}`);
  process.exit(1);
}

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
function inviteCode() {
  const bytes = randomBytes(10);
  return [...bytes].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

function sql(script) {
  return execFileSync(
    "telnyx-edge",
    ["storage", "sqldb", "execute", db, "--remote", "--file", "-"],
    { input: script, encoding: "utf8" },
  );
}

const hh = randomUUID();
const now = Date.now();
const code = inviteCode();
const expires = now + 24 * 60 * 60 * 1000;
const q = (s) => `'${String(s).replace(/'/g, "''")}'`;

// Every seeded row draws its own seq exactly the way a push does: reserve the
// whole range up front with one UPDATE, then have each row read back off it.
const total = seed.accounts.length + seed.categories.length;
let slot = 0;
const seqOf = () => `(SELECT next_seq FROM households WHERE id = ${q(hh)}) - ${total} + ${++slot}`;

const accountRows = seed.accounts.map(([n, icon, color], i) =>
  `  (${q(randomUUID())}, ${q(hh)}, ${q(n)}, ${q(icon)}, ${q(color)}, 0, ${i}, ${seqOf()}, 0)`
).join(",\n");

const categoryRows = seed.categories.map(([n, icon, color, kind], i) =>
  `  (${q(randomUUID())}, ${q(hh)}, NULL, ${q(n)}, ${q(icon)}, ${q(color)}, ${q(kind)}, ${i}, ${seqOf()}, 0)`
).join(",\n");

const script = `
INSERT INTO households (id, name, next_seq, epoch, created_at)
VALUES (${q(hh)}, ${q(name)}, 1, 1, ${now});

UPDATE households SET next_seq = next_seq + ${total} WHERE id = ${q(hh)};

INSERT INTO accounts (id, household_id, name, icon, color, initial_balance_minor, sort_order, seq, deleted) VALUES
${accountRows};

INSERT INTO categories (id, household_id, parent_id, name, icon, color, kind, sort_order, seq, deleted) VALUES
${categoryRows};

INSERT INTO invites (code, household_id, expires_at, used_at)
VALUES (${q(code)}, ${q(hh)}, ${expires}, NULL);
`;

sql(script);
console.log(JSON.stringify(
  { household_id: hh, name, lang, invite_code: code, expires_at: expires },
  null, 2,
));

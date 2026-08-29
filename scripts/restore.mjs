#!/usr/bin/env node
// Restore a dump produced by `telnyx-edge storage sqldb export`.
//
// A SQL script over `execute --remote --file` is capped at roughly 4 MiB — past
// it, 422 (PRD §10). A multi-year dump will exceed that, so this splits the file
// into chunks at statement boundaries. Ten lines, written now rather than
// discovered during an outage.
//
// Usage: node scripts/restore.mjs <dump.sql|dump.sql.gz> [--db monio] [--dry-run]
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";

const args = process.argv.slice(2);
const file = args.find((a) => !a.startsWith("--"));
const dbIndex = args.indexOf("--db");
const db = dbIndex >= 0 ? args[dbIndex + 1] : "monio";
const dryRun = args.includes("--dry-run");

if (!file) {
  console.error("usage: node scripts/restore.mjs <dump.sql|dump.sql.gz> [--db monio] [--dry-run]");
  process.exit(2);
}

// Stay well under the documented ~4 MiB ceiling: a chunk is measured in bytes,
// and Polish category names are multi-byte.
const MAX_CHUNK_BYTES = 3 * 1024 * 1024;

const raw = readFileSync(file);
const text = file.endsWith(".gz") ? gunzipSync(raw).toString("utf8") : raw.toString("utf8");

/**
 * Split on semicolons that end a statement, respecting single-quoted string
 * literals (including SQL's '' escape). Splitting on /;\n/ alone would corrupt
 * any note or category name containing a semicolon.
 */
function statements(sql) {
  const out = [];
  let current = "";
  let inString = false;
  for (let i = 0; i < sql.length; i += 1) {
    const ch = sql[i];
    current += ch;
    if (ch === "'") {
      if (inString && sql[i + 1] === "'") {
        current += sql[++i];
      } else {
        inString = !inString;
      }
    } else if (ch === ";" && !inString) {
      const trimmed = current.trim();
      if (trimmed.length > 1) out.push(trimmed);
      current = "";
    }
  }
  if (current.trim().length > 0) out.push(current.trim());
  return out;
}

const all = statements(text);
const chunks = [];
let buffer = "";
for (const statement of all) {
  const candidate = buffer.length === 0 ? statement : `${buffer}\n${statement}`;
  if (Buffer.byteLength(candidate, "utf8") > MAX_CHUNK_BYTES && buffer.length > 0) {
    chunks.push(buffer);
    buffer = statement;
  } else {
    buffer = candidate;
  }
}
if (buffer.length > 0) chunks.push(buffer);

console.error(
  `${all.length} statements → ${chunks.length} chunk(s), largest ` +
    `${Math.max(...chunks.map((c) => Buffer.byteLength(c, "utf8")))} bytes`,
);

if (dryRun) {
  console.error("--dry-run: nothing sent");
  process.exit(0);
}

chunks.forEach((chunk, index) => {
  process.stderr.write(`applying chunk ${index + 1}/${chunks.length}… `);
  execFileSync(
    "telnyx-edge",
    ["storage", "sqldb", "execute", db, "--remote", "--file", "-"],
    { input: chunk, encoding: "utf8", stdio: ["pipe", "ignore", "inherit"] },
  );
  process.stderr.write("ok\n");
});

console.error(
  "\nRestore applied. Two steps remain, and skipping them is worse than not restoring:\n" +
    "  2. Bump households.epoch so every device resets its cursor and re-pulls.\n" +
    "  3. Ask ONE recently-online phone to tap 'Wyślij wszystko ponownie' in Settings.\n",
);

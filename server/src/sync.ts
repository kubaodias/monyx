// Push, pull, and seq allocation.
//
// Takes its database as a parameter rather than reaching for the imported env:
// that is what lets the protocol be exercised against the node:sqlite fake, and
// it is the same rule the isolation boundary in db.ts depends on.
import type { HouseholdDb } from "./db.ts";
import type { SqlPreparedStatement } from "@telnyx/edge-runtime";
import {
  COLUMNS,
  DEPENDENCY_ORDER,
  FOREIGN_KEYS,
  validateChange,
  type Change,
  type Rejection,
  type TableName,
} from "./schema.ts";

export interface PushResult {
  seq: number;
  applied: number;
  rejected: Rejection[];
  /** Rows accepted, in the order they were written — used by the alert path. */
  acceptedTransactions: Record<string, unknown>[];
}

export interface PullPage {
  changes: { table: TableName; row: Record<string, unknown> }[];
  epoch: number;
  seq: number;
  has_more: boolean;
}

/** Push batches are capped at 200 changes client-side; enforced here too. */
export const MAX_PUSH_CHANGES = 200;

/** Default pull page size. has_more drives the loop. */
export const DEFAULT_PULL_LIMIT = 500;

/**
 * Build the statements that allocate seq for a batch of accepted rows.
 *
 * seq is allocated per row, never per batch, and never read into TypeScript.
 * One statement reserves the whole range and each row takes its own
 * offset inside it — N + 1 statements, and exactly one ordering assumption
 * rather than N of them.
 *
 * The literal offset is baked in when the batch is built. The counter is never
 * read, computed and written back: the platform documents that two callers
 * doing so can clobber each other, and BEGIN is rejected at runtime, so there
 * is no transaction to wrap it in. The arithmetic stays inside the batch.
 *
 * Lives in its own function taking (household_id, rows) because the planned voice
 * tools will write transactions server-side, and allocation duplicated into a
 * second call site is how two writers end up disagreeing about the counter.
 */
export function buildSeqStatements(
  db: HouseholdDb,
  changes: Change[],
): SqlPreparedStatement[] {
  const n = changes.length;
  const hh = db.householdId;
  const statements: SqlPreparedStatement[] = [];

  // Statement 1, once. RETURNING gives us the top of the reserved range
  // atomically, so the response can name the range without a second read.
  statements.push(
    db
      .prepare("UPDATE households SET next_seq = next_seq + ? WHERE id = ? RETURNING next_seq")
      .bind(n, hh),
  );

  // Statement 1 + i, for i = 1..N. The offset runs across the WHOLE batch, not
  // per table — resetting it per table produces duplicate seqs across tables and
  // is silent.
  changes.forEach((change, index) => {
    statements.push(upsertStatement(db, change, index + 1, n));
  });

  return statements;
}

/** The seq expression for row i of a batch of n. */
function seqExpr(): string {
  return "(SELECT next_seq FROM households WHERE id = ?) - ? + ?";
}

/**
 * A full-row upsert. There is no op field: every change is an upsert and a
 * deletion is that row with deleted = 1.
 */
function upsertStatement(
  db: HouseholdDb,
  change: Change,
  offset: number,
  n: number,
): SqlPreparedStatement {
  const { table, row } = change;
  const cols = COLUMNS[table];
  const hh = db.householdId;

  const placeholders: string[] = [];
  const values: unknown[] = [];

  for (const col of cols) {
    if (col === "seq") {
      placeholders.push(seqExpr());
      values.push(hh, n, offset);
    } else if (col === "household_id") {
      // Stamped from the token, never taken from the request.
      placeholders.push("?");
      values.push(hh);
    } else {
      placeholders.push("?");
      values.push(normalize(table, col, row[col]));
    }
  }

  // household_id is never updated: a row cannot change household.
  const updatable = cols.filter((c) => c !== "id" && c !== "household_id");
  const setClause = updatable.map((c) => `${c} = excluded.${c}`).join(", ");

  let sql =
    `INSERT INTO ${table} (${cols.join(", ")}) VALUES (${placeholders.join(", ")}) ` +
    `ON CONFLICT(id) DO UPDATE SET ${setClause}`;

  // budgets carries UNIQUE (household_id, category_id, period). Two devices
  // that each create a budget row for the same category and month while offline
  // would otherwise collide on that constraint and, because batch() is
  // all-or-nothing, take the whole push down with them. The second clause
  // resolves it in place — last push to commit wins, which is the same rule the
  // rest of sync follows. SQLite has allowed multiple ON CONFLICT targets
  // since 3.35; the platform runs 3.51.
  if (table === "budgets") {
    const budgetUpdatable = updatable.filter((c) => c !== "category_id" && c !== "period");
    sql +=
      ` ON CONFLICT(household_id, category_id, period) DO UPDATE SET ` +
      budgetUpdatable.map((c) => `${c} = excluded.${c}`).join(", ");
  }

  // month_plans carries UNIQUE (household_id, period) for the same reason and
  // needs the same escape hatch: two phones planning August offline each mint
  // their own id, and without this the second one takes the whole push down.
  if (table === "month_plans") {
    const planUpdatable = updatable.filter((c) => c !== "period");
    sql +=
      ` ON CONFLICT(household_id, period) DO UPDATE SET ` +
      planUpdatable.map((c) => `${c} = excluded.${c}`).join(", ");
  }

  return db.prepare(sql).bind(...values);
}

/** Fill defaults the client may omit, and coerce absent optionals to NULL. */
function normalize(table: TableName, col: string, value: unknown): unknown {
  if (value === undefined) {
    if (table === "transactions" && col === "source") return "manual";
    if (col === "sort_order") return 0;
    if (col === "initial_balance_minor") return 0;
    if (col === "archived") return 0;
    return null;
  }
  return value;
}

/**
 * Apply a push.
 *
 * Validation runs first, in TypeScript: bad rows are separated out and
 * reported, and only rows known to be valid enter the batch. That is what makes
 * the applied / rejected split possible at all — batch() is all-or-nothing, so
 * a mixed outcome cannot come from the database.
 */
export async function push(
  db: HouseholdDb,
  rawChanges: unknown[],
): Promise<PushResult> {
  const rejected: Rejection[] = [];
  const valid: Change[] = [];

  for (const raw of rawChanges.slice(0, MAX_PUSH_CHANGES)) {
    const result = validateChange(raw);
    if (result.ok) valid.push(result.change);
    else rejected.push(result.rejection);
  }
  for (const raw of rawChanges.slice(MAX_PUSH_CHANGES)) {
    const id = (raw as { row?: { id?: unknown } })?.row?.id;
    rejected.push({
      table: String((raw as { table?: unknown })?.table ?? "?"),
      id: typeof id === "string" ? id : null,
      reason: "batch_too_large",
    });
  }

  // Foreign keys that exist and belong to the same household, checked
  // before any SQL write. Rows introduced earlier in this same batch count as
  // present — the client orders changes by dependency, so a category and a
  // transaction in it arrive together and legitimately.
  const accepted = await filterByForeignKeys(db, valid, rejected);

  if (accepted.length === 0) {
    const seq = await currentSeq(db);
    return { seq, applied: 0, rejected, acceptedTransactions: [] };
  }

  // The whole batch runs inside a single batch() — atomically — and each row
  // draws its own seq inside it. The server applies changes in array
  // order, so a row touched twice in one batch ends in its final state.
  const statements = buildSeqStatements(db, accepted);
  const results = await db.batch<{ next_seq: number }>(statements);

  const top = results[0]?.results?.[0]?.next_seq;
  const seq = typeof top === "number" ? top : await currentSeq(db);

  return {
    seq,
    applied: accepted.length,
    rejected,
    acceptedTransactions: accepted
      .filter((c) => c.table === "transactions")
      .map((c) => c.row),
  };
}

async function currentSeq(db: HouseholdDb): Promise<number> {
  const row = await db
    .prepare("SELECT next_seq FROM households WHERE id = ?")
    .bind(db.householdId)
    .first<{ next_seq: number }>();
  return row?.next_seq ?? 0;
}

/**
 * Drop rows whose foreign keys do not resolve inside this household, reporting
 * each as a rejection rather than letting it reach SQL.
 */
async function filterByForeignKeys(
  db: HouseholdDb,
  changes: Change[],
  rejected: Rejection[],
): Promise<Change[]> {
  // Ids referenced anywhere in this batch, per target table.
  const wanted: Record<string, Set<string>> = {};
  for (const change of changes) {
    for (const fk of FOREIGN_KEYS[change.table]) {
      const value = change.row[fk.column];
      if (typeof value === "string") {
        (wanted[fk.table] ??= new Set()).add(value);
      }
    }
  }

  const existing: Record<string, Set<string>> = {};
  for (const [table, ids] of Object.entries(wanted)) {
    existing[table] = await existingIds(db, table as TableName, [...ids]);
  }

  // Rows created earlier in this same batch are legitimate targets.
  const introduced: Record<string, Set<string>> = {};
  const out: Change[] = [];

  for (const change of changes) {
    let bad: string | null = null;
    for (const fk of FOREIGN_KEYS[change.table]) {
      const value = change.row[fk.column];
      if (typeof value !== "string") continue;
      const present =
        existing[fk.table]?.has(value) || introduced[fk.table]?.has(value);
      if (!present) {
        bad = `missing_${fk.column}`;
        break;
      }
    }
    if (bad) {
      rejected.push({
        table: change.table,
        id: String(change.row["id"]),
        reason: bad,
      });
      continue;
    }
    (introduced[change.table] ??= new Set()).add(String(change.row["id"]));
    out.push(change);
  }

  return out;
}

async function existingIds(
  db: HouseholdDb,
  table: TableName,
  ids: string[],
): Promise<Set<string>> {
  if (ids.length === 0) return new Set();
  const placeholders = ids.map(() => "?").join(", ");
  const { results } = await db
    .prepare(
      `SELECT id FROM ${table} WHERE household_id = ? AND id IN (${placeholders})`,
    )
    .bind(db.householdId, ...ids)
    .all<{ id: string }>();
  return new Set(results.map((r) => r.id));
}

/**
 * Return every row of every table with seq > since, ordered by seq.
 *
 * Because no two rows share a seq, a page boundary can never fall in the middle
 * of a group of rows sharing one value, which would drop the remainder of that
 * group forever.
 *
 * Each table is asked for limit + 1 rows and the merge is truncated to limit,
 * which makes has_more exact: any row left out of a table's query has a seq
 * above the page cut, so it cannot belong on this page.
 */
export async function pull(
  db: HouseholdDb,
  since: number,
  limit: number = DEFAULT_PULL_LIMIT,
): Promise<PullPage> {
  const capped = Math.max(1, Math.min(limit, 2000));
  const merged: { table: TableName; seq: number; row: Record<string, unknown> }[] = [];

  for (const table of DEPENDENCY_ORDER) {
    const { results } = await db
      .prepare(
        `SELECT * FROM ${table} WHERE household_id = ? AND seq > ? ORDER BY seq LIMIT ?`,
      )
      .bind(db.householdId, since, capped + 1)
      .all<Record<string, unknown>>();
    for (const row of results) {
      merged.push({ table, seq: Number(row["seq"]), row });
    }
  }

  merged.sort((a, b) => a.seq - b.seq);
  const has_more = merged.length > capped;
  const page = has_more ? merged.slice(0, capped) : merged;

  const head = await db
    .prepare("SELECT epoch, next_seq FROM households WHERE id = ?")
    .bind(db.householdId)
    .first<{ epoch: number; next_seq: number }>();

  return {
    changes: page.map(({ table, row }) => ({ table, row })),
    epoch: head?.epoch ?? 1,
    // The high-water mark of this page, never the household counter: the client
    // persists the highest seq actually applied and nothing else.
    seq: page.length > 0 ? page[page.length - 1]!.seq : since,
    has_more,
  };
}

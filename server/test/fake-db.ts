// A SqlDatabase over node:sqlite.
//
// There is no local SQL database and no --local flag on the platform, and
// secrets do not emulate either, so the protocol is exercised against a fake.
// Node 22.5+ ships node:sqlite and a test runner, so this needs zero
// devDependencies beyond TypeScript.
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import type {
  SqlDatabase,
  SqlPreparedStatement,
  SqlQueryResult,
} from "@telnyx/edge-runtime";

const HERE = dirname(fileURLToPath(import.meta.url));

function isRead(sql: string): boolean {
  return /^\s*(SELECT|WITH)/i.test(sql) || /\bRETURNING\b/i.test(sql);
}

class FakeStatement implements SqlPreparedStatement {
  readonly db: DatabaseSync;
  readonly sql: string;
  readonly params: unknown[];

  constructor(db: DatabaseSync, sql: string, params: unknown[] = []) {
    this.db = db;
    this.sql = sql;
    this.params = params;
  }

  /** bind() returns a NEW statement; the original is never mutated. */
  bind(...values: unknown[]): SqlPreparedStatement {
    return new FakeStatement(this.db, this.sql, values);
  }

  private run_(): SqlQueryResult {
    const stmt = this.db.prepare(this.sql);
    // node:sqlite rejects undefined and booleans; normalise the way the wire
    // codec does.
    const params = this.params.map((v) => {
      if (v === undefined || v === null) return null;
      if (typeof v === "boolean") return v ? 1 : 0;
      return v as never;
    });
    if (isRead(this.sql)) {
      const rows = stmt.all(...(params as never[])) as Record<string, unknown>[];
      const changed = this.db.prepare("SELECT changes() AS c").get() as { c: number };
      return {
        results: rows,
        success: true,
        meta: { duration: 0, rows_read: rows.length, rows_written: 0, last_row_id: 0, changes: changed.c },
      };
    }
    const info = stmt.run(...(params as never[]));
    return {
      results: [],
      success: true,
      meta: {
        duration: 0,
        rows_read: 0,
        rows_written: Number(info.changes),
        last_row_id: Number(info.lastInsertRowid),
        changes: Number(info.changes),
      },
    };
  }

  async first<T = Record<string, unknown>>(column?: string): Promise<T | null> {
    const { results } = this.run_();
    const row = results[0];
    if (!row) return null;
    return (column ? ((row as Record<string, unknown>)[column] ?? null) : row) as T;
  }

  async run<T = Record<string, unknown>>(): Promise<SqlQueryResult<T>> {
    return this.run_() as SqlQueryResult<T>;
  }

  async all<T = Record<string, unknown>>(): Promise<SqlQueryResult<T>> {
    return this.run_() as SqlQueryResult<T>;
  }

  async raw<T = unknown[]>(): Promise<T[]> {
    const { results } = this.run_();
    return results.map((r) => Object.values(r)) as T[];
  }
}

export class FakeDb implements SqlDatabase {
  readonly db: DatabaseSync;

  constructor() {
    this.db = new DatabaseSync(":memory:");
    // SQLDB has foreign keys on and stock SQLite does not.
    this.db.exec("PRAGMA foreign_keys = ON");
    // Every migration, in order — not just 0001. Pinning the first one meant the
    // tests silently ran against the schema as it was on day one, so a column
    // added later existed in production and not under test.
    const dir = join(HERE, "..", "migrations");
    for (const file of readdirSync(dir).filter((f) => f.endsWith(".sql")).sort()) {
      this.db.exec(readFileSync(join(dir, file), "utf8"));
    }
  }

  prepare(query: string): SqlPreparedStatement {
    return new FakeStatement(this.db, query);
  }

  /** batch() is all-or-nothing: if any statement fails, nothing commits. */
  async batch<T = Record<string, unknown>>(
    statements: SqlPreparedStatement[],
  ): Promise<SqlQueryResult<T>[]> {
    this.db.exec("BEGIN");
    try {
      const out: SqlQueryResult<T>[] = [];
      for (const statement of statements) {
        out.push((await statement.all()) as SqlQueryResult<T>);
      }
      this.db.exec("COMMIT");
      return out;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  async exec(query: string) {
    this.db.exec(query);
    return { count: 0, duration: 0 };
  }
}

/** A household with one member, one account and a parent/child category pair. */
export function seedHousehold(fake: FakeDb, id = "hh1") {
  const now = 1_756_000_000_000;
  // Every synced row carries its own seq — no two rows share one, so the
  // fixture must not hand them all the same value either.
  const p = id === "hh1" ? "" : `${id}-`;
  fake.db.exec(
    `INSERT INTO households (id, name, next_seq, epoch, created_at)
     VALUES ('${id}', 'Dom', 6, 1, ${now});
     INSERT INTO members (id, household_id, name, created_at, seq, deleted)
     VALUES ('${p}mem1', '${id}', 'Kuba', ${now}, 1, 0);
     INSERT INTO accounts (id, household_id, name, initial_balance_minor, sort_order, seq, deleted)
     VALUES ('${p}acc1', '${id}', 'Gotowka', 0, 0, 2, 0),
            ('${p}acc2', '${id}', 'Karta', 0, 1, 3, 0);
     INSERT INTO categories (id, household_id, parent_id, name, kind, sort_order, seq, deleted)
     VALUES ('${p}cat1', '${id}', NULL, 'Jedzenie', 'expense', 0, 4, 0),
            ('${p}cat2', '${id}', '${p}cat1', 'Restauracje', 'expense', 1, 5, 0),
            ('${p}cat3', '${id}', NULL, 'Transport', 'expense', 2, 6, 0);`,
  );
  return { householdId: id, now };
}

/** A minimal expense change, ready to push. */
export function expense(
  id: string,
  amountMinor: number,
  categoryId: string | null,
  occurredOn = "2026-08-15",
) {
  return {
    table: "transactions",
    row: {
      id,
      kind: "expense",
      amount_minor: amountMinor,
      account_id: "acc1",
      category_id: categoryId,
      occurred_at: 1_756_000_000_000,
      occurred_on: occurredOn,
      created_by: "mem1",
      created_at: 1_756_000_000_000,
      deleted: 0,
    },
  };
}

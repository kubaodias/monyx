// The only path to env.DB. Nothing else in the codebase imports the binding
// — isolation has to be a mechanism, not an intention.
//
// Bindings come from the import, never from the fetch(req, env) argument:
// reading env.DB off that second argument type-checks cleanly and is undefined
// at runtime.
import { env } from "@telnyx/edge-runtime";
import type {
  SqlDatabase,
  SqlPreparedStatement,
  SqlQueryResult,
} from "@telnyx/edge-runtime";

/** A database scoped to one household. Every helper takes the id first. */
export interface HouseholdDb {
  readonly householdId: string;
  prepare(sql: string): SqlPreparedStatement;
  batch<T = Record<string, unknown>>(
    statements: SqlPreparedStatement[],
  ): Promise<SqlQueryResult<T>[]>;
}

/** Resolved from an invite code at enrolment, before a household is known. */
export interface InviteRow {
  code: string;
  household_id: string;
  expires_at: number;
  used_at: number | null;
}

/** The live binding, resolved lazily so tests never touch the runtime env. */
function binding(): SqlDatabase {
  return env.DB;
}

/**
 * The normal path: a handle pinned to one household. `db` is injectable so
 * sync.ts can be exercised against the node:sqlite fake.
 */
export function forHousehold(
  householdId: string,
  db: SqlDatabase = binding(),
): HouseholdDb {
  return {
    householdId,
    prepare: (sql) => db.prepare(sql),
    batch: (statements) => db.batch(statements),
  };
}

/**
 * Exception one: enrolment has no household yet, so the code is the only key
 * we have. Returns the invite row or null.
 */
export async function resolveInvite(
  code: string,
  db: SqlDatabase = binding(),
): Promise<InviteRow | null> {
  return db
    .prepare(
      "SELECT code, household_id, expires_at, used_at FROM invites WHERE code = ?",
    )
    .bind(code)
    .first<InviteRow>();
}

/**
 * Exception two: the daily sweep spans every household. Naming the exceptions
 * is what makes the rule checkable.
 */
export async function allHouseholds(
  db: SqlDatabase = binding(),
): Promise<{ id: string; epoch: number }[]> {
  const { results } = await db
    .prepare("SELECT id, epoch FROM households")
    .all<{ id: string; epoch: number }>();
  return results;
}

/** A device's session, resolved from its bearer token. */
export interface SessionRow {
  device_id: string;
  member_id: string;
  household_id: string;
  epoch: number;
}

/**
 * Exception three: a session token is the only key a request carries, so
 * resolving it to a household necessarily precedes any household scoping.
 * Everything downstream goes through forHousehold() with the id this returns.
 *
 * Two exceptions were planned; this is the third, and it is named here for the
 * same reason — making the exceptions countable is what makes the rule
 * checkable. See docs/decisions/0001-session-lookup-is-a-db-exception.md.
 */
export async function resolveSession(
  sessionToken: string,
  db: SqlDatabase = binding(),
): Promise<SessionRow | null> {
  return db
    .prepare(
      `SELECT d.id AS device_id, d.member_id AS member_id,
              m.household_id AS household_id, h.epoch AS epoch
       FROM devices d
       JOIN members m    ON m.id = d.member_id
       JOIN households h ON h.id = m.household_id
       WHERE d.session_token = ?`,
    )
    .bind(sessionToken)
    .first<SessionRow>();
}

// Budget aggregates, thresholds and the daily sweep.
//
// This is the ONLY implementation of the budget query. /budgets/status was cut
// (§7): the phone computes its own number from the same synced rows, and the
// server's SQL survives only here, inside the alert path, where nothing
// competes with it.
import type { HouseholdDb } from "./db.ts";
import { currentPeriod } from "./schema.ts";

/** 80% and 100% of a category budget (§8). */
export const THRESHOLDS = [80, 100] as const;

/**
 * Thresholds clear with hysteresis: 80% clears below 75%, 100% below 95% (§8).
 *
 * The naive rule — never clear — has a failure worse than the oscillation it
 * prevents: someone types 1500,00 instead of 15,00, both alerts fire, they
 * delete the transaction thirty seconds later, and the category is silent for
 * the rest of the month. The one feature the project exists for, disabled by a
 * typo the user already corrected.
 */
const CLEAR_BELOW: Record<number, number> = { 80: 75, 100: 95 };

export interface BudgetStatus {
  category_id: string;
  category_name: string;
  limit_minor: number;
  spent_minor: number;
  /** Integer percent, floored. */
  pct: number;
}

export interface PendingAlert {
  category_id: string;
  category_name: string;
  threshold: number;
  limit_minor: number;
  spent_minor: number;
  pct: number;
  period: string;
}

/**
 * The limits in effect for a period, with the spend against each.
 *
 * Carry-forward is resolved lazily at query time (§6): a budgets row is not a
 * limit for one month, it is a limit that holds from its period onward until a
 * newer row supersedes it.
 *
 * A deleted row is still the newest row, it just carries no limit — so the
 * deleted = 0 filter drops the category entirely rather than falling back to an
 * older limit, and inheritance stops there.
 *
 * A budget includes its subcategories: a limit on Home covers Home > Repairs.
 * Nesting is one level deep. A transfer has no category and never enters
 * spending statistics, which is excluded here at the query level rather than
 * left to the caller. A budget on a deleted category never fires.
 */
export async function budgetStatuses(
  db: HouseholdDb,
  period: string,
): Promise<BudgetStatus[]> {
  const hh = db.householdId;
  const { results } = await db
    .prepare(
      `WITH eff AS (
         SELECT b.category_id, b.limit_minor
         FROM budgets b
         WHERE b.household_id = ?1
           AND b.deleted = 0
           AND b.period = (
             SELECT MAX(b2.period) FROM budgets b2
             WHERE b2.household_id = b.household_id
               AND b2.category_id  = b.category_id
               AND b2.period      <= ?2
           )
       )
       SELECT eff.category_id                     AS category_id,
              c.name                              AS category_name,
              eff.limit_minor                     AS limit_minor,
              COALESCE(SUM(t.amount_minor), 0)    AS spent_minor
       FROM eff
       JOIN categories c
         ON c.id = eff.category_id
        AND c.household_id = ?1
        AND c.deleted = 0
       LEFT JOIN transactions t
         ON t.household_id = ?1
        AND t.deleted = 0
        AND t.kind = 'expense'
        AND substr(t.occurred_on, 1, 7) = ?2
        AND (
              t.category_id = eff.category_id
           OR t.category_id IN (
                SELECT sc.id FROM categories sc
                WHERE sc.parent_id = eff.category_id
                  AND sc.household_id = ?1
                  AND sc.deleted = 0
              )
        )
       GROUP BY eff.category_id, c.name, eff.limit_minor`,
    )
    .bind(hh, period)
    .all<{
      category_id: string;
      category_name: string;
      limit_minor: number;
      spent_minor: number;
    }>();

  return results.map((r) => ({
    ...r,
    pct: r.limit_minor > 0 ? Math.floor((r.spent_minor * 100) / r.limit_minor) : 0,
  }));
}

/**
 * Clear thresholds that have fallen back below their hysteresis floor, then
 * claim the ones newly crossed.
 *
 * Claiming is the mutex. budget_alerts has PRIMARY KEY (household_id,
 * category_id, period, threshold) on a single-primary SQLite where writes
 * serialise, so INSERT OR IGNORE followed by changes() is a real mutex (§8).
 * Whoever inserts the row owns the notification; everyone else sees zero rows
 * changed and does nothing. No actor is needed to re-derive a property the
 * primary key already gives.
 *
 * Returns only what this caller owns and must deliver.
 */
export async function claimAlerts(
  db: HouseholdDb,
  period: string,
  nowMs: number,
): Promise<PendingAlert[]> {
  const hh = db.householdId;
  const statuses = await budgetStatuses(db, period);
  const toDeliver: PendingAlert[] = [];

  for (const status of statuses) {
    if (status.limit_minor <= 0) continue;

    const claimed: number[] = [];

    for (const threshold of THRESHOLDS) {
      const clearAt = CLEAR_BELOW[threshold]!;
      if (status.pct < clearAt) {
        // Fell back below the floor — forget it so it can fire again.
        await db
          .prepare(
            `DELETE FROM budget_alerts
             WHERE household_id = ? AND category_id = ? AND period = ? AND threshold = ?`,
          )
          .bind(hh, status.category_id, period, threshold)
          .run();
        continue;
      }
      if (status.pct < threshold) continue;

      // Claim, then send, then stamp. notified_at starts at 0, meaning claimed
      // but not delivered; the daily sweep retries anything still at 0 (§8).
      const res = await db
        .prepare(
          `INSERT OR IGNORE INTO budget_alerts
             (household_id, category_id, period, threshold, notified_at)
           VALUES (?, ?, ?, ?, 0)`,
        )
        .bind(hh, status.category_id, period, threshold)
        .run();
      if ((res.meta?.changes ?? 0) > 0) claimed.push(threshold);
    }

    if (claimed.length === 0) continue;

    // One expense crossing both thresholds sends one notification, for the
    // higher one. The lower threshold is marked notified without being
    // delivered — and it is marked with a REAL timestamp, never 0. Writing 0
    // would leave it looking undelivered, and the sweep would send it hours
    // later as a second notification for a single crossing (§8).
    const highest = Math.max(...claimed);
    for (const threshold of claimed) {
      if (threshold === highest) continue;
      await stamp(db, status.category_id, period, threshold, nowMs);
    }

    toDeliver.push({
      category_id: status.category_id,
      category_name: status.category_name,
      threshold: highest,
      limit_minor: status.limit_minor,
      spent_minor: status.spent_minor,
      pct: status.pct,
      period,
    });
  }

  return toDeliver;
}

/**
 * Rows claimed but never delivered (notified_at = 0) — a request that died
 * between claiming and sending, or a delivery that failed. The daily sweep
 * retries these.
 */
export async function undeliveredAlerts(
  db: HouseholdDb,
  period: string,
): Promise<PendingAlert[]> {
  const hh = db.householdId;
  const { results } = await db
    .prepare(
      `SELECT category_id, period, threshold FROM budget_alerts
       WHERE household_id = ? AND notified_at = 0`,
    )
    .bind(hh)
    .all<{ category_id: string; period: string; threshold: number }>();
  if (results.length === 0) return [];

  // Recompute the numbers rather than storing them: the limit or the spend may
  // have moved since the claim, and the notification should carry the truth.
  const byPeriod = new Map<string, BudgetStatus[]>();
  const out: PendingAlert[] = [];

  for (const row of results) {
    let statuses = byPeriod.get(row.period);
    if (!statuses) {
      statuses = await budgetStatuses(db, row.period);
      byPeriod.set(row.period, statuses);
    }
    const status = statuses.find((s) => s.category_id === row.category_id);
    if (!status || status.limit_minor <= 0) {
      // The budget or its category is gone; the alert has nothing to say.
      await stamp(db, row.category_id, row.period, row.threshold, Date.now());
      continue;
    }
    out.push({
      category_id: status.category_id,
      category_name: status.category_name,
      threshold: row.threshold,
      limit_minor: status.limit_minor,
      spent_minor: status.spent_minor,
      pct: status.pct,
      period: row.period,
    });
  }
  return out;
}

/** Mark an alert delivered. Only ever called with a real timestamp. */
export async function stamp(
  db: HouseholdDb,
  categoryId: string,
  period: string,
  threshold: number,
  nowMs: number,
): Promise<void> {
  await db
    .prepare(
      `UPDATE budget_alerts SET notified_at = ?
       WHERE household_id = ? AND category_id = ? AND period = ? AND threshold = ?`,
    )
    .bind(nowMs, db.householdId, categoryId, period, threshold)
    .run();
}

/** The Europe/Warsaw period the sweep should look at. */
export function sweepPeriod(nowMs: number): string {
  return currentPeriod(nowMs);
}

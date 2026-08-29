// HTTP handler and routing, nothing else.
//
// The router does NOT consume the request body: each handler reads its own.
// §12's voice webhooks sign over {timestamp}|{raw_body}, so a central
// JSON.parse here would destroy the bytes needed to verify them. Costs nothing
// to get right today and is irritating to retrofit.
import { env } from "@telnyx/edge-runtime";
import { authenticate, createInvite, enroll, touchDevice } from "./auth.ts";
import {
  claimAlerts,
  stamp,
  sweepPeriod,
  undeliveredAlerts,
  type PendingAlert,
} from "./budgets.ts";
import { allHouseholds, forHousehold, type HouseholdDb } from "./db.ts";
import { sendBudgetAlert, type DeviceToken } from "./fcm.ts";
import { DEFAULT_PULL_LIMIT, pull, push } from "./sync.ts";
import { periodOf } from "./schema.ts";

const BACKUP_KEY = "backup/last";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function fail(status: number, code: string): Response {
  // The server never composes user-facing text: it returns codes, and the
  // client maps them to Polish strings (§9).
  return json({ error: code }, status);
}

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const text = await req.text();
    if (text.length === 0) return {};
    const parsed = JSON.parse(text);
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * Claim, then send, then stamp (§8). Claiming already happened; this delivers
 * and stamps. A delivery that fails leaves notified_at at 0 for the sweep.
 */
async function deliverAlerts(
  db: HouseholdDb,
  alerts: PendingAlert[],
  nowMs: number,
): Promise<void> {
  if (alerts.length === 0) return;

  const { results: devices } = await db
    .prepare(
      `SELECT d.id AS device_id, d.fcm_token AS fcm_token
       FROM devices d JOIN members m ON m.id = d.member_id
       WHERE m.household_id = ? AND d.fcm_token IS NOT NULL`,
    )
    .bind(db.householdId)
    .all<{ device_id: string; fcm_token: string }>();

  const tokens: DeviceToken[] = devices.map((d) => ({
    device_id: d.device_id,
    fcm_token: d.fcm_token,
  }));

  for (const alert of alerts) {
    try {
      const outcome = await sendBudgetAlert(alert, tokens);
      if (outcome.delivered > 0) {
        await stamp(db, alert.category_id, alert.period, alert.threshold, nowMs);
      }
      for (const deviceId of outcome.unregistered) {
        await db
          .prepare("UPDATE devices SET fcm_token = NULL WHERE id = ?")
          .bind(deviceId)
          .run();
      }
    } catch (error) {
      // Leave notified_at at 0 — the daily sweep retries it (§8).
      console.error("alert delivery failed", error);
    }
  }
}

async function handleEnroll(req: Request, nowMs: number): Promise<Response> {
  const limiter = env.ENROLL_LIMIT;
  if (limiter) {
    const key = req.headers.get("x-forwarded-for") ?? "anonymous";
    const { success } = await limiter.limit({ key });
    if (!success) return fail(429, "rate_limited");
  }

  const body = await readJson(req);
  if (!body) return fail(400, "bad_json");

  const result = await enroll(
    {
      invite_code: body["invite_code"],
      member_name: body["member_name"],
      device_label: body["device_label"],
    },
    nowMs,
  );
  if (!result.ok) return fail(result.status, result.code);
  return json(result.value);
}

async function handlePush(req: Request, session: SessionLike, nowMs: number): Promise<Response> {
  const body = await readJson(req);
  if (!body) return fail(400, "bad_json");

  const changes = body["changes"];
  if (!Array.isArray(changes)) return fail(400, "changes_must_be_an_array");

  const db = forHousehold(session.household_id);

  const fcmToken = typeof body["fcm_token"] === "string" ? body["fcm_token"] : null;
  await touchDevice(session.household_id, session.device_id, fcmToken, nowMs);

  const result = await push(db, changes);

  // The threshold check runs inline right after the batch commits — not in an
  // actor (§8). The alert lands seconds after the expense that crossed the
  // line, which is the only moment it is worth anything.
  const periods = new Set<string>();
  for (const row of result.acceptedTransactions) {
    const on = row["occurred_on"];
    if (typeof on === "string") periods.add(periodOf(on));
  }
  for (const period of periods) {
    const alerts = await claimAlerts(db, period, nowMs);
    await deliverAlerts(db, alerts, nowMs);
  }

  return json({ seq: result.seq, applied: result.applied, rejected: result.rejected });
}

async function handlePull(url: URL, session: SessionLike): Promise<Response> {
  const since = Number(url.searchParams.get("since") ?? "0");
  const limit = Number(url.searchParams.get("limit") ?? String(DEFAULT_PULL_LIMIT));
  if (!Number.isFinite(since) || since < 0) return fail(400, "bad_since");

  const db = forHousehold(session.household_id);
  const page = await pull(db, since, Number.isFinite(limit) ? limit : DEFAULT_PULL_LIMIT);

  // An invisible backup is not a backup (§8): the app reads this on open and
  // Settings warns when it is more than three days old.
  let lastBackupAt: number | null = null;
  try {
    const raw = await env.KV.get(BACKUP_KEY);
    if (raw) lastBackupAt = Number(raw) || null;
  } catch {
    lastBackupAt = null;
  }

  return json({ ...page, last_backup_at: lastBackupAt });
}

async function handleCron(req: Request, nowMs: number): Promise<Response> {
  let expected: string;
  try {
    expected = await env.SECRETS.get("CRON_SECRET");
  } catch {
    return fail(500, "cron_secret_unavailable");
  }
  const provided = req.headers.get("x-cron-secret");
  if (!provided || provided !== expected) return fail(401, "unauthorized");

  const body = (await readJson(req)) ?? {};

  // The workflow reports its own success: the export runs outside the platform,
  // so the function would otherwise never learn it happened (§8).
  const reported = body["last_backup_at"];
  if (typeof reported === "number" && Number.isFinite(reported)) {
    await env.KV.put(BACKUP_KEY, String(reported));
  }

  const period = sweepPeriod(nowMs);
  const households = await allHouseholds();
  let delivered = 0;

  for (const household of households) {
    const db = forHousehold(household.id);
    // What the push path cannot see: a month boundary, a budget revised
    // downward, a delivery that failed, a phone that was offline (§8).
    const fresh = await claimAlerts(db, period, nowMs);
    const retries = await undeliveredAlerts(db, period);
    const all = [...fresh, ...dedupe(retries, fresh)];
    await deliverAlerts(db, all, nowMs);
    delivered += all.length;
  }

  return json({ ok: true, households: households.length, alerts: delivered, period });
}

function dedupe(retries: PendingAlert[], fresh: PendingAlert[]): PendingAlert[] {
  const seen = new Set(fresh.map((a) => `${a.category_id}|${a.period}|${a.threshold}`));
  return retries.filter((a) => !seen.has(`${a.category_id}|${a.period}|${a.threshold}`));
}

interface SessionLike {
  device_id: string;
  member_id: string;
  household_id: string;
  epoch: number;
}

export default {
  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const nowMs = Date.now();

    // Must be fast — it serves platform monitoring — and never touches the
    // database (§7).
    if (path === "/health") return new Response("ok", { status: 200 });

    try {
      if (path === "/auth/enroll" && req.method === "POST") {
        return await handleEnroll(req, nowMs);
      }
      if (path === "/cron/daily" && req.method === "POST") {
        return await handleCron(req, nowMs);
      }

      const session = await authenticate(req.headers.get("authorization"));
      if (!session) return fail(401, "unauthorized");

      if (path === "/invites" && req.method === "POST") {
        return json(await createInvite(session.household_id, nowMs));
      }
      if (path === "/sync/push" && req.method === "POST") {
        return await handlePush(req, session, nowMs);
      }
      if (path === "/sync/pull" && req.method === "GET") {
        return await handlePull(url, session);
      }

      return fail(404, "not_found");
    } catch (error) {
      console.error("unhandled", path, error);
      return fail(500, "internal_error");
    }
  },
};

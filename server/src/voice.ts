// The voice assistant's read-only window onto a household.
//
// /voice/context runs at call setup and returns the month itself: the whole
// digest goes into the system prompt before the assistant speaks, so the first
// question costs no round trip and every one after it is answered from context.
//
// The allowlist is the only gate. That is a deliberate choice and a weak one —
// caller ID is not a credential, and a spoofed ANI reaches the household's
// finances — but it is the choice that makes the preload possible at all. A
// secret placed in a prompt cannot be withdrawn from it, so anything gated
// AFTER the preload can only be guarded by the model's willingness to keep it,
// which is not a boundary. The two are mutually exclusive; this picks the
// preload. See ADR 0018.
//
// /voice/digest re-reads the same digest mid-call, for a caller who asks
// whether something has just landed, and covers the case where the call-setup
// webhook timed out and the prompt has no digest in it.
//
// Read-only by construction: this module issues SELECTs and nothing else.
import { randomBytes, timingSafeEqual } from "node:crypto";
import { budgetStatuses } from "./budgets.ts";
import type { HouseholdDb } from "./db.ts";

/**
 * How long a ticket outlives the call setup that minted it. It only ever buys
 * a re-read of what the prompt already holds, so it outliving the call is not
 * interesting — the call itself is capped well below this.
 */
export const TICKET_TTL_SECS = 30 * 60;

const TICKET_PREFIX = "voice/ticket-";

/** Recent transactions read aloud; more than this is a list, not an answer. */
const RECENT_LIMIT = 10;

/**
 * Who may call and which household they reach.
 *
 * Held as a secret rather than a table: it is a handful of phone numbers, and
 * a phone number is personal data that has no business in the repository or in
 * a database whose backups get copied around. See
 * docs/decisions/0018-the-assistant-gets-a-window-not-a-key.md.
 */
export interface Caller {
  msisdn: string;
  household_id: string;
  name: string;
}

export interface Ticket {
  msisdn: string;
  household_id: string;
}

/** Storage this module needs. Injected so the tests never touch the runtime. */
export interface VoiceStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
  delete(key: string): Promise<void>;
}

// ------------------------------------------------------------------ identity

/**
 * Compare on digits alone.
 *
 * The same phone arrives as +48…, 0048…, 48… or bare national depending on the
 * carrier in the path, and a caller who is silently not on the allowlist is
 * indistinguishable from a broken deployment.
 */
export function normalizeMsisdn(raw: unknown): string {
  if (typeof raw !== "string") return "";
  let digits = raw.replace(/\D/g, "");
  if (digits.startsWith("00")) digits = digits.slice(2);
  // A bare Polish national number is nine digits; anything longer already
  // carries a country code.
  if (digits.length === 9) digits = `48${digits}`;
  return digits;
}

export function parseAllowlist(raw: string): Caller[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: Caller[] = [];
  for (const entry of parsed) {
    if (typeof entry !== "object" || entry === null) continue;
    const row = entry as Record<string, unknown>;
    const msisdn = normalizeMsisdn(row["msisdn"]);
    const household = row["household_id"];
    if (msisdn.length === 0) continue;
    if (typeof household !== "string" || household.length === 0) continue;
    out.push({
      msisdn,
      household_id: household,
      name: typeof row["name"] === "string" ? row["name"] : "",
    });
  }
  return out;
}

export function findCaller(allowlist: Caller[], target: unknown): Caller | null {
  const msisdn = normalizeMsisdn(target);
  if (msisdn.length === 0) return null;
  return allowlist.find((c) => c.msisdn === msisdn) ?? null;
}

/** Constant-time where it matters, and unfooled by a length mismatch. */
export function secretEquals(a: string, b: string): boolean {
  const left = Buffer.from(a, "utf8");
  const right = Buffer.from(b, "utf8");
  if (left.length !== right.length) {
    // Still compare, so the reject path costs what the accept path costs.
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}

// -------------------------------------------------------------------- tickets

export function newTicketId(): string {
  return randomBytes(24).toString("base64url");
}

function ticketKey(id: string): string {
  // KV keys may not contain a colon.
  return `${TICKET_PREFIX}${id}`;
}

export async function putTicket(store: VoiceStore, id: string, ticket: Ticket): Promise<void> {
  await store.put(ticketKey(id), JSON.stringify(ticket), { expirationTtl: TICKET_TTL_SECS });
}

export async function readTicket(store: VoiceStore, id: unknown): Promise<Ticket | null> {
  if (typeof id !== "string" || id.length === 0 || id.length > 200) return null;
  // A ticket id reaches KV as part of a key; keep it to the alphabet we mint.
  if (!/^[A-Za-z0-9_-]+$/.test(id)) return null;
  let raw: string | null;
  try {
    raw = await store.get(ticketKey(id));
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Ticket;
    if (typeof parsed?.household_id !== "string") return null;
    return {
      msisdn: typeof parsed.msisdn === "string" ? parsed.msisdn : "",
      household_id: parsed.household_id,
    };
  } catch {
    return null;
  }
}

// --------------------------------------------------------------------- digest

export interface DigestAccount {
  name: string;
  balance_minor: number;
  archived: boolean;
  /** Savings held elsewhere: listed, but not in the total or the month. */
  excluded: boolean;
}

export interface DigestBudget {
  category: string;
  limit_minor: number;
  spent_minor: number;
  pct: number;
}

export interface DigestSpend {
  category: string;
  spent_minor: number;
}

export interface DigestTx {
  occurred_on: string;
  kind: string;
  amount_minor: number;
  category: string | null;
  account: string;
  member: string;
  note: string | null;
}

export interface DigestData {
  household: string;
  period: string;
  today: string;
  accounts: DigestAccount[];
  income_minor: number;
  expense_minor: number;
  planned_minor: number | null;
  previous_expense_minor: number;
  budgets: DigestBudget[];
  spending: DigestSpend[];
  recent: DigestTx[];
}

function monthBounds(period: string): [string, string] {
  // Lexicographic order matches chronological order for YYYY-MM-DD, so a plain
  // BETWEEN uses the index on occurred_on. '-31' is a safe upper bound in
  // every month: it is above every real day and below the next month's '-01'.
  return [`${period}-01`, `${period}-31`];
}

export function previousPeriod(period: string): string {
  const year = Number(period.slice(0, 4));
  const month = Number(period.slice(5, 7));
  if (!Number.isFinite(year) || !Number.isFinite(month)) return period;
  const prevMonth = month === 1 ? 12 : month - 1;
  const prevYear = month === 1 ? year - 1 : year;
  return `${String(prevYear).padStart(4, "0")}-${String(prevMonth).padStart(2, "0")}`;
}

/**
 * Everything the assistant is allowed to know, in one pass.
 *
 * One shot rather than a tool per question: a voice call charges for silence,
 * and a household's month is a few kilobytes. The model answers follow-ups from
 * what it already has.
 */
export async function collectDigest(
  db: HouseholdDb,
  period: string,
  today: string,
): Promise<DigestData> {
  const hh = db.householdId;
  const [from, to] = monthBounds(period);
  const [prevFrom, prevTo] = monthBounds(previousPeriod(period));

  const household = await db
    .prepare("SELECT name FROM households WHERE id = ?")
    .bind(hh)
    .first<{ name: string }>();

  // Mirrors the app's accountBalances(): opening balance, plus what came in,
  // minus what went out, minus transfers away, plus transfers in.
  const { results: accounts } = await db
    .prepare(
      `SELECT a.name AS name,
              a.archived AS archived,
              a.excluded_from_summary AS excluded,
              a.initial_balance_minor
                + COALESCE((SELECT SUM(CASE t.kind
                                         WHEN 'income'  THEN  t.amount_minor
                                         ELSE                -t.amount_minor
                                       END)
                            FROM transactions t
                            WHERE t.household_id = a.household_id
                              AND t.account_id   = a.id
                              AND t.deleted      = 0), 0)
                + COALESCE((SELECT SUM(t.amount_minor)
                            FROM transactions t
                            WHERE t.household_id        = a.household_id
                              AND t.transfer_account_id = a.id
                              AND t.kind                = 'transfer'
                              AND t.deleted             = 0), 0)
              AS balance_minor
       FROM accounts a
       WHERE a.household_id = ? AND a.deleted = 0
       ORDER BY a.archived, a.sort_order, a.name`,
    )
    .bind(hh)
    .all<{ name: string; archived: number; excluded: number; balance_minor: number }>();

  const { results: totals } = await db
    .prepare(
      `SELECT kind, SUM(amount_minor) AS total
       FROM transactions
       WHERE household_id = ? AND deleted = 0
         AND kind IN ('income','expense')
         AND account_id NOT IN (SELECT id FROM accounts WHERE excluded_from_summary = 1)
         AND occurred_on BETWEEN ? AND ?
       GROUP BY kind`,
    )
    .bind(hh, from, to)
    .all<{ kind: string; total: number }>();

  const previous = await db
    .prepare(
      `SELECT COALESCE(SUM(amount_minor), 0) AS total
       FROM transactions
       WHERE household_id = ? AND deleted = 0 AND kind = 'expense'
         AND account_id NOT IN (SELECT id FROM accounts WHERE excluded_from_summary = 1)
         AND occurred_on BETWEEN ? AND ?`,
    )
    .bind(hh, prevFrom, prevTo)
    .first<{ total: number }>();

  const plan = await db
    .prepare(
      "SELECT planned_minor FROM month_plans WHERE household_id = ? AND period = ? AND deleted = 0",
    )
    .bind(hh, period)
    .first<{ planned_minor: number }>();

  // Rolled up to the root, the way a budget is: a limit on Home covers
  // Home > Repairs, so the spend that answers "how much on Home" must too.
  const { results: spending } = await db
    .prepare(
      `SELECT COALESCE(p.name, c.name) AS category,
              SUM(t.amount_minor)      AS spent_minor
       FROM transactions t
       JOIN categories c ON c.id = t.category_id
       LEFT JOIN categories p ON p.id = c.parent_id
       WHERE t.household_id = ? AND t.deleted = 0 AND t.kind = 'expense'
         AND t.account_id NOT IN (SELECT id FROM accounts WHERE excluded_from_summary = 1)
         AND t.occurred_on BETWEEN ? AND ?
       GROUP BY COALESCE(p.id, c.id)
       ORDER BY spent_minor DESC`,
    )
    .bind(hh, from, to)
    .all<{ category: string; spent_minor: number }>();

  const { results: recent } = await db
    .prepare(
      `SELECT t.occurred_on AS occurred_on,
              t.kind        AS kind,
              t.amount_minor AS amount_minor,
              c.name        AS category,
              a.name        AS account,
              m.name        AS member,
              t.note        AS note
       FROM transactions t
       JOIN accounts a ON a.id = t.account_id
       JOIN members  m ON m.id = t.created_by
       LEFT JOIN categories c ON c.id = t.category_id
       WHERE t.household_id = ? AND t.deleted = 0
       ORDER BY t.occurred_at DESC, t.seq DESC
       LIMIT ?`,
    )
    .bind(hh, RECENT_LIMIT)
    .all<{
      occurred_on: string;
      kind: string;
      amount_minor: number;
      category: string | null;
      account: string;
      member: string;
      note: string | null;
    }>();

  // The one budget query in the codebase, reused rather than restated.
  const budgets = await budgetStatuses(db, period);

  const totalOf = (kind: string): number =>
    totals.find((row) => row.kind === kind)?.total ?? 0;

  return {
    household: household?.name ?? "",
    period,
    today,
    accounts: accounts.map((row) => ({
      name: row.name,
      balance_minor: row.balance_minor,
      archived: row.archived === 1,
      excluded: row.excluded === 1,
    })),
    income_minor: totalOf("income"),
    expense_minor: totalOf("expense"),
    planned_minor: plan?.planned_minor ?? null,
    previous_expense_minor: previous?.total ?? 0,
    budgets: budgets.map((b) => ({
      category: b.category_name,
      limit_minor: b.limit_minor,
      spent_minor: b.spent_minor,
      pct: b.pct,
    })),
    spending: spending.map((row) => ({
      category: row.category,
      spent_minor: row.spent_minor,
    })),
    recent,
  };
}

// ------------------------------------------------------------------ rendering

/** Minor units to the decimal string a person would say. */
export function amount(minor: number): string {
  const negative = minor < 0;
  const abs = Math.abs(Math.trunc(minor));
  const major = Math.floor(abs / 100);
  const cents = abs % 100;
  return `${negative ? "-" : ""}${major}.${String(cents).padStart(2, "0")}`;
}

export function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function attr(name: string, value: string | number | null): string {
  if (value === null) return "";
  return ` ${name}="${escapeXml(String(value))}"`;
}

/**
 * XML rather than prose or a JSON blob.
 *
 * Prose invites the model to quote it back verbatim, and quoting a table over
 * the phone is unlistenable. Tags are unambiguous about which number belongs to
 * which label, survive being read out of order, and cost fewer tokens than the
 * equivalent JSON once every key is repeated per row.
 */
export function renderDigest(data: DigestData): string {
  const lines: string[] = [];
  lines.push(
    `<budget currency="PLN"${attr("household", data.household)}${attr("period", data.period)}${attr("today", data.today)}>`,
  );

  const live = data.accounts.filter((a) => !a.archived && !a.excluded);
  const total = live.reduce((sum, a) => sum + a.balance_minor, 0);
  lines.push(`  <accounts total="${amount(total)}">`);
  for (const account of data.accounts) {
    lines.push(
      `    <account${attr("name", account.name)} balance="${amount(account.balance_minor)}"${
        account.archived ? ' archived="true"' : ""
      }${account.excluded ? ' in_summary="false"' : ""}/>`,
    );
  }
  lines.push("  </accounts>");

  lines.push(
    `  <month income="${amount(data.income_minor)}" expense="${amount(data.expense_minor)}" net="${amount(
      data.income_minor - data.expense_minor,
    )}"${
      data.planned_minor === null ? "" : ` planned="${amount(data.planned_minor)}"`
    } previous_month_expense="${amount(data.previous_expense_minor)}"/>`,
  );

  if (data.budgets.length > 0) {
    lines.push("  <budgets>");
    for (const b of data.budgets) {
      lines.push(
        `    <budget${attr("category", b.category)} limit="${amount(b.limit_minor)}" spent="${amount(
          b.spent_minor,
        )}" percent="${b.pct}" remaining="${amount(b.limit_minor - b.spent_minor)}"/>`,
      );
    }
    lines.push("  </budgets>");
  }

  if (data.spending.length > 0) {
    lines.push("  <spending>");
    for (const s of data.spending) {
      lines.push(`    <category${attr("name", s.category)} spent="${amount(s.spent_minor)}"/>`);
    }
    lines.push("  </spending>");
  }

  if (data.recent.length > 0) {
    lines.push("  <recent>");
    for (const t of data.recent) {
      lines.push(
        `    <tx${attr("date", t.occurred_on)}${attr("kind", t.kind)} amount="${amount(
          t.amount_minor,
        )}"${attr("category", t.category)}${attr("account", t.account)}${attr("by", t.member)}${attr(
          "note",
          t.note,
        )}/>`,
      );
    }
    lines.push("  </recent>");
  }

  lines.push("</budget>");
  return lines.join("\n");
}

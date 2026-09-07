// Types, table specs and input validation.
//
// Every change is validated here before any SQL runs. This is forced
// by two platform facts working together: batch() is all-or-nothing, and SQLDB
// failures reject with a plain Error — no typed class, no error codes. A single
// malformed row reaching SQL would roll back the entire push, and classifying
// why would mean matching on message substrings.

export type TableName =
  | "members"
  | "accounts"
  | "categories"
  | "month_plans"
  | "budgets"
  | "recurring_rules"
  | "transactions";

/**
 * Dependency order, not timestamp order. A new category and a
 * transaction in it must not arrive the other way round: the foreign key check
 * would reject the transaction and a real expense would be lost while the user
 * watched it save.
 */
export const DEPENDENCY_ORDER: readonly TableName[] = [
  "members",
  "accounts",
  "categories",
  // Depends on nothing but the household; sits beside budgets because that is
  // where it is read.
  "month_plans",
  "budgets",
  // Before transactions, because a generated transaction carries
  // recurring_rule_id and the foreign key check would reject it if the rule
  // that produced it had not arrived yet.
  "recurring_rules",
  "transactions",
] as const;

const TABLE_SET = new Set<string>(DEPENDENCY_ORDER);

export function isTableName(value: unknown): value is TableName {
  return typeof value === "string" && TABLE_SET.has(value);
}

/**
 * Columns per table, in the order they are written. `household_id` and `seq`
 * are stamped by the server and are not accepted from the client.
 */
export const COLUMNS: Record<TableName, readonly string[]> = {
  members: ["id", "household_id", "name", "created_at", "seq", "deleted"],
  accounts: [
    "id", "household_id", "name", "icon", "color",
    "initial_balance_minor", "sort_order", "archived", "seq", "deleted",
  ],
  categories: [
    "id", "household_id", "parent_id", "name", "icon", "color",
    "kind", "sort_order", "seq", "deleted",
  ],
  month_plans: [
    "id", "household_id", "period", "planned_minor", "seq", "deleted",
  ],
  budgets: [
    "id", "household_id", "category_id", "period", "limit_minor",
    "seq", "deleted",
  ],
  recurring_rules: [
    "id", "household_id", "kind", "amount_minor", "account_id",
    "category_id", "note", "freq", "starts_on", "ends_on",
    "created_by", "created_at", "sort_order", "seq", "deleted",
  ],
  transactions: [
    "id", "household_id", "kind", "amount_minor", "account_id",
    "transfer_account_id", "category_id", "note", "occurred_at",
    "occurred_on", "created_by", "source", "recurring_rule_id",
    "created_at", "seq", "deleted",
  ],
};

/** Foreign keys checked before the batch, against this household only. */
export const FOREIGN_KEYS: Record<
  TableName,
  readonly { column: string; table: TableName }[]
> = {
  members: [],
  accounts: [],
  categories: [{ column: "parent_id", table: "categories" }],
  month_plans: [],
  budgets: [{ column: "category_id", table: "categories" }],
  recurring_rules: [
    { column: "account_id", table: "accounts" },
    { column: "category_id", table: "categories" },
    { column: "created_by", table: "members" },
  ],
  transactions: [
    { column: "account_id", table: "accounts" },
    { column: "transfer_account_id", table: "accounts" },
    { column: "category_id", table: "categories" },
    { column: "created_by", table: "members" },
    { column: "recurring_rule_id", table: "recurring_rules" },
  ],
};

export interface Change {
  table: TableName;
  row: Record<string, unknown>;
}

export interface Rejection {
  table: string;
  id: string | null;
  reason: string;
}

export type ValidationResult =
  | { ok: true; change: Change }
  | { ok: false; rejection: Rejection };

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const PERIOD_RE = /^\d{4}-\d{2}$/;
const TX_KINDS = new Set(["expense", "income", "transfer"]);
const CAT_KINDS = new Set(["expense", "income"]);
const SOURCES = new Set(["manual", "voice", "receipt"]);
const FREQS = new Set(["weekly", "monthly", "yearly"]);

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function isInt(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v);
}

function optionalText(v: unknown): boolean {
  return v === undefined || v === null || typeof v === "string";
}

/** Optional id-shaped foreign key: absent, null, or a well-formed id. */
function optionalId(v: unknown): boolean {
  return v === undefined || v === null || (typeof v === "string" && ID_RE.test(v));
}

function flag(v: unknown): boolean {
  return v === 0 || v === 1;
}

/**
 * Validate one incoming change. Money is an integer in minor units — never a
 * float, never a string. That is the one rule whose violation is a critical
 * bug, so it is checked on every amount here.
 */
export function validateChange(raw: unknown): ValidationResult {
  if (!isPlainObject(raw)) {
    return { ok: false, rejection: { table: "?", id: null, reason: "change_not_an_object" } };
  }
  const table = raw["table"];
  const row = raw["row"];
  if (!isTableName(table)) {
    return { ok: false, rejection: { table: String(table ?? "?"), id: null, reason: "unknown_table" } };
  }
  if (!isPlainObject(row)) {
    return { ok: false, rejection: { table, id: null, reason: "row_not_an_object" } };
  }

  const id = row["id"];
  const rid = typeof id === "string" ? id : null;
  if (typeof id !== "string" || !ID_RE.test(id)) {
    return { ok: false, rejection: { table, id: rid, reason: "bad_id" } };
  }
  if (!flag(row["deleted"])) {
    return { ok: false, rejection: { table, id: rid, reason: "bad_deleted" } };
  }

  const reject = (reason: string): ValidationResult => ({
    ok: false,
    rejection: { table, id: rid, reason },
  });

  switch (table) {
    case "members": {
      if (typeof row["name"] !== "string" || row["name"].length === 0) return reject("bad_name");
      if (!isInt(row["created_at"])) return reject("bad_created_at");
      break;
    }
    case "accounts": {
      if (typeof row["name"] !== "string" || row["name"].length === 0) return reject("bad_name");
      if (!optionalText(row["icon"]) || !optionalText(row["color"])) return reject("bad_icon_or_color");
      if (!isInt(row["initial_balance_minor"])) return reject("bad_initial_balance_minor");
      if (!isInt(row["sort_order"])) return reject("bad_sort_order");
      // Absent is legal, and means 0: a client built before archiving existed
      // still pushes accounts, and rejecting those would strand it.
      if (row["archived"] !== undefined && !flag(row["archived"])) return reject("bad_archived");
      break;
    }
    case "categories": {
      if (typeof row["name"] !== "string" || row["name"].length === 0) return reject("bad_name");
      if (!CAT_KINDS.has(String(row["kind"]))) return reject("bad_kind");
      if (!optionalId(row["parent_id"])) return reject("bad_parent_id");
      if (row["parent_id"] === id) return reject("self_parent");
      if (!optionalText(row["icon"]) || !optionalText(row["color"])) return reject("bad_icon_or_color");
      if (!isInt(row["sort_order"])) return reject("bad_sort_order");
      break;
    }
    case "month_plans": {
      if (typeof row["period"] !== "string" || !PERIOD_RE.test(row["period"])) return reject("bad_period");
      // A plan of zero is meaningful — "I have nothing to spend this month" —
      // so only a negative or non-integer is wrong.
      if (!isInt(row["planned_minor"]) || (row["planned_minor"] as number) < 0) {
        return reject("bad_planned_minor");
      }
      break;
    }
    case "budgets": {
      if (typeof row["category_id"] !== "string" || !ID_RE.test(row["category_id"])) return reject("bad_category_id");
      if (typeof row["period"] !== "string" || !PERIOD_RE.test(row["period"])) return reject("bad_period");
      if (!isInt(row["limit_minor"]) || row["limit_minor"] < 0) return reject("bad_limit_minor");
      break;
    }
    case "recurring_rules": {
      // A rule cannot be a transfer. AddScreen offers expense and income only,
      // and a repeating transfer between two of the household's own accounts is
      // a standing order the bank already runs.
      if (!CAT_KINDS.has(String(row["kind"]))) return reject("bad_kind");
      if (!isInt(row["amount_minor"]) || (row["amount_minor"] as number) <= 0) {
        return reject("bad_amount_minor");
      }
      if (typeof row["account_id"] !== "string" || !ID_RE.test(row["account_id"])) {
        return reject("bad_account_id");
      }
      if (!optionalId(row["category_id"])) return reject("bad_category_id");
      if (!optionalText(row["note"])) return reject("bad_note");
      if (!FREQS.has(String(row["freq"]))) return reject("bad_freq");
      // starts_on IS the schedule, not merely the first date it is valid from:
      // a monthly rule repeats on the anchor's day-of-month, a yearly one on its
      // month and day. A malformed anchor is therefore a malformed rule.
      if (typeof row["starts_on"] !== "string" || !DATE_RE.test(row["starts_on"])) {
        return reject("bad_starts_on");
      }
      const endsOn = row["ends_on"];
      if (endsOn !== undefined && endsOn !== null) {
        if (typeof endsOn !== "string" || !DATE_RE.test(endsOn)) return reject("bad_ends_on");
        if (endsOn < (row["starts_on"] as string)) return reject("ends_before_starts");
      }
      if (typeof row["created_by"] !== "string" || !ID_RE.test(row["created_by"])) {
        return reject("bad_created_by");
      }
      if (!isInt(row["created_at"])) return reject("bad_created_at");
      // Absent is legal, and means 0 — the same bargain accounts.archived
      // struck. A client built before rules could be dragged into an order
      // still pushes them, and rejecting those would strand it.
      if (row["sort_order"] !== undefined && !isInt(row["sort_order"])) {
        return reject("bad_sort_order");
      }
      break;
    }
    case "transactions": {
      const kind = String(row["kind"]);
      if (!TX_KINDS.has(kind)) return reject("bad_kind");
      // Amounts are always positive; direction comes from kind.
      if (!isInt(row["amount_minor"]) || (row["amount_minor"] as number) <= 0) return reject("bad_amount_minor");
      if (typeof row["account_id"] !== "string" || !ID_RE.test(row["account_id"])) return reject("bad_account_id");
      if (!optionalId(row["transfer_account_id"])) return reject("bad_transfer_account_id");
      if (!optionalId(row["category_id"])) return reject("bad_category_id");
      if (!optionalText(row["note"])) return reject("bad_note");
      if (!isInt(row["occurred_at"])) return reject("bad_occurred_at");
      if (typeof row["occurred_on"] !== "string" || !DATE_RE.test(row["occurred_on"])) return reject("bad_occurred_on");
      if (typeof row["created_by"] !== "string" || !ID_RE.test(row["created_by"])) return reject("bad_created_by");
      // NOT NULL in the schema. Unvalidated, a missing value reaches SQL and,
      // because batch() is all-or-nothing, takes the whole push down with it.
      if (!isInt(row["created_at"])) return reject("bad_created_at");
      if (row["source"] !== undefined && !SOURCES.has(String(row["source"]))) return reject("bad_source");
      if (!optionalId(row["recurring_rule_id"])) return reject("bad_recurring_rule_id");
      // A transfer has no category and never enters spending statistics.
      if (kind === "transfer") {
        if (typeof row["transfer_account_id"] !== "string") return reject("transfer_needs_target");
        if (row["transfer_account_id"] === row["account_id"]) return reject("transfer_to_self");
        if (row["category_id"] !== undefined && row["category_id"] !== null) return reject("transfer_has_category");
      } else {
        if (row["transfer_account_id"] !== undefined && row["transfer_account_id"] !== null) {
          return reject("non_transfer_has_target");
        }
        // A non-transfer with no category is allowed: v2 splits set it NULL.
      }
      break;
    }
  }

  return { ok: true, change: { table, row } };
}

/**
 * The local date in Europe/Warsaw, as 'YYYY-MM-DD'.
 *
 * Months are bucketed on a local date: without it an expense entered at
 * 01:30 on 1 September in Warsaw falls into August for the server and September
 * for the phone. The client authors occurred_on today, but the daily sweep needs
 * the current period, so the helper is written once here rather than three times
 * later.
 */
const WARSAW_DATE = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Europe/Warsaw",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export function localDate(tsMs: number): string {
  return WARSAW_DATE.format(new Date(tsMs));
}

/** The 'YYYY-MM' period a local date falls in. */
export function periodOf(localDateStr: string): string {
  return localDateStr.slice(0, 7);
}

/** The current Europe/Warsaw period, 'YYYY-MM'. */
export function currentPeriod(nowMs: number): string {
  return periodOf(localDate(nowMs));
}

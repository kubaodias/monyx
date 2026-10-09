// Exchange rates: what a foreign-currency account's money is worth in złoty.
//
// Złoty is the reporting currency and is not configurable. An account carries a
// currency (see migrations/0008) and these rates say what one unit of it was
// worth on a given day, so that every total the app prints stays in one unit.
import type { SqlDatabase, SqlPreparedStatement } from "@telnyx/edge-runtime";

/**
 * The currencies an account may be denominated in.
 *
 * All ten are in NBP table A, and every one is a two-decimal currency in ISO
 * 4217 — which is what lets amount_minor stay "hundredths of the unit" for all
 * of them, with no per-currency minor-unit table and no class of off-by-100
 * bug. Adding JPY or HUF-as-used would break that assumption, so a currency
 * does not go on this list without checking it.
 *
 * Croatia is absent on purpose: the kuna was retired when Croatia adopted the
 * euro on 1 January 2023, so a Croatian balance IS a euro balance.
 */
export const CURRENCIES = [
  "PLN", "EUR", "USD", "GBP", "CHF", "CZK", "SEK", "NOK", "DKK",
] as const;

export type Currency = (typeof CURRENCIES)[number];

const CURRENCY_SET = new Set<string>(CURRENCIES);

/** The reporting currency. Needs no rate: it is the unit the rates are in. */
export const BASE: Currency = "PLN";

/** One of the nine a rate is published for. */
export function isCurrency(value: unknown): value is Currency {
  return typeof value === "string" && CURRENCY_SET.has(value);
}

/**
 * Any currency CODE an account or a row may carry: three capitals.
 *
 * Deliberately wider than [isCurrency]. A household can add a currency of its
 * own in Settings — a code and the symbol to print after a figure — and the
 * server's job is to store what it is told, not to hold an opinion about which
 * currencies exist. A code outside [CURRENCIES] simply has no rate, which every
 * total on both ends already handles: the row keeps its own figure and is left
 * out of the złoty sums, visibly, the same way a rated currency behaves before
 * its first rate has synced.
 *
 * Still a shape check, so a push cannot write "zloty" or an empty string into
 * the column and leave a client resolving it to nothing. ISO 4217 is not
 * enumerated here or on the phone: "QQQ" is a currency as far as both are
 * concerned, and the household that typed it is the one that knows.
 */
export function isCurrencyCode(value: unknown): value is string {
  return typeof value === "string" && /^[A-Z]{3}$/.test(value);
}

/** Rates are stored scaled by this, so conversion is integer arithmetic. */
export const RATE_SCALE = 1_000_000;

/** One unit of [currency] in złoty, on [effective_on]. */
export interface Rate {
  currency: Currency;
  effective_on: string;
  published_on: string;
  rate_micro: number;
}

/**
 * Convert [amountMinor] of [currency] into grosze at [rateMicro].
 *
 * Rounds half away from zero, and does it symmetrically for negatives, so that
 * converting a refund of 10,00 € and converting the 10,00 € it refunds cannot
 * leave a grosz behind. Integer throughout: the inputs are integers, the output
 * is an integer, and no float is constructed on the way.
 */
export function convertMinor(amountMinor: number, rateMicro: number): number {
  const product = amountMinor * rateMicro;
  const sign = product < 0 ? -1 : 1;
  return sign * Math.round(Math.abs(product) / RATE_SCALE);
}

/**
 * NBP table A for one date, or null when there is no table for it.
 *
 * Null is the ordinary answer for a Saturday, a Sunday or a public holiday —
 * the API answers 404, which is not an error but "no rate was set that day".
 * The caller carries the previous publication forward; see [refreshRates].
 */
export async function fetchNbpTable(
  date: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Map<Currency, number> | null> {
  const byDate = await fetchNbpRange(date, date, fetchImpl);
  return byDate.get(date) ?? null;
}

/** NBP publishes at most this many days per request. */
export const NBP_RANGE_DAYS = 93;

/**
 * Every published table between [from] and [to], in ONE request per window.
 *
 * The single-date endpoint would mean 365 requests to backfill a year, which is
 * why the first cut of this could not realistically backfill at all and left
 * history unconvertible. The range endpoint caps at 93 days, so a year is four
 * requests and a decade is forty.
 *
 * An empty map is a legitimate answer: a range covering only a holiday weekend
 * has no publications in it, and NBP answers 404 for that exactly as it does
 * for one closed day.
 */
export async function fetchNbpRange(
  from: string,
  to: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Map<string, Map<Currency, number>>> {
  const out = new Map<string, Map<Currency, number>>();

  for (const [start, end] of rangeWindows(from, to)) {
    const url =
      `https://api.nbp.pl/api/exchangerates/tables/A/${start}/${end}/?format=json`;
    const response = await fetchImpl(url, { headers: { accept: "application/json" } });
    if (response.status === 404) continue;
    if (!response.ok) throw new Error(`nbp_http_${response.status}`);

    const body = (await response.json()) as unknown;
    if (!Array.isArray(body)) throw new Error("nbp_malformed");

    for (const table of body) {
      const row = table as Record<string, unknown>;
      const date = row["effectiveDate"];
      const rates = row["rates"];
      if (typeof date !== "string" || !Array.isArray(rates)) throw new Error("nbp_malformed");

      const parsed = new Map<Currency, number>();
      for (const entry of rates) {
        const r = entry as Record<string, unknown>;
        const code = r["code"];
        const mid = r["mid"];
        if (!isCurrency(code) || typeof mid !== "number" || !Number.isFinite(mid) || mid <= 0) continue;
        parsed.set(code, Math.round(mid * RATE_SCALE));
      }
      // A table that parsed but carries none of our currencies means the shape
      // changed under us. Better to fail the refresh and keep the rates we have
      // than to write a day that conversion will find empty.
      if (parsed.size === 0) throw new Error("nbp_no_known_currencies");
      out.set(date, parsed);
    }
  }
  return out;
}

/** [from, to] split into windows NBP will actually answer. */
export function rangeWindows(from: string, to: string): [string, string][] {
  const windows: [string, string][] = [];
  const endMs = Date.parse(`${to}T00:00:00Z`);
  let startMs = Date.parse(`${from}T00:00:00Z`);
  while (startMs <= endMs) {
    const windowEnd = Math.min(startMs + (NBP_RANGE_DAYS - 1) * 86_400_000, endMs);
    windows.push([
      new Date(startMs).toISOString().slice(0, 10),
      new Date(windowEnd).toISOString().slice(0, 10),
    ]);
    startMs = windowEnd + 86_400_000;
  }
  return windows;
}

/** Every date from [from] to [to] inclusive, as YYYY-MM-DD. */
export function datesBetween(from: string, to: string): string[] {
  const out: string[] = [];
  const end = Date.parse(`${to}T00:00:00Z`);
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= end; t += 86_400_000) {
    out.push(new Date(t).toISOString().slice(0, 10));
  }
  return out;
}

/** How far back to look for a rate to carry into the first requested date. */
const SEED_DAYS = 10;

/**
 * Store a rate for every date from [from] to [to], carrying publications across
 * closed days.
 *
 * Idempotent: re-running for dates already stored replaces them with the same
 * values, which matters because the refresh re-asks for dates it holds whenever
 * a run is retried.
 *
 * The fetch window reaches [SEED_DAYS] before [from] so that a range beginning
 * on a Sunday still has Friday's publication to carry in. Ten days covers a
 * weekend with public holidays either side of it; without the seed, asking for
 * "just today" on a Sunday wrote nothing at all.
 */
export async function refreshRates(
  db: SqlDatabase,
  from: string,
  to: string,
  nowMs: number,
  fetchImpl: typeof fetch = fetch,
): Promise<{ written: number; carried: number; missing: string[] }> {
  const seedFrom = new Date(Date.parse(`${from}T00:00:00Z`) - SEED_DAYS * 86_400_000)
    .toISOString()
    .slice(0, 10);
  const published = await fetchNbpRange(seedFrom, to, fetchImpl);

  // The seed: the newest publication strictly before [from], if there is one.
  let carrying: Map<Currency, number> | null = null;
  let carryingFrom = "";
  for (const date of datesBetween(seedFrom, from).slice(0, -1)) {
    const table = published.get(date);
    if (table) {
      carrying = table;
      carryingFrom = date;
    }
  }

  const statements: SqlPreparedStatement[] = [];
  let written = 0;
  let carried = 0;
  const missing: string[] = [];

  for (const date of datesBetween(from, to)) {
    const table = published.get(date);
    if (table) {
      carrying = table;
      carryingFrom = date;
    } else if (!carrying) {
      // Nothing published on or before this date within reach. Guessing a rate
      // would be inventing one.
      missing.push(date);
      continue;
    } else {
      carried += 1;
    }

    for (const [currency, rateMicro] of carrying!) {
      statements.push(
        db
          .prepare(
            `INSERT INTO fx_rates (currency, effective_on, published_on, rate_micro, fetched_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(currency, effective_on) DO UPDATE SET
               published_on = excluded.published_on,
               rate_micro   = excluded.rate_micro,
               fetched_at   = excluded.fetched_at`,
          )
          .bind(currency, date, carryingFrom, rateMicro, nowMs),
      );
      written += 1;
    }
  }

  if (statements.length > 0) await db.batch(statements);
  return { written, carried, missing };
}

/**
 * Every rate stored on or after [since], for the phone to keep its own copy.
 *
 * The phone computes every figure locally — there is no endpoint that returns a
 * total — so conversion happens in Room, which means the rates have to be ON
 * the device. This is what it syncs.
 */
export async function ratesSince(
  db: SqlDatabase,
  since: string,
): Promise<Rate[]> {
  const { results } = await db
    .prepare(
      `SELECT currency, effective_on, published_on, rate_micro
         FROM fx_rates
        WHERE effective_on >= ?1
        ORDER BY effective_on, currency`,
    )
    .bind(since)
    .all<Rate>();
  return results;
}

/**
 * The rate for one currency on one date, or null when none is stored.
 *
 * PLN answers 1.000000 without a lookup: it is the unit, it is never in the
 * table, and an account in złoty must not depend on a rate fetch having
 * succeeded.
 */
export async function rateFor(
  db: SqlDatabase,
  currency: Currency,
  date: string,
): Promise<Rate | null> {
  if (currency === BASE) {
    return { currency: BASE, effective_on: date, published_on: date, rate_micro: RATE_SCALE };
  }
  const { results } = await db
    .prepare(
      `SELECT currency, effective_on, published_on, rate_micro
         FROM fx_rates
        WHERE currency = ?1 AND effective_on <= ?2
        ORDER BY effective_on DESC
        LIMIT 1`,
    )
    .bind(currency, date)
    .all<Rate>();
  return results[0] ?? null;
}

/**
 * The most recent date any rate is stored for, or null when the table is empty.
 *
 * What the daily refresh starts from, so a function that has been down for a
 * week backfills the week rather than only today — a gap in the middle of the
 * table would make every transaction in it convert at the wrong day's rate.
 */
export async function latestStoredDate(db: SqlDatabase): Promise<string | null> {
  const { results } = await db
    .prepare(`SELECT MAX(effective_on) AS latest FROM fx_rates`)
    .all<{ latest: string | null }>();
  return results[0]?.latest ?? null;
}

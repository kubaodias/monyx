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

export function isCurrency(value: unknown): value is Currency {
  return typeof value === "string" && CURRENCY_SET.has(value);
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
  const url = `https://api.nbp.pl/api/exchangerates/tables/A/${date}/?format=json`;
  const response = await fetchImpl(url, { headers: { accept: "application/json" } });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`nbp_http_${response.status}`);

  const body = (await response.json()) as unknown;
  if (!Array.isArray(body) || body.length === 0) throw new Error("nbp_empty");
  const rates = (body[0] as Record<string, unknown>)["rates"];
  if (!Array.isArray(rates)) throw new Error("nbp_malformed");

  const out = new Map<Currency, number>();
  for (const entry of rates) {
    const row = entry as Record<string, unknown>;
    const code = row["code"];
    const mid = row["mid"];
    if (!isCurrency(code) || typeof mid !== "number" || !Number.isFinite(mid) || mid <= 0) continue;
    out.set(code, Math.round(mid * RATE_SCALE));
  }
  // A table that parsed but carries none of our currencies means the shape
  // changed under us. Better to fail the refresh and keep yesterday's rates
  // than to write an empty day and have conversion silently find nothing.
  if (out.size === 0) throw new Error("nbp_no_known_currencies");
  return out;
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

/**
 * Write the rates for [dates], carrying the last publication across gaps.
 *
 * Idempotent: re-running for a date that already has rows replaces them with
 * the same values. That matters because the daily cron will re-ask for dates it
 * has already stored whenever a run is retried.
 *
 * The carry-forward is the whole point of storing published_on. A Sunday gets
 * Friday's numbers with Friday's published_on, so a conversion on a Sunday is a
 * single indexed lookup that returns a row, rather than a query that has to
 * walk backwards looking for one — and the row itself says which day's rate it
 * actually is, which is what anyone reconciling against a statement needs.
 */
export async function refreshRates(
  db: SqlDatabase,
  dates: string[],
  nowMs: number,
  fetchImpl: typeof fetch = fetch,
): Promise<{ written: number; carried: number; missing: string[] }> {
  let carrying: Map<Currency, number> | null = null;
  let carryingFrom = "";
  const statements: SqlPreparedStatement[] = [];
  let written = 0;
  let carried = 0;
  const missing: string[] = [];

  for (const date of dates) {
    const table = await fetchNbpTable(date, fetchImpl);
    if (table) {
      carrying = table;
      carryingFrom = date;
    } else if (!carrying) {
      // No table for this date and nothing earlier to carry: the range starts
      // on a weekend. The caller widens the range rather than guessing.
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

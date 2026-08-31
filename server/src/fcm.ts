// FCM HTTP v1 delivery.
//
// The legacy server key was shut down in June 2024, so this goes through v1,
// which requires an OAuth2 token: assemble a JWT, sign it RS256 with
// node:crypto, exchange it, cache it. No external library.
import { env } from "@telnyx/edge-runtime";
import { createSign } from "node:crypto";
import type { PendingAlert } from "./budgets.ts";

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

interface CachedToken {
  access_token: string;
  /** Checked in code — expirationTtl alone trusts a runtime version to honour it. */
  expires_at: number;
}

/** KV keys may not contain a colon. */
const TOKEN_KEY = "fcm/token";
const TOKEN_TTL_SECONDS = 55 * 60; // the token lives 60 minutes
const SKEW_MS = 60_000;

/**
 * Module state persists per container, so the token is cached in memory as well
 * and the KV hop only happens on a cold start. Containers are reclaimed
 * without notice — usable as a cache, never as storage.
 */
let memoryToken: CachedToken | null = null;

let serviceAccountCache: ServiceAccount | null = null;

/**
 * The entire service-account JSON sits as one secret and is parsed here — a
 * bare PEM in an environment variable loses its newlines.
 */
async function serviceAccount(): Promise<ServiceAccount> {
  if (serviceAccountCache) return serviceAccountCache;
  const raw = await env.SECRETS.get("FCM_SERVICE_ACCOUNT");
  const parsed = JSON.parse(raw) as ServiceAccount;
  if (!parsed.project_id || !parsed.client_email || !parsed.private_key) {
    throw new Error("FCM_SERVICE_ACCOUNT is missing project_id, client_email or private_key");
  }
  serviceAccountCache = parsed;
  return parsed;
}

function base64url(input: Buffer | string): string {
  return Buffer.from(input)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/** Assemble and RS256-sign the assertion, then exchange it for an access token. */
async function mintToken(sa: ServiceAccount): Promise<CachedToken> {
  const now = Math.floor(Date.now() / 1000);
  const tokenUri = sa.token_uri ?? "https://oauth2.googleapis.com/token";

  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: tokenUri,
      iat: now,
      exp: now + 3600,
    }),
  );

  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  signer.end();
  const signature = base64url(signer.sign(sa.private_key));
  const assertion = `${header}.${claims}.${signature}`;

  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!response.ok) {
    throw new Error(`FCM token exchange failed: ${response.status} ${await response.text()}`);
  }

  const body = (await response.json()) as { access_token: string; expires_in: number };
  return {
    access_token: body.access_token,
    expires_at: Date.now() + TOKEN_TTL_SECONDS * 1000,
  };
}

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Date.now();
  if (memoryToken && memoryToken.expires_at > now + SKEW_MS) {
    return memoryToken.access_token;
  }

  const cached = await env.KV.get(TOKEN_KEY);
  if (cached) {
    try {
      const parsed = JSON.parse(cached) as CachedToken;
      if (parsed.expires_at > now + SKEW_MS) {
        memoryToken = parsed;
        return parsed.access_token;
      }
    } catch {
      // Unparseable cache entry: fall through and mint a fresh one.
    }
  }

  const minted = await mintToken(sa);
  memoryToken = minted;
  await env.KV.put(TOKEN_KEY, JSON.stringify(minted), { expirationTtl: TOKEN_TTL_SECONDS });
  return minted.access_token;
}

/**
 * Polish numerals, formatted server-side.
 *
 * body_loc_args is an array of STRINGS — JSON numbers are rejected as
 * INVALID_ARGUMENT. Arguments are substituted literally, so NumberFormat never
 * runs on them on the device. The no-hardcoded-strings rule still stands,
 * because the wording comes from strings.xml and a numeral is not wording.
 */
const PLN = new Intl.NumberFormat("pl-PL", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatMinor(minor: number): string {
  return PLN.format(minor / 100);
}

export interface DeviceToken {
  device_id: string;
  fcm_token: string;
}

export interface DeliveryOutcome {
  /** Device rows whose token FCM reported as gone; the caller nulls them. */
  unregistered: string[];
  delivered: number;
}

/**
 * Send one alert to every phone in the household.
 *
 * The budget is shared, so the information is shared; routing it to whoever
 * happened to enter the expense would tell the wrong person.
 */
export async function sendBudgetAlert(
  alert: PendingAlert,
  devices: DeviceToken[],
): Promise<DeliveryOutcome> {
  if (devices.length === 0) return { unregistered: [], delivered: 0 };

  const sa = await serviceAccount();
  const token = await accessToken(sa);
  const url = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;

  // Every placeholder is %1$s, %2$s, … never %d: getString() handed a String
  // for a %d specifier throws — a crash, not a rendering glitch.
  const args = [
    alert.category_name,
    formatMinor(alert.spent_minor),
    formatMinor(alert.limit_minor),
    String(alert.pct),
  ];

  // The data block drives the deep link. With a notification block present,
  // onMessageReceived is not called while the app is backgrounded — the data
  // arrives as extras on the launching Activity's intent.
  const data = {
    type: "budget_alert",
    category_id: alert.category_id,
    period: alert.period,
    threshold: String(alert.threshold),
  };

  const results = await Promise.allSettled(
    devices.map(async (device) => {
      const response = await fetch(url, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: device.fcm_token,
            // The notification block is what makes the system tray display it
            // reliably: data-only messages are throttled in Doze, and a
            // force-stopped app receives nothing at all.
            // A visible title is mandatory for a notification message, and this
            // one is only ever a fallback: the localised title comes from
            // title_loc_key and strings.xml on the device.
            notification: { title: "Monyx", body: "" },
            data,
            android: {
              priority: "high",
              notification: {
                title_loc_key: "budget_alert_title",
                body_loc_key: "budget_alert",
                body_loc_args: args,
                channel_id: "budget_alerts",
                click_action: "MONYX_BUDGET_ALERT",
              },
            },
          },
        }),
      });

      if (response.ok) return { device, status: 200 };
      const text = await response.text();
      return { device, status: response.status, text };
    }),
  );

  const unregistered: string[] = [];
  let delivered = 0;

  for (const result of results) {
    if (result.status === "rejected") {
      console.error("fcm send failed", result.reason);
      continue;
    }
    const value = result.value;
    if (value.status === 200) {
      delivered += 1;
      continue;
    }
    // A 404 UNREGISTERED means the app was uninstalled or its data cleared.
    // Null that row's token — without it, every future alert retries a phone
    // that no longer exists, forever.
    if (value.status === 404) {
      unregistered.push(value.device.device_id);
      continue;
    }
    // 400, 403, 429 and 503 are logged and left alone.
    console.error(`fcm ${value.status}`, value.text);
  }

  return { unregistered, delivered };
}

// Enrolment, invite codes, and turning a bearer token into a household.
import { randomBytes, randomUUID } from "node:crypto";
import type { SqlDatabase } from "@telnyx/edge-runtime";
import { forHousehold, resolveInvite, resolveSession, type SessionRow } from "./db.ts";

/**
 * Invite codes are 10 random characters (~50 bits), not 4.
 *
 * A four-character code is about a million possibilities, and enumerating that
 * at a modest request rate takes hours for a prize of the family's complete
 * financial history plus write access. Length is the defence; the declarative
 * rate limiter on /auth/enroll is a second layer.
 *
 * The alphabet omits I, O, 0 and 1 — these get read aloud and typed by hand.
 */
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 10;
const INVITE_TTL_MS = 24 * 60 * 60 * 1000;

export function generateInviteCode(): string {
  const bytes = randomBytes(CODE_LENGTH);
  let out = "";
  for (let i = 0; i < CODE_LENGTH; i += 1) {
    out += CODE_ALPHABET[bytes[i]! % CODE_ALPHABET.length];
  }
  return out;
}

function generateSessionToken(): string {
  return randomBytes(32).toString("base64url");
}

export interface EnrollInput {
  invite_code: unknown;
  member_name: unknown;
  device_label: unknown;
}

export interface EnrollSuccess {
  session_token: string;
  household_id: string;
  member_id: string;
  epoch: number;
  seq: number;
}

export type EnrollResult =
  | { ok: true; value: EnrollSuccess }
  | { ok: false; status: number; code: string };

/**
 * Consume an invite and enrol a device.
 *
 * The new member is a synced row, so it draws a seq like any other write —
 * otherwise the other phones would never learn the member exists and every
 * transaction they pull would reference an unknown author.
 */
export async function enroll(
  input: EnrollInput,
  nowMs: number,
  db?: SqlDatabase,
): Promise<EnrollResult> {
  const code = typeof input.invite_code === "string" ? input.invite_code.trim().toUpperCase() : "";
  const memberName = typeof input.member_name === "string" ? input.member_name.trim() : "";
  const deviceLabel =
    typeof input.device_label === "string" && input.device_label.trim().length > 0
      ? input.device_label.trim()
      : null;

  if (code.length === 0) return { ok: false, status: 400, code: "invite_code_required" };
  if (memberName.length === 0) return { ok: false, status: 400, code: "member_name_required" };

  const invite = await resolveInvite(code, db);
  if (!invite) return { ok: false, status: 404, code: "invite_not_found" };
  // Single-use, and expiring after 24 h.
  if (invite.used_at !== null) return { ok: false, status: 409, code: "invite_already_used" };
  if (invite.expires_at < nowMs) return { ok: false, status: 410, code: "invite_expired" };

  const hh = forHousehold(invite.household_id, db);
  const memberId = randomUUID();
  const deviceId = randomUUID();
  const sessionToken = generateSessionToken();

  // One batch: reserve a seq, write the member, write the device, burn the
  // invite. The invite update is conditional on used_at IS NULL, so two devices
  // racing the same code cannot both enrol.
  const results = await hh.batch<{ next_seq: number }>([
    hh
      .prepare("UPDATE households SET next_seq = next_seq + ? WHERE id = ? RETURNING next_seq")
      .bind(1, invite.household_id),
    hh
      .prepare(
        `INSERT INTO members (id, household_id, name, created_at, seq, deleted)
         VALUES (?, ?, ?, ?, (SELECT next_seq FROM households WHERE id = ?) - ? + ?, 0)`,
      )
      .bind(memberId, invite.household_id, memberName, nowMs, invite.household_id, 1, 1),
    hh
      .prepare(
        `INSERT INTO devices (id, member_id, session_token, fcm_token, label, last_seen_at, created_at)
         VALUES (?, ?, ?, NULL, ?, ?, ?)`,
      )
      .bind(deviceId, memberId, sessionToken, deviceLabel, nowMs, nowMs),
    hh
      .prepare("UPDATE invites SET used_at = ? WHERE code = ? AND used_at IS NULL")
      .bind(nowMs, code),
  ]);

  const epochRow = await hh
    .prepare("SELECT epoch FROM households WHERE id = ?")
    .bind(invite.household_id)
    .first<{ epoch: number }>();

  void results;

  return {
    ok: true,
    value: {
      session_token: sessionToken,
      household_id: invite.household_id,
      member_id: memberId,
      epoch: epochRow?.epoch ?? 1,
      // A fresh device starts at zero and pulls everything.
      seq: 0,
    },
  };
}

/**
 * Mint an invite. Any enrolled device can do this — the alternative
 * strands people: a phone dies, someone reinstalls, someone clears app data,
 * and rejoining would wait on the author being at a laptop.
 */
export async function createInvite(
  householdId: string,
  nowMs: number,
  db?: SqlDatabase,
): Promise<{ code: string; expires_at: number }> {
  const hh = forHousehold(householdId, db);
  const code = generateInviteCode();
  const expiresAt = nowMs + INVITE_TTL_MS;
  await hh
    .prepare("INSERT INTO invites (code, household_id, expires_at, used_at) VALUES (?, ?, ?, NULL)")
    .bind(code, householdId, expiresAt)
    .run();
  return { code, expires_at: expiresAt };
}

/**
 * The session token does not expire — this is a family member's phone, not a
 * bank. Revocation is deleting the row in devices.
 */
export async function authenticate(
  authorization: string | null,
  db?: SqlDatabase,
): Promise<SessionRow | null> {
  if (!authorization) return null;
  const match = /^Bearer\s+(.+)$/i.exec(authorization.trim());
  if (!match) return null;
  return resolveSession(match[1]!, db);
}

/**
 * The FCM token rides along on /sync/push rather than a dedicated endpoint: a
 * fire-and-forget call at app start fails whenever the phone happens to be
 * offline at launch, with no retry and no state to retry from.
 */
export async function touchDevice(
  householdId: string,
  deviceId: string,
  fcmToken: string | null,
  nowMs: number,
  db?: SqlDatabase,
): Promise<void> {
  const hh = forHousehold(householdId, db);
  if (fcmToken) {
    await hh
      .prepare("UPDATE devices SET fcm_token = ?, last_seen_at = ? WHERE id = ?")
      .bind(fcmToken, nowMs, deviceId)
      .run();
  } else {
    await hh
      .prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?")
      .bind(nowMs, deviceId)
      .run();
  }
}

// Enrolment, invites and session resolution.
//
// This is the only path that runs before a household is known, so it is also
// the only path where the isolation boundary could be got wrong.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb, seedHousehold } from "./fake-db.ts";
import { authenticate, createInvite, enroll, generateInviteCode, touchDevice } from "../src/auth.ts";
import { forHousehold } from "../src/db.ts";
import { pull } from "../src/sync.ts";

const NOW = 1_756_000_000_000;
const DAY = 24 * 60 * 60 * 1000;

function setup() {
  const fake = new FakeDb();
  seedHousehold(fake);
  return fake;
}

function addInvite(fake: FakeDb, code: string, expiresAt: number, usedAt: number | null = null) {
  fake.db.exec(
    `INSERT INTO invites (code, household_id, expires_at, used_at)
     VALUES ('${code}', 'hh1', ${expiresAt}, ${usedAt === null ? "NULL" : usedAt})`,
  );
}

test("an invite code is 10 characters of unambiguous alphabet", () => {
  // 10 random characters is ~50 bits. A four-character code is about a million
  // possibilities — hours of enumeration for the family's complete financial
  // history plus write access.
  for (let i = 0; i < 200; i += 1) {
    const code = generateInviteCode();
    assert.equal(code.length, 10);
    assert.match(code, /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/);
    // I, O, 0 and 1 are excluded: these get read aloud and typed by hand.
    assert.doesNotMatch(code, /[IO01]/);
  }
});

test("invite codes do not repeat in a realistic sample", () => {
  const seen = new Set<string>();
  for (let i = 0; i < 500; i += 1) seen.add(generateInviteCode());
  assert.equal(seen.size, 500);
});

test("enrolment consumes an invite and returns a session", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);

  const result = await enroll(
    { invite_code: "ABCDEFGHJK", member_name: "Kuba", device_label: "Pixel 8" },
    NOW,
    fake,
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;

  assert.equal(result.value.household_id, "hh1");
  assert.equal(result.value.epoch, 1);
  // A fresh device starts at zero and pulls everything.
  assert.equal(result.value.seq, 0);
  assert.ok(result.value.session_token.length >= 40);
});

test("the new member is a synced row, so the other phones learn it exists", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  await enroll({ invite_code: "ABCDEFGHJK", member_name: "Ala", device_label: null }, NOW, fake);

  const db = forHousehold("hh1", fake);
  const page = await pull(db, 0);
  const member = page.changes.find(
    (c) => c.table === "members" && c.row["name"] === "Ala",
  );
  assert.ok(member, "a member that never syncs leaves every transaction with an unknown author");
  assert.ok(Number(member!.row["seq"]) > 0, "the member row must draw a real seq");
});

test("an invite is single-use", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);

  const first = await enroll({ invite_code: "ABCDEFGHJK", member_name: "A", device_label: null }, NOW, fake);
  assert.equal(first.ok, true);

  const second = await enroll({ invite_code: "ABCDEFGHJK", member_name: "B", device_label: null }, NOW, fake);
  assert.equal(second.ok, false);
  if (second.ok) return;
  assert.equal(second.status, 409);
  assert.equal(second.code, "invite_already_used");
});

test("an invite expires after 24 hours", async () => {
  const fake = setup();
  addInvite(fake, "EXPIREDCDE", NOW - 1);
  const result = await enroll({ invite_code: "EXPIREDCDE", member_name: "A", device_label: null }, NOW, fake);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.equal(result.code, "invite_expired");
});

test("an unknown code is refused", async () => {
  const fake = setup();
  const result = await enroll({ invite_code: "NOSUCHCODE", member_name: "A", device_label: null }, NOW, fake);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.equal(result.status, 404);
});

test("a code is matched case-insensitively and trimmed, because people type it by hand", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  const result = await enroll(
    { invite_code: "  abcdefghjk  ", member_name: "A", device_label: null },
    NOW,
    fake,
  );
  assert.equal(result.ok, true);
});

test("a missing name is refused before the invite is burned", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  const result = await enroll({ invite_code: "ABCDEFGHJK", member_name: "  ", device_label: null }, NOW, fake);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.equal(result.code, "member_name_required");

  // The invite must still be usable — a typo in a name may not strand someone.
  const retry = await enroll({ invite_code: "ABCDEFGHJK", member_name: "Kuba", device_label: null }, NOW, fake);
  assert.equal(retry.ok, true);
});

test("a session token resolves to its household, and a wrong one resolves to nothing", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  const result = await enroll({ invite_code: "ABCDEFGHJK", member_name: "Kuba", device_label: null }, NOW, fake);
  assert.equal(result.ok, true);
  if (!result.ok) return;

  const session = await authenticate(`Bearer ${result.value.session_token}`, fake);
  assert.equal(session?.household_id, "hh1");
  assert.equal(session?.member_id, result.value.member_id);

  assert.equal(await authenticate("Bearer not-a-real-token", fake), null);
  assert.equal(await authenticate("not-even-a-bearer-header", fake), null);
  assert.equal(await authenticate(null, fake), null);
});

test("any enrolled device can mint an invite", async () => {
  const fake = setup();
  const invite = await createInvite("hh1", NOW, fake);
  assert.equal(invite.code.length, 10);
  assert.equal(invite.expires_at, NOW + DAY);

  // And that invite works — this is what stops a dead phone stranding someone
  // until the author is next at a laptop.
  const result = await enroll(
    { invite_code: invite.code, member_name: "Nowy", device_label: null },
    NOW,
    fake,
  );
  assert.equal(result.ok, true);
});

test("the FCM token rides along on push rather than its own endpoint", async () => {
  const fake = setup();
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  const result = await enroll({ invite_code: "ABCDEFGHJK", member_name: "Kuba", device_label: null }, NOW, fake);
  assert.equal(result.ok, true);
  if (!result.ok) return;

  const session = await authenticate(`Bearer ${result.value.session_token}`, fake);
  await touchDevice("hh1", session!.device_id, "fcm-token-abc", NOW, fake);

  const row = fake.db
    .prepare("SELECT fcm_token, last_seen_at FROM devices WHERE id = ?")
    .get(session!.device_id) as { fcm_token: string; last_seen_at: number };
  assert.equal(row.fcm_token, "fcm-token-abc");
  assert.equal(row.last_seen_at, NOW);

  // A push with no token must not wipe the one already stored.
  await touchDevice("hh1", session!.device_id, null, NOW + 1000, fake);
  const after = fake.db
    .prepare("SELECT fcm_token FROM devices WHERE id = ?")
    .get(session!.device_id) as { fcm_token: string };
  assert.equal(after.fcm_token, "fcm-token-abc");
});

test("two households never see each other's rows", async () => {
  const fake = setup();
  seedHousehold(fake, "hh2");
  addInvite(fake, "ABCDEFGHJK", NOW + DAY);
  const result = await enroll({ invite_code: "ABCDEFGHJK", member_name: "Kuba", device_label: null }, NOW, fake);
  assert.equal(result.ok, true);
  if (!result.ok) return;

  const session = await authenticate(`Bearer ${result.value.session_token}`, fake);
  const page = await pull(forHousehold(session!.household_id, fake), 0);

  // Every query filters by the household_id from the token, never by a value
  // from the request.
  const foreign = page.changes.filter((c) => String(c.row["household_id"]) !== "hh1");
  assert.deepEqual(foreign, []);
});

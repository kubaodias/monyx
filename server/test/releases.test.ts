// The update check. What matters is that a phone is offered exactly the newer
// releases, that the notes of everything it skipped come with them, and that
// every way minting a URL can go wrong arrives as null rather than as a URL the
// phone would try to download.
import { test } from "node:test";
import assert from "node:assert/strict";
import { FakeDb } from "./fake-db.ts";
import { releaseByCode, releasesAfter, type ReleaseRow } from "../src/db.ts";
import {
  describeUpdate,
  parseVersionCode,
  presign,
  RELEASES_BUCKET,
  URL_TTL_SECONDS,
  type Presigner,
} from "../src/releases.ts";

const SHA = "a".repeat(64);

function insert(fake: FakeDb, code: number, notes = `notes ${code}`) {
  fake.db
    .prepare(
      "INSERT INTO app_releases (version_code, version_name, object_key, sha256, size_bytes, notes, published_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .run(code, `1.0 (${code})`, `apk/monyx-${code}.apk`, SHA, 3_100_000, notes, 1_788_000_000_000 + code);
}

function presigner(respond: () => unknown, seen?: (key: string, params: unknown) => void): Presigner {
  return {
    storage: {
      buckets: {
        async createPresignedURL(key, params) {
          seen?.(key, params);
          return respond();
        },
      },
    },
  };
}

test("version_code must be a plain non-negative integer", () => {
  assert.equal(parseVersionCode("120"), 120);
  assert.equal(parseVersionCode("0"), 0);
  for (const bad of [null, "", "-1", "1.5", "12a", "1e3", "9999999999"]) {
    assert.equal(parseVersionCode(bad), null, String(bad));
  }
});

test("only newer releases come back, newest first", async () => {
  const fake = new FakeDb();
  for (const code of [100, 110, 120]) insert(fake, code);

  assert.deepEqual((await releasesAfter(105, fake)).map((r) => r.version_code), [120, 110]);
  assert.deepEqual(await releasesAfter(120, fake), []);
  assert.equal((await releaseByCode(110, fake))?.object_key, "apk/monyx-110.apk");
  assert.equal(await releaseByCode(111, fake), null);
});

test("the schema refuses a release the phone could never verify", () => {
  const fake = new FakeDb();
  assert.throws(() =>
    fake.db
      .prepare("INSERT INTO app_releases (version_code, version_name, object_key, sha256, size_bytes, published_at) VALUES (1, 'x', 'k', 'short', 10, 0)")
      .run(),
  );
  assert.throws(() =>
    fake.db
      .prepare("INSERT INTO app_releases (version_code, version_name, object_key, sha256, size_bytes, published_at) VALUES (1, 'x', 'k', ?, 0, 0)")
      .run(SHA),
  );
});

test("an update describes the newest release and carries every skipped version's notes", () => {
  const rows: ReleaseRow[] = [110, 130, 120].map((code) => ({
    version_code: code,
    version_name: `1.0 (${code})`,
    object_key: `apk/monyx-${code}.apk`,
    sha256: SHA,
    size_bytes: 3_000_000 + code,
    notes: `  notes ${code}\n`,
    published_at: code,
  }));
  const update = describeUpdate(rows);
  assert.equal(update?.version_code, 130);
  assert.equal(update?.size_bytes, 3_000_130);
  assert.deepEqual(update?.notes.map((n) => n.version_code), [130, 120, 110]);
  assert.equal(update?.notes[0]?.notes, "notes 130");
  assert.equal(describeUpdate([]), null);
});

test("the object key never reaches the phone", () => {
  const update = describeUpdate([
    { version_code: 1, version_name: "1", object_key: "apk/secret.apk", sha256: SHA, size_bytes: 1, notes: "", published_at: 0 },
  ]);
  assert.ok(!JSON.stringify(update).includes("apk/secret.apk"));
});

test("presign asks for the right object, bucket and TTL, and reads the live response shape", async () => {
  let seenKey = "";
  let seenParams: unknown;
  const out = await presign(
    "apk/monyx-120.apk",
    presigner(
      () => ({ data: { presigned_url: "https://us-central-1.telnyxcloudstorage.com/x", expires_at: "2026-09-16T10:05:00Z" } }),
      (k, p) => {
        seenKey = k;
        seenParams = p;
      },
    ),
    0,
  );
  assert.equal(seenKey, "apk/monyx-120.apk");
  assert.deepEqual(seenParams, { bucketName: RELEASES_BUCKET, body: { ttl: URL_TTL_SECONDS } });
  assert.deepEqual(out, { url: "https://us-central-1.telnyxcloudstorage.com/x", expires_at: Date.parse("2026-09-16T10:05:00Z") });
});

test("the SDK's documented shape is read too, and a missing expiry falls back to the TTL", async () => {
  const out = await presign("k", presigner(() => ({ content: { presigned_url: "https://h/x" } })), 1_000);
  assert.deepEqual(out, { url: "https://h/x", expires_at: 1_000 + URL_TTL_SECONDS * 1000 });
});

test("every failure to mint a URL is null, never a URL", async () => {
  assert.equal(await presign("k", null, 0), null);
  assert.equal(await presign("k", presigner(() => { throw new Error("403"); }), 0), null);
  assert.equal(await presign("k", presigner(() => ({})), 0), null);
  assert.equal(await presign("k", presigner(() => ({ data: { presigned_url: "http://plain/x" } })), 0), null);
  assert.equal(await presign("k", presigner(() => ({ data: { presigned_url: 42 } })), 0), null);
});

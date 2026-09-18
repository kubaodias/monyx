#!/usr/bin/env node
// Publish an Android release that installed phones will offer to update to.
//
// Usage:
//   node scripts/release.mjs --bump minor --note "Pierwsza zmiana" --note "Druga zmiana"
//   node scripts/release.mjs --bump patch --notes-file notes.txt [--skip-build] [--dry-run] [--db monyx]
//   node scripts/release.mjs --version 2.0.0 --notes-file notes.txt
//
// The version is semver. --bump takes it from the newest published release;
// --version names it outright. Each --note, or each line of --notes-file, is
// one bullet on the phone.
//
// Order is the whole design, and every step refuses rather than guesses:
//
//   1. the tracked tree is clean — the commit is tagged v<version>, so an
//      uncommitted change would ship code that no tag describes
//   2. the version is newer than every published release and not yet tagged
//   3. assembleRelease with that version, then read versionCode/versionName
//      back from the build's own output-metadata.json and compare
//   4. the APK is signed with THE release certificate; anything else would be
//      offered to every phone and refused by every installer
//   5. upload through a presigned PUT, then download it again through a fresh
//      presigned GET and compare sha256 — the bytes in the bucket, not the bytes
//      that were sent
//   6. only then INSERT the row. Until the row exists no phone can see the
//      release, so a failure anywhere above leaves nothing half-published.
//   7. tag the commit, and delete every APK but this one and the one before it.
//      A failure here is reported, not fatal: the release is already out, and
//      the next release prunes whatever this one left.
//
// Withdrawing a release is deleting its row; the one before it is still in the
// bucket, so phones not yet updated are offered that instead. See
// docs/decisions/0020-the-app-updates-itself-and-asks-first.md.
//
// The API key is read from TELNYX_API_KEY or ~/.telnyx-edge/config.toml, and is
// never printed.
import { execFileSync } from "node:child_process";
import { createHash, createHmac } from "node:crypto";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  bumpVersion,
  formatVersion,
  noteItems,
  objectsToPrune,
  parseVersion,
  versionCode as codeOf,
} from "./release-version.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ANDROID = join(ROOT, "android");
const APK_DIR = join(ANDROID, "app", "build", "outputs", "apk", "release");

// Must match server/src/releases.ts RELEASES_BUCKET.
const BUCKET = "monyx-releases";
const REGION = "us-central-1";
const APPLICATION_ID = "com.monyx";
// The release certificate regenerated on 2026-09-01: CN=Monyx, OU=Family.
const RELEASE_CERT_SHA256 = "23fb74cc2db098f45fd3d30ab3700b3644cc3b6d2a8ea03b8384b8f9bba71285";

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const options = (name) => args.flatMap((arg, i) => (arg === name && i + 1 < args.length ? [args[i + 1]] : []));

const db = option("--db") ?? "monyx";
const dryRun = flag("--dry-run");

function die(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

function step(message) {
  console.log(`• ${message}`);
}

// ---- notes -----------------------------------------------------------------

const notesFile = option("--notes-file");
const items = [
  ...options("--note"),
  ...options("--notes"),
  ...(notesFile ? [readFileSync(notesFile, "utf8")] : []),
].flatMap(noteItems);
if (items.length === 0) die('release notes are required: --note "..." (once per bullet) or --notes-file path');
const notes = items.join("\n");

// ---- 1. clean tree ---------------------------------------------------------

function git(...argv) {
  return execFileSync("git", argv, { cwd: ROOT, encoding: "utf8" }).trim();
}

const dirty = git("status", "--porcelain", "--untracked-files=no");
if (dirty) die(`tracked files have uncommitted changes — commit first:\n${dirty}`);
const commit = git("rev-parse", "--short", "HEAD");

// ---- 2. the version --------------------------------------------------------

function sqlJson(command, params = []) {
  const argv = ["storage", "sqldb", "execute", db, "--remote", "--json", "--command", command];
  for (const p of params) {
    if (typeof p === "number") argv.push("--param-json", String(p));
    else argv.push("--param", p);
  }
  return JSON.parse(execFileSync("telnyx-edge", argv, { encoding: "utf8" })).results ?? [];
}

const latest = sqlJson("SELECT version_code, version_name FROM app_releases ORDER BY version_code DESC LIMIT 1")[0];

let version;
const explicit = option("--version");
const bump = option("--bump");
if (explicit && bump) die("--version and --bump are alternatives; pass one");
if (explicit) {
  version = parseVersion(explicit);
  if (!version) die(`--version must be MAJOR.MINOR.PATCH, got "${explicit}"`);
} else if (bump) {
  if (!latest) die("nothing is published yet, so there is nothing to bump from; pass --version");
  const base = parseVersion(latest.version_name);
  if (!base) die(`the newest release is named "${latest.version_name}", which is not a version to bump; pass --version`);
  try {
    version = bumpVersion(base, bump);
  } catch (error) {
    die(error.message);
  }
} else {
  die("say which version this is: --bump major|minor|patch, or --version X.Y.Z");
}

const versionName = formatVersion(version);
const versionCode = codeOf(version);
if (latest && versionCode <= latest.version_code) {
  die(`${versionName} (${versionCode}) is not newer than the published ${latest.version_name} (${latest.version_code}); no phone could install it`);
}
const tag = `v${versionName}`;
if (git("tag", "--list", tag)) die(`tag ${tag} already exists — that version has been released`);
step(`${versionName} (versionCode ${versionCode}), commit ${commit}; latest published is ${latest ? `${latest.version_name} (${latest.version_code})` : "none"}`);

// ---- 3. build --------------------------------------------------------------

// Gradle AND apksigner need it. On macOS the /usr/bin/java stub answers "Unable
// to locate a Java Runtime" rather than failing over to anything, so JAVA_HOME
// has to reach every child that runs Java, not just the build.
const javaHome = process.env.JAVA_HOME ?? join(homedir(), ".local/jdks/temurin-21.jdk/Contents/Home");
const javaEnv = { ...process.env, JAVA_HOME: javaHome, PATH: `${join(javaHome, "bin")}:${process.env.PATH}` };

if (!flag("--skip-build")) {
  step("assembleRelease");
  execFileSync("./gradlew", ["--quiet", `-PmonyxVersion=${versionName}`, ":app:assembleRelease"], {
    cwd: ANDROID,
    stdio: "inherit",
    env: javaEnv,
  });
}

const metaPath = join(APK_DIR, "output-metadata.json");
if (!existsSync(metaPath)) die(`no ${metaPath} — build first, or drop --skip-build`);
const meta = JSON.parse(readFileSync(metaPath, "utf8"));
const element = meta.elements?.[0];
if (meta.applicationId !== APPLICATION_ID) die(`applicationId is ${meta.applicationId}, expected ${APPLICATION_ID}`);
if (element?.versionCode !== versionCode || element?.versionName !== versionName) {
  die(`the built APK is ${element?.versionName} (${element?.versionCode}), not ${versionName} (${versionCode}) — drop --skip-build`);
}
const apkPath = join(APK_DIR, element.outputFile);
const apk = readFileSync(apkPath);
const sha256 = createHash("sha256").update(apk).digest("hex");
const objectKey = `monyx-${versionCode}.apk`;

step(`built ${(apk.length / 1024 / 1024).toFixed(2)} MiB`);

// ---- 4. signed with the release key ----------------------------------------

function apksigner() {
  const sdk = process.env.ANDROID_HOME ?? "/opt/homebrew/share/android-commandlinetools";
  const tools = join(sdk, "build-tools");
  const versions = existsSync(tools) ? readdirSync(tools).sort().reverse() : [];
  for (const v of versions) {
    const bin = join(tools, v, "apksigner");
    if (existsSync(bin)) return bin;
  }
  die(`apksigner not found under ${tools}`);
}

const certs = execFileSync(apksigner(), ["verify", "--print-certs", apkPath], { encoding: "utf8", env: javaEnv });
const digests = [...certs.matchAll(/certificate SHA-256 digest: ([0-9a-f]{64})/g)].map((m) => m[1]);
if (digests.length !== 1 || digests[0] !== RELEASE_CERT_SHA256) {
  die(`APK is not signed with the release certificate (got ${digests.join(", ") || "none"})`);
}
step("signed with the release certificate");

// ---- 5. upload and verify --------------------------------------------------

function apiKey() {
  if (process.env.TELNYX_API_KEY) return process.env.TELNYX_API_KEY;
  const config = join(homedir(), ".telnyx-edge", "config.toml");
  const match = existsSync(config) && readFileSync(config, "utf8").match(/^api_key\s*=\s*"([^"]+)"/m);
  if (!match) die("no API key: set TELNYX_API_KEY or log in with telnyx-edge");
  return match[1];
}

const KEY = apiKey();

/**
 * One request to the bucket's S3-compatible endpoint, for what a presigned URL
 * cannot do: listing and deleting. Telnyx takes the API key as the access key
 * and ignores the secret, so it signs with a placeholder.
 */
async function s3(method, key = "", query = "") {
  const host = `${REGION}.telnyxcloudstorage.com`;
  const path = `/${BUCKET}/${key.split("/").map(encodeURIComponent).join("/")}`;
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, "");
  const day = amzDate.slice(0, 8);
  const hash = (text) => createHash("sha256").update(text).digest("hex");
  const hmac = (secret, text) => createHmac("sha256", secret).update(text).digest();
  const payload = hash("");
  const signed = "host;x-amz-content-sha256;x-amz-date";
  const canonical = [method, path, query, `host:${host}\nx-amz-content-sha256:${payload}\nx-amz-date:${amzDate}\n`, signed, payload].join("\n");
  const scope = `${day}/${REGION}/s3/aws4_request`;
  const signingKey = ["s3", "aws4_request"].reduce(hmac, hmac(hmac("AWS4-", day), REGION));
  const signature = createHmac("sha256", signingKey)
    .update(["AWS4-HMAC-SHA256", amzDate, scope, hash(canonical)].join("\n"))
    .digest("hex");
  const response = await fetch(`https://${host}${path}${query ? `?${query}` : ""}`, {
    method,
    headers: {
      "x-amz-date": amzDate,
      "x-amz-content-sha256": payload,
      Authorization: `AWS4-HMAC-SHA256 Credential=${KEY}/${scope}, SignedHeaders=${signed}, Signature=${signature}`,
    },
  });
  if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status}`);
  return response.text();
}

async function bucketKeys() {
  const xml = await s3("GET", "", "list-type=2");
  if (/<IsTruncated>true</.test(xml)) throw new Error("bucket listing is truncated; prune by hand");
  return [...xml.matchAll(/<Key>([^<]+)<\/Key>/g)].map((m) => m[1]);
}

const bullets = items.map((item) => `  • ${item}`).join("\n");

if (dryRun) {
  const rows = [
    ...sqlJson("SELECT version_code, object_key FROM app_releases"),
    { version_code: versionCode, object_key: objectKey },
  ];
  const prune = objectsToPrune(rows, [...(await bucketKeys()), objectKey]);
  console.log(`\ndry run — would upload ${objectKey} (sha256 ${sha256}), tag ${tag}, publish with notes:\n\n${bullets}\n`);
  console.log(prune.length ? `and delete from the bucket: ${prune.join(", ")}` : "and delete nothing from the bucket");
  process.exit(0);
}

async function presigned(key) {
  const response = await fetch(
    `https://api.telnyx.com/v2/storage/buckets/${BUCKET}/${encodeURIComponent(key)}/presigned_url`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: 300 }),
    },
  );
  if (!response.ok) die(`presign failed: HTTP ${response.status}`);
  const body = await response.json();
  // Live responses use `data`; the SDK type says `content`. See releases.ts.
  const url = (body.data ?? body.content)?.presigned_url;
  if (typeof url !== "string") die("presign response had no presigned_url");
  return url;
}

step(`uploading ${objectKey}`);
const put = await fetch(await presigned(objectKey), {
  method: "PUT",
  headers: { "Content-Type": "application/vnd.android.package-archive" },
  body: apk,
});
if (!put.ok) die(`upload failed: HTTP ${put.status}`);

const get = await fetch(await presigned(objectKey));
if (!get.ok) die(`read-back failed: HTTP ${get.status}`);
const stored = Buffer.from(await get.arrayBuffer());
const storedSha = createHash("sha256").update(stored).digest("hex");
if (storedSha !== sha256 || stored.length !== apk.length) {
  die(`read-back mismatch: stored ${stored.length} bytes sha256 ${storedSha}, sent ${apk.length} bytes sha256 ${sha256}`);
}
step("read back from the bucket — bytes identical");

// ---- 6. publish ------------------------------------------------------------

sqlJson(
  "INSERT INTO app_releases (version_code, version_name, object_key, sha256, size_bytes, notes, published_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
  [versionCode, versionName, objectKey, sha256, apk.length, notes, Date.now()],
);
const row = sqlJson("SELECT sha256, size_bytes FROM app_releases WHERE version_code = ?", [versionCode])[0];
if (row?.sha256 !== sha256 || row?.size_bytes !== apk.length) die("row written but did not read back as expected");

console.log(`\n✓ published ${versionName} (${versionCode}) — phones will offer it on their next launch:\n\n${bullets}\n`);

// ---- 7. tag and prune ------------------------------------------------------

try {
  git("tag", tag);
  step(`tagged ${tag} — push it with: git push origin ${tag}`);
} catch (error) {
  console.warn(`! could not tag ${tag}: ${error.message}`);
}

try {
  const rows = sqlJson("SELECT version_code, object_key FROM app_releases");
  const prune = objectsToPrune(rows, await bucketKeys());
  for (const key of prune) await s3("DELETE", key);
  step(prune.length ? `deleted from the bucket: ${prune.join(", ")}` : "nothing older to delete from the bucket");
} catch (error) {
  console.warn(`! pruning the bucket failed (${error.message}); the next release will prune it`);
}

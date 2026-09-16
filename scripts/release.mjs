#!/usr/bin/env node
// Publish an Android release that installed phones will offer to update to.
//
// Usage:
//   node scripts/release.mjs --notes "Po zapisaniu otwiera się lista transakcji."
//   node scripts/release.mjs --notes-file notes.txt [--skip-build] [--dry-run] [--db monyx]
//
// Order is the whole design, and every step refuses rather than guesses:
//
//   1. the tracked tree is clean — versionCode is the commit count, so an
//      uncommitted change would ship code that no commit describes
//   2. assembleRelease, then read versionCode/versionName from the build's own
//      output-metadata.json rather than recomputing them
//   3. the APK is signed with THE release certificate; anything else would be
//      offered to every phone and refused by every installer
//   4. no release with that versionCode exists yet
//   5. upload through a presigned PUT, then download it again through a fresh
//      presigned GET and compare sha256 — the bytes in the bucket, not the bytes
//      that were sent
//   6. only then INSERT the row. Until the row exists no phone can see the
//      release, so a failure anywhere above leaves nothing half-published.
//
// Withdrawing a release is deleting its row. See
// docs/decisions/0020-the-app-updates-itself-and-asks-first.md.
//
// The API key is read from TELNYX_API_KEY or ~/.telnyx-edge/config.toml, and is
// never printed.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const ANDROID = join(ROOT, "android");
const APK_DIR = join(ANDROID, "app", "build", "outputs", "apk", "release");

// Must match server/src/releases.ts RELEASES_BUCKET.
const BUCKET = "monyx-releases";
const APPLICATION_ID = "com.monyx";
// The release certificate regenerated on 2026-09-01: CN=Monyx, OU=Family.
const RELEASE_CERT_SHA256 = "23fb74cc2db098f45fd3d30ab3700b3644cc3b6d2a8ea03b8384b8f9bba71285";

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};

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

let notes = option("--notes");
const notesFile = option("--notes-file");
if (notesFile) notes = readFileSync(notesFile, "utf8");
notes = (notes ?? "").trim();
if (!notes) die('release notes are required: --notes "..." or --notes-file path');

// ---- 1. clean tree ---------------------------------------------------------

const dirty = execFileSync("git", ["status", "--porcelain", "--untracked-files=no"], {
  cwd: ROOT,
  encoding: "utf8",
}).trim();
if (dirty) die(`tracked files have uncommitted changes — commit first:\n${dirty}`);
const commit = execFileSync("git", ["rev-parse", "--short", "HEAD"], { cwd: ROOT, encoding: "utf8" }).trim();

// ---- 2. build --------------------------------------------------------------

// Gradle AND apksigner need it. On macOS the /usr/bin/java stub answers "Unable
// to locate a Java Runtime" rather than failing over to anything, so JAVA_HOME
// has to reach every child that runs Java, not just the build.
const javaHome = process.env.JAVA_HOME ?? join(homedir(), ".local/jdks/temurin-21.jdk/Contents/Home");
const javaEnv = { ...process.env, JAVA_HOME: javaHome, PATH: `${join(javaHome, "bin")}:${process.env.PATH}` };

if (!flag("--skip-build")) {
  step("assembleRelease");
  execFileSync("./gradlew", ["--quiet", ":app:assembleRelease"], {
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
const versionCode = element?.versionCode;
const versionName = element?.versionName;
if (!Number.isInteger(versionCode) || versionCode <= 0) die("output-metadata.json has no versionCode");
const apkPath = join(APK_DIR, element.outputFile);
const apk = readFileSync(apkPath);
const sha256 = createHash("sha256").update(apk).digest("hex");
const objectKey = `monyx-${versionCode}.apk`;

step(`versionCode ${versionCode} (${versionName}), commit ${commit}, ${(apk.length / 1024 / 1024).toFixed(2)} MiB`);

// ---- 3. signed with the release key ----------------------------------------

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

// ---- 4. not already published ----------------------------------------------

function sqlJson(command, params = []) {
  const argv = ["storage", "sqldb", "execute", db, "--remote", "--json", "--command", command];
  for (const p of params) {
    if (typeof p === "number") argv.push("--param-json", String(p));
    else argv.push("--param", p);
  }
  return JSON.parse(execFileSync("telnyx-edge", argv, { encoding: "utf8" })).results ?? [];
}

const existing = sqlJson("SELECT MAX(version_code) AS latest, SUM(version_code = ?) AS taken FROM app_releases", [versionCode])[0];
if (existing?.taken) die(`versionCode ${versionCode} is already published — make a commit, then release`);
if (existing?.latest != null && existing.latest > versionCode) {
  die(`versionCode ${versionCode} is older than the published ${existing.latest}; no phone could install it`);
}
step(`not yet published (latest is ${existing?.latest ?? "none"})`);

if (dryRun) {
  console.log(`\ndry run — would upload ${objectKey} (sha256 ${sha256}) and publish with notes:\n\n${notes}\n`);
  process.exit(0);
}

// ---- 5. upload and verify --------------------------------------------------

function apiKey() {
  if (process.env.TELNYX_API_KEY) return process.env.TELNYX_API_KEY;
  const config = join(homedir(), ".telnyx-edge", "config.toml");
  const match = existsSync(config) && readFileSync(config, "utf8").match(/^api_key\s*=\s*"([^"]+)"/m);
  if (!match) die("no API key: set TELNYX_API_KEY or log in with telnyx-edge");
  return match[1];
}

const KEY = apiKey();

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

console.log(`\n✓ published versionCode ${versionCode} — phones will offer it on their next launch`);

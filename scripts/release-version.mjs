// The pure half of scripts/release.mjs: versions, notes and what the bucket
// keeps. Split out so `node --test scripts/` can check it without a build, a
// bucket or a database.

/** How many APKs the bucket holds: the newest release and the one before it. */
export const KEEP_RELEASES = 2;

const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

/** MAJOR.MINOR.PATCH as numbers, or null. No pre-release tags: a phone cannot tell them apart. */
export function parseVersion(text) {
  const match = SEMVER.exec(String(text ?? "").trim());
  if (!match) return null;
  const [major, minor, patch] = match.slice(1).map(Number);
  if (minor >= 1000 || patch >= 1000) return null;
  return { major, minor, patch };
}

export function formatVersion({ major, minor, patch }) {
  return `${major}.${minor}.${patch}`;
}

export function bumpVersion(version, part) {
  const { major, minor, patch } = version;
  switch (part) {
    case "major":
      return { major: major + 1, minor: 0, patch: 0 };
    case "minor":
      return { major, minor: minor + 1, patch: 0 };
    case "patch":
      return { major, minor, patch: patch + 1 };
    default:
      throw new Error(`--bump takes major, minor or patch, not "${part}"`);
  }
}

/** Keep in step with versionCodeOf in android/app/build.gradle.kts. */
export function versionCode({ major, minor, patch }) {
  return Math.max(1, major * 1_000_000 + minor * 1_000 + patch);
}

/**
 * Release notes as the list the phone shows as bullets: one item per line, any
 * bullet the author typed ("- ", "* ", "• ") dropped, blank lines gone.
 */
export function noteItems(text) {
  return String(text ?? "")
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*(?:[-*•]\s+)?/, "").trim())
    .filter(Boolean);
}

/**
 * The APKs to delete once a release is published: everything in the bucket
 * that is not the object of one of the KEEP_RELEASES newest rows. Only keys
 * shaped like the script's own are touched, so a file put there by hand stays.
 */
export function objectsToPrune(rows, bucketKeys) {
  const keep = new Set(
    [...rows]
      .sort((a, b) => b.version_code - a.version_code)
      .slice(0, KEEP_RELEASES)
      .map((row) => row.object_key),
  );
  return bucketKeys.filter((key) => /^monyx-\d+\.apk$/.test(key) && !keep.has(key));
}

# 0021 — Releases are semver, and the bucket keeps two

**Date:** 2026-09-18 · **Status:** accepted · amends [0020](0020-the-app-updates-itself-and-asks-first.md)

## Context

ADR 0020 took versionCode from the commit count and left `versionName` at "1.0".
So every release was called "1.0". The only way to tell releases apart was a
number that means nothing to a person. Every APK ever published also stayed in the
bucket, although a phone only ever downloads the newest one.

The release notes were a block of text. They were written one change per line,
but the dialog showed them as one paragraph.

## Decision

**The version is semver, and versionCode is derived from it:**
`major * 1_000_000 + minor * 1_000 + patch`. `scripts/release.mjs` takes
`--bump major|minor|patch` (from the newest published row) or `--version X.Y.Z`,
builds with `-PmonyxVersion`, reads the version back from the build's metadata,
and tags the commit `v<version>` once the row is written.

The four rows from before this (codes 51–57, all named "1.0") were deleted, and
**0.6.0 is the first semver release**. Its code, 6000, is still above 57, so
every installed phone is offered it.

Minor and patch are capped below 1000, so the code orders exactly like the
version. There are no pre-release suffixes: the phone compares codes, and a
suffix would have no code of its own.

**The bucket keeps the newest release and the one before it.** After publishing,
the script deletes every other `monyx-<code>.apk`. It deletes through the bucket's
S3-compatible endpoint, because a presigned URL can read and write but cannot list
or delete. The previous APK is what makes withdrawal work: once the newest row is
deleted, phones that have not updated yet are offered the previous release, and
its file is still there. **The rows stay.** They are a few hundred bytes each,
and a phone that skipped several releases still needs their notes. Pruning
failures are reported, not fatal: the release is already out, and the next
release prunes again.

**Notes are bullets.** The dialog draws one bullet per line and drops any bullet
the author typed. The rows published before this were already one change per
line, so they display correctly as they are.

### Downgrading from the phone was considered and not built

Android refuses an APK whose versionCode is below the installed one, so the
stored previous APK cannot be installed as it is. Offering "go back" would mean
rebuilding the previous code under a higher versionCode for every release. That
means two builds per release, and a local database wiped whenever the schema
differs. Going back is still possible without it: release the old code again as
a new patch version.

## Consequences

- `migrations/0006_app_releases.sql` still says version_code comes from the
  commit count. That was true for the rows it describes. Nothing else in the
  schema changes.
- Tags are created locally and not pushed. `git push origin v<version>` is left
  to a person.
- Debug builds and any build without a tag report `0.0.0`, which becomes
  versionCode 1. Debug builds never check for updates, so this is harmless.

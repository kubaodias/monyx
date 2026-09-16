-- The Android releases the app can update itself to.
--
-- Not household data and never synced: there is no household_id, no seq and no
-- deleted flag, and the table is absent from schema.ts COLUMNS, so a push that
-- names it is rejected like any other unknown table. Every enrolled phone reads
-- the same rows through GET /app/latest.
--
-- The APK itself is not here. It lives in the private Cloud Storage bucket
-- named in releases.ts, under object_key, and the route hands out a short-lived
-- presigned URL to it. What IS here is what the phone checks the download
-- against before handing it to the installer: sha256 and size_bytes.
--
-- version_code is the key because it is the only thing Android compares. It
-- comes from the git commit count (android/app/build.gradle.kts), so it is
-- unique and increasing without anyone having to remember to bump it.
--
-- A row is written LAST by scripts/release.mjs, after the upload is verified,
-- so no phone is ever offered an APK that is not there yet. Withdrawing a bad
-- release is deleting its row; phones already past it stay where they are,
-- because Android will not downgrade.
CREATE TABLE app_releases (
  version_code  INTEGER PRIMARY KEY,
  version_name  TEXT NOT NULL,
  object_key    TEXT NOT NULL,
  sha256        TEXT NOT NULL CHECK (length(sha256) = 64),
  size_bytes    INTEGER NOT NULL CHECK (size_bytes > 0),
  notes         TEXT NOT NULL DEFAULT '',
  published_at  INTEGER NOT NULL
);

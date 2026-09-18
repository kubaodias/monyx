# 0020 — The app updates itself, and asks first

**Date:** 2026-09-16 · **Status:** accepted · versions and bucket retention amended by [0021](0021-releases-are-semver-and-the-bucket-keeps-two.md)

## Context

A new build reached the family's phones by hand: build on the laptop, get the
APK onto each phone, tap through the sideload. Every step of that is somebody
remembering to do it, so a fix lands on one phone and not the others, and the
household runs three versions of a sync client at once.

The app is not on Google Play and will not be. That removes the usual answer
(Play updates it) and also removes the usual objection: Play's policy forbids an
app from updating itself outside the store, and there is no store build here to
keep that rule for.

## Decision

**The app checks on launch, offers the update with its release notes, and never
forces it.** Settings › Advanced can check on demand and reopen the offer.

### Where the APK lives

In a **private Telnyx Cloud Storage bucket**, `monyx-releases`, in
`us-central-1`. Not Edge KV: KV has no published value-size limit, and every
download would pass through the function. Not an EU region: presigned URLs are
not offered there, and a 3 MB download from Warsaw to the US once a release is
not worth a second design.

**The function holds no storage credential.** It mints a presigned URL through
`env.TELNYX`, the client the `[telnyx]` binding already provides for the note
route — the same reasoning as ADR 0018's window-not-a-key.

What the database holds is the row: `version_code`, `sha256`, `size_bytes`,
`notes`. The row is written **last**, by `scripts/release.mjs`, after the upload
has been read back from the bucket and hashed. Until the row exists no phone can
see the release, so a failure at any earlier step publishes nothing.

### Two routes, not one

`GET /app/latest` runs on every launch and returns the newest release plus the
notes of **every** release newer than the phone's, so somebody who skipped two
versions reads what changed in both. `GET /app/download` mints the URL, and only
when somebody taps Update. The five-minute window starts when the download does,
and a phone that never updates never holds a URL.

### The URL can write, so the phone does not trust it

The token behind a Telnyx presigned URL grants `PutObject` as well as
`GetObject` (found by decoding one, not by reading the docs). Anybody holding a
download URL could overwrite that APK for five minutes.

That cannot become an install, because the phone checks, in order:

1. size and **sha256 against the database row** — not against the bucket;
2. the archive's package is `com.monyx` and its versionCode is newer;
3. its signing certificates are **exactly** the installed app's.

Android enforces (2) and (3) again at install. The checks exist so the dialog can
say which thing failed; the installer's own refusal is "App not installed". The
worst an overwrite achieves is a failed update, and the five-minute TTL — also
the ceiling for an account without Level 2 verification — bounds how long it is
possible at all.

### Asking, not forcing

The system confirms every install from an app that is not the installer of
record; an ordinary app cannot skip that, and this does not try. The first
update also sends the user to "Install unknown apps" for this app, once. The
dialog resumes by itself on return from that screen.

A forced minimum version was considered and not built. Its only use would be a
sync protocol change old clients cannot survive, and the protocol already
handles that on the server: an unknown table is rejected with `id: null` and the
rows stay pending until the client catches up.

### Debug builds never check

`com.monyx.debug` is a different package signed with a different key. A release
APK is not an update to it, so every offer would end in a refusal. Settings says
so rather than showing a button that cannot work.

## Consequences

- Publishing is `node scripts/release.mjs --notes "..."`. It refuses a dirty
  tree (versionCode is the commit count), an APK not signed with the release
  certificate, and a versionCode already published or older than the latest.
- **Withdrawing a release is deleting its row.** A phone that already installed
  it stays: Android does not downgrade.
- The release keystore is now also the update channel. Losing it means every
  phone uninstalls — the same consequence as before, reached more often.
- Phones still on `com.monyx.debug` need one manual install of a release build.
  Every update after that comes through the app.

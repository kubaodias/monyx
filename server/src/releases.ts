// GET /app/latest: whether this phone has an update waiting, and where to get it.
//
// The APK sits in a PRIVATE Cloud Storage bucket. Nothing here holds a storage
// credential: the presigned URL is minted through `env.TELNYX`, the client the
// `[telnyx]` binding puts on the environment, the same way note.ts reaches the
// model. See docs/decisions/0020-the-app-updates-itself-and-asks-first.md.
//
// The phone never trusts the URL for integrity. It checks sha256 and size from
// the row, then that the APK is signed with the certificate it is itself signed
// with — which Android enforces again at install. The token behind a presigned
// URL grants PutObject as well as GetObject, so a leaked URL could overwrite the
// object for its lifetime; those checks are why the worst that achieves is a
// failed update, and the short TTL is why the window is five minutes.
import type { ReleaseRow } from "./db.ts";

/** Created by hand in us-central-1: presigned URLs are not offered in the EU regions. */
export const RELEASES_BUCKET = "monyx-releases";

/**
 * Five minutes: the ceiling for an account without Level 2 verification, and
 * plenty — the phone asks for the URL when somebody taps Update, not at launch,
 * and a 3 MB download does not take five minutes.
 */
export const URL_TTL_SECONDS = 300;

/**
 * Structural, like note.ts ChatClient: the tests stand a small object in front of
 * it and never open a socket, and `env.TELNYX` satisfies it as it is.
 */
export interface Presigner {
  storage: {
    buckets: {
      createPresignedURL(
        objectName: string,
        params: { bucketName: string; body?: { ttl?: number } },
      ): PromiseLike<unknown>;
    };
  };
}

export interface ReleaseNote {
  version_code: number;
  version_name: string;
  notes: string;
  published_at: number;
}

export interface Update {
  version_code: number;
  version_name: string;
  size_bytes: number;
  sha256: string;
  /** Every release newer than the phone's, newest first, each with its own notes. */
  notes: ReleaseNote[];
}

export interface Download {
  url: string;
  expires_at: number;
}

/** A non-negative integer from the query string, or null. */
export function parseVersionCode(raw: string | null): number | null {
  if (raw === null || !/^\d{1,9}$/.test(raw)) return null;
  return Number(raw);
}

/** Null when the phone is already on the newest release. */
export function describeUpdate(rows: ReleaseRow[]): Update | null {
  const sorted = [...rows].sort((a, b) => b.version_code - a.version_code);
  const newest = sorted[0];
  if (!newest) return null;
  return {
    version_code: newest.version_code,
    version_name: newest.version_name,
    size_bytes: newest.size_bytes,
    sha256: newest.sha256,
    notes: sorted.map((r) => ({
      version_code: r.version_code,
      version_name: r.version_name,
      notes: r.notes.trim(),
      published_at: r.published_at,
    })),
  };
}

/**
 * A presigned GET for one object, or null.
 *
 * The live API answers under `data`; the SDK's own type says `content`. Both are
 * read, because the type is what the SDK will eventually be made to match and
 * the wire is what it returns today — found by calling it, not by reading it.
 */
export async function presign(
  objectKey: string,
  client: Presigner | null,
  nowMs: number,
): Promise<Download | null> {
  if (!client) return null;
  let response: unknown;
  try {
    response = await client.storage.buckets.createPresignedURL(objectKey, {
      bucketName: RELEASES_BUCKET,
      body: { ttl: URL_TTL_SECONDS },
    });
  } catch (error) {
    console.error("presign_failed", error instanceof Error ? error.message : "unknown");
    return null;
  }
  const outer = (response ?? {}) as Record<string, unknown>;
  const inner = (outer["data"] ?? outer["content"] ?? {}) as Record<string, unknown>;
  const url = inner["presigned_url"];
  if (typeof url !== "string" || !url.startsWith("https://")) return null;
  const parsed = typeof inner["expires_at"] === "string" ? Date.parse(inner["expires_at"]) : NaN;
  return {
    url,
    expires_at: Number.isFinite(parsed) ? parsed : nowMs + URL_TTL_SECONDS * 1000,
  };
}

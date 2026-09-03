// Naming the shop a spoken expense happened at, and nothing else.
//
// This is the first module in this codebase that talks to a language model,
// and the first that talks to anything outside the platform at all beyond FCM.
// Two things about it are deliberate and worth stating before the code:
//
// **It touches no database.** Not `env.DB`, not KV, not a household id beyond
// the one the rate limiter counts against. ADR 0018 said the voice routes were
// `SELECT`-only by construction; this one is stronger — there is nothing here
// to construct a query with. A text transform in, a text transform out.
//
// **It never logs the transcript.** The phone is now sending a sentence a
// household said out loud about its own money. That is the cost the owner
// accepted when they chose this over an on-device model, and the least this
// module can do is not also accumulate it in a log. Errors are logged as codes.
//
// It is also entirely optional. The phone writes the row and shows the summary
// with a note its own rules produced, offline or not, before this is ever
// called; every failure here means the phone keeps that note. See ADR 0019.

/** What the phone already worked out, and what it wants a second opinion on. */
export interface NoteInput {
  transcript: string;
  amountMinor: number;
  category: string | null;
  note: string;
}

/** The one thing this module hands back. `null` means "keep what you have". */
export interface NoteSuggestion {
  note: string | null;
}

/**
 * Telnyx's OpenAI-compatible completion endpoint. `POST /v2/ai/chat/completions`
 * is deprecated in the published spec in favour of this one.
 *
 * There is no edge binding for inference — the runtime exposes sqldb, kv,
 * secrets, rate limiters, buckets and actors, and nothing else — so this is an
 * ordinary outbound fetch with a bearer key, exactly the way `fcm.ts` reaches
 * Google.
 */
const ENDPOINT = "https://api.telnyx.com/v2/ai/openai/chat/completions";

/**
 * The spec's own default, and small on purpose. Naming a shop out of a sentence
 * that already contains it is not a task that improves with a larger model, and
 * this is billed per utterance.
 */
const MODEL = "meta-llama/Meta-Llama-3.1-8B-Instruct";

/** A label, not a sentence. Four words is a generous shop name. */
const MAX_CHARS = 40;
const MAX_WORDS = 4;

/**
 * Long enough for a small model to answer twenty tokens, short enough that a
 * wedged upstream cannot hold a function open. The phone has its own, shorter
 * deadline and will have given up before this fires.
 */
const TIMEOUT_MS = 6_000;

export function parseNoteInput(body: Record<string, unknown>): NoteInput | null {
  const transcript = body["transcript"];
  const note = body["note"];
  const amount = body["amount_minor"];
  const category = body["category"];
  if (typeof transcript !== "string" || transcript.trim().length === 0) return null;
  if (transcript.length > 400) return null;
  // The phone only ever asks about a note it already has. A request without one
  // would be asking the model to invent a label, which is the one thing it must
  // not do — see the client's own rule in NoteWriter.kt.
  if (typeof note !== "string" || note.trim().length === 0) return null;
  if (typeof amount !== "number" || !Number.isFinite(amount) || amount < 0) return null;
  return {
    transcript: transcript.trim(),
    amountMinor: Math.floor(amount),
    category: typeof category === "string" && category.length > 0 ? category : null,
    note: note.trim(),
  };
}

/**
 * The prompt, in one place.
 *
 * Three things it has to say, and the third is the one that matters:
 *
 *  - what was said, and what has already been read out of it, so the amount and
 *    the category are not offered back as a label;
 *  - that a place is written the way it is written on the shop — Polish inflects
 *    it after "w" and a label should not be inflected, which is the entire job;
 *  - that returning NOTHING is a correct answer. A model that always finds a
 *    label is worse than the table it is second-guessing, because the table's
 *    failure is to leave a note alone and a model's is to invent one.
 *
 * The examples are the owner's own sentences. They are worth more than the
 * instructions above them and should stay real rather than become tidy
 * illustrations of a rule.
 */
export function buildMessages(input: NoteInput): Array<{ role: string; content: string }> {
  return [
    {
      role: "system",
      content: [
        "You label one expense in a household budget app. Polish and English.",
        "Reply with ONLY the label: at most four words, no quotes, no explanation.",
        "If the sentence names no place or thing worth labelling, reply with: -",
        "Write a place the way it is written on the shop, not the way it was said.",
        "Polish inflects a name after a preposition; the label is not inflected.",
        "",
        "Said: 150 zl na zakupy w Biedronce -> Biedronka",
        "Said: wydalem 35 na paliwo -> -",
        "Said: 20 na transport bilet miesieczny -> Bilet miesieczny",
        "Said: sto zlotych na dom w Castoramie przy dworcu -> Castorama przy dworcu",
      ].join("\n"),
    },
    {
      role: "user",
      content: [
        `Said: ${input.transcript}`,
        input.category ? `Already filed under: ${input.category}` : null,
        `Already read as the amount: ${Math.floor(input.amountMinor / 100)}`,
        `Current label: ${input.note}`,
        "Label:",
      ]
        .filter((line) => line !== null)
        .join("\n"),
    },
  ];
}

/**
 * Trimmed, bounded, and refused when it is obviously not a label.
 *
 * The client validates again and more strictly — it is the side that still has
 * the transcript to check the answer against, and it refuses anything not
 * grounded in words that were actually said. This is the cheap half: stop
 * nonsense before it crosses the wire.
 */
export function acceptLabel(raw: string | null | undefined, input: NoteInput): string | null {
  if (typeof raw !== "string") return null;
  const cleaned = raw.trim().replace(/^["'`]+|["'`.:]+$/g, "").trim();
  if (cleaned.length === 0 || cleaned === "-") return null;
  if (cleaned.length > MAX_CHARS) return null;
  if (/[\r\n]/.test(cleaned)) return null;
  if (cleaned.split(/\s+/).length > MAX_WORDS) return null;
  if (cleaned === input.note) return null;
  return cleaned;
}

/** Injected so the tests never reach the network. */
export type Fetcher = typeof fetch;

/**
 * Ask, once, and give up quietly.
 *
 * Every failure — no key configured, a non-200, malformed JSON, a timeout, a
 * refusal dressed as an answer — returns `{ note: null }`, which the phone
 * reads as "keep the note you already have". There is no error to report
 * because there is nothing the household could do about one, and nothing is
 * broken when it happens: the row was written before this was called.
 */
export async function suggestNote(
  input: NoteInput,
  apiKey: string | null,
  fetchImpl: Fetcher = fetch,
): Promise<NoteSuggestion> {
  if (!apiKey) return { note: null };

  try {
    const response = await withTimeout(
      fetchImpl(ENDPOINT, {
        method: "POST",
        headers: {
          authorization: `Bearer ${apiKey}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          model: MODEL,
          messages: buildMessages(input),
          // Deterministic, and capped hard. A label is a handful of tokens; a
          // model that wants more of them is explaining itself, and the answer
          // is refused below anyway.
          temperature: 0,
          max_tokens: 24,
          stop: ["\n"],
        }),
      }),
    );
    if (!response || !response.ok) return { note: null };

    const payload = (await response.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    return { note: acceptLabel(payload?.choices?.[0]?.message?.content, input) };
  } catch (error) {
    // The code, never the sentence. See the header.
    console.error("note inference failed", error instanceof Error ? error.name : "unknown");
    return { note: null };
  }
}

/**
 * Promise.race rather than AbortSignal.timeout: the abort helper is not
 * something this runtime's surface promises, and a missing global here would
 * turn every call into an exception rather than a slow one.
 */
async function withTimeout(pending: Promise<Response>): Promise<Response | null> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expiry = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), TIMEOUT_MS);
  });
  try {
    return await Promise.race([pending, expiry]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

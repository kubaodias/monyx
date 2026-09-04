// The note route: a text transform that costs money and touches no database.
//
// What is being pinned here is mostly what the route REFUSES. It is the first
// thing in this codebase that sends a household's own words to a third party
// and gets billed for it, so the interesting cases are the ones where it
// declines to do that — a missing key, a bad answer, an answer that is really
// an apology — and every one of them has to come back as "keep the note you
// already have" rather than as an error, because the phone has already written
// the row and shown the summary.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  acceptLabel,
  buildMessages,
  parseNoteInput,
  suggestNote,
  type ChatClient,
  type NoteInput,
} from "../src/note.ts";

const INPUT: NoteInput = {
  transcript: "150 zł na zakupy w Biedronce",
  amountMinor: 15000,
  category: "Zakupy spożywcze",
  note: "Biedronce",
};

/**
 * A stand-in for `env.TELNYX`. Two lines, because the module only ever reaches
 * one method on it — and because a fake that satisfies the real client's whole
 * surface would be testing the SDK rather than this.
 */
function client(
  respond: (body: unknown) => unknown,
  seen?: (body: Record<string, unknown>) => void,
): ChatClient {
  return {
    ai: {
      openai: {
        chat: {
          createCompletion: async (body) => {
            seen?.(body as unknown as Record<string, unknown>);
            return respond(body);
          },
        },
      },
    },
  };
}

function reply(content: string): ChatClient {
  return client(() => ({ choices: [{ message: { content } }] }));
}

// ------------------------------------------------------------------ input

test("a request without a note is refused: the model may not invent one", () => {
  assert.equal(parseNoteInput({ transcript: "x", amount_minor: 100 }), null);
  assert.equal(parseNoteInput({ transcript: "x", amount_minor: 100, note: "  " }), null);
});

test("a request without a transcript, or with an absurd one, is refused", () => {
  assert.equal(parseNoteInput({ note: "n", amount_minor: 100 }), null);
  assert.equal(parseNoteInput({ transcript: "", note: "n", amount_minor: 100 }), null);
  assert.equal(parseNoteInput({ transcript: "x".repeat(401), note: "n", amount_minor: 100 }), null);
});

test("a well-formed request keeps only the four fields it needs", () => {
  const parsed = parseNoteInput({
    transcript: "  150 zł na zakupy w Biedronce  ",
    amount_minor: 15000.7,
    category: "Zakupy spożywcze",
    note: "Biedronce",
    household_id: "should-be-ignored",
  });
  assert.deepEqual(parsed, {
    transcript: "150 zł na zakupy w Biedronce",
    amountMinor: 15000,
    category: "Zakupy spożywcze",
    note: "Biedronce",
  });
});

// ----------------------------------------------------------------- prompt

test("the prompt carries the sentence and permission to answer with nothing", () => {
  const messages = buildMessages(INPUT);
  const whole = messages.map((m) => m.content).join("\n");
  assert.match(whole, /150 zł na zakupy w Biedronce/);
  assert.match(whole, /Zakupy spożywcze/);
  assert.match(whole, /Current label: Biedronce/);
  assert.match(whole, /reply with: -/);
  // The owner's own case for "no label at all". A model that always finds one
  // is worse than the suffix table it is second-guessing.
  assert.match(whole, /wydalem 35 na paliwo -> -/);
});

// ------------------------------------------------------------- the answer

test("a short, different label is taken", () => {
  assert.equal(acceptLabel("Biedronka", INPUT), "Biedronka");
  assert.equal(acceptLabel('  "Biedronka".  ', INPUT), "Biedronka");
});

test("a refusal, a sentence or an echo is not a label", () => {
  assert.equal(acceptLabel("-", INPUT), null);
  assert.equal(acceptLabel("", INPUT), null);
  assert.equal(acceptLabel("Biedronka\nBiedronka", INPUT), null);
  assert.equal(acceptLabel("The label for this expense is Biedronka", INPUT), null);
  assert.equal(acceptLabel("B".repeat(41), INPUT), null);
  // Already what the phone has: nothing to change.
  assert.equal(acceptLabel("Biedronce", INPUT), null);
  assert.equal(acceptLabel(undefined, INPUT), null);
});

// -------------------------------------------------------------- the call

test("no binding is not an error, and makes no call", async () => {
  assert.deepEqual(await suggestNote(INPUT, null), { note: null });
});

test("a good answer comes back as the note", async () => {
  assert.deepEqual(await suggestNote(INPUT, reply("Biedronka")), { note: "Biedronka" });
});

/**
 * No credential appears anywhere in the request this module builds — that is
 * the point of reaching the model through the binding. The runtime's auth
 * proxy attaches the bearer after this code is done with it.
 */
test("the sentence goes in the body and no key goes anywhere", async () => {
  let seen: Record<string, unknown> | null = null;
  await suggestNote(INPUT, client(() => ({ choices: [{ message: { content: "Biedronka" } }] }), (b) => {
    seen = b;
  }));

  const messages = seen!["messages"] as Array<{ content: string }>;
  assert.match(messages.map((m) => m.content).join("\n"), /Biedronce/);
  assert.equal("api_key_ref" in seen!, false);
  assert.equal(JSON.stringify(seen).toLowerCase().includes("bearer"), false);
});

test("every upstream failure means keep the note you have", async () => {
  const boom = client(() => {
    throw new Error("upstream");
  });
  const garbage = client(() => "not an object");
  const empty = client(() => ({}));
  const noChoices = client(() => ({ choices: [] }));
  const noContent = client(() => ({ choices: [{ message: {} }] }));

  for (const impl of [boom, garbage, empty, noChoices, noContent]) {
    assert.deepEqual(await suggestNote(INPUT, impl), { note: null });
  }
});

test("an apology from the model is not written into the ledger", async () => {
  const sorry = reply("I'm sorry, I cannot determine a label from that sentence.");
  assert.deepEqual(await suggestNote(INPUT, sorry), { note: null });
});

// The point of the module, stated as a test.
//
// ADR 0018 called the voice routes "SELECT-only by construction". This one is
// a stronger claim — there is nothing here to build a query with — and a claim
// like that is worth a test rather than a comment, because the way it would be
// lost is somebody adding one innocuous lookup.
//
// Comments are stripped first: the header talks ABOUT SQL, and a test that
// cannot tell prose from code would fail on its own explanation.
test("the note module has no database to touch", async () => {
  const raw = await (await import("node:fs/promises")).readFile("src/note.ts", "utf8");
  const code = raw.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
  assert.equal(/from "\.\/db\.ts"/.test(code), false);
  assert.equal(/env\.DB|env\.KV/.test(code), false);
  // Nor a credential. The binding is the whole authentication story.
  assert.equal(/API_KEY|apiKey|Bearer|SECRETS/.test(code), false);
  assert.equal(/\bSELECT\b|\bINSERT\b|\bUPDATE\b|\bDELETE\b/.test(code), false);
  assert.equal(/prepare\s*\(/.test(code), false);
});

// ------------------------------------------------------- how it is wired
//
// Structural checks, and worth saying why: this project tests modules rather
// than the router, so there is no harness that can send a Request through
// index.ts. What these pin is the wiring that would be lost silently — a route
// drifting above the authentication gate, or a rate limit quietly removed from
// the one endpoint that is billed per call. Both would still pass every other
// test in this suite.

test("the note route sits behind the device session, not in front of it", async () => {
  const index = await (await import("node:fs/promises")).readFile("src/index.ts", "utf8");
  const gate = index.indexOf("const session = await authenticate(");
  const route = index.indexOf('path === "/voice/note"');
  assert.ok(gate > 0 && route > 0);
  // After the gate: unlike /voice/context and /voice/digest, whose caller is a
  // phone LINE with no session, this caller is an enrolled phone that already
  // holds one. Inventing a second credential for it would be the wrong shape.
  assert.ok(route > gate, "/voice/note must be registered after authenticate()");
});

test("the note route is rate limited, per household", async () => {
  const index = await (await import("node:fs/promises")).readFile("src/index.ts", "utf8");
  const handler = index.slice(
    index.indexOf("async function handleVoiceNote"),
    index.indexOf("async function handleCron"),
  );
  assert.match(handler, /env\.NOTE_LIMIT/);
  assert.match(handler, /key: session\.household_id/);
  assert.match(handler, /429/);

  const config = await (await import("node:fs/promises")).readFile("telnyx.toml", "utf8");
  assert.match(config, /name = "NOTE_LIMIT"/);
});

/**
 * The credential that is not there.
 *
 * An earlier version of this route carried its own `TELNYX_API_KEY` secret,
 * because the runtime's binding types do not mention inference and the `.d.ts`
 * files looked conclusive. They were not: `[telnyx]` is materialised in
 * build-env.js, and it hands the function a client whose bearer the auth proxy
 * fills in on the way out of the pod. An account-wide key in a secret store is
 * strictly worse — one more thing to rotate, one more thing to leak — so this
 * fails if one ever comes back.
 */
test("inference goes through the binding, not through a key of our own", async () => {
  const config = await (await import("node:fs/promises")).readFile("telnyx.toml", "utf8");
  assert.match(config, /\[telnyx\]\nbinding = "TELNYX"/);
  assert.equal(/TELNYX_API_KEY/.test(config), false);

  const index = await (await import("node:fs/promises")).readFile("src/index.ts", "utf8");
  assert.match(index, /suggestNote\(input, env\.TELNYX/);
  assert.equal(/TELNYX_API_KEY/.test(index), false);
});

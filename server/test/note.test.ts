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
import { acceptLabel, buildMessages, parseNoteInput, suggestNote, type NoteInput } from "../src/note.ts";

const INPUT: NoteInput = {
  transcript: "150 zł na zakupy w Biedronce",
  amountMinor: 15000,
  category: "Zakupy spożywcze",
  note: "Biedronce",
};

function reply(content: string, status = 200): typeof fetch {
  return (async () =>
    new Response(JSON.stringify({ choices: [{ message: { content } }] }), {
      status,
      headers: { "content-type": "application/json" },
    })) as unknown as typeof fetch;
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

test("no key configured is not an error, and makes no call", async () => {
  let called = false;
  const spy = (async () => {
    called = true;
    return new Response("{}");
  }) as unknown as typeof fetch;

  assert.deepEqual(await suggestNote(INPUT, null, spy), { note: null });
  assert.equal(called, false);
});

test("a good answer comes back as the note", async () => {
  assert.deepEqual(await suggestNote(INPUT, "key", reply("Biedronka")), { note: "Biedronka" });
});

test("the key travels as a bearer token and the transcript in the body", async () => {
  let seen: Request | null = null;
  const spy = (async (_url: string, init: RequestInit) => {
    seen = { headers: new Headers(init.headers), body: init.body } as unknown as Request;
    return new Response(JSON.stringify({ choices: [{ message: { content: "Biedronka" } }] }));
  }) as unknown as typeof fetch;

  await suggestNote(INPUT, "sekret", spy);
  assert.equal(new Headers(seen!.headers).get("authorization"), "Bearer sekret");
  assert.match(String(seen!.body), /Biedronce/);
});

test("every upstream failure means keep the note you have", async () => {
  const boom = (async () => {
    throw new Error("offline");
  }) as unknown as typeof fetch;
  const notOk = (async () => new Response("nope", { status: 502 })) as unknown as typeof fetch;
  const garbage = (async () => new Response("not json")) as unknown as typeof fetch;
  const empty = (async () => new Response(JSON.stringify({}))) as unknown as typeof fetch;

  for (const impl of [boom, notOk, garbage, empty]) {
    assert.deepEqual(await suggestNote(INPUT, "key", impl), { note: null });
  }
});

test("an apology from the model is not written into the ledger", async () => {
  const sorry = reply("I'm sorry, I cannot determine a label from that sentence.");
  assert.deepEqual(await suggestNote(INPUT, "key", sorry), { note: null });
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
  assert.match(config, /binding = "TELNYX_API_KEY"/);
});

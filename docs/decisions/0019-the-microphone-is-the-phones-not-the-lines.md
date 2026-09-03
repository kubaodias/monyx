# 0019 — The microphone is the phone's, not the line's

**Date:** 2026-09-03 · **Status:** accepted

## Context

[ADR 0018](0018-the-assistant-gets-a-window-not-a-key.md) gave the household a
way to ask about its money over the phone, and left one thing open in as many
words: *"Adding a transaction by voice is the feature worth having, and it
writes through the sync epoch and needs a `created_by` member without a device —
a design question, not an increment."*

That is still true of a transaction added over the line. It is not true of one
added on the phone, and the phone is where the use case actually lives: a shop,
one hand, the phone half out of a pocket, wanting to write down two hundred
złoty of transport before it is forgotten. Nobody rings a number to do that.

The five-second rule at the top of `AddScreen.kt` is the constraint everything
here is measured against — two taps and the amount, and *"anything that
stretches that — an animation, a save confirmation, a network requirement — is a
bug, not a matter of taste"*. Voice entry has no taps at all, which exempts it
from the first clause and binds it completely to the second.

## Decision

**Held on the Add tab, recognised on the device, parsed on the device, written
through the ordinary local path.**

### The server does not change, and that is the argument

The utterance is recognised by `android.speech.SpeechRecognizer`, preferring the
on-device path where the language pack exists. It is parsed by
`com.monyx.voice.VoiceParser`, which is a pure function of its arguments. The
row is written by `MonyxRepository.addTransaction` with the phone's own member
id and `pending = 1`, exactly as the keypad writes one, and `SyncWorker` carries
it up on its own schedule.

So the question ADR 0018 left open is answered by not going there at all. **No
write-capable route is created and no member without a device is invented.** The
transaction itself never touches the server except through the sync epoch every
other row uses.

One later amendment to this, and it is worth stating rather than leaving to be
discovered: the note — and only the note — is now improved by a call to
`POST /voice/note`. That route is described at the end of this document. It
writes nothing, reads nothing, and has no database binding at all; the sentence
above still holds for every transaction this feature creates.

Recording *audio* and posting it somewhere to be transcribed was the
alternative, and it is a different question from sending the recognised TEXT of
one sentence afterwards, which is what the note route does. This paragraph is
about the audio, and about doing it in the middle of the add path. It loses on
every axis that matters: a second or two of shop 4G inside the five seconds the
add path exists to protect; money per utterance, forever; nothing at all in a
basement supermarket, which is exactly where it would be reached for; and a
household's audio sent to a third party. Telnyx Edge Compute has no
transcription of its own, so it would also mean putting a *second* vendor
behind the first.

### No model in the add path

A round trip in the middle of a sentence is the thing the `AddScreen` header
forbids by name, and offline-first is not a nicety here: every local write sets
`pending = 1` precisely so nothing in the add path waits on a network. A parse
that needed the internet would make voice the one feature that silently stops
working in a basement.

The problem is also small, and the app already owns it. The utterance is nearly
always `<verb?> <number> <preposition?> <category>`, and the target set is one
household's ten to thirty self-named categories. What a model would buy is
open-ended phrasing; what the ledger actually needs is that a wrong answer is
visible, because the ledger is shared and a wrong row syncs to everybody. So the
residue is handled by **showing the row that was written** rather than by
understanding better, and by refusing rather than guessing:

The summary used to quote the transcript back — *Usłyszano: "dodaj dwieście na
transport"* — and that was the weaker half of the same idea. The transcript
tells you what the recogniser heard; the fields tell you what is now in the
ledger, which is the thing that is either right or wrong, and the two only ever
differ when the parse went astray, which is exactly when reading the sentence
back would be least use. Every field is on the sheet and every one of them
opens the editor on a tap. A word saying "Poprawiono" went with it for the same
reason: the changed value is better evidence that something changed than a
label claiming it.

- **Whatever no field claimed is the note.** "150 zł na zakupy w Biedronce" is
  one amount, one category and two words nothing asked for — and those two words
  are the entire reason anybody says the sentence that way. So the amount, the
  date, the category and the kind keywords each report the span they used, and
  what is left over at the end of that is written down. It is not a list of
  shops and it never will be: the rule is about bookkeeping, not vocabulary.

  The trap in it, and the one thing this must never get wrong, is that "dodaj
  200 na transport" also has words left over — "dodaj" and "na". A note reading
  *Dodaj na* would be worse than not having the feature, so leading fillers are
  dropped, and there is a test whose whole job is that five ordinary sentences
  produce no note at all.
- **A note names the place; it does not repeat the sentence.** A preposition of
  place goes with the fillers, and the case ending it put on the word after it
  goes too: "w Biedronce" is written down as **Biedronka**. A note is a label on
  a row, not a sentence about a trip, and the label somebody would have typed is
  the name of the shop in the form it is written on the shop.

  Undoing a Polish case ending properly needs a dictionary and a gender, and
  neither is going near this app. What is there instead is a table of the six
  endings that turn up on the front of a shop, applied to exactly one word — the
  one the preposition governed — with a five-character floor under it, because
  the shortest chain names in the locative are five ("Lidlu", "Żabce") and below
  that a word ending in `-u` is far likelier to be ordinary Polish than a shop.
  Anything the table does not recognise is left exactly as it was heard.

  **It will sometimes be wrong**, and it is worth being plain about why that is
  allowed here when a guess at a category is not. `-cie` is ambiguous in the
  language itself — *markecie* is from "market", *gazecie* is from "gazeta" —
  and this picks the consonant because shop names are consonants. A name whose
  nominative genuinely ends in one of these strings gets cut. All of that lands
  on a field that is free text, that touches no total, that is on screen the
  moment it is written, and that one tap opens an editor on. A wrong category
  moves money between columns and syncs to the other phone; a wrong note is a
  cosmetic guess made where a wrong guess is visible and costs a tap. English
  gets the preposition and its article stripped and nothing else, having no
  case endings to undo.
- **A note is what is said ABOUT the row; a name is what is said INSTEAD of
  one.** "te zakupy były w lidlu" contains a seeded category name and is
  plainly not a category correction — *zakupy* is only being used to point at
  the transaction. The rule, and it is small on purpose: an utterance is a note
  when it carries a demonstrative or a copula from a closed list that is not
  itself part of the name that matched, AND the only thing it otherwise
  produced was a category, or nothing. Naming a field — an amount, a date, an
  account, a kind — is never a note. An explicit "notatka …" outranks all of
  it, including the undo verbs, and works in the first pass too.

  **The failure mode this accepts:** a note nobody meant to dictate. That is one
  line of text on a sheet the user is already reading, beside a field that opens
  an editor on one tap. A wrong category or a wrong amount is neither visible
  nor cheap — it moves money between columns and syncs to the other phone.
  Between the two readings, the note is the safer landing, and the rule is
  written to fall that way.
- **A tie between two category names is a refusal**, not a coin toss — except
  between a parent and its own child, where the specific one is what somebody
  naming a word the child owns meant.
- **The kind is decided from the category, and only from a category that was
  named in full.** This is a correctness rule and not a tidiness one, and it
  took two passes to get right. The first version read a keyword first:
  `Wypłata` is the seeded *income* category and *wypłaciłem* — I withdrew cash —
  shares its stem, so "wypłaciłem 200 na zakupy" was filed as income and the
  month's sign flipped. Deciding from the category instead moved the same bug
  into the scorer, where "dodaj 200 na inne wydatki" reaches `Inne przychody` on
  the single shared word *inne* and produces an income row for a sentence whose
  other word is *expenses*.

  So the rule has a second half. A category matched on only PART of its name is
  a good enough guess to file under and is not evidence about the direction: the
  kind then comes from the keyword, or from the default, and a partial match
  that contradicts it is not filed at all — the sentence goes to the keypad. The
  one asymmetry is deliberate: a partially-matched EXPENSE category outranks an
  income keyword, because income has to be asserted and expense is the default.

  **The residue, stated rather than claimed away:** a partial match can still
  file a row under the wrong category of the *right* kind, and a name said in
  full is trusted completely, so a household with a category actually called
  "Inne" would be taken at its word. Words that point both ways (`zwrot`,
  `refund`) are not keywords at all, for the same reason.
- **What it cannot finish, it does not write.** A parse that falls short hands
  what it got to the keypad, prefilled, and a tap finishes the job. The second
  engine is the existing UI, not a second parser. Indirect references,
  free-form dates and notes by voice all fall to it, deliberately.
- **Two clauses in one breath are refused outright.** "dodaj 200 na zdrowie i
  100 na dom" is the one shape where every part in isolation succeeds and the
  whole is wrong: the amount reader takes the last number, the matcher takes the
  best-scoring name, neither knows the other exists, and the row is 100 filed
  under Zdrowie — which is neither thing that was said, and which is complete
  enough to be written and synced. More than one number AND more than one name
  clearing the threshold is a sentence this grammar has no business finishing.

### Commit, then a summary — with Revert next to it

The row is written first and the summary is shown after, with Revert, Change and
Done, and the summary can itself be corrected by voice. What that costs, stated
plainly: a mis-heard row exists in the shared ledger for the seconds before the
summary is read, and if a push goes out in that window the other person's phone
shows a row that then disappears.

What makes it survivable is already in the sync design and was verified rather
than assumed. `SyncEngine.pushPending` reads `dao.pendingTransactions()` **at
push time**, the query does not filter tombstones, and `deleteTransaction`
rewrites the whole row with `deleted = 1, pending = 1`. So an add-then-revert
before the push transmits **exactly once, as a tombstone**. There is no window
in which a revert is lost.

**Sync is enqueued on save, on revert and on edit** — the same points the keypad
and the transactions list enqueue at. Holding the enqueue back until the summary
was dismissed was designed and then dropped, and the reasoning is worth keeping:
it creates no window worth having, because `enqueue` is unique-KEEP and app open
has already queued one, so a voice add within fifteen seconds of launch rides
that push at its original deadline regardless of the sheet. And it loses
durability outright when the sheet is never dismissed — screen off, pocket,
process death, or a swipe from recents, which is a force stop on several OEMs
after which no WorkManager job runs until the next launch. A voice row could sit
undelivered for a day while an identical keypad row went in fifteen seconds.

The residual cost is therefore honest and small: if a push is already in flight,
the create goes up and the tombstone follows it on the next run, so the other
phone may briefly show a row that then disappears.

### The permission dialog, and why this is the exception

`NotificationPermission.kt` says in the source, about a different permission:
*"This must NEVER be called from the Add screen — the five-second rule forbids a
permission dialog in that path."* The voice gesture lives on the Add tab. That
rule has to be named, not stepped around.

The rule is about an **unprompted** dialog in front of somebody who came to type
a number. `NotificationPermissionGate` fires from a `LaunchedEffect(Unit)` on
arrival at a screen: the person did not ask for it, and it costs them a decision
in the seconds the screen exists to protect. `RECORD_AUDIO` is requested only
after a finger has been held on the bar for half a second, which is a request
for the microphone in as many words, and only once — after a refusal the sheet
asks in words instead, because a second automatic launch is a dialog Android
will not even draw. The keypad path is untouched: type, tap, save, no dialog,
ever.

### No speaking back

In a shop, a phone announcing "zapisano dwieście złotych na transport" reads the
household's spending out to the queue behind you — and that is the exact context
this was asked for. It would also cost a `TextToSpeech` init handshake and two
seconds of wall clock during which nothing can happen, spent on a message that
takes 300 ms to read. The summary is more precise anyway: five fields at a
glance, against a sentence you have to listen to the end of.

Haptics carry the part that has to be eyes-free — one pulse when the microphone
goes live, a distinct one when the row exists. Speaking back would be an opt-in
setting for driving, and is out of scope.

### The note, and the one call that leaves the phone

Everything above is about the transaction. The note is a different matter, and
it ended up on the other side of the line.

The suffix table that writes it — six Polish locative endings, undone on one
word — is honest about being a table and gets a name like *Biedronka* right most
of the time. What it cannot do is anything it was not written for. So a model
was put behind it, and **the first attempt was on the device**: Gemini Nano
through AICore, `com.google.mlkit:genai-prompt`, which does take an arbitrary
prompt and does run with no network at all. It was built and it worked.

**It was set aside at the owner's request**, and the reason is worth recording
because it is not a technical one: it needs a multi-gigabyte model downloaded to
the phone, and they did not want that. The device list is also narrow — the
Prompt API reaches recent flagships and nothing else — so on most phones it
would have been a megabyte of dependency doing nothing. The research is not
wasted; it is what let the choice be made knowingly rather than by default.

**So the note is improved by a call to `POST /voice/note`**, which reaches
Telnyx's OpenAI-compatible completion endpoint. What that costs, and none of it
should be discovered later:

- **The transcript leaves the phone.** A sentence a household said out loud
  about its own money is sent to a third party. This is the real price, it was
  paid deliberately, and it is why the route logs error *codes* and never the
  sentence.
- **It costs money per utterance.** Which is why the route is rate limited per
  household rather than per IP — the bound worth having is on the family that
  would be billed.
- **It does nothing offline.** In the basement supermarket this feature was
  designed for, the note is whatever the table wrote.

**And the five-second rule survives, because the call is not in the add path at
all.** That is the entire argument, and it is the same shape as the one against
a server-side parse earlier in this document — which still stands. The row is
written from the phone's own rules, with a note, and the summary is on screen
before anything is sent. The answer is applied only while that sheet is still
open, through the ordinary edit path, and a reply that arrives after it has gone
is dropped: a note changing under somebody who has walked away is worse than no
note. Nobody waits a millisecond for this. A blocking call in the same place
would be forbidden, and the difference is not a matter of degree.

The model's answer has to be **plausibly better, not merely different**. The
server bounds it — four words, forty characters, one line, not an echo — and
the phone applies the rule that matters, because the phone is the side that
still has the transcript: **every word of the answer must be a word that was
actually said, or one inflection away from one.** A model cannot introduce a
shop nobody mentioned, cannot summarise and cannot editorialise. The worst it
can do is nothing.

## Consequences

- **The Add tab item is drawn by hand.** `NavigationBarItem` has no long press
  and cannot be given one from outside: Material applies its own
  `Modifier.selectable` to the item's root, *inside* whatever modifier is passed
  in. Hanging the gesture on `AddIcon`'s pill instead would have worked and been
  worse — the pill is 64×32dp inside a touch target a fifth of the screen wide
  and the full height of the bar, so a long press on the label or on the dead
  space around the pill would silently just select the tab. Almost nothing of
  `NavigationBarItem` was being used for this item anyway; it already overrode
  its colours and drew its own indicator. Every path that selects a tab now goes
  through one `selectTab(route)`, because two copies of the filter reset would
  diverge.
- **Not the Save button on the keypad,** for three reasons: it exists on one
  screen only, so the gesture would stop working the moment you were looking at
  Budget; it is in its unfinished state exactly when you would want to dictate,
  which is the state the screen is in when the app opens in a shop; and
  `Button(enabled = false)` publishes `semantics { disabled() }`, so a long press
  hung on it would be invisible to TalkBack.
- **The long press starts the listen. Nothing about the finger ends it.** The
  first version recorded for exactly as long as the bar was held, and on a real
  phone that is the wrong contract: a hand half out of a pocket does not hold
  still for the length of a sentence, so the lift was not somebody saying they
  had finished, it was a thumb shifting — and the sentence went with it,
  silently. A listen now ends on the recogniser's own endpointing, which is the
  normal path and the one that knows what a finished sentence sounds like; or
  on the sheet's Stop button; or on one of two clocks. Three seconds waiting for
  a voice to start, because nothing beginning means a mis-press or a microphone
  that is not really working, and there is nothing to write — it was five, and
  five is measurably too long to stand holding a phone that is doing nothing
  visible. Fifteen once a voice has begun, which is a safety net against a
  recogniser that never endpoints rather than a limit on the sentence — the
  longest thing this grammar can usefully be told takes about three. The first
  cancels, because nothing was said; the second stops, because something was.

  This also removed the modifier that watched for the lift, which is a gain on
  its own: it reported every pointer-up, an ordinary tab tap included, and could
  not tell whether a long press had ever happened.
- **TalkBack's long-press action starts a listen that no finger will ever end,**
  which the design now takes for granted rather than works around: Stop is a
  real control for everybody, and the clocks catch what nobody stops.
- **A message that asks nothing takes itself away.** "Nothing was heard" with a
  Done button under it makes the phone's failure into the household's chore,
  and the chore costs more than the message is worth. Everything that is a
  report rather than a question — nothing heard, not understood, could not
  save, only half understood, and every way the recogniser can fail — appears,
  is read, and closes itself after about two seconds, with the wordier ones
  given twice that. Nothing is drawn on them that can be tapped, because a
  button vanishing under a thumb reaching for it is worse than no button. What
  asks something stays: the summary, a reverted row with its Undo, and the
  permission request, where the tap IS the point — which is why a recogniser
  refusing for want of the microphone is routed to that state rather than to a
  failure message.
- **`source = 'voice'` has no reader today, and is set anyway.** The column and
  its `CHECK` constraint have allowed the value since `0001_init.sql`, and
  `sync/Mapping.kt` already round-trips it, so it costs no migration. Without it
  there is no way to ever measure how often the parser was right. It is not dead
  code; it is the only evidence this feature will ever leave.
- **The fields ARE the "change it" control.** There is no Popraw button. One
  said only "something here is wrong" and then made you find it again in a
  dialog, when the value that is wrong is already on screen and already the
  thing being looked at — so the amount, the category, the account and date,
  and the note each open `EditTransactionDialog` on a tap, with the whole row
  as the target rather than a glyph on it. The note is tappable while empty,
  because adding one is the main reason to reach for it.

  Nothing marks the rows as pressable, and there is no "Zapisano" over them.
  Both were tried and both were noise: the sheet only ever appears because
  something was just written, so the heading said nothing the figures were not
  already saying, and a pencil on every line turned a receipt into a form. The
  labels survive as `onClickLabel`, so what TalkBack announces is unchanged —
  the glyph was the part that carried no information.

  The dialog opens on all five fields rather than on the one that was tapped.
  Threading a target field through it would mean reshaping an `AlertDialog` that
  two other screens depend on, for a convenience.
- **The summary is a fast path, not the only path.** After it is dismissed the
  row is the top of the History list, where a tap opens the same editor. That
  editor deliberately does not change the kind, where the spoken correction
  does. Both are right: the dialog refuses it because switching invalidates the
  category already chosen, and the spoken grammar allows it because "przychód,
  wypłata" re-chooses both in one breath. Deleting from the editor while the
  summary is open is the same write as Revert, and lands in the same place,
  with Undo still on offer.
- **An undo verb said beside a correction changes nothing.** "nie anuluj, zmień
  na transport" — *do not cancel, change it to transport* — and "nie, cofnij
  250" arrive as the same words with the same verb in the same place, because
  tokenising drops the punctuation that separates them. Rather than pick, the
  grammar refuses both: the sheet says nothing changed, and Revert is a button
  one tap away and unambiguous. Destroying a row is the one thing in this flow
  that a person cannot see coming, so it is the one thing that is never
  inferred.
- **Every write says when it failed.** Not only the add. A revert that threw and
  still reported "Cofnięto" leaves the household believing a row is gone while
  it sits in the shared ledger, which is the same class of failure as a wrong
  row written silently and the worse half of it, because there is nothing on
  screen to notice. All four paths share one in-flight flag as well, which is
  what stops an edit reading a row from before an in-flight revert and writing
  it back with the tombstone cleared.
- **The microphone permission always has somewhere to go.** The system dialog
  the first time; the app's own settings page after that, because Android stops
  drawing that dialog after a refusal or two and a button that silently does
  nothing forever is worse than no button.
- **A second long press dismisses the previous summary unread** and starts a new
  transaction. Dictating twice in a row is a real thing to do, and stacking the
  summaries would mean building a queue for a screen that exists to be glanced
  at. Documented rather than solved.
- **A phone with no recognition service simply has no gesture** — the same way a
  checkout with no `voice.properties` has no call button. The `<queries>` entry
  in the manifest is what makes that answer honest on API 30 and above; without
  it, package visibility hides every recogniser and the feature would hide
  itself, correctly and permanently, on a phone that plainly has one.
- **The Android 14+ recording indicator appears while listening.** Correct, and
  expected, and not a bug.
- **`server/` is no longer untouched, and ADR 0018's claim needs restating.**
  That document says the voice routes are "`SELECT`-only by construction". With
  `POST /voice/note` there are three of them, and the new one is a *stronger*
  case rather than an exception: the other two issue SELECTs, and this one has
  no database binding at all — not `env.DB`, not KV, nothing to build a query
  with. It is a text transform. There is a test that reads `src/note.ts` and
  fails if a database import, an `env.DB`, or a bare SQL keyword ever appears in
  it, because the way a claim like that is lost is one innocuous lookup.
- **It authenticates with the device session, not a new secret.** The assistant's
  URL-token pattern exists for a caller that has no session; this caller is an
  enrolled phone that already holds one. There IS a new secret, but it is on the
  other side: `TELNYX_API_KEY`, the bearer key the route uses to reach
  `api.telnyx.com`. There is no edge binding for inference — the runtime offers
  sqldb, kv, secrets, rate limiters, buckets and actors — so it is an ordinary
  outbound fetch, the same way `fcm.ts` reaches Google. Absent, the route
  answers "keep the note you have" and nothing anywhere reports an error.
- **Behaviour now varies with connectivity**, which it did not before. The same
  sentence can produce one note on a phone with signal and another in a
  basement. That is a real difference and it is the acceptable one: the offline
  answer is the suffix table's, which is the answer this feature shipped with
  and a decent one.
- **The on-device version exists in the history and was set aside deliberately.**
  If the trade is ever revisited: `com.google.mlkit:genai-prompt` takes an
  arbitrary prompt, is minSdk 26, needs no allowlist, and must be pinned to
  `1.0.0-beta2` — beta3 and beta4 carry Kotlin metadata 2.3.0 and will not
  compile until this project moves off Kotlin 2.1.0. It costs about +1.4 MiB
  unminified and a model download the owner did not want.
- **No new Gradle dependency.** `SpeechRecognizer` is framework, the sheet is
  Material3, the gesture is `foundation`. `gradle/libs.versions.toml` did not
  need to change, which is the cheapest possible answer to "is this worth it".

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

So the question ADR 0018 left open is answered by not going there at all. **The
voice routes stay `SELECT`-only. No write-capable route is created, no member
without a device is invented, and no new credential exists.** The evidence is
the absence of a `server/` diff and the absence of a `server/test/` diff in the
change that introduced this feature: "read-only by construction" survived the
one feature most likely to break it.

Recording audio and posting it somewhere to be transcribed was the alternative,
and it loses on every axis that matters here. It costs a second or two of shop
4G in the middle of the five seconds the add path exists to protect; it costs
money per utterance, forever; it cannot work at all in a basement supermarket,
which is exactly where it would be reached for; and it sends a household's audio
to a third party. Telnyx Edge Compute has no transcription of its own, so it
would also mean putting a *second* vendor behind the first.

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
residue is handled by **showing what was understood** rather than by
understanding better, and by refusing rather than guessing:

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
- **Dragging off the item is not a cancel.** A thumb resting on the bar for
  three seconds while somebody speaks will drift, and a silent cancel would
  throw the sentence away with nothing to see, hear or feel. Leaving the item
  delivers, the same as lifting; Revert is the undo, and it can be seen.
- **TalkBack's long-press action starts a listen that no finger will ever end,**
  which is why the sheet has a Stop button rather than only a released finger.
- **`source = 'voice'` has no reader today, and is set anyway.** The column and
  its `CHECK` constraint have allowed the value since `0001_init.sql`, and
  `sync/Mapping.kt` already round-trips it, so it costs no migration. Without it
  there is no way to ever measure how often the parser was right. It is not dead
  code; it is the only evidence this feature will ever leave.
- **The summary is a fast path, not the only path.** After it is dismissed the
  row is the top of the History list, where `TransactionDetailSheet` and
  `EditTransactionDialog` already delete and edit it. Change reuses that dialog
  rather than growing a second editor — and the dialog deliberately does not
  edit the kind, where the spoken correction does. Both are right: the dialog
  refuses it because switching invalidates the category already chosen, and the
  spoken grammar allows it because "przychód, wypłata" re-chooses both in one
  breath.
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
- **No new Gradle dependency.** `SpeechRecognizer` is framework, the sheet is
  Material3, the gesture is `foundation`. `gradle/libs.versions.toml` did not
  need to change, which is the cheapest possible answer to "is this worth it".

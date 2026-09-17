<p align="center">
  <img src="docs/logo.png" alt="" width="96">
</p>

<h1 align="center">Monyx</h1>

<p align="center">
  A shared household budget app for one family. Native Android, backend on
  Telnyx Edge Compute.<br>
  English and Polish interface, switchable in Settings.
</p>

## Screenshots

<table>
  <tr>
    <td align="center" width="16%"><img src="docs/screenshots/overview.png" alt="Monthly summary with account filters, balance and a category breakdown" width="100%"></td>
    <td align="center" width="16%"><img src="docs/screenshots/trends.png" alt="Twelve months of spending as stacked bars, by category" width="100%"></td>
    <td align="center" width="16%"><img src="docs/screenshots/add.png" alt="Adding an expense on the built-in keypad" width="100%"></td>
    <td align="center" width="16%"><img src="docs/screenshots/history.png" alt="Transaction history with search and filters" width="100%"></td>
    <td align="center" width="16%"><img src="docs/screenshots/budget.png" alt="Monthly plan and per-category budgets" width="100%"></td>
    <td align="center" width="16%"><img src="docs/screenshots/settings.png" alt="Accounts and categories in settings" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><b>Summary</b><br>the launch screen</td>
    <td align="center"><b>12 months</b><br>the same card, turned over</td>
    <td align="center"><b>Add</b><br>keypad and calculator</td>
    <td align="center"><b>History</b><br>search and filter</td>
    <td align="center"><b>Budget</b><br>plan and limits</td>
    <td align="center"><b>Settings</b><br>accounts, categories</td>
  </tr>
</table>

The app opens on the summary, and Add is one tap away in the middle of the bar
— or zero from the home screen, where the launcher's long-press shortcut goes
straight to the keypad. It used to open on the keypad itself, which answered
"I just spent something" perfectly and put a blank amount in front of everybody
who had come to look something up instead. Saving lands on the ledger, with the
row just entered on screen as proof it went in.

There is no system keyboard in the add flow: the grid is the amount field, which
is also what makes the calculator possible — `60 +` stays on screen while the
second operand is typed, instead of the running total vanishing the moment you
press an operator.

Committing the expense is a button of its own, the width of the screen, with the
amount already on it. It used to be a filled tick in the corner of the keypad —
exactly where a calculator puts `=`, so the key that ended the entry and the key
that ended the sum were the same shape in the same place. The tick is honestly
`=` now, and when the button cannot save it names what is still missing instead
of sitting there grey.

The keypad puts itself away. Tap a category or the note and it goes; tap the
amount and it comes back, taking the system keyboard with it. It is a quarter
of the screen that means nothing once the figure is typed, and it used to sit
there through the category tap and underneath the note's own keyboard. The
figure is its own way back — it is the largest thing on the screen and it is
what you would reach for to change the number anyway.

The date can be in the future. An expense has already happened, which is true
of a receipt and false of the standing order leaving on Friday — and a
household budget is as much about what is coming as what went.

Beside the account and the date is *Make it repeat*, which hands the half-typed
transaction to the rule editor with everything already filled in. Rent gets
typed by hand once before anyone thinks "this happens every month"; catching
that thought here is the difference between setting up a rule and going to
Settings to set one up. It creates one thing, not two — the rule's first
occurrence is the transaction you were typing.

Accounts and categories are dragged into order in Settings, and the keypad's
grid, the budget list and the breakdown all follow. They were sorting by
`sortOrder` from the first schema; nothing had ever written it.

Tapping the balance turns the card over, and the three figures on it do not
change — the back adds the path, not a second opinion. The line is the month's
balance arriving: payday is the step up, and the long grind down to the next one
is the month being lived. It restarts on the 1st, so the last point on the line
is the number printed on the front; the thirty days reach back into the month
before, drawn as their own run with a break at the turn, because a balance does
not slide from last month's total down to zero overnight. Green above break-even,
red below, cut at the zero line rather than coloured by wherever the month
happens to end — a month that dipped under and recovered says so. Touching the
line answers what the balance was on that evening and what moved on the day;
dragging along it walks the month. Every day is a vertex even when nothing
happened on it, and the line is never smoothed, because a curve fitted between
two points invents balances the household never had.

Above both cards is the account strip, when there is more than one account:
each account a button carrying its own balance, every one of them on to start
with, and tapping one narrows every figure on the screen to it. There is no
"All accounts" button in front of them — with two accounts it was a third button
saying what both of the others being on already said, and it was the first thing
on the screen.

The breakdown card turns over too, from this month's pie to twelve months of
stacked bars — one bar a month, split into the categories that made it up, with
money up the side and the month being shown highlighted. Tapping a bar selects
that month for the whole screen, so the chart is the switcher as well as the
history. The legend below it keeps its rows and changes its number: what each
category costs in an average month, divided by the COMPLETED months in the
window, because a September three days old would otherwise drag every average
down a little further every morning. That month's bar is drawn pale for the same
reason. Tapping a row there hides the category instead of opening it — the whole
question the view exists for is "what does the year look like without the
holiday", and the answer is a shape, so it has to be the same shape with one
colour taken out of it.

One month, an arrow either side, and the same control in the same place on
Overview, History and Budget — showing the same month on all three. Stepping
back to March on the overview and then opening the budget used to show
September: the identical control, in the identical place, disagreeing with the
one you were just looking at. They are not three questions about three months.
They are three views of one: what it cost, what it went on, and what it was
meant to cost.

History is the month, searchable, filtered by account and by category, and
pulled down to sync. Its days carry their own subtotal, and the two days anybody
actually recognises say so: "17 September (today)", "16 September (yesterday)" —
after the date rather than instead of it, because the date is what the eye scans
the list by. Turning on Planned adds what the repeating rules are about to
write, faded and untappable, so the rent leaving on the 5th is visible before it
leaves; it is offered for this month and next, since further out the projection
stops being a fact about money and becomes arithmetic.

Money moved between accounts is a transfer, a row type of its own: it has no
category, it is drawn differently, and it never enters a spending figure
anywhere in the app — moving money is not spending it.

A transaction is tapped to edit it, and the editor holds Delete behind a
confirmation. There used to be
a sheet in front of it whose whole content was two buttons, Edit and Delete —
the tap that opened a menu now opens the thing the menu led to. The Overview's
rows were worse than an extra tap: they all navigated to the same unfiltered
month, so tapping the third and the ninth did the same thing. The editor is the
add screen — the same keypad, the same category grid, the same chips — because
writing a row and correcting one are the same job. It is also where a row says
it is still waiting to sync: a cloud beside the delete icon, which explains
itself when tapped rather than spending a line of the sheet saying so. A row the
server refused keeps its line, because that one changes how you read everything
under it.

Its category grid keeps the parents on screen and opens the chosen one's
children below them, under a heading that says the subcategory is optional —
"Dom" is somewhere people file to and not only a heading, so filing to it is
never more than one tap. The roots never move while a family is open, which is
what makes the second tap safe to skip. The date is a calendar with no floor and
no ceiling — the future included, because the standing order leaving on Friday
has not happened yet either — and no text field to type 03.09.2026 into. It has
a Today button, because a calendar scrolled to March has no other way home.

Budget is a month's plan and its envelopes. The plan is what there is to spend;
under it every category with a limit shows what it has taken out of that limit
and what is left, going red past it, and the card at the top says how much of
the plan is still unassigned. A limit carries forward until it is changed, so a
household sets one once rather than every month. Android permitting, it says
before you go over rather than after.

The arrows are for a neighbouring month. For any other, the title opens a
picker — a year, twelve months under it, and one button back to the present.
The present month keeps a ring in the grid even while another is chosen, so it
always says where now is relative to what is on screen.

Add is the only filled shape in the bottom bar, and it is filled whether or not
it is the tab you are on. A green glyph among four grey glyphs is still a
glyph; this is the thing the app exists for. Hold it instead of tapping and it
takes dictation — see [Voice](#voice).

The app updates itself. A release build asks on launch when a newer version
exists, showing the release notes of every version skipped and its size, and
downloads and installs after a yes — Settings › Advanced can also check on
demand and says which build is installed. Debug builds never offer anything.

Interface English, category names Polish: the names are rows that sync to every
device, so they are data the family owns, not translated copy. Switching the
interface language does not rename anyone's categories. The app itself is called
Portfel on a Polish phone, which is the one piece of the name that IS translated
copy — it is a label on a launcher icon, not a row anybody owns.

Captured on an emulator against a local database of demo data — not the
family's own numbers.

## Layout

```
monyx/
├── server/          Telnyx Edge Compute function (TypeScript)
├── android/         The app (Kotlin, Compose, Room)
├── scripts/         One-off operational scripts
└── docs/decisions/  ADRs — written when something surprised us
```

## Prerequisites

- Node ≥ 22.5 (for `node:sqlite` and `node --test`)
- `telnyx-edge` CLI ≥ 0.5.0 — below that the `sqldb` surface differs
- JDK 17+ and the Android SDK (platform 35, build-tools 35) for the app
- `JAVA_HOME` and `ANDROID_HOME` exported — Gradle, `apksigner` and the release
  script all need them, and macOS's `/usr/bin/java` stub answers "Unable to
  locate a Java Runtime" rather than falling back to anything

## Server

```sh
cd server
npm install
npm test          # 103 tests: sync, auth, budgets, voice, notes, releases
npm run typecheck
telnyx-edge ship  # deploy
```

Bindings live in `telnyx.toml` and are read via
`import { env } from "@telnyx/edge-runtime"` — **never** off the second argument
to `fetch(req, env)`, which type-checks cleanly and is `undefined` at runtime.

### Schema changes

Migrations are numbered and untouchable once applied. A fix is a **new file**,
never an edit to an old one.

```sh
telnyx-edge storage sqldb migrations apply monyx --remote --migrations-dir server/migrations
```

### Creating a household

There is no endpoint that creates a household — enrolment consumes an invite, it
does not bootstrap. Use the script, which seeds the default categories and
accounts and mints the first invite code in one go:

```sh
node scripts/new-household.mjs "Dom"            # Polish seed names
node scripts/new-household.mjs "Home" --lang en  # English seed names
```

`--lang` picks the language of the SEED ROWS only, and only once. Category and
account names are data that syncs to every device; the in-app language picker
changes the interface around them, not the names themselves. That is deliberate
— the family renames these anyway, and a rename must not be undone by somebody
else switching language.

It prints the `household_id` and an invite code valid for 24 hours. Type that
code into the app's onboarding screen. After that, any enrolled phone can mint
further invites from Settings.

## Android

```sh
cd android
./gradlew assembleDebug     # use the wrapper: it pins Gradle 8.11.1, and
                            # AGP 8.7.3 does not support Gradle 9
./gradlew assembleRelease   # signed, R8-minified, shrinkResources on
```

Debug builds carry `applicationIdSuffix ".debug"` so a debug and a release build
coexist on one phone. Without it the eventual release install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way through is an uninstall
that destroys local data.

`versionCode` is derived from `git rev-list --count HEAD`. **Android refuses any
APK whose versionCode is lower than the installed one**, so a bad release cannot
be rolled back without an uninstall. Settings shows `versionName (versionCode)`
so "which build do you have?" is answerable over the phone.

### Publishing an update

```sh
node scripts/release.mjs --notes "Po zapisaniu otwiera się lista transakcji."
node scripts/release.mjs --notes-file notes.txt --dry-run   # every check, no upload
node scripts/release.mjs --notes-file notes.txt --skip-build  # reuse the built APK
```

Needs `TELNYX_API_KEY`, or a `~/.telnyx-edge/config.toml` from `telnyx-edge
login`; `--db` names a database other than `monyx`.

Builds the release, checks it is signed with the release certificate, uploads it
to the private `monyx-releases` bucket, reads it back and compares sha256, and
only then writes the `app_releases` row. Installed release builds offer it on
their next launch; Settings › Advanced can check on demand. **The tree must be
committed first** — versionCode is the commit count — and a versionCode at or
below the published latest is refused, because no phone could install it.
Withdraw a release by deleting its row. See
[ADR 0020](docs/decisions/0020-the-app-updates-itself-and-asks-first.md).

### The release keystore

`android/keystore.properties` (git-ignored) points at `~/.monyx/monyx-release.jks`.

The release script pins that certificate's SHA-256 (`RELEASE_CERT_SHA256` in
`scripts/release.mjs`) and refuses to publish anything signed with another key,
so rotating the keystore means editing that constant in the same commit.

**Back up both the keystore and its password somewhere that survives a laptop
dying.** Android refuses an update signed with a different key than the installed
build. Losing the keystore means every phone must uninstall and reinstall,
losing anything not yet synced.

## Languages

English is the source and the default (`res/values/`); Polish is a translation
(`res/values-pl/`). Settings › Language offers System default, English, Polski.

Adding a language means three places, and missing any one of them fails quietly
in a different way:

1. `res/values-<tag>/strings.xml` — miss it and the language cannot be shown.
2. `res/xml/locales_config.xml` — miss it and Android's own per-app language
   list will not offer it.
3. `Locales.SUPPORTED` in `android/app/src/main/java/com/monyx/Locales.kt` —
   miss it and the in-app picker will not list it.

Selection goes through `AppCompatDelegate.setApplicationLocales`, not a
preference of our own. On API 33+ that forwards to the framework `LocaleManager`
so the choice also appears in system settings; below 33 AppCompat stores it
itself, which is what the `autoStoreLocales` meta-data in the manifest switches
on. This is why `MainActivity` is an `AppCompatActivity` and the XML theme
parent is an AppCompat one — a plain `ComponentActivity` would store the
preference and then ignore it.

**Money and dates follow the interface language; the currency and the timezone
do not.** Polish prints `5 127,00 zł` (non-breaking space, comma decimal),
English `5,127.00 zł`. But złoty is złoty in both, and `Dates.ZONE` stays
`Europe/Warsaw` because that is where the household lives, not what it reads.
The formatters in `Money.kt` are cached against the locale that built them
rather than held in a `val`: switching language does not restart the process,
and a formatter built at class-init would keep printing the old language.

## Scheduling and backup

Nothing in this repository runs on a schedule. `POST /cron/daily` exists and is
guarded by the `CRON_SECRET` secret; it sweeps the budget alerts the push path
cannot see — a month boundary, a limit revised downward, a delivery that failed
while a phone was offline. Something outside the function has to call it, and
today nothing does.

Backups are manual for a harder reason: `sqldb export` is CLI-only, with no REST
equivalent, so the function cannot dump its own database.

```sh
telnyx-edge storage sqldb export monyx --remote --output monyx-$(date -u +%Y%m%d).sql
curl -X POST "$MONYX_API_URL/cron/daily" \
  -H "x-cron-secret: $CRON_SECRET" -H 'content-type: application/json' \
  -d "{\"last_backup_at\": $(date -u +%s)000}"
```

Reporting `last_backup_at` is what makes a missed backup visible: the app reads
it and Settings warns when it is more than three days old. An invisible backup
is not a backup.

**Do not treat the phones as the backup.** `epoch` and the `households` row live
on the server, so if the database is lost no phone can authenticate to re-upload
its replica. The data survives locally, but recovery means seeding a fresh
household and re-enrolling every device by hand.

## Voice

Two different things share the word, and they do not touch each other. **Adding
by voice happens on the phone**, on-device, with no network and no Telnyx route
of any kind. **Asking about the month happens over the line**, through the call
button, and that path only ever reads.

### Adding by voice — on the phone

Hold the Add tab in the bottom bar until it buzzes, then say "dodaj 200 na
transport" or "add 200 to Transport". Let go whenever you like — the phone
stops listening when the sentence ends, not when the finger does. The row is
written and a sheet shows the row it wrote — amount, category, account, day and
note. Every one of those is a tap into the editor, so the field that is wrong is
also the way to fix it, and there is no separate "edit" button. **Anuluj** takes
the row back, with an Undo of its own if that was the mis-tap.

"150 zł na zakupy w Biedronce" saves 150,00 against Zakupy spożywcze with the
note *Biedronka*: once the amount, the date and the category have claimed their
words, whatever is left over is the note. It is bookkeeping rather than a list
of shops — and "dodaj 200 na transport" has only filler left over, so it gets
none. A preposition of place is dropped along with the case ending it put on the
word after it, from a small table of the endings shops actually have; anything
the table does not know is written down exactly as it was heard.

It can be corrected by voice too: hold the microphone on the sheet and say
"anuluj", "ma być 250", "zmień kategorię na zakupy". A sentence about the
purchase rather than about one of its fields becomes the note — "te zakupy były
w lidlu" writes that down and leaves the category alone — and "notatka …"
anywhere, including in the first breath, is always a note.

Say nothing and it gives up after three seconds. Anything it could not do — not
understood, nothing heard, no speech pack for the language — says so and then
takes itself away; there is no message here that has to be dismissed by hand.

**The transaction never leaves the phone to be understood.** Speech is Android's
own `SpeechRecognizer`, asked to prefer the on-device pack; the parse is a few
hundred lines of Kotlin in `android/app/src/main/java/com/monyx/voice/`, pure
and unit-tested on the JVM. It works with the phone in flight mode.

One thing does leave: after the row is saved, the transcript is sent to
`POST /voice/note` to see whether the note could be written better. It is worth
being plain about the price — a sentence the household said out loud goes to a
third party, it is billed per call, and it does nothing offline. What makes it
acceptable is that it is not in the add path at all: the row is written and the
summary is on screen before the call is made, the answer is used only if it
arrives while that sheet is still open, and a phone with no signal gets the note
its own rules wrote.
[ADR 0019](docs/decisions/0019-the-microphone-is-the-phones-not-the-lines.md)
argues all of it, including the on-device model that was built first and set
aside because it needed a multi-gigabyte download.

What it cannot finish, it does not write: a sentence with an amount but no
category opens the keypad with the amount already in it, and two transactions in
one breath are refused rather than blended into one wrong row. A tie between two
category names is a refusal rather than a guess, because a wrong row syncs to
everybody. A phone with no recognition service simply has no gesture.

`RECORD_AUDIO` is asked for on the first long press and never before it, which
is the one deliberate exception to the rule in `NotificationPermission.kt` that
forbids a permission dialog in the add path. The keypad path is untouched.

### Asking about the month — over the line

The household can be asked about over the phone. The assistant itself — model,
voice, transcription, instructions, and the number it answers — is configuration
held on Telnyx AI Assistants and is not in this repository. What is here is the
only part it cannot do alone: two read-only routes, because `env.DB` is bound to
the function and there is no public SQL API.

| Route | When | Returns |
|---|---|---|
| `POST /voice/context?t=…` | call setup | one XML snapshot of the month, straight into the prompt |
| `POST /voice/digest` | mid-call | the same snapshot again |
| `POST /voice/note` | after a voice entry saves | a better note for it, or nothing |

The third is not part of the assistant at all — it belongs to voice entry on the
phone, carries a device session rather than a shared secret, and touches no
database. It is listed here because it is the other place this codebase sends
text to a model.

**The allowlist is the only gate.** Caller ID is not a credential, so a spoofed
number reaches the household's finances. That is a deliberate trade and
[ADR 0018](docs/decisions/0018-the-assistant-gets-a-window-not-a-key.md) argues
it: a PIN and a preload are mutually exclusive, because a secret placed in a
prompt cannot be withdrawn from it, and the preload is what makes the first
question of a call cost nothing.

Three secrets, none of them in this repository:

- `VOICE_ALLOWLIST` — JSON `[{msisdn, household_id, name}]`. Who may call and
  which household they reach. A secret rather than a table because phone numbers
  are personal data. It fails closed: a value that will not parse admits nobody.
- `VOICE_CONTEXT_TOKEN` — travels in the call-setup URL, which is the only place
  the assistant's webhook field allows a secret.
- `VOICE_TOOL_SECRET` — the `x-voice-secret` header on the tool webhook, held on
  the Telnyx side as an integration secret.

`POST /voice/note` needs no secret of its own. It reaches the model through
`env.TELNYX`, the client the `[telnyx]` binding in `telnyx.toml` puts on the
environment; the runtime's auth proxy substitutes the real bearer as the request
leaves the pod, so the function is authenticated as itself and there is no
account-wide key anywhere in this repository, in a secret store, or in memory.
Nothing to rotate and nothing to leak. Absent the binding, the route answers
"keep the note you have" and nothing breaks.

Two of the three routes are `SELECT`-only and stayed that way when adding by
voice was built. The third, `POST /voice/note`, is stronger still: it has no
database binding at all, because it is a text transform, and a test fails if a
database import ever appears in it. What ADR 0018 left open — a
server-side write, which needs a `created_by` member with no device — is still
open, and is now unnecessary. See
[ADR 0019](docs/decisions/0019-the-microphone-is-the-phones-not-the-lines.md).

**The app has a call button** — a floating action button, bottom right, in
Telnyx green, on every tab but the keypad, where the bottom right is already the
save button. It is a different feature from the one above: it asks about the
month, and it does not write to it.

It fires `ACTION_DIAL` rather than `ACTION_CALL`: dialling fills the number in
and lets the person press call themselves, where calling would need the
`CALL_PHONE` permission to place an outgoing call from under their thumb, to
save one tap. The number comes from `BuildConfig.ASSISTANT_NUMBER`, read from an
untracked `android/voice.properties`:

```properties
assistantNumber=+00000000000
```

Absent, the constant is empty and the button hides itself, so a clean checkout
builds and simply has no call button rather than one that dials nowhere. A phone
number is personal data and does not belong in the repository, which is the same
reason `VOICE_ALLOWLIST` is a secret.

To check the routes are alive without placing a call:

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$MONYX_API_URL/voice/context?t=wrong" \
  -H 'content-type: application/json' -d '{}'          # expect 401
curl -s -X POST "$MONYX_API_URL/voice/digest" \
  -H "x-voice-secret: $VOICE_TOOL_SECRET" -H 'content-type: application/json' \
  -d '{"ticket":"nope"}'                                # expect session_expired
```

## Restore runbook

The middle step is why `epoch` exists. Skipping it is worse than not restoring
at all: `next_seq` rewinds below every device's cursor, all four phones pull
nothing forever, and the household silently splits into four divergent
single-user apps while every screen still looks perfectly normal.

1. **Restore the export.** A SQL script over `execute --remote --file` is capped
   at roughly 4 MiB, so split a large dump into chunks:
   ```sh
   node scripts/restore.mjs backups/monyx-YYYYMMDD.sql
   ```
2. **Bump the epoch.** Every device notices on its next pull, resets its cursor
   and re-pulls from scratch.
   ```sh
   telnyx-edge storage sqldb execute monyx --remote \
     --command "UPDATE households SET epoch = epoch + 1 WHERE id = ?" --param <household_id>
   ```
3. **Ask ONE recently-online phone** to tap *Re-upload everything* /
   *Wyślij wszystko ponownie* in Settings. Whatever the restore lost is still on the phones; this is what sends
   it back. One phone covers everything — asking all four means four times the
   pushes serialising on the same `households` row and every phone then pulling
   four redundant copies of every row. Ask a second phone only if rows are still
   missing.

Test this with two phones already syncing, against a realistically sized dump —
not ten rows against a fresh install. The `epoch` failure only appears when live
cursors exist, and the 4 MiB ceiling only appears at real size.

## What is deliberately not here

No multiple currencies, no debts or savings goals, no bank integration, no
Excel export, no iOS, no web.

# forgetmenot — specification

This is the single source of truth for forgetmenot. It replaces `AUDIT.md` and `BUILD.md`,
which have been deleted; everything in them is either restated here as current fact or
preserved verbatim in the appendix.

Sections 1–9 describe the app **as it is now**, and every factual claim in them was read
from the source rather than carried over from the retired documents — those had drifted,
which is part of why they were retired. Where this file and the code disagree, the code is
right and this file is a bug.

The appendix is different in kind: it is the dated historical record, left exactly as it was
written. Entries there were true on their date and may not be true now. Its value is the part
that cannot be recovered from the code — the findings that were investigated and rejected,
the fixes later superseded, and the wrong theories that cost real time. Add to it; do not
rewrite it.

## Contents

1. [What forgetmenot is](#1-what-forgetmenot-is)
2. [Data model and storage](#2-data-model-and-storage)
3. [Sync and conflict resolution](#3-sync-and-conflict-resolution)
4. [Reminders and notifications](#4-reminders-and-notifications)
5. [Home screen widgets](#5-home-screen-widgets)
6. [Platform integrations: what the app asks of Android](#6-platform-integrations-what-the-app-asks-of-android)
7. [Theming, accessibility and interaction](#7-theming-accessibility-and-interaction)
8. [Building, signing and releasing](#8-building-signing-and-releasing)
9. [Known limitations and deliberate non-goals](#9-known-limitations-and-deliberate-non-goals)
- [Appendix: decisions and their history](#appendix-decisions-and-their-history)

---

## 1. What forgetmenot is

forgetmenot is an offline-first Android app for keeping in touch with people you care about.
You add someone, say roughly how often you want to reach out to them, and tap a button each
time you actually do. The app does the arithmetic and sorts everyone into who is overdue, who
is coming up, and who you spoke to recently. It is a native Kotlin and Jetpack Compose app; the
Android application id is `com.hazia.forgetmenot` (`android/app/build.gradle:14`).

forgetmenot was a Capacitor WebView app (JavaScript UI, Kotlin plugins) until phase P7 of its port.
That web build is not part of this repository; Kotlin comments of the form "Port of www/js/…"
name files of it.

### The core model

A **friend** is one JSON record (field table in section 2). `cadenceDays` defaults to 90 and
is the one number the whole app is built around: how many days you want to let pass between
check-ins with this person. A missing or non-positive cadence reads as that default everywhere,
through `cadenceOf()` (`Classify.kt:38-39`) and `DEFAULT_CADENCE_DAYS` (`Config.kt:63`).

`contacts` is the log of reach-outs. It grows only when you deliberately record one: the Log
button on a home row logs today, and holding that button opens a date picker for a reach-out
you had days ago (`FriendRow()`, `HomeRow.kt:133-137`). The detail screen has both as buttons,
"Log today" and "Earlier…" (`LogActions()`, `DetailScreen.kt:307`), lists the logged
reach-outs with a way to remove a wrong one (`HistoryCard()`, `DetailScreen.kt:459`), and says
when you were last in touch (`HeaderBlock()`, `DetailScreen.kt:285`). Writing a note does not
touch it — a note is memory, not proof of contact: a friend added with a first note is saved
with an empty log (`AddForm.kt:213-215`). `notes` is free text with a timestamp, filtered on
the detail screen by this month / this year / all time (`NoteFilter`, `DetailFormat.kt:28`).

`classify()` (`Classify.kt:51-63`) turns a friend into the one derived value everything else
reads, a `Classification` of days, pct, section and code. With no logged contact it returns
0 days, pct 0, section `RECENT` and code `new` — a new friend is a fresh start, not infinitely
overdue. Otherwise `pct = minOf(1.0, days / cadence)`, and the section is `RECENT` below 0.33,
`COMING` below 1, and `THINKING` at or past 1. A paused friend is forced into `RECENT`. The
same `pct` draws the ring around the avatar, so the ring filling up and the friend moving up
the home list are one fact shown twice; the three sections are titled "Been a while", "In
between" and "Recently in touch" (`HomeModel.kt:30-32`).

**Tags** are cadence presets, not just labels: each carries its own `cadenceDays`, and picking
one on the add screen copies its cadence onto the form (`toggleTag()`, `AddForm.kt:127-130`);
dropping it again leaves the cadence as it is. A first install seeds three (`DEFAULT_TAGS`,
`Config.kt:78-82`): Family 30 days, Close friends 14, Friends 60. They are planted only when the
folder has no tag index at all, so a user who deletes every tag on purpose does not get them
back (`seedIfEmpty()`, `Records.kt:208-220`). Cadence itself is picked from six presets — 7,
14, 30, 90, 182, 365 days (`CADENCE_PRESETS`, `Config.kt:54-61`) — or typed as
days/months/years in the same sheet.

**Groups** are a separate record type with `memberIds` and `events` (`Group`,
`Models.kt:115-121`). An event is a title, a date and a recurrence of `once`, `monthly` or
`yearly` (`EVENT_RECURRENCE`, `Config.kt:27-31`). Deleting a group leaves its members alone
(`deleteGroup()`, `Records.kt:195-200`).

Archiving is the only way to remove a friend from view short of deleting them. An archived
friend stays in storage and disappears from every list, reminder and widget, because they all
work from `activeFriends()` (`Records.kt:122-123`); Settings → Archived friends restores them,
or deletes them permanently (`ArchivedSheet()`, `ListSheets.kt:154-185`).

### How the code is laid out

There is one Gradle module, `:app` (`android/settings.gradle:1`), and all of its source is
under `android/app/src/main/java/com/hazia/forgetmenot/`, split by what may depend on what:

- `domain/` — pure Kotlin with no Android imports: the record models (`Models.kt`), the
  constants (`Config.kt`), `classify()`, dates, merging (keeping both copies' logs on a
  conflict), reminder arithmetic (when check-ins fall and which share a notification), text
  (ids, vCard/iCalendar), share text, and backup validation. These are what most unit tests
  exercise directly.
- `data/` — the storage layer: `CachedStore.kt` (the in-memory cache and its ordered write
  queue), `SafFolder.kt` and `FolderFs.kt` (the Storage Access Framework folder behind it),
  `StoragePaths.kt` (key to file), `Records.kt` (typed read/save/delete), `Conflicts.kt` (the
  sync-conflict scan and resolution), `SyncSession.kt` (start, refresh, adopting a folder) and
  `Backup.kt`.
- `reminders/` — the scheduler, the exact-alarm check, the notification receiver and its
  buttons, and the boot receiver that re-arms the saved plan.
- `integrations/` — what the app asks of other apps: the contacts provider, the Photo Picker
  and avatar decoding, message links, and the share and calendar intents.
- `widget/` — the three Glance home-screen widgets and the summary they read.
- `ui/theme/` — colours, sizes, type and the theme tokens (section 7).
- `ui/screens/<name>/` — one package per screen: `home`, `detail`, `add`, `settings`, and
  `groups` (which also holds the group-detail screen and its three sheets).
- `ui/sheets/` — the shared overlay sheets and their machinery: the sheet frame, the question
  sheet, toasts and undo, icons, the avatar ring, and the conflict sheet (`ConflictsSheet.kt`,
  not to be confused with `data/Conflicts.kt`).

Wiring is manual dependency injection. `AppContainer` (`AppContainer.kt:22-30`) is one
instance per process holding the store, records, reminder scheduler, widget pusher, conflicts,
backup and sync session as lazy members, reached through `AppContainer.from()`
(`AppContainer.kt:64-67`) rather than a custom Application class, so broadcast receivers and
widgets that run without the activity get the same objects. Its `started` (`AppContainer.kt:52-59`)
is the cold-start load, run once per process; the conflict scan follows it in the background.

`MainActivity` (`MainActivity.kt:38`) is a plain `ComponentActivity` and the only activity. It
holds the system splash until that load is done (`installSplashScreen()`,
`MainActivity.kt:54-56`), then sets the Compose content: the theme, the navigation host and
the one app-wide toast. Every `onResume()` (`MainActivity.kt:114-123`) reschedules the
reminders and pushes the widgets, and `onNewIntent()` (`MainActivity.kt:126-130`) takes a
widget's `forgetmenot://friend/<id>` while the app is running (`launchMode="singleTask"`).

`ui/AppNavHost.kt` is the navigation graph, built with Navigation Compose and typed routes
(kotlinx.serialization): six route classes (`AppNavHost.kt:27-33`), one `composable<…>` line
per screen (`AppNavHost.kt:66-74`), and `friendIdFromUrl()` (`AppNavHost.kt:43-48`), which
checks a deep link's id before the graph navigates to it. Each screen's route class and
`composable<…>` line is reserved, so a change to one screen edits only its own line, and
changes to two screens never touch adjacent lines.

The tests are JVM unit tests under `android/app/src/test/`, mirroring the main packages, and
run by `testDebugUnitTest`. Pure logic is tested directly; screens, sheets, widgets and the
scheduler run under Robolectric, and the palette screenshots in `ui/theme` are recorded and
compared by Roborazzi against the PNGs committed under `src/test/screenshots/`. The `spec/`
tests check this document: `SpecCitationsTest` resolves every `file:line` citation in sections
1-9, and `BuildHygieneTest` checks the manifest and that nothing of the web build is left
(`nothingNamesTheOldRuntime()`, `BuildHygieneTest.kt:43-64`).

### Screens

Six screens, one route each in `ui/AppNavHost.kt`; home is the start destination:

- `HomeRoute` — search, the three sections "Been a while" / "In between" / "Recently in touch"
  (or, with Smaller home on, the three people most worth a hello and "Show everyone"), a per-row
  Log button, the Groups and Settings buttons and the add-friend FAB; also the no-folder,
  read-only and sync-conflict banners and pull-to-refresh.
- `FriendRoute(id)` — one friend: avatar ring, last in touch, tags, Log today / Earlier…, the
  Message / Plan something / Share row, what to ask next time and how you met, birthday,
  anniversary and the friend's own dates (with "Mark as wished" on the birthday), gift ideas,
  cadence, the pause switch and pause-until date, any snooze, the reach-out history, notes with
  their filter chips, and Archive.
- `AddRoute(editId)` — adding and editing both; a non-null `editId` is the friend being edited.
- `SettingsRoute` — theme, Smaller home, recent changes, archived friends, quiet mode,
  notification, exact-alarm and battery status, tag check-in defaults, sync folder and
  conflicts, backup export and import, version.
- `GroupsRoute` — the list of groups with a one-line next-event teaser.
- `GroupDetailRoute(id)` — members, events, rename, delete.

The back stack is the navigation library's, not hand-wired: each forward move is a
`navigate()`, and back, from the system gesture or a screen's own back button, pops it. From
home that leaves the app. The stack reproduces the web build's back map by construction,
because groups is only entered from home, group detail only from groups, and add from home or
from the friend being edited (`AddDestination.kt:18-22`, `GroupsDestinations.kt:15-17`). The
one place a real stack does better is detail: a friend opened from a group's member list goes
back to the group (`FriendDestination()`, `FriendDestination.kt:16-27`). Saving a new friend
pops back to home; saving an edit returns to its friend. A widget's deep link navigates to the
friend with `launchSingleTop`, so a second tap does not stack the same screen twice
(`AppNavHost()`, `AppNavHost.kt:60-64`).

The add form is seeded once per visit and kept in a ViewModel scoped to that back-stack entry
(`AddVisit`, `AddDestination.kt:15-16`), so recomposition, rotation, a resume or a resolved
conflict never clobber half-typed fields; a new visit is a new entry with a freshly seeded form.

Everything else that looks like a screen is an overlay sheet drawn on top of the current one,
and none of them is a route. Each is a Material modal bottom sheet with a heading and a Close
button, framed by `AppSheet()` (`SheetCore.kt:96-117`), and lives in `ui/sheets/`: cadence
picker, log-date picker, add a date, reach-out kind, add-note, theme, tag defaults, archived
friends, recent changes, sync conflicts and contacts import, plus the question sheet that
confirms and prompts (section 7). The onboarding prompt lives with home, and the three group
sheets (group edit, group members, group event) with the groups screens, because nothing else
opens them (`GroupSheets.kt:57-60`).

### Constraints that are not negotiable

**No network, at the OS level.** `android/app/src/main/AndroidManifest.xml` declares no
`android.permission.INTERNET`, and says so where the permission would be
(`android/app/src/main/AndroidManifest.xml:26-30`); `BuildHygieneTest` fails the build if one
appears. Without it the app is physically incapable of opening a connection, and the network
security config blocks cleartext anyway (`network_security_config.xml:8`). Fonts are bundled
in `res/font` (`Fredoka`, `Type.kt:20`, and `NunitoSans`, `Type.kt:25-28`) with no
downloadable-fonts provider, and every icon is a vector path built in code (`AppIcon`,
`Icons.kt:22`). The `wa.me` / `t.me` / `signal.me` URLs from `channelUrl()`
(`MessageLinks.kt:11-20`) are handed to another app with an intent; this app never fetches
them. `android:allowBackup="false"` keeps the data out of Google Drive and `adb backup` too
(`android/app/src/main/AndroidManifest.xml:58-64`).

**The user owns the data, in a folder they choose.** There is no app-private database. Every
write updates an in-memory cache at once and is queued to a file in a Storage Access Framework
folder the user picks (`CachedStore.write()`, `CachedStore.kt:67-71`) — `friends/<id>.json`,
`tags/<id>.json`, `groups/<id>.json` plus four index/settings files (`RECORD_DIRS` and
`INDEX_FILES`, `StoragePaths.kt:21-32`), and each friend's photo in `avatars/<id>.b64`
(`avatarPath()`, `StoragePaths.kt:49`). The folder comes from the system folder picker
(`pickContract()`, `SafFolder.kt:42`) and the app keeps a persistable grant to it
(`SafFolder.kt:83`). The intended folder is one a Syncthing fork already watches, which is how
the same data reaches a second device. Until a folder is picked nothing persists. Sections 2
and 3 cover the storage layer and conflict handling.

**One Gradle build, and nothing generated is committed.** The whole app is the `:app` module,
built by `./gradlew` from `android/`; there is no second toolchain and no web assets to stage.
Gradle output and machine-local settings are gitignored, and everything else under `android/`
is hand-written (`.gitignore:12-18`). The only committed files a tool drew are inputs, not
build output: the widget picker previews, redrawn by hand with `tools/widget-previews.py`
(`tools/widget-previews.py:2-5`), and the Roborazzi goldens that verify mode compares against.

**Native floor is API 30.** `android/variables.gradle` sets `minSdkVersion = 30`,
`compileSdkVersion = 36` and `targetSdkVersion = 36` (`android/variables.gradle:4-6`), which
`android/app/build.gradle` reads (`android/app/build.gradle:11-16`).

### What it deliberately is not

There is no account, no sign-in and no server — nothing in the codebase has a concept of a
user identity. There is no analytics, telemetry, crash reporting or remote configuration; the
missing INTERNET permission makes all of that unimplementable rather than merely absent. There
is no sync of its own: syncing is delegated to whatever the user already runs over the folder
they picked. Archiving hides rather than deletes; a question (`AskState`, `AskSheet.kt:42-48`,
section 7) guards only what cannot be undone — deleting a friend for good
(`ListSheets.kt:176`), deleting a group (`GroupSheets.kt:259-263`), replacing this device's data
with a backup (`SettingsActions.kt:65`) and keeping one side of a conflict
(`ConflictsSheet.kt:101`); and conflicting copies of a friend, tag or group are settled by edit
time or by the user, never dropped (index files are union-merged and avatar and settings
conflict copies are dropped automatically — section 3). Archiving a friend
(`archiveFriend()`, `DetailActions.kt:97-109`), deleting a tag and deleting a group event
(`deleteEvent()`, `GroupDetailScreen.kt:86-97`) act at once and go on the recent-changes list
(`RecentChanges`, `Feedback.kt:83-84`; section 9), so a slip is reversible without a dialog in
front of every tap. Deleting a tag removes it from every friend and leaves their cadence as it
was (`deleteTag()`, `Records.kt:164-178`); undoing it puts the tag back on each of them
(`deleteTagUndoably()`, `ListSheets.kt:52-69`).

The two permission asks are queued behind the home screen, not in front of it: home starts the
onboarding queue once per process, after it is first up (`HomeScreen.kt:231-239`), and each
step — sync folder, then notifications (`OnboardingStep`, `Onboarding.kt:27-40`) — checks its
own current status, skips itself if already satisfied (`onboardingQueue()`,
`Onboarding.kt:43-54`), and is marked seen however it is dismissed, so it is asked once per
install. Settings keeps a tap-to-fix row for each, and is the only place exact alarms are asked
for (`SettingsScreen.kt:189`; section 4): with one nudge per step, a few hours of drift does not
earn a third prompt in a row.

Until a folder is chosen nothing is saved, so home shows a banner saying so, which opens the
picker (`HomeScreen.kt:290-294`). It checks on each render and resume until it has seen a
folder, then stops asking for the rest of the process (`folderSeen`, `HomeModel.kt:114-115`).

---

## 2. Data model and storage

### Records

There are three record types — friend, tag, group — plus one settings object. Each lives in its
own storage key; no record contains another, and cross-record references are always by id. The
models are `@Serializable` data classes in `domain/Models.kt`, whose property names are the JSON
field names, declared in the table's order so an encoded record reads like one the web build
wrote. They are decoded through `DomainJson` (`Models.kt:30-34`): unknown fields are ignored,
defaults are encoded, and a missing or `null` list decodes to empty (`coerceInputValues`), so no
reader needs a `f.contacts ?: emptyList()` guard even for a record from another device or an
older build.

A **friend** is created by the add form's `save()` (`AddForm.kt:167`) or by
`importContacts()` (`ContactsImportSheet.kt:54`), and every save goes through `saveFriend()`
(`Records.kt:127`). A save never replaces the stored JSON outright: `mergeOver()`
(`Records.kt:81`) lays the encoded model over the cached record, so a field this build does not
know (written by a newer one) keeps its value and its place in the file.

| field | type | meaning |
|---|---|---|
| `id` | string from `uid()` | primary key; also the filename and the storage-key suffix |
| `name` | string, non-empty | the add form refuses to save without one ("Give them a name first") |
| `tags` | array of tag ids | may be empty; ids, not names |
| `cadenceDays` | number | how often you want to reach out; the add form and contacts import write `90` by default. Read through `cadenceOf()` (`Classify.kt:39`), which falls back to 90 for a missing or non-positive value; a numeric string such as `"30"` reads as its number (`JsNumberIntSerializer`, `Models.kt:141`). Left out of the file rather than written as `null`, since import rejects a `cadenceDays` that is present and not positive |
| `paused` | boolean | stops check-in nudges and shows a moon instead of a day code; birthday and anniversary alerts still fire |
| `pausedUntil` | `YYYY-MM-DD` or `null` | the same pause, ending that morning (`isPaused()`, `Classify.kt:44`); set on the detail screen |
| `askNext` | string or `null` | "Next time, ask about…": shown on the detail screen, edited by tapping it, and used as a check-in nudge's body |
| `howWeMet` | string or `null` | free text from the add/edit form, shown on the detail screen |
| `dates` | array of `{ id, label, dateISO, yearly }` | the friend's own dated entries (an interview, a move); each gets a reminder (section 4) |
| `snoozedUntil` | `YYYY-MM-DD` or `null` | set by "Next week" on a nudge: holds the next check-in until that morning, leaves the cadence and the classification alone |
| `archived` | boolean | drops the friend out of every list, widget and reminder (`activeFriends()`, `Records.kt:123`) |
| `phone` | string or `null` | free text, not normalised; `channelUrl()` (`MessageLinks.kt:11`) strips it to digits per channel |
| `email` | string or `null` | |
| `birthday` | `YYYY-MM-DD` or `null` | only month/day are used; a contact with no birth year is stored as `1900-MM-DD` (`birthdayToISO()`, `Text.kt:72`) |
| `anniversary` | `YYYY-MM-DD` or `null` | same treatment as `birthday` |
| `messageChannel` | `'sms'`, `'whatsapp'`, `'telegram'`, `'signal'`, `'email'` or `null` | preferred app for the Message button (`MESSAGE_CHANNELS`, `AddForm.kt:24`) |
| `avatar` | data URI or `null` | in memory always `data:image/jpeg;base64,…`; on disk a marker — see below |
| `contacts` | array of `{ id, date, kind? }` | one entry per logged reach-out; `date` is a full ISO timestamp, `kind` an optional `call`, `meet` or `text`, left out of the file when unset |
| `notes` | array of `{ id, date, text }` | free text; never affects classification |
| `gifts` | array of `{ id, text, addedAt }` | gift ideas, added and removed on the detail screen (`addGift()`, `DetailActions.kt:37`) and named in the birthday reminder; `addedAt` is an ISO timestamp, and an epoch-ms number from an earlier native build is read as that instant (`JsIsoTimestampSerializer`, `Models.kt:163`) |
| `createdAt` | epoch ms | stamped by `saveJson()` (`Records.kt:98-103`) on the first friend save that lacks it; the anchor for a never-logged friend's first nudge (section 4) |
| `updatedAt` | epoch ms | stamped on every save (`Records.kt:102`); decides record conflicts (section 3) and labels their two sides |

A record that cannot be decoded at all (a non-numeric `updatedAt` from a foreign writer, say) is
logged and read as absent by `decode()` (`Records.kt:66-75`), so it drops out of the lists rather
than failing them; its JSON stays in the cache and on disk untouched. Decoding is memoised per
cached value, so an unchanged record is not decoded again on every recomposition.

A malformed `pausedUntil` or `snoozedUntil` in a backup is dropped to `null` rather than refusing
the file (`validateRecord()`, `Validate.kt:68-71`).

`contacts[]` is deliberately separate from `notes[]`: writing a note is not evidence of
contact, so a friend added with an opening note still classifies as "new" (`AddForm.kt:211-213`).
Entries are prepended (`addContact()`, `Classify.kt:33`), but nothing depends on list order —
`latestContact()` (`Classify.kt:18`) scans the whole list by date, so a back-dated log sorts
correctly wherever it lands. The back-dated log anchors the chosen day at noon local time
(`noonTimestamp()`, `FriendSheets.kt:168`) so a timezone offset cannot move it to the
neighbouring day, and the picker's default and maximum are today's *local* date
(`LogDateSheet()`, `FriendSheets.kt:177-210`; `localDateISO()`, `Dates.kt:48` for the stored
day strings), since the UTC timestamp is a different calendar day for part of every day.

A reach-out may carry a **kind**, `call`, `meet` or `text` (`CONTACT_KINDS`,
`Config.kt:47`); `addContact()` keeps only those, and the backup validator drops any
other value without dropping the reach-out. Logging never asks for it, so one tap stays one tap:
the log-date sheet offers it as optional chips (`KindChips()`, `FriendSheets.kt:221`), the offer
after Message logs `text` (`offer()`, `HomeIntegrations.kt:35-43`), and any history row can set
or change it afterwards (`KindSheet()`, `FriendSheets.kt:233`). The detail header then reads
"Last in touch 3 days ago, in person" (`lastInTouchLine()`, `DetailFormat.kt:44`). A kind changed
on two devices at once follows the winning copy: `mergeFriendLogsJson()` (`Conflicts.kt:71`)
matches entries by id and does not compare their fields.

A **tag** is `{ id, name, cadenceDays, updatedAt }` (`Tag`, `Models.kt:97`; saved by `saveTag()`,
`Records.kt:157`). Picking one copies its `cadenceDays` onto the form once (`toggleTag()`,
`AddForm.kt:126`); it does not bind them afterwards. Deleting a tag strips its id from every
friend (`deleteTag()`, `Records.kt:165`), and a tag deleted while the add form is open is dropped
from the form's selection on save (`AddForm.kt:185`).

A **group** is `{ id, name, memberIds, events, createdAt, updatedAt }` (`Group`, `Models.kt:116`);
`createdAt` is set when the group is created (`GroupsScreen.kt:107`), not stamped by the save.
`deleteFriend()` (`Records.kt:137`) removes the id from every group, so there are no dangling
members. `events` is an array of `{ id, title, dateISO, recurring }` with `recurring` one of
`once | monthly | yearly` (`Recurring`, `Config.kt:20`), kept as a string so a value from a newer
build round-trips.

**Settings** is one object read through `getSettings()` (`Records.kt:113`), which decodes it over
the defaults `{ skin: 'blue', mode: 'light', quietMode: false, smallHome: false }` (`Settings`,
`Models.kt:127`). It also holds `onboarded`, a map of step key to `true` written when an
onboarding card is dismissed (`finish()`, `Onboarding.kt:94-96`). `saveSettings()`
(`Records.kt:115`) merges over the stored object like every other save.

### Ids

`uid()` (`Text.kt:18-19`) is the current epoch milliseconds in base 36 plus six random base-36
characters — 14 lowercase alphanumeric characters today, time-ordered by prefix, the same shape
as the web build's ids. `isValidId()` (`Text.kt:35`) is deliberately looser: a string of 1 to 40
characters matching `[A-Za-z0-9]` (`Text.kt:29`), so ids from an older or hand-edited backup
survive. It uses `Regex.matches()`, which must consume the whole input, because Java's `$` also
matches before a trailing newline (`Text.kt:11-13`). The length and character-class limits are
the point: an id is used verbatim both as a storage key and, through `keyToPath()`, as a path
segment, so any id arriving from outside the app (backup import, conflict filenames, a directory
listing, a deep link) must clear `isValidId()` before it is trusted. An index entry that fails it
is dropped when the index is loaded (`CachedStore.kt:230`).

### Keys, files and the cache

`Keys` (`Config.kt:6-17`) is the whole key namespace: `fm_friend_index`, `fm_tag_index`,
`fm_group_index`, `fm_settings`, and the per-record builders `fm_friend:<id>`, `fm_tag:<id>`,
`fm_group:<id>`. The `fm_` prefix is what backup import filters on (`Backup.kt:83`).

The keys are only names: there is no database and no key-value store behind them. The only store
is the cache in `CachedStore`, a `LinkedHashMap` of key to the record's JSON as the file holds it
(`CachedStore.kt:42`), mirrored to files in the sync folder. Values stay `JsonElement`s so a field
this build does not know survives a load and save; `Records` decodes them into the models on
read. (SharedPreferences hold only the folder grant and the widget summary, never records.)
Everything else reads and writes through three synchronous calls:

- `read(key)` (`CachedStore.kt:63`) — the cached value, or `null` when the key is absent;
  `key in store` (`CachedStore.kt:62`) is the presence test, distinct from a stored value.
- `write(key, value)` (`CachedStore.kt:68`) — sets the key at once and queues the file write.
- `remove(key)` (`CachedStore.kt:74`) — drops the key at once and queues the file delete.

Every change bumps `revision` (`CachedStore.kt:46`), a `StateFlow` the screens observe to re-read
what they show.

The UI never waits on I/O, but writes are not unordered: the queue (`CachedStore.kt:101-111`) is
one unlimited channel drained by a single coroutine, so every write and delete runs after the one
before it, across all keys, and two quick saves of the same record cannot land on disk swapped.
`flushWrites()` (`CachedStore.kt:113`) waits for everything queued so far; pull-to-refresh,
adopting a newly picked folder and the widget and notification actions call it, so they do not
read back, or leave the process before, a write that has not landed. A failed write is logged,
not surfaced. The queue lives in memory: a write still queued when the process dies is lost.
Every folder call, queued or not, runs on `io`, a one-thread view of `Dispatchers.IO`
(`CachedStore.kt:39`), because the folder's `.tmp`/`.bak` crash recovery assumes no write of this
app is mid-swap while a directory is listed (`CachedStore.kt:33-35`).

`keyToPath()` (`StoragePaths.kt:39-47`) maps a key to a relative path. A key under one of
the `RECORD_DIRS` prefixes (`StoragePaths.kt:21`) has its id validated (malformed throws) and
becomes `<dir>/<id>.json`; the index and settings keys come from `INDEX_FILES`
(`StoragePaths.kt:27`). Anything else falls through to a sanitising fallback that lands the file
loose in the sync root and skips `isValidId()` entirely — which is why a new record type must be
added to `RECORD_DIRS` rather than given its own branch, and to `RecordKind`
(`Conflicts.kt:26`) too, or its conflict copies go unscanned.

### The two backends

The cache talks to the folder only through `FolderFs` (`FolderFs.kt:38-56`): `status`,
`writeFile`, `readFile`, `readFiles`, `deleteFile` and `readdir`, on `/`-separated paths relative
to the folder, failing with the codes in `FolderError` (`FolderFs.kt:11`). Every method blocks;
`CachedStore` wraps each in `withContext(io)` (`CachedStore.kt:121-134`).

- **The SAF folder (the app).** `AppContainer` builds one `SafFolder` per process and hands it to
  the store (`AppContainer.kt:34-35`). Files go into the Storage Access Framework tree the user
  picked, replaced atomically through a `.tmp` and a `.bak` sibling (`replaceAtomically()`,
  `SafFolder.kt:189`); section 6 covers the grant and the provider details. Until a folder is
  chosen — the first onboarding step — every folder call fails with `NO_FOLDER_SELECTED`: the
  cache is the whole store, a queued write fails and is logged, and the starter tags are seeded
  into memory afresh on each launch. Picking a folder then copies the whole cache into it, or
  loads the data it already holds (`adoptPickedFolder()`, `Records.kt:231`; section 3).
- **The test folders (JVM).** `FakeFolder` (`FakeFolder.kt:10`) keeps files in a map, follows the
  same contract and error codes, and can inject read faults or a vanished root; `FileFolder`
  (`FileFolder.kt:14`) is a real directory, written through a `.tmp` and a rename. The unit tests
  drive `CachedStore`, `Records`, `SyncSession` and `Backup` through them unchanged.
  `RoundTripTest` loads a folder written by the web build through `FileFolder`, writes every
  record back, and requires the files to be byte-identical (`RoundTripTest.kt:34-42`): the
  on-disk format below is shared with the web build, and an unchanged record re-encodes to the
  bytes it was read from (`toJsonText()`, `StoragePaths.kt:52-60`).

### Loading, and what counts as absent

`load()` (`CachedStore.kt:218`) runs once per process in `start()` (`SyncSession.kt:59`), which
`AppContainer.started` launches (`AppContainer.kt:52-59`) and the first render awaits, behind the
splash on API 31+ (`MainActivity.kt:53-55`). It runs again on pull-to-refresh, after
`flushWrites()` (`refreshFromDisk()`, `SyncSession.kt:73-76`), and when a picked folder already
holds data. It reads the three index files and settings, then reads every record by exact name
from its index in one batch: `fsReadMany()` (`CachedStore.kt:130`) sends up to `READ_BATCH = 100`
paths (`CachedStore.kt:279`) per `readFiles()` call, which lists each directory once per call
rather than once per record (`SafFolder.kt:257-261`; section 6 has the measurements). Avatars are
a second batch, because they are sibling files rather than records.

The rule that keeps a failed read from becoming data loss is in `readFileStrict()`
(`CachedStore.kt:185-191`): only `NOT_FOUND` (or `NO_FOLDER_SELECTED`) is absent. A read that
fails any other way, or a file that does not parse, throws. That distinction matters because
absence is acted on: an absent tag index means "never set up", and `seedIfEmpty()`
(`Records.kt:213-220`) seeds the starter tags only then — not for `[]`, which means the user
deleted every tag. Mistaking an unreadable index for an absent one is how an empty index gets
written over a real one. So an index that exists but cannot be read is rebuilt from its directory
listing (`listRecordIds()`, `CachedStore.kt:194`) and written back (`hydrateIndex()`,
`CachedStore.kt:222-228`); if the listing fails too, the load throws. `seedIfEmpty()` never
touches the friend index at all — writing `[]` there would replace a real index whose read had
failed.

When the cold-start load throws, `start()` calls `blockWrites()` (`SyncSession.kt:60-64`) and
the session becomes read-only (invariant I6): `readOnly` (`CachedStore.kt:55`) makes
`persistKey()` and `deleteKey()` return before touching the folder (`CachedStore.kt:145`,
`CachedStore.kt:167`). Edits still reach the cache, so the screen reflects them, but none is
saved. Writing from a half-loaded cache would overwrite good files with partial ones. Everything
else that writes or rebuilds from the cache stands down with it:

- seeding (`Records.kt:214`), and `start()` returns before the widget push and the reminder pass
  (`SyncSession.kt:66`);
- the conflict scan: it is not launched (`AppContainer.kt:56`), finds nothing if called
  (`check()`, `Conflicts.kt:100-101`), and a resolution writes nothing (`Conflicts.kt:206`);
- pull-to-refresh, which returns `Skipped` without reading (`SyncSession.kt:74`);
- the widget push and the reminder scheduler, gated both in `Records` (`Records.kt:52-53`) and on
  their own (`WidgetPusher.kt:39`; `rescheduleReminders()`, `ReminderScheduler.kt:49-53`), since a
  theme change or a stale-widget refresh calls them too: the widgets keep the last good summary,
  and the schedule, which every pass cancels and rebuilds, keeps the reminders of friends that
  did not load;
- a widget tick and a notification action, which are refused rather than logged into the cache
  (`LogReachOutAction.kt:66`; `perform()`, `ReminderActions.kt:32-41`);
- backup export and import (below), and onboarding (`HomeScreen.kt:232-233`).

The user sees "Couldn’t read your sync folder — changes won’t be saved until the app restarts"
once per process (`ReadOnlyNotice`, `MainActivity.kt:134-137`, shown at `MainActivity.kt:67-69`),
not once per fresh activity: Android usually brings the app back by restoring the task after its
process died, and that is a new load. Home shows "Couldn’t read your sync folder — nothing is
saved until the app restarts. Tap to choose it again." in place of the choose-a-folder banner
(`HomeScreen.kt:290-291`). The session stays read-only until the process ends or a folder that
already holds data is picked and loads cleanly: `adoptPickedFolder()` clears the cache, lifts the
block and loads, and puts the block back if that load fails (`Records.kt:240-243`). Picking an
empty folder from a read-only session is refused, since copying a partial cache would seed it with
gaps (`Records.kt:235-236`); the grant goes back to the previous folder and the toast reads
"Couldn’t read your data — changes won’t be saved until the app restarts" (`SettingsActions.kt:83`).
A pull-to-refresh whose load fails is different: it toasts "Couldn’t read the sync folder — try
again in a moment" (`HomeScreen.kt:273-274`) and does not make the session read-only.

An index id whose record file is missing (deleted elsewhere, not synced yet) leaves no key in
the cache rather than a `null` (`putRecord()`, `CachedStore.kt:261`): a stored `null` was
exported into every later backup and made it unimportable. `listFriends()` (`Records.kt:119`),
`listTags()` and `listGroups()` already map ids to records and drop the gaps.

**A picked folder that is gone is a failure, not an absence.** A folder that was picked and is
still granted but no longer exists (moved, deleted, storage unmounted) once read as "no folder":
every read was `NO_FOLDER_SELECTED`, the load succeeded with nothing in it, and home and the
widgets showed nobody. Worse, if the folder came back during that session, the first edit could
write a partial index over the real one. `currentRoot()` (`SafFolder.kt:54-63`) therefore throws
`FOLDER_UNAVAILABLE` (`FolderFs.kt:18`) when the grant is held but the root is missing, not a
directory, or not writable. `readFileStrict()` does not count that as absent, so the load throws
and the session is read-only as above. `status()` (`SafFolder.kt:68-69`) still answers, with
`granted = false`, but the read-only banner takes the choose-a-folder banner's place, and
onboarding is skipped: the settings were not read, so every step would look unseen, and nothing
it marks could be saved. Moving the folder back and restarting the app recovers everything, as
does picking a folder with data from the banner. A folder never picked, or one whose grant is
gone, is still `NO_FOLDER_SELECTED`: there is nothing to protect, and picking a folder again is
the way back. Tested with `rootGone` (`FakeFolder.kt:32`) in `MissingFolderTest`
(`MissingFolderTest.kt:44-77`).

Groups predate the `groups/` directory and used to sit loose in the root, so the group index also
tries `fm_group_index.json` (`CachedStore.kt:239`), and records not found in `groups/` are looked
for at their old root names, `fm_group_<id>.json` (`CachedStore.kt:266-271`); the next save
writes them under `groups/`, and the old root file is left where it was.

### Avatar indirection

Avatars are produced locally only. `avatarDataUri()` (`Avatars.kt:31-38`) centre-crops to
128×128 and encodes a JPEG at quality 85 as `data:image/jpeg;base64,…`; `DecoderAvatarImages`
(`Avatars.kt:44-56`) decodes the source with the EXIF rotation applied and subsampled, so a large
photo never sits in memory at full size. A picked photo and a contact's photo both go through it
(`pickContact()`, `DeviceIntegrations.kt:57-65`), since contact photos can be full-size. In memory
the field is always a data URI. On disk it is not.

`persistKey()` (`CachedStore.kt:144-164`) intercepts any friend write whose `avatar` starts with
`data:image`, writes the base64 payload to `avatars/<id>.b64` (`avatarPath()`,
`StoragePaths.kt:49`), and stores the marker `file:avatars/<id>.b64` in the JSON instead. The
reason is sync behaviour: a ~10 KB base64 blob inside a record means every note edit rewrites the
image too, and every conflict copy duplicates it; a sibling file changes only when the photo does.
An `avatar` written as `null` deletes the `.b64` so a cleared photo stops syncing, and
`deleteKey()` (`CachedStore.kt:166-170`) does the same when the friend goes.

`hydrateAvatar(f, preread)` (`CachedStore.kt:203-210`) turns the marker back into
`data:image/jpeg;base64,…`, or `null` if the sidecar is unreadable; `preread` is an optional
path-to-contents map so the cold-start load can hydrate a whole index from one batch. It is the
only decoder of the marker, and **every path that pulls a friend record off disk into the cache
must call it** — a path that does not renders a broken image in the avatar ring. There are four,
and all four do: the load (`CachedStore.kt:262`), adopting a conflict copy
(`Conflicts.kt:109-111`), records brought in by an index merge (`Conflicts.kt:195`), and
`resolve()` (`Conflicts.kt:205-216`), the last three through `hydrate()` (`Conflicts.kt:91-92`). The
conflict paths write the *unhydrated* record to disk and hydrate only the cached copy, so the
marker is never expanded into a file.

### Sync folder layout

Every path is relative to the SAF folder the user picked; subdirectories are created on demand
by `resolveDirs()` (`SafFolder.kt:220-226`).

```
<chosen folder>/
├── friend_index.json          ["m4k2p1abc9de", ...]        insertion order, ids only
├── tag_index.json             ["m4k2p1f0a1b2", ...]
├── group_index.json
├── settings.json              { skin, mode, quietMode, smallHome, onboarded }
├── friends/
│   └── m4k2p1abc9de.json      full friend record; avatar = "file:avatars/m4k2p1abc9de.b64"
├── tags/
│   └── m4k2p1f0a1b2.json
├── groups/
│   └── m4k2p1c3d4e5.json
├── avatars/
│   └── m4k2p1abc9de.b64       raw base64, no data: prefix; JPEG for every avatar this app produces, but a PNG accepted on import is stored verbatim
└── backups/
    └── forgetmenot-backup-2026-09-02.json
```

Files are compact JSON, as the web build wrote them. Syncthing conflict copies, and the
`<id>.lost-<updatedAt>.json` copies the conflict scan keeps, appear beside their originals in
these directories; section 3 covers both. A `<name>.tmp` or `<name>.bak` beside a file is a write
in progress or one interrupted by a crash, recovered on the next listing of that directory
(section 6).

### Backup export and import

`export()` (`Backup.kt:57-74`) serialises the cache into a flat object mapping storage key to the
*stringified* record — a JSON file of JSON strings, pretty-printed with a two-space indent
(`Backup.kt:47`) — and writes it to `backups/forgetmenot-backup-<local YYYY-MM-DD>.json` in the
sync folder, refusing if no folder is granted. One key is not a record: `version`, from
`BACKUP_VERSION` (`Config.kt:39`, currently 1). It lacks the `fm_` prefix, which keeps it out of
the import's record list, and it lets a build that changes the record shape bump it so an older
build refuses the file rather than half-reading it (`parse()`, `Backup.kt:80-82`). A backup with
no `version` is read as 1. Because it dumps the cache rather than the files, avatars are inline
data URIs and a backup is self-contained. A read-only session (above) neither exports nor imports,
and says "Your sync folder couldn’t be read — restart the app, then try again"
(`READ_ONLY_BACKUP`, `SettingsActions.kt:15`): its cache may be half-loaded, an export would
replace that day's good backup with it, and an import would reach only the cache and be lost on
restart. Export checks the grant first (`Backup.kt:64-66`), so when the folder itself is gone it
says "Choose a sync folder first (in Settings), then try exporting again" instead. Import checks
read-only before asking to replace anything (`importText()`, `SettingsActions.kt:55-64`), and
`apply()` refuses on its own too (`Backup.kt:104`).

`parse()` (`Backup.kt:77-94`) keeps only keys beginning with `fm_`, refuses a value that is an
object or a list rather than a string, and hands the rest to `validateBackupData()`
(`Validate.kt:30`) before touching anything. One failure rejects the whole file, and nothing is
imported, if:

- any value fails to parse as JSON;
- an index is not an array, or any entry fails `isValidId()`;
- a record key's id fails `isValidId()`;
- a record is not a plain object, or its `id` is not the id in its key (rows and navigation go
  by record id);
- a friend's `contacts`, `notes`, `tags`, `dates` or `gifts`, or a group's `memberIds` or
  `events`, is present and not an array (`Validate.kt:89-94`);
- `cadenceDays` is present and not a positive number;
- a group event lacks a `YYYY-MM-DD` `dateISO`.

Several things are repaired rather than rejected, because malformed cosmetics are not a reason to
refuse someone's data (`validateRecord()`, `Validate.kt:62-87`): a `null` record (which older
exports wrote for an index id with no record) is skipped; an `avatar` failing `isValidAvatar()`
(`Text.kt:37-40`, exactly `data:image/(png|jpe?g);base64,` plus base64) becomes `null`; an
`askNext` or `howWeMet` that is not text becomes `null`; a dated entry without a valid id and
`YYYY-MM-DD` date, and a gift without a valid id and some text, are dropped; and in `fm_settings`
an unknown `skin` becomes `'blue'` and any `mode` other than `'light'`/`'dark'` becomes `'light'`
(`Validate.kt:47-54`), so the settings screen always has a theme to show. (The palette lookup
would fall back to the whole of blue/light anyway, `resolveKeys()`, `ThemeTokens.kt:78`.)

Import is a replace, not a merge. It confirms with the user ("Replace this device’s data?"),
writes every parsed key, *then* removes every key the backup did not contain (`apply()`,
`Backup.kt:102-106` — which deletes those files and sidecars), and re-pushes the widgets and
reminders, since neither recomputes on its own; the theme follows the new settings by itself.
Write first, prune second: the other order has a moment with nothing on disk, and a process
killed there would leave the device empty with the backup as the only copy.

---

## 3. Sync and conflict resolution

### The app does not sync; it writes files where a sync app can find them

forgetmenot has no sync engine and no way to reach one (no INTERNET permission,
`android/app/src/main/AndroidManifest.xml:26-30`). It writes plain JSON files into a folder the user
picks and leaves moving them between devices to a separate app — in practice a Syncthing fork
watching that folder. Its only obligation is to write files that survive being copied around by a
third party. That shapes section 2: one file per record rather than one blob, avatars split into
sibling files so a base64 string never sits in a document two devices might both edit, and no
directory listing deciding what exists on the normal read path. The Settings screen tells the user
as much under the folder row: "Point Syncthing-fork at this same folder to sync across devices"
(`SettingsScreen.kt:212`).

### The SAF grant

`SafFolder` (`SafFolder.kt:27`) is the whole surface for this: the Storage Access Framework tree the
user picked, with named-file read, write, delete and list scoped to it, behind the `FolderFs`
interface (`FolderFs.kt:38`). The picker is `pickContract()` (`SafFolder.kt:42`), which is
`ActivityResultContracts.OpenDocumentTree()` — the system's `ACTION_OPEN_DOCUMENT_TREE`. Home and
Settings each register it with `rememberLauncherForActivityResult` and launch it with `null`
(`HomeScreen.kt:219`, `SettingsScreen.kt:149`); the Activity Result API re-registers the launcher
when the activity is recreated, so a pick survives a rotation or a process trip while the picker is
open. The returned URI goes to `takeGrant()` (`SafFolder.kt:79`), which takes a persistable read and
write grant with `takePersistableUriPermission` and stores the tree URI as `tree_uri` in the
`sync_folder` SharedPreferences file (`SafFolder.kt:30-31`). Those two names are fixed (invariant
I1). No storage permission appears in the manifest: the system picker grants access directly, which
is what makes an arbitrary user-chosen folder workable.

Four things about that grant matter for maintenance:

- **A provider may refuse a lasting grant.** Some providers show a tree they will not grant a
  persistable *write* on (read-only cloud and network providers, some OEM ones), and
  `takePersistableUriPermission` throws `SecurityException`. It is caught and turned into a
  `FolderException` with code `PERMISSION_NOT_PERSISTED` (`SafFolder.kt:82-86`); a cancelled pick
  (a `null` URI) is `PICKER_CANCELLED` (`SafFolder.kt:80`). `adoptPick()` (`SyncSession.kt:114`)
  turns either into `PickResult.NotChosen`, and nothing about the current folder changes.
- **A grant is provisional until the folder is adopted.** `takeGrant()` returns a `PendingGrant`
  (`FolderFs.kt:36`): the new URI is already stored, so adoption reads and writes the new folder,
  but the previous folder keeps its grant. If adoption fails, `revert` (`SafFolder.kt:92-97`)
  stores the previous URI again and releases the new grant (`SyncSession.kt:118-122`). Left on the
  new folder with the old folder's cache, the next edit would write that cache's index over the new
  folder's.
- **Old grants are released.** Only once adoption worked does `keep` run `releaseTreeGrants()`
  (`SafFolder.kt:101`), which releases every other persisted tree grant (`SyncSession.kt:124`):
  only one folder is ever used, and the number of persisted grants an app may hold is capped.
- **The grant is re-derived on every call, never cached.** `currentRoot()` (`SafFolder.kt:54`)
  reads the stored URI and checks it is still in `persistedUriPermissions` as a write grant,
  returning null otherwise; every file method then throws `NO_FOLDER_SELECTED`
  (`SafFolder.kt:65-66`). A grant can die mid-session — a reinstall, the system reclaiming grants —
  and the store absorbs that: queued writes and deletes are logged (`persistKey()`,
  `CachedStore.kt:144-163`), `readFileJson()` (`CachedStore.kt:177`) reads as null, the conflict
  scan's `scanDir()` (`Conflicts.kt:88`) returns an empty list, and the Settings row reads "Not set
  — tap to choose" the next time it is shown (`SettingsScreen.kt:199-200`). The app keeps working
  from the in-memory cache for the session while nothing reaches disk. A grant that is still held
  but whose folder is gone (moved, deleted, storage unmounted) or no longer writable is not "no
  folder": `currentRoot()` throws `FOLDER_UNAVAILABLE` (`SafFolder.kt:61-62`), because read as
  absent it loaded as empty and the first edit wrote a partial index over the real one; a load that
  meets it makes the session read-only (I6, section 2). `Backup.export()` (`Backup.kt:57`) is the
  one path that checks the folder first and refuses up front (`Backup.kt:64-65`).

The picker's result is reported in one place, `SettingsActions.adoptPick()`
(`SettingsActions.kt:78`), which toasts "No folder chosen" for a refused or cancelled pick
(`SettingsActions.kt:81`). On success the app does not simply start writing: `adoptPickedFolder()`
(`Records.kt:231`) first waits for in-flight writes, then asks whether the folder already holds
forgetmenot data (either index file present). A folder with data — a reinstall, a second device —
wins: the cache is cleared and reloaded from it, read-only is lifted, and anything created in memory
before the pick that the folder lacks is added on top, except the starter tags, since the folder has
its own (`seededTagIds`, `Records.kt:206`). Settings are carried over only if the folder has none
(`Records.kt:245`). An empty folder receives everything currently in memory, unless the session is
read-only: its cache is partial, and copying it would seed the new folder with gaps
(`Records.kt:235-236`). If the folder cannot be read, the grant is reverted and the toast says
"Couldn't read that folder — nothing was written to it", or, when the failed load left the session
read-only, "Couldn't read your data — changes won't be saved until the app restarts"
(`SettingsActions.kt:82-84`). Widgets and reminders are re-pushed only after a successful
adoption, so a failed one leaves them with what they had (`SyncSession.kt:125-126`).

### What a conflict looks like on disk

When the same file is edited on two offline devices, Syncthing keeps one copy under the original
name and renames the loser, beside it, to
`<name>.sync-conflict-<YYYYMMDD>-<HHMMSS>-<device>.<ext>`. Because no normal read path acts on a
name it did not ask for, that file is invisible unless something goes looking. (`readFiles()`
does list each directory internally to resolve names (`SafFolder.kt:261-280`), so a conflict copy
is *seen* there and dropped, because it is not one of the names the index asked for.) The scan in
`Conflicts.check()` (`Conflicts.kt:100`) is the app's only awareness that conflicts exist.

One pattern matches both records and avatar sidecars (`CONFLICT_RE`, `Conflicts.kt:54`), applied
with `matchEntire`, so it is anchored at both ends:

```kotlin
Regex("(.+)\\.sync-conflict-\\d{8}-\\d{6}(?:-[A-Za-z0-9]+)?\\.(json|b64)", RegexOption.IGNORE_CASE)
```

The device suffix is optional because older Syncthing versions omit it. Group 1 is the original
base name — for a record, the id — which is put through `isValidId()` (`Text.kt:35`) before it
builds any path; a record conflict whose base name is not a valid id is deleted as not one of ours
(`Conflicts.kt:122`).

### Four treatment classes

The scan sorts every conflict copy by what the file contains, not where it sits. A read-only
session (I6) writes nothing, so it scans nothing: `check()` returns an empty result at once
(`Conflicts.kt:101`).

**Auto-merged: the three id indexes.** An index is a flat list of ids, so a union is at least as
correct as either side: the cached index plus the conflict copy's ids, `distinct()`, written back
over the canonical name (`mergeIndex()`, `Conflicts.kt:178-181`). Keeping an id whose record file
is gone costs nothing, since the list functions drop missing records. The merge re-reads the cached
index after its write and writes again if it grew meanwhile (the user adding a friend), so that
write is not clobbered (`Conflicts.kt:182-188`). And ids the merge brought in have records this
device never loaded, so those are read in one batch and hydrated into the cache
(`Conflicts.kt:189-197`); otherwise a friend synced in only through the index would stay invisible
until restart. A conflict copy that is not a JSON array is deleted unmerged.

**Discarded: settings and avatars.** `settings.json` is not mergeable and a theme or quiet-mode
choice is low-stakes, so the active copy wins and the conflict copy is deleted. Avatar `.b64`
conflicts are deleted in their own loop (`Conflicts.kt:158-161`) — a photo is not worth a
resolution screen. Both count toward `autoResolved`.

**Newest edit wins, the other is kept: friend, tag and group records.** These hold real content.
Every save stamps `updatedAt`, so when both copies carry a stamp and the stamps differ, the later
one wins (`Conflicts.kt:135-153`). What makes that safe to do silently is that the loser is not
discarded: it is written beside the record as `<dir>/<id>.lost-<its updatedAt>.json`
(`Conflicts.kt:145`). That name matches neither `CONFLICT_RE` nor anything a read path asks for, so
the app never sees it again and no scan sweeps it; the sync app carries it to every device; and
recovering it is renaming it over the canonical file while the app is closed. A winning conflict
copy is adopted — written to the canonical name, hydrated into the cache, its id appended to the
index if missing — by the local `adopt()` helper (`Conflicts.kt:109-114`). When the canonical copy
wins, it is rewritten only if the merge below added entries to it (`Conflicts.kt:148`). The toast
says the older edits were kept: "N sync conflicts resolved automatically; the older edits are kept
beside their records" (`autoResolvedText()`, `HomeModel.kt:99-103`).

A friend's logs are not lost with the losing copy. `contacts`, `notes`, `dates` and `gifts` only
grow one entry at a time, each with its own id, so whichever copy wins the other fields, the winner
takes every entry of the loser's that it lacks (`mergeFriendLogsJson()`, `Conflicts.kt:71`;
`Conflicts.kt:147`): a reach-out logged on one phone and a note written on the other both survive.
The merge runs on the raw JSON so the entries keep fields the models drop; `mergeFriendLogs()`
(`Merge.kt:14`) is the same rule on the domain model. It applies too when the user picks a side
(`Conflicts.kt:211-214`). There is no record of removals, so an entry removed on one phone and
still present on the other comes back (section 9).

**User-resolved: when the stamps cannot decide.** Equal stamps, or a missing stamp on either side
(a record written by something other than this app), become a `PendingConflict` for the user. Two
escapes come first: an unreadable conflict copy is deleted, and if there is no canonical file on
this device (never synced down, or deleted here but not there) the conflict copy is the only
surviving version and is adopted outright (`Conflicts.kt:126-133`). Each `PendingConflict`
(`Conflicts.kt:35-44`) carries its `RecordKind`, from which its `dir` and `recordKey` follow, so
resolution needs no second kind-to-location table that could drift.

`RecordKind` (`Conflicts.kt:26-32`) is the coupling to watch: every entry in
`StoragePaths.RECORD_DIRS` (`StoragePaths.kt:21`) and every index in `INDEX_FILES`
(`StoragePaths.kt:27`) needs a constant there (settings is the extra `"settings"` name the root
loop appends, `Conflicts.kt:165`), and a record type added without one has its conflict copies pile
up unread — the exact failure this code exists to prevent.

A record changed without the user changes the same derived surfaces a user-resolved one does, so
the scan returns the set of kinds it touched (`ScanResult`, `Conflicts.kt:46-52`) and `runScan()`
(`SyncSession.kt:86`) hands it to one per-kind rule, `applyTouched()` (`Records.kt:56-59`): a friend
re-pushes the widgets and the reminders, a group only the reminders, a tag neither.

### Resolving a record conflict

`SyncSession.pendingConflicts` (`SyncSession.kt:47`) drives three surfaces: the home banner ("N
sync conflicts found — tap to resolve", `HomeScreen.kt:295-297`), the Settings row
(`SettingsScreen.kt:203-208`) and the resolution sheet, `SyncConflictsSheet()`
(`ConflictsSheet.kt:71`). Each card offers "Keep this version" and "Keep the synced-in version",
each labelled by `conflictSummary()` (`ConflictsSheet.kt:57`) with how long ago it was edited and
what it holds (check-ins logged, members), so the two sides differ by content, not only by time.
The version not kept is gone for good, so the choice is asked a second time, "Keep this version?",
before anything is discarded (`ConflictsSheet.kt:101`).

`Conflicts.resolve(item, keepConflicting)` (`Conflicts.kt:205`) does not trust the scan's snapshot.
Before keeping the canonical side it re-reads the file and compares (`Conflicts.kt:208-209`); if it
changed, the user edited that record after the banner appeared, and honouring the snapshot would
discard that edit. It returns `false` without writing; `SyncSession.resolve()`
(`SyncSession.kt:101-105`) then re-runs the scan, and the sheet toasts "This entry changed since the
conflict was found — resolve it again to be safe". Otherwise the winner is written and hydrated,
the conflict file deleted, and the per-kind rule applied (`Conflicts.kt:215-218`); the session
drops the item from the pending list. A read-only session resolves nothing (`Conflicts.kt:206`),
so the sheet checks for one before asking and toasts "Your sync folder couldn’t be read — restart the
app, then try again" instead (`ConflictsSheet.kt:99`).

The sheet's click handler (`ConflictsSheet.kt:94-112`) awaits that result: the rows are disabled
and taps ignored while a resolution is in flight, it toasts "Kept one version, discarded the other"
only when the resolution actually happened, and toasts "Couldn't save that choice — try again"
rather than swallowing a failure. The sheet closes itself when the pending list empties, whether by
the user's last choice or by a rescan (`ConflictsSheet.kt:127-131`).

### When a scan runs

`runScan()` exists so every caller reports identically: it publishes the pending list and the
outcome (`lastScan`, which home turns into the auto-resolved toast once per scan,
`HomeScreen.kt:210-215`) and applies the touched kinds. It runs at cold start, in the background
after the load so the first frame does not wait for its directory listings, and not at all when the
load left the session read-only (`started`, `AppContainer.kt:52-58`); from `refreshFromDisk()`
(`SyncSession.kt:73`); and after a stale resolution. It deliberately does **not** run on resume: a
folder scan plus re-reading every record is real I/O, paid for a scenario — another device having
synced changes in while this one was backgrounded — that is often not even true.
`MainActivity.onResume()` (`MainActivity.kt:114-123`) does only the cheap work (reminders and
widgets), and home's own resume handler only re-reads the clock (`HomeScreen.kt:173-176`). The
expensive path sits behind a deliberate gesture: pull-to-refresh on the home list
(`HomeScreen.kt:268-283`) runs `refreshFromDisk()`, which waits for in-flight writes, reloads,
re-pushes widgets and reminders, then scans; it stands down in a read-only session, and a failed
reload toasts "Couldn't read the sync folder — try again in a moment". Section 7 covers the
gesture.

---

## 4. Reminders and notifications

### Classifying a friend

Everything the home screen shows about a person is derived, never stored. `classify()`
(`Classify.kt:51-63`) turns a record into a `Classification(days, pct, section, code)`, and every
list, ring, widget summary and section header reads it rather than recomputing. `latestContact()`
(`Classify.kt:18`) reduces `f.contacts` to the entry with the greatest date, wherever it sits in the
list; ties and unparseable dates keep the earlier entry. With no contacts at all, `classify` returns
the "new" result: counting starts at the first logged contact, not at creation.

`daysSince()` (`Dates.kt:42-45`) counts **calendar days, not elapsed 24-hour periods**: both ends
are taken to their local calendar day in the zone of `now`, and `ChronoUnit.DAYS` counts the days
between, so someone logged at 11pm yesterday reads as `1d` the moment the clock passes midnight.
Counting dates rather than dividing a millisecond difference is what keeps daylight-saving changes
out of it: they produce 23- and 25-hour local days, and any arithmetic on elapsed time would slide
every day code by one twice a year. An unparseable date counts as 0.

From `days` fall the other three fields:

- `pct = min(1.0, days / max(1, cadenceOf(f)))`. `cadenceOf()` (`Classify.kt:39`) is
  `f.cadenceDays` when it is positive and `DEFAULT_CADENCE_DAYS` (90, `Config.kt:63`) otherwise;
  the backup validator allows a record with no cadence, and without one shared fallback it would
  read differently on the list, the detail screen and the reminders. The clamp exists because
  `pct` is the fill fraction of the avatar ring in `AvatarRing()` (`AvatarRing.kt:44-47`), and a
  ring cannot overfill. The widget summary deliberately recomputes the same ratio *unclamped* as
  its `weight` in `summarise()` (`WidgetData.kt:60`) — section 5 says why the clamp is wrong as a
  ranking key.
- `section` is `THINKING` at `pct >= 1`, `COMING` from 0.33, `RECENT` below; a paused friend
  (`isPaused()`, `Classify.kt:44`: paused outright, or `pausedUntil` a day still to come) is
  always `RECENT`.
- `code` is `formatCode()` (`Dates.kt:50`): `today` at `days <= 0`, `Nd` under 30, `Nm` (rounded
  months) under 365, `Ny` above.

`buildHome()` (`HomeModel.kt:67`) buckets the active friends into those sections and emits them in
fixed order — "Been a while", "In between", "Recently in touch" (`SECTION_TITLES`,
`HomeModel.kt:29-33`) — and an empty section emits no heading. Within a section the order is the
widgets' (section 5): by how far into their own cadence each friend is, `days / cadence`, highest
first, so a weekly friend a month out comes before a yearly one at 400 days; "In between" instead
puts the soonest due first (`HomeModel.kt:83-87`). Each row is two sibling targets, open and Log,
never one nested in the other (`FriendRow()`, `HomeRow.kt:85`), so a screen reader announces both
rather than treating the inner one as part of the outer. A paused friend shows a moon glyph in
place of the code, with "Paused" as the open target's state description (`HomeRow.kt:111-125`);
the switch is on the detail screen.

**The smaller home**, for low days, is the `smallHome` setting (Settings, Home). With it on and no
search typed, home shows one section, "Maybe today": the first three of "Been a while" then "In
between", in the order above (`SMALL_HOME_COUNT`, `HomeModel.kt:36`), or a line saying nobody is
due. Below it, "Show everyone (N)" opens the full list until the app restarts or Smaller home is
toggled (`HomeSession.showAll`, `HomeModel.kt:111`) and moves focus to its first row
(`HomeScreen.kt:259-264`). A search always looks through everyone. The detail screen reads the
cadence back as the day of the next nudge, "Next nudge around …", from the same `nextCheckIn()`
the scheduler uses (`DetailScreen.kt:409-410`).

Search folds case and accents (`foldText()`, `Text.kt:96`), so "zoe" finds "Zoë", and matches a
friend's notes and tag names as well as the name (`matchesQuery()`, `HomeModel.kt:57`).

**After Message.** The app cannot see whether a message was sent, so it asks once. The detail
screen's Message button records the hand-off (`AfterMessage.handedOff()`,
`HomeIntegrations.kt:28`); on the next resume of whichever screen is on top, home or detail, if
that friend has not been logged since and it is within 12 hours, a toast asks "Talked to Ana?"
with a Log it button that logs a reach-out of kind "text" (`offer()`, `HomeIntegrations.kt:35-46`).
Logging stays a tap, and an ignored offer lapses with the toast.

### The five kinds of scheduled notification

`buildReminders()` (`ReminderPlan.kt:69`) builds the whole pending set as plain `Reminder` data,
and `ReminderScheduler.rescheduleReminders()` (`ReminderScheduler.kt:49-62`) applies it on
AlarmManager: it returns early in a read-only session (I6) and unless
`areNotificationsEnabled()` reports the app may post, then builds the new list, cancels every
alarm of the previous plan and arms the new one — a full teardown and rebuild, never a diff. The
list is built **before** anything is cancelled, and each reminder's date is computed separately:
a date that throws or is malformed skips only its own reminder (`safe()`, `ReminderPlan.kt:78`),
so one bad synced or imported record cannot cancel everyone's reminders and schedule none.

AlarmManager cannot list an app's pending alarms, so the scheduler keeps the last plan in
private preferences (`apply()`, `ReminderScheduler.kt:78-89`): it names what the next pass
cancels, and what a reboot re-arms (below). The planning runs on the caller's thread, as every
read of the records does; the AlarmManager calls run in order on a single background executor.

**Check-in nudges.** `nextCheckIn()` (`Reminders.kt:22`) gives one per non-archived friend not
paused outright: the calendar day of the last contact plus the cadence, at 09:00. It is pure and
lives in `domain/`, so `RemindersTest.kt` runs it directly with a pinned clock and zone.

`base` is the last contact, or `createdAt` for a friend never logged (`Reminders.kt:28-30`). It
must not be "now": the schedule is rebuilt on every resume, so a never-logged friend's first nudge
would be pushed one cadence further out every time the app opened and would never arrive. (A
record with no `createdAt` falls back to now until its next save stamps one.) Building the date
from the local calendar day with `plusDays` rather than adding `cadenceDays` worth of
milliseconds is the same fix as in `daysSince`: a raw offset ties the reminder to the clock time
of the last contact, so a friend logged at 11pm would be nudged at 11pm even though `classify()`
called them due at midnight.

Three adjustments follow:

- **Spread.** The due day moves later by 0 to `min(7, cadence / 10)` days, fixed per friend by a
  hash of its id (`Reminders.kt:33-35`). A contacts import stamps every friend with the same
  `createdAt`, and without it they would all come due on one morning.
- **Once per step.** A due date already past has had its nudge. The next is a whole step later,
  `max(7, round(cadence / 4))` days (`Reminders.kt:39-40`), so an unanswered check-in comes back
  about four times a cadence rather than every morning.
- **Holds.** `snoozedUntil` and `pausedUntil` move the nudge to 09:00 on that date when it is
  later (`Reminders.kt:42-44`).

**One notification or several.** `buildReminders()` collects every friend's check-in, then
`groupCheckIns()` (`Reminders.kt:54`) splits them into runs: a run starts at the earliest
remaining check-in and takes in every other within five hours of it (`GROUP_WINDOW_MS`,
`Reminders.kt:11`). A run of one is the usual "Thinking of Ana?" nudge; a longer run is one
notification, "A few people you might think of", naming them with `namesLine()`
(`Reminders.kt:72`), at the time of the first (`ReminderPlan.kt:114-126`). Since every check-in
lands at 09:00, a run is in practice everyone due that day. Birthdays, anniversaries and group
events are never folded in: each is its own thing to act on.

**Birthdays and anniversaries.** One each, at `nextYearlyOccurrence()` (`Dates.kt:109`). Only
month and day are read, so a contact imported without a birth year still gets a yearly reminder.
The day is clamped to the last real day of the target month (`clampedNineAm()`, `Dates.kt:97`), so
a Feb 29 birthday is observed on Feb 28 in common years rather than rolling into March. Both fire
regardless of the pause switch: pausing means "stop nudging me on a schedule", not "let me forget
their birthday". A birthday with gift ideas noted names them ("Gift ideas: a book and socks.",
`birthdayBody()`, `Reminders.kt:65`).

**A friend's own dates.** One per entry in `dates` (`ReminderPlan.kt:93-100`): a yearly one at
`nextYearlyOccurrence()`, a one-off at 09:00 on its day, skipped once that moment is more than
60 seconds past. Titled with the label and the friend's name ("📅 Interview · Ana"). Like
birthdays, they ignore the pause.

A check-in nudge whose friend has something to ask about says so ("Maybe ask about the new
flat.") instead of the generic line (`ReminderPlan.kt:118-119`).

**Group events.** One per event, at `computeNextEventDate()` (`Dates.kt:120`). `YEARLY` delegates
to `nextYearlyOccurrence`; `MONTHLY` rolls to this month's clamped day, or next month's if that
has passed; `ONCE` returns its stored date, and the planner skips it if it is more than 60 seconds
past (`ReminderPlan.kt:134`). A monthly or yearly series whose start date is still ahead returns
that start date (`Dates.kt:123`) rather than an earlier occurrence before the series exists. The
body names members — `A & B` for two or fewer, `A, B + N more` above — built only from members
that still resolve to a non-archived friend (`ReminderPlan.kt:129-131`).

`onceAlreadyPast()` (`Dates.kt:136`) is the *UI* test for the same question, keyed to the calendar
day rather than the planner's 60-second slack, so the groups list and the detail screen's "Was"
label always agree. `nextGroupEvent()` (`Dates.kt:142`) uses it to pick the groups-list teaser.

### Why 09:00, and why overdue anchors forward

Every kind lands at 09:00 local: each date is a `LocalDate` taken to `atTime(9, 0)` in the current
zone (`atNine()`, `Dates.kt:90`); building from the local date keeps the wall-clock time across
daylight-saving changes, which a millisecond offset would not.

When a check-in's due date is already past, the next nudge is a whole number of steps after it
(above), counted from the due date. That is what makes rescheduling **idempotent**, and
idempotence is required because the rebuild runs on every resume. A relative anchor ("now plus
an hour") would be recomputed from a new `now` each time, so someone who opens the app a few
times a day would push their own overdue nudge forward indefinitely. Anchors counted from a fixed
date converge: every rebuild between two of them produces the same instant. The earlier anchor,
"the next 09:00", converged too, but fired again every morning for as long as the friend stayed
due (A.3, 2026-10-14). The 60-second slack in `nextYearlyOccurrence` and `computeNextEventDate`
(`isMoreThanAMinuteBefore()`, `Dates.kt:102-103`) is the same idea from the other side — a rebuild
at exactly 09:00:00 on the day still treats today's occurrence as current.

That slack has a cost the scheduler pays: a rebuild in the minute after 09:00 still plans today's
occurrence, and arming it again would fire it a second time at once. So every reminder that has
been shown is recorded by id and due time (`markDelivered()`, `ReminderScheduler.kt:181-188`), kept
for two days, and a pass skips arming one already delivered (`ReminderScheduler.kt:83`).

### Delivery, the nightly rebuild and reboots

An alarm fires a broadcast to `ReminderReceiver` (`ReminderReceiver.kt:21-31`), not exported, which
decodes the reminder carried in the alarm's intent and posts it with `ReminderNotifications.post()`
(`ReminderNotifications.kt:26`). Every nudge goes on one notification channel, id `reminders`,
named "Reminders" (`CHANNEL_ID`, `ReminderNotifications.kt:18`), created on each post (a no-op once
it exists). A post is skipped if notifications have been turned off since the alarm was armed.

A fired check-in has no successor until a rebuild computes its next step, and a revoked exact-alarm
grant cancels every exact alarm. So each pass also arms one inexact heartbeat at 03:30
(`armHeartbeat()`, `ReminderScheduler.kt:122-130`) that rebuilds the schedule from the folder
without the app being opened, gated on I6 like any other pass (`ReminderReceiver.kt:33-51`). A pass
that stands down (read-only, notifications off) does not re-arm it; the next resume does.

A reboot drops every alarm. `BootReceiver` (`BootReceiver.kt:23-47`) handles three broadcasts:

- `BOOT_COMPLETED`: `rearmFromSavedPlan()` (`ReminderScheduler.kt:97`) re-arms the saved plan's
  future reminders as they were, without reading the sync folder, and shows now any that fell due
  earlier today while the phone was off; one from an earlier day is past caring about. The
  heartbeat is re-armed, and its rebuild moves an ignored check-in on to its next step.
- `MY_PACKAGE_REPLACED`: a full rebuild from the folder right after an update is installed.
  Alarms survive an update, but this way the new build's rules apply before anyone opens it.
- `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (`EXACT_ALARM_GRANTED`, `BootReceiver.kt:51`),
  which Android sends when the grant is given: a full rebuild, so inexact alarms turn exact at
  once instead of on the next resume.

The two rebuilds wait for the cold-start load and are skipped in a read-only session (I6); each
receiver awaits the queued pass (`awaitQueued()`, `ReminderScheduler.kt:69`) within 45 seconds
before it finishes, since a background process may be ended as soon as it does.

### The nudge's buttons

Check-ins and birthdays carry actions (`Reminder.actions`, `ReminderPlan.kt:51-57`). One friend's
nudge has two buttons, "Logged it" and "Next week", and the friend's id; a grouped nudge has every
id and only "Next week", since there is no one friend to log. A birthday nudge has one button,
"Wished them", which logs a reach-out exactly as "Logged it" does (`ReminderAction`,
`ReminderPlan.kt:28-32`); the detail screen offers the same as "Mark as wished" on the day
(`DetailScreen.kt:365`). An anniversary, a friend's date or a group event is not something you log,
so those have no button.

A press does not bring the app forward. Each button is a broadcast to `ReminderReceiver`
(`ReminderReceiver.kt:52-82`), which waits for the cold-start load when the app was not running and
runs `ReminderActions.perform()` (`ReminderActions.kt:34-65`) on the main thread, as every edit of
the records does:

- `LOGGED` and `WISHED` add a contact dated now and save the friend — the same save, reschedule and
  widget push the on-screen Log button gets — and put "Logged Ana" on the recent-changes list
  (section 9) with its undo (`ReminderActions.kt:43-52`). The toast "Logged — nice." is a system
  one with no button, so that list is where a mistaken press is undone.
- `LATER` sets each named friend's `snoozedUntil` `SNOOZE_DAYS` (7, `Config.kt:42`) days ahead,
  without touching the cadence, saves them with `skipSync` and reschedules once
  (`ReminderActions.kt:53-61`). The toast reads "Next nudge for Ana in a week", or "Next nudges in
  a week" for several.

Writes are flushed to the folder and the reschedule is awaited before the receiver finishes. On
success the nudge is dismissed. Ids that are no longer a friend are skipped, and if none is left
the nudge is simply dismissed. In a read-only session nothing is written: the toast says "Couldn't
save — open forgetmenot to check the sync folder" and the nudge stays, so the press can be retried
once the folder is back (`ReminderActions.kt:41`, `ReminderReceiver.kt:72-75`).

A tap on the body of a single nudge, a birthday or a friend's date opens that friend's detail
screen through the `forgetmenot://friend/<id>` deep link; a grouped nudge, an anniversary or a
group event opens home (`openIntent()`, `ReminderNotifications.kt:51-56`).

### Notification ids

`hashToId()` (`Classify.kt:70-74`) is a 32-bit rolling string hash (`h * 31 + charCode` over UTF-16
code units), absolute-valued in `Long` and taken mod `2000000000` to stay inside the signed-int
range Android uses for notification ids and PendingIntent request codes. Ids are namespaced by
prefix — `checkin:<friendId>`, `checkins:<epoch ms of a run's first check-in>`,
`birthday:<friendId>`, `anniversary:<friendId>`, `date:<friendId>:<dateId>`,
`gevent:<groupId>:<eventId>` — and hashed where each reminder is built. One id serves as the
alarm's request code and the posted notification's id (`Reminder`, `ReminderPlan.kt:34-39`). The
point is stability: the same logical reminder gets the same id on every run, so a reschedule
replaces its predecessor instead of stacking a copy. Because every pass also cancels the whole
previous plan by the ids it saved, that is belt-and-braces, and changing a hash string can never
orphan an un-cancellable alarm. A collision at 2×10⁹ buckets would make one reminder displace
another, not crash.

### The 400-notification cap

`MAX_SCHEDULED_NOTIFICATIONS = 400` (`Config.kt:35`). Android caps how many alarms one app may hold
pending — 500 in practice — and past that drops the overflow without saying so. Rather than let
the OS choose, the list is sorted by trigger time and truncated to the soonest 400
(`ReminderPlan.kt:141-142`). Nothing further out is lost, only deferred: the schedule is rebuilt on
every resume, every data change and every night, so the window rolls forward on its own.

### Quiet mode and pausing

Quiet mode is the `quietMode` setting, default `false`, toggled from the Reminders card in
Settings; the toggle reschedules at once (`toggleQuiet()`, `SettingsActions.kt:29-33`). With it on,
the list to schedule is empty (`planReminders()`, `ReminderPlan.kt:61-62`), but the cancel pass
still runs, so turning quiet mode on genuinely tears down every pending alarm rather than
suppressing them at delivery time. Turning it off rebuilds from current data.

Pausing is per-friend and narrower: it drops that friend's check-in nudge and keeps them in
"Recently in touch", but leaves birthdays, anniversaries and their own dates scheduled. Archiving
is broader: an archived friend is out of lists, widgets and reminders alike, and kept on disk.

The small icon is `ic_stat_forgetmenot`, a monochrome vector drawable, and the accent colour is the
default theme's primary (Cyanotype light, `#0A5188`), both set on every notification
(`ReminderNotifications.kt:30-32`).

### The two Android permissions

Reminders need two separate grants, and they behave nothing alike.

`POST_NOTIFICATIONS` has a normal runtime dialog. The onboarding queue and the Notifications row in
Settings ask for it while it can still be asked (`canAskNotifications()`, `SettingsPlatform.kt:44`),
and reschedule on success with a "Notifications on" toast (`SettingsScreen.kt:144-148`); once it is
blocked the row opens the app's notification settings instead.

`SCHEDULE_EXACT_ALARM` has none. `ExactAlarms.granted()` (`ExactAlarms.kt:17-19`) is the one check,
`SDK_INT < S || canScheduleExactAlarms()`, read by both the scheduler and the Settings row, so they
never disagree and no caller branches on API level. Because the app targets SDK 36, Android 14+
**denies this permission by default on fresh installs**, even though `AndroidManifest.xml:16`
declares it — declaring makes it requestable, not granted — and the only route is the "Alarms &
reminders" Settings screen. The "Exact reminders" row deep-links straight there with
`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` and a `package:` URI, and swallows a failure to start it
(`openExactAlarmSettings()`, `SettingsPlatform.kt:60-64`): some OEM builds omit that screen or hide
it after a first denial, and the in-app copy already describes the manual route.

**Do not check this one with `dumpsys package`.** On the test phone (Android 14) it
reported `SCHEDULE_EXACT_ALARM: granted=false` while `cmd appops get com.hazia.forgetmenot
SCHEDULE_EXACT_ALARM` reported `allow` — and `canScheduleExactAlarms()`, which is what the
Settings row shows, reads the **appop**. Anyone auditing from the permission list alone will
conclude the reminders are inexact when they are not.

Only `SCHEDULE_EXACT_ALARM` is declared, never `USE_EXACT_ALARM`, which is restricted to apps
whose core function is an alarm clock, timer or calendar. `RECEIVE_BOOT_COMPLETED`
(`AndroidManifest.xml:18`) lets `BootReceiver` re-arm the saved plan after a reboot
(`AndroidManifest.xml:111-119`).

Without the exact-alarm grant reminders still schedule, armed with `setAndAllowWhileIdle` instead of
`setExactAndAllowWhileIdle` (`arm()`, `ReminderScheduler.kt:132-146`; a grant revoked between the
check and the call falls through to inexact too), but the OS may delay them by hours, which
defeats a same-day birthday nudge as surely as a wrong date would. Notifications are asked for by
the onboarding queue; exact alarms only by their tap-to-fix row in Settings (section 2), which sits
beside a "Battery limits" row for the same reason: battery optimisation can hold reminders back
too.

### When rescheduling runs

The rebuild is cheap and idempotent, so it is called liberally: from the cold start
(`SyncSession.start()`, `SyncSession.kt:59`); from every `saveFriend`/`saveGroup` that does not pass
`skipSync`; on friend, group and (when it touched a friend) tag deletion; after adopting a picked
sync folder, a contacts import, a backup import, or a conflict resolution or scan that changed a
friend or group (`applyTouched()`, `Records.kt:56-59`); on the quiet-mode toggle; on a notification
grant; on pull-to-refresh (`refreshFromDisk()`, `SyncSession.kt:73`); after a nudge's button or a
widget tick; from the nightly heartbeat and `BootReceiver`; and on every resume
(`MainActivity.onResume()`, `MainActivity.kt:114-122`). Every path goes through
`Records.rescheduleReminders()` (`Records.kt:53`), which stands down in a read-only session (I6).
The resume case is load-bearing: the Notifications and Exact reminders rows open Settings screens
and return long before the user has toggled anything, so a grant only lands when they come back.
The exact-alarm grant is also announced by broadcast (above); the notification grant is not.
Bulk callers pass `skipSync = true` and pay once at the end rather than running a ten-member edit
as ten full passes: the group member picker (paid when the sheet closes,
`GroupDetailScreen.kt:158-163`), `deleteFriend` and `deleteTag`, contacts import
(`importContacts()`, `ContactsImportSheet.kt:54-63`), "Next week" on a grouped nudge, and adopting a
picked folder (`adoptPickedFolder()`, `Records.kt:231`).

If planning or arming throws, the failure is logged and emitted on `failures`
(`ReminderScheduler.kt:42`), and the activity toasts "Reminders couldn't be scheduled — check
notification permission in Settings" (`MainActivity.kt:75-77`); the failure is never silent. A
failure while the app is in the background is not toasted, but the next resume runs the pass again.

---

## 5. Home screen widgets

Three home screen widgets, written with Jetpack Glance (`androidx.glance:glance-appwidget:1.2.0`
in `android/app/build.gradle`). Each has a `GlanceAppWidgetReceiver` declared in
`android/app/src/main/AndroidManifest.xml`, pointing at its own `appwidget-provider` XML. The
receiver class names, `.widget.SpotlightWidgetReceiver`, `.widget.TopThreeWidgetReceiver` and
`.widget.RandomWidgetReceiver`, are kept from the web build (I2).

| Widget | Label | Class | Picks |
|---|---|---|---|
| Spotlight | forgetmenot — Spotlight | `SpotlightWidget` | `topByWeight(friends, 1)` |
| Top 3 | forgetmenot — Top 3 | `TopThreeWidget` | `topByWeight(friends, 3)` |
| Thinking of | forgetmenot — Thinking of | `RandomWidget` | `weightedRandom(friends, Random(friends.hashCode()))` |

A tap opens the app **at the friend the widget names**. The Spotlight and Thinking-of cards, and
each of Top 3's rows, carry an explicit `ACTION_VIEW` intent to `MainActivity` whose data is
`forgetmenot://friend/<id>` (`friendIntent()` in `widget/WidgetData.kt`, I3). Explicit, so the
manifest declares no intent-filter and `MainActivity` accepts nothing it did not already; the
URI is only a payload. `MainActivity` reads it on a fresh start (`onCreate`) and in
`onNewIntent`, and `friendIdFromUrl()` (`ui/AppNavHost.kt`) accepts only `forgetmenot://friend/`
followed by a valid id. The friend screen then rejects an id that is unknown or archived, so an
intent from anything else on the device that names no friend of ours does nothing. With no one
to name, and on Top 3's card outside its rows, the tap just starts the app (`openFriendAction()`).

### The data path

The widgets never read friend records, compute dates or format labels while they draw. They
repaint a summary that the app builds and stores for them. `WidgetPusher`
(`widget/WidgetPusher.kt`) is the widgets' half of `DataEffects`. `AppContainer` sets
`records.effects = CombinedEffects(reminders, widgets)`, so `Records.pushWidgets()` runs it.
That happens after every friend save without `skipSync`, at the cold-start load, on
pull-to-refresh, on folder adoption, after a conflict scan that touched a friend, after a backup
or contacts import, and when the skin or mode changes (below). Each push maps
`records.activeFriends()` to a compact object (`summarise()` in `widget/WidgetData.kt`), the
shape the web build's `syncWidgetsNative()` built:

```kotlin
FriendSummary(f.id, f.name, cl.code, cl.days.toDouble() / maxOf(1, cadenceOf(f)), isPaused(f, now))
```

The push writes three keys to the SharedPreferences file `forgetmenot_widget_data`
(`WidgetPrefs.kt`) in one `commit()`: `friends_json` (the summary array), `theme_json` (below)
and `pushed_day` (the local date of the push). It then updates all three widgets. The widgets
read the same file, but not by calling it from `provideGlance()`: they declare
`FriendSummaryStateDefinition` (`widget/FriendSummaryState.kt`) as their Glance state, a
read-only `DataStore` whose every read is a fresh parse of the prefs, and take the list from
`currentState()` inside the content lambda.

That indirection is load-bearing. A Glance widget composes inside a session that stays alive
for 45 seconds after its last event, and an update to a live session does not re-run
`provideGlance()`: it re-reads the state definition and recomposes whatever observed it. Read in
`provideGlance()`, the summaries observed nothing, so a push inside that window repainted
nothing and every push landed one behind. As Glance state, an update re-reads and recomposes
whether or not a session is live.

The state definition also names the file Glance keeps for it, and **Glance deletes that file
whenever any widget instance is removed**. So `getLocation()` points at a dummy that nothing
reads or writes, never at the real prefs file: removing one widget from the home screen would
otherwise wipe the summary the others draw from.

The writer (`encodeSummaries()`) and the reader (`decodeSummaries()`) live side by side in
`WidgetData.kt`, so the five fields are kept in step there. The reader is lenient field by
field: a missing field takes its default, and anything that is not an array of objects reads as
no friends. A shape change shows as an empty widget, not a crash.

`weight` is `days / cadenceDays` **uncapped**, deliberately not `classify()`'s `pct`. `pct` is
clamped to 1 for the ring; as a ranking key that clamp ties every overdue friend at 1.0, and
since Kotlin's `sortedByDescending` is stable, `topByWeight` would fall back to index order and
name whoever was added first rather than whoever is most overdue.

`code` is the same compact string Home shows, or `new`. Because the widget stores a formatted
string rather than a date, the label goes stale at midnight. The web build re-pushed on every resume.
Natively, `pushed_day` handles this: each widget's `provideGlance()` calls `refreshIfStale()`,
and a summary pushed on another local day is rebuilt from the records once `container.started`
is done (gated on I6). `provideGlance()` runs whenever a widget session starts: a placement, a
launcher reload, and the three-hourly `APPWIDGET_UPDATE` (`updatePeriodMillis`). So a stale code
lasts at most about three hours past midnight without the app being opened. Opening the app
also pushes: `MainActivity.onResume` awaits `container.started`, then reschedules the reminders
and pushes the widgets.

In a read-only session (I6) nothing is pushed. The cache may be partial, so the widgets keep
the last good summary. `Records.pushWidgets()` and `WidgetPusher` both check
`store.writesBlocked`.

`updateAllWidgets()` gives each widget its own `try`: a single `try` around all three would let
the first throw (most often "this widget isn't placed") skip the others. The pusher launches it
on `AppContainer.appScope`, the process-long scope.

### The reverse path: logging a reach-out from the widget

Spotlight and Thinking of share one card layout (`SmallFriendCard()`, `SpotlightWidget.kt`).
In REGULAR it shows the label on a line of its own, then the name beside a tick 48 dp wide
(`LogTick()`; content description "Log a reach-out with <name>"). Tapping the tick logs a
reach-out **without opening the app**. TINY is 40 dp tall and Top 3's rows are about 18 dp, so
neither has a tick: a target that small is worse than none. The label used to share the line
with the tick and wrapped on a narrow card ("Thinking / of"), hence its own line. The name is
16 sp bold, capped at two lines.

The tap runs `LogReachOutAction` (`widget/LogReachOutAction.kt`), a Glance `ActionCallback`. The
native app ships as a fresh install (I4 retired), so there is no queue for the app to drain. The
callback writes the reach-out itself, through the same `Records` the app uses
(`WidgetTick.log()`):

1. The tap time is stamped first (`Instant.now()`, written in `isoTimestamp()`'s
   `toISOString()` shape), so a tap made while the folder loads is still the tap's time.
2. The widget repaints at once: that friend's summary becomes `today`, weight 0
   (`markTappedToday()`), so a tap in a cold process does not sit for seconds with nothing
   shown. If the tap is not saved (the timeout in step 3, or steps 4 and 5), the old summary is put
   back, unless a real push has replaced the marked one since.
3. On the main thread, it awaits `container.started`, the cold-start load, which a widget tap
   in a cold process starts. Only this wait is capped, at 45 s. Past that, nothing is written
   and the toast reads "Couldn’t save — try again from the app". Once the save starts, it
   finishes.
4. If the session is read-only (the load failed, I6), nothing is written and the toast reads
   "Couldn’t save — open forgetmenot to check the sync folder".
5. If the id is unknown or archived, nothing is written and the widgets are pushed again, so a
   stale name goes away.
6. If the friend's latest reach-out is within 60 s of this tap (`WidgetTick.DUPLICATE_WINDOW`),
   it is the same reach-out again, nothing is written, and the toast reads "Already logged —
   nice.". This catches a second tap made while the first waited for the load.
7. Otherwise it calls `addContact` and `records.saveFriend`. The save pushes the widgets (that
   friend now reads `today`, weight 0) and reschedules the reminders, as a Log from the app does.
   The write is flushed to the folder before returning, since the process may end then. The
   toast reads "Logged — nice.".

There is no undo for a widget reach-out: the tap already happened.

The id travels as a `String` through `ActionParameters`; a `Float` does not survive Glance's
Bundle exactly. The web build's `pending_logs` queue, `takePendingLogs` and the
"Logged N reach-outs from the widget" toast are gone, with `SyncSession.start()`'s drain hook.

### Selection rules

`topByWeight(friends, count)` (`WidgetData.kt`) drops paused friends, sorts by weight
descending and takes `count`. Spotlight and Top 3 both use it, so Top 3 is a superset of
Spotlight.

`weightedRandom(friends, rng)` (`WidgetData.kt`) drops paused friends, weights each
`0.15 + max(0.0, weight)` and rolls once against the running sum. The 0.15 floor is what makes
it a dice roll rather than a ranking: without it a just-contacted friend would be unreachable,
and always picking the most overdue would duplicate Spotlight and turn the widget back into a
nag. `RandomWidget` seeds the roll from the friends: each Responsive size bucket composes
separately, and an unseeded roll showed a different friend per bucket and re-rolled on every
recomposition. Seeded, the pick is stable until the summary changes; the theme is left out of
the seed, so changing skin does not pick someone new.

### Sizes and the responsive layouts

Glance has no `autoSizeTextType` — `Text` takes a fixed `fontSize` — so the substitute is
discrete buckets off `LocalSize.current` under `SizeMode.Responsive`, which hands the launcher
every variant up front so it picks the largest that fits. The default `SizeMode.Single`
composes once and lets the launcher stretch the result, which clips instead of adapting.

Spotlight and Thinking-of share `SMALL_CARD_TINY = DpSize(110.dp, 40.dp)` and
`SMALL_CARD_REGULAR = DpSize(110.dp, 68.dp)` (`WidgetData.kt`). Below REGULAR's height the
label line ("Maybe say hi to" / "Thinking of", 12 sp) goes, the name drops to 14 sp and the
padding to 8 dp: 16.8 + 16 = 33 dp inside the 40 dp cell. Their provider XML declares
`minWidth 110dp`, `minHeight 68dp`, `minResizeHeight 40dp`, `targetCellWidth 2` ×
`targetCellHeight 1`.

Top 3 declares `COMPACT = DpSize(180.dp, 90.dp)` and `REGULAR = DpSize(180.dp, 120.dp)`;
`widget_topthree_info.xml` declares `minWidth 180dp`, `minHeight 120dp`, `minResizeHeight 32dp`,
`targetCellWidth 3` × `targetCellHeight 2`. The compact branch drops the per-row code and
tightens row spacing from 6 dp to 2 dp, needing 89 dp. That budget holds because every name is
one line (`maxLines = 1`): on the Pixel launcher at one row (about 103 dp) a name that wrapped
pushed the third row out. Its layout is covered by a Glance unit test (`WidgetLayoutTest`), but
on a device it has only been reached on the emulator's launcher.
The 32 dp floor is a guess at the test phone's launcher cell threshold. If one cell there is
shorter than 89 dp, the compact layout clips and needs a third bucket designed against the real
height.

All three set `updatePeriodMillis="10800000"` (three hours). It is a backstop: the real refresh
is the push, and the periodic update now also triggers the day-change refresh above. `sp`
scales with the system font size while the `dp` box does not, so the declared `minHeight`s
carry headroom over what the type needs at 1×: 68 dp for a layout that fits in 40 dp. Raising
`minHeight` only affects new placements and the resize floor.

### Colours and backgrounds

The widgets wear the app's active skin and mode. Each push stores the four widget tokens of
`settings.skin`/`settings.mode` as `#RRGGBB` strings under `theme_json`:
`ThemeTokens.widget(ThemeTokens.resolve(skin, mode))` (`ui/theme/ThemeTokens.kt`), that is
`bg`, `text`, `textMuted` and `primary` of the same palette the app draws with. An unknown skin
or mode resolves to the whole of Cyanotype light, as the app's own fallback does (§7).
`Records.saveSettings` has no effects, so `pushOnThemeChange()` (`WidgetPusher.kt`, started by
`AppContainer.started`) watches `store.revision` and pushes when the (skin, mode) pair changes.
Other settings saves do not push.

`decodeTheme()` parses the stored theme into a `WidgetTheme`. If any colour is missing or
unreadable it falls back to the whole default, never a mix. The default is
`DEFAULT_WIDGET_THEME = ThemeTokens.widget(ThemeTokens.DEFAULT)`, Cyanotype light (`#E9F1F7`,
`#142F44`, `#526A7C`, `#0A5188`), which is also what a widget shows before the app has run.

The theme is part of the Glance state with the summaries (`WidgetState`), so a theme change alone
recomposes a live widget. All three cards sit on `bg`, where text and accent clear 7:1 and muted
4.5:1 in all ten palettes (`ThemeTokensTest`). Every colour passes the same value for day and
night (`fixedColor()`), because the app's own mode, not the system's, decides Light or OLED.

The card is one white `<shape>`, `widget_card_bg.xml`, tinted to `bg` with
`background(…, colorFilter = ColorFilter.tint(…))`. The drawable exists because Glance's
`cornerRadius()` compiles to an API 31+ call that silently does nothing at this app's
`minSdk 30`; a plain `<shape>` rounds on every level. The `.cornerRadius(16.dp)` calls stay for
child clipping on 31+.

Labels are 12 sp at the smallest: Spotlight "Maybe say hi to", Thinking of "Thinking of", Top 3's
header "Maybe say hi". With no one to name, a widget says why (`emptyLabel()`): "Add someone to
think of" ("Add someone" in a tiny card) when there is no friend, "Everyone's paused" when every
friend is. It never says "All caught up", which it used to when everyone was paused, and there
is nothing to be caught up on.

### initialLayout

All three providers set `android:initialLayout="@layout/glance_default_loading_layout"`, the
placeholder shipped with `glance-appwidget` (`widget_spotlight_info.xml` says why). It is
required: Glance composes asynchronously, and without an `initialLayout` the launcher inflates
resource 0, throws `Resources$NotFoundException` and leaves the widget blank until composition
finishes — on every host reload, not just first placement. Each provider also declares
`android:previewImage`, backed by `widget_preview_spotlight.png` / `_topthree.png` /
`_random.png` at all five densities. They are drawn by `tools/widget-previews.py` from the same
numbers the widgets use (`SmallFriendCard`, `TopThreeContent`, `DEFAULT_WIDGET_THEME`): the
current wording in Cyanotype light, the skin a fresh install starts on. The script draws in the
device's Roboto, pulled from a device or emulator (`adb pull /system/fonts/Roboto-*.ttf`) since
Windows has none. Rerun it when the wording, a size or the default colours change.
`WidgetResourcesTest` checks the provider attributes, the previews at five densities and the
receiver names.

---

## 6. Platform integrations: what the app asks of Android

There is no bridge layer. Every platform service the app uses is called directly from Kotlin, and
each one sits behind a small interface whose default does nothing, so a screen built before its
integration existed, or a JVM test standing in for Android, simply offers less instead of failing.
The interfaces are `FolderFs` (`FolderFs.kt:38`) for the sync folder, `AddIntegrations`
(`AddIntegrations.kt:16`) and `DetailIntegrations` (`DetailIntegrations.kt:13`) for the add and
detail screens, and `SettingsPlatform` (`SettingsPlatform.kt:23`) for the reminder rows. The real
implementations are built once per process or per activity: `MainActivity` constructs one
`DeviceIntegrations` (`MainActivity.kt:82`) and provides it to both screens through composition
locals, over an `ActivityResultsBridge` that has to be created as a field initialiser
(`MainActivity.kt:45`), because `registerForActivityResult` may only be called while the activity
is being created.

| What the app needs | Platform API | Where |
| --- | --- | --- |
| A folder a sync app also sees | Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE` + `DocumentsContract` | `data/SafFolder.kt`; section 3, and below |
| Home-screen widgets | Glance `GlanceAppWidget` + `AppWidgetProvider` receivers, SharedPreferences | `widget/`; section 5 |
| Reminders at a set time | `AlarmManager` exact / inexact alarms, `NotificationManager`, boot broadcasts | `reminders/`; section 4 |
| Message a friend | `ACTION_VIEW` on an `sms:` / `mailto:` / `https://wa.me/…` link | `integrations/MessageLinks.kt`, `integrations/ShareIntents.kt` |
| Plan something | `ACTION_INSERT` on `CalendarContract.Events`, else an `.ics` through `ACTION_SEND` + FileProvider | `DeviceIntegrations.plan()`, `integrations/ShareIntents.kt` |
| Share a friend card | `ACTION_SEND` `text/plain` in a chooser | `DeviceIntegrations.share()`, `integrations/ShareIntents.kt` |
| Import from contacts | `ContactsContract`, `ActivityResultContracts.PickContact`, `READ_CONTACTS` | `integrations/ContactsSource.kt`, `integrations/ContactRows.kt` |
| An avatar photo | System Photo Picker, `ActivityResultContracts.PickVisualMedia` | `integrations/DeviceBridge.kt`, `integrations/Avatars.kt` |

**Failures carry a code where the caller branches on them, and a toast everywhere else.** The
sync folder throws `FolderException` with one of the `FolderError` codes (`FolderFs.kt:11-27`);
`readFileStrict()` (`CachedStore.kt:185`) treats only `NOT_FOUND` and `NO_FOLDER_SELECTED` as
absent and rethrows everything else, and that test is what separates "absent" from "failed" in
section 2. The device integrations have no caller that branches: each one toasts its own failure
and returns null or false, so the screen has nothing to apply (`DeviceIntegrations.kt:22-27`).

### The sync folder — `SafFolder.kt`

A Storage Access Framework tree the user picked, with named-file read/write/delete/list scoped to
it (`SafFolder.kt:14-27`). No storage permission is involved: the system picker is what grants
access. Section 3 covers the picker (`pickContract()`, `SafFolder.kt:42`), the lasting grant
(`takeGrant()`, `SafFolder.kt:79`) and why the grant is re-derived on every call
(`currentRoot()`, `SafFolder.kt:54`).

| Method | Arguments | Returns | Throws | Called from |
| --- | --- | --- | --- | --- |
| `status()` | none | `FolderStatus(granted, name)` | nothing — always answers | `fsStatus()` (`CachedStore.kt:127`) |
| `writeFile()` | `path` (`/`-separated, relative to the root), `data` (written UTF-8) | nothing | `NO_FOLDER_SELECTED`, `FOLDER_UNAVAILABLE`, `MISSING_PATH`, `WRITE_FAILED` | `CachedStore` |
| `readFile()` | `path` | the text | `NO_FOLDER_SELECTED`, `FOLDER_UNAVAILABLE`, `MISSING_PATH`, `NOT_FOUND`, `READ_FAILED` | `readFileStrict()` (`CachedStore.kt:185`), `readFileJson()` (`CachedStore.kt:177`) |
| `readFiles()` | `paths` | `Map<path, text>` — absent and unreadable paths are omitted, not reported | `NO_FOLDER_SELECTED`, `FOLDER_UNAVAILABLE` | `fsReadMany()` (`CachedStore.kt:130`) |
| `deleteFile()` | `path` | nothing | `NO_FOLDER_SELECTED`, `FOLDER_UNAVAILABLE` | `CachedStore` |
| `readdir()` | `path` (default `""`, the root) | bare names | `NO_FOLDER_SELECTED`, `FOLDER_UNAVAILABLE` | `fsReaddir()` (`CachedStore.kt:126`), `listRecordIds()` (`CachedStore.kt:194`) |

Every method blocks; `CachedStore` runs them one at a time on a one-thread view of
`Dispatchers.IO` (`CachedStore.kt:33-40`).

**One query per directory.** `DocumentFile` has no lookup by name: `findFile()` lists the whole
directory, and `listFiles()` plus `.name` costs a provider query per child. So every lookup goes
through `children()` (`SafFolder.kt:130`), one `DocumentsContract` query returning a name-to-URI
map; on a duplicate name the first match wins, as `findFile()` did, and a directory that cannot be
listed reads as empty. `readFile()` still lists the directory on every call, so reading N records
one at a time is N listings; `readFiles()` (`SafFolder.kt:261`) lists each directory once and
answers the whole batch from it.

Measured on an Android 14 test phone against the web build, whose folder code used the same
listing, a 201-friend folder with 40 avatars cold-started on 4 single reads and 4 batches where
one call per record would have been 241, taking about 0.57 s at 20 friends and 6.4 s at 201. That
is near enough linear at ~30 ms a record, so the remaining cost is the per-file
`openInputStream`, and getting below it would mean fewer, larger files — the one thing the
per-record layout exists to avoid. At a few dozen friends the load is a fraction of a second.

The listing is deliberately **call-scoped**, built and dropped within one `readFiles()` call: the
folder is written by another app while forgetmenot runs, and a longer-lived listing would be a
window for reading a directory that has since changed — the risk `currentRoot()` refuses by
re-deriving the grant on every call. `fsReadMany()` chunks at `READ_BATCH` (`CachedStore.kt:279`),
100 paths, so one call's memory stays bounded; 500 avatar sidecars at ~10 KB would be 5 MB of text
held at once.

**`writeFile()` derives the mime type from the extension** (`mimeFor()`, `SafFolder.kt:117`). A
SAF provider names a created file from the `(mimeType, displayName)` pair: AOSP's
`FileUtils.splitFileName` keeps the name only when its extension already maps to the mime passed,
and otherwise appends the mime's own extension. A provider that renamed `<id>.json` that way would
make every later lookup miss and every save create another file, silently. `application/json` for
`.json` and octet-stream for everything else, `.b64` included (it has no registered type), keep
both on the exact-match path. On `ExternalStorageProvider` names landed as given under a generic
mime too, so this is hardening rather than a fix for something observed.

**`writeFile()` never truncates a file in place** (`replaceAtomically()`, `SafFolder.kt:189`). It
writes `<name>.tmp`, renames the old file to `<name>.bak`, renames the `.tmp` into place and
deletes the `.bak`; if the second rename fails the `.bak` goes back and the call throws
`WRITE_FAILED`. A new file is just `.tmp` then rename. Every rename is checked to have landed on
exactly the name asked for (`rename()`, `SafFolder.kt:168`), since a provider that finds the name
taken picks `<name> (1)`; a provider that cannot rename at all falls back to truncate-and-write.
Recovery is in the listing: `children()` calls `recoverInterrupted()` (`SafFolder.kt:153`), which
renames a `<name>.bak` with no `<name>` beside it back into place, so every read, list and write
sees the old complete copy after a crash between the two renames. That is safe only because
`CachedStore` runs the folder's calls one at a time, so no write of this app is ever mid-swap when
a listing runs (`SafFolder.kt:24-25`). `deleteFile()` removes `<name>.tmp` and `<name>.bak` as
well (`SafFolder.kt:286-290`), or a stray `.bak` would bring a deleted record back. Verified on the
Android 11 test phone by planting each crash state by hand (A.3).

Three asymmetries are deliberate. `writeFile()` creates missing intermediate directories
(`resolveDirs(..., create = true)`, `SafFolder.kt:232`); the read, delete and list methods do not.
`deleteFile()` returns quietly when the file was already gone (`SafFolder.kt:282`). `readdir()`
returns an empty list for a missing directory (`SafFolder.kt:293-297`), so a first run with no
`friends/` yet is not an error path.

### Widgets — `widget/`

The app writes; the widgets only read. `WidgetPusher.pushWidgets()` (`WidgetPusher.kt:37`) writes
the summary array, the active skin's four widget colours and the day of the push into the
`forgetmenot_widget_data` prefs file (`WidgetPrefs.kt:5-11`) with `commit()`, not `apply()`, and
then asks all three widgets to update (`updateAllWidgets()`, `WidgetData.kt:152`), each in its own
`try` so one unplaced widget cannot stop the others. A tap on a widget's tick runs
`LogReachOutAction` (`LogReachOutAction.kt:101`) in the app's own process and writes the
reach-out straight to the friend's record through `WidgetTick` (`LogReachOutAction.kt:48`); there
is no queue for the app to drain. Every write of that prefs file holds `widgetPrefsLock`
(`WidgetData.kt:146`).

Section 5 covers the summary shape, the tap's provisional repaint and its undo, and why the
widget opens a friend through an explicit intent (`friendIntent()`, `WidgetData.kt:163`) rather
than an intent filter.

### Exact alarms and notifications — `reminders/`

| What | API | Where |
| --- | --- | --- |
| Arm a reminder | `setExactAndAllowWhileIdle` when the exact-alarm grant allows it, else `setAndAllowWhileIdle` | `arm()` (`ReminderScheduler.kt:132`) |
| Read the exact-alarm gate | `AlarmManager.canScheduleExactAlarms()`, granted below API 31 | `ExactAlarms` (`ExactAlarms.kt:16`) |
| Nightly rebuild | an inexact `setAndAllowWhileIdle` heartbeat | `armHeartbeat()` (`ReminderScheduler.kt:122`) |
| Post a nudge | one `reminders` channel, `NotificationCompat`, immutable `PendingIntent`s for the body and buttons | `ReminderNotifications.post()` (`ReminderNotifications.kt:26`) |
| Fire, heartbeat and button presses | the unexported `ReminderReceiver` | `ReminderReceiver` (`ReminderReceiver.kt:18`) |
| Reboot, update, exact-alarm grant | the exported `BootReceiver` | `BootReceiver` (`BootReceiver.kt:23`) |
| Ask for notifications | `POST_NOTIFICATIONS` runtime dialog, API 33+ only | `canAskNotifications()` (`SettingsPlatform.kt:44`) |
| Send the user to the exact-alarm toggle | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` for this package | `openExactAlarmSettings()` (`SettingsPlatform.kt:60`) |
| Battery optimisation | `isIgnoringBatteryOptimizations()`; opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | `batteryUnrestricted()` (`SettingsPlatform.kt:51`), `openBatterySettings()` (`SettingsPlatform.kt:68`) |

Battery optimisation is the other thing that holds a reminder back, and some phone makers apply it hard.
Settings → Battery limits shows `isIgnoringBatteryOptimizations()`; tapping opens the system's
battery-optimisation list, where the user exempts the app. The direct per-app request needs
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play restricts, so it is not declared
(`SettingsPlatform.kt:66-68`).

Section 4 covers the gate. Opening the exact-alarm screen returns at once, long before the user has
toggled anything; the grant lands either through `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`,
which `BootReceiver` turns into a rebuild (`BootReceiver.kt:51`), or on the next resume, which
reschedules (`onResume()`, `MainActivity.kt:114`). Some OEM builds lack that settings screen, so a
failure to start it is swallowed (`SettingsPlatform.kt:58-64`).

### Sharing — `ShareIntents.kt`

Both shares hand the work to another app through a chooser. `startActivity` returns as soon as the
chooser is started, **not** when the user picks a target or backs out; there is no result, so the
app cannot tell "shared" from "cancelled" and does not try.

- **A friend card** is the vCard text from `buildVCard()` (`Share.kt:21`), sent as
  `ACTION_SEND` `text/plain` inside `Intent.createChooser` (`textShareIntent()`,
  `ShareIntents.kt:27`), titled "Share <name>" (`DeviceIntegrations.kt:130-136`). A vCard pastes
  fine into contact apps; no file is involved.
- **An `.ics` invite** is the fallback when no calendar app took the `ACTION_INSERT` intent below:
  calendar apps generally add an event only from an attachment, not from `EXTRA_TEXT`.
  `writeShareFile()` (`ShareIntents.kt:37`) writes the text into `cacheDir/shared/`, cleared first
  so earlier invites do not pile up, and returns a `content://` URI from the app's FileProvider.
  The filename goes through `File(filename).name` (`prepareShareFile()`, `ShareIntents.kt:41-43`),
  so a `../` or leading `/` cannot escape the cache subfolder, and `icsFileName()`
  (`MessageLinks.kt:42`) has already reduced it to `time-with-<name>.ics`. `fileShareIntent()`
  (`ShareIntents.kt:47`) sends it as `EXTRA_STREAM` with `FLAG_GRANT_READ_URI_PERMISSION` and the
  URI as `ClipData` too, so the chosen target can read that one file for this share only.

### Calendar — `DeviceIntegrations.plan()`

"Plan something" builds a one-hour slot a week from today at 18:00 local (`planWindow()`,
`MessageLinks.kt:31`) and starts `ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI` with
the title and both times as extras (`calendarInsertIntent()`, `ShareIntents.kt:19`). That opens the
calendar app's own new-event screen, prefilled; the user saves or discards it there. The app never
writes the calendar provider, so no calendar permission is declared. `plan()`
(`DeviceIntegrations.kt:111`) falls through to the `.ics` share only on
`ActivityNotFoundException`, and toasts "No calendar app found on this device" only when that
share could not start either.

### Message links — `MessageLinks.kt`

`channelUrl()` (`MessageLinks.kt:11`) maps a channel to a link: `sms:<phone>`,
`https://wa.me/<digits>`, `https://t.me/+<digits>`, `https://signal.me/#p/+<digits>` or
`mailto:<email>`, null when the friend lacks the field. `messageUrl()` (`MessageLinks.kt:25`) takes
the friend's preferred channel, else SMS, else email. `message()` (`DeviceIntegrations.kt:100`)
starts `ACTION_VIEW` on it (`viewIntent()`, `ShareIntents.kt:16`), so the messaging app — or a
browser, for the `https` links — claims it; this app opens no connection. It returns true only when
something took the link, which is what makes the detail screen ask "Talked to …?" on return; with
no address, or no app for the link, it toasts and returns false.

### Contacts import — `ContactsSource.kt`, `ContactRows.kt`

Read only, and only with `READ_CONTACTS`. Both paths ask for it at the moment of use
(`ensureContactsPermission()`, `DeviceIntegrations.kt:90`), and a refusal toasts "Contacts
permission denied" and applies nothing.

- **One contact**: the contacts app's picker (`ActivityResultContracts.PickContact`,
  `DeviceBridge.kt:50`), then `readOne()` (`ContactsSource.kt:31`) resolves the picked URI to its
  contact id and reads that contact's rows. Its photo, the high-resolution one when there is one
  (`photo()`, `ContactsSource.kt:38-39`), goes through the same 128 px pipeline as a picked image.
  A failed read after the pick says nothing; the form is left as it was (`DeviceIntegrations.kt:57-74`).
- **Several at once**: `readAll()` (`ContactsSource.kt:27`) is one query on
  `ContactsContract.Data` for the name, phone, email and event mime types (`CONTACT_MIMES`,
  `ContactRows.kt:28`), with an explicit projection (`ContactsSource.kt:22-24`), instead of one
  query per contact. A failure toasts "Could not read your contacts".

`assembleContacts()` (`ContactRows.kt:38`) turns the rows into one entry per contact: the
structured display name, else given + family, else the contact's display name; the primary phone
and email, else the first; and the first birthday event that `parseContactBirthday()`
(`ContactRows.kt:61`) can read, which accepts `1990-03-05`, `--03-05`, `19900305` and `--0305`
with an optional time after it, and stores an unknown year as 1900.

### Avatars — `Avatars.kt`

`pickImage()` (`DeviceBridge.kt:58`) opens the system Photo Picker through
`ActivityResultContracts.PickVisualMedia` with `ImageOnly` (`DeviceBridge.kt:59`). The picker
grants access to the one image picked, so no gallery or storage permission is declared.
`DecoderAvatarImages` (`Avatars.kt:44`) decodes it with `ImageDecoder`, which applies the EXIF
rotation and decodes a large photo subsampled (`sampleSizeFor()`, `Avatars.kt:24`), so a 50 MP
picture never sits in memory at full size; `avatarDataUri()` (`Avatars.kt:31`) then takes the
centred square, scales it to `AVATAR_SIZE` (`Avatars.kt:14`), 128 px, and returns a JPEG at
quality 85 as a `data:` URI. An unreadable image toasts "Could not read that image"
(`pickPhoto()`, `DeviceIntegrations.kt:44`). One system dialog runs at a time: the bridge's three
launchers share a mutex, and a result lost to process death leaves nothing waiting; the user just
picks again (`DeviceBridge.kt:26-33`).

### Manifest surface

`android/app/src/main/AndroidManifest.xml` declares four permissions and no more:

| Permission | Why |
| --- | --- |
| `SCHEDULE_EXACT_ALARM` (`AndroidManifest.xml:16`) | So a nudge fires at its scheduled time. Never `USE_EXACT_ALARM` alongside it (section 4). Declaring it only makes it *requestable*: Android 14+ denies it by default on a fresh install, with no runtime dialog (`AndroidManifest.xml:4-15`). |
| `POST_NOTIFICATIONS` (`AndroidManifest.xml:17`) | The reminders themselves, requested at runtime on API 33+. |
| `RECEIVE_BOOT_COMPLETED` (`AndroidManifest.xml:18`) | A reboot drops every alarm; `BootReceiver` re-arms the saved plan. Without it the schedule is lost on every restart. |
| `READ_CONTACTS` (`AndroidManifest.xml:24`) | Contact import, single and bulk. `WRITE_CONTACTS` is not declared: the app never writes a contact (`AndroidManifest.xml:20-23`). |

There is deliberately **no `android.permission.INTERNET`** (`AndroidManifest.xml:26-30`): fonts are
bundled and every outbound link is handed to another app by an implicit intent. The application
still names `networkSecurityConfig` (`AndroidManifest.xml:70`), and
`network_security_config.xml:8` pins `cleartextTrafficPermitted="false"` as a standing policy, in
case a future dependency declares INTERNET unnoticed.

**The shipped app holds more than those four.** Manifest merging adds, as read off a debug build's
merged manifest and the merger's blame report:

| Merged in | From | Why it arrives |
| --- | --- | --- |
| `WAKE_LOCK` | `androidx.work:work-runtime:2.7.1` | WorkManager holds the CPU awake while a worker runs. |
| `ACCESS_NETWORK_STATE` | `androidx.work:work-runtime:2.7.1` | WorkManager's constraint trackers read connectivity. |
| `FOREGROUND_SERVICE` | `androidx.work:work-runtime:2.7.1` | WorkManager's `SystemForegroundService`. |
| `com.hazia.forgetmenot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core` | A `signature`-level permission androidx.core defines for the app's own runtime-registered receivers; nothing outside the app can hold it. |

WorkManager is not a dependency anyone here chose: `androidx.glance:glance`, pulled in by
`glance-appwidget` 1.2.0, depends on `work-runtime` (still 2.7.1), which also merges about a dozen
components of its own, each unexported or guarded by a permission only the system holds; Glance
adds six more of its own (trampoline activities, receivers and its `RemoteViewsService`).

**Do not strip these with a `tools:node="remove"` merger rule.** It is the obvious move for an app
that advertises having no network permission, and it is wrong here. Glance does not merely depend
on WorkManager, it runs on it: it hosts each widget composition in a `SessionWorker` enqueued
through `WorkManager.enqueueUniqueWork`, so **every widget repaint goes through WorkManager**. (In
the 1.1.1 artifacts the `glance` jar references `androidx/work/` 46 times and `glance-appwidget`
none, which is why the dependency looks like dead weight.) A removed permission that WorkManager's
trackers need would fail inside a widget update, and the per-widget `try` (section 5) would
swallow it: widgets that quietly stop refreshing, indistinguishable from "no data yet".

What is true is narrower: `ACCESS_NETWORK_STATE` permits *reading* connectivity, not opening a
socket, which still needs the absent `INTERNET`. The cost is disclosure — an APK inspector or
Settings → App info shows a network-shaped permission — and the honest fix for that is the listing
text, not the manifest.

The three `<queries>` entries (`AndroidManifest.xml:38-56`) exist because minSdk is 30 and
package-visibility filtering applies. `startActivity()` itself does not need visibility, but any
`resolveActivity()` or `queryIntentActivities()` check would see no target and the app would take
its fallback path though a suitable app is installed (`AndroidManifest.xml:32-37`). They are
`ACTION_INSERT` + `vnd.android.cursor.item/event` (the calendar hand-off), `ACTION_SEND` +
`text/plain` (the vCard chooser) and `ACTION_SEND` + `text/calendar` (the `.ics` chooser) — the
last needs its own entry because visibility is matched per action *and* mime type.

The FileProvider (`AndroidManifest.xml:90-98`) has authority `${applicationId}.fileprovider`, the
same string `writeShareFile()` builds from `context.packageName` (`ShareIntents.kt:38`), so changing
the applicationId keeps them aligned. It is `exported="false"` with `grantUriPermissions="true"`,
and `file_paths.xml:9` scopes it to one `<cache-path name="shared" path="shared/" />`: not
external storage, not the files root, not the whole cache. Its only consumer is the `.ics` share.

The receivers are five. `ReminderReceiver` (`AndroidManifest.xml:102-104`) is unexported: only the
app's own `PendingIntent`s reach it. `BootReceiver` (`AndroidManifest.xml:111-119`) must be exported
for the system to deliver `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and
`SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`; it ignores any other action (`BootReceiver.kt:24-27`),
and a forged one of those only causes a harmless rebuild. The three widget receivers
(`AndroidManifest.xml:122-158`) are exported for `APPWIDGET_UPDATE`, as every app-widget provider
must be. The one activity is `singleTask` (`AndroidManifest.xml:77`) with only the launcher intent
filter, so a widget tap while the app runs arrives at `onNewIntent()` (`MainActivity.kt:126`), and
no deep-link filter exists for another app to aim at.

Also in the manifest: `allowBackup="false"` (`AndroidManifest.xml:64`), so Auto Backup never copies
the folder grant, the reminder plan, the widget summaries or the shared `.ics` to Google Drive and
`adb backup` gets nothing (`AndroidManifest.xml:58-62`).

---

## 7. Theming, accessibility and interaction

### The theme matrix

Five skins — `blue` (Cyanotype), `green` (Celadon), `pink` (Dusk), `amber` (Apricot) and
`lavender` (Provence) — times Light and OLED, for ten complete palettes, retuned for the native
app on 2026-10-17. `Skin` (`ThemeTokens.kt:13-19`) holds the settings keys and display names and
`Mode` (`ThemeTokens.kt:22`) the two modes; the stored mode keys stay `light` and `dark`, so
settings files and backups keep working, and "OLED" is only what the dark mode is now called.
Every colour is one row of `palettes` (`ThemeTokens.kt:57-68`), as plain ARGB ints with no
Compose types, so the widgets (section 5) read the same values the app draws with. The theme
picker's swatch is each skin's primary in the active mode (`swatch()`, `ThemeTokens.kt:91`),
which clears 7:1 on that mode's backgrounds.

`ForgetmenotTheme(skin, mode)` (`Theme.kt:51`) takes the two stored strings and is the only thing
that changes theme: it resolves them to a palette, maps it onto a Material 3 `ColorScheme` with
every role pinned to a token (no baseline purple, no tonal-elevation tint: `surfaceTint` is
`surface`, `Color.kt:71`), and re-applies the system bars. Every component reads the same roles,
so nothing else branches on theme. Adding a skin means one `Skin` entry and two palette rows.

The app's own mode, not the system's, picks Light or OLED. Defaults are `blue`/`light`; until the
stored choice is read, the window behind the first frame is Cyanotype light's `bg`
(`values/colors.xml:5`), never a "last theme".

The web build's earlier eight palettes (Skylight, Meadow, Rosewater, Ember) are gone with it.

### Token roles

Every colour is one of these thirteen roles. Each palette was solved lightness-only at fixed
OKLCH hue and chroma to the floors below, then every role's hue was placed on one Matsuda
harmony template — measured over all thirteen, the warn pair included — so a palette reads as
one family. `ThemeTokensTest` checks every floor and the harmony score in all ten palettes.

- `bg`, `surface` — the page and the cards on it. Never `#FFFFFF` in Light; in OLED `bg` sits at
  3–6% lightness with a hue tint, never `#000000`, so most pixels are nearly off without the
  smearing and vibration of pure black.
- `text` — body copy, 7:1 on both `bg` and `surface` (AAA: phones are read outdoors).
- `textMuted` — secondary copy, 4.5:1 on both: a solved colour, not `text` at an opacity.
- `primary` — the accent and the primary action, 7:1 on both backgrounds, so it works as text
  (Save), as a fill, and as the 2 dp border that marks the chosen theme and mode button.
- `onPrimary` — what sits on a `primary` fill, 7:1 on it.
- `decorative` / `onDecorative` — the soft chip and avatar pair, 7:1. Pink appears only here and
  in `press`: never as text, never on a warning.
- `border` — decorative hairlines, about 1.6:1; visible, but not a control boundary.
- `borderStrong` — the whole boundary of chips, inputs and the switch track, 3:1 (WCAG 1.4.11).
- `press` — the pressed background, with `text` over it at 7:1.
- `warnBg` / `warnText` — the sync-conflict banner, 7:1: straw to citron in every palette, never
  red, tuned per palette so it sits on the same harmony template as the rest.

No role is red, and no ink role (text, muted, primary, the warn pair) is pink; the test checks
both by OKLCH hue. The pinned identity colours are Celadon's bisaek glaze `#A2CDC5` and sanggam
ink `#202726`, and Dusk's blush `#F4C6D6`/`#E9AFC4`; they hold those two palettes at a lower
harmony score (template fit 0.90–0.92) than the others (1.00).

The non-colour tokens are the type scale and the corner radii (12, 14 and 16 dp).

### The unrecognised-theme fallback

A hand-edited settings file or a backup from a newer build can name a skin or mode this build
lacks. `resolveKeys()` (`ThemeTokens.kt:78-82`) matches keys exactly and gives the whole of
blue/light when either is unknown — never a mix. Backup import also coerces an unknown skin to
`blue` and a mode other than `light`/`dark` to `light` (`validateBackupData()`,
`Validate.kt:47-52`), checking the skin against `THEMES` (`Config.kt:69-75`), which lists
`lavender` with the other four.

### System bars

Both bars are transparent and show `bg` through them: `enableEdgeToEdge()` in `MainActivity.kt`,
then `SystemBars()` (`Theme.kt:82-94`) turns off the system's contrast scrims and sets the icon
colour from `bg` (`statusBarDarkIcons()`, `ThemeTokens.kt:95`): dark icons in every Light
palette, light ones in every OLED one. `isLightColor()` (`ThemeTokens.kt:116`) applies WCAG's
relative-luminance curve and thresholds at 0.4, as the web build did. This is the only place
native chrome follows the in-app theme; it runs again on every theme change.

`AppFrame()` (`Theme.kt:110-117`) paints `bg` behind both bars and pads its content below the
status bar and any cutout and clear of the side insets (cutouts, the 3-button bar in landscape),
consuming them so a `Scaffold` inside does not pad twice. The bottom is left to each screen so
lists scroll under the gesture bar: a screen pads its last element, or a lazy list's content,
with `bottomInsets` (`Theme.kt:120-121`), which is the navigation bar and, when it is up, the
keyboard.

The web build drew a `--primary` strip behind the status bar instead; that rule is retired with
the palettes it was solved for.

### Dialogs and focus

The system's own dialogs are not used to ask questions: they ignore the skin and stack a second
kind of modal on a sheet. `AskState` (`AskSheet.kt:48`) is the app's own question, drawn by
`AskHost()` (`AskSheet.kt:79`) as one more sheet. Each screen keeps one and draws its `AskHost`
after its other sheets, and a sheet opened later is a newer window, so the question stacks above
any sheet it is asked from. `confirm()` (`AskSheet.kt:64`) resolves to true on confirm, and
`prompt()` (`AskSheet.kt:68`) to the trimmed, non-empty text; both resolve to no however else the
sheet closes (Cancel, Close, back, a scrim tap), and a second question replaces an unanswered
first, which resolves to no (`AskSheet.kt:54`). A prompt focuses its field as it opens, so the
keyboard comes up (`AskSheet.kt:101`). `confirm()` is kept for what cannot be undone (deleting a
friend for good, replacing data with a backup, discarding a sync conflict's other side);
`prompt()` names a new tag and takes the short free-text fields that are one line each ("Next time,
ask about", a gift idea). Deleting a group asks through a Material `AlertDialog` instead
(`ConfirmDeleteDialog()`, `GroupSheets.kt:263-272`), a stand-in until the group screens move to
`AskState`. Everything reversible acts at once and goes on the recent-changes list (section 9).

All sixteen sheets are Material `ModalBottomSheet`s: thirteen through `AppSheet()`
(`SheetCore.kt:96-142`), the three group sheets through `GroupSheet()` (`GroupSheets.kt:65`),
which live with the groups screen because nothing else opens them. A modal sheet is its own
window over a scrim: taps, keys and back go to it, not to the screen behind, which is what the
web build's `inert` had to add by hand. `AppSheet()` does three things, each load-bearing:

1. Names its pane from the live title (`paneTitle = title`, `SheetCore.kt:132`), and marks the
   title row as a heading (`SheetTitle()`, `SheetCore.kt:145`). Several titles change at runtime
   (the onboarding steps), so the name follows the title it is drawn with rather than a label
   wired up separately. The group sheets mark their title as a heading but set no pane title.
2. Moves no focus on open. Nothing in a sheet is focused until the user moves there, so no sheet
   opens with a ring around its first control, which is usually the dismissive one ("Not now");
   the one exception is a prompt's text field, above.
3. Funnels every way it closes (Close, back, a scrim tap, a swipe, or the content's own
   `close()`) into `onDismiss`, exactly once: a `closing` flag guards the two routes
   (`SheetCore.kt:107-118`). So the per-sheet hooks run however a sheet is closed: paying the
   group-member reschedule (`GroupDetailScreen.kt:162-163`) and marking an onboarding step seen
   (`dismiss()`, `Onboarding.kt:73-77`). `close()` animates the sheet away first and calls
   `onDismiss` when the hide finishes; with reduced motion it calls it at once
   (`SheetCore.kt:110`).

Nothing records and restores focus by hand: the sheet is a separate window, so the screen's own
focus is never moved, and nothing behind it is destroyed by a re-render while it is open. Back
closes only the topmost sheet, since each is its own window. A closed sheet is not composed at
all, so it cannot linger in the focus order the way the web build's merely transparent dialogs
did. The theme picker saves in place and the buttons recompose with the new selection under the
finger, so focus stays on the button just pressed with no refocusing.

`SheetStack` (`SheetCore.kt:75-83`) tracks the open sheets bottom to top, and the toast is drawn
only by the topmost (`ToastHost()`, `Feedback.kt:142-148`): a sheet's window would otherwise
cover the screen's toast.

### Keyboard operability

Every interactive element is a Material control or carries Compose's `clickable`, `toggleable`
or `selectable` with a `Role`, which makes it focusable and activates it from the keyboard and
D-pad, and gives it its name and state for TalkBack. There is no app-wide key listener. Home rows
are two sibling targets, open and Log, never one nested in the other (`FriendRow()`,
`HomeRow.kt:85`).

**The rule this imposes on future code:** a tappable element is written with one of those
modifiers and a `Role`, never with a bare `pointerInput` tap detector, which is invisible to the
keyboard and to TalkBack. (The search bar's tap-to-focus is a `pointerInput` only because the
field inside it is the real, focusable target, `HomeScreen.kt:424`.) State goes on `selectable`
for the theme and mode buttons (`PickerButton()`, `ListSheets.kt:137-150`), on Material's
`FilterChip` for toggle chips (`ChoiceChip()`, `SheetCore.kt:220`), on `toggleable` with
`Role.Switch` for the switch rows (smaller home and quiet mode, `SwitchRow()`,
`SettingsScreen.kt:307-322`; pause, `DetailScreen.kt:430`; group members), and with
`Role.Checkbox` for the contacts-import checklist rows (`ContactsImportSheet.kt:101`). In every
switch row the whole row is the toggle and the painted `Switch` takes no input of its own
(`onCheckedChange = null`), so there is one target per row.

The toast is a polite live region (`liveRegion = LiveRegionMode.Polite`, `Feedback.kt:160`). The
only interactive element kept out of the focus order is the search-clear button
(`canFocus = false`, `HomeScreen.kt:449`), which duplicates selecting and deleting the field's
text.

### Focus ring, size tiers, motion, font scale

The app draws no focus ring of its own: keyboard focus shows as Material's focus state layer on
its controls and as the ripple indication's focus highlight on the `clickable` rows. Neither
appears for a tap.

### Size tiers

A control's drawn size follows its function, in three tiers (`Sizes`, `Sizes.kt:20-39`), not one
minimum for everything:

- **T1, 50 dp — the actions a screen exists for:** the add button, Log today, the home rows'
  Log, the add form's Save, the sheet Save buttons, the log-date picks, and the actions beside
  Log today (Message, Plan something, Share); a screen's own content, Settings rows and text
  fields, is drawn at T1 too.
- **T2, 40 dp — choices:** theme and mode buttons, chips and filter pills, the inline date field.
- **T3, 32 dp — supporting controls:** icon buttons, the switch, search clear, avatar remove,
  and inline text buttons such as Clear, How? and Restore (`labelSmall` with 4 dp vertical
  padding; Material's default 8 dp and 16 sp clip in 32 dp).

The three values are the knee of a Pareto front over four measured objectives — density
(height of a representative screen inventory), legibility (each tier's label fitting at 100% and
130% font scale), glance (a perceptible step between tiers, T1 quick to find) and harmony (a
4 dp grid and one geometric step) — scored exhaustively over every triple on a 2 dp grid on
2026-10-17. They step by one ratio, 1.25, both ways, the same ratio as the type scale. The
earlier 56/48/36/32/20 dp inventory was dominated on all four objectives; its 56/48 step read as
nearly one size. Inside a card row, a T3 control does not make its row taller than the rows
around it. Weight, not size, marks the one action worth taking among equals: primary colour and
bold for a set kind and for Mark as wished on a birthday.

The hit area is a separate decision from the drawn size. Every target clears WCAG 2.5.8's 24 dp.
Compose enlarges a pointer target smaller than 48 dp to 48 dp invisibly, where no neighbour is hit
directly — the native form of the web build's centred `::before` — and the theme turns off
Material's own 48 dp layout padding, which would grow what is drawn
(`LocalMinimumInteractiveComponentSize`, `Theme.kt:72`). Inside a card row and around inline text
buttons, `RowHitArea()` (`Sizes.kt:58-65`) caps that at 40 dp, a row's height, so it never
reaches the next row. Chip rows keep a 12 dp gap (`chipGap`, `Sizes.kt:38`) so neighbouring hit
areas do not overlap. A switch's whole row is its target, and a tap anywhere on the search bar
focuses its field.

A screen change moves focus to that screen's level-1 heading (`screenHeading()`,
`ScreenHeading.kt:24-29`), on Home, a friend, the add form, Groups and a group, and Settings;
going back to a screen counts, since Navigation disposes a screen's composition when it leaves
the top of the back stack. Otherwise focus stays on a control of the screen just left, and a
keyboard or TalkBack user has nowhere sensible to continue from.

Reduced motion is the system's "Remove animations" (animator duration scale 0), read by
`reducedMotion()` (`Theme.kt:36-37`) into `AppTheme.reducedMotion`. When it is on, every
animation collapses and end states are kept: the pull-to-refresh spinner still shows, unrotated,
and a sheet closes without sliding away. This is vestibular safety, not taste.

Font scale is handled by not fighting it: every size is in sp, and no text container has a fixed
height, so larger text makes layouts taller rather than clipping; the tiers were chosen so every
label still fits at 130%. The one deliberate truncation is a home row's name, ellipsised on one
line. Type is Fredoka (display, 600) over Nunito Sans (body 400, labels 700), self-hosted in
`res/font` with their OFL licences in `assets/licenses`, on a four-step scale of 13, 16, 20 and
25 sp — a 1.25 ratio from a 16 sp body (`TypeScale`, `Type.kt:33-39`) — with line heights on the
4 dp grid. Widget text is a separate problem with a separate answer (section 5).

### Gestures

Two gestures share Home's friend list, and they must stay compatible.

**Pull to refresh** is Material's `PullToRefreshBox` around the list (`HomeScreen.kt:298-303`),
and only on the home screen. It works through nested scrolling, so it starts only when the list
itself is scrolled to its top, and fires at Material's default threshold. Its indicator is the
app's own pill (`RefreshIndicator()`, `HomeScreen.kt:504-533`): a spinner and "Pull to refresh",
"Release to refresh" or "Refreshing…". Release runs `refreshFromDisk()` (`HomeScreen.kt:268-282`);
a failed read toasts "Couldn’t read the sync folder — try again in a moment", and a read-only
session (I6) skips it. Either way the indicator is sent back up explicitly, because a refresh
that returns within one frame never flips the box's `isRefreshing`. Section 3 says why this is
a gesture and not something resume does.

**Long press to log a date** is the home row's Log button (`combinedClickable()`,
`HomeRow.kt:133-138`): a tap logs today, and holding it for the system's touch-and-hold delay
opens the date sheet instead. The hold is also offered to TalkBack as a named action, "Pick a
date".

The rule that keeps the two apart: **a hold that turns into a drag is not a long press.** Compose
gives this for free: once the finger moves past touch slop the list's scroll (and so the pull)
consumes the pointer, and `combinedClickable` gives up on a consumed pointer, so neither the tap
nor the long press fires. Any future gesture on this list must be built on Compose's gesture
detectors, which respect consumption the same way, and not on raw pointer events that ignore it.

The long press is a shortcut, not the only way: the detail screen's "Earlier…" button
(`LogActions()`, `DetailScreen.kt:307-318`) opens the same sheet, so a keyboard or switch user
can back-date too. The sheet (`LogDateSheet()`, `FriendSheets.kt:177`) leads with one-tap picks —
yesterday, two days ago, and the Saturday of the last weekend that is over (`logDatePicks()`,
`FriendSheets.kt:157`) — above the date field for anything else.

**Leaving the add form keeps what was typed.** Cancel and the back button (`BackHandler`,
`AddScreen.kt:129`) go through `leave()` (`AddForm.kt:153-156`): an untouched form is simply
left, a changed one is kept in memory and filled back in the next time the form opens for the
same friend (or for a new one), with a toast saying "Picked up where you left off" and a "Start
over with an empty form" button (`AddScreen.kt:160`). Opening the form for anyone else drops it
(`AddForm.kt:94-103`). Saving clears it. It lives in memory only (`AddDrafts`, `AddForm.kt:54`),
so it does not survive the process ending.

---

## 8. Building, signing and releasing

The app is one Gradle module, `:app`, under `android/`. There is no web layer, no Node and no
copy step: what is committed in `android/` is what Gradle compiles, and Gradle is the only tool
needed to build, test or release it.

### Prerequisites

Read from the build files, these are the versions that have to line up:

- **JDK 21.** `compileOptions` targets `JavaVersion.VERSION_21` (`app/build.gradle:95-98`) and
  Kotlin's `jvmTarget` is `JVM_21` (`app/build.gradle:123-127`). That is the level the app
  compiles *to*, so in Android Studio the Gradle JDK must also be 21. A `JavaVersion.current()`
  floor (`app/build.gradle:29-38`) fails configuration with that instruction on an older JDK,
  because the error otherwise is `invalid target release: 21` from the compiler, which names
  neither the requirement nor this file, and AGP 8.13 only enforces 17. It is a floor: a newer
  JDK is fine.
- **Android SDK platform 36.** Compile and target SDK 36, minimum 30, set once in
  `android/variables.gradle` (`android/variables.gradle:4-6`) and read by `app/build.gradle:11-16`. Gradle finds the SDK through `ANDROID_HOME` or
  `android/local.properties` (`sdk.dir=...`, backslashes doubled), which is gitignored (by both
  the root `.gitignore` and `android/.gitignore`) and recreated per machine. Android Studio
  writes it on first open; on the command line, `ANDROID_HOME` is enough.
- **Gradle 8.14.3**, downloaded by the wrapper (`gradle-wrapper.properties:3`). The first build
  also downloads the AGP/AndroidX/Robolectric dependency graph — several minutes.

The pins that matter are in `android/build.gradle`: Kotlin **2.2.21**
(`android/build.gradle:4`), AGP **8.13.0** (`android/build.gradle:13-15`, with its reason: it
needs Gradle 8.13 or later, and the wrapper pins 8.14.3), the Compose compiler plugin
version-locked to Kotlin (`android/build.gradle:17-18`), the kotlinx.serialization plugin, also
at the Kotlin version (`android/build.gradle:19-20`), and the Roborazzi Gradle plugin
(`android/build.gradle:21-22`) at the version set once in `buildscript`
(`android/build.gradle:6`), so the plugin and the test libraries cannot drift apart. The other
library versions and the SDK levels are in `android/variables.gradle`. `gradle-wrapper.properties` is committed and
hand-owned like every other file here; nothing but the build itself checks that the wrapper is
new enough for the AGP, and an AGP bump that outgrows it fails at configuration with AGP's own
message.

The Compose compiler plugin is load-bearing: every screen is Compose, and the widgets use
`androidx.glance:glance-appwidget` (1.2.0, `app/build.gradle:148`), whose content functions are
real `@Composable`s too. Since Kotlin 2.0 that compiler ships as
`org.jetbrains.kotlin.plugin.compose`, applied at `app/build.gradle:5` on top of `kotlin-android`
at `app/build.gradle:4` — both are needed — with `buildFeatures { compose = true }`
(`app/build.gradle:100-102`). Without it nothing in `ui/` or `widget/` compiles. The
serialization plugin (`app/build.gradle:6`) generates the serializers for the type-safe
navigation routes and the sync-folder JSON, and the Roborazzi plugin (`app/build.gradle:7`)
adds the screenshot tasks below.

### What is committed and what is generated

`android/` is hand-written and committed in full: the Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle-wrapper.jar`, `gradle-wrapper.properties`), `settings.gradle` (one line, `include ':app'`),
the two top-level build scripts, `gradle.properties`, `variables.gradle`, the shared debug
keystore, and everything under `android/app/` — sources, resources, manifest, keep rules, the
JVM tests and their committed screenshot goldens. Nothing in it is generated from anything else,
so a fresh checkout builds with Gradle alone and an edit is made where the file lives.
`android/gradlew` is tracked mode `100755`, so a POSIX clone gets its execute bit.

What a clean checkout does *not* have, ignored by the root `.gitignore` and `android/.gitignore`:

- Build output: `android/build/`, `android/app/build/` (the APKs, the R8 mapping, the Roborazzi
  renders and diffs), and the `*.apk`/`*.aab` patterns.
- Gradle and Kotlin state: `android/.gradle/` and `android/.kotlin/`.
- `android/local.properties` — the SDK path, per machine (above).
- `android/keystore.properties`, and every `*.keystore`/`*.jks` except the shared debug key —
  the release signing material (below).

Those two files are being tidied in P7, so they are cited here by name only.

The one generated input that *is* committed is the widget picker's preview images,
`res/drawable-*/widget_preview_*.png` at all five densities. `tools/widget-previews.py` draws
them (`tools/widget-previews.py:1-5`) from the same numbers the widgets use — the card and tick
sizes in `SpotlightWidget.kt`, the list in `TopThreeWidget.kt` and the default widget theme in
`WidgetData.kt` — and has to be rerun by hand when the wording, sizes or default colours change.
It needs Pillow and the device's Roboto (`tools/widget-previews.py:7-22`); it is not part of any
Gradle task, since the build has no font to draw with and the PNGs change rarely.

### The Gradle tasks that matter

All run from `android/` as `./gradlew <task>` (`gradlew.bat` on Windows).

| Task | What it does | Notes |
| --- | --- | --- |
| `assembleDebug` | Builds `app/build/outputs/apk/debug/app-debug.apk`. | Signed with the shared debug key, not minified. |
| `testDebugUnitTest` | Runs every JVM test in `app/src/test/`. | Robolectric for the Compose and Glance tests; includes `SpecCitationsTest` and `BuildHygieneTest`. Without the property below, the palette screenshot tests run but compare nothing. |
| `testDebugUnitTest -Proborazzi.test.verify=true` | The same, with the palette screenshots compared against the committed PNGs. | What CI runs. A mismatch past the tolerance fails the test and leaves the renders and diffs under `app/build/outputs/roborazzi/`. |
| `recordRoborazziDebug` | Re-renders the palette screenshots and overwrites the goldens in `app/src/test/screenshots/theme/`. | Run after a deliberate palette or component change, then review and commit the PNGs. |
| `assembleRelease` | Builds `app/build/outputs/apk/release/app-release.apk` through R8, with resource shrinking. | Refuses to start without `keystore.properties` (Signing, below). |

The palette screenshots are `PaletteScreenshotTest`: one PNG per palette, five skins in Light
and OLED, ten files, each drawing every token role on the component that carries it. The compare
tolerance is 1% (`PaletteScreenshotTest.kt:57-59`), which absorbs font antialiasing between
machines; the goldens were recorded on Windows and CI renders on Linux.

The test task declares two extra inputs (`app/build.gradle:111-121`): the goldens directory, so
a changed golden re-runs the tests instead of leaving them up to date or restoring them from the
build cache; and the whole repository, minus `.git`, build output, Gradle/Kotlin/IDE state, other
worktrees and `node_modules`, because `SpecCitationsTest` reads SPEC.md and every file it cites
and `BuildHygieneTest` scans the tree. Without that, an edit to SPEC.md alone, or to a cited file
outside `app/src/`, would leave `testDebugUnitTest` "up to date" and the check would never run.

The JVM tests live in `app/src/test/`, package by package next to the code they test: `domain/` (dates, classification, reminders timing, cadence encoding, and
`JsParityTest`, which pins values the web build computed — notification ids and the per-friend
spread — so the port cannot move a reminder installed phones already scheduled), `data/` (the
storage layer and conflict scan against `FakeFolder`, an in-memory folder that keeps section 6's
methods and error codes, and `FileFolder`, a real directory for the round trip), `reminders/`,
`integrations/`, `widget/` (the Glance layouts composed per size bucket), `ui/` (each screen and
sheet), `ui/theme/` and `spec/`. There is no lint.

CI (`.github/workflows/test.yml`, rewritten in P7) runs on Ubuntu for every pull request and
every push to `main`, as a single `android` job: Temurin JDK 21, Gradle's own setup action, then
`./gradlew assembleDebug testDebugUnitTest -Proborazzi.test.verify=true` from `android/`, then a
release build through R8 signed with a throwaway key the runner makes with `keytool` and writes
into a fresh `keystore.properties`, since only a release build shrinks and a broken keep rule
shows up nowhere else. If any step fails, the job uploads `app/build/outputs/roborazzi/` as the
`roborazzi-diffs` artifact: when Linux rendering differs past the tolerance, those are the
runner's own renders, and committing them re-baselines. The runner image supplies the Android
SDK through `ANDROID_HOME`, and the debug keystore is committed, so the job needs no secrets.

### What `SpecCitationsTest` catches

Sections 1–9 cite the code by `path:line`, and an insertion near the top of a cited file slides
every later citation silently while the document goes on reading as authoritative. The preamble
says the code wins when the two disagree; `SpecCitations` (`SpecCitations.kt:5-33`) makes the
disagreement something you can run, and `SpecCitationsTest` runs it against the real tree on
every `testDebugUnitTest` (the test is `SpecCitationsTest.kt:15-25`), finding the repository as the first
directory above the test's working directory that holds SPEC.md (`SpecCitationsTest.kt:11-13`).

It checks every citation of a file with a source, config or markup extension — `.html`, `.css`,
`.kts`, `.kt`, `.mjs`, `.js`, `.gradle`, `.pro`, `.xml`, `.json`, `.properties`, `.md`, `.java`,
`.yml`, `.py`, `.toml`, and `.gitignore` (`SpecCitations.kt:42-45`) — and names each problem:

- **Resolves** — the path names a file, by exact case against the walked tree rather than
  `File.exists()`, so a citation that resolves on Windows also resolves on Linux
  (`SpecCitations.kt:79-85`). Citations may use a repo-relative path or the shortest
  unambiguous tail (`SettingsPlatform.kt:70`); `AMBIGUOUS PATH` means a tail now matches two
  files, which is why this file writes `app/build.gradle`, `values/styles.xml` and
  `android/.gitignore` in full, and `ELIDED PATH` means someone wrote a `...` path
  (`SpecCitations.kt:105-111`). Build output, `.gradle`, `.kotlin`, `.idea`, `node_modules` and
  other sessions' worktrees are not indexed (`SpecCitations.kt:37-40`), or every basename would
  be ambiguous. Test sources are indexed, so they count for ambiguity too.
- **In range** — the line is inside the file and not 0, and a range does not run backwards
  (`SpecCitations.kt:117-119`), including a range written as two backticked halves
  (`SpecCitations.kt:46-48`).
- **Bare citation** — a line number given with only a colon and no path fails outright: it
  could never be checked, and such citations went stale unnoticed (`SpecCitations.kt:97-101`).
- **Moved** — the strong check. Where the prose names a symbol immediately before a citation, as
  in ``​`classify()` (`Classify.kt:51`)``, that symbol must still own the cited line: the line
  falls inside the symbol's top-level declaration, its leading comment and annotations included
  (`topLevelRanges()`, `SpecCitations.kt:155-169`), or the name appears as a whole word within
  three lines (`SpecCitations.kt:121-140`). This is what catches a slide. It applies to
  `.html`/`.js`/`.mjs`/`.kt`/`.kts` (`SpecCitations.kt:55`), only to names of six characters or
  more (`SpecCitations.kt:56`), and only when nothing but punctuation, or "at"/"in", separates
  symbol and citation (`SpecCitations.kt:52`): guessing which of a sentence's backticked names
  is the subject would cry wolf, and a checker that cries wolf gets turned off. For a call, the
  subject is the callee (`anchorFor()`, `SpecCitations.kt:171-186`).

Two floors stop a blind checker from passing: the run must have found more than 150 citations
and anchored more than 30 (`SpecCitationsTest.kt:21-24`). A checker pointed at the wrong root, or
one whose pattern stopped matching, would otherwise pass on nothing.

The appendix is exempt: everything from the first `# Appendix` heading on is a dated record, so
a citation there may point at code that has since moved (`SpecCitations.kt:87-89`). When an
edit slides the numbers, `git diff -U0` gives the hunks, and every citation below a hunk moves by
that hunk's delta.

### What `SpecCitationsSelfTest` and `BuildHygieneTest` catch

A checker that has only ever printed green against the real tree has not been shown to work: the
web build's copy-over checker once reported "in sync: 0 file(s)" on a directory it had never
opened, and what caught it was someone noticing the zero.

`SpecCitationsSelfTest`, in the same file (`SpecCitationsTest.kt:28-185`), builds a small fixture
repository in a JUnit `TemporaryFolder`: a SPEC.md with one citation of every shape the checker
resolves, a `Classify.kt`, a `Dates.kt`, a `Foo.kt` and a `values/styles.xml`
(`SpecCitationsTest.kt:38-90`). The clean fixture must pass with exactly eight citations checked
and two anchored (`SpecCitationsTest.kt:110-115`), and it carries copies of `Foo.kt` under
`build/` and `.claude/worktrees/` that would make it ambiguous if those were indexed
(`SpecCitationsTest.kt:87-89`). Then each test applies one known fault to a fresh copy and
requires the right problem by name (`SpecCitationsTest.kt:117-177`): past end of file in a
`.kt` and in a resource file, a backwards range in both written forms, an elided path, a missing
file, a wrong-case path, an ambiguous tail created by a same-named test source, a slide of thirty
lines in each of two files, a bare citation, and line zero. One negative proves the appendix rule:
the same breakage inside the appendix must *not* fail (`SpecCitationsTest.kt:179-184`). A fault
whose text is not in the fixture throws rather than passing (`SpecCitationsTest.kt:98-103`), so
an edit to the fixture cannot quietly turn a fault into a no-op.

`BuildHygieneTest` (`BuildHygieneTest.kt:9-65`) holds the build-level invariants the code alone
cannot show: the manifest declares no INTERNET permission and keeps `allowBackup="false"`
(`BuildHygieneTest.kt:17-20`); the three widget receivers keep their class names, because
launchers persist widgets by receiver name and a rename drops every placed widget
(`BuildHygieneTest.kt:22-27`); none of the web build's files is back (`BuildHygieneTest.kt:29-33`);
and no file outside `port/` and SPEC.md names the old runtime, with SPEC.md's sections 1–9 allowed
it on one line in total, the sentence that says where the app came from
(`BuildHygieneTest.kt:35-64`).

### Signing

Two keystores, with opposite rules.

**The shared debug keystore is not in this repository.** When
`android/keystores/my-shared-debug.keystore` exists, it is the debug `signingConfig`
(`app/build.gradle:60-68`), password `android`, alias `androiddebugkey`, and the debug build type
uses it (`app/build.gradle:90-92`), so debug APKs from any machine that has it install over each
other. Without it, AGP signs debug builds with the per-machine `~/.android/debug.keystore`, and
`adb install -r` of a build from another machine fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
The root `.gitignore` ignores `*.keystore` and `*.jks` and re-includes nothing. A debug key must
never sign a release.

**The release keystore is never committed.** `android/keystore.properties` is ignored by the root
`.gitignore`, so a clean checkout has none, and a release build stops immediately: once the task
graph is ready, any `:app` task whose name ends in `Release` with no properties file throws a
`GradleException` pointing at this section (`app/build.gradle:40-58`). Checking the task graph
rather than the command line means `gradlew build`, or a bare `packageRelease`, stops too. That
hard failure is deliberate: an unsigned or differently-signed "release" would fail later,
confusingly, at `adb install`, and a key change forces an uninstall that loses local state. Use
`gradlew assembleDebug` when you just want something on a device.

To create one, from `android/`:

```
keytool -genkeypair -v -keystore forgetmenot-release.keystore -alias forgetmenot -keyalg RSA -keysize 2048 -validity 10000
```

then write `android/keystore.properties`:

```properties
storeFile=forgetmenot-release.keystore
storePassword=<the password you set>
keyAlias=forgetmenot
keyPassword=<same, unless you set a separate key password>
```

`storeFile` is resolved with `rootProject.file(...)` (`app/build.gradle:71-73`), so paths are
relative to `android/`, where the properties file itself lives; bare `file()` would resolve
against `android/app/` and fail with a confusing missing-keystore error. CI writes exactly this
file, pointing at its throwaway key.

The developer's own machine points `keystore.properties` at the shared *debug* key, so release
and debug APKs install over each other. That is defensible for a personal sideloaded app and
wrong for anything distributed: the password is public, so the signature attests to nothing.
Switching to a distinct key later forces a full uninstall on every device that has the app.

### R8 and the keep rules

Release turns on `minifyEnabled` and `shrinkResources` (`app/build.gradle:82-85`) over
`proguard-android-optimize.txt` plus `proguard-rules.pro`; debug leaves minification off (AGP's
default). With no reflection-driven bridge left, `app/proguard-rules.pro` is short, and every
rule in it earns its place:

- `-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod` and
  `-keep class kotlin.Metadata { *; }` — Kotlin metadata and generic signatures, for libraries
  that read Kotlin class information reflectively at runtime.
- `-keep class com.hazia.forgetmenot.widget.** { *; }` and `-keep class androidx.glance.** { *; }`
  — the file's own comment gives the reason as keeping the receiver classes and the Compose
  runtime. The three receivers are named in the manifest and instantiated by the system, and
  their names must never change (`BuildHygieneTest.kt:22-27`). The widget's tap action is reached
  only by class name: `actionRunCallback<LogReachOutAction>()` (`widget/SpotlightWidget.kt:103`)
  records the class, and Glance instantiates `LogReachOutAction`
  (`widget/LogReachOutAction.kt:101`) from that name when the tap arrives, so R8 sees no
  constructor call. Glance's own resolution is not statically traceable either. Blunt, but
  correct.

The file is being edited in P7, so it is cited here by name only. The kotlinx.serialization
serializers have no rule in it.

CI builds a minified release on every run, so a keep rule that breaks the R8 step fails the pull
request. It does not install or run that APK: a class R8 renamed or stripped that is only
reached reflectively would still build. The on-device check of a minified release recorded in
A.1 was of the web build; the native release has not been checked that way here.

### From a clean checkout to an installed APK

```
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(`gradlew.bat` on Windows.) The release equivalent, after creating `keystore.properties`, is
`./gradlew assembleRelease` and `app/build/outputs/apk/release/app-release.apk`. Opening
`android/` in Android Studio works the same way; it writes `local.properties` for you.

**The version.** `versionCode` and `versionName` in `app/build.gradle` (`app/build.gradle:17-18`)
are the app's only version; there is no second copy to keep in step. The Settings screen's
Version row reads it back at runtime from `PackageInfo`: `appVersion`
(`SettingsPlatform.kt:70-72`) returns the installed package's `versionName`, or "?" if the
package manager cannot answer, and the row shows it (`SettingsScreen.kt:226`). A release bumps
both values in `app/build.gradle`; `versionCode` must never go down, since Android refuses to
install a lower one over a higher one as an update.

Two failure modes are environmental rather than code, both recorded in `android/gradle.properties`.
`org.gradle.parallel` is deliberately off: on a 16 GB machine parallel Kotlin daemons can run out
of memory, and running out does not produce a clean error — the daemon dies, and if it dies
mid dependency-transform every later build fails with a misleading "failed to transform
&lt;some&gt;.aar" until `~/.gradle/caches/<ver>/transforms` is deleted by hand. Interrupted builds
also leave daemons resident; when a build starts failing for no apparent reason, `gradlew --stop`
is the fix, not a code change. `org.gradle.caching=true` is on, since it costs disk rather than
memory and keeps an edit to one file from recompiling the whole of Kotlin and Compose; the
daemon heap is raised to 2 GB (`-Xmx2048m`), since Kotlin plus the Compose compiler needs more
than the stock 1536m.

---

## 9. Known limitations and deliberate non-goals

Everything here is working as designed or is a known open item. What is listed is what a
maintainer coming back in a year is likely to mistake for a defect, with the reason it is the way
it is.

### Constraints the project cannot fix

**Widgets will not resize below two launcher rows on the test phone.** All three
providers declare a resize floor — `minResizeHeight` 40 dp on Spotlight and Thinking-of
(`widget_spotlight_info.xml:10`, `widget_random_info.xml:6`), 32 dp on Top 3
(`widget_topthree_info.xml:6`) — and a matching small Responsive bucket (`SpotlightWidget.kt:45`,
`RandomWidget.kt:17`, `TopThreeWidget.kt:45`). That launcher never offers a one-row size: floors
of 90, 40 and 32 dp and `minHeight` of 96 and 40 dp all reached the system (`dumpsys
appwidget`), and no other widget on the device, including 44 that declare both resize
directions, shrinks below two rows either. Re-observed on 2026-10-12 with the release build:
the Android 11 test phone (which ignores `targetCellWidth`/`targetCellHeight`) placed
Spotlight at two rows and its resize handle would not go lower. The small buckets stay because
they are right on launchers that honour them and cost nothing here, but **they have never been
seen rendering on the test phone**: Top 3's compact layout has been reached only on the emulator's
Pixel launcher, and its 32 dp floor is a guess (`TopThreeWidget.kt:41-44`).

**Android 12+ shows a splash screen whether the app wants one or not**, with no opt-out, so
`values-v31/styles.xml` brands it and `MainActivity.kt:54-56` calls `installSplashScreen()` on
API 31+ only, keeping it up until the cold-start load is done. At minSdk 30 the launch theme is a
plain alias of the app theme (`values/styles.xml:19`). The same build opens instantly on
Android 11 and shows a brief splash on 13; that is the OS.

**Exact alarms are denied by default and have no dialog** (section 4). On an OEM build that
hides the "Alarms & reminders" screen, the Settings row's request fails quietly
(`openExactAlarmSettings()`, `SettingsPlatform.kt:60-64`, whose `start()` swallows a missing
screen, `SettingsPlatform.kt:75-77`) and reminders may arrive late until the user finds the
setting.

**Android caps pending alarms per app**, so only the soonest 400 reminders are scheduled at any
time (`MAX_SCHEDULED_NOTIFICATIONS`, `Config.kt:35`, section 4); the window rolls forward on every
resume, which rebuilds the reminders (`MainActivity.kt:114-123`).

**Widget text uses Glance's platform default font**, assumed Roboto, not the app's own fonts. On
a device whose default differs, the preview images look slightly off from the live widget.

**The widget picker's preview images show Cyanotype light**, whatever skin the app is in: they
are static images, drawn once, and a placed widget switches to the app's skin at once (section 5).

### Consequences of deliberate choices

- **No network, so no server-side anything** — no cloud backup, account sync, crash reporting,
  analytics, remote fonts or update checks, ever. Backup is a file in the user's own folder.
- **Nothing is saved until a folder is picked.** Until then every write fails harmlessly, the
  starter tags are seeded afresh on each launch (`seedIfEmpty()`, `Records.kt:213-220`), and backup
  export refuses (`Backup.kt:65`). The upside: uninstalling the app does not delete the data,
  because it was never in app-private storage.
- **An unreadable or vanished sync folder makes the session read-only** until restart
  (`start()`, `SyncSession.kt:59-70`; `writesBlocked`, `CachedStore.kt:56`; section 2): the user
  can look but not save. That is the price of never writing a half-loaded cache over good files.
- **The installed app lists a network permission anyway** (`ACCESS_NETWORK_STATE`, via
  WorkManager under Glance), and it cannot be stripped without breaking widget refresh
  (section 6). It does not permit opening a connection.
- **An overwrite costs two extra file operations.** `writeFile` replaces a file by write-then-rename
  (`replaceAtomically()`, `SafFolder.kt:189-212`, section 6), so a sync app or a killed process
  never sees a truncated record. The price is a create and a delete per overwrite, which the
  external-storage provider turns into media-store work: on the Android 11 test phone an overwrite measured
  ~350 ms against ~50 ms for the old truncate-and-write, while a new file stayed at ~125 ms
  (measured with the web build, which used the same sequence). Writes are queued off the UI, so
  only bulk overwrites (a backup imported over existing data) are noticeably slower. The swap also
  leaves a few milliseconds in which `<name>` is absent; a Syncthing scan that lands there sees a
  delete followed by a re-create, which the next scan undoes.
- **The swap's leftovers sync too.** `<name>.tmp` and `<name>.bak` exist only during a write or
  after a crash, and no listing in the app matches them (`.json` only, `CachedStore.kt:195`, and
  `.json`/`.b64` for conflict copies), but Syncthing carries them like any other file. A
  `.stignore` with `*.tmp` and `*.bak` in the sync folder keeps them local; the app does not write
  one.
- **Conflict handling understands only Syncthing's naming** (`CONFLICT_RE`, `Conflicts.kt:54`,
  section 3). A sync tool that names conflicts differently, or silently picks a winner, is
  invisible to this app.
- **Changes synced in from another device do not appear on resume**, only on pull-to-refresh or
  restart (section 3).
- **Widgets show a snapshot.** They never compute a date. A summary pushed on an earlier local
  day is rebuilt when a widget session next starts (`refreshIfStale()`, `WidgetPusher.kt:68-75`:
  a placement, a launcher reload or the three-hourly `updatePeriodMillis`), so if the app is never
  opened "9d" can stay "9d" for up to about three hours past midnight (section 5). A skin change
  reaches them only through this device's copy of the settings, so one made on another device
  shows on this one's widgets after this device next reads the folder: a cold start, a
  pull-to-refresh, or that day-change rebuild.
- **A non-default theme flashes blue/light on cold start on Android 11**, because the saved theme
  sits behind the cold-start load and below API 31 no splash covers it: the window shows Cyanotype
  light's `bg` until the load is done (section 7). On Android 12+ the splash is kept up for the
  load, so the saved theme is the first thing drawn.
- **A release build refuses to run without a keystore**, `gradlew build` included, rather than
  produce an APK signed with a key Android would treat as a different app (`app/build.gradle:49-57`,
  section 8). CI makes a throwaway key for its R8 release build and discards the APK.

### Open items not yet verified

- **Android 15+ edge-to-edge has been seen only on an emulator** (API 36), not on a phone.
  `enableEdgeToEdge()` (`MainActivity.kt:58`) draws the same way on every version, so the Android
  11 phone gets the same layout. The bottom edge is left to the inset: the add button and the
  last element of each screen pad by `bottomInsets` (section 7), sheets by the navigation bar, and
  list content scrolls behind the navigation bar, which is transparent over the page rather than
  black.

### Non-goals

- **No accounts, no server, no multi-user anything.** Sync is the user's own tool moving files
  between their own devices; two people cannot share a friend list.
- **The app cannot tell that you actually contacted someone.** Logging is a manual tap
  (`logToday()`, `Feedback.kt:180-186`). No call-log or SMS permission is declared and none will
  be; guessing from message history is declined, not deferred.
- **The app never writes to the calendar or contacts providers.** "Plan something" fires an
  `ACTION_INSERT` intent, falling back to sharing an `.ics` file (`plan()`,
  `DeviceIntegrations.kt:110-127`), and "Message" hands an
  `sms:`/`mailto:`/`wa.me`/`t.me`/`signal.me` URL to Android (`channelUrl()`,
  `MessageLinks.kt:11-23`; `message()`, `DeviceIntegrations.kt:99-108`). The app has no idea what
  happened after the hand-off. The `https` links are claimed by a browser when no messaging app
  takes them; if nothing at all can open a link, the app toasts "No app on this phone can open
  that".
- **English only.** Strings live in `res/values/strings.xml` (the app name and the widget-picker
  labels) and as literals in the composables. There is no `values-<locale>` directory;
  `supportsRtl="true"` is declared but no RTL locale exercises it.
- **Undo keeps the last five changes, in memory only.** `RecentChanges.record()`
  (`Feedback.kt:91-93`) stores each with the function that reverses it: logging a reach-out
  (from Home, the detail screen, the log-date sheet or a reminder's action), adding a note or a
  date, adding or removing a gift idea, clearing what to ask next time, removing a date or a
  reach-out, archiving a friend, deleting a tag or a group event (`RecentChanges.MAX`,
  `Feedback.kt:84`). The toast's Undo reverses the change it was shown for and stays 10 seconds
  (`ACTION_MS`, `Feedback.kt:50`); Settings → Recent changes lists all five, any of them undoable
  in any order (`RecentChanges.undo()`, `Feedback.kt:95-100`), so each reverser works by id and
  does nothing if a later change already took its record away. Closing the app clears the list.
  Edits to a friend's fields are not on it, and deleting a group or deleting a friend for good
  stays irreversible behind a question that says so.
- **A removal can come back after a sync conflict.** Merging a friend's logs keeps every entry
  either copy has (`mergeFriendLogs()`, `Merge.kt:14`), and nothing records that an entry was
  removed: a reach-out or a date removed on one phone while the other still had it returns when
  the conflict is resolved (section 3). Keeping a logged reach-out was judged worth more than this.
- **Tests stop at the platform.** `testDebugUnitTest` runs the domain code, the storage layer and
  conflict scan against fake folders (`FakeFolder.kt`, and `FileFolder.kt`, a real directory that
  copies the `.tmp`-and-rename swap), the scheduler against Robolectric's `AlarmManager`, the
  screens and sheets under Compose's test rules, Roborazzi screenshots of the palettes, and the
  widgets' Glance layouts and resources (section 8). The fakes are only as true as section 6's
  contract, so `SafFolder` itself — the atomic swap through `DocumentsContract`, SAF naming, the
  grant — is not exercised; nor is real alarm delivery, or a widget on a launcher. Those were
  verified on a device (A.3). CI runs `./gradlew assembleDebug testDebugUnitTest
  -Proborazzi.test.verify=true` and an R8 `assembleRelease` (section 8). Behaviour is still
  verified by install-and-look on a real device.

---

# Appendix: decisions and their history

Everything below is the record from the two documents this file replaces, preserved rather
than summarised. Only one thing was changed: `##` headings were demoted to `###` so they
nest under this appendix. No wording was altered, including the superseded markers and the
entries that later turned out to be wrong — those are the point.

Read anything here as true **on its date**, not necessarily now. Sections 1–9 above are the
current truth.

A.1 and A.2 are never edited, so anything recorded there that later changed is superseded in
A.3 rather than marked in place. That keeps the two preserved records verifiable as a unit:
their bytes have not moved since the consolidation, and can be diffed against it to prove it.

A note worth keeping in view: the audit record below opens by explaining that it, too,
consolidated its own predecessors because their update blocks "were getting hard to follow as
a single source of truth". This file will drift the same way. The appendix is where that
drift is recorded instead of hidden.

## A.1 — Audit record

> **Updated 2026-09-01 (tenth update):** widget type proportions assessed
> against the declared cell sizes, and three fixes applied.
> - **[Accessibility] Top-3's muted colour failed WCAG AA.** `MutedCol`
>   `#7A93A6` on the `#F7FAFC` card is 3.06:1, below the 4.5:1 AA floor for
>   normal text — and neither string it carries ("THINKING OF YOU" at 10sp,
>   the trailing code at 12sp) is large enough to qualify for the large-text
>   threshold, which needs 18.66sp bold or 24sp. Changed to `#5A7285`: 4.79:1,
>   the nearest tone to the original that clears AA, still clearly subordinate
>   to `TextCol` (delta-E 8.3). Verified by sampling the rendered pixels on
>   device before and after, not just by rebuilding.
>   **[2026-09-01: superseded]** `#5A7285` shipped only briefly. The later
>   eight-theme token-coherence pass moved `MutedCol` again, to `#4F6C81`
>   (5.28:1 on the same `#F7FAFC` card), which is what the widget carries now.
>   The reasoning above still stands — this bullet records the pass that first
>   cleared AA, not the current value. Left here for history rather than
>   deleted.
> - **`sp` scales with the user's font setting; the `dp` widget box does not.**
>   At the declared minimums the type stack overflowed at roughly 1.24x for the
>   2x1 pair and 1.16x for Top-3 — both inside the range Android offers.
>   `minHeight` raised 64->68dp (Spotlight, Random) and 110->120dp (Top-3),
>   which carries each stack to 1.3x. Note this only affects new placements and
>   the resize floor; widgets already on a home screen keep their current cell.
> - **2x1 type sizing** changed to label 10sp / name 18sp from an optimizer run
>   over legibility, one-line name capacity, font-scale headroom and label/name
>   hierarchy (fitness 0.907 vs 0.871 for the shipping 11/16). The trade is
>   deliberate: names gain size and lose about 1.5 characters before ellipsis
>   (13.9 -> 12.4 as actually placed).
>
> Two latent workflow bugs surfaced while doing this, both in code that had
> never been executed rather than in anything users could hit: `npm run build`
> and `build:debug` invoked `gradlew.bat` after a `cd`, which npm's shell does
> not resolve from the changed directory — now `.\gradlew.bat`. (The other,
> the release `storeFile` path, is in the ninth update above.) Both are the
> same lesson as that entry: a script nobody has run is not a working script.

> **Updated 2026-09-01 (ninth update):** the release build path was exercised
> for the first time. Every prior verification in this document — including the
> 2026-08-30 on-device runtime pass — was `assembleDebug`, i.e. `minifyEnabled
> false`. R8 and `shrinkResources` had never run on this app, which mattered
> because its four custom plugins are dispatched from JS purely by reflection
> and its widgets go through Glance; both are exactly what an unguarded R8 pass
> breaks. Results:
> - **One latent bug found, in code no one had ever executed.** The release
>   `signingConfig` read `storeFile file(keystoreProperties['storeFile'])`.
>   Inside the app module, bare `file()` resolves against `android/app/`, while
>   BUILD.md section 5 tells you to generate the keystore in `android/` — so
>   following BUILD.md exactly would have failed with a confusing
>   missing-keystore error on the first release build anyone ever attempted.
>   Fixed to `rootProject.file(...)`, matching how `keystore.properties` itself
>   is located two lines above.
> - **The R8 keep rules hold.** All ten JS-facing `@PluginMethod` methods
>   (`readFile`, `readdir`, `writeFile`, `deleteFile`, `pickFolder`, `share`,
>   `shareFile`, `saveWidgetData`, `status`, `request`) appear in
>   `mapping.txt` mapped to their own names, unrenamed — verified by
>   enumerating the annotated methods from source and checking each against the
>   mapping, rather than spot-checking one. All eight widget drawables and
>   label strings survived `shrinkResources`.
> - **Verified at runtime, not just statically.** APK is 6.75MB (from 21.5MB
>   debug). It installed over the debug build with `adb install -r` and no
>   uninstall — see the signing note below — launched clean with no
>   `ClassNotFoundException`/`NoSuchMethodError`, and loaded real friend
>   records out of the SAF sync folder, which is the reflection bridge working
>   minified rather than merely the app starting. All three widgets were then
>   placed: `dumpsys appwidget` shows three instances bound to
>   the phone maker's launcher with live `RemoteViews`, and each renders its own
>   composable's unique strings with the right card colour, so item 14's
>   label/content swap has not regressed. Corners are rounded, which is the
>   load-bearing detail: `cornerRadius(16.dp)` no-ops on this API 30 device, so
>   rounded corners prove the `widget_card_bg_*` shape drawables survived
>   resource shrinking intact rather than being stubbed out.
> - **Release is signed with the shared debug key** in
>   `android/keystores/my-shared-debug.keystore`, a deliberate choice for a
>   personal sideloaded app, recorded in BUILD.md section 5. Upside: debug and
>   release APKs are interchangeable on device. Cost: the key's password is the
>   public `android` convention, so the signature attests to nothing. If this
>   app is ever given to anyone else, it needs a distinct key — and that swap
>   forces a full uninstall on every device that already has it.
>
>   **[2026-09-01] Settled by decision — not an open finding. Do not re-raise.**
>   Any tool that reads the certificate rather than this file will rate it HIGH
>   and be technically right: both halves of the key (the keystore and its
>   password) are in the repository, and Android accepts a same-signature APK
>   as an in-place update, which inherits the app's data directory, its granted
>   runtime permissions, and its persisted SAF grant to the sync folder. That
>   is the whole exposure, and it is accepted knowingly: it needs an attacker
>   who already has this repository and physical access to the device, which is
>   the owner. The condition that would change the answer is the one this
>   bullet already names — the app being given to someone else — not the
>   severity a scanner assigns. A 2026-09-01 audit pass raised it as HIGH; the
>   owner reaffirmed the choice, so it is recorded here as decided rather than
>   left to be rediscovered and re-argued on every future pass.
> - **Workflow, not code:** the project is now a git repository, and
>   `overlay/` ↔ `android/` drift — the 2026-07-27 regression that cost a whole
>   audit pass to find — is now a single command, `npm run overlay:check`,
>   which fails on files that differ, are missing from `android/`, or exist as
>   custom source in `android/` and were never carried back to `overlay/`.
>
> One cosmetic nitpick, not a defect: the Top-3 widget occupies a taller cell
> than three rows of content need, leaving dead space below the last name. It
> fills in as friends are added, and resizing is a launcher gesture, not a code
> change.

> **Updated 2026-08-26 (eighth update):** an independent full-app functional
> audit of `www/index.html` and every native Kotlin file (not just the
> build/manifest/permission surface covered by the previous seven passes),
> scoped, scored, and fixed. Six real issues found, all fixed:
> - **[High] Resolving a sync conflict could silently discard an edit made
>   after the conflict was detected.** `resolveSyncConflict()` wrote the
>   `canonical`/`conflicting` snapshot captured at scan time, with nothing
>   re-reading the record first — if the user edited that same friend/tag
>   (e.g. added a note) between the conflict banner appearing and them
>   tapping "keep this version," the edit was overwritten with no warning.
>   Fixed: it now re-reads the current on-disk copy before writing and, if
>   "keep canonical" was chosen but the on-disk copy no longer matches the
>   snapshot, aborts with a toast and re-scans instead of silently
>   overwriting.
> - **[Medium-high] The unawaited startup conflict scan could race a
>   concurrent index write.** The root-level index merge in
>   `checkSyncConflicts()` read the current index, merged, then awaited a
>   disk write — if a friend was added during that single-await window, the
>   merge's write could land after (and clobber) the newer index on disk,
>   orphaning the new friend's own file from every index-based read after a
>   restart. Fixed by re-checking the index immediately after the write and
>   merging in anything that changed during the write itself.
> - **[Medium] Backup import validated friend/tag data but accepted group
>   data (`fm_group_index`/`fm_group:*`) completely unchecked**, contradicting
>   the function's own stated invariant. A tampered/corrupted backup with a
>   non-array `memberIds` would import successfully and then throw when
>   `renderGroupDetail()` ran `.map()` on it. Fixed: groups now go through
>   the same id validation as friends/tags, plus a shape check on
>   `memberIds`.
> - **[Medium] Removing a friend's photo (without deleting the friend) never
>   deleted the old avatar file** — only full friend deletion cleaned it up.
>   The stale `.b64` file lingered in the sync folder and kept propagating to
>   every other synced device indefinitely. Fixed: `persistKeyNative()` now
>   deletes the avatar file when a friend record is saved with `avatar`
>   explicitly cleared.
> - **[Low-medium] Deleting a friend left them as a phantom member in every
>   group they belonged to** — `deleteTagRecord()` already had the
>   equivalent cleanup for tags, but `deleteFriendRecord()` had no
>   counterpart for `g.memberIds`. Fixed to match the existing tag-deletion
>   pattern.
> - **[Low] The shared vCard declared `VERSION:3.0` but emitted a
>   `BDAY:--MMDD` partial date for anyone with an unknown birth year** — that
>   partial-date form is RFC 6350 (vCard 4.0) syntax, invalid under RFC 2426
>   (vCard 3.0), and would be rejected or mis-parsed by strict 3.0 importers.
>   Fixed by declaring `VERSION:4.0` instead (a full ISO date, the
>   known-year case, is valid under both, so this is the minimal correct
>   fix).
>
> One nitpick noted but not changed as a functional bug: the Spotlight
> widget always surfaces the single highest-weight friend with no
> minimum-weight/overdue threshold, so it can frame someone as "worth
> thinking of" even when everyone was contacted recently — a design nuance,
> not a wiring defect.
>
> Everything from the previous seven passes (build/Gradle/AGP config,
> manifest permissions, overlay/android sync, plugin signatures, widget
> Glance API usage) was re-spot-checked rather than re-derived from scratch,
> and still holds — this pass's new ground was full-file functional/data-
> integrity review of `index.html` and the native plugins, not the
> build pipeline.

> **Updated 2026-07-27 (seventh update):** `npm install` flagged 2
> vulnerabilities (`brace-expansion`, high; `tar`, moderate) via `npm audit`.
> Traced with `npm ls --all`: both come exclusively from `@capacitor/cli`
> (a `devDependency` — `rimraf` → `glob` → `minimatch` → `brace-expansion`
> for the first, direct for the second), not from anything in the `dependencies`
> block or from `www/`, so neither package is ever bundled into the shipped
> APK or reachable through the app itself — worst case is a crash of the
> `npx cap ...` Node process on a machine that already fed it a specifically
> crafted glob pattern or a crafted long-path tar archive, not an app-level
> exposure. Still fixed, since it's a one-command, zero-risk fix: `npm audit
> fix` (no `--force`) bumped `brace-expansion` 5.0.7→5.0.8 and `tar`
> 7.5.20→7.5.22 in `package-lock.json` only — confirmed `package.json` is
> byte-identical after the fix (neither package is depended on directly) and
> `@capacitor/cli` itself stayed at 7.6.8, so this doesn't touch any CLI
> behavior this document's build steps rely on. `npm audit` now reports 0
> vulnerabilities.

> **Updated 2026-07-27 (sixth update):** an independent code-review pass
> (`code-reviewer` + `code-verification-core`'s verification gate), covering
> every file in the project rather than just the two features added since
> item 17. One real, concrete bug found and fixed, plus several stale
> comments/claims corrected:
> - **`overlay/` had drifted out of sync with the delivered `android/`
>   tree.** The 2026-07-21 work that added `ExactAlarmPlugin` (closing the
>   "exact-alarm never requested" gap noted in Known limitations below) and
>   `SharePlugin.shareFile()`'s `FileProvider`-backed `.ics` fallback in
>   `planWithFriend()` was applied directly to `android/` and never carried
>   back into `overlay/` — the supposed source of truth for regenerating the
>   native project from scratch. `overlay/` was missing `ExactAlarmPlugin.kt`
>   entirely; the `registerPlugin(ExactAlarmPlugin::class.java)` line in
>   `MainActivity.kt`; `SharePlugin.kt`'s `shareFile()` method; the
>   `<provider>` block and `text/calendar` `<queries>` entry in
>   `AndroidManifest.xml` (whose overlay copy still declared
>   `READ_CALENDAR`/`WRITE_CALENDAR`, which the real manifest had by this
>   point dropped entirely — see the correction below); `res/xml/file_paths.xml`;
>   and the `androidx.core:core` line in `app/build.gradle`. This is a real
>   regression against this project's own reproducibility guarantee (BUILD.md:
>   "reliably produce the same tree every time") — following BUILD.md's steps
>   2–3 as written would have produced a tree missing the exact-alarm settings
>   toggle and with a `shareFile()` call that always fails (no `FileProvider`
>   declared to back it). Fixed by copying the six affected files from
>   `android/` into `overlay/`, then verified with a full recursive byte-diff
>   of `overlay/android/` against `android/`, confirming every remaining
>   difference is stock Capacitor scaffolding that was never part of the
>   overlay by design (gradle wrapper, `capacitor.build.gradle`, splash
>   drawables, etc.) — not a leftover custom-file mismatch. BUILD.md's own
>   "delete `file_paths.xml`" cleanup step (written when that file really was
>   dead stock scaffolding) has also been corrected, since it now describes
>   deleting a file the app legitimately needs.
> - **Two "Verified correct" bullets below are now stale** and are corrected
>   in place rather than left to contradict the current manifest: "no
>   `FileProvider` declared" (there now is one, narrowly scoped to
>   `cache/shared/`, backing `shareFile()`) and the `READ_CALENDAR` bullet
>   (moot — `READ_CALENDAR`/`WRITE_CALENDAR` are no longer declared at all,
>   apparently acted on from item 16's note that `WRITE_CALENDAR` "has no
>   remaining call site" without a matching audit note at the time).
> - **The Known limitations bullet about exact-alarm access is resolved**,
>   not still open — `ExactAlarmPlugin.kt` plus its onboarding-step and
>   Settings-row wiring in `www/index.html` now do what that bullet asked
>   for, just via a purpose-built `AlarmManager.canScheduleExactAlarms()`
>   plugin rather than `@capacitor/local-notifications`'s own
>   `changeExactNotificationSetting()`/`checkExactNotificationSetting()`.
>   Marked resolved below instead of removed outright, so the history of why
>   it existed isn't lost.
> - `@capacitor/camera`'s avatar-picking call (`source: 'PHOTOS'`,
>   `resultType: 'uri'`) was never covered by any previous pass's plugin
>   checklist — checked now against the actual installed package
>   (`CameraSource`/`CameraResultType` string values, and the native
>   `CameraPlugin.java`'s permission handling for the gallery-only path):
>   correct, and needs no manifest permission for this app's photos-only
>   usage.
> - **Minor:** `syncWidgetsNative()` in `www/index.html` called
>   `WidgetData.saveWidgetData()` without `await` inside a `try/catch` — a
>   rejection from that call would have been an unhandled promise rejection
>   the `catch` block could never actually see, since `try/catch` only
>   catches synchronous throws or awaited rejections. Low real-world impact
>   (`WidgetDataPlugin.saveWidgetData()` always resolves, never rejects,
>   today), but fixed with a `.catch(() => {})` on the un-awaited promise so
>   the existing defensive intent actually works.
> - **Stale doc comments corrected**, no behavior change: `WidgetDataPlugin.kt`
>   and `widget/WidgetData.kt` both referenced a nonexistent `app.js` (this
>   project has always kept everything in `www/index.html`) and the latter
>   named a phantom `buildWidgetSummary()` function that doesn't exist — both
>   now point at `syncWidgetsNative()`, the actual call site. `proguard-rules.pro`'s
>   comment listed only two of the four custom plugins its wildcard keep-rule
>   actually covers. `shareFriendCard()`'s comment in `www/index.html` claimed
>   file-attachment sharing "would need a FileProvider... for not much
>   practical gain here", which stopped being true the moment `shareFile()`
>   was added for the `.ics` case — reworded to scope that reasoning to the
>   vCard case specifically.

> **Updated 2026-07-19 (fifth update):** a full re-audit against the app's own
> complete permission/manifest/build checklist, requested specifically to
> catch anything still out of place after items 15 and 16. Found one new,
> real issue — see item 17 below — and re-verified everything else in this
> document still holds. Nothing else changed.

> **Updated 2026-07-18 (fourth update, same day):** a live install reported
> "Contacts permission denied" persisting even after explicitly granting the
> permission dialog. Root cause found and fixed — see item 15 below. This is
> a correction to this document's own prior claim, not a new regression: the
> "Verified correct" section below had listed Contacts permission
> declarations as checked and fine, which was wrong in a way that only
> shows up at runtime on a real device, not from reading the manifest and
> the plugin's JS↔native method signatures alone (which is as far as that
> earlier pass went for this specific item).

**Last full pass: 2026-07-18**, updated twice more the same day after real
build attempts on an actual machine caught two more things this sandbox's
static verification couldn't have (see items 3 and 14 below). This document
replaces all earlier audit write-ups rather than layering another update
note on top of them — the previous versions accumulated several rounds of
"update" blocks that were getting hard to follow as a single source of
truth, and this pass changed enough (including one build-breaking fix) that
a clean rewrite is more useful than another patch.

### Methodology

Nothing in this pass was taken on trust from earlier audits, including this
project's own prior write-ups. For every claim below, the same evidence
standard was applied:

- **The whole native pipeline was regenerated from scratch, independently,
  multiple times** — `npm install` → `npx cap add android` → apply
  `overlay/android/` → delete the two stale stock files → `npx cap sync
  android` — and diffed byte-for-byte against the delivered `android/`
  folder after every batch of fixes. Every regeneration matched exactly
  (aside from one harmless `build/.npmkeep` placeholder Gradle creates).
- **Every JS→native plugin call was checked against the actual installed
  npm package**, not against documentation or memory — the real
  `dist/esm/definitions.d.ts` (or equivalent) for
  `@capacitor/local-notifications`, `@capacitor/status-bar`, `@capacitor/app`,
  `@capacitor-community/contacts`, and `@ebarooni/capacitor-calendar`, all
  fetched live from the npm registry.
- **Every source file was read in full**, not sampled: the manifest, every
  Gradle file, all seven Kotlin files, all resource XML, all ~2,000 lines of
  `www/index.html`, `sw.js`, `manifest.json`, the icon SVG, ProGuard rules,
  and both `.gitignore` files.
- **External/version-compatibility claims were checked against current
  official sources** (Android's own AGP release notes, Google's Glance/
  Compose setup docs), not asserted from general knowledge, since those
  facts change over time and this app is being built well after most
  training data would reliably know current version compatibility.
- **Icon/asset claims were checked by pixel analysis** (alpha channel
  presence, safe-zone geometry, exact sampled colors), not by looking at a
  thumbnail and assuming it's fine.
- One honest limit that applies to everything below marked "not yet run
  through a real compiler": this sandbox has no Android SDK, and its
  network allowlist doesn't include Google's Maven repo or
  `services.gradle.org` (confirmed directly — the Gradle wrapper's own
  download attempt returns HTTP 403). Every fix here is as verified as
  static inspection and cross-referencing real installed packages can make
  it, but the first actual `gradlew assembleRelease` on a real machine is
  still the first true compile-test this project has had.

### Fixed this pass

1. **Compose Compiler Gradle plugin was missing — likely build-breaking.**
   `SpotlightWidget.kt`, `TopThreeWidget.kt`, and `RandomWidget.kt` all use
   `androidx.glance:glance-appwidget`, built on real `@Composable`
   functions. Neither `build.gradle` file had `buildFeatures.compose`, a
   Compose compiler classpath, or the compiler plugin applied — without
   this, Kotlin has no way to process `@Composable` annotations, and
   nothing in the widget module could have compiled. Fixed by adding
   `org.jetbrains.kotlin.plugin.compose` (version-locked to `kotlin_version`,
   the correct approach for Kotlin 2.0+) to both the top-level classpath and
   `app/build.gradle`, plus `buildFeatures { compose true }`.

2. **Calendar integration never actually worked.** `planWithFriend()` called
   `CapacitorCalendar.requestWriteOnlyCalendarPermission()` — confirmed
   against the real installed package that this method doesn't exist; the
   real one is `requestWriteOnlyCalendarAccess()`. Every tap threw, was
   swallowed by the surrounding try/catch, and silently fell back to the
   Google Calendar web link. Fixed, and the fix now also checks the
   returned permission result before calling `createEvent()` rather than
   relying on that call to fail on denial.

3. **AGP too old for the declared compileSdk/targetSdk (36).** Confirmed
   against Android's own AGP release notes: 8.7.x–8.9.0 cap out at API 35;
   8.9.1+ supports 36. The project pinned 8.7.2.

   First attempt at a fix (same day, superseded below): bumped to 8.11.1 on
   the reasoning that it would pair with the Gradle wrapper version already
   pinned (8.11.1) since the numbers matched — this reasoning was never
   checked against Google's actual AGP-to-Gradle compatibility table, and
   was wrong.

   **Corrected after a real build attempt** returned: `Minimum supported
   Gradle version is 8.13. Current version is 8.11.1.` Checked against
   Google's real table (developer.android.com/build/releases/about-agp)
   this time: AGP 8.11 genuinely requires Gradle 8.13 minimum. AGP 8.10
   requires only Gradle 8.11.1 (already pinned, no wrapper change needed)
   and still comfortably covers compileSdk 36 (Google's own "minimum AGP
   per API level" table puts the actual floor at 8.9.1). Now pinned at
   8.10.0. This is the first fix in this whole audit that a real build
   attempt — not sandbox verification — actually caught and corrected;
   recorded here rather than quietly folded in, since it's a useful data
   point on the limits of static verification.

4. **ProGuard rules for the custom plugins were subtly dead code.** The
   rule meant to protect `@PluginMethod`-annotated methods matched
   `@CapacitorPlugin` (a class-level annotation) against `<methods>`, which
   can never match anything. Note on real severity, found by checking
   whether `@capacitor/android`'s own bundled `consumerProguardFiles`
   already covered this: it does, with the fully correct version of this
   exact rule, automatically merged into every Capacitor app's build — so
   this was never actually a live risk of breaking a release build, just
   dead/misleading code sitting next to what was really doing the work.
   Replaced with the correct, explicit, non-redundant rule anyway, so the
   app's own file is accurate rather than relying silently on inherited
   framework behavior.

5. **"Export backup" silently did nothing on the native build, while
   reporting success.** It used `Blob` + `URL.createObjectURL` + `<a
   download>` — confirmed by grepping the actual installed
   `@capacitor/android` source that there is no `DownloadListener` or any
   blob-handling code anywhere in it, a known open limitation of Capacitor
   core. Fixed to write the backup JSON into the already-chosen sync folder
   instead (via the existing `SyncFolderPlugin`), in its own `backups/`
   subfolder so snapshots don't mix into the live synced files. If no sync
   folder has been chosen yet, it now says so instead of claiming success.

6. **Spotlight/Random widgets' declared `minHeight` couldn't fit their own
   content.** Both stack two text lines (an 11sp label + a 16sp name) inside
   12dp top+bottom padding — about 58dp of real content height — but
   declared `minHeight="40dp"` (the bare Android 2×1-cell-formula floor).
   Resizing either widget down to that floor would clip the second line,
   since neither composable sets `maxLines`/overflow handling. Raised to
   64dp so the OS can't offer a resize state that clips.

7. **PWA icon was an unrelated leftover design.** `icon.svg`/`icon-192.png`/
   `icon-512.png` still showed an old "two figures on a bench" mark on an
   indigo background, unconnected to the flower motif used on the native
   launcher/notification icon. Replaced with the same flower geometry
   (exact circle coordinates scaled from the real vector source, colors
   sampled directly from the actual rendered launcher PNG rather than
   redrawn from memory), verified to sit safely within the standard
   maskable-icon zone.

8. **Widget preview images were missing entirely.** None of the three
   `widget_*_info.xml` files set `android:previewImage`, so Android's
   widget picker would show a generic gray box. Built real static previews
   at all 5 density buckets, replicating each widget's actual real colors,
   corner radius, padding, and font sizes/weights from the composables
   directly (rendered in Roboto — Glance's actual default text font, since
   none of the three composables set a custom `FontFamily`).

9. **`FileProvider`/`file_paths.xml` removed.** Stock Capacitor scaffolding,
   present in every `cap add android` output regardless of whether an app
   uses it — nothing in this app's JS or Kotlin ever called it, and it
   exposed the entire internal files directory (`path="."`) for no reason.
   Removing it surfaced a real gap in the documented overlay workflow
   itself: since `overlay/` is copy-only, deleting a file from it doesn't
   stop `cap add android` from regenerating that same stock file on the
   next fresh setup. `BUILD.md` now has an explicit second cleanup step for
   this, mirroring the existing `MainActivity.java` one.

   **[2026-07-27: superseded]** Both halves of this item have since reversed.
   `file_paths.xml` is back as a real, narrowly-scoped overlay file backing
   `SharePlugin.shareFile()`, and BUILD.md now says deleting it "is now actively
   wrong and would break calendar-invite sharing" — so the second cleanup step
   described here no longer exists. Left for history rather than deleted.

10. **Dead code from a removed feature.** An unused `.notif-banner` CSS
    class and an `fm_notif_dismissed` storage key (loaded, synced, and
    conflict-merged, but never actually read or written by anything) —
    leftovers from what looks like a removed "enable notifications" prompt
    banner. Removed cleanly, including from the sync-conflict merge config
    and the file-list comment that referenced it.

11. **Unused Google Services/Firebase Gradle wiring removed.** The build
    carried an unconditional `com.google.gms:google-services` classpath and
    a conditional `apply plugin` block gated on a `google-services.json`
    that doesn't exist. No Firebase/FCM code exists anywhere in the app,
    and the app deliberately has no `INTERNET` permission — this was inert
    scaffolding that only added an unnecessary Google Maven fetch at build
    time. Removed both the classpath and the conditional block.

12. **Unused `androidx.glance:glance-material3` dependency removed.**
    Declared but never imported anywhere — no `GlanceTheme`, no
    `androidx.glance.material3` reference in any of the widget files.

13. **A real escaping bug caught by this pass's own re-check.** While adding
    the `scheduleNativeReminders()` failure toast (see below), the first
    version had doubled backslashes before its unicode escapes
    (`\\u2019`/`\\u2014`), which would have rendered as literal
    `couldn\u2019t` text instead of a proper apostrophe/em dash. Caught by
    re-parsing the actual string with a real JS engine rather than trusting
    the edit, and fixed — worth recording since it's a reminder that this
    pass's own edits got the same scrutiny as the original code, not a pass.

14. **Two real Glance API mistakes, caught only by a real build.** A build
    attempt on an actual machine got past the AGP/Gradle issue (item 3) and
    reached genuine Kotlin compilation, surfacing 14 errors — all in the
    three widget files, and both mistakes were already present in the
    original widget code, not introduced by this audit:
    - `ColorProvider(color)` isn't a valid call — confirmed against real
      Glance documentation/source, the actual function requires both `day`
      and `night` colors with no default for either. All 13 call sites
      across the three widget files were passing only one. Fixed by passing
      the same color for both (`ColorProvider(day = X, night = X)`), since
      these widgets intentionally use a fixed palette regardless of system
      theme — not a design change, just satisfying the real signature.
    - `import androidx.glance.layout.defaultWeight` doesn't exist —
      `defaultWeight()` is a `RowScope`/`ColumnScope` member extension, not
      a top-level function; it's implicitly available inside a `Row{}` or
      `Column{}` lambda without any import. Removed the bad import; the one
      real usage needed no other change.

    Recorded as its own item rather than folded into item 3's fix, since
    it's a second, independent instance of the same lesson: this project is
    now two-for-two on real build attempts catching something the sandbox's
    static verification — reading every file carefully, cross-referencing
    real installed npm packages — still could not have caught, because
    neither the AGP/Gradle pairing nor these Kotlin/Glance signatures could
    be exercised without an actual compiler.

15. **"Contacts permission denied" no matter what the user granted — `AndroidManifest.xml`
    was missing `WRITE_CONTACTS`.** `importFromContacts()` in `index.html` calls
    `Contacts.requestPermissions()`, then checks `permission.contacts !== 'granted'`. That
    single `"contacts"` result is not tied to `READ_CONTACTS` alone: the installed
    `@capacitor-community/contacts` native plugin declares its permission alias as
    `@Permission(strings = { Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS },
    alias = "contacts")` (confirmed by fetching and reading the actual
    `ContactsPlugin.java` from the installed package, not from memory or the README).
    Capacitor's own core (`Bridge.getPermissionStates()` in `@capacitor/android`) resolves
    an alias to `"granted"` only if every permission string behind it is individually
    granted — read directly from its source, which comments this exact rule as
    "multiple permissions with the same alias must all be true, otherwise all false." The
    manifest only declared `READ_CONTACTS`. An app can never be granted a dangerous
    permission Android doesn't know it's allowed to ask for, regardless of what the user
    taps in any dialog, so `WRITE_CONTACTS` was permanently stuck denied and the
    `"contacts"` alias could never resolve to `"granted"` — not a race condition, not a
    device quirk, not something re-granting in Settings could ever fix. This is also a
    known, previously reported issue against this exact plugin (its GitHub issue tracker
    has a request to make `WRITE_CONTACTS` optional, confirming the requirement is real
    and has bitten other apps). Fixed by declaring `WRITE_CONTACTS` alongside
    `READ_CONTACTS` in the manifest, matching the plugin's own README setup
    instructions — the app still never writes a contact, the extra permission exists only
    so the OS will let the alias resolve at all.

16. **"Plan something" always opened the Google Calendar web link instead of the local
    calendar app, even though the calendar permission dialog itself worked fine.**
    `planWithFriend()` was calling `CapacitorCalendar.createEvent()` — a silent, direct
    write via `ContentResolver.insert()` on `CalendarContract.Events.CONTENT_URI` — after
    requesting the `writeCalendar` alias. That alias request genuinely worked (unlike item
    15, `@ebarooni/capacitor-calendar` doesn't bundle `READ_CALENDAR`/`WRITE_CALENDAR` into
    one alias, and both are declared in the manifest), which is why the permission step
    looked fine. The failure was one layer downstream: the JS call never passes a
    `calendarId`, so the native side has to look one up itself
    (`ImplementationHelper.getDefaultCalendarId()`, confirmed by reading the plugin's
    installed `CapacitorCalendar.kt`), and it throws `PluginError.NoCalendarsAvailable` if
    the device has zero rows in `CalendarContract.Calendars` — no Google/Exchange account
    added, or calendar sync turned off, both realistic on a privacy-conscious user's phone
    or a bare test device. `planWithFriend()`'s `catch` swallowed that (and would have
    swallowed any other native failure the same way) and fell straight through to the web
    link with no toast, no log, nothing to distinguish "no calendar on this device" from
    any other cause — which is why this looked like it "just always" happens with no
    visible reason. Fixed two ways: (1) switched to `CapacitorCalendar.createEventWithPrompt()`,
    which hands the whole insert off to whichever calendar app is installed via an
    `ACTION_INSERT` intent — Android's own calendar-provider docs confirm this path needs
    no `WRITE_CALENDAR` permission at all and sidesteps the calendarId lookup entirely,
    since the calendar app resolves its own default account — which is also a closer match
    for what "opening the local phone calendar app" actually asked for than a silent write
    ever was; (2) declared a `<queries>` block for `ACTION_INSERT` +
    `vnd.android.cursor.item/event` in the manifest, since `minSdk` is 30 and Android 11+'s
    package-visibility filtering can otherwise keep this exact implicit intent from
    resolving to the installed calendar app (confirmed against a documented case of another
    Android calendar-insert plugin needing the identical `<queries>` entry). The remaining
    `catch` now logs via `console.warn` before falling back to the web link, so a future
    failure (no calendar app installed at all, or the user backing out of the prompt) is
    at least visible in logcat instead of silent.

    **[2026-07-27: fallback since changed]** The "falls back to the web link" behavior
    described just above is no longer what happens — as of 2026-07-21, the fallback shares
    a real `.ics` file via `SharePlugin.shareFile()` instead (see that date's changes and
    this document's 2026-07-27 update note at the top), and the `catch` no longer calls
    `console.warn`. Left as-is here since it accurately describes the fix *at the time this
    item was written*; the newer behavior is a feature addition on top of it, not a
    regression of it.

    `READ_CALENDAR`/`WRITE_CALENDAR` are left declared in the manifest since the comment
    above them says calendar reading is planned; `WRITE_CALENDAR` in particular has no
    remaining call site after this fix, so it's a candidate for removal if that reading
    feature doesn't end up needing it — not removed here since that's a product decision
    outside the scope of this bug.

17. **Every Capacitor/Cordova library module this app depends on quietly compiled one
    SDK level behind the app module itself — `android/variables.gradle` was never added
    to the overlay.** `app/build.gradle` hardcodes `minSdkVersion 30` /
    `compileSdk 36` / `targetSdkVersion 36` directly in its own `defaultConfig`, so the
    app module itself was never the problem. But `npx cap add android` regenerates
    `android/variables.gradle` fresh every time from Capacitor's own template, which (as
    of this writing) sets `ext.minSdkVersion = 23`, `ext.compileSdkVersion = 35`,
    `ext.targetSdkVersion = 35` — and that file was never part of `overlay/`, so it kept
    resetting to those stock values on every regeneration. That would be harmless if
    nothing read it, but every single Android library module this project depends on
    does: `capacitor-android`, `capacitor-app`, `capacitor-status-bar`,
    `capacitor-local-notifications`, `capacitor-community-contacts`,
    `ebarooni-capacitor-calendar`, and `capacitor-cordova-android-plugins` all resolve
    their own `compileSdk`/`minSdkVersion`/`targetSdkVersion` with the identical pattern
    — `project.hasProperty('compileSdkVersion') ? rootProject.ext.compileSdkVersion :
    35` — confirmed by downloading and reading each package's own installed
    `build.gradle`, not assumed from one of them. `rootProject.ext` only exists because
    the root `android/build.gradle` does `apply from: "variables.gradle"`, so all seven
    of those modules were quietly compiling against SDK 35 while the app module compiled
    against 36 — a real, live mismatch on every build, not a one-time artifact of this
    zip. Fixed by adding `variables.gradle` to the overlay with the three SDK values
    corrected to match `app/build.gradle` (30/36/36), keeping every other pinned
    dependency version in the file unchanged. See BUILD.md's "Apply the overlay" section
    and the comment at the top of `overlay/android/variables.gradle` itself for the full
    reasoning — this follows the exact same pattern already established there for why
    `android/build.gradle` had to join the overlay for Kotlin support.

### Smaller robustness fixes

- `scheduleNativeReminders()` now shows a toast if scheduling genuinely
  fails (e.g. exact-alarm permission revoked after being granted), while
  leaving the normal "notifications not granted yet" path silent, since
  that's an expected, common, non-error state — not something to nag about
  on every app open.
- `planWithFriend()` now checks the calendar permission result before
  attempting to create the event, instead of relying on `createEvent()`
  itself to fail on denial (it still worked before this, just via one
  extra unnecessary native round-trip).
  **[2026-07-19: superseded by item 16]** There is no permission check here any
  more, and no calendar permission to check — `planWithFriend()` hands the insert
  to whatever calendar app is installed via `createEventWithPrompt()`.

### Verified correct — no action needed

- Every plugin JS↔native call (`LocalNotifications`, `StatusBar`, `App`,
  `Contacts`, `CapacitorCalendar`) — method names and payload shapes all
  matched the real installed packages exactly. (This check alone wasn't
  enough to catch item 15 above — matching method names says nothing about
  whether every permission string behind a plugin's alias is declared.)
- `exported` flags on every manifest component (`MainActivity` and all
  three widget receivers correctly `true` — required for the launcher and
  for the widget host process to reach them at all), network security
  config, `allowBackup="false"`.
- Calendar *permissions* specifically: `@ebarooni/capacitor-calendar` (unlike
  Contacts) splits `READ_CALENDAR` and `WRITE_CALENDAR` into separate
  `readCalendar` / `writeCalendar` aliases rather than bundling them, so the
  alias resolution itself doesn't have a version of item 15's bug. (The
  *feature* built on top of that permission still had its own bug — see
  item 16.)
- The two custom Kotlin plugins (`SyncFolderPlugin`, `WidgetDataPlugin`)
  need no `<uses-permission>` at all: Storage Access Framework grants
  folder access directly through the system picker rather than through a
  manifest-declared permission, and widget updates only touch this app's
  own `SharedPreferences`.
- `RECEIVE_BOOT_COMPLETED` and `POST_NOTIFICATIONS` are
  redundantly re-declared in the app's own manifest even though
  `@capacitor/local-notifications` already bundles both in its own
  `AndroidManifest.xml` (merged in automatically by the Android Gradle
  manifest merger) — harmless duplication, not a bug.
- Launcher/adaptive icon: real alpha channel confirmed at all 5 densities,
  content sits at ~82% of the safe-zone radius, monochrome themed-icon
  layer present, all legacy + adaptive PNG sizes exactly correct at every
  density.
- MainActivity plugin registration order (`registerPlugin()` before
  `super.onCreate()`), splash-screen API-level branching (genuinely no
  splash on API 30, OS-mandated brief splash on 31+ that this app can only
  brand, not remove).
- `@capacitor/local-notifications` silently brings its own `WAKE_LOCK`
  permission (normal-level, auto-granted, expected for scheduled
  notifications) — not declared explicitly by the app, inherited from the
  plugin. No action needed, noted for completeness.
- Root-level `.gitignore` correctly excludes `*.keystore`, `*.jks`, and
  `keystore.properties` at any depth — the signing credentials are actually
  protected, contrary to an initial concern raised and then retracted after
  double-checking the file directly.
- XSS/injection hygiene in `index.html`: every user-controlled string
  (friend names, tags, notes) is passed through `esc()` before any
  `innerHTML` use; backup-import validates ids via a strict alphanumeric
  regex before they're used to construct storage paths; avatar data is
  validated as a real data URI before being written into an `<image>` href.
- Full syntax/structure re-check after every edit this pass: both inline
  `<script>` blocks still parse as valid JS, `<div>`/`<span>`/`<button>` tag
  counts balance, and a full sweep for backslash-escape irregularities
  across the entire file came back clean.
- **This pass, run against the full permission/manifest-security/build
  checklist** (not just the two specific bugs reported): `minifyEnabled` /
  `shrinkResources` / ProGuard rules all present and correct for release
  (the existing `proguard-rules.pro` already had the exact `@CapacitorPlugin`
  keep rules a minified build needs); AGP 8.10.0 cross-checked against the
  actual pinned Gradle wrapper version (8.11.1, confirmed by reading
  `gradle-wrapper.properties` directly rather than trusting the comment that
  cites it); no dynamic (`+`) dependency versions in any editable
  `build.gradle`; no `mavenLocal()`; **[2026-07-27: stale — see that date's
  update note above]** ~~no `FileProvider` declared (so no exported-provider
  risk)~~ — a `FileProvider` now exists (added for `SharePlugin.shareFile()`),
  scoped narrowly to `cache/shared/` only via `res/xml/file_paths.xml` (not
  the stock `path="."` config item 9 originally removed), so the
  no-exported-provider conclusion still holds, just not because the
  provider is absent; `capacitor.config.json` sets `androidScheme:
  "https"` and declares no `allowNavigation` at all, which defaults to the
  safest option rather than a permissive wildcard; `debuggable` /
  `usesCleartextTraffic` / `testOnly` / `requestLegacyExternalStorage` are
  all correctly absent from the manifest.
- The three widget receivers are `exported="true"` with no
  `android:permission` guard, which a mechanical permission check would
  flag — but `AppWidgetProvider` receivers structurally can't be gated
  behind a custom permission without breaking their ability to receive
  legitimate updates from the system's widget host, since the host doesn't
  hold any permission the app could define and check for. Every published
  Android home-screen widget looks like this; worst case here is a forced
  UI refresh from a spoofed broadcast, and these receivers only ever
  re-read the app's own `SharedPreferences` to redraw — not a real
  exposure. Not a finding.
- **[2026-07-27: superseded]** `READ_CALENDAR` has no call site anywhere in
  `index.html` — same situation as `WRITE_CALENDAR` after item 16, and
  already covered by the same manifest comment ("create (and later read)
  calendar events"). This bullet is now moot rather than a live finding:
  `READ_CALENDAR`/`WRITE_CALENDAR` are no longer declared in the manifest at
  all, so there's no permission left to flag as unused. Left here for
  history rather than deleted.
- Widget provider XML (`widget_*_info.xml`, all three): no `android:configure`
  pointing at a non-existent activity, and every `previewImage` drawable
  referenced actually exists at all five densities — confirmed by listing
  the `res/drawable-*/` directories directly rather than assuming the
  reference resolves.


### Known limitations

- **Widgets cannot be resized below two grid rows on the test phone's
  launcher (Android 11, API 30) — this is a launcher limitation, not an app
  one.** Chasing a one-row size cost several build/install cycles and four
  wrong theories, so the evidence is recorded here to stop anyone repeating
  it. What was tried, all ineffective: `minResizeHeight` at 90dp, 40dp and
  32dp; `minHeight` temporarily dropped to 96dp and then to 40dp (the value
  every other one-cell widget on the test device declares, Fossify
  Calendar's Today widget included); a fresh uninstall/install so no launcher
  cached the old spans. `dumpsys appwidget` confirmed each declared value
  reached the system correctly every time, and the manifest receiver wiring
  was verified correct (exported, APPWIDGET_UPDATE, provider meta-data). The
  control experiment settled it: no other widget on the device — 44 of them
  declare `resizeMode=3`, horizontal|vertical — can be shrunk below two rows
  either. Note also that `targetCellWidth`/`targetCellHeight` are API 31+ and
  are ignored entirely on API 30, so on this device placement and resize come
  solely from `minWidth`/`minHeight`/`minResize*` with no system-level
  enforcement behind them — it is entirely the launcher's choice.

  What was kept, because it is correct on launchers that do honour it and
  costs nothing here: `minResizeHeight="40dp"` on the Spotlight and Random
  providers and `"32dp"` on Top-3 (none
  declared one before, so the floor silently fell back to `minHeight`), and
  Glance `SizeMode.Responsive` layouts — a name-only TINY bucket for
  Spotlight/Random, and a codes-dropped COMPACT bucket for Top-3. Defaults
  are unchanged from what is known good in use: 68dp for the 2x1 pair,
  120dp for Top-3. **The smaller buckets have never been seen rendering on a
  device** and cannot be, on this launcher.

- ~~**Exact-alarm special access is declared but never actually
  requested.**~~ **[2026-07-27: resolved]** `SCHEDULE_EXACT_ALARM` is in the
  manifest, and as of 2026-07-21 a purpose-built `ExactAlarmPlugin.kt` now
  exposes `status()`/`request()` to JS — `request()` deep-links straight to
  the OS "Alarms & reminders" screen via `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
  (a different mechanism than the `@capacitor/local-notifications`
  `changeExactNotificationSetting()` call this bullet originally suggested,
  but the same end result). It's wired into both the first-run onboarding
  queue and a tap-to-fix Settings row (`requestExactAlarmPermission()` /
  `exactAlarmStatus()` in `www/index.html`), matching the existing
  notification-permission row's pattern exactly as this bullet asked for.
  Left here (rather than deleted) as a record of the gap and its fix; see
  this date's update note at the top of this document for the overlay-sync
  bug found in the process of verifying this.

- ~~Two real build attempts have now happened on an actual Windows machine
  ... Those are now fixed but not yet re-verified by a third build.~~
  **[2026-08-30: re-verified]** `./gradlew clean assembleDebug` run on this
  machine — a genuine from-clean compile, not a cached/`UP-TO-DATE` result
  (`clean` first, then confirmed `app:compileDebugKotlin` and
  `app:compileDebugJavaWithJavac` actually executed rather than showing
  `UP-TO-DATE`). `BUILD SUCCESSFUL in 1m 23s`, 269 tasks (230 executed, 39
  up-to-date), producing a fresh `app/build/outputs/apk/debug/app-debug.apk`
  (21.5MB). This is the third build attempt promised above, and it confirms
  item 14's two Glance API fixes (`ColorProvider(day=, night=)`, the removed
  bad `defaultWeight` import) compile clean, alongside every other fix
  landed since the second attempt (items 15–17, and the "eighth update"
  functional fixes at the top of this document — `android/` was confirmed
  already in sync with `overlay/` for both the `WRITE_CONTACTS` manifest fix
  and `variables.gradle`'s corrected SDK versions before this build ran, so
  this result covers all of it, not just the widget files).

  **[2026-08-30, same day: installed and live-verified]** The APK was
  installed via `adb install -r` on a real device (Android
  11) and all three home-screen widgets were placed via the launcher's
  widget picker and screenshotted live: Spotlight, Thinking-of, and Top-3
  all render their empty-state text (no data on this fresh install) with
  correct, distinct colors and no clipping — confirming item 14's
  `ColorProvider(day=, night=)` fix renders correctly at runtime (not just
  compiles) and item 6's `minHeight` fix leaves room for Spotlight's
  two-line text. This closes out the last open item from this section —
  the project has now been build-verified and runtime-verified on real
  hardware, not just sandbox/static-checked.
- Widget content font is assumed to render as Roboto (Glance's actual
  platform default), since none of the three widget composables set a
  custom `FontFamily`. If that assumption is ever wrong on a specific
  device/launcher, the widget preview images would look very slightly off
  from the live widget, though the live widget's own appearance is
  unaffected either way.
- **[2026-08-30: fixed] Widget corners were square on pre-Android-12
  devices.** `GlanceModifier.cornerRadius(16.dp)` relies on
  `RemoteViews.setViewOutlinePreferredRadius`, an API 31+-only call — on
  this app's minSdk 30 (and the API 30 test device), it silently no-opped,
  so all three widgets rendered as sharp rectangles despite the code asking
  for rounded corners. Fixed by giving each widget card a `<shape>` drawable
  background (`widget_card_bg_blue.xml` for Spotlight/Top-3,
  `widget_card_bg_cream.xml` for Random, both 16dp radius, matching each
  widget's existing color) via `GlanceModifier.background(ImageProvider(...))`
  instead of a flat `ColorProvider` color — a plain drawable resource renders
  identically on every API level, so no SDK-version branching was needed.
  `cornerRadius(16.dp)` is left in place alongside it (harmless on 31+,
  still a no-op below it). Verified live on the API 30 device: rebuilt with
  `./gradlew clean assembleDebug`, reinstalled, and all three widgets now
  show visibly rounded corners in a fresh screenshot.
- **[2026-08-30: fixed] Widget picker labels didn't match their rendered
  content.** The picker cell labeled "forgetmenot — Spotlight" rendered
  `RandomWidget`'s content (cream card, "Add someone to think of") and the
  cell labeled "forgetmenot — Thinking of" rendered `SpotlightWidget`'s
  content ("All caught up", blue card). Initially suspected as leftover
  Glance widget-state from this session's repeated install/uninstall
  churn — ruled that out with a clean `adb uninstall` (confirmed via
  `run-as ... find files` that only Glance's own `datastore/` files existed,
  no user data, and six stale `appWidgetLayout-NN` files were present,
  which prompted the stale-state theory) followed by a fresh
  `adb install`: the mismatch reproduced identically on first placement, so
  it isn't state churn. The `AndroidManifest.xml` `<receiver>`/`info.xml`
  wiring was correct (`SpotlightWidgetReceiver` → `widget_spotlight_info.xml`
  → `android:label="@string/widget_spotlight_label"`, same pattern for the
  other two) — the actual bug was one level deeper, in `strings.xml`
  itself: `widget_spotlight_label` was defined as `"forgetmenot — Thinking
  of"` and `widget_random_label` as `"forgetmenot — Spotlight"`, i.e. the
  two string *values* were swapped relative to their names (and relative to
  the manifest's own explanatory comments — "Widget 1: single
  most-needs-attention friend" for Spotlight, "Widget 3: weighted-random
  spotlight" for Random). Fixed by swapping the two string values back.
  Verified with another clean uninstall/reinstall/single-widget-placement:
  the "Spotlight" picker cell now renders `SpotlightWidget`'s actual
  content ("All caught up", blue card).

(Section 9 above carries forward only the limitation that is still live; the two entries
marked fixed are kept here because that is the only place a superseded finding belongs.)

## A.2 — Build history

These are the dated notes from `BUILD.md`. The procedure they describe now lives in section 8;
what is kept here is the record of what broke and what was learned each time it was run.

> **Updated 2026-07-27:** an independent code-review pass found that
> `overlay/` had quietly drifted out of sync with the actual delivered
> `android/` tree — the two features added on 2026-07-21 (`ExactAlarmPlugin`
> and `SharePlugin.shareFile()`'s `.ics`-calendar-invite fallback, plus the
> `FileProvider` it needs) had been made directly against `android/` and
> never carried back into `overlay/`. Concretely, `overlay/` was missing:
> `ExactAlarmPlugin.kt` entirely; the `registerPlugin(ExactAlarmPlugin::class.java)`
> line in `MainActivity.kt`; `SharePlugin.kt`'s whole `shareFile()` method;
> the `<provider>` FileProvider block and `text/calendar` `<queries>` entry
> in `AndroidManifest.xml` (which still declared `READ_CALENDAR`/
> `WRITE_CALENDAR`, removed from the real manifest at some point after item
> 16 in `AUDIT.md` without a matching overlay update); `res/xml/file_paths.xml`
> itself; and the `androidx.core:core` dependency line in `app/build.gradle`.
> This is exactly the kind of drift this document's own reproducibility claim
> (steps 2–3 "reliably produce the same tree every time") depends on not
> happening — confirmed via a byte-for-byte re-diff of `overlay/android/`
> against `android/` after fixing it, the same verification method used
> throughout this document. Fixed by copying the six affected files from
> `android/` back into `overlay/`; see the "Apply the overlay" section below
> and `AUDIT.md`'s matching update note for the full list. The stale
> "delete `file_paths.xml`" cleanup step below (which predates the
> `shareFile()` feature) has also been corrected — that instruction is now
> actively wrong and would break calendar-invite sharing if followed — and
> step 8's permission list has been corrected to drop a stale mention of a
> calendar permission dialog that hasn't existed since item 16 in `AUDIT.md`.

> **Updated 2026-07-18 (third update, same day):** the build got past the
> Gradle/AGP version issue from the previous update and reached real Kotlin
> compilation — genuine progress, and it surfaced two real API mistakes in
> the original widget code that no amount of static reading had caught:
> ```
> e: .../widget/RandomWidget.kt:44:39 No value passed for parameter 'night'.
> e: .../widget/TopThreeWidget.kt:21:31 Unresolved reference 'defaultWeight'.
> (14 errors total, all in the 3 widget files)
> ```
> Both are real Glance API mismatches, confirmed against actual Glance
> documentation and source:
> - **`ColorProvider(color)` isn't a valid call** — the real
>   `androidx.glance.color.ColorProvider` function requires both `day` and
>   `night` colors with no default for either
>   (`ColorProvider(day: Color, night: Color)`). All 13 call sites across the
>   three widget files were passing only one color. Since these widgets
>   intentionally use a fixed palette regardless of system dark/light mode
>   (not a design goal to change), each is now fixed by passing the same
>   color for both: `ColorProvider(day = X, night = X)`.
> - **`import androidx.glance.layout.defaultWeight` doesn't exist** —
>   `defaultWeight()` is a member extension function scoped to `RowScope`/
>   `ColumnScope`, not a top-level import. It's automatically available
>   inside a `Row { ... }` or `Column { ... }` lambda without any import at
>   all; the bad import line has been removed and the one real usage (inside
>   a `Row` in `TopThreeWidget.kt`) needed no other change.
>
> These were errors in the *original* widget code (not something introduced
> during this audit) that only a real compiler could catch — this is worth
> noting plainly, since it's a second data point on the same theme as the
> AGP version mistake above: this project is now two-for-two on real build
> attempts surfacing something static verification missed. If you already
> had a build attempt fail on the Gradle/AGP error, delete `android/` and
> redo steps 2–3 from scratch with this corrected overlay.

> **Updated 2026-07-18 (second update, same day):** the first real build
> attempt on an actual Windows machine came back with:
> ```
> Minimum supported Gradle version is 8.13. Current version is 8.11.1.
> ```
> This is the first genuine compile-time signal this project has had — everything
> before this was sandbox-verified short of an actual Gradle run. The AGP version
> picked in the note just below (8.11.1) was wrong: checked against Google's
> actual AGP-to-Gradle compatibility table
> (developer.android.com/build/releases/about-agp), AGP 8.11 requires Gradle
> **8.13** minimum, not 8.11.1 — that assumption was never verified against the
> real table, only reasoned from the coincidence that the version numbers
> matched. **Fixed by using AGP 8.10.0 instead**, which the same table shows
> needs only Gradle 8.11.1 (already what's pinned — no wrapper change needed)
> and which Google's own "minimum AGP per API level" table confirms is enough
> for `compileSdk`/`targetSdk` 36 (that table lists 8.9.1 as the actual floor).
> If you already ran a build and hit the error above, delete `android/`
> and redo steps 2–3 from scratch with this corrected overlay rather than
> patching just the one line, in case anything else downstream cached against
> the old version.

> **Updated 2026-07-18:** a full independent re-audit this pass — starting
> from scratch rather than trusting the previous write-up — found and fixed
> several real issues, one of them build-breaking:
> - **The Compose Compiler Gradle plugin was missing entirely.** The three
>   widget files use `androidx.glance:glance-appwidget`, which is built on
>   real `@Composable` functions — without the compiler plugin that
>   processes that annotation, the project could not have compiled at all,
>   full stop. This is now fixed (see the note below step 3), but since this
>   sandbox still can't run an actual Gradle build (see below), it hasn't
>   been watched compile clean — that first real build is still the first
>   genuine test of this specific fix.
> - The calendar plugin call in `planWithFriend()` was using a method name
>   that doesn't exist on `@ebarooni/capacitor-calendar` — confirmed against
>   the actual installed package and fixed. Previously this silently fell
>   back to the web calendar link on every use, every time.
> - The AGP version pinned in `android/build.gradle` (8.7.2) doesn't
>   officially support this project's `compileSdk`/`targetSdk` (36) — Google's
>   own release notes cap AGP 8.7–8.9.0 at API 35. Bumped to 8.10.0 (see the
>   note above — the first version of this fix used 8.11.1, which turned out
>   to be wrong).
> - "Export backup" used a browser-only `Blob` + `<a download>` pattern that
>   silently does nothing in Capacitor's Android WebView (confirmed by
>   checking the actual installed `@capacitor/android` source — no
>   download-handling code exists in it at all), while still telling the
>   user it succeeded. Now writes into the chosen sync folder instead.
> - `FileProvider`/`file_paths.xml` (unused, over-broad scope) and dead
>   leftover code from a removed notification-prompt banner were removed.
> - The Spotlight/Random widgets' declared `minHeight` (40dp) couldn't
>   actually fit their real two-line content once padding is subtracted —
>   raised to 64dp so a user can't resize them into a state where text clips.
> - The PWA icon (`icon.svg`/`icon-192.png`/`icon-512.png`) still showed an
>   unrelated old design — replaced with the same flower mark used on the
>   native launcher icon, geometry and colors matched directly from the
>   actual asset rather than redrawn from scratch.
>
> **Splash screen — what "no splash screen" actually means:** on API 30
> (Android 11, this app's `minSdk`), there is truly no splash step at all —
> the launch theme is a plain alias of the real app theme, nothing extra
> renders. On API 31+ (Android 12 and up), the OS shows its own brief system
> splash on every cold start unconditionally — this is a platform mandate no
> app can opt out of, regardless of configuration. What this project controls
> on API 31+ is *which* splash you see: the real launcher icon and this app's
> own colors, instead of Capacitor's generic stock graphic. If your test
> device is Android 12+ (likely, for a phone bought recently), you'll still
> see a brief branded flash on cold start — that's the OS, not a leftover bug.

### If the first build fails

Most likely causes, roughly in order of probability for a project this
size on a first attempt:
- A version mismatch between AGP/Gradle/the Glance library versions
  pinned in the overlay files and what's current by the time you actually
  build this.
- A typo somewhere in the ~15 new files that only a real compiler would
  catch.

Paste me the actual Gradle error output when you hit one and I'll fix the
specific file — that's a much faster loop than me trying to preemptively
guess every possible failure from here.

---

## A.3 — Changes after consolidation

Entries added after this file replaced `AUDIT.md` and `BUILD.md`, newest first. Same rules as
above: true on their date, and superseded by later entries rather than rewritten.

> **Updated 2026-10-22 (ninth entry):** P7 removed the web build. `www/`, `overlay/`,
> `capacitor.config.json`, `package.json` and every Node tool but `tools/widget-previews.py` are
> deleted, and CI is one Gradle job. The citation checker and its fault fixture moved from
> `tools/spec.mjs` and `tools/selftest.mjs` to `spec/SpecCitations.kt`, run by `testDebugUnitTest`;
> `spec/BuildHygieneTest.kt` keeps the old runtime's name out of the tree. The
> `.claude/workflows` checks no longer run in CI. Sections 1-9 were rewritten from the Kotlin,
> not carried over, and where the native app differs from what they used to say, the text now
> describes the native app. The differences worth knowing:
> - Nudge buttons act in a background receiver, toast and dismiss; they no longer open the app.
>   One channel, `reminders`. `BootReceiver` replaces the plugin's restore receiver, and a
>   nightly 03:30 rebuild and a delivered-set guard are new (section 4).
> - Widgets write the record directly; there is no reach-out queue to drain (sections 2, 5, 6).
> - A read-only session also lifts when a folder with data is picked and loads; a failed
>   adoption reverts to the previous grant; the conflict scan and resolve refuse while read-only
>   (sections 2, 3); a resolve tapped there says the folder couldn’t be read.
> - The manifest declares four permissions; WRITE_CONTACTS is gone (section 6).
> - Back closes only the topmost sheet; Escape and the hand-drawn focus ring have no native
>   counterpart; deleting a group still asks through a stand-in `AlertDialog` (section 7).
> - A message link nothing can open now toasts "No app on this phone can open that" (section 9).
> - Dropped with no subject left: the overlay mirror and `overlay:check`, `version:check`,
>   `npm run serve`, the ES-module cycle check, the plugin reflection keep rules, and the
>   wrapper-pin guard. The tagline came from `package.json` and went with it.
> - Timings measured on the web build (the folder listing on the Android 14 test phone, the
>   overwrite sequence) are kept and labelled as such; they were not re-measured natively.

> **Updated 2026-10-16 (eighth entry):** a read-only session (the sync folder could not be read
> at start-up) no longer pushes to the widgets or reschedules reminders. `init()` already skipped
> both, but `applyTheme()` and every `saveFriendRecord()` called them too, so a theme change or an
> edit made in memory rebuilt the schedule from a partial cache, cancelling the reminders of every
> friend that had not loaded, and gave the widgets that partial list. Both now return early while
> `writesBlocked` is set; `tools/storage.test.mjs` covers it. The other open item from the
> consolidation run, message links on Android 11+, needs no change: Capacitor opens them with
> `startActivity`, which package visibility does not filter.

> **Updated 2026-10-16 (seventh entry):** sizes were audited on the 6T against the hierarchy,
> in dp. Fixed: the search bar took taps only on its 18 dp text line (the whole 38 dp bar does
> now); Message, Plan something and Share were 57 dp, taller than Log today, and are 48 dp;
> inline text buttons were 48 dp boxes that pushed their rows taller, and are 32 dp with a 40 dp
> hit area; Mark as wished was as faint as Clear it, and is primary and bold; Settings rows (40)
> and text fields (41-43) are 48 dp. Remove buttons in card rows made their rows 56 dp and the
> pause-until field its row 65 dp, against about 41 for the rows around them; both now add
> nothing to the row's height (41 and 49 dp). Section 7.

> **Updated 2026-10-16 (sixth entry):** a spec-consolidate run rebuilt sections 1-9 from the
> code and resolved 1,316 citations. The rebuilt sections were not adopted: they came out 42%
> longer and left out about thirty facts the code cannot show (device measurements, the merged
> manifest's permissions, recovery steps). What the run found wrong in the current text was fixed
> in place: sixteen sheets, not fifteen; 32 modules, with `merge.js` and `reminders.js` listed;
> `index.html`'s inline theme script; 66 tracked files under `android/`, 57 of them mirrors;
> legacy group files are rewritten under `groups/`, not moved. Section 7's touch-target rule now
> sizes controls by their place in the hierarchy, with the hit area kept separate, instead of a
> flat 48 px; the 40 px pause-until field is tertiary and stays. It also found a second Enter
> handler on the kind chips (removed; the
> delegated one in `main.js` covers them), and Kotlin and manifest comments still pointing at
> `www/index.html` and an onboarding exact-alarm step that no longer exists.

> **Updated 2026-10-16 (fifth entry):** a smaller home for low days: a `smallHome` setting under
> Settings, HOME, shows only "Maybe today" (the three most due), with "Show everyone (N)" for
> the rest until the app restarts. Search is unaffected. Sections 2 and 4.

> **Updated 2026-10-16 (fourth entry):** gift ideas: a card under a friend's dates lists them,
> "Add a gift idea" asks for one with `ask()`, and removing one can be undone. `gifts[]` merges
> by id like the other logs, and the backup validator drops an idea without an id or text. The
> birthday nudge names them. Sections 2, 3 and 4.

> **Updated 2026-10-16 (third entry):** a reach-out can say how it happened: call, met up or
> message. It is optional everywhere: chips on the Earlier… sheet, `text` from the offer after
> Message, and a "How?" button on each history row for the one-tap logs. The detail header names
> the latest one. Section 2.

> **Updated 2026-10-16 (second entry):** the widget picker's previews were redrawn by a new
> script, `tools/widget-previews.py`, at all five densities: the current labels ("Maybe say hi to",
> "Thinking of", "Maybe say hi"), the log tick, Skylight light's `--bg`, `--text`, `--text-muted`
> and `--primary`, and real day codes (the old Top 3 preview showed "3w", a code the app never
> writes). Section 5.

> **Updated 2026-10-16 (first entry):** a release check of #10 and #11, an Android 16 pass, and the review's
> visual leftovers.
>
> - **Release (R8) build** of `main` after #11 installed over the debug build on the 6T and
>   launched clean; the new ExactAlarm battery methods answer in release.
> - **API 36 emulator (Android 16):** home, the detail screen with the new cards, `ask()`'s sheet,
>   the toast (52 px from the bottom: 28 plus the 24 px inset) and Settings all draw clear of both
>   system bars. Capacitor's SystemBars logs one "Error injecting safe area CSS" at start-up, from
>   an inset callback that runs before the page exists; later callbacks set the values.
> - **Measured fixes:** the cadence ring's track is half `--border`, half `--bg`, so the fill
>   clears 3:1 against it everywhere (worst was 2.22, now 3.41); dark-mode swatches use each skin's
>   dark `--primary` (worst was 2.04, now 3.92); Meadow dark's `--decorative` is green `#b5c98a`
>   instead of peach (8.90:1 with its text).
> - **System:** ten font sizes folded into seven `--fs-*` tokens; 20 inline styles in rows built
>   by JavaScript became classes (the archived list's Restore button, 12 px before, is now a full
>   48 px text button); scrolling screens end clear of the navigation bar; Battery limits reads
>   "May delay — tap to fix" on one line.
>
> Verified: `npm test`; on the 6T, the ring in Ember dark, the swatches in a dark theme sheet,
> Meadow dark, and the last Settings row 59 px above the 48 px navigation bar.

> **Updated 2026-10-15 (third entry):** the review's data-model items.
>
> - **New friend fields:** `askNext` ("Next time, ask about…", also the check-in nudge's body
>   when set), `howWeMet`, and `dates[]`, each with its own reminder. Older builds ignore them;
>   backup import drops malformed values instead of refusing the file.
> - **"Mark as wished":** a "Wished them" button on the birthday nudge and on the detail screen on
>   the day, both logging a reach-out. The detail check compares month and day; a first version
>   compared against the next occurrence, which has already moved to next year once the day's
>   09:00 has passed, and never showed the button after the morning.
> - **Sync conflicts keep both sides' logs.** Reach-outs, notes and dates from the losing copy are
>   merged into the winner by id (section 3), which used to drop them into a `.lost-` file.
>
> Verified: `npm test` (merge, validation and two conflict cases are new) and the detail screen,
> the add-a-date sheet and the form in a browser preview. On the Android 11 test phone (2026-10-16), with a
> throwaway friend in memory only: the scheduler queued the birthday, "Interview · Ana" and a check-in
> reading "Maybe ask about the new flat.", and skipped a one-off date already past; a birthday nudge
> showed "Wished them", and tapping it in the shade logged a reach-out. Test data was cleared.

> **Updated 2026-10-15 (second entry):** a release check, then the review's remaining small items.
>
> - **Release build verified.** The R8 build of everything through #9 installed over the debug
>   one on the Android 11 test phone, launched clean (empty crash buffer) and rendered a placed Thinking of
>   widget in Rosewater dark.
> - **Widgets:** labels 12 sp and one voice ("Maybe say hi to", "Thinking of", "Maybe say hi"),
>   "Everyone's paused" or "Add someone" in place of "All caught up", and a log tick on Thinking
>   of. On the phone the label wrapped beside the tick, so both small cards now put it on its own
>   line. The picker's preview images still show the old cards (section 9).
> - **`ask()` replaces `confirm()` and `prompt()`**, and a sync conflict's choice is asked twice.
> - **Skylight light's `--text` darkened** from `#3E5D74` to `#2A4356`: 8.70:1 on `--bg`, and
>   1.86:1 from `--text-muted` (was 1.26, the flattest of the eight palettes).
> - **"Talked to Ana? Log it"** after Message, **accent- and note-aware search**, **the next nudge's
>   day** on the detail screen, and **Settings → Battery limits**.
> - **The toast clears the navigation bar.** On the phone it sat on the system bar under
>   edge-to-edge; its bottom offset now adds the safe-area inset.
>
> Verified: `npm test`; in a browser preview the questions, search and readback; on the phone the
> offer on a real resume and its Log it, the Battery limits row (it reported optimised), and a
> placed Thinking of widget with the new label line, tick and "Everyone's paused". Test widgets
> were removed and the app data cleared.

> **Updated 2026-10-15 (first entry):** the widgets follow the app's skin and mode. The app sends the four
> tokens a widget uses with every summary push, and again on a theme change; the three cards
> share one white drawable tinted to `--bg` instead of a fixed blue and a fixed cream one. The
> hand-copied colours in each widget, and the limitation in section 9 about them, are gone. Thinking
> of, which was always amber, now matches the other two. Section 5.
>
> Verified: a debug build; on the Android 11 test phone a skin change to Rosewater dark wrote exactly that
> palette's `--bg`, `--text`, `--text-muted` and `--primary` to the widget store, and a placed
> Spotlight redrew in Rosewater dark and then Ember light with rounded, tinted cards on Android 11.
> Before the app first ran, it showed the Skylight light default.

> **Updated 2026-10-14 (third entry):** the two decisions the second entry left open, taken on
> scored mockups (home: today 25/40, softened 32, self-history 31; undo: a recent-changes list
> 30, one toast per action 29). The owner chose the softened home and the list.
>
> - **Home.** Sections are "Been a while", "In between" and "Recently in touch", sorted like the
>   widgets (`days / cadence`, "In between" soonest first) instead of by raw days. Each row is an
>   open button and a 48 px "Log" button side by side, no longer a button nested in a
>   `role="button"` row; the ring is 3 px; the search reads "Find someone". Section 4.
> - **Undo.** The last five changes, each undoable in any order from Settings → Recent changes,
>   replacing the one-deep undo. Archiving a friend, deleting a tag and deleting a group event no
>   longer ask first; they go on the list. Section 9 supersedes its earlier "one action deep".
>
> Verified: `npm test`, and in a browser preview the home order against the mockup, and four
> changes undone out of order from the list with no confirm dialog shown.

> **Updated 2026-10-14 (second entry):** gentler reminders and a friend screen that can log,
> after a review that scored every screen on register, glanceability, accessibility and visual
> coherence and compared the app with the owner's other projects and published keep-in-touch
> apps. The owner chose the scope; the home-list presentation and how deep undo goes were left
> for a decision on mockups, so the home sort and rows are unchanged here.
>
> - **Overdue nudges no longer repeat every morning.** Each resume used to re-anchor every overdue
>   check-in at the next 09:00, so opening the app daily without logging fired every overdue
>   friend at once, every day. Now one fires per step of a quarter cadence (at least a week),
>   imported friends are spread over up to a week, and nudges within five hours of each other
>   share one notification (the owner's rule). Section 4.
> - **Snooze and pause until a date.** "Next week" on a nudge, and an optional end date on the
>   detail screen's pause. Two new optional fields, `snoozedUntil` and `pausedUntil` (section 3);
>   an older build ignores them.
> - **The detail screen logs.** "Log today", "Earlier…" with one-tap picks, "Last in touch", and the
>   reach-out history with removal and undo. This also closes the keyboard and switch-access gap
>   section 7 recorded for back-dating.
> - **Exact alarms moved to Settings only**, out of the onboarding queue.
> - **Smaller fixes:** 48 px touch targets throughout, whole rows for switches, focus moved on
>   screen change, a 10-second Undo with a 48 px button, the add form's draft kept on Cancel and
>   Back, and a home banner while no sync folder is chosen.
>
> Verified: `npm test` (`tools/reminders.test.mjs` is new) and the screens in a browser preview.

> **Updated 2026-10-14 (first entry):** a dead-code sweep. No unreferenced function, import, local, CSS class,
> id, custom property, resource, Gradle pin or plugin method was found; what went:
>
> - **33 needless `export`s.** Names that no other module or test imports are now module-private
>   (`friendRowHTML`, `channelUrl`, `queueWrite`, the `wire*` functions in `main.js` and the
>   rest); the ones `storage.test.mjs` reaches through `store.` stay exported.
> - **Ten raster launcher icons.** With minSdk 30 every device resolves `mipmap-anydpi-v26`, so
>   the `ic_launcher.png` and `ic_launcher_round.png` at five densities never loaded. They are
>   gone from `overlay/` and `android/`, and added to `STOCK_LEFTOVERS` so a regenerated tree
>   cannot bring the template's back.
> - **A stale comment** in `app.css` still said the status-bar spacer was zero-height before
>   Android 15; under Capacitor 8 that holds only in a browser tab.
>
> Kept on purpose: the root-level `fm_group_<id>.json` fallback (section 3, still tested), and
> `StatusBar.setBackgroundColor`, which still paints the bar below Android 15.
> Verified: `npm test`; a debug build installed over the app on the Android 11 test phone, with all 29 modules
> linking, home and onboarding rendering and the theme picker working.

> **Updated 2026-10-12 (third entry):** Capacitor 8. Every package moved to its 8.x release
> (core, android and cli 8.5.3; all six plugins had one). The upgrade guide's Android steps were
> applied by hand to `overlay/`, because `npx cap migrate` stops at an interactive prompt and would
> have edited `android/`, which the overlay overwrites: AGP 8.13.0 (the 8.10.0 pin above is
> superseded), Gradle 8.14.3 with the wrapper regenerated, the guide's AndroidX versions in
> `variables.gradle`, `density` added to the activity's `configChanges`, and `=` assignments in
> `app/build.gradle` (`version.mjs` and its selftest fixture now accept them). Kotlin 2.2.21 already
> met the guide's 2.2.20, and minSdk stays 30.
>
> - **Edge-to-edge is now every version.** On the Android 11 phone the StatusBar plugin reports
>   `overlays: true` and a 28 px inset, so the spacer bar from the second entry is what draws the
>   status bar everywhere, not only from 15. The CSS reads SystemBars' `--safe-area-inset-*`
>   first. The navigation bar became transparent over the page.
> - **Verified:** debug and release (R8) builds; on the phone every plugin answered (App, Camera,
>   Contacts, Calendar, LocalNotifications, StatusBar and the four in-app ones), a folder pick,
>   save and overwrite, a reminder scheduled, a contact logged from the list, all three widget
>   receivers registered; on the API 36 emulator the bar and a scrolled list.
> - **`npm audit`:** the 8.x CLI pulls a `uuid` with a moderate advisory through its iOS `xcode`
>   dependency. An `overrides` entry pins `uuid` ^11.1.1; `cap sync` and `xcode` still work.

> **Updated 2026-10-12 (second entry):** the open items in section 9 were worked in dependency
> order — code changes first, then the release build that carries them, then the device. Commits
> `734e8d4` through this one.
>
> - **Writes are atomic** (`734e8d4`). `writeFile` replaces by write-then-rename and any listing
>   restores an orphaned `.bak`; section 6 has the mechanism. Verified on the Android 11 test phone by planting
>   each crash state by hand (a lone `.bak`; `.tmp` and `.bak` beside a live file; a `.bak` beside
>   a file being deleted; a `.bak` seen only by `readdir`), and by an app-level save and reload. The
>   cost was measured, not assumed: ~350 ms per overwrite against ~50 ms before, new files
>   unchanged at ~125 ms. Renames are ~10 ms; the create and delete each pay media-store work.
>   A first version also asked the provider for the renamed file's name, a query per rename; the
>   path-shaped document id answers that on external storage.
> - **Storage and the conflict scan have tests** (`c45ed7a`), against a fake `SyncFolder`. Each of
>   six planted bugs was caught before the file was trusted.
> - **Capacitor 7.6.9** (`dd435cd`) closes GHSA-rvm3-566m-v7fv in `@capacitor/android`, which is in
>   the APK; the other two audit findings were in the CLI's own dependencies.
> - **The first release build since the Kotlin 2.2 / Glance 1.2 bump** (an entry below says it had
>   none) built, installed over the debug build, loaded the sync folder, saved through the new
>   write path, and rendered and logged from the Spotlight widget — so R8 kept the plugins and the
>   widget receivers. It also found a bug no test had: a friend with no `cadenceDays`, which the
>   backup validator allows, classified as overdue and read "every NaN years" (`3ecb12d`).
> - **Android 15+ edge-to-edge**, on an API 36 emulator: the status bar came out unpainted, white
>   icons on the light page. The spacer now draws the bar itself (section 7). A first attempt with
>   `position: sticky` scrolled away with the page; `#screen-home` clips overflow.
> - **Section 9 was wrong about the test phone.** It said the older test phone "now runs Android 14";
>   `getprop` says 11. The two-row widget floor was re-observed there with the release build.
> - Not done: Capacitor 8, which section 9 keeps as a separate change.

> **Updated 2026-10-12:** CI was added (`015c24e`): `.github/workflows/test.yml` runs `npm test`
> on every pull request and push to `main`. Sections 8 and 9 said "there is no CI" until this
> entry; both now describe the workflow and its limit (Node only, no APK build).

> **Updated 2026-10-10:** the storage layer's data-loss paths were closed, the web layer was
> split into modules, an Android audit's findings were fixed, and sections 1–9 were re-read
> against the result and rewritten. Commits `a1b0b66` through `7b79e6a`.
>
> - **Storage stopped mistaking failure for absence** (`a1b0b66`). Picking a sync folder used
>   to neither load what was in it nor write memory out to it; it now adopts the folder's data
>   and merges anything created before the pick, or fills an empty folder. Only `NOT_FOUND` now
>   counts as an absent file: an index that exists but cannot be read is rebuilt from its
>   directory listing, `seedIfEmpty()` no longer writes the friend index at all, and if the
>   folder cannot be read at start-up the session is read-only rather than writing a half-loaded
>   cache over good files. A missing record is left out of memory instead of stored as `null`,
>   which had made every later backup unimportable; import skips such nulls and now checks
>   field types and that each record's id matches its key. Writes to one key run in order, and
>   pull-to-refresh waits for them. Reminders are built before the old ones are cancelled, a bad
>   date skips one reminder rather than all, and a never-logged friend's first nudge counts from
>   a new `createdAt` field instead of from "now", which had pushed it out on every resume.
>   Monthly and yearly events no longer fire before their start date; an index merge loads the
>   records it brings in; the conflict sheet awaits the resolution. Smaller: local dates for the
>   log picker and backup filename, pull-to-refresh checks the document's scroll, Enter on a
>   row's log button logs, contact photos are resized, the theme picker keeps focus, and a tag
>   deleted mid-edit is not saved onto the friend.
> - **`www/index.html` became markup only** (`084f939`): 29 ES modules under `www/js/` and
>   `www/css/app.css`, with no bundler, still served from `www` by Capacitor (over
>   `https://localhost`, so modules load). A desktop browser needs `npm run serve`. The tests
>   now import the shipped modules instead of slicing functions out of the HTML, and
>   `tools/modules.test.mjs` checks the import graph, because a bad import on device is a blank
>   screen with nothing to say why. `test:dates` became `test:unit`.
> - **Android audit fixes; Kotlin 2.2** (`df50f37`). Glance deletes its state file whenever any
>   widget instance is removed, and that file was the widget prefs — so removing one widget
>   could delete the queue of reach-outs logged from the others; the state definition now names
>   an unused file. Widget-prefs read-modify-writes share a lock. The Thinking-of widget seeds its
>   pick from the data, so every size bucket shows the same friend. `SyncFolderPlugin` lists a
>   directory with one `DocumentsContract` query instead of a query per child, and `pickFolder`
>   moved to `startActivityForResult` with an `@ActivityCallback`, rejects a superseded pick and
>   releases older tree grants. Every `reject()` now passes `(message, code)`, so JS sees the
>   code in `e.code` — the storage fix above depends on it. The share cache is cleared before
>   each write, and the release-signing guard reads the task graph, so `gradlew build` without
>   a keystore fails too. Kotlin 2.0.21 → 2.2.21, Glance 1.1.1 → 1.2.0, plus documentfile,
>   core-splashscreen and coroutines bumps. Not yet verified with a release build on a device.
> - **The checkers** (`df50f37`, `c75e27d`, `9ea17b6`). `spec.mjs` now checks `.css`
>   citations, fails on a bare `:123` citation instead of counting it, matches anchors as whole
>   words and paths by exact case, and flags line 0; the self-test grew from eighteen faults to
>   23. `overlay.mjs` reads the AGP version and looks up the Gradle floor from it rather than
>   hard-coding 8.11.1.
> - **The modules were simplified** (`7b79e6a`): repeated patterns became single helpers
>   (`plugin(name)`, `addContact`, `plural`, `clampedNineAm`, one conflict regex where there
>   were two, one `RECORD_SPECS` table), `render()` and the back button dispatch from maps, and
>   comments lost their history. Output strings are unchanged.
>
> Reading A.1 and A.2 after this: a function they place "in `www/index.html`" now lives in a
> module under `www/js/` (section 1 has the map), and their `index.html:N` line numbers no longer
> point at the code they name. Sections 1–9 were rewritten against the new code rather than patched; among the
> claims that changed are the conflict regexes (one now, not two), the notification-action
> listener (there is one), what `npm test` runs, and how `SyncFolder` looks up a file.
>
> Found while re-reading the code for this entry, and fixed the same day: `adoptPickedFolder()`
> cleared the cache and `writesBlocked` *before* loading the new folder, so a load that failed
> partway left a writable session over a partly loaded cache — the state the read-only rule
> exists to prevent — and then pushed that cache to the widgets and reminders. A failed load
> there now makes the session read-only again and leaves widgets and reminders alone. And in a
> session already read-only, picking an empty folder wrote nothing while the toast said "Sync
> folder set"; it now refuses, since the cache it would copy is the partial one.

> **Updated 2026-09-13:** four sibling projects in the same `Builds` folder were read for
> anything worth carrying over. Three parallel read-only surveys, one per project, each briefed
> with this app's feature set and repo discipline; every claim they made about *this* app was
> re-checked against the source before anything was planned. What was taken, and from where:
>
> - **A checker that has never failed is not known to work** (Tarot's `build.py` self-test and
>   its spec's line "a check never seen to fail is not known to work"). `tools/selftest.mjs`
>   now injects eighteen faults into a fixture tree and requires each to be named. The first
>   run was green on every fault — which is the one result that rule says not to trust — so
>   `spec.mjs`'s anchored check was disabled by hand and the self-test was watched to fail on
>   exactly that case before the green was believed. Same for the date sweep: the Feb 29 clamp
>   was removed and two of six tests went red.
> - **One version, declared once** (Tarot's `check_manifest.py`). The app already disagreed
>   with itself — `1.0.0` in `package.json` and Settings, `"1.0"` in `versionName` — and
>   nothing had compared them. `tools/version.mjs` does; `versionName` is now `1.0.0`.
> - **Sweep the calendar rules across every year** (Old Year Almanac's `tools/datecheck.py`).
>   The two date helpers gained a defaulted `now` parameter and `tools/dates.test.mjs`
>   drives them through 2027 and 2028 at 08:00 and 10:00.
> - **A backup carries its version and an import never leaves the device empty** (Equipoise's
>   `Snapshot.kt` / `Transfer.kt`). `version: 1` in every export, refuse-if-newer on import,
>   and the replace now writes before it prunes.
> - **Newest edit wins, the loser is kept** (Tendril's per-record merge, which reaches the same
>   `.sync-conflict` fallback this app had but only *after* a timestamp merge, and preserves
>   the losing copy under a name its own sweep will not eat). Record conflicts with two
>   differing `updatedAt` stamps resolve without the user; the other copy is written beside
>   the record as `<id>.lost-<ts>.json`. Ties and missing stamps still ask.
> - **The widget opens the friend it names, and can log without opening anything** (Tendril's
>   "the widget goes through the same use case as the app"; Equipoise's whole low-capacity
>   path is a Glance widget whose tap writes one row, and its finding that a `Float` through
>   Glance `ActionParameters` comes out as 0.15000000596). Every widget tap now carries
>   `forgetmenot://friend/<id>` on an explicit intent; Spotlight's REGULAR layout has a tick
>   that queues `{ id, date }` in SharedPreferences for the app to drain. Reading Capacitor's
>   `BridgeActivity.load()` showed it replays the launching intent through `onNewIntent` and
>   the App plugin retains the resulting `appUrlOpen`, so the plan's manifest intent-filter
>   and `getLaunchUrl()` call were both dropped: the explicit intent needs neither, and the
>   second would have delivered the same URL twice.
> - **"Logged it" on the nudge** (Tendril's inline notification actions). Check-in nudges only.
> - **Found while testing the tick, and older than it: widget updates inside a live Glance
>   session did nothing.** The three widgets read SharedPreferences from `provideGlance()`,
>   which a session's update event does not re-run, so a push within 45 seconds of the
>   previous one left the launcher holding the old RemoteViews — open the app, log someone,
>   and the widget stayed put until a later push. Reproduced as "every push lands one behind"
>   three times in a row before the cause was clear. Fixed by making the summaries the
>   widgets' Glance state (`widget/FriendSummaryState.kt`) and reading them through
>   `currentState()`; section 5 has the mechanics. Same session also found the Spotlight
>   name wrapping to three lines and clipping once the tick took its width: now 16 sp, two
>   lines.
> - `.semgrep/` is ignored: the file in it is a Semgrep Guardian OAuth session record, not
>   configuration, which the sibling repos had already worked out.
>
> Read and deliberately not taken: an on-device LLM (5.8 MB of native code, a 1 GiB model and
> +1.1 GB of PSS in the sibling that ships one, for what is a template with a name in it, in an
> app that has no INTERNET permission by design); FTS5 search over a few dozen in-memory
> records; an RFC 5545 recurrence parser for a once/monthly/yearly field; KMP, Compose, Room and
> a shared desktop core; AES-GCM encryption of the sync folder; a top-of-file Revision Log,
> because this appendix already is one; and atomic tmp→`.bak`→rename SAF writes, which are
> the one sync-hardening item from Tendril still worth doing and are noted in section 9.

> **Updated 2026-09-03:** the build workflow was made cross-platform, and the two blind spots
> section 8 documented but did not cover were closed.
>
> - **Supersedes the `.\gradlew.bat` fix in A.1's tenth update.** That entry diagnosed the
>   cause correctly — a bare `gradlew.bat` after a `cd` is not resolved from the changed
>   directory by npm's shell — but fixed it in a way that only works on Windows, which left
>   the documented clean-checkout path unusable on Linux and macOS. Both build scripts now go
>   through `tools/gradle.mjs`, which resolves `android/` to an absolute path and picks the
>   wrapper by platform. Separately, `android/gradlew` was tracked mode `100644`, so a POSIX
>   clone received it without its execute bit and failed with an unexplained "Permission
>   denied"; it is now `100755`, content untouched. Neither half is worth anything without
>   the other, which is why they landed in the same commit.
> - **The wrapper pin is now checked.** Section 8 recorded that the AGP 8.10.0 / Gradle
>   8.11.1 pairing "depends on that template continuing to pin 8.11.1 or higher, and
>   `overlay:check` cannot see a change there". It can now: `check` parses the version out of
>   `distributionUrl` and fails below `MIN_GRADLE`. The comparison is numeric rather than
>   lexical because the case that matters — 8.9.1 against 8.11.1 — is precisely the one a
>   string comparison gets backwards, i.e. the guard would have passed the broken pin it
>   exists to catch. Verified by downgrading the pin to 8.9.1 and watching it fail, bumping
>   it to 8.13 and watching it pass, and mangling `distributionUrl` to check the unparseable
>   path.
> - **Reverse drift now covers `res/`.** It previously walked only the Kotlin package, so an
>   edit to an existing overlaid resource was caught as `DIFFERS` while a brand-new file
>   dropped into `res/` was invisible. `res/xml/config.xml` is allowlisted as the one
>   generated file under that root. Verified by planting a rogue file in each root in turn.
> - **The drift check moved from `presync` to `postsync`.** It now runs after `cap sync`
>   instead of before it. Worth being accurate about the reason: `cap sync` was tested on
>   this tree and does *not* touch overlay files, so this fixed no observed bug. It moved
>   because `cap sync` is the step most able to overwrite a native file, and a check that
>   only ever ran before it could not see that happen. The check still verifies the result of
>   the apply, since nothing between the two touches `overlay/`.
>
> One reference in section 8 was wrong on arrival and is noted here rather than silently
> fixed: the new `overlay.mjs` comment first cited the AGP classpath as
> `overlay/android/build.gradle:29`, which is a comment line; the classpath is at `:32`.
> Caught by re-reading every line reference against the file rather than trusting the text —
> the same check that retired the previous two documents.


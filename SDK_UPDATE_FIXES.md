# Demo App Changes — SDK Sync (2026-08-08)

Context: `samvyo-android-sdk` picked up 3 new commits (`feb2799`, `c69bf5a`, `17108d7` —
producer/transport bug fixes, 6 previously-ignored server message handlers, and audio
codec parity). Most of that is internal to the SDK and needed no app-side change. This
covers what *did* need a change here, in `samvyo-demo-android-app`.

## New `RoomEvent` types the app now handles

The SDK added 4 new event types plus one changed signature. Before this pass,
`RoomViewModel.handleEvent()` had no case for any of them, so they silently fell into
the `else -> {}` catch-all.

| Event | Why it mattered | Fix (`RoomViewModel.kt`) |
|---|---|---|
| `RoomEvent.JoinFailed` | **Real bug.** Fires after the SDK exhausts 3 automatic retries on `joinRejected`. Nothing transitioned the UI off `RoomUiState.Joining`, so a permanently rejected join left the screen spinning on "Joining…" forever with no way to retry. | Transitions to `RoomUiState.Error(message)`. |
| `RoomEvent.JoinRejected` | Not fatal — the SDK auto-retries with jitter — but with no feedback the app just looks frozen for the retry window. | Shows a notification banner: `"Room busy (<reason>) — retrying…"`. |
| `RoomEvent.ViewerCount` | Missing feature. Server pushes this ~1Hz for EVENT-type rooms, which this app already supports as a room type. | New `viewerCount: StateFlow<Int?>` on the ViewModel; UI shows `"👁 N watching"` in `VideoCallScreen.kt` whenever populated (stays `null` for non-Event rooms since the server never sends it there). |
| `RoomEvent.RecordingMigrating` | Missing toast during a P2P→SFU migration, before `RecordingStarted` follows. | Notification: `"Recording will start shortly…"`. |
| `RoomEvent.ConnectionStateChange(status, reason)` | `reason` is new (e.g. `"server_restart"` when `rebuildMedia` silently re-registers our join after an SFU autoscaler restart). Backward compatible, but was silently dropped. | Logged; shows `"Server restarted — reconnecting…"` when `reason == "server_restart"`. |

## Known gap — not fixed here (SDK-side)

`toggleMyCamera` (new server message, host force-disables a peer's camera) calls
`mediaEngine?.setCameraEnabled(enabled)` directly but emits **no event** — unlike its
`toggleMyMic` sibling, which emits `MicForcedOff` (already handled, flips `isMicMuted`
correctly). Because there's no event to react to, the demo app's `isCameraOff` flag
goes stale after a server-initiated camera toggle: the hardware state changes but the
"Camera On/Off" button still shows the old state. Needs a matching event on the SDK
side (e.g. `CameraForcedOff`) for parity — can't be fixed from the app alone.

## Files touched

- `app/src/main/java/io/samvyo/demo/RoomViewModel.kt` — new event handlers, `viewerCount` state flow, `resetCallState()` reset.
- `app/src/main/java/io/samvyo/demo/ui/VideoCallScreen.kt` — viewer-count badge.

Build verified with `:app:compileDebugKotlin` and reinstalled via `installDebug`.

## Not part of this pass

The chat/emoji wire-protocol mismatch (Android's `sendCustomMessage` uses `type`/
`messageType` inverted relative to what the RN reference and other peers expect,
so cross-client chat/emoji don't show up) is a separate, still-pending fix — unrelated
to this SDK sync, tracked separately.

---

# Demo App Changes — AI Agent + Resilience-Event Parity (2026-08-24)

Context: `samvyo-demo-flutter-app` audited its `mobile/`/`desktop/` UIs against recent
`samvyo-flutter-sdk` changes (see that repo's `SDK_PARITY_INTEGRATION_PLAN.md`) and
implemented an AI Agent consent UI plus resilience-event logging as a result. Ran the
same audit directly against `samvyo-android-sdk` (grepped `VideoRoom.kt`/`RoomEvent.kt`,
not assumed) to see what applies here.

## Already covered — confirmed, no action needed

`RoomViewModel.handleEvent()` already logged all of these before this pass, found while
checking, not new: `RoomEvent.TerminalClose`, `SessionExpired`, `JoinRetryScheduled`,
`JoinFailed`, `RoomModeChanging`/`RoomModeChanged`, `CodecProduced`, `ConsumerPreempted`.
Android was already ahead of Flutter here — Flutter only wired its equivalents in this
same audit pass on their side.

## New `RoomEvent` cases added

| Event | Why it matters | Fix (`RoomViewModel.kt`) |
|---|---|---|
| `RoomEvent.ConsumerAdmissionDenied` | SDK already emits this (server refused to let us consume a peer's track); was falling into the `else` catch-all, so a denial was invisible during testing. | Log-only: `appendLog("⇠ consumer admission denied: ${event.data}")`. |
| `RoomEvent.ParticipantTypeChanged` | Authoritative, server-pushed role change (fires on a 4410/4411 close code, before reconnect finishes) — earlier and more reliable than the app's own local `_localPeerType` bookkeeping. | Log-only for now: `appendLog("⇠ role changed: ${event.participantType} (${event.reason})")`. Not urgent to replace the existing local tracking — SDK's own doc comment says the same about Flutter's local moderator flag. |

## AI Agent — implemented

Zero wiring existed in this app before this pass (`grep -rl "AiAgent\|AiConsent"
app/src/main/java/` returned nothing). Built against the real Android SDK surface,
confirmed directly in `samvyo-android-sdk` by reading `VideoRoom.kt`/`RoomEvent.kt`,
not assumed or ported blindly from Flutter's shape:

- Methods (`VideoRoom.kt`): `startAiAgent(targets, profile)`, `stopAiAgent()`,
  `requestAiSelectableStreams()`, `respondToAiConsent(granted)`,
  `grantAiConsent(mediaTags)`, `revokeAiConsent(mediaTags)`, plus synchronous seed
  getters `getPendingAiConsent()`, `getAiMyConsent()`, `getAiObservations()`.
- Events (`RoomEvent.kt`): `AiConsentRequest`, `AiConsentExpired`, `AiConsentNotApplied`,
  `AiMyConsent`, `AiSelectableStreams`, `AiAnalysis`, `AiAgentStarted`, `AiAgentError`,
  `AiError`, `AiEnded`, `AiState`.

**Two real differences from Flutter's design, found by reading the actual Android SDK
signatures rather than porting Flutter's shape 1:1** — worth knowing if this is ever
compared side-by-side with the Flutter/RN version:

1. `startAiAgent(targets: List<String>?)` takes **peer IDs only**, not per-mediaTag
   targets (Flutter's SDK has a richer `AiTargetSpec` per mediaTag; Android's does not).
   So the moderator picker here ticks by *participant*, not by individual stream —
   `toggleAiPeerTick(peerId)` groups the picker's per-stream rows by peer for display,
   but the tick itself is peer-level, matching what the SDK call can actually express.
2. `respondToAiConsent(granted: Boolean)` is **all-or-nothing** for every stream in the
   pending request — the SDK sends the same `granted` value for each `mediaTag` in one
   call. There's no partial "allow camera, deny mic" at the initial-prompt stage (that
   only becomes available afterward, per-stream, via `grantAiConsent()`/
   `revokeAiConsent()` in the self-consent panel). The consent dialog here is a plain
   Allow/Decline, not a per-stream checkbox list.

Four independent UI pieces added as a new "AI Assistant" section inside the existing
single-scroll `CallSections(vm)` in `ui/VideoCallScreen.kt` — **not** a separate screen,
per this app's established one-continuous-scroll architecture:

1. **Stream picker** (moderator-only) — `RoomViewModel.applyAiSelection()` sends the
   picker's full current tick-state every time, never just what changed (`startAiAgent()`
   is a complete re-declaration, so a stale/partial tick-state would silently revoke
   already-selected peers). Empty selection → `stopAiAgent()`, not `startAiAgent(emptyList())`
   (server refuses an explicit empty list).
2. **Consent prompt** (whoever is asked; the `AlertDialog` is mounted at the top level in
   the Modals section, not inside the moderator panel, since a request can target any
   participant). Driven entirely by `pendingAiConsent` state, which is kept in lockstep
   with `VideoRoom.getPendingAiConsent()` — on `AiConsentExpired`, the ViewModel re-reads
   that getter rather than computing expiry itself, since the SDK already narrows/clears
   its own copy internally. Matches the real bug Flutter's own doc names: a fire-and-forget
   dialog with a local timer that never actually closed.
3. **My streams / self-consent** (everyone) — `canGrant`/`canRevoke` read straight off
   the server's `AiMyConsentStream` fields, never inferred locally.
4. **Observations panel** (everyone) — seeded from `getAiObservations()` on `JoinSuccess`,
   appended on `RoomEvent.AiAnalysis`, capped at 50 entries.

**Not implemented, deliberately**: rendering `RoomEvent.AiTranscript` — same reasoning as
Flutter's own doc: relaying transcripts is a separate, per-deployment privacy decision
this demo app doesn't make on its own. `RoomEvent.AiConsentState` (moderator-only live
attached/blocked sync) also isn't handled — falls to `else`; the picker already refreshes
via `AiSelectableStreams` and `AiMyConsent`, so this wasn't needed for a working demo.

## Former known gap — now fixed in `samvyo-android-sdk` (2026-08-24, `bug-fix/parity` branch, uncommitted)

Flutter's SDK has `client.room.muteAllParticipants()` (one call, force-mutes every
peer). `samvyo-android-sdk`'s `VideoRoom.kt` had no equivalent — only per-peer
`setMicEnabled()`/`disableMic()`. Added `muteAllParticipants()` to `VideoRoom.kt`,
sending the same `{id: "allMicOff"}` wire message the JS SDK sends (confirmed by
reading `samvyo-js-sdk/lib/JsSdk.js` directly). The receiving side needed no changes —
Android's `switchMicOff` route handler (the broadcast the server replies with) already
existed and already excludes the initiating moderator. Wired into this demo app as a
"Mute All" button next to "Start REC" in `VideoCallScreen.kt`.

**Side note, not a gap**: this method exists in the JS SDK but not in the RN SDK
(checked `react-native-sdk/lib/RNSdk.js` directly, 0 occurrences) — so it wasn't
actually established RN/mobile-SDK parity, just a JS feature Flutter had separately
picked up. Still worth having on Android regardless, since it's a real moderator
workflow gap and a near-zero-risk addition.

## Other JS SDK parity gaps found and fixed in `samvyo-android-sdk` (2026-08-24)

Prompted by a closer look at `startAiAgent()`'s real JS SDK signature (see the AI
Agent section above) — checked whether other AI methods were narrower ports too,
by diffing every public method name in `samvyo-js-sdk/lib/JsSdk.js` against
`VideoRoom.kt`, then cross-checking each candidate against `react-native-sdk/lib/RNSdk.js`
(Android's own established methodology treats RN, not the browser SDK, as the mobile
reference — see `BUG_FIX_PLAN.md`'s Phase 7/8 in that repo).

| Gap | In JS | In RN | Fixed |
|---|---|---|---|
| `startAiAgent(targets)` only accepted bare peer IDs, not JS's `[{peerId, mediaTags}]` per-stream form | ✅ | ✅ (RN has the same object form) | ✅ Widened to `List<AiTargetSpec>` (new `VideoRoom.AiTargetSpec(peerId, mediaTags?)` class) |
| `respondToAiConsent()` only accepted a plain boolean (all-or-nothing) | ✅ (`grants` accepts bool/array/map/explicit array) | ✅ | ✅ Added a `respondToAiConsent(grantedMediaTags: Set<String>)` overload; the boolean overload now delegates to it |
| `getAiSelectableStreams()` — sync "last known" getter | ✅ | ✅ | ✅ Added — the SDK was already caching this internally, just not exposing it |
| `requestMyAiConsent()` — actively ask the server to re-push this peer's own consent rows | ✅ | ✅ | ✅ Added — sends `{id: "getAiMyConsent"}`; the reply already flows through the existing `AiMyConsent` event/cache, no new receive-side code needed |
| `setViewportWindow(hints)` — SDK-owned viewport/pagination/pin/spotlight-aware subscription policy engine (computes which tiles to subscribe to, calls `setSubscriptions` internally) | ✅ | ✅ | ❌ **Not done** — a substantial new feature (JS's implementation does roster sort + page-slice + staging), not a signature widening. Flagged, not built in this pass. |

**Checked and ruled out** (present in JS, absent from RN too, so not a real Android
gap under this repo's own methodology): `fetchParticipantsPage`, `searchParticipants`
(browser-only participant list utilities), `reportActiveMediaUse` (browser diagnostic),
`setVBDetails`/`toggleVB` (virtual background — explicitly documented as browser-only
in `samvyo-js-sdk/PARITY.md`: requires `MediaStreamTrackProcessor` + WebGL, not
available on RN-WebRTC or, by the same reasoning, Android's WebRTC stack).

**Files touched** (`samvyo-android-sdk`, branch `bug-fix/parity`, **uncommitted**):
`samvyo-sdk/src/main/java/io/samvyo/sdk/VideoRoom.kt` only — `muteAllParticipants()`,
`AiTargetSpec` + widened `startAiAgent()`, `respondToAiConsent(Set<String>)` overload,
`getAiSelectableStreams()`, `requestMyAiConsent()`. Verified with
`:samvyo-sdk:compileDebugKotlin` (BUILD SUCCESSFUL). This demo app's
`startAiAgent()` call site in `RoomViewModel.kt` was updated to match the new
`AiTargetSpec` signature; a "Mute All" button was added next to "Start REC".

## Files touched

- `app/src/main/java/io/samvyo/demo/RoomViewModel.kt` — new `StateFlow`s
  (`aiSelectableStreams`, `aiTickedPeers`, `isAiAgentActive`, `pendingAiConsent`,
  `aiConsentError`, `myAiStreams`, `aiObservations`), the AI Agent functions section,
  AI-state seeding in the `JoinSuccess` handler, new event cases (11 `Ai*` events +
  `ConsumerAdmissionDenied` + `ParticipantTypeChanged`), and reset in `resetCallState()`.
- `app/src/main/java/io/samvyo/demo/ui/VideoCallScreen.kt` — the "AI Assistant" section
  (stream picker, self-consent rows, observations feed) and the consent `AlertDialog`.

Build verified with `:app:compileDebugKotlin`. UI not yet exercised against a live room —
per `SDK_PARITY_INTEGRATION_PLAN.md`'s own note on the Flutter side, AI Agent is gated
behind a per-org `aiEnabled` flag in `samvyo-app-autoscaler` (off by default) — confirm
that flag before assuming a "nothing happened" live test means the code is wrong.

## Not part of this pass

P2P mesh calling and the surveillance/ingest room type — held for the same reasons
Flutter's audit found: P2P's exclusion from the room-type picker is a deliberate,
recent product decision (not a stale oversight), and surveillance rooms are
admin-provisioned/moderator-less/one-way-ingest, which doesn't fit this app's
two-way-calling UI at all. Neither is an Android-specific gap — this matches what was
already concluded on the Flutter side.

---

# Demo App Changes — Parity Sweep Against `samvyo-demo-flutter-app` (2026-08-24)

Context: goal shifted from "port Flutter's latest commits" to a direct app-to-app
comparison — read `mobile/lib/room_screen.dart` (2457 lines) section by section
against this app's `RoomViewModel.kt`/`VideoCallScreen.kt`/`MainActivity.kt`, and
checked Flutter's git history for bug-fix commits (not just features) that might
apply here too.

## Critical bug found and fixed — chat/reactions from other peers were silently dropped

While checking how `senderPeerId` gets populated on an incoming `customMessage`
(chat, emoji reactions), found that `samvyo-android-sdk`'s `VideoRoom.onCustomMessage()`
read `json.optString("peerId")` — a key **the server never sends** for this message.
Confirmed the real key by reading the Flutter SDK's own fix
(`samvyo-flutter-sdk/lib/src/room/samvyo_room.dart`'s `_handleCustomMessage()`), which
documents this precisely: *"The server sends this field as `from`, not `peerId`...
confirmed via a live capture on the iOS sibling SDK... RN never hit this because it
forwards the raw message untouched and its app reads `message.from` directly."*

**Effect**: `event.senderPeerId` was **always null**, for every incoming chat message
and emoji reaction, from anyone — including your own echoed messages. This app's
`CustomMessage` handler does `val senderPeerId = event.senderPeerId ?: return` in both
the chat and emoji branches, so every one of those messages was silently dropped before
reaching the UI. This is very likely the actual root cause of the "other peer's emoji
isn't showing" symptom investigated earlier — the JSON-shape fix from that session
(string vs. object `data`) was necessary but not sufficient; this was the real blocker
underneath it, and would have kept blocking chat too, not just reactions.

**Fixed both sides**:
- `samvyo-android-sdk`'s `VideoRoom.kt`: `onCustomMessage()` now reads `"from"` instead
  of `"peerId"`.
- This app's `RoomViewModel.kt`: since `senderPeerId` now correctly resolves —
  *including* for the server's echo of our own messages back to us — added the
  self-echo guard Flutter already has (`if (event.senderPeerId == r.peerId) return`,
  at the top of the `CustomMessage` branch). Without it, every message/reaction we send
  would now double-count: once from the optimistic local echo `sendChat()`/
  `sendReaction()` already add, once again from the now-unfiltered server echo — the
  exact bug Flutter's own commit history describes hitting and fixing.

Both repos verified with `compileDebugKotlin`. Not yet re-tested live against another
peer — worth a two-device chat/emoji test to confirm this is the actual fix.

## Second wrong-JSON-key bug, same class, lower severity — token-server error messages dropped

While checking Flutter's `session_server_api.dart` against this app's
`fetchSessionTokenFromServer()` for endpoint/shape parity (both already matched:
`POST /api/create-session-token`, `{roomId}` in, `{success, sessionToken}` out) —
noticed the two disagree on the *error* field name. Read `react-native-demo-app-server`'s
`server.js` directly: every failure response uses `message`, never `error`. This app's
error branch read `json.optString("error")` — always empty — so every real failure
reason ("roomId is required", "Invalid or expired token", etc.) was silently replaced
with a generic "Token server returned `<code>`" fallback. Fixed to read `"message"`.

## Small feature gap closed — diagnostic log now has a Copy button

Flutter's demo app got a "Copy" button next to its diagnostic log earlier this session
(`_copyDiagnosticLog()` in `room_screen.dart`) so its content could be extracted for
debugging without screenshots. This app's "Socket log" panel (`MainActivity.kt`) had
no equivalent. Added a "Copy" `TextButton` next to the "Socket log" heading, using
`LocalClipboardManager` + a `Toast` confirmation (Flutter uses a `SnackBar` — Compose
equivalent here is a plain `Toast` since this screen has no `Scaffold`/`SnackbarHost`
wired up, and adding one solely for this felt like scope creep for a one-line utility).

## Checked and ruled out — no action needed

- **Double-dispose race on peer video renderer.** Flutter's `_PeerTile.disposeRenderer()`
  could be called twice for the same peer (`PeerLeftEvent` + `TrackEndedEvent` firing on
  independent streams), crashing with a native texture error — fixed by clearing the
  reference before the async `dispose()`. Checked this app's `VideoRenderer.kt`: its
  `SurfaceViewRenderer` lifecycle is owned entirely by Compose's `DisposableEffect`
  (`onDispose { renderer.release() }`), which the framework guarantees fires exactly
  once per composable instance — there's no code path here that can call `release()`
  twice the way Flutter's two independent event handlers could. Structurally not
  exposed to this bug.
- **Screen-share self-preview recursion** ("hall of mirrors" — Flutter's desktop app was
  attaching a moderator's own shared screen back into their own preview panel). Checked
  this app's `RoomEvent.SsVideoStart` handler: already gated on `event.type == "remote"`
  before attaching a track, so a local share was never eligible to render into
  `_remoteScreenTracks` in the first place. Already correct.
- **P2P exposed in the room-type picker.** Flutter deliberately excludes P2P from its
  picker — confirmed via that repo's own code comment, a recent product decision, not
  an oversight, made because Flutter's P2P UI wiring is entirely unbuilt (`SDK_PARITY_
  INTEGRATION_PLAN.md` §1: "zero code written"). This app's picker *does* offer P2P —
  not a mismatch to fix, since unlike Flutter, this app's P2P support is actually
  complete and tested (`BUG_FIX_PLAN.md` Phases 4-5 in `samvyo-android-sdk`, both DONE):
  full `RoomMode`/migration event handling already exists in `RoomViewModel.kt`. Hiding
  a finished, tested feature to match an app that hasn't built it yet would be a
  regression, not parity.

## Not part of this pass

`setViewportWindow` (see the SDK parity section above) — explicitly deferred by the
user to a later pass.

---

# Exhaustive Line-by-Line Diff Against `samvyo-demo-flutter-app` (2026-08-24)

Context: the previous pass was targeted (recent commits + section markers). This one
reads Flutter's `mobile/lib/room_screen.dart` (2457 lines) start to finish and checks
every `sendCustomMessage`/wire-shape detail against Android's actual code — the class
of bug most likely to be silent (compiles, doesn't crash, just doesn't work
cross-platform). Cross-checked the two biggest finds against `basic-native-demo-app/
App.js` directly (not just Flutter) to confirm which side was actually wrong.

## Critical bug found and fixed — chat `type`/`messageType` were inverted

`RoomViewModel.sendChat()` sent `type = <public|private>`, `messageType = "chat"`.
Read `basic-native-demo-app/App.js`'s `sendChatMessage()` AND `samvyo-demo-flutter-app`'s
`_sendChatMessage()` directly: both send `type: 'chat'`, `messageType: <public|private>`
— the exact inverse. Confirmed on the receive side too: RN's `handleCustomMessageEvent`
checks `message.type === 'chat'`; this app's receive branch checked
`event.messageType == "chat"`.

**Effect**: this app's own chat messages were never recognized as chat by RN or
Flutter's receivers (`type` arrived as "public"/"private", not "chat"), and this app
could never recognize an RN/Flutter peer's chat message either (their `messageType`
arrives as "public"/"private", not "chat", so `event.messageType == "chat"` never
matched). Chat was silently broken in both directions, cross-platform — and this was
already flagged as a known, unfixed issue in this file's very first (2026-08-08) entry:
*"a separate, still-pending fix — unrelated to this SDK sync, tracked separately."*
That's now actually fixed.

**Fixed** (`RoomViewModel.kt`): `sendChat()` now sends `type = "chat"`,
`messageType = scope`; the receive branch now checks `event.type == "chat"` and reads
`event.messageType` for the public/private label.

## Second critical bug — presenter/participant permission field names were entirely wrong

`samvyo-android-sdk`'s `VideoRoom.handlePresenterSettings()` sent
`{allowMic, allowCamera, allowScreenShare}`; `handleParticipantSettings()` sent
`{allowChat, allowHandRaise}`. Read `App.js`'s actual `presenterPermissions`/
`participantPermissionsState` default objects directly: the real fields are
`allowPresenterRaiseHand`/`allowPresenterPublicChat`/`allowPresenterPrivateChat` and
`allowParticipantRaiseHand`/`allowParticipantPublicChat`/`allowParticipantPrivateChat`
— confirmed identical in Flutter's own `_presenterPermissions`/`_participantPermissions`
maps. Both SDK methods' doc comments claimed *"Mirrors RN..."* but used field names RN
never sends.

**Effect**: every "Save Presenter"/"Save Participants" tap sent a `roomSetting:*`
custom message whose keys mean nothing to any real receiver (RN, Flutter, or this app's
own future echo-listening code) — the button appeared to work (no error, UI updated
locally) but had **zero effect server-side**, silently.

**Fixed**: `samvyo-android-sdk`'s `handlePresenterSettings()`/`handleParticipantSettings()`
renamed to the correct fields; this app's `applyPresenterSettings()`/
`applyParticipantSettings()` and the "Presenter Permissions"/"Participant Permissions"
toggle rows in `VideoCallScreen.kt` updated to match (3 toggles each — raise hand,
public chat, private chat — replacing the old mic/camera/screen-share and chat/hand-raise
sets, which never corresponded to anything real).

## Small completion — AI consent dialog is now per-stream, not all-or-nothing

Earlier in this session, `respondToAiConsent(Set<String>)` was added to the SDK for
partial per-stream grants, but the demo app's consent `AlertDialog` was never updated
to use it — still a plain Allow/Decline. Flutter's own `_buildAiConsentOverlay()` has a
checkbox per stream (default unchecked), sending only the ticked tags. Updated
`VideoCallScreen.kt`'s consent dialog to match: one `Checkbox` per requested stream,
"Allow selected" sends the ticked set, "Decline" still sends `false` (deny all).

## Bigger gap found, NOT implemented — `roomSetting:*` echo listening

Flutter's `_handleCustomMessageEvent` also listens for 4 custom-message payload types
this app doesn't: `roomSetting:stageSettings`, `roomSetting:generalSettings`,
`roomSetting:presenterSettings`, `roomSetting:participantSettings` — these are the
server's echo of a moderator's settings change, and Flutter uses them to keep every
peer's local UI in sync with the room's actual current settings (stage membership,
permission toggles), not just the moderator who sent the change. This app's own code
already flagged this as a known gap (`RoomViewModel.kt`'s "Room settings" section
comment: *"RN's version listens for a roomSetting:* custom-message echo... isn't wired
on Android"*) — confirmed still true, and now confirmed present on Flutter too, not
just RN.

**Concrete symptom this causes**: a participant moved onto/off the stage by a
moderator (`setStagePeers`/`removeStagePeers`) never sees their own `_isOnStage` flag
update — only `goOnStage()`/`backStage()` (self-service) touch that state. Similarly,
the permission toggles in "General/Presenter/Participant Permissions" are pure local
"about to send" form state, never reflecting the room's actual current settings, and
non-moderator peers have no UI at all showing current permissions.

**Why not fixed in this pass**: unlike the bugs above (wrong key/wrong field, same
shape otherwise), this is new functionality — 4 payload parsers, new synced state
(replacing local-only toggle state), and UI for non-moderators to see current settings
they can't change. Flagging for a scoped follow-up rather than folding into this pass.

## Checked, confirmed correct, no action — screen-share locality gating

Flutter's own **mobile** `TrackStartedEvent(screenVideo)` handler calls
`_attachScreenShareTrack()` unconditionally (both local and remote), only gating the
`_isScreenSharing` flag on locality — meaning a moderator's own shared screen renders
into their own "Screen Shares" grid (the same "hall of mirrors" bug the *desktop* app
fixed in commit `ae4dc47`, whose message claims mobile already had this fixed — it
doesn't, as read directly). This is a live bug in Flutter's own mobile app, not
Android's — Android's `RoomEvent.SsVideoStart` handler already gates on
`event.type == "remote"` before attaching. Not fixed here since it isn't this repo's
bug to fix; noted in case it comes up.

## Files touched this pass

- `samvyo-android-sdk`'s `VideoRoom.kt`: `onCustomMessage()` (`from` not `peerId`,
  from the earlier finding this session), `handlePresenterSettings()`/
  `handleParticipantSettings()` (correct field names).
- This app's `RoomViewModel.kt`: `sendChat()` + chat receive branch (type/messageType
  swap), self-echo guard, token-error key fix, `applyPresenterSettings()`/
  `applyParticipantSettings()` signatures, `respondAiConsent(Set<String>)` overload.
- This app's `VideoCallScreen.kt`: presenter/participant toggle rows, per-stream AI
  consent dialog, "Copy" button on the socket log.
- This app's `MainActivity.kt`: "Copy" button + `LocalClipboardManager`/`Toast` wiring.

All verified with `compileDebugKotlin` on both repos. **Nothing in this pass has been
live-tested against another peer yet** — the chat type/messageType fix and the
presenter/participant settings fix are both exactly the kind of bug that only shows up
in a real cross-platform call, not in a compile check.

---

# UI Parity Sweep Against `basic-native-demo-app` (2026-08-24)

Context: read `App.js` (2986 lines) start to finish — the same exhaustive method as
the Flutter pass above, but against the original RN reference instead. Re-confirmed
every wire-shape already fixed above (chat, presenter/participant settings — RN's
`App.js` was in fact the primary source used to fix those, read directly, not
inferred from Flutter alone) and found one more real issue.

## Real bug found and fixed — stage-settings messageType collision

`VideoRoom.handleRoomSettingsStage(maxOnStage, stageMode)` sent
`messageType: "roomSetting:stageSettings"` — the exact label RN's own
`applyStageSettingsChanges()`/`toggleSelfStagePosition()` use for a completely
different, membership-shaped payload: `{stageStatus, stagePeers, backStageStatus,
backStagePeers}` (confirmed directly from `App.js`). Android's method sends
`{maxOnStage, stageMode}` instead — capacity/mode config, not membership; Android
handles membership separately via `stagePeers()`/`unstagePeers()`, a different
underlying mechanism (already noted in this app's own `RoomViewModel.kt` comments).

**Why this mattered even though nothing currently listens for it**: this app doesn't
yet implement `roomSetting:*` echo listening (see the "bigger gap, NOT implemented"
entry above) — but the moment that follow-up gets built, an Android moderator calling
`updateStageSettings()` would broadcast a `roomSetting:stageSettings` message that any
RN/Flutter peer parsing it would read `payload.stagePeers` as `undefined` on, and
silently reset their displayed stage membership to empty — a real regression waiting
to be triggered by unrelated future work, not by anything wrong today.

**Fixed**: renamed to `roomSetting:stageConfig` — the label is purely a client-side
dispatch convention (the server doesn't act on `maxOnStage`/`stageMode` itself, it
only relays the broadcast), so this was a safe, isolated rename with no other
references anywhere in the SDK.

**Superseded 27 Nov 2026 (Q-13, Saurav #209 / board #243):** the Android SDK now uses the one
cross-SDK label. `handleRoomSettingsStage(stageStatus, stagePeers, backStageStatus,
backStagePeers, maxOnStage, stageMode)` (all optional, named) sends `roomSetting:stageSettings`
like JS/RN/Flutter; `roomSetting:stageConfig` is gone. Because receivers treat a missing
`stageStatus`/`stagePeers` as off/empty, this demo no longer calls it: the "Save Stage Capacity"
button (and `updateStageSettings()`) was removed. It only ever sent `stageConfig`, which no
client or server read, so nothing visible changes for other participants.

## Confirmed correct, not a gap — P2P in the room-type picker

RN's own room-type picker (`App.js` line 1787) offers
`['conferencing', 'event', 'p2p']` — P2P has always been a real RN option. This
directly reinforces the earlier conclusion (reached from the Flutter side, where P2P
is deliberately hidden pending its own unbuilt UI): this app offering P2P in its
picker is correct parity with the *original* reference, not a deviation.

## Two small, optional gaps found — not fixed, flagged for a decision

1. **"Start Processing" video-processing test feature.** RN has a button that calls
   `startProcessing({inputFiles})` against two hardcoded external test video URLs, plus
   `processingStarted`/`processingCompleted`/`processingError` event handling and a
   `checkProcessingStatus()`/`getAllProcessingStatuses()` pairing. Confirmed
   `samvyo-android-sdk` already has all three methods (`startProcessing`,
   `checkProcessingStatus`, `getAllProcessingStatuses` — found in the earlier JS↔Android
   method diff) — this app's demo UI just never wires them up. Low priority: it
   exercises an unrelated video-processing pipeline against hardcoded demo assets, not
   anything about the live call itself.
2. **`produceAudio`/`produceVideo` not threaded through at join time.** RN lets a user
   toggle mute/camera-off *before* joining and joins already in that state
   (`produceAudio: canProduceMedia && !isMuted`, `produceVideo: canProduceMedia &&
   !isCameraOff`) — confirmed `samvyo-android-sdk`'s `joinRoom()` already accepts both
   params, but this app's `RoomViewModel.joinRoom()` wrapper never passes them, so a
   join is always full-audio/full-video regardless of any pre-join toggle. Minor UX gap,
   not a bug — nothing is broken, just a capability RN's app offers that this one
   doesn't yet expose.

## Files touched this pass

- `samvyo-android-sdk`'s `VideoRoom.kt`: `handleRoomSettingsStage()` messageType rename
  (`roomSetting:stageSettings` → `roomSetting:stageConfig`).

Verified with `:samvyo-sdk:compileDebugKotlin`. No demo-app changes needed for this
specific fix (nothing in this app currently listens for either label).

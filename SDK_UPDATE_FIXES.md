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

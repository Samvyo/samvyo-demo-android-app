# samvyo-demo-android-app

A reference Android app for the Samvyo video SDK (`samvyo-android-sdk`), built with
Kotlin and Jetpack Compose. It exercises the SDK's full public surface — SFU
conferencing/event rooms, P2P calls with auto P2P→SFU migration, chat, moderation,
recording, live streaming, transcription — in one continuously-scrolling screen, so
every feature's usage is visible in a small number of files rather than spread
across a deep screen hierarchy.

If you're building your own app against `samvyo-android-sdk`, this repo is meant to
be read alongside it: `RoomViewModel.kt` shows the call-lifecycle and event-handling
patterns for every SDK feature, and `MainActivity.kt` / `ui/VideoCallScreen.kt` show
one way to wire that into a UI. None of it is required structure — copy the parts
relevant to what you're building, not the whole architecture.

## Setup

See **[SETUP.md](SETUP.md)** for the full walkthrough: cloning both repos, wiring
this app to the SDK source, credentials, and getting a live call running on two
devices. Start there — this file is just an orientation.

## What this demonstrates, and where

| Feature | Where to look |
|---|---|
| Room lifecycle (init → join → leave/close), all SDK event handling | `app/src/main/java/io/samvyo/demo/RoomViewModel.kt` |
| Join screen (room type, role selection, device picker) | `app/src/main/java/io/samvyo/demo/MainActivity.kt` |
| In-call UI: media controls, chat, stage routing, moderator permission panels, recording/live-streaming/transcription | `app/src/main/java/io/samvyo/demo/ui/VideoCallScreen.kt` |
| Rendering a WebRTC video track in Compose | `app/src/main/java/io/samvyo/demo/ui/VideoRenderer.kt` |
| App-level models (chat messages, mute state, hand-raise, device list, etc.) | `app/src/main/java/io/samvyo/demo/DemoModels.kt` |

Room type support includes Conferencing, Event, and P2P (with automatic upgrade to
a group SFU call once enough peers join a P2P room). Role support includes
Moderator, Participant/Presenter, and Viewer/Attendee, with the UI adapting per
room type and role.

## Status

This app tracks `samvyo-android-sdk`'s `bug-fix/parity` branch and is used to
exercise that branch's changes end-to-end on real devices, alongside the SDK's own
unit tests. It is not a polished consumer app — the "Socket log" panel visible on
screen is a deliberate debugging aid, not a bug.

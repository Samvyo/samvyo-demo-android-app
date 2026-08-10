# Samvyo Demo App — Setup Guide

This walks through cloning both repos, wiring the demo app to the SDK source, and
getting a live video call running on two devices. Follow it top to bottom the first
time; skip to [Troubleshooting](#troubleshooting) if something specific breaks later.

## What you're setting up

Two repos work together:

- **`samvyo-android-sdk`** — the SDK itself (`io.samvyo.sdk.*`), the mediasoup/WebRTC
  client logic.
- **`samvyo-demo-android-app`** — this app. It doesn't depend on a published SDK
  version; Gradle compiles the SDK **from source** via a local path reference. That
  means both repos must be cloned on your machine, and the demo app's
  `settings.gradle.kts` must point at wherever you put the SDK repo.

## 1. Prerequisites

- **Android Studio**, a recent stable release. This project uses AGP 9.2.1 —
  if Studio complains it can't open the project or doesn't recognize the AGP
  version, update Studio first.
- **JDK 17** (Android Studio bundles one; you don't need to install a separate JDK
  unless Studio tells you otherwise).
- **Android SDK Platform 36** and matching Build Tools, installed via Android
  Studio's SDK Manager (`Tools → SDK Manager`) if not already present. `minSdk` is
  24 (Android 7.0+), so any real device or emulator from Nougat onward can run the
  built app, but *building* it requires Platform 36 on your machine.
- **Gradle itself does not need to be installed** — the repo ships a wrapper
  (`gradlew`) that downloads the exact Gradle version it needs (9.4.1) on first run.
- **Two Android devices or emulators** to actually test a call — one peer talking to
  itself isn't a very convincing demo. Physical devices are strongly preferred:
  camera/mic access via emulators is unreliable, and two emulator instances running
  WebRTC simultaneously is heavier than most dev machines handle well.
- **GitHub access to the Samvyo org** — both repos are private; make sure you've
  been added before cloning, or you'll get a 404 instead of a permission error.
- **Samvyo API credentials** (`ACCESS_KEY` / `SECRET_ACCESS_KEY`) — get these from
  your team lead or the Samvyo dashboard. Don't request these over an insecure
  channel; treat them like any other secret.
- No NDK, no CMake, no native toolchain setup — `mediasoup-client` is pulled as a
  prebuilt AAR from Maven Central, not compiled from source. If Gradle ever asks
  you to install an NDK, something else is wrong (see Troubleshooting).

## 2. Clone both repos

Clone them as **siblings**, in the same parent directory — this makes step 3's path
edit a short relative jump instead of a full absolute path:

```bash
mkdir -p ~/samvyo && cd ~/samvyo
git clone git@github.com:Samvyo/samvyo-android-sdk.git
git clone git@github.com:Samvyo/samvyo-demo-android-app.git
```

(No SSH key set up on GitHub yet? Use the HTTPS form instead —
`https://github.com/Samvyo/samvyo-android-sdk.git` and
`https://github.com/Samvyo/samvyo-demo-android-app.git` — it'll prompt for
credentials or a personal access token on push/pull.)

You should now have:

```
~/samvyo/
  samvyo-android-sdk/
    samvyo-sdk/          <- the actual Gradle module the demo app needs
    ...
  samvyo-demo-android-app/
    samvyo-demo-android-app/   <- yes, the Gradle project is nested one level in
      app/
      settings.gradle.kts
      ...
```

## 3. Point the demo app at your SDK checkout

Open `samvyo-demo-android-app/samvyo-demo-android-app/settings.gradle.kts`. Near the
bottom you'll find:

```kotlin
include(":samvyo-sdk")
project(":samvyo-sdk").projectDir = File("/home/saurav/Android-app/samvyo-android-sdk/samvyo-sdk")
```

**That absolute path is machine-specific — it won't exist on your machine.** Change
it to wherever you actually cloned `samvyo-android-sdk`'s `samvyo-sdk` module. If you
followed the sibling-directory layout above:

```kotlin
project(":samvyo-sdk").projectDir = File("../../samvyo-android-sdk/samvyo-sdk")
```

(Relative paths are resolved from the demo app's Gradle project root, i.e. from
`samvyo-demo-android-app/samvyo-demo-android-app/` — hence the `../../`.) An absolute
path works too if you'd rather not think about relative-path counting; either is
fine, it just needs to resolve to the right `samvyo-sdk` folder on disk.

This file is committed to git as-is, so **don't commit your edit** — `git status`
will show it modified; either `git stash` it before pulling/pushing anything else,
or just remember not to `git add` it.

You only need the SDK repo's source on disk for this — you don't need to open it as
its own project, run its own build, or touch its own `local.properties`. The demo
app's build absorbs `samvyo-sdk` as a module of *its own* build the moment step 3's
path is correct.

## 4. Add your API credentials

Create (or edit) `samvyo-demo-android-app/samvyo-demo-android-app/local.properties`
— this file is gitignored, so it's the right place for secrets:

```properties
sdk.dir=/path/to/your/Android/Sdk
ACCESS_KEY=your-access-key-here
SECRET_ACCESS_KEY=your-secret-access-key-here
```

`sdk.dir` is normally auto-generated by Android Studio the first time you open the
project — if it's already there, leave it alone and just add the two key lines
below it. The keys get baked into the app as `BuildConfig.ACCESS_KEY` /
`BuildConfig.SECRET_ACCESS_KEY` at build time (see `app/build.gradle.kts`) and are
used to fetch a session token from `https://test-api-v2.samvyo.com/` when you tap
"Init Room" — without valid keys, that call fails immediately and you'll see the
error surfaced on screen and in the on-screen socket log.

## 5. Open and sync

Open `samvyo-demo-android-app/samvyo-demo-android-app/` (the **inner** folder — the
one containing `settings.gradle.kts`) as a project in Android Studio. Let Gradle
sync. If step 3's path is wrong, sync will fail with something like *"Project
directory does not exist"* — that's your signal to go back and fix the path.

## 6. Build and install

First, connect your device: enable **Developer options → USB debugging** on the
phone, plug it in, accept the "Allow USB debugging?" prompt, and confirm it shows up:

```bash
adb devices
```

You should see it listed as `device` (not `unauthorized` — if it says that, check
the phone screen for the debugging prompt).

Then either use Android Studio's Run button with the device selected, or from the
command line:

```bash
cd samvyo-demo-android-app/samvyo-demo-android-app
./gradlew :app:installDebug
```

(If `./gradlew` complains about permissions, `chmod +x gradlew` and retry — this is
occasionally lost on some archive downloads, though not on a normal `git clone`.)

This builds the app *and* the SDK module from source in one pass (that's the point
of the local project reference) and installs the debug APK on whatever device/
emulator `adb devices` showed above.

## 7. Run an actual call

You need **two devices**, both on the app, both joining the **same Room ID**. The
signalling server is cloud-hosted (`test-api-v2.samvyo.com`), so the two devices
don't need to be on the same Wi-Fi/LAN — cellular data on one and Wi-Fi on the other
works fine, as long as both have internet access.

On each device:

1. Launch the app — you land on one continuous screen (join controls, and once
   connected, all call controls, live in the same scroll — there's no separate
   "call screen" to navigate to).
2. Enter a **Room ID** (anything — just make sure both devices use the exact same
   string) and a display name.
3. Pick **Room type**: Conferencing or Event.
4. Pick a role under **Join as**. For a normal two-person test, make one device
   **Moderator** and the other **Participant** (Moderator unlocks recording,
   permissions panels, live streaming, and other admin-only sections further down
   the screen — useful for seeing the full feature set on at least one device).
5. Optionally tap **Select Camera** if the device has more than two cameras.
6. Tap **Init Room** — this fetches a session token and opens the WebSocket. Watch
   the "Socket log" at the bottom of the screen if anything seems stuck; it logs
   every step (token fetch, socket connect, join, errors) in order.
7. Once state shows **Ready**, tap **Join Room**. Grant the camera/microphone
   permission prompt (and notifications, on Android 13+ — needed for the
   screen-share foreground-service notification, not for the call itself).
8. Repeat on the second device with the same Room ID. Both should see each other's
   video within a few seconds of the second device joining.

From there: mic/camera toggles, screen share, chat, emoji reactions, hand raise, and
(on the Moderator device) recording, live streaming, transcription, and the
permissions panels are all further down the same scrolling screen.

## Troubleshooting

**Gradle sync fails with "Project directory does not exist" or similar**
→ The `samvyo-sdk` path in `settings.gradle.kts` (step 3) is wrong. Double-check it
points at the `samvyo-sdk` subfolder *inside* the SDK repo, not the SDK repo's root.

**Build fails trying to resolve `io.github.haiyangwu:mediasoup-client`**
→ This resolves from Maven Central directly — no JitPack or other repo needed. If it
fails, check your network/proxy settings rather than adding repositories; nothing
else should need adding.

**`adb devices` shows nothing, or shows `unauthorized`**
→ Nothing to do with Gradle/the SDK — it's a device/USB debugging issue. Re-check
Developer options is enabled, the USB cable supports data (not charge-only), and
accept the on-device authorization prompt if one appears.

**"Init Room" immediately errors out**
→ Almost always missing/invalid `ACCESS_KEY` / `SECRET_ACCESS_KEY` in
`local.properties` (step 4), or `test-api-v2.samvyo.com` being unreachable from your
network. Check the on-screen socket log for the exact error text.

**Two devices join the same Room ID but don't see each other**
→ Confirm the Room ID string matches *exactly* (it's case-sensitive, whitespace
counts). Also confirm both devices actually reached the **In Call** state (shown in
the "State:" line) and not just **Ready**/**Joining**.

**Camera/mic don't work / app can't produce video**
→ Check the role you joined as: **Viewer/Attendee** is intentionally view-only and
never produces media — that's correct behavior, not a bug. Use Moderator or
Participant/Presenter to test two-way video.

**Screen share does nothing**
→ Make sure you granted the screen-capture system prompt when it appeared (Android
shows this itself, not the app) and, on Android 13+, that notification permission
was granted — the screen-share foreground service needs it to run.

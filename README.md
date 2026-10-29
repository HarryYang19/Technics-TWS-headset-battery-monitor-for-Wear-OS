# HeadsetBatteryWatch AZ60 (Wear OS)

Standalone Wear OS app for Samsung Galaxy Watch5 Pro, targeted at Technics EAH-AZ60 and EAH-AZ60M2 earbuds. No phone companion or phone-to-watch battery relay is used.

## What changed

- Replaced the non-existent `BluetoothDevice.getBatteryLevel()` compile-time call.
- Tries direct Bluetooth Classic RFCOMM to the paired AZ60 using the Technics/Airoha UUID, then standard SPP UUID; Android performs SDP lookup rather than hardcoding an RFCOMM channel.
- Uses a dedicated Bluetooth stream-reader thread, end-of-stream signalling and bounded frame waits (Android BluetoothSocket does not expose `setSoTimeout()`); sends read-only Airoha RACE battery queries for right ear, left ear, and charging case, validates response frames and battery percentages, and preserves any successful component reading if the connection drops mid-session.
- If private protocol reading fails, tries Android's hidden `getBatteryLevel()` reflectively as a best-effort whole-device fallback; this may be blocked or return unknown, and is displayed separately rather than mislabeled as an individual earbud.
- The complication reads the last valid cached reading. It does not establish a socket in the watch-face callback, and the app requests an immediate complication refresh after saving a new reading. Its manifest uses the legacy `android.support.wearable.complications.*` action and metadata keys required by the current AndroidX data-source implementation.
- Includes status text to help diagnose pairing, permissions, and protocol response failures.

## Important limitations

This is an experimental protocol port. The public TechnicsPods/HyperEars references report AZ60-family support, but this APK has not yet been tested on a Galaxy Watch5 Pro in this build environment. Successful compilation does not guarantee the watch's Bluetooth stack will allow a second RFCOMM connection while the earbuds are used for audio. Keep the earbuds paired directly to the watch, grant Nearby devices/Bluetooth permission, and tap refresh. `--%` means no validated battery response was received; it is not a guessed value.

The app queries only battery state; it does not send ANC, game-mode, or other control commands.

## Build on GitHub

Static checks passed for the manifest XML, workflow syntax/content, Java source parsing, Kotlin syntax patterns, dependency/version alignment, and key RACE frame invariants. This workspace does not contain an Android SDK/Gradle installation and cannot resolve external download hosts, so this ZIP has not been fully compiled or device-tested here. The GitHub Actions run is still required for build verification.

1. Upload the contents of this folder to the root of your `HeadsetBatteryWatch` repository (including `.github/workflows/build-apk.yml`), replacing the old files. If `.github/workflows/` contains any other old workflow that still uses `android-actions/setup-android@v3`, requests the removed `tools` package, or uses obsolete Node 20 action revisions, delete or disable that old workflow; otherwise GitHub may keep running the obsolete workflow and show the same failure.
2. Open **Actions** and enable workflows if GitHub asks.
3. Select **Build Wear OS APK** and choose **Run workflow**; alternatively, committing to `main` starts a build. The app version is now `1.3-az60` (`versionCode 4`). If Android refuses to install over an older debug build because its signing certificate differs, uninstall the previous test APK from the watch first, then install the new one.
4. Open the successful run and download artifact **HeadsetBatteryWatch-AZ60-debug-apk**. Extract it to get `app-debug.apk`.

The build workflow uses Node 24-compatible current first-party action revisions (`checkout@v7`, `setup-java@v6`, `upload-artifact@v7`), `android-actions/setup-android@v4`, and `gradle/actions/setup-gradle@v5`. It explicitly installs only valid Android SDK platform/build-tools packages (not the removed `tools` package), pins the SDK root for `sdkmanager`, and uses Gradle 8.11.1 with JDK 17. The Android Gradle Plugin is pinned to 8.9.2 and Kotlin Gradle Plugin to 2.2.20, a compatible pairing for this build. The project version was raised to `1.3-az60` / `versionCode 4` for this reviewed revision. The workflow runs a clean build with a stack trace so compile errors are visible in the Actions log.


## Protocol reference

The read-only RACE request/response shapes and endpoint order were checked against the public [HyperEars Technics RACE protocol notes](https://github.com/silverpoetry/HyperEars/blob/main/docs/technics-race-protocol.md) and [compatibility notes](https://github.com/silverpoetry/HyperEars/blob/main/docs/compatibility.md). Their evidence distinguishes published AZ60-family reference support from per-model in-branch device validation; this app's success on a Galaxy Watch5 Pro still needs real-device testing.

# HeadsetBatteryWatch AZ60 (Wear OS)

Standalone Wear OS app for Samsung Galaxy Watch5 Pro, targeted at Technics EAH-AZ60 / AZ60-family earbuds. No phone companion or phone-to-watch battery relay is used.

## What changed

- Replaced the non-existent `BluetoothDevice.getBatteryLevel()` compile-time call.
- Tries direct Bluetooth Classic RFCOMM to the paired AZ60 using the Technics/Airoha UUID, then standard SPP UUID; Android performs SDP lookup rather than hardcoding an RFCOMM channel.
- Sends read-only Airoha RACE battery queries for right ear, left ear, and charging case, validates response frames and battery percentages, and displays each component separately.
- If private protocol reading fails, tries Android's hidden `getBatteryLevel()` reflectively as a best-effort fallback; this may be blocked or return unknown.
- The complication reads the last valid cached reading. It does not establish a socket in the watch-face callback.
- Includes status text to help diagnose pairing, permissions, and protocol response failures.

## Important limitations

This is an experimental protocol port. The public TechnicsPods/HyperEars references report AZ60-family support, but this APK has not yet been tested on a Galaxy Watch5 Pro in this build environment. Successful compilation does not guarantee the watch's Bluetooth stack will allow a second RFCOMM connection while the earbuds are used for audio. Keep the earbuds paired directly to the watch, grant Nearby devices/Bluetooth permission, and tap refresh. `--%` means no validated battery response was received; it is not a guessed value.

The app queries only battery state; it does not send ANC, game-mode, or other control commands.

## Build on GitHub

1. Upload the contents of this folder to the root of your `HeadsetBatteryWatch` repository (including `.github/workflows/build-apk.yml`), replacing the old files.
2. Open **Actions** and enable workflows if GitHub asks.
3. Select **Build Wear OS APK** and choose **Run workflow**; alternatively, committing to `main` starts a build.
4. Open the successful run and download artifact **HeadsetBatteryWatch-AZ60-debug-apk**. Extract it to get `app-debug.apk`.

The build workflow installs only Android SDK platform/build-tools packages (not the removed `tools` package) and uses Gradle 8.11.1 with JDK 17.

# HeadsetBatteryWatch — Technics EAH-AZ Live Tile

**Source version: 1.8 (versionCode 10).**

## What changed in v1.9 (UI-only)

- Rebuilt the Tile content as a natural-height vertical stack inside a full-size ProtoLayout `Box`, with explicit horizontal and vertical center alignment. This avoids the previous expanded `Column` placing all text at the top edge.
- Added a rounded green **环境声音控制** Tile button. It uses a ProtoLayout `Clickable` + `LaunchAction` to open the existing exported `MainActivity` only; it does not send ANC commands or alter Bluetooth behavior.
- Rebalanced the main screen for round watch displays: density-aware `dp` measurements, compact individual battery cards, larger touch targets for the three noise-mode controls, and a centered 210 × 46 dp refresh button.
- Added a custom black / silver earbud / mint battery launcher icon in density-specific mipmaps and Android adaptive/round icon resources.
- No RACE packet, Bluetooth connection, refresh lifecycle, device matching, ANC state gate, or command implementation was changed.


A standalone Wear OS app for reading Technics EAH-AZ true-wireless earbud battery levels directly from the watch. No phone companion, phone relay, or cloud service is used.

## What changed in v1.7

- Fixed a repository-upgrade build failure: older uploads may leave `HeadsetBatteryComplicationService.kt` behind because GitHub does not delete files that are absent from an uploaded ZIP. This file imports `androidx.wear.watchface.*`, but the Tile-only project intentionally does not include watch-face complication libraries. The CI preflight now deletes every legacy Java/Kotlin source whose filename contains `Complication` before Gradle starts, and verifies that none remain. This is the root cause of the reported `Unresolved reference: watchface` / `ComplicationDataSourceService` errors; it is **not** caused by `minSdk 30` or Wear OS 3 compatibility.
- No rewrite of the Bluetooth, Tile, or ANC implementation was needed for this compiler error.

## What changed in v1.6

- Removed the watch-face Complication service and all complication dependencies.
- Added a classic Wear OS **Tile** (the swipeable card in the watch's tile carousel).
- The Tile performs a fresh Bluetooth Classic RFCOMM/RACE read for every timeline request. It does **not** use cached percentages as current values. If the live read fails or times out, it displays `--%` and an error instead of an old percentage.
- Entering the visible Tile triggers a new timeline request through `onTileEnterEvent()` every time. If a read is already in progress, the second request reuses it instead of opening a duplicate RFCOMM session. Leaving the Tile cancels a still-running read. Automatic background polling is disabled (`freshnessIntervalMillis = 0`).
- The full app screen refreshes on `onResume()` and when the user taps Refresh. Old values are cleared while a new read is in progress; components missing from a partial response display `--%`.
- Minimum Android API is 30 (Wear OS 3 generation). The Tile uses classic `TileService` + ProtoLayout 1.0 (`tiles:1.2.0`) rather than `Material3TileService`, so the same implementation targets Wear OS 3 through Wear OS 6 / One UI Watch 8. `targetSdk` is deliberately held at 35: when an app targets API 36, Wear Tiles 1.5+ no longer dispatches the legacy enter/leave callbacks on API 36; that would break this live-on-enter design. We do not target 36 in this release.
- EAH-AZ model discovery is family-based rather than AZ60-only. A model name only selects a candidate; valid RACE frames are still required before displaying private component battery data or exposing ANC controls.
- The main app retains guarded Off / Noise Cancellation / Transparency controls. Controls are enabled only after a valid RACE `0x000A` mode-state response and the chosen mode is only reported after device readback.

## Freshness behavior and platform limitation

When the Tile renderer asks this service for a timeline, the service connects to the paired earbud and queries the live left/right/case values. When the Tile enters the visible carousel viewport, the service requests an update if the renderer appears to be reusing an older timeline.

Wear OS controls the Tile renderer's own caching and scheduling. During the short asynchronous read, the renderer may momentarily keep the previous rendered frame on screen before the new response arrives. An app cannot force the system UI to blank that frame synchronously. This build never constructs a new Tile from old cached percentages, and a failed live read produces an explicit failure display. The Tile request has an 8.5-second internal timeout to leave margin below the platform's 10-second response deadline. Individual RACE reads are bounded to one second per ear and 0.9 seconds for the case so the second documented RFCOMM UUID remains reachable when the first endpoint connects but does not speak RACE.

The Tile does not automatically poll while off-screen. Each time the card becomes visible, it explicitly requests a new live read. If the user remains on the Tile for a long time, its values remain the snapshot taken on entry; leave and re-enter to refresh again. Since the app targets API 35, Wear OS 6/API 36 continues to deliver the legacy enter/leave callbacks used by this Tiles 1.2 implementation. If the project later raises `targetSdk` to 36, the Tile event handling must be migrated to `onRecentInteractionEventsAsync()` before doing so. The Wear OS renderer may briefly show its previous frame while the asynchronous Bluetooth request is pending; this cannot be synchronously cleared by an app, but the app never builds a refreshed timeline using stored old percentages.

## Supported device family

The adapter selects paired names matching the Technics EAH-AZ true-wireless family, including names commonly used by models such as:

- EAH-AZ40 / AZ40M2
- EAH-AZ60 / AZ60M2 (**AZ60 is the mandatory target for this project**)
- EAH-AZ70W
- EAH-AZ80
- EAH-AZ100

This is a protocol-family candidate list, not a claim that every model and firmware revision has been tested on a Galaxy Watch. Community protocol references report the RACE implementation working on AZ60, AZ80, AZ100 and other EAH-AZ variants; the detailed public compatibility record identifies AZ80 as the model with end-to-end three-mode write/readback hardware verification. The app validates battery and noise-mode responses at runtime before enabling the corresponding feature. An EAH-A800 over-ear headset is not included in this RACE TWS adapter.

Transport attempts the Technics/Airoha vendor RFCOMM UUID and then the standard SPP UUID. Android SDP resolves the channel; no fixed RFCOMM channel is hard-coded.

### Battery protocol (read-only)

- Right ear: `05 5A 03 00 D6 0C 00`
- Left ear: `05 5A 03 00 D6 0C 01`
- Charging case: `05 5A 02 00 40 00`

The Tile adapter validates response type, RACE identifier, component role, success status, frame length, and percentage range before accepting a value. The Tile deliberately does not use Android's hidden `BluetoothDevice.getBatteryLevel()` aggregate fallback because that API may expose a cached system value rather than a newly queried headset response. The main app may show an explicitly labelled Android whole-device fallback where available; it is never presented as independent left/right readings.

### Environmental sound controls

The main app can request Off / Noise Cancellation / Transparency. The app first queries the device's current mode and does not transmit a write unless the expected valid RACE mode response is received. After the command sequence, it queries again and only marks the operation successful if the returned mode matches the requested mode. There are no Adaptive, wind-noise, or game-mode controls in this build.

## Install and first use

1. Install the debug APK from the GitHub Actions artifact.
2. Open **耳机电量** once and grant **Nearby devices / Bluetooth** permission when Wear OS asks. On Wear OS 3 (Android 11 / API 30), Bluetooth permission handling follows the older Android permission model.
3. Pair the Technics earbuds directly with the watch and connect them to the watch. A phone relay is not used.
4. Add **耳机电量** from the watch's Tile carousel editor. On Samsung One UI Watch, edit the Tiles page and add the app Tile.
5. Open the app screen or enter the Tile to trigger a live read. If no RACE response is received, the Tile reports the failure rather than showing a cached battery value.

## Important when updating an existing GitHub repository

Uploading this ZIP over an existing repository does not remove old files. That is why an earlier checkout may still contain `app/src/main/java/com/example/headsetbattery/HeadsetBatteryComplicationService.kt` even though this ZIP no longer contains it. Do not restore that file or add watch-face complication dependencies. The first preflight step automatically deletes leftover Java/Kotlin files with `Complication` in their filename before Gradle compilation, then verifies the source tree is Tile-only.

## Build with GitHub Actions

The workflow is `.github/workflows/build-apk.yml`.

- JDK 17
- Android SDK platform 35 and Build Tools 35.0.0 (the workflow installs only current package IDs; it does not request the obsolete `tools` package)
- GitHub Actions: checkout v7, setup-java v6, setup-android v4, setup-gradle v6, upload-artifact v7
- Android Gradle Plugin 8.9.2
- Kotlin Gradle Plugin 2.2.20
- Gradle 8.11.1

Run **Actions → Build Wear OS APK → Run workflow**. Download the artifact named `HeadsetBatteryWatch-Technics-Live-Tile-v1.9-debug-apk`; the APK is `app-debug.apk`.

## Validation notes

The repository includes `scripts/preflight.py`, which first removes legacy watch-face Complication Kotlin/Java files left over from older ZIP uploads, then validates the manifest and string resources, SDK floor and target-SDK guard, Tile API dependency line, no-complication requirement, visible-entry live-read path, leave-event cancellation, request timeout, Bluetooth permission declarations, GitHub Actions versions, and expected RACE packet constants before Gradle starts.

Static validation is not a substitute for compiling against the Android SDK or testing on physical watches. The source package should be considered a reviewed build candidate until the GitHub Actions Gradle build succeeds and the Tile has been tested on the actual watch. In particular, AZ60 battery and ANC behavior still needs confirmation against the user's AZ60 firmware and current Wear OS build.

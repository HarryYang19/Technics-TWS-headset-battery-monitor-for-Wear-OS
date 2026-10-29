# HeadsetBatteryWatch AZ60 (Wear OS)

Standalone Wear OS app for Samsung Galaxy Watch5 Pro, targeted at Technics EAH-AZ60 and EAH-AZ60M2 earbuds. No phone companion or phone-to-watch battery relay is used.

## What changed Compared to [HeadsetBatteryWatch](https://github.com/HarryYang19/HeadsetBatteryWatch)

- Replaced the non-existent `BluetoothDevice.getBatteryLevel()` compile-time call.
- Tries direct Bluetooth Classic RFCOMM to the paired AZ60 using the Technics/Airoha UUID, then standard SPP UUID; Android performs SDP lookup rather than hardcoding an RFCOMM channel.
- Uses a dedicated Bluetooth stream-reader thread, end-of-stream signalling and bounded frame waits (Android BluetoothSocket does not expose `setSoTimeout()`); sends read-only Airoha RACE battery queries for right ear, left ear, and charging case, validates response frames and battery percentages, and preserves any successful component reading if the connection drops mid-session.
- If private protocol reading fails, tries Android's hidden `getBatteryLevel()` reflectively as a best-effort whole-device fallback; this may be blocked or return unknown, and is displayed separately rather than mislabeled as an individual earbud.
- The complication reads the last valid cached reading. It does not establish a socket in the watch-face callback, and the app requests an immediate complication refresh after saving a new reading. Its manifest uses the legacy `android.support.wearable.complications.*` action and metadata keys required by the current AndroidX data-source implementation.
- Includes status text to help diagnose pairing, permissions, and protocol response failures.

## Important limitations

This is an experimental protocol port. The public TechnicsPods/HyperEars references report AZ60-family support, but this APK has not yet been tested on a Galaxy Watch5 Pro in this build environment. Successful compilation does not guarantee the watch's Bluetooth stack will allow a second RFCOMM connection while the earbuds are used for audio. Keep the earbuds paired directly to the watch, grant Nearby devices/Bluetooth permission, and tap refresh. `--%` means no validated battery response was received; it is not a guessed value.

The app queries only battery state; it does not send ANC, game-mode, or other control commands.


## Protocol reference

The read-only RACE request/response shapes and endpoint order were checked against the public [HyperEars Technics RACE protocol notes](https://github.com/silverpoetry/HyperEars/blob/main/docs/technics-race-protocol.md) and [compatibility notes](https://github.com/silverpoetry/HyperEars/blob/main/docs/compatibility.md). Their evidence distinguishes published AZ60-family reference support from per-model in-branch device validation; this app's success on a Galaxy Watch5 Pro still needs real-device testing.

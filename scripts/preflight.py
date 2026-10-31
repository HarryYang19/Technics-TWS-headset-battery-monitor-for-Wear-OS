#!/usr/bin/env python3
"""Fast source/configuration checks before the Android Gradle build."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app"
MANIFEST = APP / "src/main/AndroidManifest.xml"
MAIN = APP / "src/main/java/com/example/headsetbattery/MainActivity.java"
READER = APP / "src/main/java/com/example/headsetbattery/TechnicsRaceReader.java"
TILE = APP / "src/main/java/com/example/headsetbattery/BatteryTileService.kt"
GRADLE = APP / "build.gradle.kts"
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def remove_legacy_complication_sources() -> list[Path]:
    """Delete the obsolete watch-face Complication service left by older repo uploads.

    v1.6+ uses a Wear Tile only. GitHub uploads that overwrite existing files do not
    delete files absent from the ZIP, so the old Kotlin source can otherwise remain
    in app/src and be compiled against a project that intentionally has no watchface
    complications dependency.
    """
    source_root = APP / "src"
    removed: list[Path] = []
    if source_root.exists():
        for path in source_root.rglob("*"):
            if path.is_file() and path.suffix.lower() in {".kt", ".java"} and "complication" in path.name.lower():
                path.unlink()
                removed.append(path)
    return removed


def main() -> int:
    removed_legacy = remove_legacy_complication_sources()
    for old_source in removed_legacy:
        print(f"Removed obsolete watch-face source: {old_source.relative_to(ROOT)}")

    for path in (MANIFEST, MAIN, READER, TILE, GRADLE, WORKFLOW):
        require(path.is_file(), f"missing required file: {path.relative_to(ROOT)}")

    ET.parse(MANIFEST)
    gradle = GRADLE.read_text(encoding="utf-8")
    main = MAIN.read_text(encoding="utf-8")
    reader = READER.read_text(encoding="utf-8")
    tile = TILE.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")
    workflow = WORKFLOW.read_text(encoding="utf-8")

    require(re.search(r"minSdk\s*=\s*30\b", gradle) is not None, "minSdk must remain API 30 (Wear OS 3)")
    require(re.search(r"targetSdk\s*=\s*35\b", gradle) is not None, "targetSdk must stay at 35 until Tile API 36 interaction callbacks are implemented")
    require('androidx.wear.tiles:tiles:1.2.0' in gradle, "Tiles 1.2.0 compatibility dependency missing")
    require('androidx.wear.protolayout:protolayout:1.0.0' in gradle, "ProtoLayout 1.0 dependency missing")
    require('protolayout-material3' not in gradle and 'Material3TileService' not in tile,
            "new Material 3 Tile API should not be used in the Wear OS 3 compatible build")
    require("android.permission.BLUETOOTH_CONNECT" in manifest, "Android 12+ Bluetooth permission missing")
    require("android.permission.BLUETOOTH" in manifest, "Wear OS 3 Bluetooth permission missing")
    require("BIND_TILE_PROVIDER" in manifest and "BatteryTileService" in manifest,
            "Tile provider service is not registered in manifest")
    require("onTileRequest" in tile and "onTileEnterEvent" in tile,
            "Tile request/enter refresh callbacks missing")
    require("TechnicsRaceReader.readLive" in tile,
            "Tile must query Bluetooth directly for every new timeline")
    require("getBatteryLevel" not in reader and "readHiddenBattery" not in reader and "overall" not in reader
            and "result.overall" not in tile,
            "Tile reader must not use possibly stale Android aggregate battery fallback")
    require("COMPONENT_TIMEOUT_MS = 1000L" in reader and "CASE_TIMEOUT_MS = 900L" in reader,
            "Tile RACE query timeout budget must leave room for UUID fallback")
    require("getInt(\"left\"" not in tile and "getInt(\"right\"" not in tile and "getInt(\"case\"" not in tile,
            "Tile must not render cached battery percentages")
    require("setFreshnessIntervalMillis(0L)" in tile,
            "Tile must not continuously background-poll while off-screen")
    timeout_match = re.search(r"TILE_REQUEST_TIMEOUT_MS\s*=\s*([0-9_]+)L", tile)
    require(timeout_match is not None and int(timeout_match.group(1).replace("_", "")) < 10_000,
            "Tile timeout must be safely below the platform's 10-second limit")
    require("05 5A 03 00 D6 0C 00" in reader and "05 5A 03 00 D6 0C 01" in reader,
            "left/right RACE battery query frames missing")
    require("05 5A 02 00 40 00" in reader, "charging-case RACE query frame missing")
    require("EAH[- ]?AZ" in reader and "AZ60" in reader,
            "family matching / AZ60 prioritization missing")
    require("NOISE_QUERY" in main and "writeNoiseMode" in main and "confirmed.mode != desiredNoiseMode" in main,
            "ANC mode capability/readback safety gates missing")
    require("onResume()" in main and "setPercent(leftValue, -1)" in main,
            "main screen must refresh on entry and clear old percentages")
    require("screenVisible && !busy.get()" in main,
            "permission callback must not launch a duplicate RFCOMM refresh while onResume is already reading")
    require("onPause()" in main and "activeSocket.getAndSet(null)" in main,
            "main screen must close its private RFCOMM session when leaving the battery screen")
    remaining_complication_sources = [
        p for p in (APP / "src").rglob("*")
        if p.is_file() and p.suffix.lower() in {".kt", ".java"} and "complication" in p.name.lower()
    ]
    require(not remaining_complication_sources,
            "obsolete watch-face Complication source remains after cleanup")
    require("Complication" not in manifest and "HeadsetBatteryComplication" not in main + tile,
            "watch-face Complication module remains in the active app source")
    require((APP / "src/main/res/values/strings.xml").is_file(), "Tile description string resource missing")
    ET.parse(APP / "src/main/res/values/strings.xml")
    require("android-actions/setup-android@v4" in workflow, "updated Android SDK setup action missing")
    require("gradle/actions/setup-gradle@v6" in workflow, "current Gradle setup action missing")
    require("actions/checkout@v7" in workflow and "actions/setup-java@v6" in workflow and
            "actions/upload-artifact@v7" in workflow, "GitHub Actions steps are not on the refreshed major versions")
    require("platforms;android-35" in workflow and "build-tools;35.0.0" in workflow,
            "Android SDK platform/build-tools install step missing")
    require('"tools"' not in workflow and "'tools'" not in workflow,
            "obsolete Android SDK tools package must not be installed")
    require("onTileEnterEvent" in tile and "requestUpdate(BatteryTileService::class.java)" in tile,
            "visible Tile entry must force a fresh timeline request")
    require("onTileLeaveEvent" in tile and "cancelActiveRead()" in tile,
            "Tile Bluetooth read must be cancelled when the user leaves the card")
    require('android:description="@string/tile_description"' in manifest,
            "Tile service description must reference a string resource")
    require('android:icon="@mipmap/ic_launcher"' in manifest and
            'android:roundIcon="@mipmap/ic_launcher_round"' in manifest,
            "launcher icon and round icon must be registered in the application manifest")
    require((APP / "src/main/res/mipmap-anydpi-v26/ic_launcher.xml").is_file() and
            (APP / "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").is_file() and
            (APP / "src/main/res/drawable-nodpi/ic_launcher_foreground.png").is_file(),
            "adaptive launcher icon resources are incomplete")
    require("setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)" in tile and
            "setHeight(DimensionBuilders.wrap())" in tile,
            "Tile content must be vertically centered using a wrap-height content column")
    require("环境声音控制" in tile and "ActionBuilders.launchAction(ComponentName(this, MainActivity::class.java))" in tile,
            "Tile environment-sound button must launch the main Activity")
    require("dp(40)" in main and "spacedParams(160, 38" in main and
            "new LinearLayout.LayoutParams(0, dp(56), 1f)" in main and
            'text("--%", 16' in main and 'text("刷新电量", 10' in main and
            "panel(Color.rgb" in main,
            "main UI must use compact Galaxy Watch round-screen dimensions")

    print("Preflight passed: Tile alignment/action, round-screen UI, launcher assets, RACE battery, ANC gates, manifest and workflow validated.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Preflight failed: {exc}", file=sys.stderr)
        raise SystemExit(1)

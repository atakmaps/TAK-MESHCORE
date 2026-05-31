# MeshCore ATAK Plugin

Dedicated ATAK plugin for MeshCore BLE companion transport.

- Package: `com.atakmaps.meshcore.plugin`
- Current version: `1.3.2`
- Target ATAK: `5.5.1` (CIV)

## Quick Start

1. Install plugin APK.
2. Open ATAK -> Tools -> MeshCore.
3. Tap **Scan & Connect** and select your device.
4. Verify contact/map updates and GeoChat messaging.

Install command:

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-Meshcore-*.apk
```

## Features

- MeshCore BLE scan/connect/disconnect
- Mesh favorites and direct-connect behavior
- GeoChat over RF transport
- Contact-targeted CoT relay behavior
- Manual beacon send
- Smart Beacon enable + advanced settings
- MeshCore GPS controls:
  - `Enable MeshCore GPS`
  - `Update ATAK with MeshCore GPS` (fresh-fix request path)
- Map status overlay with panel-open tap behavior

## 2026-05-27 Update (v1.3.0)

- Added hybrid BLE discovery for broader node compatibility:
  - phase 1 uses UUID-filtered scan (Nordic UART + Meshtastic services)
  - phase 2 auto-fallbacks to unfiltered scan gated by Mesh heuristics when phase 1 returns none
- Hardened random-contact suppression for opaque device identities:
  - blocks/sanitizes opaque `ANDROID-<16hex>` pseudo-identities from becoming standalone contacts
  - remaps inbound SA with opaque `ANDROID-<16hex>` UID to a callsign-based `ANDROID-<CALLSIGN>` UID when valid callsign context exists
- Continued auto-point safety sanitization for `U.##.######`-style synthetic callsigns
- Updated scan UX behavior to indicate active discovery until completion/device-picker transition
- Maintains dedicated Mesh transport behavior and dedicated ATAK channel data path work from prior updates

## In-Panel Settings Window

Opened from **Plugin Settings** or **Beacon Settings** in the plugin panel.

Includes:

- Bluetooth favorites/device management entry point
- Beacon interval
- Smart Beacon toggle
- Smart Beacon advanced settings

## Scope

This repository is MeshCore-only. It does not include UV-PRO radio programming workflows.

## Build

```bash
./gradlew :app:assembleCivDebug
```

Output:

`app/build/outputs/apk/civ/debug/`

## Troubleshooting

- **Connected but no traffic:** verify channel/profile parity between nodes.
- **GPS appears stale:** move outdoors, wait for lock, then retry MeshCore GPS update.
- **Chat/contact actions unavailable:** reconnect device and verify peer contact appears in Contacts.
- **Plugin missing in ATAK:** confirm ATAK version and install signature compatibility.

## Repository Structure

- `app/src/main/java/com/atakmaps/meshcore/plugin/` runtime entry and UI
- `.../bluetooth/` BLE transport
- `.../chat/` GeoChat routing
- `.../cot/` CoT bridge/relay
- `.../protocol/` packet routing/fragmentation

## License

See `LICENSE`.

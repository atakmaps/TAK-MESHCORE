# MeshCore ATAK Plugin

Dedicated ATAK plugin for MeshCore BLE companion transport.

- Package: `com.atakmaps.meshcore.plugin`
- Current version: `1.2.0`
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

# MeshCore ATAK Plugin

Dedicated ATAK plugin for MeshCore BLE companion transport.

- Package: `com.atakmaps.meshcore.plugin`
- Current version: `1.3.2`
- Target ATAK: `5.5.1` (CIV)

## Quick Start

1. Install plugin APK.
2. Open ATAK → Tools → MeshCore.
3. **First time:** tap **Scan & Connect**, wait for the picker, select your node.
4. **Returning user with a saved favorite:** tap the favorite chip to select it, then tap **Connect** to go straight to that node.
5. Verify contact/map updates and GeoChat messaging.

Install command:

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-Meshcore-*.apk
```

## Features

- **BLE scan/connect/disconnect** — two-phase discovery (UUID-filtered then heuristic fallback)
- **Boot auto-connect** — if a mesh device was previously connected, the plugin probes it on ATAK startup and connects automatically when available
- **Favorite direct-connect** — mark a node as a favorite; select it from the strip and the button changes to **Connect** for one-tap connection; deselect to return to Scan & Connect mode
- **Scan & Connect picker** — live BLE scan with green/red/grey availability dots; no auto-pairing during picker; only an explicit row tap connects
- **Forget all** — clears all saved MeshCore devices from the plugin registry and attempts Android unpair
- **Setup Favorite Mesh Devices** button — opens device management directly from the main panel
- GeoChat over RF transport
- Contact-targeted CoT relay behavior
- Manual beacon send
- Smart Beacon enable + advanced settings
- MeshCore GPS controls:
  - `Enable MeshCore GPS`
  - `Update ATAK with MeshCore GPS` (fresh-fix request path)
- Map status overlay with panel-open tap behavior

## Connection Modes

### Scan & Connect (default)
Tap **Scan & Connect** when no favorite is selected. The plugin scans for nearby MeshCore nodes and presents a picker with availability dots:
- 🟢 **Green** — node responded to BLE probe, available to connect
- 🔴 **Red** — node not responding (busy or out of range during probe)
- ⚫ **Grey** — known from registry but not seen during current scan

Tap a row to connect. No pairing dialog appears until you explicitly select a node.

### Favorite Direct-Connect
1. Tap **Setup Favorite Mesh Devices** (or open Plugin Settings → Bluetooth Devices).
2. Star a device to mark it as a favorite.
3. The favorite appears as a chip below the connection button.
4. Tap the chip to select it — the button changes to **CONNECT**.
5. Tap **CONNECT** to connect directly without scanning.
6. Tap the same chip again to deselect and return to Scan & Connect mode.

### Boot Auto-Connect
On ATAK startup the plugin silently probes the last successfully connected device. If it responds, the plugin connects automatically without any user interaction. Tapping Scan & Connect at any time cancels the boot probe and takes full control.

## In-Panel Settings Window

Opened from **Plugin Settings**, **Beacon Settings**, or the **Setup Favorite Mesh Devices** button.

Includes:

- Bluetooth favorites/device management (rename, favorite, delete, forget all)
- Beacon interval
- Smart Beacon toggle + advanced settings
- Auto-reconnect toggle

## 2026-05-31 Update (v1.3.2)

- **Ghost contact elimination** — `ContactMergeUtil` collapses duplicate callsign aliases and removes orphan synthetic radio markers on connect; canonical peer UID is resolved across BLE, mesh-node, and mesh-repeater transports
- **Inbound mesh DM from non-ATAK nodes** — messages sent from the native MeshCore app now auto-create a `MESHCORE-NODE-*` contact (named from the first 8 hex chars of the pubkey, e.g. `B5CA4888-MESH`) and deliver to GeoChat with correct DM threading
- **Single notification per message** — `GeoChatConnector` is actively stripped from mesh contacts (removing the native GeoChat unread counter); ATAK's internal conversation unread is cleared immediately after delivery via `markmessageread`; plugin's own `incrementUnreadOnce` is the sole notification source → exactly 1 badge per inbound message
- **Retransmit deduplication** — 60-second TTL dedup cache in `ChatBridge.injectInboundMeshDm` drops repeated Meshcore-protocol retransmits of the same message
- **Clear All Mesh Contacts** now clears notification badges — `clearUnreadForAllMeshContacts()` resets plugin counters, drains dedup cache, and calls ATAK's `updateTotalUnreadCount()`
- **Gateway envelope aligned with Darksteal** — 4-field format (`wireDest|displayCallsign|lineUid|message`) with backward-compatible 3-field fallback
- **GeoChat relay dedup fix** — `maybeRelayInboundRadioCotToTak` skips `b-t-f` GeoChat events already delivered by `GeoChatService` to prevent duplicate conversation-list entries
- **Scan & Connect session guard** — auto-connect and reconnect are fully blocked while the scan/picker is open; only an explicit picker row tap or favorite **Connect** press triggers a connection
- **Boot auto-connect** — probes saved device on startup; light probe (UART service discovery only) for unbonded nodes avoids spurious pairing dialogs
- **Favorite direct-connect** — button toggles between CONNECT and SCAN & CONNECT based on selected favorite
- **BLE availability prober overhaul** — serialized probe queue, generation counter to cancel stale callbacks, light vs full probe modes
- **Forget all** — new `MeshBluetoothForgetAll` class; picker dialog neutral button
- **Duplicate status icon fix** — synchronized `MeshStatusOverlay` install with orphan widget pruning
- **Setup Favorite Mesh Devices** button on main panel

## 2026-05-27 Update (v1.3.0)

- Added hybrid BLE discovery for broader node compatibility
- Hardened random-contact suppression for opaque device identities
- Auto-point safety sanitization for synthetic callsigns
- Updated scan UX with active discovery pulse

## Changelog

### 2026-06-01 (v1.3.2)

- **Silent iconset auto-install:** MeshCore iconset now installs automatically on first ATAK launch with no user interaction. The plugin stages the bundled `meschore.zip` to `/sdcard/atak/tools/import/` then fires the `com.atakmap.android.icons.ADD_ICONSET` broadcast directly to ATAK's `IconsMapAdapter`. No notification, no dialog, and no manual Point Dropper → Add Iconset step required. Install is idempotent — skipped if the iconset UID is already present in `iconsets.sqlite`.

### Earlier

## Scope
This repository is MeshCore-only. It does not include UV-PRO radio programming workflows.

## Build

```bash
./gradlew :app:assembleCivDebug
```

Output: `app/build/outputs/apk/civ/debug/`

## Troubleshooting

- **Node shows grey in picker:** it was not seen during the BLE scan window. Ensure the node is powered on and in range, then scan again.
- **Node shows red in picker:** it was seen during scan but did not complete the BLE probe handshake. It may be busy with another connection.
- **Boot auto-connect not working:** verify the device is bonded in Android Bluetooth settings and was the last successfully connected node.
- **Connected but no traffic:** verify channel/profile parity between nodes.
- **GPS appears stale:** move outdoors, wait for lock, then retry MeshCore GPS update.
- **Chat/contact actions unavailable:** reconnect device and verify peer contact appears in Contacts.
- **Plugin missing in ATAK:** confirm ATAK version and install signature compatibility.

## Repository Structure

- `app/src/main/java/com/atakmaps/meshcore/plugin/` — runtime entry and UI
- `.../bluetooth/` — BLE transport, device registry, availability prober, forget-all
- `.../chat/` — GeoChat routing
- `.../cot/` — CoT bridge/relay
- `.../protocol/` — packet routing/fragmentation
- `.../ui/` — settings fragment, status overlay, device management

## License

See `LICENSE`.

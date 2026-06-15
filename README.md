# MeshCore ATAK Plugin

Dedicated ATAK plugin for MeshCore BLE companion transport.

- Package: `com.atakmaps.meshcore.plugin`
- Current version: `1.5.2`
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
- Manual beacon send (operator-initiated only; no automatic mesh periodic beacon timer)
- **Tool Preferences** (ATAK → Tools → MeshCore → Plugin Settings): UV-PRO–styled settings with Beacon, Radio, Security, and Administrative categories
- MeshCore GPS controls:
  - `Enable MeshCore GPS` — hardware on/off at the node
  - `Use MeshCore GPS for Position` — selects node GPS as the advert position source (gated on hardware toggle)
  - `Update ATAK with MeshCore GPS` — one-shot fresh fix request
  - `Augment GPS from MeshCore` — auto-updates ATAK self-position every 2 min when phone GPS is unavailable
- AES-256-GCM end-to-end encryption for all tunnel traffic (CoT, position, relayed chat) with shared-secret passphrase
- CoT/waypoint reliability:
  - Minification strips non-essential detail to reduce fragment count
  - Immediate double-send (T+3s) for resilience against brief RF collisions
  - App-layer ACK + retry (15s × 5 attempts) — automatic retransmit until receiver confirms delivery
- **Connection battery indicator** — green percent badge beside the connected node name in the plugin panel
- **Per-contact Ping (Connectors page)** — contact card page 3 **Ping** sends a directed position request to that mesh peer over BLE
- **Radial Ping (contact submenu)** — long-press contact → radial **Contact** icon → **Ping** (mesh radio icon); same directed ping path as Connectors
- **Directed vs broadcast ping** — dropdown **Send Ping** broadcasts to all stations; per-contact Ping targets one callsign and filters reply toasts to that peer
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
- Beacon interval display and **Send Beacon** (manual)
- Smart Beacon toggle (in-panel; advanced parameters in Tool Preferences)
- Auto-reconnect toggle

## Tool Preferences

Opened from **Plugin Settings** in the main panel (ATAK native Tool Preferences screen).

Styled like UV-PRO: yellow category headers, blue Smart Beacon section header, white titles, green value summaries, and cyan **Restore Defaults** pill buttons.

| Category | Contents |
|----------|----------|
| **Beacon Settings** | ATAK callsign beacon interval; Smart Beacon thresholds (speed, rates, turn time/slope) |
| **Radio Settings** | Send Ping Reply; message retry interval and max retries |
| **Security** | RF encryption shared secret |
| **Administrative Settings** | Password-gated leadership controls; **Disable Mesh Beacon Limiting** (admin checkbox — preference only until runtime limiting is wired) |

Administrative rows unlock after the admin password is accepted. **Restore All Defaults** and per-section restore buttons reset preferences to plugin defaults (Smart Beacon fast rate **300 s**, min turn time **60 s**).

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

### v1.5.2

- Version bump for release tagging; signed-plugin cloud deploy docs updated for dual ATAK **5.5.1** / **5.6.0** catalog paths.

### v1.5.0

- **Send Ping Reply default ON:** New installs and restore-defaults reset **Send Ping Reply** to enabled (`DEFAULT_PING_REPLY_ENABLED`).

### v1.4.9

- **Tool Preferences overhaul:** UV-PRO–styled Tool Preferences with Beacon, Radio, Security, and Administrative categories; styled summaries, pill restore buttons, and scroll-stable row layouts.
- **Smart Beacon settings restored** in Tool Preferences (advanced speed/rate/turn parameters); defaults fast rate **300 s**, min turn time **60 s**; in-panel Smart Beacon toggle retained.
- **Administrative Settings:** password gate, **Disable Mesh Beacon Limiting** checkbox (same description as UV-PRO; `SettingsFragment.isDisableMeshBeaconLimiting()` for runtime wiring).
- **Removed** periodic mesh beacon UI (Enable Mesh Beacon, slot count, distribute-to-net, national mesh beacon warning); manual **Send Beacon** remains.
- **Radio / Security prefs** grouped in Tool Preferences (ping reply, retry interval/max, encryption passphrase).

### v1.4.8

- **Directed contact ping:** Directed `TYPE_PING` targets only the intended peer; non-target stations ignore the ping for reply purposes; reply toasts on the sender are filtered to that callsign.
- **Radial Ping:** Long-press contact → radial **Contact** submenu → **Ping**; uses cached mesh radio icon via `base64://` GL assets (stock friendly menu unchanged).
- **Connector icons:** Ping, Send Message, and Favorite connectors cache `ic_meshcore` as `file://` URIs so icons render reliably on the Connectors page.

### v1.4.7

- **Connection battery indicator:** Green percent badge beside the connected MeshCore node name in the plugin panel; polls via native battery packets and `GET_STATS` fallback when needed.
- **Per-contact Ping (Connectors page):** Contact card page 3 adds a **Ping** action that sends a directed position request to that mesh peer over the active BLE transport.
- **Contact card defaults:** Mesh contacts use native GeoChat as the default connector; plugin notification badge suppressed in favor of ATAK's conversation unread count.

### v1.4.6

- Password-gated admin settings and slot assignment controls.

### v1.4.5

- **Removed Smart Beacon and timed beacons:** MeshCore no longer sends automatic or interval-based position beacons. Use **Send Beacon** manually when you want to transmit position. Smart Beacon settings, GPS beacon interval, and the periodic post-connect beacon timer are removed.

### v1.4.4

- **Smart Beacon GPS speed:** Periodic beacon decisions use Android `LocationManager` GPS Doppler speed/bearing.
- **Periodic Smart Beacon timer:** Full periodic timer with fixed interval or Smart Beacon profile; first beacon 30s after connect.
- **Smart Beacon turn vs. low-speed logic** and **safety floor** for fixed-interval fallback.

### v1.4.0

- **AES-256-GCM encryption UI wired:** Toggle, passphrase field, SET button, and status indicator now functional. Saved secret is restored on startup. All tunnel traffic (CoT, position, relayed GeoChat) is encrypted when enabled; DMs use MeshCore's native per-recipient E2E crypto independently.
- **Enable MeshCore GPS hardware toggle (new):** Dedicated toggle turns the node GPS hardware on/off (`gps:1/0`). Separated from position-source selection.
- **Repurposed "Use MeshCore GPS for Position":** Now selects the node GPS fix as the advert position source rather than controlling hardware. Greyed out unless the hardware toggle is ON and Send Position With Advert is enabled.
- **Augment GPS gating:** "Augment GPS from MeshCore" toggle and row are disabled whenever the GPS hardware toggle is off. Default is OFF.
- **CoT minification:** Non-essential `<detail>` children (`precisionlocation`, `status`, `takv`, `_flow-tags_`, `archive`, `height`, `track`, `uid`, `ce`, `le`, `link`, `creator`) and XML declaration stripped before compression. Reduces fragment count from 3→2 for typical waypoints. Strictly never-worse — falls back to full XML if minification produces a larger payload. `version='2.0'` preserved (required by ATAK's CoT parser).
- **CoT hop-count threading:** MeshCore channel message `pathLen` (repeater hop count) threaded from BLE receive layer through `PacketRouter` to `CotBridge`. Logged on every received CoT event.
- **CoT ACK/retry system (new `TYPE_COT_ACK = 0x08`):** Outbound CoT events are registered in a watchdog keyed by CoT UID. Receiver sends a `TYPE_COT_ACK` after successful parse. Sender cancels watchdog on ACK; retransmits at 15s intervals up to 5 times if no ACK. GeoChat CoT (`b-t-f*`) excluded (uses chat retry). `cotRetryExecutor` shuts down cleanly on dispose.
- **Immediate double-send:** Each CoT is re-sent automatically 3 seconds after the first attempt (if not yet ACK'd) to survive brief RF collisions without waiting for the 15s retry timer.
- **Parse-failure guard in `injectCompressedCot`:** Logs the first 120 chars of the rejected XML when `CotEvent.parse` returns null, distinguishing parse failures from RF drops in diagnostic logs.

### v1.3.6

- **Fixed outbound replies to native MeshCore contacts (MESHCORE-NODE-*):** Replies typed in ATAK to a `MESHCORE-NODE-*` contact were sent as a callsign broadcast using the contact's display name (e.g. `PB-05-MESH` → wire form `PB05MS`). The receiving node's callsign is `PB-05` (wire form `PB05`), so it rejected the packet as not addressed to itself. `trySendNativeMeshDm` now falls back to a Contacts store lookup when the `chatroom` attribute is a display name rather than a UID, resolving the pubkey from the matching `MESHCORE-NODE-*` contact. Replies now route via native MeshCore pubkey DM (`sendContactTextMessage`), matching how inbound DMs arrive.
- **Delayed `dispatchChangeEvent` for MESHCORE-* icon refresh:** After `GeoChatService.onCotEvent` processes an inbound message it can asynchronously re-stamp the sender's default connector. `repairAtakPeerConnectorDefault` re-writes `GeoChatConnector` as the default immediately, then posts a 600 ms delayed `dispatchChangeEvent` on the main thread (MESHCORE-* contacts only) so the contacts-list icon re-renders to the chat bubble without triggering a duplicate-message reload.

### v1.3.5

- **Fixed double notification badge on MESHCORE-* inbound DMs:** `MeshSendMessageConnector.getFeature(NotificationCount)` now returns 0 for **all** contacts. ATAK's `ContactConnectorManager` calls all registered handlers for each connector type and sums results; previously our plugin returned a count while ATAK's native `GeoChatConnectorHandler` also returned 1 for the same contact, producing "Geo Chat: 1 + Send Message: 1" = 2 badges. Now ATAK's native tracking is the sole badge source for all contacts, matching the approach that was already working for `ANDROID-*` contacts. Badge clears correctly when the conversation is opened.
- **`markmessageread` sent on contact tap:** `handleContact` now broadcasts `markmessageread` for the contact UID before opening the conversation, ensuring the native GeoChat unread clears immediately when the user taps the chat icon.

### v1.3.4

- **Fixed cross-device DM routing:** Removed `sixCharWireForm` fallback from `rfDestinationLooksLikeSelf`. The 6-char truncation (`"JESTER_15"` → `"JESTER"`) was ambiguous — contacts sharing the same prefix (e.g. `JESTER_15` and `JESTER_25`) both matched, causing DMs addressed to one device to be injected on the other. Only exact callsign and `CallsignUtil.toRadioCallsign` (vowel-compressed) forms are now accepted, matching UV-Pro behaviour.
- **Fixed outbound packets to UV-Pro contacts:** `sendChatOverRadio` now applies `CallsignUtil.toRadioCallsign` to the destination room before passing it to `MeshCorePacket.createChatPacket`. Previously simple 6-char truncation (`"JESTER_25"` → `"JESTER"`) did not match UV-Pro's vowel-compressed wire form (`"JSTR25"`), so all DMs from MeshcoreAtak to UV-Pro contacts were silently dropped on receipt.
- **`PendingOutboundChat` stores wire room:** Retries now use the already vowel-compressed `wireRoom` rather than the raw callsign, ensuring consistent routing on retry.
- **Fixed first-message-after-restart dedup collision:** `MeshCorePacket.CHAT_MESSAGE_ID` now seeds from `System.currentTimeMillis()/1000` instead of always starting at `1`. UV-Pro caches received wire mids for 120 s; the old counter reset to `mid=1` on every ATAK restart, causing UV-Pro to silently drop the first message as a duplicate of the previous session.

### v1.3.3

- **Single notification per message (ANDROID-* contacts):** `MeshSendMessageConnector` now returns 0 for `ANDROID-*` UIDs so ATAK's native `GeoChatConnector` is the sole badge source for ATAK peers. Eliminates the "Geo Chat: 1 + Send Message: 1" double-count that showed 2 in the contacts icon.
- **No duplicate messages in conversation list:** Removed `contact.dispatchChangeEvent()` from `applyMeshInboundConnectors` and `applyMeshContactConnectors`. The async dispatch was firing after `geoChatService.onCotEvent()` inserted the new message, causing ATAK to reload the conversation and add it a second time.
- **Chat icon opens chat directly:** `handleContact` now calls `openConversation(ic, false)` for both `GeoChatConnector` and `MeshSendMessageConnector` connector types. `false` opens the DM conversation panel; `true` was opening the contact-info card. The native `GeoChatConnector` handler is no longer deferred to (it fell back to a contact card for non-network contacts).
- **Fixed outbound message relay (dedup cascade):** The COT_PLACED handler in `handleOutgoingChat` was setting the dedup entry, then calling `relayOutboundGeoChatCot` which checked the dedup again and found it already set — silently skipping the send. Dedup is now centralised in `relayOutboundGeoChatCotAsCompact` only.
- **NetConnectString AIOOBE eliminated:** `buildNativeConnectorSeed` and `buildGeoChatSeed` now use `127.0.0.1:4242` instead of `*:-1`. ATAK's `isMulticast()` parser was throwing `ArrayIndexOutOfBoundsException` on the wildcard host, preventing conversation fragments from being created.

### v1.3.2

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

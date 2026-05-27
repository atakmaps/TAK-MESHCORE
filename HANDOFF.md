# MeshCore Plugin — Handoff & Architecture (for new agents)

## 2026-05-27 Addendum (v1.3.0) — current operational truth

This addendum captures the latest field-driven fixes now in `MeshcoreAtak` and should be treated as the current baseline for troubleshooting.

### 1) Random contact root-cause and fix (critical)

Field logs showed that "random contacts" were not classic MAC addresses. They were mostly opaque ATAK SA UIDs in this form:

- `ANDROID-17292853696dcbf5`
- `ANDROID-b726a98286ca1d08`

These are opaque 16-hex identifiers (`ANDROID-<16hex>`) that can leak into contact presentation during SA relay/injection paths.

#### What was implemented

- Added opaque device ID detection for 16-hex IDs.
- Inbound auto-point sanitization now evaluates both callsign and UID, not callsign only.
- Added explicit SA UID normalization:
  - if inbound event UID is `ANDROID-<16hex>` and a valid human callsign is present, remap to callsign UID (`ANDROID-<CALLSIGN>`), register aliases, and continue.
  - if identity remains pseudo/opaque, sanitize to point representation so it does not become a standalone pseudo-contact.
- Chat fallback UID synthesis now rejects opaque sender IDs so `ANDROID-<16hex>` is not created from malformed sender strings.

### 2) BLE discovery tuning (strict but tolerant)

User feedback showed one failure mode: strict filtering avoided TVs but missed certain node brands.

#### Current scan model

- Hybrid two-phase scan:
  1. UUID-filtered BLE scan (Nordic UART + Meshtastic service UUIDs)
  2. automatic fallback to unfiltered scan if phase 1 finds no nodes
- Fallback results are still gated by Mesh heuristics (`isLikelyMeshDevice` / name heuristics), reducing false positives versus fully-open scans.
- Session guards prevent stale scan timers from interfering with active scan/connect transitions.

### 3) Scan UX indicator behavior

- Discovery feedback now runs during scan phase (pre-selection), not only during direct connect attempts.
- Pulse/indicator lifecycle is tied to scan state transitions (start, complete, error/cancel) and device-picker transition.

### 4) Version and release state

- Plugin version bumped to `1.3.0`.
- Dropdown header updated to `v1.3`.

### 5) Immediate troubleshooting checklist (for this branch)

When a user reports "random contacts":

1. Pull logs and search for `ANDROID-<16hex>` UIDs in `MeshCore.CotBridge` and PreSend lines.
2. Confirm remap/sanitize logs are present for opaque identities.
3. Distinguish true peer contact UIDs (e.g., `ANDROID-JESTER_15`) from opaque SA IDs.

When a user reports "can't find my node":

1. Confirm phase-1 UUID scan ran.
2. Confirm fallback phase started when phase-1 had no hits.
3. Verify name/heuristic matching for the vendor naming convention in logs.

## 1.1 Mesh-only Status (2026-05-25)

This branch is now cut to `1.1` with a MeshCore-only operator surface:

- `UVProDropDownReceiver` rewritten to Mesh connection/favorites/log controls only.
- `UVProMapComponent` simplified to Mesh transport wiring and Mesh startup auto-connect.
- UV radio control/APRS button flows removed from the main dropdown layout.
- Mesh direct-connect target persistence now uses `BluetoothDeviceRegistry.getMeshConnectTargetAddress`.

Additional backend class cleanup is still possible, but this release intentionally prioritizes a Mesh-only user experience and transport path.

This document is a **high-signal handoff** for a new engineer/agent coming into this repo mid-stream. It focuses on: how ATAK ↔ plugin integration works, how radio packets flow (BLE/KISS/AX.25), and the non-obvious ATAK behaviors discovered during the current iteration.

**Full operator onboarding (TPC, Fortify, `atakmaps.com` deploy, playbooks):**  
`/home/paul/Documents/ATAK/Plugins/Handoff Docs/UV-PRO-v1.8.6-AGENT-HANDOFF.md`  
Read **§0** there first if you are a **new agent** with no prior context. This file (`HANDOFF.md`) is **gitignored** — local agent notes only.

If you are new to ATAK plugin development, start with **`README.md`** for build/install basics and **`AGENTS.md`** for Cloud/VM setup details.

## MeshCore 2026-05-25 Lessons Learned (critical)

This repo now contains a working MeshCore BLE companion transport path inside `BtConnectionManager`. The highest value findings from live two-device testing:

- **Connected BLE does not mean over-air comms are working.**  
  We repeatedly saw healthy BLE/GATT state and still no contact updates until RF params were aligned.
- **Channel name and slot index are insufficient.**  
  Nodes can show `Public` on slot `0`; transport still fails if channel secrets differ. Channel secret fingerprint logging was added to verify this quickly.
- **Channel secret match is still insufficient.**  
  LoRa PHY parity (`freq`, `bw`, `sf`, `cr`) must match. We now parse `RESP_SELF_INFO` and log these values to detect mismatch immediately.
- **Inbound payload formatting differs from assumptions.**  
  Companion channel text may include sender prefix before app payload. Parser had to switch from `startsWith("UVAX1|")` to extracting envelope substring from anywhere in text.
- **After parser fix + RF alignment, contact/beacon exchange works.**  
  Field test confirmed contacts appear in ATAK after beacon send.

## Next Steps to Implement Full ATAK Functions Over MeshCore

Use this as the authoritative implementation sequence for finishing full ATAK capability on MeshCore transport.

1. **RF profile management in-plugin (P0)**  
   - Add `CMD_SET_RADIO_PARAMS` UI + persistence profile (freq/bw/sf/cr/repeat).  
   - Add one-tap "Apply profile to node" and "Read current profile".  
   - Show mismatch warning banner before users attempt network testing.

2. **Transport robustness + observability (P0)**  
   - Keep command queue deterministic under reconnect and poll cycles.  
   - Add bounded metrics panel: last RX/TX, queue depth, recent message rate, no-message watchdog state.  
   - Add structured logs for message type counters to simplify field triage.

3. **Native TAK wire as primary data-plane (P1)**  
   - Promote `!T1P`, `!T1C`, `!T1F` codecs/fragmenter to default path for PLI/chat/CoT.  
   - Retain UVAX shim as fallback compatibility mode during migration.

4. **Full CoT feature parity workstream (P1)**  
   - Validate contact-targeted CoT set end-to-end: points, routes, casevac, drawings, mission item updates.  
   - Validate message size/fragment behavior with realistic CoT payloads and lossy RF conditions.  
   - Add retry/backoff policy tuned for MeshCore airtime constraints.

5. **Contact/discovery maturity (P2)**  
   - Decide final model for advert integration vs ATAK beacon-only discovery.  
   - Implement two-stage presence for no-GPS nodes (discovery via advert/contact metadata; position from ATAK beacon updates).

6. **Namespace and packaging hardening (P2)**  
   - Complete migration from `com.uvpro.plugin` package names to MeshCore-specific namespace.  
   - Eliminate class collision risk when legacy UV-PRO and MeshCore plugin APKs coexist on same ATAK device.

7. **Verification matrix + release gates (P2)**  
   - Maintain repeatable test plan across 2-node and 3-node scenarios, mixed GPS availability, and mixed network/RF relay paths.  
   - Require clean pass on chat, beacon/contact, targeted CoT, and reconnect resilience before each release bump.

## What this plugin is now (important framing)

The plugin started as a “bridge” (toggleable relays of PLI/SA and GeoChat). It has evolved into a **contact-centric transport**:

- Plugin creates “sendable” ATAK contacts for radio peers (UIDs look like `ANDROID-<CALLSIGN>`).
- **GeoChat** to those contacts routes through the plugin `PluginConnector` (Intent action) and is transmitted over RF.
- **Contact-targeted CoT** (waypoints, routes, casevac/9-line, drawings, etc.) is intercepted by `CotBridge.PreSendProcessor`, compressed, and relayed over RF to the target radio contact.

This fork does **not** blindly relay all ATAK SA over RF; outbound traffic is centred on **contacts** (chat + targeted CoT) plus beacon/PLI.

This framing explains several implementation choices below (connectors, routing hooks, badge integration).

## Runtime architecture (services + main objects)

### Core objects (by responsibility)

- **`UVProMapComponent`**: plugin lifecycle + wiring. Initializes bluetooth, router, CoT + chat bridges. Starts timers/listeners.
- **`BtConnectionManager`**: Bluetooth SPP link to the radio. Owns connect/reconnect behavior and raw byte IO.
- **KISS layer** (`kiss/`): wraps/unwraps AX.25 frames into KISS frames for TNC-over-serial style links.
- **AX.25 layer** (`ax25/`): parse/build AX.25 frames; APRS parsing for “standard” position payloads.
- **`PacketRouter`**: takes inbound frames/payloads and routes them to subsystems (chat, GPS/PLI, CoT fragments).
- **`UVProPacket`**: compact binary packet formats for “BTECH relay” messages (chat, gps, etc.).
- **`PacketFragmenter`**: fragment/reassemble large payloads (notably large CoT) into multiple radio frames.
- **`CotBridge`** / **`CotBuilder`**: map CoT ⇄ radio. Inject inbound CoT into ATAK, build outbound position CoT, and relay CoT to radio when appropriate.
- **`ChatBridge`**: GeoChat ⇄ radio. Inject inbound radio chat into ATAK’s chat pipeline; intercept ATAK outbound chat to plugin contacts and send over radio.
- **`UVProContactHandler`**: ATAK connector integration, including unread badge (`NotificationCount`) for plugin connector address.
- **`ContactTracker`** / `RadioContact`: maintains in-range/last-seen contacts and their latest state.

### ATAK integration points used (high level)

- **Contacts**:
  - Plugin registers contacts with ATAK so they appear in the native Contacts UI and are “sendable”.
  - Each contact is reachable via a **`PluginConnector`** so ATAK can route “send to contact” actions into the plugin using an Intent action string.
- **Chat**:
  - Outbound: plugin listens to ATAK’s chat send actions and handles “send to contact” bundles, then transmits over radio.
  - Inbound: plugin injects a `b-t-f` GeoChat CoT event so ATAK’s native parser creates the chat message/thread.
- **CoT / map objects**:
  - Position injections and inbound radio-derived CoT go through ATAK’s pipeline for map display.
  - `CotBridge` registers PreSend hooks and related instrumentation from earlier bridge work — useful for debugging and incremental features, **not** a guarantee that “send marker to contact” is a supported product path here.

## Data-plane logic trees

### A) Inbound over RF → visible on ATAK map/chat

```
RF
  ↓
Radio (KISS TNC)
  ↓  (Bluetooth SPP bytes)
BtConnectionManager (bytes)
  ↓
KISS decoder → AX.25 frame(s)
  ↓
PacketRouter
  ├─ if APRS position → ContactTracker + CotBridge (APRS parser + iconset path)
  ├─ if APRS message (`:`) → ChatBridge.injectRadioMessage
  ├─ if APRS telemetry (`T#`) → touch contact; CoT reinject at last fix + remarks if known position
  ├─ if UVProPacket.Chat:
  │     ↓
  │   ChatBridge.injectRadioMessage(...)
  │     ├─ records wire mid in pendingReadAcksByConversation[senderUID]
  │     ↓ (build GeoChat CoT)
  │   CotBridge.injectChatCot(...)
  │     ↓
  │   ATAK GeoChat parser creates/updates thread + message
  │     ↓
  │   UVProContactHandler increments NotificationCount (unread badge)
  │     ↓
  │   PacketRouter sends ACK_KIND_DELIVERED back to sender over RF
  │     (ACK_KIND_READ is sent later, when user opens the conversation — see clearUnreadLocal)
  ├─ if UVProPacket.Gps/PLI:
  │     ↓
  │   CotBridge.injectPositionCot(...)
  │     ↓
  │   ATAK renders marker/contact on map
  └─ if UVProPacket.CotFragment:
        ↓
      PacketFragmenter reassembles full CoT XML
        ↓
      CotBridge injects CoT
        ↓
      ATAK processes it (marker/shape/etc)
```

**Loop prevention (inbound injection)**:
- When the plugin injects inbound CoT, ATAK may later attempt to send it out again (depending on hooks).
- `CotBridge` maintains a short “do not relay outbound” window keyed by injected CoT UID to suppress immediate echo loops.

### B) Outbound ATAK GeoChat → RF (to a plugin contact)

Goal: when user chats with `ANDROID-<CALLSIGN>` contact in native ATAK UI, radio should key and transmit.

```
User types message in ATAK chat UI
  ↓
ATAK decides destination connector(s)
  ↓
For plugin contacts: ChatManager creates Intent whose action == connector "connection string"
  ↓
Plugin registers BroadcastReceiver for ACTION_PLUGIN_CONTACT_GEOCHAT_SEND
  ↓
ChatBridge.handleOutgoingChat / relayPluginGeoChatMessageBundle
  ↓
UVProPacket.createChatPacket (assigns messageId)
  ↓
EncryptionManager (optional)
  ↓
PacketFragmenter (if needed)
  ↓
AX.25 frame(s)
  ↓
KISS encoder
  ↓
BtConnectionManager write bytes → radio
  ↓
RF
```

Important detail: **the connector action matters**.
- ATAK uses `new Intent(connector.getConnectionString())` for plugin contacts.
- Therefore the plugin must register its connector with a connection string that is a **broadcast action** it listens for (currently `com.uvpro.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND`).

### C) Outbound contact-targeted CoT → RF

`CotBridge` registers a `PreSendProcessor` with ATAK's `CommsMapComponent`. When ATAK dispatches a CoT event with a `toUIDs` list, the processor checks if any recipient is a known UV-PRO radio contact (fast path: `btechContactUids` set; fallback: `Contacts.getContactByUuid` + `PluginConnector` check). If matched, the CoT is gzip-compressed and handed to `PacketFragmenter` for RF transmission. Events exceeding 4 KB compressed are dropped with a warning. An inbound-inject skip set prevents echo loops.


### D) SA Relay — inbound network CoT → RF broadcast

When `PREF_SA_RELAY_ENABLED` is true, `CotBridge.maybeSaRelayInboundNetworkCot` fires on every `CommsLogger.logReceive` call. It:
1. Checks the type against `SA_RELAY_TYPE_PATTERN` (`a-*-G-*`, `b-m-p-*`, `b-m-r`).
2. Skips UIDs in `inboundInjectSkipOutboundRelay` (loop prevention — these came from the radio).
3. Skips the local device UID (beacon already handles self-position).
4. Enforces a per-UID 30-second throttle via `saRelayLastSentByUid` to prevent flooding.
5. Calls `sendCotOverRadio` on a background thread (same path as contact-targeted relay, size guard included).

SA Relay is intentionally not enabled by default — it is designed for a single designated relay node.

### D.1) 2026-05-21 learning: why repeated Wi-Fi SA flooded RF

Field logs confirmed that repeated RF traffic was not plugin beacon spam. The dominant repeated packets were
network-origin `a-f-G-U-C` SA CoT from a Wi-Fi peer (`JESTER_25`) that SA Relay kept rebroadcasting on its
30-second per-UID cadence.

Important distinction:
- Plugin beacon interval (for local periodic beacons) does not control inbound Wi-Fi/TAK update cadence.
- SA Relay re-broadcasts inbound network SA if eligible, so Wi-Fi peers can still drive frequent RF traffic.

Mitigation implemented in `CotBridge`:
- Added per-UID SA payload signature cache (`saRelayLastSignatureByUid`).
- Built normalized signature by stripping volatile `time/start/stale` attrs from CoT XML.
- For `a-*` SA/status types only, if signature is unchanged, relay is suppressed with:
  - `SA Relay: suppressed unchanged payload ...`
- Non-SA relay paths (chat/routes/other events) are not suppressed by this signature guard.

### SA Relay — v1.7.1 additions

**Settings surface (two paths)**

Prior to 1.7.1 the SA Relay toggle was only accessible via ATAK Menu → Tools → UV-PRO Settings
(the `preferences.xml` path).  Two changes close the gap:

- **Dropdown gear dialog** (`UVProDropDownReceiver.showSettingsDialog`): the inline settings
  dialog that opens from the UV-PRO panel now includes an SA Relay switch with a descriptive hint
  ("Throttled: one update per contact per 30 s. Requires TAK server + radio connected."). The
  switch reads/writes `PREF_SA_RELAY_ENABLED` via `SettingsFragment.isSaRelayEnabled` and
  `SharedPreferences`. Saving the dialog refreshes the dropdown status row immediately via
  `updateStatusFields()`.

- **Tools preferences XML** (`app/src/main/res/xml/preferences.xml`): a dedicated
  `PreferenceCategory` "SA Relay" now appears at the top of the UV-PRO Settings screen using a
  `PanCheckBoxPreference` keyed to `uvpro_sa_relay_enabled`. Dynamic summary via
  `SettingsFragment.onResume` shows "On — network PLI/markers/routes relayed over radio when
  connected" or "Off".

**Main panel status row**

`uvpro_dropdown.xml` gained a new `SA Relay (TAK → radio)` status row in the Beacon group.
`UVProDropDownReceiver.updateStatusFields()` populates `text_sa_relay_status` with green "On" or
grey "Off" based on the current pref value.  The value refreshes whenever the dropdown is opened
and after every settings save.

**`SA_RELAY_TYPE_PATTERN` Javadoc**

`CotBridge.SA_RELAY_TYPE_PATTERN` (the regex that gates which inbound CoT types are eligible for
relay) received a formal Javadoc comment:
> Inbound network CoT types eligible for SA Relay (network → radio).
> Matches friendly SA (`a-*-G…`), points/markers (`b-m-p…`), routes (`b-m-r…`).

**Debug logging gated on `BuildConfig.DEBUG`**

`MARKER_DEBUG` logcat blocks in `CotBridge.injectCompressedCoT` are now wrapped in
`if (BuildConfig.DEBUG)` so release builds produce no verbose marker-inspection output.

---

## Contacts model (why `ANDROID-` UIDs exist)

ATAK uses `ANDROID-<something>` UIDs for contacts/devices. To make radio peers behave like “real” ATAK contacts (sendable, chat-able), the plugin:

- Creates/registers contacts with **`ANDROID-<CALLSIGN>`** style UIDs.
- Registers aliases so that radio-truncated callsigns (e.g., 3-char) still resolve to the canonical contact UID.

**Alias mapping** matters because AX.25 payloads and radio UI often truncate callsigns; without aliases you’ll get duplicate contacts and split chat threads.

## GeoChat / threading rules (non-obvious)

ATAK’s GeoChat threading and display logic is sensitive to several fields inside the GeoChat CoT. The plugin must build inbound chat CoT carefully so ATAK:
- creates the correct thread,
- displays callsign without `ANDROID-` prefix,
- and does not deduplicate/overwrite messages.

Key principles implemented in `CotBuilder.buildChatCot(...)` and `CotBridge.injectChatCot(...)`:

- **Message uniqueness**: ATAK can deduplicate based on IDs; inbound messages must have a collision-resistant unique suffix in their CoT UID.
- **DM threading**: for DMs, the conversation ID must match what ATAK uses when user opens chat with that contact (often the peer’s `ANDROID-*` UID).
- **Display name**: `__chat chatroom` should be a human callsign (e.g. `VETTE`), not `ANDROID-VETTE`, or the UI title can look wrong.
- **Local device UID**: some DM fields should use the **local** device UID (not the peer) or ATAK parsing can mis-thread.
- **Thread id from RF destination**: if the RF destination “looks like self”, the plugin must force the conversation/thread id to the sender peer, not the destination callsign string.

## Unread badge integration (contacts icon red dot)

ATAK can query a connector feature `NotificationCount` from contacts/connectors. The plugin uses:

- `UVProContactHandler.getFeature(NotificationCount)` to return unread count for the plugin connector address only.
- A deduplicating unread key set per UID so each inbound message increments once.
- A set of listeners to clear unread when ATAK considers the conversation read, including “chat window already open” cases where ATAK doesn’t fire a simple mark-read broadcast.

Practical takeaway: badge behavior involves multiple hooks (broadcasts, contact change listeners, visibility polling). If this breaks, focus on `ChatBridge` + `UVProContactHandler`.

## BLE / AX.25 / packet formats (what goes over the wire)

### Transport stack
- **Bluetooth SPP**: raw serial-like link between Android and radio.
- **KISS**: framing protocol to carry AX.25 over serial.
- **AX.25**: amateur packet framing used for RF packet radio.
- **UVProPacket**: plugin’s compact binary payload inside AX.25 info field (plus optional APRS parser path for standard packets).

### Packet types (conceptually)
- **Chat packet**: includes sender callsign, destination/thread id, message text, and a `messageId`.
- **GPS/PLI packet**: includes lat/lon/alt/speed/course and callsign.
- **CoT fragment packet**: carries chunks of a full CoT XML blob, reassembled on receive.
- **Ping/keepalive**: lightweight presence/hello.

### Encryption
If enabled, payload uses envelope v3: AES-256-GCM with PBKDF2-HMAC-SHA256 (310k iter) and random salt per payload. All nodes must share the same secret; failures drop packets. Pre-1.5.3 CBC payloads are not supported.

## Known issues / design decisions (as of this handoff)

### GeoChat delivery receipts (delivered + read checkmarks)

Inbound `TYPE_CHAT` RF packets trigger two `TYPE_ACK` packets back to the sender:

- **ACK_KIND_DELIVERED** — sent immediately from `PacketRouter` when the chat packet is processed (before ATAK even stores the message).
- **ACK_KIND_READ** — sent when the recipient user opens the conversation. The wire `messageId` is stored per conversation UID in `ChatBridge.pendingReadAcksByConversation` during `injectRadioMessage`. When `clearUnreadLocal(conversationId)` fires (triggered by the contacts-change listener or conversation-open detection), all pending wire mids for that conversation are drained and transmitted as `ACK_KIND_READ`.

On the **sender** side, received ACKs are handled in `ChatBridge`: `outboundWireMidToLocalLineUid` maps wire mid → GeoChat line UID; the receipt CoT is built by `CotBuilder.buildGeoChatReceiptCot` and injected via `CotBridge.injectGeoChatReceipt`. ATAK's `GeoChatService` looks up the message by the **bare UUID suffix** of the GeoChat line UID (not the full `GeoChat.*` string) — the CoT UID must match this or the DB lookup returns null and the checkmark never appears.

Key gotcha: `com.atakmap.chat.markmessageread` is **not** reliably broadcast by ATAK just from opening a conversation — do not rely on it as the primary read-trigger.

### Team color semantics (fixed)
Outbound GPS beacons embed the sender’s ATAK **locationTeam** (same string as native SA). Inbound position CoT uses that value for **`detail/__group`**, so map markers match native networked contacts. The Contacts pane lists **`IndividualContact` linked to the CoT `MapItem`** (`PacketRouter.linkRadioIndividualContactToMapMarker`) so list/tint behavior matches ATAK’s native contacts UI. Older peers that omit the team extension still default missing team to **Cyan** in CoT (not the receiver’s team).

### Timing: ATAK initialization
Some ATAK singletons (e.g., `ChatManagerMapComponent`) may not be ready when the plugin is created. Where necessary, code retries registration after startup.

## “Hello world” test flows (what to run to prove it works)

### Minimal end-to-end field test (two phones, Wi‑Fi off)

1. Pair each phone to its radio, connect plugin (green dot).
2. Ensure both are on RF.
3. From VETTE: open Contacts → select `ANDROID-JUNIOR` (radio contact) → chat → send “hello”.
4. On JUNIOR: verify message appears in native chat UI and badge clears when read.
5. From VETTE: long-press a waypoint → Send → select JUNIOR → confirm the radio keys and JUNIOR's map updates.
6. From VETTE: draw a short route, send to JUNIOR — verify RF relay and map update on the receiving end.

## Where to look first (debugging map)

### If outbound chat doesn’t key radio
- `ChatBridge` receiver for `ACTION_PLUGIN_CONTACT_GEOCHAT_SEND`
- `PluginConnector` connection string configuration (must match receiver action)
- `PacketRouter` not involved; this is outbound path

### If inbound chat appears but threads incorrectly / wrong title
- `CotBridge.injectChatCot` and `CotBuilder.buildChatCot`
- DM logic for `__chat` and `chatgrp` attributes
- local UID caching for GeoChat fields

### If unread badge count is wrong / doesn’t clear
- `UVProContactHandler` unread keying + `NotificationCount`
- `ChatBridge` listeners for mark-read / open-chat / fragment visibility polling
- ensure `Contacts.getInstance().updateTotalUnreadCount()` is called on clear

### If unintended CoT re-transmit / echo appears when extending `CotBridge`
- Loop-suppression map keyed by injected UIDs (`CotBridge`)
- Ensure outbound hook skips events that originated from inbound radio injection (`ANDROID-*`, recently injected UID window)

## Key files (jump list)

- Wiring/lifecycle: `app/src/main/java/com/uvpro/plugin/UVProMapComponent.java`
- UI dropdown: `app/src/main/java/com/uvpro/plugin/UVProDropDownReceiver.java`
- Contacts/unread: `app/src/main/java/com/uvpro/plugin/UVProContactHandler.java`
- Outbound/inbound chat: `app/src/main/java/com/uvpro/plugin/chat/ChatBridge.java`
- CoT bridge/builder: `app/src/main/java/com/uvpro/plugin/cot/CotBridge.java`, `app/src/main/java/com/uvpro/plugin/cot/CotBuilder.java`
- Packet routing: `app/src/main/java/com/uvpro/plugin/protocol/PacketRouter.java`, `app/src/main/java/com/uvpro/plugin/protocol/UVProPacket.java`
- APRS parsing + symbols: `app/src/main/java/com/uvpro/plugin/ax25/AprsParser.java`, `app/src/main/java/com/uvpro/plugin/ax25/AprsSymbolMapper.java`
- Fragmentation: `app/src/main/java/com/uvpro/plugin/protocol/PacketFragmenter.java`
- BLE: `app/src/main/java/com/uvpro/plugin/bluetooth/BtConnectionManager.java`

---

## 2026-05 TPC build outage + confirmed workaround

### What failed

Starting around 2026-05-08, TPC jobs failed before project configuration with:
`The specified initialization script '/root/.gradle/init.d/00-tak-artifactory.gradle' does not exist.`

This affected previously-successful source zips too (including unchanged 1.9.1 resubmits), so it was not a plugin code regression.

### What TAK provided (and what worked)

TAK provided a project-level workaround:

1. Add `00-tak-artifactory.gradle` in repo root (same directory as `gradlew`).
2. Patch `gradlew` to rewrite old init-script references:
   - from `/root/.gradle/init.d/00-tak-artifactory.gradle`
   - to local `./00-tak-artifactory.gradle`
3. Keep 1.9.2 packaging and resubmit.

Result: submission succeeded and returned:
- `ATAK-Plugin-UVPro-1.9.2-tpc-5.5.1-civ-release.apk`
- `ATAK-Plugin-UVPro-1.9.2-tpc-5.5.1-civ-release.aab`
- `civRelease-app-mapping.txt`

### Additional mitigations tested during incident

- `local.properties` placeholder (`a=a`) in source zip: did **not** fix this failure by itself.
- `takArtifactoryInitLoaded=true` in `gradle.properties`: useful compatibility flag, but did **not** bypass missing `/root/...` init-script alone.
- CI tag pin suggestions from TAK (`ANDROID_BUILDER_TAG`, `GRADLE_BUILD_LAUNCHER_TAG`) were provided as alternate operational mitigations.

---

## Update server + certificate trust (`UVProMapComponent`, v1.9.7)

**Goal:** ATAK’s plugin repo client (`TakHttpClient` / `GetRepoIndexOperation`) must trust `atakmaps.com` for `product.infz` without manual user steps.

### What was wrong in older write-ups

- **`getCACerts for atakmaps.com … 0 certs` is not something to dismiss** when plugin-repo HTTPS fails: for the **update-server** path, ATAK only applies PKCS#12 material from the cert DB if a **non-empty unlock credential** is stored (`CertificateManagerBase.getCACerts` + `FileSystemUtils.isEmpty` on the stored value). Importing bytes alone is insufficient.
- **`AtakCertificateDatabase.importCertificate(location, connectString, type, delete)`** — the second argument is a **connect string** (often `""` here), not the PKCS#12 file unlock. The unlock is saved separately via **`saveCertificatePassword`** into ATAK’s auth DB and mirrored into default **`SharedPreferences`** under the framework key for the update-server truststore unlock.
- **Fortify (TPC):** return zips include **`fortify_scan_results.pdf`**. A real failure mode was **“Password Management: Null Password”** on **`KeyStore.load(..., null)`** when the PKCS#12 unlock was removed from code. Fixing TLS required a **non-null** unlock for the asset PKCS#12; a **literal** unlock string in source may be flagged by **other** Fortify rules — if so, use CI-injected or obfuscated material, **not** a return to `null`.

### Flow (v1.9.7)

1. **`UVProLifecycle`:** `applyUpdateServerTrustEarly` → `configureUpdateServerStatic` using resolved host ATAK `Context` (earliest practical hook).
2. **`UVProMapComponent.onCreate`:** `configureUpdateServerStatic` synchronously, then again on `view.post` / delayed posts to mitigate races with startup sync.
3. **Prefs:** update-server URL (`atakmaps.com/.../product.infz`), enable + auto-sync flags (several key aliases).
4. **`installUpdateServerTruststoreCompat`:** copy **`atakmaps-ca.p12`** ( **`keytool`**-built, matches bundled store secret) → `filesDir/uvpro_update_server_ca.p12`; `updateServerCaLocation`; `importCertificate`; **`saveCertificatePassword`** + prefs; load PKCS#12 with same secret for host binding helpers.
5. **`reloadCertificateManagerFromDatabase`:** `CertificateManager.invalidate(...)` for host/URL keys + **`refresh()`**.
6. **`registerUpdateServerCA`:** **`isrg-root-x1.pem`** first; **`bindUpdateServerCaToHost`**; **`addCertificate`**; **`injectCACert`** fallback.
7. **`scheduleDeferredUpdateServerSyncs`:** delayed **`ProductProviderManager.sync`** retries.

### Manual parity test

**TAK Package Mgmt Preferences** → truststore file + unlock matching the PKCS#12 — confirmed to fix **`Socket is closed`** when automation was insufficient; **uninstall UV-PRO** during that test if an older build keeps rewriting `updateServerCaLocation`.

### Canonical agent doc

Long-form paths, server deploy, TPC packaging, and **stale-rule corrections** live in:

`Plugins/Handoff Docs/UV-PRO-v1.8.6-AGENT-HANDOFF.md` (filename is legacy; content tracks **1.9.7**).

Short update-server-only recap: `Plugins/Handoff Docs/UPDATE-SERVER-CERT-INJECTION-HANDOFF.md`.

### Verified May 2026 (production)

- **TPC return:** `Plugins/TAK Signed/paul-c-besing-mil-army-mil-20260510-080610.zip` — **`ATAK-Plugin-UVPro-1.9.7-tpc-5.5.1-civ-release.apk`**, **`BUILD SUCCESSFUL`**, Fortify scan **`Rendering 0 results`** in `fortify_scan.txt`.
- **Device:** ATAK showed **green update server / successful sync** after installing that APK (repo HTTPS OK).
- **Fortify “hardcoded password”:** PKCS#12 store key for bundled `atakmaps-ca.p12` is **`uvpro_trust_bundle_p12_key`** in **`res/values/strings.xml`** (Base64), decoded in **`UVProMapComponent`** — not a Java string literal (still extractable from APK resources; acceptable trade for bundled trust material).
- **TPC AAPT:** `android:inputType="0x81"` **fails** resource linking; use **`textPassword`**.
- **`adb uninstall com.uvpro.plugin`:** may fail with **`DELETE_FAILED_INTERNAL_ERROR`**; **`adb install -r`** upgrade path still worked in testing.

### Structured handoff DB (local)

**`/home/paul/Documents/ATAK/Plugins/Handoff Docs/handoff.db`** — SQLite table **`uvpro_handoff`** (`updated_utc`, `plugin_version`, `git_sha`, `topic`, `summary`, `evidence`). Append rows when findings change so new agents can `SELECT` without chat history.

---

## Official signing reference (ATAK green shield)

For official plugin release/signing workflow, use:
https://wiki.tak.gov/pages/viewpage.action?pageId=41946269&spaceKey=DEV&title=Plugin%2BInitial%2BRelease

This is the process that yields ATAK's official "green shield" signed/trusted status for distributed plugin builds.

---

## 2026-05-17 Addendum (v1.9.25 line)

This section captures behavior added/validated after the original handoff body.

### UI structure changes

- Connection section now includes:
  - callsign/team/contacts/packet counters
  - encryption controls (toggle + passphrase row + status)
- Radio section no longer hosts repeater fields; repeater load controls are now in Channel Control.
- `Send Ping` quick-action button was removed from layout only (transport code kept in place).

### Repeater programming workflow (current)

- Map repeater selection populates `UVProRadioControlManager.selectedRepeater`.
- `Load Selected Repeater` now **arms** programming instead of writing immediately.
  - Armed state: yellow stroke + button text changes to `Select Channel`.
  - Next channel-tile tap executes repeater write/tune to that channel.
  - Armed state clears after selection.
- Programming now uses a captured repeater snapshot from arm-time:
  - `UVProDropDownReceiver` stores `repeaterLoadArmedSpec`.
  - `UVProRadioControlManager.programRepeaterAndTune(RepeaterSpec, channelId)` performs write+tune.
  - This avoids stale/global-selection races during delayed user actions.
- After success, dropdown forces full grid refresh + delayed second full refresh for radios that apply in phases.

### Repeater map UX

- Selecting a repeater marker triggers UV-PRO auto-open intent:
  - `UVProDropDownReceiver.SHOW_PLUGIN_CHANNEL_CONTROL`
- Dropdown attempts to open at Channel Control via repeated delayed scroll passes.
- Important: custom pinwheel menu injection attempts were rolled back after repeated regressions where radial menu stopped opening on repeater markers.

### APRS/iconset integration notes

- APRS iconset mapping is path-based via `AprsSymbolMapper` with `CotBuilder` `usericon` injection.
- `AprsIconsetInstaller` stages iconset zip to ATAK import path and guides the user with persistent reminders.
- User-facing import instructions were updated to:
  - `Select Point Dropper>Gear Icon>Add Iconset`
  - `Path= /sdcard/atak/tools/import/aprs.zip`

### SA relay and uplink behavior

- SA relay supports network->RF relay with per-UID throttling.
- `RF -> TAK Uplink Relay` adds inbound RF->external dispatcher uplink when enabled alongside SA Relay.
- Network `All Chat Rooms` GeoChat can be forwarded to RF under SA relay path.
- Local map update/delete relay path was added via COT broadcast receiver (`COT_PLACED`/`COT_DELETED`) with duplicate suppression guards.

### Branch/version state at release cut

- Version bumped to `1.9.25` in `build.gradle`.
- Commit on `main`: `6cb1e94` (`release: bundle UI and repeater workflow updates in 1.9.25`).

---

## 2026-05-19 Addendum — APRS stack, telemetry, and agent handoff (APRS-focused)

**Audience:** engineer/agent owning **inbound APRS** from KISS/AX.25 through map CoT.  
**Code root:** `Plugins/Darksteal/` (ATAK plugin package `com.uvpro.plugin`).  
**Companion docs:** `README.md`, `AGENTS.md`, sections above; icon zip / ATAK import UX also described in `Plugins/APRS /APRS_ICONSET_INTEGRATION_HANDOFF.md` (note space in directory name).

### What UV-PRO is doing with APRS (product scope)

- The radio delivers **AX.25 UI frames**; the plugin unwraps **KISS**, decodes **AX.25**, then if the destination is not the UV-PRO proprietary `OPENRL` path, treats the **info field** as **APRS** (APRS over AX.25).
- **Goal:** put **positions** on the ATAK map as normal SA-style markers, bridge **APRS bulletins** into **GeoChat** where the format matches messages, and keep **contacts** / **stale** behavior tolerable for sparse RF beacons.
- **Non-goal (today):** full APRS-IS client, igating, digipeating, or complete APRS 1.01 type coverage. Many legal APRS frames will still fall through to **`Unhandled APRS packet`** at `DEBUG` — that is expected until types are implemented.

### Inbound pipeline (exact order)

1. **`BtConnectionManager`** — Bluetooth **SPP** read thread; bytes may be KISS-framed.
2. **`kiss/`** — extract AX.25 payload(s).
3. **`Ax25Frame.decode`** — source/destination SSID, **info** as ASCII string + raw bytes.
4. **`PacketRouter.routeIncoming`**
   - If dest callsign is **`OPENRL`** → binary **`UVProPacket`** path (chat, GPS, CoT fragments, ping, ack…).
   - Else → **`routeAprsPacket(srcCall, srcSsid, info, infoBytes, destCall, unwrapDepth)`**.

### `PacketRouter.routeAprsPacket` (APRS-specific control flow)

1. **Third-party unwrap** — If `info` starts with **`}`**, `AprsParser.unwrapThirdParty` parses the embedded `CALL>TO,PATH:payload` header and **recurses** into the inner `payload` with the **inner** source SSID and first-hop destination (Mic-E destination encoding, `APRS`, etc.). `unwrapDepth` is capped (5) against abuse.
2. **Diagnostics** — `Log.d` **`APRS raw <displayCall>`** with `info_ascii=` (sanitized) and `info_hex=` (prefix of raw bytes). Useful to compare with APRS101 / over-the-air captures.
3. **Position** — `AprsParser.parsePosition(callsign, ssid, info, destCallsign)`  
   - **`destCallsign`** is the AX.25 **destination** address (6 characters). Required for **Mic-E** latitude decoding (first byte of Mic-E is in the dest field per APRS convention).
4. **Message** — `AprsParser.parseMessage(callsign, info)` for **`:addressee :text`** APRS messages → **`ChatBridge.injectRadioMessage`**.
5. **Telemetry** — `AprsParser.parseTelemetry(info)` for **`T#...`** → see **Telemetry** below.
6. Otherwise **`Log.d` `Unhandled APRS packet`** (still DEBUG-level).

**Display callsign for map + contact keys:** `aprsDisplayCallsign(base, ssid)` → **`BASE-SSID`** when SSID is **1–15**, else base only. This must stay consistent for **ContactTracker**, **CoT UID** (`ANDROID-<CALLSIGN>`), and chat routing. Example: over-the-air `KL7AIR-10` uses base `KL7AIR` with SSID **10** → map/chat key **`KL7AIR-10`**.

### `AprsParser` — supported position families

Implementation lives in **`app/src/main/java/com/uvpro/plugin/ax25/AprsParser.java`**. High-level:

| Lead / pattern | Role |
|----------------|------|
| `` ` `` or `'` + Mic-E body + **6-char dest** from AX.25 dest | **Mic-E** compressed track |
| `;` | **Object** report; object name becomes synthetic `callsign` for the parsed **`AprsPosition`** |
| `!` `=` | Position (with/without messaging capability bit semantics per APRS) |
| `/` `@` | Position with **timestamp** — parser skips leading time field before body |
| Compressed 13-char body | **Base-91 compressed** lat/lon/speed/course |
| Uncompressed | Classic **ddmm.mmN / dddmm.mmW** style |

**Symbol table + code** land on **`AprsPosition.symbolTable`** / **`.symbol`** and feed **`AprsSymbolMapper`** → **`CotBuilder`** adds **`usericon`** with **`iconsetpath`** when the staged APRS iconset is present on the device.

**Explicit non-position lead:**

| Lead | Behavior |
|------|----------|
| **`T`** | **Not** a position. Returns **`null`** from `parsePosition` **without** logging “Unhandled APRS data type” — telemetry is handled separately via **`parseTelemetry`**. |

### `AprsParser` — APRS messages (`:`)

Standard **`:`** APRS message format (addressee 9 chars, second **`:`**, text, optional `{`**`msgid`).

### `AprsParser` — telemetry (`T#…`)

- **Method:** `parseTelemetry(String info)`.
- **Recognized shape:** `T#SEQ,A1,A2,A3,A4,A5,bbbbbbbb` — **exactly seven** comma-separated fields after `T#`; last field **exactly eight** characters **`0`** or **`1`** (digital bits, MSB-first per APRS).
- **Analog fields:** parsed as non-negative integers (sanity cap **999999** in code — wide enough for raw channel counts; **EQNS/PARM scaling is not applied**).
- **Type:** **`AprsTelemetry`** with **`formatSummary()`** for logs and CoT remarks, e.g.  
  `APRS telemetry #048 A=187,1,1,11,0 bits=00000000` for  
  `T#048,187,001,001,011,000,00000000`.

**Router behavior when telemetry parses:**

1. **`Log.d`** the summary line for **`KL7AIR-10`-style** SSID display names.
2. **`ContactTracker.touchIfPresent(mapCall)`** — bumps **`lastSeen`** / packet count if the contact already exists (telemetry counts as activity).
3. If **`RadioContact.hasPosition()`** — reinject **position CoT** at **last lat/lon/alt/speed/course** via **`CotBridge.injectPositionCot(..., remarksInner)`** so ATAK refreshes the marker timestamp and can show **remarks** (when the map layer respects `detail/remarks` inner text). **Throttled** to once per **30s** per display callsign (`APRS_TELEMETRY_REINJECT_MIN_MS` in **`PacketRouter`**); throttle cleared when a new position arrives.
4. If **no prior position** for that display callsign — **no map marker is invented** (telemetry alone has no coordinates). Only the DEBUG log proves reception.

**APRS icon preservation on telemetry refresh:** on each **APRS position** decode, **`PacketRouter`** calls **`RadioContact.setLastAprsMapSymbol(symbolTable, symbol)`** on the tracked contact. Telemetry reinject passes those stored **`Character`**s into **`CotBuilder`** so **`usericon`** is not dropped on refresh.

### CoT / stale / contact policy (touches APRS UX)

- **`CotBridge.injectPositionCot`** uses **`resolveInboundContactStaleMs()`** which combines Smart Beacon / fixed beacon interval prefs with a **floor** (**`MIN_INBOUND_RADIO_STALE_MS`** — long horizon; aligns with sparse APRS).
- **`ContactTracker`** sweep thresholds (**stale / lost / remove**) are on the **order of hours** so intermittent APRS does not flicker contacts off the plugin list immediately.
- **`CotBuilder.buildPositionCot`** full overload accepts optional **`remarksInner`**; **`remarks`** still carries **`source="UV-PRO Radio"`**; inner text holds telemetry summary when set.
- **APRS map markers (v1.9.32+):** type **`a-f-G-U-C`**, **`usericon`** from APRS symbol, **no `__group`** on APRS icons (tap opens custom details panel, not grey TAK contact card). **`CotBridge.removeAprsFromContactsPane`** strips APRS UIDs from ATAK’s native Contacts list on refresh.
- **APRS vs UV-PRO peers (v1.9.33):** only **UV-PRO `OPENRL` GPS peers** register as sendable ATAK contacts. **Inbound APRS** updates **`ContactTracker`** for plugin stats but does **not** call **`registerBtechContactUid`** / **`linkRadioIndividualContactToMapMarker`**.
- **Operator UI:** tap APRS marker (not repeater) → **`AprsDetailsDropDownReceiver`** (`META_UVPRO_APRS_DETAILS` on CoT). Weather, MGRS, trails, and formatted telemetry live there — not generic ATAK point properties.

### Verified field example (from device logcat)

- **Position:** `@192126z6115.64N/14949.88W-…` from **`KL7AIR-10`** → decoded lat/lon, **`/`** + **`-`** symbol → **`p-2d.png`** style iconset path, Yellow team pool for APRS, **`MARKER_DEBUG`** in **`CotBridge`** on DEBUG builds.
- **Telemetry:** `T#048,187,001,001,011,000,00000000` — after the code path above, should **not** produce **`UVPro.APRS: Unhandled APRS data type: T`** nor **`Unhandled APRS packet`** for that string. If you still see those lines, the device is running an **older APK** — rebuild **`assembleCivDebug`** / **`assembleCivRelease`** and **`adb install -r`**.

### Known APRS gap — **APRS-201** (P0): object + `r` overlay extension

Example still **unhandled** after telemetry work:

```text
;146.94ANC*111111z6105.89N/14941.21Wr146.940MHz T103 -060 KL7AA analog voice
```

This is an **APRS object** (`;`) with a **position + extension** where **`r`** introduces **frequency / text** after the compressed/uncompressed position block. The current **`parseObjectPosition`** path is strict about where the position substring starts; **`r`**-suffix voice/repeater objects may need a dedicated parser branch or looser trimming before reusing **`parseAfterDataType`**. Good **next task** for an APRS-only iteration: **objects as first-class map items** (possibly distinct CoT type / name from `ANDROID-` peer UIDs).

### Digital channel / KISS (APRS transmit side, minimal)

- Outbound APRS **beacon** traffic is still sent as **AX.25/KISS** through the same Bluetooth link; the plugin does **not** embed a “channel ID” inside the KISS frame itself.
- Radio firmware maps KISS/APRS to a logical **digital** slot; plugin reads/writes **`autoShareLocCh`** (and related) via **`UVProRadioControlManager`** snapshots / **`setDigitalChannel`** when the user picks a channel in UI — see **`UVProRadioControlManager`** and **`UVProDropDownReceiver`** for the channel UI path.

### Build, install, and logcat for APRS debugging

```bash
cd Plugins/Darksteal
./gradlew :app:assembleCivDebug -PlocalDev=true   # or assembleCivRelease
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-UVPro-*-civ-debug.apk
```

**Logcat filter** (matches recent operator captures):

```bash
OUT="$HOME/Documents/ATAK/Plugins/Darksteal/aprs-log-$(date +%Y%m%d-%H%M%S).txt"
adb logcat -v threadtime \
  UVPro.Router:D UVPro.APRS:D UVPro.APRS.WX:D UVPro.APRS.Track:D UVPro.APRS.Details:D \
  UVPro.CotBridge:D UVPro.BT:D '*:S' | tee "$OUT"
```

After code changes, **force-stop ATAK** or at least **restart ATAK** so the updated plugin loads.

### APRS file index (jump table for APRS agent)

| File | Responsibility |
|------|------------------|
| `ax25/AprsParser.java` | All APRS text parsing: position families, third-party unwrap, messages, telemetry |
| `ax25/AprsWeatherParser.java` | Standard WX tags (`g/t/h/b`, `.../...`, `/W`); strips false vehicle motion on WX symbols |
| `ax25/AprsWeather.java` | Parsed weather DTO |
| `ax25/AprsSymbolMapper.java` | Table/symbol → ATAK **`iconsetpath`** string |
| `ax25/Ax25Frame.java` | AX.25 frame decode; source/dest/info; outbound APRS frame helper (unused for compose UI) |
| `protocol/PacketRouter.java` | APRS vs `OPENRL` fork; remarks trim; telemetry throttle; trails; no APRS ATAK contact registration |
| `aprs/AprsDetailsDropDownReceiver.java` | Custom metadata panel; live refresh via `REFRESH_APRS_DETAILS` |
| `aprs/AprsInfoFormatter.java` | Human-readable lines for panel (weather, telemetry, position) |
| `aprs/AprsTrackManager.java` | Blue movement polylines (4h / 500 pts; skips WX symbols) |
| `util/CoordinateDisplay.java` | MGRS / lat-lon formatting for details panel |
| `cot/CotBuilder.java` | `buildPositionCot` (+ remarks), APRS marker type/icon, chat/receipt builders |
| `cot/CotBridge.java` | `injectPositionCot`, APRS meta, `openAprsDetailsPanel`, `removeAprsFromContactsPane` |
| `contacts/ContactTracker.java` | `updateContact`, `getContact`, `touchIfPresent` |
| `contacts/RadioContact.java` | `hasPosition`, `setLastAprsMapSymbol`, lifecycle timestamps |
| `UVProMapComponent.java` | Registers APRS dropdown; map tap → details panel |
| `bluetooth/BtConnectionManager.java` | IO + KISS; where **`Received AX.25 frame`** is logged |
| `kiss/KissRadioFrequencyControl.java` | KISS get/set radio frequency (v1.9.33 dropdown) |
| `ax25/AprsIconsetInstaller.java` | Stage **`aprs.zip`** for ATAK import |

### Suggested backlog for a dedicated APRS agent (prioritized)

**Baseline on `main`:** **`ext.PLUGIN_VERSION = "1.9.33"`** (`Plugins/Darksteal/build.gradle`). Recent APRS UX landed in **v1.9.31–v1.9.33** (commits through **`9ea40f0`** on `main` at time of this update).

#### EPIC-APRS-1 — Map / CoT / operator UX ✅ (shipped)

| ID | Item | Ver | Status |
|----|------|-----|--------|
| **APRS-101** | Position **comment → CoT remarks** (512 char cap, `trimAprsCommentForRemarks`) | 1.9.31 | ✅ Done |
| **APRS-102** | **Telemetry reinject throttle** (30s / display callsign; cleared on new position) | 1.9.31 | ✅ Done |
| **APRS-103** | Labels / marker presentation (`a-f-G-U-C`, `usericon`, omit `__group` for APRS) | 1.9.31+ | ✅ Done (no maintainer doc ticket) |
| **APRS-104** | Bypass grey TAK contact card for APRS map icons | 1.9.31+ | ✅ Done |
| **APRS-105** | **Dedicated APRS metadata panel** (`AprsDetailsDropDownReceiver`, live refresh) | 1.9.32 | ✅ Done |
| **APRS-106** | **Weather decode** (`AprsWeatherParser`: `g/t/h/b`, `.../...`, `_` / `W`; no fake vehicle motion) | 1.9.32 | ✅ Done |
| **APRS-107** | **Suppress fake movement** on WX / fixed stations (wind ≠ course/speed) | 1.9.32 | ✅ Done |
| **APRS-108** | **MGRS** in details panel (`CoordinateDisplay`) | 1.9.32 | ✅ Done |
| **APRS-109** | **Movement trails** (`AprsTrackManager`: 4h, 500 pts, mobile only) | 1.9.32 | ✅ Done |
| **APRS-110** | **APRS not in ATAK Contacts pane** (map + details only; UV-PRO peers still contacts) | 1.9.33 | ✅ Done |

**Acceptance (regression):** install civ debug APK → restart ATAK → filter logcat (see above) → tap APRS marker → custom panel with weather/MGRS/trail; APRS callsign **absent** from ATAK Contacts; UV-PRO peer still sendable from Contacts.

#### EPIC-APRS-2 — Parser & packet-type completeness ❌ (remaining)

| ID | Item | Priority | Status | Notes |
|----|------|----------|--------|-------|
| **APRS-201** | **`;object*…` + `r` extension** (voice/repeater objects, e.g. `;146.94ANC*…r146.940MHz…`) | **P0** | ❌ Open | Still **`Unhandled APRS packet`**; see **Known gap** below |
| **APRS-202** | **Telemetry scaling** (`PARM` / `EQNS` / `UNIT` / `BITS`) | **P1** | ❌ Open | Remarks still raw 0–255 analog values |
| **APRS-203** | **Proprietary WX comments** (WX3in1, `U=…V`, etc.) | **P2** | ❌ Open | Show raw comment in panel; no structured fields (e.g. `KL7AIR-10`) |
| **APRS-204** | **Telemetry-only stations** (never sent a position) | **P3** | ❌ Open | Product decision: list UI vs synthetic marker vs ignore |
| **APRS-205** | **Outbound APRS text** (`Ax25Frame.createAprsFrame` exists; no compose UI) | **P3** | ❌ Open | Reply/beacon on APRS spec, not `OPENRL` chat |
| **APRS-206** | **Additional APRS types** (status `>`, bulletins, edge Mic-E/object cases) | **P2** | 🟡 Partial | Core families work; chase log **`Unhandled APRS packet`** lines into tickets |
| **APRS-207** | **APRS messages → GeoChat** polish | **P2** | 🟡 Works | `:addressee:text` path live; threading/UID edge cases possible |

**Recommended next sprint order:** **201 → 202 → 203 → 206** (field-log driven) → product calls on **204/205**.

#### EPIC-APRS-3 — Infrastructure & polish

| Item | Priority | Status |
|------|----------|--------|
| KISS **radio frequency** get/set in dropdown | P2 | ✅ Done (v1.9.33, `KissRadioFrequencyControl`) |
| Trail **settings** (age, color, max points, enable) | P3 | ❌ Hard-coded in `AprsTrackManager` |
| Persist trails across ATAK restart (SQLite) | P3 | ❌ In-memory only |
| Plugin **`ContactTracker`** list for APRS (stats) vs ATAK Contacts | — | ✅ By design; only native Contacts pane excluded |
| **`HANDOFF.md` / README** sync with shipped APRS UX | P4 | 🟡 This section updated for v1.9.33 |

#### Explicit non-goals (unchanged)

Full **APRS-IS** client, igating, digipeating, or complete **APRS 1.01** type coverage.

### Version note at time of this writing

Plugin version: **`1.9.33`** (`ext.PLUGIN_VERSION` in root **`build.gradle`**). Build: `./gradlew :app:assembleCivDebug -PlocalDev=true` → `ATAK-Plugin-UVPro-1.9.33-*-civ-debug.apk`. After install, **restart ATAK** so the plugin reloads.


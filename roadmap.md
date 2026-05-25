# TAK-MESHCORE Roadmap and Handoff

## Goal

Build a dedicated ATAK plugin (`TAK-MESHCORE`) that keeps the proven UV-PRO ATAK integration logic (contacts/chat/CoT bridges) while replacing RF transport with MeshCore BLE Companion transport.

Primary requirement: carry all ATAK payloads through existing MeshCore infrastructure in a way that is robust, bandwidth-aware, and not obvious/plain ATAK traffic on-air.

---

## Findings So Far (Cited)

### 1) MeshCore BLE transport is Nordic UART Service over GATT

MeshCore Companion docs specify:

- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX char (app writes): `...0002`
- TX char (firmware notifies): `...0003`
- Connection sequence: connect -> discover RX/TX -> enable TX notifications -> then send commands.
- Recommendation: write with response, command queue, one command at a time.
- MTU recommendation: request `512`.

Source:
- `https://docs.meshcore.io/companion_protocol/`
- Local snapshot: `/home/paul/.cursor/projects/home-paul-Documents-ATAK/agent-tools/c9cf0130-84bf-447b-a0d7-6948016f6833.txt` (BLE section, command sequencing, MTU section)

Firmware source confirms these UUIDs and security behavior:
- `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/esp32/SerialBLEInterface.cpp`
- `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/nrf52/SerialBLEInterface.cpp`

Notable confirmation from firmware source:
- BLE UART UUIDs match docs (ESP32 file).
- Security is MITM + PIN/bonding (`setStaticPIN`, `...ENC_MITM` permissions).

### 2) Companion command/data model relevant to plugin transport

From companion docs:

- Channel text send command: `0x03` (`CMD_SEND_CHANNEL_MESSAGE`)
  - Format includes channel index and timestamp, then UTF-8 message.
- Binary datagram command: `0x3E` (`CMD_SEND_CHANNEL_DATA_DATAGRAM`)
  - Includes `data_type` (LE16), channel index, binary payload.
  - `0xFFFF` (`DATA_TYPE_DEV`) is explicitly a developer namespace.
  - Max payload is `163` bytes.
- Message retrieval command: `0x0A` (`CMD_GET_MESSAGE`)
- Push notification: `0x83` (`PACKET_MESSAGES_WAITING`)

Sources:
- `https://docs.meshcore.io/companion_protocol/`
- Local snapshot: `/home/paul/.cursor/projects/home-paul-Documents-ATAK/agent-tools/c9cf0130-84bf-447b-a0d7-6948016f6833.txt` (command sections for `0x03`, `0x3E`, `0x0A`, packet table)

### 3) Upstream MeshCore serial frame size

Upstream serial interface uses:
- `MAX_FRAME_SIZE 172`

Source:
- `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/BaseSerialInterface.h`

This helps align chunk sizing assumptions for tunneled payload design.

---

## Current State in This Repo

Repository:
- `/home/paul/Documents/ATAK/Plugins/MeshcoreAtak`
- GitHub remote: `https://github.com/atakmaps/TAK-MESHCORE`

Current progress:
- `BtConnectionManager` has been replaced with BLE-based MeshCore transport logic while preserving legacy method names (`sendKissFrame`, `sendRawBytes`) used across existing UV-PRO code.
- File:
  - `/home/paul/Documents/ATAK/Plugins/MeshcoreAtak/app/src/main/java/com/uvpro/plugin/bluetooth/BtConnectionManager.java`
- Compile validation performed successfully with:
  - `./gradlew assembleCivDebug -PlocalDev=true`

Current shim behavior:
- AX.25 bytes are split/chunked and wrapped into text envelope:
  - `UVAX1|msgId|seq|total|base64chunk`
- Envelope is sent through MeshCore channel message command (`0x03`).
- Incoming channel/contact messages are parsed; matching `UVAX1|...` payloads are reassembled into bytes and forwarded to existing router (`PacketRouter.routeIncoming(...)`).

This is an integration bridge to unblock progress, not the final transport design.

---

## Technical Plan: Transport Architecture

### Target architecture

1. **BLE link/session layer**
   - Own GATT lifecycle (scan, connect, discover, subscribe, reconnect).
   - Command queue with write-with-response discipline.
   - Keepalive/poll loop via `0x0A` and push-triggered drains (`0x83`).

2. **MeshCore payload layer (new)**
   - Preferred mode: `0x3E` datagram + `DATA_TYPE_DEV (0xFFFF)` for binary transport.
   - Fallback mode: `0x03` text envelope where datagrams are unsupported or unavailable.

3. **ATAK tunnel codec**
   - Encapsulate existing internal payloads (currently AX.25/TLV path) behind a transport codec.
   - Chunking/reassembly with msg IDs and per-message timeout.
   - Duplicate suppression and replay window.

---

## Obfuscation Strategy for ATAK Traffic

Objective: make ATAK-over-MeshCore payloads blend into MeshCore transport and avoid human-readable/obvious CoT semantics.

### Phase A (already partially implemented)
- Convert binary frames to compact Base64 text envelopes for `0x03` channel message transport.
- This removes plain XML/CoT visibility but still leaves recognizable envelope prefixes.

### Phase B (planned, preferred)
- Move to `0x3E` datagram transport (`DATA_TYPE_DEV`), binary payloads.
- Envelope format (binary):
  - version, flags, msg_id, seq, total, payload_len, payload, integrity tag.
- This removes dependency on visible text chat payload format.

### Phase C (hardening)
- Obfuscation/encryption pipeline:
  1. compress payload (optional, size-aware),
  2. encrypt payload (AES-GCM via existing plugin secret infrastructure),
  3. apply chunk envelope,
  4. transport over datagram or text fallback.
- Result: payloads are opaque and non-semantic in-transit, while remaining inside MeshCore’s existing channel infrastructure.

Note: final "obfuscation" strength depends on key handling and whether encryption is enabled by operator policy.

---

## Roadmap (Execution Plan)

### Phase 1 - Stabilize BLE Companion Baseline (short)
- [ ] Add explicit docs/settings for MeshCore channel index in plugin preferences.
- [ ] Improve BLE state telemetry in UI (connected/notif-enabled/queue-depth/last-packet time).
- [ ] Add reconnect backoff sanity and stale queue pruning.
- [ ] Field test matrix: connect/disconnect/reconnect on at least 2 device models.

### Phase 2 - Transport Abstraction (medium)
- [ ] Introduce clear transport interface (MeshCore transport class + codec class).
- [ ] Remove transport logic from monolithic `BtConnectionManager`.
- [ ] Keep external API compatibility for existing callers (`ChatBridge`, `CotBridge`, APRS helpers).

### Phase 3 - Datagram-first data plane (core)
- [ ] Implement `0x3E` datagram send path with `DATA_TYPE_DEV`.
- [ ] Discover/validate corresponding receive parsing behavior from real node traffic.
- [ ] Keep `0x03` text envelope as fallback mode.
- [ ] Add automatic mode negotiation or manual toggle for fallback.

### Phase 4 - Security and obfuscation hardening
- [ ] Add binary envelope versioning + integrity verification.
- [ ] Enforce encrypted mode for ATAK payload tunnel where required.
- [ ] Add anti-replay/duplicate suppression cache.
- [ ] Add telemetry for drop reasons (oversize, auth fail, timeout, decode fail).

### Phase 5 - End-to-end ATAK validation
- [ ] PLI/beacon path
- [ ] GeoChat DM/group
- [ ] CoT marker send/receive
- [ ] Fragmentation/reassembly under weak link conditions
- [ ] Long-run soak test (disconnect storms, queue pressure, stale sessions)

---

## Handoff Notes for Next Agent

Start points:
- BLE transport shim:
  - `app/src/main/java/com/uvpro/plugin/bluetooth/BtConnectionManager.java`
- Existing payload flow:
  - `app/src/main/java/com/uvpro/plugin/protocol/PacketRouter.java`
  - `app/src/main/java/com/uvpro/plugin/chat/ChatBridge.java`
  - `app/src/main/java/com/uvpro/plugin/cot/CotBridge.java`

Key caution:
- Current design preserves old method names (`sendKissFrame`) for compatibility. Do not blindly delete these without updating all callers.

Immediate high-value next step:
- Implement and validate `0x3E` datagram receive/send behavior against a live MeshCore node, then shift tunnel to datagram-first.

---

## Open Questions / Risks

1. **Datagram receive path visibility**
   - Docs specify send command (`0x3E`) but practical receive packet mapping for custom dev datagrams must be confirmed on-device.

2. **Message length limits and fragmentation**
   - Text mode has practical message limits (`~133` chars in docs). Fallback chunking must remain strict.

3. **Interoperability**
   - Some nodes/firmware variants may differ in push/queue timing or command response behavior.

4. **Security UX**
   - BLE pairing/PIN workflows vary by Android version and vendor stack.

---

## References

- MeshCore Companion Protocol docs:
  - `https://docs.meshcore.io/companion_protocol/`
- Local companion protocol snapshot used during implementation:
  - `/home/paul/.cursor/projects/home-paul-Documents-ATAK/agent-tools/c9cf0130-84bf-447b-a0d7-6948016f6833.txt`
- Upstream MeshCore firmware code:
  - `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/esp32/SerialBLEInterface.cpp`
  - `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/nrf52/SerialBLEInterface.cpp`
  - `/home/paul/Documents/ATAK/MeshCore-upstream/src/helpers/BaseSerialInterface.h`

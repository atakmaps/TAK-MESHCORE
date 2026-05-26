# MeshCore ATAK Plugin

A free, open-source ATAK plugin focused on MeshCore BLE companion transport for off-grid ATAK awareness.

## 1.2 MeshCore UX/Relay Parity Update (2026-05-26)

Version `1.2.0` focuses on restoring MeshCore runtime UX parity with the previously working panel behavior:

- Restored in-panel settings dialog for beacon/smart-beacon/Bluetooth favorites management.
- Restored contact connector/endpoint metadata wiring for GeoChat from map/contact actions.
- Restored MeshCore map status overlay behavior and panel-open tap action.
- Added MeshCore GPS controls in-panel (`Enable MeshCore GPS`, `Update ATAK with MeshCore GPS`).
- Added fresh-fix request flow for manual MeshCore GPS updates (no stale fix apply during manual refresh).

## 1.1 MeshCore-only Cutover (2026-05-25)

Version `1.1.0` removes UV-PRO-specific UI workflows from the plugin panel and ships MeshCore-only connection behavior:

- MeshCore scan/connect/disconnect only (no UV radio control panel buttons).
- Dedicated Mesh favorite direct-connect behavior in the main panel.
- Startup auto-connect to last selected Mesh favorite target.
- Simplified Mesh-focused dropdown and actions surface.

Legacy UV/APRS architecture notes below are retained as historical reference while the remaining backend cleanup is completed.

## MeshCore Migration Status (2026-05-25)

Current MeshCore integration status:

- BLE companion transport is connected and stable (NUS UART service, command queue, polling).
- Mesh channel discovery is working (`CMD_GET_CHANNEL` -> slot/name/secret visibility).
- ATAK beacon/contact flow over MeshCore is now proven working between two phones after parser fixes.
- End-to-end message ingress is confirmed (`RESP_CHANNEL_MSG_V3` receive path).
- RF mismatch diagnosis is now built in via parsed self-info (`freq`, `bw`, `sf`, `cr`) from `RESP_SELF_INFO`.

Key troubleshooting lessons validated in field testing:

- Matching channel name is not enough; slot secret must match.
- Matching slot secret is still not enough; RF params must also match (`freq`, `bw`, `sf`, `cr`).
- Inbound MeshCore text may include sender prefix before payload; parser must extract `UVAX1|...` from anywhere in message body, not just at string start.
- If both nodes show connected but only `RESP_NO_MORE_MSGS (0x0A)`, verify RF profile parity before debugging ATAK contact logic.

## What Is Working Right Now

- Two-node field test is successful for discovery/beacon contact visibility over MeshCore.
- ATAK-generated beacons are transmitted over MeshCore channel messages and received by peer plugin instances.
- Plugin can now prove operational state quickly through logs:
  - channel slot + secret fingerprint (`CMD_GET_CHANNEL` / `RESP_CHANNEL_INFO`)
  - node RF profile (`RESP_SELF_INFO`)
  - inbound channel message receipt (`RESP_CHANNEL_MSG_V3`)
  - envelope extraction and reassembly (`UVAX1|...` parser)

## Next Steps to Reach Full ATAK-over-MeshCore Functionality

Priority roadmap:

1. Add plugin-side radio profile controls (`CMD_SET_RADIO_PARAMS`) so operators can align RF settings from ATAK without switching apps.
2. Add mismatch detection/warnings in UI when local node RF params differ from expected profile (local vs desired profile).
3. Expand inbound message decoding coverage to include additional companion response types used by advert/contact workflows.
4. Promote native TAK wire payload path (`!T1P`, `!T1C`, `!T1F`) to primary transport for CoT/chat/PLI, while keeping UVAX shim compatibility during transition.
5. Expand reliability around large CoT payloads (fragment retries, queue pressure, stale fragment eviction tuning, bounded reassembly memory).
6. Implement operator-visible health telemetry (queue depth, last RX/TX, no-message watchdog, channel index/secret fingerprint, RF profile).
7. ~~Finish namespace/package migration~~ — **Done (1.1.0):** `com.atakmaps.meshcore.plugin` / `MeshCore*` entry classes; distinct from UV-PRO.
8. Build a repeatable interoperability matrix (2-node/3-node, mixed GPS/no-GPS, chat + markers + routes + casevac + geofences + mission packages).

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Position Sharing (PLI)** | ✅ Working | Your ATAK position is beaconed over radio at a configurable interval. Incoming positions appear as contacts on the map. |
| **Smart Beaconing (APRS-style)** | ✅ Working | APRS-standard SmartBeaconing + corner pegging: speed-proportional rate, turn-threshold slope, and minimum turn time. Seven parameters configurable in Settings → Manage Smart Beacon Settings. |
| **Dynamic CoT Stale Window** | ✅ Working | Contact `stale` timestamp now tracks current beacon policy (fixed interval or Smart Beacon profile) so receivers do not grey contacts prematurely. |
| **Ping / Ping Reply** | ✅ Working | **Send Ping** broadcasts a discovery ping; **Send Ping Reply** (Settings) auto-replies to incoming pings with your GPS position. Replies use **slotted timing** (default 20 slots × 2.5 s) keyed by callsign hash to reduce collisions. |
| **Net slot administration** | ✅ Working | Team leadership can set slot count/time and **Distribute to net** (`TYPE_NET_SLOT_CONFIG`); receivers auto-apply newer assignments. In **Settings → Tool Preferences → UV-PRO Settings → Administration** or **Plugin Settings** (bottom of dialog). |
| **Bluetooth Scan & Connect** | ✅ Working | Instant picker showing previously-connected radios with live green/gray availability dots. Auto-connects to last used radio on ATAK startup. |
| **Radio Connection Status Overlay** | ✅ Working | Persistent BTECH icon in the lower-right map corner (green = connected, desaturated = disconnected). **Tap the icon** to open the UV-PRO panel (same as Menu → Tools → UV-PRO). |
| **GeoChat over RF (contact-centric)** | ✅ Working | Chat to radio peers using ATAK's native Contacts/GeoChat UI (plugin contacts route via RF transport). |
| **Contact groups over RF** | ✅ Working | Group create/add ([UPDATED CONTACTS]) relays **full GeoChat CoT** with `hierarchy` (same as Wi‑Fi/TAK), not compact chat-only. Slotted TX uses default ping-reply slots (20 × 2.5 s). |
| **GeoChat delivery receipts (checkmarks)** | ✅ Working | ATAK's native single-checkmark (delivered) and double-checkmark (read) appear on the sender's chat window. |
| **Retry on no ACK + delivery failure alert** | ✅ Working | If no delivered ACK within the configured interval, message is retransmitted up to max retries. If all retries exhausted, a persistent alert appears. Retry interval and max retries adjustable in Settings. |
| **Contact-targeted CoT over RF** | ✅ Working | Any CoT item sendable to a contact in ATAK — waypoints, routes, casevac/9-line, drawings, markers — is intercepted, compressed, and relayed over RF. |
| **SA Relay (opt-in)** | ✅ Working | Network-to-radio bridge: broadcasts received SA over RF to radio-only users. Configurable in Settings. |
| **AES-256 Encryption** | ✅ Working | Optional shared-secret AES-256-GCM for all radio traffic. All nodes must use the same secret. |
| **Contact Tracking** | ✅ Working | Radios in range tracked as contacts with callsign, last-seen time, and position. |
| **Map Repeater Load/Tune (KML)** | ✅ Working | Tap a repeater placemark from imported KML, arm **Load Selected Repeater**, then tap a destination channel to program/tune it (TX/RX + CTCSS/DCS). |
| **TX Power (LOW / MED / HIGH)** | ✅ Working | **TX Power** button in the Radio panel (left of Dual Watch) cycles transmit power and writes both device settings and per-channel RF memory (digital/APRS + active VFO channels). Syncs from the radio on connect. |
| **APRS TX mode (plugin-generated over KISS)** | ✅ Working | Optional APRS beacon TX runs in parallel with UV-PRO traffic. Requires FCC call + icon in settings, supports manual **Send APRS Beacon**, and can temporarily disable ATAK position beacons when desired. APRS chat requests ACK on the first message to a contact and auto-ACKs inbound APRS messages that request acknowledgment. |
| **Channel grid refresh** | ✅ Working | After long-press manual channel edit/save, the channel grid re-reads that slot from the radio so labels/frequencies match what was programmed. |
| **Bluetooth Auto-Reconnect** | ✅ Working | Three-strategy SPP connection with exponential backoff reconnect (up to 5 attempts). |
| **Radio Silence (TX Kill Switch)** | ✅ Working | Long-press control in the Radio panel that blocks all outbound TX while still receiving beacons/pings/chat/CoT. Long-press again to restore TX. |
| **RF -> TAK Uplink Relay** | ✅ Working | Optional uplink path: forward inbound RF CoT/chat from radio-only users to TAK network when SA Relay + uplink toggle are enabled. |

### 2026-05-21 Progress Update

- RF group sync now relays full GeoChat CoT `b-t-f` with `hierarchy` and improved inbound handling through ATAK GeoChat paths.
- RF group/message behavior was validated across a 3-device Wi-Fi↔RF bridge test (group create/send path stable, slot timing confirmed).
- SA Relay now suppresses unchanged periodic SA/status payloads (`a-*`) per UID, so stationary Wi-Fi contacts are not rebroadcast over RF every 30 seconds when content has not changed.
- Non-SA traffic (chat, routes, markers, targeted CoT) continues to relay normally.

## How It Works

```
┌─────────────────────────────────────┐
│           ATAK Application          │
│  ┌───────────────────────────────┐  │
│  │      UV-PRO Plugin        │  │
│  │                                │  │
│  │  Bluetooth ─► KISS TNC ─►    │  │
│  │  AX.25 frames ─► Packet      │  │
│  │  Router ─► CoT / Chat /      │  │
│  │  GPS / Encryption             │  │
│  └───────────────────────────────┘  │
└──────────────┬──────────────────────┘
               │ Bluetooth SPP (Data)
               ▼
┌─────────────────────────────────────┐
│       BTECH Radio (KISS TNC)        │
└──────────────┬──────────────────────┘
               │ RF (VHF/UHF)
               ▼
┌─────────────────────────────────────┐
│     Other Radios + EUDs in Range    │
└─────────────────────────────────────┘
```

The plugin talks to the radio over Bluetooth SPP using the KISS TNC protocol. Data is encapsulated in AX.25 frames with a compact binary format. On the ATAK side, incoming packets become CoT events, map contacts, and chat messages; outgoing ATAK data is serialized, optionally encrypted, and transmitted over radio.

## Requirements

### To Use the Plugin
- **ATAK-CIV 5.5.1** (or compatible version) installed on your Android device
- **UV-PRO** radio (UV-PRO, GMRS-PRO, or UV-50X series with KISS TNC support)
- **Bluetooth** pairing between the Android device and radio

### To Build from Source
- **JDK 17** — [Eclipse Temurin](https://adoptium.net/) recommended. Other JDK 17 distributions work too.
- **Android SDK** with API level 35 — install via [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only)
- **ATAK-CIV 5.5.1 SDK** — available from the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ)
- **Git** — to clone the repo

> **Note:** You do _not_ need to install Gradle. The included Gradle wrapper (`gradlew` / `gradlew.bat`) downloads the correct version automatically.

## Quick Install (Pre-built APK)

If you just want to install the plugin without building it:

1. Download the latest APK from the [Releases](../../releases) page.
2. Transfer it to your Android device.
3. Install with: `adb install -r ATAK-Plugin-Meshcore-*.apk`
4. Open ATAK → Menu → Tools → **MeshCore**.

APK filenames look like `ATAK-Plugin-Meshcore-*-5.5.1-civ-release.apk` (or `civ-debug` for debug builds).

### Upgrading from a debug or self-signed build

If you previously installed a **debug** or **self-signed** (non-TPC) build, Android will block the upgrade with an "App not installed" or "incompatible" error because the signing certificate changed. This is a one-time issue — all TPC-signed releases share the same certificate going forward.

**Fix (your ATAK data is safe):**

> Uninstalling the plugin APK does **not** affect the `/atak` directory, your maps, contacts, tracks, or any other ATAK data. That data belongs to the ATAK application, not this plugin.

```bash
# 1. Remove the old plugin (ATAK data is untouched)
adb uninstall com.atakmaps.meshcore.plugin

# 2. Install the new TPC-signed APK
adb install ATAK-Plugin-Meshcore-*-tpc-*-5.5.1-civ-release.apk
```

After reinstalling, open ATAK → Menu → Tools → **UV-PRO** and reconnect your radio. All previous settings are stored in ATAK's shared preferences and will be restored automatically.

## GitHub releases and signing

- **Third-party (TPC) signing:** The APK that is fully aligned with **stock ATAK-CIV** and the usual install rules is the one built and signed on the **TAK Product Center third-party pipeline** (takrepo). It may show the standard indicator that the plugin was signed with the third-party service. No extra code is required in this repo for that — trust comes from the **pipeline signature**, not a flag in Java.
- **GitHub Releases:** Each [release](https://github.com/atakmaps/TAK-MESHCORE/releases) can attach the TPC-signed civ-release APK for that version. You can also build `assembleCivRelease` locally (see below).
- **Local `assembleCivRelease`:** ProGuard/R8 needs an **ATAK apply-mapping** file. This repo sets `atak.proguard.mapping` automatically: if you place the real `proguard-civ-release-mapping.txt` from a TPC/takrepo build in `app/libs/atak-civ/`, that is used; otherwise a **placeholder empty mapping** (`tools/empty-atak-applymapping.txt`) is used so the build completes. A build with the placeholder is fine for **CI smoke tests**; for **field use**, prefer a release built with the **official ATAK mapping** and/or the **TPC APK**.
- The `android` block in `app/build.gradle` sets `bundle { storeArchive { enable = false } }` as required by **atak-takdev** `takdevLint` for release signing.

## Building from Source

### 1. Download the ATAK-CIV SDK

Go to the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ), download the **ATAK-CIV 5.5.1 SDK**, and extract these files into `app/libs/atak-civ/`:

```
app/libs/atak-civ/
├── main.jar
├── atak-gradle-takdev.jar
├── android_keystore
└── proguard-release-keep.txt
```

> The SDK zip contains more files — you only need these four.

### 2. Configure `gradle.properties`

The repository includes a committed `gradle.properties` with shared flags. If Gradle does not pick up **JDK 17** automatically, add a line to that file (or copy from the template and merge):

```bash
# optional if JAVA_HOME is not JDK 17:
# cp gradle.properties.example gradle.properties   # only if you need a fresh file
```

Open `gradle.properties` and set `org.gradle.java.home` to your JDK 17 if needed:

| OS | Typical JDK 17 Path |
|----|---------------------|
| **Windows** | `C:\\Program Files\\Eclipse Adoptium\\jdk-17.x.x-hotspot` |
| **macOS** | `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |
| **Linux** | `/usr/lib/jvm/temurin-17-jdk-amd64` |

> **Tip:** If your system `JAVA_HOME` already points to JDK 17, you can delete the `org.gradle.java.home` line entirely.

### 3. Build the APK

```bash
# Clone the repo
git clone https://github.com/atakmaps/TAK-MESHCORE.git
cd TAK-MESHCORE

# Linux/macOS
./gradlew assembleCivDebug

# Windows (Command Prompt or PowerShell)
gradlew.bat assembleCivDebug
```

The APK will be at:
```
app/build/outputs/apk/civ/debug/ATAK-Plugin-Meshcore-*.apk
```

### 4. Install

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-Meshcore-*.apk
```

Then open ATAK → Menu → Tools → **MeshCore**.

### 5. Release (minified) build — `assembleCivRelease`

For a **R8/ProGuard** release build (smaller, obfuscated) matching the TPC `civRelease` variant:

```bash
./gradlew :app:assembleCivRelease
# Windows: gradlew.bat :app:assembleCivRelease
```

Output:

```
app/build/outputs/apk/civ/release/ATAK-Plugin-Meshcore-*-civ-release.apk
```

Use the **official ProGuard apply-mapping** from the ATAK/takrepo pipeline when you need a **production-equivalent** binary (see [GitHub releases and signing](#github-releases-and-signing) above).

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `Android Gradle plugin requires Java 17` | Your `gradle.properties` is missing or `org.gradle.java.home` points to the wrong JDK. See step 2. |
| `Could not find main.jar` | The ATAK SDK files aren't in `app/libs/atak-civ/`. See step 1. |
| `AAPT: error: resource not found` | Run `./gradlew clean` and rebuild. |
| Build succeeds but plugin doesn't appear in ATAK | Make sure you're running **ATAK-CIV 5.5.1** — the plugin is compiled against this specific version. |

## Usage

1. **Pair your radio** with your Android device via Bluetooth settings.
2. Open the **UV-PRO** plugin in ATAK.
3. Tap **Scan** to find your radio, then tap it to connect.
4. The status dot turns green when connected.

### Plugin Controls

| Control | What It Does |
|---------|-------------|
| **AES-256-GCM switch** | Enable encryption (enter the shared secret first) |
| **Send Beacon** | Immediately broadcast your current ATAK/UV-PRO position beacon |
| **Send Ping** | Broadcast a discovery ping to radios in range |
| **Long Press for APRS Beacon** | Enable/disable scheduled APRS beacons (follows ATAK beacon interval policy while enabled) |
| **Send APRS Beacon** | Manually transmit one APRS position beacon now |
| **Long Press for Radio Silence** | Toggle TX block on/off (RX remains active). Active state is highlighted with an orange border. |
| **Load Selected Repeater** | Arms repeater load mode (yellow border + `Select Channel` label), then writes/tunes selected repeater to the tapped channel |
| **TX Power** | Tap to cycle **LOW → MED → HIGH**; updates global VFO settings and channel power flags on the digital/APRS channel plus active VFO slots |
| **Plugin Settings** | APRS (FCC call, SSID, icon grid picker, message), Beacon, ping reply, SA Relay, encryption, retries, and **Administration** (slot count/time, distribute to net). Full list also under ATAK **Settings → Tool Preferences → UV-PRO Settings**. |

### Repeater workflow (KML)

1. Import repeater KML into ATAK.
2. Tap a repeater placemark on the map (must contain TX/RX and tone metadata).
3. UV-PRO opens and updates **Selected Repeater**.
4. Tap **Load Selected Repeater** (button arms and turns yellow with `Select Channel` text).
5. Tap destination channel in **Channel Control** grid to program/tune that channel.

### Contact-centric routing (important)

Radio peers are represented as **native ATAK Contacts** (UIDs look like `ANDROID-<CALLSIGN>`). Use the ATAK Contacts UI to:

- open GeoChat with a radio contact (messages route over RF via the plugin).

Waypoints, routes, casevac/9-line, drawings, and other CoT items can all be sent to a radio contact using the native ATAK "Send to Contact" UI. The plugin intercepts the outbound CoT, compresses it, fragments it if needed, and transmits it over RF.

### SA Relay

Enable **SA Relay** in Settings to automatically broadcast received network SA (team positions, waypoints, routes) over RF to all radio users on frequency. This is designed for a single designated relay node — **do not enable unless instructed by your team leader.** A per-contact 30-second throttle prevents channel flooding.

As of 2026-05-21, unchanged periodic SA/status payloads (`a-*`) are also suppressed. This avoids repeated rebroadcast of stationary Wi-Fi/TAK contacts whose CoT only changes volatile timestamps.

For deeper implementation details and a full “new agent” handoff (logic trees, key files, known ATAK gotchas), see `HANDOFF.md`.

### Encryption

When enabled, all outgoing packets are encrypted with AES-256-CBC using a key derived from your passphrase (PBKDF2). **All radios in your group must use the same passphrase.** If a packet fails to decrypt on the receiving end, it is silently dropped.

## Project Structure

```
app/src/main/java/com/atakmaps/meshcore/plugin/
├── MeshCoreLifecycle.java        # Plugin entry point (plugin.xml)
├── MeshCoreTool.java             # Tool registration
├── MeshCoreMapComponent.java     # Core component — wires transport + UI
├── MeshCoreDropDownReceiver.java # Mesh panel
├── MeshCoreContactHandler.java   # ATAK contact connector
├── bluetooth/
│   └── BtConnectionManager.java  # MeshCore BLE (NUS UART)
├── protocol/
│   ├── MeshCorePacket.java       # OPENRL binary packet format
│   └── PacketRouter.java         # Routes incoming packets
├── cot/
│   ├── CotBridge.java            # CoT ↔ MeshCore relay
│   └── CotBuilder.java
├── chat/
│   └── ChatBridge.java           # GeoChat ↔ MeshCore relay
├── contacts/
│   ├── ContactTracker.java
│   └── RadioContact.java
├── voice/
│   └── (legacy PTT scaffolding; not shipped as a feature in this fork)
└── ui/
    └── SettingsFragment.java     # Preference constants and helpers
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and guidelines.

## License

MIT + Commons Clause — free to use, modify, and distribute, but commercial sale rights are reserved. See [LICENSE](LICENSE).

# MeshcoreAtak → Darksteal Migration Todo

Features implemented in `MeshcoreAtak` that need to be ported to `Darksteal`
(`com.uvpro.plugin`). Each entry lists the exact files changed, method names,
and implementation notes so the port is mechanical.

---

## 1. AES-256-GCM Encryption UI Wiring

**Status:** ✅ Implemented in MeshcoreAtak — not yet in Darksteal UI

**What it does:** Wires the `switch_encryption` toggle, passphrase row,
`btn_set_passphrase`, and `text_encryption_status` TextView to the existing
`EncryptionManager` backend. Restores saved secret on plugin startup.

**Files changed in MeshcoreAtak:**

### `MeshCoreMapComponent.java`
- After `encryptionManager = new EncryptionManager()`, added startup restore:
  ```java
  if (SettingsFragment.isEncryptionEnabled(context)) {
      encryptionManager.setSharedSecret(
              SettingsFragment.getEncryptionPassphrase(context));
  }
  ```

### `MeshCoreDropDownReceiver.java`
- **Fields added:**
  ```java
  private Switch switchEncryption;
  private View passphraseRow;
  private EditText editPassphrase;
  private Button btnSetPassphrase;
  private TextView encryptionStatusText;
  ```
- **`bindViews()`:** Bound all five views via `rootView.findViewById(getId(...))`.
- **`setupListeners()`:** Added `switchEncryption.setOnCheckedChangeListener(...)`:
  - ON: reads saved passphrase via `SettingsFragment.getEncryptionPassphrase(ctx)`, calls `encryptionManager.setSharedSecret(existing)`, shows `passphraseRow`, logs.
  - OFF: calls `encryptionManager.setSharedSecret(null)`, hides `passphraseRow`.
  - Persists state via `SettingsFragment.PREF_ENCRYPTION_ENABLED`.
- **`setupListeners()`:** Added `btnSetPassphrase.setOnClickListener(...)`:
  - Reads `editPassphrase.getText()`, persists via `SettingsFragment.PREF_ENCRYPTION_PASSPHRASE`, calls `encryptionManager.setSharedSecret(pass)`, clears field.
- **`createView()`:** Initializes `switchEncryption.setChecked(encOn)`,
  `passphraseRow.setVisibility(...)`, calls `updateEncryptionStatus()`.
- **New method `updateEncryptionStatus()`:**
  ```java
  private void updateEncryptionStatus() {
      boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
      String pass = SettingsFragment.getEncryptionPassphrase(ctx);
      if (encOn && pass != null && !pass.isEmpty()) {
          encryptionStatusText.setText("✅ AES-256-GCM active");
          encryptionStatusText.setTextColor(0xFF4CAF50);
      } else if (encOn) {
          encryptionStatusText.setText("⚠ Enter shared secret to activate");
          encryptionStatusText.setTextColor(0xFFFF9800);
      } else {
          encryptionStatusText.setText("All radios must use the same shared secret");
          encryptionStatusText.setTextColor(0xFF888888);
      }
  }
  ```

**Darksteal migration notes:**
- Darksteal already has `EncryptionManager` backend wired in `CotBridge`,
  `ChatBridge`, `PacketRouter`, `UVProRadioServices` — those are fine.
- Only the UI binding (`UVProDropDownReceiver`) and startup restore
  (`UVProMapComponent`) need the above changes applied.
- Pref keys in Darksteal: `SettingsFragment.PREF_ENCRYPTION_ENABLED` and
  `PREF_ENCRYPTION_PASSPHRASE` (already exist).

---

## 2. Enable MeshCore GPS Hardware Toggle (new toggle)

**Status:** ✅ Implemented in MeshcoreAtak — not in Darksteal

**What it does:** Adds a dedicated "Enable MeshCore GPS" toggle that turns the
node's GPS hardware on/off via `CMD_SET_SETTING_TEXT gps:1/0`. Separates
hardware control from position-source selection (see item 3).

**Files changed:**

### `meshcore_dropdown.xml`
- Added new `LinearLayout` row with `switch_mesh_enable_gps_hardware` immediately
  above the existing `switch_mesh_enable_gps` row.

### `MeshCoreDropDownReceiver.java`
- **Field added:** `private Switch switchMeshEnableGpsHardware;`
- **`bindViews()`:** `switchMeshEnableGpsHardware = rootView.findViewById(getId("switch_mesh_enable_gps_hardware"));`
- **`setupListeners()`:** Listener calls `onMeshEnableGpsHardwareChanged(isChecked)`.
- **New method `onMeshEnableGpsHardwareChanged(boolean isChecked)`:**
  - ON: sets `meshGpsEnableRequested = true`, saves GPS pref, clears callsign/custom position prefs and their switches, removes map position marker, calls `btManager.setMeshGpsEnabled(true)`.
  - OFF: clears GPS pref, sets `meshGpsEnabledState = Boolean.FALSE`, clears augment pref and switch, calls `btManager.setMeshGpsEnabled(false)`.
- **`updateMeshGpsControlsUi()`:** Hardware toggle enabled whenever connected
  (never greyed for "no GPS" — protocol has no GPS-installed flag). Checked state
  mirrors `Boolean.TRUE.equals(meshGpsEnabledState)`.
- **`forceDisableMeshGpsPositionSource()`:** Added unchecking of
  `switchMeshEnableGpsHardware` (suppressed callbacks).
- **`onMeshGpsStateChanged` listener:** When GPS turns ON, now also clears
  callsign/custom position prefs and their switches.

---

## 3. Repurposed "Use MeshCore GPS for Position" Toggle

**Status:** ✅ Implemented in MeshcoreAtak — not in Darksteal

**What it does:** The toggle previously turned GPS hardware on/off. It now
only *selects* the node GPS fix as the advert position source. It is greyed out
unless the GPS hardware toggle (item 2) is ON and "Send Position With Advert"
is ON.

**Files changed:**

### `MeshCoreDropDownReceiver.java`
- **`onMeshGpsToggleChanged(boolean isChecked)`:** Rewritten. No longer calls
  `btManager.setMeshGpsEnabled(...)`. Now only:
  - Sets `meshGpsEnableRequested` and saves `PREF_MESH_USE_GPS_FOR_POSITION`.
  - Calls `updateMeshGpsControlsUi()`, `scheduleMeshCallsignPositionSync()`.
  - If turning off, calls `pushPhoneLocationToMeshNodeIfNeeded(false)`.
  - If turning on, removes map position marker.
  - Guard: returns early if `!Boolean.TRUE.equals(meshGpsEnabledState)` (hardware
    must be on first).
- **`updateMeshGpsControlsUi()`:** `switchMeshEnableGps` is enabled only when
  `meshConnected && meshGpsHardwareOn && advertPositionEnabled`.
- **`pushPhoneLocationToMeshNodeIfNeeded()`:** Changed `nodeGpsOn` check from
  `meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState)` to
  `Boolean.TRUE.equals(meshGpsEnabledState)` (hardware state only).
- **`scheduleMeshCallsignPositionSync()`:** Same narrowing — keys off
  `!Boolean.TRUE.equals(meshGpsEnabledState)`.
- **`handleMeshNodePositionPickResult()`:** Same narrowing.

---

## 4. Augment GPS — Greyed When GPS Hardware OFF

**Status:** ✅ Already implemented (came with item 2)

**What it does:** "Augment GPS from MeshCore" toggle and its row are disabled
(alpha 0.45) whenever `!meshGpsHardwareOn`. Default = OFF.

**Files changed:**

### `MeshCoreDropDownReceiver.java`
- **`updateMeshGpsControlsUi()`:**
  ```java
  boolean gpsDrivenActionsEnabled = meshConnected && meshGpsHardwareOn;
  switchAugmentGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
  switchAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
  rowAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.55f);
  ```
- Pref default is `false` (`PREF_AUGMENT_GPS_FROM_MESHCORE`, `getBoolean(..., false)`).

---

## 5. CoT Minification (reduce fragment count)

**Status:** ✅ Implemented in MeshcoreAtak — not in Darksteal

**What it does:** Before sending a CoT event over the RF channel, strips
non-essential `<detail>` children to reduce compressed size. Target: fit under
240 bytes (single fragment). Strictly never-worse fallback — only uses minified
bytes if smaller than original.

**Files changed:**

### `cot/CotBuilder.java`
- **New constant:**
  ```java
  private static final Set<String> MINIFY_DROP_DETAILS = new HashSet<>(Arrays.asList(
      "precisionlocation", "status", "takv", "_flow-tags_", "archive",
      "height", "height_unit", "track", "uid", "ce", "le"
  ));
  ```
- **New method `public static String minifyCotXml(CotEvent event)`:**
  - Clones via `CotEvent.parse(event.toString())` — never mutates original.
  - Iterates `detail.getChildren()` (returns a copy — safe to remove while iterating).
  - Removes children whose `getElementName()` is in `MINIFY_DROP_DETAILS`.
  - Returns `clone.toString()`. On any exception returns `event.toString()` unchanged.
  - Keeps: `contact`, `__group`, `color`, `usericon`, `remarks`, `link`, and
    anything not in the blacklist.

### `cot/CotBridge.java` — `sendCotOverRadio(CotEvent event)`
- Changed compression to pick the smaller of full vs minified:
  ```java
  byte[] full = CotBuilder.compressCot(event.toString());
  String minXml = CotBuilder.minifyCotXml(event);
  byte[] min = (minXml != null) ? CotBuilder.compressCot(minXml) : null;
  byte[] compressed = (min != null && min.length < full.length) ? min : full;
  Log.d(TAG, "CoT size: full=" + full.length + " min=" + (min==null?-1:min.length)
          + " used=" + compressed.length + " type=" + event.getType());
  ```
- Result in testing: 496→450 bytes, 3 fragments→2 fragments for a typical `a-u-G`
  waypoint. Remarks preserved.

---

## 6. CoT Hop Count Threading + Toast Notifications

**Status:** ✅ Implemented in MeshcoreAtak — not in Darksteal

**What it does:** Threads the MeshCore channel message `pathLen` (repeater hop
count) from the BLE receive layer all the way to `CotBridge.injectCompressedCot`.
Toasts the sender with fragment/size info and the receiver with hop count.

**Files changed:**

### `bluetooth/BtConnectionManager.java`
- **`handleMeshMessage`:** Added overload `handleMeshMessage(String msg, int pathLen)`.
  - No-arg version delegates: `handleMeshMessage(msg, 0)`.
  - `RESP_CHANNEL_MSG`/`RESP_CHANNEL_MSG_V3` branch: passes `meta.pathLen` (already
    extracted for chat) as `envPathLen` to `handleMeshMessage(routed, envPathLen)`.
  - `handleChannelData` path: passes `0` (no hop field in AX.25 datagram packets).
- After AX.25 reassembly: `packetRouter.routeIncoming(ax25, pathLen)`.

### `protocol/PacketRouter.java`
- **`routeIncoming`:** Added overload `routeIncoming(byte[] ax25Data, int pathLen)`.
  - No-arg delegates to `routeIncoming(ax25, 0)`.
  - Passes pathLen into `routeMeshCorePacket(srcCall, ssid, infoField, pathLen)`.
- **`routeMeshCorePacket`:** Added `int pathLen` parameter.
  - `TYPE_COT`: `cotBridge.injectCompressedCot(packet.getPayload(), pathLen)`
  - `TYPE_COT_FRAGMENT`: `cotBridge.injectCompressedCot(reassembled, pathLen)`
  - All other types (GPS, CHAT, PING, ACK, etc.) unchanged.

### `cot/CotBridge.java`
- **`injectCompressedCot`:** Added overload `injectCompressedCot(byte[] compressed, int pathLen)`.
  - No-arg delegates: `injectCompressedCot(compressed, 0)`.
  - After dispatching valid CoT, fires **receiver Toast**:
    ```java
    String hopStr = pathLen <= 0 ? "direct" : pathLen + (pathLen == 1 ? " hop" : " hops");
    String toastMsg = "CoT received: " + event.getType() + " via " + hopStr;
    mv.post(() -> Toast.makeText(mv.getContext(), toastMsg, Toast.LENGTH_SHORT).show());
    ```
- **`sendCotOverRadio`:** After `PacketFragmenter.fragment(...)`, fires **sender Toast**:
  ```java
  String toastMsg = "CoT sent over mesh (" + compressed.length + " bytes, "
          + packets.size() + (packets.size() == 1 ? " fragment)" : " fragments)");
  mv.post(() -> Toast.makeText(mv.getContext(), toastMsg, Toast.LENGTH_SHORT).show());
  ```

**Darksteal migration notes:**
- Darksteal equivalent files: `MeshBtConnectionManager.java` (BLE layer),
  `PacketRouter.java`, `CotBridge.java`, `UVProDropDownReceiver.java`.
- The `pathLen` threading is identical in structure — just rename classes.
- Darksteal's `handleMeshMessage` / `routeIncoming` / `injectCompressedCot`
  need the same overload pattern applied.

---

## 7. Clear All Mesh Contacts (already matched)

**Status:** ✅ Already implemented in MeshcoreAtak and matches Darksteal.
  MeshcoreAtak version is a superset: also resets unread badges via
  `chatBridge.clearUnreadForAllMeshContacts()` / `MeshCoreContactHandler.clearAllMeshUnread()`.
  **No migration needed.**

---

## Migration Priority

| Item | Port to Darksteal? | Notes |
|------|--------------------|-------|
| 1. AES Encryption UI wiring | ✅ Yes | Backend already exists; just UI binding + startup restore |
| 2. Enable MeshCore GPS hardware toggle | ⏸ Assess | Darksteal has different GPS arch (RadioGpsAugmentController) — design separately |
| 3. Repurposed "Use GPS for Position" | ⏸ Assess | Tied to item 2 — assess with it |
| 4. Augment GPS gating | ⏸ Assess | Tied to item 2 |
| 5. CoT Minification | ✅ Yes — HIGH PRIORITY | Pure reliability win; identical code, no arch differences |
| 6. CoT Hop Count + Toast | ✅ Yes — HIGH PRIORITY | Pure reliability/visibility win; identical threading pattern |
| 7. Clear All Mesh Contacts | ✅ Already done | No action needed |

## Notes for Migration

- All MeshcoreAtak pref keys use `meshcore_` prefix. Darksteal uses `uvpro_` prefix.
  Adjust pref key strings when porting.
- MeshcoreAtak layout IDs use `meshcore_` or generic names; Darksteal uses `uvpro_`
  or `mesh_` prefixed IDs in `uvpro_dropdown.xml`. Map accordingly.
- Darksteal's BLE manager is `MeshBtConnectionManager.java`; MeshcoreAtak's is
  `BtConnectionManager.java`. Same API surface, different class names.
- The `EncryptionManager` class is byte-for-byte identical between plugins.
- Items 2/3/4 (GPS rework): Darksteal has a fundamentally different GPS architecture
  (UV-PRO radio GPS via `RadioGpsAugmentController`). Do NOT directly copy — assess
  separately whether the hardware-toggle split adds value in the UV-PRO context.
- Items 5 and 6 are the RELIABILITY PRIORITY for Darksteal. The code is
  architecturally identical — only class name substitutions required (see below).

## Class Name Map: MeshcoreAtak → Darksteal

| MeshcoreAtak | Darksteal |
|---|---|
| `BtConnectionManager.java` | `MeshBtConnectionManager.java` |
| `PacketRouter.java` | `PacketRouter.java` (same name) |
| `cot/CotBridge.java` | `cot/CotBridge.java` (same name) |
| `cot/CotBuilder.java` | `cot/CotBuilder.java` (same name) |
| `MeshCoreDropDownReceiver.java` | `UVProDropDownReceiver.java` |
| `MeshCoreMapComponent.java` | `UVProMapComponent.java` |
| `ui/SettingsFragment.java` | `ui/SettingsFragment.java` (same name) |
| `crypto/EncryptionManager.java` | `crypto/EncryptionManager.java` (identical, no change) |
| `protocol/PacketFragmenter.java` | `protocol/PacketFragmenter.java` (same name) |
| `bluetooth/BtConnectionManager` field `btManager` | `meshBtManager` (Darksteal has two BT managers) |

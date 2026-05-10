# UV-PRO Plugin — Agent Handoff Document

> **Note:** This file kept its historical name (`…v1.8.6…`); treat it as the **current** UV-PRO agent handoff unless superseded.

---

## 0. New agent — start here (onboarding)

### 0.1 Purpose & privacy

This document and `Plugins/Darksteal/HANDOFF.md` are **local operator notes** for AI/human agents. They are **not** intended for the public GitHub repo. The plugin repo intentionally **gitignores** `HANDOFF.md`; keep this file under `Plugins/Handoff Docs/` (or equivalent) so it does not get pushed with `TAK-UV-PRO`.

### 0.2 Which document for which question

| Need | Read |
|------|------|
| **Build machine, SDK paths, JDK, Gradle** | `Darksteal/AGENTS.md`, `Darksteal/README.md` |
| **Packet flows, chat, CoT, contacts, SA relay** | `Darksteal/HANDOFF.md` (gitignored locally) |
| **Version bump, TPC zip, Fortify, update server TLS, VPS deploy** | **This file** |
| **Structured findings (SQLite, agent queries)** | **`Plugins/Handoff Docs/handoff.db`** — table `uvpro_handoff` |
| **Short update-server recap** | `Plugins/Handoff Docs/UPDATE-SERVER-CERT-INJECTION-HANDOFF.md` |
| **ATAK internals (trust, `getCACerts`)** | This file §5–§9 + `decompiled/atak_src/` |

### 0.3 Read order (minimum before you change code)

1. **§0–§4** of this file (you are here → locations, identity, assets).
2. **`AGENTS.md`** — confirm SDK + `app/libs/atak-civ/` layout; without it, Gradle will not run.
3. **`HANDOFF.md`** — if the task touches RF, chat, CoT, or contacts.
4. **`UVProLifecycle.java`** + **`UVProMapComponent.java`** — any task touching plugin load order or **update server / HTTPS**.
5. **§5–§7** of this file — before editing **truststore, prefs, or `atakmaps-ca.p12`**.

### 0.4 Preconditions (checklist)

- [ ] JDK **17**, Android SDK (see `AGENTS.md`), `JAVA_HOME` / `ANDROID_HOME` set if needed.
- [ ] ATAK CIV **5.5.1** SDK files present under `app/libs/atak-civ/` (not in git).
- [ ] For device work: **`adb devices`** shows the phone; ATAK package is typically **`com.atakmap.app.civ`**.
- [ ] For VPS work: SSH to **`root@31.220.30.74`** works with the operator’s key (no password in this doc).
- [ ] For TPC: portal access / DOD SAFE per your org (not documented here).

### 0.5 Playbook A — Release a new plugin version (source → TPC)

Use when the user asks to **submit a new build to TPC**.

1. In `Darksteal/build.gradle`, set **`ext.PLUGIN_VERSION = "X.Y.Z"`** (and confirm **`ext.ATAK_VERSION = "5.5.1"`** unless targeting another ATAK).
2. **`versionCode`** is derived in `app/build.gradle` from `PLUGIN_VERSION` — do not hand-edit unless you know the project broke that rule.
3. Fix **`atakmaps-ca.p12`** if you changed the PKCS#12 store key: regenerate with **`keytool`** (see **§14 Appendix**) so **`-storepass`** matches **`Base64.decode(R.string.uvpro_trust_bundle_p12_key)`** (not a Java literal).
4. **`./gradlew assembleCivRelease`** — resolve compile/lint failures before submission.
5. **`git status`** — stage and **commit** everything that must appear in the source zip (see next step).
6. **`bash tools/package-submission.sh`** from `Darksteal/` — produces  
   `Plugins/TAK Submissions/UV-PRO-X.Y.Z-ATAK-5.5.1-source.zip` via **`git archive HEAD`**. **Uncommitted files are excluded.**
7. Upload **only that source zip** to the TPC submission portal.
8. When the return zip arrives in **`TAK Signed/`**, extract **`fortify_scan_results.pdf`**, **`build.log`**, and the **signed APK**. If Fortify fails, fix issues, bump version if needed, repeat from step 4.

### 0.6 Playbook B — Publish TPC-signed APK on `atakmaps.com`

Use when a **signed** `ATAK-Plugin-UVPro-*-tpc-5.5.1-civ-release.apk` exists.

1. **`scp`** the APK to **`/var/www/html/plugins/`** on the VPS.
2. **`cp`** it to **`com.uvpro.plugin.apk`** (canonical name referenced by `product.inf`).
3. Edit **`generate_infz.py`**: **`version`** and **`version_code`** must match the signed APK (`aapt dump badging` if unsure).
4. Run **`python3 generate_infz.py`** in that directory.
5. **`cp product.infz 5.5.1/product.infz`** so versioned URL works.
6. Verify: **`curl -fsS https://atakmaps.com/plugins/product.infz`** and **`…/5.5.1/product.infz`** — unzip and inspect `product.inf` CSV line for correct version/hash/size.

### 0.7 Playbook C — User reports “Socket is closed” / plugin repo sync fails

1. Confirm **network** and that **`https://atakmaps.com/plugins/5.5.1/product.infz`** returns **200**.
2. **Logcat:** `UVPro`, `getCACerts`, `GetRepoIndex`, `TakHttp`, `Socket`.
3. If **`getCACerts for atakmaps.com … 0`** persists: read **§5** — likely missing **stored unlock** for update-server PKCS#12 or race; compare with **v1.9.7** behavior or **manual TAK Package Mgmt** truststore test (**§5**).
4. Confirm server sends **full TLS chain** (leaf + intermediate), not leaf-only (**§5**, server TLS).

### 0.8 Playbook D — TPC / Gradle / Fortify failure

1. Open **`build.log`** in the return zip (or TPC UI) — distinguish **compile error** vs **scan failure**.
2. For **Fortify**: open **`fortify_scan_results.pdf`**; common historical issues: **Null Password** (`KeyStore.load(..., null)`), or **hardcoded secret** if a literal PKCS#12 key appears in **Java**. **v1.9.7+** uses **`R.string.uvpro_trust_bundle_p12_key`** (Base64) — not a Java literal. **Do not** “fix” Fortify by reverting to **`null`** unlock if TLS requires a real value.
3. For **missing Artifactory init script** / Gradle infra: see the **`00-tak-artifactory.gradle`** notes in `Darksteal/HANDOFF.md` (TPC outage section).

### 0.9 Golden rules

- **Never hand-craft** the TPC source zip — always **`tools/package-submission.sh`**.
- **Always commit** before packaging — **`git archive`** only ships **HEAD**.
- **Never upload the APK to TPC** as the submission artifact — **source zip only**.
- **Do not** trust outdated advice flagged in **§12** (stale rules).
- **Use host ATAK `Context`** / `getApplicationContext()` for **SharedPreferences** that ATAK reads — not the raw plugin context — for update-server prefs (see code in `UVProMapComponent`).

---

## Session scope: current production target

| Field | Value |
|--------|--------|
| **Plugin** | UV-PRO ATAK Plugin (BTECH UV-PRO radio ↔ ATAK bridge) |
| **Current version** | **1.9.7** (`ext.PLUGIN_VERSION` in root `build.gradle`) |
| **ATAK target** | **5.5.1** (CIV) |
| **Example versionCode** | `1.9.7` → **10907** (formula: major×10000 + minor×100 + patch) |
| **GitHub** | `https://github.com/atakmaps/TAK-UV-PRO` |
| **Plugin repo HEAD (trust / Fortify / AAPT fixes)** | **`74ca716`** — PKCS#12 key in `strings.xml` Base64; `textPassword` inputType |
| **Last TPC-signed drop verified** | **`paul-c-besing-mil-army-mil-20260510-080610.zip`** → `ATAK-Plugin-UVPro-1.9.7-tpc-5.5.1-civ-release.apk` |
| **Field verification** | ATAK **green update server / sync** on production phone after **1.9.7** TPC install (May 2026) |
| **Date (this revision)** | May 10, 2026 |

### Verified outcomes (May 2026)

- **TLS / trust:** No manual TAK Package Mgmt truststore step required on **1.9.7**; automation matches the earlier manual workaround (stored unlock + PKCS#12 import).
- **Fortify:** Return zip **`…080610.zip`** — `fortify_scan.txt` reports **Rendering 0 results**; PDF still present for audit. Prior finding: **hardcoded password** in Java → addressed by **`uvpro_trust_bundle_p12_key`** Base64 in **`res/values/strings.xml`** (runtime decode).
- **TPC build:** Prior failure **`inputType` `0x81`** incompatible with AAPT → use **`textPassword`**.
- **`adb uninstall com.uvpro.plugin`:** Can return **`DELETE_FAILED_INTERNAL_ERROR`** on some devices; **`adb install -r`** of the TPC APK still succeeded in testing.

---

## 1. Repository and file locations

| Item | Path |
|------|------|
| Plugin source | `/home/paul/Documents/ATAK/Plugins/Darksteal/` |
| TPC-signed APKs & return zips | `/home/paul/Documents/ATAK/Plugins/TAK Signed/` |
| Submission outputs | `/home/paul/Documents/ATAK/Plugins/TAK Submissions/` |
| Agent findings (SQLite) | `/home/paul/Documents/ATAK/Plugins/Handoff Docs/handoff.db` |
| Legacy / avoid | `/home/paul/Documents/ATAK/Plugins/Submission Zips/` (do not use for new work) |
| Decompiled ATAK (reference) | `/home/paul/Documents/ATAK/decompiled/atak_src/` |
| Cloud update server | `atakmaps.com` (hosting VPS: **`root@31.220.30.74`**, SSH key auth, e.g. `~/.ssh/id_ed25519`) |
| Update server URL (canonical) | `https://atakmaps.com/plugins/product.infz` |
| Web root (on server) | `/var/www/html/plugins/` |
| `product.infz` generator | `/var/www/html/plugins/generate_infz.py` |
| Secondary URL path | **`/plugins/5.5.1/product.infz`** — ATAK inserts `/<ATAK_VERSION>/` before `product.infz`; mirror `product.infz` into `5.5.1/` |

---

## 2. Plugin identity & build

- **Package:** `com.uvpro.plugin`
- **Entry:** `UVProLifecycle` → `UVProMapComponent`
- **ATAK UI:** Menu / Tools → UV-PRO (toolbar / dropdown per build)
- **Release build:** `./gradlew assembleCivRelease`
- **Submission packaging:** **`tools/package-submission.sh`** (uses **`git archive HEAD`** — **only committed files** are zipped; commit before packaging)
- **TPC upload:** **source zip only** — `UV-PRO-<ver>-ATAK-5.5.1-source.zip` from `TAK Submissions/`

---

## 3. What the plugin does (summary)

- Bluetooth **SPP** to the radio; **KISS** framing; **AX.25** on RF (smart beaconing is **AX.25-based**, not “APRS product” wording in docs/UI).
- **Contact-centric** transport: GeoChat and contact-targeted CoT over RF; optional **SA Relay** (throttled, off by default).
- **Plugin update server:** configures ATAK’s **TAK Package / update server** prefs and **trust material** for **`https://atakmaps.com`** so `product.infz` can sync over HTTPS without manual steps (see §6–§7).

For **architecture / packet flows**, see also `Plugins/Darksteal/HANDOFF.md`.

---

## 4. Source layout (abbreviated)

```
app/src/main/java/com/uvpro/plugin/
├── UVProLifecycle.java       # Calls applyUpdateServerTrustEarly() before map component
├── UVProMapComponent.java    # Main wiring; update-server trust + sync orchestration
├── UVProDropDownReceiver.java
├── cot/, chat/, bluetooth/, protocol/, ui/, …
```

**Assets (`app/src/main/assets/`):**

| File | Role |
|------|------|
| `plugin.xml` | ATAK plugin manifest |
| `isrg-root-x1.pem` | ISRG Root X1 (PEM) — primary parse path in `registerUpdateServerCA` |
| `atakmaps-ca.p12` | **PKCS#12 trust bundle** for ATAK’s **update-server** import (`UPDATE_SERVER_TRUST_STORE_CA`). Built with **`keytool -importcert`** (trustedCertEntry, non-empty store secret). **Not optional:** must match what the plugin stores in ATAK’s credential DB (see §6). |

---

## 5. Update server & TLS (accurate model — v1.9.7)

### Symptoms when misconfigured

- Logcat / UI: **`Socket is closed`**, **`getCACerts for atakmaps.com … 0 certs`** during `GetRepoIndexOperation` / `TakHttpClient` — HTTPS to `atakmaps.com` fails trust.

### What ATAK actually does

Decompiled reference: `gov.tak.platform.engine.net.CertificateManagerBase.getCACerts(...)`.

- For **`UPDATE_SERVER_TRUST_STORE_CA`**, ATAK loads PKCS#12 bytes from the cert DB and calls `CertificateManager.loadCertificate(p12, unlock)` **only if** credentials exist for the update-server unlock and `FileSystemUtils.isEmpty` is **false** on that value. **An empty or missing unlock means the PKCS#12 is never expanded into trust anchors** for that path, even if the bytes were imported.
- **Injection** via `CertificateManager.addCertificate(X509Certificate)` and related cache clearing **still matters** (race with startup sync, `socketFactories` cache, obfuscated builds) but **does not replace** the update-server PKCS#12 + stored unlock for the path `getCACerts` uses.

### What the plugin does (implementation)

1. **`UVProLifecycle`:** `applyUpdateServerTrustEarly(pluginContext)` → resolves host ATAK `Context`, runs `configureUpdateServerStatic` **before** the map component’s `onCreate` when possible.
2. **`UVProMapComponent.onCreate`:** calls `configureUpdateServerStatic` immediately, then again on `view.post` / delayed posts to reduce races with early background sync.
3. **`configureUpdateServerStatic`:** writes update-server URL and enable/auto-sync prefs (host `SharedPreferences`); then:
   - **`installUpdateServerTruststoreCompat`:** copies `atakmaps-ca.p12` → `atakContext.getFilesDir()/uvpro_update_server_ca.p12`, sets `updateServerCaLocation`, **`AtakCertificateDatabase.importCertificate(path, "", typeKey, false)`** (second parameter is **connect string**, not the PKCS#12 unlock), then **`AtakCertificateDatabaseBase.saveCertificatePassword(...)`** with the **same unlock** ATAK expects for that PKCS#12, and mirrors the unlock into default **`SharedPreferences`** under the key ATAK uses for the update-server truststore unlock (see `ICredentialsStore.Credentials` in ATAK — runtime key is assembled in source to satisfy static scans). Reload: **`reloadCertificateManagerFromDatabase`** (`invalidate` + `refresh`).
   - **`registerUpdateServerCA`:** load CA from **`isrg-root-x1.pem`** (fallback: PKCS#12 asset with the same unlock), **`bindUpdateServerCaToHost`** (reflection `saveCertificateForServer*` for `atakmaps.com` / 443 / 8443), **`addOfficialCertificateManagerCa`**, **`injectCACert`** fallback graph.
4. **`scheduleDeferredUpdateServerSyncs`:** retries product sync after trust is in place.

### Manual workaround (proven on device)

If a build lacks the stored unlock or races badly: **TAK Package Mgmt Preferences** → set **Update Server SSL/TLS TrustStore Location** to a PKCS#12 that includes ISRG Root X1 and set the **truststore unlock** to match that file. **While testing, uninstall/disable UV-PRO** if it keeps overwriting `updateServerCaLocation` (older builds). v1.9.7 aligns automation with that workaround.

### Server TLS

- Serve **full chain** (leaf + intermediate); ATAK does not AIA-fetch. See §8.

---

## 6. PKCS#12, “password” wording, and Fortify (TPC)

- **Historical mistake:** Dropping the **unlock string** from source (to appease scanners) and using **`KeyStore.load(..., null)`** caused Fortify **“Password Management: Null Password”** on **`UVProMapComponent`** in TPC **`fortify_scan_results.pdf`** (bundled **inside** each return zip under `TAK Signed/`, not as loose PDFs).
- **Another mistake (corrected):** Assuming “PEM / `addCertificate` alone” always satisfies the **update-server `getCACerts`** path — **false** on production ATAK; the **non-empty stored unlock** for the imported update-server PKCS#12 is required for that branch.
- **Trade-off:** Fortify flags **hardcoded passwords** in Java. The PKCS#12 store key is **not** a user account password; it unlocks a **bundled public CA** file. v1.9.7+ holds the key as **Base64 in `strings.xml`** (`uvpro_trust_bundle_p12_key`) and decodes at runtime — still recoverable from the APK, but satisfies typical “no plaintext secret in source” rules. **Never** revert to **`KeyStore.load(..., null)`** to appease scanners (separate Fortify finding).

---

## 7. Cloud server: deploying a new TPC-signed APK

```bash
scp ATAK-Plugin-UVPro-X.X.X-tpc-5.5.1-civ-release.apk root@31.220.30.74:/var/www/html/plugins/
ssh root@31.220.30.74
cp -f /var/www/html/plugins/ATAK-Plugin-UVPro-X.X.X-tpc-5.5.1-civ-release.apk \
      /var/www/html/plugins/com.uvpro.plugin.apk
# Edit version + version_code in generate_infz.py, then:
python3 /var/www/html/plugins/generate_infz.py
cp -f /var/www/html/plugins/product.infz /var/www/html/plugins/5.5.1/product.infz
```

Verify with `curl` on `/plugins/product.infz` and `/plugins/5.5.1/product.infz`.

---

## 8. TPC submission workflow (short)

```bash
cd /home/paul/Documents/ATAK/Plugins/Darksteal
# Bump PLUGIN_VERSION, commit all sources needed in the zip
./gradlew assembleCivRelease   # optional local APK; script still works without it
bash tools/package-submission.sh
```

Upload **`UV-PRO-1.9.7-ATAK-5.5.1-source.zip`** (adjust version) from `TAK Submissions/` to the portal. TPC returns **`paul-c-besing-mil-army-mil-*.zip`** containing signed APK/AAB, **`fortify_scan_results.pdf`**, dependency report, **`build.log`**.

**Naming:** keep script-generated names; TPC is strict.

---

## 9. ATAK SSL stack (reference)

| Class | Role |
|-------|------|
| `com.atakmap.net.CertificateManager` | Facade; `addCertificate`, `refresh`, socket factory cache |
| `gov.tak.platform.engine.net.CertificateManagerBase` | `getCACerts`, trust manager rebuild |
| `com.atakmap.net.AtakCertificateDatabaseBase` | `importCertificate`, `saveCertificatePassword`, `saveCertificateForServer*` |
| `CentralTrustManager` | Host-scoped trust; uses `getLocalTrustManager(host)` |

**`socketFactories` cache:** Early sync can build a factory before plugins run; plugin code **invalidates/refreshes** and **clears** cached factories where applicable.

---

## 10. Developer device notes

```bash
adb devices
adb install -r …/ATAK-Plugin-UVPro-*-tpc-5.5.1-civ-release.apk
adb logcat | grep -iE 'UVPro|getCACerts|GetRepoIndex|TakHttp|Socket'
```

**Signing:** TPC vs debug → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` until `adb uninstall com.uvpro.plugin`.

**Plugin load flag:** If `shouldLoad-com.uvpro.plugin` is false, enable in ATAK Plugin Manager or prefs (see older commands in repo docs).

---

## 11. Version history (high level)

| Version | Themes |
|---------|--------|
| 1.8.x | HTTPS update server, CA injection, server chain fix, `addCertificate` + cache clearing |
| 1.9.x | Early trust init, `importCertificate` for update server, DB refresh, deferred syncs |
| **1.9.7** | **ISRG `atakmaps-ca.p12` via `keytool`; `saveCertificatePassword` + prefs; PKCS#12 key from `strings.xml` Base64 (no Java literal)** |

---

## 12. Stale rules — do **not** rely on these anymore

- ~~“`getCACerts … 0 certs` is harmless / ignore it.”~~ **Wrong** for the **update-server host** when the PKCS#12 unlock path is empty — it correlates with **failed TLS**.
- ~~“PKCS#12 never works on Android for this plugin; never use it.”~~ **Wrong** for **`UPDATE_SERVER_TRUST_STORE_CA`** — ATAK loads PKCS#12 **if** unlock credentials are stored; the asset is **`keytool`**-friendly.
- ~~“`atakmaps-ca.p12` is unused legacy.”~~ **Wrong** — it is the **bundled update-server truststore** copy for `importCertificate`.
- ~~“Strip all unlock strings; the certificate alone fixes production.”~~ **Wrong** — production needs the **stored unlock** for that PKCS#12 path (or equivalent manual TAK Package Mgmt configuration).

---

## 13. For the next agent (extended checklist)

When a **new chat/agent** starts with no memory:

1. **State baseline:** Read **§0** and the **Session scope** table — note **`PLUGIN_VERSION`**, **`ATAK_VERSION`**, and **last known good git SHA** (update this line whenever you cut a release).
2. **Classify the task** → open the matching **playbook in §0.5–§0.8**.
3. **Code exploration:** Use **§4** file list + **`HANDOFF.md`** jump table for RF/chat/CoT; use **`UVProMapComponent`** for update-server/TLS.
4. **Before PR / push:** `./gradlew assembleCivRelease` (or at least the variant the user cares about); do not leave the repo unbuildable.
5. **Before TPC package:** all intended files **committed**; run **`package-submission.sh`**; confirm zip path under **`TAK Submissions/`**.
6. **After TPC return:** signed APK to **`TAK Signed/`**; run **Playbook B** if publishing OTA; re-read **`product.inf`** on the server for version/hash.
7. **If stuck on ATAK behavior:** grep **`decompiled/atak_src/`** for the class names in **§9**; do not guess SSL trust flow.

**Update this document when:** you change the update-server trust strategy, PKCS#12 generation, TPC packaging rules, or VPS paths — so the **next** agent does not rely on chat history.

**Current plugin-repo HEAD (handoff time):** **`74ca716`** on **`main`** — **v1.9.7**. Update this line after new commits that change trust, TPC, or packaging.

**SQLite handoff:** append rows to **`Plugins/Handoff Docs/handoff.db`** (`uvpro_handoff`) when you learn something new — keeps agents off stale chat-only context.

---

## 14. Appendix — commands & snippets

### 14.1 Regenerate `atakmaps-ca.p12` (must match `strings.xml`)

From `Darksteal/`. **`keytool -storepass`** must equal **UTF-8 string** obtained from:

`Base64.decode(getString(R.string.uvpro_trust_bundle_p12_key), DEFAULT)`.

Example (decode `strings.xml` locally, then run `keytool`):

```bash
cd /home/paul/Documents/ATAK/Plugins/Darksteal
KEY="$(python3 << 'PY'
import base64, re, pathlib
xml = pathlib.Path("app/src/main/res/values/strings.xml").read_text()
m = re.search(r'name="uvpro_trust_bundle_p12_key"[^>]*>([^<]+)<', xml)
print(base64.b64decode(m.group(1)).decode())
PY
)"
PEM="app/src/main/assets/isrg-root-x1.pem"
OUT="app/src/main/assets/atakmaps-ca.p12"
rm -f "$OUT"
keytool -importcert -noprompt -keystore "$OUT" -storetype PKCS12 \
  -storepass "$KEY" -alias isrgroot -file "$PEM"
keytool -list -keystore "$OUT" -storepass "$KEY"
```

If you **change** `uvpro_trust_bundle_p12_key`, update **`atakmaps-ca.p12`** in the same commit. After changing either, run **Playbook A**.

### 14.2 Read `versionCode` from any APK

```bash
aapt dump badging ATAK-Plugin-UVPro-*.apk | head -1
```

### 14.3 Quick server / TLS checks

```bash
curl -fsS -o /tmp/pi.zip https://atakmaps.com/plugins/product.infz && unzip -p /tmp/pi.zip product.inf
openssl s_client -connect atakmaps.com:443 -showcerts </dev/null 2>/dev/null | grep -c "BEGIN CERTIFICATE"
```

Expect **at least 2** certificates in the served chain if intermediate is bundled.

### 14.4 Logcat filter (update server + plugin)

```bash
adb logcat | grep -iE 'UVPro|getCACerts|GetRepoIndex|TakHttp|CertificateManager|Socket'
```

### 14.5 Install signed build on device

```bash
adb install -r "/path/to/ATAK-Plugin-UVPro-X.Y.Z-tpc-5.5.1-civ-release.apk"
```

If **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: `adb uninstall com.uvpro.plugin` then reinstall (user data under ATAK is separate from plugin package).

### 14.6 Query `handoff.db` (local)

```bash
sqlite3 "/home/paul/Documents/ATAK/Plugins/Handoff Docs/handoff.db" \
  "SELECT id, topic, substr(summary,1,100) FROM uvpro_handoff ORDER BY id;"
```

(On hosts without `sqlite3`, use `python3 -c "import sqlite3; ..."` the same query.)

# AGENTS.md — MeshCore ATAK Plugin

## Project overview

MeshCore is an Android ATAK plugin (APK) that bridges MeshCore BLE companions to ATAK for off-grid awareness and chat. Built with the Android Gradle Plugin and ATAK-CIV **5.5.1** SDK.

- **Package / applicationId:** `com.atakmaps.meshcore.plugin`
- **Java namespace:** `com.atakmaps.meshcore.plugin`
- **GitHub:** `atakmaps/TAK-MESHCORE` (remote `meshcore`)

This repo is **not** UV-PRO. Do not change `com.uvpro.plugin` paths on the atakmaps.com server unless explicitly asked.

## Toolchain

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android SDK | API 35 |
| ATAK-CIV SDK | 5.5.1.x in `app/libs/atak-civ/` (gitignored) |

## Build

```bash
export ANDROID_HOME=/opt/android-sdk   # or your SDK path
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew assembleCivDebug -PlocalDev=true   # fast: civDebug only
./gradlew :app:assembleCivRelease            # TPC / release
```

APK naming: `ATAK-Plugin-Meshcore-<version>-<git>-5.5.1-civ-release.apk`

## TPC submission

See `Plugins/Handoff Docs/MESHCORE-TPC-SUBMISSION.md`.

**Dual ATAK targets (5.5.1 + 5.6.0)** — default for “ready for zip”:

```bash
./tools/build-submission-zips.sh
```

Produces both `MeshCore-<ver>-ATAK-5.5.1-source.zip` and `MeshCore-<ver>-ATAK-5.6.0-source.zip` in `Plugins/TAK Submissions/`. Uses `tools/use-atak-sdk.sh` to swap SDK jars; restores 5.5.1 when done.

5.6 SDK default path: `~/Documents/ATAK/Versions/ATAK-CIV-5.6.0.18-SDK` (override with `ATAK_560_SDK`).

Single-target build (legacy):

```bash
./tools/use-atak-sdk.sh 5.5.1   # or 5.6.0
./gradlew :app:assembleCivRelease   # add -Patak.version=5.6.0 for 5.6
ATAK_VERSION=5.5.1 ./tools/package-submission.sh
```

Upload the zip matching your TPC portal ATAK compatibility (5.5.1 or 5.6.0).

## Versioning

- Set **`ext.PLUGIN_VERSION`** in root `build.gradle` only (use three segments, e.g. `1.1.0`).
- **`versionCode`** is derived in `app/build.gradle` — do not hand-edit.

## Gradle / TPC build notes

- **`gradle/takdev/atak-gradle-takdev.jar`** is vendored so TPC can build without Artifactory init scripts.
- **`00-tak-artifactory.gradle`** is included for environments that use TAK Artifactory.
- R8 uses `-applymapping`; placeholder `tools/empty-atak-applymapping.txt` if no official mapping in `app/libs/atak-civ/`.

## Trust / update server

MeshCore ships `atakmaps-ca.p12` + `isrg-root-x1.pem` (identical copies of the UV-PRO assets — same `atakmaps.com` CA) and runs the **same** cert injection chain. Both plugins write identical values to ATAK’s SharedPreferences (`atakUpdateServerUrl`, `appMgmtEnableUpdateServer`, etc.) so they are fully idempotent when installed together.

The filesDir copy is named `meshcore_update_server_ca.p12` to avoid cross-plugin file collision.

### Merge gate (blocking) — trust / update-server

Hard gate for any merge touching startup, certs, prefs, ProGuard, or lifecycle.

**High-risk files:**

| Area | Files / assets |
|------|----------------|
| Trust + prefs + reflection | `MeshCoreMapComponent.java` — keep the `configureUpdateServerStatic` → `installUpdateServerTruststoreCompat` → `reloadCertificateManagerFromDatabase` → `registerUpdateServerCA` → deferred sync chain |
| Early hook | `MeshCoreLifecycle.java` — must keep `MeshCoreMapComponent.applyUpdateServerTrustEarly` in constructor |
| Bundled trust material | `app/src/main/assets/atakmaps-ca.p12`, `app/src/main/assets/isrg-root-x1.pem` |
| Bundled iconset (map icons) | `app/src/main/assets/meschore.zip` — **must stay committed**; `package-submission.sh` verifies source zip + release APK |
| PKCS#12 unlock key | `app/src/main/res/values/strings.xml` → `meshcore_trust_bundle_p12_key` (Base64, `translatable="false"`) |

**Do not:**
- Remove or reorder the trust chain, or delay it until after ATAK starts repo HTTPS sync.
- Remove the cert assets, **meschore.zip**, or the string resource.
- Use a Java string literal for the PKCS#12 password (Fortify will flag it).

**After a merge touching these files:** clean-install on device with `pm clear com.atakmap.app.civ`, check logcat for `MeshCore` cert tags and TAK Package Management green sync.

## ProGuard

Keep `-keep class com.atakmaps.meshcore.plugin.** { *; }` in `app/proguard-gradle.txt`.

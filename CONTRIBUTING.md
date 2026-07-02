# Contributing to MeshCore ATAK Plugin

MeshCore connects ATAK to MeshCore radios over BLE. Repo: `atakmaps/TAK-MESHCORE`.

## Building

1. Clone and install ATAK-CIV **5.6.0** SDK under `app/libs/atak-civ/` (see README; `./tools/use-atak-sdk.sh 5.6.0`).
2. JDK **17**, `local.properties` with `sdk.dir=...`
3. `./gradlew assembleCivDebug` or `./gradlew :app:assembleCivRelease`

## TPC submission checklist

Before `package-submission.sh`:

- [ ] `ext.PLUGIN_VERSION` bumped in root `build.gradle` (prefer `x.y.z` three segments)
- [ ] Trust assets committed: `app/src/main/assets/atakmaps-ca.p12`, `isrg-root-x1.pem`
- [ ] **Iconset asset committed:** `app/src/main/assets/meschore.zip` (required in APK + TPC source zip; `.gitignore` has `!app/src/main/assets/meschore.zip`)
- [ ] All submission sources **committed** on `main`
- [ ] `./gradlew :app:assembleCivRelease` succeeds
- [ ] `./tools/package-submission.sh` produces `MeshCore-*-ATAK-5.6.0-source.zip`
- [ ] Portal metadata matches: **TAK-MeshCore**, `com.atakmaps.meshcore.plugin`, ATAK **5.6.0 CIV**

Full details: `Plugins/Handoff Docs/MESHCORE-TPC-SUBMISSION.md`

## Code conventions

- Java package: `com.atakmaps.meshcore.plugin` (not `com.uvpro.plugin`)
- Entry points: `MeshCoreLifecycle`, `MeshCoreMapComponent`, `MeshCoreDropDownReceiver`
- Log tags: prefer `MeshCore` or `MeshCore.*` subtags

## Update-server trust (required for OTA)

MeshCore ships the same `atakmaps.com` CA bundle as UV-PRO:

- `app/src/main/assets/atakmaps-ca.p12`
- `app/src/main/assets/isrg-root-x1.pem`
- PKCS#12 key: `meshcore_trust_bundle_p12_key` in `strings.xml`

Both must be **committed** before TPC submission (`package-submission.sh` enforces this).

## MeshCore iconset asset (required)

Auto-install and map icons depend on the bundled iconset zip:

- `app/src/main/assets/meschore.zip` — must be **git-tracked** (exception in `.gitignore`)
- `tools/package-submission.sh` fails if missing, uncommitted, absent from the source zip, or missing from the release APK

Do not remove or gitignore this file. TPC builds only include tracked assets.

## Do not merge UV-PRO-only patterns

- No UV-PRO catalog APK path (`com.uvpro.plugin.apk`) — MeshCore uses `com.atakmaps.meshcore.plugin.apk`

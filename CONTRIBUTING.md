# Contributing to MeshCore ATAK Plugin

MeshCore connects ATAK to MeshCore radios over BLE. Repo: `atakmaps/TAK-MESHCORE`.

## Building

1. Clone and install ATAK-CIV 5.5.1 SDK under `app/libs/atak-civ/` (see README).
2. JDK **17**, `local.properties` with `sdk.dir=...`
3. `./gradlew assembleCivDebug` or `./gradlew :app:assembleCivRelease`

## TPC submission checklist

Before `package-submission.sh`:

- [ ] `ext.PLUGIN_VERSION` bumped in root `build.gradle` (prefer `x.y.z` three segments)
- [ ] All submission sources **committed** on `main`
- [ ] `./gradlew :app:assembleCivRelease` succeeds
- [ ] `./tools/package-submission.sh` produces `MeshCore-*-ATAK-5.5.1-source.zip`
- [ ] Portal metadata matches: **MeshCore**, `com.atakmaps.meshcore.plugin`, ATAK **5.5.1 CIV**

Full details: `Plugins/Handoff Docs/MESHCORE-TPC-SUBMISSION.md`

## Code conventions

- Java package: `com.atakmaps.meshcore.plugin` (not `com.uvpro.plugin`)
- Entry points: `MeshCoreLifecycle`, `MeshCoreMapComponent`, `MeshCoreDropDownReceiver`
- Log tags: prefer `MeshCore` or `MeshCore.*` subtags

## Do not merge UV-PRO-only patterns

- No `atakmaps-ca.p12` / `configureUpdateServerStatic` unless MeshCore gains its own OTA server
- No UV-PRO catalog APK path (`com.uvpro.plugin.apk`)

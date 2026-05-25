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

```bash
./gradlew :app:assembleCivRelease
./tools/package-submission.sh
```

Upload `MeshCore-<version>-ATAK-5.5.1-source.zip` from `Plugins/TAK Submissions/`.

## Versioning

- Set **`ext.PLUGIN_VERSION`** in root `build.gradle` only (use three segments, e.g. `1.1.0`).
- **`versionCode`** is derived in `app/build.gradle` — do not hand-edit.

## Gradle / TPC build notes

- **`gradle/takdev/atak-gradle-takdev.jar`** is vendored so TPC can build without Artifactory init scripts.
- **`00-tak-artifactory.gradle`** is included for environments that use TAK Artifactory.
- R8 uses `-applymapping`; placeholder `tools/empty-atak-applymapping.txt` if no official mapping in `app/libs/atak-civ/`.

## Trust / update server

MeshCore **does not** ship `atakmaps-ca.p12` or configure ATAK’s UV-PRO update-server trust path. Do not re-add unless implementing a dedicated MeshCore OTA catalog.

## ProGuard

Keep `-keep class com.atakmaps.meshcore.plugin.** { *; }` in `app/proguard-gradle.txt`.

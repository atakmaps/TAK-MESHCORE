# Building TAK-MeshCore

The plugin targets **ATAK-CIV 5.6.0** and runs on any 5.6.0.x build, including
5.6.0.24 (the manifest declares `com.atakmap.app@5.6.0.CIV`).

There are two ways to get an installable plugin. Which one you need depends on
which ATAK is on your phone:

| Your ATAK | What you need | How |
|---|---|---|
| ATAK-CIV from the Play Store / tak.gov (normal install) | A **TAK.gov-signed** plugin | [Option A: TPC submission](#option-a-tpc-submission-recommended) — no Mac or compiler needed |
| Developer ATAK from the SDK | A locally built debug plugin | [Option B: build on a Mac](#option-b-build-on-a-mac) |

A plugin you compile yourself is signed with the SDK's development key. Release
ATAK will not load it (it shows as *Incompatible*); only a developer build of
ATAK will. Use Option A for phones in the field.

---

## Option A: TPC submission (recommended)

TAK.gov's Third-Party Plugin pipeline (TPC) compiles the source and signs the
APK so stock ATAK-CIV will load it. You can do this from a phone or any browser.

1. Get the source zip `MeshCore-<version>-ATAK-5.6.0-source.zip`.
   - From a Mac with the repo: `ATAK_VERSION=5.6.0 PLUGINS_DIR=./submission ./tools/package-submission.sh`
     (the script requires the changes to be committed first).
2. Sign in to tak.gov and open the TPC third-party plugin submission page
   (the same place earlier MeshCore releases were signed).
3. Upload the zip, select **ATAK-CIV 5.6.0**, and submit.
4. When the build finishes, download the signed zip and install the
   `ATAK-Plugin-Meshcore-*-tpc-*-civ-release.apk` inside it on the phone.

---

## Option B: build on a Mac

### 1. Install the tools (once)

```bash
# Homebrew: https://brew.sh
brew install --cask temurin@17            # JDK 17
brew install --cask android-commandlinetools
brew install --cask android-platform-tools # adb, for installing on the phone
```

Point the shell at them (add to `~/.zshrc`):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
```

Install the Android platform the plugin compiles against and accept licenses:

```bash
sdkmanager "platforms;android-33" "build-tools;35.0.0" "platform-tools"
yes | sdkmanager --licenses
```

*(Alternative: install Android Studio, open this folder, and let it install
the SDK. Then skip to step 2.)*

### 2. Get the ATAK SDK

1. Sign in to tak.gov and download **ATAK-CIV 5.6.0.24 SDK**.
2. Unzip it, e.g. to `~/Documents/ATAK/Versions/ATAK-CIV-5.6.0.24-SDK/`.
   It must contain `main.jar` and `android_keystore`.

### 3. Get the source

```bash
git clone https://github.com/atakmaps/TAK-MESHCORE.git
cd TAK-MESHCORE
# If you have the patch file instead of a pushed branch:
#   git am /path/to/0001-*.patch /path/to/0002-*.patch
```

### 4. Link the SDK and Android SDK

```bash
# Copies main.jar + android_keystore into app/libs/atak-civ/
ATAK_560_SDK=~/Documents/ATAK/Versions/ATAK-CIV-5.6.0.24-SDK ./tools/use-atak-sdk.sh 5.6.0

# Tell Gradle where the Android SDK is
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

### 5. Test and build

```bash
./gradlew :app:testCivDebugUnitTest      # unit tests (should all pass)
./gradlew :app:assembleCivDebug          # builds the APK
```

The APK is written to `app/build/outputs/apk/civ/debug/`.

### 6. Install on the phone

1. On the phone: **Settings → About phone → tap Build number 7 times**, then
   **Settings → Developer options → USB debugging: on**.
2. Plug the phone into the Mac with USB-C and accept the prompt on the phone.
3. Install:

   ```bash
   adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-Meshcore-*.apk
   ```

4. Open ATAK → **Plugins** and load **MeshCore**. Remember: this works only in
   the developer ATAK from the SDK (see the table at the top).

### Troubleshooting

- **`atak-gradle-takdev.jar not found`** — run step 4 again; check `app/libs/atak-civ/` has `main.jar`.
- **`SDK location not found`** — `local.properties` is missing or `ANDROID_HOME` is wrong.
- **`Unsupported class file major version`** — Gradle is using the wrong JDK; check `java -version` shows 17.
- **Plugin shows *Incompatible* in ATAK** — the phone runs release ATAK; use Option A.

---

## Compiling on an Android phone

Not practical: the build needs the full Android SDK and Gradle, which don't run
well on a phone. From a phone, use **Option A** — upload the source zip to TPC
in the browser and install the signed APK it returns.

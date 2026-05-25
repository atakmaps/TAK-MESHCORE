#!/usr/bin/env bash
#
# TPP-style source archive for MeshCore ATAK plugin:
#   - Zip name:  MeshCore-<version>-ATAK-<atak>-source.zip
#   - Single root folder inside zip:  MeshCore-<version>/
#
# Outputs default to TAK Submissions under ATAK Plugins.
# Override: PLUGINS_DIR=/path ./tools/package-submission.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PLUGIN_SLUG="${PLUGIN_SLUG:-MeshCore}"
APK_PREFIX="${APK_PREFIX:-ATAK-Plugin-Meshcore}"

VERSION="$(sed -n 's/.*ext\.PLUGIN_VERSION *= *"\([^"]*\)".*/\1/p' build.gradle | head -1)"
ATAK_VER="$(sed -n 's/.*ext\.ATAK_VERSION *= *"\([^"]*\)".*/\1/p' build.gradle | head -1)"
if [[ -z "${VERSION}" || -z "${ATAK_VER}" ]]; then
  echo "Could not read PLUGIN_VERSION / ATAK_VERSION from build.gradle" >&2
  exit 1
fi

TPP_ROOT="${TPP_ROOT:-${PLUGIN_SLUG}-${VERSION}}"
SHA="$(git rev-parse --short HEAD)"
FULLSHA="$(git rev-parse HEAD)"
STAMP="$(date -u +%Y%m%dT%H%MZ)"

PLUGINS_DIR="${PLUGINS_DIR:-${HOME}/Documents/ATAK/Plugins/TAK Submissions}"
mkdir -p "${PLUGINS_DIR}"

SOURCE_ZIP="${PLUGIN_SLUG}-${VERSION}-ATAK-${ATAK_VER}-source.zip"
SOURCE_PATH="${PLUGINS_DIR}/${SOURCE_ZIP}"

git archive --format=zip --prefix="${TPP_ROOT}/" -o "${SOURCE_PATH}" HEAD

APK=""
shopt -s nullglob
for f in "${ROOT}"/app/build/outputs/apk/civ/release/${APK_PREFIX}-"${VERSION}"-*-civ-release.apk; do
  APK="$f"
  break
done
shopt -u nullglob

APK_NAME=""
if [[ -n "${APK}" && -f "${APK}" ]]; then
  APK_NAME="$(basename "${APK}")"
  cp -f "${APK}" "${PLUGINS_DIR}/${APK_NAME}"
fi

MANIFEST="${PLUGINS_DIR}/${PLUGIN_SLUG}-${VERSION}-submission-MANIFEST.txt"
cat > "${MANIFEST}" << EOF
${PLUGIN_SLUG} ${VERSION} submission pack (local)
Package: com.atakmaps.meshcore.plugin
ATAK target: ${ATAK_VER}
Git: ${FULLSHA} (${SHA})
UTC: ${STAMP}
Output directory: ${PLUGINS_DIR}/

TPC portal (first-time plugin):
  - Plugin name: MeshCore
  - Package / applicationId: com.atakmaps.meshcore.plugin
  - ATAK compatibility: ${ATAK_VER} CIV

  - ${SOURCE_ZIP}
      TPP source archive; root folder ${TPP_ROOT}/
      Includes gradle/takdev/atak-gradle-takdev.jar so TPC can use classpath files(...)
      without Artifactory init.d (see root build.gradle).
EOF
if [[ -n "${APK_NAME}" ]]; then
  cat >> "${MANIFEST}" << EOF
  - ${APK_NAME}
      Local assembleCivRelease (not TPC-signed). Upload ${SOURCE_ZIP} to TPC, not this APK.
EOF
else
  cat >> "${MANIFEST}" << EOF
  - (no APK — run ./gradlew assembleCivRelease first)
EOF
fi

echo "Wrote:"
echo "  ${SOURCE_PATH}"
[[ -n "${APK_NAME}" ]] && echo "  ${PLUGINS_DIR}/${APK_NAME}"
echo "  ${MANIFEST}"
ls -la "${SOURCE_PATH}" "${PLUGINS_DIR}/${APK_NAME}" 2>/dev/null || ls -la "${SOURCE_PATH}"

#!/usr/bin/env bash
# Build TPC submission zips (and local release APKs) for ATAK 5.5.1 and 5.6.0.
#
# Each source zip gets atak.version=<target> injected into gradle.properties inside
# the archive (required for TPC to compile against the correct ATAK SDK).
#
# Usage (from repo root):
#   ./tools/build-submission-zips.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"

echo "========== MeshCore: ATAK 5.5.1 =========="
"${ROOT}/tools/use-atak-sdk.sh" 5.5.1
./gradlew :app:assembleCivRelease -Patak.version=5.5.1
ATAK_VERSION=5.5.1 "${ROOT}/tools/package-submission.sh"

echo ""
echo "========== MeshCore: ATAK 5.6.0 =========="
"${ROOT}/tools/use-atak-sdk.sh" 5.6.0
./gradlew :app:assembleCivRelease -Patak.version=5.6.0
ATAK_VERSION=5.6.0 "${ROOT}/tools/package-submission.sh"

"${ROOT}/tools/use-atak-sdk.sh" 5.5.1
echo ""
echo "Done. Both submission zips are in Plugins/TAK Submissions/; SDK restored to 5.5.1."

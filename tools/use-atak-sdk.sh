#!/usr/bin/env bash
# Switch app/libs/atak-civ/ between ATAK 5.5.1 and 5.6.0 SDK jars for local builds.
#
# Usage:
#   ./tools/use-atak-sdk.sh status
#   ./tools/use-atak-sdk.sh 5.5.1
#   ./tools/use-atak-sdk.sh 5.6.0
#
# Override 5.6 SDK path:
#   ATAK_560_SDK=/path/to/ATAK-CIV-5.6.0.18-SDK ./tools/use-atak-sdk.sh 5.6.0
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIB="${ROOT}/app/libs/atak-civ"
BACKUP="${LIB}/backup-5.5.1"
SDK_560="${ATAK_560_SDK:-${HOME}/Documents/ATAK/Versions/ATAK-CIV-5.6.0.18-SDK}"

usage() {
  cat <<'EOF'
Usage: ./tools/use-atak-sdk.sh <status|5.5.1|5.6.0>

  status   Show active SDK and configured paths
  5.5.1    Use backed-up 5.5.1 main.jar + android_keystore
  5.6.0    Use ATAK-CIV-5.6.0.18-SDK main.jar + android_keystore
EOF
}

ensure_backup() {
  mkdir -p "${BACKUP}"
  if [[ ! -f "${BACKUP}/main.jar" ]] && [[ -f "${LIB}/main.jar" ]]; then
    cp -f "${LIB}/main.jar" "${LIB}/android_keystore" "${BACKUP}/"
    echo "Saved current SDK as 5.5.1 backup: ${BACKUP}"
  fi
  if [[ ! -f "${BACKUP}/main.jar" ]]; then
    echo "No 5.5.1 backup at ${BACKUP}/main.jar — copy your 5.5.1 SDK there first." >&2
    exit 1
  fi
}

activate_551() {
  ensure_backup
  cp -f "${BACKUP}/main.jar" "${BACKUP}/android_keystore" "${LIB}/"
  echo "Active SDK: 5.5.1 (${LIB})"
}

activate_560() {
  if [[ ! -f "${SDK_560}/main.jar" ]] || [[ ! -f "${SDK_560}/android_keystore" ]]; then
    echo "5.6 SDK not found at ${SDK_560} (need main.jar + android_keystore)" >&2
    exit 1
  fi
  ensure_backup
  cp -f "${SDK_560}/main.jar" "${SDK_560}/android_keystore" "${LIB}/"
  echo "Active SDK: 5.6.0 (${SDK_560} -> ${LIB})"
}

sdk_fingerprint() {
  if [[ ! -f "${LIB}/main.jar" ]]; then
    echo "missing"
    return
  fi
  stat -c '%s' "${LIB}/main.jar" 2>/dev/null || stat -f '%z' "${LIB}/main.jar"
}

status() {
  local active_size backup_size sdk560_size
  active_size="$(sdk_fingerprint)"
  backup_size="$([[ -f "${BACKUP}/main.jar" ]] && stat -c '%s' "${BACKUP}/main.jar" 2>/dev/null || stat -f '%z' "${BACKUP}/main.jar" 2>/dev/null || echo missing)"
  sdk560_size="$([[ -f "${SDK_560}/main.jar" ]] && stat -c '%s' "${SDK_560}/main.jar" 2>/dev/null || stat -f '%z' "${SDK_560}/main.jar" 2>/dev/null || echo missing)"
  echo "lib:     ${LIB}/main.jar (${active_size} bytes)"
  echo "backup:  ${BACKUP}/main.jar (${backup_size} bytes)"
  echo "5.6 SDK: ${SDK_560}/main.jar (${sdk560_size} bytes)"
  if [[ "${active_size}" == "${sdk560_size}" ]] && [[ "${active_size}" != "missing" ]]; then
    echo "active:  5.6.0"
  elif [[ "${active_size}" == "${backup_size}" ]] && [[ "${active_size}" != "missing" ]]; then
    echo "active:  5.5.1"
  else
    echo "active:  unknown (compare sizes above)"
  fi
}

cmd="${1:-status}"
case "${cmd}" in
  status) status ;;
  5.5.1|551) activate_551 ;;
  5.6.0|560) activate_560 ;;
  -h|--help|help) usage ;;
  *) usage; exit 1 ;;
esac

#!/usr/bin/env bash
# Build a signed release APK. Does not create a keystore.
#
#   ./scripts/generate-keystore.sh
#   ./scripts/build-signed-apk.sh
#
# Signing values come from keystore.properties, or from KEYSTORE_FILE,
# KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD when all four are set.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROPERTIES_FILE="$ROOT/keystore.properties"
DIST_DIR="$ROOT/dist"
CLEAN=0

usage() {
  cat <<'EOF'
Usage: ./scripts/build-signed-apk.sh [--clean]

  --clean      Run ./gradlew clean before assembleRelease
  -h, --help   Show this help

Requires a keystore and keystore.properties from:
  ./scripts/generate-keystore.sh

Or set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD.

The signed APK is written to dist/vobizvoip-<version>-release.apk.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=1 ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

read_property() {
  local key="$1"
  local file="$2"
  awk -v k="$key" '
    $0 ~ /^[[:space:]]*#/ { next }
    {
      line = $0
      sub(/\r$/, "", line)
      split(line, parts, "=")
      key = parts[1]
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key == k) {
        sub(/^[^=]*=/, "", line)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
        print line
        exit
      }
    }
  ' "$file"
}

require_cmd java

if [[ -n "${KEYSTORE_FILE:-}" && -n "${KEYSTORE_PASSWORD:-}" && -n "${KEY_ALIAS:-}" && -n "${KEY_PASSWORD:-}" ]]; then
  STORE_FILE="$KEYSTORE_FILE"
  STORE_PASSWORD="$KEYSTORE_PASSWORD"
  KEY_ALIAS_VALUE="$KEY_ALIAS"
  KEY_PASSWORD_VALUE="$KEY_PASSWORD"
elif [[ -f "$PROPERTIES_FILE" ]]; then
  STORE_FILE="$(read_property storeFile "$PROPERTIES_FILE")"
  STORE_PASSWORD="$(read_property storePassword "$PROPERTIES_FILE")"
  KEY_ALIAS_VALUE="$(read_property keyAlias "$PROPERTIES_FILE")"
  KEY_PASSWORD_VALUE="$(read_property keyPassword "$PROPERTIES_FILE")"
  KEY_PASSWORD_VALUE="${KEY_PASSWORD_VALUE:-$STORE_PASSWORD}"
else
  echo "No signing credentials found." >&2
  echo "Run: ./scripts/generate-keystore.sh" >&2
  echo "That creates the keystore and writes keystore.properties." >&2
  exit 1
fi

if [[ "$STORE_FILE" != /* ]]; then
  STORE_FILE="$ROOT/$STORE_FILE"
fi

if [[ ! -f "$STORE_FILE" ]]; then
  echo "Keystore not found: $STORE_FILE" >&2
  echo "Run: ./scripts/generate-keystore.sh" >&2
  exit 1
fi

if [[ -z "$STORE_PASSWORD" || -z "$KEY_ALIAS_VALUE" || -z "$KEY_PASSWORD_VALUE" ]]; then
  echo "Incomplete signing credentials in $PROPERTIES_FILE." >&2
  echo "Run: ./scripts/generate-keystore.sh" >&2
  exit 1
fi

export KEYSTORE_FILE="$STORE_FILE"
export KEYSTORE_PASSWORD="$STORE_PASSWORD"
export KEY_ALIAS="$KEY_ALIAS_VALUE"
export KEY_PASSWORD="$KEY_PASSWORD_VALUE"

if [[ ! -x "$ROOT/gradlew" ]]; then
  echo "gradlew is missing or not executable at $ROOT/gradlew" >&2
  exit 1
fi

GRADLEW=("$ROOT/gradlew" --no-daemon)
if [[ "$CLEAN" -eq 1 ]]; then
  "${GRADLEW[@]}" clean
fi

echo "Assembling signed release APK..."
"${GRADLEW[@]}" :app:assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
  echo "Gradle finished but $APK was not produced." >&2
  echo "If you see app-release-unsigned.apk, signing credentials were not applied." >&2
  exit 1
fi

VERSION_NAME="$(
  awk -F'"' '/versionName[[:space:]]*=/ { print $2; exit }' "$ROOT/app/build.gradle.kts"
)"
VERSION_NAME="${VERSION_NAME:-unknown}"
mkdir -p "$DIST_DIR"
DEST="$DIST_DIR/vobizvoip-${VERSION_NAME}-release.apk"
cp -f "$APK" "$DEST"

echo
echo "Signed APK: $DEST"

if command -v apksigner >/dev/null 2>&1; then
  apksigner verify --print-certs "$DEST"
elif [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  SDK_ROOT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  APKSIGNER="$(ls -1 "$SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort | tail -n 1 || true)"
  if [[ -n "$APKSIGNER" ]]; then
    "$APKSIGNER" verify --print-certs "$DEST"
  fi
fi

echo "Install with: adb install -r \"$DEST\""

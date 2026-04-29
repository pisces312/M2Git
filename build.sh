#!/usr/bin/env bash
# M2Git Build Script
# Usage: ./build.sh [arm64|x86_64]   (default: arm64)

set -e

ABI="${1:-arm64}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
BUILD_DIR="$APP_DIR/build/outputs/apk/release"
KEYSTORE="D:/nili/my-git-projects/my-backup/backup-settings/my-android-release.keystore"
KEYSTORE_PASS="${KEY_STORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-pisces312}"
BUILD_TOOLS="D:/nili/dev/android_sdk/build-tools/34.0.0"

# Auto-detect version from build.gradle
VERSION=""
GRADLE_FILE="$APP_DIR/build.gradle"
if [[ -f "$GRADLE_FILE" ]]; then
    # Extract versionName using grep + sed (Git Bash compatible)
    VERSION=$(grep 'versionName' "$GRADLE_FILE" | head -1 | sed 's/.*versionName *"\([^"]*\)".*/\1/')
fi
if [[ -z "$VERSION" ]]; then
    VERSION="1.8.5"
fi
VERSION="v$VERSION"

# Validate ABI
if [[ "$ABI" != "arm64" && "$ABI" != "x86_64" ]]; then
    echo "Usage: $0 [arm64|x86_64]"
    exit 1
fi

ABI_FILTER=""
if [[ "$ABI" == "arm64" ]]; then
    ABI_FILTER="arm64-v8a"
elif [[ "$ABI" == "x86_64" ]]; then
    ABI_FILTER="x86_64"
fi

echo "=== Building M2Git $VERSION for $ABI ($ABI_FILTER) ==="

# Validate env
if [[ -z "$KEYSTORE_PASS" ]]; then
    echo "ERROR: KEY_STORE_PASSWORD env var not set"
    exit 1
fi

# Build
cd "$PROJECT_DIR"
./gradlew assembleRelease -PbuildAbi="$ABI_FILTER"

# Find unsigned APK
UNSIGNED_APK="$BUILD_DIR/app-${ABI_FILTER}-release-unsigned.apk"
if [[ ! -f "$UNSIGNED_APK" ]]; then
    echo "ERROR: Unsigned APK not found at $UNSIGNED_APK"
    ls "$BUILD_DIR" 2>/dev/null || true
    exit 1
fi

# zipalign + sign
ALIGNED_APK="$BUILD_DIR/${ABI}-aligned.apk"
SIGNED_APK="$PROJECT_DIR/M2Git-${VERSION}-${ABI}-signed.apk"

echo "=== Aligning ==="
"$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

echo "=== Signing ==="
java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

# Cleanup temp
rm -f "$ALIGNED_APK"

SIZE=$(du -h "$SIGNED_APK" | cut -f1)
echo "=== Done: $SIGNED_APK ($SIZE) ==="

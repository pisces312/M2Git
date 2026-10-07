#!/usr/bin/env bash
# M2Git Build Script
# Usage: ./build.sh [debug|release] [arm64|x86_64]   (default: release arm64)
#
# Examples:
#   ./build.sh                  # Build release, arm64
#   ./build.sh debug            # Build debug, arm64
#   ./build.sh release x86_64   # Build release, x86_64
#   ./build.sh debug arm64      # Build debug, arm64

set -e

BUILD_TYPE="${1:-release}"
ABI="${2:-arm64}"
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

# Validate BUILD_TYPE
case "$BUILD_TYPE" in
    debug|release) ;;
    *) echo "Usage: $0 [debug|release] [arm64|x86_64]"; exit 1 ;;
esac

# Build output directory
BUILD_DIR="$APP_DIR/build/outputs/apk/${BUILD_TYPE}"

# Validate ABI
case "$ABI" in
    arm64|x86_64) ;;
    *) echo "Usage: $0 [debug|release] [arm64|x86_64]"; exit 1 ;;
esac

ABI_FILTER=""
if [[ "$ABI" == "arm64" ]]; then
    ABI_FILTER="arm64-v8a"
elif [[ "$ABI" == "x86_64" ]]; then
    ABI_FILTER="x86_64"
fi

# Capitalize for Gradle task name
BUILD_TYPE_CAP="$(echo "$BUILD_TYPE" | sed 's/\b./\u&/')"
GRADLE_TASK="assemble${BUILD_TYPE_CAP}"

echo "=== Building M2Git $VERSION for $BUILD_TYPE / $ABI ($ABI_FILTER) ==="

# Signing: only needed for release
if [[ "$BUILD_TYPE" == "release" ]]; then
    if [[ -z "$KEYSTORE_PASS" ]]; then
        echo "ERROR: KEY_STORE_PASSWORD env var not set"
        exit 1
    fi
fi

# Build
cd "$PROJECT_DIR"
./gradlew "$GRADLE_TASK" -PbuildAbi="$ABI_FILTER"

# Find unsigned APK (debug uses different naming pattern)
UNSIGNED_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
    UNSIGNED_APK="$BUILD_DIR/app-${ABI_FILTER}-release-unsigned.apk"
else
    UNSIGNED_APK="$BUILD_DIR/app-${ABI_FILTER}-debug.apk"
fi

if [[ ! -f "$UNSIGNED_APK" ]]; then
    echo "ERROR: APK not found at $UNSIGNED_APK"
    ls "$BUILD_DIR" 2>/dev/null || true
    exit 1
fi

# Signing: only needed for release
SIGNED_APK=""
ALIGNED_APK=""
if [[ "$BUILD_TYPE" == "release" ]]; then
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
else
    # Debug APK: just copy with a nicer name
    SIGNED_APK="$PROJECT_DIR/M2Git-${VERSION}-${ABI}-debug.apk"
    cp -f "$UNSIGNED_APK" "$SIGNED_APK"
fi

SIZE=$(du -h "$SIGNED_APK" | cut -f1)
echo "=== Done: $SIGNED_APK ($SIZE) ==="

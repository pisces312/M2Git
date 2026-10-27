#!/usr/bin/env bash
# M2Git fast build entry points for agent and manual inner loops.
#
# `check` covers resource merge, R generation, data binding and Java/Kotlin
# compilation. It does NOT cover AndroidManifest final merge, dexing, packaging
# or signing; use `apk` after manifest edits.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=""
OFFLINE=1
CLEAN=0

usage() {
    cat <<'EOF'
Usage: ./scripts/dev.sh [--online] [--clean] <check|apk|install>

  check    compile sources + resources (no dex/package/signing)
  apk      assemble the debug APK
  install  assemble then adb install -r (respects ANDROID_SERIAL)

  --online allow network dependency resolution (default: --offline)
  --clean  run clean first; avoid this in normal loops
EOF
}

for arg in "$@"; do
    case "$arg" in
        check|apk|install) MODE="$arg" ;;
        --online) OFFLINE=0 ;;
        --clean) CLEAN=1 ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "error: unknown argument: $arg" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$MODE" ]; then
    usage >&2
    exit 2
fi

GRADLE_ARGS=(--console=plain)
if [ "$OFFLINE" -eq 1 ]; then
    GRADLE_ARGS+=(--offline)
fi

run_gradle() {
    echo ">>> ./gradlew $* ${GRADLE_ARGS[*]}"
    local start=$SECONDS
    (cd "$PROJECT_DIR" && ./gradlew "$@" "${GRADLE_ARGS[@]}")
    echo "--- gradle ok in $((SECONDS - start))s"
}

find_apk() {
    find "$PROJECT_DIR/app/build/outputs/apk/debug" -maxdepth 1 -name '*.apk' -print -quit 2>/dev/null || true
}

if [ "$CLEAN" -eq 1 ]; then
    echo "note: clean requested; normal inner loops should not need it"
    run_gradle clean
fi

case "$MODE" in
    check)
        run_gradle :app:compileDebugSources
        echo "check ok: resources + data binding + java/kotlin compiled"
        echo "note: manifest final merge, dex, packaging and signing are not covered"
        ;;
    apk)
        run_gradle :app:assembleDebug
        APK="$(find_apk)"
        [ -n "$APK" ] && echo "apk: $APK"
        ;;
    install)
        run_gradle :app:assembleDebug
        APK="$(find_apk)"
        if [ -z "$APK" ]; then
            echo "error: debug APK not found under app/build/outputs/apk/debug" >&2
            exit 1
        fi
        if ! command -v adb >/dev/null 2>&1; then
            echo "error: adb not found on PATH" >&2
            exit 1
        fi
        if command -v cygpath >/dev/null 2>&1; then
            APK_FOR_ADB="$(cygpath -w "$APK")"
        else
            APK_FOR_ADB="$APK"
        fi
        if [ -n "${ANDROID_SERIAL:-}" ]; then
            adb -s "$ANDROID_SERIAL" install -r "$APK_FOR_ADB"
        else
            adb install -r "$APK_FOR_ADB"
        fi
        echo "installed: $APK"
        ;;
esac

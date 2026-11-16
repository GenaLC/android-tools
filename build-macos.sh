#!/bin/bash
#
# macOS build script for the SIP-GSM Gateway APK + Magisk module.
#
# It mirrors build.sh (the Linux script) step for step — same Magisk module
# layout, same APK placement, same ABI handling — but does the platform bits
# macOS needs:
#
#   * finds a JDK 17 (Homebrew openjdk@17, then JAVA_HOME, then java on PATH)
#   * finds the Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / ~/Library/Android/sdk)
#   * writes local.properties WITHOUT a UTF-8 BOM
#   * does not run `sudo`, does not need `zip`/`unzip` from apt
#
# Usage:
#   ./build-macos.sh                 # debug APK + gsm2sip-magisk.zip
#   ./build-macos.sh release         # release APK + gsm2sip-magisk.zip
#   ./build-macos.sh release --install   # also install over ADB if one device is attached
#
# Environment:
#   TARGET_ABI=arm64-v8a|armeabi-v7a   which ABI to build/verify the tools for
#   SKIP_INSTALL=1                     build artifacts only (default on macOS)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Arguments ───────────────────────────────────────

BUILD_TYPE="debug"
DO_INSTALL=0
for ARG in "$@"; do
    case "$ARG" in
        debug|release) BUILD_TYPE="$ARG" ;;
        --install)     DO_INSTALL=1 ;;
        -h|--help)
            sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "ERROR: unknown argument: $ARG" >&2
            exit 1 ;;
    esac
done

# Installing to "whatever is plugged in" is the wrong default once more than
# one phone is on the bus.  macOS builds therefore only install when asked
# explicitly with --install.
if [ "$DO_INSTALL" -eq 0 ]; then
    export SKIP_INSTALL=1
fi

TARGET_ABI="${TARGET_ABI:-arm64-v8a}"

# ── Locate the JDK ──────────────────────────────────

find_java() {
    local CANDIDATES=(
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        "/opt/homebrew/opt/openjdk@17"
        "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        "/usr/local/opt/openjdk@17"
        "${JAVA_HOME:-}"
    )
    local CAND
    for CAND in "${CANDIDATES[@]}"; do
        [ -n "$CAND" ] || continue
        if [ -x "$CAND/bin/java" ]; then
            export JAVA_HOME="$CAND"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done

    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: No JDK found. Install one with:" >&2
        echo "         brew install openjdk@17" >&2
        exit 1
    fi

    local VER
    VER=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [ -z "$VER" ] || [ "$VER" -lt 17 ] 2>/dev/null; then
        echo "ERROR: JDK 17+ required (found: ${VER:-unknown})." >&2
        echo "         brew install openjdk@17" >&2
        exit 1
    fi
    echo "Java:   $(java -version 2>&1 | head -1)"
    echo "JAVA_HOME=$JAVA_HOME"
}

# ── Locate the Android SDK ──────────────────────────

find_android_sdk() {
    local CANDIDATES=(
        "${ANDROID_HOME:-}"
        "${ANDROID_SDK_ROOT:-}"
        "$HOME/Library/Android/sdk"
        "$HOME/Android/Sdk"
        "$HOME/android-sdk"
    )
    local CAND
    for CAND in "${CANDIDATES[@]}"; do
        [ -n "$CAND" ] || continue
        if [ -d "$CAND" ]; then
            export ANDROID_HOME="$CAND"
            break
        fi
    done

    if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
        echo "ERROR: Android SDK not found. Set ANDROID_HOME or install it with:" >&2
        echo "         brew install --cask android-commandlinetools" >&2
        exit 1
    fi
    export ANDROID_SDK_ROOT="$ANDROID_HOME"

    if [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
        echo "ERROR: $ANDROID_HOME/platforms/android-35 is missing." >&2
        echo "       Install it with:" >&2
        echo "         sdkmanager 'platforms;android-35' 'build-tools;35.0.0'" >&2
        exit 1
    fi
    echo "SDK:    $ANDROID_HOME"
}

# ── local.properties (no BOM!) ───────────────────────
#
# AGP reads local.properties as a properties file, and a UTF-8 BOM makes it
# see the key as "﻿sdk.dir" instead of "sdk.dir".  The build then fails with
# "SDK location not found" even though the file looks perfectly fine in an
# editor.  printf (not echo, not a text editor) writes the file without a BOM.

write_local_properties() {
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
    # Fail loudly if a BOM ever creeps in.
    if head -c 3 local.properties | od -An -tx1 | grep -q "ef bb bf"; then
        echo "ERROR: local.properties has a UTF-8 BOM; rewriting." >&2
        printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
    fi
    echo "local.properties: sdk.dir=$ANDROID_HOME (no BOM)"
}

# ── Debug keystore ──────────────────────────────────
#
# The release build is signed with the debug keystore on purpose, so that a
# release APK can replace a debug one inside the Magisk module without a
# signature mismatch (see app/build.gradle.kts).  It is never committed.

ensure_debug_keystore() {
    local KS="$HOME/.android/debug.keystore"
    if [ -f "$KS" ]; then
        echo "Keystore: $KS"
        return 0
    fi
    echo "Keystore: $KS missing — creating it with keytool."
    mkdir -p "$HOME/.android"
    keytool -genkeypair -v -keystore "$KS" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
    echo "Keystore created: $KS"
}

# ── Build the APK ───────────────────────────────────

build_apk() {
    echo ""
    echo "=== Building $BUILD_TYPE APK ==="
    echo ""
    # The incremental dexer leaves a stale "<class> 2.dex" behind when a
    # class is renamed or its companion changes shape, and the merge then
    # fails with "Type ... is defined multiple times".  The same thing
    # happens to the test reports, where a stale "X 2.xml" makes a test
    # that has been fixed look like it is still failing.  Clearing both
    # costs a few seconds and makes the build reproducible.
    rm -rf app/build/intermediates/project_dex_archive app/build/intermediates/dex 2>/dev/null || true
    rm -rf app/build/test-results 2>/dev/null || true
    if [ "$BUILD_TYPE" = "release" ]; then
        ./gradlew assembleRelease --no-daemon
        # Signed via the "shared" signingConfig, so the output is
        # app-release.apk rather than app-release-unsigned.apk.  An unsigned
        # APK is rejected by PackageManager with
        # INSTALL_PARSE_FAILED_NO_CERTIFICATES and is useless in the priv-app
        # module.
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        [ -f "$APK_PATH" ] || APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    else
        ./gradlew assembleDebug --no-daemon
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    fi

    if [ -f "$APK_PATH" ]; then
        echo ""
        echo "APK built: $APK_PATH"
        cp "$APK_PATH" "$SCRIPT_DIR/gateway.apk"
        echo "Copied to: $SCRIPT_DIR/gateway.apk"
    else
        echo "ERROR: APK not found at $APK_PATH" >&2
        exit 1
    fi
}

# ── Tool binaries ───────────────────────────────────
#
# The ALSA control ioctls encode the size of structs that contain `long`, so
# an arm64 binary and an armeabi-v7a one speak different ioctl ABIs and only
# the matching one works.  Getting this wrong is quiet, not loud: the app
# checks only the executable bit, believes it has tinymix, and then fails
# every mixer command in silence.
#
# The module ships both variants and install.sh picks the right one at flash
# time from ro.product.cpu.abi.  Here we only make sure the right bytes are in
# the right file.

arch_signature() {
    case "$1" in
        arm64-v8a|aarch64|arm64) echo "ARM aarch64" ;;
        armeabi-v7a|arm|arm32)   echo "ELF 32-bit.*ARM" ;;
        *) echo "" ;;
    esac
}

# verify_arch <file> <arch> <label>
verify_arch() {
    local FILE="$1" ARCH="$2" LABEL="$3"
    local SIG
    SIG=$(arch_signature "$ARCH")
    if [ -z "$SIG" ]; then
        echo "ERROR: unknown TARGET_ABI: $ARCH" >&2
        exit 1
    fi
    if [ ! -f "$FILE" ]; then
        echo "ERROR: missing $LABEL: $FILE" >&2
        echo "       Install Go and run build.sh to rebuild the tools, or drop a" >&2
        echo "       pre-built binary in place." >&2
        exit 1
    fi
    if ! file "$FILE" | grep -qE "$SIG"; then
        echo "ERROR: $LABEL ($FILE) is not a $ARCH binary:" >&2
        echo "       $(file "$FILE")" >&2
        echo "       The wrong-ABI mixer binary fails silently on device." >&2
        exit 1
    fi
}

check_tools() {
    echo ""
    echo "=== Checking tool binaries (TARGET_ABI=$TARGET_ABI) ==="
    # arm64 variants
    verify_arch "$SCRIPT_DIR/magisk/tinymix"  "arm64-v8a"   "tinymix (arm64)"
    verify_arch "$SCRIPT_DIR/magisk/tinycap"  "arm64-v8a"   "tinycap (arm64)"
    # armeabi-v7a variants
    verify_arch "$SCRIPT_DIR/magisk/tinymix32" "armeabi-v7a" "tinymix32 (32-bit)"

    # tinycap only ships as an arm64 build in this tree; the 32-bit variant
    # would need Go and the alsa-lib sources.
    if [ ! -f "$SCRIPT_DIR/magisk/tinycap32" ]; then
        echo "NOTE: magisk/tinycap32 does not exist — only the arm64 tinycap ships."
        echo "      On a 32-bit device the app falls back to the ROM's own capture tool."
    else
        verify_arch "$SCRIPT_DIR/magisk/tinycap32" "armeabi-v7a" "tinycap32 (32-bit)"
    fi
    echo "Tools OK — install.sh selects tinymix/tinymix32 from ro.product.cpu.abi."
}

# abox_pos.ko needs the S10e kernel tree to build (tools/abox-pos/build.sh),
# so a prebuilt copy lives in magisk/ like tinycap; a fresh build replaces it.
copy_abox_pos() {
    local KO="$SCRIPT_DIR/tools/abox-pos/abox_pos.ko"
    if [ -f "$KO" ]; then
        cp "$KO" "$SCRIPT_DIR/magisk/abox_pos.ko"
        echo "Included abox_pos.ko from tools/abox-pos ($(du -h "$KO" | cut -f1))"
    elif [ -f "$SCRIPT_DIR/magisk/abox_pos.ko" ]; then
        echo "Using prebuilt magisk/abox_pos.ko"
    else
        echo "WARNING: no abox_pos.ko — the S10e (Exynos 9820) will have no call"
        echo "         audio path.  Build it with tools/abox-pos/build.sh."
    fi
}

# ── Build the Magisk module ─────────────────────────

build_magisk() {
    echo ""
    echo "=== Building Magisk module ==="
    echo ""

    check_tools
    copy_abox_pos

    # Copy the APK into the Magisk module as a system priv-app.  This makes
    # the app a privileged system app, enabling permissions like
    # CAPTURE_AUDIO_OUTPUT that are required for telephony audio capture.
    mkdir -p "$SCRIPT_DIR/magisk/system/priv-app/Gateway"
    cp "$SCRIPT_DIR/gateway.apk" "$SCRIPT_DIR/magisk/system/priv-app/Gateway/Gateway.apk"
    echo "Included APK as priv-app in Magisk module"

    # The module directory is the ZIP root: install.sh, module.prop,
    # sepolicy.rule, system.prop, service.sh, the priv-app permissions XML
    # under system/etc/permissions/ and the mixer/capture binaries.
    local ZIP_NAME="gsm2sip-magisk.zip"
    local LEGACY_ZIP="gateway-magisk.zip"
    cd "$SCRIPT_DIR/magisk"
    rm -f "$SCRIPT_DIR/$ZIP_NAME" "$SCRIPT_DIR/$LEGACY_ZIP"
    # -X: do not store extra file attributes (uid/gid/macOS resource forks).
    zip -rX -q "$SCRIPT_DIR/$ZIP_NAME" . \
        -x "*.DS_Store" -x "__MACOSX/*" -x "*/.DS_Store"
    # The README and the device install flow still refer to gateway-magisk.zip.
    cp "$SCRIPT_DIR/$ZIP_NAME" "$SCRIPT_DIR/$LEGACY_ZIP"
    cd "$SCRIPT_DIR"
    echo "Magisk module: $SCRIPT_DIR/$ZIP_NAME"
    echo "             (copy: $SCRIPT_DIR/$LEGACY_ZIP)"
}

# ── Install to device (if connected via ADB) ────────

install_to_device() {
    if [ -n "${SKIP_INSTALL:-}" ]; then
        echo ""
        echo "SKIP_INSTALL set — built artifacts only, nothing installed."
        return 0
    fi

    local COUNT
    COUNT=$(adb devices 2>/dev/null | grep -c "device$" || true)
    if [ "${COUNT:-0}" -gt 1 ]; then
        echo ""
        echo "$COUNT devices connected — refusing to guess which one to install to."
        echo "Install explicitly:  adb -s <serial> install -r gateway.apk"
        return 0
    fi

    if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q "device$"; then
        echo ""
        echo "=== Device detected — installing ==="
        adb install -r "$SCRIPT_DIR/gateway.apk"
        echo "APK installed."
    else
        echo ""
        echo "No ADB device connected. To install manually:"
        echo "  adb install gateway.apk"
        echo "  adb push gsm2sip-magisk.zip /sdcard/"
    fi
}

# ── Main ─────────────────────────────────────────────

echo "=== SIP-GSM Gateway Build (macOS) ==="
echo ""

find_java
find_android_sdk
write_local_properties
ensure_debug_keystore

build_apk "$BUILD_TYPE"
build_magisk
install_to_device

echo ""
echo "=== Build complete ==="
echo "  APK:    $SCRIPT_DIR/gateway.apk"
echo "  Magisk: $SCRIPT_DIR/gsm2sip-magisk.zip"
echo ""
echo "Deploy to device:"
echo "  1. adb push gsm2sip-magisk.zip /sdcard/"
echo "     Install via Magisk Manager -> Modules, then reboot"
echo "     (APK is included in the module as a priv-app)"
echo "  2. After reboot: open app, grant permissions, set as default phone app"
echo "  3. Enter SIP credentials, tap START"
echo ""
echo "NOTE: Do NOT also 'adb install' — the Magisk module installs the APK"
echo "      as a privileged system app with CAPTURE_AUDIO_OUTPUT permission."

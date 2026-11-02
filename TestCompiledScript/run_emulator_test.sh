#!/usr/bin/env bash
# OpenManager Syscall & ImGui Test Runner for Android Emulator & Physical Devices
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=========================================================="
echo "   Running OpenManager Native C/C++ & ImGui Test Script   "
echo "=========================================================="

# 1. Detect target device/emulator ABI
DEVICE_ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r\n')
echo "[*] Target device architecture: ${DEVICE_ABI}"

# 2. Select appropriate compiled binary
BINARY="${SCRIPT_DIR}/bin/${DEVICE_ABI}/test_script"
if [ ! -f "${BINARY}" ]; then
    echo "[!] Exact ABI binary not found at ${BINARY}, checking fallbacks..."
    if [ "${DEVICE_ABI}" = "x86_64" ] && [ -f "${SCRIPT_DIR}/bin/x86_64/test_script" ]; then
        BINARY="${SCRIPT_DIR}/bin/x86_64/test_script"
    elif [ "${DEVICE_ABI}" = "arm64-v8a" ] && [ -f "${SCRIPT_DIR}/bin/arm64-v8a/test_script" ]; then
        BINARY="${SCRIPT_DIR}/bin/arm64-v8a/test_script"
    elif [ -f "${SCRIPT_DIR}/bin/x86_64/test_script" ]; then
        BINARY="${SCRIPT_DIR}/bin/x86_64/test_script"
    elif [ -f "${SCRIPT_DIR}/bin/arm64-v8a/test_script" ]; then
        BINARY="${SCRIPT_DIR}/bin/arm64-v8a/test_script"
    else
        echo "[-] Error: No compiled binary found in ${SCRIPT_DIR}/bin/!"
        exit 1
    fi
fi

echo "[*] Using binary: ${BINARY}"

# 3. Push binary to /data/local/tmp on Android
echo "[*] Pushing binary to Android device (/data/local/tmp/test_script)..."
adb push "${BINARY}" /data/local/tmp/test_script
adb shell chmod 755 /data/local/tmp/test_script

# 4. Execute as standard unprivileged user
echo ""
echo ">>> [TEST 1] Executing as Unprivileged User (ADB Shell) <<<"
adb shell "/data/local/tmp/test_script"
UNPRIV_EXIT=$?
echo "[*] Unprivileged execution finished with exit code: ${UNPRIV_EXIT}"

# 5. Check if Root is available on the target
IS_ROOT=$(adb shell "id -u" | tr -d '\r\n')
echo ""
echo "[*] Current ADB user ID: ${IS_ROOT}"

if [ "${IS_ROOT}" = "0" ]; then
    echo ">>> [TEST 2] Target is natively Root (UID 0), verifying root syscalls... <<<"
    adb shell "/data/local/tmp/test_script"
elif adb shell "which su" >/dev/null 2>&1; then
    echo ">>> [TEST 2] Executing with Root Privileges via su... <<<"
    adb shell "su -c /data/local/tmp/test_script" || adb shell "su 0 /data/local/tmp/test_script"
else
    echo "[!] su binary not present in default PATH; attempting direct root execution via adb root..."
    adb root || true
    adb shell "/data/local/tmp/test_script" || true
fi

echo ""
echo "=========================================================="
echo "   All Syscall & ImGui Tests Completed on Android!        "
echo "=========================================================="

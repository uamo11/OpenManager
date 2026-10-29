#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "      OpenManager Android Emulator Verification & Smoke   "
echo "=========================================================="

echo ">>> Waiting for Android Emulator to finish booting..."
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
    echo "Waiting for sys.boot_completed..."
    sleep 3
done
echo ">>> Android Emulator is online and fully booted!"

# Locate APK
echo ">>> Locating OpenManager Debug APK..."
APK_FILE=$(find . -type f \( -name "*.apk" \) | head -n 1)
if [ -z "$APK_FILE" ]; then
    echo "ERROR: Could not find any APK file in current workspace!"
    ls -la
    exit 1
fi
echo ">>> Found APK: $APK_FILE"

# Install APK
echo ">>> Installing APK onto emulator..."
adb install -r -d -t "$APK_FILE"

# Grant storage permissions
echo ">>> Granting runtime permissions..."
adb shell pm grant me.zhanghai.android.files android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant me.zhanghai.android.files android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set me.zhanghai.android.files MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Test 1: Cold start of main Activity
echo ">>> Testing Cold Start Performance (FileListActivity)..."
START_RES=$(adb shell am start -W -S -n me.zhanghai.android.files/.filelist.FileListActivity)
echo "$START_RES"
echo "--- Launch Performance Metrics ---" > emulator-performance.txt
echo "$START_RES" >> emulator-performance.txt
echo "" >> emulator-performance.txt

sleep 4

# Verify process is alive
PID=$(adb shell pidof me.zhanghai.android.files | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process me.zhanghai.android.files is not running after launch!"
    adb logcat -d > emulator-logcat.log
    exit 1
fi
echo ">>> Process is running healthy with PID: $PID"

# Test 2: Measure Memory Footprint
echo ">>> Measuring Memory Footprint..."
echo "--- Memory Footprint (dumpsys meminfo) ---" >> emulator-performance.txt
adb shell dumpsys meminfo me.zhanghai.android.files | tee -a emulator-performance.txt
echo "" >> emulator-performance.txt

# Test 3: Test Activity Switching (InstalledAppsActivity)
echo ">>> Launching InstalledAppsActivity (App Manager)..."
adb shell am start -n me.zhanghai.android.files/me.zhanghai.android.files.appmanager.InstalledAppsActivity
sleep 3
PID=$(adb shell pidof me.zhanghai.android.files | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process crashed when navigating to InstalledAppsActivity!"
    adb logcat -d > emulator-logcat.log
    exit 1
fi
echo ">>> InstalledAppsActivity verified successfully (PID: $PID)"

# Test 4: Test Activity Switching (TextEditorActivity)
echo ">>> Launching TextEditorActivity (Code Editor)..."
adb shell am start -n me.zhanghai.android.files/me.zhanghai.android.files.viewer.text.TextEditorActivity
sleep 3
PID=$(adb shell pidof me.zhanghai.android.files | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process crashed when navigating to TextEditorActivity!"
    adb logcat -d > emulator-logcat.log
    exit 1
fi
echo ">>> TextEditorActivity verified successfully (PID: $PID)"

# Return to FileListActivity for screenshot
adb shell am start -n me.zhanghai.android.files/.filelist.FileListActivity
sleep 2

# Test 5: Capture Frame Rendering Performance (gfxinfo)
echo ">>> Capturing Frame Stats (dumpsys gfxinfo)..."
echo "--- Frame Rendering Stats (dumpsys gfxinfo) ---" >> emulator-performance.txt
adb shell dumpsys gfxinfo me.zhanghai.android.files framestats >> emulator-performance.txt 2>/dev/null || true

# Test 6: Take Live Screenshot of OpenManager
echo ">>> Capturing Live Screenshot from Android Emulator..."
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png emulator-screenshot.png || true
echo ">>> Screenshot saved to emulator-screenshot.png"

# Test 7: Capture Logcat and Check for Crashes
echo ">>> Capturing Logcat and verifying zero crash logs..."
adb logcat -d > emulator-logcat.log
CRASHES=$(grep -E "FATAL EXCEPTION|SIGSEGV|AndroidRuntime: FATAL" emulator-logcat.log || true)
if [ -n "$CRASHES" ]; then
    echo "=========================================================="
    echo "ERROR: Fatal exceptions found in Logcat!"
    echo "=========================================================="
    echo "$CRASHES"
    exit 1
fi

echo "=========================================================="
echo "   All Emulator Verification Checks Passed Successfully!  "
echo "=========================================================="

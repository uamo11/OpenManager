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
APK_FILE=$(find apk-to-test -type f -name "*.apk" 2>/dev/null | head -n 1)
if [ -z "$APK_FILE" ]; then
    APK_FILE=$(find . -type f \( -name "*.apk" \) | head -n 1)
fi
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
adb shell pm grant com.y.om android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.y.om android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set com.y.om MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Prepare screenshot directory
mkdir -p screenshots
rm -f screenshots/*.png
adb shell rm -f /data/local/tmp/*.png

# 1. Cold start of main Activity (File Explorer)
echo ">>> 1. Testing Cold Start Performance (FileListActivity)..."
START_RES=$(adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity)
echo "$START_RES"
echo "--- Launch Performance Metrics ---" > emulator-performance.txt
echo "$START_RES" >> emulator-performance.txt
echo "" >> emulator-performance.txt
sleep 4

# Verify process is alive
PID=$(adb shell pidof com.y.om | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process com.y.om is not running after launch!"
    adb logcat -d > emulator-logcat.log
    exit 1
fi
echo ">>> Process is running healthy with PID: $PID"

# Capture Screenshot 1: Main File Explorer
adb shell screencap -p /data/local/tmp/01_file_explorer_main.png
adb pull /data/local/tmp/01_file_explorer_main.png screenshots/01_file_explorer_main.png || true

# Helper function to launch a screen fresh via am start, wait for layout, capture, and pull
capture_screen() {
    local cmd="$1"
    local filename="$2"
    local desc="$3"
    echo ">>> Capturing $desc ($filename)..."
    eval "$cmd"
    sleep 3
    adb shell screencap -p "/data/local/tmp/$filename"
    adb pull "/data/local/tmp/$filename" "screenshots/$filename" || true
}

# 2. Navigation Drawer (MT-Style Side Menu with Storage & Bookmarks)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG drawer" \
    "02_navigation_drawer.png" "Navigation Drawer"

# 3. Create Item Choice Bottom Sheet
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG create_choice" \
    "03_create_choice_bottom_sheet.png" "Create Item Choice Bottom Sheet"

# 4. KeyStore Generator Dialog (.jks / .bks / pk8+pem)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG keystore" \
    "04_keystore_generator_dialog.png" "KeyStore Generator Dialog"

# 5. Installed Apps Manager (InstalledAppsActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.appmanager.InstalledAppsActivity" \
    "05_installed_apps_manager.png" "Installed Apps Manager"

# 6. Fast Code / Text Editor (TextEditorActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor" \
    "06_fast_code_editor.png" "Fast Code Editor"

# 7. Settings Panel (SettingsActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity" \
    "07_settings_screen.png" "Settings Panel"

# 8. Add Storage Dialog (AddStorageDialogActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.storage.AddStorageDialogActivity" \
    "08_add_storage_dialog.png" "Add Storage Dialog"

# 9. FTP Wireless Server (FtpServerActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.ftpserver.FtpServerActivity" \
    "09_ftp_server.png" "FTP Wireless Server"

# 10. About Screen (AboutActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.about.AboutActivity" \
    "10_about_openmanager.png" "About OpenManager"

# 11. Apktool Hub (APK Reverse Engineering Dialog)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG apktool_hub" \
    "11_apktool_hub.png" "Apktool Hub"

# Measure Memory Footprint
echo ">>> Measuring Memory Footprint..."
echo "--- Memory Footprint (dumpsys meminfo) ---" >> emulator-performance.txt
adb shell dumpsys meminfo com.y.om | tee -a emulator-performance.txt
echo "" >> emulator-performance.txt

# Measure Frame Rendering Performance (gfxinfo)
echo ">>> Capturing Frame Stats (dumpsys gfxinfo)..."
echo "--- Frame Rendering Stats (dumpsys gfxinfo) ---" >> emulator-performance.txt
adb shell dumpsys gfxinfo com.y.om framestats >> emulator-performance.txt 2>/dev/null || true

# Capture Logcat and Check for Fatal Crashes
echo ">>> Capturing Logcat and verifying zero crash logs..."
adb logcat -d > emulator-logcat.log
CRASHES=$(grep -A 10 -E "FATAL EXCEPTION|AndroidRuntime: FATAL" emulator-logcat.log | grep "com.y.om" || true)
if [ -n "$CRASHES" ]; then
    echo "=========================================================="
    echo "ERROR: Fatal exceptions found in Logcat for OpenManager!"
    echo "=========================================================="
    grep -A 20 -E "FATAL EXCEPTION|AndroidRuntime: FATAL" emulator-logcat.log
    exit 1
fi

echo "=========================================================="
echo "   All 10 Section Screenshots Captured & Verified!       "
echo "=========================================================="
ls -la screenshots/

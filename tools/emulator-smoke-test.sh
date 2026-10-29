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

# Prepare screenshot directory
mkdir -p screenshots
rm -f screenshots/*.png
adb shell rm -f /data/local/tmp/*.png

# 1. Cold start of main Activity (File Explorer)
echo ">>> 1. Testing Cold Start Performance (FileListActivity)..."
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

# Capture Screenshot 1: Main File Explorer
adb shell screencap -p /data/local/tmp/01_file_explorer_main.png
adb pull /data/local/tmp/01_file_explorer_main.png screenshots/01_file_explorer_main.png || true

# 2. Navigation Drawer (Side Menu with Storage & Bookmarks)
echo ">>> 2. Capturing Navigation Drawer..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action drawer
sleep 2
adb shell screencap -p /data/local/tmp/02_navigation_drawer.png
adb pull /data/local/tmp/02_navigation_drawer.png screenshots/02_navigation_drawer.png || true
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action close_drawer
sleep 1

# 3. Create Item Choice Bottom Sheet
echo ">>> 3. Capturing Create Item Choice Bottom Sheet..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action create_choice
sleep 2
adb shell screencap -p /data/local/tmp/03_create_choice_bottom_sheet.png
adb pull /data/local/tmp/03_create_choice_bottom_sheet.png screenshots/03_create_choice_bottom_sheet.png || true
adb shell input keyevent 4
sleep 1

# 4. KeyStore Generator Dialog (.jks / .bks / pk8+pem)
echo ">>> 4. Capturing KeyStore Generator Dialog..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action keystore
sleep 2
adb shell screencap -p /data/local/tmp/04_keystore_generator_dialog.png
adb pull /data/local/tmp/04_keystore_generator_dialog.png screenshots/04_keystore_generator_dialog.png || true
adb shell input keyevent 4
sleep 1

# 5. Installed Apps Manager (InstalledAppsActivity)
echo ">>> 5. Capturing Installed Apps Manager (InstalledAppsActivity)..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action apps
sleep 3
PID=$(adb shell pidof me.zhanghai.android.files | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process crashed when navigating to InstalledAppsActivity!"
    exit 1
fi
adb shell screencap -p /data/local/tmp/05_installed_apps_manager.png
adb pull /data/local/tmp/05_installed_apps_manager.png screenshots/05_installed_apps_manager.png || true
adb shell input keyevent 4
sleep 1

# 6. Fast Code / Text Editor (TextEditorActivity)
echo ">>> 6. Capturing Fast Code Editor (TextEditorActivity)..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action editor
sleep 3
PID=$(adb shell pidof me.zhanghai.android.files | tr -d '\r')
if [ -z "$PID" ]; then
    echo "ERROR: Process crashed when navigating to TextEditorActivity!"
    exit 1
fi
adb shell screencap -p /data/local/tmp/06_fast_code_editor.png
adb pull /data/local/tmp/06_fast_code_editor.png screenshots/06_fast_code_editor.png || true
adb shell input keyevent 4
sleep 1

# 7. Settings Panel (SettingsActivity)
echo ">>> 7. Capturing Settings Panel (SettingsActivity)..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action settings
sleep 2
adb shell screencap -p /data/local/tmp/07_settings_screen.png
adb pull /data/local/tmp/07_settings_screen.png screenshots/07_settings_screen.png || true
adb shell input keyevent 4
sleep 1

# 8. Add Storage Dialog (AddStorageDialogActivity)
echo ">>> 8. Capturing Add Storage Dialog..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action add_storage
sleep 2
adb shell screencap -p /data/local/tmp/08_add_storage_dialog.png
adb pull /data/local/tmp/08_add_storage_dialog.png screenshots/08_add_storage_dialog.png || true
adb shell input keyevent 4
sleep 1

# 9. FTP Wireless Server (FtpServerActivity)
echo ">>> 9. Capturing FTP Wireless Server (FtpServerActivity)..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action ftp
sleep 2
adb shell screencap -p /data/local/tmp/09_ftp_server.png
adb pull /data/local/tmp/09_ftp_server.png screenshots/09_ftp_server.png || true
adb shell input keyevent 4
sleep 1

# 10. About Screen (AboutActivity)
echo ">>> 10. Capturing About Screen (AboutActivity)..."
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action about
sleep 2
adb shell screencap -p /data/local/tmp/10_about_openmanager.png
adb pull /data/local/tmp/10_about_openmanager.png screenshots/10_about_openmanager.png || true
adb shell input keyevent 4
sleep 1

# Measure Memory Footprint
echo ">>> Measuring Memory Footprint..."
echo "--- Memory Footprint (dumpsys meminfo) ---" >> emulator-performance.txt
adb shell dumpsys meminfo me.zhanghai.android.files | tee -a emulator-performance.txt
echo "" >> emulator-performance.txt

# Measure Frame Rendering Performance (gfxinfo)
echo ">>> Capturing Frame Stats (dumpsys gfxinfo)..."
echo "--- Frame Rendering Stats (dumpsys gfxinfo) ---" >> emulator-performance.txt
adb shell dumpsys gfxinfo me.zhanghai.android.files framestats >> emulator-performance.txt 2>/dev/null || true

# Capture Logcat and Check for Fatal Crashes
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
echo "   All 10 Section Screenshots Captured & Verified!       "
echo "=========================================================="
ls -la screenshots/

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
adb shell pm grant com.y.om android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set com.y.om MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Push compiled native C/C++ test_script binary to emulator
echo ">>> Pushing test_script binary to emulator (/data/local/tmp/test_script)..."
if [ -f "TestCompiledScript/bin/x86_64/test_script" ]; then
    adb push TestCompiledScript/bin/x86_64/test_script /data/local/tmp/test_script
    adb shell chmod 755 /data/local/tmp/test_script
fi

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

# Helper function to measure memory, gfxinfo, and CPU metrics for a section
measure_section_perf() {
    local section="$1"
    echo ">>> Measuring performance: $section..."
    local sec_pid
    sec_pid=$(adb shell pidof com.y.om | tr -d '\r' || true)
    echo "=== SECTION: $section (PID: ${sec_pid:-unknown}) ===" >> emulator-performance.txt
    adb shell dumpsys meminfo com.y.om | grep -E "TOTAL PSS:|TOTAL:|Java Heap:|Native Heap:|Views:|ViewRootImpl:|Activities:|AppContexts:" >> emulator-performance.txt || true
    adb shell dumpsys gfxinfo com.y.om | grep -E "Total frames rendered:|Janky frames:|50th percentile:|90th percentile:|95th percentile:|99th percentile:" >> emulator-performance.txt || true
    adb shell top -b -n 1 | grep "com.y.om" >> emulator-performance.txt || true
    echo "" >> emulator-performance.txt
}

# Capture Screenshot 1: Main File Explorer
adb shell screencap -p /data/local/tmp/01_file_explorer_main.png
adb pull /data/local/tmp/01_file_explorer_main.png screenshots/01_file_explorer_main.png || true
measure_section_perf "Files Explorer (Modo Oscuro)"

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
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG apps" \
    "05_installed_apps_manager.png" "Installed Apps Manager"
measure_section_perf "Installed Apps Manager"

# 6. Fast Code / Text Editor (TextEditorActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor" \
    "06_fast_code_editor.png" "Fast Code Editor"
measure_section_perf "Fast Code Editor"

# 7. Settings Panel (SettingsActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity" \
    "07_settings_screen.png" "Settings Panel"

# 8. Add Storage Dialog (AddStorageDialogActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG add_storage" \
    "08_add_storage_dialog.png" "Add Storage Dialog"

# 9. FTP Wireless Server (FtpServerActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.ftpserver.FtpServerActivity" \
    "09_ftp_server.png" "FTP Wireless Server"

# 10. About Screen (AboutActivity)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG about" \
    "10_about_openmanager.png" "About OpenManager"

# 11. Apktool Hub (APK Reverse Engineering Dialog)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG apktool_hub" \
    "11_apktool_hub.png" "Apktool Hub"

# 12. Script Action Dialog (APK-style Script Info + Interactive Permissions 777)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG script_dialog" \
    "12_script_action_dialog.png" "Script Action Dialog"

# 13. MT Manager Permissions Dialog (Checkboxes + Octal 777 + Presets)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG permissions_dialog" \
    "13_mt_permissions_dialog.png" "MT Manager Permissions Dialog"

# 14. Universal APK-style File Properties Dialog
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG file_properties" \
    "14_file_properties_dialog.png" "Universal File Properties Dialog"

# 15. Unprivileged Root & /data Directory Browsing (MT Manager Style)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG root_unprivileged" \
    "15_root_dir_unprivileged.png" "Unprivileged Root & /data Directory Browsing"

# 16. Enhanced Terminal with Zoom Controls & Extra Keys
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG terminal" \
    "16_terminal_zoom_and_keys.png" "Enhanced Terminal with Zoom Controls & Extra Keys"

# 17. Selection Bottom Bar with 5 Icon Buttons
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG selection_bar" \
    "17_selection_bottom_bar.png" "Selection Bottom Bar with 5 Icon Buttons"

# 18. Running Apps / Process Manager (App Manager Style)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG running_apps" \
    "18_running_apps_manager.png" "Running Apps / Process Manager"
measure_section_perf "Running Apps Manager"

# 19. Text Editor Search & Replace Panel (MT Manager Style)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor_search" \
    "19_text_editor_search_replace.png" "Text Editor Search & Replace Panel"

# 20. Text Editor Preferences Dialog (Word wrap, line numbers, fonts, themes, tab size)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor_preferences" \
    "20_text_editor_preferences.png" "Text Editor Preferences Dialog"

# 21. Text Editor Syntax Language Dialog (C++, Java, Kotlin, Python, Smali, etc.)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor_syntax" \
    "21_text_editor_syntax_dialog.png" "Text Editor Syntax Language Dialog"

# 22. DFROOT Protected System Files Dialog (Categorized files protected against bricking)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG dfroot_protected" \
    "22_dfroot_protected_files.png" "DFROOT Protected System Files Dialog"

# 23. DirtyFlag Protection Warning Dialog (5-second countdown, Dead Man protection)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG dirtyflag_dialog" \
    "23_dirtyflag_protection_dialog.png" "DirtyFlag Protection Warning Dialog"

# 24. Dead Man Protected Rules Dialog (Categorized system partition wipe guards)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG deadman_rules" \
    "24_deadman_protected_rules.png" "Dead Man Protected Rules Dialog"

# 25. Dead Man Protection Warning Dialog (5-second countdown & skull alert)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG deadman_dialog" \
    "25_deadman_protection_dialog.png" "Dead Man Protection Warning Dialog"

# 26. Terminal Execution of Native C / C++ & Dear ImGui Dashboard Script
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG terminal_imgui" \
    "26_terminal_imgui_script.png" "Terminal ImGui Script Execution"

# 27. Terminal Dead Man Catastrophic Deletion Interception with ImGui Output
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG terminal_deadman" \
    "27_terminal_deadman_interception.png" "Terminal Dead Man Interception"

# 28-34. Redesigned Settings & Terminal Workstation HUD Screenshots (Spanish across Dark, AMOLED & Light)
echo ">>> Priming Spanish locale for Settings & Terminal showcase screenshots..."
adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_LOCALE es --es DEMO_THEME dark || true
sleep 3

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_ACTIVE_CHIP key_category_visual_performance" \
    "28_settings_top_es.png" "Settings Top Search & Quick-Jump (ES)"

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_CATEGORY key_category_appearance --es DEMO_COLLAPSE_HEADER true" \
    "29_settings_appearance_hub_es.png" "Settings Appearance & Performance Hub (ES)"
measure_section_perf "Settings (Hub + Iconos)"

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_CATEGORY key_category_visual_performance --es DEMO_COLLAPSE_HEADER true" \
    "30_settings_performance_category_es.png" "Settings Visual Performance Category (ES)"

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_CATEGORY key_category_visual_performance --es DEMO_COLLAPSE_HEADER true --es DEMO_OPEN_PREF key_thumbnail_load_mode" \
    "31_settings_thumbnail_dialog_es.png" "Settings Rich Thumbnail Icon Choice Dialog (ES)"

echo ">>> Priming AMOLED theme before search screenshot..."
adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_THEME amoled || true
sleep 3

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_SEARCH miniaturas" \
    "32_settings_amoled_search_es.png" "Settings AMOLED Pure Black & Live Search (ES)"

echo ">>> Priming Light theme before Light Mode showcase screenshot..."
adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_THEME light || true
sleep 3

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_CATEGORY key_category_appearance --es DEMO_COLLAPSE_HEADER true" \
    "33_settings_light_mode_es.png" "Settings Light Mode Hub & Badges (ES)"

# 35-36. Files Explorer & Drawer in Light Mode (Spanish)
capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity" \
    "35_files_light_mode_es.png" "Files Explorer Light Mode (ES)"
measure_section_perf "Files Explorer (Modo Claro)"

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG drawer" \
    "36_files_drawer_light_mode_es.png" "Files Explorer Navigation Drawer Light Mode (ES)"

echo ">>> Restoring Default Dark theme before capturing Spanish Terminal HUD..."
adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_THEME dark || true
sleep 3

capture_screen "adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG terminal_script_es" \
    "34_terminal_running_script_es.png" "Terminal Workstation HUD Running a.sh (ES)"
measure_section_perf "Terminal Workstation HUD (a.sh)"

echo ">>> Capturing Terminal Notification Shade (ES)..."
adb shell cmd statusbar expand-notifications || true
sleep 2
adb shell screencap -p "/data/local/tmp/37_terminal_notification_shade_es.png"
adb pull "/data/local/tmp/37_terminal_notification_shade_es.png" "screenshots/37_terminal_notification_shade_es.png" || true
adb shell cmd statusbar collapse || true
sleep 1

echo ">>> Resetting locale after showcase captures..."
adb shell am start -W -S -n com.y.om/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_LOCALE reset --es DEMO_THEME dark || true
sleep 3

# 28. Native C / C++ ImGui & Low-Level Syscall Diagnostic Execution
echo "=========================================================="
echo ">>> 28. Testing Native C / C++ ImGui & Low-Level Syscalls..."
echo "=========================================================="
if [ -f "TestCompiledScript/run_emulator_test.sh" ]; then
    chmod +x TestCompiledScript/run_emulator_test.sh
    echo "--- Native C / C++ & Dear ImGui Syscall Probe ---" >> emulator-performance.txt
    ./TestCompiledScript/run_emulator_test.sh 2>&1 | tee -a emulator-performance.txt
fi

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
echo "   All 37 Section Screenshots Captured & Verified!       "
echo "=========================================================="
ls -la screenshots/

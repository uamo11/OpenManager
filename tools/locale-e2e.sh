#!/usr/bin/env bash
# Changing the app language from the app's own list, like a user, on Android 12 (API 31):
# Settings > Language > Chinese, then the settings, the main screen and a cold start must all be
# in Chinese. (Android 13+ uses the system's per-app language screen instead.)
set -u
PKG=com.y.om
MAIN=$PKG/me.zhanghai.android.files.filelist.FileListActivity
SETTINGS=$PKG/me.zhanghai.android.files.settings.SettingsActivity
OUT=locale-e2e
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
: > "$REPORT"
FAILED=0
log() { echo "$*" | tee -a "$REPORT"; }
pass() { log "PASS  $*"; }
fail() { log "FAIL  $*"; FAILED=1; }
shot() { adb shell screencap -p "/data/local/tmp/$1.png" >/dev/null 2>&1; adb pull "/data/local/tmp/$1.png" "$OUT/$1.png" >/dev/null 2>&1 || true; }

# find_contains <label>...: center of the first node whose text contains a label.
find_contains() {
    # A failed dump must not leave the previous screen's file to be read as the current one.
    adb shell rm -f /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 \
        || { sleep 1; adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; }
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null || : > "$OUT/ui.xml"
    python3 - "$OUT/ui.xml" "$@" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for label in sys.argv[2:]:
    for node in re.findall(r"<node [^>]*>", xml):
        t = re.search(r' text="([^"]*)"', node)
        if t and label.lower() in html.unescape(t.group(1)).lower():
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
wait_contains() {
    local timeout=$1; shift
    local end=$((SECONDS + timeout))
    while [ $SECONDS -lt $end ]; do pos=$(find_contains "$@") && { echo "$pos"; return 0; }; sleep 1; done
    return 1
}
# scroll_to <max swipes> <label>...: swipes up until a label is on screen, away from the bottom
# edge (a label half under the navigation bar used to get a tap that hit the bar).
scroll_to() {
    local max=$1; shift
    local height
    height=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1 | cut -dx -f2)
    height=${height:-2400}
    for _ in $(seq 1 "$max"); do
        if pos=$(find_contains "$@"); then
            y=${pos#* }
            if [ "$y" -lt $((height - 400)) ]; then echo "$pos"; return 0; fi
            # Visible but too low: a short swipe brings it up.
            adb shell input swipe 540 1600 540 1100 300; sleep 1
            continue
        fi
        adb shell input swipe 540 1800 540 700 250; sleep 1
    done
    # Still only reachable under the navigation bar (end of the list): let the caller use the
    # settings search instead of tapping the bar.
    if pos=$(find_contains "$@"); then
        y=${pos#* }
        if [ "$y" -lt $((height - 400)) ]; then echo "$pos"; return 0; fi
    fi
    return 1
}

log "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
adb install -r -d -t apk-to-test/app-debug.apk
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
# First start (permission prompts, update check) out of the way.
adb shell am start -W -n "$MAIN" >/dev/null 2>&1; sleep 5
adb shell am force-stop $PKG; sleep 1

log "=== Language picked from the app's list (Settings > Language > Chinese) ==="
adb shell am start -W -n "$SETTINGS" >/dev/null 2>&1; sleep 4
# The last setting must scroll above the navigation bar (it stayed under it on Android 12).
for _ in $(seq 1 25); do adb shell input swipe 540 1800 540 500 120; done
sleep 2
screen_h=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1 | cut -dx -f2)
last=$(find_contains "Language" "Idioma" || true)
last_y=${last#* }
log "      at the end of Settings: Language at y=${last_y:-?} of ${screen_h:-?}"
if [ -n "$last" ] && [ "${last_y:-9999}" -lt $(( ${screen_h:-2400} - 200 )) ]; then
    pass "the last setting (Language) scrolls above the navigation bar"
else
    shot 00_settings_end
    fail "Language stays under the navigation bar at the end of Settings"
fi
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$SETTINGS" >/dev/null 2>&1; sleep 4
pos=$(scroll_to 30 "Language" "Idioma") || {
    # A swipe now and then gets lost on the slow emulator: the settings search finds it too.
    log "      Language not reached by scrolling, using the settings search"
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n "$SETTINGS" >/dev/null 2>&1; sleep 4
    if spos=$(find_contains "Search settings" "Buscar ajustes"); then
        adb shell input tap $spos; sleep 1
        adb shell input text "Language"; sleep 2
        adb shell input keyevent KEYCODE_BACK; sleep 1
    fi
    pos=$(scroll_to 6 "Language" "Idioma")
}
if [ -n "$pos" ]; then
    # A tap while the list is still settling after a fling only stops it: retry until the
    # language dialog (the only screen here with Cancel) is open.
    for _ in 1 2 3; do
        adb shell input tap $pos; sleep 2
        find_contains "Cancel" "Cancelar" >/dev/null && break
        log "      the tap on Language did not open the list, trying again"
        sleep 1
        pos=$(scroll_to 4 "Language" "Idioma") || break
    done
    shot 01_language_list
    # The list is sorted by language tag: Chinese is near the end.
    if pos=$(scroll_to 12 "中文"); then
        adb shell input tap $pos; sleep 6
        shot 02_settings_after_pick
        if wait_contains 20 "语言" "设置" >/dev/null; then
            pass "picking Chinese in Settings switches the settings to Chinese"
        else
            fail "the settings did not switch to Chinese after picking it"
        fi
    else
        fail "Chinese is not in the language list"
    fi
else
    fail "no Language entry in Settings"
fi

log "=== The rest of the app and a cold start ==="
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" >/dev/null 2>&1; sleep 6
shot 03_main_after_restart
if wait_contains 15 "文件夹：" >/dev/null; then
    pass "after a cold start the main screen is in Chinese (文件夹：…)"
else
    fail "after a cold start the main screen is not in Chinese"
fi
adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action drawer >/dev/null; sleep 2
shot 04_drawer
if find_contains "根目录" >/dev/null && find_contains "工具" >/dev/null; then
    pass "the side menu is in Chinese (根目录 / 工具)"
else
    fail "the side menu is not in Chinese"
fi

adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
if grep -A 3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
    fail "the app crashed"
fi
log ""
if [ "$FAILED" = 0 ]; then log "ALL PASSED"; else log "SOME CHECKS FAILED"; fi
exit $FAILED

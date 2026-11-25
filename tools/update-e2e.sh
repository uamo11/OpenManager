#!/usr/bin/env bash
# Real self-update check: install an older published release, let the app find the latest one on
# GitHub, download it from the update dialog, accept Android's installer and verify the result.
set -u
PKG=com.y.om
OUT=update-e2e
mkdir -p "$OUT"
REPORT="$OUT/report.txt"; : > "$REPORT"
FAILED=0
log() { echo "$*" | tee -a "$REPORT"; }
pass() { log "PASS  $*"; }
fail() { log "FAIL  $*"; FAILED=1; }
shot() { adb shell screencap -p "/data/local/tmp/$1.png" >/dev/null 2>&1; adb pull "/data/local/tmp/$1.png" "$OUT/$1.png" >/dev/null 2>&1 || true; }
find_text() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$@" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for label in sys.argv[2:]:
    for node in re.findall(r"<node [^>]*>", xml):
        vals = [html.unescape(m.group(1)).upper() for m in re.finditer(r' (?:text|content-desc)="([^"]*)"', node)]
        if label.upper() in vals:
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
PY
}
wait_text() {
    local timeout=$1; shift; local end=$((SECONDS + timeout))
    while [ $SECONDS -lt $end ]; do pos=$(find_text "$@") && { echo "$pos"; return 0; }; sleep 2; done
    return 1
}
installed_version() { adb shell dumpsys package $PKG | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r '; }

adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 3; done

# Latest tag: the API (with the workflow token when there is one, since anonymous calls from shared
# runners hit the rate limit), else the tag /releases/latest redirects to.
latest_tag() {
    local auth=()
    [ -n "${GH_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GH_TOKEN")
    curl -fsSL "${auth[@]}" https://api.github.com/repos/uamo11/OpenManager/releases/latest 2>/dev/null \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])' 2>/dev/null && return 0
    curl -fsSI https://github.com/uamo11/OpenManager/releases/latest 2>/dev/null \
        | tr -d '\r' | sed -n 's|^[Ll]ocation: .*/releases/tag/||p' | head -n1
}
LATEST=""
for _ in 1 2 3 4 5; do
    LATEST=$(latest_tag)
    [ -n "$LATEST" ] && break
    sleep 5
done
[ -n "$LATEST" ] || { echo "Could not find the latest release tag"; exit 1; }
if [ -f apk-to-test/old.apk ]; then
    # This branch built as an "older" version: exercises the update code being shipped.
    OLD="this branch (built as 2.0.0)"
    cp apk-to-test/old.apk "$OUT/old.apk"
else
    OLD=v2.0.4
    curl -fsSL -o "$OUT/old.apk" "https://github.com/uamo11/OpenManager/releases/download/$OLD/OpenManager-$OLD-release.apk"
fi
log "Old: $OLD  Latest on GitHub: $LATEST"
# Releases carry only ARM libraries: the emulator must run them through its ARM translation.
abilist=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
log "      emulator ABIs: $abilist"
case "$abilist" in
    *arm64-v8a*) pass "the emulator can install the ARM-only release APK ($abilist)" ;;
    *) fail "the emulator has no ARM translation for the ARM-only release APK ($abilist)" ;;
esac
adb install -r "$OUT/old.apk" >/dev/null
log "Installed: $(installed_version)"
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
# The user-facing "allow installs from this app" switch.
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true

BRANCH_BUILD=0; [ -f apk-to-test/old.apk ] && BRANCH_BUILD=1
if [ $BRANCH_BUILD -eq 1 ]; then
    # Ultra RAM mode used to switch the automatic check off completely.
    adb shell am start -n $PKG/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_PERF_MODE on >/dev/null
    sleep 4
    adb shell am force-stop $PKG
fi
adb shell am start -n $PKG/me.zhanghai.android.files.filelist.FileListActivity >/dev/null
if pos=$(wait_text 60 "Download & Install" "Descargar e instalar"); then
    shot 01_update_dialog
    pass "the auto check found an update without user action (Ultra RAM mode on: $BRANCH_BUILD)"
else
    shot 01_no_update_dialog
    fail "no update dialog appeared"
fi
if [ $BRANCH_BUILD -eq 1 ] && pos=$(find_text "Later" "LATER" "Más tarde" "MÁS TARDE"); then
    # The usual case: the app stays in memory, so opening it again does not create the screen anew.
    adb shell input tap $pos
    sleep 2
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    if wait_text 10 "Download & Install" "Descargar e instalar" >/dev/null; then
        shot 01b_asked_right_away
        fail "the dialog came back right after Later"
    else
        pass "Later is respected when the app is reopened at once"
    fi
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    # As if the pause had passed (a release published meanwhile).
    adb shell am broadcast -a me.zhanghai.android.files.action.DEMO --es action update_cooldown_expire >/dev/null
    sleep 3
    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    if wait_text 60 "Download & Install" "Descargar e instalar" >/dev/null; then
        shot 01c_warm_reopen
        pass "reopening the app from memory checks again and offers the update"
    else
        shot 01c_no_dialog_on_reopen
        fail "reopening the app from memory did not offer the update"
    fi
fi
if pos=$(find_text "Download & Install" "Descargar e instalar"); then
    adb shell input tap $pos
fi
if pos=$(wait_text 180 "Install" "INSTALL" "Update" "UPDATE" "Instalar" "Actualizar" "Open with" "Abrir con"); then
    if find_text "Open with" "Abrir con" >/dev/null; then
        shot 02_chooser_instead_of_installer
        fail "Android asked which app opens the APK instead of the installer"
        pos=$(find_text "Package installer" "Instalador de paquetes") && adb shell input tap $pos && sleep 1
        pos=$(find_text "Just once" "JUST ONCE" "Solo una vez") && adb shell input tap $pos && sleep 2
        pos=$(wait_text 20 "Install" "INSTALL" "Instalar") || true
    fi
    shot 02_system_installer
    pass "download finished and Android's installer opened"
    adb shell input tap $pos
else
    shot 02_no_installer
    fail "installer never opened"
fi
end=$((SECONDS + 120))
while [ $SECONDS -lt $end ]; do
    [ "v$(installed_version)" = "$LATEST" ] && break
    sleep 3
done
now="v$(installed_version)"
shot 03_after_update
if [ "$now" = "$LATEST" ]; then
    pass "updated in place: $OLD -> $now (same signature, data kept)"
else
    fail "still on $now after the update"
fi
adb logcat -d | grep -E "FATAL EXCEPTION" -A 15 > "$OUT/fatal.txt" || true
# Only the app's own crashes count (uiautomator itself sometimes dies while dumping).
grep -q "Process: $PKG" "$OUT/fatal.txt" && fail "crash during the update flow"
log ""
[ $FAILED -eq 0 ] && log "RESULT: ALL PASSED" || log "RESULT: FAILURES"
exit $FAILED

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

OLD=v2.0.4
LATEST=$(curl -fsSL https://api.github.com/repos/uamo11/OpenManager/releases/latest | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
log "Old: $OLD  Latest on GitHub: $LATEST"
curl -fsSL -o "$OUT/old.apk" "https://github.com/uamo11/OpenManager/releases/download/$OLD/OpenManager-$OLD-release.apk"
adb install -r "$OUT/old.apk" >/dev/null
log "Installed: $(installed_version)"
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
# The user-facing "allow installs from this app" switch.
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true

adb shell am start -n $PKG/me.zhanghai.android.files.filelist.FileListActivity >/dev/null
if pos=$(wait_text 60 "Download & Install" "Descargar e instalar"); then
    shot 01_update_dialog
    pass "the auto check found an update without user action"
    adb shell input tap $pos
else
    shot 01_no_update_dialog
    fail "no update dialog appeared"
fi
if pos=$(wait_text 180 "Install" "INSTALL" "Update" "UPDATE" "Instalar" "Actualizar"); then
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
[ -s "$OUT/fatal.txt" ] && fail "crash during the update flow"
log ""
[ $FAILED -eq 0 ] && log "RESULT: ALL PASSED" || log "RESULT: FAILURES"
exit $FAILED

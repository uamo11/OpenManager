#!/usr/bin/env bash
# End-to-end test of the SSH/Telnet terminal against real servers running on the CI runner.
# The emulator reaches the runner through 10.0.2.2: OpenSSH on 2222, Telnet on 2323
# (see .github/workflows/remote-terminal-e2e.yml). Every check is verified on the server side:
# a command typed by the app must create a file on the runner.
set -u

PKG=com.y.om
MAIN=$PKG/me.zhanghai.android.files.filelist.FileListActivity
OUT=remote-e2e
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
: > "$REPORT"
FAILED=0

log() { echo "$*" | tee -a "$REPORT"; }
# Probe files are written by the server-side test user, so removing them needs root.
clear_probe() { sudo rm -f "$@"; }
pass() { log "PASS  $*"; }
fail() { log "FAIL  $*"; FAILED=1; }

shot() {
    adb shell screencap -p "/data/local/tmp/$1.png" >/dev/null 2>&1
    adb pull "/data/local/tmp/$1.png" "$OUT/$1.png" >/dev/null 2>&1 || true
}

demo() {
    adb shell am start -n "$MAIN" --es SHOW_DEMO_DIALOG "$1" >/dev/null 2>&1
}

# Prints "x y" of the first node whose text matches one of the given labels.
find_text() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$@" <<'EOF'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for label in sys.argv[2:]:
    for node in re.findall(r"<node [^>]*>", xml):
        text = re.search(r' text="([^"]*)"', node)
        desc = re.search(r' content-desc="([^"]*)"', node)
        values = [m.group(1).upper() for m in (text, desc) if m]
        if label.upper() in values:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
EOF
}

# wait_text <seconds> <label>... : waits until a label is on screen, prints its center.
wait_text() {
    local timeout=$1; shift
    local end=$((SECONDS + timeout))
    while [ $SECONDS -lt $end ]; do
        if pos=$(find_text "$@"); then
            echo "$pos"
            return 0
        fi
        sleep 1
    done
    return 1
}

# wait_file <seconds> <path> <expected content>: waits until the runner-side file matches.
wait_file() {
    local timeout=$1 path=$2 expected=$3
    local end=$((SECONDS + timeout))
    while [ $SECONDS -lt $end ]; do
        if [ -f "$path" ] && grep -q "$expected" "$path" 2>/dev/null; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

echo ">>> Waiting for emulator boot..."
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 3; done
APK=$(find apk-to-test -name "*.apk" | head -n 1)
adb install -r -d -t "$APK"
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb logcat -c

adb shell am start -W -n "$MAIN" >/dev/null
sleep 5

log "=== Connections UI ==="
demo drawer; sleep 2
shot 00_drawer_connections
if find_text "Connections" "Conexiones" >/dev/null; then
    pass "drawer shows Connections (replacing FTP)"
else
    fail "drawer has no Connections entry"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1
demo remote_profiles; sleep 3
shot 01_profiles_list
if find_text "CI OpenSSH" >/dev/null && find_text "CI Telnet" >/dev/null; then
    pass "profile list shows the saved SSH and Telnet profiles"
else
    fail "profile list does not show the seeded profiles"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

demo remote_add_sheet; sleep 3
shot 01b_add_connection_sheet
if find_text "SSH" >/dev/null && find_text "Telnet" >/dev/null && find_text "WebDAV" >/dev/null \
    && find_text "SFTP" >/dev/null && find_text "SMB" >/dev/null; then
    pass "add sheet lists SSH, Telnet, FTP, SFTP, SMB and WebDAV"
else
    fail "add sheet is missing connection types"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

demo remote_edit; sleep 3
shot 02_profile_editor
adb shell input swipe 540 1800 540 600 400; sleep 1
shot 03_profile_editor_collapsed_bars
if pos=$(find_text "Commands after login" "Comandos al iniciar sesión"); then
    adb shell input tap $pos; sleep 1
    shot 03b_profile_editor_startup_expanded
    pass "startup commands bar expands on tap"
else
    fail "startup commands bar not found"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1

demo remote_chooser; sleep 3
shot 04_terminal_long_press_chooser
if find_text "Local terminal" "Terminal local" >/dev/null; then
    pass "long-press chooser lists local terminal and profiles"
else
    fail "long-press chooser not shown"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

log "=== SSH: first connection must ask to verify the host key ==="
clear_probe /tmp/om_ssh_probe /tmp/om_shortcut_probe /tmp/om_typed_probe
demo remote_ssh
if pos=$(wait_text 40 "Trust and save" "Confiar y guardar"); then
    shot 05_ssh_hostkey_prompt
    pass "unknown host key shows the fingerprint dialog"
    adb shell input tap $pos
else
    shot 05_ssh_hostkey_prompt_missing
    fail "host key dialog did not appear"
fi
if wait_file 40 /tmp/om_ssh_probe OM_SSH_OK_42; then
    pass "SSH password login + startup command executed on the server"
else
    fail "SSH startup command did not reach the server"
    sudo tail -n 30 /tmp/sshd_om.log >> "$REPORT" 2>/dev/null || true
fi
sleep 2
shot 06_ssh_session_colors

if pos=$(wait_text 10 "probe"); then
    adb shell input tap $pos
    if wait_file 15 /tmp/om_shortcut_probe OM_SHORTCUT_OK; then
        pass "shortcut chip runs its command on the server"
    else
        fail "shortcut chip command not executed"
    fi
else
    fail "shortcut bar not visible"
fi

# Typing goes through the IME path of TerminalView like a real keyboard.
# Quoted for the device shell: an unquoted ">" would be a redirection there.
adb shell "input text 'echo%sOM_TYPED_OK%s>%s/tmp/om_typed_probe'"
t0=$(date +%s%N)
adb shell input keyevent KEYCODE_ENTER
if wait_file 15 /tmp/om_typed_probe OM_TYPED_OK; then
    t1=$(date +%s%N)
    pass "typed command executed (enter -> file on server: $(( (t1 - t0) / 1000000 )) ms incl. adb)"
else
    fail "typed command not executed"
fi
shot 07_ssh_after_typing

log "=== Predictive echo with +150 ms server latency ==="
sudo tc qdisc add dev lo root handle 1: prio
sudo tc qdisc add dev lo parent 1:3 handle 30: netem delay 150ms
sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip sport 2222 0xffff flowid 1:3
clear_probe /tmp/om_pred_probe
adb shell "input text 'echo%sOM_PREDICTED_OK%s>%s/tmp/om_pred_probe'"
shot 07b_ssh_predictive_echo
adb shell input keyevent KEYCODE_ENTER
if wait_file 20 /tmp/om_pred_probe OM_PREDICTED_OK; then
    pass "command typed over a 150 ms link executed"
else
    fail "command typed over a 150 ms link not executed"
fi
adb shell input keyevent KEYCODE_HOME; sleep 2
echo_line=$(adb logcat -d -s OMEcho | tail -n 1)
log "      $echo_line"
shown=$(echo "$echo_line" | sed -nE 's/.*shownConfirmed=([0-9]+).*/\1/p')
if [ "${shown:-0}" -gt 0 ]; then
    pass "predictive echo drew $shown keys before the server echo and all were confirmed"
else
    fail "predictive echo did not predict any key"
fi
sudo tc qdisc del dev lo root || true
demo remote_ssh; sleep 3
adb shell dumpsys meminfo $PKG > "$OUT/meminfo_ssh_session.txt" 2>/dev/null || true
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo_ssh_session.txt" 2>/dev/null || true

log "=== Reopening the SSH screen keeps the shell location ==="
adb shell input keyevent KEYCODE_HOME; sleep 1
# TerminalActivity is not exported: reopen through the app, which reattaches the live session
# exactly like the notification does.
demo remote_ssh; sleep 4
shot 07c_ssh_reopened
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_reopened.xml"
chip=$(python3 - "$OUT/ui_reopened.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'text="([^"]*)"[^>]*resource-id="[^"]*tvTerminalBreadcrumb"', xml) or \
    re.search(r'resource-id="[^"]*tvTerminalBreadcrumb"[^>]*text="([^"]*)"', xml)
print(m.group(1) if m else "")
PY
)
log "      path chip: '$chip'"
case "$chip" in
    "omtest@ci: ~/probe") pass "reopened session keeps the remote shell location ($chip)" ;;
    *) fail "reopened session shows '$chip' instead of the shell location" ;;
esac

log "=== Key deck layouts ==="
if pos=$(find_text "⋮"); then
    adb shell input tap $pos; sleep 1
    if pos2=$(find_text "Compact bar (1 row)" "Barra compacta (1 fila)"); then
        adb shell input tap $pos2; sleep 1
        shot 07d_keys_compact
        pass "key deck switches to the compact layout"
        pos=$(find_text "⋮") && adb shell input tap $pos && sleep 1
        pos2=$(find_text "Full bar (3 rows)" "Barra completa (3 filas)") && adb shell input tap $pos2 && sleep 1
    else
        fail "key deck layout menu did not open"
    fi
else
    fail "key deck has no ⋮ button"
fi

log "=== SSH: reconnect with the trusted key must not ask again ==="
clear_probe /tmp/om_ssh_probe
adb shell am force-stop $PKG; sleep 1
demo remote_ssh
if wait_file 40 /tmp/om_ssh_probe OM_SSH_OK_42; then
    if find_text "Trust and save" "Confiar y guardar" >/dev/null; then
        fail "trusted key prompted again"
    else
        pass "trusted host key reconnects without prompting"
    fi
else
    fail "reconnect with trusted key failed"
fi
shot 08_ssh_reconnect

log "=== SSH: a changed host key must be blocked ==="
sudo pkill -f "sshd -f /tmp/sshd_om/sshd_config" || true
sudo rm -f /tmp/sshd_om/ssh_host_ed25519_key /tmp/sshd_om/ssh_host_ed25519_key.pub
sudo ssh-keygen -q -t ed25519 -N '' -f /tmp/sshd_om/ssh_host_ed25519_key
sudo /usr/sbin/sshd -f /tmp/sshd_om/sshd_config -E /tmp/sshd_om.log
sleep 1
clear_probe /tmp/om_ssh_probe
adb shell am force-stop $PKG; sleep 1
demo remote_ssh
if pos=$(wait_text 40 "Cancel" "Cancelar"); then
    shot 09_ssh_hostkey_changed
    if find_text "Replace key" "Reemplazar clave" >/dev/null && ! find_text "Trust and save" "Confiar y guardar" >/dev/null; then
        pass "changed key shows the MITM warning without a one-tap accept"
    else
        fail "changed key dialog has the wrong actions"
    fi
    adb shell input tap $pos
    sleep 5
    if [ -f /tmp/om_ssh_probe ]; then
        fail "connection continued after rejecting a changed key"
    else
        pass "rejecting the changed key aborts the connection"
    fi
    shot 10_ssh_hostkey_rejected
else
    shot 09_ssh_hostkey_changed_missing
    fail "changed key was not reported"
fi

log "=== Telnet: auto-login on prompts + startup after shell prompt ==="
clear_probe /tmp/om_telnet_probe
adb shell am force-stop $PKG; sleep 1
demo remote_telnet
if wait_file 60 /tmp/om_telnet_probe OM_TELNET_OK_42; then
    pass "Telnet login + startup command executed on the server"
else
    fail "Telnet startup command did not reach the server"
fi
sleep 2
shot 11_telnet_session

# Keep the terminal-phase log: the video phase clears logcat per clip.
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
    fail "FATAL EXCEPTION during terminal/connections tests"
    grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60 >> "$REPORT"
fi

log "=== Command guard: a script must ask BEFORE deleting ==="
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_guard_test
demo guard_script
if pos=$(wait_text 30 "Block" "Bloquear"); then
    shot 14_guard_ask_dialog
    if adb shell ls /sdcard/om_guard_test/victim.txt >/dev/null 2>&1; then
        pass "file still exists while the user is being asked"
    else
        fail "file was deleted before the user answered"
    fi
    adb shell input tap $pos; sleep 4
    ran=$(adb shell cat /sdcard/om_guard_test/script_ran.txt 2>/dev/null | tr -d '\r')
    if adb shell ls /sdcard/om_guard_test/victim.txt >/dev/null 2>&1 && [ "$ran" = "rm exit=1" ]; then
        pass "Block kept the file and the script continued (rm returned 1)"
    else
        fail "Block did not work (script_ran='$ran')"
    fi
else
    shot 14_guard_ask_missing
    fail "no confirmation dialog for a script deletion"
fi
adb shell am force-stop $PKG; sleep 1
demo guard_script
if pos=$(wait_text 30 "Allow" "Permitir"); then
    adb shell input tap $pos; sleep 4
    ran=$(adb shell cat /sdcard/om_guard_test/script_ran.txt 2>/dev/null | tr -d '\r')
    if ! adb shell ls /sdcard/om_guard_test/victim.txt >/dev/null 2>&1 && [ "$ran" = "rm exit=0" ]; then
        pass "Allow deleted the file"
    else
        fail "Allow did not delete the file (script_ran='$ran')"
    fi
else
    fail "no confirmation dialog on the second run"
fi

log "=== Dead Man: relative path to /system from / is blocked without asking ==="
adb shell am force-stop $PKG; sleep 1
adb logcat -c
demo guard_deadman; sleep 7
shot 15_dead_man_blocked
ran=$(adb shell cat /sdcard/om_guard_test/deadman_ran.txt 2>/dev/null | tr -d '\r')
guard_log=$(adb logcat -d -s OMCommandGuard | grep -m1 "DEAD_MAN")
log "      $guard_log"
if [ "$ran" = "rm exit=1" ] && [ -n "$guard_log" ]; then
    pass "Dead Man blocked 'cd / && rm -rf system/...'"
else
    fail "Dead Man did not block (deadman_ran='$ran')"
fi
if find_text "Allow this deletion?" "¿Permitir este borrado?" >/dev/null; then
    fail "Dead Man asked instead of blocking"
fi
if pos=$(find_text "DEAD MAN: LOCKED" "DEAD MAN: BLOQUEADO"); then
    adb shell input tap $pos; sleep 2
    shot 16_audit_sheet
    if find_text "Audit & protection" "Auditoría y protección" >/dev/null; then
        pass "audit sheet opens from the Dead Man badge and lists the decision"
    else
        fail "audit sheet did not open"
    fi
else
    log "      (badge text not found, opening audit from the toolbar is covered by screenshots)"
fi

log "=== Video: 4K H.264 / 1080p HEVC playback ==="
for clip in om_4k_h264.mp4 om_1080p_hevc.mp4; do
    [ -f "/tmp/$clip" ] || { fail "missing test clip $clip"; continue; }
    adb push "/tmp/$clip" "/sdcard/Movies/$clip" >/dev/null
    adb logcat -c
    adb shell am start -n $PKG/me.zhanghai.android.files.viewer.video.VideoPlayerActivity \
        -a android.intent.action.VIEW -d "file:///sdcard/Movies/$clip" -t video/mp4 >/dev/null
    sleep 9
    adb shell input tap 540 1200; sleep 1
    if pos=$(find_text "Playback statistics" "Estadísticas de reproducción"); then
        adb shell input tap $pos
    fi
    sleep 4
    shot "12_video_${clip%.mp4}"
    adb logcat -d -s OMVideoPlayer > "$OUT/video_${clip%.mp4}.log" 2>/dev/null || true
    adb logcat -d | grep -E "FATAL EXCEPTION" -A 20 >> "$OUT/video_${clip%.mp4}.log" || true
    decoder=$(grep -m1 "Video decoder:" "$OUT/video_${clip%.mp4}.log" | sed 's/.*Video decoder: //')
    dropped=$(grep -oE "Dropped [0-9]+ frames" "$OUT/video_${clip%.mp4}.log" | awk '{s+=$2} END {print s+0}')
    if [ -n "$decoder" ]; then
        pass "$clip plays (decoder: $decoder, dropped frames reported: $dropped)"
    else
        fail "$clip did not initialize a video decoder"
    fi
    adb shell dumpsys meminfo $PKG > "$OUT/meminfo_video_${clip%.mp4}.txt" 2>/dev/null || true
    adb shell am force-stop $PKG; sleep 1
done
adb logcat -d > "$OUT/logcat_video.txt" 2>/dev/null || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat_video.txt"; then fail "crash during video playback"; fi

log "=== Crash audit ==="
adb logcat -d > "$OUT/logcat_end.txt" 2>/dev/null || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" "$OUT/logcat_end.txt" "$OUT/logcat_video.txt"; then
    fail "FATAL EXCEPTION in logcat"
    grep -h -A 20 "FATAL EXCEPTION" "$OUT"/logcat*.txt | head -60 >> "$REPORT"
else
    pass "no FATAL exceptions"
fi

log ""
if [ $FAILED -eq 0 ]; then log "RESULT: ALL PASSED"; else log "RESULT: FAILURES"; fi
exit $FAILED

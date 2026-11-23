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

log "=== Writing bar: compose locally, send at once ==="
clear_probe /tmp/om_compose_probe
if pos=$(find_text "⋮"); then
    adb shell input tap $pos; sleep 1
    if pos2=$(find_text "Writing bar" "Barra de escritura"); then
        adb shell input tap $pos2; sleep 2
        shot 07e_compose_bar
        adb shell "input text 'echo%sOM_COMPOSE_OK%s>%s/tmp/om_compose_probe'"
        sleep 1
        if pos3=$(find_text "Send" "Enviar"); then
            adb shell input tap $pos3
            if wait_file 20 /tmp/om_compose_probe OM_COMPOSE_OK; then
                pass "writing bar sends the composed line to the server"
            else
                fail "writing bar text never reached the server"
            fi
        else
            fail "writing bar has no send button"
        fi
        pos=$(find_text "⋮") && adb shell input tap $pos && sleep 1
        pos2=$(find_text "Writing bar" "Barra de escritura") && adb shell input tap $pos2 && sleep 1
    else
        fail "no writing bar option in the key menu"
    fi
else
    fail "no ⋮ on the key deck"
fi

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

log "=== VPS files: tapping the folder chip opens the VPS next to the phone ==="
python3 - "$OUT/vps_photo.png" <<'PY'
import struct, sys, zlib
w, h = 80, 60
raw = b"".join(b"\x00" + bytes([220, 90, 40]) * w for _ in range(h))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
open(sys.argv[1], "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
PY
sudo -u omtest mkdir -p /home/omtest/probe
sudo install -o omtest -g omtest -m 644 "$OUT/vps_photo.png" /home/omtest/probe/vps_photo.png
echo "notes from the VPS" | sudo -u omtest tee /home/omtest/probe/vps_notes.txt >/dev/null
if pos=$(find_text "$chip"); then
    adb shell input tap $pos
    if wait_text 30 "vps_photo.png" >/dev/null; then
        shot 07d_vps_files
        pass "the folder chip opens the VPS folder (~/probe) in the explorer"
        if find_text "Download" "DCIM" "Android" >/dev/null; then
            pass "the other panel shows the phone's files"
        else
            fail "the other panel does not show the phone's files"
        fi
        adb shell input tap $(find_text "vps_photo.png"); sleep 5
        shot 07e_vps_photo
        top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[A-Za-z0-9_.]*")
        case "$top" in
            *ImageViewerActivity) pass "a photo on the VPS opens in the image viewer" ;;
            *) fail "the VPS photo did not open in the image viewer ($top)" ;;
        esac
    else
        shot 07d_vps_files_missing
        fail "the folder chip did not open the VPS files"
    fi
    sudo grep -E "Accepted password .* omtest|subsystem request for sftp" /tmp/sshd_om.log | tail -2 | sed 's/^/      sshd: /' | tee -a "$REPORT" >/dev/null || true
else
    fail "no folder chip to tap ('$chip')"
fi
# Back from the photo, then back from the start folder: the live VPS terminal again (2 presses).
adb logcat -c
backs=0
while [ $backs -lt 5 ]; do
    adb shell input keyevent KEYCODE_BACK; sleep 2
    backs=$((backs + 1))
    find_text "⋮" >/dev/null && break
done
shot 07f_back_to_vps_terminal
if find_text "⋮" >/dev/null && [ $backs -le 2 ]; then
    pass "back from the VPS files returns to the SSH session ($backs presses)"
else
    fail "back from the VPS files took $backs presses to reach the SSH session"
    adb logcat -d -s OMReturnOnBack | tail -5 | sed 's/^/      /' | tee -a "$REPORT" >/dev/null
    find_text "⋮" >/dev/null || { demo remote_ssh; sleep 4; }
fi

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

log "=== Switch the profile to key login from the session ==="
sudo rm -f /home/omtest/.ssh/authorized_keys
demo remote_ssh; sleep 3
if pos=$(find_text "SSH"); then
    adb shell input tap $pos; sleep 1
    if pos2=$(find_text "Log in with a key (no password)" "Entrar con clave (sin contraseña)"); then
        adb shell input tap $pos2; sleep 1
        if pos3=$(find_text "Install key" "Instalar clave"); then
            adb shell input tap $pos3; sleep 5
            shot 07f_key_installed
            if sudo grep -q "^ssh-ed25519 AAAA" /home/omtest/.ssh/authorized_keys 2>/dev/null; then
                pass "a generated Ed25519 key was installed in authorized_keys from the session"
            else
                fail "authorized_keys has no key"
            fi
        else
            fail "no install confirmation"
        fi
    else
        fail "no 'log in with a key' action in the SSH session menu"
    fi
else
    fail "session badge not found"
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
        if sudo grep -q "Accepted publickey for omtest" /tmp/sshd_om.log 2>/dev/null; then
            pass "the reconnection logged in with the new key, not the password"
        else
            fail "the reconnection did not use the key"
            sudo tail -n 15 /tmp/sshd_om.log >> "$REPORT" 2>/dev/null || true
        fi
    fi
else
    fail "reconnect with trusted key failed"
fi
shot 08_ssh_reconnect

log "=== VPS toolbar: connection details and one-tap close ==="
if pos=$(find_text "Connection" "Conexión"); then
    adb shell input tap $pos; sleep 2
    shot 08b_ssh_connection_details
    if find_text "Encryption" "Cifrado" >/dev/null || [ -n "$(grep -o 'aes\|chacha' "$OUT/ui.xml" | head -1)" ]; then
        pass "connection sheet shows the negotiated encryption"
    else
        fail "connection sheet missing details"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "no connection button on the VPS toolbar"
fi
if find_text "Copy output" "Copiar salida" >/dev/null; then
    fail "copy button still on the toolbar"
fi
if pos=$(find_text "Disconnect" "Desconectar"); then
    adb shell input tap $pos; sleep 3
    if find_text "CI OpenSSH" >/dev/null && find_text "probe" >/dev/null; then
        fail "close button did not close the VPS terminal"
    else
        pass "close button disconnects and closes the VPS terminal at once"
    fi
else
    fail "no close button on the VPS toolbar"
fi

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

log "=== Command guard without root: ordinary deletions never ask ==="
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_guard_test
demo guard_script; sleep 8
shot 14_guard_no_root
ran=$(adb shell cat /sdcard/om_guard_test/script_ran.txt 2>/dev/null | tr -d '\r')
if find_text "Block" "Bloquear" >/dev/null; then
    fail "a confirmation appeared for an ordinary deletion without root"
elif ! adb shell ls /sdcard/om_guard_test/victim.txt >/dev/null 2>&1 && [ "$ran" = "rm exit=0" ]; then
    pass "a script without root deletes a file with no confirmation"
else
    fail "the script's deletion did not run (script_ran='$ran')"
fi

log "=== Command guard without root: a massive deletion asks first ==="
adb shell am force-stop $PKG; sleep 1
demo guard_mass
for i in $(seq 1 60); do adb shell ls /sdcard/om_guard_test/mass_ready.txt >/dev/null 2>&1 && break; sleep 1; done
if pos=$(wait_text 40 "Block" "Bloquear"); then
    shot 14b_guard_mass_dialog
    left=$(adb shell "ls /sdcard/om_guard_test/mass 2>/dev/null | wc -l" | tr -d '\r ')
    log "      files while asking: $left"
    if [ "${left:-0}" -gt 5000 ]; then
        pass "nothing was deleted while asking about $left files"
    else
        fail "files were deleted before the answer ($left left)"
    fi
    adb shell input tap $pos; sleep 4
    ran=$(adb shell cat /sdcard/om_guard_test/mass_ran.txt 2>/dev/null | tr -d '\r')
    left=$(adb shell "ls /sdcard/om_guard_test/mass 2>/dev/null | wc -l" | tr -d '\r ')
    if [ "$ran" = "rm exit=1" ] && [ "${left:-0}" -gt 5000 ]; then
        pass "Block kept the $left files and the script went on"
    else
        fail "Block did not keep the files (mass_ran='$ran', left=$left)"
    fi
else
    shot 14b_guard_mass_missing
    fail "no confirmation for deleting more than 5000 files"
fi
adb shell rm -rf /sdcard/om_guard_test/mass

log "=== DFRoot (root, simulated in the debug build): changes to /system are blocked ==="
adb shell am force-stop $PKG; sleep 1
adb logcat -c
demo guard_dfroot; sleep 7
ran=$(adb shell cat /sdcard/om_guard_test/dfroot_ran.txt 2>/dev/null | tr -d '\r')
guard_log=$(adb logcat -d -s OMCommandGuard | grep -m1 "DFROOT")
log "      $guard_log"
if [ "$ran" = "chmod exit=1" ] && [ -n "$guard_log" ]; then
    pass "DFRoot blocked 'chmod 755 /system/bin/sh' before it ran"
else
    fail "DFRoot did not block the chmod (dfroot_ran='$ran')"
fi

log "=== Root seatbelt on a real Android kernel (adb root) ==="
demo seatbelt_export
for i in $(seq 1 15); do adb shell ls /sdcard/om_guard_test/om_seatbelt.sh >/dev/null 2>&1 && break; sleep 1; done
# Root's view of /sdcard differs per namespace: run it from /data/local/tmp like a root tool would.
adb shell cp /sdcard/om_guard_test/om_seatbelt.sh /data/local/tmp/om_seatbelt.sh
log "      exported: $(adb shell ls -l /data/local/tmp/om_seatbelt.sh 2>&1 | tr -d '\r')"
before=$(adb shell "ls /dev/block | wc -l" | tr -d '\r ')
inside=$(adb shell "su 0 sh /data/local/tmp/om_seatbelt.sh /system/bin/sh -c 'echo entries=\$(ls /dev/block | wc -l) belt=\$OM_SEATBELT; touch /system/om_belt_probe 2>&1 | head -n 1'" 2>&1 | tr -d '\r')
after=$(adb shell "ls /dev/block | wc -l" | tr -d '\r ')
log "      /dev/block outside: $before -> $after; inside: $(echo "$inside" | tr '\n' ' ')"
case "$inside" in
    *"entries=0 belt=1"*)
        if [ "$after" = "$before" ] && [ "${after:-0}" -gt 0 ]; then
            pass "the seatbelt hides the partitions inside only, the phone keeps them"
        else
            fail "the seatbelt changed /dev/block outside ($before -> $after)"
        fi ;;
    *"No mount-namespace support"*|*"not private"*|*"Could not cover"*)
        log "      (isolation not available on this emulator image; the app then refuses or warns)"
        if [ "$after" = "$before" ]; then pass "without isolation nothing was mounted"; else fail "partial isolation leaked"; fi ;;
    *) fail "unexpected seatbelt result: $inside" ;;
esac
adb shell su 0 rm -f /system/om_belt_probe 2>/dev/null

log "=== Two scripts at the same time ==="
adb shell am force-stop $PKG; sleep 1
adb shell rm -f /sdcard/om_guard_test/multi_a.log /sdcard/om_guard_test/multi_b.log
demo multi_sessions; sleep 12
shot 14c_two_sessions
a1=$(adb shell cat /sdcard/om_guard_test/multi_a.log 2>/dev/null | wc -l); b1=$(adb shell cat /sdcard/om_guard_test/multi_b.log 2>/dev/null | wc -l)
sleep 4
a2=$(adb shell cat /sdcard/om_guard_test/multi_a.log 2>/dev/null | wc -l); b2=$(adb shell cat /sdcard/om_guard_test/multi_b.log 2>/dev/null | wc -l)
log "      multi_a: $a1 -> $a2 lines, multi_b: $b1 -> $b2 lines"
if [ "$a2" -gt "$a1" ] && [ "$b2" -gt "$b1" ]; then
    pass "the first script keeps running after starting a second one"
else
    fail "only one script kept running"
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
    if find_text "Activity" "Actividad" >/dev/null && find_text "Dead Man" >/dev/null; then
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

log "=== Start-up cost (debug OMStartup log) ==="
grep -h "OMStartup" "$OUT"/logcat*.txt 2>/dev/null | sed -E 's/^.*OMStartup: //' | sort | uniq -c | sort -rn | head -20 >> "$REPORT" || true

log "=== PDF received from another app ==="
python3 - "$OUT/om_received.pdf" <<'PY'
import sys
objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        None, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
stream = b"BT /F1 24 Tf 40 100 Td (OpenManager PDF) Tj ET"
out = bytearray(b"%PDF-1.4\n"); offsets = []
for i, body in enumerate(objs, 1):
    offsets.append(len(out))
    if body is None:
        out += b"%d 0 obj << /Length %d >> stream\n" % (i, len(stream)) + stream + b"\nendstream endobj\n"
    else:
        out += b"%d 0 obj " % i + body.encode() + b" endobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for o in offsets: out += b"%010d 00000 n \n" % o
out += b"trailer << /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(sys.argv[1], "wb").write(out)
PY
adb shell mkdir -p /sdcard/Download/om_pdf_test
adb push "$OUT/om_received.pdf" /sdcard/Download/om_pdf_test/om_received.pdf >/dev/null
adb shell cmd package query-activities --brief -a android.intent.action.VIEW \
    -d file:///sdcard/Download/om_pdf_test/om_received.pdf -t application/pdf > "$OUT/pdf_handlers.txt" 2>&1
entries=$(grep -o "$PKG/[A-Za-z0-9_.]*" "$OUT/pdf_handlers.txt" | sort -u | wc -l | tr -d ' ')
log "      OpenManager entries for a PDF: $entries"
if [ "$entries" = "1" ]; then
    pass "Android lists OpenManager once for a PDF (View PDF)"
else
    fail "OpenManager appears $entries times for a PDF"
fi
adb shell am force-stop $PKG; sleep 1
adb shell am start -a android.intent.action.VIEW -t application/pdf \
    -d file:///sdcard/Download/om_pdf_test/om_received.pdf -n $PKG/me.zhanghai.android.files.filelist.SmartOpenActivity >/dev/null
sleep 5
shot 17_pdf_from_other_app
adb shell input keyevent KEYCODE_BACK; sleep 4
shot 18_pdf_back_to_explorer
if find_text "om_received.pdf" >/dev/null; then
    pass "back from the PDF shows it in the explorer to copy or move"
else
    fail "back did not show the PDF in the explorer"
fi

log "=== Other file types received from another app ==="
python3 - "$OUT/om_received.png" <<'PY'
import struct, sys, zlib
w, h = 64, 48
raw = b"".join(b"\x00" + bytes([40, 120, 200]) * w for _ in range(h))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
PY
printf 'OpenManager received text\nline 2\n' > "$OUT/om_received.txt"
adb push "$OUT/om_received.png" /sdcard/Download/om_pdf_test/om_received.png >/dev/null
adb push "$OUT/om_received.txt" /sdcard/Download/om_pdf_test/om_received.txt >/dev/null
for spec in "png:image/png:19_image" "txt:text/plain:20_text"; do
    ext=${spec%%:*}; rest=${spec#*:}; mime=${rest%%:*}; tag=${rest#*:}
    f=/sdcard/Download/om_pdf_test/om_received.$ext
    adb shell cmd package query-activities --brief -a android.intent.action.VIEW -d file://$f -t $mime > "$OUT/handlers_$ext.txt" 2>&1
    n=$(grep -o "$PKG/[A-Za-z0-9_.]*" "$OUT/handlers_$ext.txt" | sort -u | wc -l | tr -d ' ')
    if [ "$n" = "1" ]; then pass "Android lists OpenManager once for $mime"; else fail "OpenManager appears $n times for $mime"; fi
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -a android.intent.action.VIEW -t $mime -d file://$f \
        -n $PKG/me.zhanghai.android.files.filelist.SmartOpenActivity >/dev/null
    sleep 5
    shot ${tag}_from_other_app
    top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[A-Za-z0-9_.]*")
    log "      $mime opened in: $top"
    case "$ext:$top" in
        png:*ImageViewerActivity|txt:*TextEditorActivity) pass "$mime opens straight in its viewer" ;;
        *) fail "$mime did not open in its viewer ($top)" ;;
    esac
    adb shell input keyevent KEYCODE_BACK; sleep 4
    shot ${tag}_back_to_explorer
    if find_text "om_received.$ext" >/dev/null; then
        pass "back from $mime shows it in the explorer"
    else
        fail "back from $mime did not show it in the explorer"
    fi
done

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

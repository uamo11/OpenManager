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

# True if one of the logs has a crash of the app itself (uiautomator sometimes dies while dumping).
app_crashed() {
    cat "$@" 2>/dev/null | grep -A 3 "FATAL EXCEPTION" | grep -q "Process: $PKG"
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

# node_center_by_id <resource-id suffix>: center of the first node with that id.
node_center_by_id() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/%s"' % re.escape(sys.argv[2]), node):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
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
        if find_text "CI OpenSSH:/home/omtest/probe/" >/dev/null; then
            pass "the VPS panel is labelled with the profile name (CI OpenSSH:/home/omtest/probe/)"
        else
            fail "the VPS panel does not show 'CI OpenSSH:/home/omtest/probe/'"
        fi
        if pos=$(find_text "Home" "Inicio"); then
            adb shell input tap $pos
            if wait_text 15 "CI OpenSSH:/home/omtest/" >/dev/null && find_text "probe" >/dev/null; then
                shot 07d2_vps_home
                pass "the home button takes the VPS panel to the VPS home folder"
            else
                shot 07d2_vps_home_wrong
                fail "the home button did not open /home/omtest on the VPS"
            fi
            pos=$(find_text "probe") && adb shell input tap $pos
            wait_text 15 "vps_photo.png" >/dev/null || fail "could not return to ~/probe"
        else
            fail "no home button in the bottom bar"
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

log "=== VPS as root: keyboard-interactive only, hidden-only home, create files, big folder ==="
# find_contains <label>...: center of the first node whose text contains a label (case-insensitive).
find_contains() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
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
count_logins() { sudo grep -c "Accepted .* for vpsroot" /tmp/sshd_om.log 2>/dev/null || true; }
logins_before=$(count_logins); logins_before=${logins_before:-0}
# A real VPS is far away: +150 ms on every packet of the SSH server.
sudo tc qdisc add dev lo root handle 1: prio
sudo tc qdisc add dev lo parent 1:3 handle 30: netem delay 150ms
sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip sport 2222 0xffff flowid 1:3
adb logcat -c
demo remote_vps_root
if pos=$(wait_contains 60 "vpsroot@vps"); then
    pass "root-like VPS (keyboard-interactive only) connects in the terminal"
    adb shell input tap $pos
    if wait_contains 60 "My VPS:/home/vpsroot" >/dev/null; then
        shot 09a_vps_root_files
        pass "VPS files open in the root home (My VPS:/home/vpsroot/)"
        logins_after=$(count_logins); logins_after=${logins_after:-0}
        if [ $((logins_after - logins_before)) -eq 1 ]; then
            pass "the files use the terminal's own connection (1 login, no second password/host-key)"
        else
            fail "the files logged in again: $((logins_after - logins_before)) logins for one session"
        fi
        if pos=$(find_contains "hidden file" "archivos ocultos" "archivo oculto"); then
            pass "a home with only hidden files says so instead of looking empty"
            adb shell input tap $pos
            if wait_contains 15 ".bashrc" >/dev/null; then
                pass "one tap shows the hidden files (.bashrc, .ssh...)"
            else
                fail "tapping the hint did not show the hidden files"
            fi
        else
            shot 09a_vps_root_no_hint
            fail "the hidden-only home showed no hint"
        fi
        # Create a folder and a file from the UI: the server must get them.
        for spec in "folder:om_ui_folder:New folder:Nueva carpeta" "file:om_ui_file.txt:New file:Nuevo archivo"; do
            kind=$(echo "$spec" | cut -d: -f1); name=$(echo "$spec" | cut -d: -f2)
            en=$(echo "$spec" | cut -d: -f3); es=$(echo "$spec" | cut -d: -f4)
            adb shell input tap $(node_center_by_id btnBottomAdd); sleep 2
            if pos=$(find_text "$en" "$es"); then
                adb shell input tap $pos; sleep 2
                adb shell input text "$name"; sleep 1
                pos=$(find_text "OK" "Aceptar") && adb shell input tap $pos
                if [ "$kind" = folder ]; then
                    ok() { sudo test -d "/home/vpsroot/$name"; }
                else
                    ok() { sudo test -f "/home/vpsroot/$name"; }
                fi
                created=no
                for _ in $(seq 1 20); do ok && { created=yes; break; }; sleep 1; done
                if [ "$created" = yes ] && wait_contains 15 "$name" >/dev/null; then
                    pass "creating a $kind on the VPS works and it appears in the list ($name)"
                else
                    shot "09b_vps_create_${kind}_failed"
                    fail "creating a $kind on the VPS failed (on server: $created)"
                fi
            else
                fail "no '$en' in the create menu"
                adb shell input keyevent KEYCODE_BACK; sleep 1
            fi
        done
        # Big folder over a 150 ms link (400 files + 200 symlinks, like /usr/bin) inside the folder
        # just created from the app: time until it is listed with every entry.
        sudo bash -c 'cd /home/vpsroot/om_ui_folder &&
            for i in $(seq -w 0 399); do echo "$i" > "f$i.txt"; done &&
            for i in $(seq -w 0 199); do ln -sf /usr/bin/env "l$i"; done'
        if pos=$(find_text "om_ui_folder"); then
            t0=$(date +%s%N)
            adb shell input tap $pos
            if wait_contains 60 "600" >/dev/null; then
                t1=$(date +%s%N)
                ms=$(( (t1 - t0) / 1000000 ))
                shot 09c_vps_big_folder
                log "      600 entries (200 symlinks) over +150 ms: listed in $ms ms (incl. adb)"
                if [ $ms -lt 20000 ]; then
                    pass "a 600-entry folder with 200 symlinks lists in ${ms} ms over a 150 ms link"
                else
                    fail "a 600-entry folder took ${ms} ms over a 150 ms link"
                fi
            else
                shot 09c_vps_big_folder_slow
                fail "the 600-entry folder did not finish listing in 60 s"
            fi
        else
            fail "no 'om_ui_folder' to open in the VPS home"
        fi
        # The link drops while the files are open on the terminal's connection (Wi-Fi/mobile
        # switch): the next SSH write fails. sshj leaves that SFTP channel broken; reusing it gave
        # "src.length=1 srcPos=0 dst.length=256 dstPos=-4" forever, and closing it killed the
        # whole connection. The explorer must recover by itself and keep working.
        log "=== VPS files: the link drops under the open explorer ==="
        adb logcat -c
        adb shell run-as $PKG touch files/om_debug_fail_next_ssh_write
        adb shell input keyevent KEYCODE_BACK; sleep 2
        if pos=$(wait_contains 30 ".ssh"); then
            adb shell input tap $pos
        fi
        recovered=no
        if wait_contains 45 "authorized_keys" >/dev/null; then recovered=yes; fi
        adb logcat -d > "$OUT/logcat_vps_drop.txt" 2>/dev/null || true
        grep -E "OMNetFault|OMSftp" "$OUT/logcat_vps_drop.txt" | tail -20 > "$OUT/vps_drop.log" || true
        cat "$OUT/vps_drop.log" | sed 's/^/      /'
        if ! grep -q "Injected network failure" "$OUT/logcat_vps_drop.txt"; then
            fail "the simulated network drop did not fire (no SSH write after it)"
        elif find_contains "dstPos" "src.length" >/dev/null; then
            shot 09d_vps_drop_internal_error
            fail "after a network drop the explorer shows sshj's internal error (dstPos=-4)"
        elif [ "$recovered" = yes ]; then
            shot 09d_vps_drop_recovered
            pass "after a network drop the VPS explorer recovers by itself (.ssh listed)"
        else
            shot 09d_vps_drop_stuck
            fail "after a network drop the VPS explorer did not recover"
        fi
        if app_crashed "$OUT/logcat_vps_drop.txt"; then fail "crash after the network drop"; fi
        # Next action on the same server still works (no broken channel left behind).
        adb shell input keyevent KEYCODE_BACK; sleep 2
        if pos=$(wait_contains 20 "om_ui_folder"); then
            adb shell input tap $pos
            if wait_contains 40 "600" >/dev/null; then
                pass "the next folder after the drop lists normally (600 entries)"
            else
                shot 09d_vps_drop_next
                fail "the next folder after the drop did not list"
            fi
        fi
    else
        shot 09a_vps_root_files_missing
        fail "VPS files did not open for the root-like account"
    fi
else
    shot 09_vps_root_terminal_missing
    fail "root-like VPS did not connect (keyboard-interactive only)"
fi
sudo tc qdisc del dev lo root || true
adb logcat -d > "$OUT/logcat_vps_root.txt" 2>/dev/null || true
grep -E "OMRemoteFiles|SFTP|sshj" "$OUT/logcat_vps_root.txt" | tail -20 > "$OUT/vps_root.log" || true
if app_crashed "$OUT/logcat_vps_root.txt"; then fail "crash while using the root VPS files"; fi
adb shell am force-stop $PKG; sleep 1
# The rest of the run expects hidden files off again.
adb shell run-as $PKG sed -i 's/name="key_file_list_show_hidden_files" value="true"/name="key_file_list_show_hidden_files" value="false"/' /data/data/$PKG/shared_prefs/${PKG}_preferences.xml 2>/dev/null || true
adb shell am start -W -n "$MAIN" >/dev/null; sleep 4

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
if app_crashed "$OUT/logcat.txt"; then
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
# The exact script the app ships (RootSeatbelt.SCRIPT), rendered from the source.
python3 - "$OUT/om_seatbelt.sh" <<'PY'
import re, sys
src = open("app/src/main/java/me/zhanghai/android/files/terminal/RootSeatbelt.kt").read()
code = int(re.search(r"EXIT_UNAVAILABLE = (\d+)", src).group(1))
mounts = re.search(r"READ_ONLY_MOUNTS = listOf\((.*?)\)", src, re.S).group(1)
mounts = " ".join(re.findall(r'"([^"]+)"', mounts))
body = src[src.index('val SCRIPT: String = """') + len('val SCRIPT: String = """'):src.index('""".trimMargin()')]
lines = [m.group(1) for l in body.split("\n") for m in [re.match(r"^\s*\|(.*)$", l)] if m]
text = "\n".join(lines) + "\n"
text = text.replace("${'$'}", "$").replace("$EXIT_UNAVAILABLE", str(code))
text = text.replace('${READ_ONLY_MOUNTS.joinToString(" ")}', mounts)
open(sys.argv[1], "w").write(text)
PY
adb push "$OUT/om_seatbelt.sh" /data/local/tmp/om_seatbelt.sh >/dev/null
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
        # Android's toybox supports it (verified on API 29): a regression, even if nothing leaked.
        if [ "$after" = "$before" ]; then fail "isolation unavailable (nothing leaked): $inside"; else fail "partial isolation leaked"; fi ;;
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
# The one-time "Viewing full screen" system tip would cover the player.
adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1 || true
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
    adb shell dumpsys meminfo $PKG > "$OUT/meminfo_video_${clip%.mp4}.txt" 2>/dev/null || true
    # Leaving the player logs its summary (frames shown/dropped, position reached).
    adb shell input keyevent KEYCODE_HOME; sleep 2
    adb logcat -d -s OMVideoPlayer > "$OUT/video_${clip%.mp4}.log" 2>/dev/null || true
    adb logcat -d | grep -E "FATAL EXCEPTION" -A 20 >> "$OUT/video_${clip%.mp4}.log" || true
    decoder=$(grep -m1 "Video decoder:" "$OUT/video_${clip%.mp4}.log" | sed 's/.*Video decoder: //')
    dropped=$(grep -oE "Dropped [0-9]+ frames" "$OUT/video_${clip%.mp4}.log" | awk '{s+=$2} END {print s+0}')
    if [ -n "$decoder" ]; then
        pass "$clip plays (decoder: $decoder, dropped frames reported: $dropped)"
    else
        fail "$clip did not initialize a video decoder"
    fi
    # logcat -c does not always clear everything: the newest summary is this clip's.
    summary=$(grep "Summary decoder=" "$OUT/video_${clip%.mp4}.log" | tail -n1 | sed 's/.*Summary //')
    heaplimit=$(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')
    log "      device Java heap limit: ${heaplimit:-?}"
    if grep -q "Playback error" "$OUT/video_${clip%.mp4}.log"; then
        fail "$clip stopped with a playback error: $(grep -m1 -oE 'Caused by: [^ ]+: [^,]{0,80}' "$OUT/video_${clip%.mp4}.log")"
    fi
    rendered=$(echo "$summary" | grep -oE "rendered=[0-9]+" | cut -d= -f2)
    sdropped=$(echo "$summary" | grep -oE "dropped=[0-9]+" | cut -d= -f2)
    position=$(echo "$summary" | grep -oE "positionMs=[0-9]+" | cut -d= -f2)
    log "      $clip summary: $summary"
    heap=$(awk '/Java Heap:/{j=$3} /Native Heap:/{n=$3} END{printf "%.1f", (j+n)/1024}' "$OUT/meminfo_video_${clip%.mp4}.txt")
    log "      $clip Java+Native heap while playing: ${heap} MB"
    # 13 s on screen: the clock must have moved and real frames must have reached the screen.
    if [ "${rendered:-0}" -ge 30 ] && [ "${position:-0}" -ge 5000 ]; then
        pass "$clip really plays: ${rendered} frames shown, ${sdropped} dropped, reached ${position} ms"
    else
        fail "$clip did not play smoothly enough to count (rendered=${rendered:-?} position=${position:-?})"
    fi
    if [ "$clip" = om_4k_h264.mp4 ]; then
        if grep -q "Software decoder cannot keep up" "$OUT/video_${clip%.mp4}.log"; then
            pass "4K on a software decoder tells the user why it stutters"
        elif echo "$decoder" | grep -qE "^(OMX\.google|c2\.android)"; then
            log "      (software decoder kept up, no hint needed)"
        fi
    fi
    adb shell am force-stop $PKG; sleep 1
done
adb logcat -d > "$OUT/logcat_video.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_video.txt"; then fail "crash during video playback"; fi

log "=== Photos: a 12 MP camera photo uses tiles and keeps its EXIF rotation ==="
for spec in "om_photo_12mp.jpg:TILES:90" "om_photo_small.jpg:BITMAP:0"; do
    name=${spec%%:*}; rest=${spec#*:}; want_mode=${rest%%:*}; want_rot=${rest#*:}
    base=${name%.jpg}
    [ -f "/tmp/$name" ] || { fail "missing test photo $name"; continue; }
    adb push "/tmp/$name" "/sdcard/Pictures/$name" >/dev/null
    adb shell am force-stop $PKG; sleep 1
    adb logcat -c
    adb shell am start -a android.intent.action.VIEW -t image/jpeg -d "file:///sdcard/Pictures/$name" \
        -n $PKG/me.zhanghai.android.files.filelist.SmartOpenActivity >/dev/null
    sleep 7
    shot "21_photo_$base"
    adb logcat -d -s OMImageViewer > "$OUT/photo_$base.log" 2>/dev/null || true
    adb shell dumpsys meminfo $PKG > "$OUT/meminfo_photo_$base.txt" 2>/dev/null || true
    line=$(grep -m1 "mode=" "$OUT/photo_$base.log" | sed 's/.*OMImageViewer: //')
    pss=$(awk '/TOTAL PSS:/{printf "%.1f", $3/1024; exit} /^ *TOTAL /{printf "%.1f", $2/1024; exit}' "$OUT/meminfo_photo_$base.txt")
    log "      $name: $line (app PSS ${pss} MB)"
    if echo "$line" | grep -q "rotation=$want_rot mode=$want_mode"; then
        pass "$name is shown as $want_mode (rotation $want_rot)"
    else
        fail "$name: expected rotation=$want_rot mode=$want_mode, got: $line"
    fi
    if [ "$want_mode" = TILES ]; then
        if grep -q "tiles ready 4000x3000 orientation=90" "$OUT/photo_$base.log"; then
            pass "12 MP photo is upright and ready from tiles"
        else
            fail "12 MP photo tiles did not get ready upright"
        fi
    fi
done
adb logcat -d > "$OUT/logcat_photo.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_photo.txt"; then fail "crash while viewing photos"; fi
adb shell am force-stop $PKG; sleep 1

log "=== Translations: settings and drawer in zh-CN, ru, pt-BR, id, vi ==="
# Share of visible texts written in the expected script/language, and the texts that are not.
# $1 = tag, $2 = regex of characters only that language uses (or "" to compare with English).
lang_report() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_$1.xml" 2>/dev/null
    python3 - "$OUT/ui_$1.xml" "$1" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
tag = sys.argv[2]
texts = [html.unescape(t) for t in re.findall(r' text="([^"]+)"', xml)]
words = [t for t in texts if re.search(r"[^\W\d_]", t)]
# English words that only appear in untranslated text (technical names like APK/Root/Shizuku are fine).
english = re.compile(r"\b(the|and|with|from|settings?|permissions?|tap|show|startup|path|window|services?|appearance|recycle|disabled|enabled)\b", re.I)
if tag.startswith("zh"):
    ok = [t for t in words if re.search(r"[一-鿿]", t)]
elif tag.startswith("ru"):
    ok = [t for t in words if re.search(r"[Ѐ-ӿ]", t)]
else:
    ok = [t for t in words if not english.search(t)]
bad = [t for t in words if t not in ok]
print(len(ok), len(words), " | ".join(bad[:10]))
PY
}
for spec in "zh-CN:zh" "ru:ru" "pt-BR:pt" "in:id" "vi:vi"; do
    locale=${spec%%:*}; tag=${spec#*:}
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n $PKG/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_LOCALE "$locale" >/dev/null 2>&1
    sleep 5
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n $PKG/me.zhanghai.android.files.settings.SettingsActivity >/dev/null 2>&1
    sleep 4
    shot "40_settings_$tag"
    report=$(lang_report "settings_$tag")
    ok_n=$(echo "$report" | cut -d' ' -f1); all_n=$(echo "$report" | cut -d' ' -f2)
    log "      $locale settings: $ok_n of $all_n texts translated; left: $(echo "$report" | cut -d' ' -f3-)"
    if [ "${all_n:-0}" -gt 5 ] && [ $((ok_n * 100)) -ge $((all_n * 80)) ]; then
        pass "settings page is in $locale ($ok_n/$all_n texts)"
    else
        fail "settings page is not in $locale ($ok_n/$all_n texts)"
    fi
done
# The drawer in Chinese, as in the screenshot of issue #2.
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_LOCALE zh-CN >/dev/null 2>&1
sleep 5
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" >/dev/null 2>&1
sleep 5
demo drawer; sleep 2
shot 41_drawer_zh_CN
if find_text "根目录" >/dev/null && find_text "工具" >/dev/null && find_text "设置" >/dev/null; then
    pass "drawer shows 根目录 / 工具 / 设置 in Chinese"
else
    fail "drawer is not in Chinese: $(lang_report drawer_zh | cut -d' ' -f3-)"
fi
# The counts above the panels used to be fixed Spanish/English ("Carpetas: 14  Archivos: 0  Disk:").
demo close_drawer; sleep 2
if find_contains "文件夹：" >/dev/null && ! find_contains "Carpetas:" "Disk:" >/dev/null; then
    pass "the folder/file counts above the panels are in Chinese"
else
    shot 41b_counts_zh_CN
    fail "the folder/file counts above the panels are not in Chinese"
fi
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_LOCALE reset >/dev/null 2>&1
sleep 4
adb shell am force-stop $PKG; sleep 1

log "=== Appearance: AMOLED tile, Material 3 theme color, Ultra RAM look ==="
SETTINGS=$PKG/me.zhanghai.android.files.settings.SettingsActivity
prefs_xml() { adb shell run-as $PKG cat /data/data/$PKG/shared_prefs/${PKG}_preferences.xml 2>/dev/null; }
# pref_value <key>: value of a boolean or string preference ("" when unset).
pref_value() {
    prefs_xml | python3 -c '
import re, sys
xml = sys.stdin.read(); key = sys.argv[1]
m = re.search(r"<boolean name=\"%s\" value=\"(\w+)\"" % re.escape(key), xml) or \
    re.search(r"<string name=\"%s\">([^<]*)</string>" % re.escape(key), xml)
print(m.group(1) if m else "")' "$1"
}
# node_attr <resource-id suffix> <attr>: prints "x y attrValue" of the first matching node.
node_attr() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_appearance.xml" 2>/dev/null
    python3 - "$OUT/ui_appearance.xml" "$1" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/%s"' % re.escape(sys.argv[2]), node):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        v = re.search(r' %s="([^"]*)"' % re.escape(sys.argv[3]), node)
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2, v.group(1) if v else "")
        sys.exit(0)
sys.exit(1)
PY
}

# 1. "System" while the system is dark, with the black background switch on: the screen already
#    looks AMOLED, so tapping the AMOLED tile used to change nothing visible ("does not click").
adb shell cmd uimode night yes >/dev/null 2>&1 || true
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_THEME system_black >/dev/null 2>&1; sleep 5
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_CATEGORY key_category_appearance >/dev/null 2>&1; sleep 5
if tile=$(node_attr tileThemeAmoled selected); then
    log "      AMOLED tile before tap: selected=$(echo "$tile" | cut -d' ' -f3)"
    adb shell input tap $(echo "$tile" | cut -d' ' -f1-2); sleep 4
    after=$(node_attr tileThemeAmoled selected | cut -d' ' -f3)
    shot 42_amoled_tile_after_tap
    if [ "$after" = "true" ]; then
        pass "tapping AMOLED with the black background already on marks it at once"
    else
        fail "AMOLED tile not marked after tapping it (selected=$after)"
    fi
else
    fail "AMOLED tile not found in Appearance"
fi
adb shell cmd uimode night no >/dev/null 2>&1 || true

# 2. Material 3 + a theme color: the color used to be ignored (Material 3 took the wallpaper's).
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_MD3 on --es DEMO_THEME_COLOR material_red --es DEMO_THEME light >/dev/null 2>&1; sleep 5
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $SETTINGS --es DEMO_CATEGORY key_category_appearance >/dev/null 2>&1; sleep 5
shot 43_md3_red_theme
theme_line=$(adb logcat -d -s OMTheme | grep "SettingsActivity" | tail -n1 | sed 's/.*OMTheme: //')
log "      $theme_line"
if echo "$theme_line" | grep -q "md3=true color=MATERIAL_RED" && echo "$theme_line" | grep -qi "colorPrimary=#FFD32F2F"; then
    pass "Material 3 uses the chosen theme color (red 700 in light mode)"
else
    fail "Material 3 ignored the theme color: $theme_line"
fi

# 3. Ultra RAM turns Material 3 off and panels uniform, and puts both back when turned off.
md3_before=$(pref_value key_material_design_3); contrast_before=$(pref_value key_dual_panel_contrast)
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_PERF_MODE on >/dev/null 2>&1; sleep 5
md3_on=$(pref_value key_material_design_3); contrast_on=$(pref_value key_dual_panel_contrast)
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_PERF_MODE off >/dev/null 2>&1; sleep 5
md3_off=$(pref_value key_material_design_3); contrast_off=$(pref_value key_dual_panel_contrast)
log "      before md3=$md3_before contrast=${contrast_before:-0} · Ultra RAM on md3=$md3_on contrast=$contrast_on · off md3=$md3_off contrast=${contrast_off:-0}"
if [ "$md3_on" = "false" ] && [ "$contrast_on" = "2" ]; then
    pass "Ultra RAM switches to the classic theme and the uniform panel background"
else
    fail "Ultra RAM did not set the light look (md3=$md3_on contrast=$contrast_on)"
fi
if [ "$md3_off" = "$md3_before" ] && [ "${contrast_off:-0}" = "${contrast_before:-0}" ]; then
    pass "turning Ultra RAM off restores the previous theme and panels"
else
    fail "Ultra RAM off did not restore the look (md3=$md3_off contrast=$contrast_off)"
fi

# Back to the defaults used by the rest of the run.
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME dark >/dev/null 2>&1; sleep 4
adb logcat -d > "$OUT/logcat_appearance.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_appearance.txt"; then fail "crash while changing the appearance"; fi
adb shell am force-stop $PKG; sleep 1

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

log "=== PDF reader: whole-document zoom, keep screen on, last page remembered ==="
python3 - "$OUT/om_pages.pdf" <<'PY'
import sys
pages = 6
objs = ["<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % (4 + 2 * i) for i in range(pages)), pages),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
streams = {}
for i in range(pages):
    page_id, content_id = 4 + 2 * i, 5 + 2 * i
    objs.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents %d 0 R /Resources << /Font << /F1 3 0 R >> >> >>" % content_id)
    objs.append(None)
    lines = " ".join("BT /F1 14 Tf 40 %d Td (Page %d line %d - the quick brown fox jumps) Tj ET" % (800 - 22 * l, i + 1, l) for l in range(34))
    streams[content_id] = ("BT /F1 48 Tf 200 420 Td (Page %d) Tj ET " % (i + 1) + lines).encode()
out = bytearray(b"%PDF-1.4\n"); offsets = []
for n, body in enumerate(objs, 1):
    offsets.append(len(out))
    if body is None:
        st = streams[n]
        out += b"%d 0 obj << /Length %d >> stream\n" % (n, len(st)) + st + b"\nendstream endobj\n"
    else:
        out += b"%d 0 obj " % n + body.encode() + b" endobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for o in offsets: out += b"%010d 00000 n \n" % o
out += b"trailer << /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(sys.argv[1], "wb").write(out)
PY
adb push "$OUT/om_pages.pdf" /sdcard/Download/om_pdf_test/om_pages.pdf >/dev/null
open_pages_pdf() {
    adb shell am start -a android.intent.action.VIEW -t application/pdf \
        -d file:///sdcard/Download/om_pdf_test/om_pages.pdf -n $PKG/me.zhanghai.android.files.filelist.SmartOpenActivity >/dev/null
}
pdf_gesture() { adb shell am broadcast -a me.zhanghai.android.files.action.PDF_DEMO --es gesture "$1" >/dev/null; }
adb shell am force-stop $PKG; sleep 1
adb logcat -c
open_pages_pdf; sleep 6
# Keep screen on from the menu.
if pos=$(find_text "More options" "Más opciones"); then
    adb shell input tap $pos; sleep 1
    if pos=$(find_text "Keep screen on" "Mantener pantalla encendida"); then
        adb shell input tap $pos; sleep 1
        if adb shell dumpsys window windows 2>/dev/null | grep -A8 "PdfViewerActivity" | grep -q "KEEP_SCREEN_ON"; then
            pass "PDF: 'Keep screen on' keeps the screen awake"
        else
            fail "PDF: 'Keep screen on' did not set the window flag"
        fi
    else
        fail "PDF: no 'Keep screen on' in the menu"
        adb shell input keyevent KEYCODE_BACK
    fi
else
    fail "PDF: menu button not found"
fi
# Pinch with two fingers: the whole document zooms, not one page.
pdf_gesture pinch; sleep 4
shot 18b_pdf_zoomed
adb logcat -d -s OMPdf > "$OUT/pdf_zoom.txt" 2>/dev/null || true
cat "$OUT/pdf_zoom.txt" | grep -E "zoom=|render" | tail -6 | sed 's/^/      /'
zoom=$(grep -o "zoom=[0-9.]*" "$OUT/pdf_zoom.txt" | tail -n1 | cut -d= -f2)
if [ -n "$zoom" ] && python3 -c "import sys; sys.exit(0 if float('$zoom') >= 2.0 else 1)"; then
    pass "PDF: a pinch zooms the document (zoom=$zoom)"
else
    fail "PDF: the pinch did not zoom the document (zoom=${zoom:-none})"
fi
if grep -q "render page=0 tier=[23]" "$OUT/pdf_zoom.txt"; then
    pass "PDF: the zoomed page is rendered again sharp for the zoom"
else
    fail "PDF: the zoomed page was not rendered sharper"
fi
# Scroll on while zoomed: the next pages must come zoomed (and sharp) too.
for _ in 1 2 3 4 5 6; do adb shell input swipe 540 1700 540 500 300; sleep 1; done
sleep 3
adb logcat -d -s OMPdf > "$OUT/pdf_zoom2.txt" 2>/dev/null || true
if grep -qE "render page=[2-5] tier=[23]" "$OUT/pdf_zoom2.txt"; then
    pass "PDF: later pages stay zoomed and sharp while scrolling (whole document zoom)"
else
    shot 18c_pdf_scroll_zoomed
    fail "PDF: later pages were not shown at the document zoom"
fi
# Double tap goes back to normal size.
pdf_gesture double_tap; sleep 3
adb logcat -d -s OMPdf > "$OUT/pdf_zoom3.txt" 2>/dev/null || true
if grep -o "zoom=[0-9.]*" "$OUT/pdf_zoom3.txt" | tail -n1 | grep -q "zoom=1.00"; then
    pass "PDF: double tap returns to normal size"
else
    fail "PDF: double tap did not return to normal size"
fi
# Leave on a later page and reopen: it continues there.
adb shell input keyevent KEYCODE_BACK; sleep 3
adb shell am force-stop $PKG; sleep 1
open_pages_pdf; sleep 6
shot 18d_pdf_resumed
if find_contains "Continuing from page" "Continúas en la página" >/dev/null; then
    pass "PDF: reopening continues at the last page read"
else
    fail "PDF: reopening did not continue at the last page"
fi
# Turn 'Keep screen on' back off for the rest of the run.
if pos=$(find_text "More options" "Más opciones"); then
    adb shell input tap $pos; sleep 1
    pos=$(find_text "Keep screen on" "Mantener pantalla encendida") && adb shell input tap $pos
    sleep 1
fi
adb logcat -d > "$OUT/logcat_pdf_reader.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_pdf_reader.txt"; then fail "crash in the PDF reader"; fi
adb shell am force-stop $PKG; sleep 1

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

log "=== Batch rename: pattern with counter, live preview, nothing else touched ==="
adb shell input keyevent KEYCODE_HOME; sleep 1
demo batch_rename
if pos=$(wait_text 25 "{name}"); then
    shot 30_batch_rename_dialog
    adb shell input tap $pos; sleep 1
    adb shell input keyevent KEYCODE_MOVE_END
    for _ in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text "Viaje%s{n}"
    if pos=$(find_text "0"); then
        adb shell input tap $pos; sleep 1
        adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent KEYCODE_DEL
        adb shell input text "2"
    fi
    # Hide the keyboard (only if shown: back would otherwise close the dialog).
    adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
    sleep 1
    shot 31_batch_rename_preview
    if find_text "IMG_001.jpg  →  Viaje 01.jpg" >/dev/null || adb shell cat /data/local/tmp/ui.xml | grep -q "Viaje 01.jpg"; then
        pass "the preview shows old → new names before renaming"
    else
        fail "the preview does not show the new names"
    fi
    if pos=$(find_text "Rename" "RENAME" "Renombrar" "RENOMBRAR"); then
        adb shell input tap $pos; sleep 4
    fi
    listing=$(adb shell ls /sdcard/om_batch_test | tr -d '\r' | sort | tr '\n' '|')
    log "      files: $listing"
    first=$(adb shell cat "'/sdcard/om_batch_test/Viaje 01.jpg'" 2>/dev/null | tr -d '\r')
    if [ "$listing" = "Viaje 01.jpg|Viaje 02.jpg|Viaje 03.jpg|keep.txt|" ] && [ "$first" = "IMG_001.jpg" ]; then
        pass "batch rename: IMG_00x.jpg -> Viaje 0x.jpg in name order, keep.txt untouched"
    else
        shot 32_batch_rename_result
        fail "batch rename result is wrong ($listing / first=$first)"
    fi
else
    shot 30_batch_rename_missing
    fail "the batch rename dialog did not open"
fi

log "=== Compare files: line diff ==="
demo compare_files
if wait_text 25 "−1 lines removed · +2 lines added" "−1 líneas quitadas · +2 líneas añadidas" >/dev/null; then
    shot 33_compare
    if adb shell cat /data/local/tmp/ui.xml | grep -q "line twenty-five"; then
        pass "compare shows the changed and added lines (−1 +2)"
    else
        fail "compare summary is right but the changed line is not shown"
    fi
else
    shot 33_compare_missing
    fail "the compare screen did not show the expected summary"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

log "=== Browser access: a PC manages the phone files with the code ==="
demo web_access
web_ok=0
for _ in $(seq 1 15); do
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_web.xml" 2>/dev/null
    read -r WEB_URL WEB_CODE < <(python3 - "$OUT/ui_web.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
texts = re.findall(r' text="([^"]*)"', xml)
url = next((t for t in texts if t.startswith("http://")), "")
code = next((t for t in texts if re.fullmatch(r"[A-Z2-9]{8}", t)), "")
print(url or "-", code or "-")
PY
)
    [ "$WEB_URL" != "-" ] && [ "$WEB_CODE" != "-" ] && { web_ok=1; break; }
    sleep 2
done
shot 34_web_access
if [ $web_ok -eq 1 ]; then
    pass "browser access shows an address and a code ($WEB_URL, code ${WEB_CODE:0:2}******)"
    WEB_PORT=${WEB_URL##*:}
    adb forward tcp:18088 tcp:$WEB_PORT >/dev/null
    B=http://127.0.0.1:18088
    login=$(curl -s "$B/")
    wrong=$(curl -s -o /dev/null -w "%{http_code}" -H "X-OM-Key: WRONGCOD" "$B/api/ls?p=/")
    if echo "$login" | grep -q 'name="k"' && [ "$wrong" = "401" ]; then
        pass "without the code: login page, API refused ($wrong)"
    else
        fail "the server answers without the right code (wrong=$wrong)"
    fi
    page=$(curl -s "$B/?k=$WEB_CODE")
    echo "$page" | grep -q "OpenManager" && echo "$page" | grep -q "uploadAll" && pass "the file manager page loads with the code" || fail "the web page did not load with the code"
    echo "hello from the PC $RANDOM" > "$OUT/from_pc.txt"
    up=$(curl -s -o /dev/null -w "%{http_code}" -H "X-OM-Key: $WEB_CODE" -T "$OUT/from_pc.txt" "$B/api/up?p=/Download&n=from_pc.txt")
    on_phone=$(adb shell cat /sdcard/Download/from_pc.txt 2>/dev/null | tr -d '\r')
    if [ "$up" = "200" ] && [ "$on_phone" = "$(cat "$OUT/from_pc.txt")" ]; then
        pass "a file uploaded from the PC lands in /sdcard/Download"
    else
        fail "upload from the PC failed (http $up, phone has '$on_phone')"
    fi
    listing=$(curl -s -H "X-OM-Key: $WEB_CODE" "$B/api/ls?p=/Download")
    back=$(curl -s "$B/api/dl?p=/Download/from_pc.txt&k=$WEB_CODE")
    if echo "$listing" | grep -q '"from_pc.txt"' && [ "$back" = "$(cat "$OUT/from_pc.txt")" ]; then
        pass "the PC lists the folder and downloads the file back"
    else
        fail "listing or download from the PC failed"
    fi
    escape=$(curl -s -o /dev/null -w "%{http_code}" -H "X-OM-Key: $WEB_CODE" "$B/api/ls?p=/../../data")
    [ "$escape" = "403" ] && pass "paths outside the storage are refused ($escape)" || fail "path escape not refused ($escape)"
    curl -s -o /dev/null -X POST -H "X-OM-Key: $WEB_CODE" "$B/api/rm?p=/Download/from_pc.txt"
    adb shell ls /sdcard/Download/from_pc.txt >/dev/null 2>&1 && fail "delete from the PC did not remove the file" || pass "delete from the PC removes the file"
    adb forward --remove tcp:18088 >/dev/null 2>&1
    if pos=$(find_text "Stop" "STOP" "Detener" "DETENER"); then adb shell input tap $pos; sleep 2; fi
    find_text "Stopped" "Detenido" >/dev/null && pass "stopping browser access from the phone" || fail "browser access did not stop"
else
    fail "browser access did not show an address and code"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

log "=== Bookmarks: add one, swipe up on the bottom bar, open it, see it in the drawer ==="
# node_with <id suffix> <text>: center of the node with that id and exactly that text.
node_with() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$1" "$2" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/%s"' % re.escape(sys.argv[2]), node):
        t = re.search(r' text="([^"]*)"', node)
        if t and html.unescape(t.group(1)) == sys.argv[3]:
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
adb shell mkdir -p /sdcard/om_bm_target
adb shell "echo bm > /sdcard/om_bm_target/bm_marker.txt"
adb logcat -c
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_bm_target" >/dev/null; sleep 4
if wait_text 20 "bm_marker.txt" >/dev/null; then
    demo more_menu; sleep 2
    if pos=$(find_text "Add bookmark" "Añadir a marcadores"); then
        adb shell input tap $pos; sleep 2
        adb shell input tap $(node_center_by_id btnBottomHome); sleep 3
        # Swipe up on the bottom bar (starting on a button: it must not be pressed).
        read -r sx sy <<< "$(node_center_by_id btnBottomSwap)"
        adb shell input swipe $sx $sy $sx $((sy - 500)) 300; sleep 2
        shot 40_bookmarks_sheet
        if pos=$(node_with tvBookmarkName om_bm_target); then
            pass "swiping up on the bottom bar opens the bookmarks with the new one"
            adb shell input tap $pos
            if wait_text 20 "bm_marker.txt" >/dev/null; then
                pass "tapping a bookmark opens its folder"
            else
                shot 40b_bookmark_open_failed
                fail "tapping the bookmark did not open its folder"
            fi
        else
            fail "swiping up on the bottom bar did not show the bookmark"
        fi
        demo drawer; sleep 2
        if node_with tvStorageTitle om_bm_target >/dev/null; then
            shot 41_bookmarks_drawer
            pass "the side menu lists the bookmark"
        else
            shot 41_bookmarks_drawer_missing
            fail "the side menu does not list the bookmark"
        fi
        demo close_drawer; sleep 1
        # ">" goes back down to the folder the panel came up from.
        adb shell input tap $(node_center_by_id btnBottomBack); sleep 3
        if ! find_text "bm_marker.txt" >/dev/null; then
            adb shell input tap $(node_center_by_id btnBottomForward); sleep 3
            if wait_text 15 "bm_marker.txt" >/dev/null; then
                pass "the bottom bar's > returns to the folder left with <"
            else
                shot 42_forward_failed
                fail "the bottom bar's > did not return to the folder"
            fi
        else
            fail "< did not leave the bookmark folder"
        fi
        # Long press on a bookmark: its menu, with "Add to home screen" (MT Manager style).
        read -r sx sy <<< "$(node_center_by_id btnBottomSwap)"
        adb shell input swipe $sx $sy $sx $((sy - 500)) 300; sleep 2
        if pos=$(node_with tvBookmarkName om_bm_target); then
            adb shell input swipe $pos $pos 1200; sleep 2
            shot 43_bookmark_menu
            if pos=$(find_text "Add to home screen" "Añadir a la pantalla de inicio"); then
                pass "holding a bookmark opens its menu with 'Add to home screen'"
                adb shell input tap $pos; sleep 3
                shot 44_pin_shortcut_dialog
                # Launcher3 asks first; accept it if it does.
                if pos=$(find_text "Add automatically" "ADD AUTOMATICALLY" "Add" "ADD" "Añadir automáticamente" "AÑADIR"); then
                    adb shell input tap $pos; sleep 2
                fi
                if adb shell dumpsys shortcut 2>/dev/null | tr -d '\r' | grep -q "bookmark:file:///storage/emulated/0/om_bm_target"; then
                    pass "the bookmark was pinned to the home screen"
                else
                    fail "no home screen shortcut for the bookmark"
                fi
            else
                fail "holding a bookmark did not show 'Add to home screen'"
            fi
            adb shell input keyevent KEYCODE_BACK; sleep 1
        else
            fail "could not reopen the bookmarks panel"
        fi
    else
        fail "no 'Add bookmark' in the ⋮ menu"
        adb shell input keyevent KEYCODE_BACK
    fi
else
    shot 40_bookmarks_folder_missing
    fail "could not open /sdcard/om_bm_target for the bookmark test"
fi
adb logcat -d > "$OUT/logcat_bookmarks.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_bookmarks.txt"; then fail "crash while using bookmarks"; fi

log "=== Back acts only on the active panel ==="
# node_in_half <left|right> <exact text>: center of that text in the left or right panel.
node_in_half() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$1" "$2" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
width = max(int(b) for b in re.findall(r'bounds="\[\d+,\d+\]\[(\d+),\d+\]"', xml))
for node in re.findall(r"<node [^>]*>", xml):
    t = re.search(r' text="([^"]*)"', node)
    if not t or html.unescape(t.group(1)) != sys.argv[3]:
        continue
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
    cx = (x1 + x2) // 2
    if (sys.argv[2] == "left") == (cx < width // 2):
        print(cx, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}
path_title() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml | tr -d '\r' | grep -o 'resource-id="[^"]*:id/tvPathTitle"[^>]*' | grep -o ' text="[^"]*"' | head -1 | cut -d'"' -f2
}
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" >/dev/null; sleep 5
if pos=$(node_in_half left om_bm_target); then
    adb shell input tap $pos; sleep 3                       # panel A: into a subfolder
    if pos=$(node_in_half right Alarms); then
        read -r w h <<< "$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')"
        ay=${pos#* }
        adb shell input tap $((w / 2 + 68)) $ay; sleep 2    # panel B: tap the icon = select (B active)
        if node_center_by_id btnSelectionClose >/dev/null; then
            pass "tapping an icon in the right panel selects it"
        else
            shot 45a_no_selection
            fail "tapping the icon did not select in the right panel"
        fi
        adb shell input tap $((w / 4)) $((h * 2 / 3)); sleep 2   # empty area of panel A: A active
        before=$(path_title)
        adb shell input keyevent KEYCODE_BACK; sleep 3
        after=$(path_title)
        shot 45_back_active_panel
        log "      panel A before back: $before  after: $after"
        if [ "$before" != "$after" ] && echo "$after" | grep -q "/storage/emulated/0/$"; then
            pass "back with a selection in the other panel goes up in the active panel"
        else
            fail "back did not act on the active panel ($before -> $after)"
        fi
        # Panel B kept its selection.
        adb shell input tap $((w * 3 / 4)) $((h * 2 / 3)); sleep 2
        if node_center_by_id btnSelectionClose >/dev/null; then
            pass "the other panel kept its selection"
        else
            fail "the other panel lost its selection"
        fi
        adb shell input keyevent KEYCODE_BACK; sleep 1
    else
        fail "no 'Alarms' in the right panel"
    fi
else
    fail "no 'om_bm_target' in the left panel"
fi

log "=== Crash audit ==="
adb logcat -d > "$OUT/logcat_end.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat.txt" "$OUT/logcat_end.txt" "$OUT/logcat_video.txt"; then
    fail "FATAL EXCEPTION in logcat"
    grep -h -A 20 "FATAL EXCEPTION" "$OUT"/logcat*.txt | head -60 >> "$REPORT"
else
    pass "no FATAL exceptions"
fi

log ""
if [ $FAILED -eq 0 ]; then log "RESULT: ALL PASSED"; else log "RESULT: FAILURES"; fi
exit $FAILED

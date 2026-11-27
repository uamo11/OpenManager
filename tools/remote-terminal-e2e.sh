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
    # A failed dump (no idle screen) must not leave the previous one to be read.
    adb shell rm -f /data/local/tmp/ui.xml
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

# dominant_color <raw> <id>: most common color inside the view with that id (last dump).
dominant_color() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$1" "$OUT/ui.xml" "$2" <<'PY2'
import re, struct, sys, collections
raw = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", raw[:8]); header = len(raw) - w * h * 4
xml = open(sys.argv[2], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/%s"' % re.escape(sys.argv[3]), n):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        c = collections.Counter()
        for y in range(y1, min(y2, h), 3):
            for x in range(x1, min(x2, w), 3):
                o = header + (y * w + x) * 4
                c[(raw[o], raw[o + 1], raw[o + 2])] += 1
        (r, g, b), _ = c.most_common(1)[0]
        print(r, g, b); sys.exit(0)
sys.exit(1)
PY2
}
# The explorer's bottom bar, to compare the terminal's key bar with it later.
adb shell am start -n "$MAIN" >/dev/null 2>&1; sleep 3
adb exec-out screencap > "$OUT/explorer_bar.raw" 2>/dev/null
explorer=$(dominant_color "$OUT/explorer_bar.raw" bottomBar || true)
explorer_top=$(dominant_color "$OUT/explorer_bar.raw" mainToolbar || true)

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
released=$(adb logcat -d -s OMSftp 2>/dev/null | grep -c "released" || true)
if [ "${released:-0}" -ge 1 ]; then
    pass "leaving the VPS files closes their connection (the SSH session stays)"
else
    fail "leaving the VPS files kept their SFTP connection open"
fi
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

log "=== Key bar: the explorer's bar colors, flat keys (user report: AI-slop look) ==="
shot 07f_key_bar
adb exec-out screencap > "$OUT/keybar.raw" 2>/dev/null
keybar=$(dominant_color "$OUT/keybar.raw" extraKeysView || true)
log "      key bar color: ${keybar:-?}  explorer bottom bar: ${explorer:-?}"
same=$(python3 -c "
a='$keybar'.split(); b='$explorer'.split()
print('yes' if len(a) == 3 and len(b) == 3 and max(abs(int(x) - int(y)) for x, y in zip(a, b)) <= 6 else 'no')")
if [ "$same" = yes ]; then
    pass "the terminal's key bar has the explorer's bottom-bar color"
else
    fail "the terminal's key bar does not match the explorer's bar (${keybar:-?} vs ${explorer:-?})"
fi

# The top too: toolbar and session strip in the explorer's toolbar color (it was a navy with
# outlined capsules), and the shortcuts strip on the key bar's color (it was rounded boxes).
same_color() {
    python3 -c "
a='$1'.split(); b='$2'.split()
print('yes' if len(a) == 3 and len(b) == 3 and max(abs(int(x) - int(y)) for x, y in zip(a, b)) <= 6 else 'no')"
}
term_top=$(dominant_color "$OUT/keybar.raw" toolbar || true)
term_hud=$(dominant_color "$OUT/keybar.raw" layoutTerminalHud || true)
shortcuts=$(dominant_color "$OUT/keybar.raw" remoteShortcutsScroll || true)
log "      toolbar: ${term_top:-?}  session strip: ${term_hud:-?}  explorer toolbar: ${explorer_top:-?}  shortcuts: ${shortcuts:-?}"
if [ "$(same_color "$term_top" "$explorer_top")" = yes ] && [ "$(same_color "$term_hud" "$explorer_top")" = yes ]; then
    pass "the terminal's toolbar and session strip have the explorer's toolbar color"
else
    fail "the terminal's top bars do not match the explorer's toolbar (${term_top:-?} / ${term_hud:-?} vs ${explorer_top:-?})"
fi
if [ "$(same_color "$shortcuts" "$keybar")" = yes ]; then
    pass "the shortcuts strip sits on the key bar's color (flat keys, no boxes)"
else
    fail "the shortcuts strip is not on the key bar's color (${shortcuts:-?} vs ${keybar:-?})"
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
# node_text_by_id <id>: text of the first node with that resource id.
node_text_by_id() {
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/%s"' % re.escape(sys.argv[2]), node):
        t = re.search(r' text="([^"]*)"', node)
        print(html.unescape(t.group(1)) if t else "")
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

log "=== SSH: a connection that dies in the background asks what to do ==="
adb logcat -c
demo remote_ssh; sleep 10
if find_text "⋮" >/dev/null; then
    adb shell input keyevent KEYCODE_HOME; sleep 2
    # The server ends the session while the app is in the background.
    # OpenSSH 9.8+ names the per-session process "sshd-session: omtest…" (older: "sshd: omtest…").
    sudo pkill -KILL -f "sshd(-session)?: omtest" || true
    sudo pkill -KILL -u omtest || true
    sleep 5
    # Back the way a user returns: Recents, then the app's card (the most recent one, centered).
    adb shell input keyevent KEYCODE_APP_SWITCH; sleep 3
    shot 09e0_recents
    read -r sw sh <<< "$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')"
    adb shell input tap $(( ${sw:-1080} / 2 )) $(( ${sh:-2400} / 2 )); sleep 4
    shot 09e_ssh_lost_in_background
    if find_text "Connection lost" "Conexión perdida" >/dev/null; then
        pass "back from the background, a dead SSH session says so with Reconnect / Server list"
        pos=$(find_text "Server list" "Lista de servidores") && adb shell input tap $pos; sleep 3
        top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[A-Za-z0-9_.]*")
        case "$top" in
            *ConnectionsActivity) pass "Server list leaves the dead session for the list of servers" ;;
            *) fail "Server list opened $top instead of the servers" ;;
        esac
    elif find_contains "Connection closed - press Enter" >/dev/null; then
        fail "the dead SSH session still shows a frozen '[Connection closed - press Enter]'"
    else
        fail "no 'Connection lost' notice after the SSH session died in the background"
    fi
else
    fail "could not open the SSH session for the background test"
fi
adb logcat -d > "$OUT/logcat_ssh_lost.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_ssh_lost.txt"; then fail "crash after the SSH session died in the background"; fi
adb shell am force-stop $PKG; sleep 1

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

log "=== Video gestures: seek, volume, brightness, hold for 2x (user report: no gestures) ==="
if [ -f /tmp/om_gestures_720p.mp4 ]; then
    adb push /tmp/om_gestures_720p.mp4 /sdcard/Movies/om_gestures_720p.mp4 >/dev/null
    adb logcat -c
    adb shell am start -n $PKG/me.zhanghai.android.files.viewer.video.VideoPlayerActivity \
        -a android.intent.action.VIEW -d "file:///sdcard/Movies/om_gestures_720p.mp4" -t video/mp4 >/dev/null
    sleep 8
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    root=$(python3 - "$OUT/ui.xml" <<'PY2'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/videoRoot"', n):
        print(" ".join(re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())); break
PY2
)
    if [ -n "$root" ]; then
        read -r vx1 vy1 vx2 vy2 <<< "$root"
        vw=$((vx2 - vx1)); vh=$((vy2 - vy1)); cy=$((vy1 + vh / 2))
        log "      player area: ${vw}x${vh}"
        # Horizontal drag over 40% of the width: about 48 s forward with a live preview.
        adb shell input swipe $((vx1 + vw * 3 / 10)) $cy $((vx1 + vw * 7 / 10)) $cy 900; sleep 2
        shot 12g_video_seek_gesture
        # Right side up: volume; left side up: brightness.
        adb shell media volume --stream 3 --set 3 >/dev/null 2>&1 || true
        adb shell input swipe $((vx1 + vw * 4 / 5)) $((vy1 + vh * 3 / 4)) $((vx1 + vw * 4 / 5)) $((vy1 + vh / 4)) 700; sleep 2
        adb shell input swipe $((vx1 + vw / 5)) $((vy1 + vh * 3 / 4)) $((vx1 + vw / 5)) $((vy1 + vh / 4)) 700; sleep 2
        # Long press: 2x while held.
        adb shell input swipe $((vx1 + vw / 2)) $((vy1 + vh * 3 / 4)) $((vx1 + vw / 2)) $((vy1 + vh * 3 / 4)) 2500; sleep 2
        adb logcat -d -s OMVideoPlayer > "$OUT/video_gestures.log" 2>/dev/null || true
        grep "Gesture" "$OUT/video_gestures.log" | sed 's/.*OMVideoPlayer: /      /' | tee -a "$REPORT" >/dev/null
        seek=$(grep -m1 "Gesture seek" "$OUT/video_gestures.log" | sed 's/.*from=\([0-9]*\) to=\([0-9]*\).*/\1 \2/')
        read -r sfrom sto <<< "${seek:-0 0}"
        moved=$(( ${sto:-0} - ${sfrom:-0} ))
        if [ "$moved" -ge 30000 ] && [ "$moved" -le 70000 ]; then
            pass "a horizontal swipe seeks the video ($((moved / 1000)) s forward)"
        else
            fail "the horizontal swipe did not seek as expected (${seek:-no seek gesture})"
        fi
        vol=$(grep -m1 "Gesture volume" "$OUT/video_gestures.log" | sed 's/.*volume=\([0-9]*\)\/[0-9]* from=\([0-9]*\).*/\1 \2/')
        read -r vnow vfrom <<< "${vol:-0 0}"
        if [ -n "$vol" ] && [ "${vnow:-0}" -gt "${vfrom:-0}" ]; then
            pass "swiping up on the right raises the volume ($vfrom -> $vnow)"
        else
            fail "swiping up on the right did not raise the volume (${vol:-no volume gesture})"
        fi
        bright=$(grep -m1 "Gesture brightness" "$OUT/video_gestures.log" | sed 's/.*brightness=\([0-9.]*\) from=\([0-9.]*\).*/\1 \2/')
        if [ -n "$bright" ] && python3 -c "import sys; a, b = map(float, '$bright'.split()); sys.exit(0 if a > b + 0.2 else 1)"; then
            pass "swiping up on the left raises the brightness ($bright)"
        else
            fail "swiping up on the left did not raise the brightness (${bright:-no brightness gesture})"
        fi
        # Muted: a short swipe up on the right (10% of the height) keeps the sound off.
        adb shell media volume --stream 3 --set 0 >/dev/null 2>&1 || true
        adb shell input swipe $((vx1 + vw * 4 / 5)) $((vy1 + vh * 6 / 10)) $((vx1 + vw * 4 / 5)) $((vy1 + vh / 2)) 500; sleep 1
        shot 12h_video_volume_icon
        muted=$(adb shell media volume --stream 3 --get 2>/dev/null | grep -o "volume is [0-9]*" | grep -o "[0-9]*$")
        log "      muted, short swipe up on the right: volume ${muted:-?}"
        if [ "${muted:-x}" = 0 ]; then
            pass "a short swipe does not turn up a muted video"
        else
            fail "a short swipe turned up a muted video (volume ${muted:-?})"
        fi
        adb shell media volume --stream 3 --set 5 >/dev/null 2>&1 || true
        # Rotation button: automatic -> landscape -> portrait.
        # While the video plays the controls hide after 3.5 s, and uiautomator waits longer than
        # that for a still screen. The first tap shows the controls, the second hits the centre
        # play/pause button: paused, the controls stay on screen for the dump.
        adb shell input tap $((vx1 + vw / 2)) $((vy1 + vh / 2)); sleep 1
        adb shell input tap $((vx1 + vw / 2)) $((vy1 + vh / 2)); sleep 1
        if rpos=$(node_center_by_id btnVideoRotate); then
            adb shell input tap $rpos; sleep 2
            rpos=$(node_center_by_id btnVideoRotate) || { adb shell input tap $((vx1 + vw / 2)) $((vy1 + vh / 2)); sleep 1; adb shell input tap $((vx1 + vw / 2)) $((vy1 + vh / 2)); sleep 1; rpos=$(node_center_by_id btnVideoRotate); }
            [ -n "$rpos" ] && adb shell input tap $rpos; sleep 2
            shot 12i_video_portrait
            rot=$(adb logcat -d -s OMVideoPlayer 2>/dev/null | grep "Rotation mode" | sed 's/.*Rotation mode //' | tr '\n' ';')
            log "      rotation button: $rot"
            if printf '%s' "$rot" | grep -q "^1 orientation=6;2 orientation=7"; then
                pass "the rotation button switches the player to landscape, then portrait"
            else
                fail "the rotation button did not switch orientation ($rot)"
            fi
        else
            shot 12i_video_no_rotate_button
            cp "$OUT/ui.xml" "$OUT/ui_video_controls.xml" 2>/dev/null || true
            fail "no rotation button in the player"
        fi
        if grep -q "Gesture speed hold 2.0" "$OUT/video_gestures.log" && grep -q "Gesture speed back 1.0" "$OUT/video_gestures.log"; then
            pass "holding the finger plays at 2x and lifting it goes back to 1x"
        else
            fail "a long press did not play at 2x while held"
        fi
    else
        fail "video gestures: the player did not open"
    fi
    adb logcat -d > "$OUT/logcat_gestures.txt" 2>/dev/null || true
    if app_crashed "$OUT/logcat_gestures.txt"; then fail "crash while using the video gestures"; fi
    adb shell am force-stop $PKG; sleep 1
else
    fail "missing test clip om_gestures_720p.mp4"
fi

log "=== AV1 4K in software: Android's decoder vs our dav1d on every core (user report: 6 fps) ==="
if [ -f /tmp/om_4k_av1.mp4 ]; then
    adb push /tmp/om_4k_av1.mp4 /sdcard/Movies/om_4k_av1.mp4 >/dev/null
    log "      emulator cores: $(adb shell nproc | tr -d '\r')"
    for dec in system dav1d-full dav1d-nohold dav1d; do
        adb shell am force-stop $PKG; sleep 1
        adb logcat -c
        adb shell am start -n $PKG/me.zhanghai.android.files.viewer.video.VideoPlayerActivity \
            -a android.intent.action.VIEW -d "file:///sdcard/Movies/om_4k_av1.mp4" -t video/mp4 \
            --es DEMO_AV1_DECODER $dec >/dev/null
        sleep 16
        shot "12h_av1_4k_$dec"
        adb shell input keyevent KEYCODE_HOME; sleep 2
        adb logcat -d -s OMVideoPlayer > "$OUT/av1_$dec.log" 2>/dev/null || true
        adb logcat -d | grep -iE "dav1d|FATAL EXCEPTION" | head -20 >> "$OUT/av1_$dec.log" || true
        decoder=$(grep -m1 "Video decoder:" "$OUT/av1_$dec.log" | sed 's/.*Video decoder: //')
        summary=$(grep "Summary decoder=" "$OUT/av1_$dec.log" | tail -n1 | sed 's/.*Summary //')
        rendered=$(echo "$summary" | grep -oE "rendered=[0-9]+" | cut -d= -f2)
        position=$(echo "$summary" | grep -oE "positionMs=[0-9]+" | cut -d= -f2)
        sdropped=$(echo "$summary" | grep -oE "dropped=[0-9]+" | cut -d= -f2)
        notdecoded=$(echo "$summary" | grep -oE "notDecoded=[0-9]+" | cut -d= -f2)
        mode=$(echo "$summary" | grep -oE "mode=[0-9]+" | cut -d= -f2)
        # Decoded per second (shown + late), without the frames left out on purpose.
        decoded=$(python3 -c "print(round((${rendered:-0} + ${sdropped:-0}) / max(${position:-0}, 1) * 1000, 1))")
        fps=$(python3 -c "print(round(${rendered:-0} / max(${position:-0}, 1) * 1000, 1))")
        # Frames the decoder got through per second (shown, decoded but late, or not needed).
        handled=$(python3 -c "print(round((${rendered:-0} + ${sdropped:-0} + ${notdecoded:-0}) / max(${position:-0}, 1) * 1000, 1))")
        log "      $dec: decoder=${decoder:-none} ${summary:-no summary} -> ${fps} fps shown, ${handled} fps decoded"
        var=${dec//-/_}
        # Share of the decoded frames thrown away as late: wasted work.
        wasted=$(python3 -c "print(round(${sdropped:-0} / max(${rendered:-0} + ${sdropped:-0}, 1) * 100, 1))")
        eval "wasted_$var=$wasted"
        eval "fps_$var=$fps; handled_$var=$handled; decoded_$var=$decoded; mode_$var=${mode:-0}; skipped_$var=${notdecoded:-0}; dec_$var='${decoder:-}'"
    done
    if printf '%s' "$dec_dav1d" | grep -q "libdav1d"; then
        pass "AV1 without a hardware decoder plays with our dav1d ($dec_dav1d)"
    else
        fail "our dav1d did not decode the AV1 clip (${dec_dav1d:-no decoder})"
    fi
    if python3 -c "import sys; sys.exit(0 if $fps_dav1d >= max($fps_system * 1.3, $fps_system + 3) else 1)"; then
        pass "4K AV1: our dav1d shows more frames than Android's decoder ($fps_dav1d vs $fps_system fps)"
    else
        fail "4K AV1: our dav1d is not clearly faster ($fps_dav1d vs $fps_system fps)"
    fi
    # 4K60 (user report: 6.9 fps on a Galaxy A07): above 1080p60 the shipped decoder skips film
    # grain, CDEF and loop restoration (mode 3).
    if [ "${mode_dav1d:-0}" = 3 ] && [ "${mode_dav1d_full:-0}" = 0 ]; then
        pass "4K60 AV1 is decoded in fast mode (no grain, deblocking only)"
    else
        fail "4K60 AV1 fast mode not chosen (mode=${mode_dav1d:-?}, full run mode=${mode_dav1d_full:-?})"
    fi
    # The emulator's software compositor shows ~10 fps of 4K whatever the decoder does, so what
    # is measured is the decoder: frames decoded per second with every filter and in fast mode.
    log "      4K60 decoded per second: full quality $decoded_dav1d_full, fast mode $decoded_dav1d (handled with skips: $handled_dav1d_full -> $handled_dav1d)"
    # Both are near the clip's 60 fps cap on the emulator; 10% is its run-to-run noise.
    if python3 -c "import sys; sys.exit(0 if $handled_dav1d >= max($handled_dav1d_full * 0.9, 24) else 1)"; then
        pass "4K60 AV1: fast mode keeps up at least as well as full quality ($handled_dav1d vs $handled_dav1d_full fps handled)"
    else
        fail "4K60 AV1: fast mode is slower or under 24 fps ($handled_dav1d vs $handled_dav1d_full fps handled)"
    fi
    # Galaxy A07 with 4K60 (v2.0.30): 35 decoded/s but 26 shown, the rest decoded and then dropped as
    # late. Holding the skipping for 8 s after falling behind wastes fewer decoded frames.
    # Only a figure here: on the emulator the frames are dropped by its slow 4K surface copy, not
    # by the decoder (both runs drop ~80% either way), so the hold is judged on a real phone.
    log "      decoded frames dropped as late: skip only while late $wasted_dav1d_nohold%, with the 8 s hold $wasted_dav1d% (emulator: display-bound, informative only)"
    if [ "${skipped_dav1d:-0}" -gt 0 ]; then
        pass "4K AV1: when late, frames nothing depends on are not decoded (${skipped_dav1d} skipped)"
    else
        fail "4K AV1: late non-reference frames were still decoded"
    fi
    adb logcat -d > "$OUT/logcat_av1.txt" 2>/dev/null || true
    if app_crashed "$OUT/logcat_av1.txt"; then fail "crash while playing 4K AV1"; fi
    adb shell am force-stop $PKG; sleep 1
else
    fail "missing test clip om_4k_av1.mp4"
fi

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

# 4. Light theme: the ⋮ menu icons must show on its white menu (several were white, user report).
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME light >/dev/null 2>&1; sleep 4
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" >/dev/null 2>&1; sleep 4
demo more_menu; sleep 2
shot 44_light_menu_icons
adb exec-out screencap > "$OUT/light_menu.raw" 2>/dev/null
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
icons=$(python3 - "$OUT/light_menu.raw" "$OUT/ui.xml" "Select all" "Bookmarks" <<'PY'
import re, struct, sys, html
raw = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", raw[:8])
header = len(raw) - w * h * 4
xml = open(sys.argv[2], encoding="utf-8", errors="replace").read()
out = []
for label in sys.argv[3:]:
    dark = -1
    for node in re.findall(r"<node [^>]*>", xml):
        t = re.search(r' text="([^"]*)"', node)
        if t and html.unescape(t.group(1)) == label:
            x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            dark = 0
            # The icon sits left of the title, on the same row.
            for y in range(max(0, y1), min(h, y2)):
                for x in range(max(0, x1 - 120), max(0, x1 - 8)):
                    o = header + (y * w + x) * 4
                    r, g, b = raw[o], raw[o + 1], raw[o + 2]
                    if 0.299 * r + 0.587 * g + 0.114 * b < 170:
                        dark += 1
            break
    out.append("%s=%d" % (label.replace(" ", "_"), dark))
print(" ".join(out))
PY
)
log "      dark icon pixels on the light menu: $icons"
if echo "$icons" | grep -qE "Select_all=([3-9][0-9]|[0-9]{3,})" && echo "$icons" | grep -qE "Bookmarks=([3-9][0-9]|[0-9]{3,})"; then
    pass "the ⋮ menu icons are visible on the light theme"
else
    fail "⋮ menu icons are missing on the light theme ($icons)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

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
# The page fills its frame (v2.0.19/20 drew the large render unscaled: half a page) and the
# page subtitle is not cut by the bar.
adb logcat -d -s OMPdf > "$OUT/pdf_shown.txt" 2>/dev/null || true
shown=$(grep -m1 "shown page=0" "$OUT/pdf_shown.txt")
log "      $shown"
frame=$(echo "$shown" | grep -o "frame=[0-9]*" | cut -d= -f2)
drawn=$(echo "$shown" | grep -o "drawn=[0-9-]*" | cut -d= -f2)
if [ -n "$frame" ] && [ -n "$drawn" ] && [ "$frame" -gt 0 ] && [ $((drawn - frame)) -le 2 ] && [ $((frame - drawn)) -le 2 ]; then
    pass "PDF: the whole page width is shown (drawn $drawn px in a $frame px frame)"
else
    fail "PDF: the page is not drawn across its frame (drawn=$drawn frame=$frame)"
fi
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_pdf.xml" 2>/dev/null
sub_ok=$(python3 - "$OUT/ui_pdf.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
bar = None; sub = None
for node in re.findall(r"<node [^>]*>", xml):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b: continue
    if re.search(r'resource-id="[^"]*:id/toolbar"', node): bar = tuple(map(int, b.groups()))
    t = re.search(r' text="([^"]*)"', node)
    if t and re.search(r"(Page|Página) \d+ (of|de) \d+", t.group(1)): sub = tuple(map(int, b.groups()))
print("yes" if bar and sub and sub[3] <= bar[3] and sub[1] >= bar[1] else "no %s %s" % (bar, sub))
PY
)
if [ "$sub_ok" = "yes" ]; then
    pass "PDF: the page subtitle fits inside the bar"
else
    fail "PDF: the page subtitle is cut by the bar ($sub_ok)"
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
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
pdf_sub=$(adb shell cat /data/local/tmp/ui.xml 2>/dev/null | tr -d '\r' | grep -oE '(Page|Página) [0-9]+ (of|de) 6' | head -1)
log "      reopened at: ${pdf_sub:-?}"
if echo "$pdf_sub" | grep -qE ' [2-6] (of|de) 6'; then
    pass "PDF: reopening continues at the last page read ($pdf_sub)"
else
    fail "PDF: reopening did not continue at the last page (${pdf_sub:-no page shown})"
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
    # Added from the bookmarks panel (the ⋮ menu no longer has its own "Add bookmark").
    demo more_menu; sleep 2
    pos=$(find_text "Bookmarks" "Marcadores") && adb shell input tap $pos; sleep 2
    if pos=$(find_text "Bookmark this folder" "Añadir esta carpeta a marcadores"); then
        adb shell input tap $pos; sleep 1
        adb shell input keyevent KEYCODE_BACK; sleep 2
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
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/tvPathTitle"', node):
        t = re.search(r' text="([^"]*)"', node)
        print(html.unescape(t.group(1)) if t else "")
        break
PY
}
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" >/dev/null; sleep 5
if pos=$(node_in_half left om_bm_target); then
    adb shell input tap $pos; sleep 3                       # panel A: into a subfolder
    if pos=$(node_in_half right Alarms); then
        read -r w h <<< "$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')"
        ay=${pos#* }
        adb shell input swipe $((w / 2 + 40)) $ay $((w / 2 + 320)) $ay 300; sleep 2    # panel B: swipe across the icon = select (B active)
        if node_center_by_id btnSelectionClose >/dev/null; then
            pass "swiping across an icon in the right panel selects it"
        else
            shot 45a_no_selection
            fail "swiping across the icon did not select in the right panel"
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

log "=== Ultra RAM: thumbnails can be turned on (light, cached); Material 3 locked ==="
adb shell am force-stop $PKG; sleep 1
thumb_before=$(pref_value key_thumbnail_load_mode)
adb shell am start -W -n $SETTINGS --es DEMO_PERF_MODE on >/dev/null 2>&1; sleep 5
thumb_on=$(pref_value key_thumbnail_load_mode)
if [ "$thumb_on" = "2" ]; then
    pass "Ultra RAM starts with file icons instead of thumbnails"
else
    fail "Ultra RAM did not start with icons only (thumbnail mode=$thumb_on)"
fi
# Material 3 cannot be switched on while Ultra RAM is on.
md3_state=""
for _ in $(seq 1 25); do
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    md3_state=$(adb shell cat /data/local/tmp/ui.xml | tr -d '\r' | python3 -c '
import re, sys, html
xml = sys.stdin.read()
nodes = re.findall(r"<node [^>]*>", xml)
for i, n in enumerate(nodes):
    t = re.search(r" text=\"([^\"]*)\"", n)
    if t and html.unescape(t.group(1)) == "Material Design 3":
        # The row (an ancestor listed before) carries the enabled state of the preference.
        for m in reversed(nodes[max(0, i - 6):i + 1]):
            if " clickable=\"true\"" in m or "android.widget.Switch" in m:
                print("enabled" if " enabled=\"true\"" in m else "disabled"); sys.exit(0)
        print("enabled" if " enabled=\"true\"" in n else "disabled"); sys.exit(0)
')
    [ -n "$md3_state" ] && break
    adb shell input swipe 540 1800 540 700 250; sleep 1
done
shot 46_settings_md3_locked
if [ "$md3_state" = "disabled" ]; then
    pass "Material 3 is locked while Ultra RAM is on"
else
    fail "Material 3 can still be switched on in Ultra RAM (${md3_state:-not found})"
fi
# The user turns thumbnails back on inside Ultra RAM: they load, small and cached on disk.
adb shell am force-stop $PKG; sleep 1
# Quoted for the device's shell too ("<" and ">" would be redirections there).
adb shell "run-as $PKG sed -i 's#<string name=\"key_thumbnail_load_mode\">2</string>#<string name=\"key_thumbnail_load_mode\">0</string>#' /data/data/$PKG/shared_prefs/${PKG}_preferences.xml"
adb shell run-as $PKG rm -rf cache/om_thumbs
log "      thumbnail mode after turning them on inside Ultra RAM: $(pref_value key_thumbnail_load_mode)"
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Movies" >/dev/null; sleep 15
shot 47_ultra_ram_thumbnails
adb logcat -d -s OMThumb > "$OUT/ultra_thumbs.txt" 2>/dev/null || true
grep -E "bind|fetch" "$OUT/ultra_thumbs.txt" | tail -6 | sed 's/^/      /' | tee -a "$REPORT"
thumbs=$(adb shell run-as $PKG ls cache/om_thumbs 2>/dev/null | tr -d '\r' | grep -c "webp$" || true)
log "      thumbnails cached on disk in Ultra RAM: ${thumbs:-0}"
if [ "${thumbs:-0}" -ge 1 ]; then
    pass "Ultra RAM shows thumbnails when turned on, cached on disk ($thumbs)"
else
    fail "Ultra RAM still shows no thumbnails after turning them on"
fi

log "=== Gallery view for one folder: big tiles, no names, resolution label ==="
# set_view <view label> <scope label>: ⋮ > Sort and view > View… (button) > choice > OK.
set_view() {
    demo more_menu; sleep 2
    pos=$(find_contains "Sort and view" "Ordenar y vista") || return 1
    adb shell input tap $pos; sleep 2
    pos=$(find_text "View…" "Vista…") || return 1
    adb shell input tap $pos; sleep 2
    pos=$(find_contains "$1") || return 1
    adb shell input tap $pos; sleep 1
    pos=$(find_contains "$2") || return 1
    adb shell input tap $pos; sleep 1
    pos=$(find_text "OK" "Aceptar") || return 1
    adb shell input tap $pos
}
if set_view "Gallery (large" "Only this folder"; then
    sleep 6
    shot 48_gallery_view
    if node_with resolutionBadge 4K >/dev/null; then
        pass "the gallery view labels the 4K video (4K)"
    else
        fail "no 4K label in the gallery view"
    fi
    if ! find_contains "om_4k" >/dev/null; then
        pass "the gallery view shows the videos without their names"
    else
        fail "the gallery view still shows the video names"
    fi
    # Videos are wide tiles: two columns across and as tall as a photo (frame about 2:1).
    node_center_by_id thumbnailFrame >/dev/null
    tile=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
frames = [(int(x2) - int(x1), int(y2) - int(y1)) for x1, y1, x2, y2 in re.findall(r'resource-id="[^"]*:id/thumbnailFrame"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]
frames = [f for f in frames if f[1] > 0]
print(" ".join(f"{w}x{h}" for w, h in frames[:4]))
sys.exit(0 if any(w >= 1.8 * h for w, h in frames) else 1)
PY
    ) && wide=1 || wide=0
    log "      video tiles in the gallery: ${tile:-none}"
    if [ "$wide" = 1 ]; then
        pass "videos are wide tiles (two columns) in the gallery"
    else
        fail "videos are not wide tiles in the gallery"
    fi
    # Gallery tiles are all picture: a slanted scroll that starts on one must not select it
    # (user report), while a deliberate sideways swipe still does.
    tc=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for x1, y1, x2, y2 in re.findall(r'resource-id="[^"]*:id/thumbnailFrame"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
    if y2 - y1 > 100 and x2 - x1 > 360:   # a video tile (wide, no name bar)
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
    )
    if [ -n "$tc" ]; then
        read -r tx ty <<< "$tc"
        adb shell input swipe $tx $ty $((tx + 100)) $((ty - 90)) 250; sleep 2
        if node_center_by_id btnSelectionClose >/dev/null; then
            shot 48b_gallery_slanted_selected
            fail "a slanted scroll over a gallery tile selected it"
            adb shell input keyevent KEYCODE_BACK; sleep 1
        else
            pass "a slanted scroll over a gallery tile does not select it"
        fi
        node_center_by_id thumbnailFrame >/dev/null
        tc=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for x1, y1, x2, y2 in re.findall(r'resource-id="[^"]*:id/thumbnailFrame"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1, y1, x2, y2 = map(int, (x1, y1, x2, y2))
    if y2 - y1 > 100 and x2 - x1 > 360:
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
        )
        if [ -n "$tc" ]; then
            read -r tx ty <<< "$tc"
            adb shell input swipe $((tx - 150)) $ty $((tx + 150)) $ty 300; sleep 2
            if node_center_by_id btnSelectionClose >/dev/null; then
                pass "a deliberate sideways swipe on a gallery tile selects it"
                adb shell input keyevent KEYCODE_BACK; sleep 1
            else
                shot 48c_gallery_swipe_not_selected
                fail "a sideways swipe on a gallery tile did not select it"
            fi
        fi
    else
        fail "no gallery tile found to swipe on"
    fi
    # Only this folder: Pictures keeps the normal list.
    adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Pictures" >/dev/null; sleep 4
    if find_contains "om_photo" >/dev/null; then
        pass "other folders keep their view (names shown in Pictures)"
    else
        fail "the gallery view leaked into another folder"
    fi
    # The ⋮ menu no longer carries a gallery entry, Refresh, Add bookmark or Exit.
    demo more_menu; sleep 2
    if find_contains "Gallery view (this folder)" "Vista galería (esta carpeta)" >/dev/null; then
        fail "the ⋮ menu still has its own gallery entry"
    else
        pass "the gallery is chosen from Sort and view, not a separate ⋮ entry"
    fi
    if find_text "Refresh" "Add bookmark" "Exit" >/dev/null; then
        fail "the ⋮ menu still shows Refresh, Add bookmark or Exit"
    else
        pass "the ⋮ menu is shorter: no Refresh, Add bookmark or Exit"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
    adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Movies" >/dev/null; sleep 3
    set_view "List" "All folders" || fail "could not switch back to the list view"
    sleep 2
else
    fail "could not choose the gallery from Sort and view"
    adb shell input keyevent KEYCODE_BACK
fi
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_PERF_MODE off >/dev/null 2>&1; sleep 5
thumb_off=$(pref_value key_thumbnail_load_mode)
log "      thumbnail mode before=${thumb_before:-0} · Ultra RAM on=$thumb_on · off=$thumb_off"
if [ "${thumb_off:-0}" = "0" ]; then
    pass "turning Ultra RAM off keeps the thumbnails the user turned on"
else
    fail "turning Ultra RAM off lost the thumbnail choice ($thumb_off)"
fi
adb shell am force-stop $PKG; sleep 1

log "=== File icon: a tap opens the file, a swipe across it selects ==="
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_bm_target" >/dev/null; sleep 5
if pos=$(node_in_half left bm_marker.txt); then
    ry=${pos#* }
    adb shell input tap 68 $ry; sleep 4
    top=$(adb shell dumpsys activity activities | grep -m1 "mResumedActivity" | tr -d '\r')
    if node_center_by_id btnSelectionClose >/dev/null; then
        fail "a tap on the icon still selects the file"
    elif echo "$top" | grep -q "TextEditor"; then
        pass "a tap on the icon opens the file"
    else
        log "      resumed: $top"
        fail "a tap on the icon did not open the file"
    fi
    # Back to the explorer (the first back may only close the editor's keyboard).
    for _ in 1 2 3; do
        adb shell dumpsys activity activities | grep -m1 "mResumedActivity" | grep -q "FileListActivity" && break
        adb shell input keyevent KEYCODE_BACK; sleep 2
    done
    if pos=$(node_in_half left bm_marker.txt); then
        ry=${pos#* }
        adb shell input swipe 40 $ry 320 $ry 300; sleep 2
        shot 49_swipe_select
        if node_center_by_id btnSelectionClose >/dev/null; then
            pass "a swipe across the icon selects the file"
            adb shell input keyevent KEYCODE_BACK; sleep 1
        else
            fail "a swipe across the icon did not select the file"
        fi
    fi
    # Holding a file opens its menu but does not select it (user request): only a swipe does.
    if pos=$(node_in_half left bm_marker.txt); then
        for hold_x in 68 300; do   # on the icon, then on the name
            ry=${pos#* }
            adb shell input swipe $hold_x $ry $hold_x $ry 1000; sleep 2
            menu=$(find_text "Open with…" >/dev/null && echo yes || echo no)
            [ "$menu" = yes ] && { adb shell input keyevent KEYCODE_BACK; sleep 1; }
            sel=$(node_center_by_id btnSelectionClose >/dev/null && echo yes || echo no)
            log "      hold at x=$hold_x: menu $menu, selected after closing it: $sel"
            if [ "$menu" = yes ] && [ "$sel" = no ]; then
                pass "holding the file (x=$hold_x) opens its menu without selecting it"
            else
                shot 49b_hold_selects
                fail "holding the file (x=$hold_x): menu $menu, selected $sel"
                [ "$sel" = yes ] && { adb shell input keyevent KEYCODE_BACK; sleep 1; }
            fi
        done
    fi
else
    fail "no bm_marker.txt in the left panel"
fi

log "=== A large APK opens its dialog at once (signature checked in the background) ==="
python3 - "$APK" "$OUT/om_big.apk" <<'PY'
import os, shutil, sys, zipfile
shutil.copy(sys.argv[1], sys.argv[2])
with zipfile.ZipFile(sys.argv[2], "a", compression=zipfile.ZIP_STORED) as z:
    with z.open("assets/om_big.bin", "w", force_zip64=True) as f:
        block = os.urandom(1 << 20)
        for _ in range(400):
            f.write(block)
PY
ls -la "$OUT/om_big.apk" | awk '{print "      big APK: " $5 " bytes"}' | tee -a "$REPORT"
adb shell mkdir -p /sdcard/om_bigapk
adb push "$OUT/om_big.apk" /sdcard/om_bigapk/om_big.apk >/dev/null
rm -f "$OUT/om_big.apk"
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_bigapk" >/dev/null; sleep 5
if pos=$(node_in_half left om_big.apk); then
    t0=$(date +%s%N)
    adb shell input tap $pos
    if wait_contains 20 "Checking…" "Comprobando…" "Valid" "Válida" "Unsigned" "Sin firmar" >/dev/null; then
        t1=$(date +%s%N); open_ms=$(( (t1 - t0) / 1000000 ))
        shot 50_big_apk_dialog
        sig_ms=0
        for _ in $(seq 1 60); do
            find_contains "Checking…" "Comprobando…" >/dev/null || break
            sleep 1
        done
        t2=$(date +%s%N); sig_ms=$(( (t2 - t0) / 1000000 ))
        log "      400 MB APK: dialog shown after ${open_ms} ms (incl. adb), signature done after ${sig_ms} ms"
        if [ $open_ms -lt 5000 ]; then
            pass "a 400 MB APK shows its dialog at once (${open_ms} ms incl. adb)"
        else
            fail "the 400 MB APK dialog took ${open_ms} ms to appear"
        fi
    else
        fail "the dialog of the 400 MB APK did not appear"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "no om_big.apk in the left panel"
fi
adb shell rm -rf /sdcard/om_bigapk
adb logcat -d > "$OUT/logcat_ultra_gallery.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_ultra_gallery.txt"; then fail "crash in Ultra RAM thumbnails, gallery, icon or big APK checks"; fi

log "=== Running apps without root: real RAM of OpenManager and its scripts ==="
open_running_apps() {
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n "$MAIN" >/dev/null; sleep 4
    demo drawer; sleep 2
    for _ in 1 2 3 4; do
        pos=$(find_text "Running Apps" "Apps en ejecución") && { adb shell input tap $pos; return 0; }
        adb shell input swipe 300 1800 300 900 300; sleep 1
    done
    return 1
}
adb logcat -c
if open_running_apps; then
    sleep 8
    shot 51_running_apps_noroot
    if pos=$(find_contains "real RAM" "RAM real"); then
        pass "without root the header shows OpenManager's real RAM (app and scripts)"
    else
        fail "no real-RAM summary without root"
    fi
    if find_contains "User (" "Usuario (" >/dev/null; then
        fail "the tabs are still shown without root"
    else
        pass "without root the tabs are hidden (only own processes are visible)"
    fi
    adb logcat -d -s OMRunning > "$OUT/running_self.txt" 2>/dev/null || true
    line=$(grep -m1 "self pid=" "$OUT/running_self.txt")
    log "      $line"
    pss=$(echo "$line" | grep -o "pss=[0-9]*" | cut -d= -f2)
    rss=$(echo "$line" | grep -o "rss=[0-9]*" | cut -d= -f2)
    if [ -n "$pss" ] && [ -n "$rss" ] && [ "$pss" -gt 0 ] && [ "$pss" -lt "$rss" ]; then
        pass "OpenManager's memory is its real PSS ($((pss / 1048576)) MB) instead of RSS ($((rss / 1048576)) MB)"
    else
        fail "the memory shown is not the real PSS (pss=$pss rss=$rss)"
    fi
    # Without root the heat card does not exist.
    if node_center_by_id layoutHeat >/dev/null; then
        fail "the heat card is shown without root"
    else
        pass "without root the heat card is not shown"
    fi
else
    fail "could not open Running Apps from the side menu"
fi

log "=== Running apps with root: what heats the phone (recorded root reading, debug build) ==="
adb shell run-as $PKG touch files/om_debug_demo_heat
if open_running_apps; then
    sleep 8
    shot 52_running_apps_heat
    if find_contains "CPU 61" >/dev/null && find_contains "Demo game" >/dev/null; then
        pass "the heat card shows the CPU temperature and the app heating the phone"
    else
        fail "the heat card did not show the reading"
    fi
else
    fail "could not open Running Apps for the heat card"
fi
adb shell run-as $PKG rm -f files/om_debug_demo_heat
adb logcat -d > "$OUT/logcat_running.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_running.txt"; then fail "crash in Running Apps"; fi
adb shell am force-stop $PKG; sleep 1

log "=== Browsing photos does not flood the media scanner (Android 16 crash) ==="
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Pictures" >/dev/null; sleep 6
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Movies" >/dev/null; sleep 6
queued=$(adb logcat -d -s OMMediaScan 2>/dev/null | grep -c "queued" || true)
log "      media scans queued while browsing: ${queued:-0}"
if [ "${queued:-0}" -eq 0 ]; then
    pass "reading files (thumbnails) asks the media scanner nothing"
else
    fail "browsing queued ${queued} media scans"
fi
adb logcat -d > "$OUT/logcat_mediascan.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_mediascan.txt"; then fail "crash while browsing photos and videos"; fi

log "=== Back stops at the internal storage without root ==="
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Download" >/dev/null; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 2
title=$(node_text_by_id tvPathTitle || true)
shot 64_back_stops_at_storage
log "      after two BACK presses from Download: $title"
case "$title" in
    */storage/emulated/0|*/storage/emulated/0/) pass "BACK climbs to the internal storage and stops there (no root)" ;;
    *) fail "BACK went to '$title' instead of stopping at the internal storage" ;;
esac

log "=== Sort and view: one MT-style dialog, applied with OK (user request) ==="
demo more_menu; sleep 2
pos=$(find_contains "Sort and view" "Ordenar y vista") && adb shell input tap $pos; sleep 2
shot 65_sort_dialog
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
state=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
out = []
for n in re.findall(r"<node [^>]*>", xml):
    t = re.search(r' text="([^"]*)"', n)
    if t and re.search(r'class="android.widget.(RadioButton|CheckBox)"', n):
        out.append(html.unescape(t.group(1)) + ("=on" if 'checked="true"' in n else "=off"))
print(" | ".join(out))
PY
)
log "      dialog: ${state:-nothing}"
if printf '%s' "$state" | grep -q "Name=on" && printf '%s' "$state" | grep -q "Reverse order" && printf '%s' "$state" | grep -q "Folders first"; then
    pass "Sort and view shows every choice at once, the active one (Name) checked"
else
    fail "Sort and view is not one dialog with the active choice ($state)"
fi
# Two changes in one trip: date + reverse order, then OK.
pos=$(find_text "Date modified" "Fecha de modificación") && adb shell input tap $pos; sleep 1
pos=$(find_contains "Reverse order" "Invertir orden") && adb shell input tap $pos; sleep 1
pos=$(find_text "OK" "Aceptar") && adb shell input tap $pos; sleep 2
demo more_menu; sleep 2
pos=$(find_contains "Sort and view" "Ordenar y vista") && adb shell input tap $pos; sleep 2
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
after=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
on = [html.unescape(re.search(r' text="([^"]*)"', n).group(1)) for n in re.findall(r"<node [^>]*>", xml)
      if re.search(r'class="android.widget.(RadioButton|CheckBox)"', n) and 'checked="true"' in n and ' text="' in n]
print(" | ".join(on))
PY
)
log "      after OK: ${after:-nothing}"
if printf '%s' "$after" | grep -q "Date modified" && printf '%s' "$after" | grep -q "Reverse order"; then
    pass "date and reverse order chosen together, applied with one OK"
else
    fail "the two changes were not both applied ($after)"
fi
# Back to name, ascending, for the tests after this one.
pos=$(find_text "Name" "Nombre") && adb shell input tap $pos; sleep 1
pos=$(find_contains "Reverse order" "Invertir orden") && adb shell input tap $pos; sleep 1
pos=$(find_text "OK" "Aceptar") && adb shell input tap $pos; sleep 2

log "=== Fast scroller: only its thumb drags, a swipe along the edge scrolls normally (user report) ==="
adb shell 'mkdir -p /sdcard/om_scroll_test && cd /sdcard/om_scroll_test && i=0; while [ $i -lt 300 ]; do touch $(printf "f_%03d.txt" $i); i=$((i+1)); done'
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_scroll_test" >/dev/null; sleep 5
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
# The list that shows f_000.txt: its right edge is where the scroller's strip lies.
strip=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
nodes = re.findall(r"<node [^>]*>", xml)
box = lambda n: tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
files = [box(n) for n in nodes if re.search(r' text="f_000\.txt"', n)]
lists = [box(n) for n in nodes if re.search(r'resource-id="[^"]*:id/recyclerView"', n)]
for x1, y1, x2, y2 in lists:
    if files and x1 <= files[0][0] < x2:
        print(x2 - 12, y1 + (y2 - y1) * 3 // 4, y1 + (y2 - y1) * 4 // 5)
        break
PY
)
if [ -n "$strip" ]; then
    read -r sx sy1 sy2 <<< "$strip"
    # A slow drag down along the strip, far from the (hidden) thumb at the top.
    adb shell input swipe $sx $sy1 $sx $sy2 900; sleep 2
    shot 70_fast_scroll_strip
    if find_text "f_000.txt" >/dev/null; then
        pass "a swipe along the panel's edge no longer jumps the list (only the thumb drags)"
    else
        fail "a swipe along the edge jumped the list like a fast-scroll drag"
    fi
else
    fail "fast scroller: the test folder did not open"
fi

log "=== Filter in an empty folder ends, and Back takes the filter off (user report) ==="
adb shell 'mkdir -p /sdcard/om_empty_filter'
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_empty_filter" >/dev/null; sleep 4
demo more_menu; sleep 2
if pos=$(find_text "Filter" "Filtrar"); then
    adb shell input tap $pos; sleep 2
    adb shell input text "abc"; sleep 1
    pos=$(find_text "Search" "Buscar" "SEARCH" "BUSCAR") && adb shell input tap $pos
    sleep 5
    shot 71_filter_empty_done
    if node_center_by_id directoryProgressBar >/dev/null; then
        fail "the filter of an empty folder keeps its progress bar running"
    else
        pass "the filter of an empty folder ends (no endless progress bar)"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
    title=$(node_text_by_id tvPathTitle || true)
    log "      after BACK: $title"
    case "$title" in
        *om_empty_filter*) pass "BACK takes the filter off and stays in the folder" ;;
        *) fail "BACK with a filter left the folder ($title)" ;;
    esac
else
    fail "no Filter in the ⋮ menu"
    adb shell input keyevent KEYCODE_BACK
fi

log "=== The ⋮ menu is compact (owner request) ==="
demo more_menu; sleep 2
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
shot 72_compact_menu
density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
pitch=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
ys = sorted(int(m.group(2)) + (int(m.group(4)) - int(m.group(2))) // 2 for n in re.findall(r"<node [^>]*>", xml)
            if re.search(r'resource-id="[^"]*:id/title"', n)
            for m in [re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)])
d = [b - a for a, b in zip(ys, ys[1:])]
print(sorted(d)[len(d) // 2] if d else 0)
PY
)
limit=$(( 44 * ${density:-420} / 160 ))
log "      ⋮ row height: ${pitch} px (limit ${limit} px at ${density} dpi)"
if [ "${pitch:-0}" -gt 0 ] && [ "$pitch" -le "$limit" ]; then
    pass "the ⋮ menu rows are compact"
else
    fail "the ⋮ menu rows are still tall (${pitch} px)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

log "=== Side bar: Exit closes OpenManager (user request) ==="
demo drawer; sleep 2
# The logo sits on the icon column instead of the screen edge, and the entry reads "About".
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_drawer.xml" 2>/dev/null
density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
header=$(python3 - "$OUT/ui_drawer.xml" "${density:-420}" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
d = int(sys.argv[2]) / 160
logo = None
for n in re.findall(r"<node [^>]*>", xml):
    if re.search(r'resource-id="[^"]*:id/drawerHeaderLogo"', n):
        logo = tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
texts = [html.unescape(t) for t in re.findall(r' text="([^"]*)"', xml)]
center = (logo[0] + logo[2]) / 2 / d if logo else -1
print("ok" if logo and 30 <= center <= 38 else "bad", round(center, 1))
PY2
)
log "      side bar: logo center at $(echo $header | cut -d' ' -f2) dp (icons at 34 dp)"
shot 41b_drawer_header
if [ "${header%% *}" = ok ]; then
    pass "the side bar logo lines up with the icons instead of the screen edge"
else
    fail "side bar header: logo not aligned ($header)"
fi
# Theme switch in the side bar header (MT style): one tap from light to dark or back.
lum_of() { python3 -c "r,g,b=map(int,'$1'.split()); print(int(0.299*r+0.587*g+0.114*b))" 2>/dev/null || echo -1; }
adb shell input keyevent KEYCODE_BACK; sleep 1
adb exec-out screencap > "$OUT/theme_before.raw" 2>/dev/null
before=$(dominant_color "$OUT/theme_before.raw" mainToolbar || true)
demo drawer; sleep 2
if pos=$(node_center_by_id btnDrawerNightMode); then
    adb shell input tap $pos; sleep 5
    adb exec-out screencap > "$OUT/theme_after.raw" 2>/dev/null
    after=$(dominant_color "$OUT/theme_after.raw" mainToolbar || true)
    shot 41c_theme_switched
    lb=$(lum_of "$before"); la=$(lum_of "$after")
    log "      theme switch: toolbar ${before:-?} (lum $lb) -> ${after:-?} (lum $la)"
    # Dark to light or light to dark (bash && and || have the same precedence: decided in python).
    if python3 -c "import sys; b, a = $lb, $la; sys.exit(0 if b >= 0 and a >= 0 and ((b < 100 and a > 150) or (b > 150 and a < 100)) else 1)"; then
        pass "the side bar's theme button switches between light and dark at once"
    else
        fail "the theme button did not switch the theme (${before:-?} -> ${after:-?})"
    fi
    # And back, for the rest of the run.
    demo drawer; sleep 2
    pos=$(node_center_by_id btnDrawerNightMode) && adb shell input tap $pos; sleep 5
else
    fail "no theme button in the side bar header"
fi
demo drawer; sleep 2
# User request: Exit and About pinned at the bottom, on screen without scrolling.
if find_text "Exit" "Salir" >/dev/null && find_text "About" "Acerca de" >/dev/null; then
    pass "the side bar shows About and Exit without scrolling"
else
    shot 41c_drawer_needs_scroll
    fail "the side bar still needs scrolling to reach Exit"
fi
shot 41d_drawer_rows
pos=""
for _ in 1 2 3 4; do
    pos=$(find_text "Exit" "Salir") && break
    adb shell input swipe 300 1800 300 600 300; sleep 1
done
if [ -n "$pos" ]; then
    # Exit is on screen, so the entry above it is too.
    if grep -q 'text="About"' "$OUT/ui.xml" && ! grep -q 'text="About OpenManager"' "$OUT/ui.xml"; then
        pass "the side bar entry reads 'About'"
    else
        fail "the side bar entry still reads 'About OpenManager' (or is missing)"
    fi
    adb shell input tap $pos; sleep 3
    left=$(adb shell dumpsys activity activities 2>/dev/null | grep -c "$PKG/me.zhanghai.android.files.filelist.FileListActivity" || true)
    recents=$(adb shell dumpsys activity recents 2>/dev/null | grep -c "A=.*$PKG\|cmp=$PKG" || true)
    log "      after Exit: FileListActivity records=$left, recent tasks=$recents"
    if [ "${left:-1}" -eq 0 ]; then
        pass "Exit in the side bar closes OpenManager"
    else
        fail "Exit left OpenManager's screen open"
    fi
else
    fail "no Exit in the side bar"
    adb shell input keyevent KEYCODE_BACK
fi

log "=== PDF: pages keep their shape after turning to landscape (user report) ==="
rot_auto=$(adb shell settings get system accelerometer_rotation | tr -d '\r')
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am force-stop $PKG; sleep 1
open_pages_pdf; sleep 6
adb logcat -c
adb shell settings put system user_rotation 1; sleep 6
shot 73_pdf_landscape
shown=$(adb logcat -d -s OMPdf 2>/dev/null | grep "shown page=" | tail -1)
log "      $shown"
ratio_ok=$(printf '%s' "$shown" | python3 -c '
import re, sys
m = re.search(r"frame=(\d+)x(\d+)", sys.stdin.read())
if not m: print("none"); sys.exit()
w, h = int(m.group(1)), int(m.group(2))
page = 842 / 595
print("yes" if w > 0 and abs(h / w - page) / page < 0.04 else "no %dx%d (h/w %.2f, page %.2f)" % (w, h, h / w, page))
')
if [ "$ratio_ok" = "yes" ]; then
    pass "PDF: in landscape the page keeps its proportions"
else
    fail "PDF: in landscape the page is stretched ($ratio_ok)"
fi
adb shell settings put system user_rotation 0
adb shell settings put system accelerometer_rotation "${rot_auto:-0}"
sleep 2
adb logcat -d > "$OUT/logcat_round28.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_round28.txt"; then fail "crash in the scroller, filter, menu, exit or PDF checks"; fi
adb shell am force-stop $PKG; sleep 1

log "=== Text editor from the side bar: new document, Save as ==="
adb shell rm -rf /sdcard/om_editor_test; adb shell mkdir -p /sdcard/om_editor_test
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test" >/dev/null; sleep 4
demo drawer; sleep 2
pos=$(node_center_by_id drawerItemTextEditor) || {
    # Lower in the drawer: scroll it.
    adb shell input swipe 300 1600 300 500 300; sleep 1
    pos=$(node_center_by_id drawerItemTextEditor)
}
editor_ok=0
if [ -n "$pos" ]; then
    adb shell input tap $pos; sleep 4
    top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[A-Za-z0-9_.]*")
    log "      side bar editor opened: ${top:-nothing}"
    case "$top" in
        *TextEditorActivity) pass "the side bar opens the text editor (it used to close at once)"; editor_ok=1 ;;
        *) fail "the side bar did not open the text editor ($top)" ;;
    esac
else
    fail "no Text editor entry in the side bar"
fi
if [ "$editor_ok" = 1 ]; then
    shot 60_editor_new_document
    pos=$(node_center_by_id codeEditor) && adb shell input tap $pos; sleep 1
    adb shell input text "hello%sfrom%sOpenManager"; sleep 1
    adb shell input keyevent KEYCODE_ENTER
    adb shell input text "second%sline"; sleep 1
    # Hide the on-screen keyboard if it is up (a BACK without it would ask to discard the text).
    if adb shell dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"; then
        adb shell input keyevent KEYCODE_BACK; sleep 1
    fi
    pos=$(node_text_by_id statusCursorPosText || true)
    log "      cursor after typing two lines: $pos"
    case "$pos" in
        2:12) pass "typing a new line updates the line and column (2:12)" ;;
        *) fail "after typing the cursor shows '$pos' instead of 2:12" ;;
    esac
    pos=$(find_text "Save" "Guardar") && adb shell input tap $pos; sleep 2
    shot 61_editor_save_as
    node_text_by_id statusCursorPosText >/dev/null 2>&1 || true   # fresh dump of the dialog
    folder=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
texts = [html.unescape(t) for t in re.findall(r'class="android.widget.EditText"[^>]* text="([^"]*)"', xml)]
texts += [html.unescape(t) for t in re.findall(r' text="([^"]*)"[^>]*class="android.widget.EditText"', xml)]
print(next((t for t in texts if t.startswith("/")), ""))
PY
)
    log "      Save as suggests the folder: ${folder:-?}"
    case "$folder" in
        */om_editor_test) pass "Save as suggests the folder open in the panel" ;;
        *) fail "Save as suggests '$folder' instead of the panel folder" ;;
    esac
    # The name is selected without its extension: typing replaces it.
    adb shell input text "om_note"; sleep 1
    pos=$(find_text "SAVE" "GUARDAR" "Save" "Guardar") && adb shell input tap $pos; sleep 4
    saved=$(adb shell cat /sdcard/om_editor_test/om_note.txt 2>/dev/null | tr -d '\r')
    log "      saved file: $(printf '%s' "$saved" | tr '\n' '|')"
    if [ "$saved" = "$(printf 'hello from OpenManager\nsecond line')" ]; then
        pass "a new document is saved with its text where the user chose"
    else
        fail "the new document was not saved as om_note.txt"
    fi
    title=$(node_text_by_id statusFileNameText || true)
    log "      editor now shows: $title"
    case "$title" in
        om_note.txt*) pass "after Save as the editor continues in the saved file" ;;
        *) fail "after Save as the editor shows '$title'" ;;
    esac
    drafts=$(adb shell run-as $PKG ls files/editor_drafts 2>/dev/null | tr -d '\r' | grep -c "untitled-" || true)
    if [ "${drafts:-0}" -eq 0 ]; then pass "the scratch file of the new document is removed"; else fail "$drafts scratch files left behind"; fi
fi

log "=== Text editor: word wrap folds a one-line JSON (user report) ==="
python3 - "$OUT/om_oneline.json" <<'PY'
import json, sys
theme = {"id": "custom_1794844469837"}
for i in range(120):
    theme["key%03dBackground" % i] = None if i % 3 else 4278190080 + i
json.dump(theme, open(sys.argv[1], "w"), separators=(",", ":"))
PY
log "      one-line JSON: $(wc -c < "$OUT/om_oneline.json") bytes"
adb push "$OUT/om_oneline.json" /sdcard/om_editor_test/om_oneline.json >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_oneline.json" >/dev/null
sleep 6
shot 63_editor_wrap_json
layout=$(adb logcat -d -s OMEditor 2>/dev/null | grep "layout lines=" | tail -1)
log "      ${layout#*OMEditor: }"
rows=$(printf '%s' "$layout" | sed -n 's/.*rows=\([0-9]*\).*/\1/p')
if [ "${rows:-0}" -ge 10 ]; then
    pass "word wrap folds a 4 KB one-line JSON into ${rows} rows"
else
    fail "word wrap did not fold the one-line JSON (rows=${rows:-?})"
fi
adb shell am force-stop $PKG; sleep 1

# raw_stats <raw screencap> <x1> <y1> <x2> <y2>: "saturated dark light" pixel counts in a box.
raw_stats() {
    python3 - "$@" <<'PY'
import struct, sys
raw = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", raw[:8])
header = len(raw) - w * h * 4
x1, y1, x2, y2 = (int(v) for v in sys.argv[2:6])
sat = dark = light = 0
for y in range(max(0, y1), min(h, y2), 2):
    for x in range(max(0, x1), min(w, x2), 2):
        o = header + (y * w + x) * 4
        r, g, b = raw[o], raw[o + 1], raw[o + 2]
        mx, mn = max(r, g, b), min(r, g, b)
        if mx > 60 and (mx - mn) > 70: sat += 1
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        if lum < 90: dark += 1
        if lum > 200: light += 1
print(sat, dark, light)
PY
}

log "=== Text editor: changing the syntax recolors the text ==="
cat > "$OUT/om_code.txt" <<'JAVA'
public class Hello {
    // A comment that turns gray
    private static final String NAME = "OpenManager";
    public static void main(String[] args) {
        int count = 42;
        for (int i = 0; i < count; i++) {
            System.out.println(NAME + " " + i);
        }
        return;
    }
}
JAVA
adb push "$OUT/om_code.txt" /sdcard/om_editor_test/om_code.txt >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_code.txt" \
    --es DEMO_EDITOR_THEME one_dark >/dev/null
sleep 4
adb exec-out screencap > "$OUT/syntax_before.raw" 2>/dev/null
read -r ex1 ey1 <<< "$(node_center_by_id codeEditor)"
if pos=$(node_center_by_id statusSyntaxText); then
    adb shell input tap $pos; sleep 2
    pos=$(find_text "Java") && adb shell input tap $pos
    sleep 4
    adb exec-out screencap > "$OUT/syntax_after.raw" 2>/dev/null
    shot 66_editor_syntax_java
    before=$(raw_stats "$OUT/syntax_before.raw" 0 300 1080 1300)
    after=$(raw_stats "$OUT/syntax_after.raw" 0 300 1080 1300)
    analyzed=$(adb logcat -d -s OMEditor 2>/dev/null | grep "analyzed lang=JAVA" | tail -1 | sed 's/.*OMEditor: //')
    log "      colored pixels plain text: ${before%% *} -> Java: ${after%% *} (${analyzed:-no JAVA analysis})"
    if [ "${after%% *}" -gt $(( ${before%% *} + 300 )) ]; then
        pass "switching the syntax from Plain text to Java colors the code"
    else
        fail "switching the syntax to Java did not color the code"
    fi
else
    fail "no syntax button in the editor's status bar"
fi

log "=== Text editor: the symbol bar looks like the explorer's bar and is readable (user reports) ==="
for apptheme in dark light; do
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME $apptheme >/dev/null 2>&1; sleep 4
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_code.txt" \
        --es DEMO_EDITOR_THEME light >/dev/null
    sleep 4
    shot 67_editor_symbols_app_$apptheme
    adb exec-out screencap > "$OUT/symbols_$apptheme.raw" 2>/dev/null
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    key=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    d = re.search(r' content-desc="([^"]*)"', n)
    if d and html.unescape(d.group(1)) in ("Tab", "Tabulación"):
        print(" ".join(re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
        break
PY
)
    if [ -n "$key" ]; then
        read -r kx1 ky1 kx2 ky2 <<< "$key"
        read -r k_sat k_dark k_light <<< "$(raw_stats "$OUT/symbols_$apptheme.raw" $kx1 $ky1 $kx2 $ky2)"
        log "      app $apptheme: Tab key dark pixels=$k_dark light pixels=$k_light"
        # Readable: the icon contrasts with the bar (both dark and light pixels in the key).
        if [ "$k_dark" -gt 5 ] && [ "$k_light" -gt 5 ]; then
            pass "app $apptheme theme: the symbol keys are readable"
        else
            fail "app $apptheme theme: the symbol keys are not readable (dark=$k_dark light=$k_light)"
        fi
    else
        fail "app $apptheme theme: no Tab key in the symbol bar"
    fi
    # The read-only switch in the toolbar takes the toolbar's icon color (it was white on white).
    lock=$(python3 - "$OUT/ui.xml" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    d = re.search(r' content-desc="([^"]*)"', n)
    if d and html.unescape(d.group(1)) in ("Read-only", "Solo lectura"):
        print(" ".join(re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
        break
PY2
)
    if [ -n "$lock" ]; then
        read -r lx1 ly1 lx2 ly2 <<< "$lock"
        read -r l_sat l_dark l_light <<< "$(raw_stats "$OUT/symbols_$apptheme.raw" $lx1 $ly1 $lx2 $ly2)"
        log "      app $apptheme: read-only switch dark pixels=$l_dark light pixels=$l_light"
        if [ "$apptheme" = light ]; then l_contrast=$l_dark; else l_contrast=$l_light; fi
        if [ "${l_contrast:-0}" -gt 20 ]; then
            pass "app $apptheme theme: the read-only switch in the toolbar is visible"
        else
            fail "app $apptheme theme: the read-only switch is invisible (dark=$l_dark light=$l_light)"
        fi
    else
        fail "app $apptheme theme: no read-only switch in the editor toolbar"
    fi
done
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME dark >/dev/null 2>&1; sleep 3
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_code.txt" >/dev/null
sleep 4
# The ⋮ menu of the editor shows an icon on every entry.
if pos=$(find_text "More options" "Más opciones"); then
    adb shell input tap $pos; sleep 2
    shot 68_editor_menu_icons
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui.xml" 2>/dev/null
    icons=$(grep -o 'resource-id="[^"]*:id/icon"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/ui.xml" | grep -vc 'bounds="\[0,0\]\[0,0\]"' || true)
    rows=$(grep -o 'resource-id="[^"]*:id/title"' "$OUT/ui.xml" | wc -l | tr -d ' ')
    log "      editor ⋮ menu: $icons icons for $rows entries"
    if [ "${icons:-0}" -ge 8 ] && [ "${icons:-0}" -ge $(( ${rows:-0} - 1 )) ]; then
        pass "the editor's ⋮ menu shows an icon on each entry"
    else
        fail "the editor's ⋮ menu has entries without icons ($icons of $rows)"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "no ⋮ button in the editor"
fi
# No empty bands: the toolbar starts right under the status bar and the symbol keys sit right on
# the navigation bar (both insets used to be added twice; user report).
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_editor_insets.xml" 2>/dev/null
density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
gaps=$(python3 - "$OUT/ui_editor_insets.xml" "${density:-420}" <<'PY2'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
d = int(sys.argv[2]) / 160
def box(i):
    for n in re.findall(r"<node [^>]*>", xml):
        if re.search(r'resource-id="[^"]*:id/%s"' % i, n):
            return tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
height = max(int(b) for b in re.findall(r'bounds="\[\d+,\d+\]\[\d+,(\d+)\]"', xml))
tb = box("toolbar"); keys = box("symbolContainer")
top = tb[1] if tb else -1
bottom = height - keys[3] if keys else -1
# The toolbar right under the status bar (up to 50 dp with big cutouts); the keys sitting on the
# navigation bar (the dump ends where it begins), not a navigation bar's height above it.
ok = 0 <= top <= 50 * d and 0 <= bottom <= 16 * d
print("ok" if ok else "bad", top, bottom, round(24 * d), round(48 * d))
PY2
)
log "      editor insets: toolbar top=$(echo $gaps | cut -d' ' -f2) px, keys above the bottom=$(echo $gaps | cut -d' ' -f3) px (status ~$(echo $gaps | cut -d' ' -f4), nav ~$(echo $gaps | cut -d' ' -f5))"
if [ "${gaps%% *}" = ok ]; then
    pass "the editor has no empty band above the toolbar or under the symbol bar"
else
    shot 68b_editor_bands
    fail "the editor still leaves an empty band ($gaps)"
fi
# Back to the default editor theme for the rest of the run.
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_code.txt" \
    --es DEMO_EDITOR_THEME one_dark >/dev/null
sleep 3
adb shell am force-stop $PKG; sleep 1

log "=== Text editor: a binary .dat shows its bytes, not an empty page (user report) ==="
python3 -c "import os,sys; open(sys.argv[1],'wb').write(bytes([0xAF,0x1B,0xB1,0xFA]) + os.urandom(2*1024*1024))" "$OUT/global-metadata.dat"
adb push "$OUT/global-metadata.dat" /sdcard/om_editor_test/global-metadata.dat >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/global-metadata.dat" >/dev/null
sleep 8
shot 69_editor_binary_dat
layout=$(adb logcat -d -s OMEditor 2>/dev/null | grep "layout lines=" | tail -1 | sed 's/.*OMEditor: //')
name=$(node_text_by_id statusFileNameText || true)
log "      $name · ${layout:-no layout}"
lines=$(printf '%s' "$layout" | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
if [ "${lines:-0}" -gt 100 ] && printf '%s' "$name" | grep -q "MB"; then
    pass "a 2 MB binary .dat opens with its content (${lines} lines)"
else
    fail "the binary .dat opened empty ($name, ${layout:-no layout})"
fi
adb logcat -d > "$OUT/logcat_binary.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_binary.txt"; then fail "crash opening a binary .dat"; fi
adb shell am force-stop $PKG; sleep 1

log "=== Text editor: big file, line numbers and typing stay right ==="
python3 - "$OUT/om_big.txt" <<'PY'
import sys
with open(sys.argv[1], "w") as f:
    for i in range(1, 150001):
        f.write("line %06d: the quick brown fox jumps over the lazy dog {}\n" % i)
PY
adb push "$OUT/om_big.txt" /sdcard/om_editor_test/om_big.txt >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
t0=$(date +%s%N)
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_editor_test/om_big.txt" \
    --ei EXTRA_GOTO_LINE 149990 >/dev/null
# Wait until the cursor is on the line (debug OMEditor log, no screen dumps), at most 30 s.
pos=""
for i in $(seq 1 60); do
    pos=$(adb logcat -d -s OMEditor 2>/dev/null | grep -o "cursor=[0-9:]*" | tail -1 | sed 's/cursor=//')
    case "$pos" in 149990:*) break ;; esac
    sleep 0.5
done
t1=$(date +%s%N)
log "      9 MB / 150 000 lines ready at line 149990 in $(( (t1 - t0) / 1000000 )) ms (adb and dumps included)"
shot 62_editor_big_file
log "      cursor after jumping near the end: $pos"
case "$pos" in
    149990:*) pass "line numbers are right deep into a 150 000-line file" ;;
    *) fail "the cursor shows '$pos' instead of line 149990" ;;
esac
adb logcat -d 2>/dev/null | grep -E "Choreographer.*Skipped|Davey" | grep -v "^--" | tail -4 | sed 's/^/      /' | tee -a "$REPORT"
sleep 3
# The jump must also be on screen (word wrap lays out long texts in the background).
visible=$(node_text_by_id codeEditor || true)
if printf '%s' "$visible" | grep -q "line 149990"; then
    pass "after the jump, line 149990 is on screen"
else
    fail "line 149990 is not on screen after the jump ($(printf '%s' "$visible" | head -c 60))"
fi
# New lines from the symbol bar (no keyboard needed): the line number follows.
for n in 1 2; do pos=$(find_text "New line" "Nueva línea") && adb shell input tap $pos; sleep 1; done
pos=$(adb logcat -d -s OMEditor 2>/dev/null | grep -o "cursor=[0-9:]*" | tail -1 | sed 's/cursor=//')
log "      cursor after two new lines: $pos"
case "$pos" in
    149992:1) pass "new lines deep in a big file update the line number" ;;
    *) fail "after two new lines the cursor shows '$pos' instead of 149992:1" ;;
esac
# Scroll fast through the file: it must stay responsive.
pos=$(node_center_by_id codeEditor)
for n in 1 2 3 4 5 6; do adb shell input swipe 500 600 500 1700 80; done
sleep 2
if node_text_by_id statusCursorPosText >/dev/null; then
    pass "the editor answers after fast scrolling through a 9 MB file"
else
    fail "the editor did not answer after fast scrolling"
fi
adb logcat -d > "$OUT/logcat_editor.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_editor.txt"; then fail "crash in the text editor"; else pass "no crash in the text editor"; fi
if grep -q "ANR in $PKG" "$OUT/logcat_editor.txt"; then fail "the text editor stopped responding (ANR)"; fi
adb shell am force-stop $PKG; sleep 1

log "=== File names: 2 lines by default, size never cut, marquee on 1 line (user report) ==="
long_name="youmo-0.6.3-voice-assistant-release-build-arm64-v8a-signed-final.apk"
adb shell rm -rf /sdcard/om_names; adb shell mkdir -p /sdcard/om_names
adb shell "dd if=/dev/zero of=/sdcard/om_names/$long_name bs=1024 count=495" >/dev/null 2>&1
adb shell "echo x > /sdcard/om_names/short.txt"
# name_box <xml> : height of the long name, the size text and its right edge, the panel's right edge
name_box() {
    python3 - "$1" "$long_name" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
nodes = re.findall(r"<node [^>]*>", xml)
def b(n): return tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
name = [b(n) for n in nodes if ':id/nameText"' in n and html.unescape(re.search(r' text="([^"]*)"', n).group(1)).startswith("youmo-0.6.3")]
size = [(b(n), html.unescape(re.search(r' text="([^"]*)"', n).group(1))) for n in nodes if ':id/sizeText"' in n]
size = [x for x in size if name and abs(x[0][1] - name[0][3]) < 120]
if not name:
    print("none"); sys.exit()
n = name[0]
sb, st = size[0] if size else ((0, 0, 0, 0), "")
print(n[3] - n[1], n[0], n[2], st.replace(" ", "_") or "-", sb[2])
PY2
}
set_name_prefs() {  # <max lines> <ellipsize value>
    adb shell am force-stop $PKG; sleep 1
    adb shell run-as $PKG cat /data/data/$PKG/shared_prefs/${PKG}_preferences.xml > "$OUT/prefs_names.xml" 2>/dev/null
    python3 - "$OUT/prefs_names.xml" "$1" "$2" <<'PY2'
import re, sys
p, lines, ell = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if "<map" not in s:
    s = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
for key, val in (("key_max_lines_file_name", lines), ("key_file_name_ellipsize", ell)):
    s = re.sub(r'\s*<string name="%s">[^<]*</string>' % key, "", s)
    s = s.replace("</map>", '    <string name="%s">%s</string>\n</map>' % (key, val))
open(p, "w", encoding="utf-8").write(s)
PY2
    owner=$(adb shell su 0 stat -c %u:%g /data/data/$PKG/shared_prefs/${PKG}_preferences.xml 2>/dev/null | tr -d '\r')
    adb push "$OUT/prefs_names.xml" /data/local/tmp/om_prefs.xml >/dev/null
    adb shell su 0 cp /data/local/tmp/om_prefs.xml /data/data/$PKG/shared_prefs/${PKG}_preferences.xml
    [ -n "$owner" ] && adb shell su 0 chown "$owner" /data/data/$PKG/shared_prefs/${PKG}_preferences.xml
    adb shell su 0 chmod 660 /data/data/$PKG/shared_prefs/${PKG}_preferences.xml
}
open_names() {
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_names" >/dev/null; sleep 5
    wait_text 15 "short.txt" >/dev/null || true
}
set_name_prefs 2 2
open_names
shot 74_names_two_lines
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_names2.xml" 2>/dev/null
two=$(name_box "$OUT/ui_names2.xml")
set_name_prefs 1 2
open_names
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_names1.xml" 2>/dev/null
one=$(name_box "$OUT/ui_names1.xml")
log "      long name, 2 lines: height/left/right/size/size right = $two; 1 line: $one"
h2=$(echo "$two" | cut -d' ' -f1); h1=$(echo "$one" | cut -d' ' -f1)
if [ "$two" != none ] && [ "$one" != none ] && python3 -c "import sys; sys.exit(0 if $h2 >= $h1 * 1.6 else 1)"; then
    pass "the 'max lines of file name' setting shows a long name on 2 lines (it stayed on 1)"
else
    fail "a long file name still shows on one line with the 2-line setting ($two vs $one)"
fi
size2=$(echo "$two" | cut -d' ' -f4); sright=$(echo "$two" | cut -d' ' -f5); nright=$(echo "$two" | cut -d' ' -f3)
if printf '%s' "$size2" | grep -qE "^[0-9.,]+_?(KB|kB|MB|B)$" && [ "${sright:-0}" -le "$(( ${nright:-0} + 2 ))" ]; then
    pass "the size is shown whole on the right of the date (${size2//_/ })"
else
    fail "the file size is cut or missing (${size2:-none})"
fi
# Marquee on a single line: the name moves.
set_name_prefs 1 3
open_names
read -r mh mx1 mx2 _ <<< "$(name_box "$OUT/ui_names1.xml")"
my=$(python3 - "$OUT/ui_names1.xml" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    if ':id/nameText"' in n and 'youmo-0.6.3' in n:
        print(re.search(r'bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', n).groups()[0]); break
PY2
)
sleep 2
adb exec-out screencap > "$OUT/marquee_a.raw" 2>/dev/null
sleep 1.5
adb exec-out screencap > "$OUT/marquee_b.raw" 2>/dev/null
moved=$(python3 - "$OUT/marquee_a.raw" "$OUT/marquee_b.raw" "${mx1:-0}" "${my:-0}" "${mx2:-0}" "${mh:-0}" <<'PY2'
import struct, sys
a = open(sys.argv[1], "rb").read(); b = open(sys.argv[2], "rb").read()
w, h = struct.unpack("<II", a[:8]); hd = len(a) - w * h * 4
x1, y1, x2, hh = (int(v) for v in sys.argv[3:7])
diff = 0
for y in range(y1, min(h, y1 + hh), 2):
    for x in range(x1, min(w, x2), 2):
        o = hd + (y * w + x) * 4
        if abs(a[o] - b[o]) + abs(a[o + 1] - b[o + 1]) + abs(a[o + 2] - b[o + 2]) > 60: diff += 1
print(diff)
PY2
)
log "      marquee: $moved pixels changed in the name over 1.5 s"
if [ "${moved:-0}" -gt 30 ]; then
    pass "marquee: a long name scrolls on one line"
else
    fail "marquee: the long name does not scroll ($moved pixels changed)"
fi
set_name_prefs 2 2
adb shell rm -rf /sdcard/om_names

log "=== Quick filter in the toolbar and 'Search results' again (user requests, MT style) ==="
adb shell rm -rf /sdcard/om_qf; adb shell mkdir -p /sdcard/om_qf/sub
for f in alpha.txt beta.txt alphabet.log gamma.txt sub/alpha_deep.txt; do adb shell "echo x > /sdcard/om_qf/$f"; done
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_qf" >/dev/null; sleep 5
wait_text 15 "gamma.txt" >/dev/null || true
demo more_menu; sleep 2
if pos=$(find_text "Filter" "Filtro"); then
    adb shell input tap $pos; sleep 2
    if node_center_by_id etQuickFilter >/dev/null; then
        adb shell input text alp; sleep 2
        shot 75_quick_filter
        adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
        adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_qf.xml" 2>/dev/null
        shown=$(grep -o 'text="[^"]*"' "$OUT/ui_qf.xml" | sed 's/text="//; s/"$//' | grep -E "^(alpha\.txt|beta\.txt|alphabet\.log|gamma\.txt)$" | sort | tr '\n' ' ')
        dialog=$(grep -c 'resource-id="android:id/button1"' "$OUT/ui_qf.xml" || true)
        log "      typed 'alp' in the toolbar: listed = $shown(dialogs: $dialog)"
        if [ "$shown" = "alpha.txt alphabet.log " ] && [ "${dialog:-0}" -eq 0 ]; then
            pass "quick filter: typing in the toolbar filters the panel at once, without a dialog"
        else
            fail "quick filter did not filter as typed ($shown)"
        fi
        # Back (a second one if the first only hid the keyboard) closes the filter.
        adb shell input keyevent KEYCODE_BACK; sleep 1
        node_center_by_id etQuickFilter >/dev/null && { adb shell input keyevent KEYCODE_BACK; sleep 1; }
        sleep 1
        if find_text "beta.txt" >/dev/null && ! node_center_by_id etQuickFilter >/dev/null; then
            pass "quick filter: Back closes it and the whole folder is back"
        else
            fail "quick filter: Back did not close it"
        fi
    else
        fail "quick filter: no text field in the toolbar"
    fi
else
    fail "no Filter entry in the ⋮ menu"
fi
# Search, open a folder from elsewhere, then show the results again from the ⋮ menu.
adb logcat -c
demo more_menu; sleep 2
if pos=$(find_text "Search files..." "Buscar archivos..."); then
    # One dialog as MT's: straight to the name field (no chooser first).
    adb shell input tap $pos; sleep 2
    shot 76d_search_dialog
    if find_text "Search subfolders" >/dev/null && find_text "Regular expression" >/dev/null; then
        pass "Search files opens one dialog with name, subfolders, case and regex (as MT)"
    else
        fail "Search files did not open the one search dialog"
    fi
    adb shell input text alpha; sleep 1
    pos=$(find_text "Search") && adb shell input tap $pos; sleep 6
    shot 76b_search_results_count
    if find_contains "3 results" >/dev/null; then
        pass "the search shows how many results it found (3 results)"
    else
        fail "the search does not show its number of results"
    fi
    if find_text "./sub/" >/dev/null; then
        pass "each search result shows its folder (./sub/), as MT"
    else
        fail "search results do not show their folder"
    fi
    # Leaving to another screen and coming back must not search again (user report).
    adb shell am start -W -n $PKG/me.zhanghai.android.files.settings.SettingsActivity >/dev/null 2>&1; sleep 2
    adb shell input keyevent KEYCODE_BACK; sleep 3
    starts_after_back=$(adb logcat -d -s OMSearch 2>/dev/null | grep -c "Search start" || true)
    adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_qf/sub" >/dev/null; sleep 4
    demo more_menu; sleep 2
    shot 76a_menu_after_search
    if pos=$(find_text "Search results" "Resultados de búsqueda"); then
        adb shell input tap $pos; sleep 4
        shot 76_search_results_again
        starts=$(adb logcat -d -s OMSearch 2>/dev/null | grep -c "Search start" || true)
        memory=$(adb logcat -d -s OMSearch 2>/dev/null | grep -c "from memory" || true)
        log "      searches started: $starts_after_back after leaving and coming back, $starts in all; results from memory: $memory"
        if find_text "alpha_deep.txt" >/dev/null && find_text "alphabet.log" >/dev/null; then
            pass "'Search results' in the ⋮ menu shows the last search again"
        else
            fail "'Search results' did not show the last search's results"
        fi
        if [ "$starts_after_back" = 1 ] && [ "$starts" = 1 ] && [ "${memory:-0}" -ge 1 ]; then
            pass "the search runs once: coming back and 'Search results' reuse its results"
        else
            fail "the search ran again ($starts_after_back after coming back, $starts in all, memory $memory)"
        fi
        # Tapping a result goes to its folder with it highlighted (as MT).
        if pos=$(find_text "alpha_deep.txt"); then
            adb shell input tap $pos; sleep 3
            shot 76c_result_opens_folder
            went=$(adb logcat -d -s OMSearch 2>/dev/null | grep -c "Result opens its folder" || true)
            if [ "${went:-0}" -ge 1 ] && find_contains "om_qf/sub" >/dev/null; then
                pass "tapping a search result opens its folder (as MT)"
            else
                fail "tapping a search result did not open its folder"
            fi
        fi
    else
        fail "no 'Search results' entry after a search"
        adb shell input keyevent KEYCODE_BACK
    fi
else
    fail "no 'Search files' entry in the ⋮ menu"
fi
# Wildcards and this folder only: "alpha*" is the whole name, sub/ is left out.
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_qf" >/dev/null; sleep 4
demo more_menu; sleep 2
if pos=$(find_text "Search files..." "Buscar archivos..."); then
    adb shell input tap $pos; sleep 2
    adb shell "input text 'alpha*'"; sleep 1
    pos=$(find_text "Search subfolders") && adb shell input tap $pos; sleep 1
    pos=$(find_text "Search") && adb shell input tap $pos; sleep 5
    shot 76e_wildcard_search
    if find_contains "2 results" >/dev/null && find_text "alphabet.log" >/dev/null && ! find_text "alpha_deep.txt" >/dev/null; then
        pass "alpha* without subfolders finds alpha.txt and alphabet.log only"
    else
        fail "wildcard search in this folder only gave the wrong results"
    fi
    # The history keeps both searches, newest first.
    demo more_menu; sleep 2
    pos=$(find_text "Search files..." "Buscar archivos...") && adb shell input tap $pos; sleep 2
    if pos=$(find_text "Search history"); then
        adb shell input tap $pos; sleep 2
        shot 76f_search_history
        if find_text "alpha*" >/dev/null && find_text "alpha" >/dev/null; then
            pass "the search history lists the earlier searches (alpha*, alpha)"
        else
            fail "the search history does not list the earlier searches"
        fi
        adb shell input keyevent KEYCODE_BACK; sleep 1
    else
        fail "no history button in the search dialog"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "no 'Search files' entry for the wildcard search"
fi
# The dialog remembers its checkboxes: back to the defaults for later tests.
adb shell run-as $PKG rm -f shared_prefs/om_search_options.xml shared_prefs/om_search_history.xml 2>/dev/null || true
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_qf

log "=== HTML viewer: a page with its CSS and script, and the DOCX subtitle (user requests) ==="
mkdir -p "$OUT/om_html"
cat > "$OUT/om_html/page.html" <<'HTML'
<!DOCTYPE html><html><head><meta charset="utf-8"><title>loading</title>
<link rel="stylesheet" href="style.css"></head>
<body><h1 id="t">OM HTML OK</h1><p>Second line.</p>
<script>
  var c = getComputedStyle(document.getElementById('t')).color;
  document.title = 'JS OK ' + (c === 'rgb(0, 128, 0)' ? 'CSS OK' : 'CSS MISSING ' + c);
</script></body></html>
HTML
echo '#t { color: rgb(0, 128, 0); }' > "$OUT/om_html/style.css"
python3 - "$OUT/om_html/notes.docx" <<'PY2'
import sys, zipfile
words = " ".join("word%d" % i for i in range(300))
doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
       '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
       '<w:p><w:r><w:t>OM DOCX</w:t></w:r></w:p><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body></w:document>') % words
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    z.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    z.writestr("word/document.xml", doc)
PY2
adb shell rm -rf /sdcard/om_html; adb push "$OUT/om_html" /sdcard/ >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.viewer.html.HtmlViewerActivity \
    --es $PKG.extra.PATH_URI file:///storage/emulated/0/om_html/page.html" >/dev/null 2>&1
sleep 6
shot 77_html_viewer
title=$(adb logcat -d -s OMHtml 2>/dev/null | grep "Title=" | tail -1 | sed 's/.*Title=//')
log "      page title after its script ran: ${title:-none}"
if [ "$title" = "JS OK CSS OK" ]; then
    pass "the HTML viewer renders a page with its CSS and runs its script"
else
    fail "the HTML viewer did not run the page (title: ${title:-none})"
fi
adb logcat -d > "$OUT/logcat_html.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_html.txt"; then fail "crash in the HTML viewer"; fi
# DOCX: the subtitle (words, reading time) is not cut by the toolbar.
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.docx.DocxViewerActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_html/notes.docx" >/dev/null 2>&1
sleep 6
shot 78_docx_subtitle
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_docx.xml" 2>/dev/null
density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
sub=$(python3 - "$OUT/ui_docx.xml" "${density:-420}" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
d = int(sys.argv[2]) / 160
for n in re.findall(r"<node [^>]*>", xml):
    t = re.search(r' text="([^"]*)"', n)
    if t and "words" in html.unescape(t.group(1)):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print(round((y2 - y1) / d, 1), html.unescape(t.group(1)).replace(" ", "_")); break
PY2
)
log "      DOCX subtitle: ${sub:-none} (dp tall)"
if [ -n "$sub" ] && python3 -c "import sys; sys.exit(0 if float('${sub%% *}') >= 15 else 1)"; then
    pass "the DOCX viewer's subtitle is shown whole"
else
    fail "the DOCX viewer's subtitle is cut or missing (${sub:-none})"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_html

log "=== payload.bin: its partitions without extracting (user request) ==="
python3 - "$OUT/payload.bin" <<'PY2'
import struct, sys
def varint(v):
    out = bytearray()
    while v > 0x7F:
        out.append((v & 0x7F) | 0x80); v >>= 7
    out.append(v); return bytes(out)
def field_int(f, v): return varint(f << 3) + varint(v)
def field_bytes(f, b): return varint((f << 3) | 2) + varint(len(b)) + b
def part(name, size, ops):
    p = field_bytes(1, name.encode()) + field_bytes(7, field_int(1, size))
    for t in ops: p += field_bytes(8, field_int(1, t) + field_int(3, 10))
    return p
manifest = field_int(3, 4096) + field_bytes(13, part("boot", 100663296, [8, 8])) + \
    field_bytes(13, part("system", 2147483648, [14, 14, 0])) + field_bytes(13, part("vendor_boot", 67108864, [8]))
data = b"CrAU" + struct.pack(">QQI", 2, len(manifest), 0) + manifest + b"\0" * 4096
open(sys.argv[1], "wb").write(data)
PY2
adb shell rm -rf /sdcard/om_ota; adb shell mkdir -p /sdcard/om_ota
adb push "$OUT/payload.bin" /sdcard/om_ota/payload.bin >/dev/null
adb shell "echo 'FILE_HASH=x' > /sdcard/om_ota/payload_properties.txt"
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_ota" >/dev/null; sleep 5
if pos=$(wait_text 15 "payload.bin"); then
    adb shell input tap $pos; sleep 3
    shot 79_payload_contents
    parsed=$(adb logcat -d -s OMPayload 2>/dev/null | grep "Payload v" | tail -1 | sed 's/.*OMPayload: //')
    log "      $parsed"
    if printf '%s' "$parsed" | grep -q "partitions=3 boot:100663296:xz,system:2147483648:zstd,vendor_boot:67108864:xz" \
        && find_text "OK" >/dev/null; then
        pass "tapping payload.bin lists its partitions, sizes and compression without extracting"
    else
        fail "payload.bin contents not shown (${parsed:-nothing})"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "payload.bin not listed"
fi
# Inside an OTA zip: payload.bin stored uncompressed, ZIP64 (as real OTAs are, at several GB).
python3 - "$OUT/payload.bin" "$OUT/om_ota.zip" <<'PY2'
import sys, zipfile
with zipfile.ZipFile(sys.argv[2], "w", compression=zipfile.ZIP_STORED, allowZip64=True) as z:
    with z.open("payload.bin", "w", force_zip64=True) as f:
        f.write(open(sys.argv[1], "rb").read())
    z.writestr("payload_properties.txt", "FILE_HASH=x\n")
PY2
adb push "$OUT/om_ota.zip" /sdcard/om_ota/ota.zip >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_ota" >/dev/null; sleep 5
if pos=$(wait_text 15 "ota.zip"); then
    adb shell input tap $pos; sleep 3
    if pos=$(wait_text 10 "payload.bin"); then
        adb shell input tap $pos; sleep 3
        shot 79b_payload_in_zip
        parsed=$(adb logcat -d -s OMPayload 2>/dev/null | grep "Payload v" | tail -1 | sed 's/.*OMPayload: //')
        log "      inside ota.zip (ZIP64): ${parsed:-$(adb logcat -d -s OMPayload 2>/dev/null | tail -2 | tr '\n' ' ')}"
        if printf '%s' "$parsed" | grep -q "partitions=3 "; then
            pass "payload.bin inside an OTA zip (ZIP64, stored) lists its partitions"
        else
            fail "payload.bin inside an OTA zip is not read"
        fi
        adb shell input keyevent KEYCODE_BACK; sleep 1
    else
        fail "payload.bin not listed inside ota.zip"
    fi
else
    fail "ota.zip not listed"
fi
adb logcat -d > "$OUT/logcat_payload.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_payload.txt"; then fail "crash opening payload.bin"; fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_ota

log "=== Transparent PNG: shown over a checkerboard (user request) ==="
python3 - "$OUT/om_transparent.png" <<'PY2'
import struct, sys, zlib
w = h = 64
rows = b"".join(b"\x00" + b"".join(bytes((255, 0, 0, 255 if (x - 32) ** 2 + (y - 32) ** 2 < 400 else 0)) for x in range(w)) for y in range(h))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
PY2
adb push "$OUT/om_transparent.png" /sdcard/Pictures/om_transparent.png >/dev/null
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.image.ImageViewerActivity \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/Pictures/om_transparent.png" >/dev/null 2>&1
sleep 5
shot 21d_transparent_png
adb exec-out screencap > "$OUT/transparent.raw" 2>/dev/null
checker=$(adb logcat -d -s OMImageViewer 2>/dev/null | grep -c "checkerboard behind it" || true)
# The center of the screen shows the red circle; around it, inside the image, light checker squares.
read -r t_sat t_dark t_light <<< "$(python3 - "$OUT/transparent.raw" <<'PY2'
import struct, sys
raw = open(sys.argv[1], "rb").read()
w, h = struct.unpack("<II", raw[:8]); hd = len(raw) - w * h * 4
cx, cy = w // 2, h // 2
# A band halfway between the circle and the image edge (image fills the width).
sat = dark = light = 0
for y in range(cy - w * 7 // 16, cy - w * 3 // 8, 2):
    for x in range(w // 8, w * 7 // 8, 2):
        o = hd + (y * w + x) * 4
        r, g, b = raw[o], raw[o + 1], raw[o + 2]
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        if lum < 90: dark += 1
        if lum > 200: light += 1
print(sat, dark, light)
PY2
)"
log "      transparent PNG: checkerboard logged=$checker, light pixels around the circle=$t_light dark=$t_dark"
if [ "${checker:-0}" -ge 1 ] && [ "${t_light:-0}" -gt "${t_dark:-0}" ]; then
    pass "a transparent PNG is shown over a checkerboard, not black"
else
    fail "the transparent PNG has no checkerboard (logged=$checker light=$t_light dark=$t_dark)"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -f /sdcard/Pictures/om_transparent.png

log "=== System file picker: OpenManager as a documents provider (user request) ==="
adb shell rm -rf /sdcard/om_dp; adb shell mkdir -p /sdcard/om_dp
adb shell "echo 'OM DOCS OK' > /sdcard/om_dp/hello.txt"
auth="$PKG.documents"
roots=$(adb shell "su 0 content query --uri content://$auth/root" 2>&1 | tr -d '\r')
kids=$(adb shell "su 0 content query --uri content://$auth/document/%2Fstorage%2Femulated%2F0%2Fom_dp/children" 2>&1 | tr -d '\r')
body=$(adb shell "su 0 content read --uri content://$auth/document/%2Fstorage%2Femulated%2F0%2Fom_dp%2Fhello.txt" 2>&1 | tr -d '\r')
outside=$(adb shell "su 0 content query --uri content://$auth/document/%2Fdata%2Fdata/children" 2>&1 | tr -d '\r' | head -c 300)
log "      roots: $(printf '%s' "$roots" | grep -o 'title=[^,]*' | head -2 | tr '\n' ' ')"
log "      om_dp children: $(printf '%s' "$kids" | grep -o '_display_name=[^,]*' | tr '\n' ' ')"
log "      hello.txt read through the picker: ${body:-nothing}"
if printf '%s' "$roots" | grep -q "title=OpenManager" && printf '%s' "$kids" | grep -q "_display_name=hello.txt" \
    && [ "$body" = "OM DOCS OK" ]; then
    pass "other apps can browse and open files through OpenManager in the system file picker"
else
    fail "the documents provider did not list or open files (roots: ${roots:0:120})"
fi
if printf '%s' "$outside" | grep -q "_display_name="; then
    fail "the documents provider lists app-private /data folders"
else
    pass "the documents provider refuses paths outside the storage"
fi
adb shell rm -rf /sdcard/om_dp

log "=== Connections: USB ADB / fastboot screen (OTG, owner request) ==="
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.terminal.remote.ConnectionsActivity" >/dev/null 2>&1; sleep 4
if pos=$(find_text "USB: ADB / fastboot"); then
    adb shell input tap $pos; sleep 3
    shot 80_usb_tools
    if find_contains "No phone in ADB or fastboot mode" >/dev/null && find_contains "OTG cable" >/dev/null; then
        pass "Connections has USB ADB/fastboot; with nothing plugged in it explains how to connect"
    else
        fail "the USB ADB/fastboot screen did not show its help"
    fi
else
    fail "no USB ADB/fastboot entry in Connections"
fi
adb logcat -d > "$OUT/logcat_usb.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_usb.txt"; then fail "crash on the USB screen"; fi
adb shell am force-stop $PKG; sleep 1

log "=== Open with: built-in viewers first, Other apps, archive viewer in the app (user request) ==="
python3 - "$OUT/om_oa_pack.bin" <<'PY2'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("inside_marker.txt", "hello")
PY2
adb shell rm -rf /sdcard/om_oa; adb shell mkdir -p /sdcard/om_oa
adb push "$OUT/om_oa_pack.bin" /sdcard/om_oa/pack.bin >/dev/null
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_oa" >/dev/null; sleep 5
if pos=$(wait_text 15 "pack.bin"); then
    adb shell input swipe $pos $pos 1200; sleep 2
    if pos2=$(find_text "Open with…" "Abrir con…"); then
        adb shell input tap $pos2; sleep 2
        shot 81_open_with_grid
        if find_text "Archive viewer" >/dev/null && find_text "Other apps…" >/dev/null; then
            pass "Open with shows the built-in viewers, with Other apps at the end"
        else
            fail "Open with did not show the built-in viewers grid"
        fi
        if pos3=$(find_text "Archive viewer"); then
            adb shell input tap $pos3; sleep 4
            shot 82_archive_inside
            if find_text "inside_marker.txt" >/dev/null; then
                pass "the archive viewer opens a zip named .bin inside OpenManager"
            else
                fail "the archive viewer did not open the archive inside OpenManager"
            fi
        fi
    else
        fail "no Open with in the file menu"
    fi
else
    fail "pack.bin not listed"
fi
adb logcat -d > "$OUT/logcat_openwith.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_openwith.txt"; then fail "crash in Open with"; fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_oa

log "=== Back clears a selection; long press on bottom buttons is customizable (user requests) ==="
adb shell rm -rf /sdcard/om_bk; adb shell mkdir -p /sdcard/om_bk
adb shell "echo a > /sdcard/om_bk/one.txt"; adb shell "echo b > /sdcard/om_bk/two.txt"
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_bk" >/dev/null; sleep 5
if pos=$(wait_text 15 "one.txt"); then
    # Select it with a swipe to the right (holding only opens the menu).
    y=${pos#* }
    adb shell input swipe 40 $y 320 $y 300; sleep 2
    # Select all is a button of the selection bar now (user request).
    if pos_all=$(node_center_by_id btnSelectAll); then
        adb shell input tap $pos_all; sleep 2
        shot 84a_select_all_button
        if find_contains "Selected: 2 / 2" "Seleccionados: 2 / 2" >/dev/null; then
            pass "the selection bar's Select all button selects every file (2 / 2)"
        else
            fail "Select all in the selection bar did not select both files"
        fi
    else
        fail "no Select all button in the selection bar"
    fi
    selected=$(node_center_by_id btnSelectionClose >/dev/null && echo yes || echo no)
    adb shell input keyevent KEYCODE_BACK; sleep 2
    after=$(node_center_by_id btnSelectionClose >/dev/null && echo yes || echo no)
    still_here=$(find_text "two.txt" >/dev/null && echo yes || echo no)
    log "      selection before Back: $selected, after Back: $after, still in the folder: $still_here"
    if [ "$selected" = yes ] && [ "$after" = no ] && [ "$still_here" = yes ]; then
        pass "Back clears the selection instead of going up a folder"
    else
        fail "Back with a selection: selection $selected -> $after, still in folder: $still_here"
    fi
else
    fail "om_bk not listed"
fi
# Long press on ⇄ with nothing assigned asks what it should do; then it does it.
if pos=$(node_center_by_id btnBottomSwap); then
    adb shell input swipe $pos $pos 1000; sleep 2
    shot 83_long_press_choose
    if pos2=$(find_text "Filter" "Filtro"); then
        adb shell input tap $pos2; sleep 1
        pos=$(node_center_by_id btnBottomSwap) && adb shell input swipe $pos $pos 1000; sleep 2
        ran=$(adb logcat -d -s OMBottomBar 2>/dev/null | grep -c "Long press action quick_filter" || true)
        if [ "${ran:-0}" -ge 1 ] && node_center_by_id etQuickFilter >/dev/null; then
            pass "holding ⇄ runs the action chosen for it (quick filter)"
        else
            fail "holding ⇄ did not run the chosen action (ran=$ran)"
        fi
        adb shell input keyevent KEYCODE_BACK; sleep 1
        adb shell input keyevent KEYCODE_BACK; sleep 1
    else
        fail "holding an unassigned bottom button did not ask what it should do"
    fi
else
    fail "no ⇄ button"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_bk

log "=== Applications: how long the list and an app's details take (owner: the section feels slow) ==="
apps_open() {
    adb logcat -c
    adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.appmanager.InstalledAppsActivity" >/dev/null 2>&1
    for _ in $(seq 1 30); do
        line=$(adb logcat -d -s OMApps 2>/dev/null | grep "List shown" | tail -1 | sed 's/.*OMApps: //')
        [ -n "$line" ] && break
        sleep 1
    done
    sleep 3
    printf '%s' "$line"
}
adb shell am force-stop $PKG; sleep 1
adb shell run-as $PKG rm -f cache/installed_apps_v2.json 2>/dev/null
cold=$(apps_open)
scan=$(adb logcat -d -s OMApps 2>/dev/null | grep "Scan done" | tail -1 | sed 's/.*OMApps: //')
icon_avg() {  # <source>: average ms of the icons loaded from it, and how many
    adb logcat -d -s OMAppIcons 2>/dev/null | grep "source=$1 " | grep -o "ms=[0-9]*" | cut -d= -f2 | \
        python3 -c "import sys; v=[int(x) for x in sys.stdin.read().split()]; print('%.1f ms x%d' % (sum(v)/len(v), len(v)) if v else 'none')"
}
icons_cold=$(icon_avg system)
shot 84_apps_cold
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am force-stop $PKG; sleep 1
disk=$(apps_open)
icons_disk=$(icon_avg disk)
adb shell input keyevent KEYCODE_BACK; sleep 1
memory=$(apps_open)
# Details of the first app in the list.
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_apps.xml" 2>/dev/null
first=$(python3 - "$OUT/ui_apps.xml" <<'PY2'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    if re.search(r':id/appName"', n):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY2
)
details=""
if [ -n "$first" ]; then
    adb logcat -c
    adb shell input tap $first; sleep 3
    details=$(adb logcat -d -s OMApps 2>/dev/null | grep "Details shown" | tail -1 | sed 's/.*OMApps: //')
    shot 85_app_details
    adb shell input keyevent KEYCODE_BACK; sleep 1
fi
log "      first open (no cache): $cold; $scan"
log "      next open (disk cache): $disk"
log "      open again (memory): $memory"
log "      app details: ${details:-not opened}"
log "      icons: from the system $icons_cold (first open); from the disk copy $icons_disk (next open)"
if printf '%s' "$icons_disk" | grep -q " x"; then
    pass "app icons come from their disk copy after the first open ($icons_disk vs $icons_cold)"
else
    fail "app icons were not read from the disk copy ($icons_disk)"
fi
ms_of() { printf '%s' "$1" | grep -o "ms=[0-9]*" | head -1 | cut -d= -f2; }
c=$(ms_of "$cold"); d=$(ms_of "$disk"); m=$(ms_of "$memory"); t=$(ms_of "$details")
if [ -n "$c" ] && [ -n "$d" ] && [ -n "$m" ] && [ "${d:-99999}" -lt 1500 ] && [ "${m:-99999}" -lt 1000 ] && [ "${t:-99999}" -lt 800 ]; then
    pass "Applications: list in ${d} ms from cache (${c} ms first time), details in ${t} ms"
else
    fail "Applications is slow or did not open (first $c ms, cache $d ms, memory $m ms, details $t ms)"
fi
adb shell am force-stop $PKG; sleep 1

log "=== App backup and restore with root (Android-DataBackup style, owner request) ==="
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_backup_test
adb shell "run-as $PKG sh -c 'mkdir -p files && echo OMBK > files/om_backup_marker'"
adb logcat -c
adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.appmanager.InstalledAppsActivity --es DEMO_BACKUP_SCRIPTS $PKG" >/dev/null 2>&1
sleep 4
adb shell am force-stop $PKG; sleep 1
bk_out=$(adb shell "su 0 sh /sdcard/om_backup_test/backup.sh" 2>&1 | tr -d '\r' | tail -3)
bk_files=$(adb shell "su 0 ls /sdcard/om_backup_test/bk /sdcard/om_backup_test/bk/apk" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
adb shell "run-as $PKG rm -f files/om_backup_marker"
gone=$(adb shell "run-as $PKG cat files/om_backup_marker" 2>/dev/null | tr -d '\r')
rs_out=$(adb shell "su 0 sh /sdcard/om_backup_test/restore.sh" 2>&1 | tr -d '\r' | tail -3)
back=$(adb shell "run-as $PKG cat files/om_backup_marker" 2>/dev/null | tr -d '\r')
label=$(adb shell "su 0 ls -Z /data/user/0/$PKG/files/om_backup_marker" 2>/dev/null | tr -d '\r')
log "      backup: $(printf '%s' "$bk_out" | tail -1) · files: $bk_files"
log "      marker after deleting: '${gone}' · restore: $(printf '%s' "$rs_out" | tail -1) · marker after restore: '${back}'"
log "      SELinux label: $label"
if printf '%s' "$bk_out" | grep -q OM_BACKUP_OK && printf '%s' "$bk_files" | grep -q "data.tar.gz" \
    && printf '%s' "$bk_files" | grep -q "\.apk"; then
    pass "backup saves the APK and the app's data (tar.gz)"
else
    fail "backup failed ($bk_out)"
fi
if printf '%s' "$rs_out" | grep -q OM_RESTORE_OK && [ -z "$gone" ] && [ "$back" = OMBK ] \
    && printf '%s' "$label" | grep -q "app_data_file"; then
    pass "restore puts the data back, readable by the app, with its SELinux label"
else
    fail "restore failed ($rs_out; marker '$back'; label $label)"
fi
adb shell rm -rf /sdcard/om_backup_test

log "=== A long copy or extraction shows its progress on screen (user request, MT style) ==="
adb shell rm -rf /sdcard/om_job; adb shell mkdir -p /sdcard/om_job/dst
# 600 MB: a faster runner copied 300 MB in under the 0.5 s after which the dialog appears.
adb shell "dd if=/dev/zero of=/sdcard/om_job/big.bin bs=1048576 count=600" >/dev/null 2>&1
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" >/dev/null 2>&1; sleep 4
adb shell am start -n "$MAIN" --es DEMO_COPY_FROM "file:///storage/emulated/0/om_job/big.bin" \
    --es DEMO_COPY_TO "file:///storage/emulated/0/om_job/dst" >/dev/null 2>&1
shown=""
for _ in $(seq 1 20); do
    shown=$(adb logcat -d -s OMFileJob 2>/dev/null | grep "Dialog shown" | tail -1 | sed 's/.*OMFileJob: //')
    [ -n "$shown" ] && break
    sleep 0.5
done
shot 86_job_progress_dialog
done_line=""
for _ in $(seq 1 60); do
    done_line=$(adb logcat -d -s OMFileJob 2>/dev/null | grep "Finished" | tail -1 | sed 's/.*OMFileJob: //')
    [ -n "$done_line" ] && break
    sleep 1
done
copied=$(adb shell stat -c %s /sdcard/om_job/dst/big.bin 2>/dev/null | tr -d '\r')
log "      ${shown:-no dialog} · ${done_line:-not finished} · copied $copied bytes"
if [ -n "$shown" ] && printf '%s' "$done_line" | grep -q "failed=false" && [ "$copied" = 629145600 ]; then
    pass "a 600 MB copy shows a progress dialog and says when it is done"
else
    fail "no progress dialog or no end notice for a long copy ($shown; $done_line; $copied)"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_job

log "=== Image viewer: 1,000 images from a search, light theme menu, background (user report) ==="
python3 - "$OUT/om_many" <<'PY2'
import os, struct, sys, zlib
d = sys.argv[1]; os.makedirs(d, exist_ok=True)
def png(seed):
    raw = b"".join(b"\x00" + bytes(((x * 31 + y * 17 + seed) % 256, seed % 256, 128) * 1)[:3] * 1 * 1 + bytes(3 * 7) for y in range(8) for x in [0])
    def chunk(t, data): return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
for i in range(1000):
    open(os.path.join(d, "img_%04d.png" % i), "wb").write(png(i))
PY2
adb shell rm -rf /sdcard/Pictures/om_many
adb push "$OUT/om_many" /sdcard/Pictures/ >/dev/null
adb shell am force-stop $PKG; sleep 1
adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME light >/dev/null 2>&1; sleep 4
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.image.ImageViewerActivity \
    --es DEMO_IMAGE_DIR /sdcard/Pictures/om_many >/dev/null 2>&1
sleep 4
if find_text "19/1,000" "19/1000" >/dev/null; then
    pass "the image viewer opens 1,000 images at the 19th"
else
    fail "the image viewer did not open the 1,000 images at the 19th"
fi
# Light theme: the ⋮ menu of the viewer is readable (it was white text on a light background).
if pos=$(find_text "More options" "Más opciones"); then
    adb shell input tap $pos; sleep 2
    shot 21c_image_viewer_menu_light
    adb exec-out screencap > "$OUT/viewer_menu.raw" 2>/dev/null
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_viewer_menu.xml" 2>/dev/null
    row=$(python3 - "$OUT/ui_viewer_menu.xml" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for n in re.findall(r"<node [^>]*>", xml):
    t = re.search(r' text="([^"]*)"', n)
    if t and html.unescape(t.group(1)) in ("Delete", "Eliminar", "删除"):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print(x1 - 20, y1 - 10, x2 + 120, y2 + 10)
        break
PY2
)
    if [ -n "$row" ]; then
        read -r sat dark light <<< "$(raw_stats "$OUT/viewer_menu.raw" $row)"
        log "      viewer menu row (light theme): dark pixels=$dark light pixels=$light"
        # Readable: a dark menu with light text (white on light had no dark pixels at all).
        if [ "${dark:-0}" -gt "${light:-0}" ] && [ "${light:-0}" -ge 5 ]; then
            pass "light theme: the image viewer's menu is readable"
        else
            fail "light theme: the image viewer's menu text is hard to read (dark=$dark light=$light)"
        fi
    else
        fail "no Delete entry in the image viewer's menu"
    fi
    adb shell input keyevent KEYCODE_BACK; sleep 1
else
    fail "no ⋮ in the image viewer"
fi
# To the background, as a screenshot does: the saved state stays small and nothing crashes.
adb shell input keyevent KEYCODE_HOME; sleep 3
state=$(adb logcat -d -s OMImageViewer 2>/dev/null | grep "Saved state bytes=" | tail -1 | grep -o "bytes=[0-9]*" | cut -d= -f2)
adb logcat -d > "$OUT/logcat_viewer_many.txt" 2>/dev/null || true
log "      saved state with 1,000 images: ${state:-?} bytes"
if [ -n "$state" ] && [ "$state" -lt 100000 ] && ! grep -q "TransactionTooLarge" "$OUT/logcat_viewer_many.txt" \
    && ! app_crashed "$OUT/logcat_viewer_many.txt"; then
    pass "1,000 images: going to the background saves ${state} bytes, no crash"
else
    fail "1,000 images: saved state ${state:-?} bytes or TransactionTooLargeException"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/Pictures/om_many
adb shell am start -W -n $SETTINGS --es DEMO_MD3 off --es DEMO_THEME_COLOR color_primary --es DEMO_THEME dark >/dev/null 2>&1; sleep 3
adb shell am force-stop $PKG; sleep 1

log "=== HEX editor: a 60 MB file opens, searches, edits and saves (user report: 25 MB limit) ==="
python3 - "$OUT/om_big.bin" <<'PY2'
import os, sys
size = 60 * 1024 * 1024
with open(sys.argv[1], "wb") as f:
    f.write(b"\x7fELF" + os.urandom(size - 4))
    f.seek(50_000_000)
    f.write(b"OMHEXMARK")
PY2
adb push "$OUT/om_big.bin" /sdcard/om_editor_test/om_big.bin >/dev/null
big_uri="file:///storage/emulated/0/om_editor_test/om_big.bin"
# The text editor explains the limit (the message used to be hidden behind the editor) and
# offers the HEX editor and a read-only look at the first 20 MB.
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "$big_uri" >/dev/null
sleep 5
shot 70_editor_too_large
err=$(node_text_by_id errorText || true)
log "      text editor: ${err:-no message}"
if printf '%s' "$err" | grep -q "MB" && node_center_by_id errorOpenHexButton >/dev/null; then
    pass "the text editor shows why a 60 MB file did not open, with a button to the HEX editor"
else
    fail "the text editor shows an empty page for a 60 MB file"
fi
if pos=$(node_center_by_id errorOpenPartialButton); then
    adb shell input tap $pos; sleep 8
    shot 71_editor_partial
    name=$(node_text_by_id statusFileNameText || true)
    layout=$(adb logcat -d -s OMEditor 2>/dev/null | grep "layout lines=" | tail -1 | sed 's/.*OMEditor: //')
    log "      partial: $name · ${layout:-no layout}"
    lines=$(printf '%s' "$layout" | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
    if [ "${lines:-0}" -gt 1000 ] && printf '%s' "$name" | grep -q "read-only"; then
        pass "the first 20 MB open read-only in the text editor"
    else
        fail "the read-only first part did not open ($name)"
    fi
else
    fail "no button to read the first part of the file"
fi
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity \
    --es "$PKG.extra.PATH_URI" "$big_uri" >/dev/null
sleep 5
if pos=$(node_center_by_id errorOpenHexButton); then
    adb shell input tap $pos; sleep 6
    shot 72_hex_big
    opened=$(adb logcat -d -s OMHex 2>/dev/null | grep "Open size=" | tail -1 | sed 's/.*OMHex: //')
    rows=$(adb logcat -d -s OMHex 2>/dev/null | grep "Rows:" | tail -1 | sed 's/.*OMHex: //')
    log "      $opened · $rows"
    ms=$(printf '%s' "$opened" | sed -n 's/.*ms=\([0-9]*\).*/\1/p')
    if printf '%s' "$opened" | grep -q "size=62914560 source=paged" && [ "${ms:-99999}" -lt 3000 ]; then
        pass "the HEX editor opens a 60 MB file page by page (${ms} ms)"
    else
        fail "the HEX editor did not open the 60 MB file paged ($opened)"
    fi
    # Layout: the subtitle is not cut by the toolbar, no EDIT/HEX/TXT buttons at the bottom, and
    # the ASCII column fits the screen.
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    adb shell cat /data/local/tmp/ui.xml > "$OUT/ui_hex.xml" 2>/dev/null
    density=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
    hexui=$(python3 - "$OUT/ui_hex.xml" "${density:-420}" <<'PY2'
import re, sys, html
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
d = int(sys.argv[2]) / 160
nodes = re.findall(r"<node [^>]*>", xml)
def b(n): return tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
width = max(b(n)[2] for n in nodes)
sub = [b(n) for n in nodes if re.search(r' text="60\.00 MB', n)]
sub_h = (sub[0][3] - sub[0][1]) / d if sub else 0
buttons = [html.unescape(re.search(r' text="([^"]*)"', n).group(1)) for n in nodes
           if 'Button' in n and re.search(r' text="(EDIT|Edit|Editar|HEX|Hex|TXT|Txt)"', n)]
ascii_right = max([b(n)[2] for n in nodes if re.search(r':id/tvAscii"', n)] or [width + 1])
ok = sub_h >= 15 and not buttons and ascii_right <= width
print("ok" if ok else "bad", round(sub_h, 1), "|".join(buttons) or "-", ascii_right, width)
PY2
)
    log "      hex layout: subtitle height=$(echo $hexui | cut -d' ' -f2) dp, bottom buttons=$(echo $hexui | cut -d' ' -f3), ASCII right edge=$(echo $hexui | cut -d' ' -f4)/$(echo $hexui | cut -d' ' -f5) px"
    if [ "${hexui%% *}" = ok ]; then
        pass "HEX editor: full subtitle, flat bottom strip, ASCII column on screen"
    else
        fail "HEX editor layout ($hexui)"
    fi
else
    fail "no 'Open in HEX editor' button"
fi
adb logcat -d > "$OUT/logcat_hex.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_hex.txt"; then fail "crash opening a 60 MB file"; fi
# Search 47 MB deep, overwrite there and save: only the edited bytes are written.
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.viewer.hex.HexEditorActivity \
    --es $PKG.extra.PATH_URI $big_uri --es DEMO_HEX_SEARCH 4F4D4845584D41524B --es DEMO_HEX_WRITE 58" >/dev/null 2>&1
saved=""
for _ in $(seq 1 30); do
    saved=$(adb logcat -d -s OMHex 2>/dev/null | grep "Save mode=patch" | tail -1 | sed 's/.*OMHex: //')
    [ -n "$saved" ] && break
    sleep 1
done
found=$(adb logcat -d -s OMHex 2>/dev/null | grep "Search match=" | tail -1 | sed 's/.*OMHex: //')
mark=$(adb shell "tail -c +50000001 /sdcard/om_editor_test/om_big.bin | head -c 9" 2>/dev/null | tr -d '\r')
fsize=$(adb shell stat -c %s /sdcard/om_editor_test/om_big.bin 2>/dev/null | tr -d '\r')
log "      $found · $saved · bytes at 50000000='$mark' size=$fsize"
if printf '%s' "$found" | grep -q "match=50000000 " && printf '%s' "$saved" | grep -q "mode=patch ok=true" \
    && [ "$mark" = "XMHEXMARK" ] && [ "$fsize" = 62914560 ]; then
    pass "search finds a mark 47 MB deep and the edit is saved in place"
else
    fail "search/edit/save in place on the 60 MB file ($found; $saved; '$mark'; $fsize)"
fi
shot 73_hex_saved
# Insert one byte at the start: the file is rewritten and everything moves by one.
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell "su 0 am start -n $PKG/me.zhanghai.android.files.viewer.hex.HexEditorActivity \
    --es $PKG.extra.PATH_URI $big_uri --es DEMO_HEX_INSERT AA" >/dev/null 2>&1
saved=""
for _ in $(seq 1 60); do
    saved=$(adb logcat -d -s OMHex 2>/dev/null | grep "Save mode=rewrite" | tail -1 | sed 's/.*OMHex: //')
    [ -n "$saved" ] && break
    sleep 1
done
first=$(adb shell "head -c 5 /sdcard/om_editor_test/om_big.bin | od -An -tx1" 2>/dev/null | tr -d '\r' | xargs)
mark=$(adb shell "tail -c +50000002 /sdcard/om_editor_test/om_big.bin | head -c 9" 2>/dev/null | tr -d '\r')
fsize=$(adb shell stat -c %s /sdcard/om_editor_test/om_big.bin 2>/dev/null | tr -d '\r')
log "      $saved · first bytes=$first mark='$mark' size=$fsize"
if printf '%s' "$saved" | grep -q "mode=rewrite ok=true" && [ "$first" = "aa 7f 45 4c 46" ] \
    && [ "$mark" = "XMHEXMARK" ] && [ "$fsize" = 62914561 ]; then
    pass "inserting a byte in a 60 MB file rewrites it correctly"
else
    fail "insert + rewrite of the 60 MB file ($saved; $first; '$mark'; $fsize)"
fi
adb logcat -d > "$OUT/logcat_hex2.txt" 2>/dev/null || true
if app_crashed "$OUT/logcat_hex2.txt"; then fail "crash editing a 60 MB file"; fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -f /sdcard/om_editor_test/om_big.bin

log "=== HTML preview, then Edit source: a 43 KB page of tables and box-drawing diagrams (user report: out of memory) ==="
# A synthetic page shaped like the users' (their files are private): CSS with numbers such as .4em
# (the cause: the highlighter looped on them until out of memory), CSS variables, 19 tables with
# ~700-character rows, and <pre> diagrams drawn with box-drawing characters and arrows.
python3 - "$OUT/om_page.html" <<'PY2'
import random, sys
random.seed(7)
words = "alpha beta gamma delta check verify session token result route queue client server cache".split()
def sentence(n): return " ".join(random.choice(words) for _ in range(n))
css = ":root{--ink:#1c2430;--muted:#536170;--bg:#f5f7fa;--card:#fff;--accent:#1759a6;--border:#d8dee6}\n" + "\n".join(
    f".c{i}{{color:var(--ink);margin:.{i % 9 + 1}em 0;opacity:.85;border:1px solid var(--border)}}" for i in range(25))
diagram = "\n".join([
    "  client ──► gateway ◄── worker",
    "               │",
    "   {ready?} ── no → retry/queue",
    "      │ yes",
    "      ↓",
    "  step-1: verify ───────── 2 ─────────",
    "         result   ──────┐   ┌──────────",
    "                        └───┘  ×3 · ≈ ¬ ∨ ‾ ► ▼",
] * 6)
parts = ['<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="UTF-8">\n<title>Test page — tables</title>\n<style>\n', css, "\n</style>\n</head>\n<body>\n"]
parts.append("<pre class=\"diagram\">" + diagram + "</pre>\n")
for t in range(19):
    parts.append(f'<h2>Section {t}</h2>\n<table class="c{t % 25}">\n')
    for r in range(4):
        parts.append("<tr><td>" + sentence(40) + ".</td><td>" + sentence(45) + " → " + sentence(20) + "</td></tr>\n")
    parts.append("</table>\n")
parts.append("<pre>" + diagram + "</pre>\n</body>\n</html>\n")
html = "".join(parts)
open(sys.argv[1], "w", encoding="utf-8").write(html)
print(len(html.encode()))
PY2
adb shell rm -rf /sdcard/om_html2; adb shell mkdir -p /sdcard/om_html2
adb push "$OUT/om_page.html" /sdcard/om_html2/om_page.html >/dev/null
log "      page: $(stat -c %s "$OUT/om_page.html") bytes"
adb shell am force-stop $PKG; sleep 1
adb logcat -c
adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW \
    --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/om_html2" >/dev/null; sleep 4
if pos=$(wait_text 15 "om_page.html"); then
    adb shell input tap $pos; sleep 5
    shot 88_html_preview
    if pos=$(find_text "More options" "Más opciones"); then
        adb shell input tap $pos; sleep 1
        if pos=$(find_text "Edit source" "Editar código fuente"); then
            adb shell input tap $pos; sleep 15
            shot 88b_html_edit_source
            adb shell dumpsys meminfo $PKG > "$OUT/meminfo_html_source.txt" 2>/dev/null || true
            heap=$(awk '/Java Heap:/{printf "%.1f", $3/1024; exit}' "$OUT/meminfo_html_source.txt")
            top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -o "$PKG/[A-Za-z0-9_.]*")
            adb logcat -d > "$OUT/logcat_html_source.txt" 2>/dev/null || true
            oom=$(grep -c "OutOfMemoryError" "$OUT/logcat_html_source.txt")
            log "      after 15 s in the editor: Java heap ${heap:-?} MB, top ${top:-?}, OutOfMemoryError lines $oom"
            if [ "$oom" = 0 ] && echo "$top" | grep -q TextEditorActivity && python3 -c "import sys; sys.exit(0 if float('${heap:-999}') < 64 else 1)"; then
                pass "HTML preview → Edit source opens the editor and stays small (${heap} MB)"
            else
                timeout 60 adb shell am dumpheap $PKG /data/local/tmp/om_html_source.hprof >/dev/null 2>&1 && sleep 3 && \
                    adb pull /data/local/tmp/om_html_source.hprof "$OUT/html_source.hprof" >/dev/null 2>&1
                fail "HTML preview → Edit source: heap ${heap:-?} MB, top ${top:-?}, OOM lines $oom"
            fi
        else
            shot 88c_no_edit_source
            fail "no Edit source entry in the HTML viewer menu"
        fi
    else
        fail "no overflow menu in the HTML viewer"
    fi
else
    fail "om_page.html not listed"
fi
adb shell am force-stop $PKG; sleep 1
adb shell rm -rf /sdcard/om_html2

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

#!/usr/bin/env bash
# Screenshots for the website (web/screenshots), taken on the CI emulator with realistic demo
# files: themes (dark, AMOLED, light), Material 3 accent colors, the main zones of the app,
# settings and languages. Output: web-shots/*.png (converted to WebP by the workflow).
set -u
PKG=com.y.om
MAIN=$PKG/me.zhanghai.android.files.filelist.FileListActivity
SETTINGS=$PKG/me.zhanghai.android.files.settings.SettingsActivity
SMART=$PKG/me.zhanghai.android.files.filelist.SmartOpenActivity
OUT=web-shots
mkdir -p "$OUT"
log() { echo "$*" | tee -a "$OUT/log.txt"; }

shot() {
    sleep "${2:-2}"
    adb shell screencap -p "/data/local/tmp/$1.png" >/dev/null 2>&1
    adb pull "/data/local/tmp/$1.png" "$OUT/$1.png" >/dev/null 2>&1 && log "shot $1" || log "MISSING $1"
}
demo() { adb shell am start -n "$MAIN" --es SHOW_DEMO_DIALOG "$1" >/dev/null 2>&1; }
open_dir() { adb shell am start -W -n "$MAIN" -a android.intent.action.VIEW --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/$1" >/dev/null 2>&1; }
settings_with() { adb shell am start -W -n "$SETTINGS" "$@" >/dev/null 2>&1; }
stop_app() { adb shell am force-stop $PKG; sleep 1; }
find_text() {
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

# node_id <resource-id suffix>: center of the first node with that id.
node_id() {
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
# Settings opened at the Appearance section (not at the root status card).
settings_appearance() {
    settings_with "$@"; sleep 3
    pos=$(find_text "Appearance") && adb shell input tap $pos
    sleep 2
}

APK=$(find apk-to-test -name "*.apk" | head -n 1)
adb install -r -d -t "$APK" >/dev/null
for p in READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE POST_NOTIFICATIONS; do
    adb shell pm grant $PKG android.permission.$p 2>/dev/null || true
done
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
# Clean status bar: fixed time, full battery and signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

log "=== Demo files ==="
# adb push into a folder that does not exist fails: create them all first.
adb shell mkdir -p /sdcard/DCIM/Camera /sdcard/Movies /sdcard/Music /sdcard/Documents/Projects /sdcard/Download
python3 - "$OUT/fixtures" <<'PY'
import os, sys, colorsys
from PIL import Image, ImageDraw
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
scenes = [("IMG_20261102_181544.jpg", 4000, 3000, 0.58), ("IMG_20261104_074210.jpg", 4000, 3000, 0.08),
          ("IMG_20261109_203015.jpg", 3000, 4000, 0.75), ("IMG_20261112_131902.jpg", 1920, 1080, 0.33),
          ("IMG_20261118_095533.jpg", 4000, 3000, 0.95), ("IMG_20261120_172740.jpg", 2560, 1440, 0.48)]
for name, w, h, hue in scenes:
    img = Image.new("RGB", (w // 4, h // 4))
    d = ImageDraw.Draw(img)
    for y in range(img.height):
        r, g, b = colorsys.hsv_to_rgb((hue + y / img.height * 0.18) % 1, 0.55, 0.95 - y / img.height * 0.45)
        d.line([(0, y), (img.width, y)], fill=(int(r * 255), int(g * 255), int(b * 255)))
    d.ellipse([img.width * 0.55, img.height * 0.12, img.width * 0.8, img.height * 0.12 + img.width * 0.25], fill=(255, 236, 190))
    pts = [(0, img.height)] + [(x, img.height * 0.65 - ((x * 7) % 90) - (x % 53)) for x in range(0, img.width + 40, 40)] + [(img.width, img.height)]
    d.polygon(pts, fill=(30, 40, 60))
    img.resize((w, h)).save(os.path.join(out, name), quality=82)
PY
for f in "$OUT"/fixtures/*.jpg; do adb push "$f" /sdcard/DCIM/Camera/ >/dev/null; done
ffmpeg -loglevel error -f lavfi -i testsrc2=size=3840x2160:rate=30 -t 2 -c:v libx264 -pix_fmt yuv420p "$OUT/fixtures/VID_20261115_210402.mp4"
ffmpeg -loglevel error -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 2 -c:v libx264 -pix_fmt yuv420p "$OUT/fixtures/VID_20261119_120914.mp4"
adb push "$OUT/fixtures/VID_20261115_210402.mp4" /sdcard/DCIM/Camera/ >/dev/null
adb push "$OUT/fixtures/VID_20261119_120914.mp4" /sdcard/DCIM/Camera/ >/dev/null
for t in "Lo-fi Night" "Morning Run" "Deep Focus"; do
    ffmpeg -loglevel error -f lavfi -i "sine=frequency=330:duration=3" -b:a 128k "$OUT/fixtures/$t.mp3"
    adb push "$OUT/fixtures/$t.mp3" "/sdcard/Music/$t.mp3" >/dev/null
done
adb shell mkdir -p /sdcard/Documents/Projects /sdcard/Download
adb push "$APK" /sdcard/Download/OpenManager.apk >/dev/null
printf '#!/system/bin/sh\n# Daily backup of my photos\nSRC=/sdcard/DCIM/Camera\nDST=/sdcard/Backups/$(date +%%F)\nmkdir -p "$DST"\nfor f in "$SRC"/*.jpg; do\n  cp "$f" "$DST/" && echo "copied $(basename "$f")"\ndone\necho "Backup done: $(ls "$DST" | wc -l) files"\n' > "$OUT/fixtures/backup.sh"
printf '# Notes\n\n- Rename holiday photos\n- Sign the release APK\n- Check the VPS logs\n' > "$OUT/fixtures/notes.md"
adb push "$OUT/fixtures/backup.sh" /sdcard/Documents/backup.sh >/dev/null
adb push "$OUT/fixtures/notes.md" /sdcard/Documents/notes.md >/dev/null
python3 - "$OUT/fixtures/Manual.pdf" <<'PY'
import sys
pages = 3
objs = ["<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % (4 + 2 * i) for i in range(pages)), pages),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
streams = {}
for i in range(pages):
    objs.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents %d 0 R /Resources << /Font << /F1 3 0 R >> >> >>" % (5 + 2 * i))
    objs.append(None)
    body = "BT /F1 30 Tf 60 760 Td (OpenManager - User Guide) Tj ET BT /F1 18 Tf 60 720 Td (Chapter %d) Tj ET " % (i + 1)
    body += " ".join("BT /F1 12 Tf 60 %d Td (Dual panels, bookmarks, terminal, VPS files and the APK tools explained step by step.) Tj ET" % (680 - 20 * l) for l in range(28))
    streams[5 + 2 * i] = body.encode()
out = bytearray(b"%PDF-1.4\n"); offsets = []
for n, body in enumerate(objs, 1):
    offsets.append(len(out))
    if body is None:
        st = streams[n]; out += b"%d 0 obj << /Length %d >> stream\n" % (n, len(st)) + st + b"\nendstream endobj\n"
    else:
        out += b"%d 0 obj " % n + body.encode() + b" endobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for o in offsets: out += b"%010d 00000 n \n" % o
out += b"trailer << /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(sys.argv[1], "wb").write(out)
PY
adb push "$OUT/fixtures/Manual.pdf" /sdcard/Documents/Manual.pdf >/dev/null
adb push "$OUT/fixtures/Manual.pdf" /sdcard/Download/Invoice-November.pdf >/dev/null
adb push "$OUT/fixtures/Lo-fi Night.mp3" "/sdcard/Download/Lo-fi Night.mp3" >/dev/null
adb shell "cd /sdcard/Download && echo backup > photos-backup.zip && echo notes > readme.txt"
adb shell "cd /sdcard/Documents && for n in invoice-2026 report-q3; do echo data > \$n.txt; done"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera >/dev/null 2>&1 || true

# First start out of the way (update check, tips).
adb shell am start -W -n "$MAIN" >/dev/null; sleep 6; stop_app

theme() { # theme <dark|amoled|light> <md3 on|off> <color>
    stop_app
    settings_with --es DEMO_THEME "$1" --es DEMO_MD3 "$2" --es DEMO_THEME_COLOR "$3"
    sleep 4; stop_app
}
panels() { # left folder, right folder
    open_dir "$1"; sleep 3
    # A short scroll (never a tap: it could open a file) makes the right panel the active one.
    adb shell input swipe 810 1500 810 1700 200; sleep 1
    open_dir "$2"; sleep 3
    adb shell input swipe 270 1500 270 1700 200; sleep 1
}

log "=== Themes on the main screen ==="
theme dark off color_primary
panels "Documents" "DCIM/Camera"; shot 01_home_dark 4
theme amoled off color_primary
panels "Documents" "DCIM/Camera"; shot 02_home_amoled 4
theme light off color_primary
panels "Documents" "DCIM/Camera"; shot 03_home_light 4

log "=== Material 3 accent colors (settings) ==="
for spec in "light:material_blue:04_md3_blue" "dark:material_red:05_md3_red" "light:material_teal:06_md3_teal" "dark:material_deep_purple:07_md3_purple"; do
    IFS=: read -r t c name <<< "$spec"
    theme "$t" on "$c"
    settings_appearance; shot "$name" 2
done
theme dark off color_primary

log "=== Zones of the app ==="
open_dir "DCIM/Camera"; sleep 3
demo more_menu; sleep 2
pos=$(find_text "Gallery view (this folder)") && adb shell input tap $pos
shot 10_gallery 6
add_bookmark() {
    open_dir "$1"; sleep 3
    demo more_menu; sleep 2
    pos=$(find_text "Add bookmark") && adb shell input tap $pos
    sleep 2
}
add_bookmark "DCIM/Camera"
add_bookmark "Documents/Projects"
add_bookmark "Download"
open_dir "Documents"; sleep 3
adb shell input swipe 540 2180 540 1500 300; shot 11_bookmarks 2
adb shell input keyevent KEYCODE_BACK; sleep 1
demo drawer; shot 12_drawer 2
demo close_drawer; sleep 1
open_dir "Download"; sleep 3
pos=$(find_text "OpenManager.apk") && adb shell input tap $pos
shot 13_apk_dialog 5
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -n "$SMART" -a android.intent.action.VIEW -t text/plain -d file:///sdcard/Documents/backup.sh >/dev/null
shot 14_editor 5
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -n "$SMART" -a android.intent.action.VIEW -t application/pdf -d file:///sdcard/Documents/Manual.pdf >/dev/null
shot 15_pdf 6
stop_app
demo terminal; sleep 6
adb shell input text "ls%s-la%s/sdcard/DCIM/Camera"; adb shell input keyevent KEYCODE_ENTER
shot 16_terminal 4
stop_app
adb shell run-as $PKG touch files/om_debug_demo_heat
adb shell am start -W -n "$MAIN" >/dev/null; sleep 3
demo running; sleep 6
pos=$(node_id btnDismissBanner) && adb shell input tap $pos
shot 17_running_apps 4
adb shell run-as $PKG rm -f files/om_debug_demo_heat
stop_app
settings_appearance; shot 18_settings 2
stop_app

log "=== Languages ==="
for spec in "zh-CN:20_lang_zh" "ru:21_lang_ru" "pt-BR:22_lang_pt" "es:23_lang_es"; do
    loc=${spec%%:*}; name=${spec#*:}
    stop_app
    settings_with --es DEMO_LOCALE "$loc"; sleep 5; stop_app
    panels "Documents" "DCIM/Camera"
    demo drawer; shot "$name" 3
done
stop_app
settings_with --es DEMO_LOCALE reset; sleep 4; stop_app
ls -la "$OUT"/*.png | tee -a "$OUT/log.txt"

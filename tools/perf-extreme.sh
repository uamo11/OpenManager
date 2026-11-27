# Extreme Ultra RAM checks, sourced by perf-probe.sh (uses its log/measure/start_cmp).
# Heavy content in many parts of the app (owner request): big files in every editor, thousands of
# entries, background music, and open/close loops that would show leaks. Each section is measured
# like the main ones; scrolling also records frame times (gfxinfo) and crashes/ANRs are collected.

EXTREME_DIR="$OUT/extreme_data"

extreme_prepare() {
    mkdir -p "$EXTREME_DIR"
    python3 - "$EXTREME_DIR" "$APK_FILE" <<'PY'
import math, os, random, struct, sys, wave, zipfile
d, apk = sys.argv[1], sys.argv[2]
random.seed(7)
# 60 MB binary for the HEX editor.
with open(os.path.join(d, "big.bin"), "wb") as f:
    f.write(b"\x7fELF")
    for _ in range(60):
        f.write(os.urandom(1024 * 1024))
# 9 MB / 150,000 lines of code-like text for the text editor.
with open(os.path.join(d, "big.txt"), "w") as f:
    for i in range(150000):
        f.write("    val item%d = listOf(%d, %d, \"value_%d\") // line %d\n" % (i, i, i * 3, i, i))
# A zip with 5,000 entries for the archive viewer.
with zipfile.ZipFile(os.path.join(d, "many.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for i in range(5000):
        z.writestr("dir_%02d/entry_%05d.txt" % (i % 50, i), "entry %d\n" % i)
# The app's own classes.dex for the DEX editor.
with zipfile.ZipFile(apk) as z:
    open(os.path.join(d, "classes.dex"), "wb").write(z.read("classes.dex"))
# Two minutes of audio for the music player.
with wave.open(os.path.join(d, "song.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
    w.writeframes(b"".join(struct.pack("<h", int(1200 * math.sin(2 * math.pi * 440 * i / 8000)))
                           for i in range(8000 * 120)))
# 300 small PNGs for the image viewer.
import zlib
os.makedirs(os.path.join(d, "pics"), exist_ok=True)
def png(seed):
    raw = b"".join(b"\x00" + bytes((x + y + seed) % 256 for x in range(64 * 3)) for y in range(64))
    def chunk(t, data): return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
for i in range(300):
    open(os.path.join(d, "pics", "img_%03d.png" % i), "wb").write(png(i))
PY
    adb shell rm -rf /sdcard/OM_Extreme
    adb shell mkdir -p /sdcard/OM_Extreme
    for f in big.bin big.txt many.zip classes.dex song.wav; do
        adb push "$EXTREME_DIR/$f" /sdcard/OM_Extreme/ >/dev/null
    done
    adb push "$EXTREME_DIR/pics" /sdcard/OM_Extreme/ >/dev/null
    # 5,000 files in one folder for the explorer.
    adb shell "mkdir -p /sdcard/OM_Extreme/thousands && cd /sdcard/OM_Extreme/thousands && i=0; while [ \$i -lt 5000 ]; do touch f_\$i.dat; i=\$((i+1)); done"
    rm -rf "$EXTREME_DIR"
}

# gfx <mode> <section>: frame-time summary since the last reset.
gfx_note() {
    local g
    g=$(adb shell dumpsys gfxinfo $PKG 2>/dev/null)
    echo "$1,$2,$(echo "$g" | grep -m1 'Total frames rendered' | grep -o '[0-9]*$'),$(echo "$g" | grep -m1 'Janky frames:' | sed 's/.*Janky frames: *//; s/ .*//'),$(echo "$g" | grep -m1 '50th percentile' | grep -o '[0-9]*ms'),$(echo "$g" | grep -m1 '90th percentile' | grep -o '[0-9]*ms'),$(echo "$g" | grep -m1 '99th percentile' | grep -o '[0-9]*ms')" >> "$OUT/frames.csv"
}

flings() {
    local n=$1 i
    for i in $(seq 1 "$n"); do adb shell input swipe 540 1900 540 500 80; sleep 0.3; done
}

uri() { echo "file:///storage/emulated/0/OM_Extreme/$1"; }

run_extreme() {
    local mode="$1"
    [ -f "$OUT/frames.csv" ] || echo "mode,section,frames,janky,p50,p90,p99" > "$OUT/frames.csv"
    log "Extreme sections ($mode)"
    adb logcat -c

    start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "$(uri thousands)"
    sleep 4
    adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
    flings 15
    gfx_note "$mode" "Files 5000 entries"
    LAST_START_MS="-"
    measure "$mode" "X Files 5000 entries"

    start_cmp $PKG/me.zhanghai.android.files.viewer.hex.HexEditorActivity --es "$PKG.extra.PATH_URI" "$(uri big.bin)"
    sleep 3
    adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
    flings 15
    adb shell input swipe 1060 600 1060 1800 1500; sleep 1
    gfx_note "$mode" "HEX 60 MB"
    measure "$mode" "X HEX 60 MB scrolled"

    start_cmp $PKG/me.zhanghai.android.files.viewer.dex.DexEditorActivity --es "$PKG.extra.PATH_URI" "$(uri classes.dex)"
    sleep 12
    adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
    flings 6
    gfx_note "$mode" "DEX classes.dex"
    measure "$mode" "X DEX app classes.dex"

    start_cmp $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity --es "$PKG.extra.PATH_URI" "$(uri big.txt)"
    sleep 6
    adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
    flings 15
    gfx_note "$mode" "Text 9 MB"
    measure "$mode" "X Text editor 9 MB"

    start_cmp $PKG/me.zhanghai.android.files.viewer.image.ImageViewerActivity --es DEMO_IMAGE_DIR /sdcard/OM_Extreme/pics
    sleep 3
    local i
    for i in $(seq 1 20); do adb shell input swipe 900 1200 150 1200 120; sleep 0.4; done
    measure "$mode" "X Image viewer 300 images"

    # The zip: tap it in its folder (it opens inside the app).
    start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity -a android.intent.action.VIEW \
        --es "$PKG.extra.PATH_URI" "file:///storage/emulated/0/OM_Extreme"
    sleep 4
    adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    local zy
    zy=$(adb shell cat /data/local/tmp/ui.xml | tr '>' '\n' | grep 'text="many.zip"' | head -n 1 \
        | sed -E 's/.*bounds="\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\]".*/\1 \2/' | awk '{print int(($1+$2)/2)}')
    if [ -n "$zy" ]; then
        adb shell input tap 300 "$zy"; sleep 5
        adb shell screencap -p /data/local/tmp/zip.png
        adb pull /data/local/tmp/zip.png "$OUT/extreme_zip_$mode.png" >/dev/null 2>&1 || true
        adb shell input tap 300 400; sleep 3
        flings 6
        measure "$mode" "X Zip 5000 entries"
    else
        log "many.zip not found on screen ($mode)"
    fi

    # Music in the background: the window is closed, only the service plays.
    start_cmp $PKG/me.zhanghai.android.files.viewer.audio.AudioPlayerActivity --es "$PKG.extra.PATH_URI" "$(uri song.wav)"
    sleep 4
    adb shell input keyevent KEYCODE_BACK
    LAST_START_MS="-"
    measure "$mode" "X Music in background"
    adb shell input keyevent KEYCODE_MEDIA_STOP

    # Leaks: open and close the heavy editors without restarting the app.
    adb shell am force-stop $PKG
    start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity
    sleep 3
    for i in 1 2 3 4 5; do
        adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.hex.HexEditorActivity --es "$PKG.extra.PATH_URI" "$(uri big.bin)" >/dev/null 2>&1
        sleep 2; flings 3; adb shell input keyevent KEYCODE_BACK; sleep 1
        adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.dex.DexEditorActivity --es "$PKG.extra.PATH_URI" "$(uri classes.dex)" >/dev/null 2>&1
        sleep 6; adb shell input keyevent KEYCODE_BACK; sleep 1
        adb shell am start -W -n $PKG/me.zhanghai.android.files.viewer.text.TextEditorActivity --es "$PKG.extra.PATH_URI" "$(uri big.txt)" >/dev/null 2>&1
        sleep 4; adb shell input keyevent KEYCODE_BACK; sleep 1
    done
    LAST_START_MS="-"
    measure "$mode" "X Files after 15 heavy editors"
    adb shell am force-stop $PKG
    adb logcat -d >> "$OUT/logcat_extreme.txt" 2>/dev/null || true
    adb logcat -c
}

extreme_report() {
    log "Frame times (emulator without GPU: compare between modes and rounds, not with phones)"
    column -s, -t < "$OUT/frames.csv" | tee -a "$REPORT"
    local fatal anr oom
    fatal=$(grep -c "FATAL EXCEPTION" "$OUT/logcat_extreme.txt" || true)
    anr=$(grep -c "ANR in $PKG" "$OUT/logcat_extreme.txt" || true)
    oom=$(grep -c "OutOfMemoryError" "$OUT/logcat_extreme.txt" || true)
    log "Extreme: crashes=$fatal ANRs=$anr OutOfMemoryError=$oom"
    grep -A 20 "FATAL EXCEPTION" "$OUT/logcat_extreme.txt" | head -60 >> "$REPORT" || true
}

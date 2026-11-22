#!/usr/bin/env bash
# Focused Ultra-RAM diagnostic: per-screen memory table with Performance Mode ON/OFF,
# system file-manager baseline, native heap attribution (malloc debug) and Java heap dumps.
set -u

PKG=com.y.om
OUT=perf-probe
mkdir -p "$OUT"
REPORT="$OUT/report.txt"
CSV="$OUT/memory.csv"
: > "$REPORT"
echo "mode,section,start_ms,threads,pss_total_kb,java_heap_kb,native_heap_kb,code_kb,graphics_kb,private_other_kb,system_kb,dalvik_alloc_kb,native_alloc_kb,app_alloc_kb,activities,views" > "$CSV"

log() { echo ">>> $*"; echo ">>> $*" >> "$REPORT"; }

adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 3; done
adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device

APK_FILE=$(find apk-to-test -type f -name "*.apk" | head -n 1)
adb install -r -d -t "$APK_FILE"
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Populate a realistic directory so the explorer has real rows to render.
adb shell "mkdir -p /storage/emulated/0/OM_Probe && cd /storage/emulated/0/OM_Probe && i=0; while [ \$i -lt 400 ]; do touch file_\$i.txt; i=\$((i+1)); done; i=0; while [ \$i -lt 40 ]; do mkdir -p dir_\$i; i=\$((i+1)); done"

# measure <mode> <section> <package>
measure() {
    local mode="$1" section="$2" pkg="${3:-$PKG}"
    sleep 6
    local mi
    mi=$(adb shell dumpsys meminfo "$pkg")
    local spid threads
    spid=$(adb shell pidof "$pkg" | tr -d '\r' | awk '{print $1}')
    threads=$(adb shell ls /proc/$spid/task 2>/dev/null | wc -l | tr -d ' ')
    if [ "$pkg" = "$PKG" ]; then
        adb shell "for t in /proc/$spid/task/*; do cat \$t/comm; done" 2>/dev/null | sed 's/[0-9]*$//' | sort | uniq -c | sort -rn \
            > "$OUT/threads_${mode}_$(echo "$section" | tr -c 'A-Za-z0-9' '_').txt" || true
    fi
    {
        echo "=== [$mode] $section ==="
        echo "$mi" | grep -E "Native Heap|Dalvik Heap|^ *TOTAL|Java Heap:|Native Heap:|Code:|Graphics:|Private Other:|System:|Activities:|Views:"
        echo
    } >> "$REPORT"
    echo "$mi" | awk -v mode="$mode" -v sec="$section" -v start="${LAST_START_MS:--}" -v threads="${threads:-0}" '
        /^ *Native Heap / && !nh { nh=1; nalloc=$(NF-1) }
        /^ *Dalvik Heap / && !dh { dh=1; dalloc=$(NF-1) }
        /^ *TOTAL / && !tt { tt=1; total=$2 }
        /Java Heap:/ { java=$3 }
        /Native Heap:/ { native=$3 }
        /Code:/ { code=$2 }
        /Graphics:/ { gfx=$2 }
        /Private Other:/ { other=$3 }
        /System:/ { sys=$2 }
        /Activities:/ { acts=$NF }
        /Views:/ { views=$2 }
        END { printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", mode, sec, start, threads, total, java, native, code, gfx, other, sys, dalloc, nalloc, dalloc+nalloc, acts, views }
    ' >> "$CSV"
}

LAST_START_MS=""
start_cmp() {
    local out
    out=$(adb shell su 0 am start -W -S -n "$1" "${@:2}" 2>/dev/null) || out=$(adb shell am start -W -S -n "$1" "${@:2}" 2>/dev/null)
    LAST_START_MS=$(echo "$out" | awk -F': ' '/TotalTime/ {gsub("\r", "", $2); print $2}')
}

set_perf() {
    adb shell am start -W -S -n $PKG/me.zhanghai.android.files.settings.SettingsActivity --es DEMO_PERF_MODE "$1" >/dev/null 2>&1
    sleep 3
    adb shell am force-stop $PKG
}

run_sections() {
    local mode="$1"
    start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity
    measure "$mode" "Files (cold)"
    adb shell input swipe 270 1500 270 400 80; sleep 0.4
    adb shell input swipe 270 1500 270 400 80; sleep 0.4
    adb shell input swipe 270 400 270 1500 80
    LAST_START_MS="-"
    measure "$mode" "Files (after scroll)"
    start_cmp $PKG/me.zhanghai.android.files.settings.SettingsActivity
    measure "$mode" "Settings"
    start_cmp $PKG/me.zhanghai.android.files.appmanager.InstalledAppsActivity
    measure "$mode" "Installed Apps"
    start_cmp $PKG/me.zhanghai.android.files.appmanager.running.RunningAppsActivity
    measure "$mode" "Running Apps"
    start_cmp $PKG/me.zhanghai.android.files.terminal.TerminalActivity
    measure "$mode" "Terminal"
    start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor
    measure "$mode" "Editor (+Files behind)"
    start_cmp $PKG/me.zhanghai.android.files.about.AboutActivity
    measure "$mode" "About"
}

log "Baseline: system DocumentsUI (Files) and Settings"
adb shell am start -W -S -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1
measure "baseline" "DocumentsUI" com.android.documentsui
adb shell am start -W -S -a android.settings.SETTINGS >/dev/null 2>&1
measure "baseline" "System Settings" com.android.settings

set_perf off
run_sections "perf-off"
set_perf on
run_sections "perf-on"

log "Native heap attribution (malloc debug backtraces, Performance Mode ON)"
adb shell setprop wrap.$PKG LIBC_DEBUG_MALLOC_OPTIONS=backtrace
for target in filelist.FileListActivity settings.SettingsActivity terminal.TerminalActivity editor; do
    name=${target%%.*}
    adb shell am force-stop $PKG
    if [ "$target" = editor ]; then
        start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor
    else
        start_cmp $PKG/me.zhanghai.android.files.$target
    fi
    sleep 10
    pid=$(adb shell pidof $PKG | tr -d '\r')
    if [ -n "$pid" ]; then
        adb shell am dumpheap -n "$pid" /data/local/tmp/nh_$name.txt
        sleep 5
        adb pull /data/local/tmp/nh_$name.txt "$OUT/native_heap_$name.txt" >/dev/null 2>&1 || true
        python3 tools/native_heap_summary.py "$OUT/native_heap_$name.txt" 30 >> "$REPORT" 2>&1 || echo "(summary failed for $name)" >> "$REPORT"
        echo >> "$REPORT"
    fi
done
adb shell setprop wrap.$PKG "''"
adb shell am force-stop $PKG

log "Java heap dumps + hwui caches (Performance Mode ON)"
for target in filelist.FileListActivity settings.SettingsActivity editor; do
    name=${target%%.*}
    if [ "$target" = editor ]; then
        start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity --es SHOW_DEMO_DIALOG editor
    else
        start_cmp $PKG/me.zhanghai.android.files.$target
    fi
    sleep 6
    pid=$(adb shell pidof $PKG | tr -d '\r')
    adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo_$name.txt" 2>&1 || true
    adb shell am dumpheap "$pid" /data/local/tmp/$name.hprof
    sleep 6
    adb pull /data/local/tmp/$name.hprof "$OUT/$name.hprof" >/dev/null 2>&1 || true
    {
        echo "=== Java heap: $name ==="
        python3 -I tools/hprof_summary.py "$OUT/$name.hprof" 25 android.graphics.Paint android.graphics.Bitmap 2>&1
        echo
        echo "=== hwui caches: $name ==="
        sed -n '/Caches:/,/Pipeline/p' "$OUT/gfxinfo_$name.txt"
        echo
    } >> "$REPORT"
done

set_perf off
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
    log "FATAL EXCEPTION found in logcat"
    grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 >> "$REPORT"
fi

# Device-equivalent "app memory": Java + native allocations minus the emulator-only GL pipe
# buffers (libOpenglSystemCommon via gralloc.ranchu / libEGL_emulation), which do not exist on
# real hardware and are identical for every UI process on this emulator.
EMU_GL_KB=$(python3 -I tools/native_heap_summary.py "$OUT/native_heap_filelist.txt" 50 2>/dev/null \
    | awk '/^--- By owning/{f=1} f && /libOpenglSystemCommon.so/ {printf "%d", $1 * 1024; exit}')
EMU_GL_KB=${EMU_GL_KB:-0}
log "Emulator GL pipe (excluded from device-equivalent): ${EMU_GL_KB} KB"
awk -F, -v emu="$EMU_GL_KB" 'NR==1 {print $0",device_equiv_kb,budget_20mb"; next}
    {d=$14-emu; printf "%s,%d,%s\n", $0, d, (d <= 20480 ? "OK" : "OVER")}' "$CSV" > "$CSV.tmp" && mv "$CSV.tmp" "$CSV"

echo
column -s, -t < "$CSV" | tee -a "$REPORT"

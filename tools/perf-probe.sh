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
echo "mode,section,pss_total_kb,java_heap_kb,native_heap_kb,code_kb,graphics_kb,private_other_kb,system_kb,dalvik_alloc_kb,native_alloc_kb,app_alloc_kb,activities,views" > "$CSV"

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
    {
        echo "=== [$mode] $section ==="
        echo "$mi" | grep -E "Native Heap|Dalvik Heap|^ *TOTAL|Java Heap:|Native Heap:|Code:|Graphics:|Private Other:|System:|Activities:|Views:"
        echo
    } >> "$REPORT"
    echo "$mi" | awk -v mode="$mode" -v sec="$section" '
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
        END { printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", mode, sec, total, java, native, code, gfx, other, sys, dalloc, nalloc, dalloc+nalloc, acts, views }
    ' >> "$CSV"
}

start_cmp() { adb shell su 0 am start -W -S -n "$1" "${@:2}" >/dev/null 2>&1 || adb shell am start -W -S -n "$1" "${@:2}" >/dev/null 2>&1; }

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
for target in filelist.FileListActivity settings.SettingsActivity terminal.TerminalActivity; do
    name=${target%%.*}
    adb shell am force-stop $PKG
    start_cmp $PKG/me.zhanghai.android.files.$target
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

log "Java heap dump + smaps (Performance Mode ON, Files explorer)"
start_cmp $PKG/me.zhanghai.android.files.filelist.FileListActivity
sleep 6
pid=$(adb shell pidof $PKG | tr -d '\r')
adb shell am dumpheap "$pid" /data/local/tmp/files.hprof
sleep 6
adb pull /data/local/tmp/files.hprof "$OUT/files.hprof" >/dev/null 2>&1 || true
adb shell showmap "$pid" > "$OUT/showmap_files.txt" 2>&1 || true
adb shell cat /proc/$pid/smaps_rollup > "$OUT/smaps_rollup_files.txt" 2>&1 || true

set_perf off
adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
    log "FATAL EXCEPTION found in logcat"
    grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 >> "$REPORT"
fi

echo
column -s, -t < "$CSV" | tee -a "$REPORT"

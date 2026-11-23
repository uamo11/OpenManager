/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import java.io.File

/**
 * Safety belt for scripts and compiled binaries run as root from OpenManager while Dead Man or
 * DFRoot is on. The shell guard only sees shell commands; a binary (or `cat x > /dev/block/...`)
 * goes straight to the kernel. Here the program runs in its own mount namespace where:
 *
 * - /dev/block is covered by an empty read-only folder: partitions cannot be opened at all;
 * - /, /system, /vendor, /product... are read-only for that process tree.
 *
 * Nothing changes for the rest of the system, and it all disappears when the program ends. The
 * namespace is checked to be private *before* anything is mounted: if mounts could propagate to the
 * real system the belt is not used (nothing is mounted), so it can never hide /dev/block from the
 * phone itself. It is a seatbelt against accidents, not a sandbox against code written to escape.
 */
object RootSeatbelt {

    const val SCRIPT_NAME = "om_seatbelt.sh"

    /** Exit code when isolation was required but is not possible on this device. */
    const val EXIT_UNAVAILABLE = 126

    private val READ_ONLY_MOUNTS = listOf(
        "/", "/system", "/system_ext", "/vendor", "/product", "/odm", "/oem",
        "/vendor_dlkm", "/odm_dlkm", "/system_dlkm"
    )

    val SCRIPT: String = """
        |#!/system/bin/sh
        |# OpenManager root seatbelt (see RootSeatbelt.kt). Usage: sh om_seatbelt.sh <program> [args...]
        |# OM_SEATBELT_REQUIRED=1: refuse to run (exit $EXIT_UNAVAILABLE) when isolation is not possible.
        |
        |_om_fail() {
        |    echo "[OpenManager] ${'$'}1" >&2
        |    shift
        |    if [ "${'$'}{OM_SEATBELT_REQUIRED:-0}" = "1" ]; then exit $EXIT_UNAVAILABLE; fi
        |    echo "[OpenManager] Running without isolation (the command guard still applies)." >&2
        |    exec "${'$'}@"
        |}
        |
        |if [ "${'$'}1" = "--om-inside" ]; then
        |    shift
        |    # Our mounts must never reach the real system: make everything private, then verify.
        |    mount --make-rprivate / 2>/dev/null || mount -o rprivate / 2>/dev/null
        |    while read -r _id _parent _dev _root _point _opts _rest; do
        |        case "${'$'}_point" in
        |            /|/dev|/dev/block)
        |                case " ${'$'}_rest " in *" shared:"*)
        |                    echo "[OpenManager] Isolation not private here: nothing was changed." >&2
        |                    exit $EXIT_UNAVAILABLE ;;
        |                esac ;;
        |        esac
        |    done < /proc/self/mountinfo
        |    if ! mount -t tmpfs -o ro,nosuid,nodev,noexec,mode=0755,size=4k om_seatbelt /dev/block 2>/dev/null; then
        |        echo "[OpenManager] Could not cover the partitions: nothing was run." >&2
        |        exit $EXIT_UNAVAILABLE
        |    fi
        |    for _om_p in ${READ_ONLY_MOUNTS.joinToString(" ")}; do
        |        [ -d "${'$'}_om_p" ] && mount -o remount,bind,ro "${'$'}_om_p" 2>/dev/null
        |    done
        |    export OM_SEATBELT=1
        |    exec "${'$'}@"
        |fi
        |
        |# Real uid from the kernel (no external tool needed).
        |_om_uid=""
        |while read -r _om_k _om_v _om_rest; do
        |    if [ "${'$'}_om_k" = "Uid:" ]; then _om_uid="${'$'}_om_v"; break; fi
        |done < /proc/self/status
        |[ "${'$'}_om_uid" = "0" ] || exec "${'$'}@"
        |[ "${'$'}{OM_SEATBELT:-0}" = "1" ] && exec "${'$'}@"
        |for _om_u in unshare /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /system/xbin/busybox; do
        |    case "${'$'}_om_u" in
        |        unshare) command -v unshare >/dev/null 2>&1 || continue; _om_cmd="unshare" ;;
        |        *) [ -x "${'$'}_om_u" ] || continue; _om_cmd="${'$'}_om_u unshare" ;;
        |    esac
        |    if ${'$'}_om_cmd -m true 2>/dev/null; then
        |        exec ${'$'}_om_cmd -m /system/bin/sh "${'$'}0" --om-inside "${'$'}@"
        |    fi
        |done
        |_om_fail "No mount-namespace support (unshare) on this device." "${'$'}@"
        |""".trimMargin()

    /** Writes the script next to the shell rc (root reads it from the app's files). */
    fun ensureScript(context: Context): File {
        val file = File(context.filesDir, SCRIPT_NAME)
        if (!file.exists() || file.readText() != SCRIPT) {
            file.writeText(SCRIPT)
        }
        file.setReadable(true, false)
        return file
    }

    /** `sh '<seatbelt>' <command>`: [command] is already shell-quoted. */
    fun wrap(context: Context, command: String, required: Boolean): String {
        val script = ensureScript(context).absolutePath.replace("'", "'\\''")
        val env = if (required) "OM_SEATBELT_REQUIRED=1 " else ""
        return "${env}/system/bin/sh '$script' $command"
    }

    /** What to do with a compiled binary run as root. */
    data class ElfPlan(
        /** Refused: the protections cannot contain what it does. */
        val refuse: Boolean,
        /** Run inside the seatbelt. */
        val isolate: Boolean,
        /** Refuse to run if the seatbelt is not possible on this device. */
        val isolationRequired: Boolean,
        val loadsKernelCode: Boolean,
        val touchesPartitions: Boolean
    )

    private val KERNEL_CODE_SYMBOLS = setOf("init_module", "finit_module", "kexec_load", "kexec_file_load")
    private val MOUNT_SYMBOLS = setOf("mount", "umount", "umount2", "pivot_root")

    /**
     * @param symbols imported/found symbol names (ElfInspector findings).
     * @param systemPaths system/kernel paths found in its strings.
     */
    fun planForElf(symbols: Set<String>, systemPaths: List<String>, deadMan: Boolean, dfroot: Boolean): ElfPlan {
        val kernelCode = symbols.any { it in KERNEL_CODE_SYMBOLS }
        val partitions = systemPaths.any { it.contains("/dev/block") }
        val mounts = symbols.any { it in MOUNT_SYMBOLS }
        val protections = deadMan || dfroot
        return ElfPlan(
            // Kernel code ignores namespaces: DFRoot cannot let it run.
            refuse = dfroot && kernelCode,
            isolate = protections,
            // Without the belt nothing would stop its partition writes or remounts.
            isolationRequired = protections && (partitions || mounts),
            loadsKernelCode = kernelCode,
            touchesPartitions = partitions
        )
    }
}

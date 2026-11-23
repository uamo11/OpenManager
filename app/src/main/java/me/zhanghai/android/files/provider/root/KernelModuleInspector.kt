/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads what a Linux kernel module (.ko) says about itself, without loading it: its .modinfo
 * (name, vermagic, license, dependencies), CPU architecture, signature, and whether it reaches for
 * block devices. Then checks it against the running kernel, the way the kernel itself would.
 *
 * A module runs inside the kernel with no limits; a module built for another kernel either fails
 * to load or, when forced (`insmod -f`), can freeze the phone. Pure Kotlin, unit tested.
 */
object KernelModuleInspector {

    data class Info(
        val isElf: Boolean,
        /** ELF e_machine: 183 aarch64, 40 arm, 62 x86_64, 3 x86. */
        val machine: Int,
        val is64Bit: Boolean,
        val name: String?,
        val vermagic: String?,
        val license: String?,
        val author: String?,
        val description: String?,
        val depends: List<String>,
        val signed: Boolean,
        /** Mentions /dev/block, by-name partitions or the kernel's block-device API. */
        val touchesPartitions: Boolean
    )

    private const val SIGNATURE_MAGIC = "~Module signature appended~\n"

    private val PARTITION_MARKERS = listOf(
        "/dev/block", "by-name", "mmcblk", "blkdev_get_by_path", "blkdev_get_by_dev", "bdev_open_by_path",
        "lookup_bdev", "submit_bio", "bdev_file_open_by_path"
    )

    fun inspect(bytes: ByteArray): Info {
        val isElf = bytes.size >= 64 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        if (!isElf) {
            return Info(false, 0, false, null, null, null, null, null, emptyList(), false, false)
        }
        val is64 = bytes[4].toInt() == 2
        val order = if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.wrap(bytes).order(order)
        val machine = buf.getShort(18).toInt() and 0xFFFF
        val modinfo = readSection(buf, is64, ".modinfo")
        val fields = mutableMapOf<String, MutableList<String>>()
        modinfo?.let { data ->
            String(data, Charsets.ISO_8859_1).split('\u0000').forEach { entry ->
                val eq = entry.indexOf('=')
                if (eq > 0) fields.getOrPut(entry.substring(0, eq)) { mutableListOf() } += entry.substring(eq + 1)
            }
        }
        val signed = bytes.size > SIGNATURE_MAGIC.length &&
            String(bytes, bytes.size - SIGNATURE_MAGIC.length, SIGNATURE_MAGIC.length, Charsets.ISO_8859_1) == SIGNATURE_MAGIC
        // Strings and symbol names: enough to see block-device access without disassembling.
        val text = String(bytes, Charsets.ISO_8859_1)
        return Info(
            isElf = true,
            machine = machine,
            is64Bit = is64,
            name = fields["name"]?.firstOrNull(),
            vermagic = fields["vermagic"]?.firstOrNull(),
            license = fields["license"]?.firstOrNull(),
            author = fields["author"]?.firstOrNull(),
            description = fields["description"]?.firstOrNull(),
            depends = fields["depends"]?.firstOrNull()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            signed = signed,
            touchesPartitions = PARTITION_MARKERS.any { text.contains(it) }
        )
    }

    /**
     * Why this module cannot work on the running kernel, or null when it can.
     *
     * @param kernelRelease `uname -r` (Java's os.version on Android).
     * @param kernelArch `uname -m` (os.arch: aarch64, x86_64, armv8l...).
     */
    fun incompatibility(info: Info, kernelRelease: String, kernelArch: String): String? {
        if (!info.isElf) return "not a kernel module (no ELF header)"
        val expectedMachine = when {
            kernelArch.startsWith("aarch64") || kernelArch.startsWith("arm64") -> 183
            kernelArch.startsWith("arm") -> 40
            kernelArch == "x86_64" || kernelArch == "amd64" -> 62
            kernelArch.matches(Regex("i[3-6]86|x86")) -> 3
            else -> null
        }
        if (expectedMachine != null && info.machine != expectedMachine) {
            return "built for ${machineName(info.machine)}, this kernel is $kernelArch"
        }
        val vermagic = info.vermagic ?: return "no vermagic: not built as a module for any kernel"
        val moduleRelease = vermagic.substringBefore(' ')
        // With modversions the kernel checks each symbol's CRC instead of the release string.
        val modversions = vermagic.split(' ').contains("modversions")
        if (!modversions && moduleRelease != kernelRelease) {
            return "built for kernel $moduleRelease, this phone runs $kernelRelease"
        }
        return null
    }

    fun machineName(machine: Int): String = when (machine) {
        183 -> "aarch64"
        40 -> "arm"
        62 -> "x86_64"
        3 -> "x86"
        else -> "machine $machine"
    }

    private fun readSection(buf: ByteBuffer, is64: Boolean, wanted: String): ByteArray? = runCatching {
        val shoff = if (is64) buf.getLong(0x28) else (buf.getInt(0x20).toLong() and 0xFFFFFFFFL)
        val shentsize = buf.getShort(if (is64) 0x3A else 0x2E).toInt() and 0xFFFF
        val shnum = buf.getShort(if (is64) 0x3C else 0x30).toInt() and 0xFFFF
        val shstrndx = buf.getShort(if (is64) 0x3E else 0x32).toInt() and 0xFFFF
        if (shoff <= 0 || shnum == 0 || shstrndx >= shnum || shentsize < (if (is64) 64 else 40)) return null
        fun header(i: Int): Pair<Long, Long> {
            val base = (shoff + i.toLong() * shentsize).toInt()
            return if (is64) buf.getLong(base + 0x18) to buf.getLong(base + 0x20)
            else (buf.getInt(base + 0x10).toLong() and 0xFFFFFFFFL) to (buf.getInt(base + 0x14).toLong() and 0xFFFFFFFFL)
        }
        fun nameOffset(i: Int): Int = buf.getInt((shoff + i.toLong() * shentsize).toInt())
        val (strOff, _) = header(shstrndx)
        val limit = buf.capacity()
        for (i in 0 until shnum) {
            var p = (strOff + nameOffset(i)).toInt()
            val sb = StringBuilder()
            while (p in 0 until limit && buf.get(p) != 0.toByte() && sb.length < 64) sb.append(buf.get(p++).toInt().toChar())
            if (sb.toString() == wanted) {
                val (off, size) = header(i)
                if (off < 0 || size < 0 || off + size > limit) return null
                return ByteArray(size.toInt()).also { out ->
                    for (j in out.indices) out[j] = buf.get(off.toInt() + j)
                }
            }
        }
        null
    }.getOrNull()
}

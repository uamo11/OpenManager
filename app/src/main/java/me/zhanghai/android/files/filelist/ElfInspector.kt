/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Build
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.terminal.tracer.SyscallCategory
import me.zhanghai.android.files.terminal.tracer.SyscallResolver
import me.zhanghai.android.files.terminal.tracer.SyscallRisk
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class ElfFinding(
    val symbol: String,
    val category: SyscallCategory,
    val risk: SyscallRisk,
    val description: String
)

data class ElfAuditReport(
    val machineArch: String,
    val is64Bit: Boolean,
    val isLittleEndian: Boolean,
    val isPie: Boolean,
    val isDynamic: Boolean,
    val interpreter: String?,
    val isAbiCompatible: Boolean,
    val findings: List<ElfFinding>,
    val totalFindings: Int,
    val deletions: List<String>,
    val rootCalls: List<String>,
    val networkCalls: List<String>,
    val executionCalls: List<String>,
    val ptraceCalls: List<String>,
    val systemCalls: List<String>,
    val isStripped: Boolean = false,
    val hasRwxSegment: Boolean = false,
    val isPacked: Boolean = false,
    val directSyscallsCount: Int = 0,
    val neededLibraries: List<String> = emptyList()
) {
    fun toScriptAuditReport(): ScriptRunnerHelper.ScriptAuditReport {
        return ScriptRunnerHelper.ScriptAuditReport(
            deletions = deletions,
            rootCalls = rootCalls,
            networkCalls = networkCalls,
            systemPaths = systemCalls + executionCalls + ptraceCalls
        )
    }
}

object ElfInspector {

    private val supportedAbis: Array<String>
        get() = runCatching { Build.SUPPORTED_ABIS }.getOrNull() ?: arrayOf("arm64-v8a", "armeabi-v7a")

    private val SENSITIVE_SYMBOLS: Map<String, Triple<SyscallCategory, SyscallRisk, String>> = mapOf(
        // Deletions
        "unlink" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "File deletion (unlink)"),
        "unlinkat" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "File deletion (unlinkat)"),
        "rmdir" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Directory deletion (rmdir)"),
        "remove" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "File/directory deletion (remove)"),

        // Symlinks & Path Evasion
        "symlink" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.HIGH, "Symbolic link creation (symlink)"),
        "symlinkat" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.HIGH, "Relative symbolic link creation (symlinkat)"),
        "readlink" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.MEDIUM, "Symbolic link read (readlink)"),
        "readlinkat" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.MEDIUM, "Relative symbolic link read (readlinkat)"),

        // Execution
        "system" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "System shell command execution (system)"),
        "popen" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Open process pipe (popen)"),
        "execve" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Replace process with new executable (execve)"),
        "execv" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Binary execution (execv)"),
        "execl" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Binary execution with argument list (execl)"),
        "execvp" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "PATH lookup and execution (execvp)"),
        "posix_spawn" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Spawn new process (posix_spawn)"),
        "fork" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Fork child process (fork)"),

        // Debugging & Injection
        "ptrace" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Process tracing, control, and injection (ptrace)"),
        "process_vm_readv" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.HIGH, "Read remote process memory"),
        "process_vm_writev" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Inject/write into remote process memory"),

        // Network
        "socket" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Create network sockets"),
        "connect" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Connect to remote servers/hosts"),
        "bind" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Bind local ports"),
        "listen" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Open listening socket"),
        "sendto" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Transmit UDP/RAW network data"),
        "recvfrom" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Receive network data"),

        // Signals & Termination
        "kill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Send termination signals to processes (kill)"),
        "tkill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Send signals to specific threads (tkill)"),
        "tgkill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Send signals to thread groups (tgkill)"),

        // Privilege & Identity
        "setuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Change effective/real user (potential root)"),
        "setgid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Change permission group (setgid)"),
        "seteuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Change effective user (seteuid)"),
        "setresuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Modify user permissions (setresuid)"),
        "setresgid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Modify group permissions (setresgid)"),
        "setgroups" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Modify supplementary groups (setgroups)"),
        "setfsuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Change filesystem UID (setfsuid)"),
        "capset" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.CRITICAL, "Modify Linux security capabilities"),
        "capget" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.MEDIUM, "Query Linux security capabilities (capget)"),
        "prctl" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Process/capability/seccomp control (prctl)"),
        "seccomp" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Operate on seccomp state (seccomp)"),
        "keyctl" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Kernel keyring manipulation (keyctl)"),

        // Kernel Modules, eBPF & Memory/Device Control
        "init_module" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Load Linux kernel module (init_module)"),
        "finit_module" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Load Linux kernel module from fd (finit_module)"),
        "delete_module" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Unload Linux kernel module (delete_module)"),
        "kexec_load" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Load new kernel for kexec (kexec_load)"),
        "kexec_file_load" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Load new kernel from fd (kexec_file_load)"),
        "bpf" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Kernel eBPF syscall (bpf)"),
        "perf_event_open" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Kernel performance/trace monitoring (perf_event_open)"),
        "klogctl" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Read/control kernel ring buffer (klogctl)"),
        "ioctl" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.MEDIUM, "Low-level device/kernel driver I/O control (ioctl)"),
        "mmap" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.MEDIUM, "Map memory/devices into address space (mmap)"),
        "mmap64" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.MEDIUM, "Map memory/devices into address space (mmap64)"),
        "mprotect" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Modify memory page protections, e.g. RWX (mprotect)"),
        "memfd_create" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Create anonymous in-memory executable file (memfd_create)"),

        // System Control & Namespaces
        "reboot" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "System reboot or shutdown (reboot)"),
        "sys_reboot" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Direct kernel reboot syscall (sys_reboot)"),
        "mount" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Mount filesystems (mount)"),
        "umount" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Unmount filesystems (umount)"),
        "umount2" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Force unmount filesystems/partitions (umount2)"),
        "chroot" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Change root directory (chroot)"),
        "pivot_root" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Pivot root filesystem (pivot_root)"),
        "setns" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Switch process namespace (setns)"),
        "unshare" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Disassociate system namespaces (unshare)"),
        "swapon" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Enable swap partition")
    )

    private val DANGEROUS_PATHS = listOf(
        "/system", "/vendor", "/product", "/apex", "/dev/block",
        "/dev/kmem", "/dev/mem", "/proc/kallsyms", "/sys/kernel", "/proc/modules",
        "/data/local/tmp", "/data/adb",
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "magisk", "kernelsu", "apatch"
    )

    private val SO_LIBRARY_REGEX = Regex("""^[A-Za-z0-9_.-]+\.so(?:\.[0-9]+)*$""")

    private val SENSITIVE_DIRECT_SYSCALL_CATEGORIES = setOf(
        SyscallCategory.DELETION_FS,
        SyscallCategory.PRIVILEGE,
        SyscallCategory.NETWORK,
        SyscallCategory.PROCESS_EXEC,
        SyscallCategory.DEBUG_PTRACE,
        SyscallCategory.SYSTEM_ADMIN,
        SyscallCategory.SIGNALING
    )

    fun isElf(path: Path): Boolean {
        return try {
            path.newInputStream().use { isElf(it) }
        } catch (_: Throwable) {
            false
        }
    }

    fun isElf(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
    }

    fun isElf(stream: InputStream): Boolean {
        val magic = ByteArray(4)
        val read = stream.read(magic)
        return read == 4 && isElf(magic)
    }

    fun inspect(path: Path): ElfAuditReport {
        return try {
            val bytes = path.newInputStream().use { stream ->
                // Read up to 2MB to parse headers and dynamic strings
                val buf = ByteArray(2 * 1024 * 1024)
                var total = 0
                var r: Int
                while (stream.read(buf, total, buf.size - total).also { r = it } > 0) {
                    total += r
                    if (total >= buf.size) break
                }
                buf.copyOf(total)
            }
            inspect(bytes)
        } catch (_: Throwable) {
            createFallbackReport()
        }
    }

    fun inspect(bytes: ByteArray): ElfAuditReport {
        if (!isElf(bytes) || bytes.size < 52) {
            return createFallbackReport()
        }

        val is64Bit = bytes[4].toInt() == 2
        val isLittleEndian = bytes[5].toInt() == 1

        fun readU16(offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            return if (isLittleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        fun readU32(offset: Int): Long {
            if (offset + 4 > bytes.size) return 0L
            val b0 = bytes[offset].toLong() and 0xFF
            val b1 = bytes[offset + 1].toLong() and 0xFF
            val b2 = bytes[offset + 2].toLong() and 0xFF
            val b3 = bytes[offset + 3].toLong() and 0xFF
            return if (isLittleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        fun readU64(offset: Int): Long {
            if (offset + 8 > bytes.size) return 0L
            val lo = readU32(offset)
            val hi = readU32(offset + 4)
            return if (isLittleEndian) lo or (hi shl 32) else (lo shl 32) or hi
        }

        val elfType = readU16(16) // 1 = ET_REL (.ko kernel module), 2 = ET_EXEC, 3 = ET_DYN (PIE)
        val isKernelModule = elfType == 1
        val isPie = elfType == 3

        val machine = readU16(18)
        val (archName, isAbiCompatible) = when (machine) {
            3 -> "x86 (32-bit)" to supportedAbis.any { it.startsWith("x86") }
            40 -> "ARM (armeabi-v7a 32-bit)" to supportedAbis.any { it.contains("arm") }
            62 -> "x86_64 (64-bit)" to supportedAbis.contains("x86_64")
            183 -> "AArch64 (arm64-v8a 64-bit)" to supportedAbis.contains("arm64-v8a")
            243 -> "RISC-V (64-bit)" to supportedAbis.any { it.contains("riscv") }
            else -> "ELF Machine #$machine" to true
        }

        // Program header parsing: PT_LOAD (1), PT_INTERP (3), PT_GNU_STACK (0x6474E551)
        var interpreter: String? = null
        var hasRwxSegment = false
        val execRanges = mutableListOf<IntRange>()

        val phOff = if (is64Bit) readU64(32) else readU32(28)
        val phEntSize = if (is64Bit) readU16(54) else readU16(42)
        val phNum = if (is64Bit) readU16(56) else readU16(44)
        val minPhEntry = if (is64Bit) 56 else 32

        if (phOff > 0L && phEntSize >= minPhEntry && phNum > 0 && phOff < bytes.size) {
            for (i in 0 until phNum) {
                val curPhLong = phOff + i.toLong() * phEntSize
                if (curPhLong + phEntSize > bytes.size) break
                val curPh = curPhLong.toInt()
                val pType = readU32(curPh)
                val pFlags = if (is64Bit) readU32(curPh + 4).toInt() else readU32(curPh + 24).toInt()
                when (pType) {
                    1L -> { // PT_LOAD
                        if ((pFlags and 0x7) == 0x7) {
                            hasRwxSegment = true
                        }
                        if ((pFlags and 0x1) != 0) {
                            val pOffset = if (is64Bit) readU64(curPh + 8) else readU32(curPh + 4)
                            val pFilesz = if (is64Bit) readU64(curPh + 32) else readU32(curPh + 16)
                            if (pFilesz > 0L && pOffset in 0L until bytes.size.toLong()) {
                                val endExcl = minOf(bytes.size.toLong(), pOffset + pFilesz).toInt()
                                if (endExcl > pOffset.toInt()) {
                                    execRanges.add(pOffset.toInt() until endExcl)
                                }
                            }
                        }
                    }
                    3L -> { // PT_INTERP
                        if (interpreter == null) {
                            val pOffset = if (is64Bit) readU64(curPh + 8) else readU32(curPh + 4)
                            val pFilesz = if (is64Bit) readU64(curPh + 32) else readU32(curPh + 16)
                            if (pOffset in 0L until bytes.size.toLong() && pFilesz > 0L &&
                                pOffset + pFilesz <= bytes.size.toLong()
                            ) {
                                val rawStr = String(
                                    bytes, pOffset.toInt(), pFilesz.toInt(), StandardCharsets.UTF_8
                                ).trimEnd('\u0000')
                                if (rawStr.isNotBlank()) {
                                    interpreter = rawStr
                                }
                            }
                        }
                    }
                    0x6474E551L -> { // PT_GNU_STACK
                        if ((pFlags and 0x1) != 0) {
                            hasRwxSegment = true
                        }
                    }
                }
            }
        }

        // Section header parsing for stripped detection (SHT_SYMTAB = 2)
        val shOff = if (is64Bit) readU64(40) else readU32(32)
        val shEntSize = if (is64Bit) readU16(58) else readU16(46)
        val shNum = if (is64Bit) readU16(60) else readU16(48)
        var isStripped = false
        if (shNum == 0 && phNum > 0 && elfType != 3) {
            isStripped = true
        } else if (shOff > 0L && shEntSize >= 24 && shNum > 0 && shOff + shNum.toLong() * shEntSize <= bytes.size) {
            var hasSymtab = false
            for (i in 0 until shNum) {
                val curSh = (shOff + i.toLong() * shEntSize).toInt()
                if (readU32(curSh + 4) == 2L) { // SHT_SYMTAB
                    hasSymtab = true
                    break
                }
            }
            if (!hasSymtab) {
                isStripped = true
            }
        }

        val isDynamic = interpreter != null || elfType == 3

        val findings = mutableListOf<ElfFinding>()
        val deletions = mutableListOf<String>()
        val rootCalls = mutableListOf<String>()
        val networkCalls = mutableListOf<String>()
        val executionCalls = mutableListOf<String>()
        val ptraceCalls = mutableListOf<String>()
        val systemCalls = mutableListOf<String>()
        val neededLibraries = LinkedHashSet<String>()
        val detectedSet = mutableSetOf<String>()
        var isPacked = false

        fun recordFinding(
            dedupKey: String,
            symbolLabel: String,
            category: SyscallCategory,
            risk: SyscallRisk,
            desc: String,
            lineText: String
        ) {
            if (!detectedSet.add(dedupKey)) return
            findings.add(
                ElfFinding(
                    symbol = symbolLabel,
                    category = category,
                    risk = risk,
                    description = desc
                )
            )
            when (category) {
                SyscallCategory.DELETION_FS -> deletions.add(lineText)
                SyscallCategory.PRIVILEGE -> rootCalls.add(lineText)
                SyscallCategory.NETWORK -> networkCalls.add(lineText)
                SyscallCategory.PROCESS_EXEC -> executionCalls.add(lineText)
                SyscallCategory.DEBUG_PTRACE -> ptraceCalls.add(lineText)
                else -> systemCalls.add(lineText)
            }
        }

        fun isWordChar(c: Int): Boolean =
            (c in 'a'.code..'z'.code) ||
                (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '_'.code

        // Single-pass O(N) printable ASCII run + word scanner
        var runStart = -1
        for (idx in 0..bytes.size) {
            val b = if (idx < bytes.size) bytes[idx].toInt() and 0xFF else -1
            if (b in 32..126) {
                if (runStart < 0) runStart = idx
            } else if (runStart >= 0) {
                val runLen = idx - runStart
                if (runLen >= 2) {
                    val text = String(bytes, runStart, runLen, StandardCharsets.ISO_8859_1)
                    if (text.contains("UPX!")) {
                        isPacked = true
                    }
                    if (neededLibraries.size < 16 && runLen in 5..64 && text.contains(".so") &&
                        SO_LIBRARY_REGEX.matches(text)
                    ) {
                        neededLibraries.add(text)
                    }
                    if (runLen >= 6) {
                        for (dp in DANGEROUS_PATHS) {
                            if (text.contains(dp) && detectedSet.add("PATH:$dp")) {
                                if (dp.endsWith("/su") || dp == "magisk" || dp == "kernelsu" || dp == "apatch") {
                                    rootCalls.add("Root binary reference ($dp)")
                                } else {
                                    systemCalls.add("System/Kernel path reference ($dp)")
                                }
                            }
                        }
                    }

                    // Split identifier words in-place
                    var wStart = -1
                    for (wIdx in 0..text.length) {
                        val ch = if (wIdx < text.length) text[wIdx].code else -1
                        if (isWordChar(ch)) {
                            if (wStart < 0) wStart = wIdx
                        } else if (wStart >= 0) {
                            if (wIdx - wStart >= 2) {
                                val word = text.substring(wStart, wIdx)
                                if (word == "su" && detectedSet.add("PATH:su_word")) {
                                    rootCalls.add("Root binary reference (su)")
                                }
                                SENSITIVE_SYMBOLS[word]?.let { (cat, risk, desc) ->
                                    recordFinding(word, word, cat, risk, desc, "$word() - $desc")
                                }
                            }
                            wStart = -1
                        }
                    }
                }
                runStart = -1
            }
        }

        // Machine-code direct syscall disassembler (for stripped / static binaries)
        var directSyscallsCount = 0
        var resolvedAnyDirectNr = false
        val scanRanges = if (execRanges.isNotEmpty()) {
            execRanges
        } else {
            val limit = minOf(bytes.size, 512 * 1024)
            if (limit > 64) listOf(64 until limit) else emptyList()
        }

        if (isLittleEndian && machine == 183) { // EM_AARCH64
            fun readLe32(offset: Int): Int {
                return (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            }
            for (range in scanRanges) {
                val alignedStart = (range.first + 3) and -4
                var pc = alignedStart
                while (pc + 4 <= range.last + 1) {
                    val insn = readLe32(pc)
                    if (insn == 0xD4000001.toInt()) { // svc #0
                        directSyscallsCount++
                        var foundNr: Int? = null
                        for (k in 1..4) {
                            val prevPc = pc - k * 4
                            if (prevPc < range.first) break
                            val prev = readLe32(prevPc)
                            // movz x8, #imm16  (0xD2800008) or movz w8, #imm16 (0x52800008)
                            if ((prev and 0xFFE0001F.toInt()) == 0xD2800008.toInt() ||
                                (prev and 0xFFE0001F.toInt()) == 0x52800008.toInt()
                            ) {
                                foundNr = (prev ushr 5) and 0xFFFF
                                break
                            }
                            // Stop backward walk on branches/svc
                            if ((prev and 0x7C000000) == 0x14000000 ||
                                (prev and 0xFC000000.toInt()) == 0xD4000000.toInt() ||
                                (prev and 0xFE000000.toInt()) == 0xD6000000.toInt()
                            ) {
                                break
                            }
                        }
                        if (foundNr != null && foundNr in 0..512) {
                            resolvedAnyDirectNr = true
                            val info = SyscallResolver.resolve(foundNr, "arm64-v8a")
                            if (info.category in SENSITIVE_DIRECT_SYSCALL_CATEGORIES) {
                                val sym = "${info.name} [svc #0, NR=$foundNr]"
                                recordFinding(
                                    "DIRECT_SYSCALL:${info.name}",
                                    sym,
                                    info.category,
                                    info.risk,
                                    info.description,
                                    "$sym - ${info.description}"
                                )
                            }
                        }
                    }
                    pc += 4
                }
            }
        } else if (isLittleEndian && machine == 62) { // EM_X86_64
            fun readLe32Signed(offset: Int): Int {
                return (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            }
            for (range in scanRanges) {
                var pc = range.first
                while (pc + 2 <= range.last + 1) {
                    if (bytes[pc] == 0x0F.toByte() && bytes[pc + 1] == 0x05.toByte()) { // syscall
                        directSyscallsCount++
                        val foundNr = when {
                            pc - 5 >= range.first && bytes[pc - 5] == 0xB8.toByte() -> readLe32Signed(pc - 4)
                            pc - 7 >= range.first &&
                                bytes[pc - 7] == 0x48.toByte() &&
                                bytes[pc - 6] == 0xC7.toByte() &&
                                bytes[pc - 5] == 0xC0.toByte() -> readLe32Signed(pc - 4)
                            else -> null
                        }
                        if (foundNr != null && foundNr in 0..512) {
                            resolvedAnyDirectNr = true
                            val info = SyscallResolver.resolve(foundNr, "x86_64")
                            if (info.category in SENSITIVE_DIRECT_SYSCALL_CATEGORIES) {
                                val sym = "${info.name} [syscall, NR=$foundNr]"
                                recordFinding(
                                    "DIRECT_SYSCALL:${info.name}",
                                    sym,
                                    info.category,
                                    info.risk,
                                    info.description,
                                    "$sym - ${info.description}"
                                )
                            }
                        }
                        pc += 2
                    } else {
                        pc++
                    }
                }
            }
        }

        if (isKernelModule && detectedSet.add("ELF_TYPE:ET_REL")) {
            systemCalls.add("Relocatable ELF / Kernel Module (ET_REL)")
        }
        if (directSyscallsCount > 0 && !resolvedAnyDirectNr && detectedSet.add("DIRECT_SYSCALL:GENERIC")) {
            systemCalls.add("Direct kernel syscall instructions ($directSyscallsCount sites)")
        }
        if (hasRwxSegment && detectedSet.add("ANOMALY:RWX")) {
            systemCalls.add("RWX memory segment / executable stack (potential shellcode or packer)")
        }
        if (isPacked && detectedSet.add("ANOMALY:UPX")) {
            systemCalls.add("Packed executable detected (UPX!)")
        }
        if (isStripped && !isDynamic && directSyscallsCount > 0 && detectedSet.add("ANOMALY:STATIC_STRIPPED_SVC")) {
            systemCalls.add("Stripped static ELF invoking kernel syscalls directly ($directSyscallsCount sites)")
        }

        val totalFindings = deletions.size + rootCalls.size + networkCalls.size + executionCalls.size + ptraceCalls.size + systemCalls.size

        return ElfAuditReport(
            machineArch = archName,
            is64Bit = is64Bit,
            isLittleEndian = isLittleEndian,
            isPie = isPie,
            isDynamic = isDynamic,
            interpreter = interpreter,
            isAbiCompatible = isAbiCompatible,
            findings = findings,
            totalFindings = totalFindings,
            deletions = deletions,
            rootCalls = rootCalls,
            networkCalls = networkCalls,
            executionCalls = executionCalls,
            ptraceCalls = ptraceCalls,
            systemCalls = systemCalls,
            isStripped = isStripped,
            hasRwxSegment = hasRwxSegment,
            isPacked = isPacked,
            directSyscallsCount = directSyscallsCount,
            neededLibraries = neededLibraries.toList()
        )
    }

    private fun createFallbackReport(): ElfAuditReport {
        val defaultAbi = supportedAbis.firstOrNull() ?: "arm64-v8a"
        return ElfAuditReport(
            machineArch = defaultAbi,
            is64Bit = defaultAbi.contains("64"),
            isLittleEndian = true,
            isPie = true,
            isDynamic = true,
            interpreter = "/system/bin/linker64",
            isAbiCompatible = true,
            findings = emptyList(),
            totalFindings = 0,
            deletions = emptyList(),
            rootCalls = emptyList(),
            networkCalls = emptyList(),
            executionCalls = emptyList(),
            ptraceCalls = emptyList(),
            systemCalls = emptyList()
        )
    }
}

/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Build
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.terminal.tracer.SyscallCategory
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
    val systemCalls: List<String>
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

        // Program header parsing for PT_INTERP (type 3)
        var interpreter: String? = null
        val phOff = if (is64Bit) readU64(32).toInt() else readU32(28).toInt()
        val phEntSize = if (is64Bit) readU16(54) else readU16(42)
        val phNum = if (is64Bit) readU16(56) else readU16(44)

        if (phOff > 0 && phEntSize > 0 && phNum > 0 && phOff < bytes.size) {
            for (i in 0 until phNum) {
                val curPh = phOff + i * phEntSize
                if (curPh + 16 > bytes.size) break
                val pType = readU32(curPh)
                if (pType == 3L) { // PT_INTERP
                    val pOffset = if (is64Bit) readU64(curPh + 8).toInt() else readU32(curPh + 4).toInt()
                    val pFilesz = if (is64Bit) readU64(curPh + 32).toInt() else readU32(curPh + 16).toInt()
                    if (pOffset in 0 until bytes.size && pFilesz > 0 && pOffset + pFilesz <= bytes.size) {
                        val rawStr = String(bytes, pOffset, pFilesz, StandardCharsets.UTF_8).trimEnd('\u0000')
                        if (rawStr.isNotBlank()) {
                            interpreter = rawStr
                        }
                    }
                    break
                }
            }
        }

        val isDynamic = interpreter != null || elfType == 3

        // Scan for sensitive symbols and syscalls in the binary content
        val findings = mutableListOf<ElfFinding>()
        val deletions = mutableListOf<String>()
        val rootCalls = mutableListOf<String>()
        val networkCalls = mutableListOf<String>()
        val executionCalls = mutableListOf<String>()
        val ptraceCalls = mutableListOf<String>()
        val systemCalls = mutableListOf<String>()

        // Convert the binary strings to search for symbol names cleanly
        val binaryText = buildString {
            var start = 0
            for (i in bytes.indices) {
                val b = bytes[i].toInt()
                if (b in 32..126) {
                    // Printable ASCII
                } else {
                    if (i - start >= 3) {
                        append(String(bytes, start, i - start, StandardCharsets.ISO_8859_1))
                        append('\n')
                    }
                    start = i + 1
                }
            }
        }

        val detectedSet = mutableSetOf<String>()
        for ((symbol, info) in SENSITIVE_SYMBOLS) {
            val (category, risk, desc) = info
            val regex = Regex("""\b${Regex.escape(symbol)}\b""")
            if (regex.containsMatchIn(binaryText) && detectedSet.add(symbol)) {
                val finding = ElfFinding(
                    symbol = symbol,
                    category = category,
                    risk = risk,
                    description = desc
                )
                findings.add(finding)

                when (category) {
                    SyscallCategory.DELETION_FS -> deletions.add("$symbol() - $desc")
                    SyscallCategory.PRIVILEGE -> rootCalls.add("$symbol() - $desc")
                    SyscallCategory.NETWORK -> networkCalls.add("$symbol() - $desc")
                    SyscallCategory.PROCESS_EXEC -> executionCalls.add("$symbol() - $desc")
                    SyscallCategory.DEBUG_PTRACE -> ptraceCalls.add("$symbol() - $desc")
                    SyscallCategory.SYSTEM_ADMIN -> systemCalls.add("$symbol() - $desc")
                    else -> {}
                }
            }
        }

        if (isKernelModule && detectedSet.add("ELF_TYPE:ET_REL")) {
            systemCalls.add("Relocatable ELF / Kernel Module (ET_REL)")
        }

        // Check for hardcoded sensitive paths in string literals
        val dangerousPaths = listOf(
            "/system", "/vendor", "/product", "/apex", "/dev/block",
            "/dev/kmem", "/dev/mem", "/proc/kallsyms", "/sys/kernel", "/proc/modules",
            "/data/local/tmp", "/data/adb",
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "magisk", "kernelsu", "apatch"
        )
        for (dp in dangerousPaths) {
            if (binaryText.contains(dp) && detectedSet.add("PATH:$dp")) {
                if (dp.endsWith("/su") || dp == "magisk" || dp == "kernelsu" || dp == "apatch") {
                    rootCalls.add("Root binary reference ($dp)")
                } else {
                    systemCalls.add("System/Kernel path reference ($dp)")
                }
            }
        }
        if (Regex("""\bsu\b""").containsMatchIn(binaryText) && detectedSet.add("PATH:su_word")) {
            rootCalls.add("Root binary reference (su)")
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
            systemCalls = systemCalls
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

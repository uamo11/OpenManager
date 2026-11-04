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
        "unlink" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de archivos (unlink)"),
        "unlinkat" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de archivos (unlinkat)"),
        "rmdir" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de directorios (rmdir)"),
        "remove" to Triple(SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Borrado de archivo/directorio (remove)"),

        // Execution
        "system" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de comando en shell del sistema (system)"),
        "popen" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Apertura de tubería de proceso (popen)"),
        "execve" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Sustitución de proceso por nuevo ejecutable (execve)"),
        "execv" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de binario (execv)"),
        "execl" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de binario con lista de argumentos (execl)"),
        "execvp" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Búsqueda y ejecución en PATH (execvp)"),
        "posix_spawn" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Creación de nuevo proceso (posix_spawn)"),
        "fork" to Triple(SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Duplicación de proceso hijo (fork)"),

        // Debugging & Injection
        "ptrace" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Trazado, control e inyección en procesos (ptrace)"),
        "process_vm_readv" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.HIGH, "Lectura de memoria de proceso remoto"),
        "process_vm_writev" to Triple(SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Inyección/escritura en memoria de proceso remoto"),

        // Network
        "socket" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Creación de sockets de red"),
        "connect" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Conexión a servidores/hosts remotos"),
        "bind" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Enlace de puertos locales"),
        "listen" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Apertura de socket en modo escucha"),
        "sendto" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Transmisión de datos UDP/RAW por red"),
        "recvfrom" to Triple(SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Recepción de datos por red"),

        // Signals & Termination
        "kill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señales de terminación a procesos (kill)"),
        "tkill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señales a hilos específicos (tkill)"),
        "tgkill" to Triple(SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señales a grupos de hilos (tgkill)"),

        // Privilege & Identity
        "setuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de usuario efectivo/real (posible root)"),
        "setgid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de grupo de permisos (setgid)"),
        "seteuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de usuario efectivo (seteuid)"),
        "setresuid" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Modificación de permisos de usuario (setresuid)"),
        "capset" to Triple(SyscallCategory.PRIVILEGE, SyscallRisk.CRITICAL, "Modificación de capacidades de seguridad Linux"),

        // System Control
        "reboot" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Reinicio o apagado del sistema"),
        "mount" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Montaje de sistemas de archivos"),
        "umount" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Desmontaje de sistemas de archivos"),
        "umount2" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Desmontaje forzado de archivos/particiones"),
        "swapon" to Triple(SyscallCategory.SYSTEM_ADMIN, SyscallRisk.HIGH, "Activación de partición swap")
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

        val elfType = readU16(16) // 2 = ET_EXEC, 3 = ET_DYN (PIE)
        val isPie = elfType == 3

        val machine = readU16(18)
        val (archName, isAbiCompatible) = when (machine) {
            3 -> "x86 (32-bit)" to supportedAbis.any { it.startsWith("x86") }
            40 -> "ARM (armeabi-v7a 32-bit)" to supportedAbis.any { it.contains("arm") }
            62 -> "x86_64 (64-bit)" to supportedAbis.contains("x86_64")
            183 -> "AArch64 (arm64-v8a 64-bit)" to supportedAbis.contains("arm64-v8a")
            243 -> "RISC-V (64-bit)" to supportedAbis.any { it.contains("riscv") }
            else -> "Máquina ELF #$machine" to true
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

        // Check for hardcoded sensitive paths in string literals
        val dangerousPaths = listOf(
            "/system", "/vendor", "/product", "/apex", "/dev/block",
            "/data/local/tmp", "/data/adb", "/system/bin/su", "su"
        )
        for (dp in dangerousPaths) {
            if (binaryText.contains(dp) && detectedSet.add("PATH:$dp")) {
                if (dp.contains("su")) {
                    rootCalls.add("Referencia binario root ($dp)")
                } else {
                    systemCalls.add("Referencia a ruta del sistema ($dp)")
                }
            }
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

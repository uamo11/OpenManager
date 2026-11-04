/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.os.Build

enum class SyscallCategory(val displayName: String, val icon: String) {
    FILE_IO("Entrada/Salida", "📄"),
    DELETION_FS("Borrado/Sistema Archivos", "🗑️"),
    PROCESS_EXEC("Ejecución/Procesos", "⚙️"),
    SIGNALING("Señales/Control", "⚡"),
    PRIVILEGE("Privilegios/Root", "👑"),
    DEBUG_PTRACE("Depuración/Inyección", "💉"),
    NETWORK("Red/Sockets", "🌐"),
    SYSTEM_ADMIN("Administración Kernel", "🛠️"),
    MEMORY("Gestión Memoria", "🧠"),
    OTHER("Otros", "ℹ️")
}

enum class SyscallRisk(val levelName: String, val colorHex: String) {
    CRITICAL("CRÍTICO", "#D32F2F"),
    HIGH("ALTO", "#F57C00"),
    MEDIUM("MEDIO", "#FBC02D"),
    LOW("NORMAL", "#388E3C")
}

data class SyscallInfo(
    val number: Int,
    val name: String,
    val category: SyscallCategory,
    val risk: SyscallRisk,
    val description: String
)

object SyscallResolver {

    // ARM64 (aarch64 / arm64-v8a) syscall definitions (asm-generic)
    private val ARM64_SYSCALLS: Map<Int, SyscallInfo> = mapOf(
        0 to SyscallInfo(0, "sys_io_setup", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Configuración de E/S asíncrona"),
        35 to SyscallInfo(35, "sys_unlinkat", SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de archivos o directorios"),
        40 to SyscallInfo(40, "sys_mount", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Montaje de sistemas de archivos"),
        43 to SyscallInfo(43, "sys_statfs", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Estadísticas del sistema de archivos"),
        56 to SyscallInfo(56, "sys_openat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Apertura o creación de archivo/descriptor"),
        57 to SyscallInfo(57, "sys_close", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Cierre de descriptor de archivo"),
        63 to SyscallInfo(63, "sys_read", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Lectura de descriptor de archivo"),
        64 to SyscallInfo(64, "sys_write", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Escritura en descriptor de archivo"),
        67 to SyscallInfo(67, "sys_pread64", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Lectura en offset específico"),
        68 to SyscallInfo(68, "sys_pwrite64", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Escritura en offset específico"),
        78 to SyscallInfo(78, "sys_readlinkat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Lectura de enlace simbólico"),
        79 to SyscallInfo(79, "sys_newfstatat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Consulta de metadatos de archivo (stat)"),
        80 to SyscallInfo(80, "sys_fstat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Consulta de metadatos de descriptor abierto"),
        117 to SyscallInfo(117, "sys_ptrace", SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Control e inyección en otros procesos"),
        129 to SyscallInfo(129, "sys_kill", SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señal de control o terminación"),
        130 to SyscallInfo(130, "sys_tkill", SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señal directa a hilo"),
        131 to SyscallInfo(131, "sys_tgkill", SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señal a grupo de hilos"),
        134 to SyscallInfo(134, "sys_rt_sigaction", SyscallCategory.SIGNALING, SyscallRisk.LOW, "Configuración de manejador de señal"),
        135 to SyscallInfo(135, "sys_rt_sigprocmask", SyscallCategory.SIGNALING, SyscallRisk.LOW, "Modificación de máscara de señales"),
        142 to SyscallInfo(142, "sys_reboot", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Reinicio o apagado del sistema"),
        146 to SyscallInfo(146, "sys_setuid", SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de UID de usuario (posible root)"),
        147 to SyscallInfo(147, "sys_setgid", SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de GID de grupo"),
        148 to SyscallInfo(148, "sys_setresuid", SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de UIDs real, efectivo y guardado"),
        149 to SyscallInfo(149, "sys_getresuid", SyscallCategory.PRIVILEGE, SyscallRisk.LOW, "Consulta de UIDs"),
        172 to SyscallInfo(172, "sys_getpid", SyscallCategory.PROCESS_EXEC, SyscallRisk.LOW, "Obtención de PID del proceso"),
        198 to SyscallInfo(198, "sys_socket", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Creación de socket de red/comunicación"),
        200 to SyscallInfo(200, "sys_bind", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Enlace de socket a puerto"),
        201 to SyscallInfo(201, "sys_listen", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Escucha de conexiones entrantes"),
        202 to SyscallInfo(202, "sys_accept", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Aceptación de conexión entrante"),
        203 to SyscallInfo(203, "sys_connect", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Conexión saliente a servidor/socket"),
        206 to SyscallInfo(206, "sys_sendto", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Envío de paquetes de datos por red"),
        207 to SyscallInfo(207, "sys_recvfrom", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Recepción de paquetes por socket"),
        220 to SyscallInfo(220, "sys_clone", SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Creación de hilo o subproceso"),
        221 to SyscallInfo(221, "sys_execve", SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de un nuevo binario"),
        222 to SyscallInfo(222, "sys_mmap", SyscallCategory.MEMORY, SyscallRisk.LOW, "Mapeo de memoria virtual"),
        226 to SyscallInfo(226, "sys_mprotect", SyscallCategory.MEMORY, SyscallRisk.MEDIUM, "Modificación de permisos de memoria"),
        242 to SyscallInfo(242, "sys_accept4", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Aceptación de socket con flags"),
        260 to SyscallInfo(260, "sys_wait4", SyscallCategory.PROCESS_EXEC, SyscallRisk.LOW, "Espera de terminación de proceso hijo"),
        270 to SyscallInfo(270, "sys_process_vm_readv", SyscallCategory.DEBUG_PTRACE, SyscallRisk.HIGH, "Lectura de memoria de proceso remoto"),
        271 to SyscallInfo(271, "sys_process_vm_writev", SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Escritura en memoria de proceso remoto"),
        276 to SyscallInfo(276, "sys_renameat2", SyscallCategory.DELETION_FS, SyscallRisk.MEDIUM, "Renombrado o sobrescritura atómica"),
        281 to SyscallInfo(281, "sys_execveat", SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de binario por descriptor"),
        435 to SyscallInfo(435, "sys_clone3", SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Creación de proceso avanzada")
    )

    // x86_64 syscall definitions
    private val X86_64_SYSCALLS: Map<Int, SyscallInfo> = mapOf(
        0 to SyscallInfo(0, "sys_read", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Lectura de descriptor"),
        1 to SyscallInfo(1, "sys_write", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Escritura en descriptor"),
        2 to SyscallInfo(2, "sys_open", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Apertura de archivo"),
        3 to SyscallInfo(3, "sys_close", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Cierre de descriptor"),
        9 to SyscallInfo(9, "sys_mmap", SyscallCategory.MEMORY, SyscallRisk.LOW, "Mapeo de memoria"),
        10 to SyscallInfo(10, "sys_mprotect", SyscallCategory.MEMORY, SyscallRisk.MEDIUM, "Protección de memoria"),
        39 to SyscallInfo(39, "sys_getpid", SyscallCategory.PROCESS_EXEC, SyscallRisk.LOW, "Obtención de PID"),
        41 to SyscallInfo(41, "sys_socket", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Creación de socket"),
        42 to SyscallInfo(42, "sys_connect", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Conexión a socket"),
        49 to SyscallInfo(49, "sys_bind", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Enlace de socket a puerto"),
        50 to SyscallInfo(50, "sys_listen", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Escucha en socket"),
        56 to SyscallInfo(56, "sys_clone", SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Creación de hilo/proceso"),
        57 to SyscallInfo(57, "sys_fork", SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Bifurcación de proceso"),
        59 to SyscallInfo(59, "sys_execve", SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de binario"),
        61 to SyscallInfo(61, "sys_wait4", SyscallCategory.PROCESS_EXEC, SyscallRisk.LOW, "Espera de proceso"),
        62 to SyscallInfo(62, "sys_kill", SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señal"),
        87 to SyscallInfo(87, "sys_unlink", SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de archivo"),
        101 to SyscallInfo(101, "sys_ptrace", SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Depuración e inyección"),
        105 to SyscallInfo(105, "sys_setuid", SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de UID"),
        106 to SyscallInfo(106, "sys_setgid", SyscallCategory.PRIVILEGE, SyscallRisk.HIGH, "Cambio de GID"),
        165 to SyscallInfo(165, "sys_mount", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Montaje de sistema de archivos"),
        169 to SyscallInfo(169, "sys_reboot", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Reinicio del sistema"),
        257 to SyscallInfo(257, "sys_openat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Apertura con openat"),
        263 to SyscallInfo(263, "sys_unlinkat", SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación con unlinkat"),
        322 to SyscallInfo(322, "sys_execveat", SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución con execveat")
    )

    // ARM (32-bit armeabi-v7a) syscall definitions
    private val ARM32_SYSCALLS: Map<Int, SyscallInfo> = mapOf(
        3 to SyscallInfo(3, "sys_read", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Lectura de archivo"),
        4 to SyscallInfo(4, "sys_write", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Escritura de archivo"),
        5 to SyscallInfo(5, "sys_open", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Apertura de archivo"),
        6 to SyscallInfo(6, "sys_close", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Cierre de archivo"),
        10 to SyscallInfo(10, "sys_unlink", SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación de archivo"),
        11 to SyscallInfo(11, "sys_execve", SyscallCategory.PROCESS_EXEC, SyscallRisk.HIGH, "Ejecución de binario"),
        20 to SyscallInfo(20, "sys_getpid", SyscallCategory.PROCESS_EXEC, SyscallRisk.LOW, "Obtención de PID"),
        21 to SyscallInfo(21, "sys_mount", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Montaje de filesystem"),
        26 to SyscallInfo(26, "sys_ptrace", SyscallCategory.DEBUG_PTRACE, SyscallRisk.CRITICAL, "Control e inyección"),
        37 to SyscallInfo(37, "sys_kill", SyscallCategory.SIGNALING, SyscallRisk.HIGH, "Envío de señal"),
        88 to SyscallInfo(88, "sys_reboot", SyscallCategory.SYSTEM_ADMIN, SyscallRisk.CRITICAL, "Reinicio del sistema"),
        120 to SyscallInfo(120, "sys_clone", SyscallCategory.PROCESS_EXEC, SyscallRisk.MEDIUM, "Creación de proceso"),
        125 to SyscallInfo(125, "sys_mprotect", SyscallCategory.MEMORY, SyscallRisk.MEDIUM, "Protección de memoria"),
        192 to SyscallInfo(192, "sys_mmap2", SyscallCategory.MEMORY, SyscallRisk.LOW, "Mapeo de memoria"),
        281 to SyscallInfo(281, "sys_socket", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Socket de red"),
        283 to SyscallInfo(283, "sys_connect", SyscallCategory.NETWORK, SyscallRisk.MEDIUM, "Conexión de socket"),
        322 to SyscallInfo(322, "sys_openat", SyscallCategory.FILE_IO, SyscallRisk.LOW, "Apertura con openat"),
        328 to SyscallInfo(328, "sys_unlinkat", SyscallCategory.DELETION_FS, SyscallRisk.CRITICAL, "Eliminación con unlinkat")
    )

    private val NAME_LOOKUP: Map<String, Pair<SyscallCategory, SyscallRisk>> = mapOf(
        // Deletions
        "unlink" to (SyscallCategory.DELETION_FS to SyscallRisk.CRITICAL),
        "unlinkat" to (SyscallCategory.DELETION_FS to SyscallRisk.CRITICAL),
        "rmdir" to (SyscallCategory.DELETION_FS to SyscallRisk.CRITICAL),
        "remove" to (SyscallCategory.DELETION_FS to SyscallRisk.CRITICAL),
        "truncate" to (SyscallCategory.DELETION_FS to SyscallRisk.MEDIUM),
        "ftruncate" to (SyscallCategory.DELETION_FS to SyscallRisk.MEDIUM),
        "rename" to (SyscallCategory.DELETION_FS to SyscallRisk.MEDIUM),
        "renameat" to (SyscallCategory.DELETION_FS to SyscallRisk.MEDIUM),
        "renameat2" to (SyscallCategory.DELETION_FS to SyscallRisk.MEDIUM),

        // Process execution
        "execve" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.HIGH),
        "execveat" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.HIGH),
        "system" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.HIGH),
        "popen" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.HIGH),
        "posix_spawn" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.HIGH),
        "fork" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.MEDIUM),
        "clone" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.MEDIUM),
        "clone3" to (SyscallCategory.PROCESS_EXEC to SyscallRisk.MEDIUM),

        // Signaling & Killing
        "kill" to (SyscallCategory.SIGNALING to SyscallRisk.HIGH),
        "tkill" to (SyscallCategory.SIGNALING to SyscallRisk.HIGH),
        "tgkill" to (SyscallCategory.SIGNALING to SyscallRisk.HIGH),
        "raise" to (SyscallCategory.SIGNALING to SyscallRisk.MEDIUM),

        // Privilege & Root
        "setuid" to (SyscallCategory.PRIVILEGE to SyscallRisk.HIGH),
        "setgid" to (SyscallCategory.PRIVILEGE to SyscallRisk.HIGH),
        "seteuid" to (SyscallCategory.PRIVILEGE to SyscallRisk.HIGH),
        "setegid" to (SyscallCategory.PRIVILEGE to SyscallRisk.HIGH),
        "setresuid" to (SyscallCategory.PRIVILEGE to SyscallRisk.HIGH),
        "capset" to (SyscallCategory.PRIVILEGE to SyscallRisk.CRITICAL),

        // Debugging / Injection
        "ptrace" to (SyscallCategory.DEBUG_PTRACE to SyscallRisk.CRITICAL),
        "process_vm_readv" to (SyscallCategory.DEBUG_PTRACE to SyscallRisk.HIGH),
        "process_vm_writev" to (SyscallCategory.DEBUG_PTRACE to SyscallRisk.CRITICAL),

        // Network
        "socket" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "connect" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "bind" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "listen" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "accept" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "accept4" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "sendto" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),
        "recvfrom" to (SyscallCategory.NETWORK to SyscallRisk.MEDIUM),

        // System Admin
        "mount" to (SyscallCategory.SYSTEM_ADMIN to SyscallRisk.CRITICAL),
        "umount" to (SyscallCategory.SYSTEM_ADMIN to SyscallRisk.CRITICAL),
        "umount2" to (SyscallCategory.SYSTEM_ADMIN to SyscallRisk.CRITICAL),
        "reboot" to (SyscallCategory.SYSTEM_ADMIN to SyscallRisk.CRITICAL),
        "swapon" to (SyscallCategory.SYSTEM_ADMIN to SyscallRisk.HIGH),

        // Memory
        "mprotect" to (SyscallCategory.MEMORY to SyscallRisk.MEDIUM),
        "mmap" to (SyscallCategory.MEMORY to SyscallRisk.LOW),
        "munmap" to (SyscallCategory.MEMORY to SyscallRisk.LOW),
        "brk" to (SyscallCategory.MEMORY to SyscallRisk.LOW),

        // Standard I/O
        "open" to (SyscallCategory.FILE_IO to SyscallRisk.LOW),
        "openat" to (SyscallCategory.FILE_IO to SyscallRisk.LOW),
        "read" to (SyscallCategory.FILE_IO to SyscallRisk.LOW),
        "write" to (SyscallCategory.FILE_IO to SyscallRisk.LOW),
        "close" to (SyscallCategory.FILE_IO to SyscallRisk.LOW)
    )

    private val supportedAbis: Array<String>
        get() = runCatching { Build.SUPPORTED_ABIS }.getOrNull() ?: arrayOf("arm64-v8a", "armeabi-v7a")

    fun resolve(number: Int, targetAbi: String? = null): SyscallInfo {
        val abi = targetAbi ?: (supportedAbis.firstOrNull() ?: "arm64-v8a")
        val map = when {
            abi.contains("arm64") || abi.contains("aarch64") -> ARM64_SYSCALLS
            abi.contains("x86_64") -> X86_64_SYSCALLS
            abi.contains("arm") -> ARM32_SYSCALLS
            else -> ARM64_SYSCALLS
        }
        val found = map[number]
        if (found != null) {
            return found
        }
        return SyscallInfo(
            number = number,
            name = "sys_#$number",
            category = SyscallCategory.OTHER,
            risk = SyscallRisk.LOW,
            description = "Llamada al sistema #$number ($abi)"
        )
    }

    fun resolveByName(name: String): SyscallInfo {
        val clean = name.removePrefix("sys_").removePrefix("__NR_").lowercase()
        val mapped = NAME_LOOKUP[clean]
        if (mapped != null) {
            return SyscallInfo(
                number = -1,
                name = "sys_$clean",
                category = mapped.first,
                risk = mapped.second,
                description = "${mapped.first.displayName} ($clean)"
            )
        }
        return SyscallInfo(
            number = -1,
            name = "sys_$clean",
            category = SyscallCategory.OTHER,
            risk = SyscallRisk.LOW,
            description = "Llamada $name"
        )
    }
}

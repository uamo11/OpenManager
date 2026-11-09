/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException
import me.zhanghai.android.files.util.asFileName
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class PartitionInspectionResult(
    val formatName: String,
    val magicDescription: String,
    val sizeBytes: Long,
    val details: String?
)

object PartitionInspector {

    fun isPartitionPath(path: Path): Boolean {
        val s = path.toString()
        return s.startsWith("/dev/block") || (s.startsWith("/dev/") && !s.startsWith("/dev/pts") && !s.startsWith("/dev/socket"))
    }

    fun inspect(path: Path): PartitionInspectionResult {
        var formatName = "Dispositivo de bloques"
        var magicDesc = "Sin firma reconocida"
        var details: String? = null
        var sizeBytes = 0L

        // 1. Read true partition size
        sizeBytes = getPartitionSize(path)

        // 2. Read first 4096 + 512 bytes for header analysis
        val header = ByteArray(4608)
        var bytesRead = 0
        try {
            path.newInputStream().use { stream ->
                while (bytesRead < header.size) {
                    val r = stream.read(header, bytesRead, header.size - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }
            }
        } catch (_: Throwable) {
            // If direct stream reading fails, try reading via raw root shell / Syscall
            try {
                val fd = Syscall.open(path.toString().toByteString(), OsConstants.O_RDONLY, 0)
                try {
                    val r = Syscall.read(fd, header, 0, header.size)
                    if (r > 0) bytesRead = r
                } finally {
                    Syscall.close(fd)
                }
            } catch (_: Throwable) {}
        }

        if (bytesRead >= 8) {
            // Check Android Boot Image (offset 0: "ANDROID!")
            val magic8 = String(header, 0, 8, StandardCharsets.US_ASCII)
            if (magic8 == "ANDROID!") {
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val headerVersion = if (bytesRead >= 44) buf.getInt(40) else 0
                formatName = "Android Boot Image"
                magicDesc = "ANDROID! (Kernel / Ramdisk)"
                details = when (headerVersion) {
                    0, 1, 2 -> "Header v$headerVersion (Kernel legado / Ramdisk embebido)"
                    3, 4 -> "Header v$headerVersion (GKI / Generic Kernel Image moderno)"
                    else -> "Header Boot Image"
                }
                return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
            }

            // Check Android Verified Boot (offset 0: "AVB0")
            val magic4 = String(header, 0, 4, StandardCharsets.US_ASCII)
            if (magic4 == "AVB0") {
                formatName = "Android Verified Boot (VBMeta)"
                magicDesc = "AVB0 (Firma criptográfica AVB 2.0)"
                details = "Verificación de integridad de arranque seguro"
                return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
            }

            // Check Device Tree Blob (offset 0: 0xD00DFEED big endian)
            if (bytesRead >= 4) {
                val b0 = header[0].toInt() and 0xFF
                val b1 = header[1].toInt() and 0xFF
                val b2 = header[2].toInt() and 0xFF
                val b3 = header[3].toInt() and 0xFF
                if (b0 == 0xD0 && b1 == 0x0D && b2 == 0xFE && b3 == 0xED) {
                    formatName = "Device Tree Blob (DTB / DTBO)"
                    magicDesc = "0xD00DFEED (Hardware Tree)"
                    details = "Árbol de configuración de hardware del kernel"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }
            }

            // Check Android Sparse Image (offset 0: 0xED26FF3A little endian -> 3A FF 26 ED)
            if (bytesRead >= 4) {
                val b0 = header[0].toInt() and 0xFF
                val b1 = header[1].toInt() and 0xFF
                val b2 = header[2].toInt() and 0xFF
                val b3 = header[3].toInt() and 0xFF
                if (b0 == 0x3A && b1 == 0xFF && b2 == 0x26 && b3 == 0xED) {
                    formatName = "Android Sparse Image"
                    magicDesc = "0xED26FF3A (Sparse Chunk)"
                    details = "Imagen de partición comprimida en bloques sparse"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }
            }

            // Check Android OTA Update Payload (offset 0: "CrAU")
            if (magic4 == "CrAU") {
                formatName = "Android OTA Update Payload"
                magicDesc = "CrAU (Payload.bin)"
                details = "Paquete de actualización OTA del sistema"
                return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
            }

            // Check GPT Partition Table (offset 512: "EFI PART")
            if (bytesRead >= 520) {
                val gptMagic = String(header, 512, 8, StandardCharsets.US_ASCII)
                if (gptMagic == "EFI PART") {
                    formatName = "Tabla de particiones GPT"
                    magicDesc = "EFI PART (GUID Partition Table)"
                    details = "Tabla de particiones maestra del almacenamiento"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }
            }

            // Check Super Partition / Logical Partitions Metadata (offset 4096: "0x616c4467" -> 67 44 6c 61 or "0Dal" -> 30 44 61 6c)
            if (bytesRead >= 4100) {
                val b0 = header[4096].toInt() and 0xFF
                val b1 = header[4097].toInt() and 0xFF
                val b2 = header[4098].toInt() and 0xFF
                val b3 = header[4099].toInt() and 0xFF
                if ((b0 == 0x67 && b1 == 0x44 && b2 == 0x6C && b3 == 0x61) ||
                    (b0 == 0x30 && b1 == 0x44 && b2 == 0x61 && b3 == 0x6C)) {
                    formatName = "Android Dynamic Partitions (Super)"
                    magicDesc = "0x616C4467 (liblp Metadata)"
                    details = "Contenedor de particiones dinámicas del sistema (system, vendor, product)"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }
            }

            // Check Filesystems (superblock at offset 1024)
            if (bytesRead >= 1084) {
                // EROFS: offset 1024 has magic 0xE0F5E1E2 (E2 E1 F5 E0)
                val e0 = header[1024].toInt() and 0xFF
                val e1 = header[1025].toInt() and 0xFF
                val e2 = header[1026].toInt() and 0xFF
                val e3 = header[1027].toInt() and 0xFF
                if (e0 == 0xE2 && e1 == 0xE1 && e2 == 0xF5 && e3 == 0xE0) {
                    formatName = "Sistema de archivos EROFS"
                    magicDesc = "0xE0F5E1E2 (Enhanced Read-Only FS)"
                    details = "Partición de sistema de solo lectura de alto rendimiento"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }

                // F2FS: offset 1024 has magic 0xF2F52010 (10 20 F5 F2)
                if (e0 == 0x10 && e1 == 0x20 && e2 == 0xF5 && e3 == 0xF2) {
                    formatName = "Sistema de archivos F2FS"
                    magicDesc = "0xF2F52010 (Flash-Friendly FS)"
                    details = "Partición optimizada para almacenamiento Flash NAND (/data)"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }

                // ext4 / ext3 / ext2: magic at offset 1080 (0x438) is 0xEF53 (53 EF)
                val m0 = header[1080].toInt() and 0xFF
                val m1 = header[1081].toInt() and 0xFF
                if (m0 == 0x53 && m1 == 0xEF) {
                    formatName = "Sistema de archivos ext4"
                    magicDesc = "0xEF53 (Linux ext4/ext3)"
                    details = "Partición formateada con el sistema de archivos estándar de Linux"
                    return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
                }
            }
        }

        // Generic fallback
        if (sizeBytes > 0) {
            details = "Dispositivo de almacenamiento en bloques de bajo nivel"
        }
        return PartitionInspectionResult(formatName, magicDesc, sizeBytes, details)
    }

    private fun getPartitionSize(path: Path): Long {
        // Method 1: Syscall open + lseek SEEK_END
        try {
            val fd = Syscall.open(path.toString().toByteString(), OsConstants.O_RDONLY, 0)
            try {
                val endOffset = Os.lseek(fd, 0L, OsConstants.SEEK_END)
                if (endOffset > 0) {
                    return endOffset
                }
            } finally {
                Syscall.close(fd)
            }
        } catch (_: Throwable) {}

        // Method 2: Resolve symlink and check sysfs /sys/class/block/<dev>/size (sectors * 512)
        try {
            val resolvedPath = try {
                path.toRealPath()
            } catch (_: Throwable) {
                path
            }
            val devName = resolvedPath.fileName?.toString()
            if (!devName.isNullOrEmpty()) {
                val sysfsSizeFile = java.io.File("/sys/class/block/$devName/size")
                if (sysfsSizeFile.exists() && sysfsSizeFile.canRead()) {
                    val sectors = sysfsSizeFile.readText().trim().toLongOrNull() ?: 0L
                    if (sectors > 0) {
                        return sectors * 512L
                    }
                }
            }
        } catch (_: Throwable) {}

        return 0L
    }
}

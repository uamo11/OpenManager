/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object SuperuserDetector {

    private const val TAG = "SuperuserDetector"

    enum class SuperuserType(
        val displayName: String,
        val iconRes: Int,
        val description: String
    ) {
        KERNEL_SU(
            "KernelSU",
            R.drawable.ic_root_kernelsu,
            "Módulo de Kernel con privilegios de superusuario activos"
        ),
        KERNEL_SU_NEXT(
            "KernelSU Next",
            R.drawable.ic_root_kernelsu_next,
            "KernelSU Next con integración avanzada de kernel"
        ),
        MAGISK(
            "Magisk",
            R.drawable.ic_root_magisk,
            "Gestor Magisk estándar con acceso root de sistema"
        ),
        MAGISK_DELTA(
            "Magisk Delta (Kitsune)",
            R.drawable.ic_root_magisk_delta,
            "Magisk Delta / Kitsune Mask con soporte de módulos"
        ),
        APATCH(
            "APatch",
            R.drawable.ic_root_apatch,
            "APatch basado en parches de KernelPatch (kp / apd)"
        ),
        BAKA_SU(
            "Baka-SU",
            R.drawable.ic_root_bakasu,
            "Gestor de superusuario minimalista Baka-SU"
        ),
        DFROOT(
            "DFRoot (Jailbreak)",
            R.drawable.ic_root_dfroot,
            "Herramienta de elevación DFRoot Jailbreak/Exploit"
        ),
        SHIZUKU(
            "Shizuku",
            R.drawable.ic_shizuku,
            "Servidor de privilegios Shizuku conectado vía Binder"
        ),
        NONE(
            "Sin Superusuario",
            R.drawable.shield_icon_white_24dp,
            "Modo estándar con permisos normales de aplicación"
        )
    }

    data class SuperuserStatus(
        val type: SuperuserType,
        val isRootGranted: Boolean,
        val isShizukuReady: Boolean,
        val hasDfRoot: Boolean,
        val suVersion: String?,
        val managerPackage: String?,
        val detectedBinaryPath: String?
    ) {
        val title: String
            get() = when {
                type == SuperuserType.SHIZUKU -> "Shizuku Conectado"
                isRootGranted -> "Superusuario: ${type.displayName}"
                type != SuperuserType.NONE -> "${type.displayName} (No autorizado)"
                else -> "Modo Estándar (Sin Root)"
            }

        val subtitle: String
            get() = when {
                type == SuperuserType.SHIZUKU -> "Acceso elevado mediante Binder API"
                isRootGranted -> "Privilegios administrativos activos para operaciones de sistema"
                type != SuperuserType.NONE -> "Gestor instalado pero permiso no concedido"
                hasDfRoot -> "DFRoot Jailbreak detectado en el dispositivo"
                else -> "Permisos estándar de almacenamiento y aplicación"
            }

        val statusTag: String
            get() = when {
                isRootGranted -> "ROOT"
                isShizukuReady -> "SHIZUKU"
                hasDfRoot -> "EXPLOIT"
                else -> "ESTÁNDAR"
            }
    }

    suspend fun detect(context: Context): SuperuserStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 1. Detección de DFRoot (puede coexistir con otro gestor)
        val hasDfRoot = detectDfRoot(pm)

        // 2. Verificar estado de Shizuku
        val isShizukuReady = try {
            ShizukuManager.ready()
        } catch (_: Throwable) {
            false
        }

        // 3. Verificar si el comando su está disponible
        val isSuAvailable = try {
            LibSuFileServiceLauncher.isSuAvailable()
        } catch (_: Throwable) {
            false
        }

        // 4. Verificar versión de su si está disponible
        var suVersion: String? = null
        var detectedBinary: String? = null
        if (isSuAvailable) {
            suVersion = getSuVersionFast()
        }

        // 5. Identificar tipo de gestor de Superusuario
        val (type, detectedPkg, binaryPath) = identifySuperuserType(pm, suVersion)

        // 6. Verificar si la app tiene permiso root concedido
        val isRootGranted = isSuAvailable && (Shell.isAppGrantedRoot() == true || testRootExecutionSafe())

        // Si no tiene root pero Shizuku está listo, el tipo principal es Shizuku
        val finalType = when {
            isRootGranted -> type
            isSuAvailable && type != SuperuserType.NONE -> type
            isShizukuReady -> SuperuserType.SHIZUKU
            hasDfRoot -> SuperuserType.DFROOT
            type != SuperuserType.NONE -> type
            else -> SuperuserType.NONE
        }

        SuperuserStatus(
            type = finalType,
            isRootGranted = isRootGranted,
            isShizukuReady = isShizukuReady,
            hasDfRoot = hasDfRoot,
            suVersion = suVersion,
            managerPackage = detectedPkg,
            detectedBinaryPath = binaryPath ?: detectedBinary
        )
    }

    private fun identifySuperuserType(
        pm: PackageManager,
        suVersion: String?
    ): Triple<SuperuserType, String?, String?> {
        val vUpper = suVersion?.uppercase() ?: ""

        // A. Comprobación por KernelSU / KernelSU Next
        val hasKsuDriver = File("/dev/kernelsu").exists()
        val hasKsuDir = File("/data/adb/ksu").exists()
        val hasKsuNextDir = File("/data/adb/ksun").exists()
        val hasKsud = File("/system/bin/ksud").exists() || File("/data/adb/ksu/bin/ksud").exists()

        if (vUpper.contains("NEXT") || isPkgInstalled(pm, "org.kernelsu.next") || isPkgInstalled(pm, "com.rifsxd.ksunext") || isPkgInstalled(pm, "org.tiann.kernelsu.next")) {
            return Triple(SuperuserType.KERNEL_SU_NEXT, "org.kernelsu.next", if (hasKsud) "/system/bin/ksud" else "/data/adb/ksu")
        }
        if (hasKsuDriver || hasKsuDir || hasKsud || vUpper.contains("KERNELSU") || isPkgInstalled(pm, "me.weishu.kernelsu")) {
            return Triple(SuperuserType.KERNEL_SU, "me.weishu.kernelsu", if (hasKsuDriver) "/dev/kernelsu" else "/data/adb/ksu")
        }

        // B. Comprobación por APatch
        val hasApatchDriver = File("/dev/apatch").exists()
        val hasApatchDir = File("/data/adb/ap").exists()
        val hasApd = File("/system/bin/apd").exists() || File("/data/adb/ap/bin/apd").exists()

        if (hasApatchDriver || hasApatchDir || hasApd || vUpper.contains("APATCH") || vUpper.contains("APD") || isPkgInstalled(pm, "me.bmax.apatch")) {
            return Triple(SuperuserType.APATCH, "me.bmax.apatch", if (hasApatchDriver) "/dev/apatch" else "/data/adb/ap")
        }

        // C. Comprobación por Magisk Delta / Kitsune
        val hasMagiskDelta = File("/data/adb/magisk/delta").exists()
        if (hasMagiskDelta || vUpper.contains("DELTA") || vUpper.contains("KITSUNE") || isPkgInstalled(pm, "io.github.huskydg.magisk")) {
            return Triple(SuperuserType.MAGISK_DELTA, "io.github.huskydg.magisk", "/data/adb/magisk")
        }

        // D. Comprobación por Magisk Oficial
        val hasMagiskDir = File("/data/adb/magisk").exists()
        if (hasMagiskDir || vUpper.contains("MAGISK") || isPkgInstalled(pm, "com.topjohnwu.magisk")) {
            return Triple(SuperuserType.MAGISK, "com.topjohnwu.magisk", "/data/adb/magisk")
        }

        // E. Comprobación por Baka-SU
        val hasBakaDir = File("/data/adb/bakasu").exists() || File("/system/bin/bakasu").exists()
        if (hasBakaDir || vUpper.contains("BAKA") || isPkgInstalled(pm, "com.shatyuka.bakasu") || isPkgInstalled(pm, "io.github.shatyuka.bakasu")) {
            return Triple(SuperuserType.BAKA_SU, "com.shatyuka.bakasu", "/data/adb/bakasu")
        }

        // F. Comprobación por DFRoot directo
        if (File("/data/local/tmp/dfroot").exists() || isPkgInstalled(pm, "com.diabl0w.dfroot")) {
            return Triple(SuperuserType.DFROOT, "com.diabl0w.dfroot", "/data/local/tmp/dfroot")
        }

        return Triple(SuperuserType.NONE, null, null)
    }

    private fun detectDfRoot(pm: PackageManager): Boolean {
        if (isPkgInstalled(pm, "com.diabl0w.dfroot") ||
            isPkgInstalled(pm, "io.github.diabl0w.dfroot") ||
            isPkgInstalled(pm, "org.dfroot")
        ) {
            return true
        }
        val dfRootFiles = listOf(
            "/data/local/tmp/dfroot",
            "/data/adb/dfroot",
            "/system/xbin/dfroot",
            "/data/local/tmp/dfroot.sh"
        )
        return dfRootFiles.any { File(it).exists() }
    }

    private fun isPkgInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getSuVersionFast(): String? {
        return try {
            val process = ProcessBuilder("su", "-v")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(500, TimeUnit.MILLISECONDS)
            if (completed && process.exitValue() == 0) {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine()?.trim() }
            } else {
                process.destroyForcibly()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun testRootExecutionSafe(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(600, TimeUnit.MILLISECONDS)
            if (completed && process.exitValue() == 0) {
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() ?: "" }
                output.contains("uid=0")
            } else {
                process.destroyForcibly()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}

/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.Context
import android.content.pm.PackageManager
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
        GENERIC_ROOT(
            "Superusuario (su)",
            R.drawable.ic_root_generic,
            "Acceso superusuario activo (gestor con nombre modificado o binario su independiente)"
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
        val isRootEnabledInSettings: Boolean = true,
        val isShizukuReady: Boolean = false,
        val hasDfRoot: Boolean = false,
        val suVersion: String? = null,
        val managerPackage: String? = null,
        val detectedBinaryPath: String? = null
    ) {
        val title: String
            get() = when {
                !isRootEnabledInSettings && (type != SuperuserType.NONE || hasDfRoot) ->
                    "${type.displayName} (Desactivado)"
                !isRootEnabledInSettings && type == SuperuserType.NONE ->
                    "Modo Estándar (Root desactivado)"
                type == SuperuserType.SHIZUKU ->
                    "Shizuku Conectado"
                isRootGranted ->
                    "Superusuario: ${type.displayName}"
                type != SuperuserType.NONE ->
                    "${type.displayName} (No autorizado)"
                hasDfRoot ->
                    "DFRoot (No autorizado)"
                else ->
                    "Modo Estándar (Sin Root)"
            }

        val subtitle: String
            get() = when {
                !isRootEnabledInSettings ->
                    "La solicitud de root está desactivada en Ajustes > Inicio"
                type == SuperuserType.SHIZUKU ->
                    "Acceso elevado mediante Binder API"
                isRootGranted ->
                    "Privilegios administrativos activos para operaciones de sistema"
                type != SuperuserType.NONE ->
                    "Gestor instalado pero permiso no concedido"
                hasDfRoot ->
                    "DFRoot Jailbreak detectado en el dispositivo"
                else ->
                    "Permisos estándar de almacenamiento y aplicación"
            }

        val statusTag: String
            get() = when {
                !isRootEnabledInSettings && (type != SuperuserType.NONE || hasDfRoot) -> "DESACTIVADO"
                isRootGranted -> "ROOT"
                isShizukuReady -> "SHIZUKU"
                hasDfRoot -> "EXPLOIT"
                else -> "ESTÁNDAR"
            }
    }

    suspend fun detect(context: Context): SuperuserStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val isRootEnabledInSettings = try {
            me.zhanghai.android.files.settings.Settings.REQUEST_ROOT_PERMISSION.valueCompat
        } catch (_: Throwable) {
            true
        }

        // 1. Detección de DFRoot (puede coexistir con otro gestor)
        val hasDfRoot = detectDfRoot(pm)

        // 2. Verificar estado de Shizuku
        val isShizukuReady = try {
            ShizukuManager.ready()
        } catch (_: Throwable) {
            false
        }

        // 3. Verificar si el comando su está disponible en el sistema
        val isSuAvailable = try {
            LibSuFileServiceLauncher.isSuAvailable()
        } catch (_: Throwable) {
            false
        }

        // 4. Versión de su (solo si el ajuste de pedir root está activo para evitar invocar su con el toggle apagado)
        var suVersion: String? = null
        if (isSuAvailable && isRootEnabledInSettings) {
            suVersion = getSuVersionFast()
        }

        // 5. Identificar tipo de gestor de Superusuario
        val (identifiedType, detectedPkg, binaryPath) = identifySuperuserType(pm, suVersion, isSuAvailable)

        // 6. Verificar si la app tiene permiso root concedido
        // Si el usuario desactivó la solicitud de root en ajustes, NUNCA ejecutar su ni reportar concedido
        val isRootGranted = if (isRootEnabledInSettings && isSuAvailable) {
            Shell.isAppGrantedRoot() == true || testRootExecutionSafe()
        } else {
            false
        }

        // Si tenemos root concedido o su disponible pero no coincidió con ningún gestor específico,
        // asignar GENERIC_ROOT con icono genérico de Android
        val resolvedType = if (identifiedType == SuperuserType.NONE && (isSuAvailable || isRootGranted)) {
            SuperuserType.GENERIC_ROOT
        } else {
            identifiedType
        }

        // Resolver tipo final
        val finalType = when {
            isRootGranted -> resolvedType
            isSuAvailable && resolvedType != SuperuserType.NONE -> resolvedType
            isShizukuReady -> SuperuserType.SHIZUKU
            resolvedType != SuperuserType.NONE -> resolvedType
            hasDfRoot -> SuperuserType.DFROOT
            else -> SuperuserType.NONE
        }

        SuperuserStatus(
            type = finalType,
            isRootGranted = isRootGranted,
            isRootEnabledInSettings = isRootEnabledInSettings,
            isShizukuReady = isShizukuReady,
            hasDfRoot = hasDfRoot,
            suVersion = suVersion,
            managerPackage = detectedPkg,
            detectedBinaryPath = binaryPath
        )
    }

    internal fun identifySuperuserType(
        pm: PackageManager,
        suVersion: String?,
        isSuAvailable: Boolean = false
    ): Triple<SuperuserType, String?, String?> {
        val vUpper = suVersion?.uppercase() ?: ""

        // A. Comprobación por KernelSU Next / ResuKisu
        val hasKsuDriver = File("/dev/kernelsu").exists()
        val hasKsuDir = File("/data/adb/ksu").exists()
        val hasKsuNextDir = File("/data/adb/ksun").exists()
        val hasKsud = File("/system/bin/ksud").exists() || File("/data/adb/ksu/bin/ksud").exists()

        if (vUpper.contains("NEXT") ||
            vUpper.contains("RESUKISU") ||
            isPkgInstalled(pm, "com.resukisu.resukisu") ||
            isPkgInstalled(pm, "org.kernelsu.next") ||
            isPkgInstalled(pm, "com.rifsxd.ksunext") ||
            isPkgInstalled(pm, "org.tiann.kernelsu.next") ||
            hasKsuNextDir
        ) {
            val pkg = when {
                isPkgInstalled(pm, "com.resukisu.resukisu") -> "com.resukisu.resukisu"
                isPkgInstalled(pm, "com.rifsxd.ksunext") -> "com.rifsxd.ksunext"
                isPkgInstalled(pm, "org.tiann.kernelsu.next") -> "org.tiann.kernelsu.next"
                else -> "org.kernelsu.next"
            }
            return Triple(SuperuserType.KERNEL_SU_NEXT, pkg, if (hasKsud) "/system/bin/ksud" else "/data/adb/ksu")
        }

        // B. Comprobación por KernelSU Oficial
        if (hasKsuDriver || hasKsuDir || hasKsud || vUpper.contains("KERNELSU") || isPkgInstalled(pm, "me.weishu.kernelsu") || isPkgInstalled(pm, "org.kernelsu")) {
            val pkg = if (isPkgInstalled(pm, "org.kernelsu")) "org.kernelsu" else "me.weishu.kernelsu"
            return Triple(SuperuserType.KERNEL_SU, pkg, if (hasKsuDriver) "/dev/kernelsu" else "/data/adb/ksu")
        }

        // C. Comprobación por APatch
        val hasApatchDriver = File("/dev/apatch").exists()
        val hasApatchDir = File("/data/adb/ap").exists()
        val hasApd = File("/system/bin/apd").exists() || File("/data/adb/ap/bin/apd").exists()

        if (hasApatchDriver || hasApatchDir || hasApd || vUpper.contains("APATCH") || vUpper.contains("APD") || isPkgInstalled(pm, "me.bmax.apatch")) {
            return Triple(SuperuserType.APATCH, "me.bmax.apatch", if (hasApatchDriver) "/dev/apatch" else "/data/adb/ap")
        }

        // D. Comprobación por Magisk Delta / Kitsune
        val hasMagiskDelta = File("/data/adb/magisk/delta").exists()
        if (hasMagiskDelta || vUpper.contains("DELTA") || vUpper.contains("KITSUNE") || isPkgInstalled(pm, "io.github.huskydg.magisk")) {
            return Triple(SuperuserType.MAGISK_DELTA, "io.github.huskydg.magisk", "/data/adb/magisk")
        }

        // E. Comprobación por Magisk Oficial
        val hasMagiskDir = File("/data/adb/magisk").exists()
        if (hasMagiskDir || vUpper.contains("MAGISK") || isPkgInstalled(pm, "com.topjohnwu.magisk")) {
            return Triple(SuperuserType.MAGISK, "com.topjohnwu.magisk", "/data/adb/magisk")
        }

        // F. Comprobación por Baka-SU
        val hasBakaDir = File("/data/adb/bakasu").exists() || File("/system/bin/bakasu").exists()
        if (hasBakaDir || vUpper.contains("BAKA") || isPkgInstalled(pm, "com.shatyuka.bakasu") || isPkgInstalled(pm, "io.github.shatyuka.bakasu")) {
            return Triple(SuperuserType.BAKA_SU, "com.shatyuka.bakasu", "/data/adb/bakasu")
        }

        // G. Comprobación por DFRoot directo
        if (isPkgInstalled(pm, "df.root") ||
            isPkgInstalled(pm, "com.df.root") ||
            isPkgInstalled(pm, "org.df.root") ||
            isPkgInstalled(pm, "com.diabl0w.dfroot") ||
            isPkgInstalled(pm, "io.github.diabl0w.dfroot") ||
            isPkgInstalled(pm, "org.dfroot") ||
            File("/data/local/tmp/dfroot").exists()
        ) {
            val pkg = when {
                isPkgInstalled(pm, "df.root") -> "df.root"
                isPkgInstalled(pm, "com.df.root") -> "com.df.root"
                isPkgInstalled(pm, "org.df.root") -> "org.df.root"
                isPkgInstalled(pm, "io.github.diabl0w.dfroot") -> "io.github.diabl0w.dfroot"
                else -> "com.diabl0w.dfroot"
            }
            return Triple(SuperuserType.DFROOT, pkg, "/data/local/tmp/dfroot")
        }

        // H. Comprobación genérica de Superusuario (gestor con nombre modificado o binario su independiente)
        if (isSuAvailable || suVersion != null || File("/system/bin/su").exists() || File("/system/xbin/su").exists() || File("/sbin/su").exists()) {
            val binary = when {
                File("/system/bin/su").exists() -> "/system/bin/su"
                File("/system/xbin/su").exists() -> "/system/xbin/su"
                File("/sbin/su").exists() -> "/sbin/su"
                else -> "su"
            }
            return Triple(SuperuserType.GENERIC_ROOT, null, binary)
        }

        return Triple(SuperuserType.NONE, null, null)
    }

    internal fun detectDfRoot(pm: PackageManager): Boolean {
        if (isPkgInstalled(pm, "df.root") ||
            isPkgInstalled(pm, "com.df.root") ||
            isPkgInstalled(pm, "org.df.root") ||
            isPkgInstalled(pm, "com.diabl0w.dfroot") ||
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

    internal fun isPkgInstalled(pm: PackageManager, packageName: String): Boolean {
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

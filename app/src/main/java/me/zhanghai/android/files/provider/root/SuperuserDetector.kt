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
import me.zhanghai.android.files.util.valueCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object SuperuserDetector {

    private const val TAG = "SuperuserDetector"

    @Volatile
    var cachedStatus: SuperuserStatus? = null

    fun invalidateCache() {
        cachedStatus = null
        try {
            LibSuFileServiceLauncher.resetCooldown()
        } catch (_: Throwable) {}
        try {
            if (Shell.isAppGrantedRoot() != true) {
                Shell.getCachedShell()?.close()
            }
        } catch (_: Throwable) {}
    }

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
            R.drawable.ic_shizuku_logo,
            "Servidor de privilegios Shizuku conectado vía Binder"
        ),
        NONE(
            "Sin Superusuario",
            R.drawable.ic_shield_locked,
            "Modo estándar con permisos normales de aplicación"
        )
    }

    data class SuperuserStatus(
        val type: SuperuserType,
        val isRootGranted: Boolean,
        val isRootEnabledInSettings: Boolean = true,
        val isShizukuReady: Boolean = false,
        val isShizukuAvailable: Boolean = false,
        val isShizukuExecutionEnabled: Boolean = false,
        val shizukuUid: Int = -1,
        val shizukuVersion: Int = -1,
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
                    if (shizukuUid >= 0) "Shizuku Conectado (${if (shizukuUid == 0) "Root" else "ADB"})" else "Shizuku Conectado"
                isRootGranted && isShizukuReady ->
                    "Superusuario: ${type.displayName} + Shizuku"
                isRootGranted ->
                    "Superusuario: ${type.displayName}"
                type != SuperuserType.NONE ->
                    "${type.displayName} (No autorizado)"
                hasDfRoot ->
                    "DFRoot (No autorizado)"
                isShizukuAvailable ->
                    "Shizuku (Sin permiso de ejecución)"
                else ->
                    "Modo Estándar (Sin Root)"
            }

        val subtitle: String
            get() = when {
                !isRootEnabledInSettings ->
                    "La solicitud de root está desactivada en Ajustes > Inicio"
                type == SuperuserType.SHIZUKU ->
                    "Acceso elevado mediante Binder API (${if (shizukuUid == 0) "Modo Root" else "Modo ADB Shell"})"
                isRootGranted && isShizukuReady ->
                    "Privilegios Root y servidor Shizuku activos simultáneamente"
                isRootGranted ->
                    "Privilegios administrativos activos para operaciones de sistema"
                type != SuperuserType.NONE ->
                    "Gestor instalado pero permiso no concedido"
                hasDfRoot ->
                    "DFRoot Jailbreak detectado en el dispositivo"
                isShizukuAvailable ->
                    "Servidor Shizuku detectado pero falta autorizar permiso de ejecución"
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

    suspend fun detect(context: Context, forceRefresh: Boolean = false): SuperuserStatus = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            invalidateCache()
        }
        val cached = cachedStatus
        if (!forceRefresh && cached != null) {
            return@withContext cached
        }
        val pm = context.packageManager
        val isRootEnabledInSettings = try {
            me.zhanghai.android.files.settings.Settings.REQUEST_ROOT_PERMISSION.valueCompat
        } catch (_: Throwable) {
            true
        }

        // 1. Detección de DFRoot (puede coexistir con otro gestor)
        val hasDfRoot = detectDfRoot(pm)

        // 2. Verificar estado de Shizuku
        val isShizukuExecutionEnabled = try {
            me.zhanghai.android.files.settings.Settings.REQUEST_SHELL_PERMISSION.valueCompat
        } catch (_: Throwable) {
            false
        }
        val isShizukuAvailable = try {
            ShizukuManager.isAvailable()
        } catch (_: Throwable) {
            false
        }
        val isShizukuReady = try {
            ShizukuManager.ready()
        } catch (_: Throwable) {
            false
        }
        val shizukuUid = if (isShizukuReady) ShizukuManager.getUid() else -1
        val shizukuVersion = if (isShizukuAvailable) ShizukuManager.getVersion() else -1

        // 3. Versión de su (no requiere privilegios root, devuelve identificación del gestor)
        val suVersion: String? = if (isRootEnabledInSettings) {
            getSuVersionFast()
        } else {
            null
        }

        // 4. Verificar si la app tiene permiso root concedido
        val isRootGranted = if (isRootEnabledInSettings) {
            testRootExecutionSafe()
        } else {
            false
        }

        // 5. Verificar si el comando su está disponible en el sistema
        val isSuAvailable = isRootGranted || suVersion != null || try {
            LibSuFileServiceLauncher.isSuAvailable()
        } catch (_: Throwable) {
            false
        }

        // 6. Identificar tipo de gestor de Superusuario
        val (identifiedType, detectedPkg, binaryPath) = identifySuperuserType(pm, suVersion, isSuAvailable)

        // Resolver tipo adecuado:
        // Si no coincidió con un gestor específico (identifiedType == NONE), pero DFRoot está activo -> DFROOT
        // Si no, si tiene root o su disponible -> GENERIC_ROOT
        val resolvedType = when {
            identifiedType != SuperuserType.NONE -> identifiedType
            hasDfRoot -> SuperuserType.DFROOT
            isRootGranted || isSuAvailable -> SuperuserType.GENERIC_ROOT
            else -> SuperuserType.NONE
        }

        // Resolver tipo final
        val finalType = when {
            isRootGranted -> resolvedType
            resolvedType != SuperuserType.NONE -> resolvedType
            isShizukuReady -> SuperuserType.SHIZUKU
            hasDfRoot -> SuperuserType.DFROOT
            else -> SuperuserType.NONE
        }

        val status = SuperuserStatus(
            type = finalType,
            isRootGranted = isRootGranted,
            isRootEnabledInSettings = isRootEnabledInSettings,
            isShizukuReady = isShizukuReady,
            isShizukuAvailable = isShizukuAvailable,
            isShizukuExecutionEnabled = isShizukuExecutionEnabled,
            shizukuUid = shizukuUid,
            shizukuVersion = shizukuVersion,
            hasDfRoot = hasDfRoot,
            suVersion = suVersion,
            managerPackage = detectedPkg,
            detectedBinaryPath = binaryPath
        )
        cachedStatus = status
        status
    }

    internal fun safeFileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Throwable) {
        false
    }

    internal fun identifySuperuserType(
        pm: PackageManager,
        suVersion: String?,
        isSuAvailable: Boolean = false
    ): Triple<SuperuserType, String?, String?> {
        val vUpper = suVersion?.uppercase() ?: ""

        // A. Comprobación por KernelSU Next / ResuKisu
        val hasKsuDriver = safeFileExists("/dev/kernelsu")
        val hasKsuDir = safeFileExists("/data/adb/ksu")
        val hasKsuBinDir = safeFileExists("/data/adb/ksu/bin")
        val hasKsuNextDir = safeFileExists("/data/adb/ksun")
        val hasKsud = safeFileExists("/system/bin/ksud") || safeFileExists("/data/adb/ksu/bin/ksud") || hasKsuBinDir

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
            val bin = when {
                safeFileExists("/data/adb/ksu/bin/ksud") -> "/data/adb/ksu/bin/ksud"
                hasKsuBinDir -> "/data/adb/ksu/bin"
                safeFileExists("/system/bin/ksud") -> "/system/bin/ksud"
                else -> "/data/adb/ksu"
            }
            return Triple(SuperuserType.KERNEL_SU_NEXT, pkg, bin)
        }

        // B. Comprobación por KernelSU Oficial
        if (hasKsuDriver || hasKsuDir || hasKsuBinDir || hasKsud || vUpper.contains("KERNELSU") || isPkgInstalled(pm, "me.weishu.kernelsu") || isPkgInstalled(pm, "org.kernelsu")) {
            val pkg = if (isPkgInstalled(pm, "org.kernelsu")) "org.kernelsu" else "me.weishu.kernelsu"
            val bin = when {
                safeFileExists("/data/adb/ksu/bin/ksud") -> "/data/adb/ksu/bin/ksud"
                hasKsuBinDir -> "/data/adb/ksu/bin"
                safeFileExists("/system/bin/ksud") -> "/system/bin/ksud"
                hasKsuDriver -> "/dev/kernelsu"
                else -> "/data/adb/ksu"
            }
            return Triple(SuperuserType.KERNEL_SU, pkg, bin)
        }

        // C. Comprobación por APatch
        val hasApatchDriver = safeFileExists("/dev/apatch")
        val hasApatchDir = safeFileExists("/data/adb/ap")
        val hasApd = safeFileExists("/system/bin/apd") || safeFileExists("/data/adb/ap/bin/apd")

        if (hasApatchDriver || hasApatchDir || hasApd || vUpper.contains("APATCH") || vUpper.contains("APD") || isPkgInstalled(pm, "me.bmax.apatch")) {
            return Triple(SuperuserType.APATCH, "me.bmax.apatch", if (hasApatchDriver) "/dev/apatch" else "/data/adb/ap")
        }

        // D. Comprobación por Magisk Delta / Kitsune
        val hasMagiskDelta = safeFileExists("/data/adb/magisk/delta")
        if (hasMagiskDelta || vUpper.contains("DELTA") || vUpper.contains("KITSUNE") || isPkgInstalled(pm, "io.github.huskydg.magisk")) {
            return Triple(SuperuserType.MAGISK_DELTA, "io.github.huskydg.magisk", "/data/adb/magisk")
        }

        // E. Comprobación por Magisk Oficial
        val hasMagiskDir = safeFileExists("/data/adb/magisk")
        if (hasMagiskDir || vUpper.contains("MAGISK") || isPkgInstalled(pm, "com.topjohnwu.magisk")) {
            return Triple(SuperuserType.MAGISK, "com.topjohnwu.magisk", "/data/adb/magisk")
        }

        // F. Comprobación por Baka-SU
        val hasBakaDir = safeFileExists("/data/adb/bakasu") || safeFileExists("/system/bin/bakasu")
        if (hasBakaDir || vUpper.contains("BAKA") || isPkgInstalled(pm, "com.shatyuka.bakasu") || isPkgInstalled(pm, "io.github.shatyuka.bakasu")) {
            return Triple(SuperuserType.BAKA_SU, "com.shatyuka.bakasu", "/data/adb/bakasu")
        }

        // G. Comprobación por DFRoot directo o versión de su con firma DFRoot
        if (vUpper.contains("DFROOT") || vUpper.contains("DIRTYFLAG") || vUpper.contains("DF-ROOT") || detectDfRoot(pm)) {
            val pkg = when {
                isPkgInstalled(pm, "df.root") -> "df.root"
                isPkgInstalled(pm, "com.df.root") -> "com.df.root"
                isPkgInstalled(pm, "org.df.root") -> "org.df.root"
                isPkgInstalled(pm, "com.diabl0w.dfroot") -> "com.diabl0w.dfroot"
                isPkgInstalled(pm, "io.github.diabl0w.dfroot") -> "io.github.diabl0w.dfroot"
                isPkgInstalled(pm, "org.dfroot") -> "org.dfroot"
                isPkgInstalled(pm, "com.dfroot") -> "com.dfroot"
                isPkgInstalled(pm, "com.dirtyflag") -> "com.dirtyflag"
                isPkgInstalled(pm, "org.dirtyflag") -> "org.dirtyflag"
                isPkgInstalled(pm, "com.dfroot.manager") -> "com.dfroot.manager"
                else -> "df.root"
            }
            val bin = when {
                safeFileExists("/data/local/tmp/dfroot") -> "/data/local/tmp/dfroot"
                safeFileExists("/data/local/tmp/dirtyflag") -> "/data/local/tmp/dirtyflag"
                safeFileExists("/system/bin/dfroot") -> "/system/bin/dfroot"
                else -> "/data/local/tmp/dfroot"
            }
            return Triple(SuperuserType.DFROOT, pkg, bin)
        }

        // H. Comprobación genérica de Superusuario (gestor con nombre modificado o binario su independiente)
        if (isSuAvailable || suVersion != null || safeFileExists("/system/bin/su") || safeFileExists("/system/xbin/su") || safeFileExists("/sbin/su")) {
            val binary = when {
                safeFileExists("/system/bin/su") -> "/system/bin/su"
                safeFileExists("/system/xbin/su") -> "/system/xbin/su"
                safeFileExists("/sbin/su") -> "/sbin/su"
                else -> "su"
            }
            return Triple(SuperuserType.GENERIC_ROOT, null, binary)
        }

        return Triple(SuperuserType.NONE, null, null)
    }

    fun detectDfRoot(pm: PackageManager? = null): Boolean {
        // 1. Check cached status if available
        val cached = cachedStatus
        if (cached != null && (cached.hasDfRoot || cached.type == SuperuserType.DFROOT)) {
            return true
        }

        // 2. Package Manager detection (DFRoot & DirtyFlag known packages)
        if (pm != null) {
            val knownPkgs = listOf(
                "df.root", "com.df.root", "org.df.root",
                "com.diabl0w.dfroot", "io.github.diabl0w.dfroot", "org.dfroot",
                "com.dfroot", "me.dfroot", "com.df.jailbreak",
                "com.dirtyflag", "org.dirtyflag", "me.dirtyflag", "com.diablo.dirtyflag",
                "com.jailbreak.dfroot", "net.dfroot", "com.dfroot.manager", "org.dfroot.manager"
            )
            if (knownPkgs.any { isPkgInstalled(pm, it) }) {
                return true
            }
        }

        // 3. Known binaries and script drops
        val dfRootFiles = listOf(
            "/data/local/tmp/dfroot",
            "/data/local/tmp/dfroot.sh",
            "/data/local/tmp/dirtyflag",
            "/data/local/tmp/dirty_flag",
            "/data/local/tmp/dirty_flag.sh",
            "/data/local/tmp/df",
            "/data/local/tmp/rootshell",
            "/data/local/tmp/dfsu",
            "/system/bin/dfroot",
            "/system/xbin/dfroot"
        )
        if (dfRootFiles.any { safeFileExists(it) }) {
            return true
        }

        // 4. System properties set by dirtyflag or dfroot
        val dfProp = getSystemProperty("ro.boot.dirtyflag")
        if (dfProp.isNotEmpty() && dfProp != "0") return true
        val dfRootProp = getSystemProperty("ro.dfroot")
        if (dfRootProp.isNotEmpty() && dfRootProp != "0") return true
        val dfSysProp = getSystemProperty("sys.dfroot")
        if (dfSysProp.isNotEmpty() && dfSysProp != "0") return true
        val dfPersistProp = getSystemProperty("persist.dfroot")
        if (dfPersistProp.isNotEmpty() && dfPersistProp != "0") return true
        val dfDirtyProp = getSystemProperty("ro.dirtyflag")
        if (dfDirtyProp.isNotEmpty() && dfDirtyProp != "0") return true
        val dfVerProp = getSystemProperty("dfroot.version")
        if (dfVerProp.isNotEmpty() && dfVerProp != "0") return true

        return false
    }

    fun isKernelSuDetected(pm: PackageManager? = null): Boolean {
        // 1. Check cached status if available
        val cached = cachedStatus
        if (cached != null && (cached.type == SuperuserType.KERNEL_SU || cached.type == SuperuserType.KERNEL_SU_NEXT)) {
            return true
        }

        // 2. Driver nodes & adb folders
        val hasKsuDriver = safeFileExists("/dev/kernelsu") || safeFileExists("/dev/ksunext")
        val hasKsuDir = safeFileExists("/data/adb/ksu") || safeFileExists("/data/adb/ksun")
        val hasKsuBinDir = safeFileExists("/data/adb/ksu/bin")
        val hasKsud = safeFileExists("/system/bin/ksud") || safeFileExists("/data/adb/ksu/bin/ksud") || hasKsuBinDir
        if (hasKsuDriver || hasKsuDir || hasKsud) {
            return true
        }

        // 3. Manager packages
        if (pm != null) {
            val ksuPkgs = listOf(
                "me.weishu.kernelsu", "org.kernelsu", "com.resukisu.resukisu",
                "org.kernelsu.next", "com.rifsxd.ksunext", "org.tiann.kernelsu.next"
            )
            if (ksuPkgs.any { isPkgInstalled(pm, it) }) {
                return true
            }
        }

        // 4. System property
        val suVer = getSystemProperty("ro.kernel.su")
        if (suVer.contains("KernelSU", ignoreCase = true)) return true

        return false
    }

    internal fun getSystemProperty(key: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val getMethod = c.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
                val line = BufferedReader(InputStreamReader(process.inputStream)).readLine()
                line?.trim() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
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
        val candidates = listOf(
            arrayOf("su", "-v"),
            arrayOf("/system/bin/su", "-v"),
            arrayOf("/system/xbin/su", "-v"),
            arrayOf("/sbin/su", "-v"),
            arrayOf("/data/local/tmp/su", "-v"),
            arrayOf("/data/local/tmp/dfroot", "-v")
        )
        for (cmd in candidates) {
            try {
                val process = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(500, TimeUnit.MILLISECONDS)
                if (completed && process.exitValue() == 0) {
                    val version = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine()?.trim() }
                    if (!version.isNullOrBlank()) {
                        return version
                    }
                } else {
                    process.destroyForcibly()
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun testRootExecutionSafe(): Boolean {
        if (try { Shell.isAppGrantedRoot() == true } catch (_: Throwable) { false }) {
            return true
        }
        val suCommands = listOf(
            arrayOf("su", "-c", "id"),
            arrayOf("/system/bin/su", "-c", "id"),
            arrayOf("/system/xbin/su", "-c", "id"),
            arrayOf("/sbin/su", "-c", "id"),
            arrayOf("/data/local/tmp/su", "-c", "id"),
            arrayOf("/data/local/tmp/dfroot", "-c", "id")
        )
        for (cmd in suCommands) {
            try {
                val process = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(1500, TimeUnit.MILLISECONDS)
                if (completed && process.exitValue() == 0) {
                    val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                    val isRoot = output.contains("uid=0(root)") ||
                        output.contains("uid=0 ") ||
                        output.contains("uid=0\n") ||
                        output.trim() == "uid=0" ||
                        (output.contains("root") && output.contains("uid=0"))
                    if (isRoot) {
                        try {
                            if (Shell.isAppGrantedRoot() != true) {
                                Shell.getCachedShell()?.close()
                            }
                        } catch (_: Throwable) {}
                        return true
                    }
                } else {
                    process.destroyForcibly()
                }
            } catch (_: Throwable) {}
        }
        try {
            if (Shell.isAppGrantedRoot() != true) {
                Shell.getCachedShell()?.close()
            }
        } catch (_: Throwable) {}
        return false
    }
}

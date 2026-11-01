/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.pm.PackageManager
import android.os.Environment
import android.system.Os
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.createFallbackFileItem
import me.zhanghai.android.files.file.createFastVirtualFileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.LinuxPath
import java.io.File

/**
 * Resolves accessible or known subdirectories and files when browsing restricted
 * Android paths without root privileges, matching MT Manager's unprivileged directory view.
 */
object UnprivilegedDirectoryResolver {

    @Volatile
    private var cachedInstalledPackages: List<String> = emptyList()

    @Volatile
    private var lastPackageCacheTimeMs: Long = 0L

    private const val PACKAGE_CACHE_TTL_MS = 30_000L

    private fun getInstalledPackagesCached(): List<String> {
        val now = System.currentTimeMillis()
        val current = cachedInstalledPackages
        if (current.isNotEmpty() && now - lastPackageCacheTimeMs < PACKAGE_CACHE_TTL_MS) {
            return current
        }
        return try {
            val pkgs = application.packageManager.getInstalledApplications(0)
                .mapNotNull { it.packageName }
            cachedInstalledPackages = pkgs
            lastPackageCacheTimeMs = now
            pkgs
        } catch (_: Throwable) {
            current
        }
    }

    data class VirtualEntry(
        val name: String,
        val type: BasicFileType = BasicFileType.DIRECTORY
    )

    private val STATIC_DIRECTORY_MAP: Map<String, List<VirtualEntry>> = mapOf(
        "/" to listOf(
            VirtualEntry("acct"),
            VirtualEntry("apex"),
            VirtualEntry("bin", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("bugreports", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("cache"),
            VirtualEntry("config"),
            VirtualEntry("d", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("data"),
            VirtualEntry("data_mirror"),
            VirtualEntry("debug_ramdisk"),
            VirtualEntry("dev"),
            VirtualEntry("etc", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("init", BasicFileType.REGULAR_FILE),
            VirtualEntry("init.environ.rc", BasicFileType.REGULAR_FILE),
            VirtualEntry("init.rc", BasicFileType.REGULAR_FILE),
            VirtualEntry("linkerconfig"),
            VirtualEntry("lost+found"),
            VirtualEntry("metadata"),
            VirtualEntry("mnt"),
            VirtualEntry("odm"),
            VirtualEntry("odm_dlkm"),
            VirtualEntry("oem"),
            VirtualEntry("postinstall"),
            VirtualEntry("proc"),
            VirtualEntry("product"),
            VirtualEntry("sdcard", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("second_stage_resources"),
            VirtualEntry("storage"),
            VirtualEntry("sys"),
            VirtualEntry("system"),
            VirtualEntry("system_dlkm"),
            VirtualEntry("system_ext"),
            VirtualEntry("vendor"),
            VirtualEntry("vendor_dlkm")
        ),
        "/storage" to listOf(
            VirtualEntry("emulated"),
            VirtualEntry("self", BasicFileType.SYMBOLIC_LINK)
        ),
        "/storage/emulated" to listOf(
            VirtualEntry("0")
        ),
        "/storage/self" to listOf(
            VirtualEntry("primary", BasicFileType.SYMBOLIC_LINK)
        ),
        "/mnt" to listOf(
            VirtualEntry("sdcard", BasicFileType.SYMBOLIC_LINK),
            VirtualEntry("user"),
            VirtualEntry("runtime"),
            VirtualEntry("pass_through"),
            VirtualEntry("installer"),
            VirtualEntry("androidwritable")
        ),
        "/mnt/user" to listOf(
            VirtualEntry("0")
        ),
        "/data" to listOf(
            VirtualEntry("app"),
            VirtualEntry("data"),
            VirtualEntry("local"),
            VirtualEntry("media"),
            VirtualEntry("misc"),
            VirtualEntry("system"),
            VirtualEntry("user"),
            VirtualEntry("user_de"),
            VirtualEntry("vendor"),
            VirtualEntry("adb")
        ),
        "/data/local" to listOf(
            VirtualEntry("tmp"),
            VirtualEntry("traces")
        ),
        "/data/user" to listOf(
            VirtualEntry("0")
        ),
        "/data/user_de" to listOf(
            VirtualEntry("0")
        ),
        "/data/media" to listOf(
            VirtualEntry("0")
        )
    )

    fun resolveEntriesForPath(directoryPath: Path): List<FileItem> {
        val rawPath = directoryPath.toString()
        val normalized = if (rawPath.length > 1) rawPath.trimEnd('/') else rawPath
        val resultMap = LinkedHashMap<String, FileItem>()

        fun addChildEntry(name: String, forcedType: BasicFileType? = null, fastVirtual: Boolean = false) {
            if (name.isBlank() || name == "." || name == ".." || resultMap.containsKey(name)) return
            val childFile = File(normalized, name)
            val childPath: Path = (directoryPath as? LinuxPath)?.resolve(name.toByteString())
                ?: Paths.get(childFile.absolutePath)
            val item = if (fastVirtual && forcedType != null) {
                childPath.createFastVirtualFileItem(forcedType)
            } else {
                try {
                    if (forcedType == null) {
                        childPath.loadFileItem()
                    } else {
                        try {
                            childPath.loadFileItem()
                        } catch (_: Throwable) {
                            childPath.createFallbackFileItem(childFile, forcedType)
                        }
                    }
                } catch (_: Throwable) {
                    childPath.createFallbackFileItem(childFile, forcedType)
                }
            }
            if (item != null) {
                resultMap[name] = item
            }
        }

        // 1. Check static known hierarchy (e.g. /, /storage, /storage/emulated, /data, /data/local, /mnt)
        STATIC_DIRECTORY_MAP[normalized]?.forEach { entry ->
            val candidateFile = File(normalized, entry.name)
            val existsOrKnown = try {
                candidateFile.exists() || Os.lstat(candidateFile.absolutePath) != null || Os.readlink(candidateFile.absolutePath) != null
            } catch (_: Throwable) {
                // Even if SELinux denies lstat, keep core structural directories so user can traverse into them
                normalized in setOf("/storage", "/storage/emulated", "/data", "/data/local", "/data/user", "/mnt") ||
                    entry.type == BasicFileType.DIRECTORY || entry.type == BasicFileType.SYMBOLIC_LINK
            }
            if (existsOrKnown) {
                addChildEntry(entry.name, entry.type)
            }
        }

        // 2. Discover mounted paths under current directory from /proc/self/mounts
        try {
            val mountsFile = File("/proc/self/mounts")
            if (mountsFile.canRead()) {
                val prefix = if (normalized == "/") "/" else "$normalized/"
                mountsFile.forEachLine { line ->
                    val parts = line.split(' ')
                    if (parts.size >= 2) {
                        val mountPoint = parts[1]
                        if (mountPoint.startsWith(prefix) && mountPoint.length > prefix.length) {
                            val sub = mountPoint.substring(prefix.length).substringBefore('/')
                            if (sub.isNotEmpty()) {
                                addChildEntry(sub, BasicFileType.DIRECTORY)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Special handling for /data/data, /data/user/0, /data/user_de/0
        if (normalized == "/data/data" || normalized == "/data/user/0" || normalized == "/data/user_de/0") {
            val ownPkg = application.packageName
            addChildEntry(ownPkg, BasicFileType.DIRECTORY, fastVirtual = false)
            for (pkg in getInstalledPackagesCached()) {
                if (pkg == ownPkg) continue
                addChildEntry(pkg, BasicFileType.DIRECTORY, fastVirtual = true)
            }
        }

        // 4. Special handling for /storage/emulated/0/Android/data and /sdcard/Android/data (and obb)
        val lowerNorm = normalized.lowercase()
        if (lowerNorm.endsWith("/android/data") || lowerNorm.endsWith("/android/obb")) {
            val ownPkg = application.packageName
            addChildEntry(ownPkg, BasicFileType.DIRECTORY, fastVirtual = false)
            val isData = lowerNorm.endsWith("/android/data")
            for (pkg in getInstalledPackagesCached()) {
                if (pkg == ownPkg) continue
                if (isData) {
                    addChildEntry(pkg, BasicFileType.DIRECTORY, fastVirtual = true)
                } else {
                    val candidate = File(normalized, pkg)
                    val exists = try {
                        candidate.exists() || Os.lstat(candidate.absolutePath) != null
                    } catch (_: Throwable) {
                        false
                    }
                    if (exists) {
                        addChildEntry(pkg, BasicFileType.DIRECTORY, fastVirtual = true)
                    }
                }
            }
        }

        // 5. Special handling for own package inside /data/data/<ownPkg> or /data/user/0/<ownPkg>
        val ownDataDir = application.applicationInfo?.dataDir
        if (ownDataDir != null && (normalized == ownDataDir || normalized == "/data/data/${application.packageName}" || normalized == "/data/user/0/${application.packageName}")) {
            val realDir = File(ownDataDir)
            realDir.listFiles()?.forEach { f ->
                addChildEntry(
                    f.name,
                    if (f.isDirectory) BasicFileType.DIRECTORY else BasicFileType.REGULAR_FILE
                )
            }
        }

        return resultMap.values.toList()
    }
}

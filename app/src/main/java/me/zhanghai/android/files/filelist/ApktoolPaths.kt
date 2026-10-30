/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.os.Environment
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.AxmlDecoder
import java.io.File
import java.util.zip.ZipInputStream

object ApktoolPaths {

    /**
     * Resolves the primary OpenManager/ApkTool base directory.
     * Uses /storage/emulated/0/OpenManager/ApkTool if accessible, with fallback to external files.
     */
    fun getApktoolBaseDir(): File {
        val candidates = listOf(
            File("/storage/emulated/0/OpenManager/ApkTool"),
            File(Environment.getExternalStorageDirectory(), "OpenManager/ApkTool"),
            File(application.getExternalFilesDir(null), "OpenManager/ApkTool")
        )
        for (dir in candidates) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.exists() && dir.canWrite()) {
                    return dir
                }
            } catch (ignored: Throwable) {}
        }
        val fallback = File("/storage/emulated/0/OpenManager/ApkTool")
        fallback.mkdirs()
        return fallback
    }

    /**
     * Resolves a clean, canonical package name from an APK file.
     * 1. Attempts PackageManager archive parsing.
     * 2. Inspects AndroidManifest.xml inside the ZIP and decodes binary AXML.
     * 3. Falls back to sanitized base file name.
     */
    fun resolvePackageName(context: Context, apkFile: File): String {
        // 1. Try PackageManager
        try {
            val pi = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (!pi?.packageName.isNullOrBlank()) {
                return pi!!.packageName
            }
        } catch (ignored: Throwable) {}

        // 2. Decode AndroidManifest.xml from APK ZIP
        try {
            ZipInputStream(apkFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "AndroidManifest.xml") {
                        val bytes = zis.readBytes()
                        val xml = if (AxmlDecoder.isAxml(bytes)) {
                            AxmlDecoder.decode(bytes)
                        } else {
                            String(bytes, Charsets.UTF_8)
                        }
                        val regex = Regex("""package\s*=\s*"([^"]+)"""")
                        val match = regex.find(xml)
                        if (match != null && match.groupValues[1].isNotBlank()) {
                            return match.groupValues[1].trim()
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (ignored: Throwable) {}

        // 3. Fallback to clean filename
        val name = apkFile.name
            .removeSuffix(".apk")
            .removeSuffix(".xapk")
            .removeSuffix(".apks")
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * Directory dedicated to a specific package under OpenManager/ApkTool/<packageName>
     */
    fun getPackageDir(packageName: String): File {
        val base = getApktoolBaseDir()
        val pkgDir = File(base, packageName)
        if (!pkgDir.exists()) {
            pkgDir.mkdirs()
        }
        return pkgDir
    }

    /**
     * Target directory for Java (JADX) decompilation:
     * OpenManager/ApkTool/<packageName>/decomp_java
     */
    fun getJavaDecompileDir(packageName: String): File {
        return File(getPackageDir(packageName), "decomp_java")
    }

    /**
     * Target directory for Apktool source decompilation:
     * OpenManager/ApkTool/<packageName>/decomp_src
     */
    fun getApktoolDecompileDir(packageName: String): File {
        return File(getPackageDir(packageName), "decomp_src")
    }

    /**
     * Target recompiled unsigned APK path:
     * OpenManager/ApkTool/<packageName>/<packageName>_recompiled.apk
     */
    fun getRecompiledApkFile(packageName: String): File {
        return File(getPackageDir(packageName), "${packageName}_recompiled.apk")
    }

    /**
     * Target signed APK path:
     * OpenManager/ApkTool/<packageName>/<packageName>_recompiled_signed.apk
     */
    fun getSignedApkFile(packageName: String): File {
        return File(getPackageDir(packageName), "${packageName}_recompiled_signed.apk")
    }
}

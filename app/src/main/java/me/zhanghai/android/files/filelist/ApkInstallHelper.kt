package me.zhanghai.android.files.filelist

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.ShizukuManager
import me.zhanghai.android.files.provider.root.ShizukuShell
import me.zhanghai.android.files.util.escapeShellArg
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ApkInstallHelper {

    data class SplitApkEntryInfo(
        val zipEntryName: String,
        val sessionEntryName: String,
        val displayName: String,
        val uncompressedSize: Long
    )

    private val SPLIT_BUNDLE_EXTENSIONS = setOf("apks", "xapk", "apkm")

    fun isSplitApkBundleName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SPLIT_BUNDLE_EXTENSIONS
    }

    fun collectSplitApkEntries(zipFile: ZipFile): List<SplitApkEntryInfo> {
        data class Candidate(
            val entry: ZipEntry,
            val leafName: String,
            val sanitizedLeaf: String,
            val lowerLeaf: String,
            val priority: Int
        )

        val candidates = mutableListOf<Candidate>()
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (name.contains("..") || name.startsWith("/") ||
                name.startsWith("__MACOSX/", ignoreCase = true)
            ) {
                continue
            }
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (entry.size == 0L) continue

            val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            if (leaf.isEmpty()) continue
            val sanitized = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "split.apk" }
            val lowerLeaf = leaf.lowercase(Locale.ROOT)
            val priority = if (lowerLeaf == "base.apk" || lowerLeaf == "master.apk" ||
                lowerLeaf.contains("base")
            ) {
                0
            } else {
                1
            }
            candidates.add(Candidate(entry, leaf, sanitized, lowerLeaf, priority))
        }

        return candidates
            .sortedWith(compareBy({ it.priority }, { it.lowerLeaf }, { it.entry.name }))
            .mapIndexed { index, item ->
                SplitApkEntryInfo(
                    zipEntryName = item.entry.name,
                    sessionEntryName = "${index}_${item.sanitizedLeaf}",
                    displayName = item.leafName,
                    uncompressedSize = item.entry.size
                )
            }
    }

    fun isSplitApkArchiveFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return try {
            ZipFile(file).use { zip ->
                val hasManifest = zip.getEntry("AndroidManifest.xml") != null
                val hasDexOrRes = zip.getEntry("classes.dex") != null ||
                    zip.getEntry("resources.arsc") != null
                if (hasManifest && hasDexOrRes) {
                    false
                } else {
                    collectSplitApkEntries(zip).isNotEmpty()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun buildMultiApkPmSessionScript(
        stagingDir: String,
        stagedFiles: List<Pair<String, Long>>,
        sdkInt: Int
    ): String {
        val escapedDir = stagingDir.escapeShellArg()
        if (stagedFiles.isEmpty()) {
            return "rm -rf $escapedDir; exit 1"
        }
        val normalizedDir = stagingDir.trimEnd('/')
        val grantPermFlag = if (sdkInt >= 23) " -g" else ""
        val bypassLowTargetFlag = if (sdkInt >= 34) " --bypass-low-target-sdk-block" else ""
        val flags = "-r -d -t$grantPermFlag$bypassLowTargetFlag"

        return buildString {
            append("CREATE_OUT=$(pm install-create ").append(flags).append(" 2>&1)\n")
            append("SESSION_ID=$(printf '%s\\n' \"\$CREATE_OUT\" | sed -n 's/.*\\[\\([0-9][0-9]*\\)\\].*/\\1/p' | head -n 1)\n")
            append("if [ -z \"\$SESSION_ID\" ]; then SESSION_ID=$(printf '%s\\n' \"\$CREATE_OUT\" | grep -oE '[0-9]+' | head -n 1); fi\n")
            append("if [ -z \"\$SESSION_ID\" ]; then printf '%s\\n' \"\$CREATE_OUT\" >&2; rm -rf ")
                .append(escapedDir).append("; exit 1; fi\n")

            for ((entryName, byteSize) in stagedFiles) {
                val safeSize = byteSize.coerceAtLeast(1L)
                val fullStagedPath = "$normalizedDir/$entryName"
                append("if ! pm install-write -S ").append(safeSize)
                    .append(" \"\$SESSION_ID\" ")
                    .append(entryName.escapeShellArg())
                    .append(" ")
                    .append(fullStagedPath.escapeShellArg())
                    .append("; then\n")
                append("  pm install-abandon \"\$SESSION_ID\" >/dev/null 2>&1\n")
                append("  rm -rf ").append(escapedDir).append("\n")
                append("  exit 1\n")
                append("fi\n")
            }

            append("COMMIT_OUT=$(pm install-commit \"\$SESSION_ID\" 2>&1)\n")
            append("STATUS=$?\n")
            append("printf '%s\\n' \"\$COMMIT_OUT\"\n")
            append("rm -rf ").append(escapedDir).append("\n")
            append("exit \$STATUS")
        }
    }

    fun isRootAvailable(): Boolean {
        return try {
            LibSuFileServiceLauncher.isSuAvailable() || (Shell.isAppGrantedRoot() == true)
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuAvailable(): Boolean {
        return ShizukuManager.ready()
    }

    private fun stageFileItemToCache(
        context: Context,
        file: FileItem,
        prefix: String,
        preferExternalCache: Boolean = false
    ): File {
        val path = file.path
        val ext = file.name.substringAfterLast('.', "apk")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { "apk" }
        val baseDir = if (preferExternalCache) {
            context.externalCacheDir ?: context.cacheDir
        } else {
            context.cacheDir
        }
        val cacheFile = File(baseDir, "${prefix}_${System.currentTimeMillis()}.$ext")
        path.newInputStream().use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
        return cacheFile
    }

    private fun stageSplitBundleToCache(
        context: Context,
        archiveFile: File,
        preferExternalCache: Boolean
    ): Pair<File, List<File>> {
        val baseDir = if (preferExternalCache) {
            context.externalCacheDir ?: context.cacheDir
        } else {
            context.cacheDir
        }
        val extractDir = File(baseDir, "om_split_extract_${System.currentTimeMillis()}_${System.nanoTime()}")
        extractDir.mkdirs()
        val extractPrefix = extractDir.canonicalPath + File.separator
        val extractedFiles = mutableListOf<File>()
        try {
            ZipFile(archiveFile).use { zip ->
                val entries = collectSplitApkEntries(zip)
                val buffer = ByteArray(65536)
                for (info in entries) {
                    val zipEntry = zip.getEntry(info.zipEntryName) ?: continue
                    val outFile = File(extractDir, info.sessionEntryName)
                    if (!outFile.canonicalPath.startsWith(extractPrefix)) continue
                    zip.getInputStream(zipEntry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (outFile.length() > 0L) {
                        extractedFiles.add(outFile)
                    }
                }
            }
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
        }
        return extractDir to extractedFiles
    }

    private fun installSplitBundleViaShell(
        context: Context,
        archiveFile: File,
        isRoot: Boolean,
        shellExec: (String) -> Triple<Boolean, String, Int>
    ): Pair<Boolean, String> {
        val (extractDir, extractedFiles) = try {
            stageSplitBundleToCache(context, archiveFile, preferExternalCache = !isRoot)
        } catch (e: Exception) {
            return false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
        }

        try {
            if (extractedFiles.isEmpty()) {
                return false to context.getString(R.string.om_split_install_no_apks_found)
            }

            val remoteStagingDir = "/data/local/tmp/om_split_${if (isRoot) "root" else "shizuku"}_${System.currentTimeMillis()}"
            val escapedRemoteDir = remoteStagingDir.escapeShellArg()
            val copyCommands = mutableListOf("mkdir -p $escapedRemoteDir")
            val stagedSizes = mutableListOf<Pair<String, Long>>()

            for (localSplit in extractedFiles) {
                val remoteSplit = "$remoteStagingDir/${localSplit.name}"
                copyCommands.add(
                    "cp ${localSplit.absolutePath.escapeShellArg()} ${remoteSplit.escapeShellArg()}"
                )
                copyCommands.add("chmod 644 ${remoteSplit.escapeShellArg()}")
                stagedSizes.add(localSplit.name to localSplit.length())
            }

            val sessionScript = buildMultiApkPmSessionScript(
                stagingDir = remoteStagingDir,
                stagedFiles = stagedSizes,
                sdkInt = Build.VERSION.SDK_INT
            )
            val fullScript = buildString {
                append(copyCommands.joinToString(" && "))
                append(" || { rm -rf ").append(escapedRemoteDir).append("; exit 1; }\n")
                append(sessionScript)
            }

            val (cmdOk, output, exitCode) = shellExec(fullScript)
            val isSuccess = (cmdOk || exitCode == 0) &&
                (output.contains("Success", ignoreCase = true) || !output.contains("Failure", ignoreCase = true))

            return if (isSuccess) {
                true to context.getString(
                    if (isRoot) R.string.om_install_success else R.string.om_install_shizuku_success
                )
            } else {
                val errorMsg = if (output.isNotEmpty()) output else context.getString(R.string.om_install_error_code_format, exitCode)
                false to errorMsg
            }
        } finally {
            extractDir.deleteRecursively()
        }
    }

    suspend fun installApkWithRoot(
        context: Context,
        apkFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isSplitApkArchiveFile(apkFile)) {
            return@withContext installSplitBundleViaShell(context, apkFile, isRoot = true) { script ->
                try {
                    val result = Shell.cmd(script).exec()
                    val out = (result.out + result.err).joinToString("\n").trim()
                    Triple(result.isSuccess, out, result.code)
                } catch (e: Exception) {
                    Triple(false, context.getString(R.string.om_install_root_shell_error_format, e.message ?: ""), -1)
                }
            }
        }

        val targetTmpPath = "/data/local/tmp/om_root_install_${System.currentTimeMillis()}.apk"

        val grantPermFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "-g " else ""
        val bypassLowTargetFlag = if (Build.VERSION.SDK_INT >= 34) "--bypass-low-target-sdk-block " else ""
        // Flags: -r (replace existing), -d (allow downgrade), -t (allow test packages), -g (grant permissions)
        val installCmd = "pm install -r -d -t $grantPermFlag$bypassLowTargetFlag${targetTmpPath.escapeShellArg()}"

        val commands = listOf(
            "cp ${apkFile.absolutePath.escapeShellArg()} ${targetTmpPath.escapeShellArg()}",
            "chmod 644 ${targetTmpPath.escapeShellArg()}",
            installCmd,
            "STATUS=$?",
            "rm -f ${targetTmpPath.escapeShellArg()}",
            "exit \$STATUS"
        )
        val script = commands.joinToString(" && ")

        val result = try {
            Shell.cmd(script).exec()
        } catch (e: Exception) {
            return@withContext false to context.getString(R.string.om_install_root_shell_error_format, e.message ?: "")
        }

        val output = (result.out + result.err).joinToString("\n").trim()
        val isSuccess = (result.isSuccess || result.code == 0) &&
            (output.contains("Success", ignoreCase = true) || !output.contains("Failure", ignoreCase = true))

        if (isSuccess) {
            true to context.getString(R.string.om_install_success)
        } else {
            val errorMsg = if (output.isNotEmpty()) output else context.getString(R.string.om_install_error_code_format, result.code)
            false to errorMsg
        }
    }

    suspend fun installWithRoot(
        context: Context,
        file: FileItem
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val path = file.path
        val tempInCache = if (!path.isLinuxPath) {
            try {
                stageFileItemToCache(context, file, "temp_install", preferExternalCache = false)
            } catch (e: Exception) {
                return@withContext false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
            }
        } else {
            null
        }

        try {
            val targetFile = tempInCache ?: path.toFile()
            installApkWithRoot(context, targetFile)
        } finally {
            tempInCache?.delete()
        }
    }

    suspend fun installApkWithShizuku(
        context: Context,
        apkFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.ready()) {
            return@withContext false to context.getString(R.string.om_install_shizuku_not_ready)
        }

        if (isSplitApkArchiveFile(apkFile)) {
            return@withContext installSplitBundleViaShell(context, apkFile, isRoot = false) { script ->
                try {
                    val result = ShizukuShell.exec(script, timeoutSeconds = 120)
                    val out = (result.out + "\n" + result.err).trim()
                    Triple(result.isSuccess, out, result.code)
                } catch (e: Exception) {
                    Triple(false, context.getString(R.string.om_install_shizuku_shell_error_format, e.message ?: ""), -1)
                }
            }
        }

        val targetTmpPath = "/data/local/tmp/om_shizuku_install_${System.currentTimeMillis()}.apk"

        val grantPermFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "-g " else ""
        val bypassLowTargetFlag = if (Build.VERSION.SDK_INT >= 34) "--bypass-low-target-sdk-block " else ""
        val installCmd = "pm install -r -d -t $grantPermFlag$bypassLowTargetFlag${targetTmpPath.escapeShellArg()}"

        val commands = listOf(
            "cp ${apkFile.absolutePath.escapeShellArg()} ${targetTmpPath.escapeShellArg()}",
            "chmod 644 ${targetTmpPath.escapeShellArg()}",
            installCmd,
            "STATUS=$?",
            "rm -f ${targetTmpPath.escapeShellArg()}",
            "exit \$STATUS"
        )
        val script = commands.joinToString(" && ")

        val result = try {
            ShizukuShell.exec(script, timeoutSeconds = 60)
        } catch (e: Exception) {
            return@withContext false to context.getString(R.string.om_install_shizuku_shell_error_format, e.message ?: "")
        }

        val output = (result.out + "\n" + result.err).trim()
        val isSuccess = (result.isSuccess || result.code == 0) &&
            (output.contains("Success", ignoreCase = true) || !output.contains("Failure", ignoreCase = true))

        if (isSuccess) {
            true to context.getString(R.string.om_install_shizuku_success)
        } else {
            val errorMsg = if (output.isNotEmpty()) output else context.getString(R.string.om_install_error_code_format, result.code)
            false to errorMsg
        }
    }

    suspend fun installWithShizuku(
        context: Context,
        file: FileItem
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.ready()) {
            return@withContext false to context.getString(R.string.om_install_shizuku_not_ready)
        }

        val path = file.path
        val tempInCache = if (!path.isLinuxPath) {
            try {
                stageFileItemToCache(context, file, "temp_shizuku_install", preferExternalCache = true)
            } catch (e: Exception) {
                return@withContext false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
            }
        } else {
            null
        }

        try {
            val targetFile = tempInCache ?: path.toFile()
            installApkWithShizuku(context, targetFile)
        } finally {
            tempInCache?.delete()
        }
    }

    suspend fun installSplitBundleWithPackageInstaller(
        context: Context,
        fileItem: FileItem,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val path = fileItem.path
        val tempArchive = if (!path.isLinuxPath) {
            try {
                stageFileItemToCache(context, fileItem, "om_split_session_in", preferExternalCache = false)
            } catch (e: Exception) {
                return@withContext false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
            }
        } else {
            null
        }

        try {
            val localFile = tempArchive ?: path.toFile()
            installSplitBundleWithPackageInstaller(
                context = context,
                archiveFile = localFile,
                displayBundleName = fileItem.name,
                onProgress = onProgress
            )
        } finally {
            tempArchive?.delete()
        }
    }

    suspend fun installSplitBundleWithPackageInstaller(
        context: Context,
        archiveFile: File,
        displayBundleName: String,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Exception) {
                true
            }
        } else {
            true
        }
        if (!canInstall) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            return@withContext false to context.getString(R.string.om_split_install_unknown_sources_needed)
        }

        if (!archiveFile.exists() || !archiveFile.canRead()) {
            return@withContext false to context.getString(R.string.om_split_apk_access_error)
        }

        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        try {
            ZipFile(archiveFile).use { zip ->
                val entries = collectSplitApkEntries(zip)
                if (entries.isEmpty()) {
                    return@withContext false to context.getString(R.string.om_split_install_no_apks_found)
                }

                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val totalKnownBytes = entries
                    .mapNotNull { it.uncompressedSize.takeIf { sz -> sz > 0L } }
                    .sum()
                if (totalKnownBytes > 0L) {
                    params.setSize(totalKnownBytes)
                }

                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val buffer = ByteArray(65536)
                    entries.forEachIndexed { idx, info ->
                        onProgress?.invoke(
                            context.getString(
                                R.string.om_split_install_preparing_format,
                                idx + 1,
                                entries.size,
                                info.displayName
                            )
                        )
                        val zipEntry = zip.getEntry(info.zipEntryName)
                            ?: throw IllegalStateException("Missing split entry: ${info.zipEntryName}")
                        val expectedLen = if (info.uncompressedSize > 0L) info.uncompressedSize else -1L
                        session.openWrite(info.sessionEntryName, 0L, expectedLen).use { out ->
                            zip.getInputStream(zipEntry).use { input ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                }
                            }
                            session.fsync(out)
                        }
                    }

                    val statusIntent = Intent(context, SplitApkInstallReceiver::class.java).apply {
                        action = SplitApkInstallReceiver.ACTION_SPLIT_INSTALL_STATUS
                        setPackage(context.packageName)
                        putExtra(SplitApkInstallReceiver.EXTRA_BUNDLE_NAME, displayBundleName)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val pendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
                    session.commit(pendingIntent.intentSender)
                }
            }
            true to displayBundleName
        } catch (e: Exception) {
            e.printStackTrace()
            if (sessionId != -1) {
                runCatching { installer.abandonSession(sessionId) }
            }
            false to (e.localizedMessage ?: e.javaClass.simpleName)
        }
    }
}

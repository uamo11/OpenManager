package me.zhanghai.android.files.filelist

import android.content.Context
import android.os.Build
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

object ApkInstallHelper {

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

    suspend fun installApkWithRoot(
        context: Context,
        apkFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val targetTmpPath = "/data/local/tmp/om_root_install_${System.currentTimeMillis()}.apk"

        val grantPermFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "-g " else ""
        // Flags: -r (replace existing), -d (allow downgrade), -t (allow test packages), -g (grant permissions)
        val installCmd = "pm install -r -d -t $grantPermFlag${targetTmpPath.escapeShellArg()}"

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
        val tempApkInCache = if (!path.isLinuxPath) {
            val cacheFile = File(context.cacheDir, "temp_install_${System.currentTimeMillis()}.apk")
            try {
                path.newInputStream().use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                cacheFile
            } catch (e: Exception) {
                return@withContext false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
            }
        } else {
            null
        }

        val targetFile = tempApkInCache ?: path.toFile()
        val result = installApkWithRoot(context, targetFile)
        tempApkInCache?.delete()
        result
    }

    suspend fun installApkWithShizuku(
        context: Context,
        apkFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ShizukuManager.ready()) {
            return@withContext false to context.getString(R.string.om_install_shizuku_not_ready)
        }

        val targetTmpPath = "/data/local/tmp/om_shizuku_install_${System.currentTimeMillis()}.apk"

        val grantPermFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "-g " else ""
        val installCmd = "pm install -r -d -t $grantPermFlag${targetTmpPath.escapeShellArg()}"

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
        val tempApkInCache = if (!path.isLinuxPath) {
            val cacheFile = File(context.cacheDir, "temp_shizuku_install_${System.currentTimeMillis()}.apk")
            try {
                path.newInputStream().use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                cacheFile
            } catch (e: Exception) {
                return@withContext false to context.getString(R.string.om_install_prepare_apk_error_format, e.message ?: "")
            }
        } else {
            null
        }

        val targetFile = tempApkInCache ?: path.toFile()
        val result = installApkWithShizuku(context, targetFile)
        tempApkInCache?.delete()
        result
    }
}

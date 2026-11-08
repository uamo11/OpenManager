/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.ApkInstallHelper
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
import org.json.JSONObject

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String
)

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
    val targetAsset: ReleaseAsset,
    val isDebugChannel: Boolean
)

object UpdateChecker {
    const val GITHUB_REPO = "uamo11/OpenManager"
    const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val PREF_LAST_CHECK_TIME = "pref_last_update_check_time"
    private const val AUTO_CHECK_COOLDOWN_MS = 2 * 60 * 60 * 1000L // 2 hours

    fun isDebugBuild(context: Context): Boolean {
        return BuildConfig.DEBUG || ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    fun parseVersionParts(versionStr: String): List<Int> {
        val clean = versionStr.trim()
            .removePrefix("v")
            .removePrefix("V")
        val parts = clean.split('.', '-', '_')
        return parts.mapNotNull { part ->
            part.filter { it.isDigit() }.toIntOrNull()
        }
    }

    fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        val latestParts = parseVersionParts(latestVersion)
        val currentParts = parseVersionParts(currentVersion)
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val latest = latestParts.getOrElse(i) { 0 }
            val current = currentParts.getOrElse(i) { 0 }
            if (latest > current) return true
            if (latest < current) return false
        }
        return false
    }

    fun selectAssetForDevice(assets: List<ReleaseAsset>, isDebug: Boolean): ReleaseAsset? {
        if (assets.isEmpty()) return null
        return if (isDebug) {
            assets.firstOrNull { it.name.endsWith("-debug.apk", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.contains("debug", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: assets.first()
        } else {
            assets.firstOrNull { it.name.endsWith("-release.apk", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.contains("release", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true) }
                ?: assets.firstOrNull { !it.name.contains("debug", ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true) }
                ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: assets.first()
        }
    }

    fun parseReleaseJson(jsonString: String, isDebug: Boolean): ReleaseInfo? {
        return try {
            val root = JSONObject(jsonString)
            val tagName = root.optString("tag_name")
            if (tagName.isNullOrEmpty()) return null
            val version = tagName.removePrefix("v").removePrefix("V").trim()
            val name = root.optString("name", "OpenManager $tagName")
            val body = root.optString("body", "")
            val htmlUrl = root.optString("html_url", "https://github.com/$GITHUB_REPO/releases")

            val assetsArray = root.optJSONArray("assets")
            val assets = mutableListOf<ReleaseAsset>()
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val assetObj = assetsArray.optJSONObject(i) ?: continue
                    val assetName = assetObj.optString("name")
                    val assetSize = assetObj.optLong("size", 0L)
                    val downloadUrl = assetObj.optString("browser_download_url")
                    if (assetName.isNotEmpty() && downloadUrl.isNotEmpty()) {
                        assets.add(ReleaseAsset(assetName, assetSize, downloadUrl))
                    }
                }
            }
            val targetAsset = selectAssetForDevice(assets, isDebug) ?: return null
            ReleaseInfo(
                tagName = tagName,
                version = version,
                name = name,
                body = body,
                htmlUrl = htmlUrl,
                assets = assets,
                targetAsset = targetAsset,
                isDebugChannel = isDebug
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun openConnectionWithRedirects(urlString: String, maxRedirects: Int = 5): HttpURLConnection {
        var url = URL(urlString)
        var redirects = 0
        while (redirects < maxRedirects) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
            connection.connect()
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308
            ) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) {
                    throw IOException("Redirect without Location header")
                }
                url = URL(url, location)
                redirects++
                continue
            }
            return connection
        }
        throw IOException("Too many redirects ($maxRedirects)")
    }

    suspend fun fetchLatestRelease(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val isDebug = isDebugBuild(context)
        val url = URL(GITHUB_LATEST_RELEASE_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                parseReleaseJson(responseBody, isDebug)
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("GitHub API HTTP $responseCode: $errorBody")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun checkUpdate(activity: FragmentActivity, isManual: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()

        if (!isManual) {
            val lastCheck = prefs.getLong(PREF_LAST_CHECK_TIME, 0L)
            if (now - lastCheck < AUTO_CHECK_COOLDOWN_MS) {
                return
            }
        }

        var loadingDialog: AlertDialog? = null
        if (isManual) {
            loadingDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_checking_title)
                .setMessage(R.string.update_checking_message)
                .setCancelable(true)
                .create()
            loadingDialog.show()
        }

        activity.lifecycleScope.launch {
            try {
                val releaseInfo = fetchLatestRelease(activity)
                prefs.edit().putLong(PREF_LAST_CHECK_TIME, now).apply()

                if (activity.isFinishing || activity.isDestroyed) return@launch
                loadingDialog?.dismiss()

                if (releaseInfo == null) {
                    if (isManual) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.update_check_error_title)
                            .setMessage(activity.getString(R.string.update_check_error_message, "Información no disponible"))
                            .setPositiveButton(R.string.retry) { _, _ -> checkUpdate(activity, isManual = true) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    return@launch
                }

                val hasUpdate = isVersionNewer(releaseInfo.version, BuildConfig.VERSION_NAME)
                if (hasUpdate) {
                    showUpdateDialog(activity, releaseInfo)
                } else {
                    if (isManual) {
                        val isDebug = isDebugBuild(activity)
                        val channelStr = activity.getString(if (isDebug) R.string.update_channel_debug else R.string.update_channel_release)
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.update_up_to_date_title)
                            .setMessage(activity.getString(R.string.update_up_to_date_message, "v" + BuildConfig.VERSION_NAME, channelStr))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (activity.isFinishing || activity.isDestroyed) return@launch
                loadingDialog?.dismiss()
                if (isManual) {
                    val errorMsg = e.localizedMessage ?: e.message ?: e.toString()
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.update_check_error_title)
                        .setMessage(activity.getString(R.string.update_check_error_message, errorMsg))
                        .setPositiveButton(R.string.retry) { _, _ -> checkUpdate(activity, isManual = true) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    fun showUpdateDialog(activity: FragmentActivity, releaseInfo: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_available, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()

        val tvUpdateTitle = view.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvUpdateChannel = view.findViewById<TextView>(R.id.tvUpdateChannel)
        val tvUpdateVersionInfo = view.findViewById<TextView>(R.id.tvUpdateVersionInfo)
        val tvUpdateFileInfo = view.findViewById<TextView>(R.id.tvUpdateFileInfo)
        val tvUpdateChangelog = view.findViewById<TextView>(R.id.tvUpdateChangelog)
        val btnUpdateLater = view.findViewById<Button>(R.id.btnUpdateLater)
        val btnUpdateGitHub = view.findViewById<Button>(R.id.btnUpdateGitHub)
        val btnUpdateDownload = view.findViewById<Button>(R.id.btnUpdateDownload)

        tvUpdateTitle.text = activity.getString(R.string.update_available_title)

        val channelLabel = activity.getString(
            if (releaseInfo.isDebugChannel) R.string.update_channel_debug else R.string.update_channel_release
        )
        tvUpdateChannel.text = activity.getString(R.string.update_detected_channel, channelLabel)
        tvUpdateChannel.setTextColor(
            ContextCompat.getColor(
                activity,
                if (releaseInfo.isDebugChannel) android.R.color.holo_blue_light else android.R.color.holo_green_light
            )
        )

        val newVerText = activity.getString(R.string.update_new_version_label, "v" + releaseInfo.version)
        val curVerText = activity.getString(R.string.update_current_version_label, "v" + BuildConfig.VERSION_NAME)
        tvUpdateVersionInfo.text = "$newVerText\n$curVerText"

        val sizeFormatted = formatSize(releaseInfo.targetAsset.size)
        tvUpdateFileInfo.text = activity.getString(R.string.update_target_apk_label, releaseInfo.targetAsset.name, sizeFormatted)

        tvUpdateChangelog.text = if (releaseInfo.body.isNotBlank()) releaseInfo.body else activity.getString(R.string.update_up_to_date_title)

        btnUpdateLater.setOnClickListener {
            dialog.dismiss()
        }

        btnUpdateGitHub.setOnClickListener {
            activity.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.htmlUrl)))
        }

        btnUpdateDownload.setOnClickListener {
            dialog.dismiss()
            downloadAndInstall(activity, releaseInfo)
        }

        dialog.show()
    }

    fun downloadAndInstall(activity: FragmentActivity, releaseInfo: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_download, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        val tvDownloadFilename = view.findViewById<TextView>(R.id.tvDownloadFilename)
        val pbDownloadProgress = view.findViewById<LinearProgressIndicator>(R.id.pbDownloadProgress)
        val tvDownloadStats = view.findViewById<TextView>(R.id.tvDownloadStats)
        val tvDownloadSpeed = view.findViewById<TextView>(R.id.tvDownloadSpeed)
        val btnCancelDownload = view.findViewById<Button>(R.id.btnCancelDownload)

        tvDownloadFilename.text = releaseInfo.targetAsset.name
        pbDownloadProgress.progress = 0
        tvDownloadStats.text = activity.getString(R.string.update_downloading_format, "0 MB", formatSize(releaseInfo.targetAsset.size), 0)
        tvDownloadSpeed.text = activity.getString(R.string.update_download_speed_format, "0 MB")

        var downloadJob: Job? = null

        btnCancelDownload.setOnClickListener {
            downloadJob?.cancel()
            dialog.dismiss()
        }

        dialog.show()

        downloadJob = activity.lifecycleScope.launch {
            var tempFile: File? = null
            var finalFile: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val baseDir = File("/storage/emulated/0/OpenManager/updates")
                    val updatesDir = if (baseDir.exists() || baseDir.mkdirs()) {
                        baseDir
                    } else {
                        File(activity.cacheDir, "updates").apply { mkdirs() }
                    }

                    val apkName = releaseInfo.targetAsset.name
                    finalFile = File(updatesDir, apkName)
                    tempFile = File(updatesDir, "$apkName.tmp")
                    if (tempFile!!.exists()) {
                        tempFile!!.delete()
                    }

                    val connection = openConnectionWithRedirects(releaseInfo.targetAsset.downloadUrl)
                    val totalBytes = if (releaseInfo.targetAsset.size > 0) {
                        releaseInfo.targetAsset.size
                    } else {
                        connection.contentLength.toLong()
                    }

                    var downloadedBytes = 0L
                    var lastUpdateTime = SystemClock.elapsedRealtime()
                    var bytesSinceLastUpdate = 0L

                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile!!).use { output ->
                            val buffer = ByteArray(16384)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                bytesSinceLastUpdate += read

                                val nowTime = SystemClock.elapsedRealtime()
                                val timeDiff = nowTime - lastUpdateTime
                                if (timeDiff >= 150L) {
                                    val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                    val speedBytesPerSec = (bytesSinceLastUpdate * 1000L) / timeDiff
                                    val curDownloaded = downloadedBytes
                                    withContext(Dispatchers.Main) {
                                        if (!activity.isFinishing && !activity.isDestroyed) {
                                            pbDownloadProgress.progress = percent.coerceIn(0, 100)
                                            tvDownloadStats.text = activity.getString(
                                                R.string.update_downloading_format,
                                                formatSize(curDownloaded),
                                                formatSize(totalBytes),
                                                percent
                                            )
                                            tvDownloadSpeed.text = activity.getString(
                                                R.string.update_download_speed_format,
                                                formatSize(speedBytesPerSec)
                                            )
                                        }
                                    }
                                    lastUpdateTime = nowTime
                                    bytesSinceLastUpdate = 0L
                                }
                            }
                            output.flush()
                        }
                    }

                    if (finalFile!!.exists()) {
                        finalFile!!.delete()
                    }
                    if (!tempFile!!.renameTo(finalFile!!)) {
                        tempFile!!.copyTo(finalFile!!, overwrite = true)
                        tempFile!!.delete()
                    }
                }

                if (activity.isFinishing || activity.isDestroyed) return@launch
                dialog.dismiss()

                Toast.makeText(activity, activity.getString(R.string.update_download_success_title), Toast.LENGTH_SHORT).show()

                finalFile?.let { installedFile ->
                    launchApkInstallation(activity, installedFile)
                }
            } catch (e: CancellationException) {
                tempFile?.delete()
            } catch (e: Exception) {
                tempFile?.delete()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                dialog.dismiss()
                val errorMsg = e.localizedMessage ?: e.message ?: e.toString()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_download_failed_title)
                    .setMessage(activity.getString(R.string.update_download_failed_message, errorMsg))
                    .setPositiveButton(R.string.retry) { _, _ -> downloadAndInstall(activity, releaseInfo) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun launchApkInstallation(activity: FragmentActivity, apkFile: File) {
        activity.lifecycleScope.launch {
            if (Settings.USE_ROOT_INSTALL.valueCompat && ApkInstallHelper.isRootAvailable()) {
                Toast.makeText(activity, activity.getString(R.string.update_installing_title), Toast.LENGTH_SHORT).show()
                val (success, message) = ApkInstallHelper.installApkWithRoot(activity, apkFile)
                if (success) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    return@launch
                }
            } else if (Settings.USE_SHIZUKU_INSTALL.valueCompat && ApkInstallHelper.isShizukuAvailable()) {
                Toast.makeText(activity, activity.getString(R.string.update_installing_title), Toast.LENGTH_SHORT).show()
                val (success, message) = ApkInstallHelper.installApkWithShizuku(activity, apkFile)
                if (success) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    return@launch
                }
            }

            // Fallback to standard system PackageInstaller
            try {
                val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        Paths.get(apkFile.absolutePath).fileProviderUri
                    } catch (e: Exception) {
                        Uri.parse("content://${BuildConfig.FILE_PROVIDIER_AUTHORITY}/" + Uri.encode(apkFile.toURI().toString()))
                    }
                } else {
                    Uri.fromFile(apkFile)
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(activity, "Error al iniciar instalador: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

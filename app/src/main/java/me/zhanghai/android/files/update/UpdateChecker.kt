/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.filelist.ApkInstallHelper
import me.zhanghai.android.files.filelist.DirectorySnapshotCache
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.terminal.TerminalExpansionManager
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.showSafeDialog
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
import org.json.JSONArray
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
    val isDebugChannel: Boolean,
    val intermediateChangelog: String = ""
)

object UpdateChecker {
    const val GITHUB_REPO = "uamo11/OpenManager"
    const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=20"
    const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val GITHUB_RELEASES_ATOM_URL = "https://github.com/$GITHUB_REPO/releases.atom"
    const val PREF_LAST_CHECK_TIME = "pref_last_update_check_time"
    private const val PREF_PENDING_UPDATE_APK_PATH = "pref_pending_update_apk_path"
    private const val PREF_PENDING_UPDATE_VERSION = "pref_pending_update_version"
    private const val PREF_AUTO_UPDATE_V2_MIGRATED = "pref_auto_update_default_v2_migrated"
    private const val PREF_SKIPPED_UPDATE_VERSION = "pref_skipped_update_version"
    private const val PREF_CACHED_ETAG = "pref_update_etag"
    private const val PREF_CACHED_RELEASE_JSON = "pref_update_cached_release_json"
    // Each check is one conditional request (a 304 does not count against GitHub's limit), so a
    // short pause is enough to avoid asking again on every app switch.
    const val AUTO_CHECK_COOLDOWN_MS = 15 * 60 * 1000L
    private const val AUTO_STARTUP_DELAY_MS = 2_500L
    private const val MIN_CLEANUP_AGE_MS = 15_000L
    private const val STALE_TMP_AGE_MS = 3_600_000L
    private const val DOWNLOAD_BUFFER_SIZE = 65_536
    private val UPDATE_APK_NAME_REGEX = Regex("(?i)^OpenManager-v([0-9][0-9.]*).*\\.apk$")

    @Volatile
    var isDownloadingUpdate = false

    private val isCheckingUpdate = AtomicBoolean(false)
    private var activeUpdateDialogRef: WeakReference<AlertDialog>? = null

    /** A release shown in a dialog the user has not answered yet (the activity went away first). */
    @Volatile
    private var unansweredRelease: ReleaseInfo? = null

    /** True when an automatic check is due: never checked, or the last one is older than the pause. */
    fun isAutoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= AUTO_CHECK_COOLDOWN_MS

    /**
     * Called every time the explorer comes to the foreground, not only on a cold start: Android
     * usually keeps the app in memory, so "opening" it again resumes the same activity.
     */
    fun checkOnForeground(activity: FragmentActivity) {
        ensureAutoUpdateDefaultMigrated(activity)
        if (!Settings.AUTO_CHECK_UPDATES.valueCompat || isDownloadingUpdate) return
        if (activeUpdateDialogRef?.get()?.isShowing == true) return
        val pending = unansweredRelease
        if (pending != null && isVersionNewer(pending.version, BuildConfig.VERSION_NAME)) {
            showUpdateDialog(activity, pending)
            return
        }
        checkUpdate(activity, isManual = false)
    }

    /**
     * The release can be visible on GitHub a few minutes before its APK finishes uploading. An
     * automatic check waits for the file instead of offering a download that would fail.
     */
    private fun isApkPublished(releaseInfo: ReleaseInfo): Boolean {
        val asset = releaseInfo.targetAsset
        if (asset.size > 0L && releaseInfo.assets.any { it.name == asset.name }) return true
        return try {
            val conn = URL(asset.downloadUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
                conn.responseCode in 200..399
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    data class ResumeDecision(
        val startOffset: Long,
        val append: Boolean,
        val resetTempBeforeRetry: Boolean
    )

    fun ensureAutoUpdateDefaultMigrated(context: Context) {
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(PREF_AUTO_UPDATE_V2_MIGRATED, false)) {
                Settings.AUTO_CHECK_UPDATES.putValue(true)
                prefs.edit().putBoolean(PREF_AUTO_UPDATE_V2_MIGRATED, true).apply()
            }
        }
    }

    fun shouldNotifyAutoUpdate(
        latestVersion: String,
        currentVersion: String,
        skippedVersion: String?,
        isManual: Boolean
    ): Boolean {
        if (!isVersionNewer(latestVersion, currentVersion)) return false
        if (!isManual && !skippedVersion.isNullOrBlank() && !isVersionNewer(latestVersion, skippedVersion)) {
            return false
        }
        return true
    }

    fun resolveDownloadResume(httpCode: Int, existingTempBytes: Long): ResumeDecision {
        return when {
            httpCode == HttpURLConnection.HTTP_PARTIAL && existingTempBytes > 0L ->
                ResumeDecision(existingTempBytes, append = true, resetTempBeforeRetry = false)
            httpCode == 416 ->
                ResumeDecision(0L, append = false, resetTempBeforeRetry = true)
            else ->
                ResumeDecision(0L, append = false, resetTempBeforeRetry = false)
        }
    }

    fun hasZipLocalFileHeaderMagic(headerBytes: ByteArray, readCount: Int = headerBytes.size): Boolean {
        return readCount >= 4 &&
            headerBytes[0] == 0x50.toByte() &&
            headerBytes[1] == 0x4B.toByte() &&
            headerBytes[2] == 0x03.toByte() &&
            headerBytes[3] == 0x04.toByte()
    }

    fun formatEtaSeconds(remainingBytes: Long, bytesPerSec: Long): String {
        if (remainingBytes <= 0L || bytesPerSec <= 0L) return "--s"
        val sec = (remainingBytes / bytesPerSec).coerceAtLeast(1L)
        return when {
            sec < 60L -> "${sec}s"
            sec < 3600L -> "${sec / 60L}m ${sec % 60L}s"
            else -> "${sec / 3600L}h ${(sec % 3600L) / 60L}m"
        }
    }

    fun formatRelativeLastCheck(
        context: Context,
        lastCheckMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (lastCheckMs <= 0L) return context.getString(R.string.settings_check_update_never)
        val diff = (nowMs - lastCheckMs).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> context.getString(R.string.settings_check_update_just_now)
            diff < 3_600_000L -> "${diff / 60_000L} min"
            diff < 86_400_000L -> "${diff / 3_600_000L} h"
            else -> "${diff / 86_400_000L} d"
        }
    }

    fun shouldDeleteUpdateCandidate(
        fileName: String,
        filePath: String,
        currentVersion: String,
        pendingPath: String?,
        pendingVersion: String?,
        fileAgeMs: Long,
        isDownloading: Boolean
    ): Boolean {
        if (isDownloading || fileAgeMs < MIN_CLEANUP_AGE_MS) return false
        if (fileName.endsWith(".apk.tmp", ignoreCase = true)) {
            return fileAgeMs > STALE_TMP_AGE_MS
        }
        if (!fileName.endsWith(".apk", ignoreCase = true)) return false
        if (!pendingPath.isNullOrBlank() && filePath == pendingPath && !pendingVersion.isNullOrBlank()) {
            return !isVersionNewer(pendingVersion, currentVersion)
        }
        val match = UPDATE_APK_NAME_REGEX.matchEntire(fileName) ?: return false
        val apkVersion = match.groupValues[1]
        return !isVersionNewer(apkVersion, currentVersion)
    }

    fun cleanupInstalledUpdateApks(context: Context) {
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val pendingPath = prefs.getString(PREF_PENDING_UPDATE_APK_PATH, null)
            val pendingVersion = prefs.getString(PREF_PENDING_UPDATE_VERSION, null)
            val currentVersion = BuildConfig.VERSION_NAME
            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
            val now = System.currentTimeMillis()
            val updatesDirs = listOf(
                File("/storage/emulated/0/OM2/updates"),
                File(context.cacheDir, "updates")
            )

            var clearedPending = false
            for (dir in updatesDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val children = dir.listFiles() ?: continue
                var deletedInDir = false
                for (file in children) {
                    if (!file.isFile) continue
                    val ageMs = (now - file.lastModified()).coerceAtLeast(0L)
                    if (!shouldDeleteUpdateCandidate(
                            fileName = file.name,
                            filePath = file.absolutePath,
                            currentVersion = currentVersion,
                            pendingPath = pendingPath,
                            pendingVersion = pendingVersion,
                            fileAgeMs = ageMs,
                            isDownloading = isDownloadingUpdate
                        )
                    ) {
                        continue
                    }

                    if (file.name.endsWith(".apk", ignoreCase = true)) {
                        val pkgInfo = runCatching {
                            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        }.getOrNull()
                        if (pkgInfo != null &&
                            (pkgInfo.packageName != context.packageName ||
                                pkgInfo.longVersionCodeCompat > currentVersionCode)
                        ) {
                            continue
                        }
                    }

                    val isPendingTarget = pendingPath != null && file.absolutePath == pendingPath
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        deletedInDir = true
                        if (isPendingTarget) clearedPending = true
                    }
                }
                if (deletedInDir) {
                    runCatching { DirectorySnapshotCache.invalidate(Paths.get(dir.absolutePath)) }
                }
            }

            if (clearedPending) {
                prefs.edit()
                    .remove(PREF_PENDING_UPDATE_APK_PATH)
                    .remove(PREF_PENDING_UPDATE_VERSION)
                    .apply()
            }

            if (TerminalExpansionManager.isTermexInstalled(context)) {
                listOf(
                    File(context.cacheDir, "termex_install.apk"),
                    File(context.cacheDir, "termex_bootstrap.zip")
                ).forEach { cacheArtifact ->
                    if (cacheArtifact.exists() && cacheArtifact.isFile) {
                        val ageMs = (now - cacheArtifact.lastModified()).coerceAtLeast(0L)
                        if (ageMs >= MIN_CLEANUP_AGE_MS) {
                            runCatching { cacheArtifact.delete() }
                        }
                    }
                }
            }
        }
    }

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
            if (tagName.isNullOrEmpty()) return parseReleaseJsonFallback(jsonString, isDebug)
            val version = tagName.removePrefix("v").removePrefix("V").trim()
            val name = root.optString("name", "OpenManager $tagName")
            val rawBody = root.optString("body", "")
            val body = cleanReleaseNotes(rawBody)
            val htmlUrl = root.optString("html_url", "https://github.com/$GITHUB_REPO/releases/tag/$tagName")

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
            val targetAsset = selectAssetForDevice(assets, isDebug) ?: run {
                val channelSuffix = if (isDebug) "debug" else "release"
                val fallbackName = "OpenManager-$tagName-$channelSuffix.apk"
                val fallbackUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/$fallbackName"
                ReleaseAsset(fallbackName, 0L, fallbackUrl)
            }
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
        } catch (_: Throwable) {
            parseReleaseJsonFallback(jsonString, isDebug)
        }
    }

    fun parseReleaseListJson(
        jsonString: String,
        isDebug: Boolean,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): ReleaseInfo? {
        return try {
            val array = JSONArray(jsonString)
            val candidates = mutableListOf<ReleaseInfo>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue

                val tagName = obj.optString("tag_name")
                if (tagName.isNullOrEmpty()) continue
                val version = tagName.removePrefix("v").removePrefix("V").trim()
                val name = obj.optString("name", "OpenManager $tagName")
                val body = obj.optString("body", "")
                val htmlUrl = obj.optString("html_url", "https://github.com/$GITHUB_REPO/releases/tag/$tagName")

                val assetsArray = obj.optJSONArray("assets")
                val assets = mutableListOf<ReleaseAsset>()
                if (assetsArray != null) {
                    for (j in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.optJSONObject(j) ?: continue
                        val assetName = assetObj.optString("name")
                        val assetSize = assetObj.optLong("size", 0L)
                        val downloadUrl = assetObj.optString("browser_download_url")
                        if (assetName.isNotEmpty() && downloadUrl.isNotEmpty()) {
                            assets.add(ReleaseAsset(assetName, assetSize, downloadUrl))
                        }
                    }
                }
                val targetAsset = selectAssetForDevice(assets, isDebug) ?: run {
                    val channelSuffix = if (isDebug) "debug" else "release"
                    val fallbackName = "OpenManager-$tagName-$channelSuffix.apk"
                    val fallbackUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/$fallbackName"
                    ReleaseAsset(fallbackName, 0L, fallbackUrl)
                }
                candidates.add(
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
                )
            }

            if (candidates.isEmpty()) {
                return parseReleaseListJsonFallback(jsonString, isDebug, currentVersion)
            }

            // Find highest version among all releases returned
            var bestRelease = candidates.first()
            for (cand in candidates) {
                if (isVersionNewer(cand.version, bestRelease.version)) {
                    bestRelease = cand
                }
            }

            val cleanBody = cleanReleaseNotes(bestRelease.body)
            bestRelease.copy(body = cleanBody, intermediateChangelog = cleanBody)
        } catch (_: Throwable) {
            parseReleaseListJsonFallback(jsonString, isDebug, currentVersion)
        }
    }

    fun extractTopLevelJsonObjects(json: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in json.indices) {
            val ch = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                if (inString) {
                    escape = true
                }
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (ch == '{') {
                if (depth == 0) {
                    start = i
                }
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0 && start != -1) {
                    objects.add(json.substring(start, i + 1))
                    start = -1
                }
            }
        }
        return objects
    }

    fun parseReleaseListJsonFallback(
        jsonString: String,
        isDebug: Boolean,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): ReleaseInfo? {
        val blocks = extractTopLevelJsonObjects(jsonString)
        val candidates = mutableListOf<ReleaseInfo>()
        for (block in blocks) {
            val rel = parseReleaseJsonFallback(block, isDebug) ?: continue
            candidates.add(rel)
        }
        if (candidates.isEmpty()) return parseReleaseJsonFallback(jsonString, isDebug)

        var bestRelease = candidates.first()
        for (cand in candidates) {
            if (isVersionNewer(cand.version, bestRelease.version)) {
                bestRelease = cand
            }
        }

        val cleanBody = cleanReleaseNotes(bestRelease.body)
        return bestRelease.copy(body = cleanBody, intermediateChangelog = cleanBody)
    }

    fun cleanHtmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""
        var text = html
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        text = text.replace(Regex("(?i)<h1[^>]*>"), "\n# ")
            .replace(Regex("(?i)</h1>"), "\n")
            .replace(Regex("(?i)<h2[^>]*>"), "\n## ")
            .replace(Regex("(?i)</h2>"), "\n")
            .replace(Regex("(?i)<h3[^>]*>"), "\n### ")
            .replace(Regex("(?i)</h3>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "\n- ")
            .replace(Regex("(?i)</li>"), "")
            .replace(Regex("(?i)<p[^>]*>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<strong[^>]*>"), "**")
            .replace(Regex("(?i)</strong>"), "**")
            .replace(Regex("(?i)<code[^>]*>"), "`")
            .replace(Regex("(?i)</code>"), "`")
            .replace(Regex("</?[a-zA-Z][^>]*>"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return text
    }

    fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val section = raw.trim()

        val dividerRegex = Regex("(?i)^(?:---+|___+|\\*\\*\\*+|<hr\\s*/?>|#+\\s+OpenManager\\s+v[0-9.]+|#+\\s+v[0-9.]+)[:\\s]*$")
        val lines = section.lines()
        val currentLines = mutableListOf<String>()
        var foundTopTitle = false

        for (line in lines) {
            val trimmed = line.trim()
            // Skip redundant top release title "# OpenManager v1.9.XX: ..." or "# v1.9.XX"
            if (!foundTopTitle && trimmed.matches(Regex("(?i)^#+\\s*(OpenManager\\s+)?v[0-9.]+.*"))) {
                foundTopTitle = true
                continue
            }
            // Stop at divider or subsequent release header (strictly isolates latest release notes)
            if (foundTopTitle && dividerRegex.matches(trimmed)) {
                break
            }
            if (currentLines.isNotEmpty() && dividerRegex.matches(trimmed)) {
                break
            }
            currentLines.add(line)
        }

        var body = currentLines.joinToString("\n").trim()
        if (body.isEmpty()) {
            body = section
        }

        // Clean markdown headings: "### 📐 Title" -> "📐 Title:"
        body = body.replace(Regex("(?m)^#+\\s*(.+?):?\\s*$"), "$1:")

        // Remove bold/italic markers
        body = body.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        body = body.replace(Regex("__([^_]+)__"), "$1")

        // Remove code backticks
        body = body.replace(Regex("`([^`]+)`"), "$1")

        // Format bullet points cleanly:
        // Sub-bullets (2 or more leading spaces/tabs)
        body = body.replace(Regex("(?m)^[ \t]{2,}[-*][ \t]+"), "   ▫ ")
        // Top-level bullets
        body = body.replace(Regex("(?m)^[ \t]*[-*][ \t]+"), "• ")

        // Remove real HTML tags if any remain
        body = body.replace(Regex("</?[a-zA-Z][^>]*>"), "")

        // Collapse excessive newlines
        body = body.replace(Regex("\n{3,}"), "\n\n").trim()

        return body
    }

    fun parseReleaseAtomFeed(
        atomXml: String,
        isDebug: Boolean,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): ReleaseInfo? {
        return try {
            val entryRegex = Regex("<entry>([\\s\\S]*?)</entry>")
            val candidates = mutableListOf<ReleaseInfo>()

            for (match in entryRegex.findAll(atomXml)) {
                val entryBlock = match.groupValues[1]

                val tagMatch = Regex("<id>[^<]*/([^/<]+)</id>").find(entryBlock)
                    ?: Regex("<link[^>]*href=\"[^\"]*/releases/tag/([^\"]+)\"").find(entryBlock)
                    ?: continue
                val tagName = tagMatch.groupValues[1].trim()
                if (tagName.isEmpty()) continue
                val version = tagName.removePrefix("v").removePrefix("V").trim()

                val titleMatch = Regex("<title>([\\s\\S]*?)</title>").find(entryBlock)
                val name = titleMatch?.groupValues?.get(1)?.trim() ?: "OpenManager $tagName"

                val linkMatch = Regex("<link[^>]*href=\"([^\"]+)\"").find(entryBlock)
                val htmlUrl = linkMatch?.groupValues?.get(1)?.trim() ?: "https://github.com/$GITHUB_REPO/releases/tag/$tagName"

                val contentMatch = Regex("<content[^>]*>([\\s\\S]*?)</content>").find(entryBlock)
                val rawHtml = contentMatch?.groupValues?.get(1) ?: ""
                val cleanedBody = cleanHtmlToMarkdown(rawHtml)

                val channelSuffix = if (isDebug) "debug" else "release"
                val apkName = "OpenManager-$tagName-$channelSuffix.apk"
                val downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/$apkName"
                val targetAsset = ReleaseAsset(apkName, 0L, downloadUrl)

                candidates.add(
                    ReleaseInfo(
                        tagName = tagName,
                        version = version,
                        name = name,
                        body = cleanedBody,
                        htmlUrl = htmlUrl,
                        assets = listOf(targetAsset),
                        targetAsset = targetAsset,
                        isDebugChannel = isDebug
                    )
                )
            }

            if (candidates.isEmpty()) return null

            var bestRelease = candidates.first()
            for (cand in candidates) {
                if (isVersionNewer(cand.version, bestRelease.version)) {
                    bestRelease = cand
                }
            }

            val cleanBody = cleanReleaseNotes(bestRelease.body)
            bestRelease.copy(body = cleanBody, intermediateChangelog = cleanBody)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseReleaseJsonFallback(json: String, isDebug: Boolean): ReleaseInfo? {
        val tagMatch = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json) ?: return null
        val tagName = tagMatch.groupValues[1]
        val version = tagName.removePrefix("v").removePrefix("V").trim()

        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val name = nameMatch?.groupValues?.get(1) ?: "OpenManager $tagName"

        val htmlUrlMatch = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val htmlUrl = htmlUrlMatch?.groupValues?.get(1) ?: "https://github.com/$GITHUB_REPO/releases"

        val bodyMatch = Regex("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        val rawBody = bodyMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
        val body = cleanReleaseNotes(rawBody)

        val assets = mutableListOf<ReleaseAsset>()
        val genericAssetRegex = Regex("\\{[^{}]*\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}")
        for (match in genericAssetRegex.findAll(json)) {
            val block = match.value
            val aName = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: continue
            val aSize = Regex("\"size\"\\s*:\\s*([0-9]+)").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val aUrl = match.groupValues[1]
            assets.add(ReleaseAsset(aName, aSize, aUrl))
        }

        val targetAsset = selectAssetForDevice(assets, isDebug) ?: run {
            val channelSuffix = if (isDebug) "debug" else "release"
            val fallbackName = "OpenManager-$tagName-$channelSuffix.apk"
            val fallbackUrl = "https://github.com/$GITHUB_REPO/releases/download/$tagName/$fallbackName"
            ReleaseAsset(fallbackName, 0L, fallbackUrl)
        }
        return ReleaseInfo(
            tagName = tagName,
            version = version,
            name = name,
            body = body,
            htmlUrl = htmlUrl,
            assets = assets,
            targetAsset = targetAsset,
            isDebugChannel = isDebug
        )
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun openConnectionWithRedirects(
        urlString: String,
        maxRedirects: Int = 5,
        rangeStartBytes: Long = 0L
    ): HttpURLConnection {
        var url = URL(urlString)
        var redirects = 0
        while (redirects < maxRedirects) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
            if (rangeStartBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$rangeStartBytes-")
            }
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

    private fun executeHttpGet(urlString: String, etag: String? = null): Triple<Int, String, String?> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 18000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github.v3+json, application/atom+xml, */*")
            setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
            if (!etag.isNullOrBlank()) {
                setRequestProperty("If-None-Match", etag)
            }
        }
        return try {
            val code = conn.responseCode
            val responseEtag = conn.getHeaderField("ETag")
            val body = when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED -> ""
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                else -> conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            Triple(code, body, responseEtag ?: etag)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchLatestRelease(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val isDebug = isDebugBuild(context)
        val curVer = BuildConfig.VERSION_NAME
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val cachedEtag = prefs.getString(PREF_CACHED_ETAG, null)
        val cachedJson = prefs.getString(PREF_CACHED_RELEASE_JSON, null)

        // 1. Primary: Lightweight /releases/latest with conditional ETag caching
        try {
            val effectiveEtag = if (!cachedJson.isNullOrBlank()) cachedEtag else null
            val (code, response, responseEtag) = executeHttpGet(GITHUB_LATEST_RELEASE_URL, effectiveEtag)
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && !cachedJson.isNullOrBlank()) {
                val cachedInfo = parseReleaseJson(cachedJson, isDebug)
                if (cachedInfo != null) return@withContext cachedInfo
            } else if (code == 200 && response.isNotBlank()) {
                val info = parseReleaseJson(response, isDebug)
                if (info != null) {
                    prefs.edit()
                        .putString(PREF_CACHED_RELEASE_JSON, response)
                        .apply {
                            if (!responseEtag.isNullOrBlank()) putString(PREF_CACHED_ETAG, responseEtag)
                        }
                        .apply()
                    return@withContext info
                }
            }
        } catch (_: Exception) {
            // Network, rate limit or parse issue, continue to fallbacks
        }

        // 2. Secondary: Zero-rate-limit Atom feed fallback (/releases.atom)
        try {
            val (code, response, _) = executeHttpGet(GITHUB_RELEASES_ATOM_URL)
            if (code == 200 && response.isNotBlank()) {
                val info = parseReleaseAtomFeed(response, isDebug, curVer)
                if (info != null) return@withContext info
            }
        } catch (_: Exception) {
            // Continue to releases list endpoint
        }

        // 3. Tertiary: Releases list API (/releases?per_page=20)
        val (code, response, _) = executeHttpGet(GITHUB_RELEASES_LIST_URL)
        if (code == 200 && response.isNotBlank()) {
            val info = parseReleaseListJson(response, isDebug, curVer)
            if (info != null) return@withContext info
        } else {
            throw IOException("GitHub API HTTP $code: $response")
        }

        null
    }

    fun checkUpdate(
        activity: FragmentActivity,
        isManual: Boolean,
        onCheckFinished: (() -> Unit)? = null
    ) {
        ensureAutoUpdateDefaultMigrated(activity)
        if (!activity.isUiAlive) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val now = System.currentTimeMillis()

        if (!isManual && !isAutoCheckDue(prefs.getLong(PREF_LAST_CHECK_TIME, 0L), now)) {
            return
        }

        if (!isCheckingUpdate.compareAndSet(false, true)) {
            return
        }

        var loadingDialog: AlertDialog? = null
        if (isManual) {
            loadingDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_checking_title)
                .setMessage(R.string.update_checking_message)
                .setCancelable(true)
                .create()
                .bindToLifecycle(activity)
            if (activity.isUiAlive) {
                loadingDialog.show()
            }
        }

        val checkJob = activity.lifecycleScope.launch {
            try {
                if (!isManual) {
                    delay(AUTO_STARTUP_DELAY_MS)
                    if (!activity.isUiAlive || !Settings.AUTO_CHECK_UPDATES.valueCompat || !isNetworkAvailable(activity)) {
                        return@launch
                    }
                } else if (!isNetworkAvailable(activity)) {
                    throw IOException(activity.getString(R.string.om_termex_network_error))
                }

                val releaseInfo = fetchLatestRelease(activity)
                if (!isManual && releaseInfo != null &&
                    isVersionNewer(releaseInfo.version, BuildConfig.VERSION_NAME) &&
                    !withContext(Dispatchers.IO) { isApkPublished(releaseInfo) }
                ) {
                    // Not recorded as checked: the next time the app is opened it looks again.
                    return@launch
                }
                prefs.edit().putLong(PREF_LAST_CHECK_TIME, System.currentTimeMillis()).apply()

                loadingDialog?.dismissSafely()
                if (!activity.isUiAlive) return@launch

                if (releaseInfo == null) {
                    if (isManual) {
                        activity.showSafeDialog {
                            setTitle(R.string.update_check_error_title)
                            setMessage(
                                activity.getString(
                                    R.string.update_check_error_message,
                                    activity.getString(R.string.om_update_info_unavailable)
                                )
                            )
                            setPositiveButton(R.string.retry) { _, _ -> checkUpdate(activity, isManual = true, onCheckFinished) }
                            setNegativeButton(android.R.string.cancel, null)
                        }
                    }
                    return@launch
                }

                val skippedVersion = prefs.getString(PREF_SKIPPED_UPDATE_VERSION, null)
                if (shouldNotifyAutoUpdate(releaseInfo.version, BuildConfig.VERSION_NAME, skippedVersion, isManual)) {
                    showUpdateDialog(activity, releaseInfo)
                } else if (isManual && !isVersionNewer(releaseInfo.version, BuildConfig.VERSION_NAME)) {
                    val isDebug = isDebugBuild(activity)
                    val channelStr = activity.getString(
                        if (isDebug) R.string.update_channel_debug else R.string.update_channel_release
                    )
                    activity.showSafeDialog {
                        setTitle(R.string.update_up_to_date_title)
                        setMessage(
                            activity.getString(
                                R.string.update_up_to_date_message,
                                "v" + BuildConfig.VERSION_NAME,
                                channelStr
                            )
                        )
                        setPositiveButton(android.R.string.ok, null)
                    }
                }
            } catch (e: CancellationException) {
                loadingDialog?.dismissSafely()
            } catch (e: Exception) {
                loadingDialog?.dismissSafely()
                if (!activity.isUiAlive) return@launch
                if (isManual) {
                    val errorMsg = e.localizedMessage ?: e.message ?: e.toString()
                    activity.showSafeDialog {
                        setTitle(R.string.update_check_error_title)
                        setMessage(activity.getString(R.string.update_check_error_message, errorMsg))
                        setPositiveButton(R.string.retry) { _, _ -> checkUpdate(activity, isManual = true, onCheckFinished) }
                        setNegativeButton(android.R.string.cancel, null)
                    }
                }
            } finally {
                isCheckingUpdate.set(false)
                if (activity.isUiAlive) {
                    onCheckFinished?.invoke()
                }
            }
        }
        loadingDialog?.setOnCancelListener { checkJob.cancel() }
    }

    private fun makeRoundedBg(fillColor: Int, strokeColor: Int, radiusDp: Float, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun resolveUpdatesDir(context: Context): File {
        val baseDir = File("/storage/emulated/0/OM2/updates")
        return if (baseDir.exists() || baseDir.mkdirs()) {
            baseDir
        } else {
            File(context.cacheDir, "updates").apply { mkdirs() }
        }
    }

    private fun isValidDownloadedApk(context: Context, file: File, expectedSize: Long): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        val hasMagic = runCatching {
            FileInputStream(file).use { ins ->
                val header = ByteArray(4)
                val read = ins.read(header)
                hasZipLocalFileHeaderMagic(header, read)
            }
        }.getOrDefault(false)
        if (!hasMagic) return false

        val pkgInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false
        return pkgInfo.packageName == context.packageName
    }

    private fun findExistingValidUpdateApk(context: Context, releaseInfo: ReleaseInfo): File? {
        val fileName = releaseInfo.targetAsset.name
        val expectedSize = releaseInfo.targetAsset.size
        val candidates = listOf(
            File("/storage/emulated/0/OM2/updates", fileName),
            File(File(context.cacheDir, "updates"), fileName)
        )
        return candidates.firstOrNull { isValidDownloadedApk(context, it, expectedSize) }
    }

    fun showUpdateDialog(activity: FragmentActivity, releaseInfo: ReleaseInfo) {
        if (!activity.isUiAlive) return

        activeUpdateDialogRef?.get()?.dismissSafely()

        val palette = OpenManagerPalette.from(activity)
        val density = activity.resources.displayMetrics.density
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_available, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
            .bindToLifecycle(activity)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activeUpdateDialogRef = WeakReference(dialog)
        unansweredRelease = releaseInfo
        dialog.setOnCancelListener { unansweredRelease = null }

        val rootUpdateCard = view.findViewById<View>(R.id.rootUpdateCard)
        val tvUpdateTitle = view.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvUpdateChannel = view.findViewById<TextView>(R.id.tvUpdateChannel)
        val dividerUpdateTop = view.findViewById<View>(R.id.dividerUpdateTop)
        val boxUpdateVersion = view.findViewById<View>(R.id.boxUpdateVersion)
        val tvUpdateVersionInfo = view.findViewById<TextView>(R.id.tvUpdateVersionInfo)
        val tvUpdateFileInfo = view.findViewById<TextView>(R.id.tvUpdateFileInfo)
        val tvUpdateChangelogHeader = view.findViewById<TextView>(R.id.tvUpdateChangelogHeader)
        val scrollUpdateChangelog = view.findViewById<View>(R.id.scrollUpdateChangelog)
        val tvUpdateChangelog = view.findViewById<TextView>(R.id.tvUpdateChangelog)
        val dividerUpdateBottom = view.findViewById<View>(R.id.dividerUpdateBottom)
        val btnUpdateLater = view.findViewById<MaterialButton>(R.id.btnUpdateLater)
        val btnUpdateSkip = view.findViewById<MaterialButton>(R.id.btnUpdateSkip)
        val btnUpdateGitHub = view.findViewById<MaterialButton>(R.id.btnUpdateGitHub)
        val btnUpdateDownload = view.findViewById<MaterialButton>(R.id.btnUpdateDownload)
        val cbAutoCheckUpdates = view.findViewById<MaterialCheckBox>(R.id.cbAutoCheckUpdates)

        rootUpdateCard?.background = makeRoundedBg(palette.dialogBg, palette.dividerLineColor, 16f, density)
        boxUpdateVersion?.background = makeRoundedBg(palette.cardBg, palette.dividerLineColor, 8f, density)
        scrollUpdateChangelog?.background = makeRoundedBg(palette.cardBg, palette.dividerLineColor, 8f, density)
        dividerUpdateTop?.setBackgroundColor(palette.dividerLineColor)
        dividerUpdateBottom?.setBackgroundColor(palette.dividerLineColor)
        tvUpdateTitle.setTextColor(palette.textPrimary)
        tvUpdateVersionInfo.setTextColor(palette.textPrimary)
        tvUpdateFileInfo.setTextColor(palette.textSecondary)
        tvUpdateChangelogHeader?.setTextColor(palette.accentColor)
        tvUpdateChangelog.setTextColor(palette.filePrimaryText)
        btnUpdateLater.setTextColor(palette.textSecondary)
        btnUpdateSkip?.setTextColor(palette.textSecondary)
        btnUpdateGitHub.setTextColor(palette.accentColor)

        tvUpdateTitle.text = activity.getString(R.string.update_available_title)

        val channelLabel = activity.getString(
            if (releaseInfo.isDebugChannel) R.string.update_channel_debug else R.string.update_channel_release
        )
        tvUpdateChannel.text = activity.getString(R.string.update_detected_channel, channelLabel)
        tvUpdateChannel.setTextColor(
            when {
                releaseInfo.isDebugChannel && palette.isDark -> Color.parseColor("#64B5F6")
                releaseInfo.isDebugChannel -> Color.parseColor("#1565C0")
                palette.isDark -> Color.parseColor("#81C784")
                else -> Color.parseColor("#2E7D32")
            }
        )

        val newVerText = activity.getString(R.string.update_new_version_label, "v" + releaseInfo.version)
        val curVerText = activity.getString(R.string.update_current_version_label, "v" + BuildConfig.VERSION_NAME)
        tvUpdateVersionInfo.text = "$newVerText\n$curVerText"

        val sizeFormatted = if (releaseInfo.targetAsset.size > 0) formatSize(releaseInfo.targetAsset.size) else "APK"
        tvUpdateFileInfo.text = activity.getString(R.string.update_target_apk_label, releaseInfo.targetAsset.name, sizeFormatted)

        val rawChangelog = if (releaseInfo.body.isNotBlank()) {
            releaseInfo.body
        } else if (releaseInfo.intermediateChangelog.isNotBlank()) {
            releaseInfo.intermediateChangelog
        } else if (releaseInfo.name.isNotBlank()) {
            releaseInfo.name
        } else {
            activity.getString(R.string.update_up_to_date_title)
        }
        tvUpdateChangelog.text = cleanReleaseNotes(rawChangelog)

        cbAutoCheckUpdates?.apply {
            setTextColor(palette.textSecondary)
            buttonTintList = ColorStateList.valueOf(palette.accentColor)
            isChecked = Settings.AUTO_CHECK_UPDATES.valueCompat
            setOnCheckedChangeListener { _, isChecked ->
                Settings.AUTO_CHECK_UPDATES.putValue(isChecked)
                if (!isChecked) {
                    Toast.makeText(activity, R.string.update_auto_disabled_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnUpdateLater.setOnClickListener {
            unansweredRelease = null
            dialog.dismissSafely()
        }

        btnUpdateSkip?.setOnClickListener {
            unansweredRelease = null
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putString(PREF_SKIPPED_UPDATE_VERSION, releaseInfo.version)
                .apply()
            Toast.makeText(
                activity,
                activity.getString(R.string.update_version_skipped_toast, "v" + releaseInfo.version),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismissSafely()
        }

        btnUpdateGitHub.setOnClickListener {
            activity.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.htmlUrl)))
        }

        val preDownloadedApk = findExistingValidUpdateApk(activity, releaseInfo)
        if (preDownloadedApk != null) {
            btnUpdateDownload.setText(R.string.update_action_install_ready)
        }

        btnUpdateDownload.setOnClickListener {
            unansweredRelease = null
            dialog.dismissSafely()
            if (preDownloadedApk != null && isValidDownloadedApk(activity, preDownloadedApk, releaseInfo.targetAsset.size)) {
                PreferenceManager.getDefaultSharedPreferences(activity).edit()
                    .putString(PREF_PENDING_UPDATE_APK_PATH, preDownloadedApk.absolutePath)
                    .putString(PREF_PENDING_UPDATE_VERSION, releaseInfo.version)
                    .apply()
                launchApkInstallation(activity, preDownloadedApk)
            } else {
                downloadAndInstall(activity, releaseInfo)
            }
        }

        dialog.show()
    }

    fun downloadAndInstall(activity: FragmentActivity, releaseInfo: ReleaseInfo) {
        if (!activity.isUiAlive) return

        val palette = OpenManagerPalette.from(activity)
        val density = activity.resources.displayMetrics.density
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_download, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .create()
            .bindToLifecycle(activity)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val rootDownloadCard = view.findViewById<View>(R.id.rootDownloadCard)
        val tvDownloadTitle = view.findViewById<TextView>(R.id.tvDownloadTitle)
        val tvDownloadFilename = view.findViewById<TextView>(R.id.tvDownloadFilename)
        val dividerDownloadTop = view.findViewById<View>(R.id.dividerDownloadTop)
        val pbDownloadProgress = view.findViewById<LinearProgressIndicator>(R.id.pbDownloadProgress)
        val tvDownloadStats = view.findViewById<TextView>(R.id.tvDownloadStats)
        val tvDownloadSpeed = view.findViewById<TextView>(R.id.tvDownloadSpeed)
        val dividerDownloadBottom = view.findViewById<View>(R.id.dividerDownloadBottom)
        val btnCancelDownload = view.findViewById<Button>(R.id.btnCancelDownload)

        rootDownloadCard?.background = makeRoundedBg(palette.dialogBg, palette.dividerLineColor, 16f, density)
        dividerDownloadTop?.setBackgroundColor(palette.dividerLineColor)
        dividerDownloadBottom?.setBackgroundColor(palette.dividerLineColor)
        tvDownloadTitle?.setTextColor(palette.textPrimary)
        tvDownloadFilename.setTextColor(palette.textSecondary)
        tvDownloadStats.setTextColor(palette.textPrimary)
        tvDownloadSpeed.setTextColor(if (palette.isDark) Color.parseColor("#81C784") else Color.parseColor("#2E7D32"))
        pbDownloadProgress.setIndicatorColor(palette.accentColor)
        pbDownloadProgress.trackColor = palette.dividerLineColor

        tvDownloadFilename.text = releaseInfo.targetAsset.name
        pbDownloadProgress.progress = 0
        val totalExpectedSize = if (releaseInfo.targetAsset.size > 0) formatSize(releaseInfo.targetAsset.size) else "-- MB"
        tvDownloadStats.text = activity.getString(R.string.update_downloading_format, "0 MB", totalExpectedSize, 0)
        tvDownloadSpeed.text = activity.getString(R.string.update_download_speed_format, "0 MB")

        var downloadJob: Job? = null
        var userCancelled = false

        btnCancelDownload.setOnClickListener {
            userCancelled = true
            downloadJob?.cancel()
            dialog.dismissSafely()
        }

        dialog.show()

        downloadJob = activity.lifecycleScope.launch {
            var tempFile: File? = null
            var finalFile: File? = null
            isDownloadingUpdate = true
            UpdateDownloadService.start(activity, releaseInfo.targetAsset.name)
            try {
                withContext(Dispatchers.IO) {
                    val existingValid = findExistingValidUpdateApk(activity, releaseInfo)
                    if (existingValid != null) {
                        finalFile = existingValid
                        return@withContext
                    }

                    val updatesDir = resolveUpdatesDir(activity)
                    val apkName = releaseInfo.targetAsset.name
                    val destinationFile = File(updatesDir, apkName)
                    val temporaryFile = File(updatesDir, "$apkName.tmp")
                    finalFile = destinationFile
                    tempFile = temporaryFile

                    val expectedSize = releaseInfo.targetAsset.size
                    var totalBytes = expectedSize
                    // Phones cut the network of apps in the background (user report: "Software
                    // caused connection abort" when minimizing): the service below keeps the app in
                    // the foreground, and a dropped connection resumes where it stopped.
                    var attempt = 0
                    while (true) {
                        try {
                            var existingTempBytes = if (temporaryFile.exists() && temporaryFile.isFile) temporaryFile.length() else 0L
                            if (expectedSize > 0L && existingTempBytes >= expectedSize) {
                                temporaryFile.delete()
                                existingTempBytes = 0L
                            }

                            var connection = openConnectionWithRedirects(
                                releaseInfo.targetAsset.downloadUrl,
                                rangeStartBytes = existingTempBytes
                            )
                            try {
                                var decision = resolveDownloadResume(connection.responseCode, existingTempBytes)
                                if (decision.resetTempBeforeRetry) {
                                    connection.disconnect()
                                    temporaryFile.delete()
                                    existingTempBytes = 0L
                                    connection = openConnectionWithRedirects(
                                        releaseInfo.targetAsset.downloadUrl,
                                        rangeStartBytes = 0L
                                    )
                                    decision = resolveDownloadResume(connection.responseCode, 0L)
                                }

                                val responseCode = connection.responseCode
                                if (responseCode !in 200..299) {
                                    throw IOException("HTTP $responseCode")
                                }

                                val responseLen = connection.contentLength.toLong()
                                totalBytes = when {
                                    decision.append && responseLen > 0L -> decision.startOffset + responseLen
                                    expectedSize > 0L -> expectedSize
                                    else -> responseLen
                                }

                                var downloadedBytes = decision.startOffset
                                var lastUpdateTime = SystemClock.elapsedRealtime()
                                var lastNotifyTime = 0L
                                var bytesSinceLastUpdate = 0L
                                var smoothedSpeed = 0L
                                var isResumedSession = decision.append

                                if (isResumedSession && totalBytes > 0L) {
                                    val initPct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    val curStr = formatSize(downloadedBytes)
                                    val totStr = formatSize(totalBytes)
                                    withContext(Dispatchers.Main) {
                                        if (activity.isUiAlive) {
                                            pbDownloadProgress.progress = initPct
                                            tvDownloadStats.text = activity.getString(
                                                R.string.update_resuming_format,
                                                curStr,
                                                totStr,
                                                initPct
                                            )
                                        }
                                    }
                                }

                                connection.inputStream.use { input ->
                                    FileOutputStream(temporaryFile, decision.append).use { output ->
                                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            coroutineContext.ensureActive()
                                            output.write(buffer, 0, read)
                                            downloadedBytes += read
                                            bytesSinceLastUpdate += read
                                            debugAbortOnce(activity, downloadedBytes - decision.startOffset)

                                            val nowTime = SystemClock.elapsedRealtime()
                                            val timeDiff = nowTime - lastUpdateTime
                                            if (timeDiff >= 150L) {
                                                val percent = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                                val instantSpeed = (bytesSinceLastUpdate * 1000L) / timeDiff
                                                smoothedSpeed = if (smoothedSpeed <= 0L) {
                                                    instantSpeed
                                                } else {
                                                    (smoothedSpeed * 3L + instantSpeed) / 4L
                                                }
                                                val curDownloaded = downloadedBytes
                                                val etaStr = formatEtaSeconds(totalBytes - curDownloaded, smoothedSpeed)
                                                val speedNow = smoothedSpeed
                                                val resumedFlag = isResumedSession
                                                isResumedSession = false
                                                withContext(Dispatchers.Main) {
                                                    if (activity.isUiAlive) {
                                                        pbDownloadProgress.progress = percent.coerceIn(0, 100)
                                                        val statsRes = if (resumedFlag) {
                                                            R.string.update_resuming_format
                                                        } else {
                                                            R.string.update_downloading_format
                                                        }
                                                        tvDownloadStats.text = activity.getString(
                                                            statsRes,
                                                            formatSize(curDownloaded),
                                                            formatSize(totalBytes),
                                                            percent
                                                        )
                                                        tvDownloadSpeed.text = if (totalBytes > 0L && speedNow > 0L) {
                                                            activity.getString(
                                                                R.string.update_speed_eta_format,
                                                                formatSize(speedNow),
                                                                etaStr
                                                            )
                                                        } else {
                                                            activity.getString(
                                                                R.string.update_download_speed_format,
                                                                formatSize(speedNow)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (nowTime - lastNotifyTime >= 1000L) {
                                                    lastNotifyTime = nowTime
                                                    UpdateDownloadService.progress(
                                                        activity, releaseInfo.targetAsset.name, percent.coerceIn(0, 100)
                                                    )
                                                }
                                                lastUpdateTime = nowTime
                                                bytesSinceLastUpdate = 0L
                                            }
                                        }
                                        output.flush()
                                    }
                                }

                                if (totalBytes > 0L && (downloadedBytes != totalBytes || temporaryFile.length() != totalBytes)) {
                                    throw IOException("Incomplete download ($downloadedBytes / $totalBytes bytes)")
                                }
                            } finally {
                                connection.disconnect()
                            }
                            break
                        } catch (e: IOException) {
                            attempt++
                            if (attempt > DOWNLOAD_RETRIES || !isRetryableDownloadError(e)) throw e
                            android.util.Log.w(TAG_DOWNLOAD, "Update download interrupted (try $attempt): $e; resuming")
                            withContext(Dispatchers.Main) {
                                if (activity.isUiAlive) {
                                    tvDownloadSpeed.text = activity.getString(R.string.update_download_resuming)
                                }
                            }
                            delay(1500L * attempt)
                            waitForNetwork(activity, 30_000L)
                        }
                    }

                    val validationSize = if (totalBytes > 0L) totalBytes else expectedSize
                    if (!isValidDownloadedApk(activity, temporaryFile, expectedSize = validationSize)) {
                        temporaryFile.delete()
                        throw IOException(activity.getString(R.string.update_corrupt_apk_error))
                    }

                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    if (!temporaryFile.renameTo(destinationFile)) {
                        temporaryFile.copyTo(destinationFile, overwrite = true)
                        temporaryFile.delete()
                    }
                    runCatching { DirectorySnapshotCache.invalidate(Paths.get(updatesDir.absolutePath)) }
                }

                dialog.dismissSafely()
                if (!activity.isUiAlive) return@launch
                UpdateDownloadService.stop(activity)

                Toast.makeText(activity, activity.getString(R.string.update_download_success_title), Toast.LENGTH_SHORT).show()

                finalFile?.let { installedFile ->
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putString(PREF_PENDING_UPDATE_APK_PATH, installedFile.absolutePath)
                        .putString(PREF_PENDING_UPDATE_VERSION, releaseInfo.version)
                        .apply()
                    android.util.Log.i(TAG_DOWNLOAD, "Update downloaded: ${installedFile.name}")
                    installWhenInFront(activity, installedFile, releaseInfo.version)
                }
            } catch (e: CancellationException) {
                dialog.dismissSafely()
                if (userCancelled) {
                    tempFile?.delete()
                }
            } catch (e: Exception) {
                dialog.dismissSafely()
                if (!activity.isUiAlive) return@launch
                val errorMsg = e.localizedMessage ?: e.message ?: e.toString()
                activity.showSafeDialog {
                    setTitle(R.string.update_download_failed_title)
                    setMessage(activity.getString(R.string.update_download_failed_message, errorMsg))
                    setPositiveButton(R.string.retry) { _, _ -> downloadAndInstall(activity, releaseInfo) }
                    setNegativeButton(android.R.string.cancel, null)
                }
            } finally {
                isDownloadingUpdate = false
                UpdateDownloadService.stop(activity)
            }
        }
    }

    private const val TAG_DOWNLOAD = "OMUpdate"
    private const val DOWNLOAD_RETRIES = 3

    /** Debug builds: while this file is in the app's files folder, the next download drops once. */
    const val DEBUG_ABORT_FLAG = "om_update_abort_once"

    private fun debugAbortOnce(context: Context, bytesThisTry: Long) {
        if (!BuildConfig.DEBUG || bytesThisTry < 1_000_000L) return
        val flag = File(context.filesDir, DEBUG_ABORT_FLAG)
        if (flag.exists() && flag.delete()) {
            throw java.net.SocketException("Software caused connection abort (debug)")
        }
    }

    /** A dropped or timed-out connection is worth resuming; a 404 or a corrupt file is not. */
    fun isRetryableDownloadError(e: IOException): Boolean {
        val message = e.message.orEmpty()
        if (message.startsWith("HTTP 4")) return false
        return e is java.net.SocketException || e is java.net.SocketTimeoutException ||
            e is java.io.EOFException || e is javax.net.ssl.SSLException ||
            e is java.net.UnknownHostException || message.startsWith("Incomplete download") ||
            message.startsWith("HTTP 5") || message.contains("unexpected end of stream", ignoreCase = true)
    }

    private suspend fun waitForNetwork(context: Context, maxMs: Long) {
        val end = SystemClock.elapsedRealtime() + maxMs
        while (!isNetworkAvailable(context) && SystemClock.elapsedRealtime() < end) delay(1000L)
    }

    /**
     * Android only opens its installer from the app in front. Downloaded in the background: a
     * notification, and the installer opens as soon as the app is back.
     */
    private fun installWhenInFront(activity: FragmentActivity, apk: File, version: String) {
        val lifecycle = activity.lifecycle
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            launchApkInstallation(activity, apk)
            return
        }
        UpdateDownloadService.readyToInstall(activity, version)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                lifecycle.removeObserver(this)
                UpdateDownloadService.cancelReady(activity)
                if (apk.isFile) launchApkInstallation(activity, apk)
            }
        })
    }

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** The device's package installer (never OpenManager), or null to let Android choose. */
    fun systemInstallerPackage(context: Context, apkUri: Uri): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val probes = listOf(
            Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(apkUri, APK_MIME),
            Intent(Intent.ACTION_VIEW).setDataAndType(apkUri, APK_MIME)
        )
        for (probe in probes) {
            val candidates = runCatching { pm.queryIntentActivities(probe, 0) }.getOrNull().orEmpty()
                .map { it.activityInfo.packageName }
                .filter { it != context.packageName }
            pickInstaller(candidates)?.let { return it }
        }
        return null
    }

    /** Prefers the platform installer when several apps claim APKs. */
    fun pickInstaller(candidates: List<String>): String? =
        candidates.firstOrNull { it == "com.google.android.packageinstaller" || it == "com.android.packageinstaller" }
            ?: candidates.firstOrNull { it.endsWith(".packageinstaller") }
            ?: candidates.firstOrNull()

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
                    setDataAndType(apkUri, APK_MIME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // OpenManager itself opens APKs too: without this Android asks "Open with" and
                    // picking OpenManager would show the APK instead of installing the update.
                    systemInstallerPackage(activity, apkUri)?.let { setPackage(it) }
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.om_update_installer_error_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

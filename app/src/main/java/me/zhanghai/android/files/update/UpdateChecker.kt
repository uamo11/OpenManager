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

    private fun executeHttpGet(urlString: String): Pair<Int, String> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 18000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github.v3+json, application/atom+xml, */*")
            setRequestProperty("User-Agent", "OpenManager-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            Pair(code, body)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchLatestRelease(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val isDebug = isDebugBuild(context)
        val curVer = BuildConfig.VERSION_NAME

        // 1. Primary: Query GitHub Releases List API (/releases?per_page=20)
        try {
            val (code, response) = executeHttpGet(GITHUB_RELEASES_LIST_URL)
            if (code == 200 && response.isNotBlank()) {
                val info = parseReleaseListJson(response, isDebug, curVer)
                if (info != null) return@withContext info
            }
        } catch (_: Exception) {
            // Network or parse issue, continue to fallbacks
        }

        // 2. Secondary: Zero-rate-limit Atom feed fallback (/releases.atom)
        // Publicly cached, no 60 req/hr anonymous API rate limit
        try {
            val (code, response) = executeHttpGet(GITHUB_RELEASES_ATOM_URL)
            if (code == 200 && response.isNotBlank()) {
                val info = parseReleaseAtomFeed(response, isDebug, curVer)
                if (info != null) return@withContext info
            }
        } catch (_: Exception) {
            // Continue to latest release endpoint
        }

        // 3. Tertiary: Direct /releases/latest endpoint
        try {
            val (code, response) = executeHttpGet(GITHUB_LATEST_RELEASE_URL)
            if (code == 200 && response.isNotBlank()) {
                val info = parseReleaseJson(response, isDebug)
                if (info != null) return@withContext info
            } else {
                throw IOException("GitHub API HTTP $code: $response")
            }
        } catch (e: Exception) {
            throw e
        }

        null
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
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvDownloadFilename = view.findViewById<TextView>(R.id.tvDownloadFilename)
        val pbDownloadProgress = view.findViewById<LinearProgressIndicator>(R.id.pbDownloadProgress)
        val tvDownloadStats = view.findViewById<TextView>(R.id.tvDownloadStats)
        val tvDownloadSpeed = view.findViewById<TextView>(R.id.tvDownloadSpeed)
        val btnCancelDownload = view.findViewById<Button>(R.id.btnCancelDownload)

        tvDownloadFilename.text = releaseInfo.targetAsset.name
        pbDownloadProgress.progress = 0
        val totalExpectedSize = if (releaseInfo.targetAsset.size > 0) formatSize(releaseInfo.targetAsset.size) else "-- MB"
        tvDownloadStats.text = activity.getString(R.string.update_downloading_format, "0 MB", totalExpectedSize, 0)
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
                    val destinationFile = File(updatesDir, apkName)
                    val temporaryFile = File(updatesDir, "$apkName.tmp")
                    finalFile = destinationFile
                    tempFile = temporaryFile

                    if (temporaryFile.exists()) {
                        temporaryFile.delete()
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
                        FileOutputStream(temporaryFile).use { output ->
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

                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    if (!temporaryFile.renameTo(destinationFile)) {
                        temporaryFile.copyTo(destinationFile, overwrite = true)
                        temporaryFile.delete()
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
                Toast.makeText(
                    activity,
                    activity.getString(R.string.om_update_installer_error_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.filelist.ApkInstallHelper
import me.zhanghai.android.files.provider.root.ShizukuManager
import me.zhanghai.android.files.provider.root.ShizukuShell
import me.zhanghai.android.files.util.escapeShellArg
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.regex.Pattern

object RunningAppsLoader {

    data class RamStats(
        val totalBytes: Long,
        val availBytes: Long,
        val usedBytes: Long,
        val usedPercent: Int
    )

    /**
     * Reads system RAM usage from ActivityManager and /proc/meminfo.
     */
    fun getRamStats(context: Context): RamStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var total = 0L
        var avail = 0L

        if (am != null) {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            total = memInfo.totalMem
            avail = memInfo.availMem
        }

        if (total <= 0L) {
            try {
                BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line!!
                        if (l.startsWith("MemTotal:")) {
                            val kb = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                            total = kb * 1024L
                        } else if (l.startsWith("MemAvailable:")) {
                            val kb = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                            avail = kb * 1024L
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        val used = (total - avail).coerceAtLeast(0L)
        val percent = if (total > 0L) ((used * 100L) / total).toInt().coerceIn(0, 100) else 0

        return RamStats(
            totalBytes = total,
            availBytes = avail,
            usedBytes = used,
            usedPercent = percent
        )
    }

    fun isRootGranted(): Boolean =
        (Shell.isAppGrantedRoot() == true) || ApkInstallHelper.isRootAvailable()

    fun isShizukuReady(): Boolean = ShizukuManager.ready()

    fun hasElevatedAccess(): Boolean = isRootGranted() || isShizukuReady()

    private val APP_PROCESS_REGEX = Pattern.compile(
        "\\*[A-Z]+\\*\\s+UID\\s+(\\d+)\\s+ProcessRecord\\{[0-9a-fA-F]+\\s+(\\d+):([^/\\s]+)"
    )

    /**
     * Discovers all running processes and apps using multi-tier strategy (App Manager Style).
     */
    fun loadRunningProcesses(context: Context): List<RunningAppModel> {
        val pm = context.packageManager
        val installedMap = mutableMapOf<String, PackageInfo>()
        val uidPackageMap = mutableMapOf<Int, String>()

        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                installedMap[pkg.packageName] = pkg
                val uid = pkg.applicationInfo?.uid ?: 0
                if (uid > 0 && !uidPackageMap.containsKey(uid)) {
                    uidPackageMap[uid] = pkg.packageName
                }
            }
        } catch (_: Throwable) {}

        val processMap = mutableMapOf<Int, RunningAppModel>()

        // 1. Tier 1: dumpsys activity processes (Root / Shizuku / Shell)
        if (isRootGranted()) {
            try {
                val output = Shell.cmd("dumpsys activity processes").exec().out
                parseDumpsysOutput(output, installedMap, processMap, pm)
            } catch (_: Throwable) {}
        } else if (isShizukuReady()) {
            try {
                val res = ShizukuShell.exec("dumpsys activity processes")
                if (res.isSuccess && res.out.isNotEmpty()) {
                    parseDumpsysOutput(res.out.lines(), installedMap, processMap, pm)
                }
            } catch (_: Throwable) {}
        }

        // 2. Tier 2: ps -A or ps command
        try {
            val psOutput = when {
                isRootGranted() -> {
                    Shell.cmd("ps -A -o PID,PPID,USER,RSS,STAT,NAME").exec().out
                }
                isShizukuReady() -> {
                    val res = ShizukuShell.exec("ps -A -o PID,PPID,USER,RSS,STAT,NAME")
                    if (res.isSuccess && res.out.isNotEmpty()) res.out.lines() else emptyList()
                }
                else -> {
                    val p = Runtime.getRuntime().exec(arrayOf("ps", "-A"))
                    p.inputStream.bufferedReader().use { it.readLines() }
                }
            }
            parsePsOutput(psOutput, installedMap, uidPackageMap, processMap, pm)
        } catch (_: Throwable) {}

        // 3. Tier 3: ActivityManager.getRunningAppProcesses()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val amList = am?.runningAppProcesses
            if (amList != null) {
                for (info in amList) {
                    if (processMap.containsKey(info.pid)) continue
                    val mainPkg = info.pkgList?.firstOrNull() ?: info.processName.substringBefore(':')
                    val pkgInfo = installedMap[mainPkg]
                    val appInfo = pkgInfo?.applicationInfo
                    val isSystem = if (appInfo != null) {
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    } else {
                        info.uid < 10000
                    }
                    val label = if (appInfo != null) {
                        try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { mainPkg }
                    } else {
                        info.processName
                    }
                    val memBytes = readProcessRssFromProc(info.pid)

                    processMap[info.pid] = RunningAppModel(
                        pid = info.pid,
                        ppid = 0,
                        uid = info.uid,
                        processName = info.processName,
                        packageName = if (pkgInfo != null) mainPkg else null,
                        label = label,
                        isApp = pkgInfo != null,
                        isSystem = isSystem,
                        memoryBytes = memBytes,
                        state = if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "R" else "S",
                        user = "u0_a${info.uid % 100000}"
                    )
                }
            }
        } catch (_: Throwable) {}

        // 4. Tier 4: Direct /proc/[0-9]+ scan
        try {
            val procDir = File("/proc")
            val pids = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            if (pids != null) {
                for (dir in pids) {
                    val pid = dir.name.toIntOrNull() ?: continue
                    if (processMap.containsKey(pid)) continue
                    val procModel = readProcPid(pid, installedMap, uidPackageMap, pm) ?: continue
                    processMap[pid] = procModel
                }
            }
        } catch (_: Throwable) {}

        return processMap.values.toList().sortedWith(compareByDescending<RunningAppModel> { it.isApp }
            .thenByDescending { it.memoryBytes }
            .thenBy { it.label.lowercase() })
    }

    fun parseDumpsysOutput(
        lines: List<String>,
        installedMap: Map<String, PackageInfo>,
        processMap: MutableMap<Int, RunningAppModel>,
        pm: PackageManager? = null
    ) {
        for (line in lines) {
            val matcher = APP_PROCESS_REGEX.matcher(line)
            if (matcher.find()) {
                val uid = matcher.group(1)?.toIntOrNull() ?: 0
                val pid = matcher.group(2)?.toIntOrNull() ?: continue
                val processName = matcher.group(3) ?: continue

                val mainPkg = processName.substringBefore(':')
                val pkgInfo = installedMap[mainPkg]
                val appInfo = pkgInfo?.applicationInfo
                val isSystem = if (appInfo != null) {
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                } else {
                    uid < 10000
                }
                val label = if (appInfo != null) {
                    try { pm?.getApplicationLabel(appInfo)?.toString() ?: mainPkg } catch (_: Throwable) { mainPkg }
                } else {
                    processName
                }
                val mem = readProcessRssFromProc(pid)

                processMap[pid] = RunningAppModel(
                    pid = pid,
                    uid = uid,
                    processName = processName,
                    packageName = if (pkgInfo != null) mainPkg else null,
                    label = label,
                    isApp = pkgInfo != null,
                    isSystem = isSystem,
                    memoryBytes = mem,
                    state = "R",
                    user = if (uid == 0) "root" else if (uid == 1000) "system" else "u0_a${uid % 100000}"
                )
            }
        }
    }

    fun parsePsOutput(
        lines: List<String>,
        installedMap: Map<String, PackageInfo>,
        uidPackageMap: Map<Int, String>,
        processMap: MutableMap<Int, RunningAppModel>,
        pm: PackageManager? = null
    ) {
        if (lines.isEmpty()) return
        val header = lines.firstOrNull()?.trim() ?: return
        val isHeader = header.contains("PID") || header.contains("USER") || header.contains("CMD")
        val contentLines = if (isHeader) lines.drop(1) else lines

        var colPid = -1
        var colPpid = -1
        var colUser = -1
        var colRss = -1
        var colStat = -1
        var colName = -1

        if (isHeader) {
            val headerTokens = header.split(Regex("\\s+"))
            for ((idx, tok) in headerTokens.withIndex()) {
                when (tok.uppercase()) {
                    "PID" -> colPid = idx
                    "PPID" -> colPpid = idx
                    "USER", "UID" -> colUser = idx
                    "RSS", "VMRSS" -> colRss = idx
                    "STAT", "S" -> colStat = idx
                    "NAME", "CMD", "COMMAND" -> colName = idx
                }
            }
        }

        for (line in contentLines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            val tokens = trimmedLine.split(Regex("\\s+"))
            if (tokens.size < 4) continue

            var pid = -1
            var ppid = 0
            var user = ""
            var rssKb = 0L
            var stat = "S"
            var name = ""

            if (colPid in tokens.indices && colName in tokens.indices) {
                pid = tokens[colPid].toIntOrNull() ?: -1
                if (colPpid in tokens.indices) ppid = tokens[colPpid].toIntOrNull() ?: 0
                if (colUser in tokens.indices) user = tokens[colUser]
                if (colRss in tokens.indices) {
                    rssKb = tokens[colRss].toLongOrNull() ?: 0L
                }
                if (colStat in tokens.indices) stat = tokens[colStat]
                name = if (colName == tokens.size - 1) {
                    tokens[colName]
                } else if (colName < tokens.size) {
                    tokens.subList(colName, tokens.size).joinToString(" ")
                } else {
                    tokens.last()
                }
            } else {
                for (token in tokens) {
                    if (pid == -1 && token.all { it.isDigit() }) {
                        pid = token.toIntOrNull() ?: -1
                    }
                }
                user = tokens[0]
                name = tokens.last()
                stat = tokens.find { it.length == 1 && it[0] in "RSDZTtW" } ?: "S"

                for (token in tokens) {
                    val num = token.toLongOrNull()
                    if (num != null && num > 100L && num < 10_000_000L && num != pid.toLong()) {
                        rssKb = num
                    }
                }
            }

            if (pid <= 0) continue

            // Update existing entry (e.g. from dumpsys) if RSS was missing
            val existing = processMap[pid]
            if (existing != null) {
                if (existing.memoryBytes <= 0L && rssKb > 0L) {
                    processMap[pid] = existing.copy(memoryBytes = rssKb * 1024L)
                }
                continue
            }

            val cleanName = name.trimStart('[').trimEnd(']')
            val basePkg = cleanName.substringBefore(':')
            val pkgInfo = installedMap[basePkg] ?: (if (user.startsWith("u0_a")) {
                val uidNum = user.removePrefix("u0_a").toIntOrNull()?.let { 10000 + it }
                if (uidNum != null) uidPackageMap[uidNum]?.let { installedMap[it] } else null
            } else null)
            val appInfo = pkgInfo?.applicationInfo
            val isSystem = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } else {
                user == "root" || user == "system"
            }
            val label = if (appInfo != null) {
                try { pm?.getApplicationLabel(appInfo)?.toString() ?: (pkgInfo?.packageName ?: basePkg) } catch (_: Throwable) { basePkg }
            } else {
                cleanName
            }

            val finalMem = if (rssKb > 0) rssKb * 1024L else readProcessRssFromProc(pid)

            val uid = when {
                user == "root" -> 0
                user == "system" -> 1000
                user.startsWith("u0_a") -> 10000 + (user.removePrefix("u0_a").toIntOrNull() ?: 0)
                else -> 10000
            }

            processMap[pid] = RunningAppModel(
                pid = pid,
                ppid = ppid,
                uid = uid,
                processName = cleanName,
                packageName = pkgInfo?.packageName,
                label = label,
                isApp = pkgInfo != null,
                isSystem = isSystem,
                memoryBytes = finalMem,
                state = stat,
                user = user
            )
        }
    }

    private fun readProcPid(
        pid: Int,
        installedMap: Map<String, PackageInfo>,
        uidPackageMap: Map<Int, String>,
        pm: PackageManager
    ): RunningAppModel? {
        val statusFile = File("/proc/$pid/status")
        if (!statusFile.exists() || !statusFile.canRead()) return null

        var name = ""
        var state = "S"
        var ppid = 0
        var uid = 0
        var vmrss = 0L
        var threads = 1

        try {
            BufferedReader(FileReader(statusFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    when {
                        l.startsWith("Name:") -> name = l.substringAfter(':').trim()
                        l.startsWith("State:") -> state = l.substringAfter(':').trim().take(1)
                        l.startsWith("PPid:") -> ppid = l.substringAfter(':').trim().toIntOrNull() ?: 0
                        l.startsWith("Uid:") -> uid = l.substringAfter(':').trim().split(Regex("\\s+"))[0].toIntOrNull() ?: 0
                        l.startsWith("VmRSS:") -> vmrss = (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) * 1024L
                        l.startsWith("Threads:") -> threads = l.substringAfter(':').trim().toIntOrNull() ?: 1
                    }
                }
            }
        } catch (_: Throwable) {
            return null
        }

        if (name.isEmpty()) return null

        var cmdline = ""
        try {
            val cmdFile = File("/proc/$pid/cmdline")
            if (cmdFile.exists() && cmdFile.canRead()) {
                cmdline = cmdFile.readText().replace('\u0000', ' ').trim()
            }
        } catch (_: Throwable) {}

        val effectiveName = if (cmdline.isNotEmpty()) cmdline.split(' ')[0] else name
        val basePkg = effectiveName.substringBefore(':')
        val pkgInfo = installedMap[basePkg] ?: uidPackageMap[uid]?.let { installedMap[it] }
        val appInfo = pkgInfo?.applicationInfo

        val isSystem = if (appInfo != null) {
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } else {
            uid < 10000
        }

        val label = if (appInfo != null) {
            try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { basePkg }
        } else {
            effectiveName
        }

        return RunningAppModel(
            pid = pid,
            ppid = ppid,
            uid = uid,
            processName = effectiveName,
            packageName = if (pkgInfo != null) pkgInfo.packageName else null,
            label = label,
            isApp = pkgInfo != null,
            isSystem = isSystem,
            memoryBytes = vmrss,
            state = state,
            threadCount = threads,
            user = if (uid == 0) "root" else if (uid == 1000) "system" else "u0_a${uid % 100000}",
            cmdline = cmdline
        )
    }

    private fun readProcessRssFromProc(pid: Int): Long {
        try {
            val file = File("/proc/$pid/status")
            if (file.exists() && file.canRead()) {
                BufferedReader(FileReader(file)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("VmRSS:")) {
                            val kb = line!!.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                            return kb * 1024L
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return 0L
    }

    /**
     * Kills a process by PID using SIGKILL (-9).
     */
    fun killProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        if (isRootGranted()) {
            val res = Shell.cmd("kill -9 $pid").exec()
            return res.isSuccess
        }
        if (isShizukuReady()) {
            val res = ShizukuShell.exec("kill -9 $pid")
            return res.isSuccess
        }
        return try {
            Process.killProcess(pid)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Force stops an application via am force-stop.
     */
    fun forceStopApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (isRootGranted()) {
            val res = Shell.cmd("am force-stop ${packageName.escapeShellArg()}").exec()
            return res.isSuccess
        }
        if (isShizukuReady()) {
            val res = ShizukuShell.exec("am force-stop ${packageName.escapeShellArg()}")
            return res.isSuccess
        }
        return false
    }
}

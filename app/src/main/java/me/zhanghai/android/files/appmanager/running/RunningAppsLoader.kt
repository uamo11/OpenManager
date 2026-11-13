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

        // Snapshot CPU jiffies before discovery for accurate CPU% delta
        val cpuTotalT0 = readTotalCpuJiffies()
        val procJiffiesT0 = readAllProcJiffies()
        val startMs = System.currentTimeMillis()

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

        // Ensure at least ~250ms elapsed between CPU snapshots for meaningful delta
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed < 250L) {
            try { Thread.sleep(250L - elapsed) } catch (_: Throwable) {}
        }
        val actualElapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val cpuTotalT1 = readTotalCpuJiffies()
        val procJiffiesT1 = readAllProcJiffies()
        val totalDelta = if (cpuTotalT0 > 0L && cpuTotalT1 > cpuTotalT0) {
            cpuTotalT1 - cpuTotalT0
        } else {
            // Fallback when /proc/stat is SELinux-restricted on Android 8+:
            // USER_HZ is 100 jiffies/sec per CPU core.
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((actualElapsedMs / 1000.0) * 100.0 * cores).toLong().coerceAtLeast(1L)
        }

        // Optional fallback CPU% from ps -A -o PID,PCPU
        val psCpuMap = readPsCpuPercentages()

        // Enrich processes with CPU% and executable type (SH / ELF64 / ELF32)
        val exeTargetMap = if (isRootGranted()) readProcExeTargetsBatch() else emptyMap()
        for ((pid, model) in processMap) {
            val j0 = procJiffiesT0[pid]
            val j1 = procJiffiesT1[pid]
            val deltaCpu = if (j0 != null && j1 != null && j1 >= j0) {
                ((j1 - j0).toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
            } else {
                null
            }
            val procCpu = when {
                deltaCpu != null && deltaCpu > 0f -> deltaCpu
                psCpuMap.containsKey(pid) -> psCpuMap[pid] ?: 0f
                else -> deltaCpu ?: model.cpuPercent
            }

            val (execType, elfArch) = detectExecutableType(
                pid = pid,
                processName = model.processName,
                cmdline = model.cmdline,
                isApp = model.isApp,
                exeTarget = exeTargetMap[pid]
            )

            processMap[pid] = model.copy(
                cpuPercent = procCpu,
                executableType = execType,
                elfArch = elfArch
            )
        }

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
            val looksLikeAppProcess = cleanName.contains('.') || cleanName.startsWith("app_process")
            val pkgInfo = installedMap[basePkg] ?: (if (looksLikeAppProcess && user.startsWith("u0_a")) {
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
        val looksLikeAppProcess = effectiveName.contains('.') || effectiveName.startsWith("app_process")
        val pkgInfo = installedMap[basePkg] ?: if (looksLikeAppProcess) uidPackageMap[uid]?.let { installedMap[it] } else null
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

    private fun readTotalCpuJiffies(): Long {
        try {
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val line = reader.readLine() ?: return 0L
                if (line.startsWith("cpu ")) {
                    val parts = line.trim().split(Regex("\\s+"))
                    var sum = 0L
                    for (i in 1 until parts.size) {
                        sum += parts[i].toLongOrNull() ?: 0L
                    }
                    return sum
                }
            }
        } catch (_: Throwable) {}
        return 0L
    }

    private fun readAllProcJiffies(): Map<Int, Long> {
        val map = HashMap<Int, Long>()
        try {
            val procDir = File("/proc")
            val dirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return map
            for (d in dirs) {
                val pid = d.name.toIntOrNull() ?: continue
                val statFile = File(d, "stat")
                if (!statFile.canRead()) continue
                try {
                    val text = statFile.readText()
                    val rparen = text.lastIndexOf(')')
                    if (rparen > 0 && rparen + 2 < text.length) {
                        val after = text.substring(rparen + 2).trim().split(Regex("\\s+"))
                        // utime is field 14 (index 11 after ')'), stime is field 15 (index 12)
                        if (after.size > 12) {
                            val utime = after[11].toLongOrNull() ?: 0L
                            val stime = after[12].toLongOrNull() ?: 0L
                            map[pid] = utime + stime
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun readPsCpuPercentages(): Map<Int, Float> {
        val map = HashMap<Int, Float>()
        try {
            val lines = when {
                isRootGranted() -> Shell.cmd("ps -A -o PID,PCPU").exec().out
                isShizukuReady() -> {
                    val res = ShizukuShell.exec("ps -A -o PID,PCPU")
                    if (res.isSuccess) res.out.lines() else emptyList()
                }
                else -> {
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("ps", "-A", "-o", "PID,PCPU"))
                        p.inputStream.bufferedReader().use { it.readLines() }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }
            for (line in lines.drop(1)) {
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.size >= 2) {
                    val pid = tokens[0].toIntOrNull() ?: continue
                    val cpu = tokens[1].toFloatOrNull() ?: continue
                    map[pid] = cpu.coerceIn(0f, 100f)
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun readProcExeTargetsBatch(): Map<Int, String> {
        val map = HashMap<Int, String>()
        try {
            val lines = Shell.cmd("ls -l /proc/[0-9]*/exe 2>/dev/null").exec().out
            for (line in lines) {
                val arrowIdx = line.indexOf("->")
                if (arrowIdx <= 0) continue
                val left = line.substring(0, arrowIdx)
                val target = line.substring(arrowIdx + 2).trim()
                val procMatch = Regex("""/proc/(\d+)/exe""").find(left) ?: continue
                val pid = procMatch.groupValues[1].toIntOrNull() ?: continue
                if (target.isNotEmpty()) {
                    map[pid] = target
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private val SHELL_NAMES = setOf("sh", "bash", "ash", "mksh", "zsh", "dash", "ksh")

    private fun detectExecutableType(
        pid: Int,
        processName: String,
        cmdline: String,
        isApp: Boolean,
        exeTarget: String?
    ): Pair<ExecutableType, String> {
        if (isApp) return ExecutableType.APP to ""

        val lowerName = processName.substringAfterLast('/').lowercase()
        val lowerCmd = cmdline.lowercase()

        // 1. Shell scripts (.sh or shell interpreter running a script)
        if (lowerName in SHELL_NAMES ||
            lowerName.endsWith(".sh") ||
            lowerCmd.contains(".sh ") ||
            lowerCmd.endsWith(".sh") ||
            lowerCmd.startsWith("/system/bin/sh") ||
            lowerCmd.startsWith("/bin/sh")
        ) {
            return ExecutableType.SHELL_SCRIPT to "Shell"
        }

        // Kernel threads (usually bracketed or no binary path)
        if (processName.startsWith("kworker") ||
            processName.startsWith("ksoftirqd") ||
            processName.startsWith("kswapd") ||
            processName.startsWith("migration") ||
            processName.startsWith("rcu_") ||
            processName == "kthreadd"
        ) {
            return ExecutableType.SYSTEM_DAEMON to ""
        }

        // 2. Inspect ELF header from exeTarget, /proc/$pid/exe, or executable path
        val candidatePaths = mutableListOf<String>()
        if (!exeTarget.isNullOrEmpty() && !exeTarget.contains("app_process")) {
            candidatePaths.add(exeTarget)
        }
        if (processName.startsWith("/")) {
            candidatePaths.add(processName.substringBefore(' '))
        } else {
            val firstArg = cmdline.substringBefore(' ').trim()
            if (firstArg.startsWith("/")) candidatePaths.add(firstArg)
            candidatePaths.add("/system/bin/$lowerName")
            candidatePaths.add("/vendor/bin/$lowerName")
            candidatePaths.add("/data/local/tmp/$lowerName")
        }
        candidatePaths.add("/proc/$pid/exe")

        for (pathStr in candidatePaths) {
            val elfInfo = readElfHeaderQuick(pathStr)
            if (elfInfo != null) return elfInfo
        }

        // 3. Fallback: if it's a native binary path or non-Java daemon (not app_process / zygote)
        val targetOrName = exeTarget ?: processName
        val isNativePath = targetOrName.startsWith("/") ||
            cmdline.startsWith("/system/bin/") ||
            cmdline.startsWith("/vendor/bin/") ||
            cmdline.startsWith("/apex/") ||
            cmdline.startsWith("/data/local/tmp/") ||
            cmdline.startsWith("/data/adb/")
        if (isNativePath && !targetOrName.contains("app_process") && !lowerName.contains("zygote")) {
            return ExecutableType.ELF_GENERIC to ""
        }

        return ExecutableType.SYSTEM_DAEMON to ""
    }

    private fun readElfHeaderQuick(pathStr: String): Pair<ExecutableType, String>? {
        return try {
            val file = File(pathStr)
            if (!file.exists() || !file.canRead()) return null
            val header = ByteArray(20)
            val read = file.inputStream().use { it.read(header) }
            if (read < 20) return null
            if (header[0] != 0x7F.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte()
            ) {
                return null
            }
            val eiClass = header[4].toInt() // 1 = 32-bit, 2 = 64-bit
            val isLittleEndian = header[5].toInt() == 1
            val b0 = header[18].toInt() and 0xFF
            val b1 = header[19].toInt() and 0xFF
            val machine = if (isLittleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
            val arch = when (machine) {
                183 -> "AArch64"
                62 -> "x86_64"
                40 -> "ARM32"
                3 -> "x86"
                243 -> "RISC-V"
                else -> "ELF"
            }
            val type = when (eiClass) {
                2 -> ExecutableType.ELF_64
                1 -> ExecutableType.ELF_32
                else -> ExecutableType.ELF_GENERIC
            }
            type to arch
        } catch (_: Throwable) {
            null
        }
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

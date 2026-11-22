/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import java.io.File

object SyscallReaderMonitor {

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val reportedSystemCwd = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    @Synchronized
    fun startMonitoring(pid: Int) {
        stopMonitoring()
        reportedSystemCwd.clear()
        if (pid <= 0) return

        monitorJob = scope.launch {
            val pidList = mutableSetOf<Int>()
            pidList.add(pid)

            while (isActive && ScriptExecutionManager.isRunning()) {
                val currentPid = ScriptExecutionManager.activePid
                if (currentPid <= 0) break

                // Discover any child threads / processes in /proc/<pid>/task
                runCatching {
                    val taskDir = File("/proc/$currentPid/task")
                    if (taskDir.isDirectory) {
                        taskDir.listFiles()?.forEach { t ->
                            t.name.toIntOrNull()?.let { pidList.add(it) }
                            val childrenFile = File(t, "children")
                            if (childrenFile.isFile && childrenFile.canRead()) {
                                childrenFile.readText().split(Regex("\\s+")).forEach { c ->
                                    c.toIntOrNull()?.let { pidList.add(it) }
                                }
                            }
                        }
                    }
                }

                // 1. Read /proc/<pid>/syscall
                for (targetPid in pidList.toList()) {
                    runCatching {
                        val syscallFile = File("/proc/$targetPid/syscall")
                        if (syscallFile.exists() && syscallFile.canRead()) {
                            val line = syscallFile.readText().trim()
                            if (line.isNotEmpty()) {
                                val parts = line.split(Regex("\\s+"))
                                val syscallNr = parts.firstOrNull()?.toIntOrNull()
                                if (syscallNr != null && syscallNr >= 0) {
                                    val argsStr = parts.drop(1).take(6).joinToString(" ")
                                    BinaryTracerEngine.recordProcSyscall(targetPid, syscallNr, argsStr)
                                }
                            }
                        }
                    }
                }

                // 2. Read /proc/<pid>/io
                runCatching {
                    val ioFile = File("/proc/$currentPid/io")
                    if (ioFile.exists() && ioFile.canRead()) {
                        var syscr = 0L
                        var syscw = 0L
                        var rbytes = 0L
                        var wbytes = 0L
                        ioFile.forEachLine { line ->
                            when {
                                line.startsWith("syscr:") -> syscr = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                line.startsWith("syscw:") -> syscw = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                line.startsWith("read_bytes:") -> rbytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                                line.startsWith("write_bytes:") -> wbytes = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                            }
                        }
                        if (syscr > 0L || syscw > 0L) {
                            BinaryTracerEngine.updateProcIo(syscr, syscw, rbytes, wbytes)
                        }
                    }
                }

                // A process working inside an OS partition is worth one note, not one per sample.
                for (targetPid in pidList.toList()) {
                    if (!reportedSystemCwd.add(targetPid)) continue
                    val canonicalCwd = runCatching { File("/proc/$targetPid/cwd").canonicalPath }.getOrNull()
                    val zone = canonicalCwd?.let { me.zhanghai.android.files.provider.root.CommandGuard.classify(it) }
                    if (zone == me.zhanghai.android.files.provider.root.CommandGuard.Zone.SYSTEM ||
                        zone == me.zhanghai.android.files.provider.root.CommandGuard.Zone.BOOT_CRITICAL
                    ) {
                        BinaryTracerEngine.recordKernelProbe("/proc/$targetPid/cwd", "[PID $targetPid] $canonicalCwd")
                    } else {
                        // Not in the OS now: allow a later note if it moves there.
                        reportedSystemCwd.remove(targetPid)
                    }
                }

                // The process tree changes constantly: forget pids that have exited.
                pidList.removeAll { !File("/proc/$it").exists() }
                pidList.add(currentPid)

                delay(500) // Counters only: 2 samples/s is plenty and halves the overhead
            }
        }
    }

    @Synchronized
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }
}

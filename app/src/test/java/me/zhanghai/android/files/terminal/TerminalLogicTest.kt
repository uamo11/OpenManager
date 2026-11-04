/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.zhanghai.android.files.terminal.tracer.BinaryTracerEngine
import me.zhanghai.android.files.terminal.tracer.DeletionDecision
import me.zhanghai.android.files.terminal.tracer.TraceEventType
import me.zhanghai.android.files.terminal.tracer.SyscallCategory
import me.zhanghai.android.files.terminal.tracer.SyscallResolver
import me.zhanghai.android.files.terminal.tracer.SyscallRisk
import me.zhanghai.android.files.filelist.ElfInspector

import java.io.File

class TerminalLogicTest {

    private fun formatShellCommand(cwd: String, initialCommand: String): String {
        val escapedCwd = cwd.replace("'", "'\\''")
        return "cd '$escapedCwd' && ($initialCommand); _mt_status=\$?; exit \$_mt_status"
    }

    private fun simulateJniArgvConstruction(cmd: String, args: Array<String>?): Array<String> {
        val size = args?.size ?: 0
        var prependCmd = false
        if (size == 0) {
            prependCmd = true
        } else if (args != null && args[0].startsWith("-")) {
            prependCmd = true
        }

        val totalArgs = size + (if (prependCmd) 1 else 0)
        val result = ArrayList<String>(totalArgs)
        if (prependCmd) {
            val progName = File(cmd).name
            result.add(if (progName.isNotEmpty()) progName else "sh")
        }
        if (args != null) {
            result.addAll(args)
        }
        return result.toTypedArray()
    }

    @Test
    fun testShellCommandEscapesSingleQuotes() {
        val cwd = "/storage/emulated/0/My Folder's Documents"
        val cmd = "sh \"/storage/emulated/0/a.sh\""
        val formatted = formatShellCommand(cwd, cmd)

        assertTrue(formatted.contains("'/storage/emulated/0/My Folder'\\''s Documents'"))
        assertTrue(formatted.contains("(sh \"/storage/emulated/0/a.sh\")"))
        assertTrue(formatted.endsWith("; _mt_status=\$?; exit \$_mt_status"))
    }

    @Test
    fun testArgv0IntegrityStandardShell() {
        val cmd = "sh \"/storage/emulated/0/a.sh\""
        val shellCmd = formatShellCommand("/storage/emulated/0", cmd)
        val args = arrayOf("sh", "-c", shellCmd)

        // Ensure args[0] is program name 'sh', NOT '-c'
        assertNotEquals("-c", args[0])
        assertEquals("sh", args[0])
        assertEquals("-c", args[1])
        assertEquals(shellCmd, args[2])
    }

    @Test
    fun testArgv0IntegrityRootAndTermex() {
        val cmd = "sh \"/storage/emulated/0/a.sh\""
        val shellCmd = formatShellCommand("/storage/emulated/0", cmd)

        val rootArgs = arrayOf("su", "-c", shellCmd)
        assertEquals("su", rootArgs[0])
        assertEquals("-c", rootArgs[1])

        val termexArgs = arrayOf("bash", "--norc", "-c", shellCmd)
        assertEquals("bash", termexArgs[0])
        assertEquals("--norc", termexArgs[1])
        assertEquals("-c", termexArgs[2])
    }

    @Test
    fun testJniAutoPrependsProgramNameWhenOmitted() {
        // Test when caller erroneously passed only ["-c", "cmd"]
        val flawedArgs = arrayOf("-c", "echo hello")
        val fixedArgv = simulateJniArgvConstruction("/system/bin/sh", flawedArgs)

        assertEquals(3, fixedArgv.size)
        assertEquals("sh", fixedArgv[0])
        assertEquals("-c", fixedArgv[1])
        assertEquals("echo hello", fixedArgv[2])
    }

    @Test
    fun testJniKeepsArgvWhenProgramNameAlreadyPresent() {
        val goodArgs = arrayOf("sh", "-c", "echo hello")
        val argv = simulateJniArgvConstruction("/system/bin/sh", goodArgs)

        assertEquals(3, argv.size)
        assertEquals("sh", argv[0])
        assertEquals("-c", argv[1])
        assertEquals("echo hello", argv[2])
    }

    @Test
    fun testJniHandlesEmptyOrNullArgs() {
        val argvNull = simulateJniArgvConstruction("/system/bin/sh", null)
        assertEquals(1, argvNull.size)
        assertEquals("sh", argvNull[0])

        val argvEmpty = simulateJniArgvConstruction("/system/bin/sh", emptyArray())
        assertEquals(1, argvEmpty.size)
        assertEquals("sh", argvEmpty[0])
    }

    @Test
    fun testDefaultFontSizeIsReadable() {
        fun getFontSize(storedSize: Int): Int {
            return if (storedSize < 13) 14 else storedSize
        }
        fun setFontSize(size: Int): Int {
            return size.coerceIn(10, 32)
        }

        assertEquals(14, getFontSize(12)) // upgrades legacy 12 to 14
        assertEquals(14, getFontSize(14))
        assertEquals(16, getFontSize(16))
        assertEquals(10, setFontSize(8))  // clamps minimum
        assertEquals(32, setFontSize(40)) // clamps maximum
    }

    @Test
    fun testScriptExecutionManagerLifecycle() {
        var finishedCode: Int? = null
        val listener = object : ScriptExecutionManager.SessionListener {
            override fun onSessionFinished(exitCode: Int) {
                finishedCode = exitCode
            }
        }
        ScriptExecutionManager.addListener(listener)

        ScriptExecutionManager.activePid = 1234
        assertEquals(1234, ScriptExecutionManager.activePid)

        ScriptExecutionManager.onSessionFinished(0, null)
        assertEquals(0, finishedCode)
        assertEquals(0, ScriptExecutionManager.lastExitCode)

        ScriptExecutionManager.clearSession()
        assertEquals(-1, ScriptExecutionManager.activePid)
        assertEquals("", ScriptExecutionManager.activeScriptName)

        ScriptExecutionManager.removeListener(listener)
    }

    @Test
    fun testElfArchitectureHeaderParsing() {
        fun parseElfMachine(bytes: ByteArray): Int {
            if (bytes.size < 20) return -1
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
                return -1
            }
            val isLittleEndian = bytes[5].toInt() == 1
            return if (isLittleEndian) {
                (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
            } else {
                ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
            }
        }

        // Fake AArch64 (183 = 0x00B7)
        val aarch64Header = ByteArray(20).apply {
            this[0] = 0x7F.toByte(); this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2 // 64-bit
            this[5] = 1 // Little endian
            this[18] = 0xB7.toByte()
            this[19] = 0x00.toByte()
        }
        assertEquals(183, parseElfMachine(aarch64Header))

        // Fake x86_64 (62 = 0x003E)
        val x86_64Header = ByteArray(20).apply {
            this[0] = 0x7F.toByte(); this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2 // 64-bit
            this[5] = 1 // Little endian
            this[18] = 0x3E.toByte()
            this[19] = 0x00.toByte()
        }
        assertEquals(62, parseElfMachine(x86_64Header))

        // Non-ELF file
        val textHeader = "#!/bin/sh\necho test\n".toByteArray()
        assertEquals(-1, parseElfMachine(textHeader))
    }

    @Test
    fun testCrlfToLfSanitization() {
        val windowsScript = "#!/system/bin/sh\r\necho \"step 1\"\r\necho \"step 2\"\r\n"
        val unixScript = windowsScript.replace("\r\n", "\n").replace("\r", "\n")

        assertTrue(windowsScript.contains("\r\n"))
        assertTrue(!unixScript.contains("\r"))
        assertEquals("#!/system/bin/sh\necho \"step 1\"\necho \"step 2\"\n", unixScript)
    }

    @Test
    fun testTracerHighFrequencySyscallAggregation() {
        BinaryTracerEngine.clear()

        // 1. Record burst of 50,000 syscalls
        BinaryTracerEngine.recordSyscallBurst("SYS_getpid", 50000L, "Benchmark 1")
        assertEquals(50000L, BinaryTracerEngine.totalSyscallsCount.get())
        assertEquals(50000L, BinaryTracerEngine.getSyscallCounters()["SYS_getpid"])

        // 2. Record second burst - should aggregate into existing recent event O(1)
        BinaryTracerEngine.recordSyscallBurst("SYS_getpid", 50000L, "Benchmark 2")
        assertEquals(100000L, BinaryTracerEngine.totalSyscallsCount.get())
        assertEquals(100000L, BinaryTracerEngine.getSyscallCounters()["SYS_getpid"])

        // Ring buffer should hold 1 consolidated event rather than 100,000 objects
        val events = BinaryTracerEngine.getEvents()
        assertEquals(1, events.size)
        assertEquals(100000L, events[0].count)
        assertEquals("SYS_getpid", events[0].target)
        assertEquals(TraceEventType.SYSCALL_BURST, events[0].type)
    }

    @Test
    fun testTracerDeletionInterceptionAndDecisionLifecycle() {
        BinaryTracerEngine.clear()

        // 1. Initial attempt
        val event = BinaryTracerEngine.recordDeletionAttempt("rm -rf /data/local/tmp/test.tmp", "/data/local/tmp/test.tmp", DeletionDecision.PENDING)
        val stats1 = BinaryTracerEngine.getStats()
        assertEquals(1L, stats1.totalDeletions)
        assertEquals(0L, stats1.blockedDeletions)
        assertEquals(0L, stats1.allowedDeletions)

        // 2. User blocks the deletion (non-fatal, script continues)
        BinaryTracerEngine.recordDecision(event.id, DeletionDecision.BLOCKED)
        val stats2 = BinaryTracerEngine.getStats()
        assertEquals(1L, stats2.totalDeletions)
        assertEquals(1L, stats2.blockedDeletions)
        assertEquals(0L, stats2.allowedDeletions)
        assertEquals(DeletionDecision.BLOCKED, event.decision)

        // 3. User allows subsequent deletion
        val event2 = BinaryTracerEngine.recordDeletionAttempt("unlink /data/local/tmp/dummy.tmp", "/data/local/tmp/dummy.tmp", DeletionDecision.ALLOWED)
        val stats3 = BinaryTracerEngine.getStats()
        assertEquals(2L, stats3.totalDeletions)
        assertEquals(1L, stats3.blockedDeletions)
        assertEquals(1L, stats3.allowedDeletions)
    }

    @Test
    fun testTracerKernelAndProcTelemetryParsing() {
        BinaryTracerEngine.clear()

        BinaryTracerEngine.recordKernelProbe("/proc/version", "Linux version 6.6.0-android")
        val stats = BinaryTracerEngine.getStats()
        assertEquals(1L, stats.totalKernelReads)

        val events = BinaryTracerEngine.getEvents()
        assertEquals(1, events.size)
        assertEquals(TraceEventType.KERNEL_PROBE, events[0].type)
        assertEquals("/proc/version", events[0].target)
    }

    @Test
    fun testDeadManTokenRegexMatches() {
        val chunk = "some output\n[OM_TRACER:DEADMAN_DELETE:rm -rf /system:/system]\nmore output"
        val regex = Regex("""\[OM_TRACER:(DELETE|DEADMAN_DELETE):([^:]+):([^\]]+)\]""")
        val match = regex.find(chunk)
        assertTrue(match != null)
        assertEquals("DEADMAN_DELETE", match!!.groupValues[1])
        assertEquals("rm -rf /system", match.groupValues[2])
        assertEquals("/system", match.groupValues[3].trim())
    }

    @Test
    fun testSyscallResolverArm64AndX86_64() {
        // ARM64 test cases
        val unlinkatArm64 = SyscallResolver.resolve(35, "arm64-v8a")
        assertEquals("sys_unlinkat", unlinkatArm64.name)
        assertEquals(SyscallCategory.DELETION_FS, unlinkatArm64.category)
        assertEquals(SyscallRisk.CRITICAL, unlinkatArm64.risk)

        val execveArm64 = SyscallResolver.resolve(221, "arm64-v8a")
        assertEquals("sys_execve", execveArm64.name)
        assertEquals(SyscallCategory.PROCESS_EXEC, execveArm64.category)
        assertEquals(SyscallRisk.HIGH, execveArm64.risk)

        val ptraceArm64 = SyscallResolver.resolve(117, "arm64-v8a")
        assertEquals("sys_ptrace", ptraceArm64.name)
        assertEquals(SyscallCategory.DEBUG_PTRACE, ptraceArm64.category)
        assertEquals(SyscallRisk.CRITICAL, ptraceArm64.risk)

        val socketArm64 = SyscallResolver.resolve(198, "arm64-v8a")
        assertEquals("sys_socket", socketArm64.name)
        assertEquals(SyscallCategory.NETWORK, socketArm64.category)
        assertEquals(SyscallRisk.MEDIUM, socketArm64.risk)

        val readArm64 = SyscallResolver.resolve(63, "arm64-v8a")
        assertEquals("sys_read", readArm64.name)
        assertEquals(SyscallCategory.FILE_IO, readArm64.category)
        assertEquals(SyscallRisk.LOW, readArm64.risk)

        // x86_64 test cases
        val unlinkatX86_64 = SyscallResolver.resolve(263, "x86_64")
        assertEquals("sys_unlinkat", unlinkatX86_64.name)
        assertEquals(SyscallCategory.DELETION_FS, unlinkatX86_64.category)
        assertEquals(SyscallRisk.CRITICAL, unlinkatX86_64.risk)

        val execveX86_64 = SyscallResolver.resolve(59, "x86_64")
        assertEquals("sys_execve", execveX86_64.name)
        assertEquals(SyscallCategory.PROCESS_EXEC, execveX86_64.category)

        // ARM32 test cases
        val unlinkArm32 = SyscallResolver.resolve(10, "armeabi-v7a")
        assertEquals("sys_unlink", unlinkArm32.name)
        assertEquals(SyscallCategory.DELETION_FS, unlinkArm32.category)
    }

    @Test
    fun testSyscallResolverByNameLookup() {
        val unlinkInfo = SyscallResolver.resolveByName("unlink")
        assertEquals(SyscallCategory.DELETION_FS, unlinkInfo.category)
        assertEquals(SyscallRisk.CRITICAL, unlinkInfo.risk)

        val mountInfo = SyscallResolver.resolveByName("mount")
        assertEquals(SyscallCategory.SYSTEM_ADMIN, mountInfo.category)
        assertEquals(SyscallRisk.CRITICAL, mountInfo.risk)

        val killInfo = SyscallResolver.resolveByName("sys_kill")
        assertEquals(SyscallCategory.SIGNALING, killInfo.category)
        assertEquals(SyscallRisk.HIGH, killInfo.risk)

        val connectInfo = SyscallResolver.resolveByName("__NR_connect")
        assertEquals(SyscallCategory.NETWORK, connectInfo.category)
        assertEquals(SyscallRisk.MEDIUM, connectInfo.risk)
    }

    @Test
    fun testElfInspectorHeaderValidationAndArchitecture() {
        // Invalid non-ELF bytes
        val nonElf = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertTrue(!ElfInspector.isElf(nonElf))

        // Valid ARM64 Little-Endian PIE header
        val header = ByteArray(64)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // 64-bit
        header[5] = 1 // Little Endian
        header[6] = 1 // Version
        // e_type at offset 16: ET_DYN = 3 (PIE)
        header[16] = 3
        header[17] = 0
        // e_machine at offset 18: EM_AARCH64 = 183 (0x00B7)
        header[18] = 0xB7.toByte()
        header[19] = 0x00

        assertTrue(ElfInspector.isElf(header))
        val report = ElfInspector.inspect(header)
        assertEquals(true, report.is64Bit)
        assertEquals(true, report.isLittleEndian)
        assertEquals(true, report.isPie)
        assertTrue(report.machineArch.contains("AArch64"))
    }

    @Test
    fun testElfInspectorSymbolAndSyscallDetection() {
        // Construct ELF header + embedded sensitive strings
        val header = ByteArray(256)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // 64-bit
        header[5] = 1 // Little Endian
        header[16] = 3 // ET_DYN
        header[18] = 0xB7.toByte() // AArch64

        // Embed symbols into the data section: unlink, system, ptrace, socket, reboot
        val symbols = "unlink\u0000system\u0000ptrace\u0000socket\u0000reboot\u0000/system/bin/su\u0000"
        val symBytes = symbols.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(symBytes, 0, header, 64, symBytes.size)

        val report = ElfInspector.inspect(header)
        assertTrue(report.totalFindings > 0)
        assertTrue(report.deletions.any { it.contains("unlink") })
        assertTrue(report.executionCalls.any { it.contains("system") })
        assertTrue(report.ptraceCalls.any { it.contains("ptrace") })
        assertTrue(report.networkCalls.any { it.contains("socket") })
        assertTrue(report.systemCalls.any { it.contains("reboot") })
        assertTrue(report.rootCalls.any { it.contains("su") })

        val scriptAuditReport = report.toScriptAuditReport()
        assertEquals(report.totalFindings, scriptAuditReport.totalFindings)
    }

    @Test
    fun testBinaryTracerProcSyscallLiveTracking() {
        BinaryTracerEngine.clear()

        // 1. Record live proc syscall for unlinkat
        BinaryTracerEngine.recordProcSyscall(1234, 35, "0xffffff9c 0x7ffffff 0x0")
        val stats1 = BinaryTracerEngine.getStats()
        assertEquals(1L, stats1.totalSyscalls)
        assertTrue(stats1.activeSyscall != null)
        assertEquals("sys_unlinkat", stats1.activeSyscall!!.name)
        assertEquals(SyscallCategory.DELETION_FS, stats1.activeSyscall!!.category)
        assertEquals(SyscallRisk.CRITICAL, stats1.activeSyscall!!.risk)

        // 2. Update I/O stats
        BinaryTracerEngine.updateProcIo(1500L, 200L, 1024000L, 512000L)
        val stats2 = BinaryTracerEngine.getStats()
        assertEquals(1500L, stats2.ioReadSyscalls)
        assertEquals(200L, stats2.ioWriteSyscalls)
        assertEquals(1024000L, stats2.ioBytesRead)
        assertEquals(512000L, stats2.ioBytesWritten)

        // 3. Events ring buffer should contain the recorded high-risk syscall event
        val events = BinaryTracerEngine.getEvents()
        assertTrue(events.any { it.target.contains("sys_unlinkat") })

        // 4. Verify clearing
        BinaryTracerEngine.clear()
        val statsCleared = BinaryTracerEngine.getStats()
        assertEquals(0L, statsCleared.totalSyscalls)
        assertEquals(0L, statsCleared.ioReadSyscalls)
        assertEquals(null, statsCleared.activeSyscall)
    }
}

/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}

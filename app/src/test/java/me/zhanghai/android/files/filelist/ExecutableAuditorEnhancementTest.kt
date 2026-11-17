/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableAuditorEnhancementTest {

    private fun putLe16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putLe64(buf: ByteArray, off: Int, v: Long) {
        putLe32(buf, off, v.toInt())
        putLe32(buf, off + 4, (v ushr 32).toInt())
    }

    @Test
    fun strippedStaticAarch64ElfDetectsDirectSvcSyscallsWithoutStrings() {
        val codeOffset = 128
        val codeSize = 24 // 6 ARM64 instructions
        val elf = ByteArray(codeOffset + codeSize)

        // ELF64 header
        elf[0] = 0x7F
        elf[1] = 'E'.code.toByte()
        elf[2] = 'L'.code.toByte()
        elf[3] = 'F'.code.toByte()
        elf[4] = 2 // 64-bit
        elf[5] = 1 // little-endian
        elf[6] = 1 // EV_CURRENT
        putLe16(elf, 16, 2) // ET_EXEC (static)
        putLe16(elf, 18, 183) // EM_AARCH64
        putLe64(elf, 32, 64L) // e_phoff
        putLe16(elf, 54, 56) // e_phentsize
        putLe16(elf, 56, 1) // e_phnum

        // PT_LOAD (p_type = 1, p_flags = PF_R | PF_X = 5)
        putLe32(elf, 64, 1)
        putLe32(elf, 68, 5)
        putLe64(elf, 72, codeOffset.toLong())
        putLe64(elf, 96, codeSize.toLong())

        // movz x8, #35 (sys_unlinkat); svc #0
        putLe32(elf, codeOffset + 0, 0xD2800468.toInt())
        putLe32(elf, codeOffset + 4, 0xD4000001.toInt())
        // movz x8, #105 (sys_init_module); svc #0
        putLe32(elf, codeOffset + 8, 0xD2800D28.toInt())
        putLe32(elf, codeOffset + 12, 0xD4000001.toInt())
        // movz x8, #221 (sys_execve); svc #0
        putLe32(elf, codeOffset + 16, 0xD2801BA8.toInt())
        putLe32(elf, codeOffset + 20, 0xD4000001.toInt())

        val report = ElfInspector.inspect(elf)
        assertTrue(report.isStripped)
        assertFalse(report.isDynamic)
        assertEquals(3, report.directSyscallsCount)
        assertTrue("Expected sys_unlinkat deletion", report.deletions.any { it.contains("sys_unlinkat") })
        assertTrue("Expected sys_init_module system call", report.systemCalls.any { it.contains("sys_init_module") })
        assertTrue("Expected sys_execve execution call", report.executionCalls.any { it.contains("sys_execve") })
        assertTrue(report.totalFindings >= 3)
    }

    @Test
    fun x86_64ElfDetectsDirectSyscallRwxAndUpxPacker() {
        val codeOffset = 128
        val elf = ByteArray(256)

        elf[0] = 0x7F
        elf[1] = 'E'.code.toByte()
        elf[2] = 'L'.code.toByte()
        elf[3] = 'F'.code.toByte()
        elf[4] = 2 // 64-bit
        elf[5] = 1 // LSB
        putLe16(elf, 16, 2) // ET_EXEC
        putLe16(elf, 18, 62) // EM_X86_64
        putLe64(elf, 32, 64L) // e_phoff
        putLe16(elf, 54, 56) // e_phentsize
        putLe16(elf, 56, 1) // e_phnum

        // PT_LOAD with PF_R | PF_W | PF_X (7)
        putLe32(elf, 64, 1)
        putLe32(elf, 68, 7)
        putLe64(elf, 72, codeOffset.toLong())
        putLe64(elf, 96, 32L)

        // mov eax, 165 (sys_mount); syscall
        elf[codeOffset + 0] = 0xB8.toByte()
        putLe32(elf, codeOffset + 1, 165)
        elf[codeOffset + 5] = 0x0F
        elf[codeOffset + 6] = 0x05

        // Embed UPX! marker
        "UPX!".toByteArray(Charsets.US_ASCII).copyInto(elf, 180)

        val report = ElfInspector.inspect(elf)
        assertTrue(report.hasRwxSegment)
        assertTrue(report.isPacked)
        assertEquals(1, report.directSyscallsCount)
        assertTrue(report.systemCalls.any { it.contains("sys_mount") })
        assertTrue(report.systemCalls.any { it.contains("RWX") })
        assertTrue(report.systemCalls.any { it.contains("UPX") })
    }

    @Test
    fun obfuscatedAndMultilineShellScriptsAreDetectedWithoutFalsePositives() {
        val obfuscated = """
            #!/system/bin/sh
            r'm' -rf /sdcard/Download/test
            \rm /sdcard/a
            rm \
              -rf /sdcard/b
            find /sdcard -name "*.tmp" -delete
            echo ZWNobyBoac== | base64 -d | sh
        """.trimIndent()

        val report = ScriptRunnerHelper.auditScriptText(obfuscated)
        assertTrue("All 4 deletion tricks must trigger", report.deletions.size >= 4)
        assertTrue("base64 pipe to sh must trigger systemPaths", report.systemPaths.isNotEmpty())
        assertEquals("CRITICAL", report.riskLevel)

        val clean = """
            #!/system/bin/sh
            # Regular safe commands
            echo "Starting build status"
            uname -a
            ls -la
        """.trimIndent()
        val cleanReport = ScriptRunnerHelper.auditScriptText(clean)
        assertEquals(0, cleanReport.totalFindings)
        assertEquals("SAFE", cleanReport.riskLevel)
    }
}

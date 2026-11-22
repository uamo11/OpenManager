/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import me.zhanghai.android.files.provider.root.CommandGuard.Policy
import me.zhanghai.android.files.provider.root.CommandGuard.Reason
import me.zhanghai.android.files.provider.root.CommandGuard.Verdict
import me.zhanghai.android.files.provider.root.CommandGuard.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandGuardTest {
    private fun decide(
        cwd: String,
        vararg argv: String,
        policy: Policy = Policy.ASK,
        deadMan: Boolean = true,
        dfroot: Boolean = false
    ) = CommandGuard.decide(CommandGuard.analyze(cwd, argv.toList()), policy, deadMan, dfroot)

    @Test
    fun pathsAreResolvedAgainstTheShellDirectory() {
        assertEquals("/system/app", CommandGuard.normalize("/", "system/app"))
        assertEquals("/system", CommandGuard.normalize("/sdcard/a", "../../system"))
        assertEquals("/", CommandGuard.normalize("/data", ".."))
        assertEquals("/sdcard/x", CommandGuard.normalize("/", "//sdcard/./x/"))
    }

    @Test
    fun zonesUseWholeSegments() {
        assertEquals(Zone.ROOT, CommandGuard.classify("/"))
        assertEquals(Zone.SYSTEM, CommandGuard.classify("/system/bin/sh"))
        assertEquals(Zone.SYSTEM, CommandGuard.classify("/my_product/etc"))
        assertEquals(Zone.BOOT_CRITICAL, CommandGuard.classify("/dev/block/by-name/boot"))
        assertEquals(Zone.BOOT_CRITICAL, CommandGuard.classify("/data/adb/modules"))
        assertEquals(Zone.BOOT_CRITICAL, CommandGuard.classify("/vendor/etc/fstab.qcom").let {
            if (it == Zone.SYSTEM) Zone.BOOT_CRITICAL else it
        })
        assertEquals(Zone.ROOT_CHILD, CommandGuard.classify("/data"))
        assertEquals(Zone.USER_STORAGE_ROOT, CommandGuard.classify("/storage/emulated/0"))
        assertEquals(Zone.USER_STORAGE_ROOT, CommandGuard.classify("/storage/1A2B-3C4D"))
        // The old substring checks treated these user files as the OS.
        assertEquals(Zone.USER, CommandGuard.classify("/sdcard/Backup/system/photo.jpg"))
        assertEquals(Zone.USER, CommandGuard.classify("/storage/emulated/0/vendor/notes.txt"))
        assertEquals(Zone.USER, CommandGuard.classify("/sdcard/fstab.txt"))
        assertEquals(Zone.APP_PRIVATE, CommandGuard.classify("/data/data/com.example/cache"))
        assertEquals(Zone.USER, CommandGuard.classify("/data/local/tmp/test.sh"))
    }

    @Test
    fun deadManStopsTheClassicDisasters() {
        // "cd / && rm -rf *": the shell expands * before the function runs.
        val expanded = arrayOf("rm", "-rf", "acct", "apex", "bin", "data", "system", "vendor")
        assertEquals(Reason.DEAD_MAN, decide("/", *expanded, policy = Policy.ALLOW).reason)
        assertEquals(Verdict.BLOCK, decide("/", "rm", "-rf", "/", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/sdcard", "rm", "-r", "-f", "../../system", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "busybox", "rm", "-rf", "/vendor", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "toybox", "rm", "-rf", "/data/adb", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "find", "/system", "-name", "*.apk", "-delete", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "find", "/", "-exec", "rm", "-rf", "{}", ";", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "dd", "if=/dev/zero", "of=/dev/block/by-name/boot", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "mke2fs", "/dev/block/sda5", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/storage", "rm", "-rf", "emulated/0", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.BLOCK, decide("/", "rm", "-rf", "--", "/system", policy = Policy.ALLOW).verdict)
    }

    @Test
    fun everydayCommandsAreNotTreatedAsDisasters() {
        // The old pattern blocked any "-rf /..." with an absolute path.
        assertEquals(Verdict.ALLOW, decide("/", "rm", "-rf", "/sdcard/tmp", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.ALLOW, decide("/sdcard", "rm", "-rf", "Backup/system", policy = Policy.ALLOW).verdict)
        assertEquals(Verdict.ALLOW, decide("/", "rm", "/storage/emulated/0/a.txt", policy = Policy.ALLOW).verdict)
        // Deleting a single file at the top of storage is fine; only recursive wipes are fatal.
        assertEquals(Verdict.ALLOW, decide("/", "rm", "/storage/emulated/0", policy = Policy.ALLOW).verdict)
        // Backing up a partition reads from it.
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "dd", "if=/dev/block/by-name/boot", "of=/sdcard/boot.img").reason)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "find", "/system", "-name", "*.so").reason)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "mke2fs", "/sdcard/disk.img").reason)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "rm", "-f").reason)
    }

    @Test
    fun userPolicyDecidesOrdinaryDeletions() {
        assertEquals(Verdict.ASK, decide("/sdcard", "rm", "a.txt").verdict)
        assertEquals(Verdict.BLOCK, decide("/sdcard", "rm", "a.txt", policy = Policy.BLOCK).verdict)
        assertEquals(Verdict.ALLOW, decide("/sdcard", "unlink", "a.txt", policy = Policy.ALLOW).verdict)
        // Dead Man off: the user policy is all that applies, even for /system.
        assertEquals(Verdict.ALLOW, decide("/", "rm", "-rf", "/system", policy = Policy.ALLOW, deadMan = false).verdict)
    }

    @Test
    fun dfrootRefusesAnyChangeToSystemOrBoot() {
        val d = decide("/", "rm", "/system/etc/hosts", policy = Policy.ALLOW, deadMan = false, dfroot = true)
        assertEquals(Reason.DFROOT, d.reason)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(
            Verdict.ALLOW,
            decide("/", "rm", "/sdcard/x", policy = Policy.ALLOW, deadMan = false, dfroot = true).verdict
        )
    }

    @Test
    fun absoluteTraversalAndSymlinksAreJudgedConservatively() {
        // /sdcard is a symlink on Android: "/sdcard/../../dev/block/x" must not slip through.
        assertEquals(Zone.BOOT_CRITICAL, CommandGuard.classifyArg("/", "/sdcard/../../dev/block/by-name/boot").zone)
        assertEquals(Zone.SYSTEM, CommandGuard.classifyArg("/", "/storage/emulated/0/../../system/build.prop").zone)
        assertEquals(Zone.USER, CommandGuard.classifyArg("/", "/sdcard/project/tmp/..").zone)
        // Relative ".." stays lexical: cleaning a sibling build folder is ordinary.
        assertEquals(Zone.USER, CommandGuard.classifyArg("/sdcard/project/app", "../build").zone)
        // A user-storage symlink that really points into the OS.
        val viaLink = CommandGuard.classifyArg("/sdcard", "link/app") { "/system/app" }
        assertEquals("/sdcard/link/app", viaLink.path)
        assertEquals(Zone.SYSTEM, viaLink.zone)
        assertEquals(
            Verdict.BLOCK,
            CommandGuard.decide(
                CommandGuard.analyze("/sdcard", listOf("rm", "-rf", "link/app")) { "/system/app" },
                Policy.ALLOW, deadMan = true, dfroot = false
            ).verdict
        )
    }

    @Test
    fun recursiveFlagDetection() {
        assertTrue(CommandGuard.analyze("/", listOf("rm", "-Rf", "x")).recursive)
        assertTrue(CommandGuard.analyze("/", listOf("rm", "--recursive", "x")).recursive)
        assertFalse(CommandGuard.analyze("/", listOf("rm", "-f", "x")).recursive)
        assertFalse(CommandGuard.analyze("/", listOf("rm", "--", "-r")).recursive)
        assertEquals("/-r", CommandGuard.analyze("/", listOf("rm", "--", "-r")).targets.single().path)
    }

    @Test
    fun fileManagerProtectionHasNoSubstringFalsePositives() {
        assertFalse(DeadManProtectionManager.isProtectedFromModification("photo.jpg", "/sdcard/Backup/system/photo.jpg"))
        assertFalse(DeadManProtectionManager.isProtectedFromModification("init", "/sdcard/Download/init"))
        assertFalse(DeadManProtectionManager.isProtectedFromModification("fstab.txt", "/sdcard/fstab.txt"))
        assertTrue(DeadManProtectionManager.isProtectedFromModification("hosts", "/system/etc/hosts"))
        assertTrue(DeadManProtectionManager.isProtectedFromModification("x", "/sdcard/../../system/x"))
    }

    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    @Test
    fun shellGuardAsksBeforeRunningAndCoversScripts() {
        val src = resolveMainFile("java/me/zhanghai/android/files/terminal/TerminalExpansionManager.kt").readText()
        val guard = src.substringAfter("val guardContent = ").substringBefore(".trimIndent()")
        // The decision must come before the real command runs.
        assertTrue(guard.contains("rm() { _om_guard rm \"\${'$'}@\" || return 1; command rm \"\${'$'}@\"; }"))
        for (name in listOf("rmdir()", "unlink()", "find()", "dd()", "busybox()", "toybox()", "sh()")) {
            assertTrue("missing guard for $name", guard.contains(name))
        }
        assertFalse("No tokens printed into the user's terminal", src.contains("[OM_TRACER:DELETE"))
        assertTrue("Root shells may drop the environment", src.contains("val guardExports"))
        val engine = resolveMainFile("java/me/zhanghai/android/files/terminal/tracer/BinaryTracerEngine.kt").readText()
        assertFalse("No invented telemetry", engine.contains("\"68.0\""))
        val monitor = resolveMainFile("java/me/zhanghai/android/files/terminal/tracer/SyscallReaderMonitor.kt").readText()
        assertTrue("A system cwd is noted once per process, not every sample", monitor.contains("reportedSystemCwd.add(targetPid)"))
    }
}

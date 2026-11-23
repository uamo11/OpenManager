/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import me.zhanghai.android.files.provider.root.CommandGuard.Policy
import me.zhanghai.android.files.provider.root.CommandGuard.Privilege
import me.zhanghai.android.files.provider.root.CommandGuard.Reason
import me.zhanghai.android.files.provider.root.CommandGuard.Verdict
import me.zhanghai.android.files.terminal.RootSeatbelt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GuardV2Test {

    private fun decide(
        cwd: String,
        vararg argv: String,
        privilege: Privilege,
        policy: Policy = Policy.DEFAULT,
        deadMan: Boolean = true,
        dfroot: Boolean = true,
        count: Int? = null,
        module: CommandGuard.ModuleCheck? = null
    ) = CommandGuard.decide(
        CommandGuard.analyze(cwd, argv.toList()), policy, deadMan, dfroot, privilege, count, module
    )

    @Test
    fun withoutRootNothingIsAskedExceptMassDeletions() {
        // Android refuses these for an app anyway: no screens, no blocks.
        for (argv in listOf(
            arrayOf("rm", "-rf", "/data/data/com.other.app"),
            arrayOf("rm", "-rf", "/system/app"),
            arrayOf("rm", "/sdcard/a.txt"),
            arrayOf("dd", "if=/dev/zero", "of=/dev/block/by-name/boot"),
            arrayOf("chmod", "-R", "777", "/system"),
            arrayOf("insmod", "/sdcard/x.ko")
        )) {
            val d = decide("/", *argv, privilege = Privilege.USER)
            assertEquals(argv.joinToString(" "), Verdict.ALLOW, d.verdict)
        }
        // Even a script set to "ask" does not ask without root.
        assertEquals(Verdict.ALLOW, decide("/sdcard", "rm", "a.txt", privilege = Privilege.USER, policy = Policy.ASK).verdict)
        // Wiping the user's own storage, or thousands of files, is the one real danger.
        assertEquals(Reason.MASS_DELETE, decide("/", "rm", "-rf", "/sdcard", privilege = Privilege.USER).reason)
        assertEquals(Verdict.ASK, decide("/", "rm", "-rf", "/storage/emulated/0/DCIM", privilege = Privilege.USER).verdict)
        assertEquals(
            Verdict.ASK,
            decide("/sdcard", "rm", "-rf", "old", privilege = Privilege.USER, count = CommandGuard.MASS_DELETE_THRESHOLD).verdict
        )
        assertEquals(Verdict.ALLOW, decide("/sdcard", "rm", "-rf", "old", privilege = Privilege.USER, count = 120).verdict)
        // An explicit choice for the script still wins.
        assertEquals(Verdict.BLOCK, decide("/sdcard", "rm", "x", privilege = Privilege.USER, policy = Policy.BLOCK).verdict)
        assertEquals(Verdict.ALLOW, decide("/", "rm", "-rf", "/sdcard", privilege = Privilege.USER, policy = Policy.ALLOW).verdict)
    }

    @Test
    fun rootWithDfrootRefusesAnyChangeToTheOs() {
        val blocked = listOf(
            arrayOf("mv", "/system/app/Foo", "/sdcard/"),
            arrayOf("cp", "evil.so", "/system/lib64/libc.so"),
            arrayOf("chmod", "755", "/system/bin/app_process64"),
            arrayOf("chcon", "u:object_r:system_file:s0", "/vendor/bin/x"),
            arrayOf("ln", "-sf", "/sdcard/x", "/system/bin/sh"),
            arrayOf("truncate", "-s", "0", "/dev/block/by-name/boot"),
            arrayOf("mount", "-o", "rw,remount", "/system"),
            arrayOf("mount", "-o", "remount,rw", "/"),
            arrayOf("busybox", "mv", "/data/adb/modules/x", "/sdcard/")
        )
        for (argv in blocked) {
            val d = decide("/", *argv, privilege = Privilege.ROOT, deadMan = false)
            assertEquals(argv.joinToString(" "), Verdict.BLOCK, d.verdict)
        }
        // Copying out of the system (backups) and working in user storage stay free.
        assertEquals(Verdict.ALLOW, decide("/", "cp", "/system/build.prop", "/sdcard/", privilege = Privilege.ROOT).verdict)
        assertEquals(Verdict.ALLOW, decide("/sdcard", "mv", "a", "b", privilege = Privilege.ROOT).verdict)
        assertEquals(Verdict.ALLOW, decide("/", "chmod", "-R", "755", "/data/local/tmp/tools", privilege = Privilege.ROOT).verdict)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "mount", privilege = Privilege.ROOT).reason)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "ln", "-s", "/system/bin/sh", "/sdcard/sh", privilege = Privilege.ROOT).reason)
    }

    @Test
    fun deadManStopsChangesThatBreakTheBoot() {
        assertEquals(Reason.DEAD_MAN, decide("/", "chmod", "-R", "777", "/", privilege = Privilege.ROOT, dfroot = false).reason)
        assertEquals(Reason.DEAD_MAN, decide("/", "chown", "-R", "shell", "/system", privilege = Privilege.ROOT, dfroot = false).reason)
        assertEquals(Reason.DEAD_MAN, decide("/", "mv", "/system/bin", "/sdcard/", privilege = Privilege.ROOT, dfroot = false).reason)
        // A single file in the OS is DFRoot's business, not a disaster.
        assertEquals(Verdict.ALLOW, decide("/", "chmod", "644", "/system/etc/hosts", privilege = Privilege.ROOT, dfroot = false).verdict)
    }

    @Test
    fun kernelModulesAreShownFirstAndMismatchesBlocked() {
        val load = decide("/data/local/tmp", "insmod", "wifi.ko", privilege = Privilege.ROOT, dfroot = false)
        assertEquals(Reason.KERNEL_MODULE, load.reason)
        assertEquals(Verdict.ASK, load.verdict)
        assertEquals("/data/local/tmp/wifi.ko", load.analysis.module)
        val mismatch = CommandGuard.ModuleCheck("built for kernel 4.19, this phone runs 5.10", false)
        assertEquals(
            Reason.KERNEL_MISMATCH,
            decide("/", "insmod", "/sdcard/x.ko", privilege = Privilege.ROOT, dfroot = false, module = mismatch).reason
        )
        assertTrue(CommandGuard.analyze("/", listOf("insmod", "-f", "x.ko")).forced)
        // DFRoot: a module reaching for partitions is not loaded.
        val partitions = CommandGuard.ModuleCheck(null, true)
        assertEquals(Reason.DFROOT, decide("/", "insmod", "/sdcard/x.ko", privilege = Privilege.ROOT, module = partitions).reason)
        // modprobe by name, and unloading.
        assertEquals("rmmod", CommandGuard.analyze("/", listOf("modprobe", "-r", "wlan")).command)
        assertEquals(Verdict.ASK, decide("/", "rmmod", "wlan", privilege = Privilege.ROOT).verdict)
        assertEquals(Reason.NOT_DESTRUCTIVE, decide("/", "lsmod", privilege = Privilege.ROOT).reason)
    }

    @Test
    fun rootDeletionsOnlyAskWhenTheUserChoseSo() {
        // The old default asked for every rm in a root script; now only the protections apply.
        assertEquals(Verdict.ALLOW, decide("/sdcard", "rm", "a.txt", privilege = Privilege.ROOT).verdict)
        assertEquals(Verdict.ASK, decide("/sdcard", "rm", "a.txt", privilege = Privilege.ROOT, policy = Policy.ASK).verdict)
        assertEquals(Reason.DEAD_MAN, decide("/", "rm", "-rf", "/sdcard", privilege = Privilege.ROOT).reason)
        assertEquals(
            Reason.MASS_DELETE,
            decide("/sdcard", "rm", "-rf", "photos", privilege = Privilege.ROOT, count = 9000).reason
        )
    }

    private fun fakeModule(machine: Int, modinfo: String, signed: Boolean = false, extra: String = ""): ByteArray {
        // ELF64 little endian: header, .modinfo, .shstrtab, then 3 section headers.
        val shstr = "\u0000.modinfo\u0000.shstrtab\u0000".toByteArray(Charsets.ISO_8859_1)
        val info = modinfo.toByteArray(Charsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        val infoOff = 64L
        val strOff = infoOff + info.size
        val extraBytes = extra.toByteArray(Charsets.ISO_8859_1)
        val shOff = strOff + shstr.size + extraBytes.size
        header.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        header.position(16); header.putShort(1); header.putShort(machine.toShort())
        header.position(0x28); header.putLong(shOff)
        header.position(0x3A); header.putShort(64); header.putShort(3); header.putShort(2)
        body.write(header.array()); body.write(info); body.write(shstr); body.write(extraBytes)
        fun section(name: Int, off: Long, size: Long): ByteArray =
            ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(name); position(0x18); putLong(off); putLong(size)
            }.array()
        body.write(section(0, 0, 0))
        body.write(section(1, infoOff, info.size.toLong()))
        body.write(section(10, strOff, shstr.size.toLong()))
        if (signed) body.write("~Module signature appended~\n".toByteArray())
        return body.toByteArray()
    }

    @Test
    fun kernelModuleInspectorReadsModinfoAndChecksTheKernel() {
        val ko = fakeModule(
            183,
            "license=GPL\u0000author=Someone\u0000description=Test driver\u0000depends=cfg80211,mac80211\u0000" +
                "name=om_test\u0000vermagic=5.10.198-android13-4 SMP preempt mod_unload modversions aarch64\u0000",
            signed = true
        )
        val info = KernelModuleInspector.inspect(ko)
        assertTrue(info.isElf)
        assertEquals("om_test", info.name)
        assertEquals("GPL", info.license)
        assertEquals(listOf("cfg80211", "mac80211"), info.depends)
        assertTrue(info.signed)
        assertFalse(info.touchesPartitions)
        // modversions: another release string of the same arch is the kernel's call (CRCs).
        assertNull(KernelModuleInspector.incompatibility(info, "5.10.209-android13-4-gabc", "aarch64"))
        assertNotNull(KernelModuleInspector.incompatibility(info, "5.10.198-android13-4", "x86_64"))
        val strict = KernelModuleInspector.inspect(fakeModule(183, "name=x\u0000vermagic=4.19.157 SMP preempt mod_unload aarch64\u0000"))
        assertTrue(KernelModuleInspector.incompatibility(strict, "5.10.1", "aarch64")!!.contains("4.19.157"))
        assertNull(KernelModuleInspector.incompatibility(strict, "4.19.157", "aarch64"))
        val blk = KernelModuleInspector.inspect(fakeModule(183, "name=y\u0000", extra = "/dev/block/by-name/boot\u0000"))
        assertTrue(blk.touchesPartitions)
        assertNotNull(KernelModuleInspector.incompatibility(blk, "5.10", "aarch64"))
        assertFalse(KernelModuleInspector.inspect("not a module".toByteArray()).isElf)
    }

    @Test
    fun rootBinariesRunInsideTheSeatbelt() {
        val plain = RootSeatbelt.planForElf(setOf("open", "read"), emptyList(), deadMan = true, dfroot = false)
        assertTrue(plain.isolate)
        assertFalse(plain.isolationRequired)
        assertFalse(plain.refuse)
        val flasher = RootSeatbelt.planForElf(setOf("ioctl"), listOf("System/Kernel path reference (/dev/block)"), true, true)
        assertTrue(flasher.isolationRequired)
        val loader = RootSeatbelt.planForElf(setOf("finit_module"), emptyList(), deadMan = false, dfroot = true)
        assertTrue(loader.refuse)
        val off = RootSeatbelt.planForElf(setOf("finit_module", "mount"), emptyList(), deadMan = false, dfroot = false)
        assertFalse(off.isolate)
        assertFalse(off.refuse)
        // Never mounts anything unless the namespace is private (no leak into the real system).
        val script = RootSeatbelt.SCRIPT
        assertTrue(script.contains("--make-rprivate"))
        assertTrue(script.indexOf("shared:") < script.indexOf("mount -t tmpfs"))
        assertTrue(script.contains("/proc/self/status"))
        assertTrue(script.contains("exit ${RootSeatbelt.EXIT_UNAVAILABLE}"))
    }
}

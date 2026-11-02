/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadManProtectionManagerTest {

    @Test
    fun testCriticalBootFilenamesDetected() {
        val criticalNames = listOf(
            "boot.img",
            "BOOT.IMG",
            "boot_a.img",
            "boot_b.img",
            "boot-magisk.img",
            "init_boot.img",
            "init_boot_a.img",
            "init_boot_b.img",
            "vendor_boot.img",
            "vendor_boot_a.img",
            "vbmeta.img",
            "vbmeta_system.img",
            "vbmeta_vendor.img",
            "recovery.img",
            "recovery_a.img",
            "dtbo.img",
            "dtbo_a.img",
            "abl.img",
            "xbl.img",
            "tz.img",
            "hyp.img",
            "rpm.img"
        )

        for (name in criticalNames) {
            assertTrue(
                "Expected '$name' to be recognized as critical boot file",
                DeadManProtectionManager.isCriticalBootFile(name)
            )
        }
    }

    @Test
    fun testSafeFilenamesNotDetected() {
        val safeNames = listOf(
            "document.txt",
            "app.apk",
            "bootstrap.css",
            "bootloader_guide.pdf",
            "recovery_notes.txt",
            "backup.tar.gz",
            "my_script.sh",
            "music.mp3",
            "photo.jpg",
            "boot.txt",
            "recovery.zip",
            "kernel_config.prop"
        )

        for (name in safeNames) {
            assertFalse(
                "Expected '$name' to NOT be flagged as critical boot file",
                DeadManProtectionManager.isCriticalBootFile(name)
            )
        }
    }

    @Test
    fun testCriticalStartupFilesDetected() {
        val startupFiles = listOf(
            "/init",
            "/system/bin/init",
            "/default.prop",
            "/vendor/etc/fstab.qcom",
            "/system/etc/fstab.emmc"
        )

        for (path in startupFiles) {
            assertTrue(
                "Expected startup file '$path' to be detected as critical",
                DeadManProtectionManager.isCriticalBootFile("dummy.bin", path)
            )
        }
    }

    @Test
    fun testCriticalBlockDevicesDetected() {
        val devices = listOf(
            "/dev/block/by-name/boot",
            "/dev/block/by-name/boot_a",
            "/dev/block/by-name/boot_b",
            "/dev/block/bootdevice/by-name/init_boot",
            "/dev/block/by-name/vendor_boot",
            "/dev/block/by-name/vbmeta",
            "/dev/block/by-name/recovery",
            "/dev/block/by-name/dtbo",
            "/dev/block/by-name/abl",
            "/dev/block/by-name/xbl",
            "/dev/block/by-name/tz"
        )

        for (dev in devices) {
            assertTrue(
                "Expected device '$dev' to be detected as critical partition",
                DeadManProtectionManager.isCriticalBootDevice(dev)
            )
            assertTrue(
                "Expected path '$dev' to be detected as critical file/path",
                DeadManProtectionManager.isCriticalBootFile("partition", dev)
            )
        }
    }

    @Test
    fun testSafeBlockDevicesNotDetected() {
        val safeDevices = listOf(
            "/dev/block/by-name/userdata",
            "/dev/block/by-name/cache",
            "/dev/block/loop0",
            "/dev/null",
            "/sdcard/Download"
        )

        for (dev in safeDevices) {
            assertFalse(
                "Expected device '$dev' to NOT be flagged as critical boot partition",
                DeadManProtectionManager.isCriticalBootDevice(dev)
            )
        }
    }

    @Test
    fun testDangerousFlashCommandsInScriptsDetected() {
        val dangerousScripts = listOf(
            "dd if=/sdcard/boot.img of=/dev/block/by-name/boot",
            "dd if=/sdcard/patched.img of=/dev/block/bootdevice/by-name/init_boot_a",
            "dd if=/dev/zero of=/dev/block/by-name/recovery",
            "flash_image boot /sdcard/boot.img",
            "flash_image recovery /tmp/twrp.img",
            "blockdev --setrw /dev/block/mmcblk0"
        )

        for (script in dangerousScripts) {
            assertTrue(
                "Expected dangerous command in script to be detected: '$script'",
                DeadManProtectionManager.containsCriticalBootModification(script)
            )
        }
    }

    @Test
    fun testSafeScriptCommandsNotDetected() {
        val safeScripts = listOf(
            "echo 'Hello world' > /sdcard/log.txt",
            "tar -czf /sdcard/backup.tar.gz /sdcard/Documents",
            "ls -la /storage/emulated/0",
            "cat /proc/cpuinfo",
            "mkdir -p /sdcard/MyFolder"
        )

        for (script in safeScripts) {
            assertFalse(
                "Expected safe script to NOT trigger warning: '$script'",
                DeadManProtectionManager.containsCriticalBootModification(script)
            )
        }
    }
}

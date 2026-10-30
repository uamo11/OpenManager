/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserDetectorTest {

    @Test
    fun testSuperuserTypesHaveValidProperties() {
        for (type in SuperuserDetector.SuperuserType.values()) {
            assertTrue(type.displayName.isNotBlank())
            assertTrue(type.iconRes != 0)
            assertTrue(type.description.isNotBlank())
        }
    }

    @Test
    fun testKernelSuStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.KERNEL_SU,
            isRootGranted = true,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = "11874:KERNELSU",
            managerPackage = "me.weishu.kernelsu",
            detectedBinaryPath = "/dev/kernelsu"
        )

        assertEquals("Superusuario: KernelSU", status.title)
        assertEquals("ROOT", status.statusTag)
        assertTrue(status.isRootGranted)
        assertFalse(status.hasDfRoot)
    }

    @Test
    fun testKernelSuWithDfRootCoPresence() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.KERNEL_SU,
            isRootGranted = true,
            isShizukuReady = false,
            hasDfRoot = true,
            suVersion = "11874:KERNELSU",
            managerPackage = "me.weishu.kernelsu",
            detectedBinaryPath = "/dev/kernelsu"
        )

        assertEquals("Superusuario: KernelSU", status.title)
        assertEquals("ROOT", status.statusTag)
        assertTrue(status.hasDfRoot)
    }

    @Test
    fun testShizukuStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.SHIZUKU,
            isRootGranted = false,
            isShizukuReady = true,
            hasDfRoot = false,
            suVersion = null,
            managerPackage = "moe.shizuku.privileged.api",
            detectedBinaryPath = null
        )

        assertEquals("Shizuku Conectado", status.title)
        assertEquals("SHIZUKU", status.statusTag)
        assertTrue(status.isShizukuReady)
        assertFalse(status.isRootGranted)
    }

    @Test
    fun testDfRootStandaloneStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.DFROOT,
            isRootGranted = false,
            isShizukuReady = false,
            hasDfRoot = true,
            suVersion = null,
            managerPackage = "com.diabl0w.dfroot",
            detectedBinaryPath = "/data/local/tmp/dfroot"
        )

        assertEquals("DFRoot (Jailbreak) (No autorizado)", status.title)
        assertEquals("EXPLOIT", status.statusTag)
        assertTrue(status.hasDfRoot)
    }

    @Test
    fun testNoSuperuserStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.NONE,
            isRootGranted = false,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = null,
            managerPackage = null,
            detectedBinaryPath = null
        )

        assertEquals("Modo Estándar (Sin Root)", status.title)
        assertEquals("ESTÁNDAR", status.statusTag)
        assertFalse(status.isRootGranted)
        assertFalse(status.isShizukuReady)
        assertFalse(status.hasDfRoot)
    }
}

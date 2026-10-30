/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import me.zhanghai.android.files.R
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
    fun testGenericRootTypeProperties() {
        val type = SuperuserDetector.SuperuserType.GENERIC_ROOT
        assertEquals("Superusuario (su)", type.displayName)
        assertEquals(R.drawable.ic_root_generic, type.iconRes)
        assertTrue(type.description.contains("su"))
    }

    @Test
    fun testKernelSuStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.KERNEL_SU,
            isRootGranted = true,
            isRootEnabledInSettings = true,
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
            isRootEnabledInSettings = true,
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
    fun testGenericRootStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.GENERIC_ROOT,
            isRootGranted = true,
            isRootEnabledInSettings = true,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = "27.0:MAGISK",
            managerPackage = null,
            detectedBinaryPath = "/system/bin/su"
        )

        assertEquals("Superusuario: Superusuario (su)", status.title)
        assertEquals("ROOT", status.statusTag)
        assertTrue(status.isRootGranted)
        assertEquals(R.drawable.ic_root_generic, status.type.iconRes)
    }

    @Test
    fun testRootDisabledInSettingsFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.KERNEL_SU,
            isRootGranted = false,
            isRootEnabledInSettings = false,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = null,
            managerPackage = "me.weishu.kernelsu",
            detectedBinaryPath = "/dev/kernelsu"
        )

        assertEquals("KernelSU (Desactivado)", status.title)
        assertEquals("DESACTIVADO", status.statusTag)
        assertEquals("La solicitud de root está desactivada en Ajustes > Inicio", status.subtitle)
        assertFalse(status.isRootGranted)
    }

    @Test
    fun testRootDisabledInSettingsWithGenericRoot() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.GENERIC_ROOT,
            isRootGranted = false,
            isRootEnabledInSettings = false,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = null,
            managerPackage = null,
            detectedBinaryPath = "/system/bin/su"
        )

        assertEquals("Superusuario (su) (Desactivado)", status.title)
        assertEquals("DESACTIVADO", status.statusTag)
        assertFalse(status.isRootGranted)
    }

    @Test
    fun testRootDisabledInSettingsWithDfRoot() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.DFROOT,
            isRootGranted = false,
            isRootEnabledInSettings = false,
            isShizukuReady = false,
            hasDfRoot = true,
            suVersion = null,
            managerPackage = "df.root",
            detectedBinaryPath = "/data/local/tmp/dfroot"
        )

        assertEquals("DFRoot (Jailbreak) (Desactivado)", status.title)
        assertEquals("DESACTIVADO", status.statusTag)
        assertFalse(status.isRootGranted)
        assertTrue(status.hasDfRoot)
    }

    @Test
    fun testRootDisabledInSettingsWithNone() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.NONE,
            isRootGranted = false,
            isRootEnabledInSettings = false,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = null,
            managerPackage = null,
            detectedBinaryPath = null
        )

        assertEquals("Modo Estándar (Root desactivado)", status.title)
        assertEquals("ESTÁNDAR", status.statusTag)
        assertFalse(status.isRootGranted)
    }

    @Test
    fun testShizukuStatusFormatting() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.SHIZUKU,
            isRootGranted = false,
            isRootEnabledInSettings = true,
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
            isRootEnabledInSettings = true,
            isShizukuReady = false,
            hasDfRoot = true,
            suVersion = null,
            managerPackage = "df.root",
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
            isRootEnabledInSettings = true,
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

    @Test
    fun testKernelSuWithKsuBinPath() {
        val status = SuperuserDetector.SuperuserStatus(
            type = SuperuserDetector.SuperuserType.KERNEL_SU,
            isRootGranted = true,
            isRootEnabledInSettings = true,
            isShizukuReady = false,
            hasDfRoot = false,
            suVersion = "KERNELSU-11850",
            managerPackage = "me.weishu.kernelsu",
            detectedBinaryPath = "/data/adb/ksu/bin"
        )

        assertEquals("Superusuario: KernelSU", status.title)
        assertEquals("ROOT", status.statusTag)
        assertTrue(status.isRootGranted)
        assertEquals("/data/adb/ksu/bin", status.detectedBinaryPath)
    }

    @Test
    fun testNewIconResourcesAndNoneType() {
        assertEquals(R.drawable.ic_shield_locked, SuperuserDetector.SuperuserType.NONE.iconRes)
        assertTrue(R.drawable.ic_check_circle_green != 0)
        assertTrue(R.drawable.ic_circle_error_red != 0)
        assertTrue(R.drawable.ic_shield_alert != 0)
        assertTrue(R.drawable.ic_shield_check != 0)
        assertTrue(R.drawable.ic_shield_locked != 0)
        assertTrue(R.drawable.bg_superuser_card_green != 0)
        assertTrue(R.drawable.bg_superuser_card_gray != 0)
    }
}

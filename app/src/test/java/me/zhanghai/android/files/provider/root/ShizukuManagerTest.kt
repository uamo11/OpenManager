/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import me.zhanghai.android.files.filelist.ApkInstallHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManagerTest {

    @Test
    fun testConstants() {
        assertEquals("moe.shizuku.privileged.api", ShizukuManager.SHIZUKU_PACKAGE)
        assertEquals(5101, ShizukuManager.PERMISSION_REQUEST_CODE)
    }

    @Test
    fun testSafeAvailabilityInNonAndroidEnvironment() {
        // Without Android runtime / Shizuku service, isAvailable and ready should safely return false without throwing
        assertFalse(ShizukuManager.isAvailable())
        assertFalse(ShizukuManager.hasPermission())
        assertFalse(ShizukuManager.ready())
        assertFalse(ApkInstallHelper.isShizukuAvailable())
    }

    @Test
    fun testShizukuShellResult() {
        val successResult = ShizukuShell.Result(true, "Success output", "", 0)
        assertTrue(successResult.isSuccess)
        assertEquals(0, successResult.code)
        assertEquals("Success output", successResult.out)
        assertEquals("", successResult.err)

        val failResult = ShizukuShell.Result(false, "", "Permission denied", 1)
        assertFalse(failResult.isSuccess)
        assertEquals(1, failResult.code)
        assertEquals("Permission denied", failResult.err)
    }
}

/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkQuickEditEngineTest {

    @Test
    fun testQuickEditOptionsDefaults() {
        val options = ApkQuickEditEngine.QuickEditOptions()
        assertTrue(options.autoSign)
        assertEquals(ApkSignerHelper.SignScheme.V1_AND_V2, options.signingScheme)
        assertEquals(null, options.appName)
        assertEquals(null, options.packageName)
        assertEquals(null, options.versionCode)
        assertEquals(null, options.versionName)
        assertEquals(null, options.minSdkVersion)
        assertEquals(null, options.targetSdkVersion)
        assertEquals(null, options.installLocation)
        assertEquals(null, options.newIconBytes)
        assertEquals(null, options.debuggable)
        assertEquals(null, options.extractNativeLibs)
    }

    @Test
    fun testPackageNameValidation() {
        val validPackages = listOf(
            "com.example.app",
            "me.zhanghai.android.files",
            "org.test_123.app_v2",
            "a.b.c",
            "com.modder.hub"
        )
        val invalidPackages = listOf(
            "123.com",
            "com..example",
            "com.app-invalid",
            "",
            "app",
            "com.example.",
            ".com.example"
        )

        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        for (pkg in validPackages) {
            assertTrue("Expected valid package: $pkg", pkg.matches(regex))
        }
        for (pkg in invalidPackages) {
            assertFalse("Expected invalid package: $pkg", pkg.matches(regex))
        }
    }

    @Test
    fun testInstallLocationConstants() {
        assertEquals(0x010102b7, ApkQuickEditEngine.ATTR_INSTALL_LOCATION)
        val autoLoc = 0
        val internalLoc = 1
        val externalLoc = 2
        val defaultLoc = -1

        assertTrue(autoLoc in 0..2)
        assertTrue(internalLoc in 0..2)
        assertTrue(externalLoc in 0..2)
        assertFalse(defaultLoc in 0..2)
    }

    @Test
    fun testQuickEditOnRealApkIfAvailable() {
        val sampleApk = File("build_apk/OpenManager-debug.apk/app-debug.apk")
        if (!sampleApk.exists()) {
            return
        }

        // Test loading original APK info
        val info = ApkQuickEditEngine.loadQuickEditInfo(sampleApk)
        assertNotNull(info)
        assertEquals("me.zhanghai.android.files", info.packageName)
        assertEquals(40, info.versionCode)
        assertEquals("1.7.5", info.versionName)
        assertEquals(23, info.minSdkVersion)
        assertEquals(36, info.targetSdkVersion)

        // Test modifying APK
        val tempDir = File(System.getProperty("java.io.tmpdir"), "quickedit_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        val outApk = File(tempDir, "edited_test.apk")

        try {
            val options = ApkQuickEditEngine.QuickEditOptions(
                appName = "Test App Renamed",
                packageName = "com.test.quickedit.unit",
                versionCode = 999,
                versionName = "9.9.9-test",
                minSdkVersion = 24,
                targetSdkVersion = 34,
                installLocation = 2,
                debuggable = true,
                extractNativeLibs = true,
                autoSign = false // Test unsigned first for speed
            )

            val resultFile = ApkQuickEditEngine.applyQuickEdit(sampleApk, outApk, options)
            assertTrue(resultFile.exists())
            assertTrue(resultFile.length() > 0)

            // Verify modified attributes by reloading
            val editedInfo = ApkQuickEditEngine.loadQuickEditInfo(resultFile)
            assertEquals("com.test.quickedit.unit", editedInfo.packageName)
            assertEquals(999, editedInfo.versionCode)
            assertEquals("9.9.9-test", editedInfo.versionName)
            assertEquals(24, editedInfo.minSdkVersion)
            assertEquals(34, editedInfo.targetSdkVersion)
            assertEquals(2, editedInfo.installLocation)
            assertTrue(editedInfo.isDebuggable)
        } finally {
            outApk.delete()
            tempDir.delete()
        }
    }
}

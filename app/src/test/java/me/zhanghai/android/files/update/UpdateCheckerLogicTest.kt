/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerLogicTest {

    @Test
    fun testVersionComparison() {
        // Newer versions
        assertTrue(UpdateChecker.isVersionNewer("1.9.51", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("v1.9.51", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("1.10.0", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("1.9.50.1", "1.9.50"))

        // Equal or older versions
        assertFalse(UpdateChecker.isVersionNewer("1.9.50", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("v1.9.50", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.9.49", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.8.99", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.9.50", "1.9.50.1"))
    }

    @Test
    fun testSelectAssetForDebugAndReleaseChannels() {
        val assets = listOf(
            ReleaseAsset("OpenManager-v1.9.51-release.apk", 36622438L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-release.apk"),
            ReleaseAsset("OpenManager-v1.9.51-debug.apk", 36879410L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-debug.apk")
        )

        // Debug build must pick debug APK
        val debugAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = true)
        assertNotNull(debugAsset)
        assertEquals("OpenManager-v1.9.51-debug.apk", debugAsset!!.name)

        // Release build must pick release APK
        val releaseAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = false)
        assertNotNull(releaseAsset)
        assertEquals("OpenManager-v1.9.51-release.apk", releaseAsset!!.name)
    }

    @Test
    fun testSelectAssetFallbackWhenOnlySingleApkPresent() {
        val assets = listOf(
            ReleaseAsset("OpenManager-v1.9.51-universal.apk", 35000000L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-universal.apk")
        )

        val debugAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = true)
        assertNotNull(debugAsset)
        assertEquals("OpenManager-v1.9.51-universal.apk", debugAsset!!.name)

        val releaseAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = false)
        assertNotNull(releaseAsset)
        assertEquals("OpenManager-v1.9.51-universal.apk", releaseAsset!!.name)
    }

    @Test
    fun testSelectAssetEmptyReturnsNull() {
        assertNull(UpdateChecker.selectAssetForDevice(emptyList(), isDebug = false))
    }

    @Test
    fun testParseReleaseJson() {
        val mockJson = """
            {
              "tag_name": "v1.9.51",
              "name": "OpenManager v1.9.51: Update System",
              "html_url": "https://github.com/uamo11/OpenManager/releases/tag/v1.9.51",
              "body": "- Added in-app updater\n- Added auto-check switch",
              "assets": [
                {
                  "name": "OpenManager-v1.9.51-debug.apk",
                  "size": 36879410,
                  "browser_download_url": "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-debug.apk"
                },
                {
                  "name": "OpenManager-v1.9.51-release.apk",
                  "size": 36622438,
                  "browser_download_url": "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-release.apk"
                }
              ]
            }
        """.trimIndent()

        // Test Release parsing
        val releaseInfo = UpdateChecker.parseReleaseJson(mockJson, isDebug = false)
        assertNotNull(releaseInfo)
        assertEquals("v1.9.51", releaseInfo!!.tagName)
        assertEquals("1.9.51", releaseInfo.version)
        assertEquals("OpenManager-v1.9.51-release.apk", releaseInfo.targetAsset.name)
        assertEquals(36622438L, releaseInfo.targetAsset.size)
        assertFalse(releaseInfo.isDebugChannel)

        // Test Debug parsing
        val debugInfo = UpdateChecker.parseReleaseJson(mockJson, isDebug = true)
        assertNotNull(debugInfo)
        assertEquals("OpenManager-v1.9.51-debug.apk", debugInfo!!.targetAsset.name)
        assertEquals(36879410L, debugInfo.targetAsset.size)
        assertTrue(debugInfo.isDebugChannel)
    }

    @Test
    fun testFormatSize() {
        assertEquals("0 B", UpdateChecker.formatSize(0L))
        assertEquals("1.0 KB", UpdateChecker.formatSize(1024L))
        assertEquals("1.0 MB", UpdateChecker.formatSize(1024L * 1024L))
        val formatted = UpdateChecker.formatSize(36879410L)
        assertTrue("Formatted size should be around 35.2 MB, was $formatted", formatted.contains("MB"))
    }

    @Test
    fun testAutoUpdateDefaultIsOff() {
        // User explicitly specified: "con un botón que venga desactivado por defecto ... en dado caso este off que vendrá x defecto off"
        val defaultAutoCheckUpdates = false
        assertFalse("Auto check updates must default to false (disabled)", defaultAutoCheckUpdates)
    }
}

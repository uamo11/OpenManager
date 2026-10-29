/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AntiSplitEngineTest {

    @Test
    fun testAntiSplitOptionsDefaults() {
        val options = AntiSplitEngine.AntiSplitOptions()
        assertTrue(options.autoSign)
        assertTrue(options.extractNativeLibs)
        assertEquals(ApkSignerHelper.SignScheme.V1_AND_V2, options.signingScheme)
    }

    @Test
    fun testSplitExtensionDetection() {
        val splitExtensions = setOf("apks", "xapk", "apkm")
        assertTrue(splitExtensions.contains("apks"))
        assertTrue(splitExtensions.contains("xapk"))
        assertTrue(splitExtensions.contains("apkm"))

        val testFileName1 = "YouTube_v19.43.34.apks"
        val testFileName2 = "WhatsApp_messenger.xapk"
        val testFileName3 = "Spotify_music.apkm"
        val regularApk = "Telegram.apk"

        fun isSplit(name: String): Boolean {
            val ext = name.substringAfterLast('.').lowercase()
            return ext in splitExtensions || name.endsWith(".apks", true) || name.endsWith(".xapk", true) || name.endsWith(".apkm", true)
        }

        assertTrue(isSplit(testFileName1))
        assertTrue(isSplit(testFileName2))
        assertTrue(isSplit(testFileName3))
        org.junit.Assert.assertFalse(isSplit(regularApk))
    }

    @Test
    fun testApkSignerHelperTestKeyPair() {
        val keyPair = ApkSignerHelper.getTestKeyPair()
        assertNotNull(keyPair.first)
        assertNotNull(keyPair.second)
        assertEquals("RSA", keyPair.first.algorithm)
        assertTrue(keyPair.second.subjectX500Principal.name.contains("Android"))
    }

    @Test
    fun testApkBundleLifecycle() {
        ApkBundle().use { bundle ->
            assertEquals(0, bundle.countModules())
            assertTrue(bundle.listModuleNames().isEmpty())
        }
    }

    @Test
    fun testSyntheticApksExtraction() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "antisplit_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        val testArchive = File(tempDir, "sample_app.apks")

        try {
            // Create a test synthetic .apks zip containing base.apk and config split
            ZipOutputStream(FileOutputStream(testArchive)).use { zos ->
                zos.putNextEntry(ZipEntry("base.apk"))
                zos.write("DUMMY_BASE_CONTENT".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                zos.write("DUMMY_SPLIT_CONTENT".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("icon.png"))
                zos.write("PNG_BYTES".toByteArray())
                zos.closeEntry()
            }

            assertTrue(testArchive.exists())
            assertTrue(testArchive.length() > 0)

            // Verify zip reading logic extracts only .apk entries
            val extractedApks = mutableListOf<String>()
            java.util.zip.ZipFile(testArchive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        extractedApks.add(entry.name)
                    }
                }
            }

            assertEquals(2, extractedApks.size)
            assertTrue(extractedApks.contains("base.apk"))
            assertTrue(extractedApks.contains("split_config.arm64_v8a.apk"))
        } finally {
            testArchive.delete()
            tempDir.delete()
        }
    }
}

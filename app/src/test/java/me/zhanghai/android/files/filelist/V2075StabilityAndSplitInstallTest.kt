/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class V2075StabilityAndSplitInstallTest {

    private fun resolveMainJavaDir(): File {
        return File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java").takeIf { it.isDirectory }
            ?: error("Could not locate src/main/java from ${File(".").absolutePath}")
    }

    private fun resolveAppBuildGradle(): File {
        return File("build.gradle").takeIf { it.isFile }
            ?: File("app/build.gradle").takeIf { it.isFile }
            ?: error("Could not locate app/build.gradle from ${File(".").absolutePath}")
    }

    @Test
    fun testIsSplitApkBundleNameDetection() {
        assertTrue(ApkInstallHelper.isSplitApkBundleName("Quetta_v2.0.8.apks"))
        assertTrue(ApkInstallHelper.isSplitApkBundleName("game_bundle.XAPK"))
        assertTrue(ApkInstallHelper.isSplitApkBundleName("app_release.ApKm"))
        assertFalse(ApkInstallHelper.isSplitApkBundleName("OpenManager.apk"))
        assertFalse(ApkInstallHelper.isSplitApkBundleName("archive.zip"))
        assertFalse(ApkInstallHelper.isSplitApkBundleName("noextension"))
    }

    @Test
    fun testCollectSplitApkEntriesOrderingAndFiltering() {
        val tempFile = File.createTempFile("v2075_bundle_", ".xapk")
        try {
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                fun addEntry(name: String, data: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    if (data.isNotEmpty()) zos.write(data)
                    zos.closeEntry()
                }
                val dummyPayload = "PK_DUMMY_APK".toByteArray(StandardCharsets.UTF_8)
                addEntry("apks/config.arm64_v8a.apk", dummyPayload)
                addEntry("apks/config.xhdpi.apk", dummyPayload)
                addEntry("apks/base.apk", dummyPayload)
                addEntry("manifest.json", "{}".toByteArray(StandardCharsets.UTF_8))
                addEntry("icon.png", dummyPayload)
                addEntry("Android/obb/com.test/main.1.com.test.obb", dummyPayload)
                addEntry("apks/empty.apk", ByteArray(0))
                addEntry("__MACOSX/._base.apk", dummyPayload)
            }

            val collected = ZipFile(tempFile).use { zip ->
                ApkInstallHelper.collectSplitApkEntries(zip)
            }

            assertEquals(3, collected.size)
            assertEquals("apks/base.apk", collected[0].zipEntryName)
            assertEquals("0_base.apk", collected[0].sessionEntryName)
            assertEquals("base.apk", collected[0].displayName)

            assertEquals("apks/config.arm64_v8a.apk", collected[1].zipEntryName)
            assertEquals("1_config.arm64_v8a.apk", collected[1].sessionEntryName)

            assertEquals("apks/config.xhdpi.apk", collected[2].zipEntryName)
            assertEquals("2_config.xhdpi.apk", collected[2].sessionEntryName)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testIsSplitApkArchiveFileDistinguishesSingleApkFromBundle() {
        val singleApk = File.createTempFile("v2075_single_", ".apk")
        val splitApks = File.createTempFile("v2075_splits_", ".apks")
        try {
            ZipOutputStream(FileOutputStream(singleApk)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write("dex".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("assets/embedded.apk"))
                zos.write("nested".toByteArray())
                zos.closeEntry()
            }

            ZipOutputStream(FileOutputStream(splitApks)).use { zos ->
                zos.putNextEntry(ZipEntry("base.apk"))
                zos.write("base".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                zos.write("arm64".toByteArray())
                zos.closeEntry()
            }

            assertFalse(
                "Standard APK with root AndroidManifest.xml and classes.dex must not be treated as split bundle",
                ApkInstallHelper.isSplitApkArchiveFile(singleApk)
            )
            assertTrue(
                "Split archive with base.apk + split APKs must be detected as split bundle",
                ApkInstallHelper.isSplitApkArchiveFile(splitApks)
            )
        } finally {
            singleApk.delete()
            splitApks.delete()
        }
    }

    @Test
    fun testBuildMultiApkPmSessionScriptFlagsAndCleanup() {
        val staged = listOf(
            "0_base.apk" to 123456L,
            "1_split_config arm64.apk" to 65432L
        )

        val api35Script = ApkInstallHelper.buildMultiApkPmSessionScript(
            stagingDir = "/data/local/tmp/om_split_root_1",
            stagedFiles = staged,
            sdkInt = 35
        )
        assertTrue(api35Script.contains("pm install-create -r -d -t -g --bypass-low-target-sdk-block"))
        assertTrue(api35Script.contains("pm install-write -S 123456 \"\$SESSION_ID\" '0_base.apk' '/data/local/tmp/om_split_root_1/0_base.apk'"))
        assertTrue(api35Script.contains("pm install-write -S 65432 \"\$SESSION_ID\" '1_split_config arm64.apk' '/data/local/tmp/om_split_root_1/1_split_config arm64.apk'"))
        assertTrue(api35Script.contains("pm install-abandon \"\$SESSION_ID\""))
        assertTrue(api35Script.contains("pm install-commit \"\$SESSION_ID\""))
        assertTrue(api35Script.contains("rm -rf '/data/local/tmp/om_split_root_1'"))

        val api30Script = ApkInstallHelper.buildMultiApkPmSessionScript(
            stagingDir = "/data/local/tmp/om_split_root_2",
            stagedFiles = staged,
            sdkInt = 30
        )
        assertTrue(api30Script.contains("pm install-create -r -d -t -g"))
        assertFalse(api30Script.contains("--bypass-low-target-sdk-block"))
    }

    @Test
    fun testSplitApkFailureDiagnostics() {
        val conflictMsg = SplitApkInstallReceiver.formatPackageInstallerFailure(
            status = 5, // STATUS_FAILURE_CONFLICT
            rawMessage = null,
            isSpanish = true
        )
        assertTrue(conflictMsg.contains("Conflicto", ignoreCase = true))

        val incompatMsg = SplitApkInstallReceiver.formatPackageInstallerFailure(
            status = 7, // STATUS_FAILURE_INCOMPATIBLE
            rawMessage = null,
            isSpanish = false
        )
        assertTrue(incompatMsg.contains("incompatible", ignoreCase = true))

        val storageMsg = SplitApkInstallReceiver.formatPackageInstallerFailure(
            status = 6, // STATUS_FAILURE_STORAGE
            rawMessage = null,
            isSpanish = true
        )
        assertTrue(storageMsg.contains("almacenamiento", ignoreCase = true))

        val abiMsg = SplitApkInstallReceiver.formatPackageInstallerFailure(
            status = 7,
            rawMessage = "INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries",
            isSpanish = true
        )
        assertTrue(abiMsg.contains("arquitectura CPU", ignoreCase = true))
        assertTrue(abiMsg.contains("INSTALL_FAILED_NO_MATCHING_ABIS"))
    }

    @Test
    fun testCodebaseRegressionGuardsAndV2075VersionBump() {
        val javaRoot = resolveMainJavaDir()
        val ktFiles = javaRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected Kotlin source files in $javaRoot", ktFiles.size > 100)

        for (ktFile in ktFiles) {
            val content = ktFile.readText()
            assertFalse(
                "Unregistered androidx.core.content.FileProvider.getUriForFile found in ${ktFile.path}",
                content.contains("androidx.core.content.FileProvider.getUriForFile")
            )
            assertFalse(
                "Deprecated android.app.ProgressDialog import found in ${ktFile.path}",
                content.contains("import android.app.ProgressDialog")
            )
        }

        val buildGradle = resolveAppBuildGradle().readText()
        assertTrue("app/build.gradle must default to versionCode 122", buildGradle.contains(": 122"))
        assertTrue("app/build.gradle must default to versionName '2.0.8'", buildGradle.contains(": '2.0.8'"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.8", "2.0.7"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.8", "1.9.73"))
    }
}

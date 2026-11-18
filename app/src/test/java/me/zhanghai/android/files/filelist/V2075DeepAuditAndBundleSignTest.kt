/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import org.junit.Assert.assertArrayEquals
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

class V2075DeepAuditAndBundleSignTest {

    private fun resolveAppRoot(): File {
        return File(".").takeIf { File(it, "src/main").isDirectory }
            ?: File("app").takeIf { File(it, "src/main").isDirectory }
            ?: error("Could not locate app directory from ${File(".").absolutePath}")
    }

    @Test
    fun testComputeDefaultSignedOutputName() {
        assertEquals("App_signed.apk", ApkSignerHelper.computeDefaultSignedOutputName("App.apk"))
        assertEquals("Bundle_signed.apks", ApkSignerHelper.computeDefaultSignedOutputName("Bundle.apks"))
        assertEquals("Game_signed.xapk", ApkSignerHelper.computeDefaultSignedOutputName("Game.XAPK"))
        assertEquals("Suite_signed.apkm", ApkSignerHelper.computeDefaultSignedOutputName("Suite.apkm"))
        assertEquals("noext_signed.apk", ApkSignerHelper.computeDefaultSignedOutputName("noext"))
    }

    @Test
    fun testSignSplitBundleDirectSignsAllSplitsAndPreservesMetadata() {
        val tempDir = File(
            System.getProperty("java.io.tmpdir"),
            "om_sign_bundle_test_${System.currentTimeMillis()}"
        ).apply { mkdirs() }

        try {
            val baseApk = File(tempDir, "base.apk")
            val abiApk = File(tempDir, "split_config.arm64_v8a.apk")
            val inputBundle = File(tempDir, "Quetta_bundle.apks")
            val outputBundle = File(tempDir, "Quetta_bundle_signed.apks")

            ApkModule().use { baseMod ->
                val manifest = AndroidManifestBlock.empty()
                manifest.packageName = "com.example.splitapp"
                manifest.setVersionCode(10)
                manifest.setVersionName("1.0")
                manifest.setMinSdkVersion(23)
                manifest.setTargetSdkVersion(35)
                manifest.setApplicationLabel("SplitApp")
                baseMod.setManifest(manifest)
                val emptyTable = TableBlock().apply {
                    newPackage(0x7f, "com.example.splitapp")
                    refresh()
                }
                baseMod.setTableBlock(emptyTable)
                baseMod.add(ByteInputSource("dex\n035\u0000".toByteArray(), "classes.dex"))
                baseMod.writeApk(baseApk)
            }

            ApkModule().use { splitMod ->
                val splitManifest = AndroidManifestBlock.empty()
                splitManifest.packageName = "com.example.splitapp"
                splitManifest.setMinSdkVersion(23)
                splitManifest.setSplit("config.arm64_v8a", false)
                splitMod.setManifest(splitManifest)
                splitMod.add(ByteInputSource(byteArrayOf(1, 2, 3, 4), "res/raw/dummy.bin"))
                splitMod.writeApk(abiApk)
            }

            val manifestJsonBytes = """{"xapk_version":2,"package_name":"com.example.splitapp"}"""
                .toByteArray(StandardCharsets.UTF_8)

            ZipOutputStream(FileOutputStream(inputBundle)).use { zos ->
                fun putFile(entryName: String, src: File) {
                    zos.putNextEntry(ZipEntry(entryName))
                    src.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                putFile("base.apk", baseApk)
                putFile("split_config.arm64_v8a.apk", abiApk)
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestJsonBytes)
                zos.closeEntry()
            }

            val signedCount = ApkSignerHelper.signSplitBundleDirect(inputBundle, outputBundle)
            assertEquals(2, signedCount)
            assertTrue("Signed bundle must exist", outputBundle.exists() && outputBundle.length() > 0L)

            ZipFile(outputBundle).use { zip ->
                val metaEntry = zip.getEntry("manifest.json")
                val actualMetaBytes = zip.getInputStream(metaEntry).use { it.readBytes() }
                assertArrayEquals("Non-APK metadata must be preserved verbatim", manifestJsonBytes, actualMetaBytes)

                val certEncodings = mutableListOf<ByteArray>()
                for (splitEntryName in listOf("base.apk", "split_config.arm64_v8a.apk")) {
                    val splitOutFile = File(tempDir, "verified_$splitEntryName")
                    val entry = zip.getEntry(splitEntryName)
                    zip.getInputStream(entry).use { ins ->
                        FileOutputStream(splitOutFile).use { outs -> ins.copyTo(outs) }
                    }
                    val verifyResult = ApkVerifier.Builder(splitOutFile).build().verify()
                    assertTrue("$splitEntryName must verify cleanly", verifyResult.isVerified)
                    assertTrue("$splitEntryName must have V1 signature", verifyResult.isVerifiedUsingV1Scheme)
                    assertTrue("$splitEntryName must have V2 signature", verifyResult.isVerifiedUsingV2Scheme)
                    assertTrue("$splitEntryName must have V3 signature", verifyResult.isVerifiedUsingV3Scheme)
                    assertTrue("$splitEntryName must include signer cert", verifyResult.signerCertificates.isNotEmpty())
                    certEncodings.add(verifyResult.signerCertificates[0].encoded)
                }

                assertArrayEquals(
                    "All signed splits in bundle must share identical certificate",
                    certEncodings[0],
                    certEncodings[1]
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPaletteNeutralDetectionAndTextRemapping() {
        assertTrue(OpenManagerPalette.isNeutralColor(0xFFFFFFFF.toInt()))
        assertTrue(OpenManagerPalette.isNeutralColor(0xFFE0E0E0.toInt()))
        assertTrue(OpenManagerPalette.isNeutralColor(0xFF303030.toInt()))
        assertTrue(OpenManagerPalette.isNeutralColor(0xFF9E9E9E.toInt()))
        assertFalse(OpenManagerPalette.isNeutralColor(0x00FFFFFF)) // Transparent
        assertFalse(OpenManagerPalette.isNeutralColor(0xFF4CAF50.toInt())) // Semantic green
        assertFalse(OpenManagerPalette.isNeutralColor(0xFF2196F3.toInt())) // Semantic blue
        assertFalse(OpenManagerPalette.isNeutralColor(0xFFFFB300.toInt())) // Semantic amber
        assertFalse(OpenManagerPalette.isNeutralColor(0xFFEF5350.toInt())) // Semantic red

        val accent = 0xFF2196F3.toInt()
        val defaultDark = OpenManagerPalette.compute(isDark = true, isAmoled = false, accentColor = accent)
        val light = OpenManagerPalette.compute(isDark = false, isAmoled = false, accentColor = accent)
        val amoled = OpenManagerPalette.compute(isDark = true, isAmoled = true, accentColor = accent)

        // Default Dark leaves all colors untouched
        assertEquals(0xFFFFFFFF.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFFFFFFFF.toInt(), defaultDark))
        assertEquals(0xFF9E9E9E.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFF9E9E9E.toInt(), defaultDark))
        assertEquals(0xFF4CAF50.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFF4CAF50.toInt(), defaultDark))

        // Light mode remaps bright neutral whites to textPrimary and medium grays to textSecondary
        assertEquals(light.textPrimary, OpenManagerPalette.remapNeutralTextColor(0xFFFFFFFF.toInt(), light))
        assertEquals(light.textPrimary, OpenManagerPalette.remapNeutralTextColor(0xFFE0E0E0.toInt(), light))
        assertEquals(light.textSecondary, OpenManagerPalette.remapNeutralTextColor(0xFF9E9E9E.toInt(), light))
        assertEquals(light.textSecondary, OpenManagerPalette.remapNeutralTextColor(0xFF888888.toInt(), light))
        // Semantic colors untouched in Light and AMOLED modes
        assertEquals(0xFF4CAF50.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFF4CAF50.toInt(), light))
        assertEquals(0xFF2196F3.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFF2196F3.toInt(), light))
        assertEquals(0xFFFFB300.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFFFFB300.toInt(), light))
        assertEquals(0xFFEF5350.toInt(), OpenManagerPalette.remapNeutralTextColor(0xFFEF5350.toInt(), amoled))
        assertEquals(amoled.textPrimary, OpenManagerPalette.remapNeutralTextColor(0xFFFFFFFF.toInt(), amoled))
    }

    @Test
    fun testCodebaseDeepAuditRegressionGuards() {
        val appRoot = resolveAppRoot()
        val javaPkg = File(appRoot, "src/main/java/me/zhanghai/android/files")

        val noRawThreadFiles = listOf(
            File(javaPkg, "filelist/ApkSignerHelper.kt"),
            File(javaPkg, "filelist/FileEncryptDecryptHelper.kt"),
            File(javaPkg, "filelist/FileListActivity.kt")
        )
        for (f in noRawThreadFiles) {
            assertTrue("Missing file ${f.path}", f.isFile)
            assertFalse(
                "kotlin.concurrent.thread must not appear in ${f.name}",
                f.readText().contains("kotlin.concurrent.thread")
            )
        }

        val createSymlink = File(javaPkg, "filelist/CreateSymlinkHelper.kt").readText()
        assertFalse(
            "CreateSymlinkHelper must not invoke Runtime.getRuntime().exec directly on UI flow",
            createSymlink.contains("Runtime.getRuntime().exec")
        )

        val exportList = File(javaPkg, "filelist/ExportFileListHelper.kt").readText()
        assertFalse(
            "ExportFileListHelper must use NIO Path APIs rather than .toFile()",
            exportList.contains(".toFile()")
        )

        val actionItemLayout = File(appRoot, "src/main/res/layout/item_dialog_action_with_icon.xml").readText()
        assertFalse(
            "item_dialog_action_with_icon.xml must not hardcode white textColor",
            actionItemLayout.contains("android:textColor=\"#FFFFFF\"")
        )
    }
}

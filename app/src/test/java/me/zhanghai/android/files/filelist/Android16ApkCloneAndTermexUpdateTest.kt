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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.zhanghai.android.files.terminal.TerminalExpansionManager
import me.zhanghai.android.files.update.UpdateChecker
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Android16ApkCloneAndTermexUpdateTest {

    private fun findZipEntryDataOffsetAndMethod(apk: File, entryName: String): Pair<Long, Int>? {
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            val tailSize = minOf(len, 65557L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize)
            raf.readFully(tail)

            var eocdOff = -1
            for (i in tailSize - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() &&
                    tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()
                ) {
                    eocdOff = i
                    break
                }
            }
            if (eocdOff < 0) return null

            val eocdBuf = ByteBuffer.wrap(tail, eocdOff, tailSize - eocdOff).order(ByteOrder.LITTLE_ENDIAN)
            val cdSize = eocdBuf.getInt(12).toLong() and 0xFFFFFFFFL
            val cdOffset = eocdBuf.getInt(16).toLong() and 0xFFFFFFFFL
            val cdBytes = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cdBytes)

            var pos = 0
            while (pos + 46 <= cdBytes.size) {
                val cdBuf = ByteBuffer.wrap(cdBytes, pos, cdBytes.size - pos).order(ByteOrder.LITTLE_ENDIAN)
                if (cdBuf.getInt(0) != 0x02014B50) break
                val nameLen = cdBuf.getShort(28).toInt() and 0xFFFF
                val extraLen = cdBuf.getShort(30).toInt() and 0xFFFF
                val commentLen = cdBuf.getShort(32).toInt() and 0xFFFF
                val name = String(cdBytes, pos + 46, nameLen, StandardCharsets.UTF_8)
                val lfhOffset = cdBuf.getInt(42).toLong() and 0xFFFFFFFFL
                if (name == entryName) {
                    val lfh = ByteArray(30)
                    raf.seek(lfhOffset)
                    raf.readFully(lfh)
                    val lfhBuf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN)
                    val method = lfhBuf.getShort(8).toInt() and 0xFFFF
                    val lfhNameLen = lfhBuf.getShort(26).toInt() and 0xFFFF
                    val lfhExtraLen = lfhBuf.getShort(28).toInt() and 0xFFFF
                    val dataOffset = lfhOffset + 30L + lfhNameLen + lfhExtraLen
                    return dataOffset to method
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
        }
        return null
    }

    @Test
    fun clonedSplitMergedApkRewritesAuthoritiesPermissionsExpandsClassesAndAligns16Kb() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "om_clone_test_${System.currentTimeMillis()}")
        val splitDir = File(tempDir, "splits").apply { mkdirs() }
        val baseApk = File(splitDir, "base.apk")
        val abiSplitApk = File(splitDir, "split_config.arm64_v8a.apk")
        val mergedApk = File(tempDir, "Quetta_merged.apk")
        val clonedApk = File(tempDir, "Quetta_cloned.apk")

        try {
            // Build synthetic base.apk mimicking a split app with conflicting providers, permissions, relative classes & stamp
            ApkModule().use { baseMod ->
                val manifest = AndroidManifestBlock.empty()
                manifest.packageName = "com.original.quetta"
                manifest.setVersionCode(208)
                manifest.setVersionName("2.0.8")
                manifest.setMinSdkVersion(26)
                manifest.setTargetSdkVersion(35)
                manifest.setApplicationLabel("Quetta")
                manifest.setExtractNativeLibs(false)

                val root = manifest.manifestElement
                root.getOrCreateAndroidAttribute("sharedUserId", ApkQuickEditEngine.ATTR_SHARED_USER_ID)
                    .setValueAsString("com.original.shared")
                root.newElement("permission")
                    .getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                    .setValueAsString("com.original.quetta.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
                root.newElement("uses-permission")
                    .getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                    .setValueAsString("com.original.quetta.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

                val appEl = manifest.getOrCreateApplicationElement()
                appEl.getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                    .setValueAsString(".QuettaApp")
                appEl.newElement("activity")
                    .getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                    .setValueAsString(".MainActivity")
                appEl.newElement("activity-alias").apply {
                    getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                        .setValueAsString(".LauncherAlias")
                    getOrCreateAndroidAttribute("targetActivity", ApkQuickEditEngine.ATTR_TARGET_ACTIVITY)
                        .setValueAsString(".MainActivity")
                }
                appEl.newElement("provider").apply {
                    getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                        .setValueAsString(".StartupProvider")
                    getOrCreateAndroidAttribute("authorities", ApkQuickEditEngine.ATTR_AUTHORITIES)
                        .setValueAsString("com.original.quetta.androidx-startup;fixed.global.authority")
                }
                appEl.newElement("meta-data").apply {
                    getOrCreateAndroidAttribute("name", ApkQuickEditEngine.ATTR_NAME)
                        .setValueAsString("com.android.stamp.source")
                    getOrCreateAndroidAttribute("value", 0x01010024)
                        .setValueAsString("https://play.google.com/store")
                }

                baseMod.setManifest(manifest)
                val emptyTable = TableBlock().apply {
                    newPackage(0x7f, "com.original.quetta")
                    refresh()
                }
                baseMod.setTableBlock(emptyTable)
                baseMod.add(ByteInputSource("STAMP".toByteArray(), "stamp-cert-sha256"))
                baseMod.add(ByteInputSource("dex\n035\u0000".toByteArray(), "classes.dex"))
                baseMod.writeApk(baseApk)
            }

            // Build synthetic arm64 native split APK
            ApkModule().use { splitMod ->
                val splitManifest = AndroidManifestBlock.empty()
                splitManifest.packageName = "com.original.quetta"
                splitManifest.setSplit("config.arm64_v8a", false)
                splitMod.setManifest(splitManifest)
                val dummyElf = ByteArray(256).also {
                    it[0] = 0x7F
                    it[1] = 'E'.code.toByte()
                    it[2] = 'L'.code.toByte()
                    it[3] = 'F'.code.toByte()
                }
                splitMod.add(ByteInputSource(dummyElf, "lib/arm64-v8a/libengine.so"))
                splitMod.writeApk(abiSplitApk)
            }

            // 1. Merge split directory via AntiSplitEngine (unsigned first to verify writeAlignedUnsignedApk 16KB alignment)
            val mergeOk = AntiSplitEngine.mergeDirectory(
                tempDir = splitDir,
                outputFile = mergedApk,
                options = AntiSplitEngine.AntiSplitOptions(
                    autoSign = false,
                    extractNativeLibs = false
                )
            )
            assertTrue("AntiSplit merge must succeed", mergeOk)
            assertTrue("Merged APK must exist", mergedApk.exists())

            val mergedLibAlign = findZipEntryDataOffsetAndMethod(mergedApk, "lib/arm64-v8a/libengine.so")
            assertNotNull("lib/arm64-v8a/libengine.so must exist in merged APK", mergedLibAlign)
            assertEquals("libengine.so must be STORED when extractNativeLibs=false", 0, mergedLibAlign!!.second)
            assertEquals(
                "writeAlignedUnsignedApk must enforce 16-KB (16384-byte) alignment for .so files",
                0L,
                mergedLibAlign.first % 16384L
            )

            // 2. Clone package via ApkQuickEditEngine
            val cloned = ApkQuickEditEngine.applyQuickEdit(
                inputApk = mergedApk,
                outputApk = clonedApk,
                options = ApkQuickEditEngine.QuickEditOptions(
                    appName = "Quetta Clone",
                    packageName = "com.cloned.quetta",
                    extractNativeLibs = false,
                    autoSign = true
                )
            )
            assertTrue("Cloned APK must exist", cloned.exists() && cloned.length() > 0L)

            // 3. Verify V1+V2+V3 signature integrity
            val sigResult = ApkVerifier.Builder(cloned).build().verify()
            assertTrue("Cloned APK must pass ApkVerifier", sigResult.isVerified)
            assertTrue("Cloned APK must have V1 signature", sigResult.isVerifiedUsingV1Scheme)
            assertTrue("Cloned APK must have V2 signature", sigResult.isVerifiedUsingV2Scheme)
            assertTrue("Cloned APK must have V3 signature", sigResult.isVerifiedUsingV3Scheme)

            // 4. Verify resources.arsc and .so ZIP alignment in final signed APK and absence of stamp
            val clonedArsc = findZipEntryDataOffsetAndMethod(cloned, "resources.arsc")
            assertNotNull("resources.arsc must exist", clonedArsc)
            assertEquals("resources.arsc must be STORED", 0, clonedArsc!!.second)
            assertEquals("resources.arsc must be 4-byte aligned", 0L, clonedArsc.first % 4L)

            val clonedLib = findZipEntryDataOffsetAndMethod(cloned, "lib/arm64-v8a/libengine.so")
            assertNotNull("libengine.so must exist in cloned APK", clonedLib)
            assertEquals("libengine.so must stay STORED", 0, clonedLib!!.second)
            assertEquals("libengine.so must stay at least 4096-byte page aligned", 0L, clonedLib.first % 4096L)

            ZipFile(cloned).use { zf ->
                assertNull("stamp-cert-sha256 must be stripped", zf.getEntry("stamp-cert-sha256"))
            }

            // 5. Verify rewritten AndroidManifest attributes
            ApkModule.loadApkFile(cloned).use { mod ->
                val manifest = mod.androidManifest!!
                assertEquals("com.cloned.quetta", manifest.packageName)

                val root = manifest.manifestElement
                assertNull(
                    "sharedUserId must be stripped when re-signing/cloning",
                    root.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_SHARED_USER_ID)
                )

                val permEl = root.getElements("permission").next()
                assertEquals(
                    "com.cloned.quetta.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    permEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )

                val usesPermEl = root.getElements("uses-permission").next()
                assertEquals(
                    "com.cloned.quetta.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    usesPermEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )

                val appEl = manifest.applicationElement!!
                assertEquals(
                    "com.original.quetta.QuettaApp",
                    appEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )

                val activityEl = appEl.getElements("activity").next()
                assertEquals(
                    "com.original.quetta.MainActivity",
                    activityEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )

                val aliasEl = appEl.getElements("activity-alias").next()
                assertEquals(
                    "com.original.quetta.LauncherAlias",
                    aliasEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )
                assertEquals(
                    "com.original.quetta.MainActivity",
                    aliasEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_TARGET_ACTIVITY)?.valueAsString
                )

                val providerEl = appEl.getElements("provider").next()
                assertEquals(
                    "com.original.quetta.StartupProvider",
                    providerEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_NAME)?.valueAsString
                )
                assertEquals(
                    "com.cloned.quetta.androidx-startup;com.cloned.quetta.provider.fixed.global.authority",
                    providerEl.searchAttributeByResourceId(ApkQuickEditEngine.ATTR_AUTHORITIES)?.valueAsString
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun termexBootstrapBuilderCreatesValidSignedDexlessNativePackWithNormalizedSonames() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "om_termex_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val bootstrapZip = File(tempDir, "bootstrap-aarch64.zip")
        val outApk = File(tempDir, "OpenManager-TermEx.apk")

        val bashPayload = "\u007FELF\u0000libreadline.so.8\u0000libandroid-support.so\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val grepPayload = "\u007FELF\u0000libc.so\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val readlinePayload = "\u007FELF\u0000libreadline.so.8\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val supportPayload = "\u007FELF\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val bashrcPayload = "alias ll='ls -la'\n".toByteArray(StandardCharsets.UTF_8)

        try {
            ZipOutputStream(FileOutputStream(bootstrapZip)).use { zos ->
                zos.putNextEntry(ZipEntry("SYMLINKS.txt"))
                zos.write("libreadline.so.8.2\u2190lib/libreadline.so.8\n".toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("bin/bash"))
                zos.write(bashPayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("bin/grep"))
                zos.write(grepPayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("lib/libreadline.so.8.2"))
                zos.write(readlinePayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("lib/libandroid-support.so"))
                zos.write(supportPayload)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("etc/bash.bashrc"))
                zos.write(bashrcPayload)
                zos.closeEntry()
            }

            val built = TerminalExpansionManager.buildTermexApkFromBootstrap(
                bootstrapZip = bootstrapZip,
                outputApk = outApk,
                apkAbi = "arm64-v8a"
            )
            assertTrue("Bootstrap builder must succeed", built)
            assertTrue("Built TermEx APK must exist", outApk.exists() && outApk.length() > 0L)

            val verifyResult = ApkVerifier.Builder(outApk).build().verify()
            assertTrue("Built TermEx APK must be validly signed", verifyResult.isVerified)

            ApkModule.loadApkFile(outApk).use { mod ->
                val manifest = mod.androidManifest!!
                assertEquals(TerminalExpansionManager.OM_TERMEX_PACKAGE, manifest.packageName)
                assertEquals(true, manifest.isExtractNativeLibs)
                val hasCodeAttr = manifest.applicationElement?.searchAttributeByResourceId(0x0101000c)
                assertNotNull("android:hasCode must be present on dexless extension APK", hasCodeAttr)
                assertFalse("android:hasCode must be false on dexless extension APK", hasCodeAttr!!.valueAsBoolean)
            }

            ZipFile(outApk).use { zf ->
                val bashEntry = zf.getEntry("lib/arm64-v8a/libbash.so")
                val grepEntry = zf.getEntry("lib/arm64-v8a/libom_cmd_grep.so")
                val readlineEntry = zf.getEntry("lib/arm64-v8a/libreadline_8.so")
                val supportEntry = zf.getEntry("lib/arm64-v8a/libandroid-support.so")
                val bashrcEntry = zf.getEntry("assets/etc/bash.bashrc")

                assertNotNull("libbash.so must be packaged", bashEntry)
                assertNotNull("libom_cmd_grep.so must be packaged", grepEntry)
                assertNotNull("libreadline_8.so (via SYMLINKS.txt) must be packaged", readlineEntry)
                assertNotNull("libandroid-support.so must be packaged", supportEntry)
                assertNotNull("assets/etc/bash.bashrc must be packaged", bashrcEntry)

                val patchedBash = zf.getInputStream(bashEntry).use { it.readBytes() }
                assertEquals(
                    "In-place SONAME patching must preserve exact ELF byte length",
                    bashPayload.size,
                    patchedBash.size
                )
                val patchedBashIso = String(patchedBash, StandardCharsets.ISO_8859_1)
                assertTrue(
                    "libbash.so must reference libreadline_8.so\\0",
                    patchedBashIso.contains("libreadline_8.so\u0000")
                )
                assertFalse(
                    "libbash.so must no longer reference versioned libreadline.so.8\\0",
                    patchedBashIso.contains("libreadline.so.8\u0000")
                )

                val patchedReadline = String(zf.getInputStream(readlineEntry).use { it.readBytes() }, StandardCharsets.ISO_8859_1)
                assertTrue(
                    "Normalized shared library must also patch its internal DT_SONAME",
                    patchedReadline.contains("libreadline_8.so\u0000")
                )
                assertArrayEquals(bashrcPayload, zf.getInputStream(bashrcEntry).use { it.readBytes() })
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun updateCleanupDeletesOnlyInstalledOrOlderOpenManagerApksWithoutTouchingNewerOrActiveFiles() {
        val updatesDir = "/storage/emulated/0/OpenManager/updates"

        // Installed same version (>= 15s old) -> safe to delete
        assertTrue(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.74-release.apk",
                filePath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                currentVersion = "1.9.74",
                pendingPath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                pendingVersion = "1.9.74",
                fileAgeMs = 30_000L,
                isDownloading = false
            )
        )

        // Older leftover OpenManager APK -> safe to delete
        assertTrue(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.72-debug.apk",
                filePath = "$updatesDir/OpenManager-v1.9.72-debug.apk",
                currentVersion = "1.9.74",
                pendingPath = null,
                pendingVersion = null,
                fileAgeMs = 60_000L,
                isDownloading = false
            )
        )

        // Newer pending update APK not yet installed -> MUST NOT delete
        assertFalse(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.75-release.apk",
                filePath = "$updatesDir/OpenManager-v1.9.75-release.apk",
                currentVersion = "1.9.74",
                pendingPath = "$updatesDir/OpenManager-v1.9.75-release.apk",
                pendingVersion = "1.9.75",
                fileAgeMs = 120_000L,
                isDownloading = false
            )
        )

        // Active download in progress -> MUST NOT delete
        assertFalse(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.74-release.apk",
                filePath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                currentVersion = "1.9.74",
                pendingPath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                pendingVersion = "1.9.74",
                fileAgeMs = 120_000L,
                isDownloading = true
            )
        )

        // Freshly written APK (< 15s) possibly still staged by PackageInstaller -> MUST NOT delete
        assertFalse(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.74-release.apk",
                filePath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                currentVersion = "1.9.74",
                pendingPath = "$updatesDir/OpenManager-v1.9.74-release.apk",
                pendingVersion = "1.9.74",
                fileAgeMs = 4_000L,
                isDownloading = false
            )
        )

        // Unrelated third-party APK placed in folder -> MUST NOT delete
        assertFalse(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "Quetta_v2.0.8.apk",
                filePath = "$updatesDir/Quetta_v2.0.8.apk",
                currentVersion = "1.9.74",
                pendingPath = null,
                pendingVersion = null,
                fileAgeMs = 86_400_000L,
                isDownloading = false
            )
        )

        // Stale >1h .apk.tmp -> delete; recent .apk.tmp -> keep
        assertTrue(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.74-release.apk.tmp",
                filePath = "$updatesDir/OpenManager-v1.9.74-release.apk.tmp",
                currentVersion = "1.9.74",
                pendingPath = null,
                pendingVersion = null,
                fileAgeMs = 4_000_000L,
                isDownloading = false
            )
        )
        assertFalse(
            UpdateChecker.shouldDeleteUpdateCandidate(
                fileName = "OpenManager-v1.9.75-release.apk.tmp",
                filePath = "$updatesDir/OpenManager-v1.9.75-release.apk.tmp",
                currentVersion = "1.9.74",
                pendingPath = null,
                pendingVersion = null,
                fileAgeMs = 60_000L,
                isDownloading = false
            )
        )
    }
}

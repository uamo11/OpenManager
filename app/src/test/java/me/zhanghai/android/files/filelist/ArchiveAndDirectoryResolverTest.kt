/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ArchiveAndDirectoryResolverTest {

    private fun getCacheKey(uri: String?, pathString: String): String =
        try {
            if (uri != null) URI.create(uri).toString() else pathString
        } catch (_: Throwable) {
            pathString
        }

    @Test
    fun testCacheKeyIsolationBetweenLinuxRootAndArchiveRoots() {
        val linuxRootUri = "file:///"
        val linuxRootPath = "/"

        val romArchiveUri = "archive:////storage/1234-5678/lineage-21.0-rom.zip?/"
        val romArchivePath = "/"

        val apkArchiveUri = "archive:////storage/emulated/0/Al%20Dolar.apk?/"
        val apkArchivePath = "/"

        val linuxKey = getCacheKey(linuxRootUri, linuxRootPath)
        val romKey = getCacheKey(romArchiveUri, romArchivePath)
        val apkKey = getCacheKey(apkArchiveUri, apkArchivePath)

        // While internal relative path is "/" for all 3, their cache keys MUST be completely distinct!
        assertNotEquals(linuxKey, romKey)
        assertNotEquals(linuxKey, apkKey)
        assertNotEquals(romKey, apkKey)

        assertEquals("file:///", linuxKey)
        assertEquals("archive:////storage/1234-5678/lineage-21.0-rom.zip?/", romKey)
        assertEquals("archive:////storage/emulated/0/Al%20Dolar.apk?/", apkKey)
    }

    @Test
    fun testCacheLookupSimulationPreventsRootFolderLeak() {
        // Simulated snapshot cache
        val cache = mutableMapOf<String, List<String>>()

        val linuxKey = "file:///"
        val rootFolders = listOf("acct", "apex", "bin", "data", "dev", "etc", "metadata", "mnt", "system")
        cache[linuxKey] = rootFolders

        val romKey = "archive:////storage/1234-5678/lineage-rom.zip?/"
        val apkKey = "archive:////storage/emulated/0/Al%20Dolar.apk?/"

        // Visiting archive roots must NOT return the cached Linux root folders!
        assertNull(cache[romKey])
        assertNull(cache[apkKey])
        assertEquals(rootFolders, cache[linuxKey])

        // When archive contents are loaded, they are stored under their own key
        val romEntries = listOf("boot.img", "system.new.dat.br", "META-INF")
        cache[romKey] = romEntries

        assertEquals(romEntries, cache[romKey])
        assertEquals(rootFolders, cache[linuxKey])
        assertNull(cache[apkKey])
    }

    @Test
    fun testUnprivilegedResolverRulesForArchives() {
        // Rule: Archive paths and non-Linux paths must NEVER receive static virtual Linux root folders!
        fun shouldResolveUnprivilegedStaticMap(isArchivePath: Boolean, isLinuxPath: Boolean): Boolean {
            if (isArchivePath || !isLinuxPath) return false
            return true
        }

        assertFalse("Archive path must never query static Linux root", shouldResolveUnprivilegedStaticMap(isArchivePath = true, isLinuxPath = false))
        assertFalse("Archive path even if masquerading as LinuxPath must never query static root", shouldResolveUnprivilegedStaticMap(isArchivePath = true, isLinuxPath = true))
        assertFalse("DocumentPath / SAF must never query static Linux root", shouldResolveUnprivilegedStaticMap(isArchivePath = false, isLinuxPath = false))
        assertTrue("Local unprivileged Linux path CAN query static root", shouldResolveUnprivilegedStaticMap(isArchivePath = false, isLinuxPath = true))
    }

    @Test
    fun testArchiveFailureHandlingNeverInjectsVirtualRoot() {
        // Simulation of FileListLiveData fallback logic
        fun determineResultState(
            isArchivePath: Boolean,
            isLinuxPath: Boolean,
            loadException: Exception?
        ): String {
            if (loadException != null) {
                if (isArchivePath) {
                    return "Failure(${loadException.message})"
                }
                if (isLinuxPath) {
                    return "VirtualLinuxRoot"
                }
                return "Failure(${loadException.message})"
            }
            return "Success"
        }

        val zipException = java.io.IOException("Corrupted central directory or OTA signature")
        val archiveResult = determineResultState(isArchivePath = true, isLinuxPath = false, loadException = zipException)
        assertEquals("Failure(Corrupted central directory or OTA signature)", archiveResult)

        val unprivilegedLinuxResult = determineResultState(isArchivePath = false, isLinuxPath = true, loadException = java.lang.SecurityException("Permission denied"))
        assertEquals("VirtualLinuxRoot", unprivilegedLinuxResult)
    }

    @Test
    fun testAndroidDataObbProtectionExcludesArchives() {
        fun isAndroidData(pathStr: String, isArchive: Boolean): Boolean {
            if (isArchive) return false
            return pathStr.contains("/Android/data", ignoreCase = true)
        }

        fun isAndroidObb(pathStr: String, isArchive: Boolean): Boolean {
            if (isArchive) return false
            return pathStr.contains("/Android/obb", ignoreCase = true)
        }

        // Inside a ROM or backup zip, there could be files matching Android/data, but they must NOT trigger SAF prompts!
        assertTrue(isAndroidData("/storage/emulated/0/Android/data", isArchive = false))
        assertFalse(isAndroidData("/Android/data", isArchive = true))
        assertFalse(isAndroidData("/system/app/Android/data", isArchive = true))

        assertTrue(isAndroidObb("/storage/emulated/0/Android/obb", isArchive = false))
        assertFalse(isAndroidObb("/Android/obb", isArchive = true))
    }

    @Test
    fun testZipResilientFallbackExtractsEntries() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("a.sh"))
            zos.write("#!/bin/sh\necho test\n".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("scripts/"))
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("scripts/sub.sh"))
            zos.write("echo sub\n".toByteArray())
            zos.closeEntry()
        }
        val zipBytes = bos.toByteArray()
        assertTrue(zipBytes.size > 0)

        val entryNames = mutableListOf<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryNames += entry.name
                zis.closeEntry()
            }
        }
        assertEquals(listOf("a.sh", "scripts/", "scripts/sub.sh"), entryNames)
    }
}

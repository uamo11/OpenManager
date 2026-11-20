/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import me.zhanghai.android.files.file.FallbackBasicFileAttributes
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.DirectorySnapshotCache
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.viewer.text.EditorTheme
import me.zhanghai.android.files.viewer.text.FastSyntaxScanner
import me.zhanghai.android.files.viewer.text.SyntaxLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.text.Collator

class PerformanceModeManagerTest {

    private class SimpleTestPath(private val fullPath: String) : Path {
        override fun toString(): String = fullPath
        override fun equals(other: Any?): Boolean = other is SimpleTestPath && other.fullPath == fullPath
        override fun hashCode(): Int = fullPath.hashCode()
        override fun compareTo(other: Path): Int = fullPath.compareTo(other.toString())
        override fun toUri(): URI = URI.create("file://$fullPath")
        override fun getFileName(): Path = SimpleTestPath(fullPath.substringAfterLast('/'))
        override fun getFileSystem(): FileSystem = throw UnsupportedOperationException()
        override fun isAbsolute(): Boolean = fullPath.startsWith("/")
        override fun getRoot(): Path = SimpleTestPath("/")
        override fun getParent(): Path? = null
        override fun getNameCount(): Int = 1
        override fun getName(index: Int): Path = this
        override fun subpath(beginIndex: Int, endIndex: Int): Path = this
        override fun startsWith(other: Path): Boolean = fullPath.startsWith(other.toString())
        override fun startsWith(other: String): Boolean = fullPath.startsWith(other)
        override fun endsWith(other: Path): Boolean = fullPath.endsWith(other.toString())
        override fun endsWith(other: String): Boolean = fullPath.endsWith(other)
        override fun normalize(): Path = this
        override fun resolve(other: Path): Path = SimpleTestPath("$fullPath/$other")
        override fun resolve(other: String): Path = SimpleTestPath("$fullPath/$other")
        override fun resolveSibling(other: Path): Path = throw UnsupportedOperationException()
        override fun resolveSibling(other: String): Path = throw UnsupportedOperationException()
        override fun relativize(other: Path): Path = throw UnsupportedOperationException()
        override fun toAbsolutePath(): Path = this
        override fun toRealPath(vararg options: LinkOption): Path = this
        override fun toFile(): File = File(fullPath)
        override fun register(
            watcher: WatchService,
            events: Array<WatchEvent.Kind<*>>,
            vararg modifiers: WatchEvent.Modifier
        ): WatchKey = throw UnsupportedOperationException()
        override fun register(watcher: WatchService, vararg events: WatchEvent.Kind<*>): WatchKey =
            throw UnsupportedOperationException()
        override fun iterator(): MutableIterator<Path> = throw UnsupportedOperationException()
    }

    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    @After
    fun tearDown() {
        PerformanceModeManager.setEnabledForTesting(false)
        DirectorySnapshotCache.setUltraLowRamMode(false)
        DirectorySnapshotCache.clear()
    }

    @Test
    fun testNormalVsUltraBudgetsEnforceStrictLowRamLimits() {
        val normal = PerformanceModeManager.budgetFor(false)
        val ultra = PerformanceModeManager.budgetFor(true)

        // Normal mode keeps all original defaults intact
        assertEquals(200, normal.nativeAppIconCacheEntries)
        assertEquals(0, normal.nativeAppIconTargetPx)
        assertEquals(12_000, normal.directorySnapshotMaxItems)
        assertEquals(28, normal.fileListViewCacheSize)
        assertEquals(48, normal.fileListPoolList)
        assertEquals(24, normal.fileListPoolGrid)
        assertEquals(5000, normal.terminalTranscriptRows)
        assertEquals(500_000, normal.editorMaxHighlightChars)
        assertEquals(200, normal.editorMaxUndoHistory)
        assertEquals(2500L, normal.runningAppsRefreshIntervalMs)
        assertTrue(normal.enableUiAnimations)
        assertTrue(normal.editorUseCustomFont)
        assertFalse(normal.forceVectorFileIcons)

        // Ultra mode cuts every cache, view pool, buffer, and heavy feature sharply
        assertEquals(1 * 1024 * 1024, ultra.coilMemoryCacheBytes)
        assertFalse(ultra.coilUseDiskCache)
        assertEquals(16, ultra.nativeAppIconCacheEntries)
        assertEquals(48, ultra.nativeAppIconTargetPx)
        assertEquals(0, ultra.directorySnapshotMaxItems)
        assertEquals(2, ultra.fileListViewCacheSize)
        assertEquals(10, ultra.fileListPoolList)
        assertEquals(6, ultra.fileListPoolGrid)
        assertEquals(64, ultra.fileListDateCacheSize)
        assertEquals(96, ultra.fileListDescriptionCacheSize)
        assertEquals(2, ultra.appListViewCacheSize)
        assertEquals(8, ultra.appListPoolSize)
        assertEquals(6000L, ultra.runningAppsRefreshIntervalMs)
        assertEquals(2, ultra.settingsViewCacheSize)
        assertEquals(500, ultra.terminalTranscriptRows)
        assertEquals(64_000, ultra.editorMaxHighlightChars)
        assertEquals(48_000, ultra.editorLiveHighlightChars)
        assertEquals(16, ultra.editorMaxUndoHistory)
        assertFalse(ultra.editorUseCustomFont)
        assertFalse(ultra.enableUiAnimations)
        assertTrue(ultra.forceVectorFileIcons)
    }

    @Test
    fun testDirectorySnapshotCacheEvictsAndSkipsInUltraLowRamMode() {
        val collator = Collator.getInstance()
        val path = SimpleTestPath("/storage/emulated/0/Download")
        val itemPath = SimpleTestPath("/storage/emulated/0/Download/sample.txt")
        val item = FileItem(
            path = itemPath,
            nameCollationKey = collator.getCollationKey("sample.txt"),
            attributesNoFollowLinks = FallbackBasicFileAttributes(
                lastModifiedMillis = 1_700_000_000_000L,
                fileType = BasicFileType.REGULAR_FILE,
                fileSize = 128L,
                key = itemPath.toString().toByteString()
            ),
            symbolicLinkTarget = null,
            symbolicLinkTargetAttributes = null,
            isHidden = false,
            mimeType = MimeType("text/plain")
        )

        DirectorySnapshotCache.setUltraLowRamMode(false)
        DirectorySnapshotCache.put(path, listOf(item))
        assertEquals(1, DirectorySnapshotCache.get(path)?.size)

        // Enabling ultra mode immediately evicts cached snapshots and refuses new inserts
        DirectorySnapshotCache.setUltraLowRamMode(true)
        assertNull(DirectorySnapshotCache.get(path))
        DirectorySnapshotCache.put(path, listOf(item))
        assertNull(DirectorySnapshotCache.get(path))

        // Restoring normal mode re-enables snapshot caching seamlessly
        DirectorySnapshotCache.setUltraLowRamMode(false)
        DirectorySnapshotCache.put(path, listOf(item))
        assertNotNull(DirectorySnapshotCache.get(path))
    }

    @Test
    fun testFastSyntaxScannerHonorsUltraHighlightCap() {
        // Build a ~75,000 char Kotlin snippet (> 64,000 ultra cap, < 500,000 normal cap)
        val unit = "val count = 42\n"
        val bigSnippet = unit.repeat(5_000)
        assertTrue(bigSnippet.length > PerformanceModeManager.ULTRA_BUDGET.editorMaxHighlightChars)
        assertTrue(bigSnippet.length < PerformanceModeManager.NORMAL_BUDGET.editorMaxHighlightChars)

        PerformanceModeManager.setEnabledForTesting(false)
        assertTrue(FastSyntaxScanner.isWithinHighlightLimit(bigSnippet.length, isLive = false))
        val normalTokens = FastSyntaxScanner.scan(bigSnippet, SyntaxLanguage.KOTLIN, EditorTheme.ONE_DARK)
        assertTrue("Normal mode must highlight 75K snippet", normalTokens.isNotEmpty())

        PerformanceModeManager.setEnabledForTesting(true)
        assertFalse(FastSyntaxScanner.isWithinHighlightLimit(bigSnippet.length, isLive = false))
        val ultraTokens = FastSyntaxScanner.scan(bigSnippet, SyntaxLanguage.KOTLIN, EditorTheme.ONE_DARK)
        assertTrue("Ultra mode must skip >64K highlighting to save spans/RAM", ultraTokens.isEmpty())
    }

    @Test
    fun testFormatStatusBadgeFormatsNormalSmoothUltraAndUltraRam() {
        assertEquals(
            "Estándar",
            PerformanceModeManager.formatStatusBadge(
                isUltraPerf = false,
                thumbMode = "0",
                activeMb = 14.2f,
                standardLabel = "Estándar",
                smoothLabel = "⚡ Fluido",
                ultraLabel = "⚡ Ultra Rápido",
                turboFormat = "⚡ Ultra RAM · %1\$s"
            )
        )
        assertEquals(
            "⚡ Fluido",
            PerformanceModeManager.formatStatusBadge(
                isUltraPerf = false,
                thumbMode = "1",
                activeMb = 14.2f,
                standardLabel = "Estándar",
                smoothLabel = "⚡ Fluido",
                ultraLabel = "⚡ Ultra Rápido",
                turboFormat = "⚡ Ultra RAM · %1\$s"
            )
        )
        assertEquals(
            "⚡ Ultra Rápido",
            PerformanceModeManager.formatStatusBadge(
                isUltraPerf = false,
                thumbMode = "2",
                activeMb = 14.2f,
                standardLabel = "Estándar",
                smoothLabel = "⚡ Fluido",
                ultraLabel = "⚡ Ultra Rápido",
                turboFormat = "⚡ Ultra RAM · %1\$s"
            )
        )
        assertEquals(
            "⚡ Ultra RAM · 12.4 MB",
            PerformanceModeManager.formatStatusBadge(
                isUltraPerf = true,
                thumbMode = "0",
                activeMb = 12.4f,
                standardLabel = "Estándar",
                smoothLabel = "⚡ Fluido",
                ultraLabel = "⚡ Ultra Rápido",
                turboFormat = "⚡ Ultra RAM · %1\$s"
            )
        )
    }

    @Test
    fun testLazyWebViewAndCoilArchitectureEliminatesStartupBloat() {
        val appInitSrc = resolveMainFile("java/me/zhanghai/android/files/app/AppInitializers.kt").readText()
        assertFalse(
            "AppInitializers must not eagerly call initializeWebViewDebugging at startup",
            appInitSrc.contains("::initializeWebViewDebugging")
        )
        val initListBlock = appInitSrc.substringAfter("val appInitializers = listOf(").substringBefore(")")
        val providersIdx = initListBlock.indexOf("::initializeFileSystemProviders")
        val perfModeIdx = initListBlock.indexOf("::initializePerformanceMode")
        val coilIdx = initListBlock.indexOf("::initializeCoil")
        assertTrue("FileSystemProviders must initialize before PerformanceMode (Settings.<clinit> needs Paths)", providersIdx in 0 until perfModeIdx)
        assertTrue("PerformanceMode must initialize before Coil", perfModeIdx in 0 until coilIdx)
        assertTrue(appInitSrc.contains("fun ensureWebViewDebuggingIfDebug()"))
        assertTrue(appInitSrc.contains("fun ensureSmbSingletonContextInitialized()"))
        assertFalse(
            "AppInitializers must use trimCoilMemory/clearCoilMemory instead of Coil.imageLoader(application)",
            appInitSrc.contains("Coil.imageLoader(application)")
        )

        val htmlViewerSrc = resolveMainFile("java/me/zhanghai/android/files/filelist/HtmlViewerDialogFragment.kt").readText()
        val docxViewerSrc = resolveMainFile("java/me/zhanghai/android/files/viewer/docx/DocxViewerActivity.kt").readText()
        val licensesSrc = resolveMainFile("java/me/zhanghai/android/files/ui/LicensesDialogExtensions.kt").readText()
        assertTrue(htmlViewerSrc.contains("ensureWebViewDebuggingIfDebug()"))
        assertTrue(docxViewerSrc.contains("ensureWebViewDebuggingIfDebug()"))
        assertTrue(licensesSrc.contains("ensureWebViewDebuggingIfDebug()"))

        val coilSrc = resolveMainFile("java/me/zhanghai/android/files/coil/CoilInitializer.kt").readText()
        assertTrue(coilSrc.contains("ImageLoaderFactory"))
        assertTrue(coilSrc.contains("CachePolicy.DISABLED"))
        assertTrue(coilSrc.contains("fun trimCoilMemory"))
        assertTrue(coilSrc.contains("fun clearCoilMemory"))

        val fragmentSrc = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListFragment.kt").readText()
        assertFalse(
            "Old buggy cache inflation (40 items in perf mode) must be removed",
            fragmentSrc.contains("setItemViewCacheSize(if (it) 40 else 28)")
        )
        assertTrue(fragmentSrc.contains("PerformanceModeManager.budgetFor"))

        val prefsXml = resolveMainFile("res/values/donottranslate_prefs.xml").readText()
        assertTrue(
            prefsXml.contains("<bool name=\"pref_default_value_visual_performance_mode\">false</bool>")
        )
    }

    @Test
    fun testPass2NativeHeapPurgeLazySmbAndPrefetchControlsWired() {
        val cmakeFile = File("CMakeLists.txt").let { if (it.exists()) it else File("app/CMakeLists.txt") }
        val cmakeSrc = cmakeFile.readText()
        assertTrue("syscall library must link dl for mallopt dlsym lookup", cmakeSrc.contains("dl)"))

        val syscallCSrc = resolveMainFile("jni/syscall.c").readText()
        assertTrue(syscallCSrc.contains("Java_me_zhanghai_android_files_provider_linux_syscall_Syscall_configureNativeHeapDecay"))
        assertTrue(syscallCSrc.contains("Java_me_zhanghai_android_files_provider_linux_syscall_Syscall_purgeNativeHeap"))
        assertTrue(syscallCSrc.contains("\"mallopt\""))
        assertTrue(syscallCSrc.contains("-100"))
        assertTrue(syscallCSrc.contains("-101"))

        val syscallKtSrc = resolveMainFile("java/me/zhanghai/android/files/provider/linux/syscall/Syscall.kt").readText()
        assertTrue(syscallKtSrc.contains("external fun configureNativeHeapDecay(immediate: Boolean): Boolean"))
        assertTrue(syscallKtSrc.contains("external fun purgeNativeHeap(): Boolean"))

        val smbClientSrc = resolveMainFile("java/me/zhanghai/android/files/provider/smb/client/Client.kt").readText()
        assertFalse("SMBClient must not be eagerly constructed at boot", smbClientSrc.contains("private val client = SMBClient()"))
        assertTrue("SMBClient must use lazy initialization", smbClientSrc.contains("private val client by lazy { SMBClient() }"))

        val fileListActSrc = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListActivity.kt").readText()
        assertTrue(fileListActSrc.contains("!PerformanceModeManager.isEnabled"))

        val fileListFragSrc = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListFragment.kt").readText()
        assertTrue(fileListFragSrc.contains("isItemPrefetchEnabled"))

        val snap = PerformanceModeManager.LiveHeapSnapshot(
            javaUsedBytes = 5L * 1024L * 1024L,
            nativeAllocatedBytes = 20L * 1024L * 1024L
        )
        assertEquals("5.0 MB", snap.formatJavaCompact())
        assertEquals("25.0 MB", snap.formatCompact())
    }
}

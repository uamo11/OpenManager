/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FallbackBasicFileAttributes
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.text.Collator

class FileListPerformanceOptimizationTest {

    private class TestPath(private val fullPath: String) : Path {
        override fun toString(): String = fullPath
        override fun equals(other: Any?): Boolean = other is TestPath && other.fullPath == fullPath
        override fun hashCode(): Int = fullPath.hashCode()
        override fun compareTo(other: Path): Int = fullPath.compareTo(other.toString())
        override fun toUri(): URI = URI.create("file://${fullPath.replace(" ", "%20")}")
        override fun getFileName(): Path? {
            if (fullPath == "/") return null
            val trimmed = fullPath.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            val name = if (idx >= 0) trimmed.substring(idx + 1) else trimmed
            return TestPath(name)
        }
        override fun getFileSystem(): FileSystem = throw UnsupportedOperationException()
        override fun isAbsolute(): Boolean = fullPath.startsWith("/")
        override fun getRoot(): Path? = if (isAbsolute) TestPath("/") else null
        override fun getParent(): Path? = null
        override fun getNameCount(): Int = 1
        override fun getName(index: Int): Path = this
        override fun subpath(beginIndex: Int, endIndex: Int): Path = this
        override fun startsWith(other: Path): Boolean = fullPath.startsWith(other.toString())
        override fun startsWith(other: String): Boolean = fullPath.startsWith(other)
        override fun endsWith(other: Path): Boolean = fullPath.endsWith(other.toString())
        override fun endsWith(other: String): Boolean = fullPath.endsWith(other)
        override fun normalize(): Path = this
        override fun resolve(other: Path): Path = resolve(other.toString())
        override fun resolve(other: String): Path =
            TestPath(if (fullPath.endsWith("/")) "$fullPath$other" else "$fullPath/$other")
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

    private val collator: Collator = Collator.getInstance()

    private fun makeItem(
        name: String,
        isDir: Boolean = false,
        size: Long = 0L,
        lastModMs: Long = 1_700_000_000_000L,
        isHidden: Boolean = name.startsWith("."),
        isParent: Boolean = false,
        mime: String = if (isDir) "vnd.android.document/directory" else "application/octet-stream"
    ): FileItem {
        val path = TestPath("/storage/emulated/0/OM_Perf/$name")
        val attrs = if (isParent) {
            DummyParentDirectoryBasicFileAttributes()
        } else {
            FallbackBasicFileAttributes(
                lastModifiedMillis = lastModMs,
                fileType = if (isDir) BasicFileType.DIRECTORY else BasicFileType.REGULAR_FILE,
                fileSize = size,
                key = path.toString().toByteString()
            )
        }
        return FileItem(
            path = path,
            nameCollationKey = collator.getCollationKeyForFileName(name),
            attributesNoFollowLinks = attrs,
            symbolicLinkTarget = null,
            symbolicLinkTargetAttributes = null,
            isHidden = isHidden,
            mimeType = MimeType(mime)
        )
    }

    private fun resolveMainFile(relPath: String): File {
        val f1 = File("src/main/$relPath")
        return if (f1.exists()) f1 else File("app/src/main/$relPath")
    }

    @Test
    fun testPrepareDisplayList5000ItemsSortingFilteringAndPositionMap() {
        val exts = arrayOf("txt", "jpg", "apk", "json", "sh", "zip")
        val raw = ArrayList<FileItem>(5000)
        for (i in 0 until 5000) {
            val isDir = i < 500
            val hidden = i % 25 == 0
            val prefix = when {
                hidden -> "."
                i % 40 == 0 -> "#"
                else -> ""
            }
            val name = if (isDir) {
                "${prefix}dir_${5000 - i}"
            } else {
                "${prefix}item_${5000 - i}.${exts[i % exts.size]}"
            }
            raw.add(
                makeItem(
                    name = name,
                    isDir = isDir,
                    size = if (isDir) 0L else (5000 - i).toLong() * 1024L,
                    lastModMs = 1_700_000_000_000L + i * 1000L,
                    isHidden = hidden
                )
            )
        }

        val parentItem = makeItem("..", isDir = true, isHidden = false, isParent = true)

        val startNs = System.nanoTime()
        val prepared = FileListEngine.prepareDisplayList(
            rawFiles = raw,
            showHidden = false,
            sortOptions = FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true),
            isSearching = false,
            parentItem = parentItem,
            previousList = null,
            computeDiff = false
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("5,000-item prepare took ${elapsedMs}ms (expected < 1500ms)", elapsedMs < 1500L)
        // 5000 total - 200 hidden (every 25th item) = 4800 regular items + 1 ".." parent
        assertEquals(4801, prepared.displayList.size)
        assertTrue(prepared.displayList[0].isParentDirectoryItem)
        assertEquals(480, prepared.directoryCount)
        assertEquals(4320, prepared.fileCount)
        // Regular directories precede unimportant '#' directories; all directories precede files
        assertFalse(prepared.displayList[1].name.startsWith("#"))
        assertTrue(prepared.displayList[1].attributes.isDirectory)
        assertFalse(prepared.displayList.last().attributes.isDirectory)

        // Position map maps every item cleanly to its sorted index
        for (idx in listOf(0, 1, 250, 480, 481, 4800)) {
            val item = prepared.displayList[idx]
            assertEquals(idx, prepared.positionMap[item.path])
        }

        // Hidden toggle includes hidden files and computes DESCENDING size cleanly with dirs still first
        val descSize = FileListEngine.prepareDisplayList(
            rawFiles = raw,
            showHidden = true,
            sortOptions = FileSortOptions(FileSortOptions.By.SIZE, FileSortOptions.Order.DESCENDING, true),
            isSearching = false,
            parentItem = parentItem,
            previousList = prepared.displayList,
            computeDiff = true
        )
        assertEquals(5001, descSize.displayList.size)
        assertTrue(descSize.displayList[0].isParentDirectoryItem)
        assertTrue(descSize.displayList[1].attributes.isDirectory)
        assertFalse(descSize.displayList[501].attributes.isDirectory)
        assertTrue(
            "Files must be sorted by DESCENDING size",
            descSize.displayList[501].attributes.size() >= descSize.displayList.last().attributes.size()
        )
        // Exceeds ASYNC_DIFF_MAX_COMBINED -> skips heavy full-list diff
        assertNull(descSize.diffResult)

        // Combined size <= 2000 computes non-null background DiffResult when items differ
        val smallA = raw.take(300)
        val smallB = raw.take(295)
        val prepA = FileListEngine.prepareDisplayList(
            smallA, true, FileSortOptions(FileSortOptions.By.TYPE, FileSortOptions.Order.ASCENDING, true),
            false, parentItem, null, false
        )
        val prepB = FileListEngine.prepareDisplayList(
            smallB, true, FileSortOptions(FileSortOptions.By.TYPE, FileSortOptions.Order.ASCENDING, true),
            false, parentItem, prepA.displayList, true
        )
        assertNotNull(prepB.diffResult)
        assertTrue(FileListEngine.isSameOrderedList(prepA.displayList, prepA.displayList.toList()))
        assertFalse(FileListEngine.isSameOrderedList(prepA.displayList, prepB.displayList))
    }

    @Test
    fun testFileIconHelperLookupAccuracyAndSpeed() {
        assertEquals(R.drawable.file_parent_directory_icon, FileIconHelper.getIconRes(makeItem("..", isDir = true, isParent = true)))
        assertEquals(R.drawable.file_directory_icon, FileIconHelper.getIconRes(makeItem("Download", isDir = true)))
        assertEquals(R.drawable.file_xml_mt_icon, FileIconHelper.getIconRes(makeItem("AndroidManifest.xml")))
        assertEquals(R.drawable.file_dex_icon, FileIconHelper.getIconRes(makeItem("classes2.DEX")))
        assertEquals(R.drawable.file_arsc_icon, FileIconHelper.getIconRes(makeItem("resources.arsc")))
        assertEquals(R.drawable.file_so_icon, FileIconHelper.getIconRes(makeItem("libimgui.so")))
        assertEquals(R.drawable.ic_exec_elf_generic, FileIconHelper.getIconRes(makeItem("tool.elf")))
        assertEquals(R.drawable.file_sh_icon, FileIconHelper.getIconRes(makeItem("run.sh")))
        assertEquals(R.drawable.file_apk_icon, FileIconHelper.getIconRes(makeItem("bundle.apks")))
        assertEquals(R.drawable.file_archive_icon, FileIconHelper.getIconRes(makeItem("archive.7z")))
        assertEquals(R.drawable.file_font_icon, FileIconHelper.getIconRes(makeItem("roboto.ttf")))
        assertEquals(R.drawable.file_audio_icon, FileIconHelper.getIconRes(makeItem("song.flac")))
        assertEquals(R.drawable.file_video_icon, FileIconHelper.getIconRes(makeItem("clip.mkv")))
        assertEquals(R.drawable.file_image_icon, FileIconHelper.getIconRes(makeItem("PHOTO.PNG")))
        assertEquals(R.drawable.file_text_icon, FileIconHelper.getIconRes(makeItem("notes.log")))
        assertEquals(R.drawable.file_code_icon, FileIconHelper.getIconRes(makeItem("Main.kt")))
        assertEquals(R.drawable.file_pdf_icon, FileIconHelper.getIconRes(makeItem("manual.pdf")))
        assertEquals(R.drawable.file_certificate_icon, FileIconHelper.getIconRes(makeItem("release.jks")))
        assertEquals(R.drawable.file_generic_icon, FileIconHelper.getIconRes(makeItem("binary.unknownbin")))
    }

    @Test
    fun testCompactSizeFormatterAndAdaptiveFlingHeuristic() {
        assertEquals("0 B", FileListEngine.formatCompactFileSize(-10L))
        assertEquals("512 B", FileListEngine.formatCompactFileSize(512L))
        assertEquals("1.0 KB", FileListEngine.formatCompactFileSize(1024L))
        assertEquals("1.5 KB", FileListEngine.formatCompactFileSize(1536L))
        assertEquals("5.00 MB", FileListEngine.formatCompactFileSize(5L * 1024L * 1024L))
        assertEquals("1.50 GB", FileListEngine.formatCompactFileSize(1610612736L))

        // Mode 0 (Auto/Full) enables fling deferral automatically for large lists (>= 100 items)
        assertFalse(FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode = 0, itemCount = 30))
        assertTrue(FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode = 0, itemCount = 100))
        assertTrue(FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode = 1, itemCount = 5))
        assertFalse(FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode = 2, itemCount = 5000))
    }

    @Test
    fun testDirectorySnapshotCacheEvictionAndSingleDirCap() {
        DirectorySnapshotCache.clear()
        try {
            val hugePath = TestPath("/storage/emulated/0/HugeDir")
            val hugeList = (0 until 4500).map { makeItem("f_$it.txt") }
            DirectorySnapshotCache.put(hugePath, hugeList)
            assertNull(
                "Single directory exceeding 4000 items must not bloat heap snapshot cache",
                DirectorySnapshotCache.get(hugePath)
            )

            // Inserting 4 directories of 3500 items each = 14000 items > 12000 limit triggers FIFO/LRU eviction of first
            for (d in 1..4) {
                val p = TestPath("/storage/emulated/0/Dir_$d")
                val list = (0 until 3500).map { makeItem("d${d}_$it.txt") }
                DirectorySnapshotCache.put(p, list)
            }
            assertNull(DirectorySnapshotCache.get(TestPath("/storage/emulated/0/Dir_1")))
            assertNotNull(DirectorySnapshotCache.get(TestPath("/storage/emulated/0/Dir_4")))
        } finally {
            DirectorySnapshotCache.clear()
        }
    }

    @Test
    fun testLargeDirectoryOptimizationWiring() {
        val metadataCache = resolveMainFile("java/me/zhanghai/android/files/filelist/AppMetadataCache.kt").readText()
        assertFalse("AppMetadataCache must be lock-free without @Synchronized", metadataCache.contains("@Synchronized"))
        assertTrue(metadataCache.contains("fun getCachedAppLabel("))
        assertTrue(metadataCache.contains("fun prefetchAppLabelAsync("))

        val liveData = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListLiveData.kt").readText()
        assertTrue(liveData.contains("ITEM_LOADER_EXECUTOR"))
        assertTrue(liveData.contains("PARALLEL_LOAD_THRESHOLD"))

        val adapter = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListAdapter.kt").readText()
        assertTrue(adapter.contains("holder.appliedStyleVersion == styleVersion"))
        assertTrue(adapter.contains("descriptionCache"))
        assertTrue(adapter.contains("dateFormatCache"))
        assertTrue(adapter.contains("size(thumbTargetPx, thumbTargetPx)"))
        assertTrue(adapter.contains("fun applyPreparedList("))

        val fragment = resolveMainFile("java/me/zhanghai/android/files/filelist/FileListFragment.kt").readText()
        assertTrue(fragment.contains("Dispatchers.Default"))
        assertTrue(fragment.contains("listPrepareJob?.cancel()"))
        assertTrue(fragment.contains("FileListEngine.countDirectoriesAndFiles"))
    }
}

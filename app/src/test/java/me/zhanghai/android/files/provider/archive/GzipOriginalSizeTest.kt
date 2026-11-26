/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import me.zhanghai.android.files.provider.archive.archiver.ArchiveReader
import org.junit.Assert.assertEquals
import org.junit.Test

class GzipOriginalSizeTest {

    @Test
    fun theTrailerSizeIsUsedAsIsBelowFourGib() {
        // User report: a 642.94 MB .sql inside a .gz showed 0 B.
        val original = 674_172_928L
        assertEquals(original, ArchiveReader.gzipOriginalSize(original, 120_000_000L))
    }

    @Test
    fun aFileOverFourGibIsLiftedPastItsCompressedSize() {
        val original = (1L shl 32) + 1_000_000L
        val isize = original and 0xFFFFFFFFL
        // Barely compressible data: the compressed file is almost as big as the original.
        assertEquals(original, ArchiveReader.gzipOriginalSize(isize, original - 10_000L))
    }

    @Test
    fun anEmptyFileStaysEmpty() {
        assertEquals(0L, ArchiveReader.gzipOriginalSize(0L, 20L))
    }
}

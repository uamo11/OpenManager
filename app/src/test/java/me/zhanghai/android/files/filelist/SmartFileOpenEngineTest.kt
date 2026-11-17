/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFileOpenEngineTest {

    @Test
    fun classifySmartOpenDetectsPdfByMimeExtensionAndMagic() {
        assertEquals(
            SmartOpenKind.PDF,
            SmartFileOpenEngine.classifySmartOpen("application/pdf", "document", null)
        )
        assertEquals(
            SmartOpenKind.PDF,
            SmartFileOpenEngine.classifySmartOpen("application/x-pdf", "archivo", null)
        )
        assertEquals(
            SmartOpenKind.PDF,
            SmartFileOpenEngine.classifySmartOpen("application/octet-stream", "FACTURA.PDF", null)
        )
        val pdfMagic = "%PDF-1.7\n1 0 obj".toByteArray(Charsets.US_ASCII)
        assertEquals(
            SmartOpenKind.PDF,
            SmartFileOpenEngine.classifySmartOpen("application/octet-stream", "attachment_sin_ext", pdfMagic)
        )
        val bomPrefixedPdf = byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(),
            '%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte()
        )
        assertTrue(SmartFileOpenEngine.hasPdfMagicHeader(bomPrefixedPdf))
        assertFalse(SmartFileOpenEngine.hasPdfMagicHeader("PK\u0003\u0004zipcontent".toByteArray()))
    }

    @Test
    fun classifySmartOpenDetectsVideoAndRoutesOtherFilesToLocation() {
        assertEquals(
            SmartOpenKind.VIDEO,
            SmartFileOpenEngine.classifySmartOpen("video/mp4", "clip", null)
        )
        assertEquals(
            SmartOpenKind.VIDEO,
            SmartFileOpenEngine.classifySmartOpen("application/octet-stream", "movie.MKV", null)
        )
        assertEquals(
            SmartOpenKind.VIDEO,
            SmartFileOpenEngine.classifySmartOpen(null, "clip.webm", null)
        )
        assertEquals(
            SmartOpenKind.VIDEO,
            SmartFileOpenEngine.classifySmartOpen(null, "clip.mov", null)
        )

        // Archives & arbitrary files must open parent location rather than entering zip as folder
        assertEquals(
            SmartOpenKind.FILE_LOCATION,
            SmartFileOpenEngine.classifySmartOpen("application/zip", "backup.zip", null)
        )
        assertEquals(
            SmartOpenKind.FILE_LOCATION,
            SmartFileOpenEngine.classifySmartOpen("application/x-7z-compressed", "pack.7z", null)
        )
        assertEquals(
            SmartOpenKind.FILE_LOCATION,
            SmartFileOpenEngine.classifySmartOpen("application/vnd.android.package-archive", "app.apk", null)
        )
        assertEquals(
            SmartOpenKind.FILE_LOCATION,
            SmartFileOpenEngine.classifySmartOpen("text/plain", "notes.txt", null)
        )
        assertEquals(
            SmartOpenKind.FILE_LOCATION,
            SmartFileOpenEngine.classifySmartOpen("application/octet-stream", "firmware.bin", null)
        )
        assertEquals(
            SmartOpenKind.DIRECTORY,
            SmartFileOpenEngine.classifySmartOpen("inode/directory", "Download", null, isDirectory = true)
        )
    }

    @Test
    fun normalizeKernelOrProviderPathRewritesWeirdAndroidMounts() {
        assertEquals(
            "/storage/emulated/0/Download/a.zip",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/mnt/user/0/emulated/0/Download/a.zip")
        )
        assertEquals(
            "/storage/emulated/0/Movies/v.mp4",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/mnt/pass_through/0/emulated/0/Movies/v.mp4")
        )
        assertEquals(
            "/storage/emulated/0/Doc.pdf",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/mnt/runtime/write/emulated/0/Doc.pdf")
        )
        assertEquals(
            "/storage/emulated/0/Android/media/com.whatsapp/f.zip",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/data/media/0/Android/media/com.whatsapp/f.zip")
        )
        assertEquals(
            "/storage/emulated/0/Download/x.zip",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/sdcard/Download/x.zip")
        )
        assertEquals(
            "/storage/ABCD-1234/clip.mp4",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/mnt/media_rw/ABCD-1234/clip.mp4")
        )
        assertEquals(
            "/storage/ABCD-1234/a.bin",
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/mnt/runtime/full/ABCD-1234/a.bin")
        )
        assertNull(
            SmartFileOpenEngine.normalizeKernelOrProviderPath("/data/user/0/app/cache/temp.zip (deleted)")
        )
        assertNull(SmartFileOpenEngine.normalizeKernelOrProviderPath("pipe:[123456]"))
    }

    @Test
    fun decodeSafAndDownloadsDocumentIds() {
        assertEquals(
            "/storage/emulated/0/Download/test.zip",
            SmartFileOpenEngine.decodeExternalStorageDocumentId("primary:Download/test.zip")
        )
        assertEquals(
            "/storage/emulated/0",
            SmartFileOpenEngine.decodeExternalStorageDocumentId("primary")
        )
        assertEquals(
            "/storage/emulated/0/Documents/doc.pdf",
            SmartFileOpenEngine.decodeExternalStorageDocumentId("home:doc.pdf")
        )
        assertEquals(
            "/storage/1234-5678/Movies/v.mp4",
            SmartFileOpenEngine.decodeExternalStorageDocumentId("1234-5678:Movies/v.mp4")
        )
        assertEquals(
            "/storage/emulated/0/Download/x.zip",
            SmartFileOpenEngine.decodeDownloadsDocumentId("raw:/sdcard/Download/x.zip")
        )
        assertNull(SmartFileOpenEngine.decodeDownloadsDocumentId("msf:45921"))
    }

    @Test
    fun extractEmbeddedCandidatesSanitizeAndStageNames() {
        val fpCandidates = SmartFileOpenEngine.extractEmbeddedStorageCandidates(
            "/external_files/Download/a.zip",
            "a.zip"
        )
        assertTrue(fpCandidates.contains("/storage/emulated/0/Download/a.zip"))

        val rootCandidates = SmartFileOpenEngine.extractEmbeddedStorageCandidates(
            "/root/storage/emulated/0/Android/data/pkg/files/a.zip",
            "a.zip"
        )
        assertTrue(rootCandidates.contains("/storage/emulated/0/Android/data/pkg/files/a.zip"))

        val androidCandidates = SmartFileOpenEngine.extractEmbeddedStorageCandidates(
            "/media/Android/data/org.telegram.messenger/files/Telegram/a.zip",
            "a.zip"
        )
        assertTrue(
            androidCandidates.contains("/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/a.zip")
        )

        assertEquals(
            "backup.zip",
            SmartFileOpenEngine.sanitizeDisplayName("../../evil/backup.zip")
        )
        assertEquals("fallback.zip", SmartFileOpenEngine.sanitizeDisplayName("..", "fallback.zip"))

        val existing = mapOf(
            "a.zip" to 1024L,
            "a (1).zip" to 2048L
        )
        assertEquals(
            "fresh.zip" to false,
            SmartFileOpenEngine.resolveUniqueStageFileName(existing, "fresh.zip", 500L)
        )
        assertEquals(
            "a.zip" to true,
            SmartFileOpenEngine.resolveUniqueStageFileName(existing, "a.zip", 1024L)
        )
        assertEquals(
            "a (2).zip" to false,
            SmartFileOpenEngine.resolveUniqueStageFileName(existing, "a.zip", 4096L)
        )
    }
}

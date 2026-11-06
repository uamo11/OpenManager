/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import me.zhanghai.android.files.provider.archive.archiver.ArchiveSecurityPreferences
import me.zhanghai.android.files.provider.archive.archiver.ArchiveSecuritySanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LibarchiveSecurityTest {

    @Before
    fun setUp() {
        ArchiveSecurityPreferences.resetStats()
    }

    @Test
    fun testCve2026_15028_sanitizeBuffer_neutralizes_sun_holesdata() {
        // Construct a synthetic TAR PAX extended header block containing the CVE-2026-15028 attack vector
        val paxHeader = "30 path=test_file.txt\n45 SUN.holesdata=0,1024,\n20 comment=malicious\n"
        val buffer = paxHeader.toByteArray(StandardCharsets.US_ASCII)
        val originalSize = buffer.size

        val sanitizedCount = ArchiveSecuritySanitizer.sanitizeBuffer(buffer, 0, buffer.size)

        assertEquals("Should neutralize exactly 1 occurrence of SUN.holesdata", 1, sanitizedCount)
        assertEquals("Buffer length must remain identical", originalSize, buffer.size)

        val resultString = String(buffer, StandardCharsets.US_ASCII)
        assertFalse("Buffer must no longer contain SUN.holesdata", resultString.contains("SUN.holesdata"))
        assertTrue("Buffer must now contain neutral SUN.xolesdata", resultString.contains("SUN.xolesdata"))
        assertTrue("Rest of the PAX header must remain completely intact", resultString.contains("path=test_file.txt"))
        assertEquals(1, ArchiveSecurityPreferences.getCve2026_15028Count())
    }

    @Test
    fun testCve2026_15028_sanitizeBuffer_multiple_occurrences() {
        val paxHeader = "45 SUN.holesdata=0,1024\n45 SUN.holesdata=\n"
        val buffer = paxHeader.toByteArray(StandardCharsets.US_ASCII)

        val sanitizedCount = ArchiveSecuritySanitizer.sanitizeBuffer(buffer, 0, buffer.size)

        assertEquals("Should neutralize all occurrences", 2, sanitizedCount)
        val resultString = String(buffer, StandardCharsets.US_ASCII)
        assertFalse(resultString.contains("SUN.holesdata"))
        assertEquals(2, ArchiveSecurityPreferences.getCve2026_15028Count())
    }

    @Test
    fun testCve2026_15028_sanitizeBuffer_clean_tar_untouched() {
        val normalTarData = "ustar  \u0000root\u0000root\u00000000644\u00000001750\u00000001750\u000000000000000\u0000"
        val buffer = normalTarData.toByteArray(StandardCharsets.US_ASCII)
        val copy = buffer.clone()

        val sanitizedCount = ArchiveSecuritySanitizer.sanitizeBuffer(buffer, 0, buffer.size)

        assertEquals("Clean tar data must not be modified", 0, sanitizedCount)
        assertTrue("Buffer contents must remain identical", buffer.contentEquals(copy))
    }

    @Test
    fun testCve2026_15028_sanitizeDirectBuffer() {
        val paxContent = "50 SUN.holesdata=0,1024,2048,1024\n"
        val bytes = paxContent.toByteArray(StandardCharsets.US_ASCII)
        val directBuffer = ByteBuffer.allocateDirect(bytes.size)
        directBuffer.put(bytes)
        directBuffer.flip()

        val count = ArchiveSecuritySanitizer.sanitizeDirectBuffer(directBuffer)

        assertEquals(1, count)
        val outputBytes = ByteArray(bytes.size)
        directBuffer.get(outputBytes)
        val outputStr = String(outputBytes, StandardCharsets.US_ASCII)
        assertFalse(outputStr.contains("SUN.holesdata"))
        assertTrue(outputStr.contains("SUN.xolesdata"))
    }

    @Test
    fun testCve2026_14164_rar5_signature_detection() {
        // RAR5 magic signature: 52 61 72 21 1A 07 01 00
        val rar5Header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        assertTrue("Must detect RAR5 header", ArchiveSecuritySanitizer.isRar5Header(rar5Header))

        // RAR4 legacy signature: 52 61 72 21 1A 07 00
        val rar4Header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0x00)
        assertFalse("RAR4 header must not be detected as RAR5", ArchiveSecuritySanitizer.isRar5Header(rar4Header))

        // Standard ZIP header: 50 4B 03 04
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        assertFalse("ZIP header must not be detected as RAR5", ArchiveSecuritySanitizer.isRar5Header(zipHeader))
    }

    @Test
    fun testMalformedSunHolesdataValidation() {
        assertTrue("Empty value is malformed", ArchiveSecuritySanitizer.isMalformedSunHolesdata(""))
        assertTrue("Trailing comma is malformed (CVE-2026-15028 trigger)", ArchiveSecuritySanitizer.isMalformedSunHolesdata("0,1024,"))
        assertTrue("Odd number of numbers is malformed", ArchiveSecuritySanitizer.isMalformedSunHolesdata("0,1024,2048"))
        assertTrue("Non-numeric characters are malformed", ArchiveSecuritySanitizer.isMalformedSunHolesdata("0,abc"))

        assertFalse("Valid pair is not malformed", ArchiveSecuritySanitizer.isMalformedSunHolesdata("0,1024"))
        assertFalse("Multiple valid pairs are not malformed", ArchiveSecuritySanitizer.isMalformedSunHolesdata("0,1024,2048,1024"))
    }

    @Test
    fun testCve2026_15028_streamSanitizer_handles_chunk_boundary_splits() {
        // Test all possible split positions across chunk boundary from k = 1 to 12
        for (k in 1..12) {
            val sanitizer = ArchiveSecuritySanitizer.StreamSanitizer()
            val chunk1Prefix = "30 path=test.txt\n45 "
            val chunk2Suffix = "=0,1024,\n20 comment=malicious\n"

            val split1 = ArchiveSecuritySanitizer.SUN_HOLESDATA_BYTES.copyOfRange(0, k)
            val split2 = ArchiveSecuritySanitizer.SUN_HOLESDATA_BYTES.copyOfRange(k, ArchiveSecuritySanitizer.SUN_HOLESDATA_BYTES.size)

            val chunk1 = (chunk1Prefix.toByteArray(StandardCharsets.US_ASCII) + split1)
            val chunk2 = (split2 + chunk2Suffix.toByteArray(StandardCharsets.US_ASCII))

            val count1 = sanitizer.sanitizeChunk(chunk1, 0, chunk1.size)
            val count2 = sanitizer.sanitizeChunk(chunk2, 0, chunk2.size)

            val reassembled = String(chunk1 + chunk2, StandardCharsets.US_ASCII)
            assertFalse(
                "Reassembled stream for split k=$k must NEVER contain SUN.holesdata",
                reassembled.contains("SUN.holesdata")
            )
            assertTrue(
                "Total sanitized count for split k=$k must be at least 1",
                (count1 + count2) >= 1
            )
        }
    }

    @Test
    fun testCve2026_15028_streamSanitizer_direct_chunk_boundary_split() {
        val sanitizer = ArchiveSecuritySanitizer.StreamSanitizer()
        val split1 = ArchiveSecuritySanitizer.SUN_HOLESDATA_BYTES.copyOfRange(0, 5) // "SUN.h"
        val split2 = ArchiveSecuritySanitizer.SUN_HOLESDATA_BYTES.copyOfRange(5, 13) // "olesdata"

        val buf1 = ByteBuffer.allocateDirect(split1.size)
        buf1.put(split1)
        buf1.flip()
        sanitizer.sanitizeDirectChunk(buf1)

        val buf2 = ByteBuffer.allocateDirect(split2.size + 10)
        buf2.put(split2)
        buf2.put("=0,1024,\n".toByteArray(StandardCharsets.US_ASCII))
        buf2.flip()
        sanitizer.sanitizeDirectChunk(buf2)

        val out1 = ByteArray(buf1.remaining()).also { buf1.get(it) }
        val out2 = ByteArray(buf2.remaining()).also { buf2.get(it) }
        val reassembled = String(out1 + out2, StandardCharsets.US_ASCII)
        assertFalse(
            "Direct buffer reassembled stream must not contain SUN.holesdata",
            reassembled.contains("SUN.holesdata")
        )
    }

    @Test
    fun testCve2026_14164_valid_rar5_header_passes_validation() {
        val validArchive = createSyntheticRar5Archive()
        val result = ArchiveSecuritySanitizer.validateRar5(validArchive)
        assertTrue("Valid RAR5 header must pass validation", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Valid)
    }

    @Test
    fun testCve2026_14164_tampered_crc_rejected() {
        val tamperedArchive = createSyntheticRar5Archive(tamperCrc = true)
        val result = ArchiveSecuritySanitizer.validateRar5(tamperedArchive)
        assertTrue("Tampered CRC32 must be rejected", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt)
        val reason = (result as ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt).reason
        assertTrue(reason.contains("CRC32 mismatch"))
    }

    @Test
    fun testCve2026_14164_truncated_header_rejected() {
        val truncatedArchive = createSyntheticRar5Archive(truncateBytes = 2)
        val result = ArchiveSecuritySanitizer.validateRar5(truncatedArchive)
        assertTrue("Truncated archive header must be rejected", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt)
    }

    @Test
    fun testCve2026_14164_invalid_initial_block_type_rejected() {
        val invalidTypeArchive = createSyntheticRar5Archive(headerType = 2L)
        val result = ArchiveSecuritySanitizer.validateRar5(invalidTypeArchive)
        assertTrue("Invalid initial block type must be rejected", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt)
        val reason = (result as ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt).reason
        assertTrue(reason.contains("Invalid initial RAR5 header block type"))
    }

    @Test
    fun testCve2026_14164_corrupt_vint_exceeding_10_bytes_rejected() {
        val magic = ArchiveSecuritySanitizer.RAR5_SIGNATURE
        val fakeCrc = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val corruptVint = ByteArray(11) { 0x80.toByte() }
        val corruptPayload = magic + fakeCrc + corruptVint

        val result = ArchiveSecuritySanitizer.validateRar5(corruptPayload)
        assertTrue("VINT exceeding 10 bytes must be rejected", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt)
        val reason = (result as ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt).reason
        assertTrue(reason.contains("exceeds 10 bytes"))
    }

    @Test
    fun testCve2026_14164_oversized_header_rejected() {
        val magic = ArchiveSecuritySanitizer.RAR5_SIGNATURE
        val fakeCrc = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val oversizedVint = encodeVint(3_000_000L)
        val payload = magic + fakeCrc + oversizedVint

        val result = ArchiveSecuritySanitizer.validateRar5(payload)
        assertTrue("Oversized block header must be rejected", result is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt)
        val reason = (result as ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt).reason
        assertTrue(reason.contains("exceeds 2MB limit"))
    }

    @Test
    fun testCve2026_14164_non_rar5_returns_not_rar5() {
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        val result = ArchiveSecuritySanitizer.validateRar5(zipBytes)
        assertTrue("Non-RAR5 file must return NotRar5", result is ArchiveSecuritySanitizer.Rar5ValidationResult.NotRar5)
    }

    private fun createSyntheticRar5Archive(
        headerType: Long = 1L,
        headerFlags: Long = 0L,
        tamperCrc: Boolean = false,
        truncateBytes: Int = 0,
        customPayload: ByteArray? = null
    ): ByteArray {
        val magic = ArchiveSecuritySanitizer.RAR5_SIGNATURE

        val payload = customPayload ?: run {
            val typeBytes = encodeVint(headerType)
            val flagsBytes = encodeVint(headerFlags)
            val arcFlagsBytes = byteArrayOf(0x00)
            typeBytes + flagsBytes + arcFlagsBytes
        }

        val sizeVint = encodeVint(payload.size.toLong())

        val crc = java.util.zip.CRC32()
        crc.update(sizeVint)
        crc.update(payload)
        var crcVal = crc.value
        if (tamperCrc) {
            crcVal = crcVal xor 0xFFFFFFFFL
        }

        val crcBytes = byteArrayOf(
            (crcVal and 0xFF).toByte(),
            ((crcVal shr 8) and 0xFF).toByte(),
            ((crcVal shr 16) and 0xFF).toByte(),
            ((crcVal shr 24) and 0xFF).toByte()
        )

        val full = magic + crcBytes + sizeVint + payload
        return if (truncateBytes > 0) full.copyOfRange(0, full.size - truncateBytes) else full
    }

    private fun encodeVint(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) {
                b = b or 0x80
            }
            bytes.add(b.toByte())
            if (v == 0L) break
        }
        return bytes.toByteArray()
    }
}

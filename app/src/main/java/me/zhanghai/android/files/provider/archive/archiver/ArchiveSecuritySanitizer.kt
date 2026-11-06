/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

object ArchiveSecuritySanitizer {
    private const val TAG = "ArchiveSecurityGuard"

    // Target sequence: "SUN.holesdata" (13 bytes)
    // Vulnerability CVE-2026-15028: Malformed SUN.holesdata in tar PAX extended headers causes
    // libarchive's solaris_sparse_parse() to perform an out-of-bounds heap read and heap buffer overflow.
    val SUN_HOLESDATA_BYTES: ByteArray = "SUN.holesdata".toByteArray(StandardCharsets.US_ASCII)

    // Neutral replacement sequence: "SUN.xolesdata" (exactly 13 bytes)
    // Libarchive treats this as an unrecognized vendor PAX attribute, skipping it cleanly
    // without triggering solaris_sparse_parse(), while preserving exact byte length and alignment.
    val NEUTRAL_HOLESDATA_BYTES: ByteArray = "SUN.xolesdata".toByteArray(StandardCharsets.US_ASCII)

    // RAR5 Signature: 0x52 0x61 0x72 0x21 0x1A 0x07 0x01 0x00 ("Rar!\x1a\x07\x01\x00")
    // Vulnerability CVE-2026-14164: Double-free in filtered_buf cleanup during unpacking state reinit in init_unpack().
    val RAR5_SIGNATURE: ByteArray = byteArrayOf(
        0x52.toByte(), 0x61.toByte(), 0x72.toByte(), 0x21.toByte(),
        0x1A.toByte(), 0x07.toByte(), 0x01.toByte(), 0x00.toByte()
    )

    private fun logWarning(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {}
    }

    /**
     * Inspects and sanitizes a byte array buffer in-place to protect against CVE-2026-15028.
     * Replaces any occurrence of "SUN.holesdata" with "SUN.xolesdata" without changing buffer size.
     * @return The number of occurrences sanitized.
     */
    fun sanitizeBuffer(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!ArchiveSecurityPreferences.isTarPaxSanitizationEnabled()) {
            return 0
        }
        var sanitizedCount = 0
        val end = offset + length - SUN_HOLESDATA_BYTES.size
        var i = offset
        while (i <= end) {
            if (buffer[i] == SUN_HOLESDATA_BYTES[0]) {
                var matches = true
                for (j in 1 until SUN_HOLESDATA_BYTES.size) {
                    if (buffer[i + j] != SUN_HOLESDATA_BYTES[j]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    System.arraycopy(NEUTRAL_HOLESDATA_BYTES, 0, buffer, i, NEUTRAL_HOLESDATA_BYTES.size)
                    sanitizedCount++
                    ArchiveSecurityPreferences.recordCve2026_15028Blocked(1)
                    logWarning("[CVE-2026-15028] Neutralized SUN.holesdata attribute at offset $i in tar PAX header")
                    i += SUN_HOLESDATA_BYTES.size - 1
                }
            }
            i++
        }
        return sanitizedCount
    }

    /**
     * Inspects and sanitizes a direct or heap ByteBuffer in-place to protect against CVE-2026-15028.
     */
    fun sanitizeDirectBuffer(buffer: ByteBuffer): Int {
        if (!ArchiveSecurityPreferences.isTarPaxSanitizationEnabled()) {
            return 0
        }
        val pos = buffer.position()
        val limit = buffer.limit()
        val remaining = limit - pos
        if (remaining < SUN_HOLESDATA_BYTES.size) {
            return 0
        }

        var sanitizedCount = 0
        val end = limit - SUN_HOLESDATA_BYTES.size
        for (i in pos..end) {
            if (buffer.get(i) == SUN_HOLESDATA_BYTES[0]) {
                var matches = true
                for (j in 1 until SUN_HOLESDATA_BYTES.size) {
                    if (buffer.get(i + j) != SUN_HOLESDATA_BYTES[j]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    for (j in NEUTRAL_HOLESDATA_BYTES.indices) {
                        buffer.put(i + j, NEUTRAL_HOLESDATA_BYTES[j])
                    }
                    sanitizedCount++
                    ArchiveSecurityPreferences.recordCve2026_15028Blocked(1)
                    logWarning("[CVE-2026-15028] Neutralized SUN.holesdata attribute at direct buffer index $i")
                }
            }
        }
        return sanitizedCount
    }

    /**
     * Checks if the given byte prefix matches the RAR5 format header.
     */
    fun isRar5Header(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < RAR5_SIGNATURE.size) return false
        for (i in RAR5_SIGNATURE.indices) {
            if (headerBytes[i] != RAR5_SIGNATURE[i]) {
                return false
            }
        }
        return true
    }

    sealed class Rar5ValidationResult {
        object Valid : Rar5ValidationResult()
        object NotRar5 : Rar5ValidationResult()
        data class Corrupt(val reason: String) : Rar5ValidationResult()
    }

    /**
     * Pre-validates the structure and CRC32 checksum of a RAR5 archive stream before
     * passing it to native libarchive decompression routines.
     *
     * Prevents CVE-2026-14164 (Double-Free in filtered_buf during unpack state reinit)
     * by rejecting malformed, truncated, or tampered header blocks before native execution.
     */
    fun validateRar5(inputStream: InputStream): Rar5ValidationResult {
        // 1. Verify 8-byte RAR5 magic signature
        val magic = ByteArray(RAR5_SIGNATURE.size)
        val magicRead = readFully(inputStream, magic)
        if (magicRead < RAR5_SIGNATURE.size || !isRar5Header(magic)) {
            return Rar5ValidationResult.NotRar5
        }

        // 2. Read 4-byte Header CRC32 (little-endian unsigned 32-bit int)
        val crcBytes = ByteArray(4)
        val crcRead = readFully(inputStream, crcBytes)
        if (crcRead < 4) {
            return Rar5ValidationResult.Corrupt("Truncated RAR5 archive: missing header CRC32")
        }
        val storedCrc = (crcBytes[0].toLong() and 0xFF) or
                ((crcBytes[1].toLong() and 0xFF) shl 8) or
                ((crcBytes[2].toLong() and 0xFF) shl 16) or
                ((crcBytes[3].toLong() and 0xFF) shl 24)

        // 3. Read Header Size VINT (Variable length integer)
        val headerSizeVintRaw = mutableListOf<Byte>()
        var headerSize = 0L
        var shift = 0
        var vintLen = 0
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                return Rar5ValidationResult.Corrupt("Truncated RAR5 archive: unexpected EOF in header size VINT")
            }
            vintLen++
            if (vintLen > 10) {
                return Rar5ValidationResult.Corrupt("Malformed RAR5 header size VINT: exceeds 10 bytes")
            }
            headerSizeVintRaw.add(b.toByte())
            headerSize = headerSize or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) {
                break
            }
            shift += 7
        }

        if (headerSize <= 0) {
            return Rar5ValidationResult.Corrupt("Invalid RAR5 header size: $headerSize (must be positive)")
        }
        if (headerSize > 2 * 1024 * 1024) { // 2MB max block header limit per RAR5 specification
            return Rar5ValidationResult.Corrupt("Oversized RAR5 block header: $headerSize bytes exceeds 2MB limit")
        }

        // 4. Read Header Data bytes
        val headerData = ByteArray(headerSize.toInt())
        val dataRead = readFully(inputStream, headerData)
        if (dataRead < headerData.size) {
            return Rar5ValidationResult.Corrupt("Truncated RAR5 header data: expected ${headerData.size} bytes, got $dataRead")
        }

        // 5. Verify CRC32 checksum over [Header Size VINT bytes] + [Header Data bytes]
        val crc32 = CRC32()
        for (b in headerSizeVintRaw) {
            crc32.update(b.toInt() and 0xFF)
        }
        crc32.update(headerData, 0, headerData.size)
        val computedCrc = crc32.value
        if (storedCrc != computedCrc) {
            return Rar5ValidationResult.Corrupt(
                "RAR5 header CRC32 mismatch: expected 0x%08X, calculated 0x%08X".format(storedCrc, computedCrc)
            )
        }

        // 6. Inspect Header Type and Header Flags from header data
        var offset = 0
        fun readVintFromData(): Long {
            var res = 0L
            var s = 0
            var l = 0
            while (offset < headerData.size) {
                val b = headerData[offset++].toInt() and 0xFF
                l++
                if (l > 10) throw IllegalArgumentException("VINT in header data exceeds 10 bytes")
                res = res or ((b.toLong() and 0x7FL) shl s)
                if ((b and 0x80) == 0) return res
                s += 7
            }
            throw EOFException("Truncated VINT in header data")
        }

        val headerType = try {
            readVintFromData()
        } catch (e: Exception) {
            return Rar5ValidationResult.Corrupt("Failed to parse RAR5 header type: ${e.message}")
        }

        // First block in a valid RAR5 file must be HEAD_MAIN (1) or HEAD_CRYPT (4)
        if (headerType != 1L && headerType != 4L) {
            return Rar5ValidationResult.Corrupt(
                "Invalid initial RAR5 header block type: $headerType (expected 1 [MAIN] or 4 [CRYPT])"
            )
        }

        val headerFlags = try {
            readVintFromData()
        } catch (e: Exception) {
            return Rar5ValidationResult.Corrupt("Failed to parse RAR5 header flags: ${e.message}")
        }

        // RAR5 header flags: bits 0x0001 to 0x0040 are defined
        val knownFlagsMask = 0x007FL
        if ((headerFlags and knownFlagsMask.inv()) != 0L) {
            return Rar5ValidationResult.Corrupt(
                "Malformed RAR5 header flags: 0x%X contains invalid or reserved flag bits".format(headerFlags)
            )
        }

        // If extra area flag (0x0001) is set, validate extra area size
        if ((headerFlags and 0x0001L) != 0L) {
            val extraSize = try {
                readVintFromData()
            } catch (e: Exception) {
                return Rar5ValidationResult.Corrupt("Failed to parse RAR5 extra area size: ${e.message}")
            }
            if (extraSize < 0 || extraSize > (headerData.size - offset)) {
                return Rar5ValidationResult.Corrupt(
                    "Invalid RAR5 extra area size: $extraSize exceeds remaining header bytes (${headerData.size - offset})"
                )
            }
        }

        return Rar5ValidationResult.Valid
    }

    /**
     * Convenience method to validate RAR5 byte array.
     */
    fun validateRar5(bytes: ByteArray): Rar5ValidationResult =
        validateRar5(ByteArrayInputStream(bytes))

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    /**
     * Checks whether a SUN.holesdata value is malformed (e.g., empty, trailing comma, non-digit).
     */
    fun isMalformedSunHolesdata(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.endsWith(",")) return true
        val parts = trimmed.split(",")
        if (parts.size % 2 != 0) return true
        for (part in parts) {
            val p = part.trim()
            if (p.isEmpty() || p.any { !it.isDigit() }) {
                return true
            }
        }
        return false
    }

    /**
     * Stateful streaming sanitizer that neutralizes "SUN.holesdata" across chunk boundaries.
     * Prevents split-chunk evasion where "SUN.holesdata" straddles consecutive read callbacks.
     */
    class StreamSanitizer {
        private val window = ByteArray(SUN_HOLESDATA_BYTES.size - 1)
        private var windowLen = 0

        fun reset() {
            windowLen = 0
        }

        fun sanitizeChunk(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!ArchiveSecurityPreferences.isTarPaxSanitizationEnabled() || length <= 0) {
                return 0
            }
            var count = 0

            // 1. Check boundary straddle between previous chunk's trailing window and current chunk's start
            if (windowLen > 0) {
                val maxK = minOf(windowLen, SUN_HOLESDATA_BYTES.size - 1)
                for (k in maxK downTo 1) {
                    val remainingNeed = SUN_HOLESDATA_BYTES.size - k
                    if (length >= remainingNeed) {
                        var match = true
                        for (idx in 0 until k) {
                            if (window[windowLen - k + idx] != SUN_HOLESDATA_BYTES[idx]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            for (idx in 0 until remainingNeed) {
                                if (buffer[offset + idx] != SUN_HOLESDATA_BYTES[k + idx]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                // Boundary match confirmed!
                                for (idx in 0 until remainingNeed) {
                                    val origPos = k + idx
                                    if (origPos == 4) {
                                        buffer[offset + idx] = 'x'.code.toByte()
                                    } else if (k > 4 && idx == 0) {
                                        buffer[offset + idx] = 'x'.code.toByte()
                                    } else {
                                        buffer[offset + idx] = NEUTRAL_HOLESDATA_BYTES[origPos]
                                    }
                                }
                                count++
                                ArchiveSecurityPreferences.recordCve2026_15028Blocked(1)
                                logWarning("[CVE-2026-15028] Neutralized cross-boundary SUN.holesdata (split at $k/$remainingNeed bytes)")
                                break
                            }
                        }
                    }
                }
            }

            // 2. Sanitize internal occurrences within this chunk
            count += sanitizeBuffer(buffer, offset, length)

            // 3. Update window with trailing bytes
            val saveLen = minOf(length, window.size)
            System.arraycopy(buffer, offset + length - saveLen, window, 0, saveLen)
            windowLen = saveLen

            return count
        }

        fun sanitizeDirectChunk(buffer: ByteBuffer): Int {
            if (!ArchiveSecurityPreferences.isTarPaxSanitizationEnabled() || !buffer.hasRemaining()) {
                return 0
            }
            var count = 0
            val pos = buffer.position()
            val len = buffer.remaining()

            // 1. Check boundary straddle
            if (windowLen > 0) {
                val maxK = minOf(windowLen, SUN_HOLESDATA_BYTES.size - 1)
                for (k in maxK downTo 1) {
                    val remainingNeed = SUN_HOLESDATA_BYTES.size - k
                    if (len >= remainingNeed) {
                        var match = true
                        for (idx in 0 until k) {
                            if (window[windowLen - k + idx] != SUN_HOLESDATA_BYTES[idx]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            for (idx in 0 until remainingNeed) {
                                if (buffer.get(pos + idx) != SUN_HOLESDATA_BYTES[k + idx]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                for (idx in 0 until remainingNeed) {
                                    val origPos = k + idx
                                    if (origPos == 4) {
                                        buffer.put(pos + idx, 'x'.code.toByte())
                                    } else if (k > 4 && idx == 0) {
                                        buffer.put(pos + idx, 'x'.code.toByte())
                                    } else {
                                        buffer.put(pos + idx, NEUTRAL_HOLESDATA_BYTES[origPos])
                                    }
                                }
                                count++
                                ArchiveSecurityPreferences.recordCve2026_15028Blocked(1)
                                logWarning("[CVE-2026-15028] Neutralized cross-boundary SUN.holesdata in direct buffer (split at $k/$remainingNeed bytes)")
                                break
                            }
                        }
                    }
                }
            }

            // 2. Sanitize direct buffer internally
            count += sanitizeDirectBuffer(buffer)

            // 3. Save trailing bytes
            val saveLen = minOf(len, window.size)
            for (idx in 0 until saveLen) {
                window[idx] = buffer.get(pos + len - saveLen + idx)
            }
            windowLen = saveLen

            return count
        }
    }
}

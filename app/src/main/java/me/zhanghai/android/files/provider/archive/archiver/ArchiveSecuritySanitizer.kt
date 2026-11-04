/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
